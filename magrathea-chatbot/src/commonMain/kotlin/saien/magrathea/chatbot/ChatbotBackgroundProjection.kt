package saien.magrathea.chatbot

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonPrimitive
import saien.magrathea.core.AgentMessage
import saien.magrathea.core.BackgroundExecutionState
import saien.magrathea.core.BackgroundOutcome
import saien.magrathea.core.ToolBackgroundReceipt
import saien.magrathea.core.ToolExecutionRecord
import saien.magrathea.core.ToolResultPart
import saien.magrathea.runtime.BackgroundExecutionView
import saien.magrathea.runtime.BackgroundSessionSnapshot
import saien.magrathea.runtime.BackgroundToolInstructions

internal const val PREEMPTED_METADATA = "magrathea.tool.preempted"
private const val HANDLE_METADATA = "magrathea.background.handle"

/**
 * Authoritative evidence for the background projection: receipts and runtime-authored closes in
 * canonical messages or the Tool journal, retrieval payloads, and the runtime's Session view.
 */
internal class ChatbotBackgroundProjection(
    private val executionsByHandle: Map<String, ChatbotBackgroundExecution>,
    private val receiptsByCall: Map<String, ToolBackgroundReceipt>,
    private val preemptedCalls: Set<String>,
    private val readResultsByHandle: Map<String, ToolResultPart>,
) {
    fun apply(activity: ChatbotToolActivitySnapshot): ChatbotToolActivitySnapshot {
        val receipt = receiptsByCall[activity.call.id]
        if (receipt != null) {
            val view = executionsByHandle[receipt.handle]
            val execution = view ?: ChatbotBackgroundExecution(
                handle = receipt.handle,
                toolName = activity.call.name,
                originCallId = activity.call.id,
                state = receipt.state.toChatbotBackgroundState(),
            )
            return when (execution.state) {
                ChatbotBackgroundState.QUEUED -> activity.copy(
                    status = ChatbotToolActivityStatus.PENDING,
                    result = null,
                    background = execution,
                )
                ChatbotBackgroundState.RUNNING -> activity.copy(
                    status = ChatbotToolActivityStatus.RUNNING,
                    result = null,
                    background = execution,
                )
                ChatbotBackgroundState.SUCCEEDED,
                ChatbotBackgroundState.FAILED,
                ChatbotBackgroundState.CANCELLED,
                ChatbotBackgroundState.INTERRUPTED,
                -> activity.copy(
                    status = execution.state.toActivityStatus(),
                    result = readResultsByHandle[receipt.handle]
                        ?.toChatbotToolResult()
                        ?.copy(id = activity.call.id, name = activity.call.name),
                    background = execution,
                )
            }
        }
        if (activity.call.id in preemptedCalls) {
            return activity.copy(
                status = ChatbotToolActivityStatus.CANCELLED,
                result = null,
            )
        }
        return activity
    }
}

internal fun buildBackgroundProjection(
    background: BackgroundSessionSnapshot?,
    messages: List<AgentMessage>,
    toolExecutions: List<ToolExecutionRecord>,
): ChatbotBackgroundProjection {
    val receipts = mutableMapOf<String, ToolBackgroundReceipt>()
    val preempted = mutableSetOf<String>()
    val readResults = mutableMapOf<String, ToolResultPart>()
    messages.forEach { message ->
        message.parts.filterIsInstance<ToolResultPart>().forEach { part ->
            part.backgroundReceipt?.let { receipts[part.toolCallId] = it }
            if (part.metadata[PREEMPTED_METADATA] != null) {
                preempted += part.toolCallId
            }
            part.readRetrievalHandle()?.let { handle ->
                readResults[handle] = part
            }
        }
    }
    toolExecutions.forEach { record ->
        record.result?.backgroundReceipt?.let { receipts[record.toolCallId] = it }
        if (record.result?.metadata?.get(PREEMPTED_METADATA) != null) {
            preempted += record.toolCallId
        }
    }
    return ChatbotBackgroundProjection(
        executionsByHandle = background?.executions.orEmpty()
            .associateBy { it.handle }
            .mapValues { (_, view) -> view.toChatbotBackgroundExecution() },
        receiptsByCall = receipts,
        preemptedCalls = preempted,
        readResultsByHandle = readResults,
    )
}

internal fun List<ChatbotToolActivitySnapshot>.applyBackgroundProjection(
    projection: ChatbotBackgroundProjection,
): List<ChatbotToolActivitySnapshot> = map { projection.apply(it) }

/** The retrieval payload for [handle], from the latest read or single-handle await result. */
private fun ToolResultPart.readRetrievalHandle(): String? {
    if (toolName == "read_background_result") {
        return metadata[HANDLE_METADATA]?.jsonPrimitive?.content
    }
    if (toolName == "await_background_results") {
        val results = (result as? JsonObject)?.get("results") as? JsonArray ?: return null
        val single = (results.singleOrNull() as? JsonObject) ?: return null
        return single["handle"]?.jsonPrimitive?.content
    }
    return null
}

internal fun BackgroundExecutionView.toChatbotBackgroundExecution(): ChatbotBackgroundExecution =
    ChatbotBackgroundExecution(
        handle = handle,
        toolName = toolName,
        originCallId = originCallId,
        state = state.toChatbotBackgroundState(outcome),
    )

internal fun BackgroundSessionSnapshot.toChatbotBackgroundSnapshot(): ChatbotBackgroundSnapshot =
    ChatbotBackgroundSnapshot(
        executions = executions.map { it.toChatbotBackgroundExecution() },
        hasPendingActivation = pendingActivation != null,
        requiresExecutionHost = requiresExecutionHost,
    )

private fun BackgroundExecutionState.toChatbotBackgroundState(
    outcome: BackgroundOutcome? = null,
): ChatbotBackgroundState = when (this) {
    BackgroundExecutionState.QUEUED -> ChatbotBackgroundState.QUEUED
    BackgroundExecutionState.RUNNING -> ChatbotBackgroundState.RUNNING
    BackgroundExecutionState.TERMINAL -> when (outcome) {
        BackgroundOutcome.SUCCEEDED -> ChatbotBackgroundState.SUCCEEDED
        BackgroundOutcome.ERROR,
        BackgroundOutcome.TIMEOUT,
        -> ChatbotBackgroundState.FAILED
        BackgroundOutcome.CANCELLED -> ChatbotBackgroundState.CANCELLED
        BackgroundOutcome.INTERRUPTED,
        null,
        -> ChatbotBackgroundState.INTERRUPTED
    }
}

private fun ChatbotBackgroundState.toActivityStatus(): ChatbotToolActivityStatus = when (this) {
    ChatbotBackgroundState.QUEUED -> ChatbotToolActivityStatus.PENDING
    ChatbotBackgroundState.RUNNING -> ChatbotToolActivityStatus.RUNNING
    ChatbotBackgroundState.SUCCEEDED -> ChatbotToolActivityStatus.SUCCEEDED
    ChatbotBackgroundState.FAILED -> ChatbotToolActivityStatus.FAILED
    ChatbotBackgroundState.CANCELLED -> ChatbotToolActivityStatus.CANCELLED
    ChatbotBackgroundState.INTERRUPTED -> ChatbotToolActivityStatus.INTERRUPTED
}
