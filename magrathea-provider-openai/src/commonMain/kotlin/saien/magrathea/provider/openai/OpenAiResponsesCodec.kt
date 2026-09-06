package saien.magrathea.provider.openai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import saien.magrathea.core.StopReason
import saien.magrathea.core.ReasoningContentKind
import saien.magrathea.core.ToolCallPart
import saien.magrathea.provider.api.ProviderChunk
import saien.magrathea.provider.api.ProviderEvent
import saien.magrathea.provider.api.ProviderProtocolDiagnostic
import saien.magrathea.provider.api.ProviderProtocolException
import saien.magrathea.provider.api.ProviderUsage
import saien.magrathea.provider.api.PROVIDER_CITATIONS_METADATA_KEY
import saien.magrathea.provider.api.validateSemantics

internal class OpenAiResponsesCodec(
    private val providerKey: String,
    private val model: String,
    private val allowServerManagedTools: Boolean = false,
) {
    private var started = false
    private var terminal = false
    private val activeItems = linkedMapOf<Int, ActiveOutputItem>()
    private val completedItems = mutableSetOf<Int>()
    private val pendingToolCalls = linkedMapOf<Int, ToolCallPart>()
    private var diagnosticEventType = "unknown"

    fun decodeNonStreaming(payload: String): ProviderChunk {
        if (started) protocolFailure("codec_instance_can_decode_only_one_response", "OpenAI codec instance can decode only one response")
        started = true
        val response = parseObject(payload, "OpenAI response")
        if (response.optionalString("status") == "failed") decodeFailure(response)
        return ProviderChunk(events = decodeTerminal(response)).also(ProviderChunk::validateSemantics)
    }

    fun decodeServerSentEvent(eventName: String?, payload: String): ProviderChunk? {
        started = true
        diagnosticEventType = safeOpenAiEventType(eventName)
        return try {
            // Responses has its own terminal event; an SSE sentinel carries no additional content.
            if (payload == "[DONE]") return null
            val root = parseObject(payload, "OpenAI streaming event")
            val type = root.requiredString("type")
            diagnosticEventType = safeOpenAiEventType(type)
            if (type in setOf("error", "response.error", "response.failed")) decodeFailure(root)
            if (terminal) return null
            val events = when (type) {
                "response.output_item.added" -> decodeItemAdded(root)
                "response.output_text.delta", "response.refusal.delta" -> decodeTextDelta(root)
                "response.reasoning_text.delta" -> decodeReasoningDelta(root, ReasoningWireKind.TEXT)
                "response.reasoning_summary_text.delta" -> decodeReasoningDelta(root, ReasoningWireKind.SUMMARY)
                "response.function_call_arguments.delta" -> decodeFunctionArgumentsDelta(root)
                "response.output_item.done" -> decodeItemDone(root)
                "response.completed", "response.incomplete" -> decodeTerminal(root.requiredObject("response"))
                // Nested boundaries and unconsumed extension events do not determine message validity.
                else -> emptyList()
            }
            events.takeIf(List<ProviderEvent>::isNotEmpty)
                ?.let { ProviderChunk(events = it).also(ProviderChunk::validateSemantics) }
        } catch (failure: ProviderProtocolException) {
            throw failure.withOpenAiDiagnosticContext(eventType = diagnosticEventType)
        }
    }

    fun finish() {
        if (!terminal) protocolFailure("missing_terminal_event", "OpenAI stream ended before a terminal response event")
    }

    private fun decodeItemAdded(root: JsonObject): List<ProviderEvent> {
        val index = root.requiredIndex("output_index")
        if (index in completedItems || index in activeItems) return emptyList()
        val item = root.requiredObject("item")
        val type = item.requiredString("type")
        if (type !in setOf("message", "reasoning", "function_call")) return emptyList()
        val active = ActiveOutputItem(start = item)
        activeItems[index] = active
        return if (type == "function_call" && activeItems.keys.first() == index) startToolCall(index, item)
        else emptyList()
    }

    private fun decodeTextDelta(root: JsonObject): List<ProviderEvent> {
        val active = root.activeItem("message") ?: return emptyList()
        if (active !== activeItems.values.first()) return emptyList()
        val delta = root.requiredString("delta", allowEmpty = true)
        return buildList {
            if (!active.textStarted) {
                active.textStarted = true
                add(ProviderEvent.TextStart())
            }
            add(ProviderEvent.TextDelta(delta))
        }
    }

    private fun decodeReasoningDelta(root: JsonObject, kind: ReasoningWireKind): List<ProviderEvent> {
        val active = root.activeItem("reasoning") ?: return emptyList()
        val key = ReasoningPartKey(kind, root.reasoningIndex(kind))
        val delta = root.requiredString("delta", allowEmpty = true)
        active.reasoningParts.getOrPut(key) { StringBuilder() }.append(delta)
        if (active !== activeItems.values.first()) return emptyList()
        return buildList {
            if (active.streamedReasoning == null) {
                active.streamedReasoning = key
                add(ProviderEvent.ReasoningStart(kind = kind.contentKind))
            }
            // Canonical blocks are sequential. Additional parts are emitted with their final
            // values at item completion, so a later revision cannot overwrite another block.
            if (active.streamedReasoning == key) add(ProviderEvent.ReasoningDelta(delta))
        }
    }

    private fun decodeFunctionArgumentsDelta(root: JsonObject): List<ProviderEvent> {
        val active = root.activeItem("function_call") ?: return emptyList()
        if (active !== activeItems.values.first()) return emptyList()
        return startToolCall(root.requiredIndex("output_index"), active.start) + ProviderEvent.ToolCallDelta(
            active.start.requiredString("call_id"), root.requiredString("delta", allowEmpty = true),
        )
    }

    private fun startToolCall(index: Int, item: JsonObject): List<ProviderEvent> {
        if (index in pendingToolCalls) return emptyList()
        val call = item.toolCall(partial = true)
        pendingToolCalls[index] = call
        return listOf(ProviderEvent.ToolCallStart(call))
    }

    private fun decodeItemDone(root: JsonObject): List<ProviderEvent> {
        val index = root.requiredIndex("output_index")
        if (index in completedItems) return emptyList()
        val item = root.requiredObject("item")
        activeItems.getOrPut(index) { ActiveOutputItem(start = item) }.finalItem = item
        return drainCompletedItems()
    }

    // Only the leading item streams into the sequential canonical blocks. Interleaved items
    // wait for that block to close; their final snapshots supply the content without intermixing it.
    private fun drainCompletedItems(): List<ProviderEvent> = buildList {
        while (activeItems.isNotEmpty()) {
            val (index, active) = activeItems.entries.first()
            val item = active.finalItem ?: break
            val type = item.requiredString("type")
            if (type != active.type) {
                protocolFailure("final_output_item_type_changed", "OpenAI final output item changed content kind")
            }
            if (type == "function_call") {
                addAll(startToolCall(index, item))
            } else {
                addAll(completeItem(item, active))
            }
            activeItems.remove(index)
            completedItems += index
        }
    }

    private fun decodeTerminal(response: JsonObject): List<ProviderEvent> {
        val output = response.requiredArray("output")
        val completed = completedEvent(response, output)
        val events = buildList {
            output.forEachIndexed { index, value ->
                if (index !in completedItems) {
                    val item = value.outputItem()
                    activeItems.getOrPut(index) { ActiveOutputItem(start = item) }.finalItem = item
                }
            }
            addAll(drainCompletedItems())
            if (activeItems.isNotEmpty()) {
                protocolFailure("terminal_output_omitted_active_items", "OpenAI terminal output omitted active items")
            }
            // Executable arguments come from the terminal snapshot, just like same-model replay.
            output.forEachIndexed { index, value ->
                val item = value.outputItem()
                if (item.optionalString("type") == "function_call") {
                    val call = item.toolCall(partial = completed.stopReason != StopReason.TOOL_CALLS)
                    val startedCall = pendingToolCalls.remove(index)
                    if (startedCall != null && (call.toolCallId != startedCall.toolCallId || call.toolName != startedCall.toolName)) {
                        protocolFailure("final_function_call_identity_changed", "OpenAI final function call identity changed")
                    }
                    if (!call.partial) add(ProviderEvent.ToolCallEnd(call))
                }
            }
            if (pendingToolCalls.isNotEmpty()) {
                protocolFailure("terminal_output_omitted_tool_calls", "OpenAI terminal output omitted pending tool calls")
            }
            add(completed)
        }
        terminal = true
        return events
    }

    private fun completeItem(item: JsonObject, active: ActiveOutputItem): List<ProviderEvent> {
        return when (item.requiredString("type")) {
            "message" -> buildList {
                val text = item.requiredArray("content").joinToString("") { value ->
                    val part = value.outputItem()
                    when (part.requiredString("type")) {
                        "output_text" -> part.requiredString("text", allowEmpty = true)
                        "refusal" -> part.requiredString("refusal", allowEmpty = true)
                        else -> ""
                    }
                }
                if (!active.textStarted) {
                    add(ProviderEvent.TextStart())
                    add(ProviderEvent.TextDelta(text))
                }
                add(ProviderEvent.TextEnd(text))
            }
            "reasoning" -> completeReasoning(item, active)
            "x_search_call", "custom_tool_call" -> {
                if (!allowServerManagedTools) {
                    protocolFailure("unrequested_hosted_tool", "OpenAI response contains an unrequested hosted tool")
                }
                emptyList()
            }
            else -> emptyList()
        }
    }

    private fun completeReasoning(item: JsonObject, active: ActiveOutputItem): List<ProviderEvent> = buildList {
        val parts = item.reasoningContent()
        active.reasoningParts.forEach { (key, text) ->
            if (item[key.kind.field] == null || item[key.kind.field] == JsonNull) parts[key] = text.toString()
        }
        val streamed = active.streamedReasoning
        val redacted = parts.values.all(String::isEmpty) && item.optionalString("encrypted_content") != null
        if (streamed != null) {
            val text = parts.remove(streamed).orEmpty()
            add(ProviderEvent.ReasoningEnd(text = if (redacted) "" else text, redacted = redacted))
        }
        parts.filterValues(String::isNotEmpty).forEach { (key, text) ->
            add(ProviderEvent.ReasoningStart(kind = key.kind.contentKind))
            add(ProviderEvent.ReasoningDelta(text))
            add(ProviderEvent.ReasoningEnd(text))
        }
        if (streamed == null && redacted) {
            add(ProviderEvent.ReasoningStart(redacted = true))
            add(ProviderEvent.ReasoningEnd(redacted = true))
        }
    }

    private fun JsonObject.activeItem(type: String): ActiveOutputItem? =
        activeItems[requiredIndex("output_index")]?.takeIf { it.type == type && it.finalItem == null }

    private fun decodeFailure(root: JsonObject): Nothing {
        val envelope = root["response"] as? JsonObject ?: root
        val error = envelope["error"] as? JsonObject ?: root["error"] as? JsonObject
        val metadata = error?.get("metadata") as? JsonObject
        throwOpenAiInBandFailure(
            label = "OpenAI Responses",
            code = error?.optionalString("code") ?: error?.optionalString("type") ?: root.optionalString("code"),
            errorType = envelope.optionalString("error_type") ?: metadata?.optionalString("error_type"),
            providerMessage = error?.optionalString("message") ?: root.optionalString("message"),
        )
    }

    private fun completedEvent(response: JsonObject, output: JsonArray): ProviderEvent.Completed {
        val status = response.requiredString("status")
        if (status !in setOf("completed", "incomplete")) {
            protocolFailure("response_ended_with_unsupported_status", "OpenAI response ended with unsupported status $status")
        }
        val callIds = output.mapNotNull { value ->
            (value as? JsonObject)?.takeIf { it.optionalString("type") == "function_call" }?.requiredString("call_id")
        }
        if (callIds.distinct().size != callIds.size) {
            protocolFailure("duplicate_function_call_id", "OpenAI final tool calls must have distinct call IDs")
        }
        val stopReason = when {
            status == "incomplete" &&
                (response["incomplete_details"] as? JsonObject)?.optionalString("reason") == "max_output_tokens" -> StopReason.MAX_TOKENS
            status == "incomplete" -> StopReason.ERROR
            callIds.isNotEmpty() -> StopReason.TOOL_CALLS
            else -> StopReason.COMPLETED
        }
        return ProviderEvent.Completed(
            finishReason = status,
            stopReason = stopReason,
            usage = (response["usage"] as? JsonObject)?.toUsage(),
            providerMetadata = buildJsonObject {
                put("provider", providerKey)
                put("model", model)
                response.optionalString("id")?.let { put(OPENAI_RESPONSE_ID_METADATA, it) }
                put(OPENAI_RESPONSE_STATUS_METADATA, status)
                put(OPENAI_RESPONSE_OUTPUT_METADATA, output)
                response.normalizedCitations(output).takeIf(JsonArray::isNotEmpty)?.let { citations ->
                    put(PROVIDER_CITATIONS_METADATA_KEY, citations)
                }
            },
        )
    }
}

