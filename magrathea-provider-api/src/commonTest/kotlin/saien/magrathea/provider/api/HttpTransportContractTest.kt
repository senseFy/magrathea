package saien.magrathea.provider.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import saien.magrathea.core.ModelDescriptor
import saien.magrathea.core.ProviderCredential
import saien.magrathea.core.ProviderTimeoutConfig

class HttpTransportContractTest {
    @Test
    fun providerTimeoutsMapToAllTransportDeadlines() {
        assertEquals(600_000, DefaultHttpTransportConfig().requestTimeoutMillis)
        assertEquals(15_000, DefaultHttpTransportConfig().connectTimeoutMillis)
        assertEquals(90_000, DefaultHttpTransportConfig().socketTimeoutMillis)

        val mapped = ProviderTimeoutConfig(
            connectTimeoutMillis = 11,
            firstEventTimeoutMillis = 22,
            streamIdleTimeoutMillis = 17,
            callTimeoutMillis = 33,
        ).toHttpTimeoutConfig()

        assertEquals(33, mapped.requestTimeoutMillis)
        assertEquals(11, mapped.connectTimeoutMillis)
        assertEquals(22, mapped.socketTimeoutMillis)
        assertFailsWith<IllegalArgumentException> {
            HttpTimeoutConfig(socketTimeoutMillis = 0)
        }
    }

    @Test
    fun jsonLines_ignoreBlankLinesAndCompleteExactlyOnce() {
        val framer = HttpStreamFramer(HttpStreamFormat.JSON_LINES)

        val frames = buildList {
            addAll(framer.accept(""))
            addAll(framer.accept("  "))
            addAll(framer.accept("{\"first\":1}"))
            addAll(framer.accept(" {\"second\":2} "))
            addAll(framer.finish())
        }

        assertEquals(
            listOf(
                HttpStreamFrame.JsonLine("{\"first\":1}"),
                HttpStreamFrame.JsonLine(" {\"second\":2} "),
                HttpStreamFrame.Completed,
            ),
            frames,
        )
        assertFailsWith<IllegalStateException> { framer.finish() }
        assertFailsWith<IllegalStateException> { framer.accept("{}") }
    }

    @Test
    fun sse_preservesNamedMultilineEventsIdRetryAndTrailingEvent() {
        val framer = HttpStreamFramer(HttpStreamFormat.SERVER_SENT_EVENTS)

        val frames = listOf(
            "\uFEFF: keep-alive",
            "retry: 1500",
            "id: interaction-1",
            "event: step.delta",
            "data: {\"delta\":",
            "data: \"hello\"}",
            "",
            "event: interaction.completed",
            "data: {}",
        ).flatMap(framer::accept) + framer.finish()

        assertEquals(
            listOf(
                HttpStreamFrame.RetryHint(1_500),
                HttpStreamFrame.ServerSentEvent(
                    event = "step.delta",
                    data = "{\"delta\":\n\"hello\"}",
                    id = "interaction-1",
                ),
                HttpStreamFrame.ServerSentEvent(
                    event = "interaction.completed",
                    data = "{}",
                    id = "interaction-1",
                ),
                HttpStreamFrame.Completed,
            ),
            frames,
        )
    }

    @Test
    fun sse_ignoresUnknownFieldsAndInvalidIdOrRetry() {
        val framer = HttpStreamFramer(HttpStreamFormat.SERVER_SENT_EVENTS)

        val frames = listOf(
            "retry: -1",
            "retry: soon",
            "id: invalid\u0000id",
            "unknown: ignored",
            "data: payload",
            "",
        ).flatMap(framer::accept) + framer.finish()

        assertEquals(
            listOf(
                HttpStreamFrame.ServerSentEvent(event = null, data = "payload", id = null),
                HttpStreamFrame.Completed,
            ),
            frames,
        )
    }

    @Test
    fun framer_rejectsUnboundedLinesAndEvents() {
        val lineLimited = HttpStreamFramer(
            format = HttpStreamFormat.JSON_LINES,
            limits = HttpTransportLimits(maxStreamLineChars = 4, maxStreamEventChars = 20),
        )
        assertFailsWith<ProviderProtocolException> { lineLimited.accept("12345") }

        val eventLimited = HttpStreamFramer(
            format = HttpStreamFormat.SERVER_SENT_EVENTS,
            limits = HttpTransportLimits(maxStreamLineChars = 20, maxStreamEventChars = 4),
        )
        eventLimited.accept("data: 1234")
        assertFailsWith<ProviderProtocolException> { eventLimited.accept("data: 5") }
    }

    @Test
    fun httpErrors_preserveClassificationStatusAndDeltaRetryAfter() {
        val response = HttpResponseSpec(
            statusCode = 429,
            headers = listOf(HttpHeader("Retry-After", "12")),
            body = "{\"error\":\"slow down\"}",
        )

        val failure = assertFailsWith<ProviderRateLimitException> {
            response.requireSuccessful(nowEpochMillis = 1_000)
        }

        assertEquals(429, failure.statusCode)
        assertEquals(12_000L, failure.retryAfterMillis)
        assertEquals("HTTP 429", failure.message)
    }

