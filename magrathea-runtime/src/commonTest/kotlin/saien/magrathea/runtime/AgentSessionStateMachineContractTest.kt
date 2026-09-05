package saien.magrathea.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import saien.magrathea.core.AgentCheckpoint
import saien.magrathea.core.AgentEvent
import saien.magrathea.core.AgentFailureCode
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
import saien.magrathea.core.MessageRole
import saien.magrathea.core.ModelDescriptor
import saien.magrathea.core.StopReason
import saien.magrathea.core.TextPart

@OptIn(ExperimentalCoroutinesApi::class)
class AgentSessionStateMachineContractTest {
    @Test
    fun lateActiveObservationMustNotDefeatExecutionSettlement() = runTest {
        val persistence = SessionObservationPersistence()
        val runner = SessionObservationRunner(persistence, persistCheckpoint = false)
        val manager = DefaultAgentSessionManager(
            runner, persistence, dispatcher = StandardTestDispatcher(testScheduler),
        )
        try {
            val request = request("late-active")
            val lease = manager.create(request.sessionId)
            lease.start(request)
            runner.blockNextInspection = true
            val inspection = async { lease.inspectRecovery() }
            runner.inspectionCaptured.await()

            persistence.blockNextLoad = true
            runner.endExecution.complete(Unit)
            persistence.refreshStarted.await()
            runner.releaseInspection.complete(Unit)
            assertEquals(AgentRecoveryDisposition.ACTIVE, inspection.await().disposition)
            persistence.releaseRefresh.complete(Unit)
            lease.awaitIdle()

            assertFalse(
                lease.state.value.isExecuting,
                "A newer revision containing an older ACTIVE observation defeated finalization",
            )
        } finally {
            runner.releaseInspection.complete(Unit)
            runner.endExecution.complete(Unit)
            persistence.releaseRefresh.complete(Unit)
            manager.close()
        }
    }

    @Test
    fun unavailableRecoveryMustNotAuthorizeFreshExecutionOverExistingCheckpoint() = runTest {
        val persistence = SessionObservationPersistence()
        val runner = SessionObservationRunner(persistence, persistCheckpoint = true)
        val manager = DefaultAgentSessionManager(
            runner, persistence, dispatcher = StandardTestDispatcher(testScheduler),
        )
        try {
            val request = request("unknown-recovery")
            val lease = manager.create(request.sessionId)
            lease.start(request)
            persistence.failNextLoad = true
            runner.endExecution.complete(Unit)
            lease.awaitIdle()
            val originalCheckpoint = assertNotNull(persistence.delegate.load(request.sessionId)?.checkpoint)
            assertEquals(AgentSessionPhase.RECOVERY_BLOCKED, lease.state.value.phase)
            assertNull(lease.state.value.recovery)
            assertFalse(lease.state.value.isExecuting)
            assertEquals(
                AgentSessionErrorCode.BUSY,
                assertFailsWith<AgentSessionException> { lease.start(request) }.code,
            )
            assertEquals(
                AgentSessionErrorCode.BUSY,
                assertFailsWith<AgentSessionException> { lease.replaceIdleRequest(request) }.code,
            )
            assertEquals(1, runner.runCalls)
            assertEquals(originalCheckpoint, persistence.delegate.load(request.sessionId)?.checkpoint)

            persistence.failNextLoad = true
            assertEquals(
                AgentSessionErrorCode.STORAGE,
                assertFailsWith<AgentSessionException> { lease.inspectRecovery() }.code,
            )
            assertEquals(AgentSessionPhase.RECOVERY_BLOCKED, lease.state.value.phase)
            assertEquals(AgentRecoveryDisposition.RESUMABLE, lease.inspectRecovery().disposition)
            lease.resume()
            lease.awaitIdle()
            assertEquals(AgentSessionPhase.TERMINAL, lease.state.value.phase)
            assertNull(persistence.delegate.load(request.sessionId)?.checkpoint)
            lease.release()
        } finally {
            runner.endExecution.complete(Unit)
            manager.close()
        }
    }

