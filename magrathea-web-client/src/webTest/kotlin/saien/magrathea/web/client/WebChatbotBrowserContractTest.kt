@file:OptIn(
    kotlin.js.ExperimentalWasmJsInterop::class,
    kotlin.uuid.ExperimentalUuidApi::class,
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
)
@file:Suppress("REDUNDANT_CALL_OF_CONVERSION_METHOD")

package saien.magrathea.web.client

import kotlin.js.JsString
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.await
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import saien.magrathea.chatbot.ChatbotException
import saien.magrathea.chatbot.ChatbotFailure
import saien.magrathea.chatbot.ChatbotSessionConfiguration
import saien.magrathea.chatbot.ChatbotSnapshot
import saien.magrathea.chatbot.ChatbotStateObserver
import saien.magrathea.chatbot.ChatbotStatus
import saien.magrathea.core.AgentCheckpoint
import saien.magrathea.core.AgentMessage
import saien.magrathea.core.AgentRequest
import saien.magrathea.core.AgentResumeCursor
import saien.magrathea.core.AgentResumePhase
import saien.magrathea.core.AgentRunId
import saien.magrathea.core.AgentSessionId
import saien.magrathea.core.AgentSessionSnapshot
import saien.magrathea.core.AgentSessionSnapshotCodec
import saien.magrathea.core.AgentStateSnapshot
import saien.magrathea.core.AgentStatus
import saien.magrathea.core.MessageRole
import saien.magrathea.core.ModelDescriptor
import saien.magrathea.core.StopReason
import saien.magrathea.core.TextPart
import saien.magrathea.gateway.protocol.GATEWAY_PROTOCOL_VERSION
import saien.magrathea.gateway.protocol.GATEWAY_SSE_EVENT
import saien.magrathea.gateway.protocol.GATEWAY_VERSION_HEADER
import saien.magrathea.gateway.protocol.GatewayCreateStreamRequest
import saien.magrathea.gateway.protocol.GatewayEvent
import saien.magrathea.gateway.protocol.GatewayProtocolCodec
import saien.magrathea.gateway.protocol.GatewayStreamDescriptor
import saien.magrathea.gateway.protocol.GatewayStreamEnvelope
import saien.magrathea.provider.api.HttpHeader
import saien.magrathea.provider.api.HttpMethod
import saien.magrathea.provider.api.HttpRequestSpec
import saien.magrathea.provider.api.HttpResponseSpec
import saien.magrathea.provider.api.HttpStreamFormat
import saien.magrathea.provider.api.HttpStreamFrame
import saien.magrathea.provider.api.HttpTransport
import saien.magrathea.provider.gateway.GatewaySessionHeaders
import saien.magrathea.provider.gateway.GatewaySessionHeadersProvider
import saien.magrathea.runtime.InMemoryToolRegistry
import saien.magrathea.storage.web.MagratheaWebStoreConfiguration
import saien.magrathea.storage.web.WebStorageException
import saien.magrathea.storage.web.WebStorageFailure
import saien.magrathea.storage.web.createMagratheaWebStore

