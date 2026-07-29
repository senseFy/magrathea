package saien.magrathea.chatbot

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.Test
import saien.magrathea.core.AgentEvent
import saien.magrathea.core.AgentFailureCode
import saien.magrathea.core.AgentCheckpoint
import saien.magrathea.core.AgentMessage
import saien.magrathea.core.AgentResumeCursor
import saien.magrathea.core.AgentResumePhase
import saien.magrathea.core.AgentSessionId
import saien.magrathea.core.AgentStateSnapshot
import saien.magrathea.core.ContextCompaction
import saien.magrathea.core.ContextManagementState
import saien.magrathea.core.MessageRole
import saien.magrathea.core.ModelDescriptor
import saien.magrathea.core.StopReason
import saien.magrathea.core.TextPart
import saien.magrathea.core.TokenUsage
import saien.magrathea.core.ToolCallPart
import saien.magrathea.core.ToolExecutionResult
import saien.magrathea.core.ToolResultPart

class ChatbotEventReducerTest {
    private val reducer = ChatbotEventReducer()

    @Test
    fun reducerShouldTrackLifecycleAndReplaceStreamingMessageById() {
        val sessionId = AgentSessionId("session-1")
        val assistantId = "assistant-1"
        val user = AgentMessage(id = "user-1", role = MessageRole.USER, parts = listOf(TextPart("hello")))
        val partial = AgentMessage(id = assistantId, role = MessageRole.ASSISTANT, parts = listOf(TextPart("hel")))
        val final = partial.copy(parts = listOf(TextPart("hello there")), stopReason = StopReason.COMPLETED)

        var state = ChatbotSnapshot(
            configuration = testChatbotConfiguration(),
            messages = listOf(user.toChatbotMessageSnapshot()),
        )
        state = reducer.reduce(state, AgentEvent.Started(sessionId, TEST_RUN_ID))
        state = reducer.reduce(state, AgentEvent.MessageEmitted(sessionId, partial))
        state = reducer.reduce(state, AgentEvent.MessageEmitted(sessionId, final))

        assertEquals(sessionId.value, state.sessionId)
        assertEquals(ChatbotStatus.RUNNING, state.status)
        assertEquals(2, state.messages.size)
        assertEquals("hello there", state.messages.last().text)

        state = reducer.reduce(
            state,
            AgentEvent.Completed(
                sessionId = sessionId,
                state = AgentStateSnapshot(
                    messages = listOf(user, final),
                    stopReason = StopReason.COMPLETED,
                    usage = TokenUsage(inputTokens = 21, outputTokens = 8),
                    latestRequestUsage = TokenUsage(inputTokens = 13, outputTokens = 5),
                ),
            ),
        )

        assertEquals(ChatbotStatus.COMPLETED, state.status)
        assertEquals(2, state.messages.size)
        assertEquals(ChatbotUsage(inputTokens = 21, outputTokens = 8), state.usage)
        assertEquals(
            ChatbotUsage(inputTokens = 13, outputTokens = 5),
            state.latestRequestUsage,
        )
        assertNull(state.failure)
        assertFalse(state.isRunning)
    }

    @Test
    fun reducerShouldExposeSafeContextCompactionMetadataFromCheckpointsAndCompletion() {
        val sessionId = AgentSessionId("session-context")
        val contextManagement = ContextManagementState(
            compaction = ContextCompaction(
                summary = "private continuity summary",
                firstKeptMessageId = "user-2",
                summarizedThroughMessageId = "assistant-1",
                sourcePrefixDigest = "digest",
                tokensBefore = 91_000,
                generation = 2,
                summaryModel = ModelDescriptor("provider", "model"),
            ),
        )
        val agentState = AgentStateSnapshot(
            messages = emptyList(),
            contextManagement = contextManagement,
            turn = 3,
        )

        val checkpointed = reducer.reduce(
            ChatbotSnapshot(configuration = testChatbotConfiguration()),
            AgentEvent.CheckpointSaved(
                AgentCheckpoint(
                    sessionId,
                    TEST_RUN_ID,
                    AgentResumeCursor(3, AgentResumePhase.MODEL_PENDING),
                    agentState,
                ),
            ),
        )

        assertEquals(2, checkpointed.contextManagement.compactionGeneration)
        assertEquals(91_000, checkpointed.contextManagement.tokensBeforeLastCompaction)
        assertTrue(checkpointed.contextManagement.isCompacted)
        assertFalse(checkpointed.toString().contains("private continuity summary"))

        val completed = reducer.reduce(
            checkpointed,
            AgentEvent.Completed(sessionId, agentState),
        )
        assertEquals(checkpointed.contextManagement, completed.contextManagement)
    }

