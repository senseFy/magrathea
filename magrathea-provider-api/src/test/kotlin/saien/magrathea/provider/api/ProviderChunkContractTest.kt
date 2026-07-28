package saien.magrathea.provider.api

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import saien.magrathea.core.AgentMessage
import saien.magrathea.core.AgentModelFactory
import saien.magrathea.core.EpochClock
import saien.magrathea.core.IdGenerator
import saien.magrathea.core.MessageRole
import saien.magrathea.core.MessageBlockPhase
import saien.magrathea.core.ReasoningContentKind
import saien.magrathea.core.ReasoningPart
import saien.magrathea.core.StopReason
import saien.magrathea.core.TextPart
import saien.magrathea.core.ToolCallLifecycle
import saien.magrathea.core.ToolCallPart

class ProviderChunkContractTest {
    @Test
    fun providerEvent_isSingleCanonicalRepresentation() {
        ProviderChunk(
            events = listOf(
                ProviderEvent.TextDelta("hello"),
                ProviderEvent.Completed(
                    finishReason = "STOP",
                    stopReason = StopReason.COMPLETED,
                    usage = ProviderUsage(inputTokens = 1, outputTokens = 2),
                ),
            ),
        ).validateSemantics()
    }

    @Test
    fun emptyChunk_isRejected() {
        try {
            ProviderChunk().validateSemantics()
            throw AssertionError("Expected validation to fail")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun completedEvent_mustBeUniqueAndFinal() {
        val invalid = listOf(
            ProviderChunk(events = listOf(ProviderEvent.Completed("STOP"), ProviderEvent.TextDelta("late"))),
            ProviderChunk(events = listOf(ProviderEvent.Completed("STOP"), ProviderEvent.Completed("STOP"))),
        )

        invalid.forEach { chunk ->
            try {
                chunk.validateSemantics()
                throw AssertionError("Expected terminal validation to fail: $chunk")
            } catch (_: IllegalArgumentException) {
            }
        }
    }

    @Test
    fun toolCallEnd_finalizesPartialArgumentsByStableId() {
        val callId = "tool_call_index_0"
        val message = ProviderEventAssembler().apply(
            previous = null,
            events = listOf(
                ProviderEvent.ToolCallStart(
                    ToolCallPart(
                        toolCallId = callId,
                        toolName = "tool.time",
                        arguments = buildJsonObject { put("partial_json", JsonPrimitive("")) },
                        partial = true,
                        providerCallId = "call-1",
                    ),
                ),
                ProviderEvent.ToolCallDelta(callId, "{\"input\":\"now\"}"),
                ProviderEvent.ToolCallEnd(
                    ToolCallPart(
                        toolCallId = callId,
                        toolName = "tool.time",
                        arguments = buildJsonObject { put("input", "now") },
                    ),
                ),
                ProviderEvent.Completed(stopReason = StopReason.TOOL_CALLS),
            ),
        )

        val call = requireNotNull(message).parts.filterIsInstance<ToolCallPart>().single()

        assertEquals("tool.time", call.toolName)
        assertEquals("call-1", call.providerCallId)
        assertEquals("now", call.arguments.jsonObject["input"]?.jsonPrimitive?.content)
        assertEquals(StopReason.TOOL_CALLS, message.stopReason)
    }

    @Test
    fun completedEventWithoutAnyOutput_isValid() {
        ProviderChunk(events = listOf(ProviderEvent.Completed(stopReason = StopReason.COMPLETED))).validateSemantics()
    }

    @Test
    fun textEvent_isValidWithoutTerminalInSameChunk() {
        ProviderChunk(events = listOf(ProviderEvent.TextDelta("partial"))).validateSemantics()
    }

    @Test
    fun `tool call lifecycle derives from partial flag`() {
        assertEquals(
            ToolCallLifecycle.DELTA,
            ToolCallPart("call-1", "search", JsonPrimitive("{}"), partial = true).lifecycle(),
        )
        assertEquals(
            ToolCallLifecycle.FINALIZED,
            ToolCallPart("call-1", "search", JsonPrimitive("{}"), partial = false).lifecycle(),
        )
    }

    @Test
    fun toolCallEnd_finalizesCall() {
        val callId = "call-finalize-1"
        val message = ProviderEventAssembler().apply(
            previous = null,
            events = listOf(
                ProviderEvent.ToolCallStart(
                    ToolCallPart(
                        toolCallId = callId,
                        toolName = "search",
                        arguments = buildJsonObject { put("partial_json", "") },
                        partial = true,
                    ),
                ),
                ProviderEvent.ToolCallDelta(callId, "{\"query\":\"kmp\"}"),
                ProviderEvent.ToolCallEnd(
                    ToolCallPart(
                        toolCallId = callId,
                        toolName = "search",
                        arguments = buildJsonObject { put("query", "kmp") },
                        partial = false,
                    ),
                ),
            ),
        )

        val finalized = ToolCallAssembler().merge(
            requireNotNull(message).parts.filterIsInstance<ToolCallPart>(),
        ).single()

        assertFalse(finalized.partial)
        assertEquals(ToolCallLifecycle.FINALIZED, finalized.lifecycle())
        assertEquals("kmp", finalized.arguments.jsonObject["query"]?.jsonPrimitive?.content)
    }

    @Test
    fun providerMaxTokens_completionDoesNotBecomeAgentMaxTurns() {
        val message = ProviderEventAssembler().apply(
            previous = AgentMessage(role = MessageRole.ASSISTANT, parts = listOf(TextPart("truncated"))),
            events = listOf(ProviderEvent.Completed("MAX_TOKENS")),
        )

        assertEquals(StopReason.MAX_TOKENS, message?.stopReason)
    }

    @Test
    fun terminalEvent_preservesNormalizedStopReasonAndUsage() {
        val usage = ProviderUsage(inputTokens = 11, outputTokens = 7, reasoningTokens = 3)
        val message = ProviderEventAssembler().apply(
            previous = AgentMessage(role = MessageRole.ASSISTANT, parts = listOf(TextPart("tool requested"))),
            events = listOf(
                ProviderEvent.Completed(
                    finishReason = "STOP",
                    stopReason = StopReason.TOOL_CALLS,
                    usage = usage,
                ),
            ),
        )

        assertEquals(StopReason.TOOL_CALLS, message?.stopReason)
        assertEquals(usage, (ProviderEvent.Completed("STOP", StopReason.TOOL_CALLS, usage)).usage)
    }

    @Test
    fun canonicalEventAssembly_doesNotDuplicateTextReasoningOrToolCall() {
        val call = ToolCallPart(
            toolCallId = "call-1",
            toolName = "search",
            arguments = buildJsonObject { put("q", "kmp") },
            partial = false,
        )
        val message = ProviderEventAssembler().apply(
            previous = null,
            events = listOf(
                ProviderEvent.ReasoningStart(),
                ProviderEvent.ReasoningDelta("inspect "),
                ProviderEvent.ReasoningDelta("sources"),
                ProviderEvent.ReasoningEnd(),
                ProviderEvent.TextStart(),
                ProviderEvent.TextDelta("hello "),
                ProviderEvent.TextDelta("world"),
                ProviderEvent.TextEnd(),
                ProviderEvent.ToolCallStart(call.copy(partial = true)),
                ProviderEvent.ToolCallEnd(call),
                ProviderEvent.Completed("STOP", StopReason.TOOL_CALLS),
            ),
        )

        assertEquals(listOf("inspect sources"), requireNotNull(message).parts.filterIsInstance<saien.magrathea.core.ReasoningPart>().map { it.text })
        assertEquals(listOf("hello world"), message.parts.filterIsInstance<TextPart>().map { it.text })
        assertEquals(listOf(call), message.parts.filterIsInstance<ToolCallPart>())
        assertEquals(StopReason.TOOL_CALLS, message.stopReason)
    }

    @Test
    fun consecutiveReasoningLifecyclesRemainDistinctAndFinalized() {
        val message = requireNotNull(
            ProviderEventAssembler().apply(
                previous = null,
                events = listOf(
                    ProviderEvent.ReasoningStart(kind = ReasoningContentKind.SUMMARY),
                    ProviderEvent.ReasoningDelta("Short summary"),
                    ProviderEvent.ReasoningEnd(text = "Short summary"),
                    ProviderEvent.ReasoningStart(kind = ReasoningContentKind.TEXT),
                    ProviderEvent.ReasoningDelta("Visible trace"),
                    ProviderEvent.ReasoningEnd(text = "Visible trace"),
                ),
            ),
        )

        val reasoning = message.parts.filterIsInstance<ReasoningPart>()
        assertEquals(listOf("Short summary", "Visible trace"), reasoning.map(ReasoningPart::text))
        assertEquals(
            listOf(ReasoningContentKind.SUMMARY, ReasoningContentKind.TEXT),
            reasoning.map(ReasoningPart::kind),
        )
        assertEquals(listOf(MessageBlockPhase.FINAL, MessageBlockPhase.FINAL), reasoning.map(ReasoningPart::phase))
    }

    @Test
    fun opaqueReasoningFinalizesWithoutBecomingVisibleText() {
        val message = requireNotNull(
            ProviderEventAssembler().apply(
                previous = null,
                events = listOf(
                    ProviderEvent.ReasoningStart(redacted = true),
                    ProviderEvent.ReasoningEnd(signature = "opaque-state", redacted = true),
                ),
            ),
        )
        val reasoning = message.parts.filterIsInstance<ReasoningPart>().single()

        assertEquals("", reasoning.text)
        assertEquals("opaque-state", reasoning.signature)
        assertTrue(reasoning.redacted)
        assertEquals(MessageBlockPhase.FINAL, reasoning.phase)
    }

    @Test
    fun providerEventAssembler_usesInjectedModelFactory() {
        val assembler = ProviderEventAssembler(
            messageFactory = AgentModelFactory(
                idGenerator = IdGenerator { "assistant-fixture-id" },
                clock = EpochClock { 1234L },
            ),
        )

        val message = assembler.apply(
            previous = null,
            events = listOf(ProviderEvent.TextDelta("hello"), ProviderEvent.Completed("STOP")),
        )

        assertEquals("assistant-fixture-id", message?.id)
        assertEquals(1234L, message?.createdAtEpochMs)
    }
}
