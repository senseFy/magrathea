package saien.magrathea.chatbot

import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import saien.magrathea.core.AgentPersistence
import saien.magrathea.core.AgentRunner
import saien.magrathea.core.AgentSessionId
import saien.magrathea.core.AgentSessionSnapshot
import saien.magrathea.core.AgentStatus
import saien.magrathea.core.text
import saien.magrathea.runtime.AgentSessionErrorCode
import saien.magrathea.runtime.AgentSessionException
import saien.magrathea.runtime.AgentSessionInvalidationScope
import saien.magrathea.runtime.AgentSessionLease
import saien.magrathea.runtime.AgentSessionManager
import saien.magrathea.runtime.AgentSessionPhase
import saien.magrathea.runtime.AgentSessionRuntimeSnapshot
import saien.magrathea.runtime.DefaultAgentSessionManager

fun interface ChatbotStateObserver {
    fun onState(snapshot: ChatbotSnapshot)
}

class ChatbotObservation internal constructor(
    private val job: Job,
) {
    fun cancel() {
        job.cancel()
    }
}

/** One observable Chatbot facade attached to a manager-owned Agent session. */
class ChatbotSession internal constructor(
    private val controller: ChatbotController,
    private val scope: CoroutineScope,
    private val onClosed: suspend (ChatbotSession) -> Unit,
) {
    private val lifecycleMutex = Mutex()
    private val closeCompletion = CompletableDeferred<Result<Unit>>()
    private var closed = false

    internal val boundSessionId: String
        get() = controller.sessionId

    fun snapshot(): ChatbotSnapshot = controller.state.value

    fun observe(observer: ChatbotStateObserver): ChatbotObservation {
        val job = scope.launch {
            controller.state.collect(observer::onState)
        }
        return ChatbotObservation(job)
    }

    @Throws(ChatbotException::class, CancellationException::class)
    suspend fun send(
        text: String,
        attachments: List<ChatbotAttachment> = emptyList(),
        options: ChatbotSendOptions = ChatbotSendOptions(),
    ) = facadeOperation {
        if (text.isBlank() && attachments.isEmpty()) {
            throw ChatbotException(ChatbotFailure.INVALID_ARGUMENT)
        }
        lifecycleMutex.withLock {
            ensureOpen()
            controller.sendMessage(text, attachments, options)
        }
    }

    @Throws(ChatbotException::class, CancellationException::class)
    suspend fun regenerate(messageId: String) = facadeOperation {
        if (messageId.isBlank()) throw ChatbotException(ChatbotFailure.INVALID_ARGUMENT)
        lifecycleMutex.withLock {
            ensureOpen()
            val target = controller.state.value.messages.firstOrNull { it.id == messageId }
            if (target?.role != ChatbotMessageRole.USER) {
                throw ChatbotException(ChatbotFailure.INVALID_ARGUMENT)
            }
            controller.regenerate(messageId)
        }
    }

    /** Changes the Provider profile/model used by future runs while preserving the conversation. */
    @Throws(ChatbotException::class, CancellationException::class)
    suspend fun updateConfiguration(configuration: ChatbotSessionConfiguration) = facadeOperation {
        lifecycleMutex.withLock {
            ensureOpen()
            if (!controller.updateConfiguration(configuration)) {
                throw ChatbotException(ChatbotFailure.BUSY)
            }
        }
    }

    @Throws(ChatbotException::class, CancellationException::class)
    suspend fun resume() = facadeOperation {
        lifecycleMutex.withLock {
            ensureOpen()
            requireResumable()
            controller.resume()
        }
    }

    internal suspend fun resumePersisted() {
        lifecycleMutex.withLock {
            ensureOpen()
            requireResumable()
            controller.resume()
        }
    }

    @Throws(ChatbotException::class, CancellationException::class)
    suspend fun cancel() = facadeOperation {
        lifecycleMutex.withLock {
            if (!closed) controller.cancel()
        }
    }

    @Throws(ChatbotException::class, CancellationException::class)
    suspend fun interrupt() = facadeOperation {
        lifecycleMutex.withLock {
            if (!closed) controller.interrupt()
        }
    }

    /** Concurrent and later callers observe the same controller cleanup outcome. */
    @Throws(ChatbotException::class, CancellationException::class)
    suspend fun close() = facadeOperation {
        closeInternal(notifyOwner = true)
    }

    internal suspend fun closeFromOwner() {
        closeInternal(notifyOwner = false)
    }

    internal fun closeAfterRejectedHandoff() {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                withContext(NonCancellable) { closeInternal(notifyOwner = true) }
            } catch (_: Throwable) {
                // No caller can receive a rejected resource; isolate best-effort cleanup failure.
            }
        }
    }

    private suspend fun closeInternal(notifyOwner: Boolean) {
        val ownsClose = lifecycleMutex.withLock {
            if (closed) {
                false
            } else {
                closed = true
                true
            }
        }
        if (ownsClose) {
            withContext(NonCancellable) {
                var failure: Throwable? = null
                try {
                    controller.close()
                } catch (error: Throwable) {
                    failure = error
                }
                try {
                    scope.cancel()
                } catch (error: Throwable) {
                    if (failure == null) failure = error
                }
                if (notifyOwner) {
                    try {
                        onClosed(this@ChatbotSession)
                    } catch (error: Throwable) {
                        if (failure == null) failure = error
                    }
                }
                closeCompletion.complete(
                    failure?.let { Result.failure(it) } ?: Result.success(Unit),
                )
            }
        }
        closeCompletion.await().getOrThrow()
    }

    private fun ensureOpen() {
        if (closed) throw ChatbotException(ChatbotFailure.CLOSED)
    }

    private fun requireResumable() {
        when (controller.state.value.status) {
            ChatbotStatus.INTERRUPTED -> Unit
            ChatbotStatus.RECOVERY_BLOCKED ->
                throw ChatbotException(ChatbotFailure.RECOVERY_BLOCKED)
            ChatbotStatus.RUNNING,
            ChatbotStatus.WAITING_FOR_TOOL,
            -> throw ChatbotException(ChatbotFailure.BUSY)
            ChatbotStatus.IDLE,
            ChatbotStatus.COMPLETED,
            ChatbotStatus.FAILED,
            ChatbotStatus.CANCELLED,
            -> throw ChatbotException(ChatbotFailure.INVALID_ARGUMENT)
        }
    }
}

