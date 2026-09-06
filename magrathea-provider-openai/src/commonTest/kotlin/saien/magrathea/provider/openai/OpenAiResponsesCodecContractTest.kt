package saien.magrathea.provider.openai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import saien.magrathea.core.TextPart
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
import saien.magrathea.provider.api.ProviderAuthException
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
        val codec = openRouterResponsesCodec("compatible-reasoning-model")
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
        val codec = openRouterResponsesCodec("compatible-reasoning-model")
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
    fun everyDialectAcceptsMissingNestedReasoningBoundaries() {
        for (provider in listOf("openai", "openrouter", "xai", "custom")) {
            val codec = OpenAiResponsesCodec(provider, "model")
            val events = OPENROUTER_COMPACTED_REASONING_STREAM.flatMap { (event, data) ->
                codec.decodeServerSentEvent(event, data)?.events.orEmpty()
            }
            codec.finish()
            val message = requireNotNull(ProviderEventAssembler().apply(null, events))
            assertEquals("Checked the constraints.", message.parts.filterIsInstance<ReasoningPart>().single().text)
            assertEquals(1, events.filterIsInstance<ProviderEvent.ToolCallEnd>().size)
        }
    }

    @Test
    fun itemCompletionSuppliesFinalReasoningWithoutSynthesizingDeltas() {
        val compactedPrefixStream = OPENROUTER_COMPACTED_REASONING_STREAM.map { (event, data) ->
            if (event == "response.reasoning_summary_text.delta" && data.contains("the constraints.")) {
                event to data.replace("the constraints.", "the ")
            } else {
                event to data
            }
        }
        val codec = openRouterResponsesCodec("compatible-reasoning-model")
        val events = compactedPrefixStream.flatMap { (event, data) ->
            codec.decodeServerSentEvent(event, data)?.events.orEmpty()
        }
        codec.finish()

        assertEquals(
            listOf("Checked ", "the "),
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
        val codec = openRouterResponsesCodec("compatible-reasoning-model")
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
    fun finalReasoningCanReviseTextAndPartCount() {
        val replacements = listOf(
            "[{\"type\":\"summary_text\",\"text\":\"Revised.\"}]" to listOf("Revised."),
            "[]" to listOf(""),
            "[{\"type\":\"summary_text\",\"text\":\"First.\"},{\"type\":\"summary_text\",\"text\":\"Second.\"}]" to listOf("First.", "Second."),
        )
        for ((summary, expected) in replacements) {
            val codec = OpenAiResponsesCodec("openai", "model")
            val events = OPENROUTER_COMPACTED_REASONING_STREAM.flatMap { (event, data) ->
                codec.decodeServerSentEvent(event, data.replace(
                    "\"summary\":[{\"type\":\"summary_text\",\"text\":\"Checked the constraints.\"}]",
                    "\"summary\":$summary",
                ))?.events.orEmpty()
            }
            codec.finish()
            val message = requireNotNull(ProviderEventAssembler().apply(null, events))
            assertEquals(expected, message.parts.filterIsInstance<ReasoningPart>().map(ReasoningPart::text))
        }
    }

    @Test
    fun lateReasoningDeltasDoNotReopenCompletedItems() {
        val codec = openRouterResponsesCodec("model")
        OPENROUTER_COMPACTED_REASONING_STREAM.take(11).forEach { (event, data) ->
            codec.decodeServerSentEvent(event, data)
        }
        assertEquals(null, codec.decodeServerSentEvent(
            "response.reasoning_summary_text.delta",
            """{"type":"response.reasoning_summary_text.delta","item_id":"rs_compacted_1","output_index":0,"summary_index":0,"delta":"late"}""",
        ))
    }

    @Test
    fun terminalReplayMetadataDoesNotRevalidateCompletedVisibleContent() {
        val codec = openRouterResponsesCodec("model")
        val events = OPENROUTER_COMPACTED_REASONING_STREAM.flatMap { (event, data) ->
            codec.decodeServerSentEvent(event, if (event == "response.completed") {
                data.replace("Checked the constraints.", "Updated replay summary.")
            } else data)?.events.orEmpty()
        }
        codec.finish()
        val message = requireNotNull(ProviderEventAssembler().apply(null, events))
        assertEquals("Checked the constraints.", message.parts.filterIsInstance<ReasoningPart>().single().text)
        assertTrue(message.metadata[OPENAI_RESPONSE_OUTPUT_METADATA].toString().contains("Updated replay summary."))
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
    fun nonStreamingFailedResponseMapsItsErrorTypeToATypedFailure() {
        val failure = """{
            "id":"resp_failed_1",
            "status":"failed",
            "error_type":"authentication",
            "error":{"code":"provider_error","message":"sensitive upstream message"}
        }""".trimIndent()

        val exception = assertFailsWith<ProviderAuthException> {
            OpenAiResponsesCodec("openrouter", "provider/model").decodeNonStreaming(failure)
        }

        assertFalse(exception.message.orEmpty().contains("sensitive upstream message"))
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
        val chunk = xAiSearchResponsesCodec()
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
        val chunk = xAiSearchResponsesCodec()
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
            providerKey = "xai",
            model = "grok-contract",
            allowServerManagedTools = true,
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
        val codec = xAiSearchResponsesCodec()
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
            providerKey = "xai",
            model = "grok-contract",
            allowServerManagedTools = true,
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
    fun hostedSearchDetailsRemainOpaqueToTheClient() {
        val chunk = xAiSearchResponsesCodec().decodeNonStreaming(
            """{"id":"r","status":"completed","output":[{"type":"x_search_call","status":"future_status"}]}""",
        )
        assertEquals(1, chunk.events.size)
        assertTrue(chunk.events.single() is ProviderEvent.Completed)
    }

    @Test
    fun terminalArgumentsReplaceProvisionalArguments() {
        val codec = OpenAiResponsesCodec("openai", "model")
        val events = OPENAI_TOOL_STREAM.flatMap { (event, data) ->
            val chunk = codec.decodeServerSentEvent(event, if (event == "response.completed") {
                data.replace("Shanghai", "Beijing")
            } else data)
            if (event != "response.completed") assertTrue(chunk?.events.orEmpty().none { it is ProviderEvent.ToolCallEnd })
            chunk?.events.orEmpty()
        }
        codec.finish()
        val call = events.filterIsInstance<ProviderEvent.ToolCallEnd>().single().toolCall
        assertEquals("Beijing", call.arguments.jsonObject["city"]?.jsonPrimitive?.content)
    }

    @Test
    fun optionalAndRepeatedBoundariesDoNotDuplicateToolCalls() {
        val codec = OpenAiResponsesCodec("openai", "model")
        val events = OPENAI_TOOL_STREAM.filterNot { (event, _) -> event == "response.function_call_arguments.done" }
            .flatMap { (event, data) ->
                val copies = if (event == "response.output_item.done" || event == "response.completed") 2 else 1
                List(copies) { codec.decodeServerSentEvent(event, data)?.events.orEmpty() }.flatten()
            }
        codec.finish()
        assertEquals(1, events.filterIsInstance<ProviderEvent.ToolCallStart>().size)
        assertEquals(1, events.filterIsInstance<ProviderEvent.ToolCallEnd>().size)
        assertEquals(1, events.filterIsInstance<ProviderEvent.Completed>().size)
    }

    @Test
    fun bodyTypeDeterminesEventsButTerminalResponseRemainsRequired() {
        val codec = OpenAiResponsesCodec("openai", "model")
        OPENAI_TOOL_STREAM.dropLast(1).forEach { (_, data) -> codec.decodeServerSentEvent("message", data) }
        assertFailsWith<ProviderProtocolException> { codec.finish() }
        codec.decodeServerSentEvent("message", OPENAI_TOOL_STREAM.last().second)
        codec.finish()
    }

    @Test
    fun sseSentinelsAreOptionalAndCannotReplaceTheTerminalResponse() {
        val codec = OpenAiResponsesCodec("openai", "model")
        val events = OPENAI_TOOL_STREAM.flatMap { (_, data) ->
            codec.decodeServerSentEvent(null, data)?.events.orEmpty()
        }
        repeat(2) { assertEquals(null, codec.decodeServerSentEvent(null, "[DONE]")) }
        codec.finish()
        assertEquals(1, events.filterIsInstance<ProviderEvent.Completed>().size)

        val premature = OpenAiResponsesCodec("openai", "model")
        assertEquals(null, premature.decodeServerSentEvent(null, "[DONE]"))
        assertFailsWith<ProviderProtocolException> { premature.finish() }
    }

    @Test
    fun finalMessageTextReplacesProvisionalDeltas() {
        val codec = OpenAiResponsesCodec("openai", "model")
        val events = OPENAI_TEXT_STREAM.flatMap { (event, data) ->
            codec.decodeServerSentEvent(event, if (event == "response.output_item.done") data.replace("Shanghai", "Beijing") else data)?.events.orEmpty()
        }
        codec.finish()
        val message = requireNotNull(ProviderEventAssembler().apply(null, events))
        assertEquals("Beijing is sunny.", message.parts.filterIsInstance<TextPart>().single().text)
        assertEquals(1, events.filterIsInstance<ProviderEvent.TextEnd>().size)
    }

    @Test
    fun nestedTextDisagreementsAndOpaqueIdChangesDoNotInvalidateFinalReasoning() {
        for (provider in listOf("openai", "openrouter", "xai", "custom")) {
            val codec = OpenAiResponsesCodec(provider, "model")
            val events = OPENAI_REASONING_TEXT_STREAM.flatMap { (event, data) ->
                val payload = when (event) {
                    "response.content_part.done" -> data.replace("reasoning_text", "summary_text").replace("I should answer directly.", "A revised view.")
                    "response.output_item.done" -> data.replace("rs_text_1", "rotated-opaque-id").replace("I should answer directly.", "Final reasoning.")
                    else -> data
                }
                codec.decodeServerSentEvent(event, payload)?.events.orEmpty()
            }
            codec.finish()
            val reasoning = requireNotNull(ProviderEventAssembler().apply(null, events)).parts.filterIsInstance<ReasoningPart>().single()
            assertEquals("Final reasoning.", reasoning.text)
            assertEquals(ReasoningContentKind.TEXT, reasoning.kind)
            assertEquals(MessageBlockPhase.FINAL, reasoning.phase)
        }
    }

    @Test
    fun terminalSnapshotsRecoverMissingItemAndNestedEvents() {
        for (stream in listOf(OPENAI_TEXT_STREAM, OPENAI_TOOL_STREAM, OPENAI_REASONING_TEXT_STREAM, OPENAI_MIXED_REASONING_STREAM)) {
            val completeCodec = OpenAiResponsesCodec("openai", "model")
            val complete = stream.flatMap { (event, data) -> completeCodec.decodeServerSentEvent(event, data)?.events.orEmpty() }
            for (keepDeltas in listOf(false, true)) {
                val codec = OpenAiResponsesCodec("openai", "model")
                val events = stream.filter { (event, _) -> event == "response.completed" || (keepDeltas && event.endsWith(".delta")) }
                    .flatMap { (event, data) -> codec.decodeServerSentEvent(event, data)?.events.orEmpty() }
                codec.finish()
                val expected = requireNotNull(ProviderEventAssembler().apply(null, complete))
                val actual = requireNotNull(ProviderEventAssembler().apply(null, events))
                assertEquals(expected.parts, actual.parts)
                assertEquals(expected.stopReason, actual.stopReason)
                assertEquals(expected.metadata, actual.metadata)
            }
        }
    }

    @Test
    fun unknownEventsOutputItemsAndAnnotationsAreRetainedOnlyAsMetadata() {
        val codec = OpenAiResponsesCodec("openai", "model")
        assertEquals(null, codec.decodeServerSentEvent(null, """{"type":"response.future_event","extension":{}}"""))
        val output = """[{"type":"future_output","opaque":"state"},${OPENAI_TEXT_ITEM.replace("\"annotations\":[]", "\"annotations\":[{\"type\":\"file_citation\",\"file_id\":\"file-1\"}]")}]"""
        val chunk = requireNotNull(codec.decodeServerSentEvent(null,
            """{"type":"response.completed","response":{"id":"r","status":"completed","output":$output}}""",
        ))
        codec.finish()
        val message = requireNotNull(ProviderEventAssembler().apply(null, chunk.events))
        assertEquals("Shanghai is sunny.", message.parts.filterIsInstance<TextPart>().single().text)
        assertEquals(output, message.metadata[OPENAI_RESPONSE_OUTPUT_METADATA].toString())
        assertTrue(message.parts.none { it is ToolCallPart })
    }

    @Test
    fun interleavedItemsDoNotMixSequentialCanonicalBlocks() {
        val stream = listOf(
            """{"type":"response.output_item.added","output_index":0,"item":{"id":"r","type":"reasoning"}}""",
            """{"type":"response.reasoning_text.delta","output_index":0,"content_index":0,"delta":"Provisional "}""",
            """{"type":"response.output_item.added","output_index":1,"item":{"id":"m","type":"message"}}""",
            """{"type":"response.output_text.delta","output_index":1,"content_index":0,"delta":"Other text"}""",
            """{"type":"response.output_item.added","output_index":2,"item":$OPENAI_TOOL_ITEM}""",
            """{"type":"response.function_call_arguments.delta","output_index":2,"delta":"{}"}""",
            """{"type":"response.output_item.done","output_index":1,"item":$OPENAI_TEXT_ITEM}""",
            """{"type":"response.output_item.done","output_index":2,"item":$OPENAI_TOOL_ITEM}""",
            """{"type":"response.reasoning_text.delta","output_index":0,"content_index":0,"delta":"reasoning"}""",
            """{"type":"response.output_item.done","output_index":0,"item":$OPENAI_REASONING_TEXT_ITEM}""",
            """{"type":"response.completed","response":{"id":"r","status":"completed","output":[$OPENAI_REASONING_TEXT_ITEM,$OPENAI_TEXT_ITEM,$OPENAI_TOOL_ITEM]}}""",
        )
        val codec = OpenAiResponsesCodec("openai", "model")
        val assembler = ProviderEventAssembler()
        var message: saien.magrathea.core.AgentMessage? = null
        stream.forEachIndexed { index, data ->
            message = assembler.apply(message, codec.decodeServerSentEvent(null, data)?.events.orEmpty())
            if (index == 8) assertEquals("Provisional reasoning", requireNotNull(message).parts.filterIsInstance<ReasoningPart>().single().text)
        }
        codec.finish()
        val parts = requireNotNull(message).parts
        assertEquals("I should answer directly.", (parts[0] as ReasoningPart).text)
        assertEquals("Shanghai is sunny.", (parts[1] as TextPart).text)
        assertFalse((parts[2] as ToolCallPart).partial)
        assertEquals(3, parts.size)
    }

    @Test
    fun opaqueWireIdsAreOptionalForToolExecution() {
        val response = OPENAI_TOOL_RESPONSE.replace("\"id\":\"resp_tool_1\",", "").replace("\"id\":\"fc_weather_1\",", "")
        val chunk = OpenAiResponsesCodec("openai", "model").decodeNonStreaming(response)
        val call = chunk.events.filterIsInstance<ProviderEvent.ToolCallEnd>().single().toolCall
        assertEquals("call_weather_1", call.toolCallId)
        assertEquals(null, call.providerCallId)
        assertFalse(call.partial)
    }

    @Test
    fun finalToolIdentityAndArgumentShapeRemainRequired() {
        for ((old, replacement, reason) in listOf(
            Triple("call_weather_1", "different-call", "final_function_call_identity_changed"),
            Triple("get_weather", "different-tool", "final_function_call_identity_changed"),
            Triple("{\\\"city\\\":\\\"Shanghai\\\"}", "[]", "arguments_not_object"),
            Triple("{\\\"city\\\":\\\"Shanghai\\\"}", "{", "malformed_arguments"),
        )) {
            val codec = OpenAiResponsesCodec("openai", "model")
            OPENAI_TOOL_STREAM.dropLast(1).forEach { (event, data) -> codec.decodeServerSentEvent(event, data) }
            val failure = assertFailsWith<ProviderProtocolException> {
                codec.decodeServerSentEvent(null, OPENAI_TOOL_STREAM.last().second.replace(old, replacement))
            }
            assertEquals("openai.responses.$reason", failure.diagnostic?.reason)
        }
    }

    @Test
    fun changingAStartedVisibleBlockIntoAToolDoesNotLeaveAnOpenCanonicalBlock() {
        for (stream in listOf(OPENAI_TEXT_STREAM, OPENAI_REASONING_TEXT_STREAM)) {
            val codec = OpenAiResponsesCodec("openai", "model")
            stream.takeWhile { (event, _) -> event != "response.output_item.done" }.forEach { (event, data) ->
                codec.decodeServerSentEvent(event, data)
            }
            val failure = assertFailsWith<ProviderProtocolException> {
                codec.decodeServerSentEvent(null,
                    """{"type":"response.output_item.done","output_index":0,"item":$OPENAI_TOOL_ITEM}""",
                )
            }
            assertEquals("openai.responses.final_output_item_type_changed", failure.diagnostic?.reason)
        }
    }

    private fun openRouterResponsesCodec(model: String): OpenAiResponsesCodec = OpenAiResponsesCodec(
        providerKey = "openrouter",
        model = model,
    )

    private fun xAiSearchResponsesCodec(): OpenAiResponsesCodec = OpenAiResponsesCodec(
        providerKey = "xai",
        model = "grok-contract",
        allowServerManagedTools = true,
    )
}
