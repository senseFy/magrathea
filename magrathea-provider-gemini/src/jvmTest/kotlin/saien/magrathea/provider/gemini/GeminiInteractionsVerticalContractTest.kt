package saien.magrathea.provider.gemini

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import saien.magrathea.core.AgentEngineConfig
import saien.magrathea.core.AgentEvent
import saien.magrathea.core.AgentMessage
import saien.magrathea.core.AgentRequest
import saien.magrathea.core.CredentialProvider
import saien.magrathea.core.CredentialRef
import saien.magrathea.core.MessageBlockPhase
import saien.magrathea.core.MessageRole
import saien.magrathea.core.ModelDescriptor
import saien.magrathea.core.ProviderConfig
import saien.magrathea.core.ProviderCredential
import saien.magrathea.core.ReasoningPart
import saien.magrathea.core.StopReason
import saien.magrathea.core.TextPart
import saien.magrathea.core.TokenUsage
import saien.magrathea.core.ToolCallPart
import saien.magrathea.core.ToolDefinition
import saien.magrathea.core.ToolExecutionRequest
import saien.magrathea.core.ToolExecutionResult
import saien.magrathea.core.ToolExecutor
import saien.magrathea.provider.api.InMemoryProviderRegistry
import saien.magrathea.runtime.DefaultAgentRunner
import saien.magrathea.runtime.InMemoryAgentPersistence
import saien.magrathea.runtime.InMemoryToolRegistry

class GeminiInteractionsVerticalContractTest {
    @Test
    fun codecTransportRuntimeToolAndStatelessReplayReachFinalState() = runBlocking {
        var credentialResolutions = 0
        val credentials = CredentialProvider {
            credentialResolutions += 1
            ProviderCredential("vertical-test-key")
        }
        val transport = ScriptedHttpTransport(
            streamScripts = listOf(
                sseFrames(TOOL_INTERACTION_SSE),
                sseFrames(FINAL_INTERACTION_SSE),
            ),
        )
        val provider = GeminiProviderAdapter(transport = transport)
        val tool = WeatherTool()
        val runner = DefaultAgentRunner(
            providerRegistry = InMemoryProviderRegistry(listOf(provider)),
            toolRegistry = InMemoryToolRegistry(listOf(tool)),
            persistence = InMemoryAgentPersistence(),
            credentialProvider = credentials,
            dispatcher = Dispatchers.Unconfined,
        )

        val events = runner.run(request()).toList()
        val state = events.filterIsInstance<AgentEvent.Completed>().single().state
        val assistants = state.messages.filter { it.role == MessageRole.ASSISTANT }
        val toolTurn = assistants.first()
        val finalTurn = assistants.last()

        assertEquals("I should verify the weather.", toolTurn.parts.filterIsInstance<ReasoningPart>().single().text)
        assertEquals("thought-sig-1", toolTurn.parts.filterIsInstance<ReasoningPart>().single().signature)
        assertEquals("I'll check the live weather. ", toolTurn.parts.filterIsInstance<TextPart>().single().text)
        val call = toolTurn.parts.filterIsInstance<ToolCallPart>().single()
        assertEquals("Shanghai", call.arguments.jsonObject["city"]?.jsonPrimitive?.content)
        assertFalse(call.partial)
        assertEquals(StopReason.TOOL_CALLS, toolTurn.stopReason)
        assertEquals(1, tool.executionCount)

        assertEquals("Shanghai is sunny and 27°C.", finalTurn.parts.filterIsInstance<TextPart>().single().text)
        assertEquals(MessageBlockPhase.FINAL, finalTurn.parts.filterIsInstance<TextPart>().single().phase)
        assertEquals(StopReason.COMPLETED, finalTurn.stopReason)
        assertEquals(TokenUsage(36, 14, 2), state.usage)
        assertEquals(2, credentialResolutions)

        val secondPayload = Json.parseToJsonElement(transport.requests[1].body!!).jsonObject
        val inputTypes = secondPayload["input"]!!.jsonArray.map { it.jsonObject["type"]!!.jsonPrimitive.content }
        assertEquals(
            listOf("user_input", "thought", "model_output", "function_call", "function_result"),
            inputTypes,
        )
        assertEquals("call-weather-1", secondPayload["input"]!!.jsonArray.last().jsonObject["call_id"]!!.jsonPrimitive.content)
        provider.close()
    }

    private fun request(): AgentRequest = AgentRequest(
        messages = listOf(
            AgentMessage(
                role = MessageRole.USER,
                parts = listOf(TextPart("Weather in Shanghai?")),
            ),
        ),
        model = ModelDescriptor(
            provider = "gemini",
            model = "gemini-contract-model",
            supportsReasoning = true,
            supportsStreaming = true,
        ),
        tools = listOf(WeatherTool.DEFINITION),
        engine = AgentEngineConfig(
            provider = ProviderConfig(credentialRef = CredentialRef("gemini")),
        ),
    )

    private class WeatherTool : ToolExecutor {
        var executionCount: Int = 0

        override val definition: ToolDefinition = DEFINITION

        override suspend fun execute(request: ToolExecutionRequest): ToolExecutionResult {
            executionCount += 1
            return ToolExecutionResult(
                toolCallId = request.toolCall.toolCallId,
                toolName = request.toolCall.toolName,
                result = buildJsonObject {
                    put("city", "Shanghai")
                    put("temperatureC", 27)
                    put("condition", "sunny")
                },
                displayText = "Shanghai: sunny, 27°C",
            )
        }

        companion object {
            val DEFINITION = ToolDefinition(
                name = "get_weather",
                description = "Returns deterministic weather",
                schema = JsonObject(emptyMap()),
            )
        }
    }
}
