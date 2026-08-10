package saien.magrathea.provider.gateway

import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import saien.magrathea.core.AgentMessage
import saien.magrathea.core.AttachmentPart
import saien.magrathea.core.ToolImageAttachmentReference
import saien.magrathea.core.ToolResultImageContent
import saien.magrathea.core.ToolResultPart
import saien.magrathea.gateway.protocol.GATEWAY_ATTACHMENT_URI_PREFIX
import saien.magrathea.gateway.protocol.GATEWAY_CSRF_HEADER
import saien.magrathea.gateway.protocol.GATEWAY_IDEMPOTENCY_HEADER
import saien.magrathea.gateway.protocol.GATEWAY_INVOCATION_INVALIDATED_PROBLEM_CODE
import saien.magrathea.gateway.protocol.GATEWAY_INVOCATION_UNKNOWN_PROBLEM_CODE
import saien.magrathea.gateway.protocol.GATEWAY_REPLAY_WINDOW_EXHAUSTED_PROBLEM_CODE
import saien.magrathea.gateway.protocol.GATEWAY_PROTOCOL_VERSION
import saien.magrathea.gateway.protocol.GATEWAY_SSE_EVENT
import saien.magrathea.gateway.protocol.GATEWAY_VERSION_HEADER
import saien.magrathea.gateway.protocol.GatewayAttachmentReference
import saien.magrathea.gateway.protocol.GatewayCreateStreamRequest
import saien.magrathea.gateway.protocol.GatewayEvent
import saien.magrathea.gateway.protocol.GatewayFailureCode
import saien.magrathea.gateway.protocol.GatewayGenerationOptions
import saien.magrathea.gateway.protocol.GatewayModelReference
import saien.magrathea.gateway.protocol.GatewayProtocolCodec
import saien.magrathea.gateway.protocol.GatewayProtocolException
import saien.magrathea.gateway.protocol.GatewayStreamDescriptor
import saien.magrathea.gateway.protocol.GatewayStreamValidator
import saien.magrathea.gateway.protocol.toProviderEventOrNull
import saien.magrathea.provider.api.DefaultHttpTransportConfig
import saien.magrathea.provider.api.HttpHeader
import saien.magrathea.provider.api.HttpMethod
import saien.magrathea.provider.api.HttpRequestSpec
import saien.magrathea.provider.api.HttpResponseSpec
import saien.magrathea.provider.api.HttpStreamFormat
import saien.magrathea.provider.api.HttpStreamFrame
import saien.magrathea.provider.api.HttpTimeoutConfig
import saien.magrathea.provider.api.HttpTransport
import saien.magrathea.provider.api.ProviderAdapter
import saien.magrathea.provider.api.ProviderAuthException
import saien.magrathea.provider.api.ProviderChunk
import saien.magrathea.provider.api.ProviderCancellationIntent
import saien.magrathea.provider.api.ProviderClientException
import saien.magrathea.provider.api.ProviderContextLimitException
import saien.magrathea.provider.api.ProviderException
import saien.magrathea.provider.api.ProviderHttpException
import saien.magrathea.provider.api.ProviderInvocation
import saien.magrathea.provider.api.ProviderInvocationIntent
import saien.magrathea.provider.api.ProviderInvocationInvalidatedException
import saien.magrathea.provider.api.ProviderInvocationResumeMode
import saien.magrathea.provider.api.ProviderNetworkException
import saien.magrathea.provider.api.ProviderProtocolException
import saien.magrathea.provider.api.ProviderRateLimitException
import saien.magrathea.provider.api.ProviderRequest
import saien.magrathea.provider.api.ProviderServerException
import saien.magrathea.provider.api.ProviderTimeoutException
import saien.magrathea.provider.api.ProviderTimeoutPhase
import saien.magrathea.provider.api.createDefaultHttpTransport
import saien.magrathea.provider.api.providerCancellationIntent
import saien.magrathea.provider.api.toHttpTimeoutConfig
import saien.magrathea.provider.api.requireSuccessful
import saien.magrathea.provider.api.sanitizedForModelBoundary

