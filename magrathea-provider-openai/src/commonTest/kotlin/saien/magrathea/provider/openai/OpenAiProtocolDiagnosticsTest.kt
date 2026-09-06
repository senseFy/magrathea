package saien.magrathea.provider.openai

import kotlin.test.*
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import saien.magrathea.core.ModelDescriptor
import saien.magrathea.core.ProviderCredential
import saien.magrathea.provider.api.*

class OpenAiProtocolDiagnosticsTest {
    @Test
    fun malformedOpenRouterTypeReachesTheStructuredFieldCheck() = runTest {
        for (type in listOf("{}", "[]")) {
            val failure = assertFailsWith<ProviderProtocolException> {
                collect(listOf("response.created" to """{"type":$type,"private":"private-canary"}"""))
            }
            assertEquals(ProviderProtocolDiagnostic(
                "openai.responses.invalid_string_field", "response.created", 1, "type",
            ), failure.diagnostic)
            assertFalse(failure.diagnostic.toString().contains("private"))
        }
    }

    @Test
    fun nonStreamingFailureDoesNotInventAnSseOrdinal() = runTest {
        val adapter = OpenAiProviderAdapter(transport = ScriptedOpenAiTransport(
            executeResponses = listOf(HttpResponseSpec(200, body = """{"status":"completed","private":"private-canary"}""")),
        ))
        val failure = assertFailsWith<ProviderProtocolException> {
            adapter.generate(ProviderRequest(
                model = ModelDescriptor("openai", "model", supportsStreaming = false), messages = emptyList(),
                credential = ProviderCredential("private-credential"),
            )).toList()
        }
        assertEquals(ProviderProtocolDiagnostic(
            "openai.responses.invalid_string_field", "non_streaming", field = "id",
        ), failure.diagnostic)
        assertFalse(failure.diagnostic.toString().contains("private"))
    }

    @Test
    fun openRouterTerminalMismatchHasAReasonAndTheActualSseOrdinal() = runTest {
        val stream = OPENAI_TEXT_STREAM.map { (type, data) ->
            type to if (type == "response.completed") data.replace("Shanghai", "private-content") else data
        }
        val failure = assertFailsWith<ProviderProtocolException> { collect(stream) }
        assertEquals(ProviderProtocolDiagnostic(
            "openai.responses.terminal_output_mismatch", "response.completed", stream.size.toLong(),
        ), failure.diagnostic)
        assertFalse(failure.retryable)
        assertFalse(failure.diagnostic.toString().contains("private-content"))
    }

    @Test
    fun textCompletionAndTerminalMismatchHaveDifferentReasons() = runTest {
        val stream = OPENAI_TEXT_STREAM.map { (type, data) ->
            type to if (type == "response.output_text.done") data.replace("Shanghai", "private-content") else data
        }
        val failure = assertFailsWith<ProviderProtocolException> { collect(stream) }
        assertEquals(ProviderProtocolDiagnostic(
            "openai.responses.text_delta_mismatch", "response.output_text.done", 6,
        ), failure.diagnostic)
    }

    @Test
    fun missingFieldsNameOnlyTheCodeDefinedField() = runTest {
        val failure = assertFailsWith<ProviderProtocolException> {
            collect(listOf("response.created" to """{"type":"response.created","response":{"status":"in_progress"}}"""))
        }
        assertEquals(ProviderProtocolDiagnostic(
            "openai.responses.invalid_string_field", "response.created", 1, "id",
        ), failure.diagnostic)
    }

    @Test
    fun unknownEventTypesNeverLeakWireValuesIntoDiagnostics() = runTest {
        val canary = "private-token-that-looks-like-an-identifier"
        val failure = assertFailsWith<ProviderProtocolException> {
            collect(listOf(canary to """{"type":"$canary"}"""))
        }
        assertEquals(ProviderProtocolDiagnostic(
            "openai.responses.unsupported_event_type", "unknown", 1,
        ), failure.diagnostic)
        assertFalse(failure.diagnostic.toString().contains(canary))
    }

    @Test
    fun malformedOpenRouterFramesKeepNormalizerReasonWithoutParserMessage() = runTest {
        val failure = assertFailsWith<ProviderProtocolException> {
            collect(listOf("response.completed" to "{private-invalid-json"))
        }
        assertEquals(ProviderProtocolDiagnostic(
            "openrouter.responses.malformed_json", "response.completed", 1,
        ), failure.diagnostic)
        assertFalse(failure.diagnostic.toString().contains("private"))
    }

    @Test
    fun cleanEofPreservesItsReasonAndExistingRetryability() = runTest {
        val failure = assertFailsWith<ProviderStreamInterruptedException> {
            collect(OPENAI_TEXT_STREAM.dropLast(1))
        }
        assertTrue(failure.retryable)
        var protocolCause: Throwable? = failure.cause
        while (protocolCause is ProviderStreamInterruptedException) protocolCause = protocolCause.cause
        assertEquals(ProviderProtocolDiagnostic(
            "openai.responses.missing_terminal_event", "stream_end",
        ), (protocolCause as ProviderProtocolException).diagnostic)
    }

    @Test
    fun chatCompletionsMalformedFramesUseTheSameDiagnosticContract() = runTest {
        val adapter = OpenAiProviderAdapter(transport = ScriptedOpenAiTransport(
            streamResponses = listOf(openAiChatSseFrames(listOf("{private-invalid-json"))),
        ))
        val failure = assertFailsWith<ProviderProtocolException> {
            adapter.generate(ProviderRequest(
                model = ModelDescriptor("openai", "model", supportsStreaming = true), messages = emptyList(),
                credential = ProviderCredential("private-credential"),
                typedConfig = OpenAiTransportConfig(protocol = OpenAiWireProtocol.CHAT_COMPLETIONS),
            )).toList()
        }
        assertEquals(ProviderProtocolDiagnostic(
            "openai.chat.malformed_json", "chat_completion_chunk", 1,
        ), failure.diagnostic)
    }

    private suspend fun collect(stream: List<Pair<String, String>>) {
        val adapter = OpenAiProviderAdapter(
            profile = OpenAiProviderProfile.openRouter(),
            transport = ScriptedOpenAiTransport(streamResponses = listOf(openAiSseFrames(stream))),
        )
        adapter.generate(ProviderRequest(
            model = ModelDescriptor("openrouter", "model", supportsStreaming = true), messages = emptyList(),
            credential = ProviderCredential("private-credential"),
            typedConfig = OpenAiTransportConfig(protocol = OpenAiWireProtocol.RESPONSES),
        )).toList()
    }
}
