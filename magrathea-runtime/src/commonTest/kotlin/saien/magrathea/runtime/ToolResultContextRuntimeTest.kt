package saien.magrathea.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import saien.magrathea.core.AgentEngineConfig
import saien.magrathea.core.AgentEvent
import saien.magrathea.core.AgentMessage
import saien.magrathea.core.AgentRequest
import saien.magrathea.core.AgentSessionId
import saien.magrathea.core.ContextManagementConfig
import saien.magrathea.core.ContextTokenThreshold
import saien.magrathea.core.MessageRole
import saien.magrathea.core.ModelDescriptor
import saien.magrathea.core.RuntimeConfig
import saien.magrathea.core.TextPart
import saien.magrathea.core.ToolCallPart
import saien.magrathea.core.ToolDefinition
import saien.magrathea.core.ToolExecutionRequest
import saien.magrathea.core.ToolExecutionResult
import saien.magrathea.core.ToolExecutor
import saien.magrathea.core.ToolResultClearingConfig
import saien.magrathea.core.ToolResultContextConfig
import saien.magrathea.core.ToolResultContextHints
import saien.magrathea.core.ToolResultPart
import saien.magrathea.provider.api.InMemoryProviderRegistry
import saien.magrathea.provider.api.ProviderAdapter
import saien.magrathea.provider.api.ProviderChunk
import saien.magrathea.provider.api.ProviderRequest

class ToolResultContextRuntimeTest {
    @Test
    fun toolsReceiveTheirBudgetAndTheModelSeesTheReducedResult() = runTest {
        val tool = LargeResultTool()
        val provider = ToolThenAnswerProvider()
        val persistence = InMemoryAgentPersistence()
        val request = request(
            messages = listOf(user("u1")),
            tools = listOf(tool.definition),
            toolResults = ToolResultContextConfig(budget = ContextTokenThreshold(maxTokens = 500), clearing = null),
        )

        runner(provider, tool, persistence).run(request).toList()

        assertEquals(500, tool.observedBudget)
        val answerRequest = provider.requests.last()
        val projected = answerRequest.messages.flatMap { it.parts }.filterIsInstance<ToolResultPart>().single()
        val text = projected.result.jsonPrimitive.content
        assertTrue(text.startsWith("[Tool result truncated to fit the model context"))
        assertTrue("HEAD-" in text && "-TAIL" in text)
        val stored = assertNotNull(persistence.load(request.sessionId)?.snapshot).state.messages
            .flatMap { it.parts }.filterIsInstance<ToolResultPart>().single()
        assertEquals(LARGE_RESULT, stored.result.jsonPrimitive.content)
    }

    @Test
    fun aClearingPassIsCheckpointedAndOnlyStubsReachTheModel() = runTest {
        val provider = ToolThenAnswerProvider(answerOnly = true)
        val persistence = InMemoryAgentPersistence()
        val history = (1..3).flatMap(::completedRun) + user("now")
        val request = request(
            messages = history,
            tools = emptyList(),
            toolResults = ToolResultContextConfig(
                budget = null,
                clearing = ToolResultClearingConfig(
                    trigger = ContextTokenThreshold(maxTokens = 300),
                    keepRecentResults = 1,
                    clearAtLeast = ContextTokenThreshold(maxTokens = 50),
                ),
            ),
        )

        val events = runner(provider, LargeResultTool(), persistence).run(request).toList()

        val sent = provider.requests.single().messages.flatMap { it.parts }.filterIsInstance<ToolResultPart>()
        assertEquals(listOf(true, true, false), sent.map { it.result is JsonObject })
        assertTrue(events.any { it is AgentEvent.CheckpointSaved })
        val saved = assertNotNull(persistence.load(request.sessionId)?.snapshot)
        assertEquals("result2", saved.state.contextManagement.toolResultClearing?.clearedThroughMessageId)
        assertTrue(
            saved.state.messages.flatMap { it.parts }.filterIsInstance<ToolResultPart>()
                .all { it.result is JsonPrimitive },
        )
    }

    private fun runner(provider: ProviderAdapter, tool: ToolExecutor, persistence: InMemoryAgentPersistence) =
        DefaultAgentRunner(
            providerRegistry = InMemoryProviderRegistry(listOf(provider)),
            toolRegistry = InMemoryToolRegistry(listOf(tool)),
            persistence = persistence,
        )

    private fun request(
        messages: List<AgentMessage>,
        tools: List<ToolDefinition>,
        toolResults: ToolResultContextConfig,
    ) = AgentRequest(
        sessionId = AgentSessionId("tool-result-context-runtime"),
        messages = messages,
        model = ModelDescriptor(provider = PROVIDER, model = "test-model", contextWindowTokens = 100_000),
        tools = tools,
        engine = AgentEngineConfig(
            runtime = RuntimeConfig(
                contextManagement = ContextManagementConfig(
                    reserveTokens = 1_000,
                    charsPerTokenEstimate = 4,
                    toolResults = toolResults,
                ),
            ),
        ),
    )

    private fun user(id: String) = AgentMessage(id = id, role = MessageRole.USER, parts = listOf(TextPart("question $id")))

    private fun completedRun(index: Int) = listOf(
        user("u$index"),
        AgentMessage(
            id = "call$index",
            role = MessageRole.ASSISTANT,
            parts = listOf(
                ToolCallPart(
                    toolCallId = "call-$index",
                    toolName = TOOL_NAME,
                    arguments = buildJsonObject { put("q", JsonPrimitive("q$index")) },
                ),
            ),
        ),
        AgentMessage(
            id = "result$index",
            role = MessageRole.TOOL,
            parts = listOf(
                ToolResultPart(
                    toolCallId = "call-$index",
                    toolName = TOOL_NAME,
                    result = JsonPrimitive("r".repeat(600)),
                    contextHints = ToolResultContextHints(stub = buildJsonObject { put("q", JsonPrimitive("q$index")) }),
                ),
            ),
        ),
        AgentMessage(id = "answer$index", role = MessageRole.ASSISTANT, parts = listOf(TextPart("answer $index"))),
    )

    private class LargeResultTool : ToolExecutor {
        override val definition = ToolDefinition(name = TOOL_NAME, description = "Large", schema = buildJsonObject { })
        var observedBudget: Long? = null

        override suspend fun execute(request: ToolExecutionRequest): ToolExecutionResult {
            observedBudget = request.resultBudgetTokens
            return ToolExecutionResult(request.toolCall.toolCallId, definition.name, JsonPrimitive(LARGE_RESULT))
        }
    }

    private class ToolThenAnswerProvider(private val answerOnly: Boolean = false) : ProviderAdapter {
        override val key: String = PROVIDER
        val requests = mutableListOf<ProviderRequest>()

        override suspend fun generate(request: ProviderRequest): Flow<ProviderChunk> = flow {
            requests += request
            if (answerOnly || request.messages.lastOrNull()?.role == MessageRole.TOOL) {
                emit(providerChunk(text = "done", completed = true))
            } else {
                emit(
                    providerChunk(
                        toolCalls = listOf(
                            ToolCallPart(
                                toolCallId = "call-live",
                                toolName = TOOL_NAME,
                                arguments = buildJsonObject { put("q", JsonPrimitive("live")) },
                            ),
                        ),
                        completed = true,
                    ),
                )
            }
        }
    }

    private companion object {
        const val PROVIDER = "tool-result-provider"
        const val TOOL_NAME = "lookup"
        val LARGE_RESULT = "HEAD-" + "x".repeat(20_000) + "-TAIL"
    }
}
