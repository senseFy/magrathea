package saien.magrathea.provider.anthropic

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import saien.magrathea.core.AgentMessage
import saien.magrathea.core.AttachmentPart
import saien.magrathea.core.MessageRole
import saien.magrathea.core.ModelDescriptor
import saien.magrathea.core.TextPart
import saien.magrathea.core.ToolCallPart
import saien.magrathea.core.ToolResultPart
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
        assertEquals("toolu_weather_1", messages[2].jsonObject["content"]!!.jsonArray.single().jsonObject["tool_use_id"]!!.jsonPrimitive.content)
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

    private fun request(
        messages: List<AgentMessage> = listOf(AgentMessage(role = MessageRole.USER, parts = listOf(TextPart("Hello")))),
        config: AnthropicTransportConfig? = null,
        temperature: Double? = null,
    ): ProviderRequest = ProviderRequest(
        model = ModelDescriptor("anthropic", "claude-contract"),
        messages = messages,
        maxTokens = 4_096,
        temperature = temperature,
        typedConfig = config,
    )
}
