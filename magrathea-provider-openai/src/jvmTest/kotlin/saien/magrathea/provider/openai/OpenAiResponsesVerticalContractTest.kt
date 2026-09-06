package saien.magrathea.provider.openai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
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
        verifyToolRoundTrip(OPENAI_TOOL_STREAM, "Shanghai")
    }

    @Test
    fun revisedTerminalArgumentsExecuteOnceAndMatchTheNextRequest() = runBlocking {
        val stream = OPENAI_TOOL_STREAM.filterNot { (event, _) -> event == "response.function_call_arguments.done" }
            .flatMap { (event, data) ->
                val payload = if (event == "response.completed") data.replace("Shanghai", "Beijing") else data
                List(if (event == "response.output_item.done" || event == "response.completed") 2 else 1) { event to payload }
            }
        verifyToolRoundTrip(stream, "Beijing")
    }

    private suspend fun verifyToolRoundTrip(stream: List<Pair<String, String>>, city: String) {
        val credentials = CredentialProvider { ProviderCredential("vertical-secret") }
        val transport = ScriptedOpenAiTransport(
            streamResponses = listOf(
                openAiSseFrames(stream),
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
        assertEquals(city, tool.lastCity)
        assertEquals("call_weather_1", call.toolCallId)
        assertEquals(city, call.arguments.jsonObject["city"]?.jsonPrimitive?.content)
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
        assertEquals(city, Json.parseToJsonElement(input[1].jsonObject["arguments"]!!.jsonPrimitive.content).jsonObject["city"]!!.jsonPrimitive.content)
        assertEquals("call_weather_1", input[2].jsonObject["call_id"]!!.jsonPrimitive.content)
        provider.close()
    }

    @Test
    fun failedOrInterruptedResponsesNeverExecuteProvisionalTools() = runBlocking {
        val failure = "response.failed" to """{"type":"response.failed","response":{"error":{"code":"invalid_request"}}}"""
        val streams = listOf(
            OPENAI_TOOL_STREAM.dropLast(1),
            OPENAI_TOOL_STREAM.dropLast(1) + failure,
            OPENAI_TOOL_STREAM + failure,
            OPENAI_TOOL_STREAM.map { (event, data) -> event to if (event == "response.completed") data.replace("Shanghai", "broken\\") else data },
        )
        for (stream in streams) {
            val tool = WeatherTool()
            val provider = OpenAiProviderAdapter(transport = ScriptedOpenAiTransport(
                streamResponses = listOf(openAiSseFrames(stream)),
            ))
            val runner = runner(provider, tool)
            val events = runner.run(request()).toList()
            assertEquals(0, tool.executionCount)
            assertTrue(events.any { it is AgentEvent.Failed || it is AgentEvent.Interrupted })
            assertTrue(events.none { it is AgentEvent.ToolRequested })
            provider.close()
        }
    }

    @Test
    fun incompleteResponseNeverExecutesToolsRegardlessOfArgumentCompleteness() = runBlocking {
        for ((reason, stopReason) in listOf("max_output_tokens" to StopReason.MAX_TOKENS, "content_filter" to StopReason.ERROR)) {
            for (item in listOf(OPENAI_TOOL_ITEM, OPENAI_TOOL_ITEM.replace("{\\\"city\\\":\\\"Shanghai\\\"}", "{"))) {
                val response = """{"id":"r","status":"incomplete","incomplete_details":{"reason":"$reason"},"output":[$item]}"""
                val stream = OPENAI_TOOL_STREAM.dropLast(1) + ("response.incomplete" to """{"type":"response.incomplete","response":$response}""")
                val tool = WeatherTool()
                val transport = ScriptedOpenAiTransport(streamResponses = listOf(openAiSseFrames(stream)))
                val provider = OpenAiProviderAdapter(transport = transport)
                val events = runner(provider, tool).run(request()).toList()
                assertEquals(0, tool.executionCount)
                assertEquals(1, transport.requests.size)
                val state = events.filterIsInstance<AgentEvent.Completed>().single().state
                assertEquals(stopReason, state.stopReason)
                assertTrue(state.messages.last().parts.filterIsInstance<ToolCallPart>().single().partial)
                val nonStreaming = OpenAiResponsesCodec("openai", "model").decodeNonStreaming(response)
                assertTrue(nonStreaming.events.none { it is saien.magrathea.provider.api.ProviderEvent.ToolCallEnd })
                provider.close()
            }
        }
    }

    @Test
    fun duplicateFinalCallIdsCannotMergeExecutableArguments() = runBlocking {
        val second = OPENAI_TOOL_ITEM.replace("fc_weather_1", "fc_weather_2").replace("Shanghai", "Beijing")
        val response = """{"id":"r","status":"completed","output":[$OPENAI_TOOL_ITEM,$second]}"""
        val stream = listOf("response.completed" to """{"type":"response.completed","response":$response}""")
        val transport = ScriptedOpenAiTransport(streamResponses = listOf(
            openAiSseFrames(stream), openAiSseFrames(OPENAI_TEXT_STREAM),
        ))
        val provider = OpenAiProviderAdapter(transport = transport)
        val tool = WeatherTool()
        val events = runner(provider, tool).run(request()).toList()
        assertEquals(0, tool.executionCount)
        assertTrue(events.any { it is AgentEvent.Failed })
        provider.close()
    }

    @Test
    fun unfinishedToolTurnDoesNotPreventASecondRunOnAnotherModel() = runBlocking {
        val item = OPENAI_TOOL_ITEM.replace("{\\\"city\\\":\\\"Shanghai\\\"}", "{")
        val response = """{"id":"r","status":"incomplete","incomplete_details":{"reason":"max_output_tokens"},"output":[$item]}"""
        val stream = listOf("response.incomplete" to """{"type":"response.incomplete","response":$response}""")
        val transport = ScriptedOpenAiTransport(streamResponses = listOf(
            openAiSseFrames(stream), openAiSseFrames(OPENAI_TEXT_STREAM),
        ))
        val provider = OpenAiProviderAdapter(transport = transport)
        val tool = WeatherTool()
        val first = runner(provider, tool).run(request()).toList().filterIsInstance<AgentEvent.Completed>().single().state
        val next = request().copy(
            model = request().model.copy(model = "other-model"),
            messages = first.messages + AgentMessage(role = MessageRole.USER, parts = listOf(TextPart("Continue"))),
        )
        val events = runner(provider, tool).run(next).toList()
        assertTrue(events.any { it is AgentEvent.Completed })
        assertEquals(0, tool.executionCount)
        assertEquals(2, transport.requests.size)
        val input = Json.parseToJsonElement(transport.requests.last().first.body!!).jsonObject["input"]!!.jsonArray
        assertTrue(input.none { it.jsonObject["type"]?.jsonPrimitive?.content in setOf("function_call", "function_call_output") })
        assertTrue(first.messages.last().parts.filterIsInstance<ToolCallPart>().single().partial)
        provider.close()
    }

    private fun runner(provider: OpenAiProviderAdapter, tool: WeatherTool) = DefaultAgentRunner(
        providerRegistry = InMemoryProviderRegistry(listOf(provider)),
        toolRegistry = InMemoryToolRegistry(listOf(tool)),
        persistence = InMemoryAgentPersistence(),
        credentialProvider = CredentialProvider { ProviderCredential("vertical-secret") },
        dispatcher = Dispatchers.Unconfined,
    )

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
        var lastCity: String? = null

        override val definition: ToolDefinition = DEFINITION

        override suspend fun execute(request: ToolExecutionRequest): ToolExecutionResult {
            executionCount += 1
            lastCity = request.toolCall.arguments.jsonObject["city"]?.jsonPrimitive?.content
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
