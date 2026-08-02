package saien.magrathea.chatbot

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
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
import saien.magrathea.core.AgentEvent
import saien.magrathea.core.AgentMessage
import saien.magrathea.core.AgentInterruption
import saien.magrathea.core.AgentInterruptionReason
import saien.magrathea.core.AgentRecoveryDisposition
import saien.magrathea.core.AgentRecoveryInfo
import saien.magrathea.core.AgentRequest
import saien.magrathea.core.AgentRunner
import saien.magrathea.core.AgentSessionId
import saien.magrathea.core.AgentStateSnapshot
import saien.magrathea.core.AgentStatus
import saien.magrathea.core.MessageRole
import saien.magrathea.core.ModelDescriptor
import saien.magrathea.core.StopReason
import saien.magrathea.core.TextPart
import saien.magrathea.core.ToolCallPart

@OptIn(ExperimentalCoroutinesApi::class)
class ChatbotControllerContractTest {
    @Test
    fun secondMessage_reusesChatSessionId() = runTest {
        val runner = ImmediateRunner()
        val controller = controller(runner, this)

        controller.sendMessage("first")
        advanceUntilIdle()
        controller.sendMessage("second")
        advanceUntilIdle()

        assertEquals(2, runner.requests.size)
        assertEquals(runner.requests.first().sessionId, runner.requests.last().sessionId)
        assertEquals(runner.requests.first().sessionId.value, controller.state.value.sessionId)
    }

    @Test
    fun regenerate_preservesChatSessionId() = runTest {
        val runner = ImmediateRunner()
        val controller = controller(runner, this)
        controller.sendMessage("first")
        advanceUntilIdle()
        val userId = controller.state.value.messages.first { it.role == ChatbotMessageRole.USER }.id
        val sessionId = requireNotNull(controller.state.value.sessionId)

        controller.regenerate(userId)
        advanceUntilIdle()

        assertEquals(sessionId, runner.requests.last().sessionId.value)
        assertEquals(sessionId, controller.state.value.sessionId)
    }

    @Test
    fun loadHistory_thenSend_usesLoadedSession() = runTest {
        val loadedSession = AgentSessionId("loaded-chat-session")
        val runner = ImmediateRunner()
        val controller = controller(runner, this)
        controller.loadHistory(
            messages = listOf(AgentMessage(role = MessageRole.USER, parts = listOf(TextPart("history")))),
            sessionId = loadedSession,
        )

        controller.sendMessage("next")
        advanceUntilIdle()

        assertEquals(loadedSession, runner.requests.single().sessionId)
        assertEquals(loadedSession.value, controller.state.value.sessionId)
    }

    @Test
    fun loadHistory_reconstructsUnresolvedToolCallAsInterrupted() = runTest {
        val controller = controller(ImmediateRunner(), this)
        controller.loadHistory(
            messages = listOf(
                AgentMessage(
                    id = "assistant-tool",
                    role = MessageRole.ASSISTANT,
                    parts = listOf(ToolCallPart("call-1", "lookup", buildJsonObject { })),
                ),
            ),
        )

        val activity = controller.state.value.toolActivities.single()
        assertEquals(ChatbotToolActivityKey("assistant-tool", 0), activity.key)
        assertEquals(ChatbotToolActivityStatus.INTERRUPTED, activity.status)
    }

    @Test
    fun cancel_marksAnExecutingToolActivityAsCancelled() = runTest {
        val controller = controller(ToolWaitingRunner(), this)
        controller.sendMessage("use a Tool")
        runCurrent()

        assertEquals(ChatbotToolActivityStatus.RUNNING, controller.state.value.toolActivities.single().status)

        controller.cancel()

        assertEquals(ChatbotStatus.CANCELLED, controller.state.value.status)
        assertEquals(ChatbotToolActivityStatus.CANCELLED, controller.state.value.toolActivities.single().status)
    }

