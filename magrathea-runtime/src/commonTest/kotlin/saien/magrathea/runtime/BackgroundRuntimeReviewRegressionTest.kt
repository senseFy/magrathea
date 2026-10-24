@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package saien.magrathea.runtime

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.ComparableTimeMark
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import saien.magrathea.core.AgentEngineConfig
import saien.magrathea.core.AgentEvent
import saien.magrathea.core.AgentInterceptor
import saien.magrathea.core.AgentMessage
import saien.magrathea.core.AgentPersistence
import saien.magrathea.core.AgentRequest
import saien.magrathea.core.AgentResumeCursor
import saien.magrathea.core.AgentResumePhase
import saien.magrathea.core.AgentRunId
import saien.magrathea.core.AgentCheckpoint
import saien.magrathea.core.AgentSessionId
import saien.magrathea.core.AgentSessionSnapshot
import saien.magrathea.core.AgentStateSnapshot
import saien.magrathea.core.BackgroundConsumptionPolicy
import saien.magrathea.core.BackgroundExecutionConfig
import saien.magrathea.core.BackgroundExecutionRecord
import saien.magrathea.core.BackgroundExecutionState
import saien.magrathea.core.BackgroundExecutionStore
import saien.magrathea.core.BackgroundOriginContext
import saien.magrathea.core.BackgroundOutcome
import saien.magrathea.core.BackgroundSessionRecords
import saien.magrathea.core.ContextManager
import saien.magrathea.core.MessageRole
import saien.magrathea.core.ModelDescriptor
import saien.magrathea.core.RunActivation
import saien.magrathea.core.RunActivationSource
import saien.magrathea.core.RuntimeConfig
import saien.magrathea.core.RuntimeNoticeDelivery
import saien.magrathea.core.RuntimeNoticeEntry
import saien.magrathea.core.RuntimeNoticePart
import saien.magrathea.core.StopReason
import saien.magrathea.core.TextPart
import saien.magrathea.core.ToolBackgroundPolicy
import saien.magrathea.core.ToolCallPart
import saien.magrathea.core.ToolDefinition
import saien.magrathea.core.ToolExecutionMode
import saien.magrathea.core.ToolExecutionPermit
import saien.magrathea.core.ToolExecutionRecord
import saien.magrathea.core.ToolExecutionRequest
import saien.magrathea.core.ToolExecutionResult
import saien.magrathea.core.ToolExecutionState
import saien.magrathea.core.ToolExecutor
import saien.magrathea.core.ToolRecoveryPolicy
import saien.magrathea.core.ToolOrigin
import saien.magrathea.core.ToolResultContextHints
import saien.magrathea.core.ToolResultAudience
import saien.magrathea.core.ToolResultPart
import saien.magrathea.core.ToolResultRetention
import saien.magrathea.core.ToolResultTextContent
import saien.magrathea.core.ToolResultImageContent
import saien.magrathea.core.RemoteToolImageSource
import saien.magrathea.provider.api.InMemoryProviderRegistry
import saien.magrathea.provider.api.ProviderAdapter
import saien.magrathea.provider.api.ProviderChunk
import saien.magrathea.provider.api.ProviderRequest

class BackgroundRuntimeReviewRegressionTest {
    @Test
    fun lateInterceptorTimeoutMustSettleAndRelease() = runTest {
        val store = InMemoryBackgroundExecutionStore()
        val executor = executor(store)
        try {
            val sessionId = AgentSessionId("review-late-timeout")
            executor.register(sessionId, NoopListener)
            val tool = DelayedTool("deep_lookup", 200, ToolBackgroundPolicy.ELIGIBLE)
            val runner = runner(
                provider = scriptedProvider("review-late-timeout", toolCall("call-1", "deep_lookup")),
                tools = listOf(tool),
                background = executor,
                interceptors = listOf(object : AgentInterceptor {
                    override suspend fun afterToolCall(
                        context: saien.magrathea.core.ToolRuntimeContext,
                        result: ToolExecutionResult,
                    ): ToolExecutionResult = kotlinx.coroutines.withTimeout(1) {
                        delay(10)
                        result
                    }
                }),
            )
            runner.run(request("review-late-timeout", sessionId, backgroundConfig(handoffWait = 100))).toList()
            advanceTimeBy(400)
            runCurrent()
            val record = store.load(sessionId).records.single()
            assertEquals(BackgroundExecutionState.TERMINAL, record.state, "Interceptor-local timeout must not strand the record")
            assertEquals(BackgroundOutcome.ERROR, record.outcome)
            assertNull(executor.liveExecution(checkNotNull(tool.lastExecutionId)))
        } finally {
            executor.close()
        }
    }

