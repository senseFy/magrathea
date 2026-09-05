package saien.magrathea.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import saien.magrathea.core.AgentCheckpoint
import saien.magrathea.core.AgentEvent
import saien.magrathea.core.AgentInterruption
import saien.magrathea.core.AgentInterruptionReason
import saien.magrathea.core.AgentMessage
import saien.magrathea.core.AgentPersistence
import saien.magrathea.core.AgentPersistenceRecord
import saien.magrathea.core.AgentRecoveryDisposition
import saien.magrathea.core.AgentRecoveryInfo
import saien.magrathea.core.AgentRequest
import saien.magrathea.core.AgentResumeCursor
import saien.magrathea.core.AgentResumePhase
import saien.magrathea.core.AgentRunId
import saien.magrathea.core.AgentRunner
import saien.magrathea.core.AgentSessionId
import saien.magrathea.core.AgentSessionSnapshot
import saien.magrathea.core.AgentStateSnapshot
import saien.magrathea.core.AgentStatus
import saien.magrathea.core.MagratheaTraceSpan
import saien.magrathea.core.MagratheaTracer
import saien.magrathea.core.MessageRole
import saien.magrathea.core.ModelDescriptor
import saien.magrathea.core.NoopMagratheaTraceSpan
import saien.magrathea.core.StopReason
import saien.magrathea.core.TextPart
import saien.magrathea.core.TraceContext
import saien.magrathea.core.TraceValue
import saien.magrathea.provider.api.InMemoryProviderRegistry
import saien.magrathea.provider.api.ProviderAdapter
import saien.magrathea.provider.api.ProviderChunk
import saien.magrathea.provider.api.ProviderRequest

