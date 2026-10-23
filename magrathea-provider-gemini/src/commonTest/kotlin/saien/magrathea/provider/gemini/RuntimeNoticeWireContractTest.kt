package saien.magrathea.provider.gemini

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonArray
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

class RuntimeNoticeWireContractTest {
    private val builder = GeminiInteractionsRequestBuilder()

    @Test
    fun runtimeNoticeProjectsExactlyLikeTheSameTextAsAUserMessage() {
        val withRuntime = builder.build(request(atlasHistory(runtimeNotice())))
        val withUser = builder.build(request(atlasHistory(userNotice())))
        val withoutNotice = builder.build(request(atlasHistory(null)))

        // The RUNTIME message projects byte-for-byte like a USER message with the same text.
        assertEquals(withUser, withRuntime)
        assertEquals(withUser.toString(), withRuntime.toString())

        // The notice is never hoisted into the system instruction.
        assertEquals(withoutNotice["system_instruction"], withRuntime["system_instruction"])
        assertEquals("Answer carefully.", withRuntime["system_instruction"]!!.jsonPrimitive.content)

        // The runtime-only receipt marker never reaches the wire.
        assertFalse(withRuntime.toString().contains("backgroundReceipt"))
        assertFalse(withRuntime.toString().contains("bg_receipt"))

        // Message order is preserved: receipt for c1, then the notice text, then the read result.
        val input = withRuntime.getValue("input").jsonArray
        assertEquals(
            listOf(
                "user_input",
                "function_call",
                "function_result",
                "model_output",
                "user_input",
                "function_call",
                "function_result",
            ),
            input.map { it.jsonObject.getValue("type").jsonPrimitive.content },
        )
        val noticeStep = input[4].jsonObject
        assertEquals(
            NOTICE_TEXT,
            noticeStep.getValue("content").jsonArray.single().jsonObject.getValue("text").jsonPrimitive.content,
        )
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
        model = ModelDescriptor("gemini", "gemini-contract-model"),
        messages = messages,
        maxTokens = 4_096,
    )

    private fun atlasHistory(notice: AgentMessage?): List<AgentMessage> = listOfNotNull(
        AgentMessage(role = MessageRole.SYSTEM, parts = listOf(TextPart("Answer carefully."))),
        AgentMessage(role = MessageRole.USER, parts = listOf(TextPart("Look up project Atlas"))),
        assistantWithSteps(
            parts = listOf(
                ToolCallPart(
                    toolCallId = "c1",
                    toolName = "slow_lookup",
                    arguments = buildJsonObject { put("topic", "Atlas") },
                ),
            ),
            steps = JsonArray(
                listOf(
                    buildJsonObject {
                        put("type", "function_call")
                        put("id", "c1")
                        put("name", "slow_lookup")
                        put("arguments", buildJsonObject { put("topic", "Atlas") })
                    },
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
        assistantWithSteps(
            parts = listOf(TextPart("Still checking.")),
            steps = JsonArray(
                listOf(
                    buildJsonObject {
                        put("type", "model_output")
                        put(
                            "content",
                            buildJsonArray {
                                add(buildJsonObject {
                                    put("type", "text")
                                    put("text", "Still checking.")
                                })
                            },
                        )
                    },
                ),
            ),
        ),
        notice,
        assistantWithSteps(
            parts = listOf(
                ToolCallPart(
                    toolCallId = "r1",
                    toolName = "read_background_result",
                    arguments = buildJsonObject { put("handle", "bg_7f3kq9") },
                ),
            ),
            steps = JsonArray(
                listOf(
                    buildJsonObject {
                        put("type", "function_call")
                        put("id", "r1")
                        put("name", "read_background_result")
                        put("arguments", buildJsonObject { put("handle", "bg_7f3kq9") })
                    },
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

    private fun assistantWithSteps(
        parts: List<saien.magrathea.core.MessagePart>,
        steps: JsonArray,
        stopReason: StopReason? = null,
    ): AgentMessage = AgentMessage(
        role = MessageRole.ASSISTANT,
        parts = parts,
        stopReason = stopReason,
        metadata = buildJsonObject { put(GEMINI_INTERACTION_STEPS_METADATA, steps) },
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
