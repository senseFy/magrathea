package saien.magrathea.runtime

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import saien.magrathea.core.AgentMessage
import saien.magrathea.core.AgentRunId
import saien.magrathea.core.BackgroundToolProtocol
import saien.magrathea.core.MessageRole
import saien.magrathea.core.ToolResultPart
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BackgroundControlHistoryFactsTest {
    private val runId = AgentRunId("current-run")
    private val handle = "background-handle"
    private val malformedValues = listOf(JsonNull, JsonArray(emptyList()), JsonObject(emptyMap()))

    @Test
    fun pendingReadsIgnoreMalformedHandlesAndStatusesWithoutLosingValidResults() {
        val pending = JsonObject(mapOf("status" to JsonPrimitive("running")))
        val malformed = malformedValues.flatMap { value ->
            listOf(
                controlResult(BackgroundToolInstructions.READ_TOOL, pending, resultHandle = value),
                controlResult(BackgroundToolInstructions.READ_TOOL, JsonObject(mapOf("status" to value))),
                controlResult(BackgroundToolInstructions.READ_TOOL, value),
            )
        }
        val history = malformed + listOf(
            controlResult(BackgroundToolInstructions.READ_TOOL, pending, isError = true),
            controlResult(BackgroundToolInstructions.READ_TOOL, pending, resultRunId = AgentRunId("old-run")),
            controlResult(BackgroundToolInstructions.READ_TOOL, pending, resultHandle = JsonPrimitive("other")),
            controlResult(
                BackgroundToolInstructions.READ_TOOL,
                JsonObject(mapOf("status" to JsonPrimitive("completed"))),
            ),
        )

        assertEquals(0, history.pendingReadResultCount(handle, runId))
        val valid = listOf("queued", "running").map { status ->
            controlResult(
                BackgroundToolInstructions.READ_TOOL,
                JsonObject(mapOf("status" to JsonPrimitive(status))),
            )
        }
        assertEquals(2, (history + valid).pendingReadResultCount(handle, runId))
    }

    @Test
    fun awaitAllowanceIgnoresMalformedFlagsWithoutLosingAnExhaustedResult() {
        val exhausted = JsonObject(mapOf("wait_allowance_exhausted" to JsonPrimitive(true)))
        val malformed = malformedValues.flatMap { value ->
            listOf(
                controlResult(BackgroundToolInstructions.AWAIT_TOOL, value),
                controlResult(
                    BackgroundToolInstructions.AWAIT_TOOL,
                    JsonObject(mapOf("wait_allowance_exhausted" to value)),
                ),
            )
        }
        val history = malformed + listOf(
            controlResult(BackgroundToolInstructions.AWAIT_TOOL, exhausted, isError = true),
            controlResult(BackgroundToolInstructions.AWAIT_TOOL, exhausted, resultRunId = AgentRunId("old-run")),
            controlResult(BackgroundToolInstructions.READ_TOOL, exhausted),
        )

        assertFalse(history.awaitAllowanceExhaustedEarlier(runId))
        assertTrue(
            (history + controlResult(BackgroundToolInstructions.AWAIT_TOOL, exhausted))
                .awaitAllowanceExhaustedEarlier(runId),
        )
    }

    private fun controlResult(
        name: String,
        result: JsonElement,
        resultHandle: JsonElement = JsonPrimitive(handle),
        resultRunId: AgentRunId = runId,
        isError: Boolean = false,
    ): AgentMessage = AgentMessage(
        role = MessageRole.TOOL,
        parts = listOf(
            ToolResultPart(
                toolCallId = "control-call",
                toolName = name,
                result = result,
                isError = isError,
                metadata = JsonObject(
                    mapOf(
                        BackgroundToolProtocol.CONTROL_RESULT_METADATA_KEY to JsonPrimitive(true),
                        BackgroundToolProtocol.CONTROL_RUN_ID_METADATA_KEY to JsonPrimitive(resultRunId.value),
                        BackgroundToolProtocol.HANDLE_METADATA_KEY to resultHandle,
                    ),
                ),
            ),
        ),
    )
}
