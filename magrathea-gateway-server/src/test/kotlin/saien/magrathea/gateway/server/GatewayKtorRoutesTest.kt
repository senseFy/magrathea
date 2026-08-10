package saien.magrathea.gateway.server

import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.options
import io.ktor.client.request.post
import io.ktor.client.request.prepareGet
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import io.ktor.utils.io.readLine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withTimeout
import saien.magrathea.core.EpochClock
import saien.magrathea.core.IdGenerator
import saien.magrathea.core.ModelDescriptor
import saien.magrathea.core.ProviderCredential
import saien.magrathea.core.StopReason
import saien.magrathea.gateway.protocol.GATEWAY_SSE_EVENT
import saien.magrathea.gateway.protocol.GATEWAY_CSRF_HEADER
import saien.magrathea.gateway.protocol.GATEWAY_IDEMPOTENCY_HEADER
import saien.magrathea.gateway.protocol.GATEWAY_INVOCATION_INVALIDATED_PROBLEM_CODE
import saien.magrathea.gateway.protocol.GATEWAY_INVOCATION_UNKNOWN_PROBLEM_CODE
import saien.magrathea.gateway.protocol.GATEWAY_REPLAY_WINDOW_EXHAUSTED_PROBLEM_CODE
import saien.magrathea.gateway.protocol.GATEWAY_PROTOCOL_VERSION
import saien.magrathea.gateway.protocol.GATEWAY_VERSION_HEADER
import saien.magrathea.gateway.protocol.GatewayEvent
import saien.magrathea.gateway.protocol.GatewayProblem
import saien.magrathea.gateway.protocol.GatewayProtocolCodec
import saien.magrathea.gateway.protocol.GatewayStreamDescriptor
import saien.magrathea.gateway.protocol.GatewayStreamEnvelope
import saien.magrathea.provider.api.InMemoryProviderRegistry
import saien.magrathea.provider.api.ProviderAdapter
import saien.magrathea.provider.api.ProviderChunk
import saien.magrathea.provider.api.ProviderEvent
import saien.magrathea.provider.api.ProviderRequest

class GatewayKtorRoutesTest {
    private val codec = GatewayProtocolCodec()

    @Test
    fun httpConfigurationKeepsTheVersionedPathAndPositiveBounds() {
        assertEquals("/v3/streams", GatewayHttpConfig().basePath)
        assertEquals("/api/v3/streams", GatewayHttpConfig(basePath = "/api/v3/streams").basePath)
        listOf(
            "/streams",
            "/./v3/streams",
            "/../v3/streams",
            "/api/../v3/streams",
            "/api//v3/streams",
            "/v3/streams/",
        ).forEach {
            kotlin.test.assertFailsWith<IllegalArgumentException> { GatewayHttpConfig(basePath = it) }
        }
        kotlin.test.assertFailsWith<IllegalArgumentException> { GatewayHttpConfig(maxRequestBodyBytes = 0) }
        kotlin.test.assertFailsWith<IllegalArgumentException> { GatewayHttpConfig(sseHeartbeatMillis = 0) }
    }