    @Test
    fun restoredQueuedExecutionMustExpireWhileWaitingForPermit() = runTest {
        val store = InMemoryBackgroundExecutionStore()
        val executor = executor(store)
        try {
            val sessionId = AgentSessionId("review-restored-queue")
            val permitGate = CompletableDeferred<Unit>()
            var executionCount = 0
            val tool = object : ToolExecutor {
                override val definition = ToolDefinition("deep_lookup", "gated permit", buildJsonObject { put("type", "object") })
                override val recoveryPolicy = ToolRecoveryPolicy.REPLAY_SAFE
                override val backgroundPolicy = ToolBackgroundPolicy.ELIGIBLE
                override fun executionPermit(request: ToolExecutionRequest): ToolExecutionPermit = object : ToolExecutionPermit {
                    override suspend fun acquire() { permitGate.await() }
                    override fun release() = Unit
                }
                override suspend fun execute(request: ToolExecutionRequest): ToolExecutionResult {
                    executionCount += 1
                    return ToolExecutionResult(request.toolCall.toolCallId, request.toolCall.toolName, JsonPrimitive("done"))
                }
            }
            runner(scriptedProvider("review-restored-queue"), listOf(tool), executor)
            executor.register(sessionId, NoopListener)
            val record = seededRecord(sessionId, "bg_restored", state = BackgroundExecutionState.QUEUED)
            store.putOrigin(BackgroundOriginContext(
                sessionId,
                record.originRunId,
                request("review-restored-queue", sessionId, backgroundConfig()).copy(messages = emptyList()),
            ))
            store.upsert(record)
            executor.resumeSession(sessionId)
            runCurrent()
            advanceTimeBy(60_001)
            permitGate.complete(Unit)
            runCurrent()
            assertEquals(0, executionCount, "Restored work older than maxQueuedLifetimeMillis must not start")
            val settled = store.load(sessionId).records.single()
            assertEquals(BackgroundExecutionState.TERMINAL, settled.state)
            assertEquals(BackgroundOutcome.INTERRUPTED, settled.outcome)
        } finally {
            executor.close()
        }
    }

    @Test
    fun interruptMustPreserveSpentHandoffWaitForResume() = runTest {
        val store = InMemoryBackgroundExecutionStore()
        val executor = executor(store)
        try {
            val sessionId = AgentSessionId("review-interrupted-budget")
            executor.register(sessionId, NoopListener)
            val tool = GatedTool("deep_lookup", ToolBackgroundPolicy.ELIGIBLE, ToolRecoveryPolicy.REPLAY_SAFE)
            val persistence = InMemoryAgentPersistence()
            val runner = runner(
                scriptedProvider("review-interrupted-budget", toolCall("call-1", "deep_lookup")),
                listOf(tool),
                executor,
                persistence,
            )
            val collected = async {
                runner.run(request("review-interrupted-budget", sessionId, backgroundConfig(handoffWait = 1_000))).toList()
            }
            runCurrent()
            advanceTimeBy(600)
            runCurrent()
            val interrupt = async { runner.interrupt(sessionId) }
            runCurrent()
            interrupt.await()
            runCatching { collected.await() }
            assertEquals(
                600L,
                persistence.load(sessionId)!!.snapshot.state.foregroundToolWaitMillis,
                "The 600ms already spent waiting must survive recovery of the same Run",
            )
        } finally {
            executor.close()
        }
    }

    private fun TestScope.executor(store: BackgroundExecutionStore): BackgroundToolExecutor =
        BackgroundToolExecutor(
            store = store,
            dispatcher = StandardTestDispatcher(testScheduler),
            timeSource = VirtualTimeSource(testScheduler),
            epochClock = { testScheduler.currentTime },
            random = Random(42),
        )

