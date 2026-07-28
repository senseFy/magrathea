package saien.magrathea.runtime

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import saien.magrathea.core.AgentEngineConfig
import saien.magrathea.core.AgentEvent
import saien.magrathea.core.AgentMessage
import saien.magrathea.core.AgentRequest
import saien.magrathea.core.AgentSessionId
import saien.magrathea.core.AgentSessionSnapshot
import saien.magrathea.core.AgentStateSnapshot
import saien.magrathea.core.AgentStatus
import saien.magrathea.core.FollowUpMessageProvider
import saien.magrathea.core.MessageRole
import saien.magrathea.core.ModelDescriptor
import saien.magrathea.core.RuntimeConfig
import saien.magrathea.core.SteeringMessageProvider
import saien.magrathea.core.TextPart
import saien.magrathea.core.ToolCallPart
import saien.magrathea.core.ToolDefinition
import saien.magrathea.core.ToolExecutionMode
import saien.magrathea.core.ToolExecutionRequest
import saien.magrathea.core.ToolExecutionResult
import saien.magrathea.core.ToolExecutor
import saien.magrathea.provider.api.InMemoryProviderRegistry
import saien.magrathea.provider.api.ProviderAdapter
import saien.magrathea.provider.api.ProviderChunk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ToolCallLimitContractTest {
    @Test
    fun executorLimitCannotBeLoosenedAndExcessParallelCallsFailClosed() = runTest {
        verifyLimit(ToolExecutionMode.PARALLEL)
    }

    @Test
    fun executorLimitCannotBeLoosenedAndExcessSequentialCallsFailClosed() = runTest {
        verifyLimit(ToolExecutionMode.SEQUENTIAL)
    }

    @Test
    fun advertisedLimitCanTightenTheRegisteredExecutorLimit() = runTest {
        verifyLimit(
            mode = ToolExecutionMode.PARALLEL,
            executorLimit = 3,
            advertisedLimit = 1,
        )
    }

    @Test
    fun toolCallBudgetResetsForEachModelTurn() = runTest {
        val tool = CountingLimitedTool(maxCallsPerTurn = 1)
        val provider = OneCallPerTurnProvider()
        val runner = DefaultAgentRunner(
            providerRegistry = InMemoryProviderRegistry(listOf(provider)),
            toolRegistry = InMemoryToolRegistry(listOf(tool)),
            sessionStore = InMemorySessionStore(),
            checkpointStore = InMemoryCheckpointStore(),
        )

        val events = runner.run(
            AgentRequest(
                sessionId = AgentSessionId("tool-limit-reset"),
                messages = listOf(
                    AgentMessage(
                        role = MessageRole.USER,
                        parts = listOf(TextPart("search twice")),
                    ),
                ),
                model = ModelDescriptor(provider.key, "model", supportsToolCalls = true),
                tools = listOf(tool.definition),
                engine = AgentEngineConfig(
                    runtime = RuntimeConfig(maxTurns = 3),
                ),
            ),
        ).toList()

        assertEquals(2, tool.calls)
        assertEquals(2, events.filterIsInstance<AgentEvent.ToolCompleted>().size)
        assertTrue(events.last() is AgentEvent.Completed)
    }

    @Test
    fun runBudgetStopsAdvertisingAnExhaustedToolOnLaterTurns() = runTest {
        val tool = CountingLimitedTool(maxCallsPerTurn = 1, maxCallsPerRun = 1)
        val provider = CallWhileAdvertisedProvider()
        val runner = DefaultAgentRunner(
            providerRegistry = InMemoryProviderRegistry(listOf(provider)),
            toolRegistry = InMemoryToolRegistry(listOf(tool)),
            sessionStore = InMemorySessionStore(),
            checkpointStore = InMemoryCheckpointStore(),
        )

        val events = runner.run(
            AgentRequest(
                sessionId = AgentSessionId("tool-run-limit"),
                messages = listOf(
                    AgentMessage(
                        role = MessageRole.USER,
                        parts = listOf(TextPart("search once")),
                    ),
                ),
                model = ModelDescriptor(provider.key, "model", supportsToolCalls = true),
                tools = listOf(tool.definition),
                engine = AgentEngineConfig(
                    runtime = RuntimeConfig(maxTurns = 3),
                ),
            ),
        ).toList()

        assertEquals(1, tool.calls)
        assertEquals(listOf(1, 0), provider.advertisedToolCounts)
        assertEquals(1, events.filterIsInstance<AgentEvent.ToolCompleted>().size)
        assertTrue(events.last() is AgentEvent.Completed)
    }

    @Test
    fun followUpUserMessageDoesNotResetTheRunBudget() = runTest {
        verifyInjectedUserMessageDoesNotResetRunBudget(
            followUpMessageProvider = FollowUpMessageProvider { context ->
                if (context.turn == 0) listOf(injectedUserMessage("follow-up")) else emptyList()
            },
        )
    }

    @Test
    fun steeringUserMessageDoesNotResetTheRunBudget() = runTest {
        verifyInjectedUserMessageDoesNotResetRunBudget(
            steeringMessageProvider = SteeringMessageProvider { context ->
                if (context.turn == 1) listOf(injectedUserMessage("steering")) else emptyList()
            },
        )
    }

    @Test
    fun resumePreservesThePersistedRunBudget() = runTest {
        val tool = CountingLimitedTool(maxCallsPerTurn = 1, maxCallsPerRun = 1)
        val provider = CallWhileAdvertisedProvider()
        val sessionStore = InMemorySessionStore()
        val sessionId = AgentSessionId("resumed-run-limit")
        val request = AgentRequest(
            sessionId = sessionId,
            messages = listOf(injectedUserMessage("search once")),
            model = ModelDescriptor(provider.key, "model", supportsToolCalls = true),
            tools = listOf(tool.definition),
            engine = AgentEngineConfig(runtime = RuntimeConfig(maxTurns = 3)),
        )
        sessionStore.saveSession(
            AgentSessionSnapshot(
                sessionId = sessionId,
                request = request,
                state = AgentStateSnapshot(
                    messages = request.messages,
                    toolCallCounts = mapOf("limited_tool" to 1),
                    turn = 1,
                    status = AgentStatus.RUNNING,
                ),
            ),
        )
        val runner = DefaultAgentRunner(
            providerRegistry = InMemoryProviderRegistry(listOf(provider)),
            toolRegistry = InMemoryToolRegistry(listOf(tool)),
            sessionStore = sessionStore,
            checkpointStore = InMemoryCheckpointStore(),
        )

        val events = runner.resume(sessionId).toList()

        assertEquals(0, tool.calls)
        assertEquals(listOf(0), provider.advertisedToolCounts)
        assertEquals(
            mapOf("limited_tool" to 1),
            events.filterIsInstance<AgentEvent.Completed>().single().state.toolCallCounts,
        )
    }

    private suspend fun verifyInjectedUserMessageDoesNotResetRunBudget(
        followUpMessageProvider: FollowUpMessageProvider = FollowUpMessageProvider { emptyList() },
        steeringMessageProvider: SteeringMessageProvider = SteeringMessageProvider { emptyList() },
    ) {
        val tool = CountingLimitedTool(maxCallsPerTurn = 1, maxCallsPerRun = 1)
        val provider = CallWhileAdvertisedProvider()
        val runner = DefaultAgentRunner(
            providerRegistry = InMemoryProviderRegistry(listOf(provider)),
            toolRegistry = InMemoryToolRegistry(listOf(tool)),
            sessionStore = InMemorySessionStore(),
            checkpointStore = InMemoryCheckpointStore(),
            followUpMessageProvider = followUpMessageProvider,
            steeringMessageProvider = steeringMessageProvider,
        )

        val events = runner.run(
            AgentRequest(
                sessionId = AgentSessionId("injected-user-run-limit"),
                messages = listOf(injectedUserMessage("search once")),
                model = ModelDescriptor(provider.key, "model", supportsToolCalls = true),
                tools = listOf(tool.definition),
                engine = AgentEngineConfig(runtime = RuntimeConfig(maxTurns = 3)),
            ),
        ).toList()

        val completed = events.filterIsInstance<AgentEvent.Completed>().single()
        assertEquals(1, tool.calls)
        assertEquals(listOf(1, 0), provider.advertisedToolCounts)
        assertEquals(mapOf("limited_tool" to 1), completed.state.toolCallCounts)
    }

    private suspend fun verifyLimit(
        mode: ToolExecutionMode,
        executorLimit: Int = 1,
        advertisedLimit: Int = 3,
    ) {
        val tool = CountingLimitedTool(executorLimit)
        val provider = ThreeCallsThenCompleteProvider()
        val runner = DefaultAgentRunner(
            providerRegistry = InMemoryProviderRegistry(listOf(provider)),
            toolRegistry = InMemoryToolRegistry(listOf(tool)),
            sessionStore = InMemorySessionStore(),
            checkpointStore = InMemoryCheckpointStore(),
        )
        val advertised = tool.definition.copy(maxCallsPerTurn = advertisedLimit)
        val events = runner.run(
            AgentRequest(
                sessionId = AgentSessionId("tool-limit-${mode.name}"),
                messages = listOf(AgentMessage(role = MessageRole.USER, parts = listOf(TextPart("search")))),
                model = ModelDescriptor(provider.key, "model", supportsToolCalls = true),
                tools = listOf(advertised),
                engine = AgentEngineConfig(
                    runtime = RuntimeConfig(maxTurns = 2, toolExecutionMode = mode),
                ),
            ),
        ).toList()

        val results = events.filterIsInstance<AgentEvent.ToolCompleted>().map { it.result }
        assertEquals(1, tool.calls)
        assertEquals(3, results.size)
        assertEquals(listOf(false, true, true), results.map { it.isError })
        assertEquals(listOf("executed", "Tool call limit exceeded", "Tool call limit exceeded"), results.map { it.displayText })
        assertTrue(events.last() is AgentEvent.Completed)
    }

    private class CountingLimitedTool(
        maxCallsPerTurn: Int,
        maxCallsPerRun: Int? = null,
    ) : ToolExecutor {
        override val definition = ToolDefinition(
            name = "limited_tool",
            description = "A bounded tool",
            schema = buildJsonObject { },
            maxCallsPerTurn = maxCallsPerTurn,
            maxCallsPerRun = maxCallsPerRun,
        )
        var calls = 0

        override suspend fun execute(request: ToolExecutionRequest): ToolExecutionResult {
            calls += 1
            return ToolExecutionResult(
                request.toolCall.toolCallId,
                request.toolCall.toolName,
                JsonPrimitive("executed"),
                displayText = "executed",
            )
        }
    }

    private class CallWhileAdvertisedProvider : ProviderAdapter {
        override val key = "call-while-advertised"
        val advertisedToolCounts = mutableListOf<Int>()

        override suspend fun generate(request: saien.magrathea.provider.api.ProviderRequest): Flow<ProviderChunk> = flow {
            advertisedToolCounts += request.tools.size
            if (request.tools.isEmpty()) {
                emit(providerChunk(text = "done", completed = true))
            } else {
                val call = ToolCallPart(
                    toolCallId = "call-${advertisedToolCounts.size}",
                    toolName = "limited_tool",
                    arguments = buildJsonObject { },
                )
                emit(
                    providerChunk(
                        toolCalls = listOf(call),
                        completed = true,
                        stopReason = saien.magrathea.core.StopReason.TOOL_CALLS,
                    ),
                )
            }
        }
    }

    private class ThreeCallsThenCompleteProvider : ProviderAdapter {
        override val key = "three-tool-calls"

        override suspend fun generate(request: saien.magrathea.provider.api.ProviderRequest): Flow<ProviderChunk> = flow {
            if (request.messages.lastOrNull()?.role == MessageRole.TOOL) {
                emit(providerChunk(text = "done", completed = true))
            } else {
                emit(
                    providerChunk(
                        toolCalls = (1..3).map { index ->
                            ToolCallPart(
                                toolCallId = "call-$index",
                                toolName = "limited_tool",
                                arguments = buildJsonObject { },
                            )
                        },
                        completed = true,
                    ),
                )
            }
        }
    }

    private class OneCallPerTurnProvider : ProviderAdapter {
        override val key = "one-tool-call-per-turn"
        private var calls = 0

        override suspend fun generate(
            request: saien.magrathea.provider.api.ProviderRequest,
        ): Flow<ProviderChunk> = flow {
            calls += 1
            if (calls <= 2) {
                emit(
                    providerChunk(
                        toolCalls = listOf(
                            ToolCallPart(
                                toolCallId = "turn-call-$calls",
                                toolName = "limited_tool",
                                arguments = buildJsonObject { },
                            ),
                        ),
                        completed = true,
                    ),
                )
            } else {
                emit(providerChunk(text = "done", completed = true))
            }
        }
    }

    private fun injectedUserMessage(text: String) = AgentMessage(
        role = MessageRole.USER,
        parts = listOf(TextPart(text)),
    )
}
