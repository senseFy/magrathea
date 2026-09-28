package saien.magrathea.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import saien.magrathea.core.AgentCheckpoint
import saien.magrathea.core.AgentEvent
import saien.magrathea.core.AgentFailureCode
import saien.magrathea.core.AgentMessage
import saien.magrathea.core.AgentPersistenceRecord
import saien.magrathea.core.AgentRecoveryDisposition
import saien.magrathea.core.AgentRecoveryInfo
import saien.magrathea.core.AgentRequest
import saien.magrathea.core.AgentResumeCursor
import saien.magrathea.core.AgentResumePhase
import saien.magrathea.core.AgentRunId
import saien.magrathea.core.AgentSessionId
import saien.magrathea.core.AgentSessionSnapshot
import saien.magrathea.core.AgentStateSnapshot
import saien.magrathea.core.AgentStatus
import saien.magrathea.core.MessageRole
import saien.magrathea.core.ModelDescriptor
import saien.magrathea.core.StopReason
import saien.magrathea.core.ToolCallPart
import saien.magrathea.core.ToolExecutionRecord
import saien.magrathea.core.ToolExecutionResult
import saien.magrathea.core.ToolExecutionState

/** No runner, persistence implementation, scheduler, or coroutine execution is needed here. */
class ManagedSessionStateTest {
    private val sessionId = AgentSessionId("pure-session-state")
    private val request = AgentRequest(
        sessionId = sessionId,
        messages = emptyList(),
        model = ModelDescriptor(provider = "test", model = "test-model"),
    )

    @Test
    fun projectingUnknownDoesNotConfirmBlockedRecovery() {
        val unknown = SessionMachine(sessionId, SessionResult(resolution = SessionResolution.UNKNOWN))
        val blocked = SessionMachine(sessionId, SessionResult(
            resolution = SessionResolution.BLOCKED,
            recovery = AgentRecoveryInfo(sessionId, disposition = AgentRecoveryDisposition.BLOCKED),
        ))

        repeat(3) {
            assertEquals(AgentSessionPhase.RECOVERY_BLOCKED, unknown.snapshot(it.toLong()).phase)
            assertEquals(AgentSessionPhase.RECOVERY_BLOCKED, blocked.snapshot(it.toLong()).phase)
        }
        assertFalse(unknown.result.isConfirmed)
        assertTrue(blocked.result.isConfirmed)
        assertEquals(
            AgentSessionErrorCode.STORAGE,
            assertFailsWith<AgentSessionException> { unknown.recoveryInfo() }.code,
        )
        assertEquals(AgentRecoveryDisposition.BLOCKED, blocked.recoveryInfo().disposition)
        assertEquals(
            AgentSessionErrorCode.BUSY,
            assertFailsWith<AgentSessionException> { unknown.begin(request, resume = false) }.code,
        )
    }

    @Test
    fun allResolutionsHaveExplicitIdleProjectionsAndAdmissionRules() {
        val phases = mapOf(
            SessionResolution.UNKNOWN to AgentSessionPhase.RECOVERY_BLOCKED,
            SessionResolution.NEW to AgentSessionPhase.NEW,
            SessionResolution.INACTIVE to AgentSessionPhase.INACTIVE,
            SessionResolution.RESUMABLE to AgentSessionPhase.RESUMABLE,
            SessionResolution.BLOCKED to AgentSessionPhase.RECOVERY_BLOCKED,
            SessionResolution.TERMINAL to AgentSessionPhase.TERMINAL,
        )
        assertEquals(SessionResolution.entries.toSet(), phases.keys)
        for ((resolution, phase) in phases) {
            val machine = SessionMachine(sessionId, SessionResult(request = request, resolution = resolution))
            assertEquals(phase, machine.snapshot(0).phase)
            assertFalse(machine.snapshot(0).isExecuting)
            assertEquals(resolution.allowsStart, runCatching { machine.requireAdmission(resume = false) }.isSuccess)
            assertEquals(
                resolution == SessionResolution.RESUMABLE,
                runCatching { machine.requireAdmission(resume = true) }.isSuccess,
            )
        }
    }

