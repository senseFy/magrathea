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
    private val json = Json {
        classDiscriminator = "type"
        encodeDefaults = false
        ignoreUnknownKeys = false
    }

    @Test
    fun agentMessage_matchesV4GoldenFixture() {
        val expected = agentMessageFixture()
        val fixture = resource("/v4/core/agent-message.json")

        assertGolden(fixture, json.encodeToString(AgentMessage.serializer(), expected))
        assertEquals(expected, json.decodeFromString(AgentMessage.serializer(), fixture))
    }

    @Test
    fun agentSessionSnapshot_matchesV4GoldenFixture() {
        val expected = sessionFixture()
        val fixture = resource("/v4/core/agent-session-snapshot.json")

        assertGolden(fixture, json.encodeToString(AgentSessionSnapshot.serializer(), expected))
        assertEquals(expected, json.decodeFromString(AgentSessionSnapshot.serializer(), fixture))
    }

    @Test
    fun storedSessionEnvelope_matchesV4GoldenFixture() {
        val expected = sessionFixture()
        val fixture = resource("/v4/core/stored-session-envelope.json")
        val codec = AgentSessionSnapshotCodec(json, sdkVersion = "0.1.0-alpha.1")

        assertGolden(fixture, codec.encode(expected))
        assertEquals(expected, codec.decode(fixture))
    }

    @Test
    fun storedCheckpointEnvelope_matchesV4GoldenFixture() {
        val session = sessionFixture()
        val expected = AgentCheckpoint(
            sessionId = session.sessionId,
            runId = session.runId,
            cursor = AgentResumeCursor(
                turn = session.state.turn,
                phase = AgentResumePhase.TURN_COMMITTED,
            ),
            state = session.state,
        )
        val fixture = resource("/v4/core/stored-checkpoint-envelope.json")
        val codec = AgentCheckpointCodec(json, sdkVersion = "0.1.0-alpha.1")

        assertGolden(fixture, codec.encode(expected))
        assertEquals(expected, codec.decode(fixture))
    }

    @Test
    fun corruptStoredSessionEnvelope_isRejected() {
        val corruptFixture = resource("/v4/core/stored-session-envelope-corrupt.json")
        val codec = AgentSessionSnapshotCodec(json, sdkVersion = "0.1.0-alpha.1")

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

    private fun resource(path: String): String {
        return requireNotNull(javaClass.getResource(path)) { "Missing serialization fixture $path" }.readText()
    }

    private fun assertGolden(expectedPayload: String, actualPayload: String) {
        val expected = json.parseToJsonElement(expectedPayload)
        val actual = json.parseToJsonElement(actualPayload)
        assertEquals("Serialization fixture changed. Actual payload: $actualPayload", expected, actual)
    }
}