data class GatewayProviderConfig(
    val baseUrl: String,
    val maxReconnectAttempts: Int = 3,
    val initialReconnectDelayMillis: Long = 250,
    val maxReconnectDelayMillis: Long = 2_000,
) {
    val normalizedBaseUrl: String = baseUrl.trimEnd('/')

    init {
        require(isAllowedGatewayBaseUrl(normalizedBaseUrl)) {
            "Gateway baseUrl must use HTTPS, except for a loopback HTTP development server"
        }
        require('?' !in normalizedBaseUrl && '#' !in normalizedBaseUrl) {
            "Gateway baseUrl must not contain query or fragment"
        }
        require(maxReconnectAttempts >= 0)
        require(initialReconnectDelayMillis > 0)
        require(maxReconnectDelayMillis >= initialReconnectDelayMillis)
    }

    override fun toString(): String =
        "GatewayProviderConfig(" +
            "baseUrl=<configured>, " +
            "maxReconnectAttempts=$maxReconnectAttempts, " +
            "initialReconnectDelayMillis=$initialReconnectDelayMillis, " +
            "maxReconnectDelayMillis=$maxReconnectDelayMillis" +
            ")"
}

data class GatewaySessionHeaders(
    val authorization: String? = null,
    val csrfToken: String? = null,
) {
    init {
        require(authorization == null || authorization.isNotBlank())
        require(csrfToken == null || csrfToken.isNotBlank())
    }

    override fun toString(): String =
        "GatewaySessionHeaders(authorization=${if (authorization == null) "none" else "<redacted>"}, " +
            "csrfToken=${if (csrfToken == null) "none" else "<redacted>"})"
}

fun interface GatewaySessionHeadersProvider {
    suspend fun current(): GatewaySessionHeaders
}

fun interface GatewayAttachmentCatalog {
    suspend fun describe(id: String, mediaType: String): GatewayAttachmentReference
}

fun interface GatewayReconnectGate {
    /** Waits until the host permits another network attempt. The default never blocks. */
    suspend fun awaitReconnectPermission()
}

internal class GatewayRemoteCancellationException(message: String) :
    ProviderInvocationInvalidatedException(ProviderNetworkException(message))

