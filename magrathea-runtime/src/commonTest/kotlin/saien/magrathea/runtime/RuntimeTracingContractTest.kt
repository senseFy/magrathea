package saien.magrathea.runtime

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import saien.magrathea.core.AgentCheckpoint
import saien.magrathea.core.AgentEvent
import saien.magrathea.core.AgentFailureCode
import saien.magrathea.core.AgentMessage
import saien.magrathea.core.AgentPersistence
import saien.magrathea.core.AgentPersistenceRecord
import saien.magrathea.core.AgentRequest
import saien.magrathea.core.AgentSessionId
import saien.magrathea.core.AgentSessionSnapshot
import saien.magrathea.core.MagratheaTraceSpan
import saien.magrathea.core.MagratheaTracer
import saien.magrathea.core.MessageRole
import saien.magrathea.core.ModelDescriptor
import saien.magrathea.core.MonotonicClock
import saien.magrathea.core.RetryPolicy
import saien.magrathea.core.TextPart
import saien.magrathea.core.ToolCallPart
import saien.magrathea.core.ToolDefinition
import saien.magrathea.core.ToolExecutionRequest
import saien.magrathea.core.ToolExecutionResult
import saien.magrathea.core.ToolExecutor
import saien.magrathea.core.TraceContext
import saien.magrathea.core.TraceSpanData
import saien.magrathea.core.TraceStatus
import saien.magrathea.core.TraceValue
import saien.magrathea.core.currentMagratheaTraceContext
import saien.magrathea.core.withMagratheaTraceContext
import saien.magrathea.provider.api.InMemoryProviderRegistry
import saien.magrathea.provider.api.ProviderAdapter
import saien.magrathea.provider.api.ProviderChunk
import saien.magrathea.provider.api.ProviderNetworkException
import saien.magrathea.provider.api.ProviderRateLimitException
import saien.magrathea.provider.api.ProviderRequest
import saien.magrathea.provider.api.ProviderUsage

class RuntimeTracingContractTest {
    @Test
    fun tracingBuildsOneContentFreeExecutionTreeAcrossRetry() = runTest {
        val canary = "trace-content-canary"
        val sink = RecordingTraceSink()
        val provider = FailThenCompleteProvider(canary)
        val runner = runner(
            provider = provider,
            sink = sink,
            retryPolicy = RetryOncePolicy(),
        )

        val events = runner.run(request(provider.key, canary)).toList()

        assertTrue(events.last() is AgentEvent.Completed)
        val execution = sink.spans.singleNamed(RuntimeTraceNames.AGENT_EXECUTION)
        val turn = sink.spans.singleNamed(RuntimeTraceNames.AGENT_TURN)
        val context = sink.spans.singleNamed(RuntimeTraceNames.CONTEXT_PREPARE)
        val providers = sink.spans.named(RuntimeTraceNames.PROVIDER_REQUEST)
        assertEquals(execution.context.spanId, turn.parentSpanId)
        assertEquals(turn.context.spanId, context.parentSpanId)
        assertEquals(2, providers.size)
        assertTrue(providers.all { it.parentSpanId == turn.context.spanId })
        assertTrue(providers.all {
            it.stringAttribute("magrathea.agent.run_id") ==
                execution.stringAttribute("magrathea.agent.run_id")
        })
        assertTrue(sink.spans.all { it.context.traceId == execution.context.traceId })
        assertEquals(listOf(TraceStatus.ERROR, TraceStatus.OK), providers.map { it.status })
        assertEquals(listOf(false, true), providers.map {
            it.booleanAttribute("magrathea.provider.event_observed")
        })
        val milestones = providers.last().events
        assertTrue(milestones.single { it.name == RuntimeTraceEvents.PROVIDER_FIRST_EVENT }.offsetMillis <=
            milestones.single { it.name == "magrathea.provider.first_text" }.offsetMillis)
        assertEquals(5L, providers.last().longAttribute("magrathea.usage.input_tokens"))
        assertEquals(2L, providers.last().longAttribute("magrathea.usage.output_tokens"))
        assertEquals(5L, execution.longAttribute("magrathea.usage.input_tokens"))
        assertEquals(2L, execution.longAttribute("magrathea.usage.output_tokens"))
        assertEquals(
            1,
            turn.events.count { it.name == RuntimeTraceEvents.PROVIDER_RETRY_SCHEDULED },
        )
        assertEquals(TraceStatus.OK, execution.status)
        assertEquals("completed", execution.stringAttribute("magrathea.outcome"))
        assertFalse(sink.spans.toString().contains(canary))
        assertTrue(sink.spans.all { it.durationMillis >= 0 })
    }

