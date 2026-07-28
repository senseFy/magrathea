package saien.magrathea.provider.api

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.timeout
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.prepareRequest
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpMethod as KtorMethod
import io.ktor.utils.io.charsets.TooLongLineException
import io.ktor.utils.io.readRemaining
import io.ktor.utils.io.readUTF8Line
import kotlin.math.min
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.io.readByteArray

data class DefaultHttpTransportConfig(
    val requestTimeoutMillis: Long = 600_000,
    val connectTimeoutMillis: Long = 15_000,
    val socketTimeoutMillis: Long = 90_000,
    val followRedirects: Boolean = false,
    val limits: HttpTransportLimits = HttpTransportLimits(),
) {
    init {
        require(requestTimeoutMillis > 0) { "requestTimeoutMillis must be positive" }
        require(connectTimeoutMillis > 0) { "connectTimeoutMillis must be positive" }
        require(socketTimeoutMillis > 0) { "socketTimeoutMillis must be positive" }
    }
}

expect fun createDefaultHttpTransport(
    config: DefaultHttpTransportConfig = DefaultHttpTransportConfig(),
): HttpTransport

internal class KtorHttpTransport(
    private val client: HttpClient,
    private val limits: HttpTransportLimits = HttpTransportLimits(),
) : HttpTransport {
    override suspend fun execute(request: HttpRequestSpec): HttpResponseSpec = mapTransportFailures {
        client.prepareRequest(request.url) {
            apply(request)
        }.execute { response ->
            response.toResponseSpec().requireSuccessful()
        }
    }

    override fun stream(
        request: HttpRequestSpec,
        format: HttpStreamFormat,
    ): Flow<HttpStreamFrame> = channelFlow {
        mapTransportFailures {
            client.prepareRequest(request.url) {
                apply(request)
            }.execute { response ->
                val responseHeaders = response.toHeaders()
                val statusCode = response.status.value
                if (statusCode !in 200..299) {
                    response.toResponseSpec().requireSuccessful()
                }

                send(HttpStreamFrame.ResponseStarted(statusCode, responseHeaders))
                val framer = HttpStreamFramer(format, limits)
                val channel = response.bodyAsChannel()
                while (true) {
                    val line = try {
                        readBoundedLine(channel)
                    } catch (_: TooLongLineException) {
                        throw ProviderProtocolException("HTTP stream line exceeds configured limit")
                    } ?: break
                    framer.accept(line).forEach { send(it) }
                }
                framer.finish().forEach { send(it) }
            }
        }
    }.withRendezvousBackpressure()

    override fun close() {
        client.close()
    }

    private fun HttpRequestBuilder.apply(request: HttpRequestSpec) {
        method = request.method.toKtorMethod()
        request.headers.forEach { header -> headers.append(header.name, header.value) }
        request.timeouts?.let { timeouts ->
            timeout {
                timeouts.requestTimeoutMillis?.let { requestTimeoutMillis = it }
                timeouts.connectTimeoutMillis?.let { connectTimeoutMillis = it }
                timeouts.socketTimeoutMillis?.let { socketTimeoutMillis = it }
            }
        }
        request.body?.let(::setBody)
    }

    private suspend fun HttpResponse.toResponseSpec(): HttpResponseSpec {
        val bodyRead = readBoundedBody()
        if (status.value in 200..299 && bodyRead.truncated) {
            throw ProviderProtocolException("HTTP response body exceeds configured limit")
        }
        val body = if (bodyRead.truncated) bodyRead.value + "…" else bodyRead.value
        return HttpResponseSpec(
            statusCode = status.value,
            headers = toHeaders(),
            body = body,
        )
    }

    private suspend fun HttpResponse.readBoundedBody(): BoundedBody {
        val maxBytes = limits.maxResponseBodyBytes
        val source = bodyAsChannel().readRemaining(maxBytes.toLong() + 1)
        val bytes = source.readByteArray()
        val truncated = bytes.size > maxBytes
        return BoundedBody(
            value = bytes.copyOfRange(0, min(bytes.size, maxBytes)).decodeToString(throwOnInvalidSequence = false),
            truncated = truncated,
        )
    }

    @Suppress("DEPRECATION")
    private suspend fun readBoundedLine(channel: io.ktor.utils.io.ByteReadChannel): String? {
        // Ktor's nullable line reader is the only API that both preserves a final
        // unterminated SSE/JSONL line and enforces a pre-allocation length bound.
        return channel.readUTF8Line(max = limits.maxStreamLineChars + 1)
    }

    private fun HttpResponse.toHeaders(): List<HttpHeader> {
        return headers.entries().flatMap { (name, values) ->
            values.map { value -> HttpHeader(name, value) }
        }
    }

    private suspend fun <T> mapTransportFailures(block: suspend () -> T): T {
        return try {
            block()
        } catch (failure: ConnectTimeoutException) {
            throw ProviderTimeoutException(ProviderTimeoutPhase.CONNECT, failure)
        } catch (failure: SocketTimeoutException) {
            throw ProviderTimeoutException(ProviderTimeoutPhase.STREAM_IDLE, failure)
        } catch (failure: HttpRequestTimeoutException) {
            throw ProviderTimeoutException(ProviderTimeoutPhase.PROVIDER_CALL, failure)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (providerFailure: ProviderException) {
            throw providerFailure
        } catch (failure: Throwable) {
            throw ProviderNetworkException("HTTP transport request failed", failure)
        }
    }
}

internal fun Flow<HttpStreamFrame>.withRendezvousBackpressure(): Flow<HttpStreamFrame> = buffer(capacity = 0)

private data class BoundedBody(
    val value: String,
    val truncated: Boolean,
)

private fun HttpMethod.toKtorMethod(): KtorMethod = when (this) {
    HttpMethod.GET -> KtorMethod.Get
    HttpMethod.POST -> KtorMethod.Post
    HttpMethod.PUT -> KtorMethod.Put
    HttpMethod.PATCH -> KtorMethod.Patch
    HttpMethod.DELETE -> KtorMethod.Delete
    HttpMethod.HEAD -> KtorMethod.Head
}
