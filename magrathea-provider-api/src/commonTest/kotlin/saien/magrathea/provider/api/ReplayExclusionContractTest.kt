package saien.magrathea.provider.api

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import saien.magrathea.core.AgentMessage
import saien.magrathea.core.MessageBlockPhase
import saien.magrathea.core.MessageRole
import saien.magrathea.core.ModelDescriptor
import saien.magrathea.core.ReasoningPart
import saien.magrathea.core.ReplayExclusion
import saien.magrathea.core.ReplayExclusionReason
import saien.magrathea.core.ReplayPolicy
import saien.magrathea.core.StopReason
import saien.magrathea.core.TextPart
import saien.magrathea.core.ToolCallPart

class ReplayExclusionContractTest {
    private val model = ModelDescriptor("openai", "target")
    private val user = AgentMessage(id = "user", role = MessageRole.USER, parts = listOf(TextPart("Continue")))

    @Test
    fun interruptedReasoningIsOmittedWithoutChangingStoredHistory() = runTest {
        val partial = AgentMessage(
            id = "partial",
            role = MessageRole.ASSISTANT,
            parts = listOf(ReasoningPart("Unfinished reasoning", phase = MessageBlockPhase.COMMENTARY)),
        )
        val history = listOf(partial, user)
        val policy = DefaultReplayPolicy()
        val replay = policy.transformWithReport(history, model)

        assertEquals(listOf(user), replay.messages)
        assertEquals(listOf(ReplayExclusion(partial.id, ReplayExclusionReason.EMPTY_AFTER_TRANSFORMATION)), replay.exclusions)
        assertEquals(replay.messages, policy.transform(history, model))
        assertEquals(listOf(partial, user), history)
        assertEquals(1, partial.parts.size)
        assertNull(partial.stopReason)
    }

    @Test
    fun survivingTextAndToolSemanticsAreRetainedWithoutAnExclusion() = runTest {
        val source = AgentMessage(
            id = "source",
            role = MessageRole.ASSISTANT,
            parts = listOf(
                ReasoningPart("Private reasoning"),
                TextPart("Visible answer"),
                ToolCallPart("call", "search", buildJsonObject { put("query", "example") }),
            ),
            metadata = buildJsonObject { put("provider", "other"); put("model", "other") },
        )
        val replay = DefaultReplayPolicy().transformWithReport(listOf(source, user), model)

        assertEquals(source.parts.drop(1), replay.messages.first().parts)
        assertEquals(listOf(MessageRole.ASSISTANT, MessageRole.TOOL, MessageRole.USER), replay.messages.map { it.role })
        assertEquals(emptyList(), replay.exclusions)
        assertEquals(3, source.parts.size)
    }

    @Test
    fun existingOmissionRulesHaveExplicitReasons() = runTest {
        val limited = AgentMessage(
            id = "limited",
            role = MessageRole.ASSISTANT,
            parts = listOf(ToolCallPart("call", "search", JsonPrimitive("partial"), partial = true)),
            stopReason = StopReason.MAX_TOKENS,
        )
        val cancelled = AgentMessage("cancelled", MessageRole.ASSISTANT, listOf(TextPart("Partial")), stopReason = StopReason.CANCELLED)
        val failed = cancelled.copy(id = "failed", stopReason = StopReason.ERROR)
        val replay = DefaultReplayPolicy().transformWithReport(listOf(limited, cancelled, failed, user), model)

        assertEquals(listOf(user), replay.messages)
        assertEquals(
            mapOf(
                limited.id to ReplayExclusionReason.UNFINISHED_TOOL_CALLS,
                cancelled.id to ReplayExclusionReason.CANCELLED_OR_FAILED,
                failed.id to ReplayExclusionReason.CANCELLED_OR_FAILED,
            ),
            replay.exclusions?.associate { it.messageId to it.reason },
        )
    }

    @Test
    fun legacyCustomPolicyLeavesItsDecisionsUnknown() = runTest {
        val policy = ReplayPolicy { messages, _ -> messages.takeLast(1) }
        val replay = policy.transformWithReport(listOf(user, user.copy(id = "next")), model)

        assertEquals(listOf(user.copy(id = "next")), replay.messages)
        assertNull(replay.exclusions)
    }
}
