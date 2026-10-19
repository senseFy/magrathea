package saien.magrathea.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
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
import saien.magrathea.core.ContextEstimationUnit
import saien.magrathea.core.ContextManagementConfig
import saien.magrathea.core.ContextManagementState
import saien.magrathea.core.ContextPreparationAction
import saien.magrathea.core.ContextPreparationReason
import saien.magrathea.core.ContextPreparationRequest
import saien.magrathea.core.ContextSummaryRequest
import saien.magrathea.core.ContextSummaryResult
import saien.magrathea.core.ContextTokenThreshold
import saien.magrathea.core.EpochClock
import saien.magrathea.core.MessageRole
import saien.magrathea.core.ModelDescriptor
import saien.magrathea.core.ModelInputModality
import saien.magrathea.core.ProviderConfig
import saien.magrathea.core.RemoteToolImageSource
import saien.magrathea.core.RuntimeConfig
import saien.magrathea.core.TextPart
import saien.magrathea.core.ToolCallPart
import saien.magrathea.core.ToolResultAudience
import saien.magrathea.core.ToolResultClearingConfig
import saien.magrathea.core.ToolResultClearingTrigger
import saien.magrathea.core.ToolResultContextConfig
import saien.magrathea.core.ToolResultContextHints
import saien.magrathea.core.ToolResultImageContent
import saien.magrathea.core.ToolResultPart
import saien.magrathea.core.ToolResultRetention
import saien.magrathea.core.ToolResultTextContent

class ToolResultContextTest {
    @Test
    fun thresholdsResolveAgainstTheInputLimitWithAnAbsoluteFallback() {
        val threshold = ContextTokenThreshold(inputPercent = 50, maxTokens = 100_000)

        assertEquals(5_000, threshold.resolve(10_000))
        assertEquals(100_000, threshold.resolve(1_000_000))
        assertEquals(100_000, threshold.resolve(null))
        assertNull(ContextTokenThreshold(inputPercent = 20).resolve(null))
        assertEquals(1, ContextTokenThreshold(inputPercent = 1).resolve(10))
    }

    @Test
    fun budgetReducesOversizedResultsDeterministicallyAndKeepsSmallOnesIntact() = runTest {
        val small = toolResult("call-small", JsonPrimitive("tiny"))
        val large = toolResult("call-large", JsonPrimitive("HEAD-" + "x".repeat(4_000) + "-TAIL"))
        val messages = listOf(
            user("u1"),
            assistantCalls("a1", "call-small", "call-large"),
            AgentMessage(id = "t1", role = MessageRole.TOOL, parts = listOf(small, large)),
        )
        val config = toolResultsConfig(budget = ContextTokenThreshold(maxTokens = 100), clearing = null)
        val manager = manager()

        val first = manager.prepare(preparation(messages, config))
        val second = manager.prepare(preparation(messages, config))

        val parts = first.messages.single { it.id == "t1" }.parts.filterIsInstance<ToolResultPart>()
        assertSame(small, parts[0])
        val reduced = parts[1].result.jsonPrimitive.content
        assertTrue(reduced.startsWith("[Tool result truncated to fit the model context"))
        assertTrue("HEAD-" in reduced && "-TAIL" in reduced)
        assertTrue(ContextTextEstimator(ContextEstimationUnit.UTF8_BYTES, 4).tokens(reduced) <= 100)
        assertEquals(first.messages, second.messages)
        assertEquals(1, first.toolResults.reducedResults)
        assertTrue(first.toolResults.reducedTokens > 900)
        assertEquals(messages, preparation(messages, config).state.messages)
    }

