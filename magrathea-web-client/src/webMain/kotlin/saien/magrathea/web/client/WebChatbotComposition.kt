package saien.magrathea.web.client

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import saien.magrathea.chatbot.ChatbotClient
import saien.magrathea.chatbot.ChatbotException
import saien.magrathea.chatbot.ChatbotFailure
import saien.magrathea.chatbot.DefaultChatbotRequestFactory
import saien.magrathea.chatbot.createChatbotClient
import saien.magrathea.core.AgentEngineConfig
import saien.magrathea.core.ContextManagementConfig
import saien.magrathea.core.RuntimeConfig
import saien.magrathea.core.ProviderConfig
import saien.magrathea.core.ToolApprovalGateway
import saien.magrathea.core.ToolPermissionGateway
import saien.magrathea.core.ToolRegistry
import saien.magrathea.gateway.protocol.GatewayModelReference
import saien.magrathea.provider.api.DefaultHttpTransportConfig
import saien.magrathea.provider.api.HttpTransport
import saien.magrathea.provider.api.ProviderAdapter
import saien.magrathea.provider.api.ProviderChunk
import saien.magrathea.provider.api.ProviderProtocolException
import saien.magrathea.provider.api.ProviderRegistry
import saien.magrathea.provider.api.ProviderRequest
import saien.magrathea.provider.api.createDefaultHttpTransport
import saien.magrathea.provider.gateway.GatewayAttachmentCatalog
import saien.magrathea.provider.gateway.GatewayProviderAdapter
import saien.magrathea.provider.gateway.GatewayProviderConfig
import saien.magrathea.provider.gateway.GatewaySessionHeaders
import saien.magrathea.provider.gateway.GatewaySessionHeadersProvider
import saien.magrathea.runtime.DefaultAgentRunner
import saien.magrathea.runtime.InMemoryToolRegistry
import saien.magrathea.storage.web.MagratheaWebStore
import saien.magrathea.storage.web.MagratheaWebStoreConfiguration
import saien.magrathea.storage.web.WebStoredRecordCorruptionReporter
import saien.magrathea.storage.web.createMagratheaWebStore

/** Browser Chatbot composition shared by all server-authorized session models through a Gateway. */
data class WebChatbotConfiguration(
    val gatewayBaseUrl: String,
    val systemPrompt: String = "",
    val databaseName: String = "magrathea-core",
    val maxTurns: Int = 8,
    val contextManagement: ContextManagementConfig = ContextManagementConfig(),
    val maxTokens: Int? = null,
    val temperature: Double? = null,
    val maxReconnectAttempts: Int = 3,
    val initialReconnectDelayMillis: Long = 250,
    val maxReconnectDelayMillis: Long = 2_000,
) {
    override fun toString(): String =
        "WebChatbotConfiguration(" +
            "gatewayBaseUrl=<configured>, " +
            "systemPromptChars=${systemPrompt.length}, " +
            "databaseNameChars=${databaseName.length}, " +
            "maxTurns=$maxTurns, " +
            "contextManagement=$contextManagement, " +
            "maxTokens=$maxTokens, " +
            "temperature=$temperature, " +
            "maxReconnectAttempts=$maxReconnectAttempts, " +
            "initialReconnectDelayMillis=$initialReconnectDelayMillis, " +
            "maxReconnectDelayMillis=$maxReconnectDelayMillis" +
            ")"
}

/**
 * Builds the browser-only Gateway composition. Authentication is evaluated for each HTTP request
 * and remains in memory; same-origin HttpOnly cookies require no custom headers provider.
 */
@Throws(ChatbotException::class)
fun createWebChatbotClient(
    configuration: WebChatbotConfiguration,
    sessionHeadersProvider: GatewaySessionHeadersProvider = GatewaySessionHeadersProvider {
        GatewaySessionHeaders()
    },
    attachmentCatalog: GatewayAttachmentCatalog? = null,
    toolRegistry: ToolRegistry = InMemoryToolRegistry(),
    approvalGateway: ToolApprovalGateway? = null,
    permissionGateway: ToolPermissionGateway? = null,
    corruptionReporter: WebStoredRecordCorruptionReporter = WebStoredRecordCorruptionReporter { },
): ChatbotClient {
    val validated = configuration.validate()
    val store = try {
        createMagratheaWebStore(
            configuration = MagratheaWebStoreConfiguration(configuration.databaseName),
            corruptionReporter = corruptionReporter,
        )
    } catch (_: IllegalArgumentException) {
        throw ChatbotException(ChatbotFailure.INVALID_ARGUMENT)
    }
    val transport = createDefaultHttpTransport(DefaultHttpTransportConfig())
    return composeWebChatbot(
        configuration = configuration,
        gatewayConfig = validated,
        sessionHeadersProvider = sessionHeadersProvider,
        attachmentCatalog = attachmentCatalog,
        toolRegistry = toolRegistry,
        approvalGateway = approvalGateway,
        permissionGateway = permissionGateway,
        store = store,
        transport = transport,
        sessionDispatcher = Dispatchers.Default,
    ).client
}

