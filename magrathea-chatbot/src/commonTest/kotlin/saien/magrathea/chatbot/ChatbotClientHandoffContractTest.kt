@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package saien.magrathea.chatbot

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import saien.magrathea.core.AgentEvent
import saien.magrathea.core.AgentInterruption
import saien.magrathea.core.AgentInterruptionReason
import saien.magrathea.core.AgentMessage
import saien.magrathea.core.AgentRecoveryDisposition
import saien.magrathea.core.AgentRecoveryInfo
import saien.magrathea.core.AgentRequest
import saien.magrathea.core.AgentSessionId
import saien.magrathea.core.AgentSessionSnapshot
import saien.magrathea.core.AgentStateSnapshot
import saien.magrathea.core.AgentStatus
import saien.magrathea.core.MessageRole
import saien.magrathea.core.ModelDescriptor
import saien.magrathea.core.StopReason
import saien.magrathea.core.TextPart
import saien.magrathea.runtime.AgentSessionErrorCode
import saien.magrathea.runtime.AgentSessionException
import saien.magrathea.runtime.AgentSessionLease
import saien.magrathea.runtime.AgentSessionManager
import saien.magrathea.runtime.AgentSessionPhase
import saien.magrathea.runtime.AgentSessionRuntimeSnapshot

class ChatbotClientHandoffContractTest {
    @Test
    fun createHandoffLinearizesBeforeDelete() = runTest {
        assertHandoffLinearizesBeforeDestruction(HandoffKind.CREATE, DestructionKind.DELETE)
    }

    @Test
    fun restoreHandoffLinearizesBeforeDelete() = runTest {
        assertHandoffLinearizesBeforeDestruction(HandoffKind.RESTORE, DestructionKind.DELETE)
    }

    @Test
    fun resumeHandoffLinearizesBeforeDelete() = runTest {
        assertHandoffLinearizesBeforeDestruction(HandoffKind.RESUME, DestructionKind.DELETE)
    }

    @Test
    fun createHandoffLinearizesBeforeClear() = runTest {
        assertHandoffLinearizesBeforeDestruction(HandoffKind.CREATE, DestructionKind.CLEAR)
    }

    @Test
    fun restoreHandoffLinearizesBeforeClear() = runTest {
        assertHandoffLinearizesBeforeDestruction(HandoffKind.RESTORE, DestructionKind.CLEAR)
    }

    @Test
    fun resumeHandoffLinearizesBeforeClear() = runTest {
        assertHandoffLinearizesBeforeDestruction(HandoffKind.RESUME, DestructionKind.CLEAR)
    }

    @Test
    fun deleteFenceBlocksOnlyTheMatchingSessionHandoff() = runTest {
        val manager = SelectiveDeleteManager()
        val client = createChatbotClient(
            sessionManager = manager,
            requestFactory = DefaultChatbotRequestFactory(),
            sessionDispatcher = StandardTestDispatcher(testScheduler),
        )

        val deletion = async { client.deleteSession(manager.deletedId.value) }
        manager.deleteEntered.await()

        val matchingRestore = async {
            runCatching { client.restoreSession(manager.deletedId.value) }
        }
        val independentRestore = async { client.restoreSession(manager.retainedId.value) }
        runCurrent()

        val matchingAcquireWasBlocked = manager.deletedAcquireCalls == 0
        val independentRestoreCompleted = independentRestore.isCompleted
        val retained = independentRestore.await()

        manager.releaseDelete.complete(Unit)
        deletion.await()
        val failure = matchingRestore.await().exceptionOrNull()
        assertTrue(matchingAcquireWasBlocked)
        assertTrue(independentRestoreCompleted)
        assertEquals(manager.retainedId.value, retained.snapshot().sessionId)
        assertTrue(failure is ChatbotException)
        assertEquals(ChatbotFailure.NOT_FOUND, failure.failure)
        assertEquals(1, manager.deletedAcquireCalls)

        retained.close()
        client.close()
    }

