package saien.magrathea.runtime

import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import saien.magrathea.core.AgentCheckpoint
import saien.magrathea.core.AgentEngineConfig
import saien.magrathea.core.AgentEvent
import saien.magrathea.core.AgentInterruption
import saien.magrathea.core.AgentInterruptionReason
import saien.magrathea.core.AgentMessage
import saien.magrathea.core.AgentRecoveryBlockReason
import saien.magrathea.core.AgentRecoveryDisposition
import saien.magrathea.core.AgentRequest
import saien.magrathea.core.AgentResumeCursor
import saien.magrathea.core.AgentResumePhase
import saien.magrathea.core.AgentRunId
import saien.magrathea.core.AgentSessionId
import saien.magrathea.core.AgentSessionSnapshot
import saien.magrathea.core.AgentStateSnapshot
import saien.magrathea.core.AgentStatus
import saien.magrathea.core.MessageRole
import saien.magrathea.core.ModelDescriptor
import saien.magrathea.core.RuntimeConfig
import saien.magrathea.core.StopReason
import saien.magrathea.core.TextPart
import saien.magrathea.core.ToolCallPart
import saien.magrathea.core.ToolDefinition
import saien.magrathea.core.ToolExecutionRecord
import saien.magrathea.core.ToolExecutionRequest
import saien.magrathea.core.ToolExecutionResult
import saien.magrathea.core.ToolExecutionState
import saien.magrathea.core.ToolExecutor
import saien.magrathea.core.ToolRecoveryPolicy
import saien.magrathea.provider.api.InMemoryProviderRegistry
import saien.magrathea.provider.api.ProviderAdapter
import saien.magrathea.provider.api.ProviderChunk
import saien.magrathea.provider.api.ProviderInvocationResumeMode
import saien.magrathea.provider.api.ProviderRequest

class RuntimeRecoveryContractTest {
    @Test
    fun hostInterruptionRollsBackPartialOutputAndResumeUsesANewProviderAttempt() = runTest {
        val sessionId = AgentSessionId("host-interruption")
        val persistence = InMemoryAgentPersistence()
        val provider = PartialThenCompleteProvider()
        val runner = runner(provider, persistence)
        val request = request(sessionId)
        val observed = mutableListOf<AgentEvent>()
        val collection = launch {
            try {
                runner.run(request).collect(observed::add)
            } catch (_: CancellationException) {
                // The runtime converts host interruption into durable recovery state.
            }
        }
        provider.partialObserved.await()

        val recovery = runner.interrupt(sessionId)
        withTimeout(2_000) { collection.join() }

        assertEquals(AgentRecoveryDisposition.RESUMABLE, recovery.disposition)
        assertEquals(AgentInterruptionReason.HOST_REQUESTED, recovery.interruption?.reason)
        val interrupted = assertNotNull(persistence.load(sessionId))
        assertEquals(AgentStatus.INTERRUPTED, interrupted.snapshot.state.status)
        assertEquals(AgentResumePhase.MODEL_PENDING, interrupted.checkpoint?.cursor?.phase)
        assertFalse(interrupted.snapshot.state.allText().contains(PARTIAL_TEXT))

        val resumed = runner.resume(sessionId).toList()
        val completed = resumed.filterIsInstance<AgentEvent.Completed>().single().state
        assertEquals(AgentStatus.COMPLETED, completed.status)
        assertTrue(completed.allText().contains(FINAL_TEXT))
        assertFalse(completed.allText().contains(PARTIAL_TEXT))
        assertEquals(2, provider.requests.size)
        val invocationIds = provider.requests.map { assertNotNull(it.invocation).requestId }
        assertTrue(invocationIds[0].endsWith(":0:0"))
        assertTrue(invocationIds[1].endsWith(":0:1"))
        assertEquals(
            invocationIds[0].substringBeforeLast(':'),
            invocationIds[1].substringBeforeLast(':'),
        )
    }

    @Test
    fun reattachProviderResumesWithTheSameInvocationIdentity() = runTest {
        val sessionId = AgentSessionId("reattach-interruption")
        val persistence = InMemoryAgentPersistence()
        val provider = PartialThenCompleteProvider(ProviderInvocationResumeMode.REATTACH)
        val runner = runner(provider, persistence)
        val collection = launch {
            try {
                runner.run(request(sessionId)).collect()
            } catch (_: CancellationException) {
                // The runtime converts host interruption into durable recovery state.
            }
        }
        provider.partialObserved.await()

        runner.interrupt(sessionId)
        withTimeout(2_000) { collection.join() }
        runner.resume(sessionId).toList()

        assertEquals(2, provider.requests.size)
        val invocationIds = provider.requests.map { assertNotNull(it.invocation).requestId }
        assertEquals(invocationIds[0], invocationIds[1])
        assertTrue(invocationIds[0].endsWith(":0:0"))
    }