class GatewayProviderAdapter(
    override val key: String,
    private val config: GatewayProviderConfig,
    private val sessionHeadersProvider: GatewaySessionHeadersProvider = GatewaySessionHeadersProvider {
        GatewaySessionHeaders()
    },
    private val attachmentCatalog: GatewayAttachmentCatalog? = null,
    private val reconnectGate: GatewayReconnectGate = GatewayReconnectGate { },
    private val transport: HttpTransport = createDefaultHttpTransport(DefaultHttpTransportConfig()),
    private val closeTransport: Boolean = true,
    private val codec: GatewayProtocolCodec = GatewayProtocolCodec(),
) : ProviderAdapter {
    override val invocationResumeMode: ProviderInvocationResumeMode =
        ProviderInvocationResumeMode.REATTACH

    init {
        require(key.isNotBlank()) { "Gateway Provider key must not be blank" }
    }

    override suspend fun abandon(invocation: ProviderInvocation) {
        var attempt = 0
        while (true) {
            try {
                abandonByRequestId(invocation.requestId)
                return
            } catch (failure: ProviderException) {
                if (!failure.retryable || attempt >= config.maxReconnectAttempts) throw failure
                reconnectGate.awaitReconnectPermission()
                delay(
                    maxOf(
                        reconnectDelayMillis(attempt),
                        (failure as? ProviderHttpException)?.retryAfterMillis ?: 0,
                    ),
                )
                attempt += 1
            }
        }
    }

    override suspend fun generate(request: ProviderRequest): Flow<ProviderChunk> = flow {
        validateProviderBoundary(request)
        val invocation = requireNotNull(request.invocation) {
            "Gateway Provider requires a stable ProviderInvocation"
        }
        val httpTimeouts = request.timeouts.toHttpTimeoutConfig()
        var descriptor: GatewayStreamDescriptor? = null
        var remoteCompleted = false
        try {
            val createRequest = if (request.invocationIntent == ProviderInvocationIntent.CREATE) {
                val messages = request.messages.map(AgentMessage::projectForGateway)
                GatewayCreateStreamRequest(
                    requestId = invocation.requestId,
                    sessionId = invocation.sessionId.value,
                    turn = invocation.turn,
                    model = GatewayModelReference(
                        provider = request.model.provider,
                        model = request.model.model,
                    ),
                    reasoningPreference = request.reasoningPreference,
                    messages = messages,
                    tools = request.tools,
                    options = GatewayGenerationOptions(
                        temperature = request.temperature,
                        maxTokens = request.maxTokens,
                    ),
                    attachments = resolveAttachments(messages),
                )
            } else {
                null
            }
            var activeDescriptor = when (request.invocationIntent) {
                ProviderInvocationIntent.CREATE -> create(requireNotNull(createRequest), httpTimeouts)
                ProviderInvocationIntent.REATTACH -> resolveExistingWithRetry(
                    invocation = invocation,
                    timeouts = httpTimeouts,
                )
            }
            descriptor = activeDescriptor
            var lastSequence = -1L
            var reconnectAttempt = 0
            var completedDelivered = false
            while (!completedDelivered) {
                val validator = GatewayStreamValidator(
                    descriptor = activeDescriptor,
                    firstExpectedSequence = lastSequence + 1,
                    requireOpenedEvent = lastSequence < 0,
                )
                var responseStarted = false
                var transportCompleted = false
                try {
                    transport.stream(
                        request = HttpRequestSpec(
                            method = HttpMethod.GET,
                            url = eventsUrl(activeDescriptor.streamId, lastSequence),
                            headers = requestHeaders(accept = "text/event-stream"),
                            timeouts = httpTimeouts,
                        ),
                        format = HttpStreamFormat.SERVER_SENT_EVENTS,
                    ).collect { frame ->
                        when (frame) {
                            is HttpStreamFrame.ResponseStarted -> {
                                validateStreamResponse(frame.statusCode, frame.headers)
                                responseStarted = true
                            }
                            is HttpStreamFrame.ServerSentEvent -> {
                                if (!responseStarted) throw ProviderProtocolException("Gateway event arrived before response metadata")
                                if (frame.event != GATEWAY_SSE_EVENT) {
                                    throw ProviderProtocolException("Unexpected Gateway SSE event type")
                                }
                                val envelope = codec.decodeEnvelope(frame.data)
                                if (frame.id != envelope.sequence.toString()) {
                                    throw ProviderProtocolException("Gateway SSE id does not match envelope sequence")
                                }
                                val event = validator.accept(envelope)
                                lastSequence = envelope.sequence
                                when (event) {
                                    is GatewayEvent.StreamOpened -> Unit
                                    is GatewayEvent.Failed -> throw event.toProviderFailure()
                                    is GatewayEvent.Cancelled -> throw GatewayRemoteCancellationException(
                                        event.reason ?: "Gateway stream was cancelled",
                                    )
                                    else -> {
                                        val providerEvent = event.toProviderEventOrNull()
                                            ?: throw ProviderProtocolException("Gateway event cannot map to ProviderEvent")
                                        val completes = event is GatewayEvent.Completed
                                        if (completes) remoteCompleted = true
                                        emit(ProviderChunk(events = listOf(providerEvent)))
                                        if (completes) {
                                            completedDelivered = true
                                            throw GatewayCompletedCollected
                                        }
                                    }
                                }
                            }
                            is HttpStreamFrame.RetryHint -> Unit
                            HttpStreamFrame.Completed -> transportCompleted = true
                            is HttpStreamFrame.JsonLine -> throw ProviderProtocolException(
                                "Gateway SSE transport emitted a JSON line",
                            )
                        }
                    }
                    if (completedDelivered) break
                    if (!transportCompleted) {
                        throw ProviderNetworkException("Gateway event stream disconnected")
                    }
                    throw ProviderNetworkException("Gateway event stream ended before terminal event")
                } catch (_: GatewayCompletedCollected) {
                    break
                } catch (_: GatewayStreamMissing) {
                    if (completedDelivered) break
                    val retainedDescriptor = resolveExistingWithRetry(
                        invocation = invocation,
                        timeouts = httpTimeouts,
                    )
                    if (retainedDescriptor.streamId != activeDescriptor.streamId) {
                        throw ProviderProtocolException(
                            "Gateway idempotency identity resolved to a different stream",
                        )
                    }
                    activeDescriptor = retainedDescriptor
                    descriptor = retainedDescriptor
                    if (reconnectAttempt >= config.maxReconnectAttempts) {
                        throw ProviderNetworkException(
                            "Gateway retained invocation stream is temporarily unavailable",
                        )
                    }
                    reconnectGate.awaitReconnectPermission()
                    delay(reconnectDelayMillis(reconnectAttempt))
                    reconnectAttempt += 1
                } catch (cancelled: CancellationException) {
                    if (completedDelivered) break
                    throw cancelled
                } catch (network: ProviderNetworkException) {
                    if (completedDelivered) break
                    if (reconnectAttempt >= config.maxReconnectAttempts) throw network
                    reconnectGate.awaitReconnectPermission()
                    delay(reconnectDelayMillis(reconnectAttempt))
                    reconnectAttempt += 1
                } catch (failure: Throwable) {
                    if (completedDelivered) break
                    throw failure
                }
            }
        } catch (cancelled: CancellationException) {
            if (
                !remoteCompleted &&
                coroutineContext.providerCancellationIntent() == ProviderCancellationIntent.CANCEL
            ) {
                withContext(NonCancellable) {
                    try {
                        val createdDescriptor = descriptor
                        if (createdDescriptor == null) {
                            abandonByRequestId(invocation.requestId, httpTimeouts)
                        } else {
                            cancelByStreamId(createdDescriptor.streamId, httpTimeouts)
                        }
                    } catch (_: Throwable) {
                        // Cancellation remains authoritative if best-effort remote cleanup fails.
                    }
                }
            }
            throw cancelled
        }
    }

    private suspend fun abandonByRequestId(
        requestId: String,
        timeouts: HttpTimeoutConfig? = null,
    ) {
        transport.execute(
            HttpRequestSpec(
                method = HttpMethod.DELETE,
                url = streamsUrl(),
                headers = requestHeaders(
                    accept = "application/json",
                    additional = listOf(HttpHeader(GATEWAY_IDEMPOTENCY_HEADER, requestId)),
                ),
                timeouts = timeouts,
            ),
        ).requireSuccessful()
    }

    private suspend fun cancelByStreamId(
        streamId: String,
        timeouts: HttpTimeoutConfig,
    ) {
        transport.execute(
            HttpRequestSpec(
                method = HttpMethod.DELETE,
                url = streamUrl(streamId),
                headers = requestHeaders(accept = "application/json"),
                timeouts = timeouts,
            ),
        ).requireSuccessful()
    }

    override fun close() {
        if (closeTransport) transport.close()
    }

    private suspend fun create(
        request: GatewayCreateStreamRequest,
        timeouts: HttpTimeoutConfig,
    ): GatewayStreamDescriptor {
        val response = transport.execute(
            HttpRequestSpec(
                method = HttpMethod.POST,
                url = streamsUrl(),
                headers = requestHeaders(
                    accept = "application/json",
                    additional = listOf(
                        HttpHeader("Content-Type", "application/json"),
                        HttpHeader(GATEWAY_IDEMPOTENCY_HEADER, request.requestId),
                    ),
                ),
                body = codec.encodeCreateRequest(request),
                timeouts = timeouts,
            ),
        )
        if (response.statusCode !in 200..299) {
            throwKnownInvocationProblem(response)
            response.requireSuccessful()
        }
        validateJsonResponse(response)
        return codec.decodeDescriptor(response.body).also { descriptor ->
            if (descriptor.requestId != request.requestId || descriptor.sessionId != request.sessionId) {
                throw ProviderProtocolException("Gateway descriptor identity does not match request")
            }
        }
    }

    private suspend fun resolveExistingWithRetry(
        invocation: ProviderInvocation,
        timeouts: HttpTimeoutConfig,
    ): GatewayStreamDescriptor {
        var attempt = 0
        while (true) {
            try {
                val response = transport.execute(
                    HttpRequestSpec(
                        method = HttpMethod.GET,
                        url = streamsUrl(),
                        headers = requestHeaders(
                            accept = "application/json",
                            additional = listOf(
                                HttpHeader(GATEWAY_IDEMPOTENCY_HEADER, invocation.requestId),
                            ),
                        ),
                        timeouts = timeouts,
                    ),
                )
                if (response.statusCode !in 200..299) {
                    throwKnownInvocationProblem(response)
                    response.requireSuccessful()
                }
                validateJsonResponse(response)
                return codec.decodeDescriptor(response.body).also { descriptor ->
                    if (
                        descriptor.requestId != invocation.requestId ||
                        descriptor.sessionId != invocation.sessionId.value
                    ) {
                        throw ProviderProtocolException("Gateway descriptor identity does not match invocation")
                    }
                }
            } catch (network: ProviderNetworkException) {
                if (attempt >= config.maxReconnectAttempts) throw network
                reconnectGate.awaitReconnectPermission()
                delay(reconnectDelayMillis(attempt))
                attempt += 1
            }
        }
    }

    private fun throwKnownInvocationProblem(response: HttpResponseSpec) {
        if (response.statusCode != 404 && response.statusCode != 409 && response.statusCode != 410) return
        validateJsonResponse(response)
        when (codec.decodeProblem(response.body).code) {
            GATEWAY_INVOCATION_INVALIDATED_PROBLEM_CODE ->
                throw ProviderInvocationInvalidatedException(
                    ProviderNetworkException("Gateway invocation is no longer reattachable"),
                )
            GATEWAY_REPLAY_WINDOW_EXHAUSTED_PROBLEM_CODE ->
                throw ProviderInvocationInvalidatedException(
                    failure = ProviderProtocolException(
                        "Gateway terminal replay is no longer retained",
                    ),
                    retryable = false,
                )
            GATEWAY_INVOCATION_UNKNOWN_PROBLEM_CODE ->
                throw ProviderInvocationInvalidatedException(
                    failure = ProviderProtocolException(
                        "Gateway invocation identity is not retained",
                    ),
                    retryable = false,
                )
        }
    }

    private fun validateProviderBoundary(request: ProviderRequest) {
        if (request.credential != null || request.credentialRef != null) {
            throw ProviderProtocolException("Gateway request must not carry a Provider credential")
        }
        if (request.endpoint != null || request.headers.isNotEmpty()) {
            throw ProviderProtocolException("Gateway request must not carry an upstream endpoint or headers")
        }
        if (request.typedConfig != null) {
            throw ProviderProtocolException("Gateway request must not carry direct-Provider transport config")
        }
    }

    private suspend fun resolveAttachments(messages: List<AgentMessage>): List<GatewayAttachmentReference> {
        val attachments = messages.flatMap { message ->
            message.parts.flatMap { part ->
                when (part) {
                    is AttachmentPart -> listOf(GatewayAttachmentIdentity(part.uri, part.mimeType))
                    is ToolResultPart -> part.content.mapNotNull { content ->
                        val image = content as? ToolResultImageContent ?: return@mapNotNull null
                        val source = image.source as? ToolImageAttachmentReference
                            ?: throw ProviderProtocolException(
                                "Gateway Tool image result must use an uploaded attachment reference",
                            )
                        val mediaType = image.mimeType
                            ?: throw ProviderProtocolException("Gateway Tool image result requires a MIME type")
                        GatewayAttachmentIdentity(source.uri, mediaType)
                    }
                    else -> emptyList()
                }
            }
        }
        if (attachments.isEmpty()) return emptyList()
        val catalog = attachmentCatalog
            ?: throw ProviderProtocolException("Gateway attachment catalog is not configured")
        return attachments.map { attachment ->
            if (!attachment.uri.startsWith(GATEWAY_ATTACHMENT_URI_PREFIX)) {
                throw ProviderProtocolException("Gateway attachment must be an uploaded reference")
            }
            val id = attachment.uri.removePrefix(GATEWAY_ATTACHMENT_URI_PREFIX)
            catalog.describe(id, attachment.mediaType).also { reference ->
                if (reference.id != id || reference.mediaType != attachment.mediaType) {
                    throw ProviderProtocolException("Gateway attachment descriptor identity mismatch")
                }
            }
        }
    }

    private suspend fun requestHeaders(
        accept: String,
        additional: List<HttpHeader> = emptyList(),
    ): List<HttpHeader> {
        val session = sessionHeadersProvider.current()
        return buildList {
            add(HttpHeader("Accept", accept))
            add(HttpHeader(GATEWAY_VERSION_HEADER, GATEWAY_PROTOCOL_VERSION.toString()))
            session.authorization?.let { add(HttpHeader("Authorization", it)) }
            session.csrfToken?.let { add(HttpHeader(GATEWAY_CSRF_HEADER, it)) }
            addAll(additional)
        }
    }

    private fun validateJsonResponse(response: HttpResponseSpec) {
        validateVersionHeader(response.headers)
        val contentType = response.headers.firstValue("Content-Type")?.substringBefore(';')?.trim()?.lowercase()
        if (contentType != "application/json") throw ProviderProtocolException("Gateway response is not JSON")
    }

    private fun validateStreamResponse(statusCode: Int, headers: List<HttpHeader>) {
        if (statusCode == 404) {
            validateVersionHeader(headers)
            throw GatewayStreamMissing
        }
        if (statusCode == 410) {
            validateVersionHeader(headers)
            throw ProviderInvocationInvalidatedException(
                failure = ProviderProtocolException("Gateway replay window is no longer retained"),
                retryable = false,
            )
        }
        if (statusCode !in 200..299) {
            HttpResponseSpec(statusCode = statusCode, headers = headers, body = "")
                .requireSuccessful()
        }
        validateVersionHeader(headers)
        val contentType = headers.firstValue("Content-Type")?.substringBefore(';')?.trim()?.lowercase()
        if (contentType != "text/event-stream") throw ProviderProtocolException("Gateway response is not SSE")
    }

    private fun validateVersionHeader(headers: List<HttpHeader>) {
        if (headers.firstValue(GATEWAY_VERSION_HEADER) != GATEWAY_PROTOCOL_VERSION.toString()) {
            throw ProviderProtocolException("Gateway response version header is missing or unsupported")
        }
    }

    private fun reconnectDelayMillis(attempt: Int): Long {
        var value = config.initialReconnectDelayMillis
        repeat(attempt) {
            value = (value * 2).coerceAtMost(config.maxReconnectDelayMillis)
        }
        return value
    }

    private fun streamsUrl(): String = "${config.normalizedBaseUrl}/v3/streams"
    private fun streamUrl(streamId: String): String = "${streamsUrl()}/$streamId"
    private fun eventsUrl(streamId: String, afterSequence: Long): String =
        "${streamUrl(streamId)}/events?afterSequence=$afterSequence"
}

