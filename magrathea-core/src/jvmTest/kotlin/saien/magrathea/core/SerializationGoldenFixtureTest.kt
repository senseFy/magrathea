package saien.magrathea.core

import kotlinx.serialization.encodeToString
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SerializationGoldenFixtureTest {
    private val fixtureSdkVersion = "0.1.0-alpha.2"

    private val json = Json {
        classDiscriminator = "type"
        encodeDefaults = false
        ignoreUnknownKeys = false
    }

    @Test
    fun agentMessage_matchesV5GoldenFixture() {
        val expected = agentMessageFixture()
        val fixture = resource("/v5/core/agent-message.json")

        assertGolden(fixture, json.encodeToString(AgentMessage.serializer(), expected))
        assertEquals(expected, json.decodeFromString(AgentMessage.serializer(), fixture))
    }

    @Test
    fun typedToolResultMessage_matchesV5GoldenFixture() {
        val expected = typedToolResultMessageFixture()
        val fixture = resource("/v5/core/typed-tool-result-message.json")

        assertGolden(fixture, json.encodeToString(AgentMessage.serializer(), expected))
        assertEquals(expected, json.decodeFromString(AgentMessage.serializer(), fixture))
    }

    @Test
    fun agentSessionSnapshot_matchesV5GoldenFixture() {
        val expected = sessionFixture()
        val fixture = resource("/v5/core/agent-session-snapshot.json")

        assertGolden(fixture, json.encodeToString(AgentSessionSnapshot.serializer(), expected))
        assertEquals(expected, json.decodeFromString(AgentSessionSnapshot.serializer(), fixture))
    }

    @Test
    fun storedSessionEnvelope_matchesV5GoldenFixture() {
        val expected = interruptedSessionFixture()
        val fixture = resource("/v5/core/stored-session-envelope.json")
        val codec = AgentSessionSnapshotCodec(json, sdkVersion = fixtureSdkVersion)

        assertGolden(fixture, codec.encode(expected))
        assertEquals(expected, codec.decode(fixture))
    }

    @Test
    fun storedCheckpointEnvelope_matchesV5GoldenFixture() {
        val session = sessionFixture()
        val expected = AgentCheckpoint(
            sessionId = session.sessionId,
            runId = session.runId,
            cursor = AgentResumeCursor(
                turn = session.state.turn,
                phase = AgentResumePhase.MODEL_PENDING,
                provider = AgentProviderInvocationCursor(
                    nextPhysicalAttempt = 3,
                    pending = AgentPendingProviderInvocation(
                        requestId = "run-fixture-1:turn-2:attempt-2",
                        purpose = ProviderRequestPurpose.CONTEXT_SUMMARY,
                        inputIdentity = "context-summary-input-fixture-1",
                    ),
                ),
            ),
            state = session.state.copy(
                status = AgentStatus.RUNNING,
                stopReason = null,
            ),
            toolExecutions = listOf(
                ToolExecutionRecord(
                    executionId = "execution-fixture-1",
                    toolCallId = "image-call-fixture-1",
                    toolName = "image_search",
                    callOrdinal = 1,
                    state = ToolExecutionState.COMPLETED,
                    result = journalToolResultFixture(),
                ),
            ),
        )
        val fixture = resource("/v5/core/stored-checkpoint-envelope.json")
        val codec = AgentCheckpointCodec(json, sdkVersion = fixtureSdkVersion)

        assertGolden(fixture, codec.encode(expected))
        assertEquals(expected, codec.decode(fixture))
    }

    @Test
    fun corruptStoredSessionEnvelope_isRejected() {
        val corruptFixture = resource("/v5/core/stored-session-envelope-corrupt.json")
        val codec = AgentSessionSnapshotCodec(json, sdkVersion = fixtureSdkVersion)

        assertThrows(SerializationException::class.java) {
            codec.decode(corruptFixture)
        }
    }

    private fun agentMessageFixture() = AgentMessage(
        id = "message-fixture-1",
        role = MessageRole.ASSISTANT,
        parts = listOf(
            ReasoningPart(text = "check sources", signature = "reason-sig"),
            TextPart(text = "I will search.", phase = MessageBlockPhase.FINAL),
            ToolCallPart(
                toolCallId = "call-1",
                toolName = "search",
                arguments = buildJsonObject { put("query", JsonPrimitive("kmp")) },
                thoughtSignature = "thought-sig",
                providerCallId = "provider-call-1",
            ),
        ),
        createdAtEpochMs = 1_700_000_000_000,
        metadata = buildJsonObject {
            put("provider", JsonPrimitive("gemini"))
            put("model", JsonPrimitive("gemini-contract"))
        },
        stopReason = StopReason.TOOL_CALLS,
    )

    private fun typedToolResultMessageFixture() = AgentMessage(
        id = "tool-message-fixture-1",
        role = MessageRole.TOOL,
        parts = listOf(
            ToolResultPart(
                toolCallId = "image-call-fixture-1",
                toolName = "image_search",
                result = buildJsonObject {
                    put("type", JsonPrimitive("image_search_results"))
                    put("count", JsonPrimitive(1))
                },
                displayText = "1 image",
                metadata = buildJsonObject {
                    put("query", JsonPrimitive("kmp architecture"))
                },
                content = listOf(
                    ToolResultTextContent(
                        text = "One image matched the query.",
                        audiences = setOf(ToolResultAudience.MODEL),
                    ),
                    ToolResultImageContent(
                        source = RemoteToolImageSource(
                            "https://images.example.com/kmp-architecture.jpg",
                        ),
                        previewSource = ToolImageAttachmentReference("attachment-preview-fixture-1"),
                        previewMimeType = "image/jpeg",
                        mimeType = "image/jpeg",
                        title = "Kotlin Multiplatform architecture",
                        altText = "A Kotlin Multiplatform architecture diagram",
                        width = 1_280,
                        height = 720,
                        attribution = ToolMediaAttribution(
                            title = "Example source",
                            url = "https://example.com/kmp-architecture",
                            license = "CC BY 4.0",
                            licenseUrl = "https://creativecommons.org/licenses/by/4.0/",
                        ),
                        audiences = setOf(ToolResultAudience.USER),
                        reference = MediaReference.forToolResult(
                            executionId = "execution-fixture-1",
                            contentIndex = 1,
                        ),
                    ),
                ),
                providerMetadata = buildJsonObject {
                    put("provider", JsonPrimitive("fixture"))
                },
                modelResultVisible = false,
                origin = toolOriginFixture(),
            ),
            ToolResultPart(
                toolCallId = "search-call-fixture-1",
                toolName = "web_search",
                result = buildJsonObject {
                    put("code", JsonPrimitive("private-provider-code"))
                },
                isError = true,
                displayText = "Search failed.",
                userErrorCode = "search-unavailable",
                modelResultVisible = false,
            ),
        ),
        createdAtEpochMs = 1_700_000_000_003,
    )

    private fun journalToolResultFixture() = ToolExecutionResult(
        toolCallId = "image-call-fixture-1",
        toolName = "image_search",
        result = buildJsonObject {
            put("type", JsonPrimitive("image_search_results"))
            put("count", JsonPrimitive(1))
        },
        displayText = "1 image",
        metadata = buildJsonObject {
            put("query", JsonPrimitive("kmp architecture"))
        },
        content = listOf(
            ToolResultTextContent(
                text = "One image matched the query.",
                audiences = setOf(ToolResultAudience.MODEL),
            ),
            ToolResultImageContent(
                source = RemoteToolImageSource(
                    "https://images.example.com/kmp-architecture.jpg",
                ),
                mimeType = "image/jpeg",
                title = "Kotlin Multiplatform architecture",
                audiences = setOf(ToolResultAudience.USER),
                reference = MediaReference.forToolResult(
                    executionId = "execution-fixture-1",
                    contentIndex = 1,
                ),
            ),
        ),
        modelResultVisible = false,
        origin = toolOriginFixture(),
    )

    private fun toolOriginFixture() = ToolOrigin(
        sourceId = "image-catalog-fixture",
        sourceLabel = "Image catalog",
        toolId = "image_search",
        toolLabel = "Image search",
    )

    private fun sessionFixture(): AgentSessionSnapshot {
        val sessionId = AgentSessionId("session-fixture-1")
        val user = AgentMessage(
            id = "user-fixture-1",
            role = MessageRole.USER,
            parts = listOf(TextPart("hello")),
            createdAtEpochMs = 1_700_000_000_001,
        )
        val request = AgentRequest(
            sessionId = sessionId,
            systemPrompt = "You are concise.",
            messages = listOf(user),
            model = ModelDescriptor(
                provider = "gemini",
                model = "gemini-contract",
                supportsReasoning = true,
                supportsStreaming = true,
                contextWindowTokens = 128_000,
            ),
            engine = AgentEngineConfig(
                provider = ProviderConfig(
                    temperature = 0.2,
                    options = ProviderOptions(
                        family = "gemini",
                        values = buildJsonObject { put("thinkingSummaries", JsonPrimitive("auto")) },
                    ),
                    credentialRef = CredentialRef(provider = "gemini", profile = "work"),
                ),
            ),
        )
        return AgentSessionSnapshot(
            sessionId = sessionId,
            runId = AgentRunId("run-fixture-1"),
            request = request,
            state = AgentStateSnapshot(
                messages = listOf(user),
                turn = 2,
                status = AgentStatus.COMPLETED,
                stopReason = StopReason.COMPLETED,
                usage = TokenUsage(inputTokens = 12, outputTokens = 4, reasoningTokens = 2),
                latestRequestUsage = TokenUsage(
                    inputTokens = 9,
                    outputTokens = 4,
                    reasoningTokens = 2,
                ),
            ),
            updatedAtEpochMs = 1_700_000_000_002,
        )
    }

    private fun interruptedSessionFixture(): AgentSessionSnapshot {
        val session = sessionFixture()
        return session.copy(
            state = session.state.copy(
                status = AgentStatus.INTERRUPTED,
                stopReason = StopReason.INTERRUPTED,
            ),
            interruption = AgentInterruption(
                reason = AgentInterruptionReason.PROVIDER_FAILURE,
                provider = ProviderInterruption(
                    code = AgentFailureCode.PROVIDER_NETWORK,
                    phase = ProviderInterruptionPhase.AFTER_FIRST_EVENT,
                    retryAtEpochMs = 1_700_000_005_002,
                ),
                occurredAtEpochMs = 1_700_000_000_002,
            ),
        )
    }

    private fun resource(path: String): String {
        return requireNotNull(javaClass.getResource(path)) { "Missing serialization fixture $path" }.readText()
    }

    private fun assertGolden(expectedPayload: String, actualPayload: String) {
        val expected = json.parseToJsonElement(expectedPayload)
        val actual = json.parseToJsonElement(actualPayload)
        assertEquals("Serialization fixture changed. Actual payload: $actualPayload", expected, actual)
    }
}
