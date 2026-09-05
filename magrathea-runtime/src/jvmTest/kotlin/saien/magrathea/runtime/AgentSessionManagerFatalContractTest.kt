package saien.magrathea.runtime

import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.StandardTestDispatcher
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
import saien.magrathea.core.MessageRole
import saien.magrathea.core.ModelDescriptor
import saien.magrathea.core.StopReason
import saien.magrathea.core.TextPart
import saien.magrathea.provider.api.InMemoryProviderRegistry
import saien.magrathea.provider.api.ProviderAdapter
import saien.magrathea.provider.api.ProviderChunk
import saien.magrathea.provider.api.ProviderRequest

class AgentSessionManagerFatalContractTest {
    @Test
    fun directFatalExecutionSettlesMatchingCheckpointAndCanResume() {
        val fatal = TestFatalError(Any())

        val escaped = assertFailsWith<TestFatalError> {
            runTest {
                val persistence = InMemoryAgentPersistence()
                val runner = FatalRecoveryRunner(
                    persistence = persistence,
                    firstFailure = fatal,
                    recoveryMode = RecoveryMode.MATCHING_CHECKPOINT,
                )
                val sessionId = AgentSessionId("fatal-checkpoint")
                val request = request(sessionId)
                persistence.commit(terminalSnapshot(request), checkpoint = null)
                val manager = DefaultAgentSessionManager(runner, persistence)

                try {
                    val lease = manager.acquire(sessionId)
                    val inspectCallsBeforeStart = runner.inspectCalls

                    lease.start(request)
                    lease.awaitIdle()

                    assertEquals(inspectCallsBeforeStart + 1, runner.inspectCalls)
                    assertEquals(
                        AgentRunId("run-${sessionId.value}"),
                        persistence.load(sessionId)?.snapshot?.runId,
                    )
                    assertEquals(AgentSessionPhase.RESUMABLE, lease.state.value.phase)
                    assertFalse(lease.state.value.lastEvent is AgentEvent.Failed)
                    lease.resume()
                    lease.awaitIdle()
                    assertEquals(1, runner.resumeCalls)
                    assertEquals(AgentSessionPhase.TERMINAL, lease.state.value.phase)
                    assertEquals(AgentStatus.COMPLETED, lease.state.value.state?.status)
                    lease.release()
                } finally {
                    manager.close()
                }
            }
        }

        assertSame(fatal, escaped)
    }

    @Test
    fun wrappedFatalWithoutARecordRestoresPreviousStateAndCanStartAgain() {
        val fatal = TestFatalError(Any())

        val escaped = assertFailsWith<TestFatalError> {
            runTest {
                val persistence = InMemoryAgentPersistence()
                val runner = FatalRecoveryRunner(
                    persistence = persistence,
                    firstFailure = TestRecoverableException(fatal),
                    recoveryMode = RecoveryMode.DELETE_RECORD,
                )
                val sessionId = AgentSessionId("fatal-no-record")
                val request = request(sessionId)
                persistence.commit(terminalSnapshot(request), checkpoint = null)
                val manager = DefaultAgentSessionManager(runner, persistence)

                try {
                    val lease = manager.acquire(sessionId)

                    lease.start(request)
                    lease.awaitIdle()

                    assertEquals(AgentSessionPhase.TERMINAL, lease.state.value.phase)
                    assertEquals(AgentStatus.COMPLETED, lease.state.value.state?.status)
                    assertFalse(lease.state.value.lastEvent is AgentEvent.Failed)
                    lease.start(request)
                    lease.awaitIdle()
                    assertEquals(2, runner.runCalls)
                    assertEquals(AgentSessionPhase.TERMINAL, lease.state.value.phase)
                    assertEquals(AgentStatus.COMPLETED, lease.state.value.state?.status)
                    lease.release()
                } finally {
                    manager.close()
                }
            }
        }

        assertSame(fatal, escaped)
    }

