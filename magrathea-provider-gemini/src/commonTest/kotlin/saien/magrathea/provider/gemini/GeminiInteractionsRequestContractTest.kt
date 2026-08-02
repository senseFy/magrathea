package saien.magrathea.provider.gemini

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
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
import saien.magrathea.core.TextPart
import saien.magrathea.core.ToolDefinition
import saien.magrathea.core.ToolResultAudience
import saien.magrathea.core.ToolResultImageContent
import saien.magrathea.core.ToolResultPart
import saien.magrathea.core.ToolResultTextContent
import saien.magrathea.provider.api.GeminiTransportConfig
import saien.magrathea.provider.api.ProviderProtocolException
import saien.magrathea.provider.api.ProviderRequest
import saien.magrathea.provider.api.ReferenceProviderInputCapabilities

class GeminiInteractionsRequestContractTest {
    private val builder = GeminiInteractionsRequestBuilder()

    @Test
    fun requestIsStatelessStableV1ShapeWithToolsAndGenerationConfig() {
        val payload = builder.build(
            ProviderRequest(
                model = ModelDescriptor("gemini", "models/gemini-contract-model", supportsStreaming = true),
                messages = listOf(
                    AgentMessage(role = MessageRole.SYSTEM, parts = listOf(TextPart("Be concise."))),
                    AgentMessage(role = MessageRole.USER, parts = listOf(TextPart("Weather?"))),
                ),
                tools = listOf(ToolDefinition("get_weather", "Gets weather", buildJsonObject { put("type", "object") })),
                temperature = 0.2,
                maxTokens = 128,
                typedConfig = GeminiTransportConfig(thinkingLevel = "low", thinkingSummaries = "auto"),
            ),
        )

        assertEquals("gemini-contract-model", payload["model"]?.jsonPrimitive?.content)
        assertEquals(false, payload["store"]?.jsonPrimitive?.content?.toBoolean())
        assertEquals(true, payload["stream"]?.jsonPrimitive?.content?.toBoolean())
        assertEquals("Be concise.", payload["system_instruction"]?.jsonPrimitive?.content)
        assertEquals("function", payload["tools"]!!.jsonArray.single().jsonObject["type"]!!.jsonPrimitive.content)
        val generation = payload["generation_config"]!!.jsonObject
        assertEquals("low", generation["thinking_level"]!!.jsonPrimitive.content)
        assertEquals("auto", generation["thinking_summaries"]!!.jsonPrimitive.content)
    }

    @Test
    fun statelessHistoryPreservesRawModelStepsAndOrdersFunctionResult() {
        val authoritativeSteps = JsonArray(
            listOf(
                buildJsonObject {
                    put("type", "function_call")
                    put("id", "call-1")
                    put("name", "lookup")
                    put("arguments", buildJsonObject { put("q", "KMP") })
                },
            ),
        )
        val payload = builder.build(
            ProviderRequest(
                model = ModelDescriptor("gemini", "gemini-contract-model"),
                messages = listOf(
                    AgentMessage(role = MessageRole.USER, parts = listOf(TextPart("Search"))),
                    AgentMessage(
                        role = MessageRole.ASSISTANT,
                        parts = emptyList(),
                        metadata = buildJsonObject { put(GEMINI_INTERACTION_STEPS_METADATA, authoritativeSteps) },
                    ),
                    AgentMessage(
                        role = MessageRole.TOOL,
                        parts = listOf(ToolResultPart("call-1", "lookup", buildJsonObject { put("answer", "KMP") })),
                    ),
                    AgentMessage(role = MessageRole.USER, parts = listOf(TextPart("Summarize"))),
                ),
            ),
        )

        val input = payload["input"]!!.jsonArray
        assertEquals(listOf("user_input", "function_call", "function_result", "user_input"), input.map { it.jsonObject["type"]!!.jsonPrimitive.content })
        assertEquals(authoritativeSteps.single(), input[1])
        assertEquals("call-1", input[2].jsonObject["call_id"]!!.jsonPrimitive.content)
        assertEquals(
            "{\"answer\":\"KMP\"}",
            input[2].jsonObject.getValue("result").jsonArray.single()
                .jsonObject.getValue("text").jsonPrimitive.content,
        )
    }

