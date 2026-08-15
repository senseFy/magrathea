package saien.magrathea.provider.anthropic

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import saien.magrathea.core.AgentMessage
import saien.magrathea.core.AttachmentPart
import saien.magrathea.core.InlineToolImageSource
import saien.magrathea.core.JsonPart
import saien.magrathea.core.MessageRole
import saien.magrathea.core.ReasoningPart
import saien.magrathea.core.RemoteToolImageSource
import saien.magrathea.core.TextPart
import saien.magrathea.core.ToolCallPart
import saien.magrathea.core.ToolImageAttachmentReference
import saien.magrathea.core.ToolImageSource
import saien.magrathea.core.ToolResultImageContent
import saien.magrathea.core.ToolResultPart
import saien.magrathea.core.ToolResultTextContent
import saien.magrathea.core.dataUrlPayload
import saien.magrathea.core.isHttpsUrl
import saien.magrathea.core.normalizedMimeType
import saien.magrathea.provider.api.AnthropicTransportConfig
import saien.magrathea.provider.api.ProviderProtocolException
import saien.magrathea.provider.api.ProviderRequest
import saien.magrathea.provider.api.modelProjection
import saien.magrathea.provider.api.ReferenceProviderInputCapabilities

internal const val ANTHROPIC_CONTENT_METADATA = "anthropic.messages.content"
internal const val ANTHROPIC_MESSAGE_ID_METADATA = "anthropic.messages.id"
internal const val ANTHROPIC_STOP_REASON_METADATA = "anthropic.messages.stop_reason"