    private fun TestScope.runner(
        provider: ProviderAdapter,
        tools: List<ToolExecutor>,
        background: BackgroundToolExecutor? = null,
        persistence: AgentPersistence = InMemoryAgentPersistence(),
        interceptors: List<AgentInterceptor> = emptyList(),
        contextManager: ContextManager? = null,
        approvalGateway: saien.magrathea.core.ToolApprovalGateway? = null,
        tracer: saien.magrathea.core.MagratheaTracer = saien.magrathea.core.NoopMagratheaTracer,
    ) = DefaultAgentRunner(
        providerRegistry = InMemoryProviderRegistry(listOf(provider)),
        toolRegistry = InMemoryToolRegistry(tools),
        persistence = persistence,
        interceptors = interceptors,
        approvalGateway = approvalGateway,
        contextManager = contextManager,
        dispatcher = StandardTestDispatcher(testScheduler),
        tracer = tracer,
        background = background,
        timeSource = VirtualTimeSource(testScheduler),
    )

    /** First call emits [toolCalls]; every later call answers "done". */
    private fun scriptedProvider(key: String, vararg toolCalls: ToolCallPart) =
        object : ProviderAdapter {
            override val key = key
            val requests = mutableListOf<ProviderRequest>()
            private var calls = 0
            override suspend fun generate(request: ProviderRequest): Flow<ProviderChunk> = flow {
                requests += request
                calls += 1
                if (calls == 1 && toolCalls.isNotEmpty()) {
                    emit(providerChunk(toolCalls = toolCalls.toList(), completed = true))
                } else {
                    emit(providerChunk(text = "done", completed = true))
                }
            }
        }

    private fun request(
        provider: String,
        sessionId: AgentSessionId,
        config: BackgroundExecutionConfig?,
        tools: List<ToolDefinition> = emptyList(),
        messages: List<AgentMessage> = listOf(userMessage("look up project atlas")),
        systemPrompt: String = "",
        toolExecutionMode: ToolExecutionMode = ToolExecutionMode.PARALLEL,
        runTimeoutMillis: Long = 1_800_000,
    ) = AgentRequest(
        sessionId = sessionId,
        messages = messages,
        model = ModelDescriptor(provider = provider, model = "model"),
        tools = tools.ifEmpty {
            listOf(
                "slow_lookup", "deep_lookup", "other_lookup", "business_tool",
                "blocker", "fast_lookup", "patient_lookup", "blocking_lookup",
            ).map {
                ToolDefinition(
                    name = it,
                    description = "test tool $it",
                    schema = buildJsonObject { put("type", "object") },
                )
            }
        },
        systemPrompt = systemPrompt,
        engine = AgentEngineConfig(
            runtime = RuntimeConfig(
                maxTurns = 6,
                toolExecutionMode = toolExecutionMode,
                runTimeoutMillis = runTimeoutMillis,
                background = config,
            ),
        ),
    )

    private val requestTools
        get() = listOf(
            "slow_lookup", "deep_lookup", "other_lookup", "business_tool",
            "blocker", "fast_lookup", "patient_lookup", "blocking_lookup",
        )

    private fun backgroundConfig(
        handoffWait: Long = 1_000,
        explicitAllowance: Long = 10_000,
        maxActivations: Int = 4,
        policy: BackgroundConsumptionPolicy = BackgroundConsumptionPolicy.LATEST_CONTEXT,
    ) = BackgroundExecutionConfig(
        handoffWaitMillis = handoffWait,
        explicitWaitAllowanceMillis = explicitAllowance,
        maxAutomaticActivationsPerSource = maxActivations,
        maxQueuedLifetimeMillis = 60_000,
        consumptionPolicy = policy,
    )

    private fun toolCall(id: String, name: String, arguments: String = "{}") = ToolCallPart(
        toolCallId = id,
        toolName = name,
        arguments = kotlinx.serialization.json.Json.parseToJsonElement(arguments).jsonObject,
    )

    private fun userMessage(text: String) = AgentMessage(
        role = MessageRole.USER,
        parts = listOf(TextPart(text)),
    )

