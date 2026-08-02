package saien.magrathea.tooling.gateway

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.options
import io.ktor.server.routing.routing
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import saien.magrathea.core.ModelDescriptor
import saien.magrathea.core.ProviderCredential
import saien.magrathea.core.StopReason
import saien.magrathea.core.text
import saien.magrathea.gateway.server.GatewayAttachmentResolver
import saien.magrathea.gateway.server.GatewayAuditSink
import saien.magrathea.gateway.server.GatewayAuthenticationInput
import saien.magrathea.gateway.server.GatewayAuthenticator
import saien.magrathea.gateway.server.GatewayAuthorizer
import saien.magrathea.gateway.server.GatewayCoordinatorConfig
import saien.magrathea.gateway.server.GatewayHttpDependencies
import saien.magrathea.gateway.server.GatewayLimitDecision
import saien.magrathea.gateway.server.GatewayModelResolver
import saien.magrathea.gateway.server.GatewayOriginPolicy
import saien.magrathea.gateway.server.GatewayPrincipal
import saien.magrathea.gateway.server.GatewayProviderCredentialResolver
import saien.magrathea.gateway.server.GatewayQuotaDecision
import saien.magrathea.gateway.server.GatewayQuotaManager
import saien.magrathea.gateway.server.GatewayQuotaReservation
import saien.magrathea.gateway.server.GatewayRateLimiter
import saien.magrathea.gateway.server.GatewayStreamCoordinator
import saien.magrathea.gateway.server.installMagratheaGateway
import saien.magrathea.provider.api.InMemoryProviderRegistry
import saien.magrathea.provider.api.ProviderAdapter
import saien.magrathea.provider.api.ProviderChunk
import saien.magrathea.provider.api.ProviderEvent
import saien.magrathea.provider.api.ProviderRequest

private const val EXPECTED_AUTHORIZATION = "Bearer e2e-browser-session"
private const val EXPECTED_CSRF = "e2e-csrf"

fun main() {
    val port = System.getenv("MAGRATHEA_GATEWAY_E2E_PORT")?.toIntOrNull() ?: 18_081
    val provider = E2eProvider()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val coordinator = GatewayStreamCoordinator(
        providerRegistry = InMemoryProviderRegistry(listOf(provider)),
        modelResolver = GatewayModelResolver { _, reference ->
            ModelDescriptor(
                provider = reference.provider,
                model = reference.model,
                supportsStreaming = true,
            )
        },
        credentialResolver = GatewayProviderCredentialResolver { _, _ ->
            ProviderCredential("server-only-e2e-provider-secret")
        },
        attachmentResolver = GatewayAttachmentResolver { _, references, messages ->
            require(references.isEmpty())
            messages
        },
        quotaManager = NoopQuotaManager,
        auditSink = GatewayAuditSink { },
        parentScope = scope,
        config = GatewayCoordinatorConfig(
            terminalRetentionMillis = 60_000,
            streamLifetimeMillis = 60_000,
        ),
    )
    val allowedOrigins = setOf(
        "http://localhost:9876",
        "http://127.0.0.1:9876",
        "http://127.0.0.1:19080",
    )
    val server = embeddedServer(Netty, host = "127.0.0.1", port = port) {
        routing {
            get("/health") { call.respondText("ok") }
            options("/e2e/provider-calls") { call.respondE2ePreflight(allowedOrigins) }
            options("/e2e/provider-cancellations") { call.respondE2ePreflight(allowedOrigins) }
            get("/e2e/provider-calls") {
                if (!call.applyE2eCors(allowedOrigins)) return@get
                call.respondText(
                    provider.calls(
                        requestId = call.request.queryParameters["requestId"],
                        sessionId = call.request.queryParameters["sessionId"],
                    ).toString(),
                )
            }
            get("/e2e/provider-cancellations") {
                if (!call.applyE2eCors(allowedOrigins)) return@get
                call.respondText(
                    provider.cancellations(
                        requestId = call.request.queryParameters["requestId"],
                        sessionId = call.request.queryParameters["sessionId"],
                    ).toString(),
                )
            }
        }
        installMagratheaGateway(
            GatewayHttpDependencies(
                coordinator = coordinator,
                authenticator = GatewayAuthenticator(::authenticate),
                authorizer = GatewayAuthorizer { principal, _, request ->
                    principal == E2E_PRINCIPAL && (request == null || request.model.provider == provider.key)
                },
                originPolicy = GatewayOriginPolicy { origin, _ -> origin == null || origin in allowedOrigins },
                rateLimiter = GatewayRateLimiter { _, _ -> GatewayLimitDecision(allowed = true) },
            ),
        )
    }
    Runtime.getRuntime().addShutdownHook(Thread {
        coordinator.close()
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    })
    server.start(wait = true)
}

