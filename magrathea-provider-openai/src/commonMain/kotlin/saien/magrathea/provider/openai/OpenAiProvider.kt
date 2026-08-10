package saien.magrathea.provider.openai

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import saien.magrathea.core.ProviderCredential
import saien.magrathea.core.ReasoningEffort
import saien.magrathea.core.ReasoningPreference
import saien.magrathea.provider.api.HttpHeader
import saien.magrathea.provider.api.HttpMethod
import saien.magrathea.provider.api.HttpRequestSpec
import saien.magrathea.provider.api.HttpResponseSpec
import saien.magrathea.provider.api.HttpStreamFormat
import saien.magrathea.provider.api.HttpStreamFrame
import saien.magrathea.provider.api.HttpTransport
import saien.magrathea.provider.api.OpenAiAuthentication
import saien.magrathea.provider.api.OpenAiTransportConfig
import saien.magrathea.provider.api.OpenAiWireProtocol
import saien.magrathea.provider.api.OpenAiXSearchToolConfig
import saien.magrathea.provider.api.ProviderAdapter
import saien.magrathea.provider.api.ProviderAuthException
import saien.magrathea.provider.api.ProviderChunk
import saien.magrathea.provider.api.ProviderInputCapabilities
import saien.magrathea.provider.api.ProviderProtocolException
import saien.magrathea.provider.api.ProviderRequest
import saien.magrathea.provider.api.ProviderStreamInterruptedException
import saien.magrathea.provider.api.ProviderTransportConfig
import saien.magrathea.provider.api.ReferenceProviderInputCapabilities
import saien.magrathea.provider.api.createDefaultHttpTransport
import saien.magrathea.provider.api.requireSuccessful
import saien.magrathea.provider.api.toHttpTimeoutConfig

