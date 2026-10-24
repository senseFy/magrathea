@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package saien.magrathea.runtime

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.ComparableTimeMark
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import saien.magrathea.core.AgentEngineConfig
import saien.magrathea.core.AgentEvent
import saien.magrathea.core.AgentMessage
import saien.magrathea.core.AgentRequest
import saien.magrathea.core.AgentSessionId
import saien.magrathea.core.AgentStateSnapshot
import saien.magrathea.core.BackgroundConsumptionPolicy
import saien.magrathea.core.BackgroundExecutionConfig
import saien.magrathea.core.BackgroundExecutionRecord
import saien.magrathea.core.BackgroundExecutionState
import saien.magrathea.core.FollowUpMessageProvider
import saien.magrathea.core.MessageRole
import saien.magrathea.core.ModelDescriptor
import saien.magrathea.core.RuntimeConfig
import saien.magrathea.core.StopReason
import saien.magrathea.core.TextPart
import saien.magrathea.core.ToolBackgroundPolicy
import saien.magrathea.core.ToolCallPart
import saien.magrathea.core.ToolDefinition
import saien.magrathea.core.ToolExecutionRequest
import saien.magrathea.core.ToolExecutionResult
import saien.magrathea.core.ToolExecutor
import saien.magrathea.core.ToolResultPart
import saien.magrathea.provider.api.InMemoryProviderRegistry
import saien.magrathea.provider.api.ProviderAdapter
import saien.magrathea.provider.api.ProviderChunk
import saien.magrathea.provider.api.ProviderRequest

class BackgroundControlRunScopeContractTest {
    @Test
    fun exhaustedAwaitInPreviousRunMustNotConsumeNextRunAllowance() = runTest {
        crossRunControlScenario(await = true)
    }

    @Test
    fun pendingReadInPreviousRunMustNotRefuseFirstReadOfNextRun() = runTest {
        crossRunControlScenario(await = false)
    }

    @Test
    fun resumedRunStillRefusesAwaitAfterExhaustion() = runTest {
        resumedControlScenario(await = true)
    }

    @Test
    fun resumedRunStillRefusesRepeatedPendingRead() = runTest {
        resumedControlScenario(await = false)
    }

    @Test
    fun followUpUserMessageDoesNotResetExhaustedAwait() = runTest {
        followUpControlScenario(await = true)
    }

    @Test
    fun followUpUserMessageDoesNotResetPendingReadGuard() = runTest {
        followUpControlScenario(await = false)
    }

    private suspend fun TestScope.crossRunControlScenario(await: Boolean) {
        val store = InMemoryBackgroundExecutionStore()
        val executor = executor(store)
        val persistence = InMemoryAgentPersistence()
        val sessionId = AgentSessionId("control-new-run-$await")
        val provider = controlProvider(await)
        val runner = runner(provider, executor, persistence)
        try {
            executor.register(sessionId, NoopListener)
            val firstRequest = request(provider.key, sessionId, await)
            val first = runner.run(firstRequest).toList()
                .filterIsInstance<AgentEvent.Completed>().single().state
            assertInitialControlSucceeded(first, await)
            assertEquals(BackgroundExecutionState.RUNNING, store.load(sessionId).records.single().state)
            val firstRunId = checkNotNull(persistence.load(sessionId)).snapshot.runId

            val second = runner.run(
                firstRequest.copy(messages = first.messages + userMessage("Check once more in this new Run")),
            ).toList().filterIsInstance<AgentEvent.Completed>().single().state
            assertNotEquals(firstRunId, checkNotNull(persistence.load(sessionId)).snapshot.runId)
            val secondResult = second.controlResult("control-4")
            assertFalse(
                secondResult.isError,
                "The first control in a new Run must not inherit the prior Run polling guard: " +
                    secondResult.result,
            )
            if (await) {
                assertEquals(50L, second.explicitWaitMillis)
                assertEquals(JsonPrimitive(true), secondResult.result.jsonObject["wait_allowance_exhausted"])
            } else {
                assertEquals("running", secondResult.result.jsonObject["status"]?.jsonPrimitive?.content)
            }
        } finally {
            executor.close()
        }
    }