    @Test
    fun reducerShouldExposeToolCallsAndToolResults() {
        val sessionId = AgentSessionId("session-1")
        val toolCall = ToolCallPart(
            toolCallId = "call-1",
            toolName = "lookup",
            arguments = buildJsonObject { },
            partial = false,
        )
        val assistant = AgentMessage(
            id = "assistant-1",
            role = MessageRole.ASSISTANT,
            parts = listOf(toolCall),
            stopReason = StopReason.TOOL_CALLS,
        )
        val tool = AgentMessage(
            id = "tool-1",
            role = MessageRole.TOOL,
            parts = listOf(
                ToolResultPart(
                    toolCallId = "call-1",
                    toolName = "lookup",
                    result = JsonPrimitive("result"),
                    displayText = "display result",
                ),
            ),
        )

        var state = reducer.reduce(
            ChatbotSnapshot(configuration = testChatbotConfiguration()),
            AgentEvent.MessageEmitted(sessionId, assistant),
        )
        state = reducer.reduce(state, AgentEvent.ToolRequested(sessionId, toolCall))
        assertEquals(ChatbotStatus.WAITING_FOR_TOOL, state.status)
        assertEquals("lookup", state.messages.single().toolCalls.single().name)

        state = reducer.reduce(
            state,
            AgentEvent.ToolCompleted(
                sessionId,
                ToolExecutionResult("call-1", "lookup", JsonPrimitive("result")),
            ),
        )
        state = reducer.reduce(state, AgentEvent.MessageEmitted(sessionId, tool))

        assertEquals(ChatbotStatus.RUNNING, state.status)
        assertEquals("display result", state.messages.last().toolResults.single().text)
    }

    @Test
    fun reducerShouldExposeEvidenceBasedToolLifecycleAndResultMetadata() {
        val sessionId = AgentSessionId("session-1")
        val partialCall = ToolCallPart(
            toolCallId = "call-1",
            toolName = "lookup",
            arguments = buildJsonObject { },
            partial = true,
        )
        val assistant = AgentMessage(
            id = "assistant-1",
            role = MessageRole.ASSISTANT,
            parts = listOf(partialCall),
        )
        var state = reducer.reduce(
            ChatbotSnapshot(configuration = testChatbotConfiguration()),
            AgentEvent.MessageEmitted(sessionId, assistant),
        )
        assertEquals(ChatbotToolActivityStatus.PREPARING, state.toolActivities.single().status)

        val finalizedCall = partialCall.copy(partial = false)
        state = reducer.reduce(
            state,
            AgentEvent.MessageEmitted(sessionId, assistant.copy(parts = listOf(finalizedCall))),
        )
        assertEquals(ChatbotToolActivityStatus.PENDING, state.toolActivities.single().status)

        state = reducer.reduce(state, AgentEvent.ToolRequested(sessionId, finalizedCall))
        assertEquals(ChatbotToolActivityStatus.RUNNING, state.toolActivities.single().status)

        val metadata = buildJsonObject { put("identity", JsonPrimitive("safe")) }
        state = reducer.reduce(
            state,
            AgentEvent.ToolCompleted(
                sessionId,
                ToolExecutionResult(
                    toolCallId = "call-1",
                    toolName = "lookup",
                    result = JsonPrimitive("result"),
                    displayText = "display result",
                    metadata = metadata,
                ),
            ),
        )
        val completed = state.toolActivities.single()
        assertEquals(ChatbotToolActivityStatus.SUCCEEDED, completed.status)
        assertEquals(metadata, assertNotNull(completed.result).metadata)
        assertNull(completed.resultMessageId)

        val toolMessage = AgentMessage(
            id = "tool-1",
            role = MessageRole.TOOL,
            parts = listOf(
                ToolResultPart(
                    toolCallId = "call-1",
                    toolName = "lookup",
                    result = JsonPrimitive("result"),
                    displayText = "display result",
                    metadata = metadata,
                ),
            ),
        )
        state = reducer.reduce(state, AgentEvent.MessageEmitted(sessionId, toolMessage))

        assertEquals("tool-1", state.toolActivities.single().resultMessageId)
        assertEquals(metadata, state.messages.last().toolResults.single().metadata)
    }

