@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package saien.magrathea.provider.gateway

import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import saien.magrathea.core.AgentMessage
import saien.magrathea.core.AgentSessionId
import saien.magrathea.core.AttachmentPart
import saien.magrathea.core.CredentialRef
import saien.magrathea.core.MediaReference
import saien.magrathea.core.MessageRole
import saien.magrathea.core.ModelDescriptor
import saien.magrathea.core.ProviderCredential
import saien.magrathea.core.ReasoningCapabilities
import saien.magrathea.core.ReasoningEffort
import saien.magrathea.core.ReasoningPreference
import saien.magrathea.core.RemoteToolImageSource
import saien.magrathea.core.StopReason
import saien.magrathea.core.TextPart
import saien.magrathea.core.ToolImageAttachmentReference
import saien.magrathea.core.ToolMediaAttribution
import saien.magrathea.core.ToolResultAudience
import saien.magrathea.core.ToolResultImageContent
import saien.magrathea.core.ToolResultPart
import saien.magrathea.core.ToolResultTextContent
import saien.magrathea.gateway.protocol.GatewayAttachmentReference
import saien.magrathea.gateway.protocol.GATEWAY_IDEMPOTENCY_HEADER
import saien.magrathea.gateway.protocol.GATEWAY_INVOCATION_INVALIDATED_PROBLEM_CODE
import saien.magrathea.gateway.protocol.GATEWAY_INVOCATION_UNKNOWN_PROBLEM_CODE
import saien.magrathea.gateway.protocol.GATEWAY_REPLAY_WINDOW_EXHAUSTED_PROBLEM_CODE
import saien.magrathea.gateway.protocol.GATEWAY_PROTOCOL_VERSION
import saien.magrathea.gateway.protocol.GATEWAY_SSE_EVENT
import saien.magrathea.gateway.protocol.GATEWAY_VERSION_HEADER
import saien.magrathea.gateway.protocol.GatewayCreateStreamRequest
import saien.magrathea.gateway.protocol.GatewayEvent
import saien.magrathea.gateway.protocol.GatewayFailureCode
import saien.magrathea.gateway.protocol.GatewayProtocolCodec
import saien.magrathea.gateway.protocol.GatewayProblem
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
import saien.magrathea.provider.api.ProviderInvocationIntent
import saien.magrathea.provider.api.ProviderInvocationInvalidatedException
import saien.magrathea.provider.api.ProviderAuthException
import saien.magrathea.provider.api.ProviderClientException
import saien.magrathea.provider.api.ProviderContextLimitException
import saien.magrathea.provider.api.ProviderCancellationContext
import saien.magrathea.provider.api.ProviderCancellationIntent
import saien.magrathea.provider.api.ProviderNetworkException
import saien.magrathea.provider.api.ProviderProtocolException
import saien.magrathea.provider.api.ProviderRateLimitException
import saien.magrathea.provider.api.ProviderRequest
import saien.magrathea.provider.api.ProviderServerException
import saien.magrathea.provider.api.ProviderTimeoutException
import saien.magrathea.provider.api.ProviderTimeoutPhase

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

        val chunks = adapter.generate(
            providerRequest().copy(
                model = providerRequest().model.copy(
                    reasoningCapabilities = ReasoningCapabilities(
                        supportedEfforts = setOf(ReasoningEffort.HIGH),
                    ),
                ),
                reasoningPreference = ReasoningPreference.Effort(ReasoningEffort.HIGH),
            ),
        ).toList()

        assertEquals(3, chunks.size)
        assertEquals(listOf("hel", "lo"), chunks.flatMap { it.events }.filterIsInstance<saien.magrathea.provider.api.ProviderEvent.TextDelta>().map { it.delta })
        assertIs<saien.magrathea.provider.api.ProviderEvent.Completed>(chunks.last().events.single())
        assertTrue(transport.streamRequests[0].url.endsWith("afterSequence=-1"))
        assertTrue(transport.streamRequests[1].url.endsWith("afterSequence=1"))
        assertEquals(1, transport.executeRequests.count { it.method == HttpMethod.POST })
        assertEquals(1, reconnectPermissions)
        val create = codec.decodeCreateRequest(transport.executeRequests.single { it.method == HttpMethod.POST }.body!!)
        assertEquals("session-1:0", create.requestId)
        assertEquals(
            ReasoningPreference.Effort(ReasoningEffort.HIGH),
            create.reasoningPreference,
        )
        assertFalse(transport.executeRequests.single().body!!.contains("credential", ignoreCase = true))
    }

    @Test
    fun completedStopsCollectionBeforeLateTransportFailureOrReconnectGate() = runTest {
        listOf(
            saien.magrathea.provider.api.ProviderNetworkException("late disconnect"),
            CancellationException("late cancellation"),
        ).forEach { lateFailure ->
            var reconnectPermissions = 0
            val transport = RecordingTransport(codec, descriptor).apply {
                streamScripts += {
                    flow {
                        emit(responseStarted())
                        emit(sse(envelope(0, GatewayEvent.StreamOpened())))
                        try {
                            emit(
                                sse(
                                    envelope(
                                        1,
                                        GatewayEvent.Completed(stopReason = StopReason.COMPLETED),
                                    ),
                                ),
                            )
                        } finally {
                            throw lateFailure
                        }
                    }
                }
            }
            val chunks = adapter(
                transport,
                reconnectGate = GatewayReconnectGate { reconnectPermissions += 1 },
            ).generate(providerRequest()).toList()

            assertIs<saien.magrathea.provider.api.ProviderEvent.Completed>(
                chunks.last().events.single(),
            )
            assertEquals(0, reconnectPermissions)
            assertEquals(1, transport.streamRequests.size)
            assertTrue(transport.executeRequests.none { it.method == HttpMethod.DELETE })
        }
    }

    @Test
    fun completedDoesNotSwallowADownstreamCollectorFailure() = runTest {
        val transport = completedTransport()

        assertFailsWith<ProviderProtocolException> {
            adapter(transport).generate(providerRequest()).collect { chunk ->
                if (chunk.events.single() is saien.magrathea.provider.api.ProviderEvent.Completed) {
                    throw ProviderProtocolException("downstream rejected completion")
                }
            }
        }

        assertTrue(transport.executeRequests.none { it.method == HttpMethod.DELETE })
    }

    @Test
    fun completedDoesNotSwallowDownstreamCancellationOrDeleteRemoteWork() = runTest {
        val transport = completedTransport()

        assertFailsWith<CancellationException> {
            adapter(transport).generate(providerRequest()).collect { chunk ->
                if (chunk.events.single() is saien.magrathea.provider.api.ProviderEvent.Completed) {
                    throw CancellationException("downstream cancelled")
                }
            }
        }

        assertTrue(transport.executeRequests.none { it.method == HttpMethod.DELETE })
    }

    @Test
    fun ambiguousCreateFailureDoesNotRepeatThePostInsideOneGenerate() = runTest {
        val executeRequests = mutableListOf<HttpRequestSpec>()
        val transport = object : HttpTransport {
            override suspend fun execute(request: HttpRequestSpec): HttpResponseSpec {
                executeRequests += request
                throw saien.magrathea.provider.api.ProviderNetworkException(
                    "create response was lost",
                )
            }

            override fun stream(
                request: HttpRequestSpec,
                format: HttpStreamFormat,
            ): Flow<HttpStreamFrame> = flow {
                emit(responseStarted())
                emit(sse(envelope(0, GatewayEvent.StreamOpened())))
                emit(sse(envelope(1, GatewayEvent.Completed(stopReason = StopReason.COMPLETED))))
                emit(HttpStreamFrame.Completed)
            }

            override fun close() = Unit
        }

        assertFailsWith<saien.magrathea.provider.api.ProviderNetworkException> {
            adapter(transport).generate(providerRequest()).toList()
        }

        assertEquals(1, executeRequests.size)
        assertEquals(HttpMethod.POST, executeRequests.single().method)
        assertEquals(
            descriptor.requestId,
            codec.decodeCreateRequest(assertNotNull(executeRequests.single().body)).requestId,
        )
        assertEquals(
            descriptor.requestId,
            executeRequests.single().headers
                .single { it.name == GATEWAY_IDEMPOTENCY_HEADER }
                .value,
        )
    }

    @Test
    fun createRejectsATerminalInvocationBeforeAnyPartialReplay() = runTest {
        val executeRequests = mutableListOf<HttpRequestSpec>()
        val transport = object : HttpTransport {
            override suspend fun execute(request: HttpRequestSpec): HttpResponseSpec {
                executeRequests += request
                return HttpResponseSpec(
                    statusCode = 409,
                    headers = listOf(
                        HttpHeader("Content-Type", "application/json"),
                        HttpHeader(GATEWAY_VERSION_HEADER, GATEWAY_PROTOCOL_VERSION.toString()),
                    ),
                    body = codec.encodeProblem(
                        GatewayProblem(
                            code = GATEWAY_INVOCATION_INVALIDATED_PROBLEM_CODE,
                            message = "Invocation is no longer reattachable",
                        ),
                    ),
                )
            }

            override fun stream(
                request: HttpRequestSpec,
                format: HttpStreamFormat,
            ): Flow<HttpStreamFrame> = error("An invalidated invocation must not replay its stream")

            override fun close() = Unit
        }

        val failure = assertFailsWith<ProviderInvocationInvalidatedException> {
            adapter(transport).generate(providerRequest()).toList()
        }

        assertTrue(failure.retryable)
        assertEquals(1, executeRequests.size)
    }

    @Test
    fun createFailsClosedWhenACompletedInvocationIsNoLongerReplayable() = runTest {
        val executeRequests = mutableListOf<HttpRequestSpec>()
        val transport = object : HttpTransport {
            override suspend fun execute(request: HttpRequestSpec): HttpResponseSpec {
                executeRequests += request
                return HttpResponseSpec(
                    statusCode = 410,
                    headers = listOf(
                        HttpHeader("Content-Type", "application/json"),
                        HttpHeader(GATEWAY_VERSION_HEADER, GATEWAY_PROTOCOL_VERSION.toString()),
                    ),
                    body = codec.encodeProblem(
                        GatewayProblem(
                            code = GATEWAY_REPLAY_WINDOW_EXHAUSTED_PROBLEM_CODE,
                            message = "Replay window exhausted",
                        ),
                    ),
                )
            }

            override fun stream(
                request: HttpRequestSpec,
                format: HttpStreamFormat,
            ): Flow<HttpStreamFrame> = error("An expired terminal replay must not open an event stream")

            override fun close() = Unit
        }

        val failure = assertFailsWith<ProviderInvocationInvalidatedException> {
            adapter(transport).generate(providerRequest()).toList()
        }

        assertFalse(failure.retryable)
        assertIs<ProviderProtocolException>(failure.failure)
        assertEquals(1, executeRequests.size)
    }

    @Test
    fun reattachResolvesAnExistingInvocationWithoutPostingOrChangingItsIdentity() = runTest {
        val transport = RecordingTransport(codec, descriptor).apply {
            getResponses += descriptorResponse(statusCode = 200)
            streamScripts += {
                flow {
                    emit(responseStarted())
                    emit(sse(envelope(0, GatewayEvent.StreamOpened())))
                    emit(sse(envelope(1, GatewayEvent.Completed(stopReason = StopReason.COMPLETED))))
                    emit(HttpStreamFrame.Completed)
                }
            }
        }

        val chunks = adapter(transport).generate(
            providerRequest().copy(invocationIntent = ProviderInvocationIntent.REATTACH),
        ).toList()

        assertIs<saien.magrathea.provider.api.ProviderEvent.Completed>(chunks.single().events.single())
        assertEquals(listOf(HttpMethod.GET), transport.executeRequests.map { it.method })
        assertEquals(
            descriptor.requestId,
            transport.executeRequests.single().headers
                .single { it.name == GATEWAY_IDEMPOTENCY_HEADER }
                .value,
        )
        assertEquals(1, transport.streamRequests.size)
    }

    @Test
    fun reattachDoesNotReprocessTheOriginalPayloadOrAttachmentCatalog() = runTest {
        val transport = RecordingTransport(codec, descriptor).apply {
            getResponses += descriptorResponse(statusCode = 200)
            streamScripts += {
                flow {
                    emit(responseStarted())
                    emit(sse(envelope(0, GatewayEvent.StreamOpened())))
                    emit(sse(envelope(1, GatewayEvent.Completed(stopReason = StopReason.COMPLETED))))
                    emit(HttpStreamFrame.Completed)
                }
            }
        }
        var attachmentCatalogCalls = 0

        val chunks = adapter(
            transport = transport,
            attachmentCatalog = GatewayAttachmentCatalog { _, _ ->
                attachmentCatalogCalls += 1
                error("A reattachment must not resolve the original request payload")
            },
        ).generate(
            providerRequest().copy(
                invocationIntent = ProviderInvocationIntent.REATTACH,
                messages = listOf(
                    AgentMessage(
                        id = "detached-payload",
                        role = MessageRole.USER,
                        parts = listOf(AttachmentPart("file:///not-uploaded", "image/png")),
                        createdAtEpochMs = 1,
                    ),
                ),
            ),
        ).toList()

        assertIs<saien.magrathea.provider.api.ProviderEvent.Completed>(chunks.single().events.single())
        assertEquals(0, attachmentCatalogCalls)
        assertEquals(listOf(HttpMethod.GET), transport.executeRequests.map(HttpRequestSpec::method))
    }

    @Test
    fun reattachAfterCoordinatorStateLossFailsClosedWithoutPostingOrOpeningAStream() = runTest {
        val transport = RecordingTransport(codec, descriptor).apply {
            getResponses += problemResponse(
                statusCode = 404,
                code = GATEWAY_INVOCATION_UNKNOWN_PROBLEM_CODE,
            )
        }

        val failure = assertFailsWith<ProviderInvocationInvalidatedException> {
            adapter(transport).generate(
                providerRequest().copy(invocationIntent = ProviderInvocationIntent.REATTACH),
            ).toList()
        }

        assertFalse(failure.retryable)
        assertIs<ProviderProtocolException>(failure.failure)
        assertEquals(listOf(HttpMethod.GET), transport.executeRequests.map { it.method })
        assertTrue(transport.streamRequests.isEmpty())
    }

    @Test
    fun reattachPreservesRetryableInvalidationAndPermanentReplayExpiry() = runTest {
        suspend fun failure(statusCode: Int, code: String): ProviderInvocationInvalidatedException {
            val transport = RecordingTransport(codec, descriptor).apply {
                getResponses += problemResponse(statusCode = statusCode, code = code)
            }
            return assertFailsWith<ProviderInvocationInvalidatedException> {
                adapter(transport).generate(
                    providerRequest().copy(invocationIntent = ProviderInvocationIntent.REATTACH),
                ).toList()
            }.also {
                assertEquals(listOf(HttpMethod.GET), transport.executeRequests.map { request -> request.method })
                assertTrue(transport.streamRequests.isEmpty())
            }
        }

        assertTrue(
            failure(
                statusCode = 409,
                code = GATEWAY_INVOCATION_INVALIDATED_PROBLEM_CODE,
            ).retryable,
        )
        val expired = failure(
            statusCode = 410,
            code = GATEWAY_REPLAY_WINDOW_EXHAUSTED_PROBLEM_CODE,
        )
        assertFalse(expired.retryable)
        assertIs<ProviderProtocolException>(expired.failure)
    }

    @Test
    fun missingRetainedStreamResolvesTheSameIdentityAndContinuesItsExistingDescriptor() = runTest {
        val transport = RecordingTransport(codec, descriptor).apply {
            getResponses += descriptorResponse(statusCode = 200)
            streamScripts += {
                flow {
                    emit(
                        HttpStreamFrame.ResponseStarted(
                            statusCode = 404,
                            headers = listOf(
                                HttpHeader("Content-Type", "application/json"),
                                HttpHeader(
                                    GATEWAY_VERSION_HEADER,
                                    GATEWAY_PROTOCOL_VERSION.toString(),
                                ),
                            ),
                        ),
                    )
                    emit(HttpStreamFrame.Completed)
                }
            }
            streamScripts += {
                flow {
                    emit(responseStarted())
                    emit(sse(envelope(0, GatewayEvent.StreamOpened())))
                    emit(sse(envelope(1, GatewayEvent.Completed(stopReason = StopReason.COMPLETED))))
                    emit(HttpStreamFrame.Completed)
                }
            }
        }

        val chunks = adapter(transport).generate(providerRequest()).toList()

        assertIs<saien.magrathea.provider.api.ProviderEvent.Completed>(chunks.single().events.single())
        assertEquals(2, transport.streamRequests.size)
        val createRequests = transport.executeRequests.filter { it.method == HttpMethod.POST }
        assertEquals(1, createRequests.size)
        val resolveRequests = transport.executeRequests.filter { it.method == HttpMethod.GET }
        assertEquals(1, resolveRequests.size)
        assertEquals(
            listOf(descriptor.requestId),
            resolveRequests.map { request ->
                request.headers.single { it.name == GATEWAY_IDEMPOTENCY_HEADER }.value
            },
        )
    }

    @Test
    fun missingStreamAfterLongReconnectHonorsCompletedTombstoneWithoutChangingRequestId() = runTest {
        val retentionMillis = 10 * 60_000L
        val transport = RecordingTransport(codec, descriptor).apply {
            postResponses += descriptorResponse()
            getResponses += problemResponse(
                statusCode = 410,
                code = GATEWAY_REPLAY_WINDOW_EXHAUSTED_PROBLEM_CODE,
            )
            streamScripts += {
                flow {
                    emit(responseStarted())
                    emit(sse(envelope(0, GatewayEvent.StreamOpened())))
                    throw saien.magrathea.provider.api.ProviderNetworkException("offline")
                }
            }
            streamScripts += {
                flow {
                    emit(missingStreamResponse())
                    emit(HttpStreamFrame.Completed)
                }
            }
        }
        val failure = assertFailsWith<ProviderInvocationInvalidatedException> {
            adapter(
                transport = transport,
                reconnectGate = GatewayReconnectGate { delay(retentionMillis + 1) },
            ).generate(providerRequest()).toList()
        }

        assertFalse(failure.retryable)
        assertIs<ProviderProtocolException>(failure.failure)
        assertTrue(testScheduler.currentTime > retentionMillis)
        val createRequests = transport.executeRequests.filter { it.method == HttpMethod.POST }
        assertEquals(1, createRequests.size)
        assertEquals(1, transport.executeRequests.count { it.method == HttpMethod.GET })
    }

    @Test
    fun missingStreamUsesTheSameCreateIdentityToClassifyRetryableInvalidation() = runTest {
        val transport = RecordingTransport(codec, descriptor).apply {
            postResponses += descriptorResponse()
            getResponses += problemResponse(
                statusCode = 409,
                code = GATEWAY_INVOCATION_INVALIDATED_PROBLEM_CODE,
            )
            streamScripts += {
                flow {
                    emit(missingStreamResponse())
                    emit(HttpStreamFrame.Completed)
                }
            }
        }

        val failure = assertFailsWith<ProviderInvocationInvalidatedException> {
            adapter(transport).generate(providerRequest()).toList()
        }

        assertTrue(failure.retryable)
        val createRequests = transport.executeRequests.filter { it.method == HttpMethod.POST }
        assertEquals(1, createRequests.size)
        assertEquals(
            listOf(descriptor.requestId),
            transport.executeRequests.filter { it.method == HttpMethod.GET }.map { request ->
                request.headers.single { it.name == GATEWAY_IDEMPOTENCY_HEADER }.value
            },
        )
    }

    @Test
    fun exhaustedEventReplayFailsClosedInsteadOfStartingANewInvocation() = runTest {
        val transport = RecordingTransport(codec, descriptor).apply {
            streamScripts += {
                flow {
                    emit(
                        HttpStreamFrame.ResponseStarted(
                            statusCode = 410,
                            headers = listOf(
                                HttpHeader("Content-Type", "application/json"),
                                HttpHeader(GATEWAY_VERSION_HEADER, GATEWAY_PROTOCOL_VERSION.toString()),
                            ),
                        ),
                    )
                    emit(HttpStreamFrame.Completed)
                }
            }
        }

        val failure = assertFailsWith<ProviderInvocationInvalidatedException> {
            adapter(transport).generate(providerRequest()).toList()
        }

        assertFalse(failure.retryable)
        assertIs<ProviderProtocolException>(failure.failure)
        assertEquals(1, transport.streamRequests.size)
    }

    @Test
    fun streamResponseStatusUsesTheProviderFailureTaxonomy() = runTest {
        suspend fun failure(
            statusCode: Int,
            additionalHeaders: List<HttpHeader> = emptyList(),
        ): Throwable {
            val transport = RecordingTransport(codec, descriptor).apply {
                streamScripts += {
                    flow {
                        emit(
                            HttpStreamFrame.ResponseStarted(
                                statusCode = statusCode,
                                headers = listOf(
                                    HttpHeader("Content-Type", "application/json"),
                                    HttpHeader(
                                        GATEWAY_VERSION_HEADER,
                                        GATEWAY_PROTOCOL_VERSION.toString(),
                                    ),
                                ) + additionalHeaders,
                            ),
                        )
                        emit(HttpStreamFrame.Completed)
                    }
                }
            }
            return assertFailsWith<Throwable> {
                adapter(transport).generate(providerRequest()).toList()
            }
        }

        assertIs<ProviderAuthException>(failure(401))
        val rateLimit = assertIs<ProviderRateLimitException>(
            failure(429, listOf(HttpHeader("Retry-After", "2"))),
        )
        assertEquals(2_000, rateLimit.retryAfterMillis)
        assertIs<ProviderServerException>(failure(503))
        assertIs<ProviderClientException>(failure(408))
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
    fun stableGatewayFailuresRoundTripIntoTheProviderTaxonomy() = runTest {
        suspend fun failure(
            code: GatewayFailureCode,
            retryable: Boolean,
            retryAfterMillis: Long? = null,
        ): ProviderInvocationInvalidatedException {
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
                                        code = code,
                                        retryable = retryable,
                                        retryAfterMillis = retryAfterMillis,
                                    ),
                                ),
                            ),
                        )
                        emit(HttpStreamFrame.Completed)
                    }
                }
            }
            return assertFailsWith<ProviderInvocationInvalidatedException> {
                adapter(transport).generate(providerRequest()).toList()
            }.also { assertEquals(retryable, it.retryable) }
        }

        assertIs<ProviderAuthException>(
            failure(GatewayFailureCode.AUTHENTICATION_FAILURE, retryable = false).failure,
        )
        assertIs<ProviderClientException>(
            failure(GatewayFailureCode.CLIENT_FAILURE, retryable = false).failure,
        )
        assertIs<ProviderProtocolException>(
            failure(GatewayFailureCode.PROTOCOL_FAILURE, retryable = false).failure,
        )
        val rateLimit = assertIs<ProviderRateLimitException>(
            failure(GatewayFailureCode.RATE_LIMIT, retryable = true, retryAfterMillis = 1_100).failure,
        )
        assertEquals(1_100, rateLimit.retryAfterMillis)
        assertIs<ProviderNetworkException>(
            failure(GatewayFailureCode.NETWORK_FAILURE, retryable = true).failure,
        )
        val timeout = assertIs<ProviderTimeoutException>(
            failure(GatewayFailureCode.TIMEOUT, retryable = true).failure,
        )
        assertEquals(ProviderTimeoutPhase.PROVIDER_CALL, timeout.phase)
        val server = assertIs<ProviderServerException>(
            failure(GatewayFailureCode.SERVER_FAILURE, retryable = true, retryAfterMillis = 2_200).failure,
        )
        assertEquals(2_200, server.retryAfterMillis)
        assertIs<ProviderServerException>(
            failure(GatewayFailureCode.INTERNAL_FAILURE, retryable = false).failure,
        )
    }

    @Test
    fun gatewayFailureHonorsServerRetryabilityAndInvalidatesThePhysicalInvocation() = runTest {
        suspend fun failure(retryable: Boolean): ProviderInvocationInvalidatedException {
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
                                        code = GatewayFailureCode.SERVER_FAILURE,
                                        retryable = retryable,
                                    ),
                                ),
                            ),
                        )
                        emit(HttpStreamFrame.Completed)
                    }
                }
            }
            return assertFailsWith<ProviderInvocationInvalidatedException> {
                adapter(transport).generate(providerRequest()).toList()
            }
        }

        assertTrue(failure(retryable = true).retryable)
        assertFalse(failure(retryable = false).retryable)
    }

    @Test
    fun remoteCancelledIsARecoverableInvalidationRatherThanCoroutineCancellation() = runTest {
        val transport = RecordingTransport(codec, descriptor).apply {
            streamScripts += {
                flow {
                    emit(responseStarted())
                    emit(sse(envelope(0, GatewayEvent.StreamOpened())))
                    emit(sse(envelope(1, GatewayEvent.Cancelled("lease expired"))))
                    emit(HttpStreamFrame.Completed)
                }
            }
        }

        val failure: Throwable = assertFailsWith<ProviderInvocationInvalidatedException> {
            adapter(transport).generate(providerRequest()).toList()
        }

        assertTrue((failure as ProviderInvocationInvalidatedException).retryable)
        assertTrue(transport.executeRequests.none { it.method == HttpMethod.DELETE })
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
        assertTrue(delete.url.endsWith("/v3/streams/stream-1"))
        assertEquals("Bearer browser-session", delete.headers.first { it.name == "Authorization" }.value)
    }

    @Test
    fun cancellationBeforeCreateDescriptorAbandonsTheScopedRequestId() = runTest {
        val transport = DescriptorPendingTransport()
        val collection = backgroundScope.async {
            adapter(transport).generate(providerRequest()).toList()
        }
        transport.createStarted.await()

        collection.cancelAndJoin()

        assertEquals(
            listOf(HttpMethod.POST, HttpMethod.DELETE),
            transport.executeRequests.map { it.method },
        )
        val abandon = transport.executeRequests.last()
        assertTrue(abandon.url.endsWith("/v3/streams"))
        assertEquals(
            descriptor.requestId,
            abandon.headers.single { it.name == GATEWAY_IDEMPOTENCY_HEADER }.value,
        )
        assertEquals(
            "Bearer browser-session",
            abandon.headers.single { it.name == "Authorization" }.value,
        )
    }

    @Test
    fun interruptionBeforeCreateDescriptorKeepsTheInvocationForReattachment() = runTest {
        val transport = DescriptorPendingTransport()
        val signal = object : ProviderCancellationContext {
            override val intent: ProviderCancellationIntent = ProviderCancellationIntent.INTERRUPT
        }
        val collection = backgroundScope.async(signal) {
            adapter(transport).generate(providerRequest()).toList()
        }
        transport.createStarted.await()

        collection.cancelAndJoin()

        assertEquals(listOf(HttpMethod.POST), transport.executeRequests.map { it.method })
    }

    @Test
    fun ordinaryCreateFailureDoesNotTriggerCancellationCleanup() = runTest {
        val executeRequests = mutableListOf<HttpRequestSpec>()
        val transport = object : HttpTransport {
            override suspend fun execute(request: HttpRequestSpec): HttpResponseSpec {
                executeRequests += request
                return HttpResponseSpec(statusCode = 400, body = "invalid request")
            }

            override fun stream(
                request: HttpRequestSpec,
                format: HttpStreamFormat,
            ): Flow<HttpStreamFrame> = error("A failed create must not open an event stream")

            override fun close() = Unit
        }

        assertFailsWith<ProviderClientException> {
            adapter(transport).generate(providerRequest()).toList()
        }

        assertEquals(listOf(HttpMethod.POST), executeRequests.map { it.method })
    }

    @Test
    fun abandonDeletesTheScopedRequestIdWithoutAStreamDescriptor() = runTest {
        val transport = RecordingTransport(codec, descriptor)
        val invocation = assertNotNull(providerRequest().invocation)

        adapter(transport).abandon(invocation)

        val delete = transport.executeRequests.single()
        assertEquals(HttpMethod.DELETE, delete.method)
        assertTrue(delete.url.endsWith("/v3/streams"))
        assertEquals(
            invocation.requestId,
            delete.headers.single { it.name == GATEWAY_IDEMPOTENCY_HEADER }.value,
        )
        assertEquals(
            "Bearer browser-session",
            delete.headers.single { it.name == "Authorization" }.value,
        )
    }

    @Test
    fun abandonRetriesRetryableHttpFailures() = runTest {
        listOf(429, 503).forEach { retryableStatus ->
            var calls = 0
            var reconnectPermissions = 0
            val transport = object : HttpTransport {
                override suspend fun execute(request: HttpRequestSpec): HttpResponseSpec {
                    calls += 1
                    return HttpResponseSpec(
                        statusCode = if (calls == 1) retryableStatus else 204,
                        headers = if (calls == 1 && retryableStatus == 429) {
                            listOf(HttpHeader("Retry-After", "2"))
                        } else {
                            emptyList()
                        },
                        body = "",
                    )
                }

                override fun stream(
                    request: HttpRequestSpec,
                    format: HttpStreamFormat,
                ): Flow<HttpStreamFrame> = error("Abandon does not open a stream")

                override fun close() = Unit
            }

            adapter(
                transport = transport,
                reconnectGate = GatewayReconnectGate { reconnectPermissions += 1 },
            ).abandon(assertNotNull(providerRequest().invocation))

            assertEquals(2, calls)
            assertEquals(1, reconnectPermissions)
        }
    }

    @Test
    fun recoverableInterruptionKeepsRemoteStreamForReattachment() = runTest {
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
        val signal = object : ProviderCancellationContext {
            override val intent: ProviderCancellationIntent = ProviderCancellationIntent.INTERRUPT
        }
        val collection = backgroundScope.async(signal) {
            adapter.generate(providerRequest()).toList()
        }
        runCurrent()

        collection.cancelAndJoin()

        assertTrue(transport.executeRequests.none { it.method == HttpMethod.DELETE })
        assertEquals(1, transport.executeRequests.count { it.method == HttpMethod.POST })
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

    @Test
    fun gatewayProjectsUserContentAndBindsModelImagesToUploadedAttachments() = runTest {
        val userOnlySecret = "USER_ONLY_GATEWAY_CANARY"
        val transport = RecordingTransport(codec, descriptor).apply {
            streamScripts += {
                flow {
                    emit(responseStarted())
                    emit(sse(envelope(0, GatewayEvent.StreamOpened())))
                    emit(sse(envelope(1, GatewayEvent.Completed(stopReason = StopReason.COMPLETED))))
                    emit(HttpStreamFrame.Completed)
                }
            }
        }
        val adapter = adapter(
            transport = transport,
            attachmentCatalog = GatewayAttachmentCatalog { id, mediaType ->
                GatewayAttachmentReference(id, mediaType, sizeBytes = 42)
            },
        )
        val toolResult = ToolResultPart(
            toolCallId = "tool-1",
            toolName = "inspect_image",
            result = buildJsonObject { put("secret", userOnlySecret) },
            displayText = userOnlySecret,
            metadata = buildJsonObject { put("mcpContent", userOnlySecret) },
            content = listOf(
                ToolResultTextContent("model text", setOf(ToolResultAudience.MODEL, ToolResultAudience.USER)),
                ToolResultTextContent(userOnlySecret, setOf(ToolResultAudience.USER)),
                ToolResultImageContent(
                    source = ToolImageAttachmentReference("magrathea-attachment:model-image"),
                    previewSource = RemoteToolImageSource("https://cdn.example.com/preview.jpg"),
                    mimeType = "image/jpeg",
                    attribution = ToolMediaAttribution("Source", "https://example.com/source"),
                    audiences = setOf(ToolResultAudience.MODEL, ToolResultAudience.USER),
                    reference = MediaReference("tool-result:run:1:0"),
                ),
                ToolResultImageContent(
                    source = RemoteToolImageSource("https://cdn.example.com/user-only.jpg"),
                    mimeType = "image/jpeg",
                    audiences = setOf(ToolResultAudience.USER),
                ),
            ),
            providerMetadata = buildJsonObject { put("private", userOnlySecret) },
            modelResultVisible = false,
        )

        adapter.generate(
            providerRequest().copy(
                messages = listOf(
                    AgentMessage(
                        id = "tool-message-1",
                        role = MessageRole.TOOL,
                        parts = listOf(toolResult),
                        createdAtEpochMs = 1,
                    ),
                ),
            ),
        ).toList()

        val request = codec.decodeCreateRequest(
            transport.executeRequests.single { it.method == HttpMethod.POST }.body!!,
        )
        val projected = request.messages.single().parts.single() as ToolResultPart
        assertEquals(2, projected.content.size)
        assertTrue(projected.content.all { it.audiences == setOf(ToolResultAudience.MODEL) })
        val image = projected.content.filterIsInstance<ToolResultImageContent>().single()
        assertEquals(null, image.previewSource)
        assertEquals(null, image.attribution)
        assertEquals(null, image.reference)
        assertEquals("model-image", request.attachments.single().id)
        assertFalse(request.toString().contains("user-only"))
        assertFalse(request.toString().contains(userOnlySecret))
        assertEquals(null, projected.displayText)
        assertEquals(emptySet(), projected.metadata.keys)
        assertEquals(null, projected.providerMetadata)
    }

    private fun adapter(
        transport: HttpTransport,
        reconnectGate: GatewayReconnectGate = GatewayReconnectGate { },
        attachmentCatalog: GatewayAttachmentCatalog? = null,
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
        attachmentCatalog = attachmentCatalog,
        reconnectGate = reconnectGate,
        transport = transport,
        closeTransport = false,
        codec = codec,
    )

    private fun completedTransport() = RecordingTransport(codec, descriptor).apply {
        streamScripts += {
            flow {
                emit(responseStarted())
                emit(sse(envelope(0, GatewayEvent.StreamOpened())))
                emit(sse(envelope(1, GatewayEvent.Completed(stopReason = StopReason.COMPLETED))))
                emit(HttpStreamFrame.Completed)
            }
        }
    }

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

    private fun missingStreamResponse() = HttpStreamFrame.ResponseStarted(
        statusCode = 404,
        headers = listOf(
            HttpHeader("Content-Type", "application/json"),
            HttpHeader(GATEWAY_VERSION_HEADER, GATEWAY_PROTOCOL_VERSION.toString()),
        ),
    )

    private fun descriptorResponse(statusCode: Int = 201) = HttpResponseSpec(
        statusCode = statusCode,
        headers = listOf(
            HttpHeader("Content-Type", "application/json"),
            HttpHeader(GATEWAY_VERSION_HEADER, GATEWAY_PROTOCOL_VERSION.toString()),
        ),
        body = codec.encodeDescriptor(descriptor),
    )

    private fun problemResponse(statusCode: Int, code: String) = HttpResponseSpec(
        statusCode = statusCode,
        headers = listOf(
            HttpHeader("Content-Type", "application/json"),
            HttpHeader(GATEWAY_VERSION_HEADER, GATEWAY_PROTOCOL_VERSION.toString()),
        ),
        body = codec.encodeProblem(GatewayProblem(code = code, message = "retained terminal")),
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
        val postResponses = mutableListOf<HttpResponseSpec>()
        val getResponses = mutableListOf<HttpResponseSpec>()
        val streamScripts = mutableListOf<(HttpRequestSpec) -> Flow<HttpStreamFrame>>()

        override suspend fun execute(request: HttpRequestSpec): HttpResponseSpec {
            executeRequests += request
            return when (request.method) {
                HttpMethod.POST -> if (postResponses.isNotEmpty()) {
                    postResponses.removeAt(0)
                } else {
                    HttpResponseSpec(
                        statusCode = 201,
                        headers = listOf(
                            HttpHeader("Content-Type", "application/json"),
                            HttpHeader(GATEWAY_VERSION_HEADER, GATEWAY_PROTOCOL_VERSION.toString()),
                        ),
                        body = codec.encodeDescriptor(descriptor),
                    )
                }
                HttpMethod.GET -> if (getResponses.isNotEmpty()) {
                    getResponses.removeAt(0)
                } else {
                    HttpResponseSpec(
                        statusCode = 200,
                        headers = listOf(
                            HttpHeader("Content-Type", "application/json"),
                            HttpHeader(GATEWAY_VERSION_HEADER, GATEWAY_PROTOCOL_VERSION.toString()),
                        ),
                        body = codec.encodeDescriptor(descriptor),
                    )
                }
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

    private class DescriptorPendingTransport : HttpTransport {
        val createStarted = CompletableDeferred<Unit>()
        val executeRequests = mutableListOf<HttpRequestSpec>()

        override suspend fun execute(request: HttpRequestSpec): HttpResponseSpec {
            executeRequests += request
            return when (request.method) {
                HttpMethod.POST -> {
                    createStarted.complete(Unit)
                    awaitCancellation()
                }
                HttpMethod.DELETE -> HttpResponseSpec(statusCode = 204, body = "")
                else -> error("Unexpected execute request")
            }
        }

        override fun stream(
            request: HttpRequestSpec,
            format: HttpStreamFormat,
        ): Flow<HttpStreamFrame> = error("A pending create must not open an event stream")

        override fun close() = Unit
    }
}