class WebChatbotBrowserContractTest {
    @Test
    fun compositionRunsCoreThroughGatewayAndNeverPersistsBrowserAuthorization() = runTest {
        withIsolatedDatabase { databaseName ->
            val authorization = "Bearer browser-auth-canary"
            val transport = ScriptedGatewayTransport(StreamBehavior.COMPLETE)
            val store = createMagratheaWebStore(MagratheaWebStoreConfiguration(databaseName))
            val composition = composeWebChatbot(
                configuration = configuration(databaseName),
                sessionHeadersProvider = GatewaySessionHeadersProvider {
                    GatewaySessionHeaders(authorization = authorization, csrfToken = "csrf-canary")
                },
                attachmentCatalog = null,
                toolRegistry = InMemoryToolRegistry(),
                approvalGateway = null,
                permissionGateway = null,
                store = store,
                transport = transport,
                sessionDispatcher = StandardTestDispatcher(testScheduler),
            )
            val session = composition.client.createSession(sessionConfiguration())
            val terminal = CompletableDeferred<ChatbotSnapshot>()
            session.observe(ChatbotStateObserver { snapshot ->
                if (snapshot.status == ChatbotStatus.COMPLETED && !terminal.isCompleted) terminal.complete(snapshot)
            })

            session.send("hello web")
            advanceUntilIdle()

            val snapshot = terminal.await()
            assertEquals(ChatbotStatus.COMPLETED, snapshot.status)
            assertEquals("gateway answer", snapshot.messages.last().text)
            val create = transport.createRequests.single()
            assertEquals("gemini", create.model.provider)
            assertEquals("gemini-test", create.model.model)
            val post = transport.executeRequests.single { it.method == HttpMethod.POST }
            assertEquals(authorization, post.headers.single { it.name == "Authorization" }.value)
            assertFalse(assertNotNull(post.body).contains(authorization))
            assertFalse(post.body!!.contains("credential", ignoreCase = true))

            val persisted = assertNotNull(
                store.persistence.load(AgentSessionId(assertNotNull(snapshot.sessionId)))?.snapshot,
            )
            assertEquals("${persisted.runId.value}:0:0", create.requestId)
            val encoded = AgentSessionSnapshotCodec().encode(persisted)
            assertFalse(encoded.contains(authorization))
            assertEquals(null, persisted.request.engine.provider.credentialRef)
            assertEquals(null, persisted.request.engine.provider.endpoint)
            assertTrue(persisted.request.engine.provider.headers.isEmpty())

            val switched = ChatbotSessionConfiguration(
                ModelDescriptor("openai", "openai-test", supportsStreaming = true),
            )
            session.updateConfiguration(switched)
            val switchedTerminal = CompletableDeferred<ChatbotSnapshot>()
            session.send("hello from another provider")
            session.observe(ChatbotStateObserver { next ->
                if (next.status == ChatbotStatus.COMPLETED && !switchedTerminal.isCompleted) {
                    switchedTerminal.complete(next)
                }
            })
            advanceUntilIdle()

            assertEquals(switched, switchedTerminal.await().configuration)
            assertEquals("openai", transport.createRequests.last().model.provider)
            assertEquals("openai-test", transport.createRequests.last().model.model)

            composition.client.close()
            assertEquals(1, transport.closeCount)
            val closed = assertFailsWith<WebStorageException> {
                store.persistence.load(persisted.sessionId)
            }
            assertEquals(WebStorageFailure.CLOSED, closed.failure)
        }
    }

    @Test
    fun refreshRecoveryReusesStableSessionTurnIdentityAndPreloadsHistory() = runTest {
        withIsolatedDatabase { databaseName ->
            val store = createMagratheaWebStore(MagratheaWebStoreConfiguration(databaseName))
            val sessionId = AgentSessionId("refresh-session")
            val user = AgentMessage(
                id = "refresh-user",
                role = MessageRole.USER,
                parts = listOf(TextPart("before refresh")),
                createdAtEpochMs = 1L,
            )
            val request = AgentRequest(
                sessionId = sessionId,
                messages = listOf(user),
                model = ModelDescriptor("gemini", "gemini-test", supportsStreaming = true),
            )
            val runId = AgentRunId("refresh-run")
            val state = AgentStateSnapshot(
                messages = listOf(user),
                turn = 0,
                status = AgentStatus.RUNNING,
            )
            store.persistence.commit(
                snapshot = AgentSessionSnapshot(
                    sessionId = sessionId,
                    runId = runId,
                    request = request,
                    state = state,
                    updatedAtEpochMs = 2L,
                ),
                checkpoint = AgentCheckpoint(
                    sessionId = sessionId,
                    runId = runId,
                    cursor = AgentResumeCursor(0, AgentResumePhase.MODEL_PENDING),
                    state = state,
                ),
            )
            val transport = ScriptedGatewayTransport(StreamBehavior.COMPLETE)
            val composition = composeWebChatbot(
                configuration = configuration(databaseName),
                sessionHeadersProvider = GatewaySessionHeadersProvider { GatewaySessionHeaders() },
                attachmentCatalog = null,
                toolRegistry = InMemoryToolRegistry(),
                approvalGateway = null,
                permissionGateway = null,
                store = store,
                transport = transport,
                sessionDispatcher = StandardTestDispatcher(testScheduler),
            )

            val resumed = composition.client.resumeSession(sessionId.value)
            assertEquals("before refresh", resumed.snapshot().messages.single().text)
            val terminal = CompletableDeferred<ChatbotSnapshot>()
            resumed.observe(ChatbotStateObserver { snapshot ->
                if (snapshot.status == ChatbotStatus.COMPLETED && !terminal.isCompleted) terminal.complete(snapshot)
            })
            advanceUntilIdle()
            terminal.await()

            assertEquals(ChatbotStatus.COMPLETED, resumed.snapshot().status)
            assertEquals("refresh-run:0:0", transport.createRequests.single().requestId)
            composition.client.close()
        }
    }

