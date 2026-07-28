package saien.magrathea.provider.openai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import saien.magrathea.core.MessageBlockPhase
import saien.magrathea.core.ReasoningContentKind
import saien.magrathea.core.ReasoningPart
import saien.magrathea.core.StopReason
import saien.magrathea.core.ToolCallPart
import saien.magrathea.provider.api.ProviderEvent
import saien.magrathea.provider.api.ProviderEventAssembler
import saien.magrathea.provider.api.ProviderContextLimitException
import saien.magrathea.provider.api.ProviderProtocolException

class OpenAiChatCompletionsCodecContractTest {
    @Test
    fun compatibleContextLengthFailureMapsToRecoverableTypedFailure() {
        val codec = OpenAiChatCompletionsCodec("openai", "compatible-model")

        assertFailsWith<ProviderContextLimitException> {
            codec.decodeNonStreaming(
                """{"error":{"code":"context_length_exceeded","message":"prompt is too long"}}""",
            )
        }
    }

    @Test
    fun streamingToolCallFinalizesOnceAndCarriesTerminalUsage() {
        val codec = OpenAiChatCompletionsCodec("openai", "compatible-model")
        val events = OPENAI_CHAT_TOOL_STREAM.flatMap { data ->
            codec.decodeServerSentEvent(null, data)?.events.orEmpty()
        }
        codec.finish()

        val start = events.filterIsInstance<ProviderEvent.ToolCallStart>().single().toolCall
        val deltas = events.filterIsInstance<ProviderEvent.ToolCallDelta>()
        val end = events.filterIsInstance<ProviderEvent.ToolCallEnd>().single().toolCall
        val completed = events.filterIsInstance<ProviderEvent.Completed>().single()

        assertEquals("call_weather_1", start.toolCallId)
        assertEquals(listOf("{\"city\":", "\"Shanghai\"}"), deltas.map(ProviderEvent.ToolCallDelta::delta))
        assertEquals("Shanghai", end.arguments.jsonObject["city"]?.jsonPrimitive?.content)
        assertFalse(end.partial)
        assertEquals(StopReason.TOOL_CALLS, completed.stopReason)
        assertEquals(10, completed.usage?.inputTokens)
        assertEquals(1, completed.usage?.reasoningTokens)
    }

    @Test
    fun nonStreamingTextAndReasoningUseCanonicalLifecycle() {
        val chunk = OpenAiChatCompletionsCodec("openai", "compatible-model")
            .decodeNonStreaming(OPENAI_CHAT_TEXT_RESPONSE)
        val message = requireNotNull(ProviderEventAssembler().apply(null, chunk.events))

        assertEquals(1, chunk.events.filterIsInstance<ProviderEvent.ReasoningStart>().size)
        assertEquals(1, chunk.events.filterIsInstance<ProviderEvent.TextStart>().size)
        assertEquals(StopReason.COMPLETED, message.stopReason)
        assertEquals(3, chunk.events.filterIsInstance<ProviderEvent.Completed>().single().usage?.reasoningTokens)
    }

    @Test
    fun normalizedReasoningDetailsRemainDistinctAndOpaqueStateIsNotRenderedAsText() {
        val response = """{
            "id":"chatcmpl_reasoning_details",
            "model":"compatible-model",
            "choices":[{
                "index":0,
                "message":{
                    "role":"assistant",
                    "content":"Final answer.",
                    "reasoning":"compatibility projection",
                    "reasoning_details":[
                        {"type":"reasoning.summary","summary":"Checked the constraints.","id":"r0","format":"anthropic-claude-v1","index":0},
                        {"type":"reasoning.text","text":"Visible reasoning.","signature":"sig-1","id":"r1","format":"anthropic-claude-v1","index":1},
                        {"type":"reasoning.encrypted","data":"opaque-data","id":"r2","format":"anthropic-claude-v1","index":2},
                        {"type":"reasoning.server_tool_call","tool_name":"openrouter:fusion","arguments":"{}","result":"{}","tool_call_id":"server-1","index":3}
                    ]
                },
                "finish_reason":"stop"
            }]
        }""".trimIndent()
        val chunk = OpenAiChatCompletionsCodec("openai", "compatible-model")
            .decodeNonStreaming(response)
        val message = requireNotNull(ProviderEventAssembler().apply(null, chunk.events))
        val reasoning = message.parts.filterIsInstance<ReasoningPart>()

        assertEquals(
            listOf(
                ReasoningContentKind.SUMMARY,
                ReasoningContentKind.TEXT,
                ReasoningContentKind.PROVIDER_DEFINED,
            ),
            reasoning.map(ReasoningPart::kind),
        )
        assertEquals(listOf("Checked the constraints.", "Visible reasoning.", ""), reasoning.map(ReasoningPart::text))
        assertEquals(listOf(false, false, true), reasoning.map(ReasoningPart::redacted))
        assertEquals("sig-1", reasoning[1].signature)
        assertEquals("opaque-data", reasoning[2].signature)
        assertEquals(
            4,
            message.metadata.getValue(OPENAI_CHAT_REASONING_DETAILS_METADATA).jsonArray.size,
        )
    }

