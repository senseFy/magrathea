package saien.magrathea.provider.gemini

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import saien.magrathea.core.AgentMessage
import saien.magrathea.core.CredentialRef
import saien.magrathea.core.MessageRole
import saien.magrathea.core.ModelDescriptor
import saien.magrathea.core.ProviderCredential
import saien.magrathea.core.ReasoningCapabilities
import saien.magrathea.core.ReasoningEffort
import saien.magrathea.core.ReasoningPreference
import saien.magrathea.core.TextPart
import saien.magrathea.provider.api.HttpResponseSpec
import saien.magrathea.provider.api.HttpRequestSpec
import saien.magrathea.provider.api.HttpStreamFormat
import saien.magrathea.provider.api.HttpStreamFrame
import saien.magrathea.provider.api.HttpTransport
import saien.magrathea.provider.api.ProviderAuthException
import saien.magrathea.provider.api.ProviderEvent
import saien.magrathea.provider.api.ProviderProtocolException
import saien.magrathea.provider.api.ProviderRateLimitException
import saien.magrathea.provider.api.ProviderRequest
import saien.magrathea.provider.api.ProviderStreamInterruptedException

class GeminiProviderTransportContractTest {
    @Test
    fun modelReasoningCapabilityResolvesBeforeGeminiEncoding() = runTest {
        val transport = ScriptedHttpTransport(
            executeScripts = listOf(HttpResponseSpec(200, body = TOOL_INTERACTION_JSON)),
        )
        val model = ModelDescriptor(
            provider = "gemini",
            model = "gemini-contract-model",
            reasoningCapabilities = ReasoningCapabilities(
                supportedEfforts = setOf(ReasoningEffort.HIGH),
            ),
        )

        GeminiProviderAdapter(transport = transport).generate(
            request(streaming = false).copy(
                model = model,
                reasoningPreference = ReasoningPreference.Effort(ReasoningEffort.HIGH),
            ),
        ).toList()

        val generationConfig = Json.parseToJsonElement(
            transport.requests.single().body.orEmpty(),
        ).jsonObject.getValue("generation_config").jsonObject
        assertEquals("high", generationConfig.getValue("thinking_level").jsonPrimitive.content)
    }

    @Test
    fun neutralAndNativeThinkingLevelConflictBeforeTransport() = runTest {
        val transport = ScriptedHttpTransport()
        val model = ModelDescriptor(
            provider = "gemini",
            model = "gemini-reasoning-contract",
            reasoningCapabilities = ReasoningCapabilities(
                supportedEfforts = setOf(ReasoningEffort.LOW),
            ),
        )

        assertFailsWith<IllegalArgumentException> {
            GeminiProviderAdapter(transport = transport).generate(
                request(streaming = false).copy(
                    model = model,
                    reasoningPreference = ReasoningPreference.Effort(ReasoningEffort.LOW),
                    typedConfig = saien.magrathea.provider.api.GeminiTransportConfig(
                        thinkingLevel = "low",
                    ),
                ),
            ).toList()
        }
        assertTrue(transport.requests.isEmpty())
    }

    @Test
    fun unsupportedCanonicalEffortFailsBeforeTransport() = runTest {
        val transport = ScriptedHttpTransport()
        val model = ModelDescriptor(
            provider = "gemini",
            model = "gemini-reasoning-contract",
            reasoningCapabilities = ReasoningCapabilities(
                supportedEfforts = setOf(ReasoningEffort.XHIGH, ReasoningEffort.MAX),
            ),
        )

        listOf(ReasoningEffort.XHIGH, ReasoningEffort.MAX).forEach { effort ->
            assertFailsWith<IllegalArgumentException> {
                GeminiProviderAdapter(transport = transport).generate(
                    request(streaming = false).copy(
                        model = model,
                        reasoningPreference = ReasoningPreference.Effort(effort),
                    ),
                ).toList()
            }
        }
        assertTrue(transport.requests.isEmpty())
    }

    @Test
    fun disabledReasoningIsRejectedBecauseInteractionsHasNoOffWireValue() = runTest {
        val transport = ScriptedHttpTransport()
        val model = ModelDescriptor(
            provider = "gemini",
            model = "gemini-reasoning-contract",
            reasoningCapabilities = ReasoningCapabilities(supportsDisabled = true),
        )

        assertFailsWith<IllegalArgumentException> {
            GeminiProviderAdapter(transport = transport).generate(
                request(streaming = false).copy(
                    model = model,
                    reasoningPreference = ReasoningPreference.Disabled,
                ),
            ).toList()
        }
        assertTrue(transport.requests.isEmpty())
    }

    @Test
    fun streamingAdapterUsesStableV1SseAndNeverSerializesCredential() = runTest {
        val canary = "gemini-secret-canary"
        val transport = ScriptedHttpTransport(streamScripts = listOf(sseFrames(TOOL_INTERACTION_SSE)))
        val adapter = GeminiProviderAdapter(
            transport = transport,
        )

        val chunks = adapter.generate(
            request(streaming = true, credential = ProviderCredential(canary)),
        ).toList()

        assertEquals(1, chunks.flatMap { it.events }.filterIsInstance<ProviderEvent.Completed>().size)
        val outbound = transport.requests.single()
        assertEquals("https://generativelanguage.googleapis.com/v1/interactions", outbound.url)
        assertEquals("text/event-stream", outbound.headers.single { it.name == "Accept" }.value)
        assertEquals(canary, outbound.headers.single { it.name == "x-goog-api-key" }.value)
        assertFalse(outbound.body.orEmpty().contains(canary))
        assertFalse(outbound.toString().contains(canary))
        val payload = Json.parseToJsonElement(outbound.body!!).jsonObject
        assertEquals(false, payload["store"]!!.jsonPrimitive.content.toBoolean())
        adapter.close()
        assertTrue(transport.closed)
    }

