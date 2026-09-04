package saien.magrathea.chatbot

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.test.Test
import saien.magrathea.core.AgentCheckpoint
import saien.magrathea.core.AgentEvent
import saien.magrathea.core.AgentFailureCode
import saien.magrathea.core.AgentMessage
import saien.magrathea.core.AgentPersistence
import saien.magrathea.core.AgentPersistenceRecord
import saien.magrathea.core.AgentInterruption
import saien.magrathea.core.AgentInterruptionReason
import saien.magrathea.core.AgentRecoveryDisposition
import saien.magrathea.core.AgentRecoveryInfo
import saien.magrathea.core.AgentRequest
import saien.magrathea.core.AgentResumeCursor
import saien.magrathea.core.AgentResumePhase
import saien.magrathea.core.AgentRunner
import saien.magrathea.core.AgentRunId
import saien.magrathea.core.AgentSessionId
import saien.magrathea.core.AgentSessionSnapshot
import saien.magrathea.core.AgentStateSnapshot
import saien.magrathea.core.AgentStatus
import saien.magrathea.core.MessageRole
import saien.magrathea.core.ModelDescriptor
import saien.magrathea.core.ProviderInterruption
import saien.magrathea.core.ProviderInterruptionPhase
import saien.magrathea.core.StopReason
import saien.magrathea.core.TextPart
import saien.magrathea.core.ToolCallPart
import saien.magrathea.runtime.AgentSessionErrorCode
import saien.magrathea.runtime.AgentSessionException
import saien.magrathea.runtime.InMemoryAgentPersistence

@OptIn(ExperimentalCoroutinesApi::class)
class ChatbotControllerContractTest {
    @Test
    fun secondMessage_reusesChatSessionId() = runTest {
        val runner = ImmediateRunner()
        val fixture = controller(runner, this)
        val controller = fixture.controller

        controller.sendMessage("first")
        advanceUntilIdle()
        controller.sendMessage("second")
        advanceUntilIdle()

        assertEquals(2, runner.requests.size)
        assertEquals(runner.requests.first().sessionId, runner.requests.last().sessionId)
        assertEquals(runner.requests.first().sessionId.value, controller.state.value.sessionId)
        fixture.close()
    }

    @Test
    fun regenerate_preservesChatSessionId() = runTest {
        val runner = ImmediateRunner()
        val fixture = controller(runner, this)
        val controller = fixture.controller
        controller.sendMessage("first")
        advanceUntilIdle()
        val userId = controller.state.value.messages.first { it.role == ChatbotMessageRole.USER }.id
        val sessionId = requireNotNull(controller.state.value.sessionId)

        controller.regenerate(userId)
        advanceUntilIdle()

        assertEquals(sessionId, runner.requests.last().sessionId.value)
        assertEquals(sessionId, controller.state.value.sessionId)
        fixture.close()
    }

    @Test
    fun loadHistory_thenSend_usesLoadedSession() = runTest {
        val loadedSession = AgentSessionId("loaded-chat-session")
        val runner = ImmediateRunner()
        val fixture = restoredController(
            runner = runner,
            scope = this,
            sessionId = loadedSession,
            messages = listOf(AgentMessage(role = MessageRole.USER, parts = listOf(TextPart("history")))),
        )
        val controller = fixture.controller

        controller.sendMessage("next")
        advanceUntilIdle()

        assertEquals(loadedSession, runner.requests.single().sessionId)
        assertEquals(loadedSession.value, controller.state.value.sessionId)
        fixture.close()
    }

    @Test
    fun loadHistory_reconstructsUnresolvedToolCallAsInterrupted() = runTest {
        val fixture = restoredController(
            runner = ImmediateRunner(),
            scope = this,
            messages = listOf(
                AgentMessage(
                    id = "assistant-tool",
                    role = MessageRole.ASSISTANT,
                    parts = listOf(ToolCallPart("call-1", "lookup", buildJsonObject { })),
                ),
            ),
        )
        val controller = fixture.controller

        val activity = controller.state.value.toolActivities.single()
        assertEquals(ChatbotToolActivityKey("assistant-tool", 0), activity.key)
        assertEquals(ChatbotToolActivityStatus.INTERRUPTED, activity.status)
        fixture.close()
    }

