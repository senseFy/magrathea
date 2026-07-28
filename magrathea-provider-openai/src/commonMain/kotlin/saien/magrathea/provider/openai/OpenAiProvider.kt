package saien.magrathea.provider.openai

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import saien.magrathea.core.ProviderCredential
import saien.magrathea.provider.api.HttpHeader
import saien.magrathea.provider.api.HttpMethod
import saien.magrathea.provider.api.HttpRequestSpec
import saien.magrathea.provider.api.HttpResponseSpec
import saien.magrathea.provider.api.HttpStreamFormat
import saien.magrathea.provider.api.HttpStreamFrame
import saien.magrathea.provider.api.HttpTransport
import saien.magrathea.provider.api.OpenAiApi
import saien.magrathea.provider.api.OpenAiAuthentication
import saien.magrathea.provider.api.OpenAiTransportConfig
import saien.magrathea.provider.api.OpenAiXSearchToolConfig
import saien.magrathea.provider.api.ProviderAdapter
import saien.magrathea.provider.api.ProviderAuthException
import saien.magrathea.provider.api.ProviderChunk
import saien.magrathea.provider.api.ProviderProtocolException
import saien.magrathea.provider.api.ProviderRequest
import saien.magrathea.provider.api.ReferenceProviderInputCapabilities
import saien.magrathea.provider.api.createDefaultHttpTransport
import saien.magrathea.provider.api.requireSuccessful
import saien.magrathea.provider.api.toHttpTimeoutConfig

/** OpenAI-family adapter for Responses and compatible Chat Completions services. */
class OpenAiProviderAdapter(
    private val transport: HttpTransport = createDefaultHttpTransport(),
    sourceJson: Json = Json,
) : ProviderAdapter {
    override val key: String = "openai"
    override val inputCapabilities = ReferenceProviderInputCapabilities.openAiResponses

    private val json = Json(sourceJson) {
        encodeDefaults = false
        ignoreUnknownKeys = false
    }

    override suspend fun generate(request: ProviderRequest): Flow<ProviderChunk> {
        val credential = requireCredential(request)
        val config = request.openAiTransportConfig()
        val payload = when (config.api) {
            OpenAiApi.RESPONSES -> OpenAiResponsesRequestBuilder(key, json).build(request)
            OpenAiApi.CHAT_COMPLETIONS -> OpenAiChatCompletionsRequestBuilder(json).build(request)
        }
        val body = json.encodeToString(JsonObject.serializer(), payload)
        val endpoint = resolveEndpoint(request, credential, config.api)
        val httpRequest = HttpRequestSpec(
            method = HttpMethod.POST,
            url = endpoint,
            headers = buildHeaders(request, credential, config.authentication, request.model.supportsStreaming),
            body = body,
            timeouts = request.timeouts.toHttpTimeoutConfig(),
        )
        return if (request.model.supportsStreaming) {
            streamResponse(request, httpRequest, config)
        } else {
            executeResponse(request, httpRequest, config)
        }
    }

    override fun close() {
        transport.close()
    }

    private fun resolveEndpoint(
        request: ProviderRequest,
        credential: ProviderCredential,
        api: OpenAiApi,
    ): String = request.endpoint ?: credential.endpoint ?: when (api) {
        OpenAiApi.RESPONSES -> DEFAULT_RESPONSES_ENDPOINT
        OpenAiApi.CHAT_COMPLETIONS -> DEFAULT_CHAT_COMPLETIONS_ENDPOINT
    }

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
    ): Flow<ProviderChunk> = channelFlow {
        val response = transport.execute(httpRequest).requireSuccessful()
        send(
            when (config.api) {
                OpenAiApi.RESPONSES -> OpenAiResponsesCodec(
                    providerKey = key,
                    model = request.model.model,
                    json = json,
                    allowServerManagedCustomToolCalls = config.hasXSearch(),
                )
                    .decodeNonStreaming(response.body)
                OpenAiApi.CHAT_COMPLETIONS -> OpenAiChatCompletionsCodec(key, request.model.model)
                    .decodeNonStreaming(response.body)
            },
        )
    }

    private fun streamResponse(
        request: ProviderRequest,
        httpRequest: HttpRequestSpec,
        config: OpenAiTransportConfig,
    ): Flow<ProviderChunk> = channelFlow {
        val responsesCodec = OpenAiResponsesCodec(
            providerKey = key,
            model = request.model.model,
            json = json,
            allowServerManagedCustomToolCalls = config.hasXSearch(),
        ).takeIf { config.api == OpenAiApi.RESPONSES }
        val chatCodec = OpenAiChatCompletionsCodec(key, request.model.model)
            .takeIf { config.api == OpenAiApi.CHAT_COMPLETIONS }
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
                    val chunk = responsesCodec?.decodeServerSentEvent(frame.event, frame.data)
                        ?: chatCodec?.decodeServerSentEvent(frame.event, frame.data)
                    chunk?.let { send(it) }
                }
                is HttpStreamFrame.RetryHint -> Unit
                is HttpStreamFrame.JsonLine -> throw ProviderProtocolException("OpenAI stream must use SSE framing")
                HttpStreamFrame.Completed -> {
                    responsesCodec?.finish()
                    chatCodec?.finish()
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

private const val DEFAULT_RESPONSES_ENDPOINT = "https://api.openai.com/v1/responses"
private const val DEFAULT_CHAT_COMPLETIONS_ENDPOINT = "https://api.openai.com/v1/chat/completions"

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