@OptIn(ExperimentalCoroutinesApi::class)
class AgentSessionManagerCancellationContractTest {
    @Test
    fun tracingCancellationBeforeAdmissionRestoresNewSessionAndAllowsRetry() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val persistence = InMemoryAgentPersistence()
        var providerCalls = 0
        val provider = object : ProviderAdapter {
            override val key = "test"

            override suspend fun generate(request: ProviderRequest): Flow<ProviderChunk> = flow {
                providerCalls += 1
                emit(providerChunk(text = "completed", completed = true))
            }
        }
        var cancelNextExecution = true
        val runner = DefaultAgentRunner(
            providerRegistry = InMemoryProviderRegistry(listOf(provider)),
            toolRegistry = InMemoryToolRegistry(),
            persistence = persistence,
            dispatcher = dispatcher,
            tracer = object : MagratheaTracer {
                override fun startSpan(
                    name: String,
                    parent: TraceContext?,
                    attributes: Map<String, TraceValue>,
                ): MagratheaTraceSpan {
                    if (name == RuntimeTraceNames.AGENT_EXECUTION && cancelNextExecution) {
                        cancelNextExecution = false
                        throw CancellationException("tracer cancelled before admission")
                    }
                    return NoopMagratheaTraceSpan
                }
            },
        )
        val manager = DefaultAgentSessionManager(runner, persistence, dispatcher = dispatcher)
        val request = request("cancelled-tracing")
        try {
            val lease = manager.create(request.sessionId)
            lease.start(request)
            lease.awaitIdle()

            assertEquals(AgentSessionPhase.NEW, lease.state.value.phase)
            assertNull(lease.state.value.lastEvent)
            assertNull(persistence.load(request.sessionId))
            assertEquals(0, providerCalls)

            lease.start(request)
            lease.awaitIdle()
            assertEquals(AgentStatus.COMPLETED, lease.state.value.state?.status)
            assertEquals(1, providerCalls)
            lease.release()
        } finally {
            manager.close()
        }
    }

    @Test
    fun cancellationWithoutARecordReleasesUnownedSession() = runTest {
        val runner = CancellationRunner(request("cancelled-unowned"))
        val manager = DefaultAgentSessionManager(
            runner, InMemoryAgentPersistence(), dispatcher = StandardTestDispatcher(testScheduler),
        )
        try {
            val lease = manager.create(runner.request.sessionId)
            lease.start(runner.request)
            runner.cancelExecution.complete(Unit)
            lease.awaitIdle()
            lease.release()
            runCurrent()

            assertTrue(manager.liveSessionIds.value.isEmpty())
        } finally {
            manager.close()
        }
    }

    @Test
    fun cancellationWithMatchingCheckpointRestoresResumableState() = runTest {
        val persistence = InMemoryAgentPersistence()
        val runner = CancellationRunner(request("cancelled-checkpoint"), persistence)
        val manager = DefaultAgentSessionManager(
            runner, persistence, dispatcher = StandardTestDispatcher(testScheduler),
        )
        try {
            val lease = manager.create(runner.request.sessionId)
            lease.start(runner.request)
            persistence.commit(
                runner.snapshot,
                AgentCheckpoint(
                    sessionId = runner.request.sessionId,
                    runId = runner.runId,
                    cursor = AgentResumeCursor(0, AgentResumePhase.TURN_PREPARING),
                    state = runner.snapshot.state,
                ),
            )
            runner.cancelExecution.complete(Unit)
            lease.awaitIdle()

            assertEquals(AgentSessionPhase.RESUMABLE, lease.state.value.phase)
            assertEquals(runner.runId, lease.state.value.runId)
            assertFalse(lease.state.value.lastEvent is AgentEvent.Failed)
            lease.resume()
            lease.awaitIdle()
            assertEquals(AgentSessionPhase.TERMINAL, lease.state.value.phase)
            assertEquals(1, runner.resumeCalls)
            lease.release()
        } finally {
            manager.close()
        }
    }

    @Test
    fun interruptPublishedBeforeCollectorCancellationIsNotRestoredToNew() = runTest {
        val runner = CancellationRunner(request("interrupt-before-cancellation"))
        val manager = DefaultAgentSessionManager(
            runner, InMemoryAgentPersistence(), dispatcher = StandardTestDispatcher(testScheduler),
        )
        try {
            val lease = manager.create(runner.request.sessionId)
            lease.start(runner.request)
            val interrupt = async { lease.interrupt() }
            runCurrent()
            assertEquals(AgentSessionPhase.RESUMABLE, lease.state.value.phase)
            assertFalse(interrupt.isCompleted)
            val revision = lease.state.value.revision

            runner.cancelExecution.complete(Unit)
            interrupt.await()

            assertEquals(AgentSessionPhase.RESUMABLE, lease.state.value.phase)
            assertEquals(revision, lease.state.value.revision)
            lease.release()
        } finally {
            runner.cancelExecution.complete(Unit)
            manager.close()
        }
    }

    @Test
    fun recoveryPublishedDuringCancellationRefreshWinsOverFallback() = runTest {
        val persistence = CancellationRefreshPersistence()
        val runner = CancellationRunner(request("concurrent-cancellation-recovery"), persistence)
        val manager = DefaultAgentSessionManager(
            runner, persistence, dispatcher = StandardTestDispatcher(testScheduler),
        )
        try {
            val lease = manager.create(runner.request.sessionId)
            lease.start(runner.request)
            persistence.blockNextLoad = true
            runner.cancelExecution.complete(Unit)
            persistence.refreshStarted.await()

            val recovery = lease.inspectRecovery()
            assertEquals(AgentRecoveryDisposition.RESUMABLE, recovery.disposition)
            val revision = lease.state.value.revision
            persistence.releaseRefresh.complete(Unit)
            lease.awaitIdle()

            assertEquals(AgentSessionPhase.RESUMABLE, lease.state.value.phase)
            assertEquals(revision, lease.state.value.revision)
            lease.release()
        } finally {
            persistence.releaseRefresh.complete(Unit)
            manager.close()
        }
    }

    @Test
    fun explicitCancelIsNotReplacedByPreviousRunsCompletedState() = runTest {
        val persistence = InMemoryAgentPersistence()
        val runner = CancellationRunner(request("explicit-cancel"), persistence)
        persistence.commit(
            runner.snapshot.copy(
                runId = AgentRunId("previous-run"),
                state = runner.snapshot.state.copy(
                    status = AgentStatus.COMPLETED,
                    stopReason = StopReason.COMPLETED,
                ),
            ),
            checkpoint = null,
        )
        val manager = DefaultAgentSessionManager(
            runner, persistence, dispatcher = StandardTestDispatcher(testScheduler),
        )
        try {
            val lease = manager.acquire(runner.request.sessionId)
            lease.start(runner.request)
            lease.cancel()

            assertEquals(AgentSessionPhase.TERMINAL, lease.state.value.phase)
            assertEquals(AgentStatus.CANCELLED, lease.state.value.state?.status)
            lease.release()
        } finally {
            manager.close()
        }
    }

    @Test
    fun interruptIsNotReplacedByPreviousRunsCompletedState() = runTest {
        val persistence = InMemoryAgentPersistence()
        val runner = CancellationRunner(request("explicit-interrupt"), persistence)
        persistence.commit(
            runner.snapshot.copy(
                runId = AgentRunId("previous-run"),
                state = runner.snapshot.state.copy(
                    status = AgentStatus.COMPLETED,
                    stopReason = StopReason.COMPLETED,
                ),
            ),
            checkpoint = null,
        )
        val manager = DefaultAgentSessionManager(
            runner, persistence, dispatcher = StandardTestDispatcher(testScheduler),
        )
        try {
            val lease = manager.acquire(runner.request.sessionId)
            lease.start(runner.request)
            val observer = manager.acquire(runner.request.sessionId)
            runner.beforeInterruptReturn = {
                runner.cancelExecution.complete(Unit)
                // Observe settlement without re-entering the controlling lease's lifecycle lock.
                observer.awaitIdle()
            }
            val recovery = lease.interrupt()

            assertEquals(AgentRecoveryDisposition.RESUMABLE, recovery.disposition)
            assertEquals(AgentSessionPhase.RESUMABLE, lease.state.value.phase)
            assertEquals(runner.runId, lease.state.value.runId)
            observer.release()
            lease.release()
        } finally {
            manager.close()
        }
    }

    @Test
    fun closePublishedBeforeCancellationCleanupRemainsClosed() = runTest {
        val runner = CancellationRunner(request("close-before-cancellation"))
        val manager = DefaultAgentSessionManager(
            runner, InMemoryAgentPersistence(), dispatcher = StandardTestDispatcher(testScheduler),
        )
        val lease = manager.create(runner.request.sessionId)
        try {
            lease.start(runner.request)
            manager.close()

            assertEquals(AgentSessionPhase.CLOSED, lease.state.value.phase)
            assertTrue(manager.liveSessionIds.value.isEmpty())
            lease.release()
        } finally {
            manager.close()
        }
    }
}