    @Test
    fun hostContextParentsTheColdExecutionFlow() = runTest {
        val sink = RecordingTraceSink()
        val provider = CompleteProvider("parented-provider")
        val flow = runner(provider, sink).run(request(provider.key, "safe"))
        val parent = TraceContext("host-trace", "host-span")

        assertTrue(sink.spans.isEmpty())
        withMagratheaTraceContext(parent) { flow.toList() }

        val execution = sink.spans.singleNamed(RuntimeTraceNames.AGENT_EXECUTION)
        assertEquals("host-trace", execution.context.traceId)
        assertEquals("host-span", execution.parentSpanId)
    }

    @Test
    fun providerCanAddAChildWithTheSharedTracer() = runTest {
        val sink = RecordingTraceSink()
        val tracer = sink.tracer(monotonicClock = IncrementingClock())
        val provider = ChildTracingProvider(tracer)
        val runner = DefaultAgentRunner(
            providerRegistry = InMemoryProviderRegistry(listOf(provider)),
            toolRegistry = InMemoryToolRegistry(),
            persistence = InMemoryAgentPersistence(),
            tracer = tracer,
        )

        runner.run(request(provider.key, "safe")).toList()

        val providerSpan = sink.spans.singleNamed(RuntimeTraceNames.PROVIDER_REQUEST)
        val adapterSpan = sink.spans.singleNamed("adapter.request")
        assertEquals(providerSpan.context.spanId, adapterSpan.parentSpanId)
        assertEquals(providerSpan.context.traceId, adapterSpan.context.traceId)
    }

    @Test
    fun toolSpanHasIdentityAndOutcomeWithoutPayload() = runTest {
        val canary = "tool-trace-canary"
        val tool = CanaryTool("tool-contract", canary)
        val provider = ToolThenCompleteProvider(tool.definition.name)
        val sink = RecordingTraceSink()

        val events = runner(provider, sink, tools = listOf(tool)).run(
            request(provider.key, canary).copy(tools = listOf(tool.definition)),
        ).toList()

        assertTrue(events.last() is AgentEvent.Completed)
        val toolSpan = sink.spans.singleNamed(RuntimeTraceNames.TOOL_CALL)
        val turnIds = sink.spans.named(RuntimeTraceNames.AGENT_TURN).map { it.context.spanId }
        assertTrue(toolSpan.parentSpanId in turnIds)
        assertEquals("tool-contract", toolSpan.stringAttribute("magrathea.tool.name"))
        assertEquals(true, toolSpan.booleanAttribute("magrathea.tool.executor_started"))
        assertEquals(false, toolSpan.booleanAttribute("magrathea.tool.result_error"))
        assertEquals(TraceStatus.OK, toolSpan.status)
        assertFalse(sink.spans.toString().contains(canary))
    }

    @Test
    fun throwingTracerCannotChangeAgentResult() = runTest {
        val provider = CompleteProvider("throwing-tracer")
        val tracer = object : MagratheaTracer {
            override fun startSpan(
                name: String,
                parent: TraceContext?,
                attributes: Map<String, TraceValue>,
            ): MagratheaTraceSpan {
                error("tracer failed")
            }
        }
        val runner = DefaultAgentRunner(
            providerRegistry = InMemoryProviderRegistry(listOf(provider)),
            toolRegistry = InMemoryToolRegistry(),
            persistence = InMemoryAgentPersistence(),
            tracer = tracer,
        )

        val events = runner.run(request(provider.key, "safe")).toList()

        assertTrue(events.last() is AgentEvent.Completed)
        assertTrue(events.none { it is AgentEvent.Failed })
    }

    @Test
    fun cancellationClosesProviderExecutionAndControlSpans() = runTest {
        val provider = HangingProvider()
        val sink = RecordingTraceSink()
        val runner = runner(provider, sink)
        val collector = launch {
            try {
                runner.run(request(provider.key, "cancel-canary")).collect { }
            } catch (_: CancellationException) {
            }
        }
        provider.started.await()

        runner.cancel(REQUEST_SESSION_ID)
        withTimeout(2_000) { collector.join() }

        val providerSpan = sink.spans.singleNamed(RuntimeTraceNames.PROVIDER_REQUEST)
        val execution = sink.spans.singleNamed(RuntimeTraceNames.AGENT_EXECUTION)
        val control = sink.spans.singleNamed(RuntimeTraceNames.AGENT_CONTROL)
        assertEquals("cancelled", providerSpan.stringAttribute("magrathea.outcome"))
        assertEquals("cancelled", execution.stringAttribute("magrathea.outcome"))
        assertEquals("cancel", control.stringAttribute("magrathea.agent.operation"))
        assertFalse(sink.spans.toString().contains("cancel-canary"))
    }