internal data class WebChatbotComposition(
    val client: ChatbotClient,
    val store: MagratheaWebStore,
    val provider: GatewayProviderAdapter,
)

internal fun composeWebChatbot(
    configuration: WebChatbotConfiguration,
    gatewayConfig: GatewayProviderConfig = configuration.validate(),
    sessionHeadersProvider: GatewaySessionHeadersProvider,
    attachmentCatalog: GatewayAttachmentCatalog?,
    toolRegistry: ToolRegistry,
    approvalGateway: ToolApprovalGateway?,
    permissionGateway: ToolPermissionGateway?,
    store: MagratheaWebStore,
    transport: HttpTransport,
    sessionDispatcher: CoroutineDispatcher,
): WebChatbotComposition {
    val provider = GatewayProviderAdapter(
        key = "gateway",
        config = gatewayConfig,
        sessionHeadersProvider = sessionHeadersProvider,
        attachmentCatalog = attachmentCatalog,
        reconnectGate = BrowserGatewayReconnectGate,
        transport = transport,
        closeTransport = true,
    )
    val runner = DefaultAgentRunner(
        providerRegistry = GatewayRoutingProviderRegistry(provider),
        toolRegistry = toolRegistry,
        persistence = store.persistence,
        approvalGateway = approvalGateway,
        permissionGateway = permissionGateway,
    )
    val requestFactory = DefaultChatbotRequestFactory(
        systemPrompt = configuration.systemPrompt,
        tools = toolRegistry.definitions(),
        configure = {
            copy(
                engine = AgentEngineConfig(
                    provider = ProviderConfig(
                        temperature = configuration.temperature,
                        maxTokens = configuration.maxTokens,
                    ),
                    runtime = RuntimeConfig(
                        maxTurns = configuration.maxTurns,
                        contextManagement = configuration.contextManagement,
                    ),
                ),
            )
        },
    )
    val client = createChatbotClient(
        runner = runner,
        requestFactory = requestFactory,
        persistence = store.persistence,
        sessionDispatcher = sessionDispatcher,
        closeResources = {
            try {
                provider.close()
            } finally {
                store.close()
            }
        },
    )
    return WebChatbotComposition(client, store, provider)
}

private class GatewayRoutingProviderRegistry(
    private val gateway: GatewayProviderAdapter,
) : ProviderRegistry {
    override fun get(key: String): ProviderAdapter? = key
        .takeIf(String::isNotBlank)
        ?.let { GatewayRoute(it, gateway) }

    override fun all(): List<ProviderAdapter> = listOf(gateway)
}

private class GatewayRoute(
    override val key: String,
    private val gateway: GatewayProviderAdapter,
) : ProviderAdapter {
    override val invocationResumeMode
        get() = gateway.invocationResumeMode

    override suspend fun generate(request: ProviderRequest): Flow<ProviderChunk> {
        if (request.model.provider != key) {
            throw ProviderProtocolException("Gateway route does not match the requested Provider")
        }
        try {
            GatewayModelReference(request.model.provider, request.model.model).validate()
        } catch (failure: IllegalArgumentException) {
            throw ProviderProtocolException("Gateway model reference is invalid", failure)
        }
        return gateway.generate(request)
    }
}

private fun WebChatbotConfiguration.validate(): GatewayProviderConfig {
    if (
        maxTurns <= 0 || (maxTokens != null && maxTokens <= 0) ||
        (temperature != null && (!temperature.isFinite() || temperature < 0.0 || temperature > 2.0))
    ) {
        throw ChatbotException(ChatbotFailure.INVALID_ARGUMENT)
    }
    return try {
        MagratheaWebStoreConfiguration(databaseName)
        GatewayProviderConfig(
            baseUrl = gatewayBaseUrl,
            maxReconnectAttempts = maxReconnectAttempts,
            initialReconnectDelayMillis = initialReconnectDelayMillis,
            maxReconnectDelayMillis = maxReconnectDelayMillis,
        )
    } catch (_: IllegalArgumentException) {
        throw ChatbotException(ChatbotFailure.INVALID_ARGUMENT)
    }
}
