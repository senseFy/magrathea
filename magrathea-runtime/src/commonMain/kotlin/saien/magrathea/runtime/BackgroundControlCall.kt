package saien.magrathea.runtime

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/** Validated model input. Background control execution never decodes untrusted JSON itself. */
internal sealed interface BackgroundControlCall {
    data class Read(val handle: String) : BackgroundControlCall
    data class Await(val handles: List<String>, val maxWaitMillis: Long?) : BackgroundControlCall
    data class Cancel(val handles: List<String>) : BackgroundControlCall
    data object Finish : BackgroundControlCall
}

internal sealed interface BackgroundControlParseResult {
    data class Valid(val call: BackgroundControlCall) : BackgroundControlParseResult
    data class Invalid(val message: String) : BackgroundControlParseResult
}

/**
 * Keeps malformed control arguments on the ordinary paired Tool-error path. Validation is pure:
 * no wait budget, cancellation, or stored-result access happens until the whole input is valid.
 */
internal fun parseBackgroundControlCall(
    name: String,
    arguments: JsonObject,
): BackgroundControlParseResult {
    fun invalid(message: String) = BackgroundControlParseResult.Invalid(message)
    fun handle(value: kotlinx.serialization.json.JsonElement?): String? =
        (value as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }

    return when (name) {
        BackgroundToolInstructions.READ_TOOL -> {
            val handle = handle(arguments["handle"])
                ?: return invalid("handle must be a non-blank string.")
            BackgroundControlParseResult.Valid(BackgroundControlCall.Read(handle))
        }
        BackgroundToolInstructions.AWAIT_TOOL,
        BackgroundToolInstructions.CANCEL_TOOL,
        -> {
            val values = arguments["handles"] as? JsonArray
                ?: return invalid("handles must be an array of non-blank strings.")
            val handles = values.map { value ->
                handle(value) ?: return invalid("handles must contain only non-blank strings.")
            }
            if (name == BackgroundToolInstructions.CANCEL_TOOL) {
                BackgroundControlParseResult.Valid(BackgroundControlCall.Cancel(handles))
            } else {
                val maxWaitMillis = if ("max_wait_ms" in arguments) {
                    val value = arguments["max_wait_ms"] as? JsonPrimitive
                    value?.takeUnless { it.isString }?.longOrNull?.takeIf { it >= 0 }
                        ?: return invalid("max_wait_ms must be a non-negative integer.")
                } else {
                    null
                }
                BackgroundControlParseResult.Valid(BackgroundControlCall.Await(handles, maxWaitMillis))
            }
        }
        BackgroundToolInstructions.FINISH_TOOL ->
            BackgroundControlParseResult.Valid(BackgroundControlCall.Finish)
        else -> invalid("Unknown background control.")
    }
}