    @Test
    fun cancel_marksAnExecutingToolActivityAsCancelled() = runTest {
        val fixture = controller(ToolWaitingRunner(), this)
        val controller = fixture.controller
        controller.sendMessage("use a Tool")
        runCurrent()

        assertEquals(ChatbotToolActivityStatus.RUNNING, controller.state.value.toolActivities.single().status)

        controller.cancel()

        assertEquals(ChatbotStatus.CANCELLED, controller.state.value.status)
        assertEquals(ChatbotToolActivityStatus.CANCELLED, controller.state.value.toolActivities.single().status)
        fixture.close()
    }

    @Test
    fun cancel_terminallyAbandonsAnInactiveInterruptedRun() = runTest {
        val runner = ImmediateRunner(recoverable = true)
        val sessionId = AgentSessionId("inactive-interrupted")
        val fixture = restoredController(
            runner = runner,
            scope = this,
            sessionId = sessionId,
            messages = listOf(
                AgentMessage(role = MessageRole.USER, parts = listOf(TextPart("recover me"))),
            ),
            status = ChatbotStatus.INTERRUPTED,
            interruption = ChatbotInterruption(
                reason = ChatbotInterruptionReason.PROVIDER_FAILURE,
                provider = ChatbotProviderInterruption(
                    failure = ChatbotFailure.NETWORK,
                    phase = ChatbotProviderInterruptionPhase.AFTER_FIRST_EVENT,
                ),
                occurredAtEpochMs = 1L,
            ),
        )
        val controller = fixture.controller

        controller.cancel()

        assertEquals(listOf(sessionId), runner.cancelled)
        assertEquals(ChatbotStatus.CANCELLED, controller.state.value.status)
        assertEquals(null, controller.state.value.interruption)
        fixture.close()
    }

    @Test
    fun newSend_cancelsRunnerNotOnlyCollector() = runTest {
        val runner = ReplacementRunner()
        val fixture = controller(runner, this)
        val controller = fixture.controller
        controller.sendMessage("first")
        runCurrent()
        val firstSession = runner.requests.single().sessionId

        controller.sendMessage("second")
        advanceUntilIdle()

        assertEquals(listOf(firstSession), runner.cancelled)
        assertEquals(2, runner.requests.size)
        fixture.close()
    }

    @Test
    fun eventForDifferentSession_isIgnored() = runTest {
        val stale = AgentMessage(
            id = "stale-assistant",
            role = MessageRole.ASSISTANT,
            parts = listOf(TextPart("must-not-appear")),
        )
        val runner = ForeignSessionEventRunner(stale)
        val fixture = controller(runner, this)
        val controller = fixture.controller

        controller.sendMessage("hello")
        advanceUntilIdle()

        assertFalse(controller.state.value.messages.any { it.id == stale.id })
        assertFalse(controller.state.value.messages.any { it.text == "must-not-appear" })
        fixture.close()
    }

    @Test
    fun cancelAfterTerminal_doesNotCancelRunnerOrOverwriteCompletedState() = runTest {
        val runner = ImmediateRunner()
        val fixture = controller(runner, this)
        val controller = fixture.controller
        controller.sendMessage("hello")
        advanceUntilIdle()
        assertEquals(ChatbotStatus.COMPLETED, controller.state.value.status)

        controller.cancel()

        assertTrue(runner.cancelled.isEmpty())
        assertEquals(ChatbotStatus.COMPLETED, controller.state.value.status)
        fixture.close()
    }

    @Test
    fun requestFactoryThrows_conversationAndStateRemainConsistent() = runTest {
        val runner = ImmediateRunner()
        var factoryCalls = 0
        val factory = ChatbotRequestFactory { context ->
            factoryCalls += 1
            if (factoryCalls == 2) error("factory failed")
            AgentRequest(
                messages = context.messages,
                model = ModelDescriptor(provider = "chat-contract", model = "chat-contract"),
            )
        }
        val fixture = controller(
            runner = runner,
            scope = this,
            requestFactory = factory,
        )
        val controller = fixture.controller
        controller.sendMessage("first")
        advanceUntilIdle()
        val before = controller.state.value

        try {
            controller.sendMessage("must-not-commit")
            fail("Expected request factory failure")
        } catch (error: IllegalStateException) {
            assertEquals("factory failed", error.message)
        }

        assertEquals(before, controller.state.value)
        assertFalse(controller.state.value.messages.any { it.text == "must-not-commit" })
        assertEquals(1, runner.requests.size)
        fixture.close()
    }