    @Test
    fun fatalRefreshFailureSettlesOwnershipButRequiresRecoveryInspectionBeforeRestart() {
        val executionFatal = TestFatalError(Any())
        val refreshFatal = TestFatalError(Any())

        val escaped = assertFailsWith<TestFatalError> {
            runTest {
                val delegate = InMemoryAgentPersistence()
                val persistence = FatalRefreshPersistence(delegate, refreshFatal)
                val runner = FatalRecoveryRunner(
                    persistence = persistence,
                    firstFailure = executionFatal,
                    recoveryMode = RecoveryMode.DELETE_RECORD,
                )
                val sessionId = AgentSessionId("fatal-refresh")
                val request = request(sessionId)
                delegate.commit(terminalSnapshot(request), checkpoint = null)
                val manager = DefaultAgentSessionManager(runner, persistence)

                try {
                    val lease = manager.acquire(sessionId)

                    lease.start(request)
                    lease.awaitIdle()

                    assertEquals(AgentSessionPhase.RECOVERY_BLOCKED, lease.state.value.phase)
                    assertFalse(lease.state.value.isExecuting)
                    assertEquals(
                        AgentSessionErrorCode.BUSY,
                        assertFailsWith<AgentSessionException> { lease.start(request) }.code,
                    )
                    assertEquals(1, runner.runCalls)
                    assertEquals(AgentRecoveryDisposition.NOT_FOUND, lease.inspectRecovery().disposition)
                    lease.start(request)
                    lease.awaitIdle()
                    assertEquals(2, runner.runCalls)
                    assertEquals(AgentSessionPhase.TERMINAL, lease.state.value.phase)
                    lease.release()
                } finally {
                    manager.close()
                }
            }
        }

        assertSame(executionFatal, escaped)
        assertTrue(escaped.suppressedExceptions.any { failure -> failure === refreshFatal })
    }

    @Test
    fun realRunnerCannotReenterProviderAfterFatalWhenRecoveryReadFails() {
        val fatal = TestFatalError(Any())
        var providerCalls = 0
        val escaped = assertFailsWith<TestFatalError> {
            runTest {
                val dispatcher = StandardTestDispatcher(testScheduler)
                val delegate = InMemoryAgentPersistence()
                var failNextLoad = false
                val persistence = object : AgentPersistence by delegate {
                    override suspend fun load(sessionId: AgentSessionId): AgentPersistenceRecord? {
                        if (failNextLoad) {
                            failNextLoad = false
                            error("transient recovery read failure")
                        }
                        return delegate.load(sessionId)
                    }
                }
                val provider = object : ProviderAdapter {
                    override val key = "test"
                    override suspend fun generate(request: ProviderRequest): Flow<ProviderChunk> = flow {
                        providerCalls += 1
                        failNextLoad = true
                        throw fatal
                    }
                }
                val runner = DefaultAgentRunner(
                    providerRegistry = InMemoryProviderRegistry(listOf(provider)),
                    toolRegistry = InMemoryToolRegistry(),
                    persistence = persistence,
                    dispatcher = dispatcher,
                )
                val manager = DefaultAgentSessionManager(runner, persistence, dispatcher)
                try {
                    val request = request(AgentSessionId("real-fatal-recovery"))
                    val lease = manager.create(request.sessionId)
                    lease.start(request)
                    lease.awaitIdle()
                    val checkpoint = assertNotNull(delegate.load(request.sessionId)?.checkpoint)
                    assertEquals(AgentSessionPhase.RECOVERY_BLOCKED, lease.state.value.phase)
                    assertEquals(
                        AgentSessionErrorCode.BUSY,
                        assertFailsWith<AgentSessionException> { lease.start(request) }.code,
                    )
                    assertEquals(checkpoint, delegate.load(request.sessionId)?.checkpoint)
                    assertEquals(AgentRecoveryDisposition.RESUMABLE, lease.inspectRecovery().disposition)
                    lease.release()
                } finally {
                    manager.close()
                }
            }
        }
        assertSame(fatal, escaped)
        assertEquals(1, providerCalls)
    }

    @Test
    fun concurrentRecoveryPublicationWinsOverFatalFallbackRestoration() {
        val fatal = TestFatalError(Any())

        val escaped = assertFailsWith<TestFatalError> {
            runTest {
                val delegate = InMemoryAgentPersistence()
                val persistence = BlockingFatalRefreshPersistence(delegate)
                val sessionId = AgentSessionId("fatal-concurrent-recovery")
                val request = request(sessionId)
                val terminal = terminalSnapshot(request)
                delegate.commit(terminal, checkpoint = null)
                val runner = ConcurrentRecoveryRunner(
                    persistence = persistence,
                    fatal = fatal,
                    terminal = terminal,
                )
                val manager = DefaultAgentSessionManager(runner, persistence)

                try {
                    val lease = manager.acquire(sessionId)

                    lease.start(request)
                    persistence.refreshLoadStarted.await()
                    val recovery = lease.inspectRecovery()
                    assertEquals(AgentRecoveryDisposition.RESUMABLE, recovery.disposition)
                    persistence.continueRefreshLoad.complete(Unit)
                    lease.awaitIdle()

                    assertEquals(AgentSessionPhase.RESUMABLE, lease.state.value.phase)
                    assertEquals(AgentStatus.INTERRUPTED, lease.state.value.state?.status)
                    assertEquals(recovery.runId, lease.state.value.runId)
                    lease.release()
                } finally {
                    persistence.continueRefreshLoad.complete(Unit)
                    manager.close()
                }
            }
        }

        assertSame(fatal, escaped)
    }
}