private class CancellationRunner(
    val request: AgentRequest,
    private val persistence: AgentPersistence = InMemoryAgentPersistence(),
) : AgentRunner {
    val cancelExecution = CompletableDeferred<Unit>()
    val runId = AgentRunId("run-${request.sessionId.value}")
    val snapshot = AgentSessionSnapshot(
        sessionId = request.sessionId,
        runId = runId,
        request = request,
        state = AgentStateSnapshot(messages = request.messages, status = AgentStatus.RUNNING),
        updatedAtEpochMs = 1L,
    )
    var resumeCalls = 0
    var beforeInterruptReturn: suspend () -> Unit = {}
    private var started = false

    override fun run(request: AgentRequest): Flow<AgentEvent> = flow {
        started = true
        emit(AgentEvent.Started(request.sessionId, runId))
        cancelExecution.await()
        throw CancellationException("runner cancelled without a terminal event")
    }

    override suspend fun resume(sessionId: AgentSessionId): Flow<AgentEvent> = flow {
        resumeCalls += 1
        emit(AgentEvent.Started(sessionId, runId))
        val completed = snapshot.state.copy(
            status = AgentStatus.COMPLETED,
            stopReason = StopReason.COMPLETED,
        )
        persistence.commit(snapshot.copy(state = completed), checkpoint = null)
        emit(AgentEvent.Completed(sessionId, completed))
    }

    override suspend fun inspectRecovery(sessionId: AgentSessionId): AgentRecoveryInfo {
        if (!started) {
            val record = persistence.load(sessionId)
            return AgentRecoveryInfo(
                sessionId = sessionId,
                runId = record?.snapshot?.runId,
                disposition = if (record == null) {
                    AgentRecoveryDisposition.NOT_FOUND
                } else {
                    AgentRecoveryDisposition.TERMINAL
                },
                status = record?.snapshot?.state?.status,
                state = record?.snapshot?.state,
            )
        }
        return AgentRecoveryInfo(
            sessionId = sessionId,
            runId = runId,
            disposition = AgentRecoveryDisposition.RESUMABLE,
            status = AgentStatus.INTERRUPTED,
            state = snapshot.state.copy(
                status = AgentStatus.INTERRUPTED,
                stopReason = StopReason.INTERRUPTED,
            ),
            interruption = AgentInterruption(AgentInterruptionReason.ORPHANED),
        )
    }

    override suspend fun interrupt(sessionId: AgentSessionId): AgentRecoveryInfo {
        beforeInterruptReturn()
        return inspectRecovery(sessionId)
    }

    override suspend fun cancel(sessionId: AgentSessionId) {
        cancelExecution.complete(Unit)
    }
}

private class CancellationRefreshPersistence(
    private val delegate: AgentPersistence = InMemoryAgentPersistence(),
) : AgentPersistence by delegate {
    var blockNextLoad = false
    val refreshStarted = CompletableDeferred<Unit>()
    val releaseRefresh = CompletableDeferred<Unit>()

    override suspend fun load(sessionId: AgentSessionId): AgentPersistenceRecord? {
        if (blockNextLoad) {
            blockNextLoad = false
            refreshStarted.complete(Unit)
            releaseRefresh.await()
            return null
        }
        return delegate.load(sessionId)
    }
}

private fun request(id: String) = AgentRequest(
    sessionId = AgentSessionId(id),
    messages = listOf(AgentMessage(role = MessageRole.USER, parts = listOf(TextPart("hello")))),
    model = ModelDescriptor(provider = "test", model = "test-model"),
)