    @Test
    fun requestFactoryFailureWhileReplacingActiveRunLeavesCancelledTruthfulState() = runTest {
        val runner = ReplacementRunner()
        var factoryCalls = 0
        val fixture = controller(
            runner = runner,
            scope = this,
            requestFactory = ChatbotRequestFactory { context ->
                factoryCalls += 1
                if (factoryCalls == 2) error("factory failed")
                AgentRequest(
                    messages = context.messages,
                    model = ModelDescriptor(provider = "chatbot-contract", model = "chatbot-contract"),
                )
            },
        )
        val controller = fixture.controller
        controller.sendMessage("first")
        runCurrent()
        val activeSession = runner.requests.single().sessionId

        try {
            controller.sendMessage("must-not-commit")
            fail("Expected request factory failure")
        } catch (error: IllegalStateException) {
            assertEquals("factory failed", error.message)
        }

        assertEquals(listOf(activeSession), runner.cancelled)
        assertEquals(ChatbotStatus.CANCELLED, controller.state.value.status)
        assertFalse(controller.state.value.messages.any { it.text == "must-not-commit" })
        assertEquals(1, runner.requests.size)
        fixture.close()
    }

    @Test
    fun runnerThrows_stateBecomesFailedWithoutEscapingControllerScope() = runTest {
        val runner = ThrowingRunner()
        val fixture = controller(runner, this)
        val controller = fixture.controller

        controller.sendMessage("hello")
        advanceUntilIdle()

        assertEquals(ChatbotStatus.FAILED, controller.state.value.status)
        assertEquals(ChatbotFailure.OPERATION_FAILED, controller.state.value.failure)
        assertFalse(controller.state.value.toString().contains("runner exploded"))
        assertEquals(listOf("hello"), controller.state.value.messages.map { it.text })
        fixture.close()
    }

    @Test
    fun configurationUpdateDoesNotChangeObservableStateWhenPersistenceFails() = runTest {
        val delegate = InMemoryAgentPersistence()
        val sessionId = AgentSessionId("configuration-storage-failure")
        val messages = listOf(
            AgentMessage(role = MessageRole.USER, parts = listOf(TextPart("hello"))),
        )
        delegate.commit(terminalSnapshot(sessionId, messages), checkpoint = null)
        val fixture = ManagedChatbotControllerFixture.create(
            runner = ImmediateRunner(),
            scope = this,
            configuration = testChatbotConfiguration("chat-contract", "chat-contract"),
            persistence = FailingCommitPersistence(delegate),
            sessionId = sessionId,
            restore = true,
        )
        val controller = fixture.controller
        val original = controller.state.value.configuration
        val updated = testChatbotConfiguration("openai", "openai-model")

        val failure = assertFailsWith<AgentSessionException> { controller.updateConfiguration(updated) }

        assertEquals(AgentSessionErrorCode.STORAGE, failure.code)
        assertEquals(original, controller.state.value.configuration)
        fixture.close()
    }

    @Test
    fun close_detachesWithoutInterruptingAndRootCloseOwnsInterruption() = runTest {
        val runner = ReplacementRunner()
        val fixture = controller(runner, this)
        val controller = fixture.controller
        controller.sendMessage("hello")
        runCurrent()
        val sessionId = runner.requests.single().sessionId

        controller.close()
        controller.close()

        assertTrue(runner.interrupted.isEmpty())
        try {
            controller.sendMessage("after-close")
            fail("Expected closed controller to reject commands")
        } catch (error: IllegalStateException) {
            assertEquals("ChatbotController is closed", error.message)
        }

        fixture.manager.close()
        assertEquals(listOf(sessionId), runner.interrupted)
    }

    @Test
    fun interruptWaitsUntilANewRunHasEnteredTheRunner() = runTest {
        val runner = ReplacementRunner()
        val fixture = controller(runner, this)
        val controller = fixture.controller
        controller.sendMessage("hello")

        controller.interrupt()

        assertEquals(1, runner.requests.size)
        assertEquals(listOf(runner.requests.single().sessionId), runner.interrupted)
        assertEquals(ChatbotStatus.INTERRUPTED, controller.state.value.status)
        fixture.close()
    }

