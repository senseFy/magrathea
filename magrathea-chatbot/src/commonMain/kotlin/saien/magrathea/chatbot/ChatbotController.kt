package saien.magrathea.chatbot

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import saien.magrathea.core.AgentEvent
import saien.magrathea.core.AgentMessage
import saien.magrathea.core.AgentRequest
import saien.magrathea.core.MessageRole
import saien.magrathea.runtime.AgentSessionLease
import saien.magrathea.runtime.AgentSessionPhase
import saien.magrathea.runtime.AgentSessionRuntimeSnapshot

/** Chatbot projection and command adapter over one manager-owned Agent session lease. */
internal class ChatbotController(
    private val lease: AgentSessionLease,
    private val requestFactory: ChatbotRequestFactory,
    initialConfiguration: ChatbotSessionConfiguration,
    private val reducer: ChatbotEventReducer = ChatbotEventReducer(),
    scope: CoroutineScope,
) {
    private val commandMutex = Mutex()
    private val stateMutex = Mutex()
    private var closed = false
    private val mutableState = MutableStateFlow(
        projectRuntime(
            runtime = lease.state.value,
            previous = ChatbotSnapshot(configuration = initialConfiguration),
            fallbackConfiguration = initialConfiguration,
            reducer = reducer,
        ),
    )
    private val projectionJob = scope.launch {
        lease.state.collect { runtime ->
            stateMutex.withLock {
                if (!closed) {
                    mutableState.value = projectRuntime(
                        runtime = runtime,
                        previous = mutableState.value,
                        fallbackConfiguration = mutableState.value.configuration,
                        reducer = reducer,
                    )
                }
            }
        }
    }

    val state: StateFlow<ChatbotSnapshot> = mutableState.asStateFlow()
    val sessionId: String
        get() = lease.sessionId.value

    suspend fun sendMessage(
        text: String,
        attachments: List<ChatbotAttachment> = emptyList(),
        options: ChatbotSendOptions = ChatbotSendOptions(),
    ) = commandMutex.withLock {
        ensureOpen()
        require(text.isNotBlank() || attachments.isNotEmpty()) {
            "A chatbot message requires text or at least one attachment"
        }
        stopPendingExecution()
        val messages = lease.state.value.authoritativeMessages() +
            userChatbotMessage(text, attachments, options.metadata)
        lease.start(buildRequest(messages))
        syncFromLease()
    }

    suspend fun regenerate(messageId: String) = commandMutex.withLock {
        ensureOpen()
        val currentMessages = lease.state.value.authoritativeMessages()
        val index = currentMessages.indexOfFirst { it.id == messageId }
        require(index >= 0) { "Message $messageId not found" }
        require(currentMessages[index].role == MessageRole.USER) {
            "Regenerate requires a user message"
        }
        val regeneratedMessages = currentMessages.take(index + 1)
        stopPendingExecution()
        lease.start(buildRequest(regeneratedMessages))
        syncFromLease()
    }

    suspend fun resume() = commandMutex.withLock {
        ensureOpen()
        lease.resume()
        syncFromLease()
    }

    suspend fun updateConfiguration(configuration: ChatbotSessionConfiguration): Boolean =
        commandMutex.withLock {
            ensureOpen()
            val runtime = lease.state.value
            val currentConfiguration = runtime.request?.toChatbotSessionConfiguration()
                ?: stateMutex.withLock { mutableState.value.configuration }
            if (currentConfiguration == configuration) return@withLock true
            if (runtime.phase.hasPendingExecution) return@withLock false

            runtime.request?.let {
                lease.replaceIdleRequest(buildRequest(runtime.authoritativeMessages(), configuration))
            }
            stateMutex.withLock {
                mutableState.value = mutableState.value.copy(configuration = configuration)
            }
            syncFromLease(fallbackConfiguration = configuration)
            true
        }

    suspend fun cancel() = commandMutex.withLock {
        if (closed) return@withLock
        if (lease.state.value.phase.hasPendingExecution) {
            lease.cancel()
            syncFromLease()
        }
    }

    suspend fun interrupt() = commandMutex.withLock {
        if (closed) return@withLock
        if (lease.state.value.phase == AgentSessionPhase.ACTIVE) {
            lease.interrupt()
            syncFromLease()
        }
    }

    suspend fun close() {
        val shouldClose = commandMutex.withLock {
            if (closed) false else {
                closed = true
                true
            }
        }
        if (!shouldClose) return
        withContext(NonCancellable) {
            projectionJob.cancelAndJoin()
            lease.release()
        }
    }

    private suspend fun stopPendingExecution() {
        if (lease.state.value.phase.hasPendingExecution) {
            lease.cancel()
            syncFromLease()
        }
    }

    private suspend fun buildRequest(
        messages: List<AgentMessage>,
        configuration: ChatbotSessionConfiguration? = null,
    ): AgentRequest {
        val selectedConfiguration = configuration
            ?: lease.state.value.request?.toChatbotSessionConfiguration()
            ?: stateMutex.withLock { mutableState.value.configuration }
        val request = requestFactory.create(
            ChatbotRequestContext(
                sessionId = lease.sessionId,
                configuration = selectedConfiguration,
                messages = messages,
            ),
        )
        return request.copy(
            sessionId = lease.sessionId,
            messages = messages,
            model = selectedConfiguration.model,
            reasoningPreference = selectedConfiguration.reasoningPreference,
            engine = request.engine.copy(
                provider = request.engine.provider.copy(
                    credentialRef = selectedConfiguration.credentialRef,
                ),
            ),
        )
    }

    private suspend fun syncFromLease(
        fallbackConfiguration: ChatbotSessionConfiguration? = null,
    ) {
        stateMutex.withLock {
            mutableState.value = projectRuntime(
                runtime = lease.state.value,
                previous = mutableState.value,
                fallbackConfiguration = fallbackConfiguration ?: mutableState.value.configuration,
                reducer = reducer,
            )
        }
    }

    private fun ensureOpen() {
        check(!closed) { "ChatbotController is closed" }
    }
}