    @Test
    fun httpErrors_classifyAuthClientRateLimitAndServerFamilies() {
        fun failure(statusCode: Int): ProviderHttpException = assertFailsWith {
            HttpResponseSpec(statusCode = statusCode, body = "failure").requireSuccessful(nowEpochMillis = 0)
        }

        assertIs<ProviderAuthException>(failure(401))
        assertIs<ProviderAuthException>(failure(403))
        assertIs<ProviderClientException>(failure(400))
        assertIs<ProviderRateLimitException>(failure(429))
        assertIs<ProviderServerException>(failure(500))
    }

    @Test
    fun contextLimitBodies_areClassifiedWithoutExposingProviderPayload() {
        val canary = "request-fragment-must-not-leak"
        val failure = assertFailsWith<ProviderContextLimitException> {
            HttpResponseSpec(
                statusCode = 400,
                body = """
                    {"error":{"code":"context_length_exceeded","message":"maximum context length: $canary"}}
                """.trimIndent(),
            ).requireSuccessful(nowEpochMillis = 0)
        }

        assertEquals(400, failure.statusCode)
        assertEquals("Provider context limit exceeded", failure.message)
        assertFalse(failure.message.orEmpty().contains(canary))
        assertTrue(isProviderContextLimitError("prompt is too long"))
        assertFalse(isProviderContextLimitError("invalid temperature"))
    }

    @Test
    fun retryAfter_supportsHttpDateAndRejectsInvalidValues() {
        val now = 784_111_777_000L // Sun, 06 Nov 1994 08:49:37 GMT

        assertEquals(
            43_000L,
            parseRetryAfterMillis("Sun, 06 Nov 1994 08:50:20 GMT", now),
        )
        assertEquals(0L, parseRetryAfterMillis("Sun, 06 Nov 1994 08:49:00 GMT", now))
        assertNull(parseRetryAfterMillis("not-a-date", now))
        assertNull(parseRetryAfterMillis("-3", now))
    }

    @Test
    fun arbitraryHttpErrorBodyNeverEntersThePublicFailure() {
        val canary = "transport-secret-canary"
        val response = HttpResponseSpec(
            statusCode = 401,
            body = "{\"unclassified\":\"$canary\",\"message\":\"${"x".repeat(200)}\"}",
        )

        val failure = assertFailsWith<ProviderAuthException> {
            response.requireSuccessful(nowEpochMillis = 0)
        }

        assertEquals(401, failure.statusCode)
        assertFalse(failure.message.orEmpty().contains(canary))
        assertEquals("HTTP 401", failure.message)
    }

    @Test
    fun requestAndResponseToString_doNotExposeHeadersQueryOrBody() {
        val canary = "transport-secret-canary"
        val request = HttpRequestSpec(
            method = HttpMethod.POST,
            url = "https://example.invalid/v1/$canary?key=$canary",
            headers = listOf(HttpHeader("Authorization", "Bearer $canary")),
            body = "{\"secret\":\"$canary\"}",
        )
        val response = HttpResponseSpec(statusCode = 200, body = canary)

        assertFalse(request.toString().contains(canary))
        assertFalse(request.headers.single().toString().contains(canary))
        assertFalse(response.toString().contains(canary))
        assertFalse(request.toString().contains("/v1/"))
        assertTrue(request.toString().contains("bodyChars="))
    }

    @Test
    fun endpointPolicyRequiresHttpsExceptForExactLoopbackHosts() {
        listOf(
            "https://provider.example/v1?region=test",
            "http://localhost:8080/v1",
            "http://127.0.0.1:8080/v1",
            "http://[::1]:8080/v1",
        ).forEach(::requireValidHttpEndpoint)

        listOf(
            "provider.example/v1",
            "ftp://provider.example/v1",
            "http://provider.example/v1",
            "http://127.0.0.1.evil.example/v1",
            "https://user:secret@provider.example/v1",
            "https://provider.example/v1#fragment",
        ).forEach { endpoint ->
            assertFailsWith<IllegalArgumentException> {
                requireValidHttpEndpoint(endpoint)
            }
        }
    }

    @Test
    fun providerRequestToString_doesNotExposeTransientCredentialEndpointOrHeaders() {
        val canary = "provider-request-secret-canary"
        val request = ProviderRequest(
            model = ModelDescriptor("gemini", "model"),
            messages = emptyList(),
            credential = ProviderCredential(canary),
            endpoint = "https://example.invalid/v1/$canary?key=$canary",
            headers = mapOf("X-Secret" to canary),
            typedConfig = OpenAiTransportConfig(
                instructions = canary,
                promptCacheKey = canary,
            ),
        )

        assertFalse(request.toString().contains(canary))
        assertTrue(request.toString().contains("credential=<redacted>"))
        assertTrue(request.toString().contains("headerNames=[X-Secret]"))
        assertTrue(request.toString().contains("typedConfig=openai"))
    }
}
