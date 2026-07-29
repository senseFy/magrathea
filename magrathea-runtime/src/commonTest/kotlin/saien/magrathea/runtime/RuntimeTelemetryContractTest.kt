package saien.magrathea.runtime

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import saien.magrathea.core.AgentEvent
import saien.magrathea.core.AgentMessage
import saien.magrathea.core.AgentRequest
import saien.magrathea.core.AgentFailureCode
import saien.magrathea.core.AgentSessionId
import saien.magrathea.core.MessageRole
import saien.magrathea.core.ModelDescriptor
import saien.magrathea.core.MonotonicClock
import saien.magrathea.core.RetryPolicy
import saien.magrathea.core.TelemetryEvent
import saien.magrathea.core.TelemetryOutcome
import saien.magrathea.core.TelemetryStoreOperation
import saien.magrathea.core.TextPart
import saien.magrathea.core.ToolCallPart
import saien.magrathea.core.ToolDefinition
import saien.magrathea.core.ToolExecutionRequest
import saien.magrathea.core.ToolExecutionResult
import saien.magrathea.core.ToolExecutor
import saien.magrathea.provider.api.InMemoryProviderRegistry
import saien.magrathea.provider.api.ProviderAdapter
import saien.magrathea.provider.api.ProviderChunk
import saien.magrathea.provider.api.ProviderNetworkException
import saien.magrathea.provider.api.ProviderRequest
import saien.magrathea.provider.api.ProviderUsage

class RuntimeTelemetryContractTest {
    @Test
    fun telemetryCoversLifecycleRetryLatencyUsageAndStoreWithoutContent() = runTest {
        val canary = "telemetry-content-canary"
        val telemetry = RecordingTelemetry()
        val provider = FailThenCompleteProvider(canary)
        val retryPolicy = RetryOncePolicy()
        val runner = runner(
            provider = provider,
            telemetry = telemetry,
            retryPolicy = retryPolicy,
        )

        val events = runner.run(request(provider.key, canary)).toList()

        assertTrue(events.last() is AgentEvent.Completed)
        assertEquals(1, telemetry.events.filterIsInstance<TelemetryEvent.SessionStarted>().size)
        assertEquals(1, telemetry.events.filterIsInstance<TelemetryEvent.TurnStarted>().size)
        assertEquals(2, telemetry.events.filterIsInstance<TelemetryEvent.ProviderRequestStarted>().size)
        assertEquals(1, telemetry.events.filterIsInstance<TelemetryEvent.ProviderFirstChunk>().size)
        assertEquals(
            listOf(TelemetryOutcome.FAILURE, TelemetryOutcome.SUCCESS),
            telemetry.events.filterIsInstance<TelemetryEvent.ProviderRequestFinished>().map { it.outcome },
        )
        assertEquals(1, telemetry.events.filterIsInstance<TelemetryEvent.RetryScheduled>().size)
        assertTrue(
            telemetry.events.filterIsInstance<TelemetryEvent.StoreOperationFinished>().any {
                it.operation == TelemetryStoreOperation.COMMIT_STATE
            },
        )
        val finished = telemetry.events.filterIsInstance<TelemetryEvent.SessionFinished>().single()
        assertEquals(TelemetryOutcome.SUCCESS, finished.outcome)
        assertEquals(5L, finished.usage.inputTokens)
        assertEquals(2L, finished.usage.outputTokens)
        assertTrue(telemetry.events.toString().contains(canary).not())
        assertTrue(telemetry.events.all { event -> event.durationOrNull()?.let { it >= 0L } != false })
    }

    @Test
    fun toolTelemetryContainsDurationAndOutcomeButNoToolContent() = runTest {
        val canary = "tool-telemetry-canary"
        val tool = CanaryTool("tool-$canary", canary)
        val provider = ToolThenCompleteProvider(tool.definition.name, canary)
        val telemetry = RecordingTelemetry()
        val runner = runner(provider, telemetry, tools = listOf(tool))

        val events = runner.run(
            request(provider.key, canary).copy(tools = listOf(tool.definition)),
        ).toList()

        assertTrue(events.last() is AgentEvent.Completed)
        val toolEvent = telemetry.events.filterIsInstance<TelemetryEvent.ToolExecutionFinished>().single()
        assertEquals(TelemetryOutcome.SUCCESS, toolEvent.outcome)
        assertFalse(toolEvent.isError)
        assertFalse(telemetry.events.toString().contains(canary))
    }

    @Test
    fun throwingTelemetrySinkCannotChangeChatbotResult() = runTest {
        val provider = CompleteProvider("throwing-telemetry")
        val runner = DefaultAgentRunner(
            providerRegistry = InMemoryProviderRegistry(listOf(provider)),
            toolRegistry = InMemoryToolRegistry(),
            persistence = InMemoryAgentPersistence(),
            telemetry = { error("telemetry sink failed") },
            monotonicClock = IncrementingClock(),
        )

        val events = runner.run(request(provider.key, "safe")).toList()

        assertTrue(events.last() is AgentEvent.Completed)
        assertTrue(events.none { it is AgentEvent.Failed })
    }

