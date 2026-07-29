package saien.magrathea.provider.openai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import saien.magrathea.core.ReasoningContentKind
import saien.magrathea.core.StopReason
import saien.magrathea.core.ToolCallPart
import saien.magrathea.provider.api.ProviderChunk
import saien.magrathea.provider.api.ProviderEvent
import saien.magrathea.provider.api.ProviderProtocolException
import saien.magrathea.provider.api.ProviderUsage
import saien.magrathea.provider.api.validateSemantics

/** Normalizes the portable Chat Completions response shape into Magrathea events. */
internal class OpenAiChatCompletionsCodec(
    private val providerKey: String,
    private val requestedModel: String,
) {
    private var responseId: String? = null
    private var responseModel: String? = null
    private var finishReason: String? = null
    private var usage: ProviderUsage? = null
    private var terminal = false
    private var textStarted = false
    private var reasoningStarted = false
    private var reasoningMode = ChatReasoningMode.NONE
    private val text = StringBuilder()
    private val reasoning = StringBuilder()
    private val reasoningDetails = linkedMapOf<Int, ActiveChatReasoningDetail>()
    private var activeReasoningDetail: ActiveChatReasoningDetail? = null
    private val toolCalls = linkedMapOf<Int, ActiveChatToolCall>()

    fun decodeNonStreaming(payload: String): ProviderChunk {
        ensurePristine()
        val root = parseChatObject(payload, "OpenAI-compatible response")
        root.throwIfError()
        responseId = root.requiredChatString("id")
        responseModel = root.optionalChatString("model")
        val choice = root.singleChoice()
        val message = choice.requiredChatObject("message")
        if (message.requiredChatString("role") != "assistant") {
            chatProtocolFailure("OpenAI-compatible response role must be assistant")
        }
        val events = buildList {
            addAll(decodeCompleteReasoning(message))
            message.optionalChatString("content")?.takeIf(String::isNotEmpty)?.let { value ->
                add(ProviderEvent.TextStart())
                add(ProviderEvent.TextDelta(value))
                add(ProviderEvent.TextEnd(text = value))
            }
            val calls = decodeCompleteToolCalls(message["tool_calls"])
            calls.forEach { call ->
                add(ProviderEvent.ToolCallStart(call.copy(arguments = JsonObject(emptyMap()), partial = true)))
                add(ProviderEvent.ToolCallEnd(call))
            }
            val reason = choice.requiredChatString("finish_reason")
            add(completedEvent(reason, root["usage"] as? JsonObject, message, calls.isNotEmpty()))
        }
        terminal = true
        return ProviderChunk(events).also(ProviderChunk::validateSemantics)
    }

    fun decodeServerSentEvent(eventName: String?, payload: String): ProviderChunk? {
        if (eventName !in setOf(null, "message", "data")) {
            chatProtocolFailure("OpenAI-compatible stream has an unsupported SSE event name")
        }
        if (terminal) chatProtocolFailure("OpenAI-compatible stream emitted data after completion")
        if (payload == "[DONE]") {
            val reason = finishReason
                ?: chatProtocolFailure("OpenAI-compatible stream ended before a finish reason")
            terminal = true
            return ProviderChunk(
                listOf(completedEvent(reason, usage = usage, hasToolCalls = toolCalls.isNotEmpty())),
            ).also(ProviderChunk::validateSemantics)
        }

        val root = parseChatObject(payload, "OpenAI-compatible streaming chunk")
        root.throwIfError()
        recordIdentity(root)
        (root["usage"] as? JsonObject)?.let { usage = it.toChatUsage() }
        val choices = root["choices"] as? JsonArray
            ?: chatProtocolFailure("OpenAI-compatible streaming chunk is missing choices")
        if (choices.isEmpty()) return null
        if (choices.size != 1) chatProtocolFailure("OpenAI-compatible streaming requires exactly one choice")
        val choice = choices.single() as? JsonObject
            ?: chatProtocolFailure("OpenAI-compatible streaming choice must be an object")
        if ((choice["index"] as? JsonPrimitive)?.intOrNull != 0) {
            chatProtocolFailure("OpenAI-compatible streaming choice index must be zero")
        }
        if (finishReason != null) {
            chatProtocolFailure("OpenAI-compatible stream emitted a choice after its finish reason")
        }

        val events = buildList {
            val delta = choice["delta"] as? JsonObject
                ?: chatProtocolFailure("OpenAI-compatible streaming choice is missing delta")
            delta.optionalChatString("role")?.let { role ->
                if (role != "assistant") chatProtocolFailure("OpenAI-compatible streaming role must be assistant")
            }
            val reasoningDetailElement = delta["reasoning_details"]
            if (reasoningDetailElement != null && reasoningDetailElement != JsonNull) {
                addAll(decodeReasoningDetailDeltas(reasoningDetailElement))
            } else if (reasoningMode != ChatReasoningMode.DETAILS) {
                delta.legacyReasoningValue()?.takeIf(String::isNotEmpty)?.let { value ->
                    if (reasoningMode == ChatReasoningMode.NONE) reasoningMode = ChatReasoningMode.LEGACY
                    if (!reasoningStarted) {
                        reasoningStarted = true
                        add(ProviderEvent.ReasoningStart(kind = ReasoningContentKind.TEXT))
                    }
                    reasoning.append(value)
                    add(ProviderEvent.ReasoningDelta(value))
                }
            }
            delta.optionalChatString("content")?.takeIf(String::isNotEmpty)?.let { value ->
                if (!textStarted) {
                    textStarted = true
                    add(ProviderEvent.TextStart())
                }
                text.append(value)
                add(ProviderEvent.TextDelta(value))
            }
            decodeToolCallDeltas(delta["tool_calls"]).forEach(::add)

            choice.optionalChatString("finish_reason")?.let { reason ->
                finishReason = reason
                if (reasoningMode == ChatReasoningMode.DETAILS) {
                    addAll(finalizeActiveReasoningDetail())
                } else if (reasoningStarted) {
                    add(ProviderEvent.ReasoningEnd(text = reasoning.toString()))
                }
                if (textStarted) add(ProviderEvent.TextEnd(text = text.toString()))
                toolCalls.entries.sortedBy(Map.Entry<Int, ActiveChatToolCall>::key).forEach { (_, active) ->
                    add(ProviderEvent.ToolCallEnd(active.finalized()))
                }
            }
        }
        return events.takeIf(List<ProviderEvent>::isNotEmpty)
            ?.let { ProviderChunk(it).also(ProviderChunk::validateSemantics) }
    }

    fun finish() {
        if (!terminal) chatProtocolFailure("OpenAI-compatible stream ended without [DONE]")
    }

    private fun recordIdentity(root: JsonObject) {
        val id = root.requiredChatString("id")
        if (responseId == null) responseId = id else if (responseId != id) {
            chatProtocolFailure("OpenAI-compatible response ID changed")
        }
        root.optionalChatString("model")?.let { model ->
            if (responseModel == null) responseModel = model else if (responseModel != model) {
                chatProtocolFailure("OpenAI-compatible response model changed")
            }
        }
    }

    private fun decodeCompleteReasoning(message: JsonObject): List<ProviderEvent> {
        val details = message.optionalChatArray("reasoning_details")
        if (details != null) {
            reasoningMode = ChatReasoningMode.DETAILS
            return details.flatMapIndexed { index, element ->
                val detail = element as? JsonObject
                    ?: chatProtocolFailure("OpenAI-compatible reasoning detail must be an object")
                completeReasoningDetailEvents(detail, index)
            }
        }
        val value = message.legacyReasoningValue()?.takeIf(String::isNotEmpty) ?: return emptyList()
        reasoningMode = ChatReasoningMode.LEGACY
        return listOf(
            ProviderEvent.ReasoningStart(kind = ReasoningContentKind.TEXT),
            ProviderEvent.ReasoningDelta(value),
            ProviderEvent.ReasoningEnd(text = value),
        )
    }

    private fun decodeReasoningDetailDeltas(element: JsonElement): List<ProviderEvent> {
        if (reasoningMode == ChatReasoningMode.LEGACY) {
            chatProtocolFailure("OpenAI-compatible stream changed reasoning representation")
        }
        val details = element as? JsonArray
            ?: chatProtocolFailure("OpenAI-compatible reasoning_details must be an array")
        reasoningMode = ChatReasoningMode.DETAILS
        return buildList {
            details.forEach { item ->
                val detail = item as? JsonObject
                    ?: chatProtocolFailure("OpenAI-compatible reasoning detail must be an object")
                val explicitIndex = (detail["index"] as? JsonPrimitive)?.intOrNull
                val current = activeReasoningDetail
                val index = explicitIndex ?: current
                    ?.takeIf { it.matches(detail) }
                    ?.index ?: reasoningDetails.size
                if (index < 0) chatProtocolFailure("OpenAI-compatible reasoning detail index must not be negative")

                if (current == null || current.index != index) {
                    addAll(finalizeActiveReasoningDetail())
                    if (index != reasoningDetails.size || reasoningDetails.containsKey(index)) {
                        chatProtocolFailure("OpenAI-compatible reasoning detail index is out of order")
                    }
                    val created = ActiveChatReasoningDetail.from(index, detail)
                    reasoningDetails[index] = created
                    activeReasoningDetail = created
                    addAll(created.startEvents())
                }
                val active = activeReasoningDetail
                    ?: chatProtocolFailure("OpenAI-compatible reasoning detail is not active")
                if (!active.matches(detail)) {
                    chatProtocolFailure("OpenAI-compatible reasoning detail identity changed")
                }
                addAll(active.append(detail))
            }
        }
    }

    private fun finalizeActiveReasoningDetail(): List<ProviderEvent> {
        val active = activeReasoningDetail ?: return emptyList()
        activeReasoningDetail = null
        return active.endEvents()
    }

    private fun completeReasoningDetailEvents(detail: JsonObject, fallbackIndex: Int): List<ProviderEvent> {
        val index = (detail["index"] as? JsonPrimitive)?.intOrNull ?: fallbackIndex
        if (index != fallbackIndex) {
            chatProtocolFailure("OpenAI-compatible reasoning detail index is out of order")
        }
        val active = ActiveChatReasoningDetail.from(index, detail)
        return active.startEvents() + active.append(detail) + active.endEvents()
    }

    private fun decodeToolCallDeltas(element: JsonElement?): List<ProviderEvent> {
        if (element == null || element == JsonNull) return emptyList()
        val calls = element as? JsonArray
            ?: chatProtocolFailure("OpenAI-compatible tool-call delta must be an array")
        return buildList {
            calls.forEach { item ->
                val delta = item as? JsonObject
                    ?: chatProtocolFailure("OpenAI-compatible tool-call delta must be an object")
                val index = (delta["index"] as? JsonPrimitive)?.intOrNull?.takeIf { it >= 0 }
                    ?: chatProtocolFailure("OpenAI-compatible tool-call delta is missing an index")
                val function = delta["function"] as? JsonObject
                val active = toolCalls[index] ?: run {
                    val id = delta.requiredChatString("id")
                    val type = delta.optionalChatString("type") ?: "function"
                    if (type != "function") chatProtocolFailure("Unsupported OpenAI-compatible tool-call type $type")
                    val name = function?.requiredChatString("name")
                        ?: chatProtocolFailure("OpenAI-compatible tool-call delta is missing a function name")
                    ActiveChatToolCall(id, name, delta).also { created ->
                        toolCalls[index] = created
                        add(ProviderEvent.ToolCallStart(created.partial()))
                    }
                }
                delta.optionalChatString("id")?.let { if (it != active.id) chatProtocolFailure("OpenAI-compatible tool-call ID changed") }
                function?.optionalChatString("name")?.let { if (it != active.name) chatProtocolFailure("OpenAI-compatible tool name changed") }
                function?.optionalChatString("arguments")?.let { arguments ->
                    active.arguments.append(arguments)
                    if (arguments.isNotEmpty()) add(ProviderEvent.ToolCallDelta(active.id, arguments))
                }
            }
        }
    }

    private fun decodeCompleteToolCalls(element: JsonElement?): List<ToolCallPart> {
        if (element == null || element == JsonNull) return emptyList()
        val calls = element as? JsonArray
            ?: chatProtocolFailure("OpenAI-compatible tool_calls must be an array")
        return calls.map { item ->
            val call = item as? JsonObject
                ?: chatProtocolFailure("OpenAI-compatible tool call must be an object")
            val id = call.requiredChatString("id")
            val type = call.optionalChatString("type") ?: "function"
            if (type != "function") chatProtocolFailure("Unsupported OpenAI-compatible tool-call type $type")
            val function = call.requiredChatObject("function")
            ToolCallPart(
                toolCallId = id,
                toolName = function.requiredChatString("name"),
                arguments = parseChatArguments(function.requiredChatString("arguments", allowEmpty = true)),
                partial = false,
                providerCallId = id,
                providerMetadata = call,
            )
        }
    }

    private fun completedEvent(
        reason: String,
        usageObject: JsonObject?,
        message: JsonObject? = null,
        hasToolCalls: Boolean,
    ): ProviderEvent.Completed = completedEvent(
        reason = reason,
        usage = usageObject?.toChatUsage(),
        message = message,
        hasToolCalls = hasToolCalls,
    )

    private fun completedEvent(
        reason: String,
        usage: ProviderUsage?,
        message: JsonObject? = null,
        hasToolCalls: Boolean,
    ): ProviderEvent.Completed {
        val normalized = when {
            hasToolCalls || reason == "tool_calls" || reason == "function_call" -> StopReason.TOOL_CALLS
            reason == "length" || reason == "max_tokens" -> StopReason.MAX_TOKENS
            reason in setOf("error", "content_filter") -> StopReason.ERROR
            else -> StopReason.COMPLETED
        }
        return ProviderEvent.Completed(
            finishReason = reason,
            stopReason = normalized,
            usage = usage,
            providerMetadata = buildJsonObject {
                put("provider", providerKey)
                put("model", requestedModel)
                responseId?.let { put("openai.chat.id", it) }
                responseModel?.let { put("openai.chat.model", it) }
                message?.let { put(OPENAI_CHAT_MESSAGE_METADATA, it) }
                val authoritativeReasoning = message?.optionalChatArray("reasoning_details")
                    ?: reasoningDetails.values.toList()
                        .takeIf(List<ActiveChatReasoningDetail>::isNotEmpty)
                        ?.let { values -> JsonArray(values.map(ActiveChatReasoningDetail::authoritative)) }
                authoritativeReasoning?.let { put(OPENAI_CHAT_REASONING_DETAILS_METADATA, it) }
            },
        )
    }

    private fun ensurePristine() {
        if (terminal || responseId != null || finishReason != null || toolCalls.isNotEmpty()) {
            chatProtocolFailure("OpenAI-compatible codec instance can decode only one response")
        }
    }
}