    @Test
    fun equalValueCopiesDoNotInvalidateObservations() {
        val observed = SessionMachine(sessionId, SessionResult(request = request))
        val copied = observed.replaceRequest(request.copy())

        assertNotSame(observed.result, copied.result)
        assertEquals(observed.result, copied.result)
        assertEquals(observed.resultVersion, copied.resultVersion)
        assertTrue(copied.acceptsObservationFrom(observed))
    }

    @Test
    fun presentationOnlyEventsDoNotInvalidateRecovery() {
        val observed = active()
        val attempt = assertNotNull(observed.execution)
        val presentation = assertNotNull(observed.recordEvent(
            attempt,
            AgentEvent.TurnStarted(sessionId, assertNotNull(observed.result.state).turn),
        ))
        assertTrue(presentation.result.lastEvent is AgentEvent.TurnStarted)
        assertTrue(observed.result.lastEvent is AgentEvent.Started)
        assertTrue(presentation.result.sameOutcomeAs(observed.result))
        assertEquals(observed.resultVersion, presentation.resultVersion)

        val recovered = presentation.observePersistence(observed, recoveryObservation(observed))
        assertEquals(SessionResolution.RESUMABLE, recovered.result.resolution)
    }

    @Test
    fun resultVersionsRejectAbaChangesButNeverPreventOwnershipSettlement() {
        val observed = active()
        val attempt = assertNotNull(observed.execution)
        val initialTurn = assertNotNull(observed.result.state).turn
        val progressed = assertNotNull(observed.recordEvent(
            attempt,
            AgentEvent.TurnStarted(sessionId, initialTurn + 1),
        ))
        val returned = assertNotNull(progressed.recordEvent(attempt, AgentEvent.TurnStarted(sessionId, initialTurn)))

        assertTrue(returned.result.sameOutcomeAs(observed.result))
        assertTrue(returned.resultVersion > observed.resultVersion)
        assertFalse(returned.acceptsObservationFrom(observed))
        assertEquals(
            SessionResolution.UNKNOWN,
            returned.observePersistence(observed, recoveryObservation(observed)).result.resolution,
        )
        val settled = returned.settle(attempt, observed, RecoveryObservation.Unavailable)
        assertNull(settled.execution)
        assertEquals(AgentSessionPhase.RECOVERY_BLOCKED, settled.snapshot(0).phase)
        assertFalse(attempt.completion.isCompleted, "Only the manager delivers completion after publication")
    }

    @Test
    fun diagnosticFailureEventCannotManufactureAFailedOutcome() {
        val unknown = SessionResult(
            resolution = SessionResolution.UNKNOWN,
            lastEvent = AgentEvent.Failed(sessionId, AgentFailureCode.INTERNAL),
        )
        val resolved = unknown.resolve(RecoveryObservation.Absent, absent = SessionResult())
        assertEquals(SessionResolution.NEW, resolved.resolution)
    }

    @Test
    fun failedOutcomeDoesNotRequireADiagnosticEvent() {
        val failed = SessionResult(
            resolution = SessionResolution.UNKNOWN,
            state = AgentStateSnapshot(emptyList(), status = AgentStatus.FAILED),
            failure = AgentFailureCode.INTERNAL,
            lastEvent = null,
        )
        val resolved = failed.resolve(RecoveryObservation.Absent, absent = SessionResult())
        assertEquals(SessionResolution.TERMINAL, resolved.resolution)
        assertEquals(AgentFailureCode.INTERNAL, resolved.failure)
    }

    @Test
    fun cancellationPlanSeparatesPendingCommitFromConfirmedResult() {
        val before = active()
        val observation = recoveryObservation(before)
        val settled = before.settle(assertNotNull(before.execution), before, observation)
        val plan = settled.planCancellation(before, observation.record, nowEpochMs = 42L)
        val commit = assertNotNull(plan.commit)

        assertEquals(AgentStatus.RUNNING, observation.record.snapshot.state.status)
        assertNotNull(observation.record.checkpoint)
        assertEquals(AgentStatus.CANCELLED, commit.state.status)
        assertEquals(42L, commit.updatedAtEpochMs)
        val pending = settled.prepareCancellation(plan)
        assertEquals(SessionResolution.UNKNOWN, pending.result.resolution)
        assertEquals(AgentSessionPhase.RECOVERY_BLOCKED, pending.snapshot(0).phase)
        val cancelled = pending.completeCancellation(plan)
        assertEquals(SessionResolution.TERMINAL, cancelled.result.resolution)
        assertEquals(AgentStatus.CANCELLED, cancelled.result.state?.status)
    }