private enum class RecoveryMode {
    MATCHING_CHECKPOINT,
    DELETE_RECORD,
}

private class FatalRecoveryRunner(
    private val persistence: AgentPersistence,
    private val firstFailure: Throwable,
    private val recoveryMode: RecoveryMode,
) : AgentRunner {
    var runCalls: Int = 0
        private set
    var resumeCalls: Int = 0
        private set
    var inspectCalls: Int = 0
        private set

    private var request: AgentRequest? = null
    private var runId: AgentRunId? = null

    override fun run(request: AgentRequest): Flow<AgentEvent> = flow {
        runCalls += 1
        this@FatalRecoveryRunner.request = request
        val runId = AgentRunId("run-${request.sessionId.value}")
        this@FatalRecoveryRunner.runId = runId
        emit(AgentEvent.Started(request.sessionId, runId))
        if (runCalls == 1) {
            when (recoveryMode) {
                RecoveryMode.MATCHING_CHECKPOINT -> persistRecoverable(request, runId)
                RecoveryMode.DELETE_RECORD -> persistence.deleteSession(request.sessionId)
            }
            throw firstFailure
        }
        emitCompletion(request, runId)
    }

    override suspend fun resume(sessionId: AgentSessionId): Flow<AgentEvent> {
        resumeCalls += 1
        val request = checkNotNull(request)
        val runId = checkNotNull(runId)
        return flow {
            emit(AgentEvent.Started(sessionId, runId))
            emitCompletion(request, runId)
        }
    }

    override suspend fun interrupt(sessionId: AgentSessionId): AgentRecoveryInfo =
        inspectRecovery(sessionId)

    override suspend fun inspectRecovery(sessionId: AgentSessionId): AgentRecoveryInfo {
        inspectCalls += 1
        val record = persistence.load(sessionId) ?: return AgentRecoveryInfo(
            sessionId = sessionId,
            disposition = AgentRecoveryDisposition.NOT_FOUND,
        )
        val checkpoint = record.checkpoint
        return AgentRecoveryInfo(
            sessionId = sessionId,
            runId = record.snapshot.runId,
            disposition = if (checkpoint == null) {
                AgentRecoveryDisposition.TERMINAL
            } else {
                AgentRecoveryDisposition.RESUMABLE
            },
            status = record.snapshot.state.status,
            state = record.snapshot.state,
            cursor = checkpoint?.cursor,
            interruption = record.snapshot.interruption
                ?: checkpoint?.let { AgentInterruption(AgentInterruptionReason.ORPHANED) },
        )
    }

    override suspend fun cancel(sessionId: AgentSessionId) {
        throw CancellationException("No active collector")
    }

    private suspend fun persistRecoverable(request: AgentRequest, runId: AgentRunId) {
        val state = AgentStateSnapshot(
            messages = request.messages,
            status = AgentStatus.RUNNING,
        )
        persistence.commit(
            snapshot = AgentSessionSnapshot(
                sessionId = request.sessionId,
                runId = runId,
                request = request,
                state = state,
                updatedAtEpochMs = 2L,
            ),
            checkpoint = AgentCheckpoint(
                sessionId = request.sessionId,
                runId = runId,
                cursor = AgentResumeCursor(0, AgentResumePhase.TURN_PREPARING),
                state = state,
            ),
        )
    }

    private suspend fun kotlinx.coroutines.flow.FlowCollector<AgentEvent>.emitCompletion(
        request: AgentRequest,
        runId: AgentRunId,
    ) {
        val state = AgentStateSnapshot(
            messages = request.messages,
            status = AgentStatus.COMPLETED,
            stopReason = StopReason.COMPLETED,
        )
        persistence.commit(
            snapshot = AgentSessionSnapshot(
                sessionId = request.sessionId,
                runId = runId,
                request = request,
                state = state,
                updatedAtEpochMs = 3L,
            ),
            checkpoint = null,
        )
        emit(AgentEvent.Completed(request.sessionId, state))
    }
}

