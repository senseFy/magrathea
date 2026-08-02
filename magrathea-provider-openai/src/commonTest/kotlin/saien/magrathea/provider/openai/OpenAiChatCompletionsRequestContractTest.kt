package saien.magrathea.provider.openai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import saien.magrathea.core.AgentMessage
import saien.magrathea.core.AttachmentPart
import saien.magrathea.core.InlineToolImageSource
import saien.magrathea.core.MessageRole
import saien.magrathea.core.ModelDescriptor
import saien.magrathea.core.ModelInputModality
import saien.magrathea.core.RemoteToolImageSource
import saien.magrathea.core.ReasoningContentKind
import saien.magrathea.core.ReasoningPart
import saien.magrathea.core.TextPart
import saien.magrathea.core.ToolCallPart
import saien.magrathea.core.ToolDefinition
import saien.magrathea.core.ToolResultAudience
import saien.magrathea.core.ToolResultImageContent
import saien.magrathea.core.ToolResultPart
import saien.magrathea.core.ToolResultTextContent
import saien.magrathea.provider.api.OpenAiTransportConfig
import saien.magrathea.provider.api.OpenAiWireProtocol
import saien.magrathea.provider.api.ProviderProtocolException
import saien.magrathea.provider.api.ProviderRequest

class OpenAiChatCompletionsRequestContractTest {
    private val builder = OpenAiChatCompletionsRequestBuilder()