    @Test
    fun orphanedRunningSnapshotIsDetectedAndCanResumeFromItsCheckpoint() = runTest {
        val sessionId = AgentSessionId("orphaned-process")
        val persistence = InMemoryAgentPersistence()
        val request = request(sessionId)
        val runId = AgentRunId("orphaned-run")
        val state = AgentStateSnapshot(
            messages = request.messages,
            status = AgentStatus.RUNNING,
            stopReason = StopReason.COMPLETED,
        )
        persistence.commit(
            AgentSessionSnapshot(sessionId, runId, request, state),
            AgentCheckpoint(
                sessionId,
                runId,
                AgentResumeCursor(0, AgentResumePhase.MODEL_PENDING),
                state,
            ),
        )
        val provider = RecordingCompleteProvider()
        val runner = runner(provider, persistence)

        val recovery = runner.inspectRecovery(sessionId)

        assertEquals(AgentRecoveryDisposition.RESUMABLE, recovery.disposition)
        assertEquals(AgentInterruptionReason.ORPHANED, recovery.interruption?.reason)
        runner.resume(sessionId).toList()
        assertEquals("orphaned-run:0:1", provider.requests.single().invocation?.requestId)
    }

    @Test
    fun inconsistentCheckpointPhaseIsBlockedBeforeAnyWorkRuns() = runTest {
        val sessionId = AgentSessionId("inconsistent-checkpoint")
        val call = ToolCallPart(
            toolCallId = TOOL_CALL_ID,
            toolName = TOOL_NAME,
            arguments = buildJsonObject { put("value", "recover") },
        )
        val request = request(sessionId, tools = listOf(CountingRecoveryTool().definition))
        val state = AgentStateSnapshot(
            messages = request.messages,
            pendingToolCalls = listOf(call),
            status = AgentStatus.WAITING_FOR_TOOLS,
            stopReason = StopReason.TOOL_CALLS,
        )
        val runId = AgentRunId("inconsistent-run")
        val persistence = InMemoryAgentPersistence()
        persistence.commit(
            AgentSessionSnapshot(sessionId, runId, request, state),
            AgentCheckpoint(
                sessionId = sessionId,
                runId = runId,
                cursor = AgentResumeCursor(0, AgentResumePhase.TOOLS_PENDING),
                state = state,
                toolExecutions = emptyList(),
            ),
        )
        val provider = RecordingCompleteProvider()
        val runner = runner(provider, persistence)

        val recovery = runner.inspectRecovery(sessionId)
        val resumed = runner.resume(sessionId).toList()

        assertEquals(AgentRecoveryDisposition.BLOCKED, recovery.disposition)
        assertEquals(AgentRecoveryBlockReason.CHECKPOINT_MISMATCH, recovery.blockedReason)
        assertEquals(
            AgentRecoveryBlockReason.CHECKPOINT_MISMATCH,
            resumed.filterIsInstance<AgentEvent.RecoveryBlocked>().single().reason,
        )
        assertTrue(provider.requests.isEmpty())
    }

    @Test
    fun pendingAndCompletedToolJournalEntriesNeverDuplicateSideEffects() = runTest {
        val pendingTool = CountingRecoveryTool(ToolRecoveryPolicy.FAIL_CLOSED)
        val pending = toolFixture(ToolExecutionState.PENDING, pendingTool)

        val pendingEvents = pending.runner.resume(pending.sessionId).toList()

        assertEquals(1, pendingTool.executionCount)
        assertEquals(1, pendingEvents.filterIsInstance<AgentEvent.ToolCompleted>().size)

        val completedResult = ToolExecutionResult(
            toolCallId = TOOL_CALL_ID,
            toolName = TOOL_NAME,
            result = JsonPrimitive("already completed"),
        )
        val completedTool = CountingRecoveryTool(ToolRecoveryPolicy.FAIL_CLOSED)
        val completed = toolFixture(
            state = ToolExecutionState.COMPLETED,
            tool = completedTool,
            result = completedResult,
        )

        val completedEvents = completed.runner.resume(completed.sessionId).toList()

        assertEquals(0, completedTool.executionCount)
        assertEquals(
            completedResult,
            completedEvents.filterIsInstance<AgentEvent.ToolCompleted>().single().result,
        )
    }

