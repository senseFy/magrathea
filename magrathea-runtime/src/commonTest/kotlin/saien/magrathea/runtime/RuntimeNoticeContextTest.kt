package saien.magrathea.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import saien.magrathea.core.AgentEngineConfig
import saien.magrathea.core.AgentMessage
import saien.magrathea.core.AgentRequest
import saien.magrathea.core.AgentSessionId
import saien.magrathea.core.AgentStateSnapshot
import saien.magrathea.core.BackgroundOutcome
import saien.magrathea.core.ContextEstimationUnit
import saien.magrathea.core.ContextManagementConfig
import saien.magrathea.core.ContextPreparationRequest
import saien.magrathea.core.ContextSummaryResult
import saien.magrathea.core.ContextTokenThreshold
import saien.magrathea.core.MessageRole
import saien.magrathea.core.ModelDescriptor
import saien.magrathea.core.ModelInputModality
import saien.magrathea.core.ProviderConfig
import saien.magrathea.core.RuntimeConfig
import saien.magrathea.core.RuntimeNoticeDelivery
import saien.magrathea.core.RuntimeNoticeEntry
import saien.magrathea.core.RuntimeNoticePart
import saien.magrathea.core.TextPart
import saien.magrathea.core.ToolResultContextHints
import saien.magrathea.core.ToolResultClearingConfig
import saien.magrathea.core.ToolResultContextConfig
import saien.magrathea.core.ToolResultPart

class RuntimeNoticeContextTest {
    @Test
    fun aRuntimeNoticeCountsTowardTheTokenEstimate() {
        val estimator = ContextTextEstimator(ContextEstimationUnit.UTF16_CHARS, 4)
        val base = listOf(user("u1"))
        val withNotice = base + runtimeNotice("n1")

        assertTrue(
            estimateMessages(withNotice, TEXT_MODALITIES, estimator) >
                estimateMessages(base, TEXT_MODALITIES, estimator),
        )
    }

    @Test
    fun theLastUserAnchorIgnoresRuntimeNotices() = runTest {
        // Without an explicit run-start anchor, the fallback anchor is the last USER message.
        // The RUNTIME notice after u2 must not become the anchor: the active-run protection must
        // still start at u2, leaving the older run's results clearable.
        val messages = listOf(
            user("u1"),
            resultMessage("result-a", "a".repeat(600), "q-a"),
            resultMessage("result-b", "b".repeat(600), "q-b"),
            user("u2"),
            runtimeNotice("n1"),
            resultMessage("result-new", "c".repeat(600), "q-new"),
        )

        val prepared = manager().prepare(
            ContextPreparationRequest(
                request = request(messages),
                state = AgentStateSnapshot(messages = messages),
                turn = 0,
                activeRunStartMessageId = null,
            ),
        )

        val clearing = assertNotNull(prepared.state.toolResultClearing)
        assertEquals("result-a", clearing.clearedThroughMessageId)
        assertStub(prepared.messages, "result-a", retainedQuery = "q-a")
        assertIntact(prepared.messages, "result-b")
        assertIntact(prepared.messages, "result-new")
    }

    @Test
    fun aRuntimeNoticeRendersAsANoticeInCompactionInput() {
        val serialized = serializeContextConversation(
            messages = listOf(
                user("u1"),
                runtimeNotice("n1"),
            ),
            maxToolResultChars = 2_000,
            modelInputModalities = TEXT_MODALITIES,
        )

        assertTrue(serialized.contains("[runtime notice]"))
        assertTrue(serialized.contains(NOTICE_TEXT.trim()))
    }

    private fun manager() = TokenAwareContextManager(
        summarizer = { ContextSummaryResult("summary") },
    )

    private fun request(messages: List<AgentMessage>) = AgentRequest(
        sessionId = AgentSessionId("runtime-notice-context"),
        messages = messages,
        model = ModelDescriptor(
            provider = "test",
            model = "test-model",
            contextWindowTokens = 100_000,
            inputModalities = TEXT_MODALITIES,
        ),
        engine = AgentEngineConfig(
            provider = ProviderConfig(maxTokens = 50),
            runtime = RuntimeConfig(
                contextManagement = ContextManagementConfig(
                    reserveTokens = 100,
                    keepRecentTokens = 200,
                    summaryMaxTokens = 64,
                    charsPerTokenEstimate = 4,
                    toolResults = ToolResultContextConfig(
                        clearing = ToolResultClearingConfig(
                            trigger = ContextTokenThreshold(maxTokens = 1),
                            keepRecentResults = 1,
                            clearAtLeast = ContextTokenThreshold(maxTokens = 50),
                            protectActiveRun = true,
                        ),
                    ),
                ),
            ),
        ),
    )

    private fun user(id: String) = AgentMessage(
        id = id,
        role = MessageRole.USER,
        parts = listOf(TextPart("question $id")),
    )

    private fun runtimeNotice(id: String) = AgentMessage(
        id = id,
        role = MessageRole.RUNTIME,
        parts = listOf(
            RuntimeNoticePart(
                entries = listOf(
                    RuntimeNoticeEntry(
                        handle = "bg_1",
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

    private fun resultMessage(id: String, result: String, stubQuery: String) = AgentMessage(
        id = id,
        role = MessageRole.TOOL,
        parts = listOf(
            ToolResultPart(
                toolCallId = "call-$id",
                toolName = "lookup",
                result = JsonPrimitive(result),
                contextHints = ToolResultContextHints(
                    stub = buildJsonObject { put("query", JsonPrimitive(stubQuery)) },
                ),
            ),
        ),
    )

    private fun assertStub(messages: List<AgentMessage>, id: String, retainedQuery: String) {
        val part = messages.single { it.id == id }.parts.single() as ToolResultPart
        val result = part.result as? JsonObject ?: error("$id was not cleared: ${part.result}")
        assertEquals("tool_result_cleared", result.getValue("type").jsonPrimitive.content)
        assertEquals(
            retainedQuery,
            result.getValue("retained").jsonObject.getValue("query").jsonPrimitive.content,
        )
    }

    private fun assertIntact(messages: List<AgentMessage>, id: String) {
        val part = messages.single { it.id == id }.parts.single() as ToolResultPart
        assertTrue(part.result is JsonPrimitive, "$id should keep its original result")
    }

    private companion object {
        val TEXT_MODALITIES = setOf(ModelInputModality.TEXT)
        const val NOTICE_TEXT = "<runtime_notice>\nA background result is available.\n</runtime_notice>"
    }
}
