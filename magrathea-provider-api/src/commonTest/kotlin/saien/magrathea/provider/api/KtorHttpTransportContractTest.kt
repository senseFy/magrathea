package saien.magrathea.provider.api

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondOk
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield

class KtorHttpTransportContractTest {
    @Test
    fun execute_preservesRequestAndResponseWithoutLeakingKtorTypes() = runTest {
        val transport = KtorHttpTransport(
            client = HttpClient(
                MockEngine { request ->
                    assertEquals("https://example.invalid/v1/interactions", request.url.toString())
                    assertEquals("request-id", request.headers["X-Request-Id"])
                    respond(
                        content = "{\"ok\":true}",
                        status = HttpStatusCode.OK,
                        headers = headersOf("X-Response-Id", "response-id"),
                    )
                },
            ) { expectSuccess = false },
        )

        val response = transport.execute(
            HttpRequestSpec(
                method = HttpMethod.POST,
                url = "https://example.invalid/v1/interactions",
                headers = listOf(HttpHeader("X-Request-Id", "request-id")),
                body = "{}",
            ),
        )

        assertEquals(200, response.statusCode)
        assertEquals("{\"ok\":true}", response.body)
        assertEquals("response-id", response.headers.single { it.name == "X-Response-Id" }.value)
        transport.close()
    }

    @Test
    fun stream_appliesJsonLinesAndSseFraming() = runTest {
        val jsonLines = KtorHttpTransport(
            HttpClient(
                MockEngine {
                    respond(
                        content = "{\"one\":1}\n\n{\"two\":2}\n",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/x-ndjson"),
                    )
                },
            ) { expectSuccess = false },
        )
        val jsonFrames = jsonLines.stream(request(), HttpStreamFormat.JSON_LINES).toList()
        assertEquals(
            listOf(HttpStreamFrame.JsonLine("{\"one\":1}"), HttpStreamFrame.JsonLine("{\"two\":2}")),
            jsonFrames.filterIsInstance<HttpStreamFrame.JsonLine>(),
        )
        assertIs<HttpStreamFrame.ResponseStarted>(jsonFrames.first())
        assertEquals(HttpStreamFrame.Completed, jsonFrames.last())
        jsonLines.close()

        val sse = KtorHttpTransport(
            HttpClient(
                MockEngine {
                    respond(
                        content = "event: step.delta\ndata: {\"delta\":\"hi\"}\n\nevent: interaction.completed\ndata: {}\n\n",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "text/event-stream"),
                    )
                },
            ) { expectSuccess = false },
        )
        val sseFrames = sse.stream(request(), HttpStreamFormat.SERVER_SENT_EVENTS).toList()
        assertEquals(
            listOf("step.delta", "interaction.completed"),
            sseFrames.filterIsInstance<HttpStreamFrame.ServerSentEvent>().map { it.event },
        )
        assertEquals(HttpStreamFrame.Completed, sseFrames.last())
        sse.close()
    }

    @Test
    fun executeAndStream_shareBodyFreeHttpErrorMapping() = runTest {
        val canary = "ktor-error-secret-canary"
        fun failingTransport() = KtorHttpTransport(
            client = HttpClient(
                MockEngine {
                    respond(
                        content = "{\"token\":\"$canary\",\"message\":\"unavailable\"}",
                        status = HttpStatusCode.ServiceUnavailable,
                        headers = headersOf("Retry-After", "3"),
                    )
                },
            ) { expectSuccess = false },
        )

        val executeFailure = assertFailsWith<ProviderServerException> {
            failingTransport().execute(request())
        }
        assertEquals(503, executeFailure.statusCode)
        assertEquals(3_000L, executeFailure.retryAfterMillis)
        assertTrue(canary !in executeFailure.message.orEmpty())

        val streamFailure = assertFailsWith<ProviderServerException> {
            failingTransport().stream(request(), HttpStreamFormat.SERVER_SENT_EVENTS).toList()
        }
        assertEquals(503, streamFailure.statusCode)
        assertEquals("HTTP 503", streamFailure.message)
    }

    @Test
    fun cancellation_isPropagatedInsteadOfMappedToNetworkFailure() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val transport = KtorHttpTransport(
            HttpClient(
                MockEngine {
                    entered.complete(Unit)
                    release.await()
                    respondOk()
                },
            ) { expectSuccess = false },
        )
        val operation = async { transport.execute(request()) }
        entered.await()

        operation.cancel(CancellationException("contract cancellation"))
        val failure = assertFailsWith<CancellationException> { operation.await() }

        assertEquals("contract cancellation", failure.message)
        release.complete(Unit)
        transport.close()
    }

    @Test
    fun transportTimeoutsKeepTheirPhaseInsteadOfBecomingGenericNetworkFailures() = runTest {
        suspend fun phaseFor(failure: Throwable): ProviderTimeoutPhase {
            val transport = KtorHttpTransport(
                HttpClient(MockEngine { throw failure }) { expectSuccess = false },
            )
            val mapped = assertFailsWith<ProviderTimeoutException> {
                transport.execute(request())
            }
            transport.close()
            return mapped.phase
        }

        assertEquals(ProviderTimeoutPhase.CONNECT, phaseFor(ConnectTimeoutException("connect")))
        assertEquals(ProviderTimeoutPhase.STREAM_IDLE, phaseFor(SocketTimeoutException("socket")))
    }

    @Test
    fun networkDisconnectIsMappedAndSuccessfulBodiesAreBounded() = runTest {
        val disconnected = KtorHttpTransport(
            HttpClient(
                MockEngine { throw IllegalStateException("connection reset") },
            ) { expectSuccess = false },
        )
        val networkFailure = assertFailsWith<ProviderNetworkException> {
            disconnected.execute(request())
        }
        assertIs<IllegalStateException>(networkFailure.cause)
        disconnected.close()

        val oversized = KtorHttpTransport(
            client = HttpClient(
                MockEngine { respondOk("12345") },
            ) { expectSuccess = false },
            limits = HttpTransportLimits(maxResponseBodyBytes = 4),
        )
        assertFailsWith<ProviderProtocolException> {
            oversized.execute(request())
        }
        oversized.close()
    }

    @Test
    fun slowConsumerBackpressuresTheNextFrameInsteadOfBufferingAhead() = runTest {
        val consumerEntered = CompletableDeferred<Unit>()
        val releaseConsumer = CompletableDeferred<Unit>()
        val secondEmissionAttempted = CompletableDeferred<Unit>()
        val secondEmissionCompleted = CompletableDeferred<Unit>()
        val source = flow {
            emit(HttpStreamFrame.ResponseStarted(200, emptyList()))
            secondEmissionAttempted.complete(Unit)
            emit(HttpStreamFrame.JsonLine("next"))
            secondEmissionCompleted.complete(Unit)
        }

        val collection = launch {
            source.withRendezvousBackpressure().collect { frame ->
                if (frame is HttpStreamFrame.ResponseStarted) {
                    consumerEntered.complete(Unit)
                    releaseConsumer.await()
                }
            }
        }
        consumerEntered.await()
        secondEmissionAttempted.await()
        yield()

        assertFalse(secondEmissionCompleted.isCompleted)
        releaseConsumer.complete(Unit)
        collection.join()
        assertTrue(secondEmissionCompleted.isCompleted)
    }

    private fun request(): HttpRequestSpec = HttpRequestSpec(
        method = HttpMethod.POST,
        url = "https://example.invalid/v1/interactions",
        headers = listOf(HttpHeader(HttpHeaders.ContentType, "application/json")),
        body = "{}",
    )
}
