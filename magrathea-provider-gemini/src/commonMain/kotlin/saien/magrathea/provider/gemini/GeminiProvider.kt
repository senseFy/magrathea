package saien.magrathea.provider.gemini

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import saien.magrathea.core.ProviderCredential
import saien.magrathea.provider.api.GeminiTransportConfig
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
import saien.magrathea.provider.api.ProviderStreamInterruptedException
import saien.magrathea.provider.api.ProviderTransportConfig
import saien.magrathea.provider.api.ReferenceProviderInputCapabilities
import saien.magrathea.provider.api.createDefaultHttpTransport
import saien.magrathea.provider.api.requireSuccessful
import saien.magrathea.provider.api.toHttpTimeoutConfig

/** Gemini Interactions API v1 adapter using client-managed history and canonical events. */
class GeminiProviderAdapter(
    private val transport: HttpTransport = createDefaultHttpTransport(),
    sourceJson: Json = Json,
) : ProviderAdapter {
    override val key: String = "gemini"
    override val optionsFamily: String = "gemini"

    override fun inputCapabilities(config: ProviderTransportConfig?) = when (config) {
        null, is GeminiTransportConfig -> ReferenceProviderInputCapabilities.geminiInteractions
        else -> throw IllegalArgumentException("Gemini provider received options for another provider family")
    }

    private val json = Json(sourceJson) {
        encodeDefaults = false
        ignoreUnknownKeys = false
    }
    private val requestBuilder = GeminiInteractionsRequestBuilder(json)

    override suspend fun generate(request: ProviderRequest): Flow<ProviderChunk> {
        val credential = requireCredential(request)
        val payload = requestBuilder.build(request)
        val body = json.encodeToString(JsonObject.serializer(), payload)
        val endpoint = request.endpoint ?: credential.endpoint ?: DEFAULT_INTERACTIONS_ENDPOINT
        val headers = buildHeaders(credential.headers + request.headers, credential.value, request.model.supportsStreaming)
        val httpRequest = HttpRequestSpec(
            method = HttpMethod.POST,
            url = endpoint,
            headers = headers,
            body = body,
            timeouts = request.timeouts.toHttpTimeoutConfig(),
        )

        return if (request.model.supportsStreaming) {
            streamInteraction(request, httpRequest)
        } else {
            executeInteraction(request, httpRequest)
        }
    }

    override fun close() {
        transport.close()
    }

    private fun executeInteraction(
        request: ProviderRequest,
        httpRequest: HttpRequestSpec,
    ): Flow<ProviderChunk> = channelFlow {
        val response = transport.execute(httpRequest).requireSuccessful()
        send(GeminiInteractionsCodec(request.model.model, json).decodeNonStreaming(response.body))
    }

    private fun streamInteraction(
        request: ProviderRequest,
        httpRequest: HttpRequestSpec,
    ): Flow<ProviderChunk> = channelFlow {
        val codec = GeminiInteractionsCodec(request.model.model, json)
        var transportCompleted = false
        transport.stream(httpRequest, HttpStreamFormat.SERVER_SENT_EVENTS).collect { frame ->
            if (transportCompleted) {
                throw ProviderProtocolException("Gemini transport emitted a frame after completion")
            }
            when (frame) {
                is HttpStreamFrame.ResponseStarted -> HttpResponseSpec(
                    statusCode = frame.statusCode,
                    headers = frame.headers,
                    body = "",
                ).requireSuccessful()
                is HttpStreamFrame.ServerSentEvent -> codec.decodeServerSentEvent(frame.event, frame.data)?.let { send(it) }
                is HttpStreamFrame.RetryHint -> Unit
                is HttpStreamFrame.JsonLine -> throw ProviderProtocolException("Gemini Interactions stream must use SSE framing")
                HttpStreamFrame.Completed -> {
                    try {
                        codec.finish()
                    } catch (failure: ProviderProtocolException) {
                        throw ProviderStreamInterruptedException(failure)
                    }
                    transportCompleted = true
                }
            }
        }
        if (!transportCompleted) {
            throw ProviderProtocolException("Gemini transport ended without a completion frame")
        }
    }

    private fun buildHeaders(
        supplied: Map<String, String>,
        apiKey: String,
        streaming: Boolean,
    ): List<HttpHeader> {
        val reserved = setOf("authorization", "x-goog-api-key", "content-type", "accept")
        return buildList {
            supplied.forEach { (name, value) ->
                if (name.lowercase() !in reserved) add(HttpHeader(name, value))
            }
            add(HttpHeader("Content-Type", "application/json"))
            add(HttpHeader("Accept", if (streaming) "text/event-stream" else "application/json"))
            add(HttpHeader("x-goog-api-key", apiKey))
        }
    }

    private fun requireCredential(request: ProviderRequest): ProviderCredential {
        request.credentialRef?.let { reference ->
            if (reference.provider != key) {
                throw ProviderAuthException("Gemini cannot use a credential for another provider")
            }
        }
        return request.credential ?: throw ProviderAuthException("Gemini credential is required")
    }

}

private const val DEFAULT_INTERACTIONS_ENDPOINT = "https://generativelanguage.googleapis.com/v1/interactions"