    @Test
    fun recoverableProviderFailureEndsExecutionAsInterrupted() = runTest {
        val sink = RecordingTraceSink()
        val provider = AlwaysFailingNetworkProvider()

        val events = runner(provider, sink)
            .run(request(provider.key, "interruption-canary"))
            .toList()

        assertTrue(events.last() is AgentEvent.Interrupted)
        val providerSpan = sink.spans.singleNamed(RuntimeTraceNames.PROVIDER_REQUEST)
        assertEquals(TraceStatus.ERROR, providerSpan.status)
        assertEquals(
            AgentFailureCode.PROVIDER_NETWORK.name,
            providerSpan.stringAttribute("magrathea.error.code"),
        )
        assertEquals(false, providerSpan.booleanAttribute("magrathea.provider.event_observed"))
        val execution = sink.spans.singleNamed(RuntimeTraceNames.AGENT_EXECUTION)
        assertEquals(TraceStatus.UNSET, execution.status)
        assertEquals("interrupted", execution.stringAttribute("magrathea.outcome"))
        assertFalse(sink.spans.toString().contains("interruption-canary"))
    }

    @Test
    fun resumeCreatesAnotherExecutionAndDoesNotDoubleCountProviderUsage() = runTest {
        val sink = RecordingTraceSink()
        val persistence = InMemoryAgentPersistence()
        val provider = MeteredInterruptionThenCompleteProvider()
        val runner = DefaultAgentRunner(
            providerRegistry = InMemoryProviderRegistry(listOf(provider)),
            toolRegistry = InMemoryToolRegistry(),
            persistence = persistence,
            tracer = sink.tracer(monotonicClock = IncrementingClock()),
        )

        runner.run(request(provider.key, "metered-recovery")).toList()
        runner.resume(REQUEST_SESSION_ID).toList()

        val providerSpans = sink.spans.named(RuntimeTraceNames.PROVIDER_REQUEST)
        assertEquals(listOf(7L, 7L), providerSpans.map {
            it.longAttribute("magrathea.usage.input_tokens")
        })
        assertEquals(listOf(2L, 3L), providerSpans.map {
            it.longAttribute("magrathea.usage.output_tokens")
        })
        val executions = sink.spans.named(RuntimeTraceNames.AGENT_EXECUTION)
        assertEquals(2, executions.size)
        assertEquals(listOf("interrupted", "completed"), executions.map {
            it.stringAttribute("magrathea.outcome")
        })
        assertEquals(listOf(false, true), executions.map {
            it.booleanAttribute("magrathea.agent.resumed")
        })
        assertEquals(listOf(7L, 7L), executions.map {
            it.longAttribute("magrathea.usage.input_tokens")
        })
        assertEquals(listOf(2L, 3L), executions.map {
            it.longAttribute("magrathea.usage.output_tokens")
        })
        assertEquals(1, executions.mapNotNull {
            it.stringAttribute("magrathea.agent.run_id")
        }.distinct().size)
    }

    @Test
    fun missingResumeTracesSuccessfulLoadAndTypedExecutionFailure() = runTest {
        val sink = RecordingTraceSink()
        val runner = DefaultAgentRunner(
            providerRegistry = InMemoryProviderRegistry(),
            toolRegistry = InMemoryToolRegistry(),
            persistence = InMemoryAgentPersistence(),
            tracer = sink.tracer(monotonicClock = IncrementingClock()),
        )
        val missingId = AgentSessionId("missing-trace-session")

        val events = runner.resume(missingId).toList()

        assertEquals(AgentFailureCode.NOT_FOUND, events.filterIsInstance<AgentEvent.Failed>().single().code)
        val store = sink.spans.singleNamed(RuntimeTraceNames.STORE_OPERATION)
        assertEquals("load", store.stringAttribute("magrathea.store.operation"))
        assertEquals(TraceStatus.OK, store.status)
        val execution = sink.spans.singleNamed(RuntimeTraceNames.AGENT_EXECUTION)
        assertEquals(TraceStatus.ERROR, execution.status)
        assertEquals(
            AgentFailureCode.NOT_FOUND.name,
            execution.stringAttribute("magrathea.error.code"),
        )
    }