    @Test
    fun pageHideDoesNotCancelButExplicitCancelDeletesRemoteStream() = runTest {
        withIsolatedDatabase { databaseName ->
            val transport = ScriptedGatewayTransport(StreamBehavior.HANG)
            val store = createMagratheaWebStore(MagratheaWebStoreConfiguration(databaseName))
            val composition = composeWebChatbot(
                configuration = configuration(databaseName),
                sessionHeadersProvider = GatewaySessionHeadersProvider { GatewaySessionHeaders() },
                attachmentCatalog = null,
                toolRegistry = InMemoryToolRegistry(),
                approvalGateway = null,
                permissionGateway = null,
                store = store,
                transport = transport,
                sessionDispatcher = StandardTestDispatcher(testScheduler),
            )
            val session = composition.client.createSession(sessionConfiguration())
            session.send("keep streaming")
            runCurrent()
            transport.streamStarted.await()

            dispatchPageHideForTest()
            runCurrent()

            assertTrue(transport.executeRequests.none { it.method == HttpMethod.DELETE })
            assertEquals(ChatbotStatus.RUNNING, session.snapshot().status)

            session.cancel()
            runCurrent()
            assertEquals(1, transport.executeRequests.count { it.method == HttpMethod.DELETE })
            assertEquals(ChatbotStatus.CANCELLED, session.snapshot().status)
            composition.client.close()
        }
    }

    @Test
    fun invalidConfigurationFailsBeforeTransportOrStorageWork() {
        listOf(
            configuration("db").copy(gatewayBaseUrl = "http://gateway.example"),
            configuration("../escape"),
            configuration("db").copy(temperature = Double.NaN),
        ).forEach { invalid ->
            val error = assertFailsWith<ChatbotException> { createWebChatbotClient(invalid) }
            assertEquals(ChatbotFailure.INVALID_ARGUMENT, error.failure)
        }
    }

    @Test
    fun configurationToStringOmitsGatewayPromptAndDatabaseValues() {
        val canary = "web-config-canary"
        val rendered = WebChatbotConfiguration(
            gatewayBaseUrl = "https://gateway.example/$canary",
            systemPrompt = canary,
            databaseName = canary,
        ).toString()

        assertFalse(rendered.contains(canary))
        assertTrue(rendered.contains("gatewayBaseUrl=<configured>"))
        assertTrue(rendered.contains("systemPromptChars=${canary.length}"))
    }

    @Test
    fun invalidSessionModelFailsClosedBeforeGatewayTransportWork() = runTest {
        withIsolatedDatabase { databaseName ->
            val transport = ScriptedGatewayTransport(StreamBehavior.COMPLETE)
            val store = createMagratheaWebStore(MagratheaWebStoreConfiguration(databaseName))
            val composition = composeWebChatbot(
                configuration = configuration(databaseName),
                sessionHeadersProvider = GatewaySessionHeadersProvider { GatewaySessionHeaders() },
                attachmentCatalog = null,
                toolRegistry = InMemoryToolRegistry(),
                approvalGateway = null,
                permissionGateway = null,
                store = store,
                transport = transport,
                sessionDispatcher = StandardTestDispatcher(testScheduler),
            )
            val session = composition.client.createSession(
                ChatbotSessionConfiguration(ModelDescriptor("gemini:direct", "model")),
            )
            val terminal = CompletableDeferred<ChatbotSnapshot>()
            session.observe(ChatbotStateObserver { snapshot ->
                if (snapshot.status == ChatbotStatus.FAILED && !terminal.isCompleted) {
                    terminal.complete(snapshot)
                }
            })

            session.send("must not reach transport")
            advanceUntilIdle()

            val snapshot = terminal.await()
            assertEquals(ChatbotStatus.FAILED, snapshot.status)
            assertEquals(ChatbotFailure.PROTOCOL, snapshot.failure)
            assertTrue(transport.executeRequests.isEmpty())
            composition.client.close()
        }
    }

    private fun configuration(databaseName: String) = WebChatbotConfiguration(
        gatewayBaseUrl = "https://gateway.example",
        databaseName = databaseName,
        initialReconnectDelayMillis = 1,
        maxReconnectDelayMillis = 4,
    )