    @Test
    fun httpContractEnforcesAuthOriginVersionIdempotencyOwnershipAndCursor() = testApplication {
        val fixture = HttpFixture()
        application { installMagratheaGateway(fixture.dependencies) }

        val missingAuth = client.post("/v3/streams") {
            validHeaders(includeAuth = false)
            setBody(codec.encodeCreateRequest(GatewayStreamCoordinatorTest.request()))
        }
        assertEquals(HttpStatusCode.Unauthorized, missingAuth.status)

        val badOrigin = client.post("/v3/streams") {
            validHeaders(origin = "https://evil.example")
            setBody(codec.encodeCreateRequest(GatewayStreamCoordinatorTest.request()))
        }
        assertEquals(HttpStatusCode.Forbidden, badOrigin.status)

        val createdResponse = client.post("/v3/streams") {
            validHeaders()
            setBody(codec.encodeCreateRequest(GatewayStreamCoordinatorTest.request()))
        }
        assertEquals(HttpStatusCode.Created, createdResponse.status)
        assertSensitiveResponseHeaders(createdResponse.headers[HttpHeaders.CacheControl], createdResponse.headers["X-Content-Type-Options"])
        assertEquals(ALLOWED_ORIGIN, createdResponse.headers[HttpHeaders.AccessControlAllowOrigin])
        assertExposesGatewayHeaders(createdResponse.headers[HttpHeaders.AccessControlExposeHeaders])
        val descriptor = codec.decodeDescriptor(createdResponse.bodyAsText())

        val reusedResponse = client.post("/v3/streams") {
            validHeaders()
            setBody(codec.encodeCreateRequest(GatewayStreamCoordinatorTest.request()))
        }
        assertEquals(HttpStatusCode.OK, reusedResponse.status)
        assertEquals(descriptor, codec.decodeDescriptor(reusedResponse.bodyAsText()))
        assertEquals(1, fixture.providerCalls)

        val conflict = client.post("/v3/streams") {
            validHeaders()
            setBody(
                codec.encodeCreateRequest(
                    GatewayStreamCoordinatorTest.request().copy(
                        messages = listOf(GatewayStreamCoordinatorTest.message("changed")),
                    ),
                ),
            )
        }
        assertEquals(HttpStatusCode.Conflict, conflict.status)
        assertFalse(conflict.bodyAsText().contains("changed"))

        val stream = client.get("/v3/streams/${descriptor.streamId}/events?afterSequence=-1") {
            readHeaders("user-a")
        }
        assertEquals(HttpStatusCode.OK, stream.status)
        assertExposesGatewayHeaders(stream.headers[HttpHeaders.AccessControlExposeHeaders])
        assertTrue(stream.headers[HttpHeaders.ContentType]?.startsWith("text/event-stream") == true)
        val allEvents = parseSse(stream.bodyAsText())
        assertEquals(listOf(0L, 1L, 2L), allEvents.map { it.sequence })
        assertTrue(stream.bodyAsText().contains("event: $GATEWAY_SSE_EVENT"))

        val resumed = client.get("/v3/streams/${descriptor.streamId}/events?afterSequence=1") {
            readHeaders("user-a")
        }
        assertEquals(listOf(2L), parseSse(resumed.bodyAsText()).map { it.sequence })

        val otherOwner = client.get("/v3/streams/${descriptor.streamId}/events?afterSequence=-1") {
            readHeaders("user-b")
        }
        assertEquals(HttpStatusCode.NotFound, otherOwner.status)

        val invalidCursor = client.get("/v3/streams/${descriptor.streamId}/events?afterSequence=99") {
            readHeaders("user-a")
        }
        assertEquals(HttpStatusCode.BadRequest, invalidCursor.status)
        assertEquals("invalid_cursor", codec.decodeProblem(invalidCursor.bodyAsText()).code)

        listOf(
            "afterSequence=-1&afterSequence=0",
            "afterSequence=-1&unexpected=true",
        ).forEach { query ->
            val ambiguous = client.get("/v3/streams/${descriptor.streamId}/events?$query") {
                readHeaders("user-a")
            }
            assertEquals(HttpStatusCode.BadRequest, ambiguous.status)
            assertEquals("invalid_cursor", codec.decodeProblem(ambiguous.bodyAsText()).code)
        }

        val queriedCreate = client.post("/v3/streams?unexpected=true") {
            validHeaders()
            setBody(codec.encodeCreateRequest(GatewayStreamCoordinatorTest.request()))
        }
        assertEquals(HttpStatusCode.BadRequest, queriedCreate.status)

        val queriedCancel = client.delete("/v3/streams/${descriptor.streamId}?unexpected=true") {
            readHeaders("user-a")
        }
        assertEquals(HttpStatusCode.BadRequest, queriedCancel.status)

        fixture.close()
    }

