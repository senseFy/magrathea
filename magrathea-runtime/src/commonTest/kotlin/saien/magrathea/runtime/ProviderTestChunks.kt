package saien.magrathea.runtime

import saien.magrathea.core.StopReason
import saien.magrathea.core.ToolCallPart
import saien.magrathea.provider.api.ProviderChunk
import saien.magrathea.provider.api.ProviderEvent
import saien.magrathea.provider.api.ProviderUsage

internal fun providerChunk(
    text: String? = null,
    reasoning: String? = null,
    toolCalls: List<ToolCallPart> = emptyList(),
    completed: Boolean = false,
    stopReason: StopReason = if (toolCalls.isEmpty()) StopReason.COMPLETED else StopReason.TOOL_CALLS,
    usage: ProviderUsage? = null,
): ProviderChunk {
    val events = buildList {
        text?.let {
            add(ProviderEvent.TextStart())
            add(ProviderEvent.TextDelta(it))
            if (completed) add(ProviderEvent.TextEnd())
        }
        reasoning?.let {
            add(ProviderEvent.ReasoningStart())
            add(ProviderEvent.ReasoningDelta(it))
            if (completed) add(ProviderEvent.ReasoningEnd())
        }
        toolCalls.forEach { toolCall ->
            if (toolCall.partial) {
                add(ProviderEvent.ToolCallStart(toolCall))
            } else {
                add(ProviderEvent.ToolCallStart(toolCall.copy(partial = true)))
                add(ProviderEvent.ToolCallEnd(toolCall))
            }
        }
        if (completed) {
            add(
                ProviderEvent.Completed(
                    finishReason = stopReason.name,
                    stopReason = stopReason,
                    usage = usage,
                ),
            )
        } else {
            usage?.let { add(ProviderEvent.UsageDelta(it)) }
        }
    }
    return ProviderChunk(events)
}