    @Test
    fun interrupt_restoresTheAuthoritativeCheckpointInsteadOfKeepingProvisionalOutput() = runTest {
        val runner = ProvisionalOutputRunner()
        val fixture = controller(runner, this)
        val controller = fixture.controller
        controller.sendMessage("hello")
        runCurrent()
        assertEquals(2, controller.state.value.messages.size)

        controller.interrupt()

        assertEquals(
            listOf("hello"),
            controller.state.value.messages.map(ChatbotMessageSnapshot::text),
        )
        assertEquals(ChatbotStatus.INTERRUPTED, controller.state.value.status)
        assertEquals(
            ChatbotInterruptionReason.HOST_REQUESTED,
            controller.state.value.interruption?.reason,
        )
        fixture.close()
    }

    @Test
    fun blankMessageWithoutAttachment_isRejectedBeforeRequestCreation() = runTest {
        val runner = ImmediateRunner()
        val fixture = controller(runner, this)
        val controller = fixture.controller

        try {
            controller.sendMessage("   ")
            fail("Expected blank chat input to be rejected")
        } catch (_: IllegalArgumentException) {
        }

        assertTrue(runner.requests.isEmpty())
        assertTrue(controller.state.value.messages.isEmpty())
        fixture.close()
    }

    @Test
    fun regenerate_rejectsAssistantMessageWithoutStartingNewRun() = runTest {
        val runner = ImmediateRunner()
        val fixture = controller(runner, this)
        val controller = fixture.controller
        controller.sendMessage("hello")
        advanceUntilIdle()
        val assistantId = controller.state.value.messages.first { it.role == ChatbotMessageRole.ASSISTANT }.id

        try {
            controller.regenerate(assistantId)
            fail("Expected regenerate to require a user message")
        } catch (_: IllegalArgumentException) {
        }

        assertEquals(1, runner.requests.size)
        assertEquals(ChatbotStatus.COMPLETED, controller.state.value.status)
        fixture.close()
    }

    private suspend fun controller(
        runner: AgentRunner,
        scope: CoroutineScope,
        requestFactory: ChatbotRequestFactory = DefaultChatbotRequestFactory(),
    ): ManagedChatbotControllerFixture =
        ManagedChatbotControllerFixture.create(
            runner = runner,
            scope = scope,
            requestFactory = requestFactory,
            configuration = testChatbotConfiguration("chat-contract", "chat-contract"),
        )

    private suspend fun restoredController(
        runner: AgentRunner,
        scope: CoroutineScope,
        sessionId: AgentSessionId = AgentSessionId.create(),
        messages: List<AgentMessage>,
        status: ChatbotStatus = ChatbotStatus.COMPLETED,
        interruption: ChatbotInterruption? = null,
    ): ManagedChatbotControllerFixture {
        val persistence = InMemoryAgentPersistence()
        val state = AgentStateSnapshot(
            messages = messages,
            status = when (status) {
                ChatbotStatus.INTERRUPTED -> AgentStatus.INTERRUPTED
                else -> AgentStatus.COMPLETED
            },
            stopReason = when (status) {
                ChatbotStatus.INTERRUPTED -> StopReason.INTERRUPTED
                else -> StopReason.COMPLETED
            },
        )
        val snapshot = terminalSnapshot(sessionId, messages).copy(
            state = state,
            interruption = if (status == ChatbotStatus.INTERRUPTED) {
                interruption?.toAgentInterruption()
                    ?: AgentInterruption(AgentInterruptionReason.ORPHANED)
            } else {
                null
            },
        )
        persistence.commit(
            snapshot,
            checkpoint = if (status == ChatbotStatus.INTERRUPTED) {
                AgentCheckpoint(
                    sessionId,
                    snapshot.runId,
                    AgentResumeCursor(0, AgentResumePhase.MODEL_PENDING),
                    state,
                )
            } else {
                null
            },
        )
        return ManagedChatbotControllerFixture.create(
            runner = runner,
            scope = scope,
            configuration = testChatbotConfiguration("chat-contract", "chat-contract"),
            persistence = persistence,
            sessionId = sessionId,
            restore = true,
        )
    }

    private fun terminalSnapshot(
        sessionId: AgentSessionId,
        messages: List<AgentMessage>,
    ): AgentSessionSnapshot {
        val request = AgentRequest(
            sessionId = sessionId,
            messages = messages,
            model = ModelDescriptor("chat-contract", "chat-contract"),
        )
        return AgentSessionSnapshot(
            sessionId = sessionId,
            runId = AgentRunId("stored-${sessionId.value}"),
            request = request,
            state = AgentStateSnapshot(
                messages = messages,
                status = AgentStatus.COMPLETED,
                stopReason = StopReason.COMPLETED,
            ),
        )
    }