    @Test
    fun malformedAndLimitedRequestsReturnStableProblemsWithoutSecrets() = testApplication {
        val fixture = HttpFixture(rateAllowed = false)
        application { installMagratheaGateway(fixture.dependencies) }

        val wrongVersion = client.post("/v3/streams") {
            header(GATEWAY_VERSION_HEADER, "1")
            header(HttpHeaders.Authorization, "Bearer user-a")
            header(HttpHeaders.Origin, ALLOWED_ORIGIN)
            header(GATEWAY_IDEMPOTENCY_HEADER, "session-1:0")
            contentType(ContentType.Application.Json)
            setBody(codec.encodeCreateRequest(GatewayStreamCoordinatorTest.request()))
        }
        assertEquals(HttpStatusCode.BadRequest, wrongVersion.status)
        assertEquals("invalid_request", codec.decodeProblem(wrongVersion.bodyAsText()).code)

        val rateLimited = client.post("/v3/streams") {
            validHeaders()
            setBody(codec.encodeCreateRequest(GatewayStreamCoordinatorTest.request()))
        }
        assertEquals(HttpStatusCode.TooManyRequests, rateLimited.status)
        assertSensitiveResponseHeaders(rateLimited.headers[HttpHeaders.CacheControl], rateLimited.headers["X-Content-Type-Options"])
        assertEquals("1", rateLimited.headers[HttpHeaders.RetryAfter])
        assertExposesGatewayHeaders(rateLimited.headers[HttpHeaders.AccessControlExposeHeaders])
        val body = rateLimited.bodyAsText()
        assertEquals("rate_limited", codec.decodeProblem(body).code)
        assertFalse(body.contains("server-only-secret"))
        assertFalse(body.contains("hello from browser"))

        fixture.close()
    }

    @Test
    fun preflightCsrfAuthorizationQuotaIdempotencyAndBodyLimitsFailClosed() = testApplication {
        val fixture = HttpFixture()
        application {
            installMagratheaGateway(
                fixture.dependencies,
                GatewayHttpConfig(maxRequestBodyBytes = 512),
            )
        }

        val preflight = client.options("/v3/streams") {
            header(HttpHeaders.Origin, ALLOWED_ORIGIN)
            header(HttpHeaders.AccessControlRequestMethod, "POST")
            header(HttpHeaders.AccessControlRequestHeaders, GATEWAY_CSRF_HEADER)
        }
        assertEquals(HttpStatusCode.NoContent, preflight.status)
        assertEquals(ALLOWED_ORIGIN, preflight.headers[HttpHeaders.AccessControlAllowOrigin])
        assertNotEquals("*", preflight.headers[HttpHeaders.AccessControlAllowOrigin])
        assertEquals("true", preflight.headers[HttpHeaders.AccessControlAllowCredentials])
        assertTrue(preflight.headers[HttpHeaders.Vary].orEmpty().contains(HttpHeaders.Origin))

        val missingCsrf = client.post("/v3/streams") {
            validHeaders(includeCsrf = false)
            setBody(codec.encodeCreateRequest(GatewayStreamCoordinatorTest.request()))
        }
        assertEquals(HttpStatusCode.Unauthorized, missingCsrf.status)

        val mismatchedIdempotency = client.post("/v3/streams") {
            validHeaders(idempotencyKey = "different-request")
            setBody(codec.encodeCreateRequest(GatewayStreamCoordinatorTest.request()))
        }
        assertEquals(HttpStatusCode.BadRequest, mismatchedIdempotency.status)
        assertEquals("invalid_request", codec.decodeProblem(mismatchedIdempotency.bodyAsText()).code)

        val oversized = client.post("/v3/streams") {
            validHeaders()
            setBody("x".repeat(513))
        }
        assertEquals(HttpStatusCode.BadRequest, oversized.status)
        assertEquals("invalid_request", codec.decodeProblem(oversized.bodyAsText()).code)
        fixture.close()
    }

    @Test
    fun authorizationIsAMandatoryServerDecision() = testApplication {
        val unauthorizedFixture = HttpFixture(authorizerAllowed = false)
        application { installMagratheaGateway(unauthorizedFixture.dependencies) }
        val forbidden = client.post("/v3/streams") {
            validHeaders()
            setBody(codec.encodeCreateRequest(GatewayStreamCoordinatorTest.request()))
        }
        assertEquals(HttpStatusCode.Forbidden, forbidden.status)
        assertEquals(0, unauthorizedFixture.providerCalls)
        unauthorizedFixture.close()
    }

