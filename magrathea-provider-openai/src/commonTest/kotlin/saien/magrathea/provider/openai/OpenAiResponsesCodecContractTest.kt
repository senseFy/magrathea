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
import saien.magrathea.provider.api.PROVIDER_CITATIONS_METADATA_KEY

class OpenAiResponsesCodecContractTest {
    @Test
    fun contextLengthFailureMapsToRecoverableTypedFailure() {
        val codec = OpenAiResponsesCodec("openai", "context-model")

        assertFailsWith<ProviderContextLimitException> {
            codec.decodeServerSentEvent(
                "error",
                """{"type":"error","error":{"code":"context_length_exceeded","message":"maximum context length"}}""",
            )
        }
    }

    @Test
    fun compatibleResponsesReasoningTextStreamsThroughContentPartLifecycle() {
        val codec = OpenAiResponsesCodec("openai", "compatible-reasoning-model")
        val assembler = ProviderEventAssembler()
        var message: saien.magrathea.core.AgentMessage? = null
        val events = OPENAI_REASONING_TEXT_STREAM.flatMap { (event, data) ->
            codec.decodeServerSentEvent(event, data)?.events.orEmpty().also { chunkEvents ->
                message = assembler.apply(message, chunkEvents)
            }
        }
        codec.finish()

        val reasoning = requireNotNull(message).parts.filterIsInstance<ReasoningPart>().single()
        assertEquals("I should answer directly.", reasoning.text)
        assertEquals(ReasoningContentKind.TEXT, reasoning.kind)
        assertEquals(MessageBlockPhase.FINAL, reasoning.phase)
        assertFalse(reasoning.redacted)
        assertEquals(1, events.filterIsInstance<ProviderEvent.ReasoningStart>().size)
        assertEquals(1, events.filterIsInstance<ProviderEvent.ReasoningEnd>().size)
    }

    @Test
    fun openAiReasoningSummaryUsesTheSameCanonicalLifecycle() {
        val codec = OpenAiResponsesCodec("openai", "openai-reasoning-model")
        val events = OPENAI_REASONING_SUMMARY_STREAM.flatMap { (event, data) ->
            codec.decodeServerSentEvent(event, data)?.events.orEmpty()
        }
        codec.finish()
        val message = requireNotNull(ProviderEventAssembler().apply(null, events))

        val reasoning = message.parts.filterIsInstance<ReasoningPart>().single()
        assertEquals("I should answer directly.", reasoning.text)
        assertEquals(ReasoningContentKind.SUMMARY, reasoning.kind)
        assertEquals(MessageBlockPhase.FINAL, reasoning.phase)
        assertEquals(1, events.filterIsInstance<ProviderEvent.ReasoningStart>().size)
        assertEquals(1, events.filterIsInstance<ProviderEvent.ReasoningEnd>().size)
    }

    @Test
    fun compatibleResponsesCanFinalizeCompactedReasoningAtTheAuthoritativeItemBoundary() {
        val codec = OpenAiResponsesCodec("openai", "compatible-reasoning-model")
        val events = OPENROUTER_COMPACTED_REASONING_STREAM.flatMap { (event, data) ->
            codec.decodeServerSentEvent(event, data)?.events.orEmpty()
        }
        codec.finish()
        val message = requireNotNull(ProviderEventAssembler().apply(null, events))

        val reasoning = message.parts.filterIsInstance<ReasoningPart>().single()
        assertEquals("Checked the constraints.", reasoning.text)
        assertEquals(ReasoningContentKind.SUMMARY, reasoning.kind)
        assertEquals(MessageBlockPhase.FINAL, reasoning.phase)
        assertEquals(1, events.filterIsInstance<ProviderEvent.ReasoningStart>().size)
        assertEquals(1, events.filterIsInstance<ProviderEvent.ReasoningEnd>().size)
        assertEquals(1, events.filterIsInstance<ProviderEvent.ToolCallEnd>().size)
        assertEquals(StopReason.TOOL_CALLS, message.stopReason)
    }

    @Test
    fun authoritativeItemBoundaryAppendsOnlyTheMissingReasoningSuffix() {
        val compactedPrefixStream = OPENROUTER_COMPACTED_REASONING_STREAM.map { (event, data) ->
            if (event == "response.reasoning_summary_text.delta" && data.contains("the constraints.")) {
                event to data.replace("the constraints.", "the ")
            } else {
                event to data
            }
        }
        val codec = OpenAiResponsesCodec("openai", "compatible-reasoning-model")
        val events = compactedPrefixStream.flatMap { (event, data) ->
            codec.decodeServerSentEvent(event, data)?.events.orEmpty()
        }
        codec.finish()

        assertEquals(
            listOf("Checked ", "the ", "constraints."),
            events.filterIsInstance<ProviderEvent.ReasoningDelta>().map { it.delta },
        )
        assertEquals(
            "Checked the constraints.",
            events.filterIsInstance<ProviderEvent.ReasoningEnd>().single().text,
        )
    }

