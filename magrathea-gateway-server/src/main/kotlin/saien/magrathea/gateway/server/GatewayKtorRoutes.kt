package saien.magrathea.gateway.server

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.contentType
import io.ktor.server.request.header
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.response.respondText
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.options
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.utils.io.readRemaining
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeStringUtf8
import kotlin.math.ceil
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.produceIn
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.readByteArray
import saien.magrathea.gateway.protocol.GATEWAY_PROTOCOL_VERSION
import saien.magrathea.gateway.protocol.GATEWAY_CSRF_HEADER
import saien.magrathea.gateway.protocol.GATEWAY_IDEMPOTENCY_HEADER
import saien.magrathea.gateway.protocol.GATEWAY_SSE_EVENT
import saien.magrathea.gateway.protocol.GATEWAY_VERSION_HEADER
import saien.magrathea.gateway.protocol.GatewayProblem
import saien.magrathea.gateway.protocol.GatewayProtocolCodec
import saien.magrathea.gateway.protocol.GatewayProtocolException

data class GatewayHttpConfig(
    val basePath: String = "/v1/streams",
    val maxRequestBodyBytes: Int = 8 * 1024 * 1024,
    val sseHeartbeatMillis: Long = 15_000,
) {
    init {
        require(
            GATEWAY_V1_BASE_PATH.matches(basePath) &&
                basePath.split('/').none { segment -> segment == "." || segment == ".." },
        ) {
            "Gateway basePath must be a safe path ending in /v1/streams"
        }
        require(maxRequestBodyBytes > 0)
        require(sseHeartbeatMillis > 0)
    }
}

data class GatewayHttpDependencies(
    val coordinator: GatewayStreamCoordinator,
    val authenticator: GatewayAuthenticator,
    val authorizer: GatewayAuthorizer,
    val originPolicy: GatewayOriginPolicy,
    val rateLimiter: GatewayRateLimiter,
    val codec: GatewayProtocolCodec = GatewayProtocolCodec(),
)

fun Application.installMagratheaGateway(
    dependencies: GatewayHttpDependencies,
    config: GatewayHttpConfig = GatewayHttpConfig(),
) {
    routing {
        options(config.basePath) {
            call.handlePreflight(dependencies)
        }
        options("${config.basePath}/{streamId}") {
            call.handlePreflight(dependencies)
        }
        options("${config.basePath}/{streamId}/events") {
            call.handlePreflight(dependencies)
        }

        post(config.basePath) {
            call.handleGateway(dependencies.codec) {
                enforceVersion()
                enforceOrigin(dependencies, GatewayOperation.CREATE_STREAM)
                val principal = authenticate(dependencies)
                enforceRateLimit(dependencies, principal, GatewayOperation.CREATE_STREAM)
                enforceJsonContentType()
                enforceNoQueryParameters()
                val body = receiveBoundedText(config.maxRequestBodyBytes)
                val createRequest = dependencies.codec.decodeCreateRequest(body)
                val idempotencyKey = request.header(GATEWAY_IDEMPOTENCY_HEADER)
                    ?: throw GatewayProtocolException("Missing Idempotency-Key")
                if (idempotencyKey != createRequest.requestId) {
                    throw GatewayProtocolException("Idempotency-Key does not match requestId")
                }
                if (!dependencies.authorizer.authorize(principal, GatewayOperation.CREATE_STREAM, createRequest)) {
                    throw GatewayAuthorizationException()
                }
                val outcome = dependencies.coordinator.create(principal, createRequest)
                response.headers.append(GATEWAY_VERSION_HEADER, GATEWAY_PROTOCOL_VERSION.toString())
                respondText(
                    text = dependencies.codec.encodeDescriptor(outcome.descriptor),
                    contentType = ContentType.Application.Json,
                    status = if (outcome.created) HttpStatusCode.Created else HttpStatusCode.OK,
                )
            }
        }

        get("${config.basePath}/{streamId}/events") {
            call.handleGateway(dependencies.codec) {
                enforceVersion()
                enforceOrigin(dependencies, GatewayOperation.READ_STREAM)
                val principal = authenticate(dependencies)
                enforceRateLimit(dependencies, principal, GatewayOperation.READ_STREAM)
                if (!dependencies.authorizer.authorize(principal, GatewayOperation.READ_STREAM, null)) {
                    throw GatewayAuthorizationException()
                }
                val streamId = parameters["streamId"] ?: throw GatewayStreamNotFoundException()
                val afterSequence = readAfterSequence()
                val events = dependencies.coordinator.events(principal, streamId, afterSequence)
                response.headers.append(GATEWAY_VERSION_HEADER, GATEWAY_PROTOCOL_VERSION.toString())
                response.headers.append("X-Accel-Buffering", "no")
                respondBytesWriter(contentType = EVENT_STREAM_CONTENT_TYPE) {
                    writeGatewayEventStream(events, dependencies.codec, config.sseHeartbeatMillis)
                }
            }
        }

        delete("${config.basePath}/{streamId}") {
            call.handleGateway(dependencies.codec) {
                enforceVersion()
                enforceOrigin(dependencies, GatewayOperation.CANCEL_STREAM)
                val principal = authenticate(dependencies)
                enforceRateLimit(dependencies, principal, GatewayOperation.CANCEL_STREAM)
                if (!dependencies.authorizer.authorize(principal, GatewayOperation.CANCEL_STREAM, null)) {
                    throw GatewayAuthorizationException()
                }
                enforceNoQueryParameters()
                val streamId = parameters["streamId"] ?: throw GatewayStreamNotFoundException()
                dependencies.coordinator.cancel(principal, streamId)
                response.headers.append(GATEWAY_VERSION_HEADER, GATEWAY_PROTOCOL_VERSION.toString())
                respond(HttpStatusCode.NoContent)
            }
        }
    }
}