    private suspend fun TestScope.assertHandoffLinearizesBeforeDestruction(
        handoffKind: HandoffKind,
        destructionKind: DestructionKind,
    ) {
        val manager = ControlledHandoffManager(handoffKind)
        val client = createChatbotClient(
            sessionManager = manager,
            requestFactory = DefaultChatbotRequestFactory(),
            sessionDispatcher = StandardTestDispatcher(testScheduler),
        )
        val handoff = async {
            when (handoffKind) {
                HandoffKind.CREATE -> client.createSession(testChatbotConfiguration())
                HandoffKind.RESTORE -> client.restoreSession(manager.initialSessionId.value)
                HandoffKind.RESUME -> client.resumeSession(manager.initialSessionId.value)
            }
        }
        manager.handoffEntered.await()
        val targetId = manager.targetSessionId
        val destruction = async {
            when (destructionKind) {
                DestructionKind.DELETE -> client.deleteSession(targetId.value)
                DestructionKind.CLEAR -> client.clearHistory()
            }
        }
        runCurrent()

        val destructionEnteredBeforeHandoff = manager.destructionEntered.isCompleted
        val destructionCompletedBeforeHandoff = destruction.isCompleted

        manager.releaseHandoff.complete(Unit)
        runCurrent()
        val delivered = handoff.await()
        manager.destructionEntered.await()

        val destructionWaitedForItsOwnRelease = !destruction.isCompleted
        manager.releaseDestruction.complete(Unit)
        destruction.await()

        assertFalse(destructionEnteredBeforeHandoff)
        assertFalse(destructionCompletedBeforeHandoff)
        assertTrue(destructionWaitedForItsOwnRelease)
        assertEquals(
            ChatbotFailure.CLOSED,
            assertFailsWith<ChatbotException> { delivered.send("after destruction") }.failure,
        )
        assertEquals(1, manager.lease.releaseCount)
        client.close()
    }
}

private enum class HandoffKind {
    CREATE,
    RESTORE,
    RESUME,
}

private enum class DestructionKind {
    DELETE,
    CLEAR,
}

private class ControlledHandoffManager(
    private val handoffKind: HandoffKind,
) : AgentSessionManager {
    val initialSessionId = AgentSessionId("${handoffKind.name.lowercase()}-handoff")
    val handoffEntered = CompletableDeferred<Unit>()
    val releaseHandoff = CompletableDeferred<Unit>()
    val destructionEntered = CompletableDeferred<Unit>()
    val releaseDestruction = CompletableDeferred<Unit>()

    private val mutableLiveSessionIds = MutableStateFlow<Set<AgentSessionId>>(emptySet())
    override val liveSessionIds: StateFlow<Set<AgentSessionId>> = mutableLiveSessionIds

    lateinit var lease: ControlledHandoffLease
        private set

    val targetSessionId: AgentSessionId
        get() = lease.sessionId

    override suspend fun create(sessionId: AgentSessionId): AgentSessionLease {
        assertEquals(HandoffKind.CREATE, handoffKind)
        lease = ControlledHandoffLease(sessionId, resumable = false)
        mutableLiveSessionIds.value = setOf(sessionId)
        handoffEntered.complete(Unit)
        releaseHandoff.await()
        return lease
    }

    override suspend fun acquire(sessionId: AgentSessionId): AgentSessionLease {
        assertEquals(initialSessionId, sessionId)
        lease = ControlledHandoffLease(
            sessionId = sessionId,
            resumable = handoffKind == HandoffKind.RESUME,
            onResume = if (handoffKind == HandoffKind.RESUME) {
                {
                    handoffEntered.complete(Unit)
                    releaseHandoff.await()
                }
            } else {
                null
            },
        )
        mutableLiveSessionIds.value = setOf(sessionId)
        if (handoffKind == HandoffKind.RESTORE) {
            handoffEntered.complete(Unit)
            releaseHandoff.await()
        }
        return lease
    }

    override suspend fun listSessions(): List<AgentSessionSnapshot> = emptyList()

    override suspend fun delete(sessionId: AgentSessionId) {
        assertEquals(targetSessionId, sessionId)
        destructionEntered.complete(Unit)
        releaseDestruction.await()
        lease.markDeleted()
        mutableLiveSessionIds.value = emptySet()
    }

    override suspend fun clear() {
        destructionEntered.complete(Unit)
        releaseDestruction.await()
        lease.markDeleted()
        mutableLiveSessionIds.value = emptySet()
    }

    override suspend fun close() = Unit
}