private data class ActiveChatToolCall(
    val id: String,
    val name: String,
    val providerMetadata: JsonObject,
    val arguments: StringBuilder = StringBuilder(),
) {
    fun partial(): ToolCallPart = ToolCallPart(
        toolCallId = id,
        toolName = name,
        arguments = JsonObject(emptyMap()),
        partial = true,
        providerCallId = id,
        providerMetadata = providerMetadata,
    )

    fun finalized(): ToolCallPart = ToolCallPart(
        toolCallId = id,
        toolName = name,
        arguments = parseChatArguments(arguments.toString()),
        partial = false,
        providerCallId = id,
        providerMetadata = providerMetadata,
    )
}

private data class ActiveChatReasoningDetail(
    val index: Int,
    val type: String,
    val common: MutableMap<String, JsonElement>,
    val visible: StringBuilder = StringBuilder(),
    val opaque: StringBuilder = StringBuilder(),
    var signature: String? = null,
    var sawVisibleField: Boolean = false,
    var appended: Boolean = false,
) {
    fun matches(detail: JsonObject): Boolean {
        if (detail.requiredChatString("type") != type) return false
        val explicitIndex = (detail["index"] as? JsonPrimitive)?.intOrNull
        if (explicitIndex != null && explicitIndex != index) return false
        return IDENTITY_FIELDS.all { field ->
            val previous = common[field]
            val next = detail[field]
            previous == null || next == null || previous == next
        }
    }

    fun startEvents(): List<ProviderEvent> = when (type) {
        "reasoning.summary" -> listOf(
            ProviderEvent.ReasoningStart(kind = ReasoningContentKind.SUMMARY),
        )
        "reasoning.text" -> listOf(
            ProviderEvent.ReasoningStart(
                signature = signature,
                kind = ReasoningContentKind.TEXT,
            ),
        )
        "reasoning.encrypted" -> listOf(ProviderEvent.ReasoningStart(redacted = true))
        "reasoning.server_tool_call" -> emptyList()
        else -> chatProtocolFailure("Unsupported OpenAI-compatible reasoning detail type $type")
    }

    fun append(detail: JsonObject): List<ProviderEvent> {
        mergeCommon(detail)
        val wasAppended = appended
        appended = true
        return when (type) {
            "reasoning.summary" -> {
                val delta = detail.requiredChatString("summary", allowEmpty = true)
                sawVisibleField = true
                visible.append(delta)
                listOf(ProviderEvent.ReasoningDelta(delta))
            }
            "reasoning.text" -> {
                val textFieldPresent = "text" in detail && detail["text"] != JsonNull
                val delta = detail.optionalChatString("text").orEmpty()
                if (textFieldPresent) {
                    sawVisibleField = true
                    visible.append(delta)
                }
                val nextSignature = detail.optionalChatString("signature")
                if (signature != null && nextSignature != null && signature != nextSignature) {
                    chatProtocolFailure("OpenAI-compatible reasoning signature changed")
                }
                if (nextSignature != null) signature = nextSignature
                if (textFieldPresent || nextSignature != null) {
                    listOf(ProviderEvent.ReasoningDelta(delta, nextSignature))
                } else {
                    emptyList()
                }
            }
            "reasoning.encrypted" -> {
                opaque.append(detail.requiredChatString("data", allowEmpty = true))
                emptyList()
            }
            "reasoning.server_tool_call" -> {
                if (wasAppended) chatProtocolFailure("OpenAI-compatible server Tool reasoning detail was repeated")
                validateServerToolCall(JsonObject(common))
                emptyList()
            }
            else -> chatProtocolFailure("Unsupported OpenAI-compatible reasoning detail type $type")
        }
    }

    fun endEvents(): List<ProviderEvent> = when (type) {
        "reasoning.summary" -> listOf(ProviderEvent.ReasoningEnd(text = visible.toString()))
        "reasoning.text" -> listOf(
            ProviderEvent.ReasoningEnd(
                text = visible.toString().takeIf { sawVisibleField },
                signature = signature,
            ),
        )
        "reasoning.encrypted" -> listOf(
            ProviderEvent.ReasoningEnd(
                signature = opaque.toString(),
                redacted = true,
            ),
        )
        "reasoning.server_tool_call" -> emptyList()
        else -> chatProtocolFailure("Unsupported OpenAI-compatible reasoning detail type $type")
    }

    fun authoritative(): JsonObject = buildJsonObject {
        common.forEach { (key, value) -> put(key, value) }
        when (type) {
            "reasoning.summary" -> put("summary", visible.toString())
            "reasoning.text" -> {
                if (sawVisibleField) put("text", visible.toString())
                signature?.let { put("signature", it) }
            }
            "reasoning.encrypted" -> put("data", opaque.toString())
            "reasoning.server_tool_call" -> Unit
        }
    }

    private fun mergeCommon(detail: JsonObject) {
        val payloadFields = when (type) {
            "reasoning.summary" -> setOf("summary")
            "reasoning.text" -> setOf("text", "signature")
            "reasoning.encrypted" -> setOf("data")
            else -> emptySet()
        }
        detail.forEach { (key, value) ->
            if (key !in payloadFields) {
                val previous = common[key]
                if (previous != null && previous != value) {
                    chatProtocolFailure("OpenAI-compatible reasoning detail metadata changed")
                }
                common[key] = value
            }
        }
    }

    companion object {
        fun from(index: Int, detail: JsonObject): ActiveChatReasoningDetail {
            val type = detail.requiredChatString("type")
            if (type !in REASONING_DETAIL_TYPES) {
                chatProtocolFailure("Unsupported OpenAI-compatible reasoning detail type $type")
            }
            return ActiveChatReasoningDetail(
                index = index,
                type = type,
                common = detail
                    .filterKeys { key -> key !in REASONING_DETAIL_PAYLOAD_FIELDS }
                    .toMutableMap(),
            )
        }
    }
}