private data class ActiveOutputItem(
    val start: JsonObject,
    var finalItem: JsonObject? = null,
    var textStarted: Boolean = false,
    var streamedReasoning: ReasoningPartKey? = null,
    val reasoningParts: MutableMap<ReasoningPartKey, StringBuilder> = linkedMapOf(),
) {
    val type: String get() = start.requiredString("type")
}

private data class ReasoningPartKey(val kind: ReasoningWireKind, val index: Int)

private enum class ReasoningWireKind(val field: String, val partType: String, val contentKind: ReasoningContentKind) {
    SUMMARY("summary", "summary_text", ReasoningContentKind.SUMMARY),
    TEXT("content", "reasoning_text", ReasoningContentKind.TEXT),
}

private fun JsonObject.reasoningContent(): MutableMap<ReasoningPartKey, String> = linkedMapOf<ReasoningPartKey, String>().also { parts ->
    ReasoningWireKind.entries.forEach { kind ->
        optionalArray(kind.field)?.forEachIndexed { index, value ->
            val part = value.outputItem()
            if (part.requiredString("type") == kind.partType) {
                parts[ReasoningPartKey(kind, index)] = part.requiredString("text", allowEmpty = true)
            }
        }
    }
}

private fun JsonObject.toolCall(partial: Boolean): ToolCallPart = ToolCallPart(
    toolCallId = requiredString("call_id"),
    toolName = requiredString("name"),
    arguments = if (partial) JsonObject(emptyMap()) else parseArguments(requiredString("arguments", allowEmpty = true), "OpenAI function-call arguments"),
    partial = partial,
    providerCallId = optionalString("id")?.takeIf(String::isNotBlank),
    providerMetadata = this,
)