    @Test
    fun confirmedAbsenceAfterReadFailureAllowsFreshExecution() = runTest {
        val persistence = SessionObservationPersistence()
        val runner = SessionObservationRunner(persistence, persistCheckpoint = false)
        val manager = DefaultAgentSessionManager(runner, persistence, StandardTestDispatcher(testScheduler))
        try {
            val request = request("unknown-then-absent")
            val lease = manager.create(request.sessionId)
            lease.start(request)
            persistence.failNextLoad = true
            runner.endExecution.complete(Unit)
            lease.awaitIdle()

            assertEquals(AgentSessionPhase.RECOVERY_BLOCKED, lease.state.value.phase)
            assertEquals(AgentRecoveryDisposition.NOT_FOUND, lease.inspectRecovery().disposition)
            assertEquals(AgentSessionPhase.NEW, lease.state.value.phase)
            lease.start(request)
            lease.awaitIdle()
            assertEquals(2, runner.runCalls)
            lease.release()
        } finally {
            runner.endExecution.complete(Unit)
            manager.close()
        }
    }

    @Test
    fun recoveryInspectionFailureKeepsCheckpointAndCanBeRetried() = runTest {
        val persistence = SessionObservationPersistence()
        val runner = SessionObservationRunner(persistence, persistCheckpoint = true)
        val manager = DefaultAgentSessionManager(runner, persistence, StandardTestDispatcher(testScheduler))
        try {
            val request = request("inspection-unavailable")
            val lease = manager.create(request.sessionId)
            lease.start(request)
            runner.failNextInspection = true
            runner.endExecution.complete(Unit)
            lease.awaitIdle()

            assertEquals(AgentSessionPhase.RECOVERY_BLOCKED, lease.state.value.phase)
            assertNotNull(persistence.delegate.load(request.sessionId)?.checkpoint)
            assertEquals(
                AgentSessionErrorCode.INVALID_STATE,
                assertFailsWith<AgentSessionException> { lease.resume() }.code,
            )
            assertEquals(AgentRecoveryDisposition.RESUMABLE, lease.inspectRecovery().disposition)
            lease.release()
        } finally {
            runner.endExecution.complete(Unit)
            manager.close()
        }
    }

    @Test
    fun inconsistentRecoveryObservationsNeverAuthorizeFreshWork() = runTest {
        for (disposition in listOf(
            AgentRecoveryDisposition.ACTIVE,
            AgentRecoveryDisposition.NOT_FOUND,
            AgentRecoveryDisposition.TERMINAL,
        )) {
            val persistence = SessionObservationPersistence()
            val runner = SessionObservationRunner(persistence, persistCheckpoint = true)
            val manager = DefaultAgentSessionManager(runner, persistence, StandardTestDispatcher(testScheduler))
            try {
                val request = request("inconsistent-$disposition")
                val lease = manager.create(request.sessionId)
                lease.start(request)
                runner.recoveryOverride = AgentRecoveryInfo(
                    sessionId = request.sessionId,
                    runId = lease.state.value.runId,
                    disposition = disposition,
                )
                runner.endExecution.complete(Unit)
                lease.awaitIdle()

                assertEquals(AgentSessionPhase.RECOVERY_BLOCKED, lease.state.value.phase)
                assertEquals(
                    AgentSessionErrorCode.BUSY,
                    assertFailsWith<AgentSessionException> { lease.start(request) }.code,
                )
                runner.recoveryOverride = AgentRecoveryInfo(
                    sessionId = request.sessionId,
                    runId = AgentRunId("another-run"),
                    disposition = AgentRecoveryDisposition.RESUMABLE,
                )
                assertEquals(
                    AgentSessionErrorCode.STORAGE,
                    assertFailsWith<AgentSessionException> { lease.inspectRecovery() }.code,
                )
                runner.recoveryOverride = null
                assertEquals(AgentRecoveryDisposition.RESUMABLE, lease.inspectRecovery().disposition)
                assertEquals(1, runner.runCalls)
                lease.release()
            } finally {
                runner.endExecution.complete(Unit)
                manager.close()
            }
        }
    }