    @Test
    fun explicitReasoningTextDoneCanUseTheItemBoundaryForPartCompletion() {
        val missingPartDone = OPENAI_REASONING_SUMMARY_STREAM.filterNot { (event, _) ->
            event == "response.reasoning_summary_part.done"
        }
        val codec = OpenAiResponsesCodec("openai", "compatible-reasoning-model")
        val events = missingPartDone.flatMap { (event, data) ->
            codec.decodeServerSentEvent(event, data)?.events.orEmpty()
        }
        codec.finish()

        assertEquals(1, events.filterIsInstance<ProviderEvent.ReasoningEnd>().size)
        assertEquals(
            "I should answer directly.",
            events.filterIsInstance<ProviderEvent.ReasoningEnd>().single().text,
        )
    }

    @Test
    fun authoritativeReasoningReconciliationStillFailsClosedOnTextOrPartCountChanges() {
        val textChanged = OpenAiResponsesCodec("openai", "compatible-reasoning-model")
        OPENROUTER_COMPACTED_REASONING_STREAM.take(10).forEach { (event, data) ->
            textChanged.decodeServerSentEvent(event, data)
        }
        val reasoningDone = OPENROUTER_COMPACTED_REASONING_STREAM[10]
        assertFailsWith<ProviderProtocolException> {
            textChanged.decodeServerSentEvent(
                reasoningDone.first,
                reasoningDone.second.replace("Checked the constraints.", "Changed reasoning."),
            )
        }

        val countChanged = OpenAiResponsesCodec("openai", "compatible-reasoning-model")
        OPENROUTER_COMPACTED_REASONING_STREAM.take(10).forEach { (event, data) ->
            countChanged.decodeServerSentEvent(event, data)
        }
        assertFailsWith<ProviderProtocolException> {
            countChanged.decodeServerSentEvent(
                reasoningDone.first,
                reasoningDone.second.replace(
                    "\"summary\":[{\"type\":\"summary_text\",\"text\":\"Checked the constraints.\"}]",
                    "\"summary\":[]",
                ),
            )
        }
    }

    @Test
    fun reasoningEventsAfterTheAuthoritativeItemBoundaryStillFailClosed() {
        val codec = OpenAiResponsesCodec("openai", "compatible-reasoning-model")
        OPENROUTER_COMPACTED_REASONING_STREAM.take(11).forEach { (event, data) ->
            codec.decodeServerSentEvent(event, data)
        }

        assertFailsWith<ProviderProtocolException> {
            codec.decodeServerSentEvent(
                "response.reasoning_summary_text.delta",
                """{"type":"response.reasoning_summary_text.delta","item_id":"rs_compacted_1","output_index":0,"summary_index":0,"delta":"late"}""",
            )
        }
    }

    @Test
    fun compactedReasoningStillRequiresExactTerminalOutputEquality() {
        val codec = OpenAiResponsesCodec("openai", "compatible-reasoning-model")
        OPENROUTER_COMPACTED_REASONING_STREAM.take(11).forEach { (event, data) ->
            codec.decodeServerSentEvent(event, data)
        }
        val terminal = OPENROUTER_COMPACTED_REASONING_STREAM.last()

        assertFailsWith<ProviderProtocolException> {
            codec.decodeServerSentEvent(
                terminal.first,
                terminal.second.replace("Checked the constraints.", "Changed after item completion."),
            )
        }
    }

    @Test
    fun nonStreamingReasoningSummaryDoesNotExposeOpaqueContinuityState() {
        val chunk = OpenAiResponsesCodec("openai", "openai-reasoning-model")
            .decodeNonStreaming(OPENAI_REASONING_SUMMARY_RESPONSE)
        val message = requireNotNull(ProviderEventAssembler().apply(null, chunk.events))

        val reasoning = message.parts.filterIsInstance<ReasoningPart>().single()
        assertEquals("I should answer directly.", reasoning.text)
        assertEquals(null, reasoning.signature)
        assertFalse(reasoning.redacted)
    }

