package saien.magrathea.core

import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StoredEnvelopeContractTest {
    private val json = Json {
        classDiscriminator = "type"
        encodeDefaults = false
        ignoreUnknownKeys = true
    }
    private val sessionCodec = AgentSessionSnapshotCodec(json, sdkVersion = "test-sdk")
    private val checkpointCodec = AgentCheckpointCodec(json, sdkVersion = "test-sdk")

    @Test
    fun sessionCodec_roundTripsCurrentVersionedEnvelope() {
        val expected = snapshot()

        val encoded = sessionCodec.encode(expected)
        val envelope = json.parseToJsonElement(encoded).jsonObject

        assertEquals(STORAGE_SCHEMA_V7_VERSION, CURRENT_STORAGE_SCHEMA_VERSION)
        assertEquals(CURRENT_STORAGE_SCHEMA_VERSION, envelope.getValue("schemaVersion").jsonPrimitive.content.toInt())
        assertEquals("test-sdk", envelope.getValue("sdkVersion").jsonPrimitive.content)
        assertEquals(expected, sessionCodec.decode(encoded))
        val result = sessionCodec.decodeResult(encoded)
        assertEquals(expected, result.value)
        assertEquals(CURRENT_STORAGE_SCHEMA_VERSION, result.sourceSchemaVersion)
        assertNull(result.rewritePayload)
    }

    @Test
    fun sessionCodec_roundTripsModelOutputCapability() {
        val original = snapshot()
        val expected = original.copy(
            request = original.request.copy(
                model = original.request.model.copy(maxOutputTokens = 16_384),
            ),
        )

        assertEquals(expected, sessionCodec.decode(sessionCodec.encode(expected)))
    }

    @Test
    fun schemaV6MigrationAddsUnknownCapabilityToRequestAndCompactionModels() {
        val original = snapshot()
        val withCompaction = original.copy(
            state = original.state.copy(
                contextManagement = ContextManagementState(
                    compaction = ContextCompaction(
                        summary = "Earlier context",
                        firstKeptMessageId = original.state.messages.single().id,
                        summarizedThroughMessageId = "older-message",
                        sourcePrefixDigest = "prefix-digest",
                        tokensBefore = 100,
                        generation = 1,
                        summaryModel = original.request.model,
                        createdAtEpochMs = 1L,
                    ),
                ),
            ),
        )
        val current = sessionCodec.encode(withCompaction)
        val v6 = current
            .replaceFirst("\"schemaVersion\":7", "\"schemaVersion\":6")
            .replace(",\"maxOutputTokens\":null", "")

        assertEquals(2, current.split("\"maxOutputTokens\":null").size - 1)
        val migrated = sessionCodec.decodeResult(v6)

        assertEquals(6, migrated.sourceSchemaVersion)
        assertEquals(withCompaction, migrated.value)
        assertTrue(requireNotNull(migrated.rewritePayload).contains("\"maxOutputTokens\":null"))
    }

    @Test
    fun schemaV6MigrationRejectsAPrematureSchemaV7TargetField() {
        val mislabeled = sessionCodec.encode(snapshot())
            .replaceFirst("\"schemaVersion\":7", "\"schemaVersion\":6")

        val failure = assertFailsWith<StoredEnvelopeDecodeException> {
            sessionCodec.decode(mislabeled)
        }

        assertEquals(StoredEnvelopeDecodeFailure.MIGRATION_FAILED, failure.failure)
        assertEquals(6, failure.storedSchemaVersion)
    }

    @Test
    fun checkpointCodec_roundTripsCurrentVersionedEnvelope() {
        val snapshot = snapshot()
        val expected = checkpoint(snapshot, turn = 3)

        val encoded = checkpointCodec.encode(expected)
        val envelope = json.parseToJsonElement(encoded).jsonObject

        assertEquals(CURRENT_STORAGE_SCHEMA_VERSION, envelope.getValue("schemaVersion").jsonPrimitive.content.toInt())
        assertEquals("test-sdk", envelope.getValue("sdkVersion").jsonPrimitive.content)
        assertEquals(expected, checkpointCodec.decode(encoded))
        val result = checkpointCodec.decodeResult(encoded)
        assertEquals(expected, result.value)
        assertEquals(CURRENT_STORAGE_SCHEMA_VERSION, result.sourceSchemaVersion)
        assertNull(result.rewritePayload)
    }

    @Test
    fun codecsUseSdkOwnedWireSettingsAcrossCallerJsonConfigurations() {
        val callerJson = Json {
            classDiscriminator = "_caller_kind"
            encodeDefaults = false
            explicitNulls = false
            ignoreUnknownKeys = true
            isLenient = true
            coerceInputValues = true
        }
        val callerSessionCodec = AgentSessionSnapshotCodec(callerJson, sdkVersion = "caller-sdk")
        val callerCheckpointCodec = AgentCheckpointCodec(callerJson, sdkVersion = "caller-sdk")
        val fixedSessionCodec = AgentSessionSnapshotCodec(sdkVersion = "fixed-sdk")
        val fixedCheckpointCodec = AgentCheckpointCodec(sdkVersion = "fixed-sdk")
        val expectedSession = snapshot()
        val expectedCheckpoint = checkpoint(expectedSession, turn = 2)

        val callerSessionPayload = callerSessionCodec.encode(expectedSession)
        val callerCheckpointPayload = callerCheckpointCodec.encode(expectedCheckpoint)

        assertTrue("\"type\":\"text\"" in callerSessionPayload)
        assertFalse("_caller_kind" in callerSessionPayload)
        assertEquals(expectedSession, fixedSessionCodec.decode(callerSessionPayload))
        assertEquals(expectedCheckpoint, fixedCheckpointCodec.decode(callerCheckpointPayload))
        assertEquals(expectedSession, callerSessionCodec.decode(fixedSessionCodec.encode(expectedSession)))
        assertEquals(
            expectedCheckpoint,
            callerCheckpointCodec.decode(fixedCheckpointCodec.encode(expectedCheckpoint)),
        )
    }

    @Test
    fun checkpointCodec_roundTripsPendingProviderInvocation() {
        val snapshot = snapshot()
        val expected = AgentCheckpoint(
            sessionId = snapshot.sessionId,
            runId = snapshot.runId,
            cursor = AgentResumeCursor(
                turn = 1,
                phase = AgentResumePhase.MODEL_PENDING,
                provider = AgentProviderInvocationCursor(
                    nextPhysicalAttempt = 2,
                    pending = AgentPendingProviderInvocation(
                        requestId = "run-contract:turn-1:attempt-1",
                        purpose = ProviderRequestPurpose.CONTEXT_SUMMARY,
                        inputIdentity = "context-summary-input-1",
                    ),
                ),
            ),
            state = snapshot.state.copy(turn = 1),
        )

        assertEquals(expected, checkpointCodec.decode(checkpointCodec.encode(expected)))
    }

    @Test
    fun codecs_rejectPayloadsMissingAnyCanonicalCurrentSchemaField() {
        val session = sessionCodec.encode(snapshot())
        val missingUsage = session.replace(
            ",\"usage\":{\"inputTokens\":null,\"outputTokens\":null,\"reasoningTokens\":null}",
            "",
        )
        val missingTools = session.replace(",\"tools\":[]", "")

        assertTrue(missingUsage != session)
        assertTrue(missingTools != session)
        assertFailsWith<SerializationException> { sessionCodec.decode(missingUsage) }
        assertFailsWith<SerializationException> { sessionCodec.decode(missingTools) }

        val checkpoint = checkpoint(snapshot(), turn = 0)
        val encodedCheckpoint = checkpointCodec.encode(checkpoint)
        val missingRetryCount = encodedCheckpoint.replace(",\"retryCount\":0", "")
        val missingProviderCursor = encodedCheckpoint.replace(
            ",\"provider\":{\"nextPhysicalAttempt\":0,\"pending\":null}",
            "",
        )
        val negativeRetryCount = encodedCheckpoint.replace("\"retryCount\":0", "\"retryCount\":-1")
        val negativeProviderAttempt = encodedCheckpoint.replace(
            "\"nextPhysicalAttempt\":0",
            "\"nextPhysicalAttempt\":-1",
        )
        assertTrue(missingRetryCount != encodedCheckpoint)
        assertTrue(missingProviderCursor != encodedCheckpoint)
        assertTrue(negativeRetryCount != encodedCheckpoint)
        assertTrue(negativeProviderAttempt != encodedCheckpoint)
        assertFailsWith<SerializationException> { checkpointCodec.decode(missingRetryCount) }
        assertFailsWith<SerializationException> { checkpointCodec.decode(missingProviderCursor) }
        assertFailsWith<SerializationException> { checkpointCodec.decode(negativeRetryCount) }
        assertFailsWith<SerializationException> { checkpointCodec.decode(negativeProviderAttempt) }
    }

    @Test
    fun sessionCodec_alwaysPersistsGeneratedIdentityAndTimestamps() {
        val message = AgentMessage(role = MessageRole.USER, parts = listOf(TextPart("hello")))
        val request = AgentRequest(
            messages = listOf(message),
            model = ModelDescriptor(provider = "test", model = "test"),
        )
        val snapshot = AgentSessionSnapshot(
            sessionId = request.sessionId,
            runId = AgentRunId("run-generated"),
            request = request,
            state = AgentStateSnapshot(messages = request.messages),
        )

        val encoded = sessionCodec.encode(snapshot)
        val payload = json.parseToJsonElement(encoded).jsonObject.getValue("payload").jsonObject
        val requestPayload = payload.getValue("request").jsonObject
        val messagePayload = requestPayload.getValue("messages").jsonArray.single().jsonObject

        assertTrue("updatedAtEpochMs" in payload)
        assertTrue("sessionId" in requestPayload)
        assertTrue("id" in messagePayload)
        assertTrue("createdAtEpochMs" in messagePayload)
        assertEquals(snapshot, sessionCodec.decode(encoded))
    }

    @Test
    fun sessionCodec_rejectsRawSnapshotWithoutEnvelope() {
        val rawSnapshot = json.encodeToString(AgentSessionSnapshot.serializer(), snapshot())

        assertFailsWith<SerializationException> {
            sessionCodec.decode(rawSnapshot)
        }
    }

    @Test
    fun sessionCodec_rejectsUnsupportedSchemaVersion() {
        val encoded = sessionCodec.encode(snapshot())
        val prior = encoded.replaceFirst(
            "\"schemaVersion\":$CURRENT_STORAGE_SCHEMA_VERSION",
            "\"schemaVersion\":${MINIMUM_READABLE_STORAGE_SCHEMA_VERSION - 1}",
        )
        val future = encoded
            .replaceFirst(
                "\"schemaVersion\":$CURRENT_STORAGE_SCHEMA_VERSION",
                "\"schemaVersion\":${CURRENT_STORAGE_SCHEMA_VERSION + 1}",
            )

        val priorFailure = assertFailsWith<StoredEnvelopeDecodeException> {
            sessionCodec.decode(prior)
        }
        assertEquals(
            StoredEnvelopeDecodeFailure.UNSUPPORTED_OLDER_SCHEMA,
            priorFailure.failure,
        )
        assertEquals(MINIMUM_READABLE_STORAGE_SCHEMA_VERSION - 1, priorFailure.storedSchemaVersion)

        val futureFailure = assertFailsWith<StoredEnvelopeDecodeException> {
            sessionCodec.decode(future)
        }
        assertEquals(
            StoredEnvelopeDecodeFailure.UNSUPPORTED_NEWER_SCHEMA,
            futureFailure.failure,
        )
        assertEquals(CURRENT_STORAGE_SCHEMA_VERSION + 1, futureFailure.storedSchemaVersion)
    }

    @Test
    fun sessionCodec_classifiesMissingOrMalformedSchemaAsCorruptBeforeCurrentDecode() {
        val encoded = sessionCodec.encode(snapshot())
        listOf(
            encoded.replaceFirst("\"schemaVersion\":$CURRENT_STORAGE_SCHEMA_VERSION,", ""),
            encoded.replaceFirst(
                "\"schemaVersion\":$CURRENT_STORAGE_SCHEMA_VERSION",
                "\"schemaVersion\":\"$CURRENT_STORAGE_SCHEMA_VERSION\"",
            ),
            encoded.replaceFirst(
                "\"schemaVersion\":$CURRENT_STORAGE_SCHEMA_VERSION",
                "\"schemaVersion\":0",
            ),
        ).forEach { malformed ->
            val failure = assertFailsWith<StoredEnvelopeDecodeException> {
                sessionCodec.decode(malformed)
            }
            assertEquals(StoredEnvelopeDecodeFailure.CORRUPT, failure.failure)
            assertEquals(CURRENT_STORAGE_SCHEMA_VERSION, failure.currentSchemaVersion)
        }
    }

    @Test
    fun publicDecodeFailureNeverExposesStoredPayloadOrDecoderCause() {
        val secret = "session-prompt-canary-never-log"
        val failure = assertFailsWith<StoredEnvelopeDecodeException> {
            sessionCodec.decode(
                """{"schemaVersion":$CURRENT_STORAGE_SCHEMA_VERSION,"secret":"$secret"}""",
            )
        }

        assertEquals(StoredEnvelopeDecodeFailure.CORRUPT, failure.failure)
        assertFalse(failure.toString().contains(secret))
        assertNull(failure.cause)
    }

    @Test
    fun sessionCodec_roundTripsTypedToolResultContent() {
        val original = snapshot()
        val toolMessage = AgentMessage(
            id = "tool-message",
            role = MessageRole.TOOL,
            parts = listOf(
                ToolResultPart(
                    toolCallId = "image-call",
                    toolName = "image_search",
                    result = JsonPrimitive("result"),
                    modelResultVisible = false,
                    content = listOf(
                        ToolResultImageContent(
                            source = RemoteToolImageSource("https://cdn.example.com/image.png"),
                            previewSource = RemoteToolImageSource(
                                "https://cdn.example.com/preview.png",
                            ),
                            mimeType = "image/png",
                            attribution = ToolMediaAttribution(
                                "Example",
                                "https://example.com/article",
                            ),
                            audiences = setOf(ToolResultAudience.USER),
                        ),
                    ),
                ),
            ),
            createdAtEpochMs = 2L,
        )
        val expected = original.copy(
            request = original.request.copy(messages = original.request.messages + toolMessage),
            state = original.state.copy(messages = original.state.messages + toolMessage),
        )

        assertEquals(expected, sessionCodec.decode(sessionCodec.encode(expected)))
    }

    @Test
    fun sessionCodec_roundTripsTypedProviderInterruption() {
        val original = snapshot()
        val expected = original.copy(
            state = original.state.copy(
                status = AgentStatus.INTERRUPTED,
                stopReason = StopReason.INTERRUPTED,
            ),
            interruption = AgentInterruption(
                reason = AgentInterruptionReason.PROVIDER_FAILURE,
                provider = ProviderInterruption(
                    code = AgentFailureCode.PROVIDER_NETWORK,
                    phase = ProviderInterruptionPhase.AFTER_FIRST_EVENT,
                    retryAtEpochMs = 5_000L,
                ),
                occurredAtEpochMs = 2_500L,
            ),
        )

        assertEquals(expected, sessionCodec.decode(sessionCodec.encode(expected)))
    }

    @Test
    fun sessionCodec_rejectsInconsistentProviderInterruptionMetadata() {
        val original = snapshot()
        val providerInterruption = original.copy(
            state = original.state.copy(
                status = AgentStatus.INTERRUPTED,
                stopReason = StopReason.INTERRUPTED,
            ),
            interruption = AgentInterruption(
                reason = AgentInterruptionReason.PROVIDER_FAILURE,
                provider = ProviderInterruption(
                    code = AgentFailureCode.PROVIDER_NETWORK,
                    phase = ProviderInterruptionPhase.BEFORE_FIRST_EVENT,
                    retryAtEpochMs = 5_000L,
                ),
                occurredAtEpochMs = 2_500L,
            ),
        )
        val encodedProvider = sessionCodec.encode(providerInterruption)

        assertFailsWith<SerializationException> {
            sessionCodec.decode(
                encodedProvider.replaceFirst(
                    "\"reason\":\"PROVIDER_FAILURE\"",
                    "\"reason\":\"HOST_REQUESTED\"",
                ),
            )
        }
        assertFailsWith<SerializationException> {
            sessionCodec.decode(
                encodedProvider.replaceFirst(
                    "\"code\":\"PROVIDER_NETWORK\"",
                    "\"code\":\"PROVIDER_PROTOCOL\"",
                ),
            )
        }
        assertFailsWith<SerializationException> {
            sessionCodec.decode(encodedProvider.replaceFirst("\"retryAtEpochMs\":5000", "\"retryAtEpochMs\":-1"))
        }

        val encodedHost = sessionCodec.encode(
            original.copy(
                state = original.state.copy(
                    status = AgentStatus.INTERRUPTED,
                    stopReason = StopReason.INTERRUPTED,
                ),
                interruption = AgentInterruption(
                    reason = AgentInterruptionReason.HOST_REQUESTED,
                    occurredAtEpochMs = 2_500L,
                ),
            ),
        )
        assertFailsWith<SerializationException> {
            sessionCodec.decode(
                encodedHost.replaceFirst(
                    "\"reason\":\"HOST_REQUESTED\"",
                    "\"reason\":\"PROVIDER_FAILURE\"",
                ),
            )
        }
    }

    @Test
    fun sessionCodec_rejectsUnknownEnvelopeAndPayloadFieldsEvenWithPermissiveJson() {
        val encoded = sessionCodec.encode(snapshot())
        val unknownEnvelope = encoded.dropLast(1) + ",\"futureEnvelopeField\":true}"
        val unknownPayload = encoded.replaceFirst("\"payload\":{", "\"payload\":{\"futurePayloadField\":true,")

        assertFailsWith<SerializationException> {
            sessionCodec.decode(unknownEnvelope)
        }
        assertFailsWith<SerializationException> {
            sessionCodec.decode(unknownPayload)
        }
    }

    @Test
    fun codecs_rejectCorruptJson() {
        assertFailsWith<SerializationException> {
            sessionCodec.decode("{\"schemaVersion\":4")
        }
        assertFailsWith<SerializationException> {
            checkpointCodec.decode("not-json")
        }
    }

    @Test
    fun sessionCodec_rejectsMismatchedSnapshotIdentityOnEncodeAndDecode() {
        assertFailsWith<IllegalArgumentException> {
            snapshot(requestSessionId = AgentSessionId("different-request-session"))
        }

        val encoded = sessionCodec.encode(snapshot())
        val corrupted = encoded.replaceFirst("session-contract", "different-envelope-session")
        assertFailsWith<SerializationException> {
            sessionCodec.decode(corrupted)
        }
    }

    @Test
    fun checkpointCodec_rejectsMismatchedAndNegativeTurnsOnEncodeAndDecode() {
        val snapshot = snapshot()
        assertFailsWith<IllegalArgumentException> {
            AgentCheckpoint(
                sessionId = snapshot.sessionId,
                runId = snapshot.runId,
                cursor = AgentResumeCursor(2, AgentResumePhase.MODEL_PENDING),
                state = snapshot.state.copy(turn = 1),
            )
        }

        val valid = checkpoint(snapshot, turn = 1)
        val corrupt = checkpointCodec.encode(valid).replaceFirst("\"turn\":1", "\"turn\":-1")
        assertFailsWith<SerializationException> { checkpointCodec.decode(corrupt) }
    }

    private fun snapshot(
        requestSessionId: AgentSessionId = AgentSessionId("session-contract"),
    ): AgentSessionSnapshot {
        val sessionId = AgentSessionId("session-contract")
        val message = AgentMessage(
            id = "message-contract",
            role = MessageRole.USER,
            parts = listOf(TextPart("hello")),
            createdAtEpochMs = 1L,
        )
        val request = AgentRequest(
            sessionId = requestSessionId,
            messages = listOf(message),
            model = ModelDescriptor(provider = "test", model = "test"),
        )
        return AgentSessionSnapshot(
            sessionId = sessionId,
            runId = AgentRunId("run-contract"),
            request = request,
            state = AgentStateSnapshot(messages = listOf(message)),
            updatedAtEpochMs = 2L,
        )
    }

    private fun checkpoint(
        snapshot: AgentSessionSnapshot,
        turn: Int,
    ): AgentCheckpoint = AgentCheckpoint(
        sessionId = snapshot.sessionId,
        runId = snapshot.runId,
        cursor = AgentResumeCursor(turn, AgentResumePhase.MODEL_PENDING),
        state = snapshot.state.copy(turn = turn),
    )
}
