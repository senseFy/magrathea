package saien.magrathea.tooling.consumer

import saien.magrathea.core.AgentSessionId
import saien.magrathea.core.IdGenerator
import saien.magrathea.provider.api.HttpRequestSpec
import saien.magrathea.provider.api.HttpResponseSpec
import saien.magrathea.provider.api.HttpStreamFormat
import saien.magrathea.provider.api.HttpStreamFrame
import saien.magrathea.provider.api.HttpTransport
import saien.magrathea.provider.gemini.GeminiProviderAdapter
import saien.magrathea.provider.openai.OpenAiProviderAdapter
import saien.magrathea.provider.anthropic.AnthropicProviderAdapter
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import saien.magrathea.credentials.CredentialStoreFailure
import saien.magrathea.core.ToolOrigin
import saien.magrathea.policy.ToolApprovalMode
import saien.magrathea.policy.ToolPolicy
import saien.magrathea.policy.ToolRiskLevel
import saien.magrathea.storage.room.StoredRecordKind
import saien.magrathea.chatbot.ChatbotStatus
import saien.magrathea.gateway.protocol.GatewayModelReference
import saien.magrathea.provider.gateway.GatewayProviderConfig
import saien.magrathea.runtime.search.WebSearchBackend
import saien.magrathea.runtime.search.WebSearchBackendResponse
import saien.magrathea.runtime.search.WebSearchPolicy
import saien.magrathea.runtime.search.WebSearchTool

fun publishedCoreSessionId(value: String): AgentSessionId =
    AgentSessionId.create(IdGenerator { value })

fun publishedGeminiProviderKey(): String {
    val provider = GeminiProviderAdapter(
        transport = NoopPublishedTransport,
    )
    return provider.key.also { provider.close() }
}

fun publishedProviderKeys(): String {
    val providers = listOf(
        OpenAiProviderAdapter(transport = NoopPublishedTransport),
        AnthropicProviderAdapter(transport = NoopPublishedTransport),
    )
    return providers.joinToString(",") { it.key }
}

fun publishedPlatformAdapterFingerprint(): String =
    "${StoredRecordKind.SESSION.name}:${CredentialStoreFailure.NOT_FOUND.name}:${ChatbotStatus.IDLE.name}"

fun publishedGatewayFingerprint(): String {
    val model = GatewayModelReference(provider = "gateway", model = "consumer-model")
    val config = GatewayProviderConfig("https://gateway.example/consumer/")
    return "${model.provider}:${model.model}:${config.normalizedBaseUrl}:${ChatbotStatus.IDLE.name}"
}

fun publishedWebSearchFingerprint(): String {
    val policy = WebSearchPolicy(
        maxSearchCallsPerRun = 2,
        maxResultsPerQuery = 7,
        maxSourcesInContext = 5,
    )
    val tool = WebSearchTool(
        backend = WebSearchBackend { WebSearchBackendResponse(emptyList()) },
        policy = policy,
    )
    return "${tool.definition.name}:${tool.definition.maxCallsPerTurn}:${policy.maxResultsPerQuery}:${policy.maxSourcesInContext}"
}

fun publishedExtensionFingerprint(): String {
    val identity = ToolOrigin(
        sourceId = "consumer",
        sourceLabel = "Published consumer",
        toolId = "search",
        toolLabel = "Search",
    )
    val policy = ToolPolicy(
        toolName = identity.toolId,
        riskLevel = ToolRiskLevel.LOW,
        approvalMode = ToolApprovalMode.ALLOW,
    )
    return "${identity.sourceId}:${policy.toolName}:${policy.riskLevel.name}:${policy.approvalMode.name}"
}

fun publishedAppleLinkFingerprint(): String = listOf(
    publishedCoreSessionId("apple-link").value,
    publishedGeminiProviderKey(),
    publishedProviderKeys(),
    publishedPlatformAdapterFingerprint(),
    publishedGatewayFingerprint(),
    publishedWebSearchFingerprint(),
    publishedExtensionFingerprint(),
).joinToString("|")

private object NoopPublishedTransport : HttpTransport {
    override suspend fun execute(request: HttpRequestSpec): HttpResponseSpec = error("Not used by publication consumer")

    override fun stream(request: HttpRequestSpec, format: HttpStreamFormat): Flow<HttpStreamFrame> = emptyFlow()

    override fun close() = Unit
}