private class BlockingFatalRefreshPersistence(
    private val delegate: AgentPersistence,
) : AgentPersistence {
    val refreshLoadStarted = CompletableDeferred<Unit>()
    val continueRefreshLoad = CompletableDeferred<Unit>()
    private var blockNextLoad = false

    fun blockNextLoad() {
        blockNextLoad = true
    }

    override suspend fun commit(snapshot: AgentSessionSnapshot, checkpoint: AgentCheckpoint?) =
        delegate.commit(snapshot, checkpoint)

    override suspend fun load(sessionId: AgentSessionId): AgentPersistenceRecord? {
        if (blockNextLoad) {
            blockNextLoad = false
            refreshLoadStarted.complete(Unit)
            continueRefreshLoad.await()
            return null
        }
        return delegate.load(sessionId)
    }

    override suspend fun listSessions(): List<AgentSessionSnapshot> = delegate.listSessions()

    override suspend fun deleteSession(sessionId: AgentSessionId) =
        delegate.deleteSession(sessionId)

    override suspend fun clear() = delegate.clear()
}

private class FatalRefreshPersistence(
    private val delegate: AgentPersistence,
    private val refreshFailure: Throwable,
) : AgentPersistence {
    private var failNextLoad = false

    override suspend fun commit(snapshot: AgentSessionSnapshot, checkpoint: AgentCheckpoint?) =
        delegate.commit(snapshot, checkpoint)

    override suspend fun load(sessionId: AgentSessionId): AgentPersistenceRecord? {
        if (failNextLoad) {
            failNextLoad = false
            throw refreshFailure
        }
        return delegate.load(sessionId)
    }

    override suspend fun listSessions(): List<AgentSessionSnapshot> = delegate.listSessions()

    override suspend fun deleteSession(sessionId: AgentSessionId) {
        delegate.deleteSession(sessionId)
        failNextLoad = true
    }

    override suspend fun clear() = delegate.clear()
}

private class ConcurrentRecoveryRunner(
    private val persistence: BlockingFatalRefreshPersistence,
    private val fatal: TestFatalError,
    private val terminal: AgentSessionSnapshot,
) : AgentRunner {
    private val recoveryRunId = AgentRunId("run-${terminal.sessionId.value}")
    private val recoveryState = terminal.state.copy(
        status = AgentStatus.INTERRUPTED,
        stopReason = StopReason.INTERRUPTED,
    )
    private var recoveryAvailable = false

    override fun run(request: AgentRequest): Flow<AgentEvent> = flow {
        emit(AgentEvent.Started(request.sessionId, recoveryRunId))
        recoveryAvailable = true
        persistence.blockNextLoad()
        throw fatal
    }

    override suspend fun resume(sessionId: AgentSessionId): Flow<AgentEvent> =
        error("Resume is not part of this contract")

    override suspend fun interrupt(sessionId: AgentSessionId): AgentRecoveryInfo =
        inspectRecovery(sessionId)

    override suspend fun inspectRecovery(sessionId: AgentSessionId): AgentRecoveryInfo =
        if (recoveryAvailable) {
            AgentRecoveryInfo(
                sessionId = sessionId,
                runId = recoveryRunId,
                disposition = AgentRecoveryDisposition.RESUMABLE,
                status = recoveryState.status,
                state = recoveryState,
                interruption = AgentInterruption(AgentInterruptionReason.ORPHANED),
            )
        } else {
            AgentRecoveryInfo(
                sessionId = sessionId,
                runId = terminal.runId,
                disposition = AgentRecoveryDisposition.TERMINAL,
                status = terminal.state.status,
                state = terminal.state,
            )
        }

    override suspend fun cancel(sessionId: AgentSessionId) = Unit
}

private fun request(sessionId: AgentSessionId): AgentRequest = AgentRequest(
    sessionId = sessionId,
    messages = listOf(
        AgentMessage(
            role = MessageRole.USER,
            parts = listOf(TextPart("hello")),
        ),
    ),
    model = ModelDescriptor(provider = "test", model = "test-model"),
)

private fun terminalSnapshot(request: AgentRequest): AgentSessionSnapshot = AgentSessionSnapshot(
    sessionId = request.sessionId,
    runId = AgentRunId("stored-${request.sessionId.value}"),
    request = request,
    state = AgentStateSnapshot(
        messages = request.messages,
        status = AgentStatus.COMPLETED,
        stopReason = StopReason.COMPLETED,
    ),
    updatedAtEpochMs = 1L,
)