    @Test
    fun cancel_terminallyAbandonsAnInactiveInterruptedRun() = runTest {
        val runner = ImmediateRunner()
        val sessionId = AgentSessionId("inactive-interrupted")
        val controller = controller(runner, this)
        controller.loadHistory(
            messages = listOf(
                AgentMessage(role = MessageRole.USER, parts = listOf(TextPart("recover me"))),
            ),
            sessionId = sessionId,
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

        controller.cancel()

        assertEquals(listOf(sessionId), runner.cancelled)
        assertEquals(ChatbotStatus.CANCELLED, controller.state.value.status)
        assertEquals(null, controller.state.value.interruption)
    }

    @Test
    fun newSend_cancelsRunnerNotOnlyCollector() = runTest {
        val runner = ReplacementRunner()
        val controller = controller(runner, this)
        controller.sendMessage("first")
        runCurrent()
        val firstSession = runner.requests.single().sessionId

        controller.sendMessage("second")
        advanceUntilIdle()

        assertEquals(listOf(firstSession), runner.cancelled)
        assertEquals(2, runner.requests.size)
    }

    @Test
    fun eventForDifferentSession_isIgnored() = runTest {
        val stale = AgentMessage(
            id = "stale-assistant",
            role = MessageRole.ASSISTANT,
            parts = listOf(TextPart("must-not-appear")),
        )
        val runner = ForeignSessionEventRunner(stale)
        val controller = controller(runner, this)

        controller.sendMessage("hello")
        advanceUntilIdle()

        assertFalse(controller.state.value.messages.any { it.id == stale.id })
        assertFalse(controller.state.value.messages.any { it.text == "must-not-appear" })
    }

    @Test
    fun cancelAfterTerminal_doesNotCancelRunnerOrOverwriteCompletedState() = runTest {
        val runner = ImmediateRunner()
        val controller = controller(runner, this)
        controller.sendMessage("hello")
        advanceUntilIdle()
        assertEquals(ChatbotStatus.COMPLETED, controller.state.value.status)

        controller.cancel()

        assertTrue(runner.cancelled.isEmpty())
        assertEquals(ChatbotStatus.COMPLETED, controller.state.value.status)
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
        val controller = ChatbotController(
            runner = runner,
            requestFactory = factory,
            initialConfiguration = testChatbotConfiguration("chat-contract", "chat-contract"),
            scope = this,
        )
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
    }

    @Test
    fun requestFactoryFailureWhileReplacingActiveRunLeavesCancelledTruthfulState() = runTest {
        val runner = ReplacementRunner()
        var factoryCalls = 0
        val controller = ChatbotController(
            runner = runner,
            requestFactory = ChatbotRequestFactory { context ->
                factoryCalls += 1
                if (factoryCalls == 2) error("factory failed")
                AgentRequest(
                    messages = context.messages,
                    model = ModelDescriptor(provider = "chatbot-contract", model = "chatbot-contract"),
                )
            },
            initialConfiguration = testChatbotConfiguration("chatbot-contract", "chatbot-contract"),
            scope = this,
        )
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
    }

    @Test
    fun runnerThrows_stateBecomesFailedWithoutEscapingControllerScope() = runTest {
        val runner = ThrowingRunner()
        val controller = controller(runner, this)

        controller.sendMessage("hello")
        advanceUntilIdle()

        assertEquals(ChatbotStatus.FAILED, controller.state.value.status)
        assertEquals(ChatbotFailure.OPERATION_FAILED, controller.state.value.failure)
        assertFalse(controller.state.value.toString().contains("runner exploded"))
        assertEquals(listOf("hello"), controller.state.value.messages.map { it.text })
    }

    @Test
    fun configurationUpdateDoesNotChangeObservableStateWhenPersistenceFails() = runTest {
        val controller = controller(ImmediateRunner(), this)
        controller.sendMessage("hello")
        advanceUntilIdle()
        val original = controller.state.value.configuration
        val updated = testChatbotConfiguration("openai", "openai-model")

        assertFailsWith<IllegalStateException> {
            controller.updateConfiguration(updated) { _, _ -> error("storage failed") }
        }

        assertEquals(original, controller.state.value.configuration)
    }

    @Test
    fun close_interruptsActiveRunner_isIdempotentAndRejectsFurtherCommands() = runTest {
        val runner = ReplacementRunner()
        val controller = controller(runner, this)
        controller.sendMessage("hello")
        runCurrent()
        val sessionId = runner.requests.single().sessionId

        controller.close()
        controller.close()

        assertEquals(listOf(sessionId), runner.interrupted)
        assertEquals(ChatbotStatus.INTERRUPTED, controller.state.value.status)
        try {
            controller.sendMessage("after-close")
            fail("Expected closed controller to reject commands")
        } catch (error: IllegalStateException) {
            assertEquals("ChatbotController is closed", error.message)
        }
    }

    @Test
    fun interruptWaitsUntilANewRunHasEnteredTheRunner() = runTest {
        val runner = ReplacementRunner()
        val controller = controller(runner, this)
        controller.sendMessage("hello")

        controller.interrupt()

        assertEquals(1, runner.requests.size)
        assertEquals(listOf(runner.requests.single().sessionId), runner.interrupted)
        assertEquals(ChatbotStatus.INTERRUPTED, controller.state.value.status)
    }

    @Test
    fun interrupt_restoresTheAuthoritativeCheckpointInsteadOfKeepingProvisionalOutput() = runTest {
        val runner = ProvisionalOutputRunner()
        val controller = controller(runner, this)
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
    }

    @Test
    fun blankMessageWithoutAttachment_isRejectedBeforeRequestCreation() = runTest {
        val runner = ImmediateRunner()
        val controller = controller(runner, this)

        try {
            controller.sendMessage("   ")
            fail("Expected blank chat input to be rejected")
        } catch (_: IllegalArgumentException) {
        }

        assertTrue(runner.requests.isEmpty())
        assertTrue(controller.state.value.messages.isEmpty())
    }

    @Test
    fun regenerate_rejectsAssistantMessageWithoutStartingNewRun() = runTest {
        val runner = ImmediateRunner()
        val controller = controller(runner, this)
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
    }

    private fun controller(runner: AgentRunner, scope: CoroutineScope): ChatbotController {
        return ChatbotController(
            runner = runner,
            requestFactory = DefaultChatbotRequestFactory(),
            initialConfiguration = testChatbotConfiguration("chat-contract", "chat-contract"),
            scope = scope,
        )
    }

    private open class ImmediateRunner : TestAgentRunner() {
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
                        stopReason = StopReason.COMPLETED,
                    ),
                ),
            )
        }

        override suspend fun resume(sessionId: AgentSessionId): Flow<AgentEvent> = flow { }

        override suspend fun cancel(sessionId: AgentSessionId) {
            cancelled += sessionId
        }
    }

    private class ReplacementRunner : TestAgentRunner() {
        val requests = mutableListOf<AgentRequest>()
        val cancelled = mutableListOf<AgentSessionId>()
        val interrupted = mutableListOf<AgentSessionId>()

        override fun run(request: AgentRequest): Flow<AgentEvent> = flow {
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
                        AgentStateSnapshot(messages = request.messages, stopReason = StopReason.COMPLETED),
                    ),
                )
            }
        }

        override suspend fun resume(sessionId: AgentSessionId): Flow<AgentEvent> = flow { }

        override suspend fun cancel(sessionId: AgentSessionId) {
            cancelled += sessionId
        }

        override suspend fun interrupt(sessionId: AgentSessionId): AgentRecoveryInfo {
            interrupted += sessionId
            return AgentRecoveryInfo(
                sessionId = sessionId,
                runId = TEST_RUN_ID,
                disposition = AgentRecoveryDisposition.RESUMABLE,
                interruption = AgentInterruption(AgentInterruptionReason.HOST_REQUESTED),
            )
        }
    }

    private class ToolWaitingRunner : TestAgentRunner() {
        override fun run(request: AgentRequest): Flow<AgentEvent> = flow {
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
        }

        override suspend fun resume(sessionId: AgentSessionId): Flow<AgentEvent> = flow { }

        override suspend fun cancel(sessionId: AgentSessionId) = Unit
    }

    private class ProvisionalOutputRunner : TestAgentRunner() {
        private lateinit var request: AgentRequest

        override fun run(request: AgentRequest): Flow<AgentEvent> = flow {
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
        }

        override suspend fun resume(sessionId: AgentSessionId): Flow<AgentEvent> = flow { }

        override suspend fun cancel(sessionId: AgentSessionId) = Unit

        override suspend fun interrupt(sessionId: AgentSessionId): AgentRecoveryInfo {
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
