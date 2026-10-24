package saien.magrathea.chatbot

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import saien.magrathea.core.ToolCallPart
import saien.magrathea.core.ToolOrigin
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
private const val AWAIT_RESULTS_METADATA = "magrathea.background.results"
private const val RESULT_IS_ERROR_METADATA = "magrathea.background.result_is_error"
private const val RESULT_ERROR_CODE_METADATA = "magrathea.background.user_error_code"

/**
 * Authoritative evidence for the background projection: receipts and runtime-authored closes in
 * canonical messages or the Tool journal, retrieval payloads, and the runtime's Session view.
 * Receipt and preemption evidence binds to a specific call identity — Providers may reuse call
 * IDs across turns, so [ChatbotToolActivityKey] (message + call ordinal) is the lookup key.
 */
internal class ChatbotBackgroundProjection(
    private val executionsByHandle: Map<String, ChatbotBackgroundExecution>,
    private val receiptsByCall: Map<ChatbotToolActivityKey, ToolBackgroundReceipt>,
    private val preemptedCalls: Set<ChatbotToolActivityKey>,
    private val readResultsByHandle: Map<String, ToolResultPart>,
) {
    fun apply(activity: ChatbotToolActivitySnapshot): ChatbotToolActivitySnapshot {
        val receipt = receiptsByCall[activity.key]
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
                        ?.toBackgroundToolResult(activity),
                    background = execution,
                )
            }
        }
        if (activity.key in preemptedCalls) {
            return activity.copy(
                status = ChatbotToolActivityStatus.CANCELLED,
                result = null,
            )
        }
        return activity
    }
}

/**
 * The attached result adopts the stored execution's error semantics: the read part itself stays
 * non-error so control traffic never looks like a Tool failure, but the projected activity is
 * FAILED with the Tool's own error marker and code.
 */
private fun ToolResultPart.toBackgroundToolResult(
    activity: ChatbotToolActivitySnapshot,
): ChatbotToolResult = copy(
    isError = metadata[RESULT_IS_ERROR_METADATA]?.jsonPrimitive?.booleanOrNull == true,
    userErrorCode = (metadata[RESULT_ERROR_CODE_METADATA] as? JsonPrimitive)?.content
        ?: userErrorCode,
).toChatbotToolResult().copy(
    id = activity.call.id,
    name = activity.call.name,
)

internal fun buildBackgroundProjection(
    background: BackgroundSessionSnapshot?,
    messages: List<AgentMessage>,
    toolExecutions: List<ToolExecutionRecord>,
    backgroundEnabled: Boolean = false,
): ChatbotBackgroundProjection {
    val receipts = mutableMapOf<ChatbotToolActivityKey, ToolBackgroundReceipt>()
    val preempted = mutableSetOf<ChatbotToolActivityKey>()
    val readResults = mutableMapOf<String, ToolResultPart>()
    val callIndex = messages.chatbotCallIndex(backgroundEnabled)

    callIndex.resultBindings.forEach { binding ->
        binding.call?.key?.let { key ->
            binding.result.backgroundReceipt?.let { receipts[key] = it }
            if (binding.result.metadata[PREEMPTED_METADATA] != null) {
                preempted += key
            }
        }
        binding.result.retrievalResults().forEach { (handle, result) ->
            readResults[handle] = result
        }
    }
    toolExecutions.forEach { record ->
        val key = callIndex.lastKeyFor(record.toolCallId, record.toolName)
        record.result?.backgroundReceipt?.let { receipt ->
            if (key != null) receipts[key] = receipt
        }
        if (record.result?.metadata?.get(PREEMPTED_METADATA) != null && key != null) {
            preempted += key
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

/**
 * The retrieval payloads a part carries: one handle → result pair per settled handle, so a
 * multi-handle await attributes each slice to its own background execution. Later messages
 * overwrite earlier pairs of the same handle.
 */
private fun ToolResultPart.retrievalResults(): List<Pair<String, ToolResultPart>> {
    if (toolName == "read_background_result") {
        return metadata[HANDLE_METADATA]?.jsonPrimitive?.content
            ?.let { listOf(it to this) }
            .orEmpty()
    }
    if (toolName == "await_background_results") {
        val entries = metadata[AWAIT_RESULTS_METADATA] as? JsonArray
        if (entries != null) {
            return entries.mapNotNull { entry ->
                (entry as? JsonObject)?.let(::awaitSliceResult)
            }
        }
        val results = (result as? JsonObject)?.get("results") as? JsonArray ?: return emptyList()
        val single = (results.singleOrNull() as? JsonObject) ?: return emptyList()
        val handle = single["handle"]?.jsonPrimitive?.content ?: return emptyList()
        return listOf(handle to this)
    }
    return emptyList()
}

/**
 * Rebuilds the result one awaited handle delivered: its own content slice, metadata (with the
 * runtime's handle/execution/Tool identity and the slice's error semantics), origin, and display
 * text. An out-of-range slice yields empty content rather than a failure.
 */
private fun ToolResultPart.awaitSliceResult(entry: JsonObject): Pair<String, ToolResultPart>? {
    val handle = entry["handle"]?.jsonPrimitive?.content ?: return null
    val offset = entry["content_offset"]?.jsonPrimitive?.intOrNull ?: 0
    val count = entry["content_count"]?.jsonPrimitive?.intOrNull ?: 0
    val slice = if (offset >= 0 && count >= 0 && offset + count <= content.size) {
        content.subList(offset, offset + count)
    } else {
        emptyList()
    }
    val origin = (entry["origin"] as? JsonObject)?.let { encoded ->
        runCatching {
            Json.decodeFromJsonElement(ToolOrigin.serializer(), encoded)
        }.getOrNull()
    }
    return handle to copy(
        metadata = buildJsonObject {
            (entry["metadata"] as? JsonObject)?.forEach { (key, value) -> put(key, value) }
            put("magrathea.background.handle", handle)
            entry["execution_id"]?.let { put("magrathea.background.execution_id", it) }
            entry["tool_name"]?.let { put("magrathea.background.tool_name", it) }
            entry["result_is_error"]?.let { put(RESULT_IS_ERROR_METADATA, it) }
            entry["user_error_code"]?.let { put(RESULT_ERROR_CODE_METADATA, it) }
        },
        origin = origin,
        displayText = (entry["display_text"] as? JsonPrimitive)?.content,
        content = slice,
    )
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