private enum class ChatReasoningMode {
    NONE,
    LEGACY,
    DETAILS,
}

private fun JsonObject.singleChoice(): JsonObject {
    val choices = this["choices"] as? JsonArray
        ?: chatProtocolFailure("OpenAI-compatible response is missing choices")
    if (choices.size != 1) chatProtocolFailure("OpenAI-compatible response requires exactly one choice")
    val choice = choices.single() as? JsonObject
        ?: chatProtocolFailure("OpenAI-compatible choice must be an object")
    if ((choice["index"] as? JsonPrimitive)?.intOrNull != 0) {
        chatProtocolFailure("OpenAI-compatible choice index must be zero")
    }
    return choice
}

private fun JsonObject.throwIfError() {
    val error = this["error"] as? JsonObject ?: return
    val metadata = error["metadata"] as? JsonObject
    throwOpenAiInBandFailure(
        label = "OpenAI Chat Completions",
        code = error.optionalChatString("code") ?: error.optionalChatString("type"),
        errorType = metadata?.optionalChatString("error_type"),
        providerMessage = error.optionalChatString("message"),
    )
}

private fun JsonObject.toChatUsage(): ProviderUsage = ProviderUsage(
    inputTokens = (this["prompt_tokens"] as? JsonPrimitive)?.intOrNull,
    outputTokens = (this["completion_tokens"] as? JsonPrimitive)?.intOrNull,
    reasoningTokens = ((this["completion_tokens_details"] as? JsonObject)?.get("reasoning_tokens") as? JsonPrimitive)?.intOrNull,
)