private fun projectRuntime(
    runtime: AgentSessionRuntimeSnapshot,
    previous: ChatbotSnapshot,
    fallbackConfiguration: ChatbotSessionConfiguration,
    reducer: ChatbotEventReducer,
): ChatbotSnapshot {
    val eventProjection = runtime.lastEvent?.let { reducer.reduce(previous, it) } ?: previous
    val state = runtime.state
    val runtimeFailure = runtime.failure
    val messages = runtime.authoritativeMessages().map { it.toChatbotMessageSnapshot() }
    val status = runtime.toChatbotStatus(eventProjection.status)
    var toolActivities = reconcileToolActivities(messages, eventProjection.toolActivities)
    state?.pendingToolCalls?.forEach { toolCall ->
        toolActivities = toolActivities.withToolRequested(toolCall)
    }
    (runtime.lastEvent as? AgentEvent.ToolCompleted)?.let { event ->
        toolActivities = toolActivities.withToolCompleted(event.result)
    }
    val terminalToolStatus = when (status) {
        ChatbotStatus.CANCELLED -> ChatbotToolActivityStatus.CANCELLED
        ChatbotStatus.COMPLETED,
        ChatbotStatus.FAILED,
        ChatbotStatus.INTERRUPTED,
        ChatbotStatus.RECOVERY_BLOCKED,
        -> ChatbotToolActivityStatus.INTERRUPTED
        ChatbotStatus.IDLE,
        ChatbotStatus.RUNNING,
        ChatbotStatus.WAITING_FOR_TOOL,
        -> null
    }
    if (terminalToolStatus != null) {
        toolActivities = toolActivities.withUnresolvedToolActivities(terminalToolStatus)
    }

    return eventProjection.copy(
        configuration = runtime.request?.toChatbotSessionConfiguration() ?: fallbackConfiguration,
        sessionId = if (runtime.phase == AgentSessionPhase.NEW) null else runtime.sessionId.value,
        messages = messages,
        status = status,
        failure = when {
            runtime.phase == AgentSessionPhase.RECOVERY_BLOCKED -> ChatbotFailure.RECOVERY_BLOCKED
            runtimeFailure != null -> runtimeFailure.toChatbotFailure()
            status == ChatbotStatus.FAILED -> eventProjection.failure
            else -> null
        },
        interruption = runtime.recovery?.interruption?.toChatbotInterruption()
            ?: if (status == ChatbotStatus.INTERRUPTED) eventProjection.interruption else null,
        usage = state?.usage?.toChatbotUsage() ?: eventProjection.usage,
        latestRequestUsage = state?.latestRequestUsage?.toChatbotUsage()
            ?: eventProjection.latestRequestUsage,
        contextManagement = state?.contextManagement?.toChatbotContextManagementSnapshot()
            ?: eventProjection.contextManagement,
        toolActivities = toolActivities,
    )
}

private fun AgentSessionRuntimeSnapshot.toChatbotStatus(fallback: ChatbotStatus): ChatbotStatus =
    when (phase) {
        AgentSessionPhase.NEW -> ChatbotStatus.IDLE
        AgentSessionPhase.RESUMABLE -> ChatbotStatus.INTERRUPTED
        AgentSessionPhase.RECOVERY_BLOCKED -> ChatbotStatus.RECOVERY_BLOCKED
        AgentSessionPhase.ACTIVE,
        AgentSessionPhase.INACTIVE,
        AgentSessionPhase.TERMINAL,
        AgentSessionPhase.CLOSED,
        AgentSessionPhase.DELETED,
        -> state?.status?.toChatbotStatus() ?: fallback
    }

private val AgentSessionPhase.hasPendingExecution: Boolean
    get() = this == AgentSessionPhase.ACTIVE ||
        this == AgentSessionPhase.RESUMABLE ||
        this == AgentSessionPhase.RECOVERY_BLOCKED

private fun AgentSessionRuntimeSnapshot.authoritativeMessages(): List<AgentMessage> =
    state?.messages ?: request?.messages.orEmpty()

internal fun AgentRequest.toChatbotSessionConfiguration(): ChatbotSessionConfiguration =
    ChatbotSessionConfiguration(
        model = model,
        credentialRef = engine.provider.credentialRef,
        reasoningPreference = reasoningPreference,
    )
