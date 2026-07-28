package saien.magrathea.provider.anthropic

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import saien.magrathea.core.StopReason
import saien.magrathea.core.ToolCallPart
import saien.magrathea.provider.api.ProviderAuthException
import saien.magrathea.provider.api.ProviderChunk
import saien.magrathea.provider.api.ProviderClientException
import saien.magrathea.provider.api.ProviderContextLimitException
import saien.magrathea.provider.api.ProviderEvent
import saien.magrathea.provider.api.ProviderProtocolException
import saien.magrathea.provider.api.ProviderRateLimitException
import saien.magrathea.provider.api.ProviderServerException
import saien.magrathea.provider.api.ProviderUsage
import saien.magrathea.provider.api.isProviderContextLimitError
import saien.magrathea.provider.api.validateSemantics

internal class AnthropicMessagesCodec(
    private val providerKey: String,
    private val model: String,
    private val json: Json = Json,
) {
    private var messageId: String? = null
    private var started = false
    private var terminal = false
    private var doneSentinelSeen = false
    private var activeBlock: ActiveBlock? = null
    private val completedBlocks = mutableListOf<JsonObject>()
    private var inputTokens: Int? = null
    private var outputTokens: Int? = null
    private var reasoningTokens: Int? = null
    private var stopReason: String? = null

    fun decodeNonStreaming(payload: String): ProviderChunk {
        ensurePristine()
        val message = parseObject(payload, "Anthropic response")
        messageId = message.requiredString("id")
        if (message.requiredString("role") != "assistant") protocolFailure("Anthropic response role must be assistant")
        started = true
        val content = message.requiredArray("content")
        val events = buildList {
            content.forEachIndexed { index, element ->
                val block = element as? JsonObject
                    ?: protocolFailure("Anthropic content block $index must be an object")
                addAll(decodeCompleteBlock(block))
                completedBlocks += block
            }
            val reason = message.requiredString("stop_reason")
            add(completedEvent(reason, content, (message["usage"] as? JsonObject)?.toUsage()))
        }
        terminal = true
        return ProviderChunk(events = events).also(ProviderChunk::validateSemantics)
    }

    fun decodeServerSentEvent(eventName: String?, payload: String): ProviderChunk? {
        if (payload == "[DONE]") {
            if (eventName != null && eventName != "data") {
                protocolFailure("Anthropic [DONE] has an invalid SSE event name")
            }
            if (!terminal) protocolFailure("Anthropic emitted [DONE] before message_stop")
            if (doneSentinelSeen) protocolFailure("Anthropic emitted duplicate [DONE]")
            doneSentinelSeen = true
            return null
        }
        if (doneSentinelSeen) protocolFailure("Anthropic emitted data after [DONE]")
        if (terminal) protocolFailure("Anthropic emitted data after message_stop")
        val root = parseObject(payload, "Anthropic streaming event")
        val type = root.requiredString("type")
        if (eventName == null || eventName != type) {
            protocolFailure("Anthropic SSE event name does not match payload type")
        }
        val events = when (type) {
            "message_start" -> decodeMessageStart(root)
            "content_block_start" -> decodeBlockStart(root)
            "content_block_delta" -> decodeBlockDelta(root)
            "content_block_stop" -> decodeBlockStop(root)
            "message_delta" -> decodeMessageDelta(root)
            "message_stop" -> decodeMessageStop()
            "ping" -> emptyList()
            "error" -> decodeError(root)
            else -> emptyList()
        }
        return events.takeIf(List<ProviderEvent>::isNotEmpty)
            ?.let { ProviderChunk(events = it).also(ProviderChunk::validateSemantics) }
    }

    fun finish() {
        if (!terminal) protocolFailure("Anthropic stream ended before message_stop")
        if (activeBlock != null) protocolFailure("Anthropic stream ended with an active content block")
    }

    private fun decodeMessageStart(root: JsonObject): List<ProviderEvent> {
        if (started) protocolFailure("Anthropic emitted duplicate message_start")
        val message = root.requiredObject("message")
        messageId = message.requiredString("id")
        if (message.requiredString("role") != "assistant") protocolFailure("Anthropic response role must be assistant")
        if (message.requiredArray("content").isNotEmpty()) protocolFailure("Anthropic message_start content must be empty")
        val usage = (message["usage"] as? JsonObject)?.toUsage()
        inputTokens = usage?.inputTokens
        outputTokens = usage?.outputTokens
        reasoningTokens = usage?.reasoningTokens
        started = true
        return emptyList()
    }

    private fun decodeBlockStart(root: JsonObject): List<ProviderEvent> {
        requireStarted()
        if (activeBlock != null) protocolFailure("Anthropic started a content block before stopping the previous block")
        val index = root.requiredIndex()
        if (index != completedBlocks.size) protocolFailure("Anthropic content block index is out of order")
        val block = root.requiredObject("content_block")
        val type = block.requiredString("type")
        val active = ActiveBlock(index, type, block)
        activeBlock = active
        return when (type) {
            "text" -> {
                active.text.append(block.requiredString("text", allowEmpty = true))
                listOf(ProviderEvent.TextStart())
            }
            "thinking" -> {
                active.text.append(block.requiredString("thinking", allowEmpty = true))
                active.signature = block.optionalString("signature")?.takeIf(String::isNotBlank)
                listOf(ProviderEvent.ReasoningStart(signature = active.signature))
            }
            "redacted_thinking" -> {
                active.signature = block.requiredString("data")
                listOf(ProviderEvent.ReasoningStart(signature = active.signature, redacted = true))
            }
            "tool_use" -> {
                active.toolId = block.requiredString("id")
                active.toolName = block.requiredString("name")
                active.initialInput = block["input"] as? JsonObject
                    ?: protocolFailure("Anthropic tool input must be an object")
                listOf(ProviderEvent.ToolCallStart(active.partialToolCall()))
            }
            else -> protocolFailure("Unsupported Anthropic content block type $type")
        }
    }

    private fun decodeBlockDelta(root: JsonObject): List<ProviderEvent> {
        val active = requireActive(root)
        val delta = root.requiredObject("delta")
        return when (delta.requiredString("type")) {
            "text_delta" -> {
                if (active.type != "text") protocolFailure("Anthropic text delta targets a non-text block")
                val text = delta.requiredString("text", allowEmpty = true)
                active.text.append(text)
                listOf(ProviderEvent.TextDelta(text))
            }
            "thinking_delta" -> {
                if (active.type != "thinking") protocolFailure("Anthropic thinking delta targets a non-thinking block")
                val text = delta.requiredString("thinking", allowEmpty = true)
                active.text.append(text)
                listOf(ProviderEvent.ReasoningDelta(text, active.signature))
            }
            "signature_delta" -> {
                if (active.type != "thinking") protocolFailure("Anthropic signature delta targets a non-thinking block")
                val signature = delta.requiredString("signature")
                if (active.signature != null && active.signature != signature) {
                    protocolFailure("Anthropic thinking signature changed")
                }
                active.signature = signature
                listOf(ProviderEvent.ReasoningDelta("", signature))
            }
            "input_json_delta" -> {
                if (active.type != "tool_use") protocolFailure("Anthropic input JSON delta targets a non-tool block")
                val fragment = delta.requiredString("partial_json", allowEmpty = true)
                active.inputJson.append(fragment)
                listOf(ProviderEvent.ToolCallDelta(active.toolId!!, fragment))
            }
            else -> protocolFailure("Unsupported Anthropic content block delta type")
        }
    }

    private fun decodeBlockStop(root: JsonObject): List<ProviderEvent> {
        val active = requireActive(root)
        val (completed, events) = when (active.type) {
            "text" -> buildJsonObject {
                put("type", "text")
                put("text", active.text.toString())
            } to listOf(ProviderEvent.TextEnd(text = active.text.toString()))
            "thinking" -> {
                val signature = active.signature ?: protocolFailure("Anthropic thinking block ended without a signature")
                buildJsonObject {
                    put("type", "thinking")
                    put("thinking", active.text.toString())
                    put("signature", signature)
                } to listOf(
                    ProviderEvent.ReasoningEnd(text = active.text.toString(), signature = signature),
                )
            }
            "redacted_thinking" -> active.start to listOf(
                ProviderEvent.ReasoningEnd(signature = active.signature, redacted = true),
            )
            "tool_use" -> {
                val input = active.finalInput(json)
                val block = buildJsonObject {
                    put("type", "tool_use")
                    put("id", active.toolId!!)
                    put("name", active.toolName!!)
                    put("input", input)
                }
                block to listOf(ProviderEvent.ToolCallEnd(active.finalToolCall(input, block)))
            }
            else -> protocolFailure("Unsupported Anthropic content block type ${active.type}")
        }
        completedBlocks += completed
        activeBlock = null
        return events
    }

    private fun decodeMessageDelta(root: JsonObject): List<ProviderEvent> {
        requireStarted()
        if (activeBlock != null) protocolFailure("Anthropic message_delta arrived before content_block_stop")
        val delta = root.requiredObject("delta")
        val reason = delta.requiredString("stop_reason")
        if (stopReason != null && stopReason != reason) protocolFailure("Anthropic stop reason changed")
        stopReason = reason
        (root["usage"] as? JsonObject)?.toUsage()?.let { usage ->
            usage.inputTokens?.let { inputTokens = it }
            usage.outputTokens?.let { outputTokens = it }
            usage.reasoningTokens?.let { reasoningTokens = it }
        }
        return emptyList()
    }

    private fun decodeMessageStop(): List<ProviderEvent> {
        requireStarted()
        if (activeBlock != null) protocolFailure("Anthropic message_stop arrived with an active content block")
        val reason = stopReason ?: protocolFailure("Anthropic message_stop arrived before a stop reason")
        terminal = true
        return listOf(
            completedEvent(
                reason = reason,
                content = JsonArray(completedBlocks),
                usage = ProviderUsage(inputTokens, outputTokens, reasoningTokens),
            ),
        )
    }

    private fun decodeError(root: JsonObject): List<ProviderEvent> {
        terminal = true
        val error = root.requiredObject("error")
        val type = error.requiredString("type")
        val providerMessage = error.requiredString("message", allowEmpty = true)
        if (isProviderContextLimitError("$type $providerMessage")) {
            throw ProviderContextLimitException()
        }
        throw when (type) {
            "authentication_error", "permission_error" -> ProviderAuthException(
                "Anthropic stream failed with code $type",
                statusCode = if (type == "permission_error") 403 else 401,
            )
            "rate_limit_error" -> ProviderRateLimitException("Anthropic stream failed with code $type")
            "invalid_request_error" -> ProviderClientException(
                "Anthropic stream failed with code $type",
                statusCode = 400,
            )
            "overloaded_error" -> ProviderServerException(
                "Anthropic stream failed with code $type",
                statusCode = 529,
            )
            else -> ProviderServerException("Anthropic stream failed with code $type", statusCode = 500)
        }
    }

    private fun decodeCompleteBlock(block: JsonObject): List<ProviderEvent> = when (val type = block.requiredString("type")) {
        "text" -> {
            val text = block.requiredString("text", allowEmpty = true)
            listOf(ProviderEvent.TextStart(), ProviderEvent.TextDelta(text), ProviderEvent.TextEnd(text))
        }
        "thinking" -> {
            val text = block.requiredString("thinking", allowEmpty = true)
            val signature = block.requiredString("signature")
            listOf(
                ProviderEvent.ReasoningStart(signature),
                ProviderEvent.ReasoningDelta(text, signature),
                ProviderEvent.ReasoningEnd(text, signature),
            )
        }
        "redacted_thinking" -> {
            val data = block.requiredString("data")
            listOf(
                ProviderEvent.ReasoningStart(data, redacted = true),
                ProviderEvent.ReasoningEnd(signature = data, redacted = true),
            )
        }
        "tool_use" -> {
            val id = block.requiredString("id")
            val name = block.requiredString("name")
            val input = block["input"] as? JsonObject ?: protocolFailure("Anthropic tool input must be an object")
            val call = ToolCallPart(
                toolCallId = id,
                toolName = name,
                arguments = input,
                partial = false,
                providerCallId = id,
                providerMetadata = block,
            )
            listOf(
                ProviderEvent.ToolCallStart(call.copy(arguments = JsonObject(emptyMap()), partial = true)),
                ProviderEvent.ToolCallEnd(call),
            )
        }
        else -> protocolFailure("Unsupported Anthropic content block type $type")
    }

    private fun completedEvent(
        reason: String,
        content: JsonArray,
        usage: ProviderUsage?,
    ): ProviderEvent.Completed {
        val hasToolUse = content.any { (it as? JsonObject)?.optionalString("type") == "tool_use" }
        if (hasToolUse != (reason == "tool_use")) {
            protocolFailure("Anthropic stop reason does not match its tool-use blocks")
        }
        return ProviderEvent.Completed(
            finishReason = reason,
            stopReason = when (reason) {
                "tool_use" -> StopReason.TOOL_CALLS
                "max_tokens" -> StopReason.MAX_TOKENS
                "end_turn", "stop_sequence", "refusal", "pause_turn" -> StopReason.COMPLETED
                else -> protocolFailure("Unsupported Anthropic stop reason $reason")
            },
            usage = usage,
            providerMetadata = buildJsonObject {
                put("provider", providerKey)
                put("model", model)
                put(ANTHROPIC_MESSAGE_ID_METADATA, messageId!!)
                put(ANTHROPIC_STOP_REASON_METADATA, reason)
                put(ANTHROPIC_CONTENT_METADATA, content)
            },
        )
    }

    private fun requireActive(root: JsonObject): ActiveBlock {
        requireStarted()
        val active = activeBlock ?: protocolFailure("Anthropic content event arrived without an active block")
        if (root.requiredIndex() != active.index) protocolFailure("Anthropic content block index changed")
        return active
    }

    private fun ensurePristine() {
        if (started || terminal || activeBlock != null || completedBlocks.isNotEmpty()) {
            protocolFailure("Anthropic codec instance can decode only one message")
        }
    }

    private fun requireStarted() {
        if (!started) protocolFailure("Anthropic event arrived before message_start")
    }
}

