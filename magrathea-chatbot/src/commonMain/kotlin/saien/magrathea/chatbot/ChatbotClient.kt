package saien.magrathea.chatbot

import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import saien.magrathea.core.AgentRequest
import saien.magrathea.core.AgentPersistence
import saien.magrathea.core.AgentRecoveryDisposition
import saien.magrathea.core.AgentRecoveryInfo
import saien.magrathea.core.AgentRunner
import saien.magrathea.core.AgentSessionId
import saien.magrathea.core.AgentSessionSnapshot
import saien.magrathea.core.AgentStatus
import saien.magrathea.core.SystemEpochClock
import saien.magrathea.core.text

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

/** One observable Chatbot session with explicit model, send, cancellation, and close lifecycle. */
class ChatbotSession internal constructor(
    private val controller: ChatbotController,
    private val scope: CoroutineScope,
    private val persistRequest: suspend (AgentSessionId, AgentRequest) -> Unit,
    private val onClosed: suspend (ChatbotSession) -> Unit,
) {
    private val lifecycleMutex = Mutex()
    private var closed = false

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
        if (text.isBlank() && attachments.isEmpty()) throw ChatbotException(ChatbotFailure.INVALID_ARGUMENT)
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
            if (!controller.updateConfiguration(configuration, persistRequest)) {
                throw ChatbotException(ChatbotFailure.BUSY)
            }
        }
    }

    internal suspend fun restore(snapshot: AgentSessionSnapshot) {
        lifecycleMutex.withLock {
            ensureOpen()
            controller.loadHistory(
                messages = snapshot.state.messages,
                sessionId = snapshot.sessionId,
                configuration = snapshot.request.toChatbotSessionConfiguration(),
                usage = snapshot.state.usage.toChatbotUsage(),
                latestRequestUsage = snapshot.state.latestRequestUsage.toChatbotUsage(),
                contextManagement = snapshot.state.contextManagement
                    .toChatbotContextManagementSnapshot(),
                status = snapshot.state.status.toChatbotStatus(),
                interruption = snapshot.interruption?.toChatbotInterruption(),
            )
        }
    }

    @Throws(ChatbotException::class, CancellationException::class)
    suspend fun resume() = facadeOperation {
        lifecycleMutex.withLock {
            ensureOpen()
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
            resumeController()
        }
    }

    internal suspend fun resumePersisted() {
        lifecycleMutex.withLock {
            ensureOpen()
            resumeController()
        }
    }

    private suspend fun resumeController() {
        val sessionId = controller.state.value.sessionId
            ?: throw ChatbotException(ChatbotFailure.NOT_FOUND)
        controller.resume(AgentSessionId(sessionId))
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

    @Throws(ChatbotException::class, CancellationException::class)
    suspend fun close() = facadeOperation {
        closeInternal(notifyOwner = true)
    }

    internal suspend fun closeFromOwner() {
        closeInternal(notifyOwner = false)
    }

    private suspend fun closeInternal(notifyOwner: Boolean) {
        var newlyClosed = false
        try {
            lifecycleMutex.withLock {
                if (closed) return@withLock
                closed = true
                newlyClosed = true
                withContext(NonCancellable) {
                    try {
                        controller.close()
                    } finally {
                        scope.cancel()
                    }
                }
            }
        } finally {
            if (newlyClosed && notifyOwner) {
                withContext(NonCancellable) {
                    onClosed(this@ChatbotSession)
                }
            }
        }
    }

    private fun ensureOpen() {
        if (closed) throw ChatbotException(ChatbotFailure.CLOSED)
    }
}

