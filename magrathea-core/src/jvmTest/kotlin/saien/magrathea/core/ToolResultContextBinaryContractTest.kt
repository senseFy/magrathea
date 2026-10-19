package saien.magrathea.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

class ToolResultContextBinaryContractTest {
    private val int = Int::class.javaPrimitiveType!!
    private val long = Long::class.javaPrimitiveType!!
    private val boolean = Boolean::class.javaPrimitiveType!!
    private val string = String::class.java
    private val boxedLong = Long::class.javaObjectType
    private val defaultMarker = Class.forName("kotlin.jvm.internal.DefaultConstructorMarker")

    /** Constructor and copy parameters published before Tool-result context management. */
    private val published: Map<Class<*>, Array<Class<*>>> = mapOf(
        ContextManagementConfig::class.java to arrayOf(boolean, long, long, int, int, int, boxedLong, int),
        ContextManagementState::class.java to arrayOf(
            ContextCompaction::class.java,
            ContextUsageObservation::class.java,
        ),
        ContextUsageObservation::class.java to arrayOf(long, string, string, long, string, string, string),
        ContextPreparationRequest::class.java to arrayOf(
            AgentRequest::class.java,
            AgentStateSnapshot::class.java,
            int,
            ContextPreparationReason::class.java,
        ),
        ContextPreparationResult::class.java to arrayOf(
            List::class.java,
            ContextManagementState::class.java,
            boxedLong,
            boxedLong,
            ContextPreparationAction::class.java,
            TokenUsage::class.java,
            ContextPreparationFailure::class.java,
        ),
        ToolExecutionRequest::class.java to arrayOf(
            AgentSessionId::class.java,
            AgentRunId::class.java,
            string,
            AgentMessage::class.java,
            ToolCallPart::class.java,
        ),
        ToolExecutionResult::class.java to arrayOf(
            string, string, JsonElement::class.java, boolean, string, string, JsonObject::class.java,
            List::class.java, boolean, ToolOrigin::class.java,
        ),
        ToolResultPart::class.java to arrayOf(
            string, string, JsonElement::class.java, boolean, string, string, JsonObject::class.java,
            List::class.java, JsonObject::class.java, boolean, ToolOrigin::class.java,
        ),
    )

    /** Their published constructors declared no default arguments. */
    private val withoutDefaultConstructor = setOf(
        ContextUsageObservation::class.java,
        ToolExecutionRequest::class.java,
    )

    @Test
    fun publishedConstructorAndCopySignaturesRemainCallable() {
        published.forEach { (type, parameters) ->
            type.getConstructor(*parameters)
            type.getMethod("copy", *parameters)
            type.getDeclaredMethod("copy\$default", type, *parameters, int, Any::class.java)
            if (type !in withoutDefaultConstructor) {
                type.getConstructor(*parameters, int, defaultMarker)
            }
        }
    }

    @Test
    fun publishedConstructorsApplyTheCurrentDefaults() {
        val configType = ContextManagementConfig::class.java
        val config = configType.getConstructor(*published.getValue(configType))
            .newInstance(true, 16_384L, 20_000L, 4_096, 4, 2_000, null, 1)
        assertEquals(ContextManagementConfig(), config)

        val partType = ToolResultPart::class.java
        val part = partType.getConstructor(*published.getValue(partType)).newInstance(
            "call", "tool", JsonPrimitive("ok"), false, null, null, buildJsonObject { },
            emptyList<ToolResultContent>(), null, true, null,
        ) as ToolResultPart
        assertNull(part.contextHints)
    }

    @Test
    fun publishedCopiesPreserveFieldsAddedLater() {
        val config = ContextManagementConfig(
            toolResults = ToolResultContextConfig.DISABLED,
            estimationUnit = ContextEstimationUnit.UTF16_CHARS,
        )
        val configType = ContextManagementConfig::class.java
        val copiedConfig = configType.getDeclaredMethod(
            "copy\$default", configType, *published.getValue(configType), int, Any::class.java,
        ).invoke(null, config, false, 0L, 0L, 0, 0, 0, null, 0, 0b1111_1110, null)
        assertEquals(
            ContextManagementConfig(
                enabled = false,
                toolResults = ToolResultContextConfig.DISABLED,
                estimationUnit = ContextEstimationUnit.UTF16_CHARS,
            ),
            copiedConfig,
        )

        val clearing = ToolResultClearing(
            clearedThroughMessageId = "message",
            sourcePrefixDigest = "digest",
            generation = 1,
            clearedResults = 2,
            freedTokens = 300,
            trigger = ToolResultClearingTrigger.PRESSURE,
            createdAtEpochMs = 1,
        )
        val stateType = ContextManagementState::class.java
        val copiedState = stateType.getMethod("copy", *published.getValue(stateType))
            .invoke(ContextManagementState(toolResultClearing = clearing), null, null) as ContextManagementState
        assertEquals(clearing, copiedState.toolResultClearing)

        val hints = ToolResultContextHints(retention = ToolResultRetention.PINNED)
        val partType = ToolResultPart::class.java
        val copiedPart = partType.getMethod("copy", *published.getValue(partType)).invoke(
            ToolResultPart("call", "tool", JsonPrimitive("ok"), contextHints = hints),
            "call", "tool", JsonPrimitive("changed"), false, null, null, buildJsonObject { },
            emptyList<ToolResultContent>(), null, true, null,
        ) as ToolResultPart
        assertEquals(JsonPrimitive("changed"), copiedPart.result)
        assertEquals(hints, copiedPart.contextHints)

        val call = ToolCallPart(toolCallId = "call", toolName = "tool", arguments = buildJsonObject { })
        val request = ToolExecutionRequest(
            sessionId = AgentSessionId("session"),
            runId = AgentRunId("run"),
            executionId = "execution",
            assistantMessage = AgentMessage(role = MessageRole.ASSISTANT, parts = listOf(call)),
            toolCall = call,
            resultBudgetTokens = 512,
        )
        val requestType = ToolExecutionRequest::class.java
        val copiedRequest = requestType.getDeclaredMethod(
            "copy\$default", requestType, *published.getValue(requestType), int, Any::class.java,
        ).invoke(null, request, null, null, "recovered", null, null, 0b1_1011, null) as ToolExecutionRequest
        assertEquals("recovered", copiedRequest.executionId)
        assertEquals(512, copiedRequest.resultBudgetTokens)
    }
}