private data class GatewayAttachmentIdentity(
    val uri: String,
    val mediaType: String,
)

private object GatewayCompletedCollected : RuntimeException()

private object GatewayStreamMissing : RuntimeException()

private fun AgentMessage.projectForGateway(): AgentMessage = copy(
    parts = parts.map { part ->
        if (part is ToolResultPart) part.sanitizedForModelBoundary() else part
    },
)

private fun GatewayEvent.Failed.toProviderFailure(): ProviderException {
    if (code == GatewayFailureCode.CONTEXT_LIMIT) return ProviderContextLimitException()
    val failure = when (code) {
        GatewayFailureCode.AUTHENTICATION_FAILURE -> ProviderAuthException(
            message = "Gateway Provider authentication failed",
            statusCode = 401,
            retryAfterMillis = retryAfterMillis,
        )
        GatewayFailureCode.CLIENT_FAILURE -> ProviderClientException(
            message = "Gateway Provider rejected the request",
            statusCode = 400,
            retryAfterMillis = retryAfterMillis,
        )
        GatewayFailureCode.RATE_LIMIT -> ProviderRateLimitException(
            message = "Gateway Provider rate limit exceeded",
            retryAfterMillis = retryAfterMillis,
        )
        GatewayFailureCode.NETWORK_FAILURE -> ProviderNetworkException(
            message = "Gateway Provider network request failed",
        )
        GatewayFailureCode.TIMEOUT -> ProviderTimeoutException(
            phase = ProviderTimeoutPhase.PROVIDER_CALL,
        )
        GatewayFailureCode.SERVER_FAILURE -> ProviderServerException(
            message = "Gateway Provider request failed",
            statusCode = 502,
            retryAfterMillis = retryAfterMillis,
        )
        GatewayFailureCode.PROTOCOL_FAILURE, GatewayFailureCode.REPLAY_WINDOW_EXHAUSTED ->
            ProviderProtocolException("Gateway Provider response violated the protocol")
        GatewayFailureCode.INTERNAL_FAILURE -> ProviderServerException(
            message = "Gateway could not complete the Provider request",
            statusCode = 500,
        )
        GatewayFailureCode.CONTEXT_LIMIT -> error("Handled above")
    }
    return ProviderInvocationInvalidatedException(failure, retryable)
}