    private suspend fun TestScope.resumedControlScenario(await: Boolean) {
        val store = InMemoryBackgroundExecutionStore()
        val executor = executor(store)
        val persistence = InMemoryAgentPersistence()
        val sessionId = AgentSessionId("control-resumed-run-$await")
        val waitingForInterrupt = CompletableDeferred<Unit>()
        val provider = controlProvider(await, waitingForInterrupt)
        val runner = runner(provider, executor, persistence)
        try {
            executor.register(sessionId, NoopListener)
            val running = async { runner.run(request(provider.key, sessionId, await)).toList() }
            // The first read/await has been committed before the next Provider invocation blocks.
            waitingForInterrupt.await()
            runner.interrupt(sessionId)
            runCatching { running.await() }
            val interrupted = checkNotNull(persistence.load(sessionId)).snapshot
            assertEquals(StopReason.INTERRUPTED, interrupted.state.stopReason)
            assertInitialControlSucceeded(interrupted.state, await)
            assertEquals(BackgroundExecutionState.RUNNING, store.load(sessionId).records.single().state)

            // A new Runner instance prevents a transient in-memory counter from satisfying resume.
            val restoredRunner = runner(provider, executor, persistence)
            val resumed = restoredRunner.resume(sessionId).toList()
                .filterIsInstance<AgentEvent.Completed>().single().state
            assertEquals(interrupted.runId, checkNotNull(persistence.load(sessionId)).snapshot.runId)
            val repeated = resumed.controlResult("control-4")
            assertTrue(repeated.isError, "Resuming the same Run must retain its polling guard")
            if (await) {
                assertEquals(50L, resumed.explicitWaitMillis)
                assertEquals(JsonPrimitive(true), repeated.result.jsonObject["wait_allowance_exhausted"])
            } else {
                assertEquals("running", repeated.result.jsonObject["status"]?.jsonPrimitive?.content)
                assertTrue(repeated.result.jsonObject["error"]!!.jsonPrimitive.content.startsWith("Already checked:"))
            }
        } finally {
            executor.close()
        }
    }

    private fun assertInitialControlSucceeded(state: AgentStateSnapshot, await: Boolean) {
        val result = state.controlResult("control-2")
        assertFalse(result.isError)
        if (await) {
            assertEquals(JsonPrimitive(true), result.result.jsonObject["wait_allowance_exhausted"])
            assertEquals(50L, state.explicitWaitMillis)
        } else {
            assertEquals("running", result.result.jsonObject["status"]?.jsonPrimitive?.content)
        }
    }

    private suspend fun TestScope.followUpControlScenario(await: Boolean) {
        val store = InMemoryBackgroundExecutionStore()
        val executor = executor(store)
        val persistence = InMemoryAgentPersistence()
        val sessionId = AgentSessionId("control-follow-up-$await")
        val provider = controlProvider(await, repeatAt = 3)
        val followUp = userMessage("Check once more within this Run")
        var injected = false
        val runner = runner(
            provider,
            executor,
            persistence,
            followUpMessageProvider = FollowUpMessageProvider { context ->
                if (!injected && context.state.messages.any { message ->
                        message.parts.filterIsInstance<ToolResultPart>().any { it.toolCallId == "control-2" }
                    }
                ) {
                    injected = true
                    listOf(followUp)
                } else {
                    emptyList()
                }
            },
        )
        try {
            executor.register(sessionId, NoopListener)
            val completed = runner.run(request(provider.key, sessionId, await)).toList()
                .filterIsInstance<AgentEvent.Completed>().single().state
            assertInitialControlSucceeded(completed, await)
            val firstIndex = completed.messages.indexOfFirst { message ->
                message.parts.filterIsInstance<ToolResultPart>().any { it.toolCallId == "control-2" }
            }
            val followUpIndex = completed.messages.indexOfFirst { it.id == followUp.id }
            val repeatedIndex = completed.messages.indexOfFirst { message ->
                message.parts.filterIsInstance<ToolResultPart>().any { it.toolCallId == "control-3" }
            }
            assertTrue(firstIndex >= 0 && followUpIndex > firstIndex && repeatedIndex > followUpIndex)
            val repeated = completed.controlResult("control-3")
            assertTrue(repeated.isError, "A follow-up USER message must not start a fresh polling allowance")
            if (await) {
                assertEquals(50L, completed.explicitWaitMillis)
                assertEquals(JsonPrimitive(true), repeated.result.jsonObject["wait_allowance_exhausted"])
            } else {
                assertTrue(repeated.result.jsonObject["error"]!!.jsonPrimitive.content.startsWith("Already checked:"))
            }
        } finally {
            executor.close()
        }
    }

    private fun controlProvider(
        await: Boolean,
        waitingForInterrupt: CompletableDeferred<Unit>? = null,
        repeatAt: Int = 4,
    ) = object : ProviderAdapter {
        override val key = "control-run-scope-$await"
        private var calls = 0

        override suspend fun generate(request: ProviderRequest): Flow<ProviderChunk> = flow {
            calls += 1
            when {
                calls == 1 -> emit(providerChunk(toolCalls = listOf(toolCall("launch", "deep_lookup")), completed = true))
                calls == 2 || calls == repeatAt -> {
                    val handle = request.messages.flatMap { it.parts }
                        .filterIsInstance<ToolResultPart>().firstNotNullOf { it.backgroundReceipt?.handle }
                    val control = if (await) {
                        toolCall("control-$calls", "await_background_results", """{"handles":["$handle"]}""")
                    } else {
                        toolCall("control-$calls", "read_background_result", """{"handle":"$handle"}""")
                    }
                    emit(providerChunk(toolCalls = listOf(control), completed = true))
                }
                calls == 3 -> {
                    if (waitingForInterrupt != null) {
                        waitingForInterrupt.complete(Unit)
                        awaitCancellation()
                    }
                    emit(providerChunk(text = "done", completed = true))
                }
                else -> emit(providerChunk(text = "done", completed = true))
            }
        }
    }

