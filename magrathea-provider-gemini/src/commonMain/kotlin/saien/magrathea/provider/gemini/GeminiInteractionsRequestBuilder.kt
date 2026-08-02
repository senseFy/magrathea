package saien.magrathea.provider.gemini

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import saien.magrathea.core.AgentMessage
import saien.magrathea.core.AttachmentPart
import saien.magrathea.core.InlineToolImageSource
import saien.magrathea.core.JsonPart
import saien.magrathea.core.MessageRole
import saien.magrathea.core.RemoteToolImageSource
import saien.magrathea.core.TextPart
import saien.magrathea.core.ToolImageAttachmentReference
import saien.magrathea.core.ToolResultImageContent
import saien.magrathea.core.ToolResultPart
import saien.magrathea.core.ToolResultTextContent
import saien.magrathea.provider.api.GeminiTransportConfig
import saien.magrathea.provider.api.ProviderProtocolException
import saien.magrathea.provider.api.ProviderRequest
import saien.magrathea.provider.api.modelProjection
import saien.magrathea.provider.api.ReferenceProviderInputCapabilities

internal class GeminiInteractionsRequestBuilder(
    private val json: Json = Json,
) {
    fun build(request: ProviderRequest): JsonObject = buildJsonObject {
        put("model", request.model.model.removePrefix("models/"))
        put("input", buildInput(request))
        put("stream", request.model.supportsStreaming)
        put("store", false)
        systemInstruction(request.messages)?.let { put("system_instruction", it) }
        if (request.tools.isNotEmpty()) {
            put("tools", buildJsonArray {
                request.tools.forEach { tool ->
                    add(buildJsonObject {
                        put("type", "function")
                        put("name", tool.name)
                        put("description", tool.description)
                        put("parameters", tool.schema)
                    })
                }
            })
        }
        generationConfig(request)?.let { put("generation_config", it) }
    }

    private fun buildInput(request: ProviderRequest): JsonArray = buildJsonArray {
        request.messages.forEach { message ->
            when (message.role) {
                MessageRole.SYSTEM -> Unit
                MessageRole.USER -> add(userInputStep(message))
                MessageRole.ASSISTANT -> {
                    val steps = message.metadata[GEMINI_INTERACTION_STEPS_METADATA] as? JsonArray
                        ?: throw ProviderProtocolException(
                            "Gemini stateless replay requires authoritative interaction steps for every assistant message",
                        )
                    if (steps.isEmpty()) throw ProviderProtocolException("Gemini assistant interaction steps must not be empty")
                    steps.forEach { step ->
                        add(step as? JsonObject ?: throw ProviderProtocolException("Stored Gemini interaction step must be an object"))
                    }
                }
                MessageRole.TOOL -> message.parts.filterIsInstance<ToolResultPart>().also { results ->
                    if (results.isEmpty()) throw ProviderProtocolException("Gemini tool message must contain a tool result")
                }.forEach { result -> add(functionResultStep(result, request)) }
            }
        }
    }

    private fun userInputStep(message: AgentMessage): JsonObject = buildJsonObject {
        put("type", "user_input")
        val content = buildJsonArray {
            message.parts.forEach { part ->
                when (part) {
                    is TextPart -> add(textContent(part.text))
                    is JsonPart -> add(textContent(json.encodeToString(JsonElement.serializer(), part.value)))
                    is AttachmentPart -> add(GeminiAttachmentCodec.encode(part))
                    else -> throw ProviderProtocolException("Unsupported Gemini user message part ${part::class.simpleName}")
                }
            }
        }
        if (content.isEmpty()) throw ProviderProtocolException("Gemini user input must not be empty")
        put("content", content)
    }

    private fun functionResultStep(
        result: ToolResultPart,
        request: ProviderRequest,
    ): JsonObject = buildJsonObject {
        put("type", "function_result")
        put("call_id", result.toolCallId)
        put("name", result.toolName)
        put("is_error", result.isError)
        put("result", result.toFunctionResultContent(request))
    }

    private fun ToolResultPart.toFunctionResultContent(request: ProviderRequest): JsonArray {
        val projection = modelProjection(
            request.model.inputModalities,
            ReferenceProviderInputCapabilities.geminiInteractions,
        )
        return buildJsonArray {
            projection.canonicalResult?.let { add(textContent(renderToolResult(it))) }
            projection.content.forEach { block ->
                when (block) {
                    is ToolResultTextContent -> add(textContent(block.text))
                    is ToolResultImageContent -> add(block.toGeminiContent())
                }
            }
        }
    }

    private fun ToolResultImageContent.toGeminiContent(): JsonObject {
        val mediaType = mimeType
            ?: throw ProviderProtocolException("Gemini Tool image result requires a MIME type")
        return buildJsonObject {
            put("type", "image")
            put("mime_type", mediaType)
            when (val imageSource = source) {
                is InlineToolImageSource -> put("data", imageSource.data)
                is RemoteToolImageSource -> put("uri", imageSource.uri)
                is ToolImageAttachmentReference -> throw ProviderProtocolException(
                    "Gemini Tool image attachment reference must be resolved before request encoding",
                )
            }
        }
    }

    private fun renderToolResult(value: JsonElement): String = when (value) {
        is JsonPrimitive -> value.contentOrNull ?: value.toString()
        else -> json.encodeToString(JsonElement.serializer(), value)
    }

    private fun systemInstruction(messages: List<AgentMessage>): String? {
        val systemMessages = messages.filter { it.role == MessageRole.SYSTEM }
        if (systemMessages.isEmpty()) return null
        val lines = systemMessages.flatMap { message ->
            message.parts.map { part ->
                (part as? TextPart)?.text
                    ?: throw ProviderProtocolException("Gemini system instruction only supports text")
            }
        }
        return lines.joinToString("\n").takeIf(String::isNotBlank)
    }

    private fun generationConfig(request: ProviderRequest): JsonObject? {
        val typed = when (val config = request.typedConfig) {
            null -> GeminiTransportConfig()
            is GeminiTransportConfig -> config
            else -> throw IllegalArgumentException("Gemini provider received options for another provider family")
        }
        typed.thinkingLevel?.let {
            require(it in THINKING_LEVELS) { "Unsupported Gemini thinking level" }
        }
        typed.thinkingSummaries?.let {
            require(it in THINKING_SUMMARIES) { "Unsupported Gemini thinking summaries mode" }
        }
        if (
            request.temperature == null &&
            request.maxTokens == null &&
            typed.thinkingLevel == null &&
            typed.thinkingSummaries == null
        ) return null
        return buildJsonObject {
            request.temperature?.let { put("temperature", it) }
            request.maxTokens?.let { put("max_output_tokens", it) }
            typed.thinkingLevel?.let { put("thinking_level", it) }
            typed.thinkingSummaries?.let { put("thinking_summaries", it) }
        }
    }
}

private fun textContent(text: String): JsonObject = buildJsonObject {
    put("type", "text")
    put("text", text)
}

private val THINKING_LEVELS = setOf("minimal", "low", "medium", "high")
private val THINKING_SUMMARIES = setOf("auto", "none")