private fun List<HttpHeader>.firstValue(name: String): String? =
    firstOrNull { it.name.equals(name, ignoreCase = true) }?.value

private fun isAllowedGatewayBaseUrl(value: String): Boolean {
    if (
        value.isEmpty() || '?' in value || '#' in value || '@' in value || '\\' in value ||
        value.any { it.isWhitespace() || it.isISOControl() }
    ) {
        return false
    }
    val scheme = when {
        value.startsWith(HTTPS_SCHEME) -> HTTPS_SCHEME
        value.startsWith(HTTP_SCHEME) -> HTTP_SCHEME
        else -> return false
    }
    val remainder = value.removePrefix(scheme)
    val authority = remainder.substringBefore('/')
    val path = remainder.removePrefix(authority)
    if (authority.isEmpty() || !isSafeGatewayBasePath(path)) return false

    val parsedAuthority = parseGatewayAuthority(authority) ?: return false
    return scheme == HTTPS_SCHEME || parsedAuthority.host.lowercase() in LOOPBACK_HOSTS
}

private fun parseGatewayAuthority(value: String): GatewayAuthority? {
    val host: String
    val rawPort: String?
    if (value.startsWith('[')) {
        val closingBracket = value.indexOf(']')
        if (closingBracket <= 1) return null
        host = value.substring(0, closingBracket + 1)
        val suffix = value.substring(closingBracket + 1)
        rawPort = when {
            suffix.isEmpty() -> null
            suffix.startsWith(':') -> suffix.removePrefix(":").takeIf(String::isNotEmpty) ?: return null
            else -> return null
        }
        val address = host.substring(1, host.lastIndex)
        if (!isValidIpv6Address(address)) return null
    } else {
        if (value.count { it == ':' } > 1) return null
        val separator = value.indexOf(':')
        host = if (separator < 0) value else value.substring(0, separator)
        rawPort = if (separator < 0) null else value.substring(separator + 1).takeIf(String::isNotEmpty) ?: return null
        if (!isSafeGatewayHost(host)) return null
    }
    if (rawPort != null) {
        if (rawPort.any { !it.isDigit() }) return null
        val port = rawPort.toIntOrNull() ?: return null
        if (port !in 1..65_535) return null
    }
    return GatewayAuthority(host)
}

