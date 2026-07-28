package saien.magrathea.provider.anthropic

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import saien.magrathea.core.MessageBlockPhase
import saien.magrathea.core.ReasoningPart
import saien.magrathea.core.StopReason
import saien.magrathea.core.ToolCallPart
import saien.magrathea.provider.api.ProviderEvent
import saien.magrathea.provider.api.ProviderEventAssembler
import saien.magrathea.provider.api.ProviderContextLimitException
import saien.magrathea.provider.api.ProviderProtocolException
import saien.magrathea.provider.api.ProviderServerException

class AnthropicMessagesCodecContractTest {
    @Test
    fun contextLengthFailureMapsToRecoverableTypedFailure() {
        val codec = AnthropicMessagesCodec("anthropic", "claude-contract")

        assertFailsWith<ProviderContextLimitException> {
            codec.decodeServerSentEvent(
                "error",
                """{"type":"error","error":{"type":"invalid_request_error","message":"prompt is too long"}}""",
            )
        }
    }

    @Test
    fun officialNamedSseMapsBlockIndexToToolIdAndFinalizesAtBlockStop() {
        val codec = AnthropicMessagesCodec("anthropic", "claude-contract")
        val events = ANTHROPIC_TOOL_STREAM.flatMap { (event, data) ->
            codec.decodeServerSentEvent(event, data)?.events.orEmpty()
        }
        codec.finish()

        val start = events.filterIsInstance<ProviderEvent.ToolCallStart>().single().toolCall
        val deltas = events.filterIsInstance<ProviderEvent.ToolCallDelta>()
        val end = events.filterIsInstance<ProviderEvent.ToolCallEnd>().single().toolCall
        val completed = events.filterIsInstance<ProviderEvent.Completed>().single()

        assertEquals("toolu_weather_1", start.toolCallId)
        assertEquals(listOf("toolu_weather_1", "toolu_weather_1", "toolu_weather_1"), deltas.map { it.toolCallId })
        assertEquals("Shanghai", end.arguments.jsonObject["city"]?.jsonPrimitive?.content)
        assertFalse(end.partial)
        assertEquals(StopReason.TOOL_CALLS, completed.stopReason)
        assertEquals(2, completed.usage?.reasoningTokens)
    }

    @Test
    fun thinkingSignatureDeltaIsPreservedUntilBlockStop() {
        val stream = listOf(
            "message_start" to """{"type":"message_start","message":{"id":"msg_reasoning","role":"assistant","content":[],"usage":{"input_tokens":3,"output_tokens":1}}}""",
            "content_block_start" to """{"type":"content_block_start","index":0,"content_block":{"type":"thinking","thinking":"","signature":""}}""",
            "content_block_delta" to """{"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"I should check."}}""",
            "content_block_delta" to """{"type":"content_block_delta","index":0,"delta":{"type":"signature_delta","signature":"sig-reasoning-1"}}""",
            "content_block_stop" to """{"type":"content_block_stop","index":0}""",
            "content_block_start" to """{"type":"content_block_start","index":1,"content_block":{"type":"text","text":""}}""",
            "content_block_delta" to """{"type":"content_block_delta","index":1,"delta":{"type":"text_delta","text":"Done."}}""",
            "content_block_stop" to """{"type":"content_block_stop","index":1}""",
            "message_delta" to """{"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":4,"output_tokens_details":{"thinking_tokens":2}}}""",
            "message_stop" to """{"type":"message_stop"}""",
        )
        val codec = AnthropicMessagesCodec("anthropic", "claude-contract")
        val events = stream.flatMap { (event, data) -> codec.decodeServerSentEvent(event, data)?.events.orEmpty() }

        val end = events.filterIsInstance<ProviderEvent.ReasoningEnd>().single()
        assertEquals("I should check.", end.text)
        assertEquals("sig-reasoning-1", end.signature)
        val reasoning = requireNotNull(ProviderEventAssembler().apply(null, events))
            .parts.filterIsInstance<ReasoningPart>().single()
        assertEquals(MessageBlockPhase.FINAL, reasoning.phase)
    }

