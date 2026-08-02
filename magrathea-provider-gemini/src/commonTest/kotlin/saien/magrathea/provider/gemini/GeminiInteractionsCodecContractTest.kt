package saien.magrathea.provider.gemini

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import saien.magrathea.core.ReasoningPart
import saien.magrathea.core.ReasoningContentKind
import saien.magrathea.core.MessageBlockPhase
import saien.magrathea.core.StopReason
import saien.magrathea.core.TextPart
import saien.magrathea.core.ToolCallPart
import saien.magrathea.provider.api.HttpStreamFrame
import saien.magrathea.provider.api.ProviderAuthException
import saien.magrathea.provider.api.ProviderClientException
import saien.magrathea.provider.api.ProviderContextLimitException
import saien.magrathea.provider.api.ProviderEvent
import saien.magrathea.provider.api.ProviderEventAssembler
import saien.magrathea.provider.api.ProviderException
import saien.magrathea.provider.api.ProviderNetworkException
import saien.magrathea.provider.api.ProviderProtocolException
import saien.magrathea.provider.api.ProviderRateLimitException
import saien.magrathea.provider.api.ProviderServerException
import saien.magrathea.provider.api.ProviderTimeoutException
import saien.magrathea.provider.api.ProviderTimeoutPhase

class GeminiInteractionsCodecContractTest {
    @Test
    fun officialSseTranscript_preservesStepLifecycleToolUsageAndAuthoritativeHistory() {
        val codec = GeminiInteractionsCodec(MODEL)
        val events = sseFrames(TOOL_INTERACTION_SSE).flatMap { frame ->
            when (frame) {
                is HttpStreamFrame.ServerSentEvent -> codec.decodeServerSentEvent(frame.event, frame.data)?.events.orEmpty()
                HttpStreamFrame.Completed -> codec.finish().let { emptyList() }
                else -> emptyList()
            }
        }

        assertEquals(1, events.filterIsInstance<ProviderEvent.ReasoningStart>().size)
        assertEquals(
            ReasoningContentKind.SUMMARY,
            events.filterIsInstance<ProviderEvent.ReasoningStart>().single().kind,
        )
        assertEquals("I should verify the weather.", events.filterIsInstance<ProviderEvent.ReasoningDelta>().joinToString("") { it.delta })
        assertEquals("thought-sig-1", events.filterIsInstance<ProviderEvent.ReasoningEnd>().single().signature)
        assertEquals(listOf("I'll check ", "the live weather. "), events.filterIsInstance<ProviderEvent.TextDelta>().map { it.delta })

        val toolStart = events.filterIsInstance<ProviderEvent.ToolCallStart>().single().toolCall
        val toolEnd = events.filterIsInstance<ProviderEvent.ToolCallEnd>().single().toolCall
        assertTrue(toolStart.partial)
        assertFalse(toolEnd.partial)
        assertEquals("call-weather-1", toolEnd.toolCallId)
        assertEquals("get_weather", toolEnd.toolName)
        assertEquals("Shanghai", toolEnd.arguments.jsonObject["city"]?.jsonPrimitive?.content)

        val completed = events.filterIsInstance<ProviderEvent.Completed>().single()
        assertEquals(StopReason.TOOL_CALLS, completed.stopReason)
        assertEquals(12, completed.usage?.inputTokens)
        assertEquals(8, completed.usage?.outputTokens)
        assertEquals(2, completed.usage?.reasoningTokens)
        val steps = completed.providerMetadata?.get(GEMINI_INTERACTION_STEPS_METADATA)
        assertIs<JsonArray>(steps)
        assertEquals(listOf("thought", "model_output", "function_call"), steps.map { it.jsonObject["type"]!!.jsonPrimitive.content })
        assertIs<ProviderEvent.Completed>(events.last())
    }

