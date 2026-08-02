package saien.magrathea.runtime

import kotlin.coroutines.coroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import saien.magrathea.core.AgentEngineConfig
import saien.magrathea.core.AgentEvent
import saien.magrathea.core.AgentFailureCode
import saien.magrathea.core.AgentMessage
import saien.magrathea.core.AgentRequest
import saien.magrathea.core.MessageRole
import saien.magrathea.core.ModelDescriptor
import saien.magrathea.core.ProviderConfig
import saien.magrathea.core.ProviderInterruption
import saien.magrathea.core.ProviderInterruptionPhase
import saien.magrathea.core.ProviderTimeoutConfig
import saien.magrathea.core.RetryPolicy
import saien.magrathea.core.RuntimeConfig
import saien.magrathea.core.TextPart
import saien.magrathea.core.ToolCallPart
import saien.magrathea.core.ToolDefinition
import saien.magrathea.core.ToolExecutionRequest
import saien.magrathea.core.ToolExecutionResult
import saien.magrathea.core.ToolExecutor
import saien.magrathea.provider.api.InMemoryProviderRegistry
import saien.magrathea.provider.api.ProviderAdapter
import saien.magrathea.provider.api.ProviderCancellationIntent
import saien.magrathea.provider.api.ProviderChunk
import saien.magrathea.provider.api.ProviderInvocationResumeMode
import saien.magrathea.provider.api.ProviderRequest
import saien.magrathea.provider.api.providerCancellationIntent

class RuntimeTimeoutContractTest {
    @Test
    fun firstProviderEventHasAnIndependentDeadline() = runTest {
        val provider = DelayedProvider("first-event") {
            delay(101)
            emit(providerChunk(text = "late", completed = true))
        }

        val configured = providerTimeouts(first = 100)
        val events = runner(provider, StandardTestDispatcher(testScheduler))
            .run(request(provider.key, configured))
            .toList()

        assertEquals(AgentFailureCode.TIMEOUT, events.singleInterruption().code)
        assertEquals(ProviderInterruptionPhase.BEFORE_FIRST_EVENT, events.singleInterruption().phase)
        assertEquals(configured, provider.requests.single().timeouts)
    }

    @Test
    fun idleDeadlineStartsAgainAfterEveryCanonicalChunk() = runTest {
        val provider = DelayedProvider("stream-idle") {
            emit(providerChunk(text = "partial"))
            delay(101)
            emit(providerChunk(text = "late", completed = true))
        }

        val events = runner(provider, StandardTestDispatcher(testScheduler))
            .run(request(provider.key, providerTimeouts(idle = 100)))
            .toList()

        assertEquals(AgentFailureCode.TIMEOUT, events.singleInterruption().code)
        assertEquals(ProviderInterruptionPhase.AFTER_FIRST_EVENT, events.singleInterruption().phase)
        assertTrue(events.filterIsInstance<AgentEvent.MessageEmitted>().isNotEmpty())
        val interrupted = events.filterIsInstance<AgentEvent.Interrupted>().single()
        assertTrue(
            interrupted.state.messages
                .flatMap(AgentMessage::parts)
                .filterIsInstance<TextPart>()
                .any { it.text == "partial" },
        )
    }

    @Test
    fun providerCallDeadlineBoundsAnOtherwiseHealthyStream() = runTest {
        val provider = DelayedProvider("provider-call") {
            repeat(5) { index ->
                emit(providerChunk(text = index.toString()))
                delay(80)
            }
            emit(providerChunk(text = "done", completed = true))
        }

        val events = runner(provider, StandardTestDispatcher(testScheduler))
            .run(request(provider.key, providerTimeouts(first = 100, idle = 100, call = 250)))
            .toList()

        assertEquals(AgentFailureCode.TIMEOUT, events.singleInterruption().code)
        assertEquals(ProviderInterruptionPhase.AFTER_FIRST_EVENT, events.singleInterruption().phase)
    }

    @Test
    fun firstEventDeadlineInterruptsAReattachInvocationAndRetriesTheSameIdentity() = runTest {
        val provider = ReattachDeadlineProvider(DeadlineScenario.FIRST_EVENT)
        val runner = runner(
            provider = provider,
            dispatcher = StandardTestDispatcher(testScheduler),
            retryPolicy = RetryOncePolicy,
        )

        val events = runner.run(
            request(
                provider = provider.key,
                timeouts = providerTimeouts(first = 100),
                runtime = RuntimeConfig(maxProviderRetries = 1),
            ),
        ).toList()

        assertTrue(events.any { it is AgentEvent.Completed })
        assertEquals(listOf(ProviderCancellationIntent.INTERRUPT), provider.cancellationIntents)
        assertEquals(2, provider.requestIds.size)
        assertEquals(provider.requestIds[0], provider.requestIds[1])
    }