    @Test
    fun activeObservationReturningAfterSettlementCannotResurrectOwnership() = runTest {
        val persistence = SessionObservationPersistence()
        val runner = SessionObservationRunner(persistence, persistCheckpoint = false)
        val manager = DefaultAgentSessionManager(runner, persistence, StandardTestDispatcher(testScheduler))
        try {
            val request = request("late-after-settlement")
            val lease = manager.create(request.sessionId)
            val observer = manager.acquire(request.sessionId)
            lease.start(request)
            runner.blockNextInspection = true
            val inspection = async { lease.inspectRecovery() }
            runner.inspectionCaptured.await()
            runner.endExecution.complete(Unit)
            observer.awaitIdle()
            assertEquals(AgentSessionPhase.NEW, observer.state.value.phase)
            val settledRevision = observer.state.value.revision

            runner.releaseInspection.complete(Unit)
            assertEquals(AgentRecoveryDisposition.NOT_FOUND, inspection.await().disposition)
            assertEquals(settledRevision, observer.state.value.revision)
            lease.start(request)
            lease.awaitIdle()
            assertEquals(2, runner.runCalls)
            observer.release()
            lease.release()
        } finally {
            runner.releaseInspection.complete(Unit)
            runner.endExecution.complete(Unit)
            manager.close()
        }
    }

    @Test
    fun inspectionReturningAfterUnavailableSettlementReportsUncertainty() = runTest {
        val persistence = SessionObservationPersistence()
        val runner = SessionObservationRunner(persistence, persistCheckpoint = true)
        val manager = DefaultAgentSessionManager(runner, persistence, StandardTestDispatcher(testScheduler))
        try {
            val request = request("late-after-unavailable")
            val lease = manager.create(request.sessionId)
            val observer = manager.acquire(request.sessionId)
            lease.start(request)
            runner.blockNextInspection = true
            val inspection = async { runCatching { lease.inspectRecovery() } }
            runner.inspectionCaptured.await()
            persistence.failNextLoad = true
            runner.endExecution.complete(Unit)
            observer.awaitIdle()
            runner.releaseInspection.complete(Unit)

            val failure = inspection.await().exceptionOrNull()
            assertEquals(AgentSessionErrorCode.STORAGE, (failure as? AgentSessionException)?.code)
            assertEquals(AgentSessionPhase.RECOVERY_BLOCKED, observer.state.value.phase)
            assertEquals(AgentRecoveryDisposition.RESUMABLE, lease.inspectRecovery().disposition)
            observer.release()
            lease.release()
        } finally {
            runner.releaseInspection.complete(Unit)
            runner.endExecution.complete(Unit)
            manager.close()
        }
    }

    @Test
    fun explicitCancelResolvesUnknownRecoveryWithoutStartingProviderWork() = runTest {
        val persistence = SessionObservationPersistence()
        val runner = SessionObservationRunner(persistence, persistCheckpoint = true)
        val manager = DefaultAgentSessionManager(runner, persistence, StandardTestDispatcher(testScheduler))
        try {
            val request = request("cancel-unknown")
            val lease = manager.create(request.sessionId)
            lease.start(request)
            persistence.failNextLoad = true
            runner.endExecution.complete(Unit)
            lease.awaitIdle()
            lease.cancel()

            assertEquals(AgentSessionPhase.TERMINAL, lease.state.value.phase)
            assertEquals(AgentStatus.CANCELLED, lease.state.value.state?.status)
            assertNull(persistence.delegate.load(request.sessionId)?.checkpoint)
            assertEquals(1, runner.runCalls)
            lease.release()
        } finally {
            runner.endExecution.complete(Unit)
            manager.close()
        }
    }

    @Test
    fun failedCancellationCommitCannotAuthorizeFreshWorkOverPendingCheckpoint() = runTest {
        val persistence = SessionObservationPersistence()
        val runner = SessionObservationRunner(persistence, persistCheckpoint = true)
        val manager = DefaultAgentSessionManager(runner, persistence, StandardTestDispatcher(testScheduler))
        try {
            val request = request("cancel-commit-failure")
            val lease = manager.create(request.sessionId)
            lease.start(request)
            runner.endExecution.complete(Unit)
            lease.awaitIdle()
            val checkpoint = assertNotNull(persistence.delegate.load(request.sessionId)?.checkpoint)
            persistence.failNextCommit = true

            assertEquals(
                AgentSessionErrorCode.STORAGE,
                assertFailsWith<AgentSessionException> { lease.cancel() }.code,
            )
            assertEquals(AgentSessionPhase.RECOVERY_BLOCKED, lease.state.value.phase)
            assertEquals(
                AgentSessionErrorCode.BUSY,
                assertFailsWith<AgentSessionException> { lease.start(request) }.code,
            )
            assertEquals(checkpoint, persistence.delegate.load(request.sessionId)?.checkpoint)
            lease.cancel()
            assertEquals(AgentStatus.CANCELLED, lease.state.value.state?.status)
            assertNull(persistence.delegate.load(request.sessionId)?.checkpoint)
            lease.release()
        } finally {
            runner.endExecution.complete(Unit)
            manager.close()
        }
    }