private data class ActiveBlock(
    val index: Int,
    val type: String,
    val start: JsonObject,
    val text: StringBuilder = StringBuilder(),
    val inputJson: StringBuilder = StringBuilder(),
    var signature: String? = null,
    var toolId: String? = null,
    var toolName: String? = null,
    var initialInput: JsonObject = JsonObject(emptyMap()),
) {
    fun partialToolCall(): ToolCallPart = ToolCallPart(
        toolCallId = toolId!!,
        toolName = toolName!!,
        arguments = initialInput,
        partial = true,
        providerCallId = toolId,
        providerMetadata = start,
    )

    fun finalInput(json: Json): JsonObject {
        if (inputJson.isEmpty()) return initialInput
        if (initialInput.isNotEmpty()) protocolFailure("Anthropic tool block mixed initial input and JSON deltas")
        val parsed = try {
            json.parseToJsonElement(inputJson.toString())
        } catch (failure: Throwable) {
            throw ProviderProtocolException("Malformed Anthropic tool input", failure)
        }
        return parsed as? JsonObject ?: protocolFailure("Anthropic tool input must decode to an object")
    }

    fun finalToolCall(input: JsonObject, metadata: JsonObject): ToolCallPart = ToolCallPart(
        toolCallId = toolId!!,
        toolName = toolName!!,
        arguments = input,
        partial = false,
        providerCallId = toolId,
        providerMetadata = metadata,
    )
}