    @Test
    fun quotaDenialReturnsStableRateResponseBeforeProviderWork() = testApplication {
        val fixture = HttpFixture(quotaAllowed = false)
        application { installMagratheaGateway(fixture.dependencies) }
        val denied = client.post("/v3/streams") {
            validHeaders()
            setBody(codec.encodeCreateRequest(GatewayStreamCoordinatorTest.request()))
        }

        assertEquals(HttpStatusCode.TooManyRequests, denied.status)
        assertEquals("quota_exceeded", codec.decodeProblem(denied.bodyAsText()).code)
        assertEquals("1", denied.headers[HttpHeaders.RetryAfter])
        assertEquals(0, fixture.providerCalls)
        fixture.close()
    }

    @Test
    fun createReportsATerminalInvocationAsInvalidatedBeforeReplay() = testApplication {
        val fixture = HttpFixture(hangProvider = true)
        application { installMagratheaGateway(fixture.dependencies) }
        val createRequest = GatewayStreamCoordinatorTest.request()
        val created = client.post("/v3/streams") {
            validHeaders()
            setBody(codec.encodeCreateRequest(createRequest))
        }
        val descriptor = codec.decodeDescriptor(created.bodyAsText())

        val cancelled = client.delete("/v3/streams/${descriptor.streamId}") {
            readHeaders("user-a")
        }
        assertEquals(HttpStatusCode.NoContent, cancelled.status)

        val invalidated = client.post("/v3/streams") {
            validHeaders()
            setBody(codec.encodeCreateRequest(createRequest))
        }

        assertEquals(HttpStatusCode.Conflict, invalidated.status)
        assertEquals(
            GATEWAY_INVOCATION_INVALIDATED_PROBLEM_CODE,
            codec.decodeProblem(invalidated.bodyAsText()).code,
        )
        assertEquals(1, fixture.providerCalls)
        fixture.close()
    }

    @Test
    fun resolveIsReadOnlyForActiveAndInvalidatedInvocations() = testApplication {
        val fixture = HttpFixture(hangProvider = true)
        application { installMagratheaGateway(fixture.dependencies) }
        val request = GatewayStreamCoordinatorTest.request()
        val created = client.post("/v3/streams") {
            validHeaders()
            setBody(codec.encodeCreateRequest(request))
        }
        val descriptor = codec.decodeDescriptor(created.bodyAsText())

        val active = client.get("/v3/streams") {
            readHeaders("user-a")
            header(GATEWAY_IDEMPOTENCY_HEADER, request.requestId)
        }
        assertEquals(HttpStatusCode.OK, active.status)
        assertEquals(descriptor, codec.decodeDescriptor(active.bodyAsText()))
        assertEquals(1, fixture.providerCalls)

        client.delete("/v3/streams/${descriptor.streamId}") {
            readHeaders("user-a")
        }
        val invalidated = client.get("/v3/streams") {
            readHeaders("user-a")
            header(GATEWAY_IDEMPOTENCY_HEADER, request.requestId)
        }
        assertEquals(HttpStatusCode.Conflict, invalidated.status)
        assertEquals(
            GATEWAY_INVOCATION_INVALIDATED_PROBLEM_CODE,
            codec.decodeProblem(invalidated.bodyAsText()).code,
        )
        assertEquals(1, fixture.providerCalls)
        fixture.close()
    }

    @Test
    fun resolveReturnsRetainedTerminalAndUnknownFailsClosedWithoutProviderWork() = testApplication {
        val fixture = HttpFixture()
        application { installMagratheaGateway(fixture.dependencies) }
        val request = GatewayStreamCoordinatorTest.request()
        val created = client.post("/v3/streams") {
            validHeaders()
            setBody(codec.encodeCreateRequest(request))
        }
        val descriptor = codec.decodeDescriptor(created.bodyAsText())
        val replay = client.get("/v3/streams/${descriptor.streamId}/events?afterSequence=-1") {
            readHeaders("user-a")
        }
        assertIs<GatewayEvent.Completed>(parseSse(replay.bodyAsText()).last().event)

        val terminal = client.get("/v3/streams") {
            readHeaders("user-a")
            header(GATEWAY_IDEMPOTENCY_HEADER, request.requestId)
        }
        assertEquals(HttpStatusCode.OK, terminal.status)
        assertEquals(descriptor, codec.decodeDescriptor(terminal.bodyAsText()))
        assertEquals(1, fixture.providerCalls)

        val unknown = client.get("/v3/streams") {
            readHeaders("user-a")
            header(GATEWAY_IDEMPOTENCY_HEADER, "unknown-session:0")
        }
        assertEquals(HttpStatusCode.NotFound, unknown.status)
        assertEquals(
            GATEWAY_INVOCATION_UNKNOWN_PROBLEM_CODE,
            codec.decodeProblem(unknown.bodyAsText()).code,
        )
        assertEquals(1, fixture.providerCalls)
        fixture.close()
    }

