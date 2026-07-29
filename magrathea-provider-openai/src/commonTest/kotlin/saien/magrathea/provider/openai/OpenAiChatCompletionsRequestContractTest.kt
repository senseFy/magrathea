package saien.magrathea.provider.openai

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
import saien.magrathea.core.ReasoningContentKind
import saien.magrathea.core.ReasoningPart
import saien.magrathea.core.TextPart
import saien.magrathea.core.ToolCallPart
import saien.magrathea.core.ToolDefinition
import saien.magrathea.core.ToolResultPart
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
    ): ProviderRequest = ProviderRequest(
        model = ModelDescriptor("openai", "compatible-model", supportsStreaming = true),
        messages = messages,
        tools = tools,
        typedConfig = typedConfig,
    )
}