    @Test
    fun budgetKeepsModelImagesWhileBoundingText() = runTest {
        val image = ToolResultImageContent(
            source = RemoteToolImageSource("https://images.example.com/a.png"),
            mimeType = "image/png",
            audiences = setOf(ToolResultAudience.MODEL),
        )
        val result = ToolResultPart(
            toolCallId = "call-1",
            toolName = "image_search",
            result = JsonPrimitive("summary"),
            content = listOf(
                ToolResultTextContent("d".repeat(4_000), setOf(ToolResultAudience.MODEL)),
                image,
            ),
        )
        val messages = listOf(
            user("u1"),
            assistantCalls("a1", "call-1"),
            AgentMessage(id = "t1", role = MessageRole.TOOL, parts = listOf(result)),
        )

        val prepared = manager().prepare(
            preparation(
                messages,
                toolResultsConfig(budget = ContextTokenThreshold(maxTokens = 100), clearing = null),
                modalities = setOf(ModelInputModality.TEXT, ModelInputModality.IMAGE),
            ),
        )

        val projected = prepared.messages.single { it.id == "t1" }.parts.single() as ToolResultPart
        assertEquals(listOf(image), projected.content)
        assertTrue(projected.result.jsonPrimitive.content.startsWith("[Tool result truncated"))
    }

    @Test
    fun pressureClearsOlderResultsButKeepsRecentOnesAndTheActiveRun() = runTest {
        val messages = completedRuns(3) + activeRun()

        val prepared = manager().prepare(preparation(messages, clearingConfig()))

        assertStub(prepared.messages, "result1", retainedQuery = "q1")
        assertStub(prepared.messages, "result2", retainedQuery = "q2")
        assertIntact(prepared.messages, "result3")
        assertIntact(prepared.messages, "active-result")
        val clearing = assertNotNull(prepared.state.toolResultClearing)
        assertEquals("result2", clearing.clearedThroughMessageId)
        assertEquals(1, clearing.generation)
        assertEquals(ToolResultClearingTrigger.PRESSURE, clearing.trigger)
        assertEquals(2, prepared.toolResults.newlyClearedResults)
        assertEquals(ToolResultClearingTrigger.PRESSURE, prepared.toolResults.clearingTrigger)
        assertTrue(prepared.toolResults.freedTokens > 0)
        assertEquals(ContextPreparationAction.UNCHANGED, prepared.action)
    }

    @Test
    fun pinnedResultsAreNeverCleared() = runTest {
        val messages = (completedRuns(3) + activeRun()).map { message ->
            if (message.id != "result1") return@map message
            message.copy(
                parts = message.parts.map { part ->
                    (part as ToolResultPart).copy(
                        contextHints = ToolResultContextHints(retention = ToolResultRetention.PINNED),
                    )
                },
            )
        }

        val prepared = manager().prepare(preparation(messages, clearingConfig()))

        assertIntact(prepared.messages, "result1")
        assertStub(prepared.messages, "result2", retainedQuery = "q2")
        assertEquals(1, prepared.toolResults.newlyClearedResults)
    }

    @Test
    fun passesBelowTheMinimumAreSkipped() = runTest {
        val config = clearingConfig(clearAtLeast = ContextTokenThreshold(maxTokens = 1_000_000))

        val prepared = manager().prepare(preparation(completedRuns(3) + activeRun(), config))

        assertNull(prepared.state.toolResultClearing)
        assertNull(prepared.toolResults.clearingTrigger)
        assertIntact(prepared.messages, "result1")
    }

    @Test
    fun idleClearingRunsOnlyOnceTheProviderCacheIsLikelyCold() = runTest {
        val messages = completedRuns(3) + activeRun()
        val lastAssistant = messages.last { it.role == MessageRole.ASSISTANT }.createdAtEpochMs
        val config = clearingConfig(
            trigger = ContextTokenThreshold(maxTokens = 1_000_000),
            idleAfterMillis = 60_000,
        )

        val warm = manager(now = lastAssistant + 10_000).prepare(preparation(messages, config))
        val cold = manager(now = lastAssistant + 61_000).prepare(preparation(messages, config))

        assertNull(warm.state.toolResultClearing)
        assertEquals(ToolResultClearingTrigger.IDLE, cold.state.toolResultClearing?.trigger)
        assertStub(cold.messages, "result1", retainedQuery = "q1")
    }

