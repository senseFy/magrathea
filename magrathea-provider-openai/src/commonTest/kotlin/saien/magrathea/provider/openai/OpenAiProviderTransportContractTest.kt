package saien.magrathea.provider.openai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import saien.magrathea.core.AgentMessage
import saien.magrathea.core.CredentialRef
import saien.magrathea.core.MessageRole
import saien.magrathea.core.ModelDescriptor
import saien.magrathea.core.ProviderCredential
import saien.magrathea.core.TextPart
import saien.magrathea.provider.api.HttpStreamFormat
import saien.magrathea.provider.api.HttpResponseSpec
import saien.magrathea.provider.api.OpenAiApi
import saien.magrathea.provider.api.OpenAiAuthentication
import saien.magrathea.provider.api.OpenAiTransportConfig
import saien.magrathea.provider.api.OpenAiXSearchToolConfig
import saien.magrathea.provider.api.ProviderAuthException
import saien.magrathea.provider.api.ProviderEvent
import saien.magrathea.provider.api.ProviderProtocolException
import saien.magrathea.provider.api.ProviderRequest

class OpenAiProviderTransportContractTest {
    @Test
    fun adapterUsesResponsesSseAndKeepsCredentialOutOfBodyAndDiagnostics() = runTest {
        val secret = "openai-secret-canary"
        val transport = ScriptedOpenAiTransport(streamResponses = listOf(openAiSseFrames(OPENAI_TOOL_STREAM)))
        val adapter = OpenAiProviderAdapter(transport = transport)

        val chunks = adapter.generate(request(streaming = true, secret = secret)).toList()

        assertEquals(1, chunks.flatMap { it.events }.filterIsInstance<ProviderEvent.Completed>().size)
        val (outbound, format) = transport.requests.single()
        assertEquals("https://api.openai.com/v1/responses", outbound.url)
        assertEquals(HttpStreamFormat.SERVER_SENT_EVENTS, format)
        assertEquals(600_000, outbound.timeouts?.requestTimeoutMillis)
        assertEquals(15_000, outbound.timeouts?.connectTimeoutMillis)
        assertEquals(120_000, outbound.timeouts?.socketTimeoutMillis)
        assertEquals("Bearer $secret", outbound.headers.single { it.name == "Authorization" }.value)
        assertFalse(outbound.body.orEmpty().contains(secret))
        assertFalse(outbound.toString().contains(secret))
        adapter.close()
        assertTrue(transport.closed)
    }

    @Test
    fun adapterRejectsMissingOrForeignCredential() = runTest {
        val adapter = OpenAiProviderAdapter(transport = ScriptedOpenAiTransport())
        assertFailsWith<ProviderAuthException> {
            adapter.generate(request(streaming = false, secret = null)).toList()
        }
        assertFailsWith<ProviderAuthException> {
            adapter.generate(
                request(streaming = false, secret = "secret").copy(credentialRef = CredentialRef("anthropic")),
            ).toList()
        }
    }

    @Test
    fun credentialEndpointAndAuthenticationConfigureCompatibleResponsesService() = runTest {
        val secret = "compatible-openai-secret"
        val endpoint = "https://compatible.example.test/api/responses"
        val transport = ScriptedOpenAiTransport(
            executeResponses = listOf(HttpResponseSpec(200, body = OPENAI_TEXT_RESPONSE)),
        )

        OpenAiProviderAdapter(transport = transport).generate(
            request(streaming = false, secret = secret).copy(
                credential = ProviderCredential(
                    value = secret,
                    endpoint = endpoint,
                ),
                headers = mapOf(
                    "Authorization" to "Bearer request-value-must-not-win",
                    "api-key" to "request-value-must-not-win",
                ),
                typedConfig = OpenAiTransportConfig(authentication = OpenAiAuthentication.API_KEY),
            ),
        ).toList()

        val outbound = transport.requests.single().first
        assertEquals(endpoint, outbound.url)
        assertEquals(secret, outbound.headers.single { it.name == "api-key" }.value)
        assertFalse(outbound.headers.any { it.name.equals("Authorization", ignoreCase = true) })
        assertFalse(outbound.headers.any { it.value.contains("request-value-must-not-win") })
    }