    @Test
    fun resolveReportsExpiredTerminalReplayWithoutRepeatingProviderWork() = testApplication {
        val fixture = HttpFixture(
            coordinatorConfig = GatewayCoordinatorConfig(
                terminalRetentionMillis = 25,
                idempotencyRetentionMillis = 5_000,
                streamLifetimeMillis = 25,
            ),
        )
        application { installMagratheaGateway(fixture.dependencies) }
        val request = GatewayStreamCoordinatorTest.request()
        val created = client.post("/v3/streams") {
            validHeaders()
            setBody(codec.encodeCreateRequest(request))
        }
        assertEquals(HttpStatusCode.Created, created.status)
        val descriptor = codec.decodeDescriptor(created.bodyAsText())
        val replay = client.get("/v3/streams/${descriptor.streamId}/events?afterSequence=-1") {
            readHeaders("user-a")
        }
        assertIs<GatewayEvent.Completed>(parseSse(replay.bodyAsText()).last().event)
        delay(500)

        val expired = client.get("/v3/streams") {
            readHeaders("user-a")
            header(GATEWAY_IDEMPOTENCY_HEADER, request.requestId)
        }

        assertEquals(HttpStatusCode.Gone, expired.status)
        assertEquals(
            GATEWAY_REPLAY_WINDOW_EXHAUSTED_PROBLEM_CODE,
            codec.decodeProblem(expired.bodyAsText()).code,
        )
        assertEquals(1, fixture.providerCalls)
        fixture.close()
    }

    @Test
    fun eventLeaseFailureIsMappedBeforeTheRouteCommitsSse200() = testApplication {
        val clock = MutableEpochClock()
        val fixture = HttpFixture(
            hangProvider = true,
            clock = clock,
            coordinatorConfig = GatewayCoordinatorConfig(
                terminalRetentionMillis = 60_000,
                streamLifetimeMillis = 60_000,
            ),
        )
        application { installMagratheaGateway(fixture.dependencies) }
        val created = client.post("/v3/streams") {
            validHeaders()
            setBody(codec.encodeCreateRequest(GatewayStreamCoordinatorTest.request()))
        }
        val descriptor = codec.decodeDescriptor(created.bodyAsText())
        clock.nowEpochMs = descriptor.expiresAtEpochMs

        val expired = client.get("/v3/streams/${descriptor.streamId}/events?afterSequence=-1") {
            readHeaders("user-a")
        }

        assertEquals(HttpStatusCode.NotFound, expired.status)
        assertTrue(expired.headers[HttpHeaders.ContentType]?.startsWith("application/json") == true)
        assertEquals("stream_not_found", codec.decodeProblem(expired.bodyAsText()).code)
        fixture.close()
    }

    @Test
    fun collectionDeleteAbandonsTheScopedInvocationIdempotently() = testApplication {
        val fixture = HttpFixture(hangProvider = true)
        application { installMagratheaGateway(fixture.dependencies) }
        val request = GatewayStreamCoordinatorTest.request()
        val created = client.post("/v3/streams") {
            validHeaders()
            setBody(codec.encodeCreateRequest(request))
        }
        assertEquals(HttpStatusCode.Created, created.status)

        repeat(2) {
            val abandoned = client.delete("/v3/streams") {
                readHeaders("user-a")
                header(GATEWAY_IDEMPOTENCY_HEADER, request.requestId)
            }
            assertEquals(HttpStatusCode.NoContent, abandoned.status)
        }

        val invalidated = client.post("/v3/streams") {
            validHeaders()
            setBody(codec.encodeCreateRequest(request))
        }
        assertEquals(HttpStatusCode.Conflict, invalidated.status)
        assertEquals(
            GATEWAY_INVOCATION_INVALIDATED_PROBLEM_CODE,
            codec.decodeProblem(invalidated.bodyAsText()).code,
        )
        assertEquals(1, fixture.providerCalls)
        fixture.close()
    }