    @Test
    fun startedToolBlocksByDefaultButExplicitReplaySafeToolCanResume() = runTest {
        val unsafeTool = CountingRecoveryTool(ToolRecoveryPolicy.FAIL_CLOSED)
        val unsafe = toolFixture(ToolExecutionState.STARTED, unsafeTool)

        val blocked = unsafe.runner.inspectRecovery(unsafe.sessionId)
        val blockedEvents = unsafe.runner.resume(unsafe.sessionId).toList()

        assertEquals(AgentRecoveryDisposition.BLOCKED, blocked.disposition)
        assertEquals(AgentRecoveryBlockReason.TOOL_OUTCOME_UNKNOWN, blocked.blockedReason)
        assertEquals(0, unsafeTool.executionCount)
        assertEquals(
            AgentRecoveryBlockReason.TOOL_OUTCOME_UNKNOWN,
            blockedEvents.filterIsInstance<AgentEvent.RecoveryBlocked>().single().reason,
        )

        val replaySafeTool = CountingRecoveryTool(ToolRecoveryPolicy.REPLAY_SAFE)
        val replaySafe = toolFixture(ToolExecutionState.STARTED, replaySafeTool)

        assertEquals(
            AgentRecoveryDisposition.RESUMABLE,
            replaySafe.runner.inspectRecovery(replaySafe.sessionId).disposition,
        )
        replaySafe.runner.resume(replaySafe.sessionId).toList()
        assertEquals(1, replaySafeTool.executionCount)
    }

    @Test
    fun cancellingAnInactiveInterruptedRunMakesItTerminalAndRemovesItsCheckpoint() = runTest {
        val sessionId = AgentSessionId("cancel-interrupted")
        val persistence = InMemoryAgentPersistence()
        val request = request(sessionId)
        val runId = AgentRunId("cancel-interrupted-run")
        val interruption = AgentInterruption(AgentInterruptionReason.PROVIDER_NETWORK)
        val state = AgentStateSnapshot(
            messages = request.messages,
            status = AgentStatus.INTERRUPTED,
            stopReason = StopReason.INTERRUPTED,
        )
        persistence.commit(
            AgentSessionSnapshot(
                sessionId = sessionId,
                runId = runId,
                request = request,
                state = state,
                interruption = interruption,
            ),
            AgentCheckpoint(
                sessionId = sessionId,
                runId = runId,
                cursor = AgentResumeCursor(0, AgentResumePhase.MODEL_PENDING),
                state = state.copy(
                    status = AgentStatus.RUNNING,
                    stopReason = null,
                ),
            ),
        )
        val runner = runner(RecordingCompleteProvider(), persistence)

        runner.cancel(sessionId)

        val cancelled = assertNotNull(persistence.load(sessionId))
        assertEquals(AgentStatus.CANCELLED, cancelled.snapshot.state.status)
        assertEquals(StopReason.CANCELLED, cancelled.snapshot.state.stopReason)
        assertEquals(null, cancelled.snapshot.interruption)
        assertEquals(null, cancelled.checkpoint)
        assertEquals(
            AgentRecoveryDisposition.TERMINAL,
            runner.inspectRecovery(sessionId).disposition,
        )
    }

    private suspend fun toolFixture(
        state: ToolExecutionState,
        tool: CountingRecoveryTool,
        result: ToolExecutionResult? = null,
    ): ToolFixture {
        val sessionId = AgentSessionId("tool-${state.name.lowercase()}-${tool.recoveryPolicy.name.lowercase()}")
        val call = ToolCallPart(
            toolCallId = TOOL_CALL_ID,
            toolName = TOOL_NAME,
            arguments = buildJsonObject { put("value", "recover") },
        )
        val request = request(
            sessionId = sessionId,
            tools = listOf(tool.definition),
            maxTurns = 1,
        )
        val assistant = AgentMessage(
            id = "tool-assistant",
            role = MessageRole.ASSISTANT,
            parts = listOf(call),
            stopReason = StopReason.TOOL_CALLS,
        )
        val agentState = AgentStateSnapshot(
            messages = request.messages + assistant,
            pendingToolCalls = listOf(call),
            status = AgentStatus.WAITING_FOR_TOOLS,
            stopReason = StopReason.TOOL_CALLS,
        )
        val runId = AgentRunId("tool-run-${state.name.lowercase()}")
        val journal = ToolExecutionRecord(
            executionId = "tool-execution",
            toolCallId = TOOL_CALL_ID,
            toolName = TOOL_NAME,
            callOrdinal = 1,
            state = state,
            result = result,
        )
        val persistence = InMemoryAgentPersistence()
        persistence.commit(
            AgentSessionSnapshot(sessionId, runId, request, agentState),
            AgentCheckpoint(
                sessionId = sessionId,
                runId = runId,
                cursor = AgentResumeCursor(0, AgentResumePhase.TOOLS_PENDING),
                state = agentState,
                toolExecutions = listOf(journal),
            ),
        )
        val runner = DefaultAgentRunner(
            providerRegistry = InMemoryProviderRegistry(listOf(NoCallProvider())),
            toolRegistry = InMemoryToolRegistry(listOf(tool)),
            persistence = persistence,
        )
        return ToolFixture(sessionId, runner)
    }