    @Test
    fun xSearchConfigurationAllowsProviderManagedCustomToolTrace() = runTest {
        val transport = ScriptedOpenAiTransport(
            executeResponses = listOf(HttpResponseSpec(200, body = XAI_HOSTED_X_SEARCH_RESPONSE)),
        )

        val chunks = OpenAiProviderAdapter(transport = transport).generate(
            request(streaming = false, secret = "secret").copy(
                typedConfig = OpenAiTransportConfig(
                    hostedTools = listOf(OpenAiXSearchToolConfig()),
                ),
            ),
        ).toList()

        assertEquals(0, chunks.flatMap { it.events }.filterIsInstance<ProviderEvent.ToolCallStart>().size)
        assertEquals(1, chunks.flatMap { it.events }.filterIsInstance<ProviderEvent.Completed>().size)
    }

    @Test
    fun chatCompletionsModeUsesItsOwnEndpointPayloadAndCodec() = runTest {
        val secret = "compatible-chat-secret"
        val transport = ScriptedOpenAiTransport(
            streamResponses = listOf(openAiChatSseFrames(OPENAI_CHAT_TOOL_STREAM)),
        )

        val chunks = OpenAiProviderAdapter(transport = transport).generate(
            request(streaming = true, secret = secret).copy(
                model = ModelDescriptor("openai", "compatible-model", supportsStreaming = true),
                typedConfig = OpenAiTransportConfig(api = OpenAiApi.CHAT_COMPLETIONS),
            ),
        ).toList()

        val outbound = transport.requests.single().first
        assertEquals("https://api.openai.com/v1/chat/completions", outbound.url)
        assertTrue(outbound.body.orEmpty().contains("\"messages\""))
        assertFalse(outbound.body.orEmpty().contains("\"input\""))
        assertFalse(outbound.body.orEmpty().contains(secret))
        assertEquals(1, chunks.flatMap { it.events }.filterIsInstance<ProviderEvent.ToolCallEnd>().size)
        assertEquals(1, chunks.flatMap { it.events }.filterIsInstance<ProviderEvent.Completed>().size)
    }

    @Test
    fun transientRequestEndpointOverridesCredentialEndpoint() = runTest {
        val transport = ScriptedOpenAiTransport(
            executeResponses = listOf(HttpResponseSpec(200, body = OPENAI_TEXT_RESPONSE)),
        )

        OpenAiProviderAdapter(transport = transport).generate(
            request(streaming = false, secret = "secret").copy(
                endpoint = "https://request.example.test/responses",
                credential = ProviderCredential(
                    value = "secret",
                    endpoint = "https://credential.example.test/responses",
                ),
            ),
        ).toList()

        assertEquals("https://request.example.test/responses", transport.requests.single().first.url)
    }

    @Test
    fun semanticControlHeadersNeverLeaveTheProcess() = runTest {
        val transport = ScriptedOpenAiTransport(
            executeResponses = listOf(HttpResponseSpec(200, body = OPENAI_TEXT_RESPONSE)),
        )
        OpenAiProviderAdapter(transport = transport).generate(
            request(streaming = false, secret = "secret").copy(
                headers = mapOf(
                    "instructions" to "semantic-control",
                    "reasoning-effort" to "high",
                    "X-Gateway-Trace" to "trace-1",
                ),
            ),
        ).toList()

        val names = transport.requests.single().first.headers.map { it.name.lowercase() }
        assertFalse("instructions" in names)
        assertFalse("reasoning-effort" in names)
        assertTrue("x-gateway-trace" in names)
    }

    @Test
    fun adapterRejectsStreamWithoutProtocolOrTransportTerminal() = runTest {
        val noProtocolTerminal = ScriptedOpenAiTransport(
            streamResponses = listOf(openAiSseFrames(OPENAI_TOOL_STREAM.dropLast(1))),
        )
        assertFailsWith<ProviderProtocolException> {
            OpenAiProviderAdapter(transport = noProtocolTerminal)
                .generate(request(streaming = true, secret = "secret"))
                .toList()
        }

        val noTransportTerminal = ScriptedOpenAiTransport(
            streamResponses = listOf(openAiSseFrames(OPENAI_TOOL_STREAM).dropLast(1)),
        )
        assertFailsWith<ProviderProtocolException> {
            OpenAiProviderAdapter(transport = noTransportTerminal)
                .generate(request(streaming = true, secret = "secret"))
                .toList()
        }
    }

    private fun request(streaming: Boolean, secret: String?): ProviderRequest = ProviderRequest(
        model = ModelDescriptor("openai", "gpt-contract", supportsStreaming = streaming),
        messages = listOf(AgentMessage(role = MessageRole.USER, parts = listOf(TextPart("Weather?")))),
        credentialRef = CredentialRef("openai"),
        credential = secret?.let(::ProviderCredential),
    )
}