private suspend fun ApplicationCall.handlePreflight(dependencies: GatewayHttpDependencies) {
    val origin = request.header(HttpHeaders.Origin)
    if (origin == null || !dependencies.originPolicy.isAllowed(origin, null)) {
        respond(HttpStatusCode.Forbidden)
        return
    }
    applyCorsHeaders(origin)
    response.headers.append(
        HttpHeaders.AccessControlAllowMethods,
        listOf(HttpMethod.Post, HttpMethod.Get, HttpMethod.Delete).joinToString(",") { it.value },
    )
    response.headers.append(
        HttpHeaders.AccessControlAllowHeaders,
        listOf(
            HttpHeaders.ContentType,
            HttpHeaders.Authorization,
            GATEWAY_IDEMPOTENCY_HEADER,
            GATEWAY_VERSION_HEADER,
            GATEWAY_CSRF_HEADER,
        )
            .joinToString(","),
    )
    response.headers.append(HttpHeaders.AccessControlMaxAge, "600")
    respond(HttpStatusCode.NoContent)
}

private suspend fun ApplicationCall.enforceOrigin(
    dependencies: GatewayHttpDependencies,
    operation: GatewayOperation,
) {
    val origin = request.header(HttpHeaders.Origin)
    if (!dependencies.originPolicy.isAllowed(origin, operation)) throw GatewayOriginException()
    if (origin != null) applyCorsHeaders(origin)
}

private fun ApplicationCall.applyCorsHeaders(origin: String) {
    response.headers.append(HttpHeaders.AccessControlAllowOrigin, origin)
    response.headers.append(HttpHeaders.AccessControlAllowCredentials, "true")
    response.headers.append(
        HttpHeaders.AccessControlExposeHeaders,
        listOf(GATEWAY_VERSION_HEADER, HttpHeaders.RetryAfter).joinToString(","),
    )
    response.headers.append(HttpHeaders.Vary, HttpHeaders.Origin)
}

private fun ApplicationCall.enforceVersion() {
    if (request.header(GATEWAY_VERSION_HEADER) != GATEWAY_PROTOCOL_VERSION.toString()) {
        throw GatewayProtocolException("Missing or unsupported Gateway version header")
    }
}

private suspend fun ApplicationCall.authenticate(dependencies: GatewayHttpDependencies): GatewayPrincipal {
    return dependencies.authenticator.authenticate(
        GatewayAuthenticationInput(
            authorization = request.header(HttpHeaders.Authorization),
            cookie = request.header(HttpHeaders.Cookie),
            csrfToken = request.header(GATEWAY_CSRF_HEADER),
        ),
    ) ?: throw GatewayAuthenticationException()
}

private suspend fun ApplicationCall.enforceRateLimit(
    dependencies: GatewayHttpDependencies,
    principal: GatewayPrincipal,
    operation: GatewayOperation,
) {
    val decision = dependencies.rateLimiter.check(principal, operation)
    if (!decision.allowed) throw GatewayRateLimitException(decision.retryAfterMillis)
}

private fun ApplicationCall.enforceJsonContentType() {
    if (request.contentType().withoutParameters() != ContentType.Application.Json) {
        throw GatewayProtocolException("Content-Type must be application/json")
    }
}

private fun ApplicationCall.enforceNoQueryParameters() {
    if (request.queryParameters.names().isNotEmpty()) {
        throw GatewayProtocolException("Gateway route does not accept query parameters")
    }
}

private fun ApplicationCall.readAfterSequence(): Long {
    val query = request.queryParameters
    if (query.names().any { it != "afterSequence" }) throw GatewayCursorException()
    val values = query.getAll("afterSequence").orEmpty()
    if (values.size > 1) throw GatewayCursorException()
    return values.singleOrNull()?.toLongOrNull() ?: if (values.isEmpty()) -1L else throw GatewayCursorException()
}

