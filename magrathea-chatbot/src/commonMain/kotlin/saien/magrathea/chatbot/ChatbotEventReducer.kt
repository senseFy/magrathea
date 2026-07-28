package saien.magrathea.chatbot

import saien.magrathea.core.AgentEvent

internal class ChatbotEventReducer {
    fun reduce(state: ChatbotSnapshot, event: AgentEvent): ChatbotSnapshot = when (event) {
        is AgentEvent.Started -> state.copy(
            sessionId = event.sessionId.value,
            status = ChatbotStatus.RUNNING,
            failure = null,
        )
        is AgentEvent.TurnStarted,
        is AgentEvent.ContextTransformed,
        is AgentEvent.Debug,
        is AgentEvent.RetryScheduled -> state
        is AgentEvent.CheckpointSaved -> state.copy(
            contextManagement = event.checkpoint.state.contextManagement
                .toChatbotContextManagementSnapshot(),
        )
        is AgentEvent.MessageEmitted -> {
            val messages = state.messages.replaceOrAppend(event.message.toChatbotMessageSnapshot())
            state.copy(
                messages = messages,
                status = ChatbotStatus.RUNNING,
                failure = null,
                toolActivities = reconcileToolActivities(messages, state.toolActivities),
            )
        }
        is AgentEvent.ToolRequested -> state.copy(
            status = ChatbotStatus.WAITING_FOR_TOOL,
            toolActivities = state.toolActivities.withToolRequested(event.toolCall),
        )
        is AgentEvent.ToolCompleted -> state.copy(
            status = ChatbotStatus.RUNNING,
            toolActivities = state.toolActivities.withToolCompleted(event.result),
        )
        is AgentEvent.Completed -> {
            val messages = event.state.messages.map { it.toChatbotMessageSnapshot() }
            state.copy(
                messages = messages,
                status = ChatbotStatus.COMPLETED,
                failure = null,
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
            failure = event.code.toChatbotFailure(),
            toolActivities = state.toolActivities.withUnresolvedToolActivities(
                ChatbotToolActivityStatus.INTERRUPTED,
            ),
        )
        is AgentEvent.Cancelled -> state.copy(
            status = ChatbotStatus.CANCELLED,
            toolActivities = state.toolActivities.withUnresolvedToolActivities(
                ChatbotToolActivityStatus.CANCELLED,
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
