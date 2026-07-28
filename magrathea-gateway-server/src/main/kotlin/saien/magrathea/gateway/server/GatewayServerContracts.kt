package saien.magrathea.gateway.server

import saien.magrathea.core.AgentMessage
import saien.magrathea.core.ModelDescriptor
import saien.magrathea.core.ProviderCredential
import saien.magrathea.gateway.protocol.GatewayAttachmentReference
import saien.magrathea.gateway.protocol.GatewayCreateStreamRequest
import saien.magrathea.gateway.protocol.GatewayModelReference
import saien.magrathea.gateway.protocol.GatewayUsage

data class GatewayPrincipal(
    val subject: String,
    val tenantId: String,
) {
    init {
        require(subject.isNotBlank()) { "Gateway principal subject must not be blank" }
        require(tenantId.isNotBlank()) { "Gateway principal tenant must not be blank" }
    }
}

data class GatewayAuthenticationInput(
    val authorization: String?,
    val cookie: String?,
    val csrfToken: String?,
) {
    override fun toString(): String =
        "GatewayAuthenticationInput(authorization=${authorization != null}, cookie=${cookie != null}, csrfToken=${csrfToken != null})"
}

fun interface GatewayAuthenticator {
    suspend fun authenticate(input: GatewayAuthenticationInput): GatewayPrincipal?
}

enum class GatewayOperation {
    CREATE_STREAM,
    READ_STREAM,
    CANCEL_STREAM,
}

fun interface GatewayAuthorizer {
    suspend fun authorize(
        principal: GatewayPrincipal,
        operation: GatewayOperation,
        request: GatewayCreateStreamRequest?,
    ): Boolean
}

fun interface GatewayOriginPolicy {
    suspend fun isAllowed(origin: String?, operation: GatewayOperation?): Boolean
}

data class GatewayLimitDecision(
    val allowed: Boolean,
    val retryAfterMillis: Long? = null,
) {
    init {
        require(retryAfterMillis == null || retryAfterMillis >= 0)
    }
}

fun interface GatewayRateLimiter {
    suspend fun check(principal: GatewayPrincipal, operation: GatewayOperation): GatewayLimitDecision
}

sealed interface GatewayQuotaDecision {
    data class Granted(val reservation: GatewayQuotaReservation) : GatewayQuotaDecision
    data class Denied(val retryAfterMillis: Long? = null) : GatewayQuotaDecision {
        init {
            require(retryAfterMillis == null || retryAfterMillis >= 0)
        }
    }
}

interface GatewayQuotaReservation {
    suspend fun complete(usage: GatewayUsage?)
    suspend fun cancel()
    suspend fun fail()
}

fun interface GatewayQuotaManager {
    suspend fun reserve(
        principal: GatewayPrincipal,
        request: GatewayCreateStreamRequest,
    ): GatewayQuotaDecision
}

fun interface GatewayAttachmentResolver {
    suspend fun resolve(
        principal: GatewayPrincipal,
        references: List<GatewayAttachmentReference>,
        messages: List<AgentMessage>,
    ): List<AgentMessage>
}

fun interface GatewayProviderCredentialResolver {
    suspend fun resolve(principal: GatewayPrincipal, model: ModelDescriptor): ProviderCredential
}

/** Resolves the untrusted wire reference to a server-owned model capability descriptor. */
fun interface GatewayModelResolver {
    suspend fun resolve(principal: GatewayPrincipal, reference: GatewayModelReference): ModelDescriptor
}

enum class GatewayAuditAction {
    STREAM_CREATED,
    STREAM_REUSED,
    STREAM_COMPLETED,
    STREAM_FAILED,
    STREAM_CANCELLED,
}

data class GatewayAuditEvent(
    val action: GatewayAuditAction,
    val subject: String,
    val tenantId: String,
    val requestId: String,
    val streamId: String,
    val sessionId: String,
    val provider: String,
    val model: String,
    val usage: GatewayUsage? = null,
    val failureCode: String? = null,
)

fun interface GatewayAuditSink {
    suspend fun record(event: GatewayAuditEvent)
}

class GatewayAuthenticationException : RuntimeException("Gateway authentication required")
class GatewayAuthorizationException : RuntimeException("Gateway operation is not authorized")
class GatewayOriginException : RuntimeException("Gateway origin is not allowed")
class GatewayRateLimitException(val retryAfterMillis: Long?) : RuntimeException("Gateway rate limit exceeded")
class GatewayQuotaException(val retryAfterMillis: Long?) : RuntimeException("Gateway quota exceeded")
class GatewayIdempotencyConflictException : RuntimeException("Idempotency key was reused with a different request")
class GatewayStreamNotFoundException : RuntimeException("Gateway stream not found")
class GatewayReplayWindowException : RuntimeException("Gateway replay cursor is outside the retained window")
class GatewayCursorException : RuntimeException("Gateway replay cursor is invalid")

object RejectingGatewayAttachmentResolver : GatewayAttachmentResolver {
    override suspend fun resolve(
        principal: GatewayPrincipal,
        references: List<GatewayAttachmentReference>,
        messages: List<AgentMessage>,
    ): List<AgentMessage> {
        require(references.isEmpty()) { "No Gateway attachment resolver is configured" }
        return messages
    }
}