    @Test
    fun idleDeadlineInterruptsAReattachInvocationAndResumeUsesTheSameIdentity() = runTest {
        val provider = ReattachDeadlineProvider(DeadlineScenario.STREAM_IDLE)
        val persistence = InMemoryAgentPersistence()
        val runner = runner(
            provider = provider,
            dispatcher = StandardTestDispatcher(testScheduler),
            persistence = persistence,
        )
        val agentRequest = request(
            provider = provider.key,
            timeouts = providerTimeouts(idle = 100),
        )

        val interrupted = runner.run(agentRequest).toList()
        val resumed = runner.resume(agentRequest.sessionId).toList()

        assertEquals(ProviderInterruptionPhase.AFTER_FIRST_EVENT, interrupted.singleInterruption().phase)
        assertTrue(resumed.any { it is AgentEvent.Completed })
        assertEquals(listOf(ProviderCancellationIntent.INTERRUPT), provider.cancellationIntents)
        assertEquals(2, provider.requestIds.size)
        assertEquals(provider.requestIds[0], provider.requestIds[1])
    }

    @Test
    fun callDeadlineInterruptsAReattachInvocationAndResumeUsesTheSameIdentity() = runTest {
        val provider = ReattachDeadlineProvider(DeadlineScenario.PROVIDER_CALL)
        val persistence = InMemoryAgentPersistence()
        val runner = runner(
            provider = provider,
            dispatcher = StandardTestDispatcher(testScheduler),
            persistence = persistence,
        )
        val agentRequest = request(
            provider = provider.key,
            timeouts = providerTimeouts(first = 100, idle = 100, call = 250),
        )

        val interrupted = runner.run(agentRequest).toList()
        val resumed = runner.resume(agentRequest.sessionId).toList()

        assertEquals(ProviderInterruptionPhase.AFTER_FIRST_EVENT, interrupted.singleInterruption().phase)
        assertTrue(resumed.any { it is AgentEvent.Completed })
        assertEquals(listOf(ProviderCancellationIntent.INTERRUPT), provider.cancellationIntents)
        assertEquals(2, provider.requestIds.size)
        assertEquals(provider.requestIds[0], provider.requestIds[1])
    }

    @Test
    fun runDeadlineBoundsAllProviderAndToolTurns() = runTest {
        val provider = DelayedProvider("agent-run") {
            delay(101)
            emit(providerChunk(text = "late", completed = true))
        }
        val runtime = RuntimeConfig(
            maxProviderRetries = 0,
            defaultToolTimeoutMillis = 100,
            runTimeoutMillis = 100,
        )

        val events = runner(provider, StandardTestDispatcher(testScheduler))
            .run(
                request(
                    provider.key,
                    providerTimeouts(first = 500, idle = 500, call = 1_000),
                    runtime,
                ),
            )
            .toList()

        assertEquals(AgentFailureCode.TIMEOUT, events.singleFailure())
        assertFalse(events.any { it is AgentEvent.Cancelled })
    }

    @Test
    fun runDeadlineRemainsAuthoritativeWhileAToolIsExecuting() = runTest {
        val provider = ToolThenDoneProvider()
        val tool = SlowTool(timeoutMillis = 500)
        val events = runner(provider, StandardTestDispatcher(testScheduler), tool)
            .run(
                request(
                    provider.key,
                    providerTimeouts(),
                    RuntimeConfig(
                        maxProviderRetries = 0,
                        defaultToolTimeoutMillis = 500,
                        runTimeoutMillis = 100,
                    ),
                ),
            )
            .toList()

        assertEquals(AgentFailureCode.TIMEOUT, events.singleFailure())
        assertFalse(events.any { it is AgentEvent.Cancelled })
        assertFalse(events.any { it is AgentEvent.ToolCompleted })
    }

    @Test
    fun toolUsesRuntimeDefaultUnlessItsDefinitionOverridesIt() = runTest {
        suspend fun execute(toolTimeout: Long?): ToolExecutionResult {
            val tool = SlowTool(toolTimeout)
            val provider = ToolThenDoneProvider()
            val events = runner(provider, StandardTestDispatcher(testScheduler), tool)
                .run(
                    request(
                        provider.key,
                        providerTimeouts(),
                        RuntimeConfig(
                            maxProviderRetries = 0,
                            defaultToolTimeoutMillis = 100,
                            runTimeoutMillis = 1_000,
                        ),
                    ),
                )
                .toList()
            assertTrue(events.any { it is AgentEvent.Completed })
            return events.filterIsInstance<AgentEvent.ToolCompleted>().single().result
        }

        assertTrue(execute(toolTimeout = null).isError)
        assertFalse(execute(toolTimeout = 200).isError)
    }

