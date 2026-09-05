package saien.magrathea.runtime

import saien.magrathea.provider.api.ProviderChunk
import saien.magrathea.provider.api.ProviderEvent
import saien.magrathea.provider.api.ProviderException
import saien.magrathea.provider.api.ProviderHttpException
import saien.magrathea.provider.api.ProviderRequest

/** One fixed-size accumulator per physical attempt; no message history or payload is retained. */
internal class ProviderTraceSummary(
    private val span: RuntimeTraceSpan,
    request: ProviderRequest,
) {
    private val recording = span.context != null
    private var chunks = 0L
    private var events = 0L
    private var textChars = 0L
    private var reasoningChars = 0L
    private var toolCalls = 0L
    private var activeTextChars = 0L
    private var activeReasoningChars = 0L
    private var firstText = false
    private var terminal = false

    init {
        if (recording) span.addEvent("magrathea.provider.request_facts", traceAttributes(
            "message_count" to request.messages.size,
            "tool_count" to request.tools.size,
            "streaming" to request.model.supportsStreaming,
            "custom_endpoint" to (request.endpoint != null),
        ))
    }

    fun observe(chunk: ProviderChunk) {
        if (!recording) return
        chunks += 1
        events += chunk.events.size
        for (event in chunk.events) when (event) {
            is ProviderEvent.TextStart -> activeTextChars = 0
            is ProviderEvent.TextDelta -> {
                textChars += event.delta.length
                activeTextChars += event.delta.length
                observeText(event.delta)
            }
            is ProviderEvent.TextEnd -> {
                event.text?.let { text ->
                    textChars += text.length - activeTextChars
                    observeText(text)
                }
                activeTextChars = 0
            }
            is ProviderEvent.ReasoningStart -> activeReasoningChars = 0
            is ProviderEvent.ReasoningDelta -> {
                reasoningChars += event.delta.length
                activeReasoningChars += event.delta.length
            }
            is ProviderEvent.ReasoningEnd -> {
                event.text?.let { reasoningChars += it.length - activeReasoningChars }
                activeReasoningChars = 0
            }
            is ProviderEvent.ToolCallStart -> toolCalls += 1
            is ProviderEvent.Completed -> terminal = true
            else -> Unit
        }
    }

    private fun observeText(text: String) {
        if (!firstText && text.isNotEmpty()) {
            firstText = true
            span.addEvent("magrathea.provider.first_text")
        }
    }

    fun failure(failure: Throwable, protocolViolation: Boolean = false) {
        if (!recording) return
        val cause = failure.providerFailureCause() ?: failure
        span.addEvent("magrathea.provider.failure", traceAttributes(
            "type" to if (protocolViolation) "protocol" else cause.providerTraceFailureType(),
            "http_status" to (cause as? ProviderHttpException)?.statusCode,
            "retryable" to (cause as? ProviderException)?.retryable,
        ))
    }

    fun finish() {
        if (!recording) return
        span.addEvent("magrathea.provider.content_counts", traceAttributes(
            "events" to events,
            "text_chars" to textChars,
            "reasoning_chars" to reasoningChars,
            "tool_calls" to toolCalls,
        ))
        span.addEvent("magrathea.provider.stream_summary", traceAttributes(
            "chunks" to chunks,
            "terminal_observed" to terminal,
        ))
    }
}