    private fun sessionConfiguration() = ChatbotSessionConfiguration(
        ModelDescriptor("gemini", "gemini-test", supportsStreaming = true),
    )
}

private enum class StreamBehavior {
    COMPLETE,
    HANG,
}

private class ScriptedGatewayTransport(
    private val behavior: StreamBehavior,
) : HttpTransport {
    private val codec = GatewayProtocolCodec()
    val executeRequests = mutableListOf<HttpRequestSpec>()
    val createRequests = mutableListOf<GatewayCreateStreamRequest>()
    val streamStarted = CompletableDeferred<Unit>()
    var closeCount = 0
    private var descriptor: GatewayStreamDescriptor? = null

    override suspend fun execute(request: HttpRequestSpec): HttpResponseSpec {
        executeRequests += request
        return when (request.method) {
            HttpMethod.POST -> {
                val create = codec.decodeCreateRequest(assertNotNull(request.body))
                createRequests += create
                val created = GatewayStreamDescriptor(
                    streamId = "stream-${create.sessionId}",
                    requestId = create.requestId,
                    sessionId = create.sessionId,
                    expiresAtEpochMs = 10_000L,
                )
                descriptor = created
                HttpResponseSpec(
                    statusCode = 201,
                    headers = jsonHeaders(),
                    body = codec.encodeDescriptor(created),
                )
            }
            HttpMethod.DELETE -> HttpResponseSpec(statusCode = 204, body = "")
            else -> error("Unexpected execute request")
        }
    }

    override fun stream(request: HttpRequestSpec, format: HttpStreamFormat): Flow<HttpStreamFrame> = flow {
        assertEquals(HttpStreamFormat.SERVER_SENT_EVENTS, format)
        val stream = assertNotNull(descriptor)
        streamStarted.complete(Unit)
        emit(
            HttpStreamFrame.ResponseStarted(
                statusCode = 200,
                headers = listOf(
                    HttpHeader("Content-Type", "text/event-stream"),
                    HttpHeader(GATEWAY_VERSION_HEADER, GATEWAY_PROTOCOL_VERSION.toString()),
                ),
            ),
        )
        emit(sse(stream, 0, GatewayEvent.StreamOpened()))
        when (behavior) {
            StreamBehavior.COMPLETE -> {
                emit(sse(stream, 1, GatewayEvent.TextDelta("gateway answer")))
                emit(sse(stream, 2, GatewayEvent.Completed(stopReason = StopReason.COMPLETED)))
                emit(HttpStreamFrame.Completed)
            }
            StreamBehavior.HANG -> awaitCancellation()
        }
    }

    override fun close() {
        closeCount += 1
    }

    private fun sse(
        descriptor: GatewayStreamDescriptor,
        sequence: Long,
        event: GatewayEvent,
    ): HttpStreamFrame.ServerSentEvent {
        val envelope = GatewayStreamEnvelope(
            streamId = descriptor.streamId,
            requestId = descriptor.requestId,
            sessionId = descriptor.sessionId,
            sequence = sequence,
            event = event,
        )
        return HttpStreamFrame.ServerSentEvent(
            event = GATEWAY_SSE_EVENT,
            data = codec.encodeEnvelope(envelope),
            id = sequence.toString(),
        )
    }

    private fun jsonHeaders(): List<HttpHeader> = listOf(
        HttpHeader("Content-Type", "application/json"),
        HttpHeader(GATEWAY_VERSION_HEADER, GATEWAY_PROTOCOL_VERSION.toString()),
    )
}

private suspend fun withIsolatedDatabase(block: suspend (String) -> Unit) {
    val databaseName = "web-client-test-${Uuid.random()}"
    try {
        block(databaseName)
    } finally {
        check(deleteDatabaseForTest(databaseName).await().toString() == "ok")
    }
}

private fun dispatchPageHideForTest(): Unit = js(
    "globalThis.dispatchEvent(new Event('pagehide'))",
)

private fun deleteDatabaseForTest(databaseName: String): Promise<JsString> = js(
    """
    new Promise((resolve) => {
      const request = globalThis.indexedDB.deleteDatabase(databaseName);
      request.onsuccess = () => resolve("ok");
      request.onerror = () => resolve("error");
      request.onblocked = () => resolve("blocked");
    })
    """,
)