    @Test
    fun nonStreamingAndStreamingProduceEquivalentFinalCanonicalState() {
        val streamingEvents = decodeStreaming(TOOL_INTERACTION_SSE)
        val nonStreamingEvents = GeminiInteractionsCodec(MODEL).decodeNonStreaming(TOOL_INTERACTION_JSON).events
        val assembler = ProviderEventAssembler()
        val streaming = assembler.apply(null, streamingEvents)!!
        val nonStreaming = assembler.apply(null, nonStreamingEvents)!!

        assertEquals(
            streaming.parts.filterIsInstance<TextPart>().map { it.text },
            nonStreaming.parts.filterIsInstance<TextPart>().map { it.text },
        )
        assertEquals(
            streaming.parts.filterIsInstance<ReasoningPart>().map { Triple(it.text, it.signature, it.kind) },
            nonStreaming.parts.filterIsInstance<ReasoningPart>().map { Triple(it.text, it.signature, it.kind) },
        )
        assertEquals(
            listOf(MessageBlockPhase.FINAL),
            streaming.parts.filterIsInstance<ReasoningPart>().map(ReasoningPart::phase),
        )
        assertEquals(
            streaming.parts.filterIsInstance<ToolCallPart>().map { Triple(it.toolCallId, it.toolName, it.arguments) },
            nonStreaming.parts.filterIsInstance<ToolCallPart>().map { Triple(it.toolCallId, it.toolName, it.arguments) },
        )
        assertEquals(StopReason.TOOL_CALLS, streaming.stopReason)
        assertEquals(streaming.stopReason, nonStreaming.stopReason)
    }

    @Test
    fun malformedOrOutOfOrderLifecycleFailsBeforeToolFinalization() {
        val deltaBeforeStart = GeminiInteractionsCodec(MODEL)
        deltaBeforeStart.decodeServerSentEvent(
            "interaction.created",
            """{"interaction":{"id":"id","model":"$MODEL","status":"in_progress"},"event_type":"interaction.created"}""",
        )
        assertFailsWith<ProviderProtocolException> {
            deltaBeforeStart.decodeServerSentEvent(
                "step.delta",
                """{"index":0,"delta":{"type":"text","text":"unsafe"},"event_type":"step.delta"}""",
            )
        }

        val malformedArguments = GeminiInteractionsCodec(MODEL)
        val prefix = listOf(
            "interaction.created" to """{"interaction":{"id":"id","model":"$MODEL","status":"in_progress"},"event_type":"interaction.created"}""",
            "step.start" to """{"index":0,"step":{"type":"function_call","id":"call","name":"tool","arguments":{}},"event_type":"step.start"}""",
            "step.delta" to """{"index":0,"delta":{"type":"arguments_delta","arguments":"{"},"event_type":"step.delta"}""",
        )
        prefix.forEach { (event, data) -> malformedArguments.decodeServerSentEvent(event, data) }
        assertFailsWith<ProviderProtocolException> {
            malformedArguments.decodeServerSentEvent("step.stop", """{"index":0,"event_type":"step.stop"}""")
        }
    }

    @Test
    fun completedIsTerminalAndDoneCannotArriveEarlyOrTwice() {
        val earlyDone = GeminiInteractionsCodec(MODEL)
        assertFailsWith<ProviderProtocolException> { earlyDone.decodeServerSentEvent("done", "[DONE]") }

        val codec = GeminiInteractionsCodec(MODEL)
        decodeStreaming(TOOL_INTERACTION_SSE, codec)
        assertFailsWith<ProviderProtocolException> {
            codec.decodeServerSentEvent("done", "[DONE]")
        }
        assertFailsWith<ProviderProtocolException> {
            codec.decodeServerSentEvent("step.stop", """{"index":3,"event_type":"step.stop"}""")
        }
    }

    @Test
    fun namedSseEventMustMatchPayloadEventType() {
        val codec = GeminiInteractionsCodec(MODEL)
        assertFailsWith<ProviderProtocolException> {
            codec.decodeServerSentEvent(
                "step.delta",
                """{"interaction":{"id":"id","model":"$MODEL","status":"in_progress"},"event_type":"interaction.created"}""",
            )
        }
    }

    @Test
    fun streamingContextLimitFailureRemainsTypedForRuntimeRecovery() {
        assertFailsWith<ProviderContextLimitException> {
            GeminiInteractionsCodec(MODEL).decodeServerSentEvent(
                "error",
                """{
  "event_type":"error",
  "error":{
    "code":"INVALID_ARGUMENT",
    "message":"The input token count exceeds the maximum number of tokens allowed"
  }
}""",
            )
        }
    }