private suspend fun ApplicationCall.receiveBoundedText(maxBytes: Int): String {
    request.header(HttpHeaders.ContentLength)?.toLongOrNull()?.let { contentLength ->
        if (contentLength > maxBytes) throw GatewayProtocolException("Gateway request body exceeds configured limit")
    }
    val source = receiveChannel().readRemaining(maxBytes.toLong() + 1)
    val bytes = source.readByteArray()
    if (bytes.size > maxBytes) throw GatewayProtocolException("Gateway request body exceeds configured limit")
    return bytes.decodeToString(throwOnInvalidSequence = true)
}

private suspend inline fun ApplicationCall.handleGateway(
    codec: GatewayProtocolCodec,
    crossinline block: suspend ApplicationCall.() -> Unit,
) {
    applySensitiveResponseHeaders()
    try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Throwable) {
        val mapped = failure.toHttpFailure()
        mapped.retryAfterMillis?.let { retryAfterMillis ->
            response.headers.append(HttpHeaders.RetryAfter, ceil(retryAfterMillis / 1_000.0).toLong().toString())
        }
        response.headers.append(GATEWAY_VERSION_HEADER, GATEWAY_PROTOCOL_VERSION.toString())
        respondText(
            text = codec.encodeProblem(
                GatewayProblem(
                    code = mapped.code,
                    message = mapped.message,
                    retryAfterMillis = mapped.retryAfterMillis,
                ),
            ),
            contentType = ContentType.Application.Json,
            status = mapped.status,
        )
    }
}

private fun ApplicationCall.applySensitiveResponseHeaders() {
    response.headers.append(HttpHeaders.CacheControl, "no-store")
    response.headers.append("X-Content-Type-Options", "nosniff")
}

private suspend fun ByteWriteChannel.writeGatewayEventStream(
    events: Flow<saien.magrathea.gateway.protocol.GatewayStreamEnvelope>,
    codec: GatewayProtocolCodec,
    heartbeatMillis: Long,
) = coroutineScope {
    val eventChannel = events.produceIn(this)
    try {
        while (true) {
            val received = withTimeoutOrNull(heartbeatMillis) { eventChannel.receiveCatching() }
            if (received == null) {
                this@writeGatewayEventStream.writeStringUtf8(": heartbeat\n\n")
                this@writeGatewayEventStream.flush()
                continue
            }
            val envelope = received.getOrNull() ?: break
            this@writeGatewayEventStream.writeStringUtf8("id: ${envelope.sequence}\n")
            this@writeGatewayEventStream.writeStringUtf8("event: $GATEWAY_SSE_EVENT\n")
            this@writeGatewayEventStream.writeStringUtf8("data: ${codec.encodeEnvelope(envelope)}\n\n")
            this@writeGatewayEventStream.flush()
        }
    } finally {
        eventChannel.cancel()
    }
}

private fun Throwable.toHttpFailure(): HttpFailure = when (this) {
    is GatewayAuthenticationException -> HttpFailure(HttpStatusCode.Unauthorized, "authentication_required", "Authentication required")
    is GatewayAuthorizationException -> HttpFailure(HttpStatusCode.Forbidden, "forbidden", "Operation is not allowed")
    is GatewayOriginException -> HttpFailure(HttpStatusCode.Forbidden, "origin_forbidden", "Origin is not allowed")
    is GatewayRateLimitException -> HttpFailure(HttpStatusCode.TooManyRequests, "rate_limited", "Rate limit exceeded", retryAfterMillis)
    is GatewayQuotaException -> HttpFailure(HttpStatusCode.TooManyRequests, "quota_exceeded", "Quota exceeded", retryAfterMillis)
    is GatewayIdempotencyConflictException -> HttpFailure(HttpStatusCode.Conflict, "idempotency_conflict", "Idempotency key conflict")
    is GatewayStreamNotFoundException -> HttpFailure(HttpStatusCode.NotFound, "stream_not_found", "Stream not found")
    is GatewayReplayWindowException -> HttpFailure(HttpStatusCode.Gone, "replay_window_exhausted", "Replay window exhausted")
    is GatewayCursorException -> HttpFailure(HttpStatusCode.BadRequest, "invalid_cursor", "Replay cursor is invalid")
    is GatewayProtocolException, is IllegalArgumentException -> HttpFailure(HttpStatusCode.BadRequest, "invalid_request", "Request is invalid")
    else -> HttpFailure(HttpStatusCode.InternalServerError, "internal_error", "Gateway request failed")
}

private data class HttpFailure(
    val status: HttpStatusCode,
    val code: String,
    val message: String,
    val retryAfterMillis: Long? = null,
)

private val EVENT_STREAM_CONTENT_TYPE = ContentType.parse("text/event-stream")
private val GATEWAY_V1_BASE_PATH = Regex("^(?:/[A-Za-z0-9._~-]+)*/v1/streams$")