    @Test
    fun cancellationProducesOnlyTypedCancellationTelemetry() = runTest {
        val provider = HangingProvider()
        val telemetry = RecordingTelemetry()
        val runner = runner(provider, telemetry)
        val collector = launch {
            try {
                runner.run(request(provider.key, "cancel-content-canary")).collect { }
            } catch (_: CancellationException) {
            }
        }
        provider.started.await()

        runner.cancel(REQUEST_SESSION_ID)
        withTimeout(2_000) { collector.join() }

        assertEquals(
            TelemetryOutcome.CANCELLED,
            telemetry.events.filterIsInstance<TelemetryEvent.ProviderRequestFinished>().single().outcome,
        )
        assertEquals(
            TelemetryOutcome.CANCELLED,
            telemetry.events.filterIsInstance<TelemetryEvent.SessionFinished>().single().outcome,
        )
        assertFalse(telemetry.events.toString().contains("cancel-content-canary"))
    }

    @Test
    fun missingResumeRecordsLoadDurationAndTypedTerminalFailure() = runTest {
        val telemetry = RecordingTelemetry()
        val runner = DefaultAgentRunner(
            providerRegistry = InMemoryProviderRegistry(),
            toolRegistry = InMemoryToolRegistry(),
            persistence = InMemoryAgentPersistence(),
            telemetry = telemetry,
            monotonicClock = IncrementingClock(),
        )
        val missingId = AgentSessionId("missing-telemetry-session")

        val events = runner.resume(missingId).toList()

        assertEquals(AgentFailureCode.NOT_FOUND, events.filterIsInstance<AgentEvent.Failed>().single().code)
        val store = telemetry.events.filterIsInstance<TelemetryEvent.StoreOperationFinished>().single()
        assertEquals(TelemetryStoreOperation.LOAD_STATE, store.operation)
        assertEquals(TelemetryOutcome.SUCCESS, store.outcome)
        val finished = telemetry.events.filterIsInstance<TelemetryEvent.SessionFinished>().single()
        assertEquals(TelemetryOutcome.FAILURE, finished.outcome)
        assertEquals(AgentFailureCode.NOT_FOUND, finished.failureCode)
    }

    private fun runner(
        provider: ProviderAdapter,
        telemetry: RecordingTelemetry,
        retryPolicy: RetryPolicy = saien.magrathea.core.NoopRetryPolicy,
        tools: List<ToolExecutor> = emptyList(),
    ) = DefaultAgentRunner(
        providerRegistry = InMemoryProviderRegistry(listOf(provider)),
        toolRegistry = InMemoryToolRegistry(tools),
        persistence = InMemoryAgentPersistence(),
        retryPolicy = retryPolicy,
        telemetry = telemetry,
        monotonicClock = IncrementingClock(),
    )

    private fun request(provider: String, content: String) = AgentRequest(
        sessionId = REQUEST_SESSION_ID,
        messages = listOf(AgentMessage(role = MessageRole.USER, parts = listOf(TextPart(content)))),
        model = ModelDescriptor(provider = provider, model = "telemetry-contract"),
    )

    private class RecordingTelemetry : saien.magrathea.core.MagratheaTelemetry {
        val events = mutableListOf<TelemetryEvent>()
        override fun record(event: TelemetryEvent) {
            events += event
        }
    }

    private class IncrementingClock : MonotonicClock {
        private var value = 0L
        override fun nowMillis(): Long = value++
    }

    private class RetryOncePolicy : RetryPolicy {
        private var decisions = 0
        override suspend fun shouldRetry(attempt: Int, error: Throwable): Boolean = decisions++ == 0
        override suspend fun backoffDelayMs(attempt: Int): Long = 0L
    }

    private class FailThenCompleteProvider(
        private val canary: String,
    ) : ProviderAdapter {
        override val key: String = "telemetry-retry-provider"
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

    private class ToolThenCompleteProvider(
        private val toolName: String,
        private val canary: String,
    ) : ProviderAdapter {
        override val key: String = "telemetry-tool-provider"

        override suspend fun generate(request: ProviderRequest): Flow<ProviderChunk> = flow {
            if (request.messages.lastOrNull()?.role == MessageRole.TOOL) {
                emit(providerChunk(text = "done", completed = true))
            } else {
                emit(
                    providerChunk(
                        toolCalls = listOf(
                            ToolCallPart(
                                toolCallId = "call-$canary",
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
            description = "Telemetry contract tool",
            schema = buildJsonObject { },
        )

        override suspend fun execute(request: ToolExecutionRequest): ToolExecutionResult = ToolExecutionResult(
            toolCallId = request.toolCall.toolCallId,
            toolName = request.toolCall.toolName,
            result = JsonPrimitive(canary),
        )
    }

    private class HangingProvider : ProviderAdapter {
        override val key: String = "telemetry-hanging-provider"
        val started = CompletableDeferred<Unit>()

        override suspend fun generate(request: ProviderRequest): Flow<ProviderChunk> = flow {
            started.complete(Unit)
            awaitCancellation()
        }
    }

    private fun TelemetryEvent.durationOrNull(): Long? = when (this) {
        is TelemetryEvent.ProviderFirstChunk -> latencyMillis
        is TelemetryEvent.ProviderRequestFinished -> durationMillis
        is TelemetryEvent.ToolExecutionFinished -> durationMillis
        is TelemetryEvent.StoreOperationFinished -> durationMillis
        else -> null
    }

    private companion object {
        val REQUEST_SESSION_ID = saien.magrathea.core.AgentSessionId("telemetry-session")
    }
}