    @Test
    fun completedSameRunWinsCancellationWithoutACommit() {
        val before = active()
        val attempt = assertNotNull(before.execution)
        val state = assertNotNull(before.result.state).copy(
            status = AgentStatus.COMPLETED,
            stopReason = StopReason.COMPLETED,
        )
        val completed = assertNotNull(before.recordEvent(attempt, AgentEvent.Completed(sessionId, state)))
        val settled = completed.settle(attempt, completed, RecoveryObservation.Unavailable)
        val plan = settled.planCancellation(before, persisted = null, nowEpochMs = 42L)

        assertNull(plan.commit)
        assertFalse(plan.removesPendingWork)
        assertEquals(AgentStatus.COMPLETED, settled.completeCancellation(plan).result.state?.status)
    }

    @Test
    fun oldCancellationAndInterruptionCannotAffectTheNextExecution() {
        val before = active()
        val oldAttempt = assertNotNull(before.execution)
        val observation = recoveryObservation(before)
        val settled = before.settle(oldAttempt, before, observation)
        val plan = settled.planCancellation(before, observation.record, nowEpochMs = 42L)
        val next = settled.completeCancellation(plan).begin(request, resume = false)

        assertEquals(next.generation, next.prepareCancellation(plan).generation)
        assertEquals(next.result, next.prepareCancellation(plan).result)
        assertEquals(next.result, next.completeCancellation(plan).result)
        assertEquals(next.result, next.interrupted(oldAttempt, assertNotNull(observation.recovery)).result)
        assertEquals(next.result, next.observePersistence(before, observation).result)
        assertEquals(next.execution, next.settle(oldAttempt, before, observation).execution)
    }

    @Test
    fun closedAndDeletedLifetimesRejectLateTransitionsButReleaseOwnership() {
        for (deleted in listOf(false, true)) {
            val before = active()
            val attempt = assertNotNull(before.execution)
            val observation = recoveryObservation(before)
            val settled = before.settle(attempt, before, observation)
            val plan = settled.planCancellation(before, observation.record, nowEpochMs = 42L)
            val fenced = before.close(deleted)
            val closed = fenced.settle(attempt, before, observation)

            assertNull(closed.execution)
            assertFalse(closed.isExecuting)
            assertEquals(fenced.lifecycle, closed.close(!deleted).lifecycle)
            assertEquals(fenced.result, closed.observePersistence(before, observation).result)
            assertEquals(fenced.result, closed.interrupted(attempt, assertNotNull(observation.recovery)).result)
            assertEquals(fenced.result, closed.completeCancellation(plan).result)
            assertNull(closed.recordEvent(attempt, AgentEvent.Cancelled(sessionId)))
            assertEquals(
                if (deleted) AgentSessionErrorCode.DELETED else AgentSessionErrorCode.CLOSED,
                assertFailsWith<AgentSessionException> { closed.begin(request, resume = false) }.code,
            )
        }
    }

    @Test
    fun toolJournalFollowsCheckpointsWhileActiveAndClearsOnTerminal() {
        val before = active()
        val attempt = assertNotNull(before.execution)
        val runId = assertNotNull(before.result.runId)
        val batch = toolBatch()
        val journaled = assertNotNull(before.recordEvent(
            attempt,
            AgentEvent.CheckpointSaved(batch.checkpoint(runId, batch.pendingJournal)),
        ))
        assertEquals(batch.pendingJournal, journaled.snapshot(0).toolExecutions)

        val startedJournal = batch.pendingJournal.map { record ->
            if (record.toolCallId == "call-1") {
                record.copy(state = ToolExecutionState.STARTED)
            } else {
                record
            }
        }
        val progressed = assertNotNull(journaled.recordEvent(
            attempt,
            AgentEvent.CheckpointSaved(batch.checkpoint(runId, startedJournal)),
        ))
        assertEquals(startedJournal, progressed.snapshot(0).toolExecutions)
        assertEquals(AgentSessionPhase.ACTIVE, progressed.snapshot(0).phase)

        val completed = assertNotNull(progressed.recordEvent(
            attempt,
            AgentEvent.Completed(
                sessionId,
                batch.waiting.copy(status = AgentStatus.COMPLETED, stopReason = StopReason.COMPLETED),
            ),
        ))
        assertEquals(emptyList(), completed.result.toolExecutions)
        assertEquals(AgentSessionPhase.TERMINAL, completed.snapshot(0).phase)
        assertEquals(emptyList(), completed.snapshot(0).toolExecutions)

        val resumable = SessionMachine(sessionId, SessionResult(
            resolution = SessionResolution.RESUMABLE,
            toolExecutions = batch.pendingJournal,
        ))
        assertEquals(AgentSessionPhase.RESUMABLE, resumable.snapshot(0).phase)
        assertEquals(emptyList(), resumable.snapshot(0).toolExecutions)
    }

