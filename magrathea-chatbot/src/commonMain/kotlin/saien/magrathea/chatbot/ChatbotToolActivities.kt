package saien.magrathea.chatbot

import saien.magrathea.core.ToolCallPart
import saien.magrathea.core.ToolExecutionResult

/**
 * Rebuilds Tool activities from canonical message snapshots while retaining live lifecycle evidence
 * that has not reached a Tool result message yet.
 */
internal fun reconcileToolActivities(
    messages: List<ChatbotMessageSnapshot>,
    previous: List<ChatbotToolActivitySnapshot> = emptyList(),
    terminalUnresolvedStatus: ChatbotToolActivityStatus? = null,
): List<ChatbotToolActivitySnapshot> {
    val previousByKey = previous.associateBy(ChatbotToolActivitySnapshot::key)
    val activities = mutableListOf<ChatbotToolActivitySnapshot>()

    messages.forEach { message ->
        message.toolCalls.forEachIndexed { callOrdinal, call ->
            val key = ChatbotToolActivityKey(message.id, callOrdinal)
            val prior = previousByKey[key]
            val status = when {
                prior?.result != null -> prior.status
                prior?.status?.isTerminal == true -> prior.status
                prior?.status == ChatbotToolActivityStatus.RUNNING -> prior.status
                call.partial -> ChatbotToolActivityStatus.PREPARING
                else -> ChatbotToolActivityStatus.PENDING
            }
            activities += ChatbotToolActivitySnapshot(
                key = key,
                call = call,
                status = status,
                resultMessageId = prior?.resultMessageId,
                result = prior?.result,
            )
        }

        message.toolResults.forEach { result ->
            val activityIndex = activities.indexOfLast { activity ->
                activity.call.id == result.id &&
                    activity.call.name == result.name &&
                    (activity.resultMessageId == null || activity.resultMessageId == message.id)
            }
            if (activityIndex >= 0) {
                activities[activityIndex] = activities[activityIndex].copy(
                    status = result.terminalStatus,
                    resultMessageId = message.id,
                    result = result,
                )
            }
        }
    }

    return if (terminalUnresolvedStatus == null) {
        activities
    } else {
        activities.map { activity ->
            if (activity.status.isUnresolved) {
                activity.copy(status = terminalUnresolvedStatus)
            } else {
                activity
            }
        }
    }
}

internal fun List<ChatbotToolActivitySnapshot>.withToolRequested(
    toolCall: ToolCallPart,
): List<ChatbotToolActivitySnapshot> = updateLastMatching(toolCall.toolCallId, toolCall.toolName) {
    it.copy(
        call = toolCall.toChatbotToolCall(),
        status = ChatbotToolActivityStatus.RUNNING,
    )
}

internal fun List<ChatbotToolActivitySnapshot>.withToolCompleted(
    result: ToolExecutionResult,
): List<ChatbotToolActivitySnapshot> = updateLastMatching(result.toolCallId, result.toolName) {
    val chatbotResult = result.toChatbotToolResult()
    it.copy(
        status = chatbotResult.terminalStatus,
        result = chatbotResult,
    )
}

internal fun List<ChatbotToolActivitySnapshot>.withUnresolvedToolActivities(
    status: ChatbotToolActivityStatus,
): List<ChatbotToolActivitySnapshot> {
    require(status == ChatbotToolActivityStatus.CANCELLED || status == ChatbotToolActivityStatus.INTERRUPTED)
    return map { activity ->
        if (activity.status.isUnresolved) activity.copy(status = status) else activity
    }
}

private inline fun List<ChatbotToolActivitySnapshot>.updateLastMatching(
    callId: String,
    toolName: String,
    update: (ChatbotToolActivitySnapshot) -> ChatbotToolActivitySnapshot,
): List<ChatbotToolActivitySnapshot> {
    val index = indexOfLast { activity ->
        activity.call.id == callId &&
            activity.call.name == toolName &&
            activity.status != ChatbotToolActivityStatus.SUCCEEDED &&
            activity.status != ChatbotToolActivityStatus.FAILED
    }
    if (index < 0) return this
    return toMutableList().also { activities -> activities[index] = update(activities[index]) }
}

private val ChatbotToolResult.terminalStatus: ChatbotToolActivityStatus
    get() = if (isError) ChatbotToolActivityStatus.FAILED else ChatbotToolActivityStatus.SUCCEEDED

private val ChatbotToolActivityStatus.isUnresolved: Boolean
    get() = this == ChatbotToolActivityStatus.PREPARING ||
        this == ChatbotToolActivityStatus.PENDING ||
        this == ChatbotToolActivityStatus.RUNNING

private val ChatbotToolActivityStatus.isTerminal: Boolean
    get() = !isUnresolved
