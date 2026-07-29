package saien.magrathea.provider.openai

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
import saien.magrathea.core.MessageRole
import saien.magrathea.core.ModelDescriptor
import saien.magrathea.core.ProviderConfig
import saien.magrathea.core.ProviderCredential
import saien.magrathea.core.StopReason
import saien.magrathea.core.TextPart
import saien.magrathea.core.ToolCallPart
import saien.magrathea.core.ToolDefinition
import saien.magrathea.core.ToolExecutionRequest
import saien.magrathea.core.ToolExecutionResult
import saien.magrathea.core.ToolExecutor
import saien.magrathea.provider.api.InMemoryProviderRegistry
import saien.magrathea.runtime.DefaultAgentRunner
import saien.magrathea.runtime.InMemoryAgentPersistence
import saien.magrathea.runtime.InMemoryToolRegistry

class OpenAiResponsesVerticalContractTest {
    @Test
    fun codecTransportRuntimeToolAndAuthoritativeReplayExecuteToolExactlyOnce() = runBlocking {
        val credentials = CredentialProvider { ProviderCredential("vertical-secret") }
        val transport = ScriptedOpenAiTransport(
            streamResponses = listOf(
                openAiSseFrames(OPENAI_TOOL_STREAM),
                openAiSseFrames(OPENAI_TEXT_STREAM),
            ),
        )
        val provider = OpenAiProviderAdapter(transport = transport)
        val tool = WeatherTool()
        val runner = DefaultAgentRunner(
            providerRegistry = InMemoryProviderRegistry(listOf(provider)),
            toolRegistry = InMemoryToolRegistry(listOf(tool)),
            persistence = InMemoryAgentPersistence(),
            credentialProvider = credentials,
            dispatcher = Dispatchers.Unconfined,
        )

        val state = runner.run(request()).toList().filterIsInstance<AgentEvent.Completed>().single().state
        val assistants = state.messages.filter { it.role == MessageRole.ASSISTANT }
        val call = assistants.first().parts.filterIsInstance<ToolCallPart>().single()

        assertEquals(1, tool.executionCount)
        assertEquals("call_weather_1", call.toolCallId)
        assertEquals("Shanghai", call.arguments.jsonObject["city"]?.jsonPrimitive?.content)
        assertFalse(call.partial)
        assertEquals(StopReason.TOOL_CALLS, assistants.first().stopReason)
        assertEquals("Shanghai is sunny.", assistants.last().parts.filterIsInstance<TextPart>().single().text)
        assertEquals(StopReason.COMPLETED, assistants.last().stopReason)

        val secondPayload = Json.parseToJsonElement(transport.requests[1].first.body!!).jsonObject
        val input = secondPayload["input"]!!.jsonArray
        assertEquals(listOf("user", "function_call", "function_call_output"), input.map { item ->
            item.jsonObject["type"]?.jsonPrimitive?.content ?: item.jsonObject["role"]!!.jsonPrimitive.content
        })
        assertEquals("fc_weather_1", input[1].jsonObject["id"]!!.jsonPrimitive.content)
        assertEquals("call_weather_1", input[2].jsonObject["call_id"]!!.jsonPrimitive.content)
        provider.close()
    }

    private fun request(): AgentRequest = AgentRequest(
        messages = listOf(AgentMessage(role = MessageRole.USER, parts = listOf(TextPart("Weather?")))),
        model = ModelDescriptor(
            provider = "openai",
            model = "gpt-contract",
            supportsToolCalls = true,
            supportsStreaming = true,
        ),
        tools = listOf(WeatherTool.DEFINITION),
        engine = AgentEngineConfig(
            provider = ProviderConfig(credentialRef = CredentialRef("openai")),
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
                result = buildJsonObject { put("condition", "sunny") },
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