    @Test
    fun reasoningItemCanCarrySummaryAndExplicitReasoningTextWithoutConflatingThem() {
        val response = """{
            "id":"resp_mixed_reasoning",
            "status":"completed",
            "output":[{
                "id":"rs_mixed",
                "type":"reasoning",
                "status":"completed",
                "summary":[{"type":"summary_text","text":"Checked the constraints."}],
                "content":[{"type":"reasoning_text","text":"A Provider-visible reasoning trace."}],
                "encrypted_content":"opaque-state"
            }],
            "usage":{"input_tokens":3,"output_tokens":4,"output_tokens_details":{"reasoning_tokens":2}}
        }""".trimIndent()

        val chunk = OpenAiResponsesCodec("openai", "mixed-reasoning-model")
            .decodeNonStreaming(response)
        val message = requireNotNull(ProviderEventAssembler().apply(null, chunk.events))
        val reasoning = message.parts.filterIsInstance<ReasoningPart>()

        assertEquals(
            listOf(ReasoningContentKind.SUMMARY, ReasoningContentKind.TEXT),
            reasoning.map(ReasoningPart::kind),
        )
        assertEquals(
            listOf("Checked the constraints.", "A Provider-visible reasoning trace."),
            reasoning.map(ReasoningPart::text),
        )
        assertEquals(listOf(false, false), reasoning.map(ReasoningPart::redacted))
    }

    @Test
    fun streamingSummaryAndReasoningTextUseIndependentCanonicalLifecycles() {
        val codec = OpenAiResponsesCodec("openai", "mixed-reasoning-model")
        val events = OPENAI_MIXED_REASONING_STREAM.flatMap { (event, data) ->
            codec.decodeServerSentEvent(event, data)?.events.orEmpty()
        }
        codec.finish()
        val reasoning = requireNotNull(ProviderEventAssembler().apply(null, events))
            .parts.filterIsInstance<ReasoningPart>()

        assertEquals(
            listOf(ReasoningContentKind.SUMMARY, ReasoningContentKind.TEXT),
            reasoning.map(ReasoningPart::kind),
        )
        assertEquals(listOf(MessageBlockPhase.FINAL, MessageBlockPhase.FINAL), reasoning.map(ReasoningPart::phase))
        assertEquals(2, events.filterIsInstance<ProviderEvent.ReasoningStart>().size)
        assertEquals(2, events.filterIsInstance<ProviderEvent.ReasoningEnd>().size)
    }

    @Test
    fun nullableCompatibleReasoningContentIsTreatedAsAbsent() {
        val response = """{
            "id":"resp_nullable_reasoning",
            "status":"completed",
            "output":[{
                "id":"rs_nullable",
                "type":"reasoning",
                "status":"completed",
                "summary":[{"type":"summary_text","text":"Visible summary."}],
                "content":null,
                "format":"anthropic-claude-v1",
                "signature":"opaque-signature"
            }]
        }""".trimIndent()

        val chunk = OpenAiResponsesCodec("openai", "compatible-model")
            .decodeNonStreaming(response)
        val reasoning = requireNotNull(ProviderEventAssembler().apply(null, chunk.events))
            .parts.filterIsInstance<ReasoningPart>().single()

        assertEquals("Visible summary.", reasoning.text)
        assertEquals(ReasoningContentKind.SUMMARY, reasoning.kind)
        assertEquals(null, reasoning.signature)
    }

    @Test
    fun officialToolStreamMapsItemIdToCallIdAndFinalizesExactlyOnce() {
        val codec = OpenAiResponsesCodec("openai", "gpt-contract")
        val events = OPENAI_TOOL_STREAM.flatMap { (event, data) ->
            codec.decodeServerSentEvent(event, data)?.events.orEmpty()
        }
        codec.finish()

        val start = events.filterIsInstance<ProviderEvent.ToolCallStart>().single().toolCall
        val deltas = events.filterIsInstance<ProviderEvent.ToolCallDelta>()
        val end = events.filterIsInstance<ProviderEvent.ToolCallEnd>().single().toolCall
        val completed = events.filterIsInstance<ProviderEvent.Completed>().single()

        assertEquals("call_weather_1", start.toolCallId)
        assertEquals("fc_weather_1", start.providerCallId)
        assertEquals(listOf("call_weather_1", "call_weather_1"), deltas.map { it.toolCallId })
        assertEquals("Shanghai", end.arguments.jsonObject["city"]?.jsonPrimitive?.content)
        assertFalse(end.partial)
        assertEquals(StopReason.TOOL_CALLS, completed.stopReason)
        assertEquals(1, completed.usage?.reasoningTokens)
    }

