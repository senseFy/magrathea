@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package saien.magrathea.provider.gateway

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import saien.magrathea.core.AgentMessage
import saien.magrathea.core.AgentSessionId
import saien.magrathea.core.CredentialRef
import saien.magrathea.core.MessageRole
import saien.magrathea.core.ModelDescriptor
import saien.magrathea.core.ProviderCredential
import saien.magrathea.core.StopReason
import saien.magrathea.core.TextPart
import saien.magrathea.gateway.protocol.GATEWAY_IDEMPOTENCY_HEADER
import saien.magrathea.gateway.protocol.GATEWAY_PROTOCOL_VERSION
import saien.magrathea.gateway.protocol.GATEWAY_SSE_EVENT
import saien.magrathea.gateway.protocol.GATEWAY_VERSION_HEADER
import saien.magrathea.gateway.protocol.GatewayCreateStreamRequest
import saien.magrathea.gateway.protocol.GatewayEvent
import saien.magrathea.gateway.protocol.GatewayFailureCode
import saien.magrathea.gateway.protocol.GatewayProtocolCodec
import saien.magrathea.gateway.protocol.GatewayStreamDescriptor
import saien.magrathea.gateway.protocol.GatewayStreamEnvelope
import saien.magrathea.provider.api.HttpHeader
import saien.magrathea.provider.api.HttpMethod
import saien.magrathea.provider.api.HttpRequestSpec
import saien.magrathea.provider.api.HttpResponseSpec
import saien.magrathea.provider.api.HttpStreamFormat
import saien.magrathea.provider.api.HttpStreamFrame
import saien.magrathea.provider.api.HttpTransport
import saien.magrathea.provider.api.ProviderInvocation
import saien.magrathea.provider.api.ProviderContextLimitException
import saien.magrathea.provider.api.ProviderProtocolException
import saien.magrathea.provider.api.ProviderRequest

class GatewayProviderAdapterTest {
    private val codec = GatewayProtocolCodec()
    private val descriptor = GatewayStreamDescriptor(
        streamId = "stream-1",
        requestId = "session-1:0",
        sessionId = "session-1",
        expiresAtEpochMs = 10_000,
    )

    @Test
    fun gatewayBaseUrlAcceptsOnlyCanonicalHttpsOrExactLoopbackHttpAuthorities() {
        listOf(
            "https://gateway.example",
            "https://gateway.example:8443/api",
            "https://[2001:db8::1]/gateway-v1",
            "http://localhost:8080",
            "http://127.0.0.1",
            "http://[::1]:18081/api",
        ).forEach { value ->
            assertEquals(value, GatewayProviderConfig(value).normalizedBaseUrl)
        }
        assertEquals(
            "https://gateway.example/api",
            GatewayProviderConfig("https://gateway.example/api///").normalizedBaseUrl,
        )

        listOf(
            "http://gateway.example",
            "http://127.0.0.1.evil.example",
            "https://user@gateway.example",
            "https://gateway.example:0",
            "https://gateway.example:65536",
            "https://gateway.example:not-a-port",
            "https://gateway.example:",
            "https://[broken",
            "https://[::::]",
            "https://[1:2:3:4:5:6:7:8:9]",
            "https://[::1]suffix",
            "https://gateway_example",
            "https://gateway.example/api//nested",
            "https://gateway.example/api/../admin",
            "https://gateway.example/api%2Fadmin",
            "https://gateway.example?target=other",
            "https://gateway.example#fragment",
            "HTTPS://gateway.example",
        ).forEach { value ->
            assertFailsWith<IllegalArgumentException>(value) { GatewayProviderConfig(value) }
        }
    }

    @Test
    fun gatewayConfigToStringOmitsTheConfiguredUrl() {
        val canary = "gateway-url-canary"
        val rendered = GatewayProviderConfig("https://gateway.example/$canary").toString()

        assertFalse(rendered.contains(canary))
        assertTrue(rendered.contains("baseUrl=<configured>"))
    }