    @Test
    fun streamingReasoningDetailsAssembleByIndexAndFinalizeBeforeDone() {
        val stream = listOf(
            """{"id":"chatcmpl_reasoning_stream","model":"compatible-model","choices":[{"index":0,"delta":{"role":"assistant","reasoning_details":[{"type":"reasoning.text","text":"Think ","id":"r0","format":"anthropic-claude-v1","index":0}]},"finish_reason":null}]}""",
            """{"id":"chatcmpl_reasoning_stream","model":"compatible-model","choices":[{"index":0,"delta":{"reasoning_details":[{"type":"reasoning.text","text":"carefully.","signature":"sig-final","id":"r0","format":"anthropic-claude-v1","index":0}]},"finish_reason":null}]}""",
            """{"id":"chatcmpl_reasoning_stream","model":"compatible-model","choices":[{"index":0,"delta":{"content":"Done."},"finish_reason":null}]}""",
            """{"id":"chatcmpl_reasoning_stream","model":"compatible-model","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}""",
            "[DONE]",
        )
        val codec = OpenAiChatCompletionsCodec("openai", "compatible-model")
        val events = stream.flatMap { payload ->
            codec.decodeServerSentEvent(null, payload)?.events.orEmpty()
        }
        codec.finish()
        val message = requireNotNull(ProviderEventAssembler().apply(null, events))
        val reasoning = message.parts.filterIsInstance<ReasoningPart>().single()

        assertEquals("Think carefully.", reasoning.text)
        assertEquals("sig-final", reasoning.signature)
        assertEquals(ReasoningContentKind.TEXT, reasoning.kind)
        assertEquals(MessageBlockPhase.FINAL, reasoning.phase)
        assertEquals(
            "Think carefully.",
            message.metadata.getValue(OPENAI_CHAT_REASONING_DETAILS_METADATA)
                .jsonArray.single().jsonObject.getValue("text").jsonPrimitive.content,
        )
    }

    @Test
    fun missingDoneMalformedArgumentsAndIdentityChangesFailClosed() {
        val incomplete = OpenAiChatCompletionsCodec("openai", "compatible-model")
        OPENAI_CHAT_TOOL_STREAM.dropLast(1).forEach { incomplete.decodeServerSentEvent(null, it) }
        assertFailsWith<ProviderProtocolException> { incomplete.finish() }

        val changed = OpenAiChatCompletionsCodec("openai", "compatible-model")
        changed.decodeServerSentEvent(null, OPENAI_CHAT_TOOL_STREAM.first())
        assertFailsWith<ProviderProtocolException> {
            changed.decodeServerSentEvent(
                null,
                OPENAI_CHAT_TOOL_STREAM[1].replace("chatcmpl_tool_1", "chatcmpl_other"),
            )
        }

        assertFailsWith<ProviderProtocolException> {
            OpenAiChatCompletionsCodec("openai", "compatible-model").decodeNonStreaming(
                OPENAI_CHAT_TOOL_RESPONSE.replace("""{\"city\":\"Shanghai\"}""", "not-json"),
            )
        }
    }
}