private fun JsonObject.toUsage(): ProviderUsage = ProviderUsage(
    inputTokens = (this["input_tokens"] as? JsonPrimitive)?.intOrNull,
    outputTokens = (this["output_tokens"] as? JsonPrimitive)?.intOrNull,
    reasoningTokens = ((this["output_tokens_details"] as? JsonObject)?.get("thinking_tokens") as? JsonPrimitive)?.intOrNull,
)

private fun parseObject(payload: String, label: String): JsonObject = try {
    Json.parseToJsonElement(payload) as? JsonObject ?: protocolFailure("$label must be a JSON object")
} catch (failure: ProviderProtocolException) {
    throw failure
} catch (failure: Throwable) {
    throw ProviderProtocolException("Malformed $label", failure)
}

private fun JsonObject.requiredObject(key: String): JsonObject =
    this[key] as? JsonObject ?: protocolFailure("Anthropic payload is missing object field $key")

private fun JsonObject.requiredArray(key: String): JsonArray =
    this[key] as? JsonArray ?: protocolFailure("Anthropic payload is missing array field $key")

private fun JsonObject.requiredString(key: String, allowEmpty: Boolean = false): String {
    val value = (this[key] as? JsonPrimitive)?.contentOrNull
        ?: protocolFailure("Anthropic payload is missing string field $key")
    if (!allowEmpty && value.isBlank()) protocolFailure("Anthropic payload field $key must not be blank")
    return value
}

private fun JsonObject.optionalString(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

private fun JsonObject.requiredIndex(): Int =
    (this["index"] as? JsonPrimitive)?.intOrNull?.takeIf { it >= 0 }
        ?: protocolFailure("Anthropic payload is missing a non-negative content block index")

private fun protocolFailure(message: String): Nothing = throw ProviderProtocolException(message)