    @Test
    fun networkReconnectUsesLastSequenceAndEmitsCanonicalEventsWithoutDuplicates() = runTest {
        var reconnectPermissions = 0
        val transport = RecordingTransport(codec, descriptor).apply {
            streamScripts += { request ->
                flow {
                    emit(responseStarted())
                    emit(sse(envelope(0, GatewayEvent.StreamOpened())))
                    emit(sse(envelope(1, GatewayEvent.TextDelta("hel"))))
                    throw saien.magrathea.provider.api.ProviderNetworkException("disconnected")
                }
            }
            streamScripts += { request ->
                flow {
                    emit(responseStarted())
                    emit(sse(envelope(2, GatewayEvent.TextDelta("lo"))))
                    emit(sse(envelope(3, GatewayEvent.Completed(stopReason = StopReason.COMPLETED))))
                    emit(HttpStreamFrame.Completed)
                }
            }
        }
        val adapter = adapter(
            transport,
            reconnectGate = GatewayReconnectGate { reconnectPermissions += 1 },
        )

        val chunks = adapter.generate(providerRequest()).toList()

        assertEquals(3, chunks.size)
        assertEquals(listOf("hel", "lo"), chunks.flatMap { it.events }.filterIsInstance<saien.magrathea.provider.api.ProviderEvent.TextDelta>().map { it.delta })
        assertIs<saien.magrathea.provider.api.ProviderEvent.Completed>(chunks.last().events.single())
        assertTrue(transport.streamRequests[0].url.endsWith("afterSequence=-1"))
        assertTrue(transport.streamRequests[1].url.endsWith("afterSequence=1"))
        assertEquals(1, transport.executeRequests.count { it.method == HttpMethod.POST })
        assertEquals(1, reconnectPermissions)
        val create = codec.decodeCreateRequest(transport.executeRequests.single { it.method == HttpMethod.POST }.body!!)
        assertEquals("session-1:0", create.requestId)
        assertFalse(transport.executeRequests.single().body!!.contains("credential", ignoreCase = true))
    }

    @Test
    fun gatewayContextLimitMapsBackToRecoverableProviderFailure() = runTest {
        val transport = RecordingTransport(codec, descriptor).apply {
            streamScripts += {
                flow {
                    emit(responseStarted())
                    emit(sse(envelope(0, GatewayEvent.StreamOpened())))
                    emit(
                        sse(
                            envelope(
                                1,
                                GatewayEvent.Failed(
                                    code = GatewayFailureCode.CONTEXT_LIMIT,
                                    retryable = false,
                                ),
                            ),
                        ),
                    )
                    emit(HttpStreamFrame.Completed)
                }
            }
        }

        assertFailsWith<ProviderContextLimitException> {
            adapter(transport).generate(providerRequest()).toList()
        }
    }

    @Test
    fun localCancellationSendsAuthenticatedIdempotentRemoteDelete() = runTest {
        val transport = RecordingTransport(codec, descriptor).apply {
            streamScripts += {
                flow {
                    emit(responseStarted())
                    emit(sse(envelope(0, GatewayEvent.StreamOpened())))
                    awaitCancellation()
                }
            }
        }
        val adapter = adapter(transport)
        val collection = backgroundScope.async { adapter.generate(providerRequest()).toList() }
        runCurrent()

        collection.cancelAndJoin()

        val delete = transport.executeRequests.single { it.method == HttpMethod.DELETE }
        assertTrue(delete.url.endsWith("/v1/streams/stream-1"))
        assertEquals("Bearer browser-session", delete.headers.first { it.name == "Authorization" }.value)
    }

    @Test
    fun directProviderCredentialEndpointHeaderAndTypedConfigAreRejectedBeforeNetwork() = runTest {
        val transport = RecordingTransport(codec, descriptor)
        val adapter = adapter(transport)

        assertFailsWith<ProviderProtocolException> {
            adapter.generate(providerRequest().copy(credential = ProviderCredential("vendor-secret"))).toList()
        }
        assertFailsWith<ProviderProtocolException> {
            adapter.generate(providerRequest().copy(credentialRef = CredentialRef("gemini"))).toList()
        }
        assertFailsWith<ProviderProtocolException> {
            adapter.generate(providerRequest().copy(endpoint = "https://vendor.example")).toList()
        }
        assertFailsWith<ProviderProtocolException> {
            adapter.generate(providerRequest().copy(headers = mapOf("X-Api-Key" to "secret"))).toList()
        }
        assertTrue(transport.executeRequests.isEmpty())
    }

