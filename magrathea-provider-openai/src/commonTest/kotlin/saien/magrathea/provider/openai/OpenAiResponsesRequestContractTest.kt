package saien.magrathea.provider.openai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
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
import saien.magrathea.provider.api.OpenAiTransportConfig
import saien.magrathea.provider.api.OpenAiXSearchToolConfig
import saien.magrathea.provider.api.ProviderProtocolException
import saien.magrathea.provider.api.ProviderRequest
import saien.magrathea.provider.api.ReferenceProviderInputCapabilities

class OpenAiResponsesRequestContractTest {
    private val builder = OpenAiResponsesRequestBuilder("openai")

    @Test
    fun sameModelReplayPreservesAuthoritativeTopLevelItemsAndFunctionOutput() {
        val authoritative = Json.parseToJsonElement("[$OPENAI_TOOL_ITEM]").jsonArray
        val payload = builder.build(
            request(
                messages = listOf(
                    AgentMessage(role = MessageRole.USER, parts = listOf(TextPart("Weather?"))),
                    AgentMessage(
                        role = MessageRole.ASSISTANT,
                        parts = emptyList(),
                        metadata = buildJsonObject {
                            put("provider", "openai")
                            put("model", "gpt-contract")
                            put(OPENAI_RESPONSE_OUTPUT_METADATA, authoritative)
                        },
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
            ),
        )

        val input = payload["input"]!!.jsonArray
        assertEquals(listOf("user", "function_call", "function_call_output"), input.map {
            it.jsonObject["type"]?.jsonPrimitive?.content ?: it.jsonObject["role"]!!.jsonPrimitive.content
        })
        assertEquals(authoritative.single(), input[1])
        assertEquals("call_weather_1", input[2].jsonObject["call_id"]!!.jsonPrimitive.content)
    }

    @Test
    fun reasoningConfigurationUsesStatelessEncryptedContentInclude() {
        val payload = builder.build(
            request(
                typedConfig = OpenAiTransportConfig(
                    instructions = "Answer briefly",
                    reasoningEffort = "high",
                    reasoningSummary = "auto",
                ),
                reasoning = true,
            ),
        )

        assertEquals("Answer briefly", payload["instructions"]!!.jsonPrimitive.content)
        assertEquals("high", payload["reasoning"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
        assertEquals("reasoning.encrypted_content", payload["include"]!!.jsonArray.single().jsonPrimitive.content)
        assertFalse(payload.containsKey("api_mode"))
    }

    @Test
    fun xSearchHostedToolUsesTheDocumentedResponsesWireShape() {
        val payload = builder.build(
            request(
                typedConfig = OpenAiTransportConfig(
                    hostedTools = listOf(
                        OpenAiXSearchToolConfig(
                            allowedHandles = listOf("xai", "kotlin"),
                            fromDate = "2026-07-01",
                            toDate = "2026-07-16",
                            enableImageUnderstanding = true,
                            enableVideoUnderstanding = true,
                        ),
                    ),
                    maxToolTurns = 3,
                ),
            ),
        )

        val tool = payload.getValue("tools").jsonArray.single().jsonObject
        assertEquals("x_search", tool.getValue("type").jsonPrimitive.content)
        assertEquals(
            listOf("xai", "kotlin"),
            tool.getValue("allowed_x_handles").jsonArray.map { it.jsonPrimitive.content },
        )
        assertEquals("2026-07-01", tool.getValue("from_date").jsonPrimitive.content)
        assertEquals("2026-07-16", tool.getValue("to_date").jsonPrimitive.content)
        assertEquals(true, tool.getValue("enable_image_understanding").jsonPrimitive.content.toBoolean())
        assertEquals(true, tool.getValue("enable_video_understanding").jsonPrimitive.content.toBoolean())
        assertEquals(3, payload.getValue("max_turns").jsonPrimitive.content.toInt())
        assertEquals("auto", payload.getValue("tool_choice").jsonPrimitive.content)
    }

    @Test
    fun imageAndFileAttachmentsUseTheirCanonicalInputTypes() {
        val payload = builder.build(
            request(
                messages = listOf(
                    AgentMessage(
                        role = MessageRole.USER,
                        parts = listOf(
                            AttachmentPart("data:image/png;base64,IMAGE_DATA", "image/png"),
                            AttachmentPart(
                                uri = "data:application/pdf;base64,PDF_DATA",
                                mimeType = "application/pdf",
                                fileName = "brief.pdf",
                            ),
                            AttachmentPart(
                                uri = "https://files.example.test/data.csv",
                                mimeType = "text/csv",
                                fileName = "data.csv",
                            ),
                        ),
                    ),
                ),
            ),
        )
        val parts = payload["input"]!!.jsonArray.single().jsonObject["content"]!!.jsonArray
            .map { it.jsonObject }

        assertEquals("input_image", parts[0]["type"]!!.jsonPrimitive.content)
        assertEquals("input_file", parts[1]["type"]!!.jsonPrimitive.content)
        assertEquals(
            "data:application/pdf;base64,PDF_DATA",
            parts[1]["file_data"]!!.jsonPrimitive.content,
        )
        assertEquals("brief.pdf", parts[1]["filename"]!!.jsonPrimitive.content)
        assertEquals("input_file", parts[2]["type"]!!.jsonPrimitive.content)
        assertEquals(
            "https://files.example.test/data.csv",
            parts[2]["file_url"]!!.jsonPrimitive.content,
        )
        assertEquals("data.csv", parts[2]["filename"]!!.jsonPrimitive.content)
    }

    @Test
    fun invalidFileAttachmentFailsWithoutEchoingData() {
        val secret = "SECRET_PDF"

        val failure = assertFailsWith<ProviderProtocolException> {
            builder.build(
                request(
                    messages = listOf(
                        AgentMessage(
                            role = MessageRole.USER,
                            parts = listOf(
                                AttachmentPart(
                                    uri = "data:application/pdf;base64,$secret",
                                    mimeType = "text/csv",
                                    fileName = "brief.pdf",
                                ),
                            ),
                        ),
                    ),
                ),
            )
        }
        assertFalse(failure.message.orEmpty().contains(secret))

        val imageClassificationBypass = assertFailsWith<ProviderProtocolException> {
            builder.build(
                request(
                    messages = listOf(
                        AgentMessage(
                            role = MessageRole.USER,
                            parts = listOf(
                                AttachmentPart(
                                    uri = "data:image/png;base64,$secret",
                                    mimeType = "application/pdf",
                                    fileName = "brief.pdf",
                                ),
                            ),
                        ),
                    ),
                ),
            )
        }
        assertFalse(imageClassificationBypass.message.orEmpty().contains(secret))

        val invalidUri = assertFailsWith<ProviderProtocolException> {
            builder.build(
                request(
                    messages = listOf(
                        AgentMessage(
                            role = MessageRole.USER,
                            parts = listOf(
                                AttachmentPart(
                                    uri = "magrathea-attachment:secret-file",
                                    mimeType = "application/pdf",
                                    fileName = "brief.pdf",
                                ),
                            ),
                        ),
                    ),
                ),
            )
        }
        assertFalse(invalidUri.message.orEmpty().contains("secret-file"))

        val unsupportedType = assertFailsWith<ProviderProtocolException> {
            builder.build(
                request(
                    messages = listOf(
                        AgentMessage(
                            role = MessageRole.USER,
                            parts = listOf(
                                AttachmentPart(
                                    uri = "data:audio/mpeg;base64,$secret",
                                    mimeType = "audio/mpeg",
                                    fileName = "recording.mp3",
                                ),
                            ),
                        ),
                    ),
                ),
            )
        }
        assertFalse(unsupportedType.message.orEmpty().contains(secret))
    }

    @Test
    fun declaredAttachmentEnvelopeIsFullyEncodable() {
        val declared = ReferenceProviderInputCapabilities.openAiResponses.attachmentMimeTypes +
            "text/plain"
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
            payload["input"]!!.jsonArray.single().jsonObject["content"]!!.jsonArray.size,
        )
    }

    @Test
    fun sameModelAssistantWithoutAuthoritativeItemsFailsClosed() {
        assertFailsWith<ProviderProtocolException> {
            builder.build(
                request(
                    messages = listOf(
                        AgentMessage(
                            role = MessageRole.ASSISTANT,
                            parts = listOf(TextPart("reconstructed")),
                            metadata = buildJsonObject {
                                put("provider", "openai")
                                put("model", "gpt-contract")
                            },
                        ),
                    ),
                ),
            )
        }
    }

    @Test
    fun crossModelReplayUsesCanonicalTextAndTopLevelFunctionCall() {
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
                            put("provider", "anthropic")
                            put("model", "claude-foreign")
                        },
                    ),
                ),
            ),
        )

        val input = payload["input"]!!.jsonArray
        assertEquals("assistant", input[0].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals("function_call", input[1].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("foreign-call-1", input[1].jsonObject["call_id"]!!.jsonPrimitive.content)
    }

    private fun request(
        messages: List<AgentMessage> = listOf(AgentMessage(role = MessageRole.USER, parts = listOf(TextPart("Hello")))),
        typedConfig: OpenAiTransportConfig? = null,
        reasoning: Boolean = false,
    ): ProviderRequest = ProviderRequest(
        model = ModelDescriptor("openai", "gpt-contract", supportsReasoning = reasoning),
        messages = messages,
        typedConfig = typedConfig,
    )
}