    private fun runner(
        provider: ProviderAdapter,
        dispatcher: CoroutineDispatcher,
        tool: ToolExecutor? = null,
        persistence: InMemoryAgentPersistence = InMemoryAgentPersistence(),
        retryPolicy: RetryPolicy = saien.magrathea.core.NoopRetryPolicy,
    ) = DefaultAgentRunner(
        providerRegistry = InMemoryProviderRegistry(listOf(provider)),
        toolRegistry = InMemoryToolRegistry(listOfNotNull(tool)),
        persistence = persistence,
        retryPolicy = retryPolicy,
        dispatcher = dispatcher,
    )

    private fun request(
        provider: String,
        timeouts: ProviderTimeoutConfig,
        runtime: RuntimeConfig = RuntimeConfig(maxProviderRetries = 0),
    ) = AgentRequest(
        messages = listOf(AgentMessage(role = MessageRole.USER, parts = listOf(TextPart("hello")))),
        model = ModelDescriptor(provider = provider, model = "timeout-contract", supportsToolCalls = true),
        engine = AgentEngineConfig(
            provider = ProviderConfig(timeouts = timeouts),
            runtime = runtime,
        ),
        tools = listOf(SlowTool.DEFINITION),
    )

    private fun providerTimeouts(
        first: Long = 500,
        idle: Long = 500,
        call: Long = 1_000,
    ) = ProviderTimeoutConfig(
        connectTimeoutMillis = 50,
        firstEventTimeoutMillis = first,
        streamIdleTimeoutMillis = idle,
        callTimeoutMillis = call,
    )

    private fun List<AgentEvent>.singleFailure(): AgentFailureCode =
        filterIsInstance<AgentEvent.Failed>().single().code

    private fun List<AgentEvent>.singleInterruption(): ProviderInterruption =
        requireNotNull(filterIsInstance<AgentEvent.Interrupted>().single().interruption.provider)

    private class DelayedProvider(
        override val key: String,
        private val response: suspend kotlinx.coroutines.flow.FlowCollector<ProviderChunk>.() -> Unit,
    ) : ProviderAdapter {
        val requests = mutableListOf<ProviderRequest>()

        override suspend fun generate(request: ProviderRequest): Flow<ProviderChunk> {
            requests += request
            return flow(response)
        }
    }

    private enum class DeadlineScenario {
        FIRST_EVENT,
        STREAM_IDLE,
        PROVIDER_CALL,
    }

    private class ReattachDeadlineProvider(
        private val scenario: DeadlineScenario,
    ) : ProviderAdapter {
        override val key: String = "reattach-${scenario.name.lowercase()}"
        override val invocationResumeMode: ProviderInvocationResumeMode =
            ProviderInvocationResumeMode.REATTACH
        val requestIds = mutableListOf<String>()
        val cancellationIntents = mutableListOf<ProviderCancellationIntent>()

        override suspend fun generate(request: ProviderRequest): Flow<ProviderChunk> = flow {
            requestIds += requireNotNull(request.invocation).requestId
            if (requestIds.size > 1) {
                emit(providerChunk(text = "done", completed = true))
                return@flow
            }
            try {
                when (scenario) {
                    DeadlineScenario.FIRST_EVENT -> awaitCancellation()
                    DeadlineScenario.STREAM_IDLE -> {
                        emit(providerChunk(text = "partial"))
                        awaitCancellation()
                    }
                    DeadlineScenario.PROVIDER_CALL -> {
                        while (true) {
                            emit(providerChunk(text = "partial"))
                            delay(80)
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                cancellationIntents += coroutineContext.providerCancellationIntent()
                throw cancelled
            }
        }
    }

    private object RetryOncePolicy : RetryPolicy {
        override suspend fun shouldRetry(attempt: Int, error: Throwable): Boolean = attempt == 1
    }

    private class SlowTool(timeoutMillis: Long?) : ToolExecutor {
        override val definition: ToolDefinition = DEFINITION.copy(timeoutMs = timeoutMillis)

        override suspend fun execute(request: ToolExecutionRequest): ToolExecutionResult {
            delay(150)
            return ToolExecutionResult(
                toolCallId = request.toolCall.toolCallId,
                toolName = request.toolCall.toolName,
                result = JsonPrimitive("done"),
            )
        }

        companion object {
            val DEFINITION = ToolDefinition(
                name = "slow_tool",
                description = "Timeout contract Tool",
                schema = buildJsonObject { },
            )
        }
    }

    private class ToolThenDoneProvider : ProviderAdapter {
        override val key: String = "tool-then-done"

        override suspend fun generate(request: ProviderRequest): Flow<ProviderChunk> = flow {
            if (request.messages.lastOrNull()?.role == MessageRole.TOOL) {
                emit(providerChunk(text = "done", completed = true))
            } else {
                emit(
                    providerChunk(
                        toolCalls = listOf(
                            ToolCallPart(
                                toolCallId = "slow-call",
                                toolName = SlowTool.DEFINITION.name,
                                arguments = buildJsonObject { },
                            ),
                        ),
                        completed = true,
                    ),
                )
            }
        }
    }
}