/**
 * Owns Chatbot facades; Agent execution ownership remains in [sessionManager].
 *
 * Facade handoff is fenced per session from before lease acquisition through cancellation-safe
 * delivery. A matching delete waits for that handoff, while clear waits for every admitted
 * handoff; unrelated session handoffs remain independent.
 *
 * Delegates invoked by an operation must not synchronously await lifecycle or destructive calls
 * back into this client or one of its sessions. Reverse actions must be scheduled after the
 * delegate returns so they do not wait on the operation fence that owns the callback.
 */
class ChatbotClient internal constructor(
    private val requestFactory: ChatbotRequestFactory,
    private val sessionManager: AgentSessionManager,
    private val ownsSessionManager: Boolean,
    private val closeResources: suspend () -> Unit,
    private val sessionDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private data class InvalidationAdmission(
        val fence: CompletableDeferred<Unit>,
        val preceding: List<CompletableDeferred<Unit>>,
    )

    private val mutex = Mutex()
    private val sessions = mutableListOf<ChatbotSession>()
    private val activeHandoffs =
        mutableMapOf<AgentSessionId, MutableSet<CompletableDeferred<Unit>>>()
    private val sessionInvalidations =
        mutableMapOf<AgentSessionId, CompletableDeferred<Unit>>()
    private var globalInvalidation: CompletableDeferred<Unit>? = null
    private var activeOperations = 0
    private var operationsSettled = CompletableDeferred(Unit)
    private val closeCompletion = CompletableDeferred<Result<Unit>>()
    private var closed = false

    @Throws(ChatbotException::class, CancellationException::class)
    suspend fun createSession(configuration: ChatbotSessionConfiguration): ChatbotSession =
        facadeOperation {
            val sessionId = AgentSessionId.create()
            handoffSession(
                sessionId = sessionId,
                acquireLease = { sessionManager.create(sessionId) },
                resolveConfiguration = { configuration },
            )
        }

    /** Attaches a new facade to canonical persisted or live state without starting execution. */
    @Throws(ChatbotException::class, CancellationException::class)
    suspend fun restoreSession(sessionId: String): ChatbotSession = facadeOperation {
        if (sessionId.isBlank()) throw ChatbotException(ChatbotFailure.INVALID_ARGUMENT)
        val resolvedSessionId = AgentSessionId(sessionId)
        handoffSession(
            sessionId = resolvedSessionId,
            acquireLease = { sessionManager.acquire(resolvedSessionId) },
            resolveConfiguration = { lease ->
                lease.state.value.request?.toChatbotSessionConfiguration()
                    ?: throw ChatbotException(ChatbotFailure.NOT_FOUND)
            },
        )
    }

    @Throws(ChatbotException::class, CancellationException::class)
    suspend fun resumeSession(sessionId: String): ChatbotSession = facadeOperation {
        if (sessionId.isBlank()) throw ChatbotException(ChatbotFailure.INVALID_ARGUMENT)
        val resolvedSessionId = AgentSessionId(sessionId)
        handoffSession(
            sessionId = resolvedSessionId,
            acquireLease = { sessionManager.acquire(resolvedSessionId) },
            resolveConfiguration = { lease ->
                lease.state.value.request?.toChatbotSessionConfiguration()
                    ?: throw ChatbotException(ChatbotFailure.NOT_FOUND)
            },
            prepare = ChatbotSession::resumePersisted,
        )
    }

    @Throws(ChatbotException::class, CancellationException::class)
    suspend fun history(): List<ChatbotHistoryItem> = facadeOperation {
        clientOperation {
            sessionManager.listSessions().map { snapshot ->
                snapshot.toHistoryItem(resolveHistoryStatus(snapshot))
            }
        }
    }

    /**
     * Invalidates this client's facades for [sessionId] when the manager commits its delete fence,
     * even if persistence deletion then fails. In that case the thrown [ChatbotException] reports
     * [ChatbotInvalidationScope.SESSION].
     */
    @Throws(ChatbotException::class, CancellationException::class)
    suspend fun deleteSession(sessionId: String) = facadeOperation {
        if (sessionId.isBlank()) throw ChatbotException(ChatbotFailure.INVALID_ARGUMENT)
        val resolvedSessionId = AgentSessionId(sessionId)
        clientOperation {
            withContext(NonCancellable) {
                val invalidation = beginSessionInvalidation(resolvedSessionId)
                try {
                    invalidation.preceding.forEach { it.await() }
                    destructiveClientMutation(
                        successScope = AgentSessionInvalidationScope.SESSION,
                        sessionId = sessionId,
                        mutation = { sessionManager.delete(resolvedSessionId) },
                    )
                } finally {
                    finishSessionInvalidation(resolvedSessionId, invalidation.fence)
                }
            }
        }
    }

    /**
     * Invalidates every facade owned by this client when the manager commits its clear fence, even
     * if persistence clearing then fails. In that case the thrown [ChatbotException] reports
     * [ChatbotInvalidationScope.ALL_SESSIONS].
     */
    @Throws(ChatbotException::class, CancellationException::class)
    suspend fun clearHistory() = facadeOperation {
        clientOperation {
            withContext(NonCancellable) {
                val invalidation = beginGlobalInvalidation()
                try {
                    invalidation.preceding.forEach { it.await() }
                    destructiveClientMutation(
                        successScope = AgentSessionInvalidationScope.ALL_SESSIONS,
                        mutation = sessionManager::clear,
                    )
                } finally {
                    finishGlobalInvalidation(invalidation.fence)
                }
            }
        }
    }

    /** Rejects new facade operations immediately; all callers await the same cleanup outcome. */
    @Throws(ChatbotException::class, CancellationException::class)
    suspend fun close(): Unit = facadeOperation {
        val operationGate = mutex.withLock {
            if (closed) return@withLock null
            closed = true
            operationsSettled
        }

        if (operationGate != null) {
            withContext(NonCancellable) {
                val outcome = try {
                    operationGate.await()
                    closeOwnedResources()
                    Result.success(Unit)
                } catch (failure: Throwable) {
                    Result.failure(failure)
                }
                closeCompletion.complete(outcome)
            }
        }

        closeCompletion.await().getOrThrow()
    }

    private suspend fun closeOwnedResources() {
        var failure: Throwable? = null
        if (ownsSessionManager) {
            try {
                sessionManager.close()
            } catch (error: Throwable) {
                failure = error
            }
        }
        try {
            closeAllRegisteredSessions()
        } catch (error: Throwable) {
            if (failure == null) failure = error
        }
        try {
            closeResources()
        } catch (error: Throwable) {
            if (failure == null) failure = error
        }
        failure?.let { throw it }
    }

    /**
     * Owns the per-session linearization interval from before lease acquisition through facade
     * delivery or failed-handoff cleanup. A caller that is cancelled after construction or
     * registration never leaves an unreachable facade attachment behind; any execution already
     * started by [prepare] remains manager-owned.
     */
    private suspend fun handoffSession(
        sessionId: AgentSessionId,
        acquireLease: suspend () -> AgentSessionLease,
        resolveConfiguration: (AgentSessionLease) -> ChatbotSessionConfiguration,
        prepare: suspend (ChatbotSession) -> Unit = {},
    ): ChatbotSession {
        beginOperation()
        val handoff = try {
            beginSessionHandoff(sessionId)
        } catch (failure: Throwable) {
            withContext(NonCancellable) { endOperation() }
            throw failure
        }
        var acquiredLease: AgentSessionLease? = null
        var candidate: ChatbotSession? = null
        try {
            val lease = acquireLease()
            acquiredLease = lease
            val session = newSession(lease, resolveConfiguration(lease))
            candidate = session
            prepare(session)
            registerSession(session)
            currentCoroutineContext().ensureActive()
        } catch (failure: Throwable) {
            withContext(NonCancellable) {
                try {
                    candidate?.let { session ->
                        mutex.withLock { sessions.remove(session) }
                        session.closeFromOwner()
                    } ?: acquiredLease?.release()
                } catch (_: Throwable) {
                    // Preserve the acquisition/prepare/handoff failure after best-effort cleanup.
                } finally {
                    finishSessionHandoff(sessionId, handoff)
                    endOperation()
                }
            }
            throw failure
        }
        return deliverSession(sessionId, handoff, checkNotNull(candidate))
    }

    /**
     * Acquires the last potentially suspending client boundary before transferring ownership.
     * Once the continuation is resumed, only non-suspending gate publication remains.
     */
    private suspend fun deliverSession(
        sessionId: AgentSessionId,
        handoff: CompletableDeferred<Unit>,
        session: ChatbotSession,
    ): ChatbotSession {
        try {
            mutex.lock()
        } catch (failure: Throwable) {
            withContext(NonCancellable) {
                try {
                    mutex.withLock { sessions.remove(session) }
                    session.closeFromOwner()
                } catch (_: Throwable) {
                    // Preserve the delivery failure after best-effort cleanup.
                } finally {
                    finishSessionHandoff(sessionId, handoff)
                    endOperation()
                }
            }
            throw failure
        }

        val operationsGate = finishHandoffDeliveryLocked(sessionId, handoff)
        return suspendCancellableCoroutine { continuation ->
            try {
                continuation.resume(session) { _, rejectedSession, _ ->
                    rejectedSession.closeAfterRejectedHandoff()
                }
            } finally {
                mutex.unlock()
                handoff.complete(Unit)
                operationsGate?.complete(Unit)
            }
        }
    }

    /** Called with [mutex] held; the state change stays invisible until delivery is resumed. */
    private fun finishHandoffDeliveryLocked(
        sessionId: AgentSessionId,
        handoff: CompletableDeferred<Unit>,
    ): CompletableDeferred<Unit>? {
        val handoffs = activeHandoffs[sessionId]
            ?: error("Unknown Chatbot session handoff")
        check(handoffs.remove(handoff)) { "Unknown Chatbot session handoff" }
        if (handoffs.isEmpty()) activeHandoffs.remove(sessionId)

        check(activeOperations > 0) { "Chatbot client operation count underflow" }
        activeOperations -= 1
        return operationsSettled.takeIf { activeOperations == 0 }
    }

    private suspend fun beginSessionHandoff(sessionId: AgentSessionId): CompletableDeferred<Unit> {
        while (true) {
            var admitted: CompletableDeferred<Unit>? = null
            val blocker = mutex.withLock {
                val invalidation = globalInvalidation ?: sessionInvalidations[sessionId]
                if (invalidation == null) {
                    admitted = CompletableDeferred<Unit>().also { handoff ->
                        activeHandoffs.getOrPut(sessionId) { mutableSetOf() } += handoff
                    }
                }
                invalidation
            }
            admitted?.let { return it }
            checkNotNull(blocker).await()
        }
    }

    private suspend fun finishSessionHandoff(
        sessionId: AgentSessionId,
        handoff: CompletableDeferred<Unit>,
    ) {
        val removed = mutex.withLock {
            val handoffs = activeHandoffs[sessionId] ?: return@withLock false
            val didRemove = handoffs.remove(handoff)
            if (handoffs.isEmpty()) activeHandoffs.remove(sessionId)
            didRemove
        }
        check(removed) { "Unknown Chatbot session handoff" }
        check(handoff.complete(Unit)) { "Chatbot session handoff already completed" }
    }

    private suspend fun beginSessionInvalidation(sessionId: AgentSessionId): InvalidationAdmission {
        while (true) {
            var admitted: InvalidationAdmission? = null
            val blocker = mutex.withLock {
                val invalidation = globalInvalidation ?: sessionInvalidations[sessionId]
                if (invalidation == null) {
                    val fence = CompletableDeferred<Unit>()
                    sessionInvalidations[sessionId] = fence
                    admitted = InvalidationAdmission(
                        fence = fence,
                        preceding = activeHandoffs[sessionId]?.toList().orEmpty(),
                    )
                }
                invalidation
            }
            admitted?.let { return it }
            checkNotNull(blocker).await()
        }
    }

    private suspend fun finishSessionInvalidation(
        sessionId: AgentSessionId,
        fence: CompletableDeferred<Unit>,
    ) {
        val removed = mutex.withLock {
            if (sessionInvalidations[sessionId] !== fence) return@withLock false
            sessionInvalidations.remove(sessionId)
            true
        }
        check(removed) { "Unknown Chatbot session invalidation" }
        check(fence.complete(Unit)) { "Chatbot session invalidation already completed" }
    }

    private suspend fun beginGlobalInvalidation(): InvalidationAdmission {
        while (true) {
            var admitted: InvalidationAdmission? = null
            val blocker = mutex.withLock {
                val invalidation = globalInvalidation
                if (invalidation == null) {
                    val fence = CompletableDeferred<Unit>()
                    val preceding = mutableListOf<CompletableDeferred<Unit>>()
                    preceding += sessionInvalidations.values
                    activeHandoffs.values.forEach(preceding::addAll)
                    globalInvalidation = fence
                    admitted = InvalidationAdmission(fence, preceding)
                }
                invalidation
            }
            admitted?.let { return it }
            checkNotNull(blocker).await()
        }
    }

    private suspend fun finishGlobalInvalidation(fence: CompletableDeferred<Unit>) {
        val removed = mutex.withLock {
            if (globalInvalidation !== fence) return@withLock false
            globalInvalidation = null
            true
        }
        check(removed) { "Unknown Chatbot global invalidation" }
        check(fence.complete(Unit)) { "Chatbot global invalidation already completed" }
    }

    private suspend fun resolveHistoryStatus(snapshot: AgentSessionSnapshot): ChatbotStatus {
        if (snapshot.state.status !in RECOVERABLE_STORED_STATUSES) {
            return snapshot.state.status.toChatbotStatus()
        }
        val lease = try {
            sessionManager.acquire(snapshot.sessionId)
        } catch (failure: AgentSessionException) {
            return if (failure.code == AgentSessionErrorCode.BUSY) {
                ChatbotStatus.RUNNING
            } else {
                snapshot.state.status.toChatbotStatus()
            }
        }
        return try {
            lease.state.value.toChatbotStatus()
        } finally {
            withContext(NonCancellable) { lease.release() }
        }
    }

    private fun newSession(
        lease: AgentSessionLease,
        configuration: ChatbotSessionConfiguration,
    ): ChatbotSession {
        val scope = CoroutineScope(SupervisorJob() + sessionDispatcher)
        return try {
            ChatbotSession(
                controller = ChatbotController(
                    lease = lease,
                    requestFactory = requestFactory,
                    initialConfiguration = configuration,
                    scope = scope,
                ),
                scope = scope,
                onClosed = { session -> mutex.withLock { sessions.remove(session) } },
            )
        } catch (failure: Throwable) {
            scope.cancel()
            throw failure
        }
    }

    private suspend fun registerSession(session: ChatbotSession) {
        mutex.withLock { sessions += session }
    }

    private suspend fun closeRegisteredSessions(sessionId: String) {
        val owned = mutex.withLock {
            sessions.filter { it.boundSessionId == sessionId }
                .also { sessions.removeAll(it.toSet()) }
        }
        closeSessions(owned)
    }

    private suspend fun closeAllRegisteredSessions() {
        val owned = mutex.withLock { sessions.toList().also { sessions.clear() } }
        closeSessions(owned)
    }

    private suspend fun destructiveClientMutation(
        successScope: AgentSessionInvalidationScope,
        sessionId: String? = null,
        mutation: suspend () -> Unit,
    ) {
        var mutationFailure: Throwable? = null
        try {
            mutation()
        } catch (failure: Throwable) {
            mutationFailure = failure
        }
        val invalidationScope = mutationFailure
            ?.let { failure ->
                (failure as? AgentSessionException)?.invalidationScope
                    ?: AgentSessionInvalidationScope.NONE
            }
            ?: successScope
        var cleanupFailure: Throwable? = null
        try {
            when (invalidationScope) {
                AgentSessionInvalidationScope.NONE -> Unit
                AgentSessionInvalidationScope.SESSION -> {
                    sessionId?.let { closeRegisteredSessions(it) }
                }
                AgentSessionInvalidationScope.ALL_SESSIONS -> closeAllRegisteredSessions()
            }
        } catch (failure: Throwable) {
            cleanupFailure = failure
        }
        mutationFailure?.let { throw it }
        cleanupFailure?.let {
            throw ChatbotException(
                failure = ChatbotFailure.OPERATION_FAILED,
                invalidationScope = invalidationScope.toChatbotInvalidationScope(),
            )
        }
    }

    private suspend fun closeSessions(owned: List<ChatbotSession>) {
        var failure: Throwable? = null
        owned.forEach { session ->
            try {
                session.closeFromOwner()
            } catch (error: Throwable) {
                if (failure == null) failure = error
            }
        }
        failure?.let { throw it }
    }

    private suspend fun <T> clientOperation(operation: suspend () -> T): T {
        beginOperation()
        return try {
            operation()
        } finally {
            withContext(NonCancellable) { endOperation() }
        }
    }

    private suspend fun beginOperation() {
        mutex.withLock {
            ensureOpen()
            if (activeOperations == 0) operationsSettled = CompletableDeferred()
            activeOperations += 1
        }
    }

    private suspend fun endOperation() {
        mutex.withLock {
            check(activeOperations > 0) { "Chatbot client operation count underflow" }
            activeOperations -= 1
            if (activeOperations == 0) operationsSettled.complete(Unit)
        }
    }

    private fun ensureOpen() {
        if (closed) throw ChatbotException(ChatbotFailure.CLOSED)
    }
}