    @Test
    fun laterMessageProjectionDoesNotRegressAnObservedRunningTool() {
        val sessionId = AgentSessionId("session-1")
        val call = ToolCallPart(
            toolCallId = "call-1",
            toolName = "lookup",
            arguments = buildJsonObject { },
        )
        val assistant = AgentMessage(
            id = "assistant-1",
            role = MessageRole.ASSISTANT,
            parts = listOf(call),
        )
        var state = reducer.reduce(
            ChatbotSnapshot(configuration = testChatbotConfiguration()),
            AgentEvent.MessageEmitted(sessionId, assistant),
        )
        state = reducer.reduce(state, AgentEvent.ToolRequested(sessionId, call))

        state = reducer.reduce(state, AgentEvent.MessageEmitted(sessionId, assistant))

        assertEquals(ChatbotToolActivityStatus.RUNNING, state.toolActivities.single().status)
    }

    @Test
    fun projectionShouldNotConfuseCallIdsReusedAcrossTurns() {
        val firstCall = AgentMessage(
            id = "assistant-1",
            role = MessageRole.ASSISTANT,
            parts = listOf(ToolCallPart("reused", "lookup", buildJsonObject { })),
        ).toChatbotMessageSnapshot()
        val firstResult = AgentMessage(
            id = "tool-1",
            role = MessageRole.TOOL,
            parts = listOf(ToolResultPart("reused", "lookup", JsonPrimitive("first"))),
        ).toChatbotMessageSnapshot()
        val secondCall = AgentMessage(
            id = "assistant-2",
            role = MessageRole.ASSISTANT,
            parts = listOf(ToolCallPart("reused", "lookup", buildJsonObject { })),
        ).toChatbotMessageSnapshot()

        val activities = reconcileToolActivities(
            messages = listOf(firstCall, firstResult, secondCall),
            terminalUnresolvedStatus = ChatbotToolActivityStatus.INTERRUPTED,
        )

        assertEquals(2, activities.size)
        assertEquals(ChatbotToolActivityKey("assistant-1", 0), activities[0].key)
        assertEquals(ChatbotToolActivityStatus.SUCCEEDED, activities[0].status)
        assertEquals("tool-1", activities[0].resultMessageId)
        assertEquals(ChatbotToolActivityKey("assistant-2", 0), activities[1].key)
        assertEquals(ChatbotToolActivityStatus.INTERRUPTED, activities[1].status)
        assertNull(activities[1].result)
    }

    @Test
    fun reducerShouldPreserveMessagesWhenFailureOrCancellationArrives() {
        val sessionId = AgentSessionId("session-1")
        val message = AgentMessage(id = "assistant-1", role = MessageRole.ASSISTANT, parts = listOf(TextPart("partial")))

        var state = reducer.reduce(
            ChatbotSnapshot(configuration = testChatbotConfiguration()),
            AgentEvent.MessageEmitted(sessionId, message),
        )
        state = reducer.reduce(state, AgentEvent.Failed(sessionId, AgentFailureCode.PROVIDER_NETWORK))

        assertEquals(ChatbotStatus.FAILED, state.status)
        assertEquals(ChatbotFailure.NETWORK, state.failure)
        assertEquals("partial", state.messages.single().text)

        state = reducer.reduce(state, AgentEvent.Cancelled(sessionId))

        assertEquals(ChatbotStatus.CANCELLED, state.status)
        assertEquals("partial", state.messages.single().text)
        assertEquals(ChatbotFailure.NETWORK, state.failure)
    }
}
