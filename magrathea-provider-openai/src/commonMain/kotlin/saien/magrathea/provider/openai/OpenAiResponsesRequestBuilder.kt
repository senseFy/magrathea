package saien.magrathea.provider.openai

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
import saien.magrathea.provider.api.OpenAiTransportConfig
import saien.magrathea.provider.api.OpenAiResponsesHostedTool
import saien.magrathea.provider.api.OpenAiXSearchToolConfig
import saien.magrathea.provider.api.ProviderProtocolException
import saien.magrathea.provider.api.ProviderRequest
import saien.magrathea.provider.api.modelProjection
import saien.magrathea.provider.api.ReferenceProviderInputCapabilities

internal const val OPENAI_RESPONSE_OUTPUT_METADATA = "openai.responses.output"
internal const val OPENAI_RESPONSE_ID_METADATA = "openai.responses.id"
internal const val OPENAI_RESPONSE_STATUS_METADATA = "openai.responses.status"

internal class OpenAiResponsesRequestBuilder(
    private val providerKey: String,
    private val json: Json = Json,
) {
    fun build(request: ProviderRequest): JsonObject = buildJsonObject {
        val config = request.openAiTransportConfig()
        put("model", request.model.model)
        put("input", buildInput(request))
        put("stream", request.model.supportsStreaming)
        put("store", false)

        if (request.tools.isNotEmpty() || config.hostedTools.isNotEmpty()) {
            put("tools", buildTools(request, config.hostedTools))
            put("tool_choice", "auto")
            put("parallel_tool_calls", true)
        }
        config.instructions?.takeIf(String::isNotBlank)?.let { put("instructions", it) }
        request.temperature?.let { put("temperature", it) }
        request.maxTokens?.let { put("max_output_tokens", it) }
        if (config.reasoningEffort != null || config.reasoningSummary != null) {
            put("reasoning", buildJsonObject {
                config.reasoningEffort?.let { put("effort", it) }
                config.reasoningSummary?.let { put("summary", it) }
            })
        }
        if (request.model.supportsReasoning || config.reasoningEffort != null || config.reasoningSummary != null) {
            put("include", buildJsonArray { add(JsonPrimitive("reasoning.encrypted_content")) })
        }
        config.serviceTier?.let { put("service_tier", it) }
        config.promptCacheKey?.let { put("prompt_cache_key", it) }
        config.promptCacheRetention?.let { put("prompt_cache_retention", it) }
        config.maxToolTurns?.let { put("max_turns", it) }
    }

    private fun buildInput(request: ProviderRequest): JsonArray = buildJsonArray {
        request.messages.forEach { message ->
            when (message.role) {
                MessageRole.SYSTEM -> add(systemInput(message))
                MessageRole.USER -> add(userInput(message))
                MessageRole.ASSISTANT -> assistantInput(message, request).forEach(::add)
                MessageRole.TOOL -> toolResultInput(message, request).forEach(::add)
            }
        }
    }

    private fun systemInput(message: AgentMessage): JsonObject = buildJsonObject {
        val text = message.parts.joinToString("\n") { part ->
            (part as? TextPart)?.text
                ?: throw ProviderProtocolException("OpenAI system messages only support text")
        }
        if (text.isBlank()) throw ProviderProtocolException("OpenAI system message must not be blank")
        put("role", "developer")
        put("content", buildJsonArray { add(inputText(text)) })
    }

    private fun userInput(message: AgentMessage): JsonObject = buildJsonObject {
        val content = buildJsonArray {
            message.parts.forEach { part ->
                when (part) {
                    is TextPart -> if (part.text.isNotBlank()) add(inputText(part.text))
                    is JsonPart -> add(inputText(json.encodeToString(JsonElement.serializer(), part.value)))
                    is AttachmentPart -> add(part.toInputPart())
                    else -> throw ProviderProtocolException("Unsupported OpenAI user message part ${part::class.simpleName}")
                }
            }
        }
        if (content.isEmpty()) throw ProviderProtocolException("OpenAI user message must not be empty")
        put("role", "user")
        put("content", content)
    }

    private fun assistantInput(message: AgentMessage, request: ProviderRequest): List<JsonObject> {
        val sourceProvider = message.metadata["provider"]?.jsonPrimitive?.contentOrNull
        val sourceModel = message.metadata["model"]?.jsonPrimitive?.contentOrNull
        val authoritative = message.metadata[OPENAI_RESPONSE_OUTPUT_METADATA] as? JsonArray
        if (sourceProvider == providerKey && sourceModel == request.model.model) {
            return authoritative?.mapIndexed { index, item ->
                item as? JsonObject
                    ?: throw ProviderProtocolException("Stored OpenAI output item $index must be an object")
            } ?: throw ProviderProtocolException(
                "Same-model OpenAI replay requires authoritative response output items",
            )
        }

        return buildList {
            val visibleContent = message.parts.mapNotNull { part ->
                when (part) {
                    is TextPart -> part.text.takeIf(String::isNotBlank)
                    is JsonPart -> json.encodeToString(JsonElement.serializer(), part.value)
                    is ToolCallPart -> null
                    else -> throw ProviderProtocolException(
                        "Unsupported cross-model OpenAI assistant part ${part::class.simpleName}",
                    )
                }
            }.joinToString("\n")
            if (visibleContent.isNotEmpty()) {
                add(buildJsonObject {
                    put("role", "assistant")
                    put("content", visibleContent)
                })
            }
            message.parts.filterIsInstance<ToolCallPart>().forEach { call ->
                if (call.partial) throw ProviderProtocolException("OpenAI cannot replay a partial tool call")
                if (call.arguments !is JsonObject) {
                    throw ProviderProtocolException("OpenAI tool arguments must be an object")
                }
                add(buildJsonObject {
                    put("type", "function_call")
                    put("call_id", call.toolCallId)
                    put("name", call.toolName)
                    put("arguments", json.encodeToString(JsonElement.serializer(), call.arguments))
                })
            }
            if (isEmpty()) throw ProviderProtocolException("OpenAI assistant replay must not be empty")
        }
    }

    private fun toolResultInput(message: AgentMessage, request: ProviderRequest): List<JsonObject> {
        val results = message.parts.filterIsInstance<ToolResultPart>()
        if (results.isEmpty() || results.size != message.parts.size) {
            throw ProviderProtocolException("OpenAI tool message must contain only tool results")
        }
        return results.map { result ->
            buildJsonObject {
                put("type", "function_call_output")
                put("call_id", result.toolCallId)
                put("output", result.toResponsesOutput(request))
            }
        }
    }

    private fun ToolResultPart.toResponsesOutput(request: ProviderRequest): JsonElement {
        val projection = modelProjection(
            request.model.inputModalities,
            ReferenceProviderInputCapabilities.openAiResponses,
        )
        if (projection.content.isEmpty()) {
            return JsonPrimitive(renderToolResult(requireNotNull(projection.canonicalResult)))
        }
        return buildJsonArray {
            projection.canonicalResult?.let { add(inputText(renderToolResult(it))) }
            projection.content.forEach { block ->
                when (block) {
                    is ToolResultTextContent -> add(inputText(block.text))
                    is ToolResultImageContent -> add(block.toInputImage())
                }
            }
        }
    }

    private fun ToolResultImageContent.toInputImage(): JsonObject {
        val imageUrl = source.toOpenAiImageUrl(mimeType)
        return buildJsonObject {
            put("type", "input_image")
            put("image_url", imageUrl)
            put("detail", "auto")
        }
    }

    private fun ToolImageSource.toOpenAiImageUrl(mimeType: String?): String = when (this) {
        is RemoteToolImageSource -> uri
        is InlineToolImageSource -> {
            val mediaType = mimeType
                ?: throw ProviderProtocolException("OpenAI inline Tool images require a MIME type")
            "data:$mediaType;base64,$data"
        }
        is ToolImageAttachmentReference -> throw ProviderProtocolException(
            "OpenAI Tool image attachment references must be resolved before request encoding",
        )
    }

    private fun buildTools(
        request: ProviderRequest,
        hostedTools: List<OpenAiResponsesHostedTool>,
    ): JsonArray = buildJsonArray {
        request.tools.forEach { tool ->
            add(buildJsonObject {
                put("type", "function")
                put("name", tool.name)
                put("description", tool.description)
                put("parameters", tool.schema)
            })
        }
        hostedTools.forEach { tool ->
            add(tool.toWireTool())
        }
    }

    private fun AttachmentPart.toInputPart(): JsonObject {
        val effectiveMimeType = validatedOpenAiMimeType()
        return if (effectiveMimeType.startsWith("image/")) {
            toInputImage()
        } else {
            toInputFile()
        }
    }

    private fun AttachmentPart.validatedOpenAiMimeType(): String {
        val dataUrl = dataUrlPayload()
        if (uri.startsWith("data:", ignoreCase = true) && dataUrl == null) {
            throw ProviderProtocolException("OpenAI attachments require a valid base64 data URL")
        }
        val declaredMimeType = normalizedMimeType()
        val dataMimeType = dataUrl?.mediaType
        if (declaredMimeType.isBlank() && dataMimeType == null) {
            throw ProviderProtocolException("OpenAI attachments require a MIME type")
        }
        if (
            declaredMimeType.isNotBlank() &&
            dataMimeType != null &&
            declaredMimeType != dataMimeType
        ) {
            throw ProviderProtocolException("OpenAI attachment MIME types must match")
        }
        val effectiveMimeType = declaredMimeType.ifBlank { dataMimeType.orEmpty() }
        if (!ReferenceProviderInputCapabilities.openAiResponses.supportsAttachment(effectiveMimeType)) {
            throw ProviderProtocolException("OpenAI attachment type is not supported")
        }
        return effectiveMimeType
    }

    private fun AttachmentPart.toInputImage(): JsonObject {
        if (!isHttpsUrl() && dataUrlPayload() == null) {
            throw ProviderProtocolException(
                "OpenAI image attachments require HTTPS or a valid base64 data URL",
            )
        }
        return buildJsonObject {
            put("type", "input_image")
            put("image_url", uri)
            put("detail", imageDetail())
        }
    }

    private fun AttachmentPart.toInputFile(): JsonObject {
        val dataUrl = dataUrlPayload()
        if (!isHttpsUrl() && dataUrl == null) {
            throw ProviderProtocolException(
                "OpenAI file attachments require HTTPS or a valid base64 data URL",
            )
        }
        val validatedFileName = validatedFileName()
        return buildJsonObject {
            put("type", "input_file")
            if (isHttpsUrl()) {
                put("file_url", uri)
            } else {
                put("file_data", uri)
            }
            validatedFileName?.let { put("filename", it) }
        }
    }

    private fun AttachmentPart.validatedFileName(): String? {
        val value = fileName ?: return null
        if (
            value.isBlank() ||
            value != value.trim() ||
            value.length > MAX_FILE_NAME_LENGTH ||
            value.any { character -> character.code < 0x20 || character == '/' || character == '\\' }
        ) {
            throw ProviderProtocolException("OpenAI file attachment name is invalid")
        }
        return value
    }

    private fun AttachmentPart.imageDetail(): String {
        val detail = providerMetadata?.get("detail")?.jsonPrimitive?.contentOrNull ?: "auto"
        if (detail !in setOf("auto", "low", "high")) {
            throw ProviderProtocolException("Unsupported OpenAI image detail")
        }
        return detail
    }

    private fun renderToolResult(value: JsonElement): String = when (value) {
        is JsonPrimitive -> value.contentOrNull ?: value.toString()
        else -> json.encodeToString(JsonElement.serializer(), value)
    }
}

