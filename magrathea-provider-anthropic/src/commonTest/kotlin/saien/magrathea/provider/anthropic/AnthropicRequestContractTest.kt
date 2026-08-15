package saien.magrathea.provider.anthropic

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
import saien.magrathea.core.ReasoningPart
import saien.magrathea.core.TextPart
import saien.magrathea.core.ToolCallPart
import saien.magrathea.core.ToolResultAudience
import saien.magrathea.core.ToolResultImageContent
import saien.magrathea.core.ToolResultPart
import saien.magrathea.core.ToolResultTextContent
import saien.magrathea.provider.api.AnthropicTransportConfig
import saien.magrathea.provider.api.ProviderProtocolException
import saien.magrathea.provider.api.ProviderRequest
import saien.magrathea.provider.api.ReferenceProviderInputCapabilities

class AnthropicRequestContractTest {
    private val builder = AnthropicRequestBuilder("anthropic")

    @Test
    fun orderedSystemMessagesAndAdaptiveThinkingUseCurrentWireShape() {
        val payload = builder.build(
            request(
                messages = listOf(
                    AgentMessage(role = MessageRole.SYSTEM, parts = listOf(TextPart("First"))),
                    AgentMessage(role = MessageRole.SYSTEM, parts = listOf(TextPart("Second"))),
                    AgentMessage(role = MessageRole.USER, parts = listOf(TextPart("Hello"))),
                ),
                config = AnthropicTransportConfig(
                    thinkingMode = "adaptive",
                    thinkingDisplay = "summarized",
                    effort = "medium",
                ),
            ),
        )

        assertEquals("First\nSecond", payload["system"]!!.jsonPrimitive.content)
        assertEquals("adaptive", payload["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("summarized", payload["thinking"]!!.jsonObject["display"]!!.jsonPrimitive.content)
        assertEquals("medium", payload["output_config"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
        assertFalse(payload.containsKey("interleaved_thinking"))
    }

    @Test
    fun sameModelReplayPreservesThinkingAndToolBlocksExactly() {
        val content = Json.parseToJsonElement(ANTHROPIC_TOOL_CONTENT).jsonArray
        val payload = builder.build(
            request(
                messages = listOf(
                    AgentMessage(role = MessageRole.USER, parts = listOf(TextPart("Weather?"))),
                    AgentMessage(
                        role = MessageRole.ASSISTANT,
                        parts = emptyList(),
                        metadata = buildJsonObject {
                            put("provider", "anthropic")
                            put("model", "claude-contract")
                            put(ANTHROPIC_CONTENT_METADATA, content)
                        },
                    ),
                    AgentMessage(
                        role = MessageRole.TOOL,
                        parts = listOf(
                            ToolResultPart(
                                toolCallId = "toolu_weather_1",
                                toolName = "get_weather",
                                result = buildJsonObject { put("condition", "sunny") },
                            ),
                        ),
                    ),
                ),
            ),
        )

        val messages = payload["messages"]!!.jsonArray
        assertEquals(content, messages[1].jsonObject["content"])
        val toolResult = messages[2].jsonObject["content"]!!.jsonArray.single().jsonObject
        assertEquals("toolu_weather_1", toolResult["tool_use_id"]!!.jsonPrimitive.content)
        assertEquals(
            "{\"condition\":\"sunny\"}",
            toolResult.getValue("content").jsonArray.single()
                .jsonObject.getValue("text").jsonPrimitive.content,
        )
    }

    @Test
    fun toolResultComposesCanonicalAndTypedContentAndPreservesNativeErrorFlag() {
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

        val results = payload.getValue("messages").jsonArray.single()
            .jsonObject.getValue("content").jsonArray.map { it.jsonObject }
        assertEquals(
            listOf("{\"structured\":\"canonical\"}", "model summary"),
            results[0].getValue("content").jsonArray.map { content ->
                content.jsonObject.getValue("text").jsonPrimitive.content
            },
        )
        assertEquals(true, results[1].getValue("is_error").jsonPrimitive.content.toBoolean())
        assertEquals(
            "Tool failed without model-visible error details.",
            results[1].getValue("content").jsonArray.single()
                .jsonObject.getValue("text").jsonPrimitive.content,
        )
        assertFalse(payload.toString().contains("USER_ONLY_SECRET"))
    }

    @Test
    fun mcpContentOnlyImagesRespectMimeAudienceAndModelModality() {
        val toolResult = ToolResultPart(
            toolCallId = "toolu_image_1",
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

        val content = payload.getValue("messages").jsonArray.single()
            .jsonObject.getValue("content").jsonArray.single()
            .jsonObject.getValue("content").jsonArray
        assertEquals(listOf("image"), content.map {
            it.jsonObject.getValue("type").jsonPrimitive.content
        })
        val source = content.single().jsonObject.getValue("source").jsonObject
        assertEquals("base64", source.getValue("type").jsonPrimitive.content)
        assertEquals(MCP_IMAGE_DATA, source.getValue("data").jsonPrimitive.content)
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
        val textOnlyContent = textOnly.getValue("messages").jsonArray.single()
            .jsonObject.getValue("content").jsonArray.single()
            .jsonObject.getValue("content").jsonArray
        assertEquals(listOf("text"), textOnlyContent.map {
            it.jsonObject.getValue("type").jsonPrimitive.content
        })
        assertEquals(
            "Tool completed without model-visible output.",
            textOnlyContent.single().jsonObject.getValue("text").jsonPrimitive.content,
        )
        assertFalse(textOnly.toString().contains(MCP_IMAGE_DATA))
        assertFalse(textOnly.toString().contains(UNSUPPORTED_IMAGE_DATA))
        assertFalse(textOnly.toString().contains(NULL_MIME_IMAGE_DATA))
        assertFalse(textOnly.toString().contains(USER_ONLY_IMAGE_DATA))
    }

    @Test
    fun manualThinkingValidatesBudgetAndTemperatureBeforeTransport() {
        assertFailsWith<IllegalArgumentException> {
            builder.build(request(config = AnthropicTransportConfig(thinkingMode = "enabled", thinkingBudgetTokens = 4_096)))
        }
        assertFailsWith<IllegalArgumentException> {
            builder.build(
                request(
                    config = AnthropicTransportConfig(thinkingMode = "adaptive"),
                    temperature = 0.5,
                ),
            )
        }
    }

    @Test
    fun imageAndPdfAreTypedWhileUnsupportedAttachmentFailsWithoutSecretEcho() {
        val payload = builder.build(
            request(
                messages = listOf(
                    AgentMessage(
                        role = MessageRole.USER,
                        parts = listOf(
                            AttachmentPart("data:image/png;base64,IMAGE_DATA", "image/png"),
                            AttachmentPart("https://example.invalid/report.pdf", "application/pdf"),
                        ),
                    ),
                ),
            ),
        )
        val content = payload["messages"]!!.jsonArray.single().jsonObject["content"]!!.jsonArray
        assertEquals(listOf("image", "document"), content.map { it.jsonObject["type"]!!.jsonPrimitive.content })

        val failure = assertFailsWith<ProviderProtocolException> {
            builder.build(
                request(
                    messages = listOf(
                        AgentMessage(
                            role = MessageRole.USER,
                            parts = listOf(AttachmentPart("data:text/plain;base64,SECRET_DATA", "text/plain")),
                        ),
                    ),
                ),
            )
        }
        assertFalse(failure.message.orEmpty().contains("SECRET_DATA"))

        val mismatch = assertFailsWith<ProviderProtocolException> {
            builder.build(
                request(
                    messages = listOf(
                        AgentMessage(
                            role = MessageRole.USER,
                            parts = listOf(
                                AttachmentPart(
                                    "data:image/png;base64,SECRET_IMAGE",
                                    "application/pdf",
                                ),
                            ),
                        ),
                    ),
                ),
            )
        }
        assertFalse(mismatch.message.orEmpty().contains("SECRET_IMAGE"))

        val declaredPdfWinsOverMisleadingUrlSuffix = builder.build(
            request(
                messages = listOf(
                    AgentMessage(
                        role = MessageRole.USER,
                        parts = listOf(
                            AttachmentPart(
                                "https://example.invalid/misleading.png",
                                "application/pdf",
                            ),
                        ),
                    ),
                ),
            ),
        )
        assertEquals(
            "document",
            declaredPdfWinsOverMisleadingUrlSuffix["messages"]!!
                .jsonArray
                .single()
                .jsonObject["content"]!!
                .jsonArray
                .single()
                .jsonObject["type"]!!
                .jsonPrimitive
                .content,
        )
    }

    @Test
    fun declaredAttachmentEnvelopeIsFullyEncodable() {
        val declared = ReferenceProviderInputCapabilities.anthropicMessages.attachmentMimeTypes
        val payload = builder.build(
            request(
                messages = listOf(
                    AgentMessage(
                        role = MessageRole.USER,
                        parts = declared.sorted().map { mime ->
                            AttachmentPart("data:$mime;base64,ATTACHMENT_DATA", mime)
                        },
                    ),
                ),
            ),
        )

        assertEquals(
            declared.size,
            payload["messages"]!!.jsonArray.single().jsonObject["content"]!!.jsonArray.size,
        )
    }

    @Test
    fun crossModelReplayUsesCanonicalTextAndToolUseBlocks() {
        val payload = builder.build(
            request(
                messages = listOf(
                    AgentMessage(
                        role = MessageRole.ASSISTANT,
                        parts = listOf(
                            TextPart("I will check."),
                            ToolCallPart(
                                toolCallId = "foreign-call-1",
                                toolName = "lookup",
                                arguments = buildJsonObject { put("query", "KMP") },
                                partial = false,
                            ),
                        ),
                        metadata = buildJsonObject {
                            put("provider", "openai")
                            put("model", "gpt-foreign")
                        },
                    ),
                ),
            ),
        )

        val content = payload["messages"]!!.jsonArray.single().jsonObject["content"]!!.jsonArray
        assertEquals(listOf("text", "tool_use"), content.map { it.jsonObject["type"]!!.jsonPrimitive.content })
        assertEquals("foreign-call-1", content[1].jsonObject["id"]!!.jsonPrimitive.content)
    }

    @Test
    fun interruptedReplayDropsReasoningAndKeepsAnswerText() {
        val payload = builder.build(
            request(
                messages = listOf(
                    AgentMessage(
                        role = MessageRole.ASSISTANT,
                        parts = listOf(
                            ReasoningPart("Half-finished reasoning."),
                            TextPart("Partial answer text."),
                        ),
                    ),
                ),
            ),
        )

        val content = payload["messages"]!!.jsonArray.single().jsonObject["content"]!!.jsonArray
        assertEquals(listOf("text"), content.map { it.jsonObject["type"]!!.jsonPrimitive.content })
        assertEquals("Partial answer text.", content[0].jsonObject["text"]!!.jsonPrimitive.content)
    }

    private fun request(
        messages: List<AgentMessage> = listOf(AgentMessage(role = MessageRole.USER, parts = listOf(TextPart("Hello")))),
        config: AnthropicTransportConfig? = null,
        temperature: Double? = null,
        inputModalities: Set<ModelInputModality> = setOf(ModelInputModality.TEXT),
    ): ProviderRequest = ProviderRequest(
        model = ModelDescriptor(
            "anthropic",
            "claude-contract",
            inputModalities = inputModalities,
        ),
        messages = messages,
        maxTokens = 4_096,
        temperature = temperature,
        typedConfig = config,
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