    @Test
    fun strictSequenceAndSseIdentityFailuresDoNotReconnect() = runTest {
        val transport = RecordingTransport(codec, descriptor).apply {
            streamScripts += {
                flow {
                    emit(responseStarted())
                    emit(sse(envelope(0, GatewayEvent.StreamOpened())))
                    emit(sse(envelope(2, GatewayEvent.TextDelta("gap"))))
                }
            }
        }

        assertFailsWith<saien.magrathea.gateway.protocol.GatewayProtocolException> {
            adapter(transport).generate(providerRequest()).toList()
        }
        assertEquals(1, transport.streamRequests.size)
    }

    @Test
    fun sessionHeadersNeverRenderBearerOrCsrfValues() {
        val authorization = "Bearer secret-canary"
        val csrf = "csrf-secret-canary"

        val rendered = GatewaySessionHeaders(authorization, csrf).toString()

        assertFalse(rendered.contains(authorization))
        assertFalse(rendered.contains(csrf))
        assertTrue(rendered.contains("<redacted>"))
    }

    private fun adapter(
        transport: HttpTransport,
        reconnectGate: GatewayReconnectGate = GatewayReconnectGate { },
    ) = GatewayProviderAdapter(
        key = "gemini",
        config = GatewayProviderConfig(
            baseUrl = "https://gateway.example",
            initialReconnectDelayMillis = 1,
            maxReconnectDelayMillis = 4,
        ),
        sessionHeadersProvider = GatewaySessionHeadersProvider {
            GatewaySessionHeaders(authorization = "Bearer browser-session", csrfToken = "csrf")
        },
        reconnectGate = reconnectGate,
        transport = transport,
        closeTransport = false,
        codec = codec,
    )

    private fun providerRequest() = ProviderRequest(
        invocation = ProviderInvocation("session-1:0", AgentSessionId("session-1"), turn = 0),
        model = ModelDescriptor(provider = "gemini", model = "gemini-test", supportsStreaming = true),
        messages = listOf(
            AgentMessage(
                id = "message-1",
                role = MessageRole.USER,
                parts = listOf(TextPart("hello")),
                createdAtEpochMs = 1,
            ),
        ),
    )

    private fun envelope(sequence: Long, event: GatewayEvent) = GatewayStreamEnvelope(
        streamId = descriptor.streamId,
        requestId = descriptor.requestId,
        sessionId = descriptor.sessionId,
        sequence = sequence,
        event = event,
    )

    private fun responseStarted() = HttpStreamFrame.ResponseStarted(
        statusCode = 200,
        headers = listOf(
            HttpHeader("Content-Type", "text/event-stream"),
            HttpHeader(GATEWAY_VERSION_HEADER, GATEWAY_PROTOCOL_VERSION.toString()),
        ),
    )

    private fun sse(envelope: GatewayStreamEnvelope) = HttpStreamFrame.ServerSentEvent(
        event = GATEWAY_SSE_EVENT,
        data = codec.encodeEnvelope(envelope),
        id = envelope.sequence.toString(),
    )

    private class RecordingTransport(
        private val codec: GatewayProtocolCodec,
        private val descriptor: GatewayStreamDescriptor,
    ) : HttpTransport {
        val executeRequests = mutableListOf<HttpRequestSpec>()
        val streamRequests = mutableListOf<HttpRequestSpec>()
        val streamScripts = mutableListOf<(HttpRequestSpec) -> Flow<HttpStreamFrame>>()

        override suspend fun execute(request: HttpRequestSpec): HttpResponseSpec {
            executeRequests += request
            return when (request.method) {
                HttpMethod.POST -> HttpResponseSpec(
                    statusCode = 201,
                    headers = listOf(
                        HttpHeader("Content-Type", "application/json"),
                        HttpHeader(GATEWAY_VERSION_HEADER, GATEWAY_PROTOCOL_VERSION.toString()),
                    ),
                    body = codec.encodeDescriptor(descriptor),
                )
                HttpMethod.DELETE -> HttpResponseSpec(statusCode = 204, body = "")
                else -> error("Unexpected execute request")
            }
        }

        override fun stream(request: HttpRequestSpec, format: HttpStreamFormat): Flow<HttpStreamFrame> {
            assertEquals(HttpStreamFormat.SERVER_SENT_EVENTS, format)
            streamRequests += request
            return streamScripts.removeAt(0)(request)
        }

        override fun close() = Unit
    }
}
