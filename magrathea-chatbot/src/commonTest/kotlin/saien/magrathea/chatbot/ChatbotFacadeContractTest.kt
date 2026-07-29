@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package saien.magrathea.chatbot

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import saien.magrathea.core.AttachmentPart
import saien.magrathea.core.AgentEngineConfig
import saien.magrathea.core.AgentEvent
import saien.magrathea.core.AgentFailureCode
import saien.magrathea.core.AgentCheckpoint
import saien.magrathea.core.AgentMessage
import saien.magrathea.core.AgentPersistence
import saien.magrathea.core.AgentPersistenceRecord
import saien.magrathea.core.AgentRequest
import saien.magrathea.core.AgentRecoveryDisposition
import saien.magrathea.core.AgentRecoveryInfo
import saien.magrathea.core.AgentResumeCursor
import saien.magrathea.core.AgentResumePhase
import saien.magrathea.core.AgentRunId
import saien.magrathea.core.AgentRunner
import saien.magrathea.core.AgentSessionId
import saien.magrathea.core.AgentSessionSnapshot
import saien.magrathea.core.AgentStateSnapshot
import saien.magrathea.core.AgentStatus
import saien.magrathea.core.CredentialRef
import saien.magrathea.core.MessageBlockPhase
import saien.magrathea.core.MessageRole
import saien.magrathea.core.ModelDescriptor
import saien.magrathea.core.ProviderConfig
import saien.magrathea.core.ReasoningPart
import saien.magrathea.core.ReasoningContentKind
import saien.magrathea.core.StopReason
import saien.magrathea.core.TextPart
import saien.magrathea.core.TokenUsage
import saien.magrathea.core.ToolCallPart
import saien.magrathea.core.ToolResultPart
import saien.magrathea.runtime.InMemoryAgentPersistence
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChatbotFacadeContractTest {
    @Test
    fun lifecycle_observationHistoryAndIdempotentCloseStayPlatformNeutral() = runTest {
        val store = InMemoryAgentPersistence()
        val runner = CompletingRunner(store)
        var resourceCloses = 0
        val client = testClient(
            runner = runner,
            store = store,
            dispatcher = StandardTestDispatcher(testScheduler),
            closeResources = { resourceCloses += 1 },
        )
        val session = client.createSession(testChatbotConfiguration())
        val terminal = CompletableDeferred<ChatbotSnapshot>()
        val observation = session.observe(ChatbotStateObserver { snapshot ->
            if (snapshot.status in setOf(
                    ChatbotStatus.COMPLETED,
                    ChatbotStatus.FAILED,
                    ChatbotStatus.CANCELLED,
                )
            ) {
                terminal.complete(snapshot)
            }
        })

        session.send("hello")
        advanceUntilIdle()

        val snapshot = terminal.await()
        assertEquals(ChatbotStatus.COMPLETED, snapshot.status)
        assertEquals(listOf(ChatbotMessageRole.USER, ChatbotMessageRole.ASSISTANT), snapshot.messages.map { it.role })
        assertEquals("answer", snapshot.messages.last().text)
        assertEquals(ChatbotMessagePhase.FINAL, snapshot.messages.last().textBlocks.single().phase)
        assertEquals("reason", snapshot.messages.last().reasoning.single().text)
        assertEquals(ChatbotStopReason.COMPLETED, snapshot.messages.last().stopReason)
        assertEquals(ChatbotUsage(3, 5, 2), snapshot.usage)
        assertEquals(ChatbotUsage(3, 5, 2), snapshot.latestRequestUsage)
        assertEquals(snapshot.sessionId, client.history().single().sessionId)

        observation.cancel()
        client.close()
        client.close()
        assertEquals(1, resourceCloses)
        assertEquals(
            ChatbotFailure.CLOSED,
            assertFailsWith<ChatbotException> { session.send("after-close") }.failure,
        )
    }

    @Test
    fun invalidInputAndMissingResumeFailBeforeRunnerWork() = runTest {
        assertFailsWith<IllegalArgumentException> {
            ChatbotSessionConfiguration(ModelDescriptor(" ", "model"))
        }
        assertFailsWith<IllegalArgumentException> {
            ChatbotSessionConfiguration(ModelDescriptor("provider", " "))
        }
        assertFailsWith<IllegalArgumentException> {
            ChatbotSessionConfiguration(
                model = ModelDescriptor("openai", "model"),
                credentialRef = CredentialRef("anthropic", "work"),
            )
        }
        val store = InMemoryAgentPersistence()
        val runner = CompletingRunner(store)
        val client = testClient(runner, store)
        val session = client.createSession(testChatbotConfiguration())

        assertEquals(
            ChatbotFailure.INVALID_ARGUMENT,
            assertFailsWith<ChatbotException> { session.send("  ") }.failure,
        )
        assertEquals(
            ChatbotFailure.INVALID_ARGUMENT,
            assertFailsWith<ChatbotException> { session.regenerate("missing") }.failure,
        )
        assertEquals(0, runner.runCount)
        val missing = assertFailsWith<ChatbotException> { client.resumeSession("missing") }
        assertEquals(ChatbotFailure.NOT_FOUND, missing.failure)
        assertEquals(null, missing.cause)
        assertFalse(missing.toString().contains("missing"))
        client.close()
    }

    @Test
    fun attachmentSendAndRegenerateAreAvailableThroughThePublicFacade() = runTest {
        val store = InMemoryAgentPersistence()
        val runner = CompletingRunner(store)
        val client = testClient(
            runner = runner,
            store = store,
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        val session = client.createSession(testChatbotConfiguration())

        session.send(
            text = "",
            attachments = listOf(
                ChatbotAttachment(
                    uri = "magrathea-attachment:image-1",
                    mimeType = "image/png",
                    fileName = "diagram.png",
                ),
            ),
            options = ChatbotSendOptions(buildJsonObject { put("source", "attachment") }),
        )
        advanceUntilIdle()

        val firstSnapshot = session.snapshot()
        val user = firstSnapshot.messages.first { it.role == ChatbotMessageRole.USER }
        assertEquals("image/png", user.attachments.single().mimeType)
        assertEquals("diagram.png", user.attachments.single().fileName)
        assertEquals("diagram.png", runner.requests.single().messages.first()
            .parts.filterIsInstance<AttachmentPart>().single().fileName)
        assertEquals("attachment", runner.requests.single().messages.first().metadata["source"]?.jsonPrimitive?.content)
        val firstSessionId = firstSnapshot.sessionId

        session.regenerate(user.id)
        advanceUntilIdle()

        assertEquals(2, runner.runCount)
        assertEquals(firstSessionId, session.snapshot().sessionId)
        assertEquals(firstSessionId, runner.requests.last().sessionId.value)
        client.close()
    }

    @Test
    fun sessionConfigurationDrivesRequestsHistorySwitchingAndResume() = runTest {
        val store = InMemoryAgentPersistence()
        val runner = CompletingRunner(store)
        val dispatcher = StandardTestDispatcher(testScheduler)
        val initial = ChatbotSessionConfiguration(
            model = ModelDescriptor(
                provider = "gemini",
                model = "gemini-fast",
                displayName = "Gemini Fast",
                supportsStreaming = true,
            ),
            credentialRef = CredentialRef(provider = "gemini", profile = "personal"),
        )
        val updated = ChatbotSessionConfiguration(
            model = ModelDescriptor(
                provider = "openai",
                model = "openai-reasoning",
                displayName = "OpenAI Reasoning",
                supportsReasoning = true,
                supportsStreaming = true,
            ),
            credentialRef = CredentialRef(provider = "openai", profile = "openrouter"),
        )
        val client = testClient(
            runner = runner,
            store = store,
            dispatcher = dispatcher,
            requestFactory = ChatbotRequestFactory {
                AgentRequest(
                    sessionId = AgentSessionId("factory-placeholder"),
                    messages = emptyList(),
                    model = ModelDescriptor("factory-placeholder", "factory-placeholder"),
                    engine = AgentEngineConfig(
                        provider = ProviderConfig(
                            credentialRef = CredentialRef(
                                provider = "factory-placeholder",
                                profile = "must-be-overridden",
                            ),
                        ),
                    ),
                )
            },
        )
        val session = client.createSession(initial)

        assertEquals(initial, session.snapshot().configuration)
        session.send("first")
        advanceUntilIdle()

        val sessionId = AgentSessionId(requireNotNull(session.snapshot().sessionId))
        assertEquals(initial.model, runner.requests.single().model)
        assertEquals(initial.credentialRef, runner.requests.single().engine.provider.credentialRef)
        assertEquals(sessionId, runner.requests.single().sessionId)
        assertEquals(
            listOf("first"),
            runner.requests.single().messages.map { message ->
                message.parts.filterIsInstance<TextPart>().joinToString("") { it.text }
            },
        )
        requireNotNull(store.load(sessionId)).also { record ->
            store.commit(
                record.snapshot.copy(updatedAtEpochMs = 1L),
                record.checkpoint,
            )
        }

        session.updateConfiguration(updated)

        assertEquals(updated, session.snapshot().configuration)
        val persisted = requireNotNull(store.load(sessionId)?.snapshot)
        assertEquals(updated.model, persisted.request.model)
        assertEquals(updated.credentialRef, persisted.request.engine.provider.credentialRef)
        assertTrue(persisted.updatedAtEpochMs > 1L)
        assertEquals(updated, client.history().single().configuration)

        session.send("second")
        advanceUntilIdle()
        assertEquals(updated.model, runner.requests.last().model)
        assertEquals(updated.credentialRef, runner.requests.last().engine.provider.credentialRef)

        session.close()
        val resumed = client.resumeSession(sessionId.value)
        assertEquals(updated, resumed.snapshot().configuration)
        advanceUntilIdle()
        assertEquals(ChatbotStatus.COMPLETED, resumed.snapshot().status)
        client.close()
    }

    @Test
    fun historySurfacesOrphanedAndBlockedRunsAsRecoveryStates() = runTest {
        suspend fun historyStatus(
            disposition: AgentRecoveryDisposition,
            recoveryStatus: AgentStatus? = null,
        ): ChatbotStatus {
            val store = InMemoryAgentPersistence()
            val sessionId = AgentSessionId("history-${disposition.name.lowercase()}")
            val request = AgentRequest(
                sessionId = sessionId,
                messages = listOf(
                    AgentMessage(role = MessageRole.USER, parts = listOf(TextPart("question"))),
                ),
                model = ModelDescriptor("test", "test-model"),
            )
            val runId = AgentRunId("history-run")
            val state = AgentStateSnapshot(
                messages = request.messages,
                status = AgentStatus.RUNNING,
            )
            store.commit(
                AgentSessionSnapshot(
                    sessionId = sessionId,
                    runId = runId,
                    request = request,
                    state = state,
                ),
                AgentCheckpoint(
                    sessionId = sessionId,
                    runId = runId,
                    cursor = AgentResumeCursor(0, AgentResumePhase.MODEL_PENDING),
                    state = state,
                ),
            )
            val client = testClient(
                runner = CompletingRunner(
                    store,
                    recoveryDisposition = disposition,
                    recoveryStatus = recoveryStatus,
                ),
                store = store,
            )
            return client.history().single().status.also { client.close() }
        }

        assertEquals(
            ChatbotStatus.INTERRUPTED,
            historyStatus(AgentRecoveryDisposition.RESUMABLE),
        )
        assertEquals(
            ChatbotStatus.RECOVERY_BLOCKED,
            historyStatus(AgentRecoveryDisposition.BLOCKED),
        )
        assertEquals(
            ChatbotStatus.COMPLETED,
            historyStatus(AgentRecoveryDisposition.TERMINAL, AgentStatus.COMPLETED),
        )
    }

    @Test
    fun updatingConfigurationWhileGeneratingFailsBusyWithoutCancellingTheRun() = runTest {
        val store = InMemoryAgentPersistence()
        val runner = BlockingRunner()
        val client = testClient(
            runner = runner,
            store = store,
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        val initial = testChatbotConfiguration("gemini", "gemini-fast")
        val session = client.createSession(initial)
        session.send("hello")
        runCurrent()

        val failure = assertFailsWith<ChatbotException> {
            session.updateConfiguration(testChatbotConfiguration("openai", "openai-fast"))
        }

        assertEquals(ChatbotFailure.BUSY, failure.failure)
        assertEquals(initial, session.snapshot().configuration)
        assertTrue(runner.cancelled.isEmpty())
        session.cancel()
        client.close()
    }

    @Test
    fun resumeDoesNotCancelAnActiveRunOrRestartATerminalRun() = runTest {
        val activeStore = InMemoryAgentPersistence()
        val activeRunner = BlockingRunner()
        val activeClient = testClient(
            runner = activeRunner,
            store = activeStore,
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        val activeSession = activeClient.createSession(testChatbotConfiguration())
        activeSession.send("still running")
        runCurrent()

        assertEquals(
            ChatbotFailure.BUSY,
            assertFailsWith<ChatbotException> { activeSession.resume() }.failure,
        )
        assertTrue(activeRunner.cancelled.isEmpty())
        activeClient.close()

        val terminalStore = InMemoryAgentPersistence()
        val terminalClient = testClient(
            runner = CompletingRunner(terminalStore),
            store = terminalStore,
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        val terminalSession = terminalClient.createSession(testChatbotConfiguration())
        terminalSession.send("completed")
        advanceUntilIdle()

        assertEquals(
            ChatbotFailure.INVALID_ARGUMENT,
            assertFailsWith<ChatbotException> { terminalSession.resume() }.failure,
        )
        terminalClient.close()
    }

    @Test
    fun draftConfigurationCanChangeBeforeTheSessionGetsAnIdentity() = runTest {
        val store = InMemoryAgentPersistence()
        val runner = CompletingRunner(store)
        val client = testClient(
            runner = runner,
            store = store,
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        val updated = testChatbotConfiguration("anthropic", "claude-fast")
        val session = client.createSession(testChatbotConfiguration("gemini", "gemini-fast"))

        session.updateConfiguration(updated)

        assertEquals(null, session.snapshot().sessionId)
        assertEquals(updated, session.snapshot().configuration)
        session.send("hello")
        advanceUntilIdle()
        assertEquals(updated.model, runner.requests.single().model)
        assertEquals(updated, client.history().single().configuration)
        client.close()
    }

    @Test
    fun regenerateDoesNotMisclassifyRequestFactoryFailureAsInvalidInput() = runTest {
        val store = InMemoryAgentPersistence()
        val runner = CompletingRunner(store)
        var factoryCalls = 0
        val client = testClient(
            runner = runner,
            store = store,
            dispatcher = StandardTestDispatcher(testScheduler),
            requestFactory = ChatbotRequestFactory { context ->
                factoryCalls += 1
                if (factoryCalls == 2) throw IllegalArgumentException("factory implementation failed")
                AgentRequest(
                    messages = context.messages,
                    model = ModelDescriptor("test", "test-model"),
                )
            },
        )
        val session = client.createSession(testChatbotConfiguration())
        session.send("hello")
        advanceUntilIdle()
        val userId = session.snapshot().messages.first { it.role == ChatbotMessageRole.USER }.id

        val failure = assertFailsWith<ChatbotException> { session.regenerate(userId) }

        assertEquals(ChatbotFailure.OPERATION_FAILED, failure.failure)
        assertFalse(failure.toString().contains("factory implementation failed"))
        assertEquals(1, runner.runCount)
        client.close()
    }

    @Test
    fun closeLinearizesWithHistoryAndClosedCallsFailBeforeStorageWork() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var listCalls = 0
        val blockingStore = object : AgentPersistence {
            override suspend fun commit(
                snapshot: AgentSessionSnapshot,
                checkpoint: AgentCheckpoint?,
            ) = Unit

            override suspend fun load(sessionId: AgentSessionId): AgentPersistenceRecord? = null

            override suspend fun listSessions(): List<AgentSessionSnapshot> {
                listCalls += 1
                entered.complete(Unit)
                release.await()
                return emptyList()
            }

            override suspend fun deleteSession(sessionId: AgentSessionId) = Unit

            override suspend fun clear() = Unit
        }
        val runnerStore = InMemoryAgentPersistence()
        val client = composeChatbotClient(
            requestFactory = DefaultChatbotRequestFactory(),
            controllerFactory = { requestFactory, configuration, scope ->
                ChatbotController(
                    CompletingRunner(runnerStore),
                    requestFactory,
                    configuration,
                    scope = scope,
                )
            },
            persistence = blockingStore,
            closeResources = { },
            sessionDispatcher = StandardTestDispatcher(testScheduler),
        )

        val history = async { client.history() }
        entered.await()
        val close = async { client.close() }
        runCurrent()

        assertFalse(close.isCompleted)
        release.complete(Unit)
        assertEquals(emptyList(), history.await())
        close.await()
        assertEquals(1, listCalls)

        assertEquals(
            ChatbotFailure.CLOSED,
            assertFailsWith<ChatbotException> { client.history() }.failure,
        )
        assertEquals(1, listCalls)
    }

    @Test
    fun sessionCloseNotifiesItsOwnerOnceAndRacesSafelyWithClientClose() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val store = InMemoryAgentPersistence()
        var ownerNotifications = 0
        val directScope = CoroutineScope(dispatcher)
        val directSession = ChatbotSession(
            controller = ChatbotController(
                runner = CompletingRunner(store),
                requestFactory = DefaultChatbotRequestFactory(),
                initialConfiguration = testChatbotConfiguration(),
                scope = directScope,
            ),
            scope = directScope,
            persistRequest = { _, _ -> },
            onClosed = { ownerNotifications += 1 },
        )

        directSession.close()
        directSession.close()
        assertEquals(1, ownerNotifications)

        var resourceCloses = 0
        val client = testClient(
            runner = CompletingRunner(store),
            store = store,
            dispatcher = dispatcher,
            closeResources = { resourceCloses += 1 },
        )
        val ownedSession = client.createSession(testChatbotConfiguration())
        val sessionClose = async { ownedSession.close() }
        val clientClose = async { client.close() }

        advanceUntilIdle()
        sessionClose.await()
        clientClose.await()
        assertEquals(1, resourceCloses)
    }

    @Test
    fun resumePreloadsPersistedConversationBeforeAsyncRecoveryCompletes() = runTest {
        val store = InMemoryAgentPersistence()
        val sessionId = AgentSessionId("persisted")
        val user = AgentMessage(
            id = "persisted-user",
            role = MessageRole.USER,
            parts = listOf(TextPart("persisted question")),
            createdAtEpochMs = 1L,
        )
        val request = AgentRequest(
            sessionId = sessionId,
            messages = listOf(user),
            model = ModelDescriptor("test", "test-model"),
        )
        val runId = AgentRunId("persisted-run")
        store.commit(
            AgentSessionSnapshot(
                sessionId = sessionId,
                runId = runId,
                request = request,
                state = AgentStateSnapshot(messages = listOf(user), status = AgentStatus.RUNNING),
                updatedAtEpochMs = 2L,
            ),
            AgentCheckpoint(
                sessionId,
                runId,
                AgentResumeCursor(0, AgentResumePhase.MODEL_PENDING),
                AgentStateSnapshot(messages = listOf(user), status = AgentStatus.RUNNING),
            ),
        )
        val client = testClient(
            runner = CompletingRunner(store),
            store = store,
            dispatcher = StandardTestDispatcher(testScheduler),
        )

        val resumed = client.resumeSession(sessionId.value)

        assertEquals("persisted question", resumed.snapshot().messages.single().text)
        advanceUntilIdle()
        assertEquals(ChatbotStatus.COMPLETED, resumed.snapshot().status)
        client.close()
    }

    @Test
    fun canonicalMessageMappingPreservesRichDataAndRedactsProviderFailure() {
        val mapped = AgentMessage(
            id = "assistant",
            role = MessageRole.ASSISTANT,
            parts = listOf(
                TextPart("comment", phase = MessageBlockPhase.COMMENTARY),
                ReasoningPart(
                    "hidden",
                    redacted = true,
                    kind = ReasoningContentKind.SUMMARY,
                ),
                AttachmentPart(
                    "magrathea-attachment:file-1",
                    "image/png",
                    fileName = "diagram.png",
                ),
                ToolCallPart("call-1", "weather", buildJsonObject { }, partial = false),
                ToolResultPart(
                    toolCallId = "call-1",
                    toolName = "weather",
                    result = buildJsonObject {
                        put("code", "weather-unavailable")
                    },
                    isError = true,
                    displayText = "sunny",
                    metadata = buildJsonObject {
                        put("citations", buildJsonArray {
                            add(buildJsonObject {
                                put("title", "Forecast")
                                put("url", "https://example.test")
                                put("snippet", "clear")
                            })
                        })
                    },
                ),
            ),
            createdAtEpochMs = 7L,
            stopReason = StopReason.MAX_TOKENS,
        ).toChatbotMessageSnapshot()

        assertEquals(ChatbotMessagePhase.COMMENTARY, mapped.textBlocks.single().phase)
        assertTrue(mapped.reasoning.single().redacted)
        assertEquals(ChatbotReasoningKind.SUMMARY, mapped.reasoning.single().kind)
        assertEquals("image/png", mapped.attachments.single().mimeType)
        assertEquals("diagram.png", mapped.attachments.single().fileName)
        assertEquals("weather", mapped.toolCalls.single().name)
        assertEquals("Forecast", mapped.toolResults.single().citations.single().title)
        assertEquals("weather-unavailable", mapped.toolResults.single().errorCode)
        assertEquals(ChatbotStopReason.MAX_TOKENS, mapped.stopReason)
        assertEquals(ChatbotUsage(11, 13, 17), TokenUsage(11, 13, 17).toChatbotUsage())
        assertEquals(ChatbotFailure.OPERATION_FAILED, AgentFailureCode.INTERNAL.toChatbotFailure())
        assertEquals(ChatbotFailure.PROTOCOL, AgentFailureCode.PROVIDER_PROTOCOL.toChatbotFailure())
        assertEquals(ChatbotFailure.TIMEOUT, AgentFailureCode.TIMEOUT.toChatbotFailure())
        assertEquals(
            ChatbotFailure.CONTEXT_LIMIT,
            AgentFailureCode.CONTEXT_LIMIT.toChatbotFailure(),
        )
    }

    @Test
    fun deleteSessionAndClearHistoryCloseOwnedSessionsAndEraseBothStores() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val persistence = InMemoryAgentPersistence()
        val runner = CompletingRunner(persistence)
        val client = testClient(runner, persistence, dispatcher)
        val first = client.createSession(testChatbotConfiguration())
        val second = client.createSession(testChatbotConfiguration())
        first.send("first")
        second.send("second")
        advanceUntilIdle()
        val firstId = AgentSessionId(requireNotNull(first.snapshot().sessionId))
        val secondId = AgentSessionId(requireNotNull(second.snapshot().sessionId))
        requireNotNull(persistence.load(firstId)).also { record ->
            persistence.commit(
                record.snapshot,
                AgentCheckpoint(
                    firstId,
                    record.snapshot.runId,
                    AgentResumeCursor(record.snapshot.state.turn, AgentResumePhase.MODEL_PENDING),
                    record.snapshot.state,
                ),
            )
        }
        requireNotNull(persistence.load(secondId)).also { record ->
            persistence.commit(
                record.snapshot,
                AgentCheckpoint(
                    secondId,
                    record.snapshot.runId,
                    AgentResumeCursor(record.snapshot.state.turn, AgentResumePhase.MODEL_PENDING),
                    record.snapshot.state,
                ),
            )
        }

        client.deleteSession(firstId.value)
        client.deleteSession(firstId.value)

        assertEquals(null, persistence.load(firstId))
        assertEquals(listOf(secondId.value), client.history().map { it.sessionId })
        assertEquals(
            ChatbotFailure.CLOSED,
            assertFailsWith<ChatbotException> { first.send("after-delete") }.failure,
        )

        client.clearHistory()
        client.clearHistory()

        assertTrue(persistence.listSessions().isEmpty())
        assertEquals(null, persistence.load(secondId))
        assertEquals(
            ChatbotFailure.CLOSED,
            assertFailsWith<ChatbotException> { second.send("after-clear") }.failure,
        )
        assertTrue(client.history().isEmpty())
        client.close()
    }

    private fun testClient(
        runner: AgentRunner,
        store: InMemoryAgentPersistence,
        dispatcher: CoroutineDispatcher = Dispatchers.Default,
        closeResources: suspend () -> Unit = {},
        requestFactory: ChatbotRequestFactory = DefaultChatbotRequestFactory(),
    ): ChatbotClient = composeChatbotClient(
        requestFactory = requestFactory,
        controllerFactory = { factory, configuration, scope ->
            ChatbotController(runner, factory, configuration, scope = scope)
        },
        persistence = store,
        inspectRecovery = runner::inspectRecovery,
        closeResources = closeResources,
        sessionDispatcher = dispatcher,
    )

    private class CompletingRunner(
        private val store: InMemoryAgentPersistence,
        private val recoveryDisposition: AgentRecoveryDisposition =
            AgentRecoveryDisposition.NOT_FOUND,
        private val recoveryStatus: AgentStatus? = null,
    ) : TestAgentRunner() {
        var runCount = 0
        val requests = mutableListOf<AgentRequest>()

        override fun run(request: AgentRequest): Flow<AgentEvent> = flow {
            runCount += 1
            requests += request
            val runId = AgentRunId("completing-${request.sessionId.value}-$runCount")
            emit(AgentEvent.Started(request.sessionId, runId))
            val assistant = AgentMessage(
                id = "assistant-${request.sessionId.value}",
                role = MessageRole.ASSISTANT,
                parts = listOf(
                    ReasoningPart("reason"),
                    TextPart("answer", phase = MessageBlockPhase.FINAL),
                ),
                createdAtEpochMs = 2L,
                stopReason = StopReason.COMPLETED,
            )
            val state = AgentStateSnapshot(
                messages = request.messages + assistant,
                turn = 1,
                status = AgentStatus.COMPLETED,
                stopReason = StopReason.COMPLETED,
                usage = TokenUsage(3, 5, 2),
                latestRequestUsage = TokenUsage(3, 5, 2),
            )
            store.commit(
                AgentSessionSnapshot(request.sessionId, runId, request, state, updatedAtEpochMs = 10L),
                null,
            )
            emit(AgentEvent.Completed(request.sessionId, state))
        }

        override suspend fun resume(sessionId: AgentSessionId): Flow<AgentEvent> = flow {
            val snapshot = requireNotNull(store.load(sessionId)?.snapshot)
            emit(AgentEvent.Started(sessionId, snapshot.runId))
            emit(AgentEvent.Completed(sessionId, snapshot.state))
        }

        override suspend fun cancel(sessionId: AgentSessionId) = Unit

        override suspend fun inspectRecovery(sessionId: AgentSessionId): AgentRecoveryInfo {
            val snapshot = store.load(sessionId)?.snapshot
            return AgentRecoveryInfo(
                sessionId = sessionId,
                runId = snapshot?.runId,
                disposition = recoveryDisposition,
                status = recoveryStatus ?: snapshot?.state?.status,
            )
        }
    }

    private class BlockingRunner : TestAgentRunner() {
        val requests = mutableListOf<AgentRequest>()
        val cancelled = mutableListOf<AgentSessionId>()

        override fun run(request: AgentRequest): Flow<AgentEvent> = flow {
            requests += request
            emit(AgentEvent.Started(request.sessionId, TEST_RUN_ID))
            awaitCancellation()
        }

        override suspend fun resume(sessionId: AgentSessionId): Flow<AgentEvent> = flow {
            awaitCancellation()
        }

        override suspend fun cancel(sessionId: AgentSessionId) {
            cancelled += sessionId
        }
    }
}
