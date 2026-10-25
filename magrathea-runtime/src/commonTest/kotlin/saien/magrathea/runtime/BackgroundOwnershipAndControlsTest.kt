@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package saien.magrathea.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.ComparableTimeMark
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import saien.magrathea.core.AgentEngineConfig
import saien.magrathea.core.AgentEvent
import saien.magrathea.core.AgentInterceptor
import saien.magrathea.core.AgentMessage
import saien.magrathea.core.AgentRequest
import saien.magrathea.core.AgentRunId
import saien.magrathea.core.AgentSessionId
import saien.magrathea.core.AgentStatus
import saien.magrathea.core.BackgroundExecutionConfig
import saien.magrathea.core.BackgroundExecutionRecord
import saien.magrathea.core.BackgroundExecutionState
import saien.magrathea.core.BackgroundExecutionStore
import saien.magrathea.core.BackgroundOutcome
import saien.magrathea.core.BackgroundSessionRecords
import saien.magrathea.core.MessageRole
import saien.magrathea.core.ModelDescriptor
import saien.magrathea.core.RuntimeConfig
import saien.magrathea.core.TextPart
import saien.magrathea.core.ToolBackgroundPolicy
import saien.magrathea.core.ToolCallPart
import saien.magrathea.core.ToolDefinition
import saien.magrathea.core.ToolExecutionRequest
import saien.magrathea.core.ToolExecutionResult
import saien.magrathea.core.ToolExecutor
import saien.magrathea.core.ToolRecoveryPolicy
import saien.magrathea.core.ToolResultPart
import saien.magrathea.core.ToolRuntimeContext
import saien.magrathea.provider.api.InMemoryProviderRegistry
import saien.magrathea.provider.api.ProviderAdapter
import saien.magrathea.provider.api.ProviderChunk
import saien.magrathea.provider.api.ProviderRequest

/** Composed lifecycle transitions and untrusted model input, rather than individual happy paths. */
class BackgroundOwnershipAndControlsTest {
    @Test
    fun stopAllCancelsAnInterruptedRunAndClearsItsRecoveryCheckpoint() = runTest {
        val store = InMemoryBackgroundExecutionStore()
        val executor = executor(store)
        val sessionId = AgentSessionId("stop-interrupted-run")
        val toolStarted = CompletableDeferred<Unit>()
        val allowToolResult = CompletableDeferred<Unit>()
        val tool = object : ToolExecutor {
            override val definition = definition("lookup")
            override val backgroundPolicy = ToolBackgroundPolicy.ELIGIBLE
            override val recoveryPolicy = ToolRecoveryPolicy.REPLAY_SAFE
            override suspend fun execute(request: ToolExecutionRequest): ToolExecutionResult {
                toolStarted.complete(Unit)
                allowToolResult.await()
                return ToolExecutionResult(
                    toolCallId = request.toolCall.toolCallId,
                    toolName = request.toolCall.toolName,
                    result = JsonPrimitive("result"),
                )
            }
        }
        val persistence = InMemoryAgentPersistence()
        val runner = DefaultAgentRunner(
            providerRegistry = InMemoryProviderRegistry(listOf(provider(call("lookup-1", "lookup")))),
            toolRegistry = InMemoryToolRegistry(listOf(tool)),
            persistence = persistence,
            dispatcher = StandardTestDispatcher(testScheduler),
            background = executor,
            timeSource = VirtualTimeSource(testScheduler),
        )
        val manager = DefaultAgentSessionManager(
            runner = runner,
            persistence = persistence,
            dispatcher = StandardTestDispatcher(testScheduler),
            background = executor,
        )
        try {
            val lease = manager.create(sessionId)
            val initial = request(sessionId, listOf(tool.definition))
            lease.start(initial.copy(engine = initial.engine.copy(runtime = initial.engine.runtime.copy(
                background = initial.engine.runtime.background!!.copy(handoffWaitMillis = 60_000),
            ))))
            runCurrent()
            assertTrue(toolStarted.isCompleted)
            lease.interrupt()
            assertEquals(AgentSessionPhase.RESUMABLE, lease.state.value.phase)
            assertNotNull(persistence.load(sessionId)?.checkpoint)

            lease.stopAll()
            assertEquals(AgentSessionPhase.TERMINAL, lease.state.value.phase)
            assertEquals(AgentStatus.CANCELLED, lease.state.value.state?.status)
            val persisted = assertNotNull(persistence.load(sessionId))
            assertEquals(AgentStatus.CANCELLED, persisted.snapshot.state.status)
            assertNull(persisted.checkpoint)
            assertEquals(
                AgentSessionErrorCode.INVALID_STATE,
                assertFailsWith<AgentSessionException> { lease.resume() }.code,
            )
            // Stop remains an idempotent no-op once both Run and background work are closed.
            lease.stopAll()
            assertEquals(AgentStatus.CANCELLED, lease.state.value.state?.status)
        } finally {
            allowToolResult.complete(Unit)
            manager.close()
        }
    }