    @Test
    fun interruptBeforeInitialLoadCompletesTracesInterruptedWithRunIdentity() = runTest {
        val sink = RecordingTraceSink()
        val provider = CompleteProvider("preflight-interrupt-provider")
        val persistence = BlockingFirstLoadPersistence()
        val runner = DefaultAgentRunner(
            providerRegistry = InMemoryProviderRegistry(listOf(provider)),
            toolRegistry = InMemoryToolRegistry(),
            persistence = persistence,
            tracer = sink.tracer(monotonicClock = IncrementingClock()),
        )
        val collector = launch {
            try {
                runner.run(request(provider.key, "safe")).collect { }
            } catch (_: CancellationException) {
            }
        }
        persistence.firstLoadStarted.await()

        val recovery = runner.interrupt(REQUEST_SESSION_ID)
        withTimeout(2_000) { collector.join() }

        val runId = requireNotNull(recovery.runId).value
        val execution = sink.spans.singleNamed(RuntimeTraceNames.AGENT_EXECUTION)
        val control = sink.spans.singleNamed(RuntimeTraceNames.AGENT_CONTROL)
        assertEquals("interrupted", execution.stringAttribute("magrathea.outcome"))
        assertEquals(runId, execution.stringAttribute("magrathea.agent.run_id"))
        assertEquals(runId, control.stringAttribute("magrathea.agent.run_id"))
    }

    @Test
    fun failureSummariesBelongToEachPhysicalAttempt() = runTest {
        val sink = RecordingTraceSink()
        val provider = AlwaysRateLimitedProvider()
        val runner = runner(
            provider = provider,
            sink = sink,
            retryPolicy = RetryOncePolicy(),
        )

        runner.run(request(provider.key, "safe")).toList()

        val attempts = sink.spans.filter { it.name == RuntimeTraceNames.PROVIDER_REQUEST }
        assertEquals(listOf(0L, 1L), attempts.map { it.longAttribute("magrathea.provider.attempt") })
        attempts.forEach { span ->
            val failure = span.events.single { it.name == "magrathea.provider.failure" }
            assertEquals(TraceValue.StringValue("rate_limit"), failure.attributes["type"])
            assertEquals(TraceValue.LongValue(429), failure.attributes["http_status"])
            assertEquals(TraceValue.BooleanValue(true), failure.attributes["retryable"])
            assertEquals(TraceStatus.ERROR, span.status)
        }
    }

    private fun runner(
        provider: ProviderAdapter,
        sink: RecordingTraceSink,
        retryPolicy: RetryPolicy = saien.magrathea.core.NoopRetryPolicy,
        tools: List<ToolExecutor> = emptyList(),
    ) = DefaultAgentRunner(
        providerRegistry = InMemoryProviderRegistry(listOf(provider)),
        toolRegistry = InMemoryToolRegistry(tools),
        persistence = InMemoryAgentPersistence(),
        retryPolicy = retryPolicy,
        tracer = sink.tracer(monotonicClock = IncrementingClock()),
    )

    private fun request(provider: String, content: String) = AgentRequest(
        sessionId = REQUEST_SESSION_ID,
        messages = listOf(AgentMessage(role = MessageRole.USER, parts = listOf(TextPart(content)))),
        model = ModelDescriptor(provider = provider, model = "tracing-contract"),
    )

    private class IncrementingClock : MonotonicClock {
        private var value = 0L

        override fun nowMillis(): Long = value++
    }

    private class RetryOncePolicy : RetryPolicy {
        private var decisions = 0

        override suspend fun shouldRetry(attempt: Int, error: Throwable): Boolean = decisions++ == 0

        override suspend fun backoffDelayMs(attempt: Int, error: Throwable): Long = 0L
    }

    private class FailThenCompleteProvider(
        private val canary: String,
    ) : ProviderAdapter {
        override val key: String = "trace-retry-provider"
        private var calls = 0

        override suspend fun generate(request: ProviderRequest): Flow<ProviderChunk> = flow {
            if (calls++ == 0) throw ProviderNetworkException(canary)
            emit(
                providerChunk(
                    text = canary,
                    completed = true,
                    usage = ProviderUsage(inputTokens = 5, outputTokens = 2),
                ),
            )
        }
    }

    private class CompleteProvider(
        override val key: String,
    ) : ProviderAdapter {
        override suspend fun generate(request: ProviderRequest): Flow<ProviderChunk> = flow {
            emit(providerChunk(text = "done", completed = true))
        }
    }

