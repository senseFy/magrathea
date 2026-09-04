package saien.magrathea.chatbot

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.Test
import saien.magrathea.core.AgentCheckpoint
import saien.magrathea.core.AgentEvent
import saien.magrathea.core.AgentInterruption
import saien.magrathea.core.AgentInterruptionReason
import saien.magrathea.core.AgentMessage
import saien.magrathea.core.AgentRecoveryDisposition
import saien.magrathea.core.AgentRecoveryInfo
import saien.magrathea.core.AgentRequest
import saien.magrathea.core.AgentResumeCursor
import saien.magrathea.core.AgentResumePhase
import saien.magrathea.core.AgentRunner
import saien.magrathea.core.AgentSessionSnapshot
import saien.magrathea.core.AgentSessionId
import saien.magrathea.core.AgentStateSnapshot
import saien.magrathea.core.AgentStatus
import saien.magrathea.core.MessageRole
import saien.magrathea.core.ModelDescriptor
import saien.magrathea.core.StopReason
import saien.magrathea.core.TextPart
import saien.magrathea.runtime.InMemoryAgentPersistence

@OptIn(ExperimentalCoroutinesApi::class)
class ChatbotControllerTest {
    @Test
    fun sendMessageShouldBuildRequestAndReduceCompletedState() = runTest {
        val runner = ScriptedAgentRunner(runScript = { request ->
            val user = request.messages.single()
            val assistant = AgentMessage(
                id = "assistant-1",
                role = MessageRole.ASSISTANT,
                parts = listOf(TextPart("reply")),
                stopReason = StopReason.COMPLETED,
            )
            listOf(
                AgentEvent.Started(request.sessionId, TEST_RUN_ID),
                AgentEvent.MessageEmitted(request.sessionId, assistant),
                AgentEvent.Completed(
                    request.sessionId,
                    AgentStateSnapshot(
                        messages = listOf(user, assistant),
                        status = AgentStatus.COMPLETED,
                        stopReason = StopReason.COMPLETED,
                    ),
                ),
            )
        })
        val fixture = ManagedChatbotControllerFixture.create(
            runner = runner,
            scope = this,
            configuration = testChatbotConfiguration(provider = "fake", model = "fake"),
        )
        val controller = fixture.controller

        controller.sendMessage(
            text = "hello",
            attachments = listOf(ChatbotAttachment("content://file/1", "text/plain")),
        )
        advanceUntilIdle()

        assertEquals(1, runner.requests.size)
        assertEquals("hello", runner.requests.single().messages.single().text())
        assertEquals(2, runner.requests.single().messages.single().parts.size)
        assertEquals(ChatbotStatus.COMPLETED, controller.state.value.status)
        assertEquals(listOf("hello", "reply"), controller.state.value.messages.map { it.text })
        assertNotNull(controller.state.value.sessionId)
        fixture.close()
    }

    @Test
    fun cancelShouldCancelActiveSessionAndState() = runTest {
        val runner = ScriptedAgentRunner(runScript = { request ->
            listOf(AgentEvent.Started(request.sessionId, TEST_RUN_ID))
        }, suspendAfterRunScript = true)
        val fixture = ManagedChatbotControllerFixture.create(
            runner = runner,
            scope = this,
            configuration = testChatbotConfiguration(provider = "fake", model = "fake"),
        )
        val controller = fixture.controller

        controller.sendMessage("hello")
        runCurrent()
        val sessionId = runner.requests.single().sessionId

        controller.cancel()

        assertEquals(listOf(sessionId), runner.cancelled)
        assertEquals(ChatbotStatus.CANCELLED, controller.state.value.status)
        fixture.close()
    }

