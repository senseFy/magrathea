package saien.magrathea.provider.anthropic

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import saien.magrathea.core.ProviderCredential
import saien.magrathea.provider.api.AnthropicAuthentication
import saien.magrathea.provider.api.AnthropicTransportConfig
import saien.magrathea.provider.api.HttpHeader
import saien.magrathea.provider.api.HttpMethod
import saien.magrathea.provider.api.HttpRequestSpec
import saien.magrathea.provider.api.HttpResponseSpec
import saien.magrathea.provider.api.HttpStreamFormat
import saien.magrathea.provider.api.HttpStreamFrame
import saien.magrathea.provider.api.HttpTransport
import saien.magrathea.provider.api.ProviderAdapter
import saien.magrathea.provider.api.ProviderAuthException
import saien.magrathea.provider.api.ProviderChunk
import saien.magrathea.provider.api.ProviderProtocolException
import saien.magrathea.provider.api.ProviderRequest
import saien.magrathea.provider.api.ProviderTransportConfig
import saien.magrathea.provider.api.ReferenceProviderInputCapabilities
import saien.magrathea.provider.api.createDefaultHttpTransport
import saien.magrathea.provider.api.requireSuccessful
import saien.magrathea.provider.api.toHttpTimeoutConfig

/** Anthropic Messages API adapter that maps content blocks into canonical events. */
class AnthropicProviderAdapter(
    private val transport: HttpTransport = createDefaultHttpTransport(),
    sourceJson: Json = Json,
) : ProviderAdapter {
    override val key: String = "anthropic"
    override val optionsFamily: String = "anthropic"

    override fun inputCapabilities(config: ProviderTransportConfig?) = when (config) {
        null, is AnthropicTransportConfig -> ReferenceProviderInputCapabilities.anthropicMessages
        else -> throw IllegalArgumentException("Anthropic provider received options for another provider family")
    }

    private val json = Json(sourceJson) {
        encodeDefaults = false
        ignoreUnknownKeys = false
    }

    override suspend fun generate(request: ProviderRequest): Flow<ProviderChunk> {
        val credential = requireCredential(request)
        val config = request.anthropicTransportConfig()
        val payload = AnthropicRequestBuilder(key, json).build(request)
        val body = json.encodeToString(JsonObject.serializer(), payload)
        val httpRequest = HttpRequestSpec(
            method = HttpMethod.POST,
            url = request.endpoint ?: credential.endpoint ?: DEFAULT_MESSAGES_ENDPOINT,
            headers = buildHeaders(request, credential, config.authentication, request.model.supportsStreaming),
            body = body,
            timeouts = request.timeouts.toHttpTimeoutConfig(),
        )
        return if (request.model.supportsStreaming) streamMessage(request, httpRequest)
        else executeMessage(request, httpRequest)
    }

    override fun close() {
        transport.close()
    }

    private fun executeMessage(request: ProviderRequest, httpRequest: HttpRequestSpec): Flow<ProviderChunk> = channelFlow {
        val response = transport.execute(httpRequest).requireSuccessful()
        send(AnthropicMessagesCodec(key, request.model.model, json).decodeNonStreaming(response.body))
    }

    private fun streamMessage(request: ProviderRequest, httpRequest: HttpRequestSpec): Flow<ProviderChunk> = channelFlow {
        val codec = AnthropicMessagesCodec(key, request.model.model, json)
        var transportCompleted = false
        transport.stream(httpRequest, HttpStreamFormat.SERVER_SENT_EVENTS).collect { frame ->
            if (transportCompleted) throw ProviderProtocolException("Anthropic transport emitted a frame after completion")
            when (frame) {
                is HttpStreamFrame.ResponseStarted -> HttpResponseSpec(
                    statusCode = frame.statusCode,
                    headers = frame.headers,
                    body = "",
                ).requireSuccessful()
                is HttpStreamFrame.ServerSentEvent -> codec.decodeServerSentEvent(frame.event, frame.data)?.let { send(it) }
                is HttpStreamFrame.RetryHint -> Unit
                is HttpStreamFrame.JsonLine -> throw ProviderProtocolException("Anthropic Messages stream must use SSE framing")
                HttpStreamFrame.Completed -> {
                    codec.finish()
                    transportCompleted = true
                }
            }
        }
        if (!transportCompleted) throw ProviderProtocolException("Anthropic transport ended without a completion frame")
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
        authentication: AnthropicAuthentication,
        streaming: Boolean,
    ): List<HttpHeader> = buildList {
        val supplied = credential.headers + request.headers
        supplied.forEach { (name, value) ->
            if (name.lowercase() !in RESERVED_HEADERS) add(HttpHeader(name, value))
        }
        add(HttpHeader("Content-Type", "application/json"))
        add(HttpHeader("Accept", if (streaming) "text/event-stream" else "application/json"))
        when (authentication) {
            AnthropicAuthentication.X_API_KEY -> add(HttpHeader("x-api-key", credential.value))
            AnthropicAuthentication.BEARER -> add(HttpHeader("Authorization", "Bearer ${credential.value}"))
        }
        add(HttpHeader("anthropic-version", "2023-06-01"))
    }
}

private const val DEFAULT_MESSAGES_ENDPOINT = "https://api.anthropic.com/v1/messages"

private val RESERVED_HEADERS = setOf(
    "content-type",
    "accept",
    "x-api-key",
    "authorization",
    "anthropic-version",
    "anthropic-beta",
    "anthropic-thinking-mode",
    "anthropic-thinking-budget-tokens",
    "anthropic-thinking-display",
    "anthropic-effort",
)