private fun isValidIpv6Address(value: String): Boolean {
    if (value.isEmpty() || value.countOccurrences("::") > 1) return false
    val hasCompression = "::" in value
    val rawGroups = value.split(':').filter(String::isNotEmpty)
    var groupCount = rawGroups.size
    rawGroups.forEachIndexed { index, group ->
        if ('.' in group) {
            if (index != rawGroups.lastIndex || !isValidIpv4Address(group)) return false
            groupCount += 1 // An IPv4 tail occupies two IPv6 groups rather than one.
        } else if (group.length !in 1..4 || group.any { !it.isAsciiHexDigit() }) {
            return false
        }
    }
    return if (hasCompression) groupCount < 8 else groupCount == 8
}

private fun isValidIpv4Address(value: String): Boolean {
    val groups = value.split('.')
    return groups.size == 4 && groups.all { group ->
        group.isNotEmpty() && group.length <= 3 && group.all(Char::isDigit) &&
            (group.length == 1 || group.first() != '0') && (group.toIntOrNull() ?: 256) in 0..255
    }
}

private fun String.countOccurrences(value: String): Int {
    var count = 0
    var offset = 0
    while (true) {
        val index = indexOf(value, offset)
        if (index < 0) return count
        count += 1
        offset = index + value.length
    }
}