    @Test
    fun overflowRecoveryClearsBelowTheMinimumAndSummarizesStubs() = runTest {
        var summaryRequest: ContextSummaryRequest? = null
        val manager = TokenAwareContextManager(
            summarizer = { request ->
                summaryRequest = request
                ContextSummaryResult("earlier lookups")
            },
            clock = EpochClock { 0L },
        )
        val config = clearingConfig(clearAtLeast = ContextTokenThreshold(maxTokens = 1_000_000))

        val prepared = manager.prepare(
            preparation(
                completedRuns(3) + activeRun(),
                config,
                reason = ContextPreparationReason.OVERFLOW_RECOVERY,
            ),
        )

        assertEquals(ToolResultClearingTrigger.OVERFLOW_RECOVERY, prepared.state.toolResultClearing?.trigger)
        assertEquals(ContextPreparationAction.COMPACTED, prepared.action)
        val conversation = assertNotNull(summaryRequest).conversation
        assertTrue("tool_result_cleared" in conversation)
        // Only the protected third result reaches the summarizer in full; cleared ones are stubs.
        assertEquals(1, conversation.split("r".repeat(600)).size - 1)
        assertNotNull(prepared.state.toolResultClearing)
    }

    @Test
    fun parallelResultsInOneMessageAreClearedTogether() = runTest {
        val messages = completedRuns(1) + listOf(
            user("u2"),
            assistantCalls("a2", "call-2a", "call-2b"),
            AgentMessage(
                id = "parallel",
                role = MessageRole.TOOL,
                parts = listOf(
                    toolResult("call-2a", JsonPrimitive("a".repeat(600)), query = "q2a"),
                    toolResult("call-2b", JsonPrimitive("b".repeat(600)), query = "q2b"),
                ),
            ),
            AgentMessage(id = "answer2", role = MessageRole.ASSISTANT, parts = listOf(TextPart("done"))),
        ) + activeRun()

        val prepared = manager().prepare(preparation(messages, clearingConfig()))

        assertStub(prepared.messages, "result1", retainedQuery = "q1")
        val parallel = prepared.messages.single { it.id == "parallel" }.parts.filterIsInstance<ToolResultPart>()
        assertTrue(parallel.none { it.result is JsonObject && "tool_result_cleared" in it.result.toString() })
        assertEquals("result1", prepared.state.toolResultClearing?.clearedThroughMessageId)
    }

    @Test
    fun theWatermarkAdvancesMonotonicallyAcrossPasses() = runTest {
        val manager = manager()
        val firstMessages = completedRuns(3) + activeRun()
        val first = manager.prepare(preparation(firstMessages, clearingConfig()))
        val secondMessages = completedRuns(5) + activeRun()

        val second = manager.prepare(
            preparation(
                secondMessages,
                clearingConfig(),
                state = AgentStateSnapshot(messages = secondMessages, contextManagement = first.state),
            ),
        )

        assertEquals(2, second.state.toolResultClearing?.generation)
        assertEquals("result4", second.state.toolResultClearing?.clearedThroughMessageId)
        listOf("result1", "result2", "result3", "result4").forEach { id ->
            assertStub(second.messages, id, retainedQuery = "q" + id.removePrefix("result"))
        }
        assertIntact(second.messages, "result5")
        assertEquals(4, second.state.toolResultClearing?.clearedResults)
    }

    @Test
    fun editingTheClearedPrefixInvalidatesTheWatermark() = runTest {
        val manager = manager()
        val messages = completedRuns(3) + activeRun()
        val first = manager.prepare(preparation(messages, clearingConfig()))
        val edited = messages.map { message ->
            if (message.id == "u1") message.copy(parts = listOf(TextPart("edited question"))) else message
        }
        val relaxed = clearingConfig(trigger = ContextTokenThreshold(maxTokens = 1_000_000))

        val prepared = manager.prepare(
            preparation(
                edited,
                relaxed,
                state = AgentStateSnapshot(messages = edited, contextManagement = first.state),
            ),
        )

        assertNull(prepared.state.toolResultClearing)
        assertIntact(prepared.messages, "result1")
    }

