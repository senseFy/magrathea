@file:OptIn(
    kotlin.js.ExperimentalWasmJsInterop::class,
    kotlin.uuid.ExperimentalUuidApi::class,
)
@file:Suppress("REDUNDANT_CALL_OF_CONVERSION_METHOD")

package saien.magrathea.samples.web

import kotlin.js.JsString
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.await
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.flow.toList
import saien.magrathea.core.AgentMessage
import saien.magrathea.core.AgentSessionId
import saien.magrathea.core.MessageRole
import saien.magrathea.core.ModelDescriptor
import saien.magrathea.core.TextPart
import saien.magrathea.chatbot.ChatbotSnapshot
import saien.magrathea.chatbot.ChatbotStateObserver
import saien.magrathea.chatbot.ChatbotStatus
import saien.magrathea.chatbot.ChatbotSessionConfiguration
import saien.magrathea.provider.gateway.GatewaySessionHeaders
import saien.magrathea.provider.gateway.GatewaySessionHeadersProvider
import saien.magrathea.provider.gateway.GatewayProviderAdapter
import saien.magrathea.provider.gateway.GatewayProviderConfig
import saien.magrathea.provider.gateway.GatewayReconnectGate
import saien.magrathea.provider.api.ProviderEvent
import saien.magrathea.provider.api.ProviderInvocation
import saien.magrathea.provider.api.ProviderRequest
import saien.magrathea.web.client.WebChatbotConfiguration
import saien.magrathea.web.client.createWebChatbotClient

class WebGatewayE2eTest {
    @Test
    fun gatewayAdapterDirectlyStreamsOverRealHttp() = runTest {
        awaitBrowserGateway()
        val sessionId = "adapter-${Uuid.random()}"
        val requestId = "$sessionId:0"
        val adapter = GatewayProviderAdapter(
            key = "gateway-e2e",
            config = GatewayProviderConfig(
                baseUrl = "http://127.0.0.1:18081",
                initialReconnectDelayMillis = 10,
                maxReconnectDelayMillis = 40,
            ),
            sessionHeadersProvider = GatewaySessionHeadersProvider {
                GatewaySessionHeaders("Bearer e2e-browser-session", "e2e-csrf")
            },
            reconnectGate = GatewayReconnectGate {
                // runTest advances coroutine delays virtually. Keep actual browser reconnects
                // separated in wall-clock time so a short loopback startup hiccup can recover.
                awaitRealTimeDelay(100).await()
            },
        )
        try {
            val chunks = try {
                adapter.generate(
                    ProviderRequest(
                        invocation = ProviderInvocation(requestId, AgentSessionId(sessionId), 0),
                        model = ModelDescriptor("gateway-e2e", "e2e-model", supportsStreaming = true),
                        messages = listOf(
                            AgentMessage(
                                role = MessageRole.USER,
                                parts = listOf(TextPart("direct adapter HTTP")),
                            ),
                        ),
                    ),
                ).toList()
            } catch (failure: Throwable) {
                val cause = failure.cause
                println(
                    "MAGRATHEA_GATEWAY_E2E_HTTP_FAILURE " +
                        "${failure::class.simpleName}: ${failure.message}; " +
                        "cause=${cause?.let { it::class.simpleName }}:${cause?.message}",
                )
                throw failure
            }
            assertTrue(chunks.flatMap { it.events }.any { it is ProviderEvent.Completed })
            assertEquals(1, awaitMetric("provider-calls", requestId))
        } finally {
            adapter.close()
        }
    }

