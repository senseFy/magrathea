@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package saien.magrathea.chatbot

import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
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
import saien.magrathea.core.AgentInterruption
import saien.magrathea.core.AgentInterruptionReason
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
import saien.magrathea.core.ReasoningCapabilities
import saien.magrathea.core.ReasoningEffort
import saien.magrathea.core.ReasoningPreference
import saien.magrathea.core.ReasoningPart
import saien.magrathea.core.ReasoningContentKind
import saien.magrathea.core.StopReason
import saien.magrathea.core.TextPart
import saien.magrathea.core.TokenUsage
import saien.magrathea.core.ToolCallPart
import saien.magrathea.core.ToolResultPart
import saien.magrathea.runtime.InMemoryAgentPersistence
import saien.magrathea.runtime.AgentSessionLease
import saien.magrathea.runtime.AgentSessionManager
import saien.magrathea.runtime.AgentSessionPhase
import saien.magrathea.runtime.AgentSessionRuntimeSnapshot
import saien.magrathea.runtime.DefaultAgentSessionManager
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
                reasoningCapabilities = ReasoningCapabilities(
                    supportedEfforts = setOf(ReasoningEffort.HIGH),
                ),
                supportsStreaming = true,
            ),
            credentialRef = CredentialRef(provider = "openai", profile = "openrouter"),
            reasoningPreference = ReasoningPreference.Effort(ReasoningEffort.HIGH),
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
        assertEquals(updated.reasoningPreference, runner.requests.last().reasoningPreference)

        session.close()
        val restored = client.restoreSession(sessionId.value)
        assertEquals(updated, restored.snapshot().configuration)
        advanceUntilIdle()
        assertEquals(ChatbotStatus.COMPLETED, restored.snapshot().status)
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
        val client = createChatbotClient(
            runner = CompletingRunner(runnerStore),
            requestFactory = DefaultChatbotRequestFactory(),
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
    fun concurrentCloseFollowerWaitsForThePublishedCleanupOutcome() = runTest {
        val enteredCleanup = CompletableDeferred<Unit>()
        val releaseCleanup = CompletableDeferred<Unit>()
        var resourceCloses = 0
        val store = InMemoryAgentPersistence()
        val client = testClient(
            runner = CompletingRunner(store),
            store = store,
            dispatcher = StandardTestDispatcher(testScheduler),
            closeResources = {
                resourceCloses += 1
                enteredCleanup.complete(Unit)
                releaseCleanup.await()
            },
        )

        val owner = async { client.close() }
        runCurrent()
        enteredCleanup.await()
        val follower = async { client.close() }
        runCurrent()

        assertFalse(owner.isCompleted)
        assertFalse(follower.isCompleted)
        assertEquals(
            ChatbotFailure.CLOSED,
            assertFailsWith<ChatbotException> { client.history() }.failure,
        )

        releaseCleanup.complete(Unit)
        owner.await()
        follower.await()
        assertEquals(1, resourceCloses)
    }

    @Test
    fun concurrentAndLateCloseCallersShareTheCleanupFailure() = runTest {
        val enteredCleanup = CompletableDeferred<Unit>()
        val releaseCleanup = CompletableDeferred<Unit>()
        var resourceCloses = 0
        val store = InMemoryAgentPersistence()
        val client = testClient(
            runner = CompletingRunner(store),
            store = store,
            dispatcher = StandardTestDispatcher(testScheduler),
            closeResources = {
                resourceCloses += 1
                enteredCleanup.complete(Unit)
                releaseCleanup.await()
                error("close resource failure")
            },
        )

        val owner = async {
            assertFailsWith<ChatbotException> { client.close() }
        }
        runCurrent()
        enteredCleanup.await()
        val follower = async {
            assertFailsWith<ChatbotException> { client.close() }
        }
        runCurrent()
        assertFalse(follower.isCompleted)

        releaseCleanup.complete(Unit)
        val ownerFailure = owner.await()
        val followerFailure = follower.await()
        val lateFailure = assertFailsWith<ChatbotException> { client.close() }

        assertEquals(ChatbotFailure.OPERATION_FAILED, ownerFailure.failure)
        assertEquals(ownerFailure.failure, followerFailure.failure)
        assertEquals(ownerFailure.failure, lateFailure.failure)
        assertEquals(1, resourceCloses)
    }

    @Test
    fun cancelledFirstCloserStillPublishesCleanupForFollowers() = runTest {
        val enteredCleanup = CompletableDeferred<Unit>()
        val releaseCleanup = CompletableDeferred<Unit>()
        var resourceCloses = 0
        val store = InMemoryAgentPersistence()
        val client = testClient(
            runner = CompletingRunner(store),
            store = store,
            dispatcher = StandardTestDispatcher(testScheduler),
            closeResources = {
                resourceCloses += 1
                enteredCleanup.complete(Unit)
                releaseCleanup.await()
            },
        )

        val owner = async { client.close() }
        runCurrent()
        enteredCleanup.await()
        owner.cancel()
        val follower = async { client.close() }
        runCurrent()

        assertFalse(owner.isCompleted)
        assertFalse(follower.isCompleted)

        releaseCleanup.complete(Unit)
        assertFailsWith<kotlin.coroutines.cancellation.CancellationException> { owner.await() }
        follower.await()
        client.close()
        assertEquals(1, resourceCloses)
    }

    @Test
    fun sessionCloseCallersShareControllerCleanupFailure() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val enteredRelease = CompletableDeferred<Unit>()
        val releaseFailure = CompletableDeferred<Unit>()
        var releaseCalls = 0
        var ownerNotifications = 0
        val delegate = CancellingHandoffLease(
            sessionId = AgentSessionId("failing-session-close"),
            cancellationPoint = HandoffCancellationPoint.NONE,
            handoffJob = Job(),
        )
        val lease = object : AgentSessionLease by delegate {
            override suspend fun release() {
                releaseCalls += 1
                enteredRelease.complete(Unit)
                releaseFailure.await()
                error("lease release failure")
            }
        }
        val scope = CoroutineScope(dispatcher)
        val session = ChatbotSession(
            controller = ChatbotController(
                lease = lease,
                requestFactory = DefaultChatbotRequestFactory(),
                initialConfiguration = testChatbotConfiguration(),
                scope = scope,
            ),
            scope = scope,
            onClosed = { ownerNotifications += 1 },
        )

        val owner = async {
            assertFailsWith<ChatbotException> { session.close() }
        }
        runCurrent()
        enteredRelease.await()
        val follower = async {
            assertFailsWith<ChatbotException> { session.close() }
        }
        runCurrent()
        assertFalse(follower.isCompleted)

        releaseFailure.complete(Unit)
        val ownerFailure = owner.await()
        val followerFailure = follower.await()
        val lateFailure = assertFailsWith<ChatbotException> { session.close() }

        assertEquals(ChatbotFailure.OPERATION_FAILED, ownerFailure.failure)
        assertEquals(ownerFailure.failure, followerFailure.failure)
        assertEquals(ownerFailure.failure, lateFailure.failure)
        assertEquals(1, releaseCalls)
        assertEquals(1, ownerNotifications)
    }

    @Test
    fun rejectedHandoffCleanupContainsLeaseReleaseFailure() = runTest {
        val uncaught = mutableListOf<Throwable>()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(
            SupervisorJob() + dispatcher + CoroutineExceptionHandler { _, failure ->
                uncaught += failure
            },
        )
        var releaseCalls = 0
        var ownerNotifications = 0
        val delegate = CancellingHandoffLease(
            sessionId = AgentSessionId("rejected-failing-handoff"),
            cancellationPoint = HandoffCancellationPoint.NONE,
            handoffJob = Job(),
        )
        val lease = object : AgentSessionLease by delegate {
            override suspend fun release() {
                releaseCalls += 1
                error("rejected handoff release failure")
            }
        }
        val session = ChatbotSession(
            controller = ChatbotController(
                lease = lease,
                requestFactory = DefaultChatbotRequestFactory(),
                initialConfiguration = testChatbotConfiguration(),
                scope = scope,
            ),
            scope = scope,
            onClosed = { ownerNotifications += 1 },
        )

        session.closeAfterRejectedHandoff()
        runCurrent()

        assertTrue(uncaught.isEmpty())
        assertEquals(1, releaseCalls)
        assertEquals(1, ownerNotifications)
    }

    @Test
    fun sessionCloseNotifiesItsOwnerOnceAndRacesSafelyWithClientClose() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val store = InMemoryAgentPersistence()
        var ownerNotifications = 0
        val directScope = CoroutineScope(dispatcher)
        val directManager = DefaultAgentSessionManager(
            runner = CompletingRunner(store),
            persistence = store,
            dispatcher = dispatcher,
        )
        val directLease = directManager.create()
        val directSession = ChatbotSession(
            controller = ChatbotController(
                lease = directLease,
                requestFactory = DefaultChatbotRequestFactory(),
                initialConfiguration = testChatbotConfiguration(),
                scope = directScope,
            ),
            scope = directScope,
            onClosed = { ownerNotifications += 1 },
        )

        directSession.close()
        directSession.close()
        advanceUntilIdle()
        assertEquals(1, ownerNotifications)
        directManager.close()

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
            runner = CompletingRunner(
                store,
                recoveryDisposition = AgentRecoveryDisposition.RESUMABLE,
            ),
            store = store,
            dispatcher = StandardTestDispatcher(testScheduler),
        )

        val resumed = client.resumeSession(sessionId.value)

        assertEquals("persisted question", resumed.snapshot().messages.single().text)
        assertEquals(
            ChatbotFailure.INVALID_ARGUMENT,
            assertFailsWith<ChatbotException> {
                client.resumeSession(sessionId.value)
            }.failure,
        )
        advanceUntilIdle()
        assertEquals(ChatbotStatus.COMPLETED, resumed.snapshot().status)
        client.close()
    }

    @Test
    fun restoreProjectsRecoveryWithoutStartingPersistedExecution() = runTest {
        val store = InMemoryAgentPersistence()
        val sessionId = AgentSessionId("restore-only")
        val user = AgentMessage(
            id = "restore-only-user",
            role = MessageRole.USER,
            parts = listOf(TextPart("wait for a host")),
            createdAtEpochMs = 1L,
        )
        val request = AgentRequest(
            sessionId = sessionId,
            messages = listOf(user),
            model = ModelDescriptor("test", "test-model"),
        )
        val runId = AgentRunId("restore-only-run")
        val runningState = AgentStateSnapshot(
            messages = listOf(user),
            status = AgentStatus.RUNNING,
        )
        store.commit(
            AgentSessionSnapshot(
                sessionId = sessionId,
                runId = runId,
                request = request,
                state = runningState,
                updatedAtEpochMs = 2L,
            ),
            AgentCheckpoint(
                sessionId,
                runId,
                AgentResumeCursor(0, AgentResumePhase.MODEL_PENDING),
                runningState,
            ),
        )
        val runner = CompletingRunner(
            store = store,
            recoveryDisposition = AgentRecoveryDisposition.RESUMABLE,
        )
        val client = testClient(
            runner = runner,
            store = store,
            dispatcher = StandardTestDispatcher(testScheduler),
        )

        val restored = client.restoreSession(sessionId.value)
        val secondRestoredFacade = client.restoreSession(sessionId.value)
        advanceUntilIdle()

        assertTrue(restored !== secondRestoredFacade)
        assertEquals(ChatbotStatus.INTERRUPTED, restored.snapshot().status)
        assertEquals(restored.snapshot(), secondRestoredFacade.snapshot())
        assertEquals(
            ChatbotInterruptionReason.ORPHANED,
            restored.snapshot().interruption?.reason,
        )
        assertEquals(0, runner.resumeCount)

        restored.resume()
        advanceUntilIdle()
        assertEquals(1, runner.resumeCount)
        assertEquals(ChatbotStatus.COMPLETED, restored.snapshot().status)
        assertEquals(ChatbotStatus.COMPLETED, secondRestoredFacade.snapshot().status)
        client.close()
    }

    @Test
    fun restoreFailsClosedForUnattachableOrInconsistentRecovery() = runTest {
        val store = InMemoryAgentPersistence()
        val sessionId = AgentSessionId("restore-fail-closed")
        val user = AgentMessage(
            id = "restore-fail-closed-user",
            role = MessageRole.USER,
            parts = listOf(TextPart("recover safely")),
        )
        val request = AgentRequest(
            sessionId = sessionId,
            messages = listOf(user),
            model = ModelDescriptor("test", "test-model"),
        )
        val runId = AgentRunId("restore-fail-closed-run")
        val runningState = AgentStateSnapshot(
            messages = listOf(user),
            status = AgentStatus.RUNNING,
        )
        store.commit(
            AgentSessionSnapshot(sessionId, runId, request, runningState),
            AgentCheckpoint(
                sessionId,
                runId,
                AgentResumeCursor(0, AgentResumePhase.MODEL_PENDING),
                runningState,
            ),
        )

        val activeClient = testClient(
            runner = CompletingRunner(store, AgentRecoveryDisposition.ACTIVE),
            store = store,
        )
        assertEquals(
            ChatbotFailure.BUSY,
            assertFailsWith<ChatbotException> {
                activeClient.restoreSession(sessionId.value)
            }.failure,
        )
        activeClient.close()

        val missingClient = testClient(
            runner = CompletingRunner(store, AgentRecoveryDisposition.NOT_FOUND),
            store = store,
        )
        assertEquals(
            ChatbotFailure.NOT_FOUND,
            assertFailsWith<ChatbotException> {
                missingClient.restoreSession(sessionId.value)
            }.failure,
        )
        missingClient.close()

        val inconsistentRunner = CompletingRunner(
            store = store,
            recoveryDisposition = AgentRecoveryDisposition.RESUMABLE,
            recoveryRunId = AgentRunId("newer-run"),
        )
        val inconsistentClient = testClient(inconsistentRunner, store)
        assertEquals(
            ChatbotFailure.BUSY,
            assertFailsWith<ChatbotException> {
                inconsistentClient.restoreSession(sessionId.value)
            }.failure,
        )
        assertEquals(2, inconsistentRunner.inspectCount)
        inconsistentClient.close()
    }

    @Test
    fun facadeCloseOnlyDetachesAndDoesNotInterruptCanonicalRuntime() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val store = InMemoryAgentPersistence()
        val runner = BlockingRunner()
        val manager = DefaultAgentSessionManager(runner, store, dispatcher)
        val client = createChatbotClient(
            sessionManager = manager,
            requestFactory = DefaultChatbotRequestFactory(),
            sessionDispatcher = dispatcher,
        )
        val session = client.createSession(testChatbotConfiguration())
        session.send("keep running")
        runCurrent()
        val sessionId = AgentSessionId(requireNotNull(session.snapshot().sessionId))

        session.close()

        assertTrue(runner.interrupted.isEmpty())
        assertTrue(sessionId in manager.liveSessionIds.value)
        val attached = manager.acquire(sessionId)
        assertTrue(attached.state.value.isExecuting)
        attached.release()

        client.close()
        assertTrue(runner.interrupted.isEmpty())
        manager.close()
        assertEquals(listOf(sessionId), runner.interrupted)
    }

    @Test
    fun closingBorrowedClientLeavesManagerUsable() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val store = InMemoryAgentPersistence()
        val runner = BlockingRunner()
        val manager = DefaultAgentSessionManager(runner, store, dispatcher)
        val client = createChatbotClient(
            sessionManager = manager,
            requestFactory = DefaultChatbotRequestFactory(),
            sessionDispatcher = dispatcher,
        )
        val session = client.createSession(testChatbotConfiguration())
        session.send("background run")
        runCurrent()
        val sessionId = AgentSessionId(requireNotNull(session.snapshot().sessionId))

        client.close()

        assertTrue(runner.interrupted.isEmpty())
        val lateLease = manager.acquire(sessionId)
        assertTrue(lateLease.isAttached)
        assertTrue(lateLease.state.value.isExecuting)
        lateLease.release()
        manager.close()
        assertEquals(listOf(sessionId), runner.interrupted)
    }

    @Test
    fun closingOwnedClientInterruptsManagedRoot() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val store = InMemoryAgentPersistence()
        val runner = BlockingRunner()
        val client = testClient(runner, store, dispatcher)
        val session = client.createSession(testChatbotConfiguration())
        session.send("owned run")
        runCurrent()
        val sessionId = AgentSessionId(requireNotNull(session.snapshot().sessionId))

        client.close()

        assertEquals(listOf(sessionId), runner.interrupted)
    }

    @Test
    fun cancellationDuringFacadeConstructionReleasesTheAcquiredLease() = runTest {
        val manager = CancellingHandoffManager(HandoffCancellationPoint.STATE_READ)
        val client = createChatbotClient(
            sessionManager = manager,
            requestFactory = DefaultChatbotRequestFactory(),
            sessionDispatcher = StandardTestDispatcher(testScheduler),
        )

        val creation = async { client.createSession(testChatbotConfiguration()) }
        runCurrent()

        assertTrue(creation.isCancelled)
        assertFailsWith<kotlin.coroutines.cancellation.CancellationException> { creation.await() }
        assertEquals(1, manager.lease.releaseCount)
        client.close()
    }

    @Test
    fun cancellationDuringResumeHandoffDetachesWithoutCancellingCanonicalExecution() = runTest {
        val manager = CancellingHandoffManager(HandoffCancellationPoint.RESUME)
        val client = createChatbotClient(
            sessionManager = manager,
            requestFactory = DefaultChatbotRequestFactory(),
            sessionDispatcher = StandardTestDispatcher(testScheduler),
        )

        val resume = async { client.resumeSession(manager.sessionId.value) }
        runCurrent()

        assertTrue(resume.isCancelled)
        assertFailsWith<kotlin.coroutines.cancellation.CancellationException> { resume.await() }
        assertEquals(1, manager.lease.resumeCount)
        assertEquals(1, manager.lease.releaseCount)
        assertEquals(0, manager.lease.cancelCount)
        assertEquals(0, manager.lease.interruptCount)
        client.close()
    }

    @Test
    fun closeCannotPassAnAdmittedOperationBeforeFacadeDelivery() = runTest {
        val manager = CancellingHandoffManager(HandoffCancellationPoint.NONE)
        val closeFinished = CompletableDeferred<Result<Unit>>()
        val closeScopeJob = Job()
        val closeScope = CoroutineScope(closeScopeJob + Dispatchers.Unconfined)
        var cleanupObservedDeliveredLookup = false
        lateinit var client: ChatbotClient
        lateinit var lookupContext: CallbackOnJobLookupContext
        lookupContext = CallbackOnJobLookupContext(
            triggerAt = 2,
            onTrigger = {
                closeScope.launch(start = CoroutineStart.UNDISPATCHED) {
                    closeFinished.complete(runCatching { client.close() })
                }
            },
        )
        client = ChatbotClient(
            requestFactory = DefaultChatbotRequestFactory(),
            sessionManager = manager,
            ownsSessionManager = false,
            closeResources = {
                cleanupObservedDeliveredLookup = lookupContext.triggerReturned
            },
            sessionDispatcher = StandardTestDispatcher(testScheduler),
        )
        var creationResult: Result<ChatbotSession>? = null

        suspend { client.createSession(testChatbotConfiguration()) }.startCoroutine(
            object : Continuation<ChatbotSession> {
                override val context: CoroutineContext = lookupContext

                override fun resumeWith(result: Result<ChatbotSession>) {
                    creationResult = result
                }
            },
        )
        runCurrent()

        assertTrue(checkNotNull(creationResult).isSuccess)
        closeFinished.await().getOrThrow()
        assertTrue(cleanupObservedDeliveredLookup)
        assertEquals(2, lookupContext.triggerAtLookup)
        assertEquals(1, manager.lease.releaseCount)
        closeScopeJob.cancel()
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
                    userErrorCode = "weather-unavailable",
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

    @Test
    fun failedPersistenceDeleteStillClosesTheInvalidatedSessionFacades() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val backingStore = InMemoryAgentPersistence()
        val persistence = FailingDestructivePersistence(
            delegate = backingStore,
            failDelete = true,
        )
        val client = testClient(
            runner = CompletingRunner(backingStore),
            store = persistence,
            dispatcher = dispatcher,
        )
        val deleted = client.createSession(testChatbotConfiguration())
        val retained = client.createSession(testChatbotConfiguration())
        deleted.send("delete me")
        retained.send("keep me")
        advanceUntilIdle()
        val deletedId = AgentSessionId(requireNotNull(deleted.snapshot().sessionId))

        val failure = assertFailsWith<ChatbotException> {
            client.deleteSession(deletedId.value)
        }

        assertEquals(ChatbotFailure.STORAGE, failure.failure)
        assertEquals(ChatbotInvalidationScope.SESSION, failure.invalidationScope)
        assertEquals(1, persistence.deleteCalls)
        assertTrue(backingStore.load(deletedId) != null)
        assertEquals(
            ChatbotFailure.CLOSED,
            assertFailsWith<ChatbotException> { deleted.send("stale facade") }.failure,
        )
        retained.send("still attached")
        advanceUntilIdle()

        val replacement = client.restoreSession(deletedId.value)
        assertEquals(deletedId.value, replacement.snapshot().sessionId)
        replacement.close()
        retained.close()
        client.close()
    }

    @Test
    fun failedPersistenceClearStillClosesEveryInvalidatedSessionFacade() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val backingStore = InMemoryAgentPersistence()
        val persistence = FailingDestructivePersistence(
            delegate = backingStore,
            failClear = true,
        )
        val client = testClient(
            runner = CompletingRunner(backingStore),
            store = persistence,
            dispatcher = dispatcher,
        )
        val first = client.createSession(testChatbotConfiguration())
        val second = client.createSession(testChatbotConfiguration())
        first.send("first")
        second.send("second")
        advanceUntilIdle()
        val firstId = AgentSessionId(requireNotNull(first.snapshot().sessionId))
        val secondId = AgentSessionId(requireNotNull(second.snapshot().sessionId))

        val failure = assertFailsWith<ChatbotException> { client.clearHistory() }

        assertEquals(ChatbotFailure.STORAGE, failure.failure)
        assertEquals(ChatbotInvalidationScope.ALL_SESSIONS, failure.invalidationScope)
        assertEquals(1, persistence.clearCalls)
        assertTrue(backingStore.load(firstId) != null)
        assertTrue(backingStore.load(secondId) != null)
        assertEquals(
            ChatbotFailure.CLOSED,
            assertFailsWith<ChatbotException> { first.send("stale first facade") }.failure,
        )
        assertEquals(
            ChatbotFailure.CLOSED,
            assertFailsWith<ChatbotException> { second.send("stale second facade") }.failure,
        )

        val replacement = client.restoreSession(firstId.value)
        assertEquals(firstId.value, replacement.snapshot().sessionId)
        replacement.close()
        client.close()
    }

    private fun testClient(
        runner: AgentRunner,
        store: AgentPersistence,
        dispatcher: CoroutineDispatcher = Dispatchers.Default,
        closeResources: suspend () -> Unit = {},
        requestFactory: ChatbotRequestFactory = DefaultChatbotRequestFactory(),
    ): ChatbotClient = createChatbotClient(
        runner = runner,
        requestFactory = requestFactory,
        persistence = store,
        closeResources = closeResources,
        sessionDispatcher = dispatcher,
    )

    private class FailingDestructivePersistence(
        private val delegate: AgentPersistence,
        private val failDelete: Boolean = false,
        private val failClear: Boolean = false,
    ) : AgentPersistence by delegate {
        var deleteCalls: Int = 0
            private set
        var clearCalls: Int = 0
            private set

        override suspend fun deleteSession(sessionId: AgentSessionId) {
            deleteCalls += 1
            if (failDelete) error("synthetic delete failure")
            delegate.deleteSession(sessionId)
        }

        override suspend fun clear() {
            clearCalls += 1
            if (failClear) error("synthetic clear failure")
            delegate.clear()
        }
    }

    private class CompletingRunner(
        private val store: InMemoryAgentPersistence,
        private val recoveryDisposition: AgentRecoveryDisposition =
            AgentRecoveryDisposition.NOT_FOUND,
        private val recoveryStatus: AgentStatus? = null,
        private val recoveryRunId: AgentRunId? = null,
    ) : TestAgentRunner() {
        var runCount = 0
        var resumeCount = 0
        var inspectCount = 0
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
            resumeCount += 1
            val snapshot = requireNotNull(store.load(sessionId)?.snapshot)
            val completed = snapshot.state.copy(
                status = AgentStatus.COMPLETED,
                stopReason = StopReason.COMPLETED,
            )
            store.commit(snapshot.copy(state = completed, interruption = null), checkpoint = null)
            emit(AgentEvent.Started(sessionId, snapshot.runId))
            emit(AgentEvent.Completed(sessionId, completed))
        }

        override suspend fun cancel(sessionId: AgentSessionId) = Unit

        override suspend fun inspectRecovery(sessionId: AgentSessionId): AgentRecoveryInfo {
            inspectCount += 1
            val snapshot = store.load(sessionId)?.snapshot
            return AgentRecoveryInfo(
                sessionId = sessionId,
                runId = recoveryRunId ?: snapshot?.runId,
                disposition = recoveryDisposition,
                status = recoveryStatus ?: snapshot?.state?.status,
                state = snapshot?.state?.let { state ->
                    recoveryStatus?.let { status ->
                        state.copy(
                            status = status,
                            stopReason = if (status == AgentStatus.COMPLETED) {
                                StopReason.COMPLETED
                            } else {
                                state.stopReason
                            },
                        )
                    } ?: state
                },
                interruption = snapshot?.interruption ?: if (
                    recoveryDisposition == AgentRecoveryDisposition.RESUMABLE
                ) {
                    AgentInterruption(AgentInterruptionReason.ORPHANED)
                } else {
                    null
                },
            )
        }
    }

    private class BlockingRunner : TestAgentRunner() {
        val requests = mutableListOf<AgentRequest>()
        val cancelled = mutableListOf<AgentSessionId>()
        val interrupted = mutableListOf<AgentSessionId>()
        private val activeJobs = mutableMapOf<AgentSessionId, Job>()

        override fun run(request: AgentRequest): Flow<AgentEvent> = flow {
            activeJobs[request.sessionId] = currentCoroutineContext()[Job]!!
            try {
                requests += request
                emit(AgentEvent.Started(request.sessionId, TEST_RUN_ID))
                awaitCancellation()
            } finally {
                activeJobs.remove(request.sessionId)
            }
        }

        override suspend fun resume(sessionId: AgentSessionId): Flow<AgentEvent> = flow {
            activeJobs[sessionId] = currentCoroutineContext()[Job]!!
            try {
                awaitCancellation()
            } finally {
                activeJobs.remove(sessionId)
            }
        }

        override suspend fun cancel(sessionId: AgentSessionId) {
            cancelled += sessionId
            activeJobs[sessionId]?.cancelAndJoin()
        }

        override suspend fun interrupt(sessionId: AgentSessionId): AgentRecoveryInfo {
            interrupted += sessionId
            activeJobs[sessionId]?.cancelAndJoin()
            return AgentRecoveryInfo(
                sessionId = sessionId,
                runId = TEST_RUN_ID,
                disposition = AgentRecoveryDisposition.RESUMABLE,
                status = AgentStatus.INTERRUPTED,
                interruption = AgentInterruption(AgentInterruptionReason.HOST_REQUESTED),
            )
        }
    }

    private enum class HandoffCancellationPoint {
        NONE,
        STATE_READ,
        RESUME,
    }

    private class CancellingHandoffManager(
        private val cancellationPoint: HandoffCancellationPoint,
    ) : AgentSessionManager {
        val sessionId = AgentSessionId("cancelled-handoff-${cancellationPoint.name.lowercase()}")
        lateinit var lease: CancellingHandoffLease
            private set

        private val mutableLiveSessionIds = MutableStateFlow<Set<AgentSessionId>>(emptySet())
        override val liveSessionIds: StateFlow<Set<AgentSessionId>> = mutableLiveSessionIds

        override suspend fun create(sessionId: AgentSessionId): AgentSessionLease =
            newLease(sessionId)

        override suspend fun acquire(sessionId: AgentSessionId): AgentSessionLease {
            assertEquals(this.sessionId, sessionId)
            return newLease(sessionId)
        }

        override suspend fun listSessions(): List<AgentSessionSnapshot> = emptyList()
        override suspend fun delete(sessionId: AgentSessionId) = Unit
        override suspend fun clear() = Unit
        override suspend fun close() = Unit

        private suspend fun newLease(sessionId: AgentSessionId): AgentSessionLease {
            lease = CancellingHandoffLease(
                sessionId = sessionId,
                cancellationPoint = cancellationPoint,
                handoffJob = if (cancellationPoint == HandoffCancellationPoint.NONE) {
                    Job()
                } else {
                    currentCoroutineContext()[Job]!!
                },
            )
            mutableLiveSessionIds.value = setOf(sessionId)
            return lease
        }
    }

    private class CancellingHandoffLease(
        override val sessionId: AgentSessionId,
        private val cancellationPoint: HandoffCancellationPoint,
        private val handoffJob: Job,
    ) : AgentSessionLease {
        private val request = AgentRequest(
            sessionId = sessionId,
            messages = listOf(
                AgentMessage(role = MessageRole.USER, parts = listOf(TextPart("resume me"))),
            ),
            model = ModelDescriptor("test", "test-model"),
        )
        private val runtime = MutableStateFlow(
            if (cancellationPoint == HandoffCancellationPoint.RESUME) {
                val interrupted = AgentStateSnapshot(
                    messages = request.messages,
                    status = AgentStatus.INTERRUPTED,
                    stopReason = StopReason.INTERRUPTED,
                )
                AgentSessionRuntimeSnapshot(
                    revision = 0L,
                    sessionId = sessionId,
                    request = request,
                    runId = TEST_RUN_ID,
                    state = interrupted,
                    phase = AgentSessionPhase.RESUMABLE,
                    recovery = AgentRecoveryInfo(
                        sessionId = sessionId,
                        runId = TEST_RUN_ID,
                        disposition = AgentRecoveryDisposition.RESUMABLE,
                        status = AgentStatus.INTERRUPTED,
                        state = interrupted,
                        interruption = AgentInterruption(AgentInterruptionReason.ORPHANED),
                    ),
                )
            } else {
                AgentSessionRuntimeSnapshot(revision = 0L, sessionId = sessionId)
            },
        )
        private val edgeEvents = MutableSharedFlow<AgentEvent>()
        private var cancelOnStateRead = cancellationPoint == HandoffCancellationPoint.STATE_READ
        private var attached = true

        var resumeCount = 0
            private set
        var releaseCount = 0
            private set
        var cancelCount = 0
            private set
        var interruptCount = 0
            private set

        override val state: StateFlow<AgentSessionRuntimeSnapshot>
            get() {
                if (cancelOnStateRead) {
                    cancelOnStateRead = false
                    handoffJob.cancel()
                }
                return runtime
            }
        override val events: SharedFlow<AgentEvent> = edgeEvents
        override val isAttached: Boolean
            get() = attached

        override suspend fun start(request: AgentRequest) = Unit

        override suspend fun resume() {
            resumeCount += 1
            if (cancellationPoint == HandoffCancellationPoint.RESUME) handoffJob.cancel()
        }

        override suspend fun interrupt(): AgentRecoveryInfo {
            interruptCount += 1
            return requireNotNull(runtime.value.recovery)
        }

        override suspend fun inspectRecovery(): AgentRecoveryInfo =
            requireNotNull(runtime.value.recovery)

        override suspend fun cancel() {
            cancelCount += 1
        }

        override suspend fun replaceIdleRequest(request: AgentRequest) = Unit
        override suspend fun awaitIdle() = Unit

        override suspend fun release() {
            if (attached) {
                attached = false
                releaseCount += 1
            }
        }
    }

    private class CallbackOnJobLookupContext(
        private val triggerAt: Int,
        private val onTrigger: () -> Unit,
        private val delegate: Job = Job(),
    ) : CoroutineContext {
        var jobLookups: Int = 0
            private set
        var triggerAtLookup: Int = 0
            private set
        var triggerReturned: Boolean = false
            private set

        override fun <R> fold(
            initial: R,
            operation: (R, CoroutineContext.Element) -> R,
        ): R = operation(initial, delegate)

        @Suppress("UNCHECKED_CAST")
        override fun <E : CoroutineContext.Element> get(
            key: CoroutineContext.Key<E>,
        ): E? {
            if (key !== Job) return null
            jobLookups += 1
            if (jobLookups == triggerAt) {
                triggerAtLookup = jobLookups
                onTrigger()
                triggerReturned = true
            }
            return delegate as E
        }

        override fun minusKey(key: CoroutineContext.Key<*>): CoroutineContext =
            if (key === Job) EmptyCoroutineContext else this
    }
}