    private class ChildTracingProvider(
        private val tracer: MagratheaTracer,
    ) : ProviderAdapter {
        override val key: String = "child-tracing-provider"

        override suspend fun generate(request: ProviderRequest): Flow<ProviderChunk> = flow {
            val span = tracer.startSpan(
                name = "adapter.request",
                parent = currentMagratheaTraceContext(),
            )
            span.end(TraceStatus.OK)
            emit(providerChunk(text = "done", completed = true))
        }
    }

    private class AlwaysFailingNetworkProvider : ProviderAdapter {
        override val key: String = "trace-network-interruption-provider"

        override suspend fun generate(request: ProviderRequest): Flow<ProviderChunk> = flow {
            throw ProviderNetworkException("network-diagnostic-canary")
        }
    }

    private class AlwaysRateLimitedProvider : ProviderAdapter {
        override val key: String = "trace-rate-limit-provider"

        override suspend fun generate(request: ProviderRequest): Flow<ProviderChunk> = flow {
            throw ProviderRateLimitException("rate limited")
        }
    }

    private class BlockingFirstLoadPersistence : AgentPersistence {
        private val delegate = InMemoryAgentPersistence()
        private val loadMutex = Mutex()
        private var blockFirstLoad = true
        val firstLoadStarted = CompletableDeferred<Unit>()

        override suspend fun commit(snapshot: AgentSessionSnapshot, checkpoint: AgentCheckpoint?) {
            delegate.commit(snapshot, checkpoint)
        }

        override suspend fun load(sessionId: AgentSessionId): AgentPersistenceRecord? {
            val shouldBlock = loadMutex.withLock {
                blockFirstLoad.also { blockFirstLoad = false }
            }
            if (shouldBlock) {
                firstLoadStarted.complete(Unit)
                awaitCancellation()
            }
            return delegate.load(sessionId)
        }

        override suspend fun listSessions(): List<AgentSessionSnapshot> = delegate.listSessions()

        override suspend fun deleteSession(sessionId: AgentSessionId) = delegate.deleteSession(sessionId)

        override suspend fun clear() = delegate.clear()
    }

    private class MeteredInterruptionThenCompleteProvider : ProviderAdapter {
        override val key: String = "trace-metered-recovery-provider"
        private var calls = 0

        override suspend fun generate(request: ProviderRequest): Flow<ProviderChunk> = flow {
            if (calls++ == 0) {
                emit(
                    providerChunk(
                        text = "partial",
                        usage = ProviderUsage(inputTokens = 7, outputTokens = 2),
                    ),
                )
                throw ProviderNetworkException("connection lost after metered output")
            }
            emit(
                providerChunk(
                    text = "done",
                    completed = true,
                    usage = ProviderUsage(inputTokens = 7, outputTokens = 3),
                ),
            )
        }
    }

    private class ToolThenCompleteProvider(
        private val toolName: String,
    ) : ProviderAdapter {
        override val key: String = "trace-tool-provider"

        override suspend fun generate(request: ProviderRequest): Flow<ProviderChunk> = flow {
            if (request.messages.lastOrNull()?.role == MessageRole.TOOL) {
                emit(providerChunk(text = "done", completed = true))
            } else {
                emit(
                    providerChunk(
                        toolCalls = listOf(
                            ToolCallPart(
                                toolCallId = "call-1",
                                toolName = toolName,
                                arguments = buildJsonObject { },
                            ),
                        ),
                        completed = true,
                    ),
                )
            }
        }
    }

    private class CanaryTool(
        name: String,
        private val canary: String,
    ) : ToolExecutor {
        override val definition = ToolDefinition(
            name = name,
            description = "Tracing contract tool",
            schema = buildJsonObject { },
        )

        override suspend fun execute(request: ToolExecutionRequest): ToolExecutionResult =
            ToolExecutionResult(
                toolCallId = request.toolCall.toolCallId,
                toolName = request.toolCall.toolName,
                result = JsonPrimitive(canary),
            )
    }

    private class HangingProvider : ProviderAdapter {
        override val key: String = "trace-hanging-provider"
        val started = CompletableDeferred<Unit>()

        override suspend fun generate(request: ProviderRequest): Flow<ProviderChunk> = flow {
            started.complete(Unit)
            awaitCancellation()
        }
    }

    private fun List<TraceSpanData>.named(name: String): List<TraceSpanData> =
        filter { it.name == name }

    private fun List<TraceSpanData>.singleNamed(name: String): TraceSpanData =
        named(name).single()

    private companion object {
        val REQUEST_SESSION_ID = AgentSessionId("trace-session")
    }
}