    @Test
    fun resumeShouldCollectRunnerResumeEvents() = runTest {
        val sessionId = AgentSessionId("session-1")
        val persistence = InMemoryAgentPersistence()
        val assistant = AgentMessage(
            id = "assistant-1",
            role = MessageRole.ASSISTANT,
            parts = listOf(TextPart("resumed")),
            stopReason = StopReason.COMPLETED,
        )
        val runner = ScriptedAgentRunner(
            resumeScript = {
                listOf(
                    AgentEvent.Started(sessionId, TEST_RUN_ID),
                    AgentEvent.Completed(
                        sessionId,
                        AgentStateSnapshot(
                            messages = listOf(assistant),
                            status = AgentStatus.COMPLETED,
                            stopReason = StopReason.COMPLETED,
                        ),
                    ),
                )
            },
            recoveryState = AgentStateSnapshot(
                messages = emptyList(),
                status = AgentStatus.INTERRUPTED,
                stopReason = StopReason.INTERRUPTED,
            ),
            persistence = persistence,
        )
        val request = AgentRequest(
            sessionId = sessionId,
            messages = emptyList(),
            model = ModelDescriptor("fake", "fake"),
        )
        val interrupted = AgentStateSnapshot(
            messages = emptyList(),
            status = AgentStatus.INTERRUPTED,
            stopReason = StopReason.INTERRUPTED,
        )
        persistence.commit(
            AgentSessionSnapshot(
                sessionId = sessionId,
                runId = TEST_RUN_ID,
                request = request,
                state = interrupted,
                interruption = AgentInterruption(AgentInterruptionReason.ORPHANED),
            ),
            AgentCheckpoint(
                sessionId,
                TEST_RUN_ID,
                AgentResumeCursor(0, AgentResumePhase.MODEL_PENDING),
                interrupted,
            ),
        )
        val fixture = ManagedChatbotControllerFixture.create(
            runner = runner,
            scope = this,
            configuration = testChatbotConfiguration(provider = "fake", model = "fake"),
            persistence = persistence,
            sessionId = sessionId,
            restore = true,
        )
        val controller = fixture.controller

        controller.resume()
        advanceUntilIdle()

        assertEquals(listOf(sessionId), runner.resumed)
        assertEquals(ChatbotStatus.COMPLETED, controller.state.value.status)
        assertEquals("resumed", controller.state.value.messages.single().text)
        fixture.close()
    }

    @Test
    fun resumeShouldReconcileConversationToTheSafeCheckpointBeforeNewInput() = runTest {
        val sessionId = AgentSessionId("session-recovery")
        val persistence = InMemoryAgentPersistence()
        val user = AgentMessage(
            id = "user-1",
            role = MessageRole.USER,
            parts = listOf(TextPart("hello")),
        )
        val provisional = AgentMessage(
            id = "assistant-provisional",
            role = MessageRole.ASSISTANT,
            parts = listOf(TextPart("partial")),
        )
        val checkpoint = AgentCheckpoint(
            sessionId = sessionId,
            runId = TEST_RUN_ID,
            cursor = AgentResumeCursor(0, AgentResumePhase.MODEL_PENDING),
            state = AgentStateSnapshot(messages = listOf(user)),
        )
        val runner = ScriptedAgentRunner(
            resumeScript = {
                listOf(
                    AgentEvent.Started(sessionId, TEST_RUN_ID),
                    AgentEvent.CheckpointSaved(checkpoint),
                )
            },
            recoveryState = checkpoint.state.copy(
                status = AgentStatus.INTERRUPTED,
                stopReason = StopReason.INTERRUPTED,
            ),
            persistence = persistence,
        )
        val persistedState = AgentStateSnapshot(
            messages = listOf(user, provisional),
            status = AgentStatus.INTERRUPTED,
            stopReason = StopReason.INTERRUPTED,
        )
        persistence.commit(
            AgentSessionSnapshot(
                sessionId = sessionId,
                runId = TEST_RUN_ID,
                request = AgentRequest(
                    sessionId = sessionId,
                    messages = listOf(user),
                    model = ModelDescriptor("fake", "fake"),
                ),
                state = persistedState,
                interruption = AgentInterruption(AgentInterruptionReason.ORPHANED),
            ),
            checkpoint,
        )
        val fixture = ManagedChatbotControllerFixture.create(
            runner = runner,
            scope = this,
            configuration = testChatbotConfiguration(provider = "fake", model = "fake"),
            persistence = persistence,
            sessionId = sessionId,
            restore = true,
        )
        val controller = fixture.controller

        controller.resume()
        advanceUntilIdle()
        controller.sendMessage("next")
        advanceUntilIdle()

        assertEquals(listOf("hello", "next"), runner.requests.single().messages.map { it.text() })
        fixture.close()
    }