    @Test
    fun canonicalGoogleErrorStatusMapsToTheDirectProviderFailureHierarchy() {
        val canary = "private-provider-error-canary"
        val authentication = interactionFailure("UNAUTHENTICATED", "401", canary)
        val permission = interactionFailure("PERMISSION_DENIED", "403", canary)
        val client = interactionFailure("INVALID_ARGUMENT", "400", canary)
        val rateLimit = interactionFailure("RESOURCE_EXHAUSTED", "429", canary)
        val timeout = interactionFailure("DEADLINE_EXCEEDED", "504", canary)
        val cancellation = interactionFailure("CANCELLED", "499", canary)
        val unavailable = interactionFailure("UNAVAILABLE", "503", canary)
        val unknown = interactionFailure("FUTURE_GOOGLE_STATUS", "unknown", canary)

        assertEquals(401, assertIs<ProviderAuthException>(authentication).statusCode)
        assertEquals(403, assertIs<ProviderAuthException>(permission).statusCode)
        assertEquals(400, assertIs<ProviderClientException>(client).statusCode)
        assertEquals(429, assertIs<ProviderRateLimitException>(rateLimit).statusCode)
        assertEquals(
            ProviderTimeoutPhase.PROVIDER_CALL,
            assertIs<ProviderTimeoutException>(timeout).phase,
        )
        assertIs<ProviderNetworkException>(cancellation)
        assertEquals(503, assertIs<ProviderServerException>(unavailable).statusCode)
        assertEquals(500, assertIs<ProviderServerException>(unknown).statusCode)
        listOf(
            authentication,
            permission,
            client,
            rateLimit,
            timeout,
            cancellation,
            unavailable,
            unknown,
        ).forEach { failure ->
            assertFalse(failure.toString().contains(canary))
        }
    }

    @Test
    fun numericGoogleErrorCodeMapsWithoutRequiringAStatusField() {
        val failure = assertFailsWith<ProviderRateLimitException> {
            GeminiInteractionsCodec(MODEL).decodeServerSentEvent(
                "error",
                """{
  "event_type":"error",
  "error":{"code":429,"message":"quota exhausted"}
}""",
            )
        }

        assertEquals(429, failure.statusCode)
    }

    @Test
    fun incompleteStatusMapsToMaxTokensForStreamingAndNonStreaming() {
        val streaming = decodeStreaming(
            """event: interaction.created
data: {"interaction":{"id":"v1_incomplete","model":"$MODEL","status":"in_progress"},"event_type":"interaction.created"}

event: step.start
data: {"index":0,"step":{"type":"model_output"},"event_type":"step.start"}

event: step.delta
data: {"index":0,"delta":{"type":"text","text":"truncated"},"event_type":"step.delta"}

event: step.stop
data: {"index":0,"event_type":"step.stop"}

event: interaction.completed
data: {"interaction":{"id":"v1_incomplete","status":"incomplete","usage":{"total_input_tokens":4,"total_output_tokens":8}},"event_type":"interaction.completed"}

event: done
data: [DONE]
""",
        )
        val nonStreaming = GeminiInteractionsCodec(MODEL).decodeNonStreaming(
            """{
  "id":"v1_incomplete",
  "model":"$MODEL",
  "status":"incomplete",
  "steps":[{"type":"model_output","content":[{"type":"text","text":"truncated"}]}],
  "usage":{"total_input_tokens":4,"total_output_tokens":8}
}""",
        ).events

        listOf(streaming, nonStreaming).forEach { events ->
            val completed = events.filterIsInstance<ProviderEvent.Completed>().single()
            assertEquals(StopReason.MAX_TOKENS, completed.stopReason)
            assertEquals(4, completed.usage?.inputTokens)
            assertEquals(8, completed.usage?.outputTokens)
        }
    }

    @Test
    fun nonStreamingResponseMustMatchRequestedModel() {
        assertFailsWith<ProviderProtocolException> {
            GeminiInteractionsCodec(MODEL).decodeNonStreaming(
                """{
  "id":"v1_wrong_model",
  "model":"different-model",
  "status":"completed",
  "steps":[{"type":"model_output","content":[{"type":"text","text":"wrong source"}]}]
}""",
            )
        }
    }