    private fun ChatbotInterruption.toAgentInterruption(): AgentInterruption =
        AgentInterruption(
            reason = when (reason) {
                ChatbotInterruptionReason.HOST_REQUESTED -> AgentInterruptionReason.HOST_REQUESTED
                ChatbotInterruptionReason.PROVIDER_FAILURE -> AgentInterruptionReason.PROVIDER_FAILURE
                ChatbotInterruptionReason.ORPHANED -> AgentInterruptionReason.ORPHANED
            },
            provider = provider?.let {
                ProviderInterruption(
                    code = when (it.failure) {
                        ChatbotFailure.NETWORK -> AgentFailureCode.PROVIDER_NETWORK
                        ChatbotFailure.TIMEOUT -> AgentFailureCode.TIMEOUT
                        ChatbotFailure.RATE_LIMITED -> AgentFailureCode.PROVIDER_RATE_LIMIT
                        else -> AgentFailureCode.PROVIDER_SERVER
                    },
                    phase = when (it.phase) {
                        ChatbotProviderInterruptionPhase.BEFORE_FIRST_EVENT ->
                            ProviderInterruptionPhase.BEFORE_FIRST_EVENT
                        ChatbotProviderInterruptionPhase.AFTER_FIRST_EVENT ->
                            ProviderInterruptionPhase.AFTER_FIRST_EVENT
                    },
                    retryAtEpochMs = it.retryAtEpochMs,
                )
            },
            occurredAtEpochMs = occurredAtEpochMs,
        )

    private class FailingCommitPersistence(
        private val delegate: AgentPersistence,
    ) : AgentPersistence {
        override suspend fun commit(
            snapshot: AgentSessionSnapshot,
            checkpoint: AgentCheckpoint?,
        ): Unit = error("storage failed")

        override suspend fun load(sessionId: AgentSessionId): AgentPersistenceRecord? =
            delegate.load(sessionId)

        override suspend fun listSessions(): List<AgentSessionSnapshot> = delegate.listSessions()
        override suspend fun deleteSession(sessionId: AgentSessionId) = delegate.deleteSession(sessionId)
        override suspend fun clear() = delegate.clear()
    }

    private open class ImmediateRunner(
        private val recoverable: Boolean = false,
    ) : TestAgentRunner() {
        val requests = mutableListOf<AgentRequest>()
        val cancelled = mutableListOf<AgentSessionId>()

        override fun run(request: AgentRequest): Flow<AgentEvent> = flow {
            requests += request
            val assistant = AgentMessage(
                id = "assistant-${requests.size}",
                role = MessageRole.ASSISTANT,
                parts = listOf(TextPart("reply-${requests.size}")),
                stopReason = StopReason.COMPLETED,
            )
            emit(AgentEvent.Started(request.sessionId, TEST_RUN_ID))
            emit(
                AgentEvent.Completed(
                    request.sessionId,
                    AgentStateSnapshot(
                        messages = request.messages + assistant,
                        status = AgentStatus.COMPLETED,
                        stopReason = StopReason.COMPLETED,
                    ),
                ),
            )
        }

        override suspend fun resume(sessionId: AgentSessionId): Flow<AgentEvent> = flow { }

        override suspend fun cancel(sessionId: AgentSessionId) {
            cancelled += sessionId
        }

        override suspend fun inspectRecovery(sessionId: AgentSessionId): AgentRecoveryInfo =
            if (recoverable) {
                AgentRecoveryInfo(
                    sessionId = sessionId,
                    runId = AgentRunId("stored-${sessionId.value}"),
                    disposition = AgentRecoveryDisposition.RESUMABLE,
                    status = AgentStatus.INTERRUPTED,
                    interruption = AgentInterruption(AgentInterruptionReason.ORPHANED),
                )
            } else {
                super.inspectRecovery(sessionId)
            }
    }

    private class ReplacementRunner : TestAgentRunner() {
        val requests = mutableListOf<AgentRequest>()
        val cancelled = mutableListOf<AgentSessionId>()
        val interrupted = mutableListOf<AgentSessionId>()
        private val activeJobs = mutableMapOf<AgentSessionId, Job>()