    @Test
    fun aClearingPassInvalidatesProviderReportedUsage() = runTest {
        val messages = completedRuns(3) + activeRun()
        val request = request(messages, clearingConfig())
        val observed = ContextManagementState().withUsageObservation(
            request = request,
            messages = messages,
            throughMessageId = messages.last().id,
            inputTokens = 5_000,
        )

        val prepared = manager().prepare(
            ContextPreparationRequest(
                request = request,
                state = AgentStateSnapshot(messages = messages, contextManagement = observed),
                turn = 0,
                activeRunStartMessageId = "active-user",
            ),
        )

        assertNotNull(prepared.state.toolResultClearing)
        assertNull(prepared.state.usageObservation)
    }

    @Test
    fun oversizedToolStubsFallBackToTheGenericStub() = runTest {
        val messages = (completedRuns(3) + activeRun()).map { message ->
            if (message.id != "result1") return@map message
            message.copy(
                parts = message.parts.map { part ->
                    (part as ToolResultPart).copy(
                        contextHints = ToolResultContextHints(
                            stub = buildJsonObject { put("query", JsonPrimitive("q".repeat(300))) },
                        ),
                    )
                },
            )
        }
        val config = clearingConfig().copy(maxStubChars = 100)

        val prepared = manager().prepare(preparation(messages, config))

        val stub = stubResult(prepared.messages, "result1")
        assertFalse("retained" in stub)
        assertEquals("lookup", stub.getValue("tool").jsonPrimitive.content)
        assertEquals(1, prepared.toolResults.ignoredStubs)
    }

    @Test
    fun theActiveRunIsClearedOnlyWhenItsProtectionIsOff() = runTest {
        val messages = completedRuns(1) + listOf(
            user("active-user"),
            assistantCalls("active-a1", "active-call-1"),
            AgentMessage(
                id = "active-result-1",
                role = MessageRole.TOOL,
                parts = listOf(toolResult("active-call-1", JsonPrimitive("s".repeat(600)), query = "s1")),
            ),
            assistantCalls("active-a2", "active-call-2"),
            AgentMessage(
                id = "active-result-2",
                role = MessageRole.TOOL,
                parts = listOf(toolResult("active-call-2", JsonPrimitive("t".repeat(600)), query = "s2")),
            ),
        )
        val unprotected = clearingConfig(protectActiveRun = false)

        val protectedRun = manager().prepare(preparation(messages, clearingConfig()))
        val openRun = manager().prepare(preparation(messages, unprotected))

        assertIntact(protectedRun.messages, "active-result-1")
        assertStub(openRun.messages, "active-result-1", retainedQuery = "s1")
        assertIntact(openRun.messages, "active-result-2")
    }

    @Test
    fun utf8EstimationCountsChineseTextConservatively() {
        val text = "中文搜索结果".repeat(100)

        assertEquals(450, ContextTextEstimator(ContextEstimationUnit.UTF8_BYTES, 4).tokens(text))
        assertEquals(150, ContextTextEstimator(ContextEstimationUnit.UTF16_CHARS, 4).tokens(text))
    }

    private fun manager(now: Long = 0L) = TokenAwareContextManager(
        summarizer = { ContextSummaryResult("summary") },
        clock = EpochClock { now },
    )

    private fun clearingConfig(
        trigger: ContextTokenThreshold = ContextTokenThreshold(maxTokens = 300),
        clearAtLeast: ContextTokenThreshold = ContextTokenThreshold(maxTokens = 50),
        idleAfterMillis: Long? = null,
        protectActiveRun: Boolean = true,
    ) = toolResultsConfig(
        budget = null,
        clearing = ToolResultClearingConfig(
            trigger = trigger,
            keepRecentResults = 1,
            clearAtLeast = clearAtLeast,
            idleAfterMillis = idleAfterMillis,
            protectActiveRun = protectActiveRun,
        ),
    )

    private fun toolResultsConfig(
        budget: ContextTokenThreshold?,
        clearing: ToolResultClearingConfig?,
    ) = ToolResultContextConfig(budget = budget, clearing = clearing)

    private fun preparation(
        messages: List<AgentMessage>,
        toolResults: ToolResultContextConfig,
        reason: ContextPreparationReason = ContextPreparationReason.PROACTIVE,
        state: AgentStateSnapshot = AgentStateSnapshot(messages = messages),
        modalities: Set<ModelInputModality> = setOf(ModelInputModality.TEXT),
    ) = ContextPreparationRequest(
        request = request(messages, toolResults, modalities),
        state = state,
        turn = 0,
        reason = reason,
        activeRunStartMessageId = messages.firstOrNull { it.id == "active-user" }?.id,
    )