    @Test
    fun storeFalseAllowsPresentButEmptyInteractionIdWithoutPersistingIt() {
        val events = decodeStreaming(
            """event: interaction.created
data: {"interaction":{"id":"","model":"$MODEL","status":"in_progress"},"event_type":"interaction.created"}

event: interaction.status_update
data: {"interaction_id":"","status":"in_progress","event_type":"interaction.status_update"}

event: step.start
data: {"index":0,"step":{"type":"model_output"},"event_type":"step.start"}

event: step.delta
data: {"index":0,"delta":{"type":"text","text":"stateless"},"event_type":"step.delta"}

event: step.stop
data: {"index":0,"event_type":"step.stop"}

event: interaction.completed
data: {"interaction":{"id":"","status":"completed"},"event_type":"interaction.completed"}

event: done
data: [DONE]
""",
        )

        val completed = events.filterIsInstance<ProviderEvent.Completed>().single()
        assertEquals(StopReason.COMPLETED, completed.stopReason)
        assertFalse(completed.providerMetadata!!.containsKey(GEMINI_INTERACTION_ID_METADATA))

        val mismatch = GeminiInteractionsCodec(MODEL)
        mismatch.decodeServerSentEvent(
            "interaction.created",
            """{"interaction":{"id":"","model":"$MODEL","status":"in_progress"},"event_type":"interaction.created"}""",
        )
        assertFailsWith<ProviderProtocolException> {
            mismatch.decodeServerSentEvent(
                "interaction.status_update",
                """{"interaction_id":"unexpected","status":"in_progress","event_type":"interaction.status_update"}""",
            )
        }
    }

    @Test
    fun emptyThoughtSignatureDeltaIsIgnoredUntilARealSignatureArrives() {
        val events = decodeStreaming(
            """event: interaction.created
data: {"interaction":{"id":"","model":"$MODEL","status":"in_progress"},"event_type":"interaction.created"}

event: step.start
data: {"index":0,"step":{"type":"thought"},"event_type":"step.start"}

event: step.delta
data: {"index":0,"delta":{"type":"thought_summary","content":{"type":"text","text":"summary"}},"event_type":"step.delta"}

event: step.delta
data: {"index":0,"delta":{"type":"thought_signature","signature":""},"event_type":"step.delta"}

event: step.delta
data: {"index":0,"delta":{"type":"thought_signature","signature":"real-signature"},"event_type":"step.delta"}

event: step.stop
data: {"index":0,"event_type":"step.stop"}

event: step.start
data: {"index":1,"step":{"type":"model_output"},"event_type":"step.start"}

event: step.delta
data: {"index":1,"delta":{"type":"text","text":"done"},"event_type":"step.delta"}

event: step.stop
data: {"index":1,"event_type":"step.stop"}

event: interaction.completed
data: {"interaction":{"id":"","status":"completed"},"event_type":"interaction.completed"}

event: done
data: [DONE]
""",
        )

        assertEquals("real-signature", events.filterIsInstance<ProviderEvent.ReasoningEnd>().single().signature)
    }

    private fun decodeStreaming(
        transcript: String,
        codec: GeminiInteractionsCodec = GeminiInteractionsCodec(MODEL),
    ): List<ProviderEvent> = sseFrames(transcript).flatMap { frame ->
        when (frame) {
            is HttpStreamFrame.ServerSentEvent -> codec.decodeServerSentEvent(frame.event, frame.data)?.events.orEmpty()
            HttpStreamFrame.Completed -> codec.finish().let { emptyList() }
            else -> emptyList()
        }
    }

    private fun interactionFailure(
        status: String,
        code: String,
        message: String,
    ): ProviderException = assertFailsWith<ProviderException> {
        GeminiInteractionsCodec(MODEL).decodeServerSentEvent(
            "error",
            """{
  "event_type":"error",
  "error":{"status":"$status","code":"$code","message":"$message"}
}""",
        )
    }

    private companion object {
        const val MODEL = "gemini-contract-model"
    }
}