        override fun run(request: AgentRequest): Flow<AgentEvent> = flow {
            activeJobs[request.sessionId] = currentCoroutineContext()[Job]!!
            try {
                requests += request
                emit(AgentEvent.Started(request.sessionId, TEST_RUN_ID))
                if (requests.size == 1) {
                    try {
                        awaitCancellation()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    }
                } else {
                    emit(
                        AgentEvent.Completed(
                            request.sessionId,
                            AgentStateSnapshot(
                                messages = request.messages,
                                status = AgentStatus.COMPLETED,
                                stopReason = StopReason.COMPLETED,
                            ),
                        ),
                    )
                }
            } finally {
                activeJobs.remove(request.sessionId)
            }
        }

        override suspend fun resume(sessionId: AgentSessionId): Flow<AgentEvent> = flow { }

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
                interruption = AgentInterruption(AgentInterruptionReason.HOST_REQUESTED),
            )
        }
    }

    private class ToolWaitingRunner : TestAgentRunner() {
        private val activeJobs = mutableMapOf<AgentSessionId, Job>()

        override fun run(request: AgentRequest): Flow<AgentEvent> = flow {
            activeJobs[request.sessionId] = currentCoroutineContext()[Job]!!
            try {
                val call = ToolCallPart("call-1", "lookup", buildJsonObject { })
                emit(AgentEvent.Started(request.sessionId, TEST_RUN_ID))
                emit(
                    AgentEvent.MessageEmitted(
                        request.sessionId,
                        AgentMessage(
                            id = "assistant-tool",
                            role = MessageRole.ASSISTANT,
                            parts = listOf(call),
                        ),
                    ),
                )
                emit(AgentEvent.ToolRequested(request.sessionId, call))
                awaitCancellation()
            } finally {
                activeJobs.remove(request.sessionId)
            }
        }

        override suspend fun resume(sessionId: AgentSessionId): Flow<AgentEvent> = flow { }

        override suspend fun cancel(sessionId: AgentSessionId) {
            activeJobs[sessionId]?.cancelAndJoin()
        }
    }

    private class ProvisionalOutputRunner : TestAgentRunner() {
        private lateinit var request: AgentRequest
        private var activeJob: Job? = null

        override fun run(request: AgentRequest): Flow<AgentEvent> = flow {
            activeJob = currentCoroutineContext()[Job]
            try {
                this@ProvisionalOutputRunner.request = request
                emit(AgentEvent.Started(request.sessionId, TEST_RUN_ID))
                emit(
                    AgentEvent.MessageEmitted(
                        request.sessionId,
                        AgentMessage(
                            id = "provisional-assistant",
                            role = MessageRole.ASSISTANT,
                            parts = listOf(TextPart("provisional")),
                        ),
                    ),
                )
                awaitCancellation()
            } finally {
                activeJob = null
            }
        }

        override suspend fun resume(sessionId: AgentSessionId): Flow<AgentEvent> = flow { }

        override suspend fun cancel(sessionId: AgentSessionId) {
            activeJob?.cancelAndJoin()
        }

        override suspend fun interrupt(sessionId: AgentSessionId): AgentRecoveryInfo {
            activeJob?.cancelAndJoin()
            val state = AgentStateSnapshot(
                messages = request.messages,
                status = AgentStatus.INTERRUPTED,
                stopReason = StopReason.INTERRUPTED,
            )
            return AgentRecoveryInfo(
                sessionId = sessionId,
                runId = TEST_RUN_ID,
                disposition = AgentRecoveryDisposition.RESUMABLE,
                status = state.status,
                state = state,
                interruption = AgentInterruption(AgentInterruptionReason.HOST_REQUESTED),
            )
        }
    }

    private class ForeignSessionEventRunner(
        private val stale: AgentMessage,
    ) : TestAgentRunner() {
        override fun run(request: AgentRequest): Flow<AgentEvent> = flow {
            emit(AgentEvent.Started(request.sessionId, TEST_RUN_ID))
            emit(AgentEvent.MessageEmitted(AgentSessionId("foreign-session"), stale))
        }

        override suspend fun resume(sessionId: AgentSessionId): Flow<AgentEvent> = flow { }

        override suspend fun cancel(sessionId: AgentSessionId) = Unit
    }

    private class ThrowingRunner : TestAgentRunner() {
        override fun run(request: AgentRequest): Flow<AgentEvent> = flow {
            error("runner exploded")
        }

        override suspend fun resume(sessionId: AgentSessionId): Flow<AgentEvent> = flow {
            error("runner exploded")
        }

        override suspend fun cancel(sessionId: AgentSessionId) = Unit
    }
}
