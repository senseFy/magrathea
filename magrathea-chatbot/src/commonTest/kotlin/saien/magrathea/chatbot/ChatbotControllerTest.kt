package saien.magrathea.chatbot

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.Test
import saien.magrathea.core.AgentEvent
import saien.magrathea.core.AgentMessage
import saien.magrathea.core.AgentRequest
import saien.magrathea.core.AgentRunner
import saien.magrathea.core.AgentSessionId
import saien.magrathea.core.AgentStateSnapshot
import saien.magrathea.core.MessageRole
import saien.magrathea.core.StopReason
import saien.magrathea.core.TextPart

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
                AgentEvent.Started(request.sessionId),
                AgentEvent.MessageEmitted(request.sessionId, assistant),
                AgentEvent.Completed(
                    request.sessionId,
                    AgentStateSnapshot(messages = listOf(user, assistant), stopReason = StopReason.COMPLETED),
                ),
            )
        })
        val controller = ChatbotController(
            runner = runner,
            requestFactory = DefaultChatbotRequestFactory(),
            initialConfiguration = testChatbotConfiguration(provider = "fake", model = "fake"),
            scope = this,
        )

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
    }

    @Test
    fun cancelShouldCancelActiveSessionAndState() = runTest {
        val runner = ScriptedAgentRunner(runScript = { request ->
            listOf(AgentEvent.Started(request.sessionId))
        }, suspendAfterRunScript = true)
        val controller = ChatbotController(
            runner = runner,
            requestFactory = DefaultChatbotRequestFactory(),
            initialConfiguration = testChatbotConfiguration(provider = "fake", model = "fake"),
            scope = this,
        )

        controller.sendMessage("hello")
        runCurrent()
        val sessionId = runner.requests.single().sessionId

        controller.cancel()

        assertEquals(listOf(sessionId), runner.cancelled)
        assertEquals(ChatbotStatus.CANCELLED, controller.state.value.status)
    }

    @Test
    fun resumeShouldCollectRunnerResumeEvents() = runTest {
        val sessionId = AgentSessionId("session-1")
        val assistant = AgentMessage(
            id = "assistant-1",
            role = MessageRole.ASSISTANT,
            parts = listOf(TextPart("resumed")),
            stopReason = StopReason.COMPLETED,
        )
        val runner = ScriptedAgentRunner(
            resumeScript = {
                listOf(
                    AgentEvent.Started(sessionId),
                    AgentEvent.Completed(
                        sessionId,
                        AgentStateSnapshot(messages = listOf(assistant), stopReason = StopReason.COMPLETED),
                    ),
                )
            },
        )
        val controller = ChatbotController(
            runner = runner,
            requestFactory = DefaultChatbotRequestFactory(),
            initialConfiguration = testChatbotConfiguration(provider = "fake", model = "fake"),
            scope = this,
        )

        controller.resume(sessionId)
        advanceUntilIdle()

        assertEquals(listOf(sessionId), runner.resumed)
        assertEquals(ChatbotStatus.COMPLETED, controller.state.value.status)
        assertEquals("resumed", controller.state.value.messages.single().text)
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
                AgentEvent.Started(request.sessionId),
                AgentEvent.Completed(
                    request.sessionId,
                    AgentStateSnapshot(messages = request.messages + assistant, stopReason = StopReason.COMPLETED),
                ),
            )
        })
        val controller = ChatbotController(
            runner = runner,
            requestFactory = DefaultChatbotRequestFactory(),
            initialConfiguration = testChatbotConfiguration(provider = "fake", model = "fake"),
            scope = this,
        )

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
    }

    private class ScriptedAgentRunner(
        private val runScript: (AgentRequest) -> List<AgentEvent> = { emptyList() },
        private val resumeScript: (AgentSessionId) -> List<AgentEvent> = { emptyList() },
        private val suspendAfterRunScript: Boolean = false,
    ) : AgentRunner {
        val requests = mutableListOf<AgentRequest>()
        val cancelled = mutableListOf<AgentSessionId>()
        val resumed = mutableListOf<AgentSessionId>()

        override fun run(request: AgentRequest): Flow<AgentEvent> = flow {
            requests += request
            runScript(request).forEach { emit(it) }
            if (suspendAfterRunScript) awaitCancellation()
        }

        override suspend fun resume(sessionId: AgentSessionId): Flow<AgentEvent> {
            resumed += sessionId
            return flow {
                resumeScript(sessionId).forEach { emit(it) }
            }
        }

        override suspend fun cancel(sessionId: AgentSessionId) {
            cancelled += sessionId
        }
    }
}

private fun AgentMessage.text(): String = parts.filterIsInstance<TextPart>().joinToString("") { it.text }
