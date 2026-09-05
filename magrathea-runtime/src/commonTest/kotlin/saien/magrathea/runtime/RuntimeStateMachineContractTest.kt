package saien.magrathea.runtime

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.test.Test
import saien.magrathea.core.AgentCheckpoint
import saien.magrathea.core.AgentEngineConfig
import saien.magrathea.core.AgentEvent
import saien.magrathea.core.AgentFailureCode
import saien.magrathea.core.AgentMessage
import saien.magrathea.core.AgentRequest
import saien.magrathea.core.AgentResumeCursor
import saien.magrathea.core.AgentResumePhase
import saien.magrathea.core.AgentRunId
import saien.magrathea.core.AgentSessionId
import saien.magrathea.core.AgentSessionSnapshot
import saien.magrathea.core.AgentStateSnapshot
import saien.magrathea.core.AgentStatus
import saien.magrathea.core.AttachmentPart
import saien.magrathea.core.MessageRole
import saien.magrathea.core.ModelDescriptor
import saien.magrathea.core.RetryPolicy
import saien.magrathea.core.RuntimeConfig
import saien.magrathea.core.StopReason
import saien.magrathea.core.TextPart
import saien.magrathea.core.ToolCallPart
import saien.magrathea.core.ToolDefinition
import saien.magrathea.core.ToolExecutionRequest
import saien.magrathea.core.ToolExecutionResult
import saien.magrathea.core.ToolExecutionState
import saien.magrathea.core.ToolExecutor
import saien.magrathea.core.ToolOrigin
import saien.magrathea.core.ToolResultPart
import saien.magrathea.provider.api.InMemoryProviderRegistry
import saien.magrathea.provider.api.ProviderAdapter
import saien.magrathea.provider.api.ProviderChunk
import saien.magrathea.provider.api.ProviderNetworkException
import saien.magrathea.provider.api.ProviderRequest

class RuntimeStateMachineContractTest {
    @Test
    fun resume_restoresLatestCheckpointTurnAndState() = runTest {
        val sessionId = AgentSessionId("resume-checkpoint-session")
        val request = request(sessionId = sessionId, maxTurns = 6)
        val runId = AgentRunId("resume-checkpoint-run")
        val persistence = InMemoryAgentPersistence()
        persistence.commit(
            AgentSessionSnapshot(
                sessionId = sessionId,
                runId = runId,
                request = request,
                state = stateWithText("stale-session-state", turn = 0),
            ),
            AgentCheckpoint(
                sessionId = sessionId,
                runId = runId,
                cursor = AgentResumeCursor(2, AgentResumePhase.MODEL_PENDING),
                state = stateWithText("authoritative-checkpoint-state", turn = 2),
            ),
        )
        val provider = RecordingCompleteProvider()
        val runner = runner(provider, persistence)

        val events = runner.resume(sessionId).toList()

        assertEquals(2, events.filterIsInstance<AgentEvent.TurnStarted>().single().turn)
        val providerText = provider.requests.single().messages
            .flatMap { it.parts }
            .filterIsInstance<TextPart>()
            .joinToString("|") { it.text }
        assertTrue(providerText.contains("authoritative-checkpoint-state"))
        assertFalse(providerText.contains("stale-session-state"))
    }

    @Test
    fun resume_completedSession_isIdempotent() = runTest {
        val sessionId = AgentSessionId("resume-completed-session")
        val request = request(sessionId = sessionId)
        val completed = stateWithText("already-complete", turn = 1).copy(
            status = AgentStatus.COMPLETED,
            stopReason = StopReason.COMPLETED,
        )
        val runId = AgentRunId("completed-run")
        val persistence = InMemoryAgentPersistence()
        persistence.commit(AgentSessionSnapshot(sessionId, runId, request, completed), null)
        val provider = RecordingCompleteProvider()
        val runner = runner(provider, persistence)

        val events = runner.resume(sessionId).toList()

        assertTrue(provider.requests.isEmpty())
        assertEquals(completed, events.filterIsInstance<AgentEvent.Completed>().single().state)
    }

    @Test
    fun downstreamStopsAtCompleted_doesNotRewritePersistedSessionAsCancelled() = runTest {
        val sessionId = AgentSessionId("terminal-collector-cancellation")
        val request = request(sessionId = sessionId)
        val persistence = InMemoryAgentPersistence()
        val provider = RecordingCompleteProvider()
        val runner = runner(provider, persistence)

        runner.run(request)
            .takeWhile { it !is AgentEvent.Completed }
            .collect()

        val saved = assertNotNull(persistence.load(sessionId)?.snapshot)
        assertEquals(AgentStatus.COMPLETED, saved.state.status)
        assertEquals(StopReason.COMPLETED, saved.state.stopReason)
        val resumed = runner.resume(sessionId).toList()
        assertTrue(provider.requests.size == 1)
        assertEquals(AgentStatus.COMPLETED, resumed.filterIsInstance<AgentEvent.Completed>().single().state.status)
    }