/**
 * Creates an owning Chatbot root over [runner]. Closing it interrupts managed live sessions before
 * invoking [closeResources]. [persistence] must be the same store used by [runner]. Supplied
 * delegates follow the non-reentrancy contract on [ChatbotClient].
 */
fun createChatbotClient(
    runner: AgentRunner,
    requestFactory: ChatbotRequestFactory,
    persistence: AgentPersistence,
    closeResources: suspend () -> Unit,
    sessionDispatcher: CoroutineDispatcher = Dispatchers.Default,
): ChatbotClient = ChatbotClient(
    requestFactory = requestFactory,
    sessionManager = DefaultAgentSessionManager(
        runner = runner,
        persistence = persistence,
        dispatcher = sessionDispatcher,
    ),
    ownsSessionManager = true,
    closeResources = closeResources,
    sessionDispatcher = sessionDispatcher,
)

/** Creates a borrowed Chatbot facade root; closing it never closes [sessionManager]. */
fun createChatbotClient(
    sessionManager: AgentSessionManager,
    requestFactory: ChatbotRequestFactory,
    sessionDispatcher: CoroutineDispatcher = Dispatchers.Default,
): ChatbotClient = ChatbotClient(
    requestFactory = requestFactory,
    sessionManager = sessionManager,
    ownsSessionManager = false,
    closeResources = {},
    sessionDispatcher = sessionDispatcher,
)

