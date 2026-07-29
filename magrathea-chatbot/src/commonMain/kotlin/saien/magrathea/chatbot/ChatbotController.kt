package saien.magrathea.chatbot

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import saien.magrathea.core.AgentEvent
import saien.magrathea.core.AgentFailureCode
import saien.magrathea.core.AgentMessage
import saien.magrathea.core.AgentRecoveryDisposition
import saien.magrathea.core.AgentRequest
import saien.magrathea.core.AgentRunner
import saien.magrathea.core.AgentSessionId
import saien.magrathea.core.MessageRole

internal class ChatbotController(
    private val runner: AgentRunner,
    private val requestFactory: ChatbotRequestFactory,
    initialConfiguration: ChatbotSessionConfiguration,
    private val reducer: ChatbotEventReducer = ChatbotEventReducer(),
    scope: CoroutineScope? = null,
) {
    private val ownsScope = scope == null
    private val controllerScope = scope ?: CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val commandMutex = Mutex()
    private val stateMutex = Mutex()
    private val conversation = mutableListOf<AgentMessage>()
    private var activeRun: ActiveRun? = null
    private var sessionId: AgentSessionId? = null
    private var configuration: ChatbotSessionConfiguration = initialConfiguration
    private var nextGeneration: Long = 0
    private var closed: Boolean = false
    private val mutableState = MutableStateFlow(ChatbotSnapshot(configuration = initialConfiguration))

    val state: StateFlow<ChatbotSnapshot> = mutableState.asStateFlow()

    suspend fun sendMessage(
        text: String,
        attachments: List<ChatbotAttachment> = emptyList(),
        options: ChatbotSendOptions = ChatbotSendOptions(),
    ) = commandMutex.withLock {
        ensureOpen()
        require(text.isNotBlank() || attachments.isNotEmpty()) {
            "A chatbot message requires text or at least one attachment"
        }
        val stoppedActiveRun = stopActiveRun()
        val currentSessionId = sessionId ?: AgentSessionId.create()
        val currentConversation = stateMutex.withLock { conversation.toList() }
        val updatedConversation = currentConversation + userChatbotMessage(text, attachments, options.metadata)
        val request = try {
            buildRequest(currentSessionId, configuration, updatedConversation)
        } catch (failure: Throwable) {
            if (stoppedActiveRun) markCancelled()
            throw failure
        }
        stateMutex.withLock {
            sessionId = currentSessionId
            conversation.replaceWith(updatedConversation)
            val messages = updatedConversation.map { it.toChatbotMessageSnapshot() }
            val previousActivities = if (stoppedActiveRun) {
                mutableState.value.toolActivities.withUnresolvedToolActivities(
                    ChatbotToolActivityStatus.CANCELLED,
                )
            } else {
                mutableState.value.toolActivities
            }
            mutableState.value = mutableState.value.copy(
                sessionId = currentSessionId.value,
                messages = messages,
                status = ChatbotStatus.RUNNING,
                failure = null,
                interruption = null,
                toolActivities = reconcileToolActivities(messages, previousActivities),
            )
        }
        startRun(request)
    }

    suspend fun regenerate(messageId: String) = commandMutex.withLock {
        ensureOpen()
        val currentSessionId = sessionId ?: AgentSessionId.create()
        val updatedConversation = stateMutex.withLock {
            val index = conversation.indexOfFirst { it.id == messageId }
            require(index >= 0) { "Message $messageId not found" }
            require(conversation[index].role == MessageRole.USER) {
                "Regenerate requires a user message"
            }
            conversation.take(index + 1)
        }
        val stoppedActiveRun = stopActiveRun()
        val request = try {
            buildRequest(currentSessionId, configuration, updatedConversation)
        } catch (failure: Throwable) {
            if (stoppedActiveRun) markCancelled()
            throw failure
        }
        stateMutex.withLock {
            sessionId = currentSessionId
            conversation.replaceWith(updatedConversation)
            val messages = updatedConversation.map { it.toChatbotMessageSnapshot() }
            val previousActivities = if (stoppedActiveRun) {
                mutableState.value.toolActivities.withUnresolvedToolActivities(
                    ChatbotToolActivityStatus.CANCELLED,
                )
            } else {
                mutableState.value.toolActivities
            }
            mutableState.value = mutableState.value.copy(
                sessionId = currentSessionId.value,
                messages = messages,
                status = ChatbotStatus.RUNNING,
                failure = null,
                interruption = null,
                toolActivities = reconcileToolActivities(messages, previousActivities),
            )
        }
        startRun(request)
    }

    suspend fun resume(sessionId: AgentSessionId) = commandMutex.withLock {
        ensureOpen()
        stateMutex.withLock {
            check(activeRun == null) { "Cannot resume while a run is active" }
            this@ChatbotController.sessionId = sessionId
            mutableState.value = mutableState.value.copy(
                sessionId = sessionId.value,
                status = ChatbotStatus.RUNNING,
                failure = null,
                interruption = null,
            )
        }
        startOperation(sessionId) { runner.resume(sessionId) }
    }

    suspend fun updateConfiguration(
        configuration: ChatbotSessionConfiguration,
        persistRequest: suspend (AgentSessionId, AgentRequest) -> Unit,
    ): Boolean = commandMutex.withLock {
        ensureOpen()
        val current = stateMutex.withLock {
            if (this.configuration == configuration) return@withLock ConfigurationState.Unchanged
            if (mutableState.value.hasPendingRun) return@withLock ConfigurationState.Busy
            ConfigurationState.Ready(
                sessionId = sessionId,
                messages = conversation.toList(),
            )
        }
        when (current) {
            ConfigurationState.Unchanged -> return@withLock true
            ConfigurationState.Busy -> return@withLock false
            is ConfigurationState.Ready -> {
                current.sessionId?.let { id ->
                    persistRequest(id, buildRequest(id, configuration, current.messages))
                }
                stateMutex.withLock {
                    this.configuration = configuration
                    mutableState.value = mutableState.value.copy(configuration = configuration)
                }
                true
            }
        }
    }

    suspend fun cancel() = commandMutex.withLock {
        if (closed) return@withLock
        val stopped = stopActiveRun()
        val persistedSessionId = stateMutex.withLock {
            sessionId?.takeIf {
                mutableState.value.status == ChatbotStatus.INTERRUPTED ||
                    mutableState.value.status == ChatbotStatus.RECOVERY_BLOCKED
            }
        }
        if (!stopped && persistedSessionId != null) {
            runner.cancel(persistedSessionId)
        }
        if (stopped || persistedSessionId != null) {
            stateMutex.withLock {
                mutableState.value = mutableState.value.copy(
                    status = ChatbotStatus.CANCELLED,
                    failure = null,
                    interruption = null,
                    toolActivities = mutableState.value.toolActivities.withUnresolvedToolActivities(
                        ChatbotToolActivityStatus.CANCELLED,
                    ),
                )
            }
        }
    }

    suspend fun interrupt() = commandMutex.withLock {
        if (closed) return@withLock
        interruptActiveRun()
    }

    suspend fun loadHistory(
        messages: List<AgentMessage>,
        sessionId: AgentSessionId? = null,
        configuration: ChatbotSessionConfiguration = this.configuration,
        usage: ChatbotUsage = ChatbotUsage(),
        latestRequestUsage: ChatbotUsage = ChatbotUsage(),
        contextManagement: ChatbotContextManagementSnapshot =
            ChatbotContextManagementSnapshot(),
        status: ChatbotStatus = ChatbotStatus.IDLE,
        interruption: ChatbotInterruption? = null,
    ) = commandMutex.withLock {
        ensureOpen()
        stopActiveRun()
        stateMutex.withLock {
            conversation.replaceWith(messages)
            this@ChatbotController.sessionId = sessionId
            this@ChatbotController.configuration = configuration
            val messageSnapshots = messages.map { it.toChatbotMessageSnapshot() }
            mutableState.value = ChatbotSnapshot(
                configuration = configuration,
                sessionId = sessionId?.value,
                messages = messageSnapshots,
                status = status,
                interruption = interruption,
                usage = usage,
                latestRequestUsage = latestRequestUsage,
                contextManagement = contextManagement,
                toolActivities = reconcileToolActivities(
                    messages = messageSnapshots,
                    terminalUnresolvedStatus = ChatbotToolActivityStatus.INTERRUPTED,
                ),
            )
        }
    }

    suspend fun close() {
        var cancelOwnedScope = false
        commandMutex.withLock {
            if (closed) return@withLock
            interruptActiveRun()
            closed = true
            cancelOwnedScope = ownsScope
        }
        if (cancelOwnedScope) controllerScope.cancel()
    }

    private suspend fun startRun(request: AgentRequest) {
        startOperation(request.sessionId) { runner.run(request) }
    }

    private fun buildRequest(
        sessionId: AgentSessionId,
        configuration: ChatbotSessionConfiguration,
        messages: List<AgentMessage>,
    ): AgentRequest {
        val request = requestFactory.create(
            ChatbotRequestContext(
                sessionId = sessionId,
                configuration = configuration,
                messages = messages,
            ),
        )
        return request.copy(
            sessionId = sessionId,
            messages = messages,
            model = configuration.model,
            engine = request.engine.copy(
                provider = request.engine.provider.copy(
                    credentialRef = configuration.credentialRef,
                ),
            ),
        )
    }

    private suspend fun startOperation(
        sessionId: AgentSessionId,
        source: suspend () -> Flow<AgentEvent>,
    ) {
        nextGeneration += 1
        val generation = nextGeneration
        val ready = CompletableDeferred<Unit>()
        val job = controllerScope.launch(start = CoroutineStart.LAZY) {
            var terminalSeen = false
            var cancelled = false
            try {
                source().collect { event ->
                    val applied = applyEvent(event, sessionId, generation)
                    ready.complete(Unit)
                    if (applied && event.isTerminal()) {
                        terminalSeen = true
                        throw TerminalEventCollected()
                    }
                }
            } catch (_: TerminalEventCollected) {
                // A terminal AgentEvent owns completion; stop collecting upstream immediately.
            } catch (error: CancellationException) {
                cancelled = true
                throw error
            } catch (_: Throwable) {
                applyFailure(sessionId, generation)
                terminalSeen = true
            } finally {
                if (!cancelled && !terminalSeen) {
                    applyFailure(sessionId, generation)
                }
                clearActiveRun(generation)
                ready.complete(Unit)
            }
        }
        stateMutex.withLock {
            activeRun = ActiveRun(sessionId, generation, job, ready)
        }
        if (!job.start()) {
            applyFailure(sessionId, generation)
            clearActiveRun(generation)
            ready.complete(Unit)
        }
    }

    private suspend fun stopActiveRun(): Boolean {
        val active = claimActiveRun() ?: return false
        try {
            runner.cancel(active.sessionId)
        } finally {
            active.job.cancelAndJoin()
        }
        return true
    }

    private suspend fun interruptActiveRun(): Boolean {
        val active = claimActiveRun() ?: return false
        val recovery = try {
            runner.interrupt(active.sessionId)
        } finally {
            active.job.cancelAndJoin()
        }
        stateMutex.withLock {
            val recoveredState = recovery.state
            val recoveredMessages = recoveredState?.messages
                ?.map { it.toChatbotMessageSnapshot() }
                ?: mutableState.value.messages
            if (recoveredState != null) {
                conversation.replaceWith(recoveredState.messages)
            }
            val recoveredSnapshot = mutableState.value.copy(
                messages = recoveredMessages,
                usage = recoveredState?.usage?.toChatbotUsage() ?: mutableState.value.usage,
                latestRequestUsage = recoveredState?.latestRequestUsage?.toChatbotUsage()
                    ?: mutableState.value.latestRequestUsage,
                contextManagement = recoveredState?.contextManagement
                    ?.toChatbotContextManagementSnapshot()
                    ?: mutableState.value.contextManagement,
                toolActivities = reconcileToolActivities(
                    messages = recoveredMessages,
                    previous = mutableState.value.toolActivities,
                ),
            )
            mutableState.value = when (recovery.disposition) {
                AgentRecoveryDisposition.BLOCKED -> recoveredSnapshot.copy(
                    status = ChatbotStatus.RECOVERY_BLOCKED,
                    failure = ChatbotFailure.RECOVERY_BLOCKED,
                    interruption = recovery.interruption?.toChatbotInterruption(),
                    toolActivities = reconcileToolActivities(
                        messages = recoveredMessages,
                        previous = mutableState.value.toolActivities,
                        terminalUnresolvedStatus = ChatbotToolActivityStatus.INTERRUPTED,
                    ),
                )
                AgentRecoveryDisposition.RESUMABLE -> recoveredSnapshot.copy(
                    status = ChatbotStatus.INTERRUPTED,
                    failure = null,
                    interruption = recovery.interruption?.toChatbotInterruption(),
                    toolActivities = reconcileToolActivities(
                        messages = recoveredMessages,
                        previous = mutableState.value.toolActivities,
                        terminalUnresolvedStatus = ChatbotToolActivityStatus.INTERRUPTED,
                    ),
                )
                AgentRecoveryDisposition.TERMINAL -> recoveredSnapshot.copy(
                    status = recovery.status?.toChatbotStatus() ?: mutableState.value.status,
                    failure = null,
                    interruption = null,
                )
                AgentRecoveryDisposition.NOT_FOUND,
                AgentRecoveryDisposition.ACTIVE,
                -> recoveredSnapshot.copy(
                    status = ChatbotStatus.RECOVERY_BLOCKED,
                    failure = ChatbotFailure.RECOVERY_BLOCKED,
                    toolActivities = reconcileToolActivities(
                        messages = recoveredMessages,
                        previous = mutableState.value.toolActivities,
                        terminalUnresolvedStatus = ChatbotToolActivityStatus.INTERRUPTED,
                    ),
                )
            }
        }
        return true
    }

    private suspend fun claimActiveRun(): ActiveRun? {
        val observed = stateMutex.withLock { activeRun } ?: return null
        observed.ready.await()
        return stateMutex.withLock {
            activeRun
                ?.takeIf { current ->
                    current.generation == observed.generation && mutableState.value.isRunning
                }
                ?.also { activeRun = null }
        }
    }

    private suspend fun applyEvent(
        event: AgentEvent,
        expectedSessionId: AgentSessionId,
        generation: Long,
    ): Boolean = stateMutex.withLock {
        val active = activeRun
        if (active?.generation != generation || active.sessionId != expectedSessionId) return@withLock false
        if (event.sessionId() != expectedSessionId) return@withLock false
        mutableState.value = reducer.reduce(mutableState.value, event)
        when (event) {
            is AgentEvent.Completed -> conversation.replaceWith(event.state.messages)
            is AgentEvent.Interrupted -> conversation.replaceWith(event.state.messages)
            is AgentEvent.MessageEmitted -> conversation.replaceOrAppend(event.message)
            else -> Unit
        }
        true
    }

    private suspend fun applyFailure(sessionId: AgentSessionId, generation: Long) {
        applyEvent(
            AgentEvent.Failed(sessionId, AgentFailureCode.INTERNAL),
            sessionId,
            generation,
        )
    }

    private suspend fun clearActiveRun(generation: Long) {
        stateMutex.withLock {
            if (activeRun?.generation == generation) activeRun = null
        }
    }

    private suspend fun markCancelled() {
        stateMutex.withLock {
            mutableState.value = mutableState.value.copy(
                status = ChatbotStatus.CANCELLED,
                toolActivities = mutableState.value.toolActivities.withUnresolvedToolActivities(
                    ChatbotToolActivityStatus.CANCELLED,
                ),
            )
        }
    }

    private fun ensureOpen() {
        check(!closed) { "ChatbotController is closed" }
    }

    private data class ActiveRun(
        val sessionId: AgentSessionId,
        val generation: Long,
        val job: Job,
        val ready: CompletableDeferred<Unit>,
    )

    private sealed interface ConfigurationState {
        data object Unchanged : ConfigurationState
        data object Busy : ConfigurationState
        data class Ready(
            val sessionId: AgentSessionId?,
            val messages: List<AgentMessage>,
        ) : ConfigurationState
    }

    private class TerminalEventCollected : Throwable()
}

