package saien.magrathea.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import saien.magrathea.core.AgentEvent
import saien.magrathea.core.AgentFailureCode
import saien.magrathea.core.AgentRecoveryDisposition
import saien.magrathea.core.AgentRecoveryInfo
import saien.magrathea.core.AgentRequest
import saien.magrathea.core.AgentRunId
import saien.magrathea.core.AgentSessionId
import saien.magrathea.core.AgentStateSnapshot
import saien.magrathea.core.ModelDescriptor
import saien.magrathea.core.ToolExecutionRecord
import saien.magrathea.core.ToolExecutionState

class AgentSessionRuntimeSnapshotBinaryContractTest {
    private val snapshotClass = AgentSessionRuntimeSnapshot::class.java
    private val legacyParameterTypes = arrayOf(
        Long::class.javaPrimitiveType,
        AgentSessionId::class.java,
        AgentRequest::class.java,
        AgentRunId::class.java,
        AgentStateSnapshot::class.java,
        AgentSessionPhase::class.java,
        AgentRecoveryInfo::class.java,
        AgentFailureCode::class.java,
        AgentEvent::class.java,
    )

    @Test
    fun originalNineParameterConstructorRemainsCallable() {
        val expected = snapshot()
        val actual = snapshotClass.getConstructor(*legacyParameterTypes)
            .newInstance(*legacyArguments(expected))

        assertEquals(expected, actual)
        assertEquals(emptyList(), actual.toolExecutions)
    }

    @Test
    fun originalDefaultConstructorRetainsItsMaskAndDefaults() {
        val sessionId = AgentSessionId("binary-contract")
        val actual = snapshotClass.getConstructor(
            *legacyParameterTypes,
            Int::class.javaPrimitiveType,
            Class.forName("kotlin.jvm.internal.DefaultConstructorMarker"),
        ).newInstance(7L, sessionId, *arrayOfNulls<Any?>(7), 508, null)

        assertEquals(7L, actual.revision)
        assertEquals(sessionId, actual.sessionId)
        assertNull(actual.request)
        assertNull(actual.runId)
        assertNull(actual.state)
        assertEquals(AgentSessionPhase.NEW, actual.phase)
        assertNull(actual.recovery)
        assertNull(actual.failure)
        assertNull(actual.lastEvent)
        assertEquals(emptyList(), actual.toolExecutions)
    }

    @Test
    fun originalCopyAndDefaultCopyPreserveTheJournal() {
        val original = snapshot(toolExecutions = journal("exec-1"))
        val replacement = snapshot(sessionId = AgentSessionId("copied-session"))
        val copied = snapshotClass.getMethod("copy", *legacyParameterTypes)
            .invoke(original, *legacyArguments(replacement)) as AgentSessionRuntimeSnapshot

        assertEquals(replacement.toolExecutions + journal("exec-1"), copied.toolExecutions)
        assertEquals(replacement.revision, copied.revision)
        assertEquals(replacement.sessionId, copied.sessionId)
        assertEquals(replacement.phase, copied.phase)

        val defaultCopy = snapshotClass.getDeclaredMethod(
            "copy\$default",
            snapshotClass,
            *legacyParameterTypes,
            Int::class.javaPrimitiveType,
            Any::class.java,
        ).invoke(null, original, 0L, *arrayOfNulls<Any?>(8), 511, null) as AgentSessionRuntimeSnapshot

        assertEquals(original, defaultCopy)
        assertEquals(journal("exec-1"), defaultCopy.toolExecutions)
    }

    @Test
    fun copyCanExplicitlyReplaceOrClearTheJournal() {
        val original = snapshot(toolExecutions = journal("exec-1"))
        val updated = original.copy(toolExecutions = journal("exec-2"))

        assertEquals(journal("exec-2"), updated.toolExecutions)
        assertEquals(original, updated.copy(toolExecutions = journal("exec-1")))
        assertEquals(snapshot(), updated.copy(toolExecutions = emptyList()))
    }

    private fun journal(executionId: String) = listOf(
        ToolExecutionRecord(
            executionId = executionId,
            toolCallId = "call-1",
            toolName = "lookup",
            callOrdinal = 1,
            state = ToolExecutionState.PENDING,
        ),
    )

    private fun snapshot(
        sessionId: AgentSessionId = AgentSessionId("original-session"),
        toolExecutions: List<ToolExecutionRecord> = emptyList(),
    ) = AgentSessionRuntimeSnapshot(
        revision = 7L,
        sessionId = sessionId,
        request = AgentRequest(
            sessionId = sessionId,
            messages = emptyList(),
            model = ModelDescriptor(provider = "test-provider", model = "test-model"),
        ),
        runId = AgentRunId("run-1"),
        state = AgentStateSnapshot(messages = emptyList()),
        phase = AgentSessionPhase.ACTIVE,
        recovery = AgentRecoveryInfo(
            sessionId = sessionId,
            disposition = AgentRecoveryDisposition.ACTIVE,
        ),
        failure = AgentFailureCode.INTERNAL,
        lastEvent = AgentEvent.Started(sessionId, AgentRunId("run-1")),
        toolExecutions = toolExecutions,
    )

    private fun legacyArguments(snapshot: AgentSessionRuntimeSnapshot): Array<Any?> = arrayOf(
        snapshot.revision,
        snapshot.sessionId,
        snapshot.request,
        snapshot.runId,
        snapshot.state,
        snapshot.phase,
        snapshot.recovery,
        snapshot.failure,
        snapshot.lastEvent,
    )
}