/** Owns Chatbot sessions and the resources supplied through [createChatbotClient]. */
class ChatbotClient internal constructor(
    private val requestFactory: ChatbotRequestFactory,
    private val controllerFactory: (
        ChatbotRequestFactory,
        ChatbotSessionConfiguration,
        CoroutineScope,
    ) -> ChatbotController,
    private val persistence: AgentPersistence,
    private val inspectRecovery: (suspend (AgentSessionId) -> AgentRecoveryInfo)?,
    private val closeResources: suspend () -> Unit,
    private val sessionDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val mutex = Mutex()
    private val sessions = mutableListOf<ChatbotSession>()
    private var closed = false

    @Throws(ChatbotException::class, CancellationException::class)
    suspend fun createSession(configuration: ChatbotSessionConfiguration): ChatbotSession = facadeOperation {
        mutex.withLock {
            ensureOpen()
            newSession(configuration).also(sessions::add)
        }
    }

    @Throws(ChatbotException::class, CancellationException::class)
    suspend fun resumeSession(sessionId: String): ChatbotSession = facadeOperation {
        if (sessionId.isBlank()) throw ChatbotException(ChatbotFailure.INVALID_ARGUMENT)
        mutex.withLock {
            ensureOpen()
            val snapshot = persistence.load(AgentSessionId(sessionId))?.snapshot
                ?: throw ChatbotException(ChatbotFailure.NOT_FOUND)
            val session = newSession(snapshot.request.toChatbotSessionConfiguration())
            try {
                session.restore(snapshot)
                session.resumePersisted()
                sessions += session
                session
            } catch (failure: Throwable) {
                withContext(NonCancellable) {
                    try {
                        session.closeFromOwner()
                    } catch (_: Throwable) {
                        // Preserve the resume failure while attempting deterministic cleanup.
                    }
                }
                throw failure
            }
        }
    }

    @Throws(ChatbotException::class, CancellationException::class)
    suspend fun history(): List<ChatbotHistoryItem> = facadeOperation {
        mutex.withLock {
            ensureOpen()
            persistence.listSessions().map { snapshot ->
                ChatbotHistoryItem(
                    sessionId = snapshot.sessionId.value,
                    configuration = snapshot.request.toChatbotSessionConfiguration(),
                    updatedAtEpochMs = snapshot.updatedAtEpochMs,
                    status = snapshot.historyStatus(),
                    lastMessageText = snapshot.state.messages.lastOrNull()?.text().orEmpty(),
                )
            }
        }
    }

    @Throws(ChatbotException::class, CancellationException::class)
    suspend fun deleteSession(sessionId: String) = facadeOperation {
        if (sessionId.isBlank()) throw ChatbotException(ChatbotFailure.INVALID_ARGUMENT)
        val id = AgentSessionId(sessionId)
        val ownedSessions = mutex.withLock {
            ensureOpen()
            sessions.filter { it.snapshot().sessionId == sessionId }
                .also { sessions.removeAll(it.toSet()) }
        }
        withContext(NonCancellable) {
            var failed = false
            ownedSessions.forEach { session ->
                try {
                    session.closeFromOwner()
                } catch (_: Throwable) {
                    failed = true
                }
            }
            try {
                persistence.deleteSession(id)
            } catch (_: Throwable) {
                failed = true
            }
            if (failed) throw ChatbotException(ChatbotFailure.OPERATION_FAILED)
        }
    }

    @Throws(ChatbotException::class, CancellationException::class)
    suspend fun clearHistory() = facadeOperation {
        val ownedSessions = mutex.withLock {
            ensureOpen()
            sessions.toList().also { sessions.clear() }
        }
        withContext(NonCancellable) {
            var failed = false
            ownedSessions.forEach { session ->
                try {
                    session.closeFromOwner()
                } catch (_: Throwable) {
                    failed = true
                }
            }
            try {
                persistence.clear()
            } catch (_: Throwable) {
                failed = true
            }
            if (failed) throw ChatbotException(ChatbotFailure.OPERATION_FAILED)
        }
    }

    @Throws(ChatbotException::class, CancellationException::class)
    suspend fun close() = facadeOperation {
        val ownedSessions = mutex.withLock {
            if (closed) return@withLock null
            closed = true
            sessions.toList().also { sessions.clear() }
        } ?: return@facadeOperation
        withContext(NonCancellable) {
            var failed = false
            ownedSessions.forEach { session ->
                try {
                    session.closeFromOwner()
                } catch (_: Throwable) {
                    failed = true
                }
            }
            try {
                closeResources()
            } catch (_: Throwable) {
                failed = true
            }
            if (failed) throw ChatbotException(ChatbotFailure.OPERATION_FAILED)
        }
    }

    private fun ensureOpen() {
        if (closed) throw ChatbotException(ChatbotFailure.CLOSED)
    }

    private fun newSession(configuration: ChatbotSessionConfiguration): ChatbotSession {
        val scope = CoroutineScope(SupervisorJob() + sessionDispatcher)
        return try {
            ChatbotSession(
                controller = controllerFactory(requestFactory, configuration, scope),
                scope = scope,
                persistRequest = ::persistRequest,
                onClosed = { session ->
                    mutex.withLock {
                        sessions.remove(session)
                    }
                },
            )
        } catch (failure: Throwable) {
            scope.cancel()
            throw failure
        }
    }

    private suspend fun persistRequest(sessionId: AgentSessionId, request: AgentRequest) {
        val record = persistence.load(sessionId)
            ?: throw ChatbotException(ChatbotFailure.NOT_FOUND)
        persistence.commit(
            snapshot = record.snapshot.copy(
                request = request,
                updatedAtEpochMs = SystemEpochClock.nowEpochMs(),
            ),
            checkpoint = record.checkpoint,
        )
    }

    private suspend fun AgentSessionSnapshot.historyStatus(): ChatbotStatus {
        val storedStatus = state.status.toChatbotStatus()
        if (
            inspectRecovery == null ||
            state.status !in setOf(
                AgentStatus.RUNNING,
                AgentStatus.WAITING_FOR_TOOLS,
                AgentStatus.INTERRUPTED,
            )
        ) {
            return storedStatus
        }
        val recovery = inspectRecovery(sessionId)
        return when (recovery.disposition) {
            AgentRecoveryDisposition.RESUMABLE -> ChatbotStatus.INTERRUPTED
            AgentRecoveryDisposition.BLOCKED -> ChatbotStatus.RECOVERY_BLOCKED
            AgentRecoveryDisposition.ACTIVE -> ChatbotStatus.RUNNING
            AgentRecoveryDisposition.TERMINAL -> recovery.status
                ?.toChatbotStatus()
                ?: storedStatus
            AgentRecoveryDisposition.NOT_FOUND -> storedStatus
        }
    }
}