private fun JsonElement.outputItem(): JsonObject = this as? JsonObject
    ?: protocolFailure("output_item_must_be_an_object", "OpenAI output item must be an object")

private fun JsonObject.reasoningIndex(kind: ReasoningWireKind): Int = requiredIndex(
    if (kind == ReasoningWireKind.SUMMARY) "summary_index" else "content_index",
)

private fun JsonObject.normalizedCitations(output: JsonArray): JsonArray {
    val annotationTitles = linkedMapOf<String, String>()
    output.forEach { outputItem ->
        val item = outputItem as? JsonObject ?: return@forEach
        if (item.optionalString("type") != "message") return@forEach
        (item["content"] as? JsonArray).orEmpty().forEach { contentItem ->
            val content = contentItem as? JsonObject ?: return@forEach
            if (content.optionalString("type") != "output_text") return@forEach
            (content["annotations"] as? JsonArray).orEmpty().forEach { annotationItem ->
                val annotation = annotationItem as? JsonObject ?: return@forEach
                if (annotation.optionalString("type") != "url_citation") return@forEach
                val url = annotation.optionalString("url")?.takeIf(String::isNotBlank) ?: return@forEach
                if (url !in annotationTitles) annotationTitles[url] = annotation.optionalString("title").orEmpty()
            }
        }
    }
    val urls = linkedSetOf<String>()
    (this["citations"] as? JsonArray).orEmpty().forEach { citation ->
        (citation as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)?.let(urls::add)
    }
    urls += annotationTitles.keys
    return buildJsonArray {
        urls.forEach { url ->
            add(buildJsonObject {
                val annotationTitle = annotationTitles[url].orEmpty()
                put(
                    "title",
                    annotationTitle.takeUnless { title -> title.all(Char::isDigit) }.orEmpty().ifBlank { url },
                )
                put("url", url)
                put("snippet", "")
            })
        }
    }
}