    @Test
    fun stopAllAlsoCancelsWorkAdmittedWhileItsFirstSweepIsNotifying() = runTest {
        val backing = InMemoryBackgroundExecutionStore()
        val stopRefreshEntered = CompletableDeferred<Unit>()
        val allowStopRefresh = CompletableDeferred<Unit>()
        var pauseStoppedRead = false
        val store = object : BackgroundExecutionStore by backing {
            override suspend fun load(sessionId: AgentSessionId): BackgroundSessionRecords {
                val records = backing.load(sessionId)
                if (pauseStoppedRead && records.records.any { it.outcome == BackgroundOutcome.CANCELLED }) {
                    pauseStoppedRead = false
                    stopRefreshEntered.complete(Unit)
                    allowStopRefresh.await()
                }
                return backing.load(sessionId)
            }
        }
        val executor = executor(store)
        val sessionId = AgentSessionId("stop-admission-race")
        val admissionEntered = CompletableDeferred<Unit>()
        val allowAdmission = CompletableDeferred<Unit>()
        val allowToolResult = CompletableDeferred<Unit>()
        val tool = object : ToolExecutor {
            override val definition = definition("lookup")
            override val backgroundPolicy = ToolBackgroundPolicy.ELIGIBLE
            override suspend fun execute(request: ToolExecutionRequest): ToolExecutionResult {
                allowToolResult.await()
                return ToolExecutionResult(
                    toolCallId = request.toolCall.toolCallId,
                    toolName = request.toolCall.toolName,
                    result = JsonPrimitive("late result"),
                )
            }
        }
        val interceptor = object : AgentInterceptor {
            override suspend fun beforeToolCall(context: ToolRuntimeContext): ToolRuntimeContext {
                admissionEntered.complete(Unit)
                allowAdmission.await()
                return context
            }
        }
        val persistence = InMemoryAgentPersistence()
        val runner = DefaultAgentRunner(
            providerRegistry = InMemoryProviderRegistry(listOf(provider(call("lookup-2", "lookup")))),
            toolRegistry = InMemoryToolRegistry(listOf(tool)),
            persistence = persistence,
            interceptors = listOf(interceptor),
            dispatcher = StandardTestDispatcher(testScheduler),
            background = executor,
            timeSource = VirtualTimeSource(testScheduler),
        )
        val manager = DefaultAgentSessionManager(
            runner = runner,
            persistence = persistence,
            dispatcher = StandardTestDispatcher(testScheduler),
            background = executor,
        )
        try {
            val lease = manager.create(sessionId)
            lease.start(request(sessionId, listOf(tool.definition)))
            runCurrent()
            assertTrue(admissionEntered.isCompleted)
            // Existing background work makes stop's first sweep publish a settlement.
            executor.putRecord(queuedRecord(sessionId))
            pauseStoppedRead = true
            val stopping = async { lease.stopAll() }
            runCurrent()
            assertTrue(stopRefreshEntered.isCompleted)

            // The foreground Run can still admit and hand off another call while stop's
            // first settlement callback is suspended outside the executor's Session mutex.
            allowAdmission.complete(Unit)
            advanceTimeBy(20)
            runCurrent()
            assertEquals(2, backing.load(sessionId).records.size)
            assertEquals(
                BackgroundExecutionState.RUNNING,
                backing.load(sessionId).records.single { it.originCallId == "lookup-2" }.state,
            )
            allowStopRefresh.complete(Unit)
            stopping.await()
            runCurrent()
            val stopped = backing.load(sessionId)
            assertTrue(stopped.records.all {
                it.state == BackgroundExecutionState.TERMINAL &&
                    it.outcome == BackgroundOutcome.CANCELLED && it.activationSuppressed
            }, "stopAll must include work handed off during its first sweep")
            allowToolResult.complete(Unit)
            runCurrent()
            assertEquals(stopped, backing.load(sessionId))
        } finally {
            allowStopRefresh.complete(Unit)
            allowAdmission.complete(Unit)
            allowToolResult.complete(Unit)
            manager.close()
        }
    }