/**
 * Creates a Provider-neutral chatbot facade over an existing [AgentRunner].
 *
 * [persistence] must be the same persistence boundary used by [runner]. The returned
 * client owns its session scopes and invokes [closeResources] exactly once when the client closes.
 */
fun createChatbotClient(
    runner: AgentRunner,
    requestFactory: ChatbotRequestFactory,
    persistence: AgentPersistence,
    closeResources: suspend () -> Unit,
    sessionDispatcher: CoroutineDispatcher = Dispatchers.Default,
): ChatbotClient = composeChatbotClient(
    requestFactory = requestFactory,
    controllerFactory = { factory, configuration, scope ->
        ChatbotController(runner, factory, configuration, scope = scope)
    },
    persistence = persistence,
    inspectRecovery = runner::inspectRecovery,
    closeResources = closeResources,
    sessionDispatcher = sessionDispatcher,
)

internal fun composeChatbotClient(
    requestFactory: ChatbotRequestFactory,
    controllerFactory: (
        ChatbotRequestFactory,
        ChatbotSessionConfiguration,
        CoroutineScope,
    ) -> ChatbotController,
    persistence: AgentPersistence,
    inspectRecovery: (suspend (AgentSessionId) -> AgentRecoveryInfo)? = null,
    closeResources: suspend () -> Unit,
    sessionDispatcher: CoroutineDispatcher = Dispatchers.Default,
): ChatbotClient = ChatbotClient(
    requestFactory = requestFactory,
    controllerFactory = controllerFactory,
    persistence = persistence,
    inspectRecovery = inspectRecovery,
    closeResources = closeResources,
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

private fun AgentRequest.toChatbotSessionConfiguration(): ChatbotSessionConfiguration =
    ChatbotSessionConfiguration(
        model = model,
        credentialRef = engine.provider.credentialRef,
        reasoningPreference = reasoningPreference,
    )

private suspend inline fun <T> facadeOperation(crossinline operation: suspend () -> T): T = try {
    operation()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (known: ChatbotException) {
    throw known
} catch (_: Throwable) {
    throw ChatbotException(ChatbotFailure.OPERATION_FAILED)
}