    @Test
    fun midBatchCheckpointDropsCompletedCallsFromPendingToolCalls() {
        val before = active()
        val attempt = assertNotNull(before.execution)
        val runId = assertNotNull(before.result.runId)
        val batch = toolBatch()
        val journaled = assertNotNull(before.recordEvent(
            attempt,
            AgentEvent.CheckpointSaved(batch.checkpoint(runId, batch.pendingJournal)),
        ))

        val completedFirst = batch.pendingJournal.map { record ->
            if (record.toolCallId == "call-1") {
                record.copy(
                    state = ToolExecutionState.COMPLETED,
                    result = ToolExecutionResult("call-1", "first", JsonPrimitive("done")),
                )
            } else {
                record
            }
        }
        val updated = assertNotNull(journaled.recordEvent(
            attempt,
            AgentEvent.CheckpointSaved(batch.checkpoint(runId, completedFirst)),
        ))

        assertEquals(listOf(batch.calls[1]), updated.result.state?.pendingToolCalls)
        assertEquals(completedFirst, updated.snapshot(0).toolExecutions)
    }

    private class ToolBatch(
        val calls: List<ToolCallPart>,
        val waiting: AgentStateSnapshot,
        val pendingJournal: List<ToolExecutionRecord>,
    ) {
        fun checkpoint(runId: AgentRunId, journal: List<ToolExecutionRecord>) = AgentCheckpoint(
            sessionId = AgentSessionId("pure-session-state"),
            runId = runId,
            cursor = AgentResumeCursor(0, AgentResumePhase.TOOLS_PENDING),
            state = waiting,
            toolExecutions = journal,
        )
    }

    private fun toolBatch(): ToolBatch {
        val calls = listOf(
            ToolCallPart("call-1", "first", buildJsonObject { }),
            ToolCallPart("call-2", "second", buildJsonObject { }),
        )
        val waiting = AgentStateSnapshot(
            messages = listOf(
                AgentMessage(id = "assistant-tools", role = MessageRole.ASSISTANT, parts = calls),
            ),
            pendingToolCalls = calls,
            status = AgentStatus.WAITING_FOR_TOOLS,
        )
        val pendingJournal = calls.mapIndexed { index, call ->
            ToolExecutionRecord(
                executionId = "exec-${index + 1}",
                toolCallId = call.toolCallId,
                toolName = call.toolName,
                callOrdinal = index + 1,
                state = ToolExecutionState.PENDING,
            )
        }
        return ToolBatch(calls, waiting, pendingJournal)
    }

    private fun active(): SessionMachine {
        val started = SessionMachine(sessionId).begin(request, resume = false)
        return assertNotNull(started.recordEvent(
            assertNotNull(started.execution),
            AgentEvent.Started(sessionId, AgentRunId("pure-run")),
        ))
    }

    private fun recoveryObservation(machine: SessionMachine): RecoveryObservation.Present {
        val state = assertNotNull(machine.result.state)
        val runId = assertNotNull(machine.result.runId)
        return RecoveryObservation.Present(
            record = AgentPersistenceRecord(
                snapshot = AgentSessionSnapshot(sessionId, runId, request, state, updatedAtEpochMs = 1L),
                checkpoint = AgentCheckpoint(
                    sessionId = sessionId,
                    runId = runId,
                    cursor = AgentResumeCursor(state.turn, AgentResumePhase.TURN_PREPARING),
                    state = state,
                ),
            ),
            recovery = AgentRecoveryInfo(
                sessionId = sessionId,
                runId = runId,
                disposition = AgentRecoveryDisposition.RESUMABLE,
                state = state.copy(status = AgentStatus.INTERRUPTED),
            ),
        )
    }
}