    private fun request(
        messages: List<AgentMessage>,
        toolResults: ToolResultContextConfig,
        modalities: Set<ModelInputModality> = setOf(ModelInputModality.TEXT),
    ) = AgentRequest(
        sessionId = AgentSessionId("tool-result-context"),
        messages = messages,
        model = ModelDescriptor(
            provider = "test",
            model = "test-model",
            contextWindowTokens = 100_000,
            inputModalities = modalities,
        ),
        engine = AgentEngineConfig(
            provider = ProviderConfig(maxTokens = 50),
            runtime = RuntimeConfig(
                contextManagement = ContextManagementConfig(
                    reserveTokens = 100,
                    keepRecentTokens = 200,
                    summaryMaxTokens = 64,
                    charsPerTokenEstimate = 4,
                    toolResults = toolResults,
                ),
            ),
        ),
    )

    private fun completedRuns(count: Int): List<AgentMessage> = (1..count).flatMap { index ->
        listOf(
            user("u$index", createdAt = index * 10L),
            assistantCalls("call-message$index", "call-$index", createdAt = index * 10L + 1),
            AgentMessage(
                id = "result$index",
                role = MessageRole.TOOL,
                parts = listOf(toolResult("call-$index", JsonPrimitive("r".repeat(600)), query = "q$index")),
                createdAtEpochMs = index * 10L + 2,
            ),
            AgentMessage(
                id = "answer$index",
                role = MessageRole.ASSISTANT,
                parts = listOf(TextPart("answer $index")),
                createdAtEpochMs = index * 10L + 3,
            ),
        )
    }

    private fun activeRun(): List<AgentMessage> = listOf(
        user("active-user", createdAt = 1_000),
        assistantCalls("active-call-message", "active-call", createdAt = 1_001),
        AgentMessage(
            id = "active-result",
            role = MessageRole.TOOL,
            parts = listOf(toolResult("active-call", JsonPrimitive("n".repeat(600)), query = "now")),
            createdAtEpochMs = 1_002,
        ),
    )

    private fun user(id: String, createdAt: Long = 0L) = AgentMessage(
        id = id,
        role = MessageRole.USER,
        parts = listOf(TextPart("question $id")),
        createdAtEpochMs = createdAt,
    )

    private fun assistantCalls(id: String, vararg callIds: String, createdAt: Long = 0L) = AgentMessage(
        id = id,
        role = MessageRole.ASSISTANT,
        parts = callIds.map { callId ->
            ToolCallPart(
                toolCallId = callId,
                toolName = "lookup",
                arguments = buildJsonObject { put("q", JsonPrimitive(callId)) },
            )
        },
        createdAtEpochMs = createdAt,
    )

    private fun toolResult(callId: String, result: JsonPrimitive, query: String? = null) = ToolResultPart(
        toolCallId = callId,
        toolName = "lookup",
        result = result,
        contextHints = query?.let { ToolResultContextHints(stub = buildJsonObject { put("query", JsonPrimitive(it)) }) },
    )

    private fun stubResult(messages: List<AgentMessage>, id: String): JsonObject {
        val part = messages.single { it.id == id }.parts.single() as ToolResultPart
        val result = part.result as? JsonObject ?: error("$id was not cleared: ${part.result}")
        assertEquals("tool_result_cleared", result.getValue("type").jsonPrimitive.content)
        assertTrue(part.content.isEmpty())
        return result
    }

    private fun assertStub(messages: List<AgentMessage>, id: String, retainedQuery: String) {
        val stub = stubResult(messages, id)
        assertEquals(retainedQuery, stub.getValue("retained").jsonObject.getValue("query").jsonPrimitive.content)
    }

    private fun assertIntact(messages: List<AgentMessage>, id: String) {
        val part = messages.single { it.id == id }.parts.single() as ToolResultPart
        assertTrue(part.result is JsonPrimitive, "$id should keep its original result")
    }
}