    @Test
    fun nonStreamingUsesTheSameCanonicalLifecycle() {
        val chunk = OpenAiResponsesCodec("openai", "gpt-contract").decodeNonStreaming(OPENAI_TOOL_RESPONSE)
        val message = requireNotNull(ProviderEventAssembler().apply(null, chunk.events))

        assertEquals(1, chunk.events.filterIsInstance<ProviderEvent.ToolCallStart>().size)
        assertEquals(1, chunk.events.filterIsInstance<ProviderEvent.ToolCallEnd>().size)
        assertEquals(1, chunk.events.filterIsInstance<ProviderEvent.Completed>().size)
        assertFalse(message.parts.filterIsInstance<ToolCallPart>().single().partial)
        assertEquals(StopReason.TOOL_CALLS, message.stopReason)
    }

    @Test
    fun nonStreamingXSearchIsServerSideAndPreservesGroundedCitations() {
        val chunk = OpenAiResponsesCodec("openai", "grok-contract")
            .decodeNonStreaming(OPENAI_X_SEARCH_RESPONSE)
        val message = requireNotNull(ProviderEventAssembler().apply(null, chunk.events))

        assertEquals(0, chunk.events.filterIsInstance<ProviderEvent.ToolCallStart>().size)
        assertEquals(0, chunk.events.filterIsInstance<ProviderEvent.ToolCallEnd>().size)
        assertEquals(
            "KMP is being discussed.[[1]](https://x.com/kotlin/status/1)",
            message.parts.filterIsInstance<saien.magrathea.core.TextPart>().single().text,
        )
        assertEquals(
            listOf(
                "https://x.com/kotlin/status/1",
                "https://x.com/gradle/status/2",
            ),
            message.metadata.getValue(PROVIDER_CITATIONS_METADATA_KEY).jsonArray.map {
                it.jsonObject.getValue("url").jsonPrimitive.content
            },
        )
        assertEquals(StopReason.COMPLETED, message.stopReason)
    }

    @Test
    fun nonStreamingXSearchAcceptsXaiSchemaWhereIdAndStatusAreOptional() {
        val chunk = OpenAiResponsesCodec("openai", "grok-contract")
            .decodeNonStreaming(XAI_SCHEMA_X_SEARCH_RESPONSE)
        val message = requireNotNull(ProviderEventAssembler().apply(null, chunk.events))

        assertEquals(
            "KMP is being discussed.[[1]](https://x.com/kotlin/status/1)",
            message.parts.filterIsInstance<saien.magrathea.core.TextPart>().single().text,
        )
        assertEquals(StopReason.COMPLETED, message.stopReason)
    }

    @Test
    fun xaiHostedXSearchCustomToolTraceIsConsumedOnlyWhenRequestedByTheClient() {
        assertFailsWith<ProviderProtocolException> {
            OpenAiResponsesCodec("openai", "grok-contract")
                .decodeNonStreaming(XAI_HOSTED_X_SEARCH_RESPONSE)
        }

        val chunk = OpenAiResponsesCodec(
            providerKey = "openai",
            model = "grok-contract",
            allowServerManagedCustomToolCalls = true,
        ).decodeNonStreaming(XAI_HOSTED_X_SEARCH_RESPONSE)
        val message = requireNotNull(ProviderEventAssembler().apply(null, chunk.events))

        assertEquals(0, chunk.events.filterIsInstance<ProviderEvent.ToolCallStart>().size)
        assertEquals(0, chunk.events.filterIsInstance<ProviderEvent.ToolCallEnd>().size)
        assertEquals(
            "KMP is being discussed.[[1]](https://x.com/kotlin/status/1)",
            message.parts.filterIsInstance<saien.magrathea.core.TextPart>().single().text,
        )
        assertEquals(
            "https://x.com/kotlin/status/1",
            message.metadata.getValue(PROVIDER_CITATIONS_METADATA_KEY).jsonArray.single()
                .jsonObject.getValue("url").jsonPrimitive.content,
        )
        assertEquals(StopReason.COMPLETED, message.stopReason)
    }

    @Test
    fun streamingXSearchActivityDoesNotBecomeAClientSideToolCall() {
        val codec = OpenAiResponsesCodec("openai", "grok-contract")
        val events = OPENAI_X_SEARCH_STREAM.flatMap { (event, data) ->
            codec.decodeServerSentEvent(event, data)?.events.orEmpty()
        }
        codec.finish()

        assertEquals(0, events.filterIsInstance<ProviderEvent.ToolCallStart>().size)
        assertEquals(0, events.filterIsInstance<ProviderEvent.ToolCallEnd>().size)
        assertEquals(1, events.filterIsInstance<ProviderEvent.TextStart>().size)
        assertEquals(1, events.filterIsInstance<ProviderEvent.Completed>().size)
    }