    @Test
    fun realHttpGatewayStreamsCanonicalTurnThroughCore() = runTest {
        awaitBrowserGateway()
        withDatabase { databaseName ->
            val client = e2eClient(databaseName)
            try {
                val session = client.createSession(e2eSessionConfiguration())
                val terminal = CompletableDeferred<ChatbotSnapshot>()
                val observation = session.observe(terminalObserver(terminal))

                session.send("hello over real HTTP")
                val snapshot = terminal.await()

                assertEquals(ChatbotStatus.COMPLETED, snapshot.status)
                assertEquals("gateway e2e answer", snapshot.messages.last().text)
                val requestId = "${snapshot.messages.first { it.role == saien.magrathea.chatbot.ChatbotMessageRole.USER }.id}:0"
                assertEquals(1, awaitMetric("provider-calls", requestId))
                observation.cancel()
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun refreshStyleRecompositionReusesDetachedStreamAndProviderInvocation() = runTest {
        awaitBrowserGateway()
        withDatabase { databaseName ->
            val firstClient = e2eClient(databaseName)
            var secondClient: saien.magrathea.chatbot.ChatbotClient? = null
            try {
                val firstSession = firstClient.createSession(e2eSessionConfiguration())
                firstSession.send("refresh this stream")
                val sessionId = assertNotNull(firstSession.snapshot().sessionId)
                val requestId = "${firstSession.snapshot().messages.first().id}:0"
                assertEquals(1, awaitMetric("provider-calls", requestId))

                val resumedClient = e2eClient(databaseName)
                secondClient = resumedClient
                val resumed = resumedClient.resumeSession(sessionId)
                assertEquals("refresh this stream", resumed.snapshot().messages.single().text)
                val terminal = CompletableDeferred<ChatbotSnapshot>()
                resumed.observe(terminalObserver(terminal))

                assertEquals(ChatbotStatus.COMPLETED, terminal.await().status)
                assertEquals(1, awaitMetric("provider-calls", requestId))
            } finally {
                secondClient?.close()
                firstClient.close()
            }
        }
    }

    @Test
    fun explicitCancelCrossesHttpBoundaryAndStopsProvider() = runTest {
        awaitBrowserGateway()
        withDatabase { databaseName ->
            val client = e2eClient(databaseName)
            try {
                val session = client.createSession(e2eSessionConfiguration())
                session.send("hang until explicit cancel")
                val requestId = "${session.snapshot().messages.first().id}:0"
                assertEquals(1, awaitMetric("provider-calls", requestId))

                session.cancel()

                assertEquals(ChatbotStatus.CANCELLED, session.snapshot().status)
                assertEquals(1, awaitMetric("provider-cancellations", requestId))
            } finally {
                client.close()
            }
        }
    }

    private fun e2eClient(databaseName: String) = createWebChatbotClient(
        configuration = WebChatbotConfiguration(
            gatewayBaseUrl = "http://127.0.0.1:18081",
            databaseName = databaseName,
            initialReconnectDelayMillis = 10,
            maxReconnectDelayMillis = 40,
        ),
        sessionHeadersProvider = GatewaySessionHeadersProvider {
            GatewaySessionHeaders(
                authorization = "Bearer e2e-browser-session",
                csrfToken = "e2e-csrf",
            )
        },
    )

    private fun e2eSessionConfiguration() = ChatbotSessionConfiguration(
        ModelDescriptor("gateway-e2e", "e2e-model", supportsStreaming = true),
    )

    private fun terminalObserver(terminal: CompletableDeferred<ChatbotSnapshot>) =
        ChatbotStateObserver { snapshot ->
            if (
                snapshot.status in setOf(ChatbotStatus.COMPLETED, ChatbotStatus.FAILED, ChatbotStatus.CANCELLED) &&
                !terminal.isCompleted
            ) {
                terminal.complete(snapshot)
            }
        }
}

private suspend fun awaitBrowserGateway() {
    val readinessRequestId = "readiness-${Uuid.random()}"
    var lastFailure: Throwable? = null
    repeat(50) {
        val ready = try {
            fetchText(
                "http://127.0.0.1:18081/e2e/provider-calls" +
                    "?requestId=${encodeUriComponent(readinessRequestId)}",
            ).await().toString() == "0"
        } catch (failure: Throwable) {
            lastFailure = failure
            false
        }
        if (ready) return
        awaitRealTimeDelay(100).await()
    }
    val cause = lastFailure?.cause
    error(
        "Gateway E2E browser readiness failed; " +
            "lastFailure=${lastFailure?.let { it::class.simpleName }}:${lastFailure?.message}; " +
            "cause=${cause?.let { it::class.simpleName }}:${cause?.message}",
    )
}

private suspend fun awaitMetric(metric: String, requestId: String): Int {
    repeat(200) {
        val value = fetchText(
            "http://127.0.0.1:18081/e2e/$metric?requestId=${encodeUriComponent(requestId)}",
        ).await().toString().toIntOrNull() ?: 0
        if (value > 0) return value
        awaitRealTimeDelay(25).await()
    }
    error("Gateway E2E metric '$metric' was not observed for request '$requestId'")
}

private suspend fun withDatabase(block: suspend (String) -> Unit) {
    val databaseName = "gateway-e2e-${Uuid.random()}"
    try {
        block(databaseName)
    } finally {
        assertTrue(deleteDatabase(databaseName).await().toString() == "ok")
    }
}

private fun encodeUriComponent(value: String): String = js("encodeURIComponent(value)")

private fun fetchText(url: String): Promise<JsString> = js(
    "fetch(url).then((response) => { if (!response.ok) throw new Error('metric request failed'); return response.text(); })",
)

private fun awaitRealTimeDelay(delayMillis: Int): Promise<JsString> = js(
    "new Promise((resolve) => setTimeout(() => resolve('ok'), delayMillis))",
)

private fun deleteDatabase(databaseName: String): Promise<JsString> = js(
    """
    new Promise((resolve) => {
      const request = globalThis.indexedDB.deleteDatabase(databaseName);
      request.onsuccess = () => resolve("ok");
      request.onerror = () => resolve("error");
      request.onblocked = () => resolve("blocked");
    })
    """,
)
