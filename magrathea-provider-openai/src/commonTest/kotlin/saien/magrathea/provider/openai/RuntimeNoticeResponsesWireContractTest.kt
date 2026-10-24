package saien.magrathea.provider.openai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import saien.magrathea.core.AgentMessage
import saien.magrathea.core.BackgroundExecutionState
import saien.magrathea.core.BackgroundOutcome
import saien.magrathea.core.MessageRole
import saien.magrathea.core.ModelDescriptor
import saien.magrathea.core.RuntimeNoticeDelivery
import saien.magrathea.core.RuntimeNoticeEntry
import saien.magrathea.core.RuntimeNoticePart
import saien.magrathea.core.StopReason
import saien.magrathea.core.TextPart
import saien.magrathea.core.ToolBackgroundReceipt
import saien.magrathea.core.ToolCallPart
import saien.magrathea.core.ToolResultPart
import saien.magrathea.provider.api.ProviderProtocolException
import saien.magrathea.provider.api.ProviderRequest

class RuntimeNoticeResponsesWireContractTest {
    private val builder = OpenAiResponsesRequestBuilder("openai")

    @Test
    fun runtimeNoticeProjectsExactlyLikeTheSameTextAsAUserMessage() {
        val withRuntime = builder.build(request(atlasHistory(runtimeNotice())))
        val withUser = builder.build(request(atlasHistory(userNotice())))
        val withoutNotice = builder.build(request(atlasHistory(null)))

        // The RUNTIME message projects byte-for-byte like a USER message with the same text.
        assertEquals(withUser, withRuntime)
        assertEquals(withUser.toString(), withRuntime.toString())

        // The notice is never hoisted into system/developer input.
        assertEquals(
            withoutNotice["input"]?.jsonArray?.first(),
            withRuntime["input"]?.jsonArray?.first(),
        )

        // The runtime-only receipt marker never reaches the wire.
        assertFalse(withRuntime.toString().contains("backgroundReceipt"))
        assertFalse(withRuntime.toString().contains("bg_receipt"))

        // Message order is preserved: receipt for c1, then the notice text, then the read result.
        val input = withRuntime.getValue("input").jsonArray
        val receiptIndex = input.indexOfFirst { it.toString().contains("\"c1\"") }
        val noticeIndex = input.indexOfFirst { it.toString().contains("A background result is available.") }
        val readIndex = input.indexOfFirst { it.toString().contains("\"r1\"") }
        check(receiptIndex in 0 until noticeIndex) { "receipt must precede the notice" }
        check(noticeIndex < readIndex) { "notice must precede the read result" }
        val noticeItem = input[noticeIndex].jsonObject
        assertEquals("user", noticeItem.getValue("role").jsonPrimitive.content)
    }

    @Test
    fun aRuntimeNoticePartInsideAnyOtherRoleIsRejected() {
        val misplaced = AgentMessage(
            role = MessageRole.USER,
            parts = runtimeNotice().parts,
        )

        assertFailsWith<ProviderProtocolException> {
            builder.build(request(listOf(misplaced)))
        }
    }

    private fun request(messages: List<AgentMessage>): ProviderRequest = ProviderRequest(
        model = ModelDescriptor("openai", "gpt-contract"),
        messages = messages,
        maxTokens = 4_096,
    )

    private fun atlasHistory(notice: AgentMessage?): List<AgentMessage> = listOfNotNull(
        AgentMessage(role = MessageRole.USER, parts = listOf(TextPart("Look up project Atlas"))),
        AgentMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                ToolCallPart(
                    toolCallId = "c1",
                    toolName = "slow_lookup",
                    arguments = buildJsonObject { put("topic", "Atlas") },
                ),
            ),
            stopReason = StopReason.TOOL_CALLS,
        ),
        AgentMessage(
            role = MessageRole.TOOL,
            parts = listOf(
                ToolResultPart(
                    toolCallId = "c1",
                    toolName = "slow_lookup",
                    result = buildJsonObject {
                        put("status", "running")
                        put("handle", "bg_7f3kq9")
                        put("retrieve", "read_background_result")
                    },
                    backgroundReceipt = ToolBackgroundReceipt(
                        handle = "bg_7f3kq9",
                        executionId = "exec-1",
                        state = BackgroundExecutionState.RUNNING,
                    ),
                ),
            ),
        ),
        AgentMessage(role = MessageRole.ASSISTANT, parts = listOf(TextPart("Still checking."))),
        notice,
        AgentMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                ToolCallPart(
                    toolCallId = "r1",
                    toolName = "read_background_result",
                    arguments = buildJsonObject { put("handle", "bg_7f3kq9") },
                ),
            ),
            stopReason = StopReason.TOOL_CALLS,
        ),
        AgentMessage(
            role = MessageRole.TOOL,
            parts = listOf(
                ToolResultPart(
                    toolCallId = "r1",
                    toolName = "read_background_result",
                    result = buildJsonObject { put("status", "succeeded") },
                ),
            ),
        ),
    )

    private fun runtimeNotice(): AgentMessage = AgentMessage(
        role = MessageRole.RUNTIME,
        parts = listOf(
            RuntimeNoticePart(
                entries = listOf(
                    RuntimeNoticeEntry(
                        handle = "bg_7f3kq9",
                        toolName = "slow_lookup",
                        callId = "c1",
                        outcome = BackgroundOutcome.SUCCEEDED,
                        resultVersion = 0,
                    ),
                ),
                delivery = RuntimeNoticeDelivery.COALESCED,
                text = NOTICE_TEXT,
            ),
        ),
    )

    private fun userNotice(): AgentMessage = AgentMessage(
        role = MessageRole.USER,
        parts = listOf(TextPart(NOTICE_TEXT)),
    )

    private companion object {
        const val NOTICE_TEXT = "<runtime_notice>\nA background result is available.\n</runtime_notice>"
    }
}