    private fun AgentStateSnapshot.controlResult(id: String): ToolResultPart =
        messages.flatMap { it.parts }.filterIsInstance<ToolResultPart>().single { it.toolCallId == id }

    private fun TestScope.executor(store: InMemoryBackgroundExecutionStore) = BackgroundToolExecutor(
        store = store,
        dispatcher = StandardTestDispatcher(testScheduler),
        timeSource = VirtualTimeSource(testScheduler),
        epochClock = { testScheduler.currentTime },
        random = Random(42),
    )

    private fun TestScope.runner(
        provider: ProviderAdapter,
        background: BackgroundToolExecutor,
        persistence: InMemoryAgentPersistence,
        followUpMessageProvider: FollowUpMessageProvider = FollowUpMessageProvider { emptyList() },
    ) = DefaultAgentRunner(
        providerRegistry = InMemoryProviderRegistry(listOf(provider)),
        toolRegistry = InMemoryToolRegistry(listOf(GatedTool)),
        persistence = persistence,
        followUpMessageProvider = followUpMessageProvider,
        dispatcher = StandardTestDispatcher(testScheduler),
        background = background,
        timeSource = VirtualTimeSource(testScheduler),
    )

    private fun request(provider: String, sessionId: AgentSessionId, await: Boolean) = AgentRequest(
        sessionId = sessionId,
        messages = listOf(userMessage("look up project atlas")),
        model = ModelDescriptor(provider = provider, model = "model"),
        tools = listOf(GatedTool.definition),
        engine = AgentEngineConfig(
            runtime = RuntimeConfig(
                maxTurns = 6,
                runTimeoutMillis = 1_800_000,
                background = BackgroundExecutionConfig(
                    handoffWaitMillis = 100,
                    explicitWaitAllowanceMillis = if (await) 50 else 0,
                    maxAutomaticActivationsPerSource = 4,
                    maxQueuedLifetimeMillis = 60_000,
                    consumptionPolicy = BackgroundConsumptionPolicy.LATEST_CONTEXT,
                ),
            ),
        ),
    )

    private fun toolCall(id: String, name: String, arguments: String = "{}") = ToolCallPart(
        toolCallId = id,
        toolName = name,
        arguments = Json.parseToJsonElement(arguments).jsonObject,
    )

    private fun userMessage(text: String) = AgentMessage(role = MessageRole.USER, parts = listOf(TextPart(text)))

    private object GatedTool : ToolExecutor {
        override val definition = ToolDefinition("deep_lookup", "gated", buildJsonObject { put("type", "object") })
        override val backgroundPolicy = ToolBackgroundPolicy.ELIGIBLE
        override suspend fun execute(request: ToolExecutionRequest): ToolExecutionResult = awaitCancellation()
    }

    private class VirtualTimeSource(private val scheduler: TestCoroutineScheduler) : TimeSource.WithComparableMarks {
        override fun markNow(): ComparableTimeMark = VirtualMark(scheduler.currentTime.milliseconds, scheduler)
    }

    private class VirtualMark(
        private val at: Duration,
        private val scheduler: TestCoroutineScheduler,
    ) : ComparableTimeMark {
        override fun elapsedNow(): Duration = scheduler.currentTime.milliseconds - at
        override fun plus(duration: Duration): ComparableTimeMark = VirtualMark(at + duration, scheduler)
        override fun minus(duration: Duration): ComparableTimeMark = VirtualMark(at - duration, scheduler)
        override fun minus(other: ComparableTimeMark): Duration = at - (other as VirtualMark).at
        override fun compareTo(other: ComparableTimeMark): Int = at.compareTo((other as VirtualMark).at)
        override fun equals(other: Any?): Boolean = other is VirtualMark && at == other.at && scheduler == other.scheduler
        override fun hashCode(): Int = at.hashCode()
        override fun hasPassedNow(): Boolean = elapsedNow() >= Duration.ZERO
        override fun hasNotPassedNow(): Boolean = elapsedNow() < Duration.ZERO
    }

    private object NoopListener : BackgroundToolExecutor.SessionListener {
        override suspend fun onRecordChanged(sessionId: AgentSessionId, record: BackgroundExecutionRecord) = Unit
        override suspend fun onSettled(sessionId: AgentSessionId, record: BackgroundExecutionRecord) = Unit
    }
}