    @Test
    fun resume_turnCommitted_advancesToTheNextTurn() = runTest {
        val sessionId = AgentSessionId("resume-turn-committed")
        val request = request(sessionId = sessionId)
        val runId = AgentRunId("turn-committed-run")
        val committed = stateWithText("committed-turn", turn = 1)
        val persistence = InMemoryAgentPersistence()
        persistence.commit(
            AgentSessionSnapshot(sessionId, runId, request, committed),
            AgentCheckpoint(
                sessionId,
                runId,
                AgentResumeCursor(1, AgentResumePhase.TURN_COMMITTED),
                committed,
            ),
        )
        val provider = RecordingCompleteProvider()
        val runner = runner(provider, persistence)

        val events = runner.resume(sessionId).toList()

        assertEquals(2, events.filterIsInstance<AgentEvent.TurnStarted>().single().turn)
        assertEquals(1, provider.requests.size)
    }

    @Test
    fun resume_pendingToolCall_failsSafeWithoutProviderOrToolSideEffect() = runTest {
        val sessionId = AgentSessionId("resume-pending-tool-session")
        val tool = CountingTool()
        val pendingCall = ToolCallPart(
            toolCallId = "pending-call-1",
            toolName = tool.definition.name,
            arguments = buildJsonObject { put("value", "must-not-run") },
        )
        val request = request(sessionId = sessionId, tools = listOf(tool.definition))
        val pendingState = stateWithText("waiting", turn = 1).copy(
            status = AgentStatus.WAITING_FOR_TOOLS,
            pendingToolCalls = listOf(pendingCall),
            stopReason = StopReason.TOOL_CALLS,
        )
        val runId = AgentRunId("pending-tool-run")
        val persistence = InMemoryAgentPersistence()
        persistence.commit(
            AgentSessionSnapshot(sessionId, runId, request, pendingState),
            null,
        )
        val provider = RecordingCompleteProvider()
        val sink = RecordingTraceSink()
        val runner = DefaultAgentRunner(
            providerRegistry = InMemoryProviderRegistry(listOf(provider)),
            toolRegistry = InMemoryToolRegistry(listOf(tool)),
            persistence = persistence,
            tracer = sink.tracer(),
        )

        val events = runner.resume(sessionId).toList()

        assertTrue(provider.requests.isEmpty())
        assertEquals(0, tool.executionCount)
        val blocked = events.filterIsInstance<AgentEvent.RecoveryBlocked>().single()
        val execution = sink.spans.single { it.name == RuntimeTraceNames.AGENT_EXECUTION }
        assertEquals(saien.magrathea.core.TraceStatus.UNSET, execution.status)
        assertEquals("recovery_blocked", execution.stringAttribute("magrathea.outcome"))
        assertEquals(blocked.reason.name, execution.stringAttribute("magrathea.recovery.block_reason"))
    }

    @Test
    fun maxTurnsOne_toolRequestTerminatesWithMaxTurns() = runTest {
        val provider = AlwaysToolProvider()
        val tool = CountingTool()
        val runner = runner(provider = provider, tool = tool)

        val events = runner.run(
            request(maxTurns = 1, tools = listOf(tool.definition)),
        ).toList()

        assertEquals(1, provider.callCount)
        assertEquals(1, tool.executionCount)
        assertEquals(1, events.filterIsInstance<AgentEvent.TurnStarted>().size)
        assertEquals(StopReason.MAX_TURNS, events.filterIsInstance<AgentEvent.Completed>().single().state.stopReason)
    }

    @Test
    fun maxTurnsN_executesExactlyNModelCallsAndTerminatesWithMaxTurns() = runTest {
        val provider = AlwaysToolProvider()
        val tool = CountingTool()
        val runner = runner(provider = provider, tool = tool)

        val events = runner.run(
            request(maxTurns = 3, tools = listOf(tool.definition)),
        ).toList()

        assertEquals(3, provider.callCount)
        assertEquals(3, tool.executionCount)
        assertEquals(StopReason.MAX_TURNS, events.filterIsInstance<AgentEvent.Completed>().single().state.stopReason)
    }