/** OpenAI-family protocol adapter configured by a Provider profile. */
class OpenAiProviderAdapter(
    val profile: OpenAiProviderProfile = OpenAiProviderProfile.openAi(),
    private val transport: HttpTransport = createDefaultHttpTransport(),
    sourceJson: Json = Json,
) : ProviderAdapter {
    override val key: String = profile.providerId
    override val optionsFamily: String = "openai"

    override fun inputCapabilities(config: ProviderTransportConfig?): ProviderInputCapabilities {
        val openAiConfig = config.openAiConfigOrDefault()
        val protocol = resolveProtocol(openAiConfig)
        validateConfig(openAiConfig, protocol)
        return when (protocol) {
            OpenAiWireProtocol.RESPONSES -> ReferenceProviderInputCapabilities.openAiResponses
            OpenAiWireProtocol.CHAT_COMPLETIONS -> ReferenceProviderInputCapabilities.openAiChatCompletions
        }
    }

    private val json = Json(sourceJson) {
        encodeDefaults = false
        ignoreUnknownKeys = false
    }

    override suspend fun generate(request: ProviderRequest): Flow<ProviderChunk> {
        require(request.model.provider == key) {
            "OpenAI Provider profile $key cannot serve model Provider ${request.model.provider}"
        }
        val protocol = resolveProtocol(request.openAiTransportConfig())
        val resolvedRequest = request.withResolvedReasoning(
            protocol = protocol,
            dialect = profile.dialect,
            chatCompletionsReasoningFormat = profile.chatCompletionsReasoningFormat,
            reasoningEffortMapping = profile.reasoningEffortMapping,
            disabledReasoningValue = profile.disabledReasoningValue,
        )
        val credential = requireCredential(resolvedRequest)
        val config = resolvedRequest.openAiTransportConfig()
        validateConfig(config, protocol)
        val payload = when (protocol) {
            OpenAiWireProtocol.RESPONSES -> OpenAiResponsesRequestBuilder(key, json).build(resolvedRequest)
            OpenAiWireProtocol.CHAT_COMPLETIONS -> OpenAiChatCompletionsRequestBuilder(
                json = json,
                reasoningFormat = profile.chatCompletionsReasoningFormat
                    ?: OpenAiChatCompletionsReasoningFormat.REASONING_EFFORT,
            ).build(resolvedRequest)
        }
        val body = json.encodeToString(JsonObject.serializer(), payload)
        val endpoint = resolveEndpoint(resolvedRequest, credential, protocol)
        val httpRequest = HttpRequestSpec(
            method = HttpMethod.POST,
            url = endpoint,
            headers = buildHeaders(
                resolvedRequest,
                credential,
                config.authentication,
                resolvedRequest.model.supportsStreaming,
            ),
            body = body,
            timeouts = resolvedRequest.timeouts.toHttpTimeoutConfig(),
        )
        return if (resolvedRequest.model.supportsStreaming) {
            streamResponse(resolvedRequest, httpRequest, config, protocol)
        } else {
            executeResponse(resolvedRequest, httpRequest, config, protocol)
        }
    }

    override fun close() {
        transport.close()
    }

    private fun resolveEndpoint(
        request: ProviderRequest,
        credential: ProviderCredential,
        protocol: OpenAiWireProtocol,
    ): String = request.endpoint
        ?: credential.endpoint
        ?: profile.defaultEndpoint(protocol)
        ?: throw IllegalArgumentException(
            "OpenAI Provider profile ${profile.providerId} requires an endpoint for $protocol",
        )

    private fun authenticationHeader(
        credential: ProviderCredential,
        authentication: OpenAiAuthentication,
    ): HttpHeader = when (authentication) {
        OpenAiAuthentication.BEARER -> HttpHeader("Authorization", "Bearer ${credential.value}")
        OpenAiAuthentication.API_KEY -> HttpHeader("api-key", credential.value)
    }

    private fun executeResponse(
        request: ProviderRequest,
        httpRequest: HttpRequestSpec,
        config: OpenAiTransportConfig,
        protocol: OpenAiWireProtocol,
    ): Flow<ProviderChunk> = channelFlow {
        val response = transport.execute(httpRequest).requireSuccessful()
        send(
            when (protocol) {
                OpenAiWireProtocol.RESPONSES -> OpenAiResponsesCodec(
                    providerKey = key,
                    model = request.model.model,
                    json = json,
                    dialectPolicy = profile.dialect.responsesPolicy(config.hasXSearch()),
                )
                    .decodeNonStreaming(response.body)
                OpenAiWireProtocol.CHAT_COMPLETIONS -> OpenAiChatCompletionsCodec(key, request.model.model)
                    .decodeNonStreaming(response.body)
            },
        )
    }

    private fun streamResponse(
        request: ProviderRequest,
        httpRequest: HttpRequestSpec,
        config: OpenAiTransportConfig,
        protocol: OpenAiWireProtocol,
    ): Flow<ProviderChunk> = channelFlow {
        val normalizer = OpenAiResponsesDialectNormalizer(profile.dialect, json)
            .takeIf { protocol == OpenAiWireProtocol.RESPONSES }
        val responsesCodec = OpenAiResponsesCodec(
            providerKey = key,
            model = request.model.model,
            json = json,
            dialectPolicy = profile.dialect.responsesPolicy(config.hasXSearch()),
        ).takeIf { protocol == OpenAiWireProtocol.RESPONSES }
        val chatCodec = OpenAiChatCompletionsCodec(key, request.model.model)
            .takeIf { protocol == OpenAiWireProtocol.CHAT_COMPLETIONS }
        var transportCompleted = false
        transport.stream(httpRequest, HttpStreamFormat.SERVER_SENT_EVENTS).collect { frame ->
            if (transportCompleted) throw ProviderProtocolException("OpenAI transport emitted a frame after completion")
            when (frame) {
                is HttpStreamFrame.ResponseStarted -> HttpResponseSpec(
                    statusCode = frame.statusCode,
                    headers = frame.headers,
                    body = "",
                ).requireSuccessful()
                is HttpStreamFrame.ServerSentEvent -> {
                    val normalized = normalizer?.normalize(frame.event, frame.data)
                    val chunk = responsesCodec?.decodeServerSentEvent(
                        normalized?.eventName ?: frame.event,
                        normalized?.payload ?: frame.data,
                    )
                        ?: chatCodec?.decodeServerSentEvent(frame.event, frame.data)
                    chunk?.let { send(it) }
                }
                is HttpStreamFrame.RetryHint -> Unit
                is HttpStreamFrame.JsonLine -> throw ProviderProtocolException("OpenAI stream must use SSE framing")
                HttpStreamFrame.Completed -> {
                    try {
                        responsesCodec?.finish()
                        chatCodec?.finish()
                    } catch (failure: ProviderProtocolException) {
                        throw ProviderStreamInterruptedException(failure)
                    }
                    transportCompleted = true
                }
            }
        }
        if (!transportCompleted) throw ProviderProtocolException("OpenAI transport ended without a completion frame")
    }

    private fun requireCredential(request: ProviderRequest): ProviderCredential {
        request.credentialRef?.let { reference ->
            if (reference.provider != key) throw ProviderAuthException("$key cannot use a credential for another provider")
        }
        return request.credential ?: throw ProviderAuthException("$key credential is required")
    }

    private fun resolveProtocol(config: OpenAiTransportConfig): OpenAiWireProtocol =
        config.protocol ?: profile.defaultProtocol

    private fun validateConfig(
        config: OpenAiTransportConfig,
        protocol: OpenAiWireProtocol,
    ) {
        val hostedToolsSupported =
            protocol == OpenAiWireProtocol.RESPONSES ||
                (config.hostedTools.isEmpty() && config.maxToolTurns == null)
        require(hostedToolsSupported) {
            "OpenAI hosted Tools are supported only by the Responses API"
        }
        if (config.hasXSearch()) {
            require(profile.dialect == OpenAiProtocolDialect.XAI) {
                "OpenAI X Search requires an xAI Provider profile"
            }
        }
    }

    private fun buildHeaders(
        request: ProviderRequest,
        credential: ProviderCredential,
        authentication: OpenAiAuthentication,
        streaming: Boolean,
    ): List<HttpHeader> = buildList {
        val supplied = credential.headers + request.headers
        supplied.forEach { (name, value) ->
            if (name.lowercase() !in RESERVED_HEADERS) add(HttpHeader(name, value))
        }
        add(HttpHeader("Content-Type", "application/json"))
        add(HttpHeader("Accept", if (streaming) "text/event-stream" else "application/json"))
        add(authenticationHeader(credential, authentication))
    }
}

