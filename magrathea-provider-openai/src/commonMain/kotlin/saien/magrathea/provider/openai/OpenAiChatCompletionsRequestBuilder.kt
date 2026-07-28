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
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import saien.magrathea.core.AgentMessage
import saien.magrathea.core.AttachmentPart
import saien.magrathea.core.JsonPart
import saien.magrathea.core.MessageRole
import saien.magrathea.core.ReasoningPart
import saien.magrathea.core.TextPart
import saien.magrathea.core.ToolCallPart
import saien.magrathea.core.ToolResultPart
import saien.magrathea.core.dataUrlPayload
import saien.magrathea.core.isHttpsUrl
import saien.magrathea.core.normalizedMimeType
import saien.magrathea.provider.api.ProviderProtocolException
import saien.magrathea.provider.api.ProviderRequest
import saien.magrathea.provider.api.ReferenceProviderInputCapabilities

internal const val OPENAI_CHAT_MESSAGE_METADATA = "openai.chat.message"
internal const val OPENAI_CHAT_REASONING_DETAILS_METADATA = "openai.chat.reasoning_details"

/** Builds the portable subset shared by OpenAI-compatible Chat Completions services. */
internal class OpenAiChatCompletionsRequestBuilder(
    private val json: Json = Json,
) {
    fun build(request: ProviderRequest): JsonObject = buildJsonObject {
        val config = request.openAiTransportConfig()
        if (config.hostedTools.isNotEmpty() || config.maxToolTurns != null) {
            throw ProviderProtocolException(
                "OpenAI-compatible Chat Completions does not support Responses hosted Tools",
            )
        }
        put("model", request.model.model)
        put("messages", buildMessages(request))
        put("stream", request.model.supportsStreaming)
        request.temperature?.let { put("temperature", it) }
        request.maxTokens?.let { put("max_tokens", it) }
        config.reasoningEffort?.takeIf(String::isNotBlank)?.let { put("reasoning_effort", it) }
        config.serviceTier?.takeIf(String::isNotBlank)?.let { put("service_tier", it) }
        if (request.tools.isNotEmpty()) {
            put("tools", buildTools(request))
            put("tool_choice", "auto")
        }
    }

    private fun buildMessages(request: ProviderRequest): JsonArray = buildJsonArray {
        request.openAiTransportConfig().instructions
            ?.takeIf(String::isNotBlank)
            ?.let { add(textMessage("system", it)) }
        request.messages.forEach { message ->
            when (message.role) {
                MessageRole.SYSTEM -> add(systemMessage(message))
                MessageRole.USER -> add(userMessage(message))
                MessageRole.ASSISTANT -> add(assistantMessage(message, request))
                MessageRole.TOOL -> toolMessages(message).forEach(::add)
            }
        }
    }

    private fun systemMessage(message: AgentMessage): JsonObject {
        val text = message.parts.joinToString("\n") { part ->
            when (part) {
                is TextPart -> part.text
                is JsonPart -> json.encodeToString(JsonElement.serializer(), part.value)
                else -> throw ProviderProtocolException("OpenAI-compatible system messages only support text")
            }
        }
        if (text.isBlank()) throw ProviderProtocolException("OpenAI-compatible system message must not be blank")
        return textMessage("system", text)
    }

    private fun userMessage(message: AgentMessage): JsonObject {
        val textParts = mutableListOf<String>()
        val attachments = mutableListOf<AttachmentPart>()
        message.parts.forEach { part ->
            when (part) {
                is TextPart -> part.text.takeIf(String::isNotBlank)?.let(textParts::add)
                is JsonPart -> textParts += json.encodeToString(JsonElement.serializer(), part.value)
                is AttachmentPart -> attachments += part
                else -> throw ProviderProtocolException(
                    "Unsupported OpenAI-compatible user message part ${part::class.simpleName}",
                )
            }
        }
        if (textParts.isEmpty() && attachments.isEmpty()) {
            throw ProviderProtocolException("OpenAI-compatible user message must not be empty")
        }
        return buildJsonObject {
            put("role", "user")
            if (attachments.isEmpty()) {
                put("content", textParts.joinToString("\n"))
            } else {
                put("content", buildJsonArray {
                    textParts.forEach { text ->
                        add(buildJsonObject {
                            put("type", "text")
                            put("text", text)
                        })
                    }
                    attachments.forEach { add(it.toImagePart()) }
                })
            }
        }
    }

    private fun assistantMessage(message: AgentMessage, request: ProviderRequest): JsonObject {
        val sameProviderAndModel =
            message.metadata["provider"]?.jsonPrimitive?.contentOrNull == request.model.provider &&
                message.metadata["model"]?.jsonPrimitive?.contentOrNull == request.model.model
        val authoritativeReasoning = if (sameProviderAndModel) {
            when (val value = message.metadata[OPENAI_CHAT_REASONING_DETAILS_METADATA]) {
                null -> null
                is JsonArray -> value
                else -> throw ProviderProtocolException(
                    "Stored OpenAI-compatible reasoning details must be an array",
                )
            }
        } else {
            null
        }
        val textParts = mutableListOf<String>()
        val reasoningParts = mutableListOf<String>()
        message.parts.forEach { part ->
            when (part) {
                is TextPart -> part.text.takeIf(String::isNotBlank)?.let(textParts::add)
                is ReasoningPart -> part.text.takeIf(String::isNotBlank)?.let(reasoningParts::add)
                is JsonPart -> textParts += json.encodeToString(JsonElement.serializer(), part.value)
                is ToolCallPart -> Unit
                else -> throw ProviderProtocolException(
                    "Unsupported OpenAI-compatible assistant part ${part::class.simpleName}",
                )
            }
        }
        val text = textParts.joinToString("\n")
        val reasoning = reasoningParts.joinToString("\n")
        val calls = message.parts.filterIsInstance<ToolCallPart>()
        if (text.isEmpty() && reasoning.isEmpty() && calls.isEmpty()) {
            throw ProviderProtocolException("OpenAI-compatible assistant replay must not be empty")
        }
        return buildJsonObject {
            put("role", "assistant")
            if (text.isEmpty()) put("content", JsonNull) else put("content", text)
            if (authoritativeReasoning != null) {
                put("reasoning_details", authoritativeReasoning)
            } else if (reasoning.isNotEmpty()) {
                put("reasoning_content", reasoning)
            }
            if (calls.isNotEmpty()) {
                put("tool_calls", buildJsonArray {
                    calls.forEach { call ->
                        if (call.partial) {
                            throw ProviderProtocolException("OpenAI-compatible APIs cannot replay a partial tool call")
                        }
                        if (call.arguments !is JsonObject) {
                            throw ProviderProtocolException("OpenAI-compatible tool arguments must be an object")
                        }
                        add(buildJsonObject {
                            put("id", call.toolCallId)
                            put("type", "function")
                            put("function", buildJsonObject {
                                put("name", call.toolName)
                                put("arguments", json.encodeToString(JsonElement.serializer(), call.arguments))
                            })
                        })
                    }
                })
            }
        }
    }

    private fun toolMessages(message: AgentMessage): List<JsonObject> {
        val results = message.parts.filterIsInstance<ToolResultPart>()
        if (results.isEmpty() || results.size != message.parts.size) {
            throw ProviderProtocolException("OpenAI-compatible tool messages must contain only tool results")
        }
        return results.map { result ->
            buildJsonObject {
                put("role", "tool")
                put("tool_call_id", result.toolCallId)
                put("content", renderToolResult(result))
            }
        }
    }

    private fun buildTools(request: ProviderRequest): JsonArray = buildJsonArray {
        request.tools.forEach { tool ->
            add(buildJsonObject {
                put("type", "function")
                put("function", buildJsonObject {
                    put("name", tool.name)
                    put("description", tool.description)
                    put("parameters", tool.schema)
                })
            })
        }
    }

    private fun AttachmentPart.toImagePart(): JsonObject {
        val dataUrl = dataUrlPayload()
        if (uri.startsWith("data:", ignoreCase = true) && dataUrl == null) {
            throw ProviderProtocolException("OpenAI-compatible images require a valid base64 data URL")
        }
        val declared = normalizedMimeType()
        val embedded = dataUrl?.mediaType
        if (declared.isNotBlank() && embedded != null && declared != embedded) {
            throw ProviderProtocolException("OpenAI-compatible image MIME types must match")
        }
        val effective = declared.ifBlank { embedded.orEmpty() }
        if (!ReferenceProviderInputCapabilities.openAiChatCompletions.supportsAttachment(effective)) {
            throw ProviderProtocolException("OpenAI-compatible attachment type is not supported")
        }
        if (!isHttpsUrl() && dataUrl == null) {
            throw ProviderProtocolException("OpenAI-compatible images require HTTPS or a valid base64 data URL")
        }
        return buildJsonObject {
            put("type", "image_url")
            put("image_url", buildJsonObject { put("url", uri) })
        }
    }

    private fun renderToolResult(result: ToolResultPart): String = when (val value = result.result) {
        is JsonPrimitive -> value.contentOrNull ?: value.toString()
        else -> json.encodeToString(JsonElement.serializer(), value)
    }
}

private fun textMessage(role: String, content: String): JsonObject = buildJsonObject {
    put("role", role)
    put("content", content)
}