    @Test
    fun redactedThinkingIsOpaqueContinuityStateWithoutVisibleText() {
        val response = """{
            "id":"msg_redacted",
            "role":"assistant",
            "model":"claude-contract",
            "content":[{"type":"redacted_thinking","data":"opaque-redacted-state"}],
            "stop_reason":"end_turn",
            "usage":{"input_tokens":3,"output_tokens":2}
        }""".trimIndent()
        val chunk = AnthropicMessagesCodec("anthropic", "claude-contract")
            .decodeNonStreaming(response)
        val reasoning = requireNotNull(ProviderEventAssembler().apply(null, chunk.events))
            .parts.filterIsInstance<ReasoningPart>().single()

        assertEquals("", reasoning.text)
        assertEquals("opaque-redacted-state", reasoning.signature)
        assertEquals(true, reasoning.redacted)
        assertEquals(MessageBlockPhase.FINAL, reasoning.phase)
    }

    @Test
    fun inputDeltaBeforeToolStartAndMissingTerminalFailClosed() {
        val noStart = AnthropicMessagesCodec("anthropic", "claude-contract")
        val start = ANTHROPIC_TOOL_STREAM.first()
        noStart.decodeServerSentEvent(start.first, start.second)
        assertFailsWith<ProviderProtocolException> {
            noStart.decodeServerSentEvent(
                "content_block_delta",
                """{"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"{}"}}""",
            )
        }

        val incomplete = AnthropicMessagesCodec("anthropic", "claude-contract")
        ANTHROPIC_TOOL_STREAM.dropLast(1).forEach { (event, data) -> incomplete.decodeServerSentEvent(event, data) }
        assertFailsWith<ProviderProtocolException> { incomplete.finish() }
    }

    @Test
    fun streamErrorMapsToTypedFailureWithoutProviderMessage() {
        val codec = AnthropicMessagesCodec("anthropic", "claude-contract")
        val failure = assertFailsWith<ProviderServerException> {
            codec.decodeServerSentEvent(
                "error",
                """{"type":"error","error":{"type":"overloaded_error","message":"secret upstream detail"}}""",
            )
        }
        assertEquals(529, failure.statusCode)
        assertFalse(failure.message.orEmpty().contains("secret upstream detail"))
    }

    @Test
    fun optionalDoneSentinelIsAcceptedOnlyOnceAfterMessageStop() {
        val codec = AnthropicMessagesCodec("anthropic", "claude-contract")
        ANTHROPIC_TEXT_STREAM.forEach { (event, data) -> codec.decodeServerSentEvent(event, data) }

        assertEquals(null, codec.decodeServerSentEvent("data", "[DONE]"))
        codec.finish()

        assertFailsWith<ProviderProtocolException> {
            codec.decodeServerSentEvent("data", "[DONE]")
        }

        val premature = AnthropicMessagesCodec("anthropic", "claude-contract")
        assertFailsWith<ProviderProtocolException> {
            premature.decodeServerSentEvent("data", "[DONE]")
        }
    }

    @Test
    fun terminalUsageCanReplaceProvisionalInputTokenCount() {
        val stream = ANTHROPIC_TEXT_STREAM.map { (event, data) ->
            if (event == "message_delta") {
                event to """{"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"input_tokens":20,"output_tokens":5}}"""
            } else {
                event to data.replace("\"input_tokens\":20", "\"input_tokens\":0")
            }
        }
        val codec = AnthropicMessagesCodec("anthropic", "claude-contract")
        val events = stream.flatMap { (event, data) ->
            codec.decodeServerSentEvent(event, data)?.events.orEmpty()
        }
        val completed = events.filterIsInstance<ProviderEvent.Completed>().single()

        assertEquals(20, completed.usage?.inputTokens)
    }

    @Test
    fun nonStreamingUsesCanonicalEventsAndAuthoritativeContentMetadata() {
        val chunk = AnthropicMessagesCodec("anthropic", "claude-contract").decodeNonStreaming(ANTHROPIC_TOOL_RESPONSE)
        val message = requireNotNull(ProviderEventAssembler().apply(null, chunk.events))

        assertEquals(1, chunk.events.filterIsInstance<ProviderEvent.ToolCallEnd>().size)
        assertEquals(1, chunk.events.filterIsInstance<ProviderEvent.Completed>().size)
        assertFalse(message.parts.filterIsInstance<ToolCallPart>().single().partial)
        assertEquals(StopReason.TOOL_CALLS, message.stopReason)
    }
}