internal fun AgentStatus.toChatbotStatus(): ChatbotStatus = when (this) {
    AgentStatus.IDLE -> ChatbotStatus.IDLE
    AgentStatus.RUNNING -> ChatbotStatus.RUNNING
    AgentStatus.WAITING_FOR_TOOLS -> ChatbotStatus.WAITING_FOR_TOOL
    AgentStatus.COMPLETED -> ChatbotStatus.COMPLETED
    AgentStatus.FAILED -> ChatbotStatus.FAILED
    AgentStatus.CANCELLED -> ChatbotStatus.CANCELLED
    AgentStatus.INTERRUPTED -> ChatbotStatus.INTERRUPTED
}

private fun AgentSessionRuntimeSnapshot.toChatbotStatus(): ChatbotStatus = when (phase) {
    AgentSessionPhase.ACTIVE -> state?.status?.toChatbotStatus() ?: ChatbotStatus.RUNNING
    AgentSessionPhase.RESUMABLE -> ChatbotStatus.INTERRUPTED
    AgentSessionPhase.RECOVERY_BLOCKED -> ChatbotStatus.RECOVERY_BLOCKED
    AgentSessionPhase.NEW,
    AgentSessionPhase.INACTIVE,
    AgentSessionPhase.TERMINAL,
    AgentSessionPhase.CLOSED,
    AgentSessionPhase.DELETED,
    -> state?.status?.toChatbotStatus() ?: ChatbotStatus.IDLE
}

