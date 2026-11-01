package saien.magrathea.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Wire identities used by persisted background control results. Tool declarations, prompts,
 * scheduling, and execution admission remain the responsibility of Runtime.
 */
object BackgroundToolProtocol {
    const val READ_TOOL = "read_background_result"
    const val AWAIT_TOOL = "await_background_results"
    const val CANCEL_TOOL = "cancel_background_executions"
    const val FINISH_TOOL = "finish_without_reply"

    const val HANDLE_METADATA_KEY = "magrathea.background.handle"
    const val AWAIT_RESULTS_METADATA_KEY = "magrathea.background.results"
    const val CONTROL_RESULT_METADATA_KEY = "magrathea.background.control"
    const val CONTROL_RUN_ID_METADATA_KEY = "magrathea.background.control_run_id"
    const val RESULT_IS_ERROR_METADATA_KEY = "magrathea.background.result_is_error"
    const val RESULT_ERROR_CODE_METADATA_KEY = "magrathea.background.user_error_code"
}

/** Canonical-history facts, independent of execution status, host policy, and activation budgets. */
data class BackgroundResultDeliveryFacts(
    val delivered: Boolean,
    val acknowledged: Boolean,
)

/**
 * Derives delivery and acknowledgement from canonical [messages] for one background result.
 *
 * A matching Runtime notice carries both [handle] and [resultVersion]. Terminal read/await
 * results in the existing wire protocol are handle-addressed. An acknowledgement must follow
 * the latest matching delivery in a later message: a COMPLETED assistant message or a
 * successful `finish_without_reply` result. Non-terminal and failed retrievals do not deliver.
 * Unknown JSON shapes are ignored; arbitrary Tool results must not prevent history inspection.
 *
 * Only lightweight result identity is required; callers do not need a complete execution record
 * or a live session. These facts neither authorize execution nor decide automatic continuation.
 */
fun backgroundResultDeliveryFacts(
    handle: String,
    resultVersion: Int,
    messages: List<AgentMessage>,
): BackgroundResultDeliveryFacts {
    val latestDeliveryIndex = backgroundDeliveryIndices(handle, resultVersion, messages)
        .lastOrNull() ?: return BackgroundResultDeliveryFacts(delivered = false, acknowledged = false)
    val acknowledged = messages.drop(latestDeliveryIndex + 1).any { message ->
        when {
            message.role == MessageRole.ASSISTANT && message.stopReason == StopReason.COMPLETED -> true
            message.role == MessageRole.TOOL -> message.parts.filterIsInstance<ToolResultPart>().any {
                it.toolName == BackgroundToolProtocol.FINISH_TOOL && !it.isError
            }
            else -> false
        }
    }
    return BackgroundResultDeliveryFacts(delivered = true, acknowledged = acknowledged)
}

/** `status` values in a retrieval result that mean the model has the outcome. */
private val TERMINAL_DELIVERY_STATUSES =
    setOf("succeeded", "failed", "timed_out", "interrupted", "cancelled")

private fun ToolResultPart.deliversTerminalBackgroundResult(handle: String): Boolean {
    if (isError) return false
    return when (toolName) {
        BackgroundToolProtocol.READ_TOOL ->
            (metadata[BackgroundToolProtocol.HANDLE_METADATA_KEY] as? JsonPrimitive)?.contentOrNull == handle &&
                ((result as? JsonObject)?.get("status") as? JsonPrimitive)?.contentOrNull in TERMINAL_DELIVERY_STATUSES
        BackgroundToolProtocol.AWAIT_TOOL ->
            ((result as? JsonObject)?.get("results") as? JsonArray)?.any { entry ->
                val fields = entry as? JsonObject ?: return@any false
                (fields["handle"] as? JsonPrimitive)?.contentOrNull == handle &&
                    (fields["status"] as? JsonPrimitive)?.contentOrNull in TERMINAL_DELIVERY_STATUSES
            } ?: false
        else -> false
    }
}

private fun backgroundDeliveryIndices(
    handle: String,
    resultVersion: Int,
    messages: List<AgentMessage>,
): List<Int> = messages.mapIndexedNotNull { index, message ->
    when {
        message.role == MessageRole.RUNTIME &&
            message.parts.filterIsInstance<RuntimeNoticePart>().any { part ->
                part.entries.any {
                    it.handle == handle && it.resultVersion == resultVersion
                }
            } -> index
        message.role == MessageRole.TOOL &&
            message.parts.filterIsInstance<ToolResultPart>().any { part ->
                part.deliversTerminalBackgroundResult(handle)
            } -> index
        else -> null
    }
}