private fun OpenAiResponsesHostedTool.toWireTool(): JsonObject = when (this) {
    is OpenAiXSearchToolConfig -> buildJsonObject {
        put("type", "x_search")
        if (allowedHandles.isNotEmpty()) {
            put("allowed_x_handles", buildJsonArray {
                allowedHandles.forEach { add(JsonPrimitive(it)) }
            })
        }
        if (excludedHandles.isNotEmpty()) {
            put("excluded_x_handles", buildJsonArray {
                excludedHandles.forEach { add(JsonPrimitive(it)) }
            })
        }
        fromDate?.let { put("from_date", it) }
        toDate?.let { put("to_date", it) }
        if (enableImageUnderstanding) put("enable_image_understanding", true)
        if (enableVideoUnderstanding) put("enable_video_understanding", true)
    }
}

private const val MAX_FILE_NAME_LENGTH = 255

internal fun ProviderRequest.openAiTransportConfig(): OpenAiTransportConfig = when (val config = typedConfig) {
    null -> OpenAiTransportConfig()
    is OpenAiTransportConfig -> config
    else -> throw IllegalArgumentException("OpenAI provider received options for another provider family")
}

private fun inputText(text: String): JsonObject = buildJsonObject {
    put("type", "input_text")
    put("text", text)
}