    @Test
    fun storageFailureEventStillRequiresConfirmationOfCheckpointCleanup() = runTest {
        verifyFailureCannotAuthorizeWork(AgentEvent.Failed(AgentSessionId("failure-outcome"), AgentFailureCode.STORAGE))
    }

    @Test
    fun unexpectedRunnerExceptionStillRequiresConfirmationOfCheckpointCleanup() = runTest {
        verifyFailureCannotAuthorizeWork(terminalEvent = null)
    }

    @Test
    fun bufferedProgressCannotUndoConfirmedInterruption() = runTest {
        val persistence = SessionObservationPersistence()
        val runner = SessionObservationRunner(persistence, persistCheckpoint = false)
        val manager = DefaultAgentSessionManager(runner, persistence, StandardTestDispatcher(testScheduler))
        try {
            val request = request("progress-after-interrupt")
            val lease = manager.create(request.sessionId)
            lease.start(request)
            val runId = assertNotNull(lease.state.value.runId)
            runner.interruptResult = AgentRecoveryInfo(
                sessionId = request.sessionId,
                runId = runId,
                disposition = AgentRecoveryDisposition.RESUMABLE,
                state = assertNotNull(lease.state.value.state).copy(status = AgentStatus.INTERRUPTED),
            )
            runner.terminalEvent = AgentEvent.Started(request.sessionId, runId)
            val interruption = async { lease.interrupt() }
            runCurrent()
            assertEquals(AgentSessionPhase.RESUMABLE, lease.state.value.phase)
            val revision = lease.state.value.revision
            runner.endExecution.complete(Unit)
            interruption.await()

            assertEquals(AgentSessionPhase.RESUMABLE, lease.state.value.phase)
            assertEquals(AgentStatus.INTERRUPTED, lease.state.value.state?.status)
            assertEquals(revision, lease.state.value.revision)
            lease.release()
        } finally {
            runner.endExecution.complete(Unit)
            manager.close()
        }
    }

    @Test
    fun unavailableRefreshDoesNotEraseCanonicalCompletion() = runTest {
        val persistence = SessionObservationPersistence()
        val runner = SessionObservationRunner(persistence, persistCheckpoint = false)
        val manager = DefaultAgentSessionManager(runner, persistence, StandardTestDispatcher(testScheduler))
        try {
            val request = request("confirmed-completion")
            val lease = manager.create(request.sessionId)
            lease.start(request)
            val completed = assertNotNull(lease.state.value.state).copy(
                status = AgentStatus.COMPLETED,
                stopReason = StopReason.COMPLETED,
            )
            runner.terminalEvent = AgentEvent.Completed(request.sessionId, completed)
            persistence.failNextLoad = true
            runner.endExecution.complete(Unit)
            lease.awaitIdle()

            assertEquals(AgentSessionPhase.TERMINAL, lease.state.value.phase)
            assertEquals(completed, lease.state.value.state)
            assertEquals(AgentRecoveryDisposition.TERMINAL, lease.inspectRecovery().disposition)
            lease.release()
        } finally {
            runner.endExecution.complete(Unit)
            manager.close()
        }
    }

