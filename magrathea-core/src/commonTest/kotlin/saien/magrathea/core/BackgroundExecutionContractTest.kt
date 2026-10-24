package saien.magrathea.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BackgroundExecutionContractTest {
    private val json = Json {
        classDiscriminator = "type"
        encodeDefaults = false
        ignoreUnknownKeys = true
    }

    @Test
    fun backgroundExecutionConfigValidatesItsBudgets() {
        val config = BackgroundExecutionConfig(
            handoffWaitMillis = 0,
            explicitWaitAllowanceMillis = 0,
            maxAutomaticActivationsPerSource = 0,
            maxQueuedLifetimeMillis = 1,
        )
        assertEquals(BackgroundConsumptionPolicy.LATEST_CONTEXT, config.consumptionPolicy)
        assertEquals(false, config.allowBusinessToolsInContinuations)

        assertFailsWith<IllegalArgumentException> {
            config.copy(handoffWaitMillis = -1)
        }
        assertFailsWith<IllegalArgumentException> {
            config.copy(explicitWaitAllowanceMillis = -1)
        }
        assertFailsWith<IllegalArgumentException> {
            config.copy(maxAutomaticActivationsPerSource = -1)
        }
        assertFailsWith<IllegalArgumentException> {
            config.copy(maxQueuedLifetimeMillis = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            config.copy(maxQueuedLifetimeMillis = -1)
        }
    }

    @Test
    fun toolBackgroundReceiptRequiresAnUnfinishedHandle() {
        ToolBackgroundReceipt(
            handle = "bg_1",
            executionId = "exec-1",
            state = BackgroundExecutionState.QUEUED,
        )
        ToolBackgroundReceipt(
            handle = "bg_1",
            executionId = "exec-1",
            state = BackgroundExecutionState.RUNNING,
        )

        assertFailsWith<IllegalArgumentException> {
            ToolBackgroundReceipt("", "exec-1", BackgroundExecutionState.QUEUED)
        }
        assertFailsWith<IllegalArgumentException> {
            ToolBackgroundReceipt("  ", "exec-1", BackgroundExecutionState.QUEUED)
        }
        assertFailsWith<IllegalArgumentException> {
            ToolBackgroundReceipt("bg_1", "", BackgroundExecutionState.QUEUED)
        }
        assertFailsWith<IllegalArgumentException> {
            ToolBackgroundReceipt("bg_1", "exec-1", BackgroundExecutionState.TERMINAL)
        }
    }

    @Test
    fun runtimeNoticeEntryRejectsCancelledAndBlankIdentity() {
        val entry = RuntimeNoticeEntry(
            handle = "bg_1",
            toolName = "slow_lookup",
            callId = "c1",
            outcome = BackgroundOutcome.SUCCEEDED,
            resultVersion = 0,
        )
        assertEquals(0, entry.resultVersion)

        assertFailsWith<IllegalArgumentException> {
            entry.copy(handle = "")
        }
        assertFailsWith<IllegalArgumentException> {
            entry.copy(toolName = "")
        }
        assertFailsWith<IllegalArgumentException> {
            entry.copy(callId = "")
        }
        assertFailsWith<IllegalArgumentException> {
            entry.copy(outcome = BackgroundOutcome.CANCELLED)
        }
        assertFailsWith<IllegalArgumentException> {
            entry.copy(resultVersion = -1)
        }
        listOf(
            BackgroundOutcome.SUCCEEDED,
            BackgroundOutcome.ERROR,
            BackgroundOutcome.TIMEOUT,
            BackgroundOutcome.INTERRUPTED,
        ).forEach { outcome -> entry.copy(outcome = outcome) }
    }

    @Test
    fun runtimeNoticePartRequiresEntriesAndText() {
        val part = RuntimeNoticePart(
            entries = listOf(noticeEntry()),
            delivery = RuntimeNoticeDelivery.CONTINUATION,
            text = "<runtime_notice>\ndone\n</runtime_notice>",
        )
        assertEquals(1, part.entries.size)

        assertFailsWith<IllegalArgumentException> {
            part.copy(entries = emptyList())
        }
        assertFailsWith<IllegalArgumentException> {
            part.copy(text = "")
        }
        assertFailsWith<IllegalArgumentException> {
            part.copy(text = "   ")
        }
    }

    @Test
    fun runActivationRequiresAnAnchorOnlyForBackgroundResults() {
        RunActivation(source = RunActivationSource.USER_INPUT)
        RunActivation(source = RunActivationSource.USER_INPUT, anchorMessageId = "m1")

        assertFailsWith<IllegalArgumentException> {
            RunActivation(source = RunActivationSource.BACKGROUND_RESULTS)
        }
        assertFailsWith<IllegalArgumentException> {
            RunActivation(
                source = RunActivationSource.BACKGROUND_RESULTS,
                anchorMessageId = "m1",
            )
        }
        assertFailsWith<IllegalArgumentException> {
            RunActivation(
                source = RunActivationSource.BACKGROUND_RESULTS,
                anchorMessageId = " ",
                sourceIds = listOf("s1"),
            )
        }
        RunActivation(
            source = RunActivationSource.BACKGROUND_RESULTS,
            anchorMessageId = "m1",
            sourceIds = listOf("s1"),
        )
    }

    @Test
    fun agentStateSnapshotRejectsNegativeWaitCounters() {
        AgentStateSnapshot(messages = emptyList(), foregroundToolWaitMillis = 0, explicitWaitMillis = 0)
        assertFailsWith<IllegalArgumentException> {
            AgentStateSnapshot(messages = emptyList(), foregroundToolWaitMillis = -1)
        }
        assertFailsWith<IllegalArgumentException> {
            AgentStateSnapshot(messages = emptyList(), explicitWaitMillis = -1)
        }
    }

    @Test
    fun backgroundExecutionRecordKeepsStateOutcomeAndResultConsistent() {
        val queued = record(state = BackgroundExecutionState.QUEUED)
        assertEquals(null, queued.outcome)
        val running = queued.copy(state = BackgroundExecutionState.RUNNING)
        val terminal = running.copy(
            state = BackgroundExecutionState.TERMINAL,
            outcome = BackgroundOutcome.SUCCEEDED,
            result = toolResult(),
        )

        assertFailsWith<IllegalArgumentException> {
            queued.copy(state = BackgroundExecutionState.TERMINAL)
        }
        assertFailsWith<IllegalArgumentException> {
            queued.copy(outcome = BackgroundOutcome.ERROR)
        }
        assertFailsWith<IllegalArgumentException> {
            queued.copy(result = toolResult())
        }
        assertFailsWith<IllegalArgumentException> {
            terminal.copy(outcome = BackgroundOutcome.SUCCEEDED, result = null)
        }
        terminal.copy(outcome = BackgroundOutcome.ERROR, result = null)
        assertFailsWith<IllegalArgumentException> {
            queued.copy(effectiveTimeoutMillis = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            queued.copy(
                executionRequest = queued.executionRequest.copy(
                    sessionId = AgentSessionId("other-session"),
                ),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            queued.copy(
                executionRequest = queued.executionRequest.copy(executionId = "other-execution"),
            )
        }
    }

    @Test
    fun backgroundOriginContextRequiresAMessagelessRequestFromItsSession() {
        val origin = BackgroundOriginContext(
            sessionId = AgentSessionId("session-1"),
            runId = AgentRunId("run-1"),
            request = originRequest(AgentSessionId("session-1")),
        )
        assertEquals(AgentRunId("run-1"), origin.runId)

        assertFailsWith<IllegalArgumentException> {
            BackgroundOriginContext(
                sessionId = AgentSessionId("session-1"),
                runId = AgentRunId("run-1"),
                request = originRequest(AgentSessionId("other-session")),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            BackgroundOriginContext(
                sessionId = AgentSessionId("session-1"),
                runId = AgentRunId("run-1"),
                request = originRequest(AgentSessionId("session-1")).copy(
                    messages = listOf(
                        AgentMessage(role = MessageRole.USER, parts = listOf(TextPart("hi"))),
                    ),
                ),
            )
        }
    }

    @Test
    fun toolExecutorDefaultsToBlockingBackgroundPolicy() {
        val executor = object : ToolExecutor {
            override val definition = ToolDefinition(
                name = "blocking_tool",
                description = "Never runs in the background",
                schema = buildJsonObject { put("type", "object") },
            )

            override suspend fun execute(request: ToolExecutionRequest) = ToolExecutionResult(
                toolCallId = request.toolCall.toolCallId,
                toolName = request.toolCall.toolName,
                result = JsonPrimitive("done"),
            )
        }

        assertEquals(ToolBackgroundPolicy.BLOCKING, executor.backgroundPolicy)
    }

    @Test
    fun runtimeNoticePartRoundTripsInsideARuntimeMessage() {
        val message = AgentMessage(
            id = "notice-1",
            role = MessageRole.RUNTIME,
            parts = listOf(
                RuntimeNoticePart(
                    entries = listOf(noticeEntry()),
                    delivery = RuntimeNoticeDelivery.COALESCED,
                    text = "<runtime_notice>\nA background result is available.\n</runtime_notice>",
                ),
            ),
        )

        val encoded = json.encodeToString(AgentMessage.serializer(), message)

        assertTrue("\"type\":\"runtime_notice\"" in encoded)
        assertEquals(message, json.decodeFromString(AgentMessage.serializer(), encoded))
    }

    @Test
    fun toolResultPartRoundTripsItsBackgroundReceipt() {
        val part = ToolResultPart(
            toolCallId = "c1",
            toolName = "slow_lookup",
            result = buildJsonObject { put("status", "running") },
            backgroundReceipt = ToolBackgroundReceipt(
                handle = "bg_1",
                executionId = "exec-1",
                state = BackgroundExecutionState.RUNNING,
            ),
        )

        val encoded = json.encodeToString(ToolResultPart.serializer(), part)

        assertTrue("\"backgroundReceipt\"" in encoded)
        assertEquals(part, json.decodeFromString(ToolResultPart.serializer(), encoded))
    }

    @Test
    fun backgroundExecutionRecordAndOriginRoundTrip() {
        val record = record(
            state = BackgroundExecutionState.TERMINAL,
            outcome = BackgroundOutcome.SUCCEEDED,
            result = toolResult(),
        )
        val origin = BackgroundOriginContext(
            sessionId = AgentSessionId("session-1"),
            runId = AgentRunId("run-1"),
            request = originRequest(AgentSessionId("session-1")),
        )

        assertEquals(
            record,
            json.decodeFromString(
                BackgroundExecutionRecord.serializer(),
                json.encodeToString(BackgroundExecutionRecord.serializer(), record),
            ),
        )
        assertEquals(
            origin,
            json.decodeFromString(
                BackgroundOriginContext.serializer(),
                json.encodeToString(BackgroundOriginContext.serializer(), origin),
            ),
        )
    }

    private fun noticeEntry() = RuntimeNoticeEntry(
        handle = "bg_1",
        toolName = "slow_lookup",
        callId = "c1",
        outcome = BackgroundOutcome.SUCCEEDED,
        resultVersion = 0,
    )

    private fun toolResult() = ToolExecutionResult(
        toolCallId = "c1",
        toolName = "slow_lookup",
        result = JsonPrimitive("done"),
    )

    private fun originRequest(sessionId: AgentSessionId) = AgentRequest(
        sessionId = sessionId,
        messages = emptyList(),
        model = ModelDescriptor(provider = "test", model = "test-model"),
    )

    private fun record(
        state: BackgroundExecutionState,
        outcome: BackgroundOutcome? = null,
        result: ToolExecutionResult? = null,
    ) = BackgroundExecutionRecord(
        sessionId = AgentSessionId("session-1"),
        executionId = "exec-1",
        handle = "bg_1",
        originRunId = AgentRunId("run-1"),
        originCallId = "c1",
        toolName = "slow_lookup",
        sourceId = "u1",
        state = state,
        outcome = outcome,
        result = result,
        executionRequest = ToolExecutionRequest(
            sessionId = AgentSessionId("session-1"),
            runId = AgentRunId("run-1"),
            executionId = "exec-1",
            assistantMessage = AgentMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(
                    ToolCallPart(
                        toolCallId = "c1",
                        toolName = "slow_lookup",
                        arguments = buildJsonObject { put("topic", "Atlas") },
                    ),
                ),
            ),
            toolCall = ToolCallPart(
                toolCallId = "c1",
                toolName = "slow_lookup",
                arguments = buildJsonObject { put("topic", "Atlas") },
            ),
        ),
        effectiveTimeoutMillis = 30_000,
        queuedAtEpochMs = 1_000,
    )
}