    @Test
    fun finalizedTool_isCheckpointedBeforeSideEffect() = runTest {
        val provider = AlwaysToolProvider()
        val persistence = InMemoryAgentPersistence()
        val tool = CheckpointAssertingTool(persistence)
        val request = request(maxTurns = 1, tools = listOf(tool.definition))
        val runner = DefaultAgentRunner(
            providerRegistry = InMemoryProviderRegistry(listOf(provider)),
            toolRegistry = InMemoryToolRegistry(listOf(tool)),
            persistence = persistence,
        )

        runner.run(request).toList()

        assertTrue(tool.pendingCheckpointObservedBeforeExecution)
        assertEquals(1, tool.executionCount)
    }

    @Test
    fun duplicateFinalizedToolCallId_executesSideEffectOnce() = runTest {
        val provider = object : ProviderAdapter {
            override val key: String = PROVIDER_KEY

            override suspend fun generate(request: ProviderRequest): Flow<ProviderChunk> = flow {
                val duplicateCall = ToolCallPart(
                    toolCallId = "duplicate-call-id",
                    toolName = TOOL_NAME,
                    arguments = buildJsonObject { put("value", "once") },
                )
                emit(
                    providerChunk(
                        toolCalls = listOf(duplicateCall, duplicateCall),
                        completed = true,
                    ),
                )
            }
        }
        val tool = CountingTool()
        val events = runner(provider = provider, tool = tool).run(
            request(maxTurns = 1, tools = listOf(tool.definition)),
        ).toList()

        assertEquals(1, tool.executionCount)
        assertEquals(1, events.filterIsInstance<AgentEvent.ToolRequested>().size)
    }