private fun JsonObject.toUsage(): ProviderUsage = ProviderUsage(
    inputTokens = (this["input_tokens"] as? JsonPrimitive)?.intOrNull,
    outputTokens = (this["output_tokens"] as? JsonPrimitive)?.intOrNull,
    reasoningTokens = ((this["output_tokens_details"] as? JsonObject)?.get("reasoning_tokens") as? JsonPrimitive)?.intOrNull,
)

private fun parseArguments(value: String, label: String): JsonObject {
    if (value.isBlank()) return JsonObject(emptyMap())
    val parsed = try {
        Json.parseToJsonElement(value)
    } catch (failure: Throwable) {
        throw ProviderProtocolException(
            ProviderProtocolDiagnostic("openai.responses.malformed_arguments"), "Malformed $label", failure,
        )
    }
    return parsed as? JsonObject ?: protocolFailure("arguments_not_object", "$label must decode to an object")
}

private fun parseObject(payload: String, label: String): JsonObject = try {
    Json.parseToJsonElement(payload) as? JsonObject ?: protocolFailure("payload_not_object", "$label must be a JSON object")
} catch (failure: ProviderProtocolException) {
    throw failure
} catch (failure: Throwable) {
    throw ProviderProtocolException(
        ProviderProtocolDiagnostic("openai.responses.malformed_json"), "Malformed $label", failure,
    )
}