    @Test
    fun regenerateShouldTrimMessagesAfterRequestedMessage() = runTest {
        val runner = ScriptedAgentRunner(runScript = { request ->
            val assistant = AgentMessage(
                id = "assistant-${request.messages.size}",
                role = MessageRole.ASSISTANT,
                parts = listOf(TextPart("reply-${request.messages.size}")),
                stopReason = StopReason.COMPLETED,
            )
            listOf(
                AgentEvent.Started(request.sessionId, TEST_RUN_ID),
                AgentEvent.Completed(
                    request.sessionId,
                    AgentStateSnapshot(
                        messages = request.messages + assistant,
                        status = AgentStatus.COMPLETED,
                        stopReason = StopReason.COMPLETED,
                    ),
                ),
            )
        })
        val fixture = ManagedChatbotControllerFixture.create(
            runner = runner,
            scope = this,
            configuration = testChatbotConfiguration(provider = "fake", model = "fake"),
        )
        val controller = fixture.controller

        controller.sendMessage("first")
        advanceUntilIdle()
        controller.sendMessage("second")
        advanceUntilIdle()
        val secondUserId = controller.state.value.messages.first { it.text == "second" }.id

        controller.regenerate(secondUserId)
        advanceUntilIdle()

        assertEquals(3, runner.requests.size)
        assertEquals(listOf("first", "reply-1", "second"), runner.requests.last().messages.map { it.text() })
        assertTrue(controller.state.value.messages.last().text.startsWith("reply-"))
        fixture.close()
    }

    private class ScriptedAgentRunner(
        private val runScript: (AgentRequest) -> List<AgentEvent> = { emptyList() },
        private val resumeScript: (AgentSessionId) -> List<AgentEvent> = { emptyList() },
        private val suspendAfterRunScript: Boolean = false,
        private val recoveryState: AgentStateSnapshot? = null,
        private val persistence: InMemoryAgentPersistence? = null,
    ) : TestAgentRunner() {
        val requests = mutableListOf<AgentRequest>()
        val cancelled = mutableListOf<AgentSessionId>()
        val resumed = mutableListOf<AgentSessionId>()
        private val activeJobs = mutableMapOf<AgentSessionId, Job>()

        override fun run(request: AgentRequest): Flow<AgentEvent> = flow {
            activeJobs[request.sessionId] = currentCoroutineContext()[Job]!!
            try {
                requests += request
                runScript(request).forEach { emit(it) }
                if (suspendAfterRunScript) awaitCancellation()
            } finally {
                activeJobs.remove(request.sessionId)
            }
        }

        override suspend fun resume(sessionId: AgentSessionId): Flow<AgentEvent> {
            resumed += sessionId
            return flow {
                resumeScript(sessionId).forEach { event ->
                    if (event is AgentEvent.Completed) {
                        persistence?.load(sessionId)?.snapshot?.let { stored ->
                            persistence.commit(
                                stored.copy(state = event.state, interruption = null),
                                checkpoint = null,
                            )
                        }
                    }
                    emit(event)
                }
            }
        }

        override suspend fun cancel(sessionId: AgentSessionId) {
            cancelled += sessionId
            activeJobs[sessionId]?.cancelAndJoin()
        }

        override suspend fun inspectRecovery(sessionId: AgentSessionId): AgentRecoveryInfo =
            recoveryState?.let { state ->
                AgentRecoveryInfo(
                    sessionId = sessionId,
                    runId = TEST_RUN_ID,
                    disposition = AgentRecoveryDisposition.RESUMABLE,
                    status = state.status,
                    state = state,
                    interruption = AgentInterruption(AgentInterruptionReason.ORPHANED),
                )
            } ?: super.inspectRecovery(sessionId)
    }
}

private fun AgentMessage.text(): String = parts.filterIsInstance<TextPart>().joinToString("") { it.text }