    @Test
    fun collectionDeleteBeforeCreateInvalidatesTheScopedRequestIdempotently() = testApplication {
        val fixture = HttpFixture(hangProvider = true)
        application { installMagratheaGateway(fixture.dependencies) }
        val request = GatewayStreamCoordinatorTest.request()

        repeat(2) {
            val abandoned = client.delete("/v3/streams") {
                readHeaders("user-a")
                header(GATEWAY_IDEMPOTENCY_HEADER, request.requestId)
            }
            assertEquals(HttpStatusCode.NoContent, abandoned.status)
        }

        val invalidated = client.post("/v3/streams") {
            validHeaders()
            setBody(codec.encodeCreateRequest(request))
        }
        assertEquals(HttpStatusCode.Conflict, invalidated.status)
        assertEquals(
            GATEWAY_INVOCATION_INVALIDATED_PROBLEM_CODE,
            codec.decodeProblem(invalidated.bodyAsText()).code,
        )
        assertEquals(0, fixture.providerCalls)
        fixture.close()
    }

    @Test
    fun idleSseWritesHeartbeatCommentsWithoutConsumingSemanticSequence() = testApplication {
        val fixture = HttpFixture(hangProvider = true)
        application {
            installMagratheaGateway(
                fixture.dependencies,
                GatewayHttpConfig(sseHeartbeatMillis = 10),
            )
        }
        val createdResponse = client.post("/v3/streams") {
            validHeaders()
            setBody(codec.encodeCreateRequest(GatewayStreamCoordinatorTest.request()))
        }
        val descriptor = codec.decodeDescriptor(createdResponse.bodyAsText())

        client.prepareGet("/v3/streams/${descriptor.streamId}/events?afterSequence=-1") {
            readHeaders("user-a")
        }.execute { response ->
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("no", response.headers["X-Accel-Buffering"])
            assertSensitiveResponseHeaders(
                response.headers[HttpHeaders.CacheControl],
                response.headers["X-Content-Type-Options"],
            )
            val channel = response.bodyAsChannel()
            val lines = mutableListOf<String>()
            withTimeout(2_000) {
                while (lines.none { it == ": heartbeat" }) {
                    lines += channel.readLine() ?: error("SSE ended before heartbeat")
                }
            }
            assertTrue(lines.any { it == "id: 0" })
            assertTrue(lines.any { it == ": heartbeat" })
            assertTrue(lines.none { it.startsWith("id: 1") })
        }

        fixture.close()
    }

    private fun io.ktor.client.request.HttpRequestBuilder.validHeaders(
        includeAuth: Boolean = true,
        includeCsrf: Boolean = true,
        origin: String = ALLOWED_ORIGIN,
        idempotencyKey: String = "session-1:0",
    ) {
        header(GATEWAY_VERSION_HEADER, GATEWAY_PROTOCOL_VERSION.toString())
        if (includeAuth) header(HttpHeaders.Authorization, "Bearer user-a")
        if (includeCsrf) header(GATEWAY_CSRF_HEADER, "csrf-user-a")
        header(HttpHeaders.Origin, origin)
        header(GATEWAY_IDEMPOTENCY_HEADER, idempotencyKey)
        contentType(ContentType.Application.Json)
    }

    private fun io.ktor.client.request.HttpRequestBuilder.readHeaders(user: String) {
        header(GATEWAY_VERSION_HEADER, GATEWAY_PROTOCOL_VERSION.toString())
        header(HttpHeaders.Authorization, "Bearer $user")
        header(GATEWAY_CSRF_HEADER, "csrf-$user")
        header(HttpHeaders.Origin, ALLOWED_ORIGIN)
    }

    private fun parseSse(body: String): List<GatewayStreamEnvelope> = body.lineSequence()
        .filter { it.startsWith("data: ") }
        .map { codec.decodeEnvelope(it.removePrefix("data: ")) }
        .toList()

