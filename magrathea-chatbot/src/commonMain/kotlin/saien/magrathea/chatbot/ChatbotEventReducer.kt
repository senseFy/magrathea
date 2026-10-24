package saien.magrathea.chatbot

import saien.magrathea.core.AgentEvent

internal class ChatbotEventReducer {
    fun reduce(
        state: ChatbotSnapshot,
        event: AgentEvent,
        backgroundEnabled: Boolean = false,
    ): ChatbotSnapshot = when (event) {
        is AgentEvent.Started -> state.copy(
            sessionId = event.sessionId.value,
            status = ChatbotStatus.RUNNING,
            failure = null,
            interruption = null,
            stopReason = null,
            replay = null,
        )
        is AgentEvent.TurnStarted,
        is AgentEvent.RetryScheduled -> state
        is AgentEvent.ContextTransformed -> state.copy(replay = event.replay)
        is AgentEvent.CheckpointSaved -> {
            val messages = event.checkpoint.state.messages.toChatbotMessageSnapshots(backgroundEnabled)
            state.copy(
                messages = messages,
                stopReason = event.checkpoint.state.stopReason?.toChatbotStopReason(),
                usage = event.checkpoint.state.usage.toChatbotUsage(),
                latestRequestUsage = event.checkpoint.state.latestRequestUsage.toChatbotUsage(),
                contextManagement = event.checkpoint.state.contextManagement
                    .toChatbotContextManagementSnapshot(),
                toolActivities = reconcileToolActivities(messages, state.toolActivities),
            )
        }
        is AgentEvent.MessageEmitted -> {
            val messages = if (event.message.role == saien.magrathea.core.MessageRole.RUNTIME) {
                state.messages
            } else {
                state.messages.replaceOrAppend(
                    event.message.toChatbotMessageSnapshot(backgroundEnabled = backgroundEnabled),
                )
            }
            state.copy(
                messages = messages,
                status = ChatbotStatus.RUNNING,
                failure = null,
                interruption = null,
                toolActivities = reconcileToolActivities(messages, state.toolActivities),
            )
        }
        is AgentEvent.ToolRequested -> {
            // Runtime control calls never become activities.
            if (backgroundEnabled && isBackgroundControlTool(event.toolCall.toolName)) {
                state.copy(status = ChatbotStatus.RUNNING)
            } else {
                state.copy(
                    status = ChatbotStatus.WAITING_FOR_TOOL,
                    toolActivities = state.toolActivities.withToolRequested(event.toolCall),
                )
            }
        }
        is AgentEvent.ToolCompleted -> {
            if (event.result.isBackgroundControlResult()) {
                state.copy(status = ChatbotStatus.RUNNING)
            } else {
                state.copy(
                    status = ChatbotStatus.RUNNING,
                    toolActivities = state.toolActivities.withToolCompleted(event.result),
                )
            }
        }
        is AgentEvent.ToolHandedOff -> state.copy(
            toolActivities = state.toolActivities.withToolHandedOff(
                toolCallId = event.toolCallId,
                toolName = event.toolName,
                handle = event.receipt.handle,
            ),
        )
        is AgentEvent.BackgroundExecutionSettled -> state.copy(
            toolActivities = state.toolActivities.withBackgroundSettled(
                handle = event.handle,
                state = event.outcome.toChatbotBackgroundState(),
            ),
        )
        is AgentEvent.Completed -> {
            val messages = event.state.messages.toChatbotMessageSnapshots(backgroundEnabled)
            state.copy(
                messages = messages,
                status = ChatbotStatus.COMPLETED,
                stopReason = event.state.stopReason?.toChatbotStopReason(),
                failure = null,
                interruption = null,
                usage = event.state.usage.toChatbotUsage(),
                latestRequestUsage = event.state.latestRequestUsage.toChatbotUsage(),
                contextManagement = event.state.contextManagement
                    .toChatbotContextManagementSnapshot(),
                toolActivities = reconcileToolActivities(
                    messages = messages,
                    previous = state.toolActivities,
                    terminalUnresolvedStatus = ChatbotToolActivityStatus.INTERRUPTED,
                ),
            )
        }
        is AgentEvent.Failed -> state.copy(
            status = ChatbotStatus.FAILED,
            stopReason = ChatbotStopReason.ERROR,
            failure = event.code.toChatbotFailure(),
            interruption = null,
            toolActivities = state.toolActivities.withUnresolvedToolActivities(
                ChatbotToolActivityStatus.INTERRUPTED,
            ),
        )
        is AgentEvent.Cancelled -> state.copy(
            status = ChatbotStatus.CANCELLED,
            stopReason = ChatbotStopReason.CANCELLED,
            interruption = null,
            toolActivities = state.toolActivities.withUnresolvedToolActivities(
                ChatbotToolActivityStatus.CANCELLED,
            ),
        )
        is AgentEvent.Interrupted -> {
            val messages = event.state.messages.toChatbotMessageSnapshots(backgroundEnabled)
            state.copy(
                messages = messages,
                status = ChatbotStatus.INTERRUPTED,
                stopReason = event.state.stopReason?.toChatbotStopReason(),
                failure = null,
                interruption = event.interruption.toChatbotInterruption(),
                usage = event.state.usage.toChatbotUsage(),
                latestRequestUsage = event.state.latestRequestUsage.toChatbotUsage(),
                contextManagement = event.state.contextManagement
                    .toChatbotContextManagementSnapshot(),
                toolActivities = reconcileToolActivities(
                    messages = messages,
                    previous = state.toolActivities,
                    terminalUnresolvedStatus = ChatbotToolActivityStatus.INTERRUPTED,
                ),
            )
        }
        is AgentEvent.RecoveryBlocked -> state.copy(
            status = ChatbotStatus.RECOVERY_BLOCKED,
            failure = ChatbotFailure.RECOVERY_BLOCKED,
            toolActivities = state.toolActivities.withUnresolvedToolActivities(
                ChatbotToolActivityStatus.INTERRUPTED,
            ),
        )
    }
}

private fun List<ChatbotMessageSnapshot>.replaceOrAppend(
    message: ChatbotMessageSnapshot,
): List<ChatbotMessageSnapshot> {
    val index = indexOfFirst { it.id == message.id }
    return if (index >= 0) {
        toMutableList().also { it[index] = message }
    } else {
        this + message
    }
}
