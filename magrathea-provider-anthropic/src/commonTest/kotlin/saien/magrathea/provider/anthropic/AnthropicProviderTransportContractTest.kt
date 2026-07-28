package saien.magrathea.provider.anthropic

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
import saien.magrathea.provider.api.AnthropicAuthentication
import saien.magrathea.provider.api.AnthropicTransportConfig
import saien.magrathea.provider.api.HttpStreamFormat
import saien.magrathea.provider.api.HttpResponseSpec
import saien.magrathea.provider.api.ProviderAuthException
import saien.magrathea.provider.api.ProviderEvent
import saien.magrathea.provider.api.ProviderProtocolException
import saien.magrathea.provider.api.ProviderRequest

class AnthropicProviderTransportContractTest {
    @Test
    fun adapterUsesNamedSseAndKeepsCredentialOutOfBodyAndDiagnostics() = runTest {
        val secret = "anthropic-secret-canary"
        val transport = ScriptedAnthropicTransport(streamResponses = listOf(anthropicSseFrames(ANTHROPIC_TOOL_STREAM)))
        val adapter = AnthropicProviderAdapter(transport = transport)

        val chunks = adapter.generate(request(true, secret)).toList()

        assertEquals(1, chunks.flatMap { it.events }.filterIsInstance<ProviderEvent.Completed>().size)
        val (outbound, format) = transport.requests.single()
        assertEquals(HttpStreamFormat.SERVER_SENT_EVENTS, format)
        assertEquals(secret, outbound.headers.single { it.name == "x-api-key" }.value)
        assertEquals("2023-06-01", outbound.headers.single { it.name == "anthropic-version" }.value)
        assertFalse(outbound.body.orEmpty().contains(secret))
        assertFalse(outbound.toString().contains(secret))
        adapter.close()
        assertTrue(transport.closed)
    }

    @Test
    fun missingCredentialAndMissingProtocolTerminalFailClosed() = runTest {
        assertFailsWith<ProviderAuthException> {
            AnthropicProviderAdapter(transport = ScriptedAnthropicTransport())
                .generate(request(false, null)).toList()
        }

        val incomplete = ScriptedAnthropicTransport(
            streamResponses = listOf(anthropicSseFrames(ANTHROPIC_TOOL_STREAM.dropLast(1))),
        )
        assertFailsWith<ProviderProtocolException> {
            AnthropicProviderAdapter(transport = incomplete)
                .generate(request(true, "secret")).toList()
        }
    }

    @Test
    fun credentialEndpointAndBearerAuthenticationConfigureCompatibleMessagesService() = runTest {
        val secret = "compatible-anthropic-secret"
        val endpoint = "https://compatible.example.test/api/messages"
        val transport = ScriptedAnthropicTransport(
            executeResponses = listOf(HttpResponseSpec(200, body = ANTHROPIC_TEXT_RESPONSE)),
        )

        AnthropicProviderAdapter(transport = transport).generate(
            request(streaming = false, secret = secret).copy(
                credential = ProviderCredential(
                    value = secret,
                    endpoint = endpoint,
                ),
                headers = mapOf(
                    "Authorization" to "Bearer request-value-must-not-win",
                    "x-api-key" to "request-value-must-not-win",
                ),
                typedConfig = AnthropicTransportConfig(authentication = AnthropicAuthentication.BEARER),
            ),
        ).toList()

        val outbound = transport.requests.single().first
        assertEquals(endpoint, outbound.url)
        assertEquals("Bearer $secret", outbound.headers.single { it.name == "Authorization" }.value)
        assertFalse(outbound.headers.any { it.name.equals("x-api-key", ignoreCase = true) })
        assertEquals("2023-06-01", outbound.headers.single { it.name == "anthropic-version" }.value)
        assertFalse(outbound.headers.any { it.value.contains("request-value-must-not-win") })
    }

    @Test
    fun transientRequestEndpointOverridesCredentialEndpoint() = runTest {
        val transport = ScriptedAnthropicTransport(
            executeResponses = listOf(HttpResponseSpec(200, body = ANTHROPIC_TEXT_RESPONSE)),
        )

        AnthropicProviderAdapter(transport = transport).generate(
            request(streaming = false, secret = "secret").copy(
                endpoint = "https://request.example.test/messages",
                credential = ProviderCredential(
                    value = "secret",
                    endpoint = "https://credential.example.test/messages",
                ),
            ),
        ).toList()

        assertEquals("https://request.example.test/messages", transport.requests.single().first.url)
    }

    @Test
    fun thinkingControlsArePayloadOnlyAndNeverOutboundHeaders() = runTest {
        val transport = ScriptedAnthropicTransport(
            executeResponses = listOf(HttpResponseSpec(200, body = ANTHROPIC_TEXT_RESPONSE)),
        )
        AnthropicProviderAdapter(transport = transport).generate(
            request(false, "secret").copy(
                headers = mapOf(
                    "anthropic-thinking-mode" to "adaptive",
                    "anthropic-effort" to "medium",
                    "X-Gateway-Trace" to "trace-1",
                ),
            ),
        ).toList()

        val names = transport.requests.single().first.headers.map { it.name.lowercase() }
        assertFalse("anthropic-thinking-mode" in names)
        assertFalse("anthropic-effort" in names)
        assertTrue("x-gateway-trace" in names)
    }

    private fun request(streaming: Boolean, secret: String?): ProviderRequest = ProviderRequest(
        model = ModelDescriptor("anthropic", "claude-contract", supportsStreaming = streaming),
        messages = listOf(AgentMessage(role = MessageRole.USER, parts = listOf(TextPart("Weather?")))),
        credentialRef = CredentialRef("anthropic"),
        credential = secret?.let(::ProviderCredential),
    )
}
