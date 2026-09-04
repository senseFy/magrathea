package saien.magrathea.chatbot

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.Test
import saien.magrathea.core.AgentEvent
import saien.magrathea.core.AgentMessage
import saien.magrathea.core.AgentRequest
import saien.magrathea.core.AgentRunner
import saien.magrathea.core.AgentSessionId
import saien.magrathea.core.AgentStateSnapshot
import saien.magrathea.core.MessageRole
import saien.magrathea.core.MessageBlockPhase
import saien.magrathea.core.ReasoningPart
import saien.magrathea.core.StopReason
import saien.magrathea.core.TextPart
import saien.magrathea.core.TokenUsage
import saien.magrathea.core.ToolResultPart

@OptIn(ExperimentalCoroutinesApi::class)
class ChatbotLifecycleContractTest {
    @Test
    fun chatbotFacadePreservesPublicMessageSemantics() {
        val sessionId = AgentSessionId("chat-facade")
        val assistant = AgentMessage(
            id = "assistant-facade",
            role = MessageRole.ASSISTANT,
            parts = listOf(
                TextPart("working", phase = MessageBlockPhase.COMMENTARY),
                ReasoningPart("private reasoning", redacted = false),
                TextPart("answer", phase = MessageBlockPhase.FINAL),
                ToolResultPart(
                    toolCallId = "call-1",
                    toolName = "lookup",
                    result = JsonPrimitive("source result"),
                    metadata = buildJsonObject {
                        put(
                            "citations",
                            buildJsonArray {
                                add(
                                    buildJsonObject {
                                        put("title", "Reference")
                                        put("url", "https://example.test/reference")
                                        put("snippet", "Relevant excerpt")
                                    },
                                )
                            },
                        )
                    },
                ),
            ),
            createdAtEpochMs = 1_700_000_000_123,
            stopReason = StopReason.MAX_TOKENS,
        )

        val state = ChatbotEventReducer().reduce(
            ChatbotSnapshot(configuration = testChatbotConfiguration()),
            AgentEvent.Completed(
                sessionId,
                AgentStateSnapshot(
                    messages = listOf(assistant),
                    stopReason = StopReason.MAX_TOKENS,
                    usage = TokenUsage(inputTokens = 7, outputTokens = 5, reasoningTokens = 3),
                ),
            ),
        )

        val message = state.messages.single()
        assertEquals(
            listOf(ChatbotMessagePhase.COMMENTARY, ChatbotMessagePhase.FINAL),
            message.textBlocks.map { it.phase },
        )
        assertEquals("private reasoning", message.reasoning.single().text)
        assertFalse(message.reasoning.single().redacted)
        assertEquals("Reference", message.toolResults.single().citations.single().title)
        assertEquals(1_700_000_000_123, message.createdAtEpochMs)
        assertEquals(ChatbotStopReason.MAX_TOKENS, message.stopReason)
        assertEquals(ChatbotUsage(inputTokens = 7, outputTokens = 5, reasoningTokens = 3), state.usage)
    }

    @Test
    fun terminalEventStopsChatbotUpstreamAndIgnoresLaterEvents() = runTest {
        val runner = PostTerminalRunner()
        val fixture = ManagedChatbotControllerFixture.create(
            runner = runner,
            scope = this,
            configuration = testChatbotConfiguration("chat-terminal", "chat-terminal"),
        )
        val controller = fixture.controller

        controller.sendMessage("hello")
        advanceUntilIdle()

        assertEquals(ChatbotStatus.COMPLETED, controller.state.value.status)
        assertEquals(listOf("hello", "done"), controller.state.value.messages.map { it.text })
        assertFalse(controller.state.value.messages.any { it.text == "late-event" })
        assertFalse(runner.reachedCodeAfterTerminal)
        assertTrue(runner.cancelledByTerminal)
        fixture.close()
    }

    private class PostTerminalRunner : TestAgentRunner() {
        var reachedCodeAfterTerminal = false
        var cancelledByTerminal = false

        override fun run(request: AgentRequest): Flow<AgentEvent> = flow {
            val assistant = AgentMessage(
                id = "terminal-assistant",
                role = MessageRole.ASSISTANT,
                parts = listOf(TextPart("done")),
                stopReason = StopReason.COMPLETED,
            )
            try {
                emit(
                    AgentEvent.Completed(
                        request.sessionId,
                        AgentStateSnapshot(
                            messages = request.messages + assistant,
                            status = saien.magrathea.core.AgentStatus.COMPLETED,
                            stopReason = StopReason.COMPLETED,
                        ),
                    ),
                )
                reachedCodeAfterTerminal = true
                emit(
                    AgentEvent.MessageEmitted(
                        request.sessionId,
                        AgentMessage(
                            role = MessageRole.ASSISTANT,
                            parts = listOf(TextPart("late-event")),
                        ),
                    ),
                )
            } finally {
                cancelledByTerminal = true
            }
        }

        override suspend fun resume(sessionId: AgentSessionId): Flow<AgentEvent> = flow { }
        override suspend fun cancel(sessionId: AgentSessionId) = Unit
    }
}