    @Test
    fun invalidMaxTurns_isRejectedAtConstruction() {
        try {
            RuntimeConfig(maxTurns = 0)
            fail("Expected maxTurns validation to fail")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun runtimeHardLimits_areValidatedAtConstruction() {
        listOf<() -> Unit>(
            { RuntimeConfig(maxProviderRetries = -1) },
            { RuntimeConfig(maxToolResultChars = 0) },
            { RuntimeConfig(maxInlineAttachmentBytes = 0) },
        ).forEach { construct ->
            assertFailsWith<IllegalArgumentException> { construct() }
        }
    }

    @Test
    fun alwaysRetryPolicy_isStoppedByRuntimeHardLimit() = runTest {
        val provider = AlwaysFailProvider()
        val retryPolicy = RecordingAlwaysRetryPolicy()
        val runner = DefaultAgentRunner(
            providerRegistry = InMemoryProviderRegistry(listOf(provider)),
            toolRegistry = InMemoryToolRegistry(),
            persistence = InMemoryAgentPersistence(),
            retryPolicy = retryPolicy,
        )

        val events = runner.run(
            request(runtimeConfig = RuntimeConfig(maxProviderRetries = 2)),
        ).toList()

        assertEquals(3, provider.callCount)
        assertEquals(2, retryPolicy.decisionCount)
        assertEquals(2, events.filterIsInstance<AgentEvent.RetryScheduled>().size)
        assertEquals(1, events.filterIsInstance<AgentEvent.Interrupted>().size)
    }

    @Test
    fun providerRetryOrdinalRestartsForEachProviderInvocation() = runTest {
        val provider = RetryBeforeEachTurnProvider()
        val retryPolicy = OrdinalRecordingRetryPolicy()
        val tool = CountingTool()
        val traceSink = RecordingTraceSink()
        val runner = DefaultAgentRunner(
            providerRegistry = InMemoryProviderRegistry(listOf(provider)),
            toolRegistry = InMemoryToolRegistry(listOf(tool)),
            persistence = InMemoryAgentPersistence(),
            retryPolicy = retryPolicy,
            tracer = traceSink.tracer(),
        )

        val events = runner.run(
            request(
                tools = listOf(tool.definition),
                runtimeConfig = RuntimeConfig(maxTurns = 3, maxProviderRetries = 1),
            ),
        ).toList()

        assertEquals(listOf(1, 1), retryPolicy.decisionOrdinals)
        assertEquals(listOf(1, 1), retryPolicy.backoffOrdinals)
        assertEquals(
            listOf(1, 1),
            events.filterIsInstance<AgentEvent.RetryScheduled>().map { it.attempt },
        )
        assertEquals(4, provider.callCount)
        assertEquals(1, tool.executionCount)
        assertEquals(2, events.filterIsInstance<AgentEvent.Completed>().single().state.retryCount)
        assertEquals(
            listOf(0, 1, 0, 1),
            traceSink.spans
                .filter { it.name == RuntimeTraceNames.PROVIDER_REQUEST }
                .map { it.longAttribute("magrathea.provider.attempt")?.toInt() },
        )
    }

    @Test
    fun hostInterruptionDuringBackoffDoesNotCountAnUnstartedRetry() = runTest {
        val sessionId = AgentSessionId("retry-backoff-interruption")
        val persistence = InMemoryAgentPersistence()
        val retryScheduled = CompletableDeferred<Unit>()
        val runner = DefaultAgentRunner(
            providerRegistry = InMemoryProviderRegistry(listOf(AlwaysFailProvider())),
            toolRegistry = InMemoryToolRegistry(),
            persistence = persistence,
            retryPolicy = LongBackoffRetryPolicy(),
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        val collection = launch {
            try {
                runner.run(request(sessionId = sessionId)).collect { event ->
                    if (event is AgentEvent.RetryScheduled) retryScheduled.complete(Unit)
                }
            } catch (_: CancellationException) {
                // Host interruption is represented by durable recovery state.
            }
        }
        retryScheduled.await()

        val recovery = runner.interrupt(sessionId)
        withTimeout(2_000L) { collection.join() }

        assertEquals(0, recovery.state?.retryCount)
        assertEquals(0, assertNotNull(persistence.load(sessionId)).snapshot.state.retryCount)
    }

    @Test
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun eventFlow_usesRendezvousBackpressure() = runTest {
        val provider = RecordingCompleteProvider()
        val releaseStartedCollector = CompletableDeferred<Unit>()
        val runner = DefaultAgentRunner(
            providerRegistry = InMemoryProviderRegistry(listOf(provider)),
            toolRegistry = InMemoryToolRegistry(),
            persistence = InMemoryAgentPersistence(),
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        val collector = launch {
            runner.run(request()).collect { event ->
                if (event is AgentEvent.Started) releaseStartedCollector.await()
            }
        }

        runCurrent()
        assertTrue(provider.requests.isEmpty())

        releaseStartedCollector.complete(Unit)
        runCurrent()
        collector.join()
        assertEquals(1, provider.requests.size)
    }

    @Test
    fun malformedOrOversizedInlineAttachment_failsBeforeProviderOrPersistence() = runTest {
        val invalidAttachments = listOf(
            AttachmentPart("data:image/png;base64,not_base64", "image/png") to 1024,
            AttachmentPart("data:image/png;base64,QUJD", "image/png") to 2,
        )

        invalidAttachments.forEachIndexed { index, (attachment, maxBytes) ->
            val provider = RecordingCompleteProvider()
            val persistence = InMemoryAgentPersistence()
            val request = request(
                sessionId = AgentSessionId("invalid-inline-$index"),
                messages = listOf(AgentMessage(role = MessageRole.USER, parts = listOf(attachment))),
                runtimeConfig = RuntimeConfig(maxInlineAttachmentBytes = maxBytes),
            )
            val runner = DefaultAgentRunner(
                providerRegistry = InMemoryProviderRegistry(listOf(provider)),
                toolRegistry = InMemoryToolRegistry(),
                persistence = persistence,
            )

            val events = runner.run(request).toList()

            assertEquals(
                AgentFailureCode.INVALID_STATE,
                events.filterIsInstance<AgentEvent.Failed>().single().code,
            )
            assertTrue(provider.requests.isEmpty())
            assertEquals(null, persistence.load(request.sessionId))
        }
    }

    @Test
    fun oversizedToolOriginInInitialMessages_failsBeforeProviderOrPersistence() = runTest {
        val provider = RecordingCompleteProvider()
        val persistence = InMemoryAgentPersistence()
        val request = request(
            sessionId = AgentSessionId("oversized-tool-origin"),
            messages = listOf(
                AgentMessage(
                    role = MessageRole.TOOL,
                    parts = listOf(
                        ToolResultPart(
                            toolCallId = "call-1",
                            toolName = "lookup",
                            result = JsonPrimitive("ok"),
                            origin = ToolOrigin(
                                sourceId = "s".repeat(32),
                                sourceLabel = "s".repeat(32),
                                toolId = "t".repeat(32),
                                toolLabel = "t".repeat(32),
                            ),
                        ),
                    ),
                ),
            ),
            runtimeConfig = RuntimeConfig(maxToolResultChars = 128),
        )
        val runner = DefaultAgentRunner(
            providerRegistry = InMemoryProviderRegistry(listOf(provider)),
            toolRegistry = InMemoryToolRegistry(),
            persistence = persistence,
        )

        val events = runner.run(request).toList()

        assertEquals(
            AgentFailureCode.INVALID_STATE,
            events.filterIsInstance<AgentEvent.Failed>().single().code,
        )
        assertTrue(provider.requests.isEmpty())
        assertEquals(null, persistence.load(request.sessionId))
    }

    @Test
    fun inlineAttachmentAtConfiguredByteLimit_isAccepted() = runTest {
        val provider = RecordingCompleteProvider()
        val runner = runner(provider)

        val events = runner.run(
            request(
                messages = listOf(
                    AgentMessage(
                        role = MessageRole.USER,
                        parts = listOf(AttachmentPart("data:image/png;base64,QUI=", "image/png")),
                    ),
                ),
                runtimeConfig = RuntimeConfig(maxInlineAttachmentBytes = 2),
            ),
        ).toList()

        assertEquals(1, provider.requests.size)
        assertEquals(1, events.filterIsInstance<AgentEvent.Completed>().size)
    }

    @Test
    fun largeConversationWithoutKnownContextWindow_isNotCountTrimmed() = runTest {
        listOf(100, 1_000).forEach { messageCount ->
            val provider = RecordingCompleteProvider()
            val runner = runner(provider)
            val messages = (0 until messageCount).map { index ->
                AgentMessage(role = MessageRole.USER, parts = listOf(TextPart("message-$index")))
            }

            runner.run(
                request(
                    messages = messages,
                ),
            ).collect { }

            val providerMessages = provider.requests.single().messages
            assertEquals(messageCount, providerMessages.size)
            assertEquals(
                "message-${messageCount - 1}",
                providerMessages.last().parts.filterIsInstance<TextPart>().single().text,
            )
        }
    }

    @Test
    fun thousandChunkStream_completesWithoutRuntimeEventBufferGrowth() = runTest {
        val provider = LongStreamingProvider(chunkCount = 1_000)
        val runner = runner(provider)
        var messageEventCount = 0
        var finalTextLength = 0

        runner.run(request()).collect { event ->
            if (event is AgentEvent.MessageEmitted) messageEventCount += 1
            if (event is AgentEvent.Completed) {
                finalTextLength = event.state.messages
                    .last()
                    .parts
                    .filterIsInstance<TextPart>()
                    .sumOf { it.text.length }
            }
        }

        assertEquals(1_000, messageEventCount)
        assertEquals(1_000, finalTextLength)
    }

    @Test
    fun cancelDuringStream_isNeverRetriedAndPersistsCancelledState() = runTest {
        val provider = CancellableProvider()
        val retryPolicy = RecordingAlwaysRetryPolicy()
        val persistence = InMemoryAgentPersistence()
        val request = request(sessionId = AgentSessionId("cancel-stream-session"))
        val runner = DefaultAgentRunner(
            providerRegistry = InMemoryProviderRegistry(listOf(provider)),
            toolRegistry = InMemoryToolRegistry(),
            persistence = persistence,
            retryPolicy = retryPolicy,
        )
        val events = mutableListOf<AgentEvent>()
        val collector = launch {
            try {
                runner.run(request).collect(events::add)
            } catch (_: CancellationException) {
            }
        }
        provider.started.await()

        runner.cancel(request.sessionId)
        withTimeout(2_000) { collector.join() }

        assertTrue(provider.cancelled)
        assertEquals(0, retryPolicy.decisionCount)
        assertTrue(events.none { it is AgentEvent.RetryScheduled })
        val persisted = persistence.load(request.sessionId)?.snapshot
        assertNotNull(persisted)
        assertEquals(AgentStatus.CANCELLED, persisted.state.status)
        assertEquals(StopReason.CANCELLED, persisted.state.stopReason)
    }

    @Test
    fun cancelAfterPartialOutput_persistsObservedStateWithoutRetry() = runTest {
        val provider = PartialCancellableProvider()
        val retryPolicy = RecordingAlwaysRetryPolicy()
        val persistence = InMemoryAgentPersistence()
        val request = request(sessionId = AgentSessionId("cancel-partial-session"))
        val runner = DefaultAgentRunner(
            providerRegistry = InMemoryProviderRegistry(listOf(provider)),
            toolRegistry = InMemoryToolRegistry(),
            persistence = persistence,
            retryPolicy = retryPolicy,
        )
        val collector = launch {
            try {
                runner.run(request).collect { }
            } catch (_: CancellationException) {
            }
        }
        provider.partialObserved.await()

        runner.cancel(request.sessionId)
        withTimeout(2_000) { collector.join() }

        assertEquals(0, retryPolicy.decisionCount)
        val persisted = requireNotNull(persistence.load(request.sessionId)?.snapshot)
        val text = persisted.state.messages
            .flatMap { it.parts }
            .filterIsInstance<TextPart>()
            .joinToString("") { it.text }
        assertTrue(text.contains("visible-partial"))
        assertEquals(AgentStatus.CANCELLED, persisted.state.status)
    }

    @Test
    fun cancelDuringTool_propagatesWithoutToolErrorResult() = runTest {
        val provider = AlwaysToolProvider()
        val tool = BlockingTool()
        val persistence = InMemoryAgentPersistence()
        val request = request(
            sessionId = AgentSessionId("cancel-tool-session"),
            tools = listOf(tool.definition),
        )
        val runner = DefaultAgentRunner(
            providerRegistry = InMemoryProviderRegistry(listOf(provider)),
            toolRegistry = InMemoryToolRegistry(listOf(tool)),
            persistence = persistence,
        )
        val events = mutableListOf<AgentEvent>()
        val collector = launch {
            try {
                runner.run(request).collect { events += it }
            } catch (_: CancellationException) {
            }
        }
        tool.started.await()

        runner.cancel(request.sessionId)
        withTimeout(2_000) { collector.join() }

        assertTrue(tool.cancelled)
        assertTrue(events.none { it is AgentEvent.ToolCompleted })
        val persisted = requireNotNull(persistence.load(request.sessionId)?.snapshot)
        assertEquals(AgentStatus.CANCELLED, persisted.state.status)
        assertEquals(1, persisted.state.pendingToolCalls.size)
    }

    @Test
    fun providerErrorAfterPartialOutput_doesNotReplayRequest() = runTest {
        val provider = PartialThenFailProvider()
        val retryPolicy = RetryOncePolicy()
        val runner = DefaultAgentRunner(
            providerRegistry = InMemoryProviderRegistry(listOf(provider)),
            toolRegistry = InMemoryToolRegistry(),
            persistence = InMemoryAgentPersistence(),
            retryPolicy = retryPolicy,
        )

        val events = runner.run(request()).toList()

        assertEquals(1, provider.callCount)
        assertEquals(0, retryPolicy.decisionCount)
        assertTrue(events.none { it is AgentEvent.RetryScheduled })
        assertEquals(1, events.filterIsInstance<AgentEvent.Failed>().size)
    }

    @Test
    fun transientProviderErrorBeforeOutput_canRetryOnce() = runTest {
        val provider = FailThenCompleteProvider()
        val retryPolicy = RetryOncePolicy()
        val runner = DefaultAgentRunner(
            providerRegistry = InMemoryProviderRegistry(listOf(provider)),
            toolRegistry = InMemoryToolRegistry(),
            persistence = InMemoryAgentPersistence(),
            retryPolicy = retryPolicy,
        )

        val events = runner.run(request()).toList()

        assertEquals(2, provider.callCount)
        assertEquals(1, retryPolicy.decisionCount)
        assertEquals(1, events.filterIsInstance<AgentEvent.RetryScheduled>().size)
        assertEquals(1, events.filterIsInstance<AgentEvent.Completed>().size)
    }

    @Test
    fun sameSessionConcurrentRun_isRejectedWithoutStartingSecondProviderCall() = runTest {
        val provider = ConcurrentProvider()
        val runner = runner(provider)
        val request = request(sessionId = AgentSessionId("same-session-concurrent"))
        val first = launch {
            try {
                runner.run(request).collect { }
            } catch (_: CancellationException) {
            }
        }
        provider.firstStarted.await()

        try {
            try {
                runner.run(request).toList()
                fail("Expected the concurrent run to be rejected")
            } catch (error: IllegalStateException) {
                assertTrue(error.message.orEmpty().contains("already running"))
            }
            assertEquals(1, provider.callCount)
        } finally {
            provider.releaseFirst.complete(Unit)
            first.cancelAndJoin()
        }
    }

    @Test
    fun immediateProviderCompletion_leavesNoActiveRun() = runTest {
        val provider = RecordingCompleteProvider()
        val runner = runner(provider)
        val request = request(sessionId = AgentSessionId("immediate-completion-session"))

        runner.run(request).toList()

        assertFalse(runner.isSessionActive(request.sessionId))
    }

    private fun runner(
        provider: ProviderAdapter,
        persistence: InMemoryAgentPersistence = InMemoryAgentPersistence(),
        tool: ToolExecutor? = null,
    ): DefaultAgentRunner {
        return DefaultAgentRunner(
            providerRegistry = InMemoryProviderRegistry(listOf(provider)),
            toolRegistry = InMemoryToolRegistry(listOfNotNull(tool)),
            persistence = persistence,
        )
    }

    private fun request(
        sessionId: AgentSessionId = AgentSessionId.create(),
        maxTurns: Int = 4,
        tools: List<ToolDefinition> = emptyList(),
        messages: List<AgentMessage> = listOf(
            AgentMessage(role = MessageRole.USER, parts = listOf(TextPart("start"))),
        ),
        runtimeConfig: RuntimeConfig = RuntimeConfig(maxTurns = maxTurns),
    ): AgentRequest {
        return AgentRequest(
            sessionId = sessionId,
            messages = messages,
            model = ModelDescriptor(provider = PROVIDER_KEY, model = "runtime-contract-model"),
            tools = tools,
            engine = AgentEngineConfig(runtime = runtimeConfig),
        )
    }

    private fun stateWithText(text: String, turn: Int): AgentStateSnapshot {
        return AgentStateSnapshot(
            messages = listOf(AgentMessage(role = MessageRole.USER, parts = listOf(TextPart(text)))),
            turn = turn,
            status = AgentStatus.RUNNING,
        )
    }

    private class RecordingCompleteProvider : ProviderAdapter {
        override val key: String = PROVIDER_KEY
        val requests = mutableListOf<ProviderRequest>()

        override suspend fun generate(request: ProviderRequest): Flow<ProviderChunk> = flow {
            requests += request
            emit(completedChunk())
        }
    }

    private class AlwaysToolProvider : ProviderAdapter {
        override val key: String = PROVIDER_KEY
        var callCount: Int = 0

        override suspend fun generate(request: ProviderRequest): Flow<ProviderChunk> = flow {
            val call = ++callCount
            emit(
                providerChunk(
                    toolCalls = listOf(
                        ToolCallPart(
                            toolCallId = "loop-call-$call",
                            toolName = TOOL_NAME,
                            arguments = buildJsonObject { put("value", "turn-$call") },
                        ),
                    ),
                    completed = true,
                ),
            )
        }
    }

    private class RetryBeforeEachTurnProvider : ProviderAdapter {
        override val key: String = PROVIDER_KEY
        var callCount: Int = 0

        override suspend fun generate(request: ProviderRequest): Flow<ProviderChunk> = flow {
            callCount += 1
            when (callCount) {
                1, 3 -> throw ProviderNetworkException("transient failure before output")
                2 -> emit(
                    providerChunk(
                        toolCalls = listOf(
                            ToolCallPart(
                                toolCallId = "retry-ordinal-tool-call",
                                toolName = TOOL_NAME,
                                arguments = buildJsonObject { put("value", "first turn") },
                            ),
                        ),
                        completed = true,
                    ),
                )
                else -> emit(providerChunk(text = "complete", completed = true))
            }
        }
    }

    private class CancellableProvider : ProviderAdapter {
        override val key: String = PROVIDER_KEY
        val started = CompletableDeferred<Unit>()
        var cancelled: Boolean = false

        override suspend fun generate(request: ProviderRequest): Flow<ProviderChunk> = flow {
            started.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                cancelled = true
            }
        }
    }

    private class PartialCancellableProvider : ProviderAdapter {
        override val key: String = PROVIDER_KEY
        val partialObserved = CompletableDeferred<Unit>()

        override suspend fun generate(request: ProviderRequest): Flow<ProviderChunk> = flow {
            emit(providerChunk(text = "visible-partial"))
            partialObserved.complete(Unit)
            awaitCancellation()
        }
    }

    private class ConcurrentProvider : ProviderAdapter {
        override val key: String = PROVIDER_KEY
        var callCount: Int = 0
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()

        override suspend fun generate(request: ProviderRequest): Flow<ProviderChunk> = flow {
            val call = ++callCount
            if (call == 1) {
                firstStarted.complete(Unit)
                releaseFirst.await()
            }
            emit(completedChunk())
        }
    }

    private class PartialThenFailProvider : ProviderAdapter {
        override val key: String = PROVIDER_KEY
        var callCount: Int = 0

        override suspend fun generate(request: ProviderRequest): Flow<ProviderChunk> = flow {
            callCount += 1
            emit(providerChunk(text = "partial"))
            error("stream failed after partial output")
        }
    }

    private class FailThenCompleteProvider : ProviderAdapter {
        override val key: String = PROVIDER_KEY
        var callCount: Int = 0

        override suspend fun generate(request: ProviderRequest): Flow<ProviderChunk> = flow {
            val call = ++callCount
            if (call == 1) throw ProviderNetworkException("failed before output")
            emit(completedChunk())
        }
    }

    private class AlwaysFailProvider : ProviderAdapter {
        override val key: String = PROVIDER_KEY
        var callCount: Int = 0

        override suspend fun generate(request: ProviderRequest): Flow<ProviderChunk> = flow {
            callCount += 1
            throw ProviderNetworkException("failed before output")
        }
    }

    private class LongStreamingProvider(
        private val chunkCount: Int,
    ) : ProviderAdapter {
        override val key: String = PROVIDER_KEY

        override suspend fun generate(request: ProviderRequest): Flow<ProviderChunk> = flow {
            repeat(chunkCount - 1) { emit(providerChunk(text = "x")) }
            emit(providerChunk(text = "x", completed = true))
        }
    }

    private class RecordingAlwaysRetryPolicy : RetryPolicy {
        var decisionCount: Int = 0

        override suspend fun shouldRetry(attempt: Int, error: Throwable): Boolean {
            decisionCount += 1
            return true
        }

        override suspend fun backoffDelayMs(attempt: Int, error: Throwable): Long = 0
    }

    private class OrdinalRecordingRetryPolicy : RetryPolicy {
        val decisionOrdinals = mutableListOf<Int>()
        val backoffOrdinals = mutableListOf<Int>()

        override suspend fun shouldRetry(attempt: Int, error: Throwable): Boolean {
            decisionOrdinals += attempt
            return true
        }

        override suspend fun backoffDelayMs(attempt: Int, error: Throwable): Long {
            backoffOrdinals += attempt
            return 0L
        }
    }

    private class LongBackoffRetryPolicy : RetryPolicy {
        override suspend fun shouldRetry(attempt: Int, error: Throwable): Boolean = true

        override suspend fun backoffDelayMs(attempt: Int, error: Throwable): Long = 60_000L
    }

    private class RetryOncePolicy : RetryPolicy {
        var decisionCount: Int = 0

        override suspend fun shouldRetry(attempt: Int, error: Throwable): Boolean {
            decisionCount += 1
            return decisionCount == 1
        }

        override suspend fun backoffDelayMs(attempt: Int, error: Throwable): Long = 0
    }

    private class CountingTool : ToolExecutor {
        override val definition = ToolDefinition(
            name = TOOL_NAME,
            description = "Runtime state machine contract tool",
            schema = buildJsonObject { },
        )
        var executionCount: Int = 0

        override suspend fun execute(request: ToolExecutionRequest): ToolExecutionResult {
            executionCount += 1
            return ToolExecutionResult(
                toolCallId = request.toolCall.toolCallId,
                toolName = request.toolCall.toolName,
                result = JsonPrimitive("executed"),
            )
        }
    }

    private class BlockingTool : ToolExecutor {
        override val definition = ToolDefinition(
            name = TOOL_NAME,
            description = "Blocking cancellation contract tool",
            schema = buildJsonObject { },
        )
        val started = CompletableDeferred<Unit>()
        var cancelled: Boolean = false

        override suspend fun execute(request: ToolExecutionRequest): ToolExecutionResult {
            started.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                cancelled = true
            }
        }
    }

    private class CheckpointAssertingTool(
        private val persistence: InMemoryAgentPersistence,
    ) : ToolExecutor {
        override val definition = ToolDefinition(
            name = TOOL_NAME,
            description = "Checkpoint-before-side-effect contract tool",
            schema = buildJsonObject { },
        )
        var executionCount: Int = 0
        var pendingCheckpointObservedBeforeExecution: Boolean = false

        override suspend fun execute(request: ToolExecutionRequest): ToolExecutionResult {
            val checkpoint = persistence.load(request.sessionId)?.checkpoint
            pendingCheckpointObservedBeforeExecution =
                checkpoint?.toolExecutions?.any {
                    it.executionId == request.executionId &&
                        it.state == ToolExecutionState.STARTED
                } == true
            executionCount += 1
            return ToolExecutionResult(
                toolCallId = request.toolCall.toolCallId,
                toolName = request.toolCall.toolName,
                result = JsonPrimitive("executed"),
            )
        }
    }

    companion object {
        private const val PROVIDER_KEY = "runtime-state-contract-provider"
        private const val TOOL_NAME = "runtime_contract_tool"

        private fun completedChunk(): ProviderChunk {
            return providerChunk(text = "done", completed = true)
        }
    }
}
