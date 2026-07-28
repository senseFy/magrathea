package saien.magrathea.provider.anthropic

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
import saien.magrathea.core.TextPart
import saien.magrathea.core.ToolCallPart
import saien.magrathea.core.ToolDefinition
import saien.magrathea.core.ToolExecutionRequest
import saien.magrathea.core.ToolExecutionResult
import saien.magrathea.core.ToolExecutor
import saien.magrathea.provider.api.InMemoryProviderRegistry
import saien.magrathea.runtime.DefaultAgentRunner
import saien.magrathea.runtime.InMemoryCheckpointStore
import saien.magrathea.runtime.InMemorySessionStore
import saien.magrathea.runtime.InMemoryToolRegistry

class AnthropicMessagesVerticalContractTest {
    @Test
    fun namedSseRuntimeToolAndExactBlockReplayExecuteToolExactlyOnce() = runBlocking {
        val credentials = CredentialProvider { ProviderCredential("vertical-secret") }
        val transport = ScriptedAnthropicTransport(
            streamResponses = listOf(
                anthropicSseFrames(ANTHROPIC_TOOL_STREAM),
                anthropicSseFrames(ANTHROPIC_TEXT_STREAM),
            ),
        )
        val provider = AnthropicProviderAdapter(transport = transport)
        val tool = WeatherTool()
        val runner = DefaultAgentRunner(
            providerRegistry = InMemoryProviderRegistry(listOf(provider)),
            toolRegistry = InMemoryToolRegistry(listOf(tool)),
            sessionStore = InMemorySessionStore(),
            checkpointStore = InMemoryCheckpointStore(),
            credentialProvider = credentials,
            dispatcher = Dispatchers.Unconfined,
        )

        val state = runner.run(request()).toList().filterIsInstance<AgentEvent.Completed>().single().state
        val assistants = state.messages.filter { it.role == MessageRole.ASSISTANT }
        val call = assistants.first().parts.filterIsInstance<ToolCallPart>().single()

        assertEquals(1, tool.executionCount)
        assertEquals("toolu_weather_1", call.toolCallId)
        assertEquals("Shanghai", call.arguments.jsonObject["city"]?.jsonPrimitive?.content)
        assertFalse(call.partial)
        assertEquals("Shanghai is sunny.", assistants.last().parts.filterIsInstance<TextPart>().single().text)

        val secondPayload = Json.parseToJsonElement(transport.requests[1].first.body!!).jsonObject
        val messages = secondPayload["messages"]!!.jsonArray
        assertEquals(Json.parseToJsonElement(ANTHROPIC_TOOL_CONTENT), messages[1].jsonObject["content"])
        assertEquals("toolu_weather_1", messages[2].jsonObject["content"]!!.jsonArray.single().jsonObject["tool_use_id"]!!.jsonPrimitive.content)
        provider.close()
    }

    private fun request(): AgentRequest = AgentRequest(
        messages = listOf(AgentMessage(role = MessageRole.USER, parts = listOf(TextPart("Weather?")))),
        model = ModelDescriptor(
            provider = "anthropic",
            model = "claude-contract",
            supportsToolCalls = true,
            supportsStreaming = true,
        ),
        tools = listOf(WeatherTool.DEFINITION),
        engine = AgentEngineConfig(provider = ProviderConfig(credentialRef = CredentialRef("anthropic"))),
    )

    private class WeatherTool : ToolExecutor {
        var executionCount = 0
        override val definition = DEFINITION

        override suspend fun execute(request: ToolExecutionRequest): ToolExecutionResult {
            executionCount += 1
            return ToolExecutionResult(
                toolCallId = request.toolCall.toolCallId,
                toolName = request.toolCall.toolName,
                result = buildJsonObject { put("condition", "sunny") },
            )
        }

        companion object {
            val DEFINITION = ToolDefinition("get_weather", "Weather", JsonObject(emptyMap()))
        }
    }
}