private fun OpenAiTransportConfig.hasXSearch(): Boolean =
    hostedTools.any { tool -> tool is OpenAiXSearchToolConfig }

private fun ProviderTransportConfig?.openAiConfigOrDefault(): OpenAiTransportConfig = when (this) {
    null -> OpenAiTransportConfig()
    is OpenAiTransportConfig -> this
    else -> throw IllegalArgumentException("OpenAI provider received options for another provider family")
}

private fun ProviderRequest.withResolvedReasoning(
    protocol: OpenAiWireProtocol,
    dialect: OpenAiProtocolDialect,
    chatCompletionsReasoningFormat: OpenAiChatCompletionsReasoningFormat?,
    reasoningEffortMapping: Map<ReasoningEffort, String>,
    disabledReasoningValue: String?,
): ProviderRequest {
    if (reasoningPreference == ReasoningPreference.Auto) return this
    require(
        protocol != OpenAiWireProtocol.CHAT_COMPLETIONS ||
            chatCompletionsReasoningFormat != null,
    ) {
        "Provider-neutral reasoning requires an explicit compatible Chat Completions reasoning format"
    }
    val config = typedConfig.openAiConfigOrDefault()
    require(config.reasoningEffort == null) {
        "Provider-neutral reasoning cannot be combined with OpenAI reasoningEffort"
    }
    val effort = when (val preference = reasoningPreference) {
        ReasoningPreference.Auto -> error("Auto reasoning is resolved before Provider mapping")
        ReasoningPreference.Disabled -> {
            require(config.reasoningSummary == null) {
                "Disabled reasoning cannot be combined with an OpenAI reasoning summary"
            }
            val value = requireNotNull(disabledReasoningValue) {
                "The selected OpenAI-family profile does not define how to disable reasoning"
            }
            if (dialect != OpenAiProtocolDialect.COMPATIBLE) {
                require(value == REFERENCE_DISABLED_REASONING_VALUE) {
                    "Disabled reasoning must map to none for the selected OpenAI-family dialect"
                }
            }
            value
        }
        is ReasoningPreference.Effort -> {
            val value = if (dialect == OpenAiProtocolDialect.COMPATIBLE) {
                requireNotNull(reasoningEffortMapping[preference.level]) {
                    "The selected compatible OpenAI-family profile has no mapping for ${preference.level}"
                }
            } else {
                preference.level.referenceReasoningValue()
            }
            value
        }
    }
    return copy(typedConfig = config.copy(reasoningEffort = effort))
}

private const val REFERENCE_DISABLED_REASONING_VALUE = "none"

private fun ReasoningEffort.referenceReasoningValue(): String = when (this) {
    ReasoningEffort.MINIMAL -> "minimal"
    ReasoningEffort.LOW -> "low"
    ReasoningEffort.MEDIUM -> "medium"
    ReasoningEffort.HIGH -> "high"
    ReasoningEffort.XHIGH -> "xhigh"
    ReasoningEffort.MAX -> "max"
}

private val RESERVED_HEADERS = setOf(
    "authorization",
    "api-key",
    "content-type",
    "accept",
    "instructions",
    "reasoning-effort",
    "reasoning-summary",
    "service-tier",
    "prompt-cache-key",
    "prompt-cache-retention",
)