internal class AnthropicRequestBuilder(
    private val providerKey: String,
    private val json: Json = Json,
) {
    fun build(request: ProviderRequest): JsonObject = buildJsonObject {
        val config = request.anthropicTransportConfig()
        validateConfig(config, request)
        put("model", request.model.model)
        put("max_tokens", request.maxTokens ?: DEFAULT_MAX_TOKENS)
        put("stream", request.model.supportsStreaming)
        systemPrompt(request.messages)?.let { put("system", it) }
        put("messages", buildMessages(request))
        if (request.tools.isNotEmpty()) put("tools", buildTools(request))
        request.temperature?.let { put("temperature", it) }
        config.thinkingMode?.let { mode ->
            put("thinking", buildJsonObject {
                put("type", mode)
                config.thinkingBudgetTokens?.let { put("budget_tokens", it) }
                config.thinkingDisplay?.let { put("display", it) }
            })
        }
        config.effort?.let { effort ->
            put("output_config", buildJsonObject { put("effort", effort) })
        }
    }

    private fun buildMessages(request: ProviderRequest): JsonArray = buildJsonArray {
        request.messages.forEach { message ->
            when (message.role) {
                MessageRole.SYSTEM -> Unit
                MessageRole.USER -> add(userMessage(message))
                MessageRole.ASSISTANT -> add(assistantMessage(message, request))
                MessageRole.TOOL -> add(toolResultMessage(message, request))
            }
        }
    }

    private fun userMessage(message: AgentMessage): JsonObject = buildJsonObject {
        val content = buildJsonArray {
            message.parts.forEach { part ->
                when (part) {
                    is TextPart -> if (part.text.isNotBlank()) add(textBlock(part.text))
                    is JsonPart -> add(textBlock(json.encodeToString(JsonElement.serializer(), part.value)))
                    is AttachmentPart -> add(part.toContentBlock())
                    else -> throw ProviderProtocolException("Unsupported Anthropic user message part ${part::class.simpleName}")
                }
            }
        }
        if (content.isEmpty()) throw ProviderProtocolException("Anthropic user message must not be empty")
        put("role", "user")
        put("content", content)
    }

    private fun assistantMessage(message: AgentMessage, request: ProviderRequest): JsonObject = buildJsonObject {
        val sourceProvider = message.metadata["provider"]?.jsonPrimitive?.contentOrNull
        val sourceModel = message.metadata["model"]?.jsonPrimitive?.contentOrNull
        val authoritative = message.metadata[ANTHROPIC_CONTENT_METADATA] as? JsonArray
        val content = if (sourceProvider == providerKey && sourceModel == request.model.model) {
            authoritative ?: throw ProviderProtocolException(
                "Same-model Anthropic replay requires authoritative content blocks",
            )
        } else {
            buildJsonArray {
                message.parts.forEach { part ->
                    when (part) {
                        is TextPart -> if (part.text.isNotBlank()) add(textBlock(part.text))
                        is JsonPart -> add(textBlock(json.encodeToString(JsonElement.serializer(), part.value)))
                        // Reasoning cannot be replayed without the same-model authoritative
                        // blocks (signatures included), so interrupted-stream recovery and
                        // cross-model replays drop it instead of failing the request.
                        is ReasoningPart -> Unit
                        is ToolCallPart -> {
                            if (part.partial) throw ProviderProtocolException("Anthropic cannot replay a partial tool call")
                            if (part.arguments !is JsonObject) {
                                throw ProviderProtocolException("Anthropic tool input must be an object")
                            }
                            add(buildJsonObject {
                                put("type", "tool_use")
                                put("id", part.toolCallId)
                                put("name", part.toolName)
                                put("input", part.arguments)
                            })
                        }
                        else -> throw ProviderProtocolException(
                            "Unsupported cross-model Anthropic assistant part ${part::class.simpleName}",
                        )
                    }
                }
            }
        }
        if (content.isEmpty()) throw ProviderProtocolException("Anthropic assistant replay must not be empty")
        put("role", "assistant")
        put("content", content)
    }

    private fun toolResultMessage(message: AgentMessage, request: ProviderRequest): JsonObject = buildJsonObject {
        val results = message.parts.filterIsInstance<ToolResultPart>()
        if (results.isEmpty() || results.size != message.parts.size) {
            throw ProviderProtocolException("Anthropic tool message must contain only tool results")
        }
        put("role", "user")
        put("content", buildJsonArray {
            results.forEach { result ->
                add(buildJsonObject {
                    put("type", "tool_result")
                    put("tool_use_id", result.toolCallId)
                    put("is_error", result.isError)
                    put("content", result.toAnthropicToolContent(request))
                })
            }
        })
    }

    private fun ToolResultPart.toAnthropicToolContent(request: ProviderRequest): JsonArray {
        val projection = modelProjection(
            request.model.inputModalities,
            ReferenceProviderInputCapabilities.anthropicMessages,
        )
        return buildJsonArray {
            projection.canonicalResult?.let { add(textBlock(renderToolResult(it))) }
            projection.content.forEach { block ->
                when (block) {
                    is ToolResultTextContent -> add(textBlock(block.text))
                    is ToolResultImageContent -> add(block.toAnthropicImage())
                }
            }
        }
    }

    private fun ToolResultImageContent.toAnthropicImage(): JsonObject = buildJsonObject {
        put("type", "image")
        put("source", source.toAnthropicImageSource(mimeType))
    }

    private fun ToolImageSource.toAnthropicImageSource(mimeType: String?): JsonObject = buildJsonObject {
        when (this@toAnthropicImageSource) {
            is RemoteToolImageSource -> {
                put("type", "url")
                put("url", uri)
            }
            is InlineToolImageSource -> {
                val mediaType = mimeType
                    ?: throw ProviderProtocolException("Anthropic inline Tool images require a MIME type")
                put("type", "base64")
                put("media_type", mediaType)
                put("data", data)
            }
            is ToolImageAttachmentReference -> throw ProviderProtocolException(
                "Anthropic Tool image attachment references must be resolved before request encoding",
            )
        }
    }

    private fun buildTools(request: ProviderRequest): JsonArray = buildJsonArray {
        request.tools.forEach { tool ->
            add(buildJsonObject {
                put("name", tool.name)
                put("description", tool.description)
                put("input_schema", tool.schema)
            })
        }
    }

    private fun systemPrompt(messages: List<AgentMessage>): String? {
        val lines = messages.filter { it.role == MessageRole.SYSTEM }.flatMap { message ->
            message.parts.map { part ->
                (part as? TextPart)?.text
                    ?: throw ProviderProtocolException("Anthropic system messages only support text")
            }
        }
        return lines.joinToString("\n").takeIf(String::isNotBlank)
    }

    private fun AttachmentPart.toContentBlock(): JsonObject {
        val dataUrl = dataUrlPayload()
        if (uri.startsWith("data:", ignoreCase = true) && dataUrl == null) {
            throw ProviderProtocolException("Anthropic attachments require a valid base64 data URL")
        }
        val declaredMime = normalizedMimeType()
        val dataMime = dataUrl?.mediaType
        if (declaredMime.isBlank() && dataMime == null) {
            throw ProviderProtocolException("Anthropic attachments require a MIME type")
        }
        if (declaredMime.isNotBlank() && dataMime != null && declaredMime != dataMime) {
            throw ProviderProtocolException("Anthropic attachment MIME type does not match its data URL")
        }
        val mime = declaredMime.ifBlank { dataMime.orEmpty() }
        if (!ReferenceProviderInputCapabilities.anthropicMessages.supportsAttachment(mime)) {
            throw ProviderProtocolException("Anthropic attachment MIME type is not supported")
        }
        val blockType = when {
            mime.startsWith("image/") -> "image"
            mime == "application/pdf" -> "document"
            else -> throw ProviderProtocolException("Anthropic only supports image and PDF attachments in this SDK")
        }
        if (!isHttpsUrl() && dataUrl == null) {
            throw ProviderProtocolException("Anthropic attachments require HTTPS or a valid base64 data URL")
        }
        return buildJsonObject {
            put("type", blockType)
            put("source", buildJsonObject {
                if (dataUrl != null) {
                    put("type", "base64")
                    put("media_type", mime)
                    put("data", dataUrl.data)
                } else {
                    put("type", "url")
                    put("url", uri)
                }
            })
        }
    }

    private fun validateConfig(config: AnthropicTransportConfig, request: ProviderRequest) {
        val mode = config.thinkingMode
        if (mode != null && mode !in THINKING_MODES) throw IllegalArgumentException("Unsupported Anthropic thinking mode")
        if (config.thinkingDisplay != null && config.thinkingDisplay !in THINKING_DISPLAYS) {
            throw IllegalArgumentException("Unsupported Anthropic thinking display")
        }
        if (config.effort != null && config.effort !in EFFORT_LEVELS) {
            throw IllegalArgumentException("Unsupported Anthropic effort")
        }
        when (mode) {
            "enabled" -> {
                val budget = config.thinkingBudgetTokens
                    ?: throw IllegalArgumentException("Anthropic enabled thinking requires a token budget")
                require(budget >= 1_024) { "Anthropic thinking budget must be at least 1024" }
                require(budget < (request.maxTokens ?: DEFAULT_MAX_TOKENS)) {
                    "Anthropic thinking budget must be less than max tokens"
                }
            }
            "adaptive" -> require(config.thinkingBudgetTokens == null) {
                "Anthropic adaptive thinking does not accept a manual token budget"
            }
            "disabled" -> require(config.thinkingBudgetTokens == null && config.thinkingDisplay == null) {
                "Anthropic disabled thinking does not accept budget or display"
            }
            null -> require(config.thinkingBudgetTokens == null && config.thinkingDisplay == null) {
                "Anthropic thinking options require an explicit mode"
            }
        }
        if (mode != null && mode != "disabled" && request.temperature != null) {
            throw IllegalArgumentException("Anthropic thinking is incompatible with temperature")
        }
    }

    private fun renderToolResult(value: JsonElement): String = when (value) {
        is JsonPrimitive -> value.contentOrNull ?: value.toString()
        else -> json.encodeToString(JsonElement.serializer(), value)
    }
}

private fun textBlock(text: String): JsonObject = buildJsonObject {
    put("type", "text")
    put("text", text)
}

internal fun ProviderRequest.anthropicTransportConfig(): AnthropicTransportConfig = when (val config = typedConfig) {
    null -> AnthropicTransportConfig()
    is AnthropicTransportConfig -> config
    else -> throw IllegalArgumentException("Anthropic provider received options for another provider family")
}

private const val DEFAULT_MAX_TOKENS = 4_096
private val THINKING_MODES = setOf("adaptive", "enabled", "disabled")
private val THINKING_DISPLAYS = setOf("summarized", "omitted")
private val EFFORT_LEVELS = setOf("low", "medium", "high", "xhigh", "max")