    @Test
    fun historicalTerminalInspectionCannotConfirmTheCurrentExecution() = runTest {
        val persistence = SessionObservationPersistence()
        val runner = SessionObservationRunner(persistence, persistCheckpoint = true)
        val manager = DefaultAgentSessionManager(runner, persistence, StandardTestDispatcher(testScheduler))
        try {
            val request = request("historical-inspection")
            val lease = manager.create(request.sessionId)
            lease.start(request)
            val runId = lease.state.value.runId
            runner.recoveryOverride = AgentRecoveryInfo(
                sessionId = request.sessionId,
                runId = AgentRunId("historical-run"),
                disposition = AgentRecoveryDisposition.TERMINAL,
                status = AgentStatus.COMPLETED,
            )
            assertEquals(AgentRecoveryDisposition.ACTIVE, lease.inspectRecovery().disposition)
            assertEquals(runId, lease.state.value.runId)
            persistence.failNextLoad = true
            runner.endExecution.complete(Unit)
            lease.awaitIdle()

            assertEquals(AgentSessionPhase.RECOVERY_BLOCKED, lease.state.value.phase)
            runner.recoveryOverride = null
            assertEquals(AgentRecoveryDisposition.RESUMABLE, lease.inspectRecovery().disposition)
            lease.release()
        } finally {
            runner.endExecution.complete(Unit)
            manager.close()
        }
    }

    @Test
    fun confirmedCheckpointAbsenceDoesNotRestoreObsoleteResumableState() = runTest {
        val persistence = SessionObservationPersistence()
        val runner = SessionObservationRunner(persistence, persistCheckpoint = true)
        val manager = DefaultAgentSessionManager(runner, persistence, StandardTestDispatcher(testScheduler))
        try {
            val request = request("resume-without-checkpoint")
            val lease = manager.create(request.sessionId)
            lease.start(request)
            runner.endExecution.complete(Unit)
            lease.awaitIdle()
            assertEquals(AgentSessionPhase.RESUMABLE, lease.state.value.phase)
            runner.discardOnResume = true
            lease.resume()
            lease.awaitIdle()

            assertEquals(AgentSessionPhase.NEW, lease.state.value.phase)
            assertNull(persistence.delegate.load(request.sessionId))
            assertEquals(AgentRecoveryDisposition.NOT_FOUND, lease.inspectRecovery().disposition)
            lease.start(request)
            lease.awaitIdle()
            assertEquals(2, runner.runCalls)
            lease.release()
        } finally {
            runner.endExecution.complete(Unit)
            manager.close()
        }
    }

    private suspend fun TestScope.verifyFailureCannotAuthorizeWork(terminalEvent: AgentEvent?) {
        val persistence = SessionObservationPersistence()
        val runner = SessionObservationRunner(persistence, persistCheckpoint = true).apply {
            this.terminalEvent = terminalEvent
            executionFailure = IllegalStateException("unexpected adapter failure")
        }
        val manager = DefaultAgentSessionManager(runner, persistence, StandardTestDispatcher(testScheduler))
        try {
            val request = request("failure-outcome")
            val lease = manager.create(request.sessionId)
            lease.start(request)
            persistence.failNextLoad = true
            runner.endExecution.complete(Unit)
            lease.awaitIdle()

            assertEquals(AgentSessionPhase.RECOVERY_BLOCKED, lease.state.value.phase)
            assertEquals(
                AgentSessionErrorCode.BUSY,
                assertFailsWith<AgentSessionException> { lease.start(request) }.code,
            )
            assertEquals(AgentRecoveryDisposition.RESUMABLE, lease.inspectRecovery().disposition)
            assertEquals(1, runner.runCalls)
            lease.release()
        } finally {
            runner.endExecution.complete(Unit)
            manager.close()
        }
    }
}

private class SessionObservationPersistence(
    val delegate: AgentPersistence = InMemoryAgentPersistence(),
) : AgentPersistence by delegate {
    var failNextLoad = false
    var failNextCommit = false
    var blockNextLoad = false
    val refreshStarted = CompletableDeferred<Unit>()
    val releaseRefresh = CompletableDeferred<Unit>()

    override suspend fun commit(snapshot: AgentSessionSnapshot, checkpoint: AgentCheckpoint?) {
        if (failNextCommit) {
            failNextCommit = false
            error("terminal commit unavailable")
        }
        delegate.commit(snapshot, checkpoint)
    }

    override suspend fun load(sessionId: AgentSessionId): AgentPersistenceRecord? {
        if (failNextLoad) {
            failNextLoad = false
            throw IllegalStateException("transient recovery read failure")
        }
        if (blockNextLoad) {
            blockNextLoad = false
            refreshStarted.complete(Unit)
            releaseRefresh.await()
        }
        return delegate.load(sessionId)
    }
}