private fun isSafeGatewayHost(value: String): Boolean {
    if (value.isEmpty() || value.startsWith('.') || value.endsWith('.')) return false
    return value.split('.').all { label ->
        label.isNotEmpty() && label.length <= 63 &&
            label.first() != '-' && label.last() != '-' &&
            label.all { it.isAsciiLetterOrDigit() || it == '-' }
    }
}

private fun isSafeGatewayBasePath(value: String): Boolean {
    if (value.isEmpty()) return true
    if (!value.startsWith('/') || value.endsWith('/') || "//" in value) return false
    return value.removePrefix("/").split('/').all { segment ->
        segment.isNotEmpty() && segment != "." && segment != ".." &&
            segment.all { it.isAsciiLetterOrDigit() || it in GATEWAY_PATH_PUNCTUATION }
    }
}

private fun Char.isAsciiLetterOrDigit(): Boolean =
    this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9'

private fun Char.isAsciiHexDigit(): Boolean =
    isAsciiLetterOrDigit() && (isDigit() || lowercaseChar() in 'a'..'f')

private data class GatewayAuthority(val host: String)

private const val HTTPS_SCHEME = "https://"
private const val HTTP_SCHEME = "http://"
private val GATEWAY_PATH_PUNCTUATION = setOf('-', '.', '_', '~')
private val LOOPBACK_HOSTS = setOf("localhost", "127.0.0.1", "[::1]")