    @Test
    fun suspendedExecutionCannotSettleWithoutAResume() = runTest {
        checkRetiredFinalizer(Fence.SUSPEND)
    }

    @Test
    fun retiredFinalizerCannotCrossStopAfterResume() = runTest {
        checkRetiredFinalizer(Fence.STOP)
    }

    @Test
    fun retiredFinalizerCannotCrossTargetedCancelAfterResume() = runTest {
        checkRetiredFinalizer(Fence.CANCEL)
    }

    @Test
    fun retiredFinalizerCannotRecreateDeletedSessionAfterResume() = runTest {
        checkRetiredFinalizer(Fence.DELETE)
    }

    @Test
    fun retiredFinalizerCannotOverwriteTheReplacementResult() = runTest {
        checkRetiredFinalizer(Fence.REPLACEMENT_RESULT)
    }

    private enum class Fence { SUSPEND, STOP, CANCEL, DELETE, REPLACEMENT_RESULT }

    private suspend fun TestScope.checkRetiredFinalizer(fence: Fence) {
        val store = InMemoryBackgroundExecutionStore()
        val executor = executor(store)
        val sessionId = AgentSessionId("owner-${fence.name}")
        val finalizerEntered = CompletableDeferred<Unit>()
        val oldFinalizer = CompletableDeferred<Unit>()
        val replacementExecution = CompletableDeferred<Unit>()
        var starts = 0
        val tool = object : ToolExecutor {
            override val definition = definition("lookup")
            override val backgroundPolicy = ToolBackgroundPolicy.ELIGIBLE
            override val recoveryPolicy = ToolRecoveryPolicy.REPLAY_SAFE
            override suspend fun execute(request: ToolExecutionRequest): ToolExecutionResult {
                val attempt = ++starts
                if (attempt == 1) delay(50) else replacementExecution.await()
                return ToolExecutionResult(
                    toolCallId = request.toolCall.toolCallId,
                    toolName = request.toolCall.toolName,
                    result = JsonPrimitive(if (attempt == 1) "old-result" else "new-result"),
                )
            }
        }
        val interceptor = object : AgentInterceptor {
            override suspend fun afterToolCall(
                context: ToolRuntimeContext,
                result: ToolExecutionResult,
            ): ToolExecutionResult {
                if (result.result == JsonPrimitive("old-result")) {
                    finalizerEntered.complete(Unit)
                    oldFinalizer.await()
                }
                return result
            }
        }
        val persistence = InMemoryAgentPersistence()
        val runner = DefaultAgentRunner(
            providerRegistry = InMemoryProviderRegistry(listOf(provider(call("lookup-1", "lookup")))),
            toolRegistry = InMemoryToolRegistry(listOf(tool)),
            persistence = persistence,
            interceptors = listOf(interceptor),
            dispatcher = StandardTestDispatcher(testScheduler),
            background = executor,
            timeSource = VirtualTimeSource(testScheduler),
        )
        val manager = DefaultAgentSessionManager(
            runner = runner,
            persistence = persistence,
            dispatcher = StandardTestDispatcher(testScheduler),
            background = executor,
        )
        try {
            val lease = manager.create(sessionId)
            lease.start(request(sessionId, listOf(tool.definition)))
            advanceTimeBy(100)
            runCurrent()
            assertTrue(finalizerEntered.isCompleted)
            lease.suspendBackground()
            assertEquals(BackgroundExecutionState.QUEUED, store.load(sessionId).records.single().state)
            if (fence != Fence.SUSPEND) {
                lease.resumeBackground()
                // Repeated resume cannot install a second owner for the same durable execution.
                lease.resumeBackground()
                runCurrent()
                assertEquals(2, starts)
            }
            when (fence) {
                Fence.SUSPEND -> Unit
                Fence.STOP -> lease.stopAll()
                Fence.CANCEL -> lease.cancelBackground(listOf(store.load(sessionId).records.single().handle))
                Fence.DELETE -> manager.delete(sessionId)
                Fence.REPLACEMENT_RESULT -> replacementExecution.complete(Unit)
            }
            runCurrent()
            val expected = store.load(sessionId)
            when (fence) {
                Fence.SUSPEND -> assertEquals(BackgroundExecutionState.QUEUED, expected.records.single().state)
                Fence.STOP, Fence.CANCEL -> {
                    assertEquals(BackgroundOutcome.CANCELLED, expected.records.single().outcome)
                    assertTrue(expected.records.single().activationSuppressed)
                }
                Fence.DELETE -> assertTrue(expected.records.isEmpty())
                Fence.REPLACEMENT_RESULT -> assertEquals(
                    JsonPrimitive("new-result"), expected.records.single().result?.result,
                )
            }
            oldFinalizer.complete(Unit)
            runCurrent()
            assertEquals(expected, store.load(sessionId), "A retired owner must never commit again")
        } finally {
            oldFinalizer.complete(Unit)
            replacementExecution.complete(Unit)
            manager.close()
        }
    }