    private suspend fun rejectionCode(block: suspend () -> Unit): AgentSessionErrorCode? =
        (runCatching { block() }.exceptionOrNull() as? AgentSessionException)?.code

    private fun seededResult() = ToolExecutionResult(
        toolCallId = "origin-call",
        toolName = "deep_lookup",
        result = JsonPrimitive("found-it"),
        content = listOf(ToolResultTextContent("found-it", setOf(ToolResultAudience.MODEL))),
        metadata = buildJsonObject { put("seed", "yes") },
    )

    private fun seededRecord(
        sessionId: AgentSessionId,
        handle: String,
        state: BackgroundExecutionState = BackgroundExecutionState.TERMINAL,
        outcome: BackgroundOutcome? = null,
    ): BackgroundExecutionRecord {
        val request = ToolExecutionRequest(
            sessionId = sessionId,
            runId = AgentRunId("origin-run"),
            executionId = "exec-$handle",
            assistantMessage = AgentMessage(role = MessageRole.ASSISTANT, parts = emptyList()),
            toolCall = toolCall("origin-call", "deep_lookup"),
        )
        return BackgroundExecutionRecord(
            sessionId = sessionId,
            executionId = "exec-$handle",
            handle = handle,
            originRunId = AgentRunId("origin-run"),
            originCallId = "origin-call",
            toolName = "deep_lookup",
            sourceId = "source-user",
            state = state,
            outcome = outcome,
            result = if (outcome != null) seededResult() else null,
            executionRequest = request,
            effectiveTimeoutMillis = 1_000,
            queuedAtEpochMs = 0,
            startedAtEpochMs = 0,
            settledAtEpochMs = if (state == BackgroundExecutionState.TERMINAL) 1 else null,
        )
    }

    private class DelayedTool(
        name: String,
        private val delayMillis: Long,
        override val backgroundPolicy: ToolBackgroundPolicy,
    ) : ToolExecutor {
        override val definition = ToolDefinition(
            name = name,
            description = "delayed",
            schema = buildJsonObject { put("type", "object") },
        )

        var lastExecutionId: String? = null

        override suspend fun execute(request: ToolExecutionRequest): ToolExecutionResult {
            lastExecutionId = request.executionId
            delay(delayMillis)
            return ToolExecutionResult(
                toolCallId = request.toolCall.toolCallId,
                toolName = request.toolCall.toolName,
                result = JsonPrimitive("result-${request.toolCall.toolName}"),
            )
        }
    }

    private class GatedTool(
        name: String,
        override val backgroundPolicy: ToolBackgroundPolicy,
        override val recoveryPolicy: saien.magrathea.core.ToolRecoveryPolicy =
            saien.magrathea.core.ToolRecoveryPolicy.FAIL_CLOSED,
    ) : ToolExecutor {
        override val definition = ToolDefinition(
            name = name,
            description = "gated",
            schema = buildJsonObject { put("type", "object") },
        )
        val gate = CompletableDeferred<Unit>()
        var executions = 0
        var cancelled = false
        var lastExecutionId: String? = null

        override suspend fun execute(request: ToolExecutionRequest): ToolExecutionResult {
            executions += 1
            lastExecutionId = request.executionId
            try {
                gate.await()
            } catch (cancelled: CancellationException) {
                this.cancelled = true
                throw cancelled
            }
            return ToolExecutionResult(
                toolCallId = request.toolCall.toolCallId,
                toolName = request.toolCall.toolName,
                result = JsonPrimitive("result-${request.toolCall.toolName}"),
            )
        }
    }

    private class ErrorTool(
        name: String,
        private val delayMillis: Long,
    ) : ToolExecutor {
        override val definition = ToolDefinition(
            name = name,
            description = "failing",
            schema = buildJsonObject { put("type", "object") },
        )
        override val backgroundPolicy = ToolBackgroundPolicy.ELIGIBLE
        var lastExecutionId: String? = null

        override suspend fun execute(request: ToolExecutionRequest): ToolExecutionResult {
            lastExecutionId = request.executionId
            delay(delayMillis)
            return ToolExecutionResult(
                toolCallId = request.toolCall.toolCallId,
                toolName = request.toolCall.toolName,
                result = JsonPrimitive("lookup failed upstream"),
                isError = true,
                userErrorCode = "LOOKUP_FAILED",
                displayText = "Lookup failed",
            )
        }
    }