private fun AgentSessionSnapshot.toHistoryItem(status: ChatbotStatus): ChatbotHistoryItem = ChatbotHistoryItem(
    sessionId = sessionId.value,
    configuration = request.toChatbotSessionConfiguration(),
    updatedAtEpochMs = updatedAtEpochMs,
    status = status,
    lastMessageText = state.messages.lastOrNull()?.text().orEmpty(),
)

private val RECOVERABLE_STORED_STATUSES = setOf(
    AgentStatus.RUNNING,
    AgentStatus.WAITING_FOR_TOOLS,
    AgentStatus.INTERRUPTED,
)

private suspend inline fun <T> facadeOperation(crossinline operation: suspend () -> T): T = try {
    operation()
} catch (cancellation: CancellationException) {
    throw cancellation
} catch (known: ChatbotException) {
    throw known
} catch (failure: AgentSessionException) {
    throw ChatbotException(
        failure = failure.code.toChatbotFailure(),
        invalidationScope = failure.invalidationScope.toChatbotInvalidationScope(),
    )
} catch (_: Throwable) {
    throw ChatbotException(ChatbotFailure.OPERATION_FAILED)
}

private fun AgentSessionInvalidationScope.toChatbotInvalidationScope(): ChatbotInvalidationScope =
    when (this) {
        AgentSessionInvalidationScope.NONE -> ChatbotInvalidationScope.NONE
        AgentSessionInvalidationScope.SESSION -> ChatbotInvalidationScope.SESSION
        AgentSessionInvalidationScope.ALL_SESSIONS -> ChatbotInvalidationScope.ALL_SESSIONS
    }

private fun AgentSessionErrorCode.toChatbotFailure(): ChatbotFailure = when (this) {
    AgentSessionErrorCode.NOT_FOUND -> ChatbotFailure.NOT_FOUND
    AgentSessionErrorCode.ALREADY_EXISTS,
    AgentSessionErrorCode.BUSY,
    -> ChatbotFailure.BUSY
    AgentSessionErrorCode.CLOSED,
    AgentSessionErrorCode.DELETED,
    AgentSessionErrorCode.DETACHED,
    -> ChatbotFailure.CLOSED
    AgentSessionErrorCode.INVALID_STATE -> ChatbotFailure.OPERATION_FAILED
    AgentSessionErrorCode.STORAGE -> ChatbotFailure.STORAGE
}
