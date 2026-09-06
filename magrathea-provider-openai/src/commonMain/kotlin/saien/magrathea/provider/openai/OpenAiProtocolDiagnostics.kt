package saien.magrathea.provider.openai

import saien.magrathea.provider.api.ProviderProtocolDiagnostic
import saien.magrathea.provider.api.ProviderProtocolException

internal fun ProviderProtocolException.withOpenAiDiagnosticContext(
    eventType: String? = null,
    eventIndex: Long? = null,
): ProviderProtocolException {
    val facts = diagnostic ?: ProviderProtocolDiagnostic("openai.unspecified_protocol_failure")
    return ProviderProtocolException(
        diagnostic = facts.copy(
            eventType = facts.eventType ?: eventType,
            eventIndex = facts.eventIndex ?: eventIndex,
        ),
        message = message.orEmpty(),
        cause = this,
    )
}

/** Never put an arbitrary SSE name or payload type into a trace. */
internal fun safeOpenAiEventType(type: String?): String =
    type?.takeIf { it in DIAGNOSTIC_EVENT_TYPES } ?: "unknown"

private val DIAGNOSTIC_EVENT_TYPES = setOf(
    "data", "error", "response.error",
    "response.created", "response.queued", "response.in_progress",
    "response.output_item.added", "response.output_item.done",
    "response.content_part.added", "response.content_part.done",
    "response.output_text.delta", "response.output_text.done", "response.output_text.annotation.added",
    "response.reasoning_summary_part.added", "response.reasoning_summary_part.done",
    "response.reasoning_text.delta", "response.reasoning_text.done",
    "response.reasoning_summary_text.delta", "response.reasoning_summary_text.done",
    "response.function_call_arguments.delta", "response.function_call_arguments.done",
    "response.custom_tool_call_input.delta", "response.custom_tool_call_input.done",
    "response.x_search_call.in_progress", "response.x_search_call.searching", "response.x_search_call.completed",
    "response.completed", "response.incomplete", "response.failed",
)