private fun JsonObject.requiredObject(key: String): JsonObject =
    this[key] as? JsonObject ?: protocolFailure("invalid_object_field", "OpenAI payload is missing object field $key", field = key)

private fun JsonObject.requiredArray(key: String): JsonArray =
    this[key] as? JsonArray ?: protocolFailure("invalid_array_field", "OpenAI payload is missing array field $key", field = key)

private fun JsonObject.optionalArray(key: String): JsonArray? = when (val value = this[key]) {
    null, JsonNull -> null
    is JsonArray -> value
    else -> protocolFailure("invalid_array_field", "OpenAI payload field $key must be an array", field = key)
}

private fun JsonObject.requiredString(key: String, allowEmpty: Boolean = false): String {
    val value = (this[key] as? JsonPrimitive)?.contentOrNull
        ?: protocolFailure("invalid_string_field", "OpenAI payload is missing string field $key", field = key)
    if (!allowEmpty && value.isBlank()) protocolFailure("blank_string_field", "OpenAI payload field $key must not be blank", field = key)
    return value
}

private fun JsonObject.optionalString(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

private fun JsonObject.requiredIndex(key: String): Int =
    (this[key] as? JsonPrimitive)?.intOrNull?.takeIf { it >= 0 }
        ?: protocolFailure("invalid_index_field", "OpenAI payload is missing non-negative integer field $key", field = key)

private fun protocolFailure(reason: String, message: String, field: String? = null): Nothing =
    throw ProviderProtocolException(ProviderProtocolDiagnostic("openai.responses.$reason", field = field), message)