    private class PermitGatedTool(
        name: String,
        override val backgroundPolicy: ToolBackgroundPolicy,
    ) : ToolExecutor {
        override val definition = ToolDefinition(
            name = name,
            description = "permit-gated",
            schema = buildJsonObject { put("type", "object") },
        )
        private val permitGate = CompletableDeferred<Unit>()

        override fun executionPermit(request: ToolExecutionRequest): ToolExecutionPermit =
            object : ToolExecutionPermit {
                override suspend fun acquire() = permitGate.await()
                override fun release() = Unit
            }

        override suspend fun execute(request: ToolExecutionRequest): ToolExecutionResult =
            ToolExecutionResult(
                toolCallId = request.toolCall.toolCallId,
                toolName = request.toolCall.toolName,
                result = JsonPrimitive("result-${request.toolCall.toolName}"),
            )
    }

    private class CountingTool(
        name: String,
        override val recoveryPolicy: ToolRecoveryPolicy = ToolRecoveryPolicy.REPLAY_SAFE,
        override val backgroundPolicy: ToolBackgroundPolicy = ToolBackgroundPolicy.BLOCKING,
    ) : ToolExecutor {
        override val definition = ToolDefinition(
            name = name,
            description = "counting",
            schema = buildJsonObject { put("type", "object") },
        )
        var executions = 0

        override suspend fun execute(request: ToolExecutionRequest): ToolExecutionResult {
            executions += 1
            return ToolExecutionResult(
                toolCallId = request.toolCall.toolCallId,
                toolName = request.toolCall.toolName,
                result = JsonPrimitive("counted"),
            )
        }
    }

    private class RecordingContextManager(
        private val anchors: MutableList<String?>,
    ) : ContextManager {
        override suspend fun prepare(
            request: saien.magrathea.core.ContextPreparationRequest,
        ): saien.magrathea.core.ContextPreparationResult {
            anchors += request.activeRunStartMessageId
            return saien.magrathea.core.ContextPreparationResult(
                action = saien.magrathea.core.ContextPreparationAction.UNCHANGED,
                messages = request.request.messages,
                state = request.state.contextManagement,
                estimatedInputTokens = null,
                inputLimitTokens = null,
            )
        }
    }

    private class VirtualTimeSource(
        private val scheduler: TestCoroutineScheduler,
    ) : TimeSource.WithComparableMarks {
        override fun markNow(): ComparableTimeMark =
            VirtualMark(scheduler.currentTime.milliseconds, scheduler)
    }

    private class VirtualMark(
        private val at: Duration,
        private val scheduler: TestCoroutineScheduler,
    ) : ComparableTimeMark {
        override fun elapsedNow(): Duration = scheduler.currentTime.milliseconds - at
        override fun plus(duration: Duration): ComparableTimeMark = VirtualMark(at + duration, scheduler)
        override fun minus(duration: Duration): ComparableTimeMark = VirtualMark(at - duration, scheduler)
        override fun minus(other: ComparableTimeMark): Duration = at - (other as VirtualMark).at
        override fun compareTo(other: ComparableTimeMark): Int =
            at.compareTo((other as VirtualMark).at)
        override fun equals(other: Any?): Boolean =
            other is VirtualMark && at == other.at && scheduler == other.scheduler
        override fun hashCode(): Int = at.hashCode()
        override fun hasPassedNow(): Boolean = elapsedNow() >= Duration.ZERO
        override fun hasNotPassedNow(): Boolean = elapsedNow() < Duration.ZERO
    }

    private object NoopListener : BackgroundToolExecutor.SessionListener {
        override suspend fun onRecordChanged(
            sessionId: AgentSessionId,
            record: BackgroundExecutionRecord,
        ) = Unit

        override suspend fun onSettled(
            sessionId: AgentSessionId,
            record: BackgroundExecutionRecord,
        ) = Unit
    }
}