    @Test
    fun malformedControlsReturnPairedErrorsWithoutFailingTheRunOrCancellingWork() = runTest {
        val invalidArguments = listOf(
            "read_background_result" to "{}",
            "read_background_result" to """{"handle":{}}""",
            "read_background_result" to """{"handle":42}""",
            "read_background_result" to """{"handle":null}""",
            "read_background_result" to """{"handle":" "}""",
            "await_background_results" to "{}",
            "await_background_results" to """{"handles":"bg_keep"}""",
            "await_background_results" to """{"handles":null}""",
            "await_background_results" to """{"handles":[{}]}""",
            "await_background_results" to """{"handles":[false]}""",
            "await_background_results" to """{"handles":[""]}""",
            "await_background_results" to """{"handles":["bg_keep"],"max_wait_ms":-1}""",
            "await_background_results" to """{"handles":["bg_keep"],"max_wait_ms":"5"}""",
            "await_background_results" to """{"handles":["bg_keep"],"max_wait_ms":0.5}""",
            "await_background_results" to """{"handles":["bg_keep"],"max_wait_ms":null}""",
            "await_background_results" to """{"handles":["bg_keep"],"max_wait_ms":{}}""",
            "await_background_results" to """{"handles":["bg_keep"],"max_wait_ms":9223372036854775808}""",
            "cancel_background_executions" to "{}",
            "cancel_background_executions" to """{"handles":"bg_keep"}""",
            "cancel_background_executions" to """{"handles":["bg_keep",{}]}""",
        )
        val calls = invalidArguments.mapIndexed { index, (name, arguments) ->
            call("invalid-$index", name, arguments)
        }
        val store = InMemoryBackgroundExecutionStore()
        val executor = executor(store)
        val sessionId = AgentSessionId("invalid-controls")
        executor.register(sessionId, NoopListener)
        val preserved = queuedRecord(sessionId)
        executor.putRecord(preserved)
        try {
            val runner = DefaultAgentRunner(
                providerRegistry = InMemoryProviderRegistry(listOf(provider(*calls.toTypedArray()))),
                toolRegistry = InMemoryToolRegistry(),
                persistence = InMemoryAgentPersistence(),
                background = executor,
                dispatcher = StandardTestDispatcher(testScheduler),
            )
            val events = runner.run(request(sessionId)).toList()
            assertTrue(events.none { it is AgentEvent.Failed })
            val completed = events.filterIsInstance<AgentEvent.Completed>().single()
            val results = completed.state.messages.flatMap { it.parts.filterIsInstance<ToolResultPart>() }
            assertEquals(calls.map { it.toolCallId }, results.map { it.toolCallId })
            assertTrue(results.all { it.isError })
            assertTrue(results.all {
                it.metadata[BackgroundToolInstructions.CONTROL_RESULT_METADATA_KEY] == JsonPrimitive(true)
            })
            assertEquals(0L, completed.state.explicitWaitMillis)
            assertEquals(preserved, store.load(sessionId).records.single())
        } finally {
            executor.close()
        }
    }