    private fun runner(
        provider: ProviderAdapter,
        persistence: InMemoryAgentPersistence,
    ) = DefaultAgentRunner(
        providerRegistry = InMemoryProviderRegistry(listOf(provider)),
        toolRegistry = InMemoryToolRegistry(),
        persistence = persistence,
    )

    private fun request(
        sessionId: AgentSessionId,
        tools: List<ToolDefinition> = emptyList(),
        maxTurns: Int = 2,
    ) = AgentRequest(
        sessionId = sessionId,
        messages = listOf(
            AgentMessage(role = MessageRole.USER, parts = listOf(TextPart("recover me"))),
        ),
        model = ModelDescriptor(PROVIDER_KEY, "recovery-model", supportsStreaming = true),
        tools = tools,
        engine = AgentEngineConfig(runtime = RuntimeConfig(maxTurns = maxTurns)),
    )

    private data class ToolFixture(
        val sessionId: AgentSessionId,
        val runner: DefaultAgentRunner,
    )

    private class PartialThenCompleteProvider(
        override val invocationResumeMode: ProviderInvocationResumeMode =
            ProviderInvocationResumeMode.NEW_ATTEMPT,
    ) : ProviderAdapter {
        override val key: String = PROVIDER_KEY
        val partialObserved = CompletableDeferred<Unit>()
        val requests = mutableListOf<ProviderRequest>()

        override suspend fun generate(request: ProviderRequest): Flow<ProviderChunk> = flow {
            requests += request
            if (requests.size == 1) {
                emit(providerChunk(text = PARTIAL_TEXT))
                partialObserved.complete(Unit)
                awaitCancellation()
            } else {
                emit(providerChunk(text = FINAL_TEXT, completed = true))
            }
        }
    }

    private class RecordingCompleteProvider : ProviderAdapter {
        override val key: String = PROVIDER_KEY
        val requests = mutableListOf<ProviderRequest>()

        override suspend fun generate(request: ProviderRequest): Flow<ProviderChunk> = flow {
            requests += request
            emit(providerChunk(text = FINAL_TEXT, completed = true))
        }
    }

    private class NoCallProvider : ProviderAdapter {
        override val key: String = PROVIDER_KEY

        override suspend fun generate(request: ProviderRequest): Flow<ProviderChunk> =
            error("Provider must not be called while recovering a pending Tool phase")
    }

    private class CountingRecoveryTool(
        override val recoveryPolicy: ToolRecoveryPolicy = ToolRecoveryPolicy.FAIL_CLOSED,
    ) : ToolExecutor {
        override val definition = ToolDefinition(
            name = TOOL_NAME,
            description = "Recovery contract Tool",
            schema = buildJsonObject { },
        )
        var executionCount = 0

        override suspend fun execute(request: ToolExecutionRequest): ToolExecutionResult {
            executionCount += 1
            return ToolExecutionResult(
                toolCallId = request.toolCall.toolCallId,
                toolName = request.toolCall.toolName,
                result = JsonPrimitive("executed"),
            )
        }
    }

    private fun AgentStateSnapshot.allText(): String = messages
        .flatMap(AgentMessage::parts)
        .filterIsInstance<TextPart>()
        .joinToString(separator = "") { it.text }

    private companion object {
        const val PROVIDER_KEY = "recovery-provider"
        const val TOOL_NAME = "recovery_tool"
        const val TOOL_CALL_ID = "recovery-call"
        const val PARTIAL_TEXT = "provisional partial"
        const val FINAL_TEXT = "durable final"
    }
}