    private fun assertExposesGatewayHeaders(value: String?) {
        val exposed = value.orEmpty().split(',').map(String::trim).toSet()
        assertTrue(GATEWAY_VERSION_HEADER in exposed)
        assertTrue(HttpHeaders.RetryAfter in exposed)
    }

    private fun assertSensitiveResponseHeaders(cacheControl: String?, contentTypeOptions: String?) {
        assertEquals("no-store", cacheControl)
        assertEquals("nosniff", contentTypeOptions)
    }

    private class HttpFixture(
        rateAllowed: Boolean = true,
        private val hangProvider: Boolean = false,
        authorizerAllowed: Boolean = true,
        quotaAllowed: Boolean = true,
        clock: EpochClock = saien.magrathea.core.SystemEpochClock,
        coordinatorConfig: GatewayCoordinatorConfig = GatewayCoordinatorConfig(),
    ) : AutoCloseable {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var providerCalls = 0
        private var nextId = 0
        private val provider = object : ProviderAdapter {
            override val key = "gemini"

            override suspend fun generate(request: ProviderRequest): Flow<ProviderChunk> {
                providerCalls += 1
                if (hangProvider) {
                    return flow { awaitCancellation() }
                }
                return flowOf(
                    ProviderChunk(events = listOf(ProviderEvent.TextDelta("hello"))),
                    ProviderChunk(events = listOf(ProviderEvent.Completed(stopReason = StopReason.COMPLETED))),
                )
            }
        }
        private val quota = object : GatewayQuotaManager {
            override suspend fun reserve(
                principal: GatewayPrincipal,
                request: saien.magrathea.gateway.protocol.GatewayCreateStreamRequest,
            ): GatewayQuotaDecision = if (quotaAllowed) {
                GatewayQuotaDecision.Granted(
                    object : GatewayQuotaReservation {
                        override suspend fun complete(usage: saien.magrathea.gateway.protocol.GatewayUsage?) = Unit
                        override suspend fun cancel() = Unit
                        override suspend fun fail() = Unit
                    },
                )
            } else {
                GatewayQuotaDecision.Denied(retryAfterMillis = 1_000)
            }
        }
        private val coordinator = GatewayStreamCoordinator(
            providerRegistry = InMemoryProviderRegistry(listOf(provider)),
            modelResolver = GatewayModelResolver { _, reference ->
                ModelDescriptor(
                    provider = reference.provider,
                    model = reference.model,
                    supportsStreaming = true,
                )
            },
            credentialResolver = GatewayProviderCredentialResolver { _, _ -> ProviderCredential("server-only-secret") },
            attachmentResolver = RejectingGatewayAttachmentResolver,
            quotaManager = quota,
            auditSink = GatewayAuditSink { },
            parentScope = scope,
            config = coordinatorConfig,
            idGenerator = IdGenerator { "http-${nextId++}" },
            clock = clock,
        )
        val dependencies = GatewayHttpDependencies(
            coordinator = coordinator,
            authenticator = GatewayAuthenticator { input ->
                when {
                    input.authorization == "Bearer user-a" && input.csrfToken == "csrf-user-a" ->
                        GatewayPrincipal("user-a", "tenant-a")
                    input.authorization == "Bearer user-b" && input.csrfToken == "csrf-user-b" ->
                        GatewayPrincipal("user-b", "tenant-b")
                    else -> null
                }
            },
            authorizer = GatewayAuthorizer { _, _, _ -> authorizerAllowed },
            originPolicy = GatewayOriginPolicy { origin, _ -> origin == ALLOWED_ORIGIN },
            rateLimiter = GatewayRateLimiter { _, _ ->
                GatewayLimitDecision(rateAllowed, retryAfterMillis = if (rateAllowed) null else 500)
            },
        )

        override fun close() = coordinator.close()
    }

    companion object {
        private const val ALLOWED_ORIGIN = "https://chat.example"
    }

    private class MutableEpochClock(
        var nowEpochMs: Long = 0,
    ) : EpochClock {
        override fun nowEpochMs(): Long = nowEpochMs
    }
}