private suspend fun authenticate(input: GatewayAuthenticationInput): GatewayPrincipal? =
    E2E_PRINCIPAL.takeIf {
        input.authorization == EXPECTED_AUTHORIZATION && input.csrfToken == EXPECTED_CSRF
    }

private val E2E_PRINCIPAL = GatewayPrincipal(subject = "e2e-user", tenantId = "e2e-tenant")

private object NoopQuotaManager : GatewayQuotaManager {
    override suspend fun reserve(
        principal: GatewayPrincipal,
        request: saien.magrathea.gateway.protocol.GatewayCreateStreamRequest,
    ): GatewayQuotaDecision = GatewayQuotaDecision.Granted(
        object : GatewayQuotaReservation {
            override suspend fun complete(usage: saien.magrathea.gateway.protocol.GatewayUsage?) = Unit
            override suspend fun cancel() = Unit
            override suspend fun fail() = Unit
        },
    )
}

private class E2eProvider : ProviderAdapter {
    override val key: String = "gateway-e2e"
    private val callsByRequest = ConcurrentHashMap<String, AtomicInteger>()
    private val callsBySession = ConcurrentHashMap<String, AtomicInteger>()
    private val cancellationsByRequest = ConcurrentHashMap<String, AtomicInteger>()
    private val cancellationsBySession = ConcurrentHashMap<String, AtomicInteger>()

    fun calls(requestId: String?, sessionId: String?): Int =
        metric(callsByRequest, callsBySession, requestId, sessionId)

    fun cancellations(requestId: String?, sessionId: String?): Int =
        metric(cancellationsByRequest, cancellationsBySession, requestId, sessionId)

    override suspend fun generate(request: ProviderRequest): Flow<ProviderChunk> = flow {
        val invocation = requireNotNull(request.invocation)
        val requestId = invocation.requestId
        val sessionId = invocation.sessionId.value
        callsByRequest.computeIfAbsent(requestId) { AtomicInteger() }.incrementAndGet()
        callsBySession.computeIfAbsent(sessionId) { AtomicInteger() }.incrementAndGet()
        val prompt = request.messages.lastOrNull()?.text().orEmpty()
        try {
            emit(ProviderChunk(events = listOf(ProviderEvent.TextStart())))
            emit(ProviderChunk(events = listOf(ProviderEvent.TextDelta("gateway e2e answer"))))
            when {
                prompt.contains("hang", ignoreCase = true) -> awaitCancellation()
                prompt.contains("refresh", ignoreCase = true) -> delay(1_000)
            }
            emit(
                ProviderChunk(
                    events = listOf(
                        ProviderEvent.TextEnd(),
                        ProviderEvent.Completed(stopReason = StopReason.COMPLETED),
                    ),
                ),
            )
        } finally {
            if (prompt.contains("hang", ignoreCase = true)) {
                cancellationsByRequest.computeIfAbsent(requestId) { AtomicInteger() }.incrementAndGet()
                cancellationsBySession.computeIfAbsent(sessionId) { AtomicInteger() }.incrementAndGet()
            }
        }
    }

    private fun metric(
        byRequest: ConcurrentHashMap<String, AtomicInteger>,
        bySession: ConcurrentHashMap<String, AtomicInteger>,
        requestId: String?,
        sessionId: String?,
    ): Int = when {
        !requestId.isNullOrBlank() -> byRequest[requestId]?.get() ?: 0
        !sessionId.isNullOrBlank() -> bySession[sessionId]?.get() ?: 0
        else -> 0
    }
}

private suspend fun ApplicationCall.respondE2ePreflight(allowedOrigins: Set<String>) {
    if (!applyE2eCors(allowedOrigins)) {
        respond(HttpStatusCode.Forbidden)
        return
    }
    response.headers.append(HttpHeaders.AccessControlAllowMethods, "GET,OPTIONS")
    respond(HttpStatusCode.NoContent)
}

private suspend fun ApplicationCall.applyE2eCors(allowedOrigins: Set<String>): Boolean {
    val origin = request.headers[HttpHeaders.Origin] ?: return true
    if (origin !in allowedOrigins) {
        respond(HttpStatusCode.Forbidden)
        return false
    }
    response.headers.append(HttpHeaders.AccessControlAllowOrigin, origin)
    response.headers.append(HttpHeaders.Vary, HttpHeaders.Origin)
    return true
}