    @Test
    fun validAwaitAcceptsOmittedAndZeroWaitLimits() = runTest {
        val store = InMemoryBackgroundExecutionStore()
        val executor = executor(store)
        val sessionId = AgentSessionId("valid-controls")
        executor.register(sessionId, NoopListener)
        val calls = arrayOf(
            call("await-1", "await_background_results", """{"handles":["unknown"]}"""),
            call("await-2", "await_background_results", """{"handles":[],"max_wait_ms":0}"""),
        )
        try {
            val runner = DefaultAgentRunner(
                providerRegistry = InMemoryProviderRegistry(listOf(provider(*calls))),
                toolRegistry = InMemoryToolRegistry(),
                persistence = InMemoryAgentPersistence(),
                background = executor,
                dispatcher = StandardTestDispatcher(testScheduler),
            )
            val completed = runner.run(request(sessionId)).toList().filterIsInstance<AgentEvent.Completed>().single()
            val results = completed.state.messages.flatMap { it.parts.filterIsInstance<ToolResultPart>() }
            assertEquals(2, results.size)
            assertTrue(results.all { !it.isError })
        } finally {
            executor.close()
        }
    }

    private fun TestScope.executor(store: BackgroundExecutionStore) = BackgroundToolExecutor(
        store = store,
        dispatcher = StandardTestDispatcher(testScheduler),
        timeSource = VirtualTimeSource(testScheduler),
        epochClock = { testScheduler.currentTime },
    )

    private fun provider(vararg calls: ToolCallPart) = object : ProviderAdapter {
        override val key = "ownership-controls"
        private var first = true
        override suspend fun generate(request: ProviderRequest): Flow<ProviderChunk> = flow {
            if (first) {
                first = false
                emit(providerChunk(toolCalls = calls.toList(), completed = true))
            } else {
                emit(providerChunk(text = "done", completed = true))
            }
        }
    }

    private fun request(sessionId: AgentSessionId, tools: List<ToolDefinition> = emptyList()) = AgentRequest(
        sessionId = sessionId,
        model = ModelDescriptor(provider = "ownership-controls", model = "test"),
        messages = listOf(AgentMessage(role = MessageRole.USER, parts = listOf(TextPart("look up")))),
        tools = tools,
        engine = AgentEngineConfig(runtime = RuntimeConfig(
            maxTurns = 6,
            background = BackgroundExecutionConfig(10, 10_000, 4, 60_000),
        )),
    )

    private fun definition(name: String) = ToolDefinition(
        name = name,
        description = "test tool",
        schema = buildJsonObject { put("type", "object") },
    )

    private fun call(id: String, name: String, arguments: String = "{}") = ToolCallPart(
        toolCallId = id,
        toolName = name,
        arguments = Json.parseToJsonElement(arguments).jsonObject,
    )

    private fun queuedRecord(sessionId: AgentSessionId) = BackgroundExecutionRecord(
        sessionId = sessionId,
        executionId = "execution-keep",
        handle = "bg_keep",
        originRunId = AgentRunId("origin"),
        originCallId = "lookup-1",
        toolName = "lookup",
        sourceId = "source",
        state = BackgroundExecutionState.QUEUED,
        executionRequest = ToolExecutionRequest(
            sessionId = sessionId,
            runId = AgentRunId("origin"),
            executionId = "execution-keep",
            assistantMessage = AgentMessage(role = MessageRole.ASSISTANT, parts = emptyList()),
            toolCall = call("lookup-1", "lookup"),
        ),
        effectiveTimeoutMillis = 30_000,
        queuedAtEpochMs = 0,
    )

    private class VirtualTimeSource(private val scheduler: TestCoroutineScheduler) : TimeSource.WithComparableMarks {
        override fun markNow(): ComparableTimeMark = Mark(scheduler.currentTime.milliseconds, scheduler)
        private class Mark(private val at: Duration, private val scheduler: TestCoroutineScheduler) : ComparableTimeMark {
            override fun elapsedNow(): Duration = scheduler.currentTime.milliseconds - at
            override fun plus(duration: Duration): ComparableTimeMark = Mark(at + duration, scheduler)
            override fun minus(duration: Duration): ComparableTimeMark = Mark(at - duration, scheduler)
            override fun minus(other: ComparableTimeMark): Duration = at - (other as Mark).at
            override fun compareTo(other: ComparableTimeMark): Int = at.compareTo((other as Mark).at)
            override fun equals(other: Any?): Boolean = other is Mark && at == other.at && scheduler == other.scheduler
            override fun hashCode(): Int = at.hashCode()
        }
    }

    private object NoopListener : BackgroundToolExecutor.SessionListener {
        override suspend fun onRecordChanged(sessionId: AgentSessionId, record: BackgroundExecutionRecord) = Unit
        override suspend fun onSettled(sessionId: AgentSessionId, record: BackgroundExecutionRecord) = Unit
    }
}