private val ChatbotSnapshot.hasPendingRun: Boolean
    get() = isRunning ||
        status == ChatbotStatus.INTERRUPTED ||
        status == ChatbotStatus.RECOVERY_BLOCKED

private fun MutableList<AgentMessage>.replaceWith(messages: List<AgentMessage>) {
    clear()
    addAll(messages)
}

private fun MutableList<AgentMessage>.replaceOrAppend(message: AgentMessage) {
    val index = indexOfFirst { it.id == message.id }
    if (index >= 0) this[index] = message else add(message)
}

private fun AgentEvent.sessionId(): AgentSessionId = when (this) {
    is AgentEvent.Started -> sessionId
    is AgentEvent.TurnStarted -> sessionId
    is AgentEvent.ContextTransformed -> sessionId
    is AgentEvent.Debug -> sessionId
    is AgentEvent.MessageEmitted -> sessionId
    is AgentEvent.ToolRequested -> sessionId
    is AgentEvent.ToolCompleted -> sessionId
    is AgentEvent.RetryScheduled -> sessionId
    is AgentEvent.CheckpointSaved -> checkpoint.sessionId
    is AgentEvent.Completed -> sessionId
    is AgentEvent.Failed -> sessionId
    is AgentEvent.Cancelled -> sessionId
    is AgentEvent.Interrupted -> sessionId
    is AgentEvent.RecoveryBlocked -> sessionId
}

private fun AgentEvent.isTerminal(): Boolean =
    this is AgentEvent.Completed ||
        this is AgentEvent.Failed ||
        this is AgentEvent.Cancelled ||
        this is AgentEvent.Interrupted ||
        this is AgentEvent.RecoveryBlocked
