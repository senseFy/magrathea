package saien.magrathea.core

import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
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

        assertEquals(STORAGE_SCHEMA_V8_VERSION, CURRENT_STORAGE_SCHEMA_VERSION)
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
        val original = snapshot(contextManagement = MIGRATED_CONTEXT_MANAGEMENT)
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
        val v6 = current.downgradedToSchema7().downgradedToSchema6()

        assertEquals(2, current.split("\"maxOutputTokens\":null").size - 1)
        val migrated = sessionCodec.decodeResult(v6)

        assertEquals(6, migrated.sourceSchemaVersion)
        assertEquals(withCompaction, migrated.value)
        assertTrue(requireNotNull(migrated.rewritePayload).contains("\"maxOutputTokens\":null"))
    }

    @Test
    fun schemaV7MigrationDisablesToolResultStagesAndDropsUsageObservations() {
        val original = toolResultContextSnapshot()
        val v7 = sessionCodec.encode(original).downgradedToSchema7()

        val migrated = sessionCodec.decodeResult(v7)

        assertEquals(7, migrated.sourceSchemaVersion)
        val expected = original.copy(
            request = original.request.copy(
                engine = original.request.engine.copy(
                    runtime = original.request.engine.runtime.copy(
                        contextManagement = MIGRATED_CONTEXT_MANAGEMENT,
                    ),
                ),
                messages = original.request.messages.map { it.withoutContextHints() },
            ),
            state = original.state.copy(
                messages = original.state.messages.map { it.withoutContextHints() },
                contextManagement = ContextManagementState(),
            ),
        )
        assertEquals(expected, migrated.value)
        val rewrite = requireNotNull(migrated.rewritePayload)
        assertTrue(rewrite.contains("\"estimationUnit\":\"UTF16_CHARS\""))
        assertTrue(rewrite.contains("\"contextHints\":null"))
    }

    @Test
    fun schemaV7MigrationRejectsAPrematureSchemaV8TargetField() {
        val mislabeled = sessionCodec.encode(snapshot())
            .replaceFirst("\"schemaVersion\":8", "\"schemaVersion\":7")

        val failure = assertFailsWith<StoredEnvelopeDecodeException> {
            sessionCodec.decode(mislabeled)
        }

        assertEquals(StoredEnvelopeDecodeFailure.MIGRATION_FAILED, failure.failure)
        assertEquals(7, failure.storedSchemaVersion)
    }

    @Test
    fun schemaV7CheckpointMigrationAddsHintsToJournalResults() {
        val snapshot = snapshot()
        val result = ToolExecutionResult(
            toolCallId = "call-contract",
            toolName = "lookup",
            result = JsonPrimitive("found"),
            contextHints = ToolResultContextHints(retention = ToolResultRetention.PINNED),
        )
        val checkpoint = checkpoint(snapshot, turn = 2).copy(
            toolExecutions = listOf(
                ToolExecutionRecord(
                    executionId = "execution-contract",
                    toolCallId = "call-contract",
                    toolName = "lookup",
                    callOrdinal = 1,
                    state = ToolExecutionState.COMPLETED,
                    result = result,
                ),
            ),
        )
        val v7 = checkpointCodec.encode(checkpoint).downgradedToSchema7()

        val migrated = checkpointCodec.decodeResult(v7)

        assertEquals(7, migrated.sourceSchemaVersion)
        assertEquals(
            null,
            migrated.value.toolExecutions.single().result?.contextHints,
        )
    }

    @Test
    fun sessionCodec_roundTripsToolResultContextState() {
        val expected = toolResultContextSnapshot()

        assertEquals(expected, sessionCodec.decode(sessionCodec.encode(expected)))
    }

    @Test
    fun schemaV6MigrationRejectsAPrematureSchemaV7TargetField() {
        val mislabeled = sessionCodec.encode(snapshot())
            .replaceFirst("\"schemaVersion\":8", "\"schemaVersion\":6")

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

    private fun toolResultContextSnapshot(): AgentSessionSnapshot {
        val base = snapshot()
        val call = AgentMessage(
            id = "assistant-contract",
            role = MessageRole.ASSISTANT,
            parts = listOf(
                ToolCallPart(
                    toolCallId = "call-contract",
                    toolName = "lookup",
                    arguments = JsonObject(mapOf("query" to JsonPrimitive("kmp"))),
                ),
            ),
            createdAtEpochMs = 3L,
        )
        val result = AgentMessage(
            id = "tool-contract",
            role = MessageRole.TOOL,
            parts = listOf(
                ToolResultPart(
                    toolCallId = "call-contract",
                    toolName = "lookup",
                    result = JsonPrimitive("found"),
                    contextHints = ToolResultContextHints(
                        stub = JsonObject(mapOf("query" to JsonPrimitive("kmp"))),
                    ),
                ),
            ),
            createdAtEpochMs = 4L,
        )
        val messages = base.state.messages + call + result
        return base.copy(
            request = base.request.copy(messages = messages),
            state = base.state.copy(
                messages = messages,
                contextManagement = ContextManagementState(
                    usageObservation = ContextUsageObservation(
                        inputTokens = 40,
                        throughMessageId = "tool-contract",
                        historyPrefixDigest = "prefix-digest",
                        compactionGeneration = 0,
                        provider = "test",
                        model = "test",
                        requestFingerprint = "fingerprint",
                        clearingGeneration = 1,
                        resultBudgetTokens = 12_000,
                    ),
                    toolResultClearing = ToolResultClearing(
                        clearedThroughMessageId = "message-contract",
                        sourcePrefixDigest = "clearing-digest",
                        generation = 1,
                        clearedResults = 1,
                        freedTokens = 100,
                        trigger = ToolResultClearingTrigger.PRESSURE,
                        createdAtEpochMs = 5L,
                    ),
                ),
            ),
        )
    }

    private fun AgentMessage.withoutContextHints(): AgentMessage = copy(
        parts = parts.map { part -> if (part is ToolResultPart) part.copy(contextHints = null) else part },
    )

    private fun snapshot(
        requestSessionId: AgentSessionId = AgentSessionId("session-contract"),
        contextManagement: ContextManagementConfig = ContextManagementConfig(),
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
            engine = AgentEngineConfig(runtime = RuntimeConfig(contextManagement = contextManagement)),
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

/** What a schema-7 request decodes to after migration. */
private val MIGRATED_CONTEXT_MANAGEMENT = ContextManagementConfig(
    toolResults = ToolResultContextConfig.DISABLED,
    estimationUnit = ContextEstimationUnit.UTF16_CHARS,
)

/** Rebuilds the schema-7 shape of a current envelope by removing every schema-8 field. */
private fun String.downgradedToSchema7(): String {
    val envelope = Json.parseToJsonElement(this).jsonObject
    require(envelope.getValue("schemaVersion").jsonPrimitive.content == "8")
    val payload = envelope.getValue("payload").jsonObject
        .mapIfPresent("request") { request ->
            request.jsonObject
                .mapIfPresent("messages") { it.withoutToolResultHints() }
                .mapIfPresent("engine") { engine ->
                    engine.jsonObject.mapIfPresent("runtime") { runtime ->
                        runtime.jsonObject.mapIfPresent("contextManagement") {
                            it.jsonObject.without("toolResults", "estimationUnit")
                        }
                    }
                }
        }
        .mapIfPresent("state") { state ->
            state.jsonObject
                .mapIfPresent("messages") { it.withoutToolResultHints() }
                .mapIfPresent("contextManagement") { contextManagement ->
                    contextManagement.jsonObject
                        .without("toolResultClearing")
                        .mapIfPresent("usageObservation") { observation ->
                            (observation as? JsonObject)?.without("clearingGeneration", "resultBudgetTokens")
                                ?: observation
                        }
                }
        }
        .mapIfPresent("toolExecutions") { records ->
            JsonArray(
                records.jsonArray.map { record ->
                    record.jsonObject.mapIfPresent("result") { result ->
                        (result as? JsonObject)?.without("contextHints") ?: result
                    }
                },
            )
        }
    return JsonObject(envelope + mapOf("schemaVersion" to JsonPrimitive(7), "payload" to payload)).toString()
}

private fun String.downgradedToSchema6(): String =
    replaceFirst("\"schemaVersion\":7", "\"schemaVersion\":6")
        .replace(",\"maxOutputTokens\":null", "")

private fun JsonElement.withoutToolResultHints(): JsonElement = JsonArray(
    jsonArray.map { message ->
        message.jsonObject.mapIfPresent("parts") { parts ->
            JsonArray(
                parts.jsonArray.map { part ->
                    val value = part.jsonObject
                    if (value["type"]?.jsonPrimitive?.content == "tool_result") value.without("contextHints") else value
                },
            )
        }
    },
)

private fun JsonObject.without(vararg keys: String): JsonObject = JsonObject(this - keys.toSet())

private fun JsonObject.mapIfPresent(key: String, transform: (JsonElement) -> JsonElement): JsonObject {
    val value = this[key] ?: return this
    return JsonObject(this + (key to transform(value)))
}