private class SessionObservationRunner(
    private val persistence: AgentPersistence,
    private val persistCheckpoint: Boolean,
) : AgentRunner {
    var runCalls = 0
    var blockNextInspection = false
    var failNextInspection = false
    var recoveryOverride: AgentRecoveryInfo? = null
    var interruptResult: AgentRecoveryInfo? = null
    var discardOnResume = false
    var terminalEvent: AgentEvent? = null
    var executionFailure: Exception = CancellationException("runner stopped without a terminal event")
    val endExecution = CompletableDeferred<Unit>()
    val inspectionCaptured = CompletableDeferred<Unit>()
    val releaseInspection = CompletableDeferred<Unit>()
    private var running = false
    private var runId: AgentRunId? = null
    private var state: AgentStateSnapshot? = null

    override fun run(request: AgentRequest): Flow<AgentEvent> = flow {
        runCalls += 1
        val id = AgentRunId("observed-run-$runCalls")
        runId = id
        val snapshot = AgentStateSnapshot(messages = request.messages, status = AgentStatus.RUNNING)
        state = snapshot
        running = true
        try {
            if (persistCheckpoint) {
                persistence.commit(
                    AgentSessionSnapshot(request.sessionId, id, request, snapshot, updatedAtEpochMs = 1L),
                    AgentCheckpoint(
                        sessionId = request.sessionId,
                        runId = id,
                        cursor = AgentResumeCursor(0, AgentResumePhase.TURN_PREPARING),
                        state = snapshot,
                    ),
                )
            }
            emit(AgentEvent.Started(request.sessionId, id))
            endExecution.await()
            val terminal = terminalEvent
            if (terminal != null) emit(terminal) else throw executionFailure
        } finally {
            running = false
        }
    }

    override suspend fun resume(sessionId: AgentSessionId): Flow<AgentEvent> = flow {
        if (discardOnResume) {
            persistence.deleteSession(sessionId)
            throw CancellationException("checkpoint no longer exists")
        }
        val record = checkNotNull(persistence.load(sessionId))
        val completed = record.snapshot.state.copy(
            status = AgentStatus.COMPLETED,
            stopReason = StopReason.COMPLETED,
        )
        persistence.commit(record.snapshot.copy(state = completed), checkpoint = null)
        emit(AgentEvent.Completed(sessionId, completed))
    }

    override suspend fun inspectRecovery(sessionId: AgentSessionId): AgentRecoveryInfo {
        if (failNextInspection) {
            failNextInspection = false
            error("recovery inspection unavailable")
        }
        recoveryOverride?.let { return it }
        val observed = if (running) {
            AgentRecoveryInfo(
                sessionId = sessionId,
                runId = runId,
                disposition = AgentRecoveryDisposition.ACTIVE,
                status = AgentStatus.RUNNING,
                state = state,
            )
        } else {
            val record = persistence.load(sessionId)
            AgentRecoveryInfo(
                sessionId = sessionId,
                runId = record?.snapshot?.runId,
                disposition = if (record == null) {
                    AgentRecoveryDisposition.NOT_FOUND
                } else {
                    AgentRecoveryDisposition.RESUMABLE
                },
                status = record?.snapshot?.state?.status,
                state = record?.snapshot?.state,
                interruption = record?.let { AgentInterruption(AgentInterruptionReason.ORPHANED) },
            )
        }
        if (blockNextInspection) {
            blockNextInspection = false
            inspectionCaptured.complete(Unit)
            releaseInspection.await()
        }
        return observed
    }

    override suspend fun interrupt(sessionId: AgentSessionId): AgentRecoveryInfo {
        interruptResult?.let { return it }
        endExecution.complete(Unit)
        return inspectRecovery(sessionId)
    }

    override suspend fun cancel(sessionId: AgentSessionId) {
        endExecution.complete(Unit)
    }
}

private fun request(id: String) = AgentRequest(
    sessionId = AgentSessionId(id),
    messages = listOf(AgentMessage(role = MessageRole.USER, parts = listOf(TextPart("hello")))),
    model = ModelDescriptor(provider = "test", model = "test-model"),
)