    @Test
    fun canonicalHistoryToolsAndImagesUsePortableChatShape() {
        val payload = builder.build(
            request(
                messages = listOf(
                    AgentMessage(role = MessageRole.SYSTEM, parts = listOf(TextPart("Be concise"))),
                    AgentMessage(
                        role = MessageRole.USER,
                        parts = listOf(
                            TextPart("What is in this image?"),
                            AttachmentPart("data:image/png;base64,IMAGE_DATA", "image/png"),
                        ),
                    ),
                    AgentMessage(
                        role = MessageRole.ASSISTANT,
                        parts = listOf(
                            ReasoningPart("I should use the weather tool"),
                            ToolCallPart(
                                toolCallId = "call_weather_1",
                                toolName = "get_weather",
                                arguments = buildJsonObject { put("city", "Shanghai") },
                            ),
                        ),
                    ),
                    AgentMessage(
                        role = MessageRole.TOOL,
                        parts = listOf(
                            ToolResultPart(
                                toolCallId = "call_weather_1",
                                toolName = "get_weather",
                                result = buildJsonObject { put("condition", "sunny") },
                            ),
                        ),
                    ),
                ),
                tools = listOf(
                    ToolDefinition(
                        name = "get_weather",
                        description = "Get weather",
                        schema = buildJsonObject { put("type", "object") },
                    ),
                ),
            ),
        )

        val messages = payload["messages"]!!.jsonArray
        assertEquals(listOf("system", "user", "assistant", "tool"), messages.map {
            it.jsonObject["role"]!!.jsonPrimitive.content
        })
        val userContent = messages[1].jsonObject["content"]!!.jsonArray
        assertEquals("text", userContent[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("image_url", userContent[1].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals(
            "call_weather_1",
            messages[2].jsonObject["tool_calls"]!!.jsonArray.single().jsonObject["id"]!!.jsonPrimitive.content,
        )
        assertEquals(
            "I should use the weather tool",
            messages[2].jsonObject["reasoning_content"]!!.jsonPrimitive.content,
        )
        assertEquals("call_weather_1", messages[3].jsonObject["tool_call_id"]!!.jsonPrimitive.content)
        assertEquals(
            "{\"condition\":\"sunny\"}",
            messages[3].jsonObject.getValue("content").jsonPrimitive.content,
        )
        assertEquals(
            "get_weather",
            payload["tools"]!!.jsonArray.single().jsonObject["function"]!!.jsonObject["name"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun transportControlsMapOnlyToPortableFields() {
        val payload = builder.build(
            request(
                typedConfig = OpenAiTransportConfig(
                    protocol = OpenAiWireProtocol.CHAT_COMPLETIONS,
                    instructions = "Answer briefly",
                    reasoningEffort = "high",
                    serviceTier = "auto",
                    promptCacheKey = "responses-only",
                ),
            ),
        )

        assertEquals("Answer briefly", payload["messages"]!!.jsonArray.first().jsonObject["content"]!!.jsonPrimitive.content)
        assertEquals("high", payload["reasoning_effort"]!!.jsonPrimitive.content)
        assertEquals("auto", payload["service_tier"]!!.jsonPrimitive.content)
        assertFalse(payload.containsKey("prompt_cache_key"))
        assertFalse(payload.containsKey("store"))
    }

    @Test
    fun mcpContentOnlyImagesRespectMimeAudienceAndModelModality() {
        val toolResult = ToolResultPart(
            toolCallId = "call-image-1",
            toolName = "inspect_image",
            result = mcpImageEnvelope(MCP_IMAGE_DATA),
            content = listOf(
                ToolResultImageContent(
                    source = InlineToolImageSource(MCP_IMAGE_DATA),
                    mimeType = "image/png",
                    audiences = setOf(ToolResultAudience.MODEL),
                ),
                ToolResultImageContent(
                    source = InlineToolImageSource(UNSUPPORTED_IMAGE_DATA),
                    mimeType = "image/svg+xml",
                    audiences = setOf(ToolResultAudience.MODEL),
                ),
                ToolResultImageContent(
                    source = InlineToolImageSource(NULL_MIME_IMAGE_DATA),
                    mimeType = null,
                    audiences = setOf(ToolResultAudience.MODEL),
                ),
                ToolResultImageContent(
                    source = InlineToolImageSource(USER_ONLY_IMAGE_DATA),
                    mimeType = "image/png",
                    audiences = setOf(ToolResultAudience.USER),
                ),
            ),
            modelResultVisible = false,
        )
        val payload = builder.build(
            request(
                messages = listOf(
                    AgentMessage(
                        role = MessageRole.TOOL,
                        parts = listOf(toolResult),
                    ),
                ),
                inputModalities = setOf(ModelInputModality.TEXT, ModelInputModality.IMAGE),
            ),
        )

        val messages = payload.getValue("messages").jsonArray
        assertEquals(listOf("tool", "user"), messages.map {
            it.jsonObject.getValue("role").jsonPrimitive.content
        })
        assertEquals(
            "(see attached Tool images)",
            messages.first().jsonObject.getValue("content").jsonPrimitive.content,
        )
        val image = messages.last().jsonObject.getValue("content").jsonArray[1]
            .jsonObject.getValue("image_url").jsonObject.getValue("url").jsonPrimitive.content
        assertEquals("data:image/png;base64,$MCP_IMAGE_DATA", image)
        assertEquals(1, payload.toString().countOccurrences(MCP_IMAGE_DATA))
        assertFalse(payload.toString().contains(UNSUPPORTED_IMAGE_DATA))
        assertFalse(payload.toString().contains(NULL_MIME_IMAGE_DATA))
        assertFalse(payload.toString().contains(USER_ONLY_IMAGE_DATA))

        val textOnly = builder.build(
            request(
                messages = listOf(
                    AgentMessage(role = MessageRole.TOOL, parts = listOf(toolResult)),
                ),
            ),
        )
        assertEquals(1, textOnly.getValue("messages").jsonArray.size)
        assertEquals(
            "Tool completed without model-visible output.",
            textOnly.getValue("messages").jsonArray.single()
                .jsonObject.getValue("content").jsonPrimitive.content,
        )
        assertFalse(textOnly.toString().contains(MCP_IMAGE_DATA))
        assertFalse(textOnly.toString().contains(UNSUPPORTED_IMAGE_DATA))
        assertFalse(textOnly.toString().contains(NULL_MIME_IMAGE_DATA))
        assertFalse(textOnly.toString().contains(USER_ONLY_IMAGE_DATA))
    }

    @Test
    fun imageOnlyToolOutputFallsBackToStructuredResultForTextOnlyModels() {
        val payload = builder.build(
            request(
                messages = listOf(
                    AgentMessage(
                        role = MessageRole.TOOL,
                        parts = listOf(
                            ToolResultPart(
                                toolCallId = "call-image-1",
                                toolName = "inspect_image",
                                result = buildJsonObject { put("status", "image-available") },
                                content = listOf(
                                    ToolResultImageContent(
                                        source = RemoteToolImageSource(
                                            "https://cdn.example.com/model-image.jpg",
                                        ),
                                        mimeType = "image/jpeg",
                                        audiences = setOf(ToolResultAudience.MODEL),
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )

        val messages = payload.getValue("messages").jsonArray
        assertEquals(1, messages.size)
        assertEquals(
            "{\"status\":\"image-available\"}",
            messages.single().jsonObject.getValue("content").jsonPrimitive.content,
        )
    }

    @Test
    fun toolOutputComposesCanonicalAndModelTextWithoutUserOnlyData() {
        val payload = builder.build(
            request(
                messages = listOf(
                    AgentMessage(
                        role = MessageRole.TOOL,
                        parts = listOf(
                            ToolResultPart(
                                toolCallId = "call-both",
                                toolName = "lookup",
                                result = buildJsonObject { put("structured", "canonical") },
                                content = listOf(
                                    ToolResultTextContent(
                                        "model summary",
                                        setOf(ToolResultAudience.MODEL),
                                    ),
                                ),
                            ),
                            ToolResultPart(
                                toolCallId = "call-user",
                                toolName = "lookup",
                                result = buildJsonObject { put("secret", "USER_ONLY_SECRET") },
                                isError = true,
                                content = listOf(
                                    ToolResultTextContent(
                                        "USER_ONLY_SECRET",
                                        setOf(ToolResultAudience.USER),
                                    ),
                                ),
                                modelResultVisible = false,
                            ),
                        ),
                    ),
                ),
            ),
        )

        val messages = payload.getValue("messages").jsonArray
        assertEquals(
            "{\"structured\":\"canonical\"}\nmodel summary",
            messages[0].jsonObject.getValue("content").jsonPrimitive.content,
        )
        assertEquals(
            "Tool failed without model-visible error details.",
            messages[1].jsonObject.getValue("content").jsonPrimitive.content,
        )
        assertFalse(payload.toString().contains("USER_ONLY_SECRET"))
    }

    @Test
    fun sameModelReplayUsesAuthoritativeReasoningDetailsWithoutFlatteningOpaqueBlocks() {
        val details = Json.parseToJsonElement(
            """[
                {"type":"reasoning.summary","summary":"Checked constraints.","index":0},
                {"type":"reasoning.encrypted","data":"opaque-state","index":1}
            ]""".trimIndent(),
        ).jsonArray
        val assistant = AgentMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                ReasoningPart(
                    text = "Checked constraints.",
                    kind = ReasoningContentKind.SUMMARY,
                ),
                ReasoningPart(text = "", signature = "opaque-state", redacted = true),
                TextPart("Final answer."),
            ),
            metadata = buildJsonObject {
                put("provider", "openai")
                put("model", "compatible-model")
                put(OPENAI_CHAT_REASONING_DETAILS_METADATA, details)
            },
        )

        val payload = builder.build(request(messages = listOf(assistant)))
        val replay = payload["messages"]!!.jsonArray.single().jsonObject

        assertEquals(details, replay["reasoning_details"])
        assertFalse(replay.containsKey("reasoning_content"))
    }

    @Test
    fun filesAndMalformedImagesFailClosedWithoutEchoingContent() {
        val secret = "ATTACHMENT_SECRET"
        val fileFailure = assertFailsWith<ProviderProtocolException> {
            builder.build(
                request(
                    messages = listOf(
                        AgentMessage(
                            role = MessageRole.USER,
                            parts = listOf(AttachmentPart("data:application/pdf;base64,$secret", "application/pdf")),
                        ),
                    ),
                ),
            )
        }
        assertFalse(fileFailure.message.orEmpty().contains(secret))

        val mismatch = assertFailsWith<ProviderProtocolException> {
            builder.build(
                request(
                    messages = listOf(
                        AgentMessage(
                            role = MessageRole.USER,
                            parts = listOf(AttachmentPart("data:image/png;base64,$secret", "image/jpeg")),
                        ),
                    ),
                ),
            )
        }
        assertFalse(mismatch.message.orEmpty().contains(secret))
    }

    private fun request(
        messages: List<AgentMessage> = listOf(AgentMessage(role = MessageRole.USER, parts = listOf(TextPart("Hello")))),
        tools: List<ToolDefinition> = emptyList(),
        typedConfig: OpenAiTransportConfig = OpenAiTransportConfig(protocol = OpenAiWireProtocol.CHAT_COMPLETIONS),
        inputModalities: Set<ModelInputModality> = setOf(ModelInputModality.TEXT),
    ): ProviderRequest = ProviderRequest(
        model = ModelDescriptor(
            "openai",
            "compatible-model",
            supportsStreaming = true,
            inputModalities = inputModalities,
        ),
        messages = messages,
        tools = tools,
        typedConfig = typedConfig,
    )

    private fun mcpImageEnvelope(data: String) = buildJsonObject {
        put("content", buildJsonArray {
            add(buildJsonObject {
                put("type", "image")
                put("data", data)
                put("mimeType", "image/png")
            })
        })
    }

    private fun String.countOccurrences(value: String): Int = windowed(value.length).count { it == value }

    private companion object {
        const val MCP_IMAGE_DATA = "MCP_IMAGE_DATA"
        const val UNSUPPORTED_IMAGE_DATA = "UNSUPPORTED_IMAGE_DATA"
        const val NULL_MIME_IMAGE_DATA = "NULL_MIME_IMAGE_DATA"
        const val USER_ONLY_IMAGE_DATA = "USER_ONLY_IMAGE_DATA"
    }
}