private fun parseChatArguments(value: String): JsonObject {
    if (value.isBlank()) return JsonObject(emptyMap())
    val parsed = try {
        Json.parseToJsonElement(value)
    } catch (failure: Throwable) {
        throw ProviderProtocolException("Malformed OpenAI-compatible function-call arguments", failure)
    }
    return parsed as? JsonObject
        ?: chatProtocolFailure("OpenAI-compatible function-call arguments must decode to an object")
}

private fun parseChatObject(payload: String, label: String): JsonObject = try {
    Json.parseToJsonElement(payload) as? JsonObject
        ?: chatProtocolFailure("$label must be a JSON object")
} catch (failure: ProviderProtocolException) {
    throw failure
} catch (failure: Throwable) {
    throw ProviderProtocolException("Malformed $label", failure)
}

private fun JsonObject.requiredChatObject(key: String): JsonObject =
    this[key] as? JsonObject ?: chatProtocolFailure("OpenAI-compatible payload is missing object field $key")

private fun JsonObject.optionalChatArray(key: String): JsonArray? = when (val value = this[key]) {
    null, JsonNull -> null
    is JsonArray -> value
    else -> chatProtocolFailure("OpenAI-compatible payload field $key must be an array")
}

private fun JsonObject.requiredChatString(key: String, allowEmpty: Boolean = false): String {
    val value = optionalChatString(key)
        ?: chatProtocolFailure("OpenAI-compatible payload is missing string field $key")
    if (!allowEmpty && value.isBlank()) chatProtocolFailure("OpenAI-compatible payload field $key must not be blank")
    return value
}

private fun JsonObject.optionalChatString(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull

private fun JsonObject.legacyReasoningValue(): String? {
    val values = listOfNotNull(
        optionalChatString("reasoning"),
        optionalChatString("reasoning_content"),
    ).distinct()
    if (values.size > 1) {
        chatProtocolFailure("OpenAI-compatible reasoning aliases disagree")
    }
    return values.singleOrNull()
}

private fun validateServerToolCall(detail: JsonObject) {
    detail.requiredChatString("tool_name")
    detail.requiredChatString("arguments", allowEmpty = true)
    detail.requiredChatString("result", allowEmpty = true)
}

private fun chatProtocolFailure(message: String): Nothing = throw ProviderProtocolException(message)

private val REASONING_DETAIL_TYPES = setOf(
    "reasoning.summary",
    "reasoning.text",
    "reasoning.encrypted",
    "reasoning.server_tool_call",
)

private val REASONING_DETAIL_PAYLOAD_FIELDS = setOf(
    "summary",
    "text",
    "signature",
    "data",
)

private val IDENTITY_FIELDS = setOf("id", "format")