    @Test
    fun streamingXaiHostedCustomToolTraceIsValidatedWithoutBecomingAClientToolCall() {
        val codec = OpenAiResponsesCodec(
            providerKey = "openai",
            model = "grok-contract",
            allowServerManagedCustomToolCalls = true,
        )
        val events = XAI_HOSTED_X_SEARCH_STREAM.flatMap { (event, data) ->
            codec.decodeServerSentEvent(event, data)?.events.orEmpty()
        }
        codec.finish()

        assertEquals(0, events.filterIsInstance<ProviderEvent.ToolCallStart>().size)
        assertEquals(0, events.filterIsInstance<ProviderEvent.ToolCallEnd>().size)
        assertEquals(1, events.filterIsInstance<ProviderEvent.TextStart>().size)
        assertEquals(1, events.filterIsInstance<ProviderEvent.Completed>().size)
    }

    @Test
    fun malformedXSearchLifecycleFailsClosed() {
        val codec = OpenAiResponsesCodec("openai", "grok-contract")
        codec.decodeServerSentEvent(
            "response.created",
            """{"type":"response.created","response":{"id":"resp_x","status":"in_progress"}}""",
        )

        assertFailsWith<ProviderProtocolException> {
            codec.decodeServerSentEvent(
                "response.output_item.added",
                """{"type":"response.output_item.added","output_index":0,"item":{"id":"xs_1","type":"x_search_call","status":"completed"}}""",
            )
        }
    }

    @Test
    fun emptyInitialArgumentsAreAcceptedButMismatchedFinalArgumentsFailClosed() {
        val codec = OpenAiResponsesCodec("openai", "gpt-contract")
        OPENAI_TOOL_STREAM.take(5).forEach { (event, data) -> codec.decodeServerSentEvent(event, data) }

        assertFailsWith<ProviderProtocolException> {
            codec.decodeServerSentEvent(
                "response.function_call_arguments.done",
                """{"type":"response.function_call_arguments.done","item_id":"fc_weather_1","output_index":0,"arguments":"{}"}""",
            )
        }
    }

    @Test
    fun itemDoneCannotReplaceArgumentsDoneOrFinalizeTwice() {
        val missingDone = OpenAiResponsesCodec("openai", "gpt-contract")
        OPENAI_TOOL_STREAM.take(5).forEach { (event, data) -> missingDone.decodeServerSentEvent(event, data) }
        assertFailsWith<ProviderProtocolException> {
            val (event, data) = OPENAI_TOOL_STREAM[6]
            missingDone.decodeServerSentEvent(event, data)
        }

        val duplicateDone = OpenAiResponsesCodec("openai", "gpt-contract")
        OPENAI_TOOL_STREAM.take(6).forEach { (event, data) -> duplicateDone.decodeServerSentEvent(event, data) }
        val (event, data) = OPENAI_TOOL_STREAM[5]
        assertFailsWith<ProviderProtocolException> { duplicateDone.decodeServerSentEvent(event, data) }
    }

    @Test
    fun missingTerminalOrMismatchedSseNameFailsClosed() {
        val incomplete = OpenAiResponsesCodec("openai", "gpt-contract")
        OPENAI_TOOL_STREAM.dropLast(1).forEach { (event, data) -> incomplete.decodeServerSentEvent(event, data) }
        assertFailsWith<ProviderProtocolException> { incomplete.finish() }

        val mismatched = OpenAiResponsesCodec("openai", "gpt-contract")
        assertFailsWith<ProviderProtocolException> {
            mismatched.decodeServerSentEvent("response.in_progress", OPENAI_TOOL_STREAM.first().second)
        }
    }

    @Test
    fun dataOnlySseAndOptionalDoneSentinelPreserveStrictLifecycleValidation() {
        val codec = OpenAiResponsesCodec("openai", "gpt-contract")
        val events = OPENAI_TOOL_STREAM.flatMap { (_, data) ->
            codec.decodeServerSentEvent(null, data)?.events.orEmpty()
        }

        assertEquals(1, events.filterIsInstance<ProviderEvent.Completed>().size)
        assertEquals(null, codec.decodeServerSentEvent(null, "[DONE]"))
        codec.finish()

        assertFailsWith<ProviderProtocolException> {
            codec.decodeServerSentEvent(null, "[DONE]")
        }

        val premature = OpenAiResponsesCodec("openai", "gpt-contract")
        assertFailsWith<ProviderProtocolException> {
            premature.decodeServerSentEvent(null, "[DONE]")
        }
    }
}
