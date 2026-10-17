package saien.magrathea.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import saien.magrathea.core.AgentEvent
import saien.magrathea.core.AgentMessage
import saien.magrathea.core.AgentPersistenceRecord
import saien.magrathea.core.AgentRequest
import saien.magrathea.core.AgentRunId
import saien.magrathea.core.AgentSessionId
import saien.magrathea.core.AgentSessionSnapshot
import saien.magrathea.core.AgentStatus
import saien.magrathea.core.MessageRole
import saien.magrathea.core.ModelDescriptor
import saien.magrathea.core.ReplayExclusion
import saien.magrathea.core.ReplayExclusionReason
import saien.magrathea.core.ReplayReport
import saien.magrathea.core.TextPart

class ReplaySnapshotStateTest {
    private val sessionId = AgentSessionId("replay-session")
    private val request = AgentRequest(sessionId, messages = listOf(
        AgentMessage(id = "user", role = MessageRole.USER, parts = listOf(TextPart("Question"))),
    ), model = ModelDescriptor("test", "model"))
    private val report = ReplayReport(AgentRunId("run"), 0, "test", "model", listOf(
        ReplayExclusion("partial", ReplayExclusionReason.EMPTY_AFTER_TRANSFORMATION),
    ))

    @Test
    fun latestReplaySurvivesLaterEventsWithoutChangingOutcomeFences() {
        var machine = active()
        val attempt = assertNotNull(machine.execution)
        val observed = machine
        machine = assertNotNull(machine.recordEvent(attempt, AgentEvent.ContextTransformed(sessionId, 0, 1, report)))
        assertTrue(machine.acceptsObservationFrom(observed))
        assertEquals(request.messages, machine.result.state?.messages)

        val answer = AgentMessage(id = "answer", role = MessageRole.ASSISTANT, parts = listOf(TextPart("Answer")))
        machine = assertNotNull(machine.recordEvent(attempt, AgentEvent.MessageEmitted(sessionId, answer)))
        val completed = assertNotNull(machine.result.state).copy(status = AgentStatus.COMPLETED)
        machine = assertNotNull(machine.recordEvent(attempt, AgentEvent.Completed(sessionId, completed)))
        machine = machine.settle(attempt, machine, RecoveryObservation.Unavailable)
        assertEquals(report, machine.snapshot(5).replay)
        assertEquals(listOf(request.messages.single(), answer), machine.snapshot(5).state?.messages)
        assertNull(machine.begin(request, resume = false).snapshot(6).replay)
    }

    @Test
    fun unreportedPreparationAndIdleRequestReplacementClearOldReports() {
        var machine = active()
        val attempt = assertNotNull(machine.execution)
        machine = assertNotNull(machine.recordEvent(attempt, AgentEvent.ContextTransformed(sessionId, 0, 1, report)))
        machine = assertNotNull(machine.recordEvent(attempt, AgentEvent.ContextTransformed(sessionId, 1, 1)))
        assertNull(machine.snapshot(3).replay)
        machine = assertNotNull(machine.recordEvent(attempt, AgentEvent.ContextTransformed(sessionId, 1, 1, report.copy(turn = 1))))
        machine = assertNotNull(machine.recordEvent(attempt, AgentEvent.Completed(sessionId, assertNotNull(machine.result.state).copy(status = AgentStatus.COMPLETED))))
        machine = machine.settle(attempt, machine, RecoveryObservation.Unavailable)
        assertNull(machine.replaceRequest(request.copy(model = ModelDescriptor("test", "other"))).snapshot(6).replay)
    }

    @Test
    fun persistedCompletionDuringCancellationPreservesTheLatestReportEvenWhenUnknown() {
        for (latest in listOf(null, report.copy(turn = 1))) {
            var machine = active()
            val attempt = assertNotNull(machine.execution)
            machine = assertNotNull(machine.recordEvent(attempt, AgentEvent.ContextTransformed(sessionId, 0, 1, report)))
            val beforeCancellation = machine
            machine = assertNotNull(machine.recordEvent(attempt, AgentEvent.ContextTransformed(sessionId, 1, 1, latest)))
            machine = machine.settle(attempt, machine, RecoveryObservation.Unavailable)
            val completed = assertNotNull(machine.result.state).copy(status = AgentStatus.COMPLETED)
            val stored = AgentPersistenceRecord(
                AgentSessionSnapshot(sessionId, report.runId, request, completed), checkpoint = null,
            )

            val plan = machine.planCancellation(beforeCancellation, stored, nowEpochMs = 0)

            assertEquals(AgentStatus.COMPLETED, plan.result.state?.status)
            assertEquals(latest, plan.result.replay)
        }
    }

    private fun active(): SessionMachine {
        val started = SessionMachine(sessionId).begin(request, resume = false)
        return assertNotNull(started.recordEvent(assertNotNull(started.execution), AgentEvent.Started(sessionId, report.runId)))
    }
}