    @Test
    fun functionResultComposesCanonicalAndTypedContentWithNativeErrorFlags() {
        val payload = builder.build(
            ProviderRequest(
                model = ModelDescriptor("gemini", "gemini-contract-model"),
                messages = listOf(
                    AgentMessage(
                        role = MessageRole.TOOL,
                        parts = listOf(
                            ToolResultPart(
                                toolCallId = "call-both",
                                toolName = "lookup",
                                result = buildJsonObject { put("structured", "canonical") },
                                isError = true,
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

        val steps = payload.getValue("input").jsonArray.map { it.jsonObject }
        assertTrue(steps.all { step ->
            step.getValue("is_error").jsonPrimitive.content.toBoolean()
        })
        val results = steps.map { step ->
            step.getValue("result").jsonArray.map { content ->
                content.jsonObject.getValue("text").jsonPrimitive.content
            }
        }
        assertEquals(
            listOf("{\"structured\":\"canonical\"}", "model summary"),
            results[0],
        )
        assertEquals(
            listOf("Tool failed without model-visible error details."),
            results[1],
        )
        assertFalse(payload.toString().contains("USER_ONLY_SECRET"))
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
            ProviderRequest(
                model = ModelDescriptor(
                    "gemini",
                    "gemini-contract-model",
                    inputModalities = setOf(ModelInputModality.TEXT, ModelInputModality.IMAGE),
                ),
                messages = listOf(
                    AgentMessage(
                        role = MessageRole.TOOL,
                        parts = listOf(toolResult),
                    ),
                ),
            ),
        )

        val result = payload.getValue("input").jsonArray.single()
            .jsonObject.getValue("result").jsonArray
        assertEquals(listOf("image"), result.map {
            it.jsonObject.getValue("type").jsonPrimitive.content
        })
        assertEquals(MCP_IMAGE_DATA, result.single().jsonObject.getValue("data").jsonPrimitive.content)
        assertEquals(1, payload.toString().countOccurrences(MCP_IMAGE_DATA))
        assertFalse(payload.toString().contains(UNSUPPORTED_IMAGE_DATA))
        assertFalse(payload.toString().contains(NULL_MIME_IMAGE_DATA))
        assertFalse(payload.toString().contains(USER_ONLY_IMAGE_DATA))

        val textOnly = builder.build(
            ProviderRequest(
                model = ModelDescriptor("gemini", "gemini-contract-model"),
                messages = listOf(
                    AgentMessage(role = MessageRole.TOOL, parts = listOf(toolResult)),
                ),
            ),
        )
        val textOnlyResult = textOnly.getValue("input").jsonArray.single()
            .jsonObject.getValue("result").jsonArray
        assertEquals(listOf("text"), textOnlyResult.map {
            it.jsonObject.getValue("type").jsonPrimitive.content
        })
        assertEquals(
            "Tool completed without model-visible output.",
            textOnlyResult.single().jsonObject.getValue("text").jsonPrimitive.content,
        )
        assertFalse(textOnly.toString().contains(MCP_IMAGE_DATA))
        assertFalse(textOnly.toString().contains(UNSUPPORTED_IMAGE_DATA))
        assertFalse(textOnly.toString().contains(NULL_MIME_IMAGE_DATA))
        assertFalse(textOnly.toString().contains(USER_ONLY_IMAGE_DATA))
    }

    @Test
    fun assistantWithoutAuthoritativeStepsIsRejectedInsteadOfLossilyReconstructed() {
        assertFailsWith<ProviderProtocolException> {
            builder.build(
                ProviderRequest(
                    model = ModelDescriptor("gemini", "gemini-contract-model"),
                    messages = listOf(AgentMessage(role = MessageRole.ASSISTANT, parts = listOf(TextPart("display-only")))),
                ),
            )
        }
    }

    @Test
    fun imageAndDocumentAttachmentsUseTypedContentWithoutDataUrlPrefix() {
        val payload = builder.build(
            ProviderRequest(
                model = ModelDescriptor("gemini", "gemini-contract-model"),
                messages = listOf(
                    AgentMessage(
                        role = MessageRole.USER,
                        parts = listOf(
                            AttachmentPart("data:image/png;base64,IMAGE_BYTES", "image/png"),
                            AttachmentPart("https://example.invalid/document.pdf", "application/pdf"),
                        ),
                    ),
                ),
            ),
        )

        val content = payload["input"]!!.jsonArray.single().jsonObject["content"]!!.jsonArray
        assertEquals("image", content[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("IMAGE_BYTES", content[0].jsonObject["data"]!!.jsonPrimitive.content)
        assertFalse(content[0].toString().contains("data:image"))
        assertEquals("document", content[1].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("https://example.invalid/document.pdf", content[1].jsonObject["uri"]!!.jsonPrimitive.content)
    }

    @Test
    fun declaredAttachmentEnvelopeIsFullyEncodableAndAliasesUseWireMimeTypes() {
        val declared = ReferenceProviderInputCapabilities.geminiInteractions.attachmentMimeTypes
            .sorted()
        val payload = builder.build(
            ProviderRequest(
                model = ModelDescriptor("gemini", "gemini-contract-model"),
                messages = listOf(
                    AgentMessage(
                        role = MessageRole.USER,
                        parts = declared.map { mime ->
                            AttachmentPart("data:$mime;base64,ATTACHMENT_DATA", mime)
                        },
                    ),
                ),
            ),
        )
        val content = payload["input"]!!.jsonArray.single().jsonObject["content"]!!.jsonArray

        assertEquals(declared.size, content.size)
        assertEquals(
            "text/csv",
            content[declared.indexOf("application/csv")].jsonObject["mime_type"]!!.jsonPrimitive.content,
        )
        assertEquals(
            "audio/m4a",
            content[declared.indexOf("audio/mp4")].jsonObject["mime_type"]!!.jsonPrimitive.content,
        )
        assertEquals(
            "video/mov",
            content[declared.indexOf("video/quicktime")].jsonObject["mime_type"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun invalidAttachmentFailsWithoutEchoingPayload() {
        val secret = "SECRET_ATTACHMENT"
        val unsupported = assertFailsWith<ProviderProtocolException> {
            builder.build(
                ProviderRequest(
                    model = ModelDescriptor("gemini", "gemini-contract-model"),
                    messages = listOf(
                        AgentMessage(
                            role = MessageRole.USER,
                            parts = listOf(
                                AttachmentPart("data:application/zip;base64,$secret", "application/zip"),
                            ),
                        ),
                    ),
                ),
            )
        }
        assertFalse(unsupported.message.orEmpty().contains(secret))

        val mismatch = assertFailsWith<ProviderProtocolException> {
            GeminiAttachmentCodec.encode(
                AttachmentPart("data:text/csv;base64,$secret", "application/pdf"),
            )
        }
        assertFalse(mismatch.message.orEmpty().contains(secret))
    }

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
