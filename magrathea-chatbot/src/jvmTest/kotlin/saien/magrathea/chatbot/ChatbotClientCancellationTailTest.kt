package saien.magrathea.chatbot

import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.runTest
import saien.magrathea.core.AgentEvent
import saien.magrathea.core.AgentMessage
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
import saien.magrathea.runtime.AgentSessionLease
import saien.magrathea.runtime.AgentSessionManager
import saien.magrathea.runtime.AgentSessionPhase
import saien.magrathea.runtime.AgentSessionRuntimeSnapshot

class ChatbotClientCancellationTailTest {
    @Test
    fun cancellationAtFinalDeliveryBoundaryReleasesTheUnreachableLease() = runTest {
        val dispatcher = ManualDispatcher()
        val manager = TailCancellationManager()
        val client = createChatbotClient(
            sessionManager = manager,
            requestFactory = DefaultChatbotRequestFactory(),
            sessionDispatcher = dispatcher,
        )
        val handoff = async(dispatcher) {
            client.restoreSession(manager.sessionId.value)
        }
        dispatcher.runUntil { manager.acquireEntered.isCompleted }

        val clientMutex = client.mutexForTest()
        clientMutex.lock()
        manager.releaseAcquire.complete(Unit)
        assertTrue(dispatcher.runNext())

        val blockerWaiting = CompletableDeferred<Unit>()
        val blockerAcquired = CompletableDeferred<Unit>()
        val releaseBlocker = CompletableDeferred<Unit>()
        val blocker = async(dispatcher) {
            blockerWaiting.complete(Unit)
            clientMutex.lock()
            try {
                blockerAcquired.complete(Unit)
                releaseBlocker.await()
            } finally {
                clientMutex.unlock()
            }
        }
        dispatcher.runUntil { blockerWaiting.isCompleted }

        // FIFO transfer lets registration finish, then holds the final delivery boundary.
        clientMutex.unlock()
        dispatcher.runUntil { blockerAcquired.isCompleted }
        assertEquals(1, client.registeredSessionCountForTest())
        assertFalse(handoff.isCompleted)
        handoff.cancel()
        releaseBlocker.complete(Unit)
        dispatcher.runUntilIdle()

        blocker.await()
        assertFailsWith<CancellationException> { handoff.await() }
        val releaseCountBeforeClientClose = manager.lease.releaseCount
        val closing = async(dispatcher) { client.close() }
        dispatcher.runUntil { closing.isCompleted }
        closing.await()
        assertEquals(1, releaseCountBeforeClientClose)
    }
}

private class ManualDispatcher : CoroutineDispatcher() {
    private val tasks = ArrayDeque<Runnable>()

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        tasks.addLast(block)
    }

    fun runNext(): Boolean {
        val task = tasks.removeFirstOrNull() ?: return false
        task.run()
        return true
    }

    fun runUntilIdle() {
        while (runNext()) {
            // Drain tasks scheduled by the handoff and rejected-resource cleanup.
        }
    }

    fun runUntil(condition: () -> Boolean) {
        repeat(1_000) {
            if (condition()) return
            check(runNext()) { "Manual dispatcher became idle before the condition was met" }
        }
        error("Manual dispatcher did not reach the condition")
    }
}

private class TailCancellationManager : AgentSessionManager {
    val sessionId = AgentSessionId("cancellation-tail")
    val lease = TailCancellationLease(sessionId)
    override val liveSessionIds: StateFlow<Set<AgentSessionId>> =
        MutableStateFlow(setOf(sessionId))
    val acquireEntered = CompletableDeferred<Unit>()
    val releaseAcquire = CompletableDeferred<Unit>()

    override suspend fun create(sessionId: AgentSessionId): AgentSessionLease =
        error("Not used")

    override suspend fun acquire(sessionId: AgentSessionId): AgentSessionLease {
        assertEquals(this.sessionId, sessionId)
        acquireEntered.complete(Unit)
        releaseAcquire.await()
        return lease
    }

    override suspend fun listSessions(): List<AgentSessionSnapshot> = emptyList()
    override suspend fun delete(sessionId: AgentSessionId) = Unit
    override suspend fun clear() = Unit
    override suspend fun close() = Unit
}

private class TailCancellationLease(
    override val sessionId: AgentSessionId,
) : AgentSessionLease {
    private val request = AgentRequest(
        sessionId = sessionId,
        messages = listOf(
            AgentMessage(
                role = MessageRole.USER,
                parts = listOf(TextPart("tail cancellation")),
            ),
        ),
        model = ModelDescriptor("test", "test-model"),
    )
    private val mutableState = MutableStateFlow(
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
        ),
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
    override suspend fun resume() = Unit
    override suspend fun interrupt(): AgentRecoveryInfo = error("Not used")
    override suspend fun inspectRecovery(): AgentRecoveryInfo = error("Not used")
    override suspend fun cancel() = Unit
    override suspend fun replaceIdleRequest(request: AgentRequest) = Unit
    override suspend fun awaitIdle() = Unit

    override suspend fun release() {
        if (attached) {
            attached = false
            releaseCount += 1
        }
    }
}

private fun ChatbotClient.mutexForTest(): Mutex {
    val field = ChatbotClient::class.java.getDeclaredField("mutex")
    field.isAccessible = true
    return field.get(this) as Mutex
}

private fun ChatbotClient.registeredSessionCountForTest(): Int {
    val field = ChatbotClient::class.java.getDeclaredField("sessions")
    field.isAccessible = true
    return (field.get(this) as List<*>).size
}