private class ControlledHandoffLease(
    override val sessionId: AgentSessionId,
    resumable: Boolean,
    private val onResume: (suspend () -> Unit)? = null,
) : AgentSessionLease {
    private val request = AgentRequest(
        sessionId = sessionId,
        messages = listOf(
            AgentMessage(
                role = MessageRole.USER,
                parts = listOf(TextPart("handoff")),
            ),
        ),
        model = ModelDescriptor("test", "test-model"),
    )
    private val interruptedState = AgentStateSnapshot(
        messages = request.messages,
        status = AgentStatus.INTERRUPTED,
        stopReason = StopReason.INTERRUPTED,
    )
    private val mutableState = MutableStateFlow(
        if (resumable) {
            AgentSessionRuntimeSnapshot(
                revision = 0L,
                sessionId = sessionId,
                request = request,
                state = interruptedState,
                phase = AgentSessionPhase.RESUMABLE,
                recovery = AgentRecoveryInfo(
                    sessionId = sessionId,
                    disposition = AgentRecoveryDisposition.RESUMABLE,
                    status = AgentStatus.INTERRUPTED,
                    state = interruptedState,
                    interruption = AgentInterruption(AgentInterruptionReason.ORPHANED),
                ),
            )
        } else {
            AgentSessionRuntimeSnapshot(
                revision = 0L,
                sessionId = sessionId,
                request = request,
                state = AgentStateSnapshot(
                    messages = request.messages,
                    status = AgentStatus.COMPLETED,
                    stopReason = StopReason.COMPLETED,
                ),
                phase = AgentSessionPhase.TERMINAL,
            )
        },
    )
    private val mutableEvents = MutableSharedFlow<AgentEvent>()
    private var attached = true

    var releaseCount: Int = 0
        private set

    override val state: StateFlow<AgentSessionRuntimeSnapshot> = mutableState
    override val events: SharedFlow<AgentEvent> = mutableEvents
    override val isAttached: Boolean
        get() = attached

    override suspend fun start(request: AgentRequest) = Unit

    override suspend fun resume() {
        onResume?.invoke()
    }

    override suspend fun interrupt(): AgentRecoveryInfo = requireNotNull(mutableState.value.recovery)
    override suspend fun inspectRecovery(): AgentRecoveryInfo = requireNotNull(mutableState.value.recovery)
    override suspend fun cancel() = Unit
    override suspend fun replaceIdleRequest(request: AgentRequest) = Unit
    override suspend fun awaitIdle() = Unit

    override suspend fun release() {
        if (attached) {
            attached = false
            releaseCount += 1
        }
    }

    fun markDeleted() {
        val current = mutableState.value
        mutableState.value = current.copy(
            revision = current.revision + 1L,
            phase = AgentSessionPhase.DELETED,
        )
    }
}

private class SelectiveDeleteManager : AgentSessionManager {
    val deletedId = AgentSessionId("deleted")
    val retainedId = AgentSessionId("retained")
    val deleteEntered = CompletableDeferred<Unit>()
    val releaseDelete = CompletableDeferred<Unit>()

    private val mutableLiveSessionIds = MutableStateFlow(setOf(deletedId, retainedId))
    override val liveSessionIds: StateFlow<Set<AgentSessionId>> = mutableLiveSessionIds
    private var deleted = false

    var deletedAcquireCalls: Int = 0
        private set

    override suspend fun create(sessionId: AgentSessionId): AgentSessionLease =
        ControlledHandoffLease(sessionId, resumable = false)

    override suspend fun acquire(sessionId: AgentSessionId): AgentSessionLease {
        if (sessionId == deletedId) {
            deletedAcquireCalls += 1
            if (deleted) throw AgentSessionException(AgentSessionErrorCode.NOT_FOUND)
        }
        return ControlledHandoffLease(sessionId, resumable = false)
    }

    override suspend fun listSessions(): List<AgentSessionSnapshot> = emptyList()

    override suspend fun delete(sessionId: AgentSessionId) {
        assertEquals(deletedId, sessionId)
        deleteEntered.complete(Unit)
        releaseDelete.await()
        deleted = true
        mutableLiveSessionIds.value = setOf(retainedId)
    }

    override suspend fun clear() = Unit
    override suspend fun close() = Unit
}