    @Test
    fun nonStreamingAdapterUsesTheSameCodecAndCanonicalEvents() = runTest {
        val transport = ScriptedHttpTransport(
            executeScripts = listOf(HttpResponseSpec(200, body = TOOL_INTERACTION_JSON)),
        )
        val adapter = GeminiProviderAdapter(
            transport = transport,
        )

        val chunks = adapter.generate(request(streaming = false)).toList()

        assertEquals(1, chunks.size)
        assertEquals(1, chunks.single().events.filterIsInstance<ProviderEvent.ToolCallEnd>().size)
        assertEquals(1, chunks.single().events.filterIsInstance<ProviderEvent.Completed>().size)
        assertEquals("application/json", transport.requests.single().headers.single { it.name == "Accept" }.value)
    }

    @Test
    fun streamingFlowWithoutTransportCompletionFailsClosed() = runTest {
        val transport = ScriptedHttpTransport(
            streamScripts = listOf(sseFrames(FINAL_INTERACTION_SSE).dropLast(1)),
        )
        val adapter = GeminiProviderAdapter(
            transport = transport,
        )

        assertFailsWith<ProviderProtocolException> {
            adapter.generate(request(streaming = true)).toList()
        }
    }

    @Test
    fun cleanEofBeforeProtocolTerminalIsRecoverable() = runTest {
        val transport = ScriptedHttpTransport(
            streamScripts = listOf(
                sseFrames(FINAL_INTERACTION_SSE.substringBefore("event: interaction.completed")),
            ),
        )
        val adapter = GeminiProviderAdapter(transport = transport)

        assertFailsWith<ProviderStreamInterruptedException> {
            adapter.generate(request(streaming = true)).toList()
        }
    }

    @Test
    fun streamingCanonicalErrorRemainsTypedAndDoesNotExposeProviderMessage() = runTest {
        val canary = "private-gemini-error-canary"
        val transport = ScriptedHttpTransport(
            streamScripts = listOf(
                sseFrames(
                    """event: error
data: {"event_type":"error","error":{"code":429,"status":"RESOURCE_EXHAUSTED","message":"$canary"}}

""",
                ),
            ),
        )
        val adapter = GeminiProviderAdapter(transport = transport)

        val failure = assertFailsWith<ProviderRateLimitException> {
            adapter.generate(request(streaming = true)).toList()
        }

        assertEquals(429, failure.statusCode)
        assertFalse(failure.toString().contains(canary))
        assertEquals(1, transport.requests.size)
    }

    @Test
    fun cancellationPropagatesWithoutFallbackOrSecondRequest() = runTest {
        val entered = CompletableDeferred<Unit>()
        val transport = SuspendingStreamTransport(entered)
        val adapter = GeminiProviderAdapter(
            transport = transport,
        )
        val operation = async { adapter.generate(request(streaming = true)).toList() }
        entered.await()

        operation.cancel(CancellationException("provider cancelled"))
        val failure = assertFailsWith<CancellationException> { operation.await() }

        assertEquals("provider cancelled", failure.message)
        assertEquals(1, transport.requestCount)
    }

    @Test
    fun credentialMustBeInjectedAndMatchTheProvider() = runTest {
        val transport = ScriptedHttpTransport()
        val adapter = GeminiProviderAdapter(transport = transport)

        assertFailsWith<ProviderAuthException> {
            adapter.generate(request(streaming = false, credential = null)).toList()
        }
        assertFailsWith<ProviderAuthException> {
            adapter.generate(
                request(
                    streaming = false,
                    credentialRef = CredentialRef("another-provider"),
                ),
            ).toList()
        }

        assertEquals(emptyList(), transport.requests)
    }

    private fun request(
        streaming: Boolean,
        credential: ProviderCredential? = ProviderCredential("test-key"),
        credentialRef: CredentialRef = CredentialRef("gemini"),
    ): ProviderRequest = ProviderRequest(
        model = ModelDescriptor("gemini", "gemini-contract-model", supportsStreaming = streaming),
        messages = listOf(AgentMessage(role = MessageRole.USER, parts = listOf(TextPart("Weather in Shanghai?")))),
        credentialRef = credentialRef,
        credential = credential,
    )

    private class SuspendingStreamTransport(
        private val entered: CompletableDeferred<Unit>,
    ) : HttpTransport {
        var requestCount: Int = 0
            private set

        override suspend fun execute(request: HttpRequestSpec): HttpResponseSpec = error("Unexpected execute")

        override fun stream(request: HttpRequestSpec, format: HttpStreamFormat): Flow<HttpStreamFrame> = flow {
            requestCount += 1
            emit(HttpStreamFrame.ResponseStarted(200, emptyList()))
            entered.complete(Unit)
            awaitCancellation()
        }

        override fun close() = Unit
    }
}
