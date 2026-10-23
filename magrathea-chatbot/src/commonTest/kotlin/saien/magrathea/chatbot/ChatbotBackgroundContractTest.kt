@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package saien.magrathea.chatbot

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import saien.magrathea.core.AgentEngineConfig
import saien.magrathea.core.BackgroundConsumptionPolicy
import saien.magrathea.core.BackgroundExecutionConfig
import saien.magrathea.core.RuntimeConfig
import saien.magrathea.core.StopReason
import saien.magrathea.core.ToolBackgroundPolicy
import saien.magrathea.core.ToolCallPart
import saien.magrathea.core.ToolDefinition
import saien.magrathea.core.ToolExecutionRequest
import saien.magrathea.core.ToolExecutionResult
import saien.magrathea.core.ToolResultPart
import saien.magrathea.provider.api.InMemoryProviderRegistry
import saien.magrathea.provider.api.ProviderAdapter
import saien.magrathea.provider.api.ProviderChunk
import saien.magrathea.provider.api.ProviderEvent
import saien.magrathea.provider.api.ProviderRequest
import saien.magrathea.runtime.BackgroundToolExecutor
import saien.magrathea.runtime.DefaultAgentRunner
import saien.magrathea.runtime.InMemoryAgentPersistence
import saien.magrathea.runtime.InMemoryBackgroundExecutionStore
import saien.magrathea.runtime.InMemoryToolRegistry
import saien.magrathea.core.ToolExecutor

@OptIn(kotlin.time.ExperimentalTime::class)
class ChatbotBackgroundContractTest {
    // 1. send -> handoff -> RUNNING activity -> settle -> pending activation -> continuation.
    @Test
    fun endToEndHandoffSettleAndContinuation() = runTest {
        val store = InMemoryBackgroundExecutionStore()
        val executor = executor(store)
        val tool = GatedTool("deep_lookup")
        val provider = ScriptedProvider("test")
        provider.script += { chunk(toolCalls = listOf(toolCall("call-1", "deep_lookup"))) }
        provider.script += { chunk(text = "answer") }
        provider.script += { request ->
            val handle = request.messages
                .flatMap { it.parts }
                .filterIsInstance<ToolResultPart>()
                .firstNotNullOf { it.backgroundReceipt?.handle }
            chunk(
                toolCalls = listOf(
                    toolCall(
                        "read-1",
                        "read_background_result",
                        """{"handle":"$handle"}""",
                    ),
                ),
            )
        }
        provider.script += { chunk(text = "supplement") }
        val persistence = InMemoryAgentPersistence()
        val runner = runner(provider, listOf(tool), executor, persistence)
        val client = client(runner, persistence, executor, backgroundConfig())
        val session = client.createSession(testChatbotConfiguration(provider = "test"))

        session.send("look up project atlas")
        advanceTimeBy(50)
        runCurrent()

        val waiting = session.snapshot()
        assertEquals(ChatbotStatus.COMPLETED, waiting.status)
        val background = assertNotNull(waiting.background)
        assertEquals(listOf(ChatbotBackgroundState.RUNNING), background.executions.map { it.state })
        assertTrue(background.requiresExecutionHost)
        val activity = waiting.toolActivities.single { it.call.id == "call-1" }
        assertEquals(ChatbotToolActivityStatus.RUNNING, activity.status)
        assertNull(activity.result)
        val backgroundExecution = assertNotNull(activity.background)
        assertEquals("deep_lookup", backgroundExecution.toolName)
        assertEquals("call-1", backgroundExecution.originCallId)
        assertEquals(ChatbotBackgroundState.RUNNING, backgroundExecution.state)
        val handle = backgroundExecution.handle

        tool.gate.complete(Unit)
        advanceTimeBy(50)
        runCurrent()

        val settled = session.snapshot()
        val settledBackground = assertNotNull(settled.background)
        assertEquals(
            listOf(ChatbotBackgroundState.SUCCEEDED),
            settledBackground.executions.map { it.state },
        )
        assertTrue(settledBackground.hasPendingActivation)
        val settledActivity = settled.toolActivities.single { it.call.id == "call-1" }
        assertEquals(ChatbotToolActivityStatus.SUCCEEDED, settledActivity.status)
        assertNull(settledActivity.result)
        assertEquals(ChatbotBackgroundState.SUCCEEDED, settledActivity.background?.state)

        assertTrue(session.continueWithBackgroundResults())
        advanceUntilIdle()

        val continued = session.snapshot()
        assertFalse(assertNotNull(continued.background).hasPendingActivation)
        val answered = continued.toolActivities.single { it.call.id == "call-1" }
        assertEquals(ChatbotToolActivityStatus.SUCCEEDED, answered.status)
        val result = assertNotNull(answered.result)
        assertEquals("call-1", result.id)
        assertEquals("deep_lookup", result.name)
        assertEquals(listOf(ChatbotCitation("Atlas", "https://atlas", "note")), result.citations)
        // Control calls never become activities and their messages are dropped.
        assertTrue(continued.toolActivities.none { it.call.id == "read-1" })
        assertTrue(continued.messages.none { it.toolCalls.any { call -> call.id == "read-1" } })
        assertTrue(continued.messages.none { it.toolResults.any { it.id == "read-1" } })
        assertEquals("supplement", continued.messages.last().text)
        assertEquals(ChatbotStatus.COMPLETED, continued.status)

        client.close()
        executor.close()
    }

    // 2. continueWithBackgroundResults is false without a pending activation or while running.
    @Test
    fun continueWithBackgroundResultsRejectsWhenNothingIsPending() = runTest {
        val store = InMemoryBackgroundExecutionStore()
        val executor = executor(store)
        val tool = GatedTool("deep_lookup")
        val provider = ScriptedProvider("test")
        provider.script += { chunk(toolCalls = listOf(toolCall("call-1", "deep_lookup"))) }
        provider.script += { chunk(text = "answer") }
        provider.script += { chunk(toolCalls = listOf(toolCall("call-2", "blocking_lookup"))) }
        val blocking = GatedTool("blocking_lookup", ToolBackgroundPolicy.BLOCKING)
        val persistence = InMemoryAgentPersistence()
        val client = client(
            runner(provider, listOf(tool, blocking), executor, persistence),
            persistence,
            executor,
            backgroundConfig(),
        )
        val session = client.createSession(testChatbotConfiguration(provider = "test"))

        assertFalse(session.continueWithBackgroundResults())

        session.send("hi")
        advanceTimeBy(50)
        runCurrent()
        tool.gate.complete(Unit)
        advanceTimeBy(50)
        runCurrent()
        assertTrue(assertNotNull(session.snapshot().background).hasPendingActivation)

        session.send("again")
        assertFalse(session.continueWithBackgroundResults())

        blocking.gate.complete(Unit)
        advanceUntilIdle()
        client.close()
        executor.close()
    }

    // 3. stopAll cancels the Run and every live execution; nothing stays pending or activatable.
    @Test
    fun stopAllDuringRunCancelsTheRecordAndTheActivity() = runTest {
        val store = InMemoryBackgroundExecutionStore()
        val executor = executor(store)
        val handedOff = GatedTool("deep_lookup")
        val running = GatedTool("slow_lookup")
        val provider = ScriptedProvider("test")
        provider.script += { chunk(toolCalls = listOf(toolCall("call-1", "deep_lookup"))) }
        provider.script += { chunk(toolCalls = listOf(toolCall("call-2", "slow_lookup"))) }
        val persistence = InMemoryAgentPersistence()
        val client = client(
            runner(provider, listOf(handedOff, running), executor, persistence),
            persistence,
            executor,
            backgroundConfig(),
        )
        val session = client.createSession(testChatbotConfiguration(provider = "test"))

        session.send("first")
        advanceTimeBy(50)
        runCurrent()
        session.send("second")
        runCurrent()

        session.stopAll()
        runCurrent()

        val stopped = session.snapshot()
        val background = assertNotNull(stopped.background)
        assertEquals(
            listOf(ChatbotBackgroundState.CANCELLED, ChatbotBackgroundState.CANCELLED),
            background.executions.map { it.state },
        )
        assertFalse(background.hasPendingActivation)
        assertFalse(background.requiresExecutionHost)
        assertEquals(
            ChatbotToolActivityStatus.CANCELLED,
            stopped.toolActivities.single { it.call.id == "call-1" }.status,
        )
        assertEquals(
            ChatbotToolActivityStatus.CANCELLED,
            stopped.toolActivities.single { it.call.id == "call-2" }.status,
        )
        client.close()
        executor.close()
    }

    // 4. cancelBackground partitions handles and marks the activity CANCELLED.
    @Test
    fun cancelBackgroundPartitionsHandlesAndCancelsTheActivity() = runTest {
        val store = InMemoryBackgroundExecutionStore()
        val executor = executor(store)
        val tool = GatedTool("deep_lookup")
        val provider = ScriptedProvider("test")
        provider.script += { chunk(toolCalls = listOf(toolCall("call-1", "deep_lookup"))) }
        val persistence = InMemoryAgentPersistence()
        val client = client(
            runner(provider, listOf(tool), executor, persistence),
            persistence,
            executor,
            backgroundConfig(),
        )
        val session = client.createSession(testChatbotConfiguration(provider = "test"))

        session.send("hi")
        advanceTimeBy(50)
        runCurrent()
        val handle = assertNotNull(
            session.snapshot().toolActivities.single { it.call.id == "call-1" }.background,
        ).handle

        val result = session.cancelBackground(listOf(handle, "bg_missing"))
        assertEquals(listOf(handle), result.cancelled)
        assertEquals(emptyList(), result.alreadyFinished)
        assertEquals(listOf("bg_missing"), result.unknown)

        runCurrent()
        val snapshot = session.snapshot()
        assertEquals(
            ChatbotBackgroundState.CANCELLED,
            assertNotNull(snapshot.background).executions.single().state,
        )
        assertEquals(
            ChatbotToolActivityStatus.CANCELLED,
            snapshot.toolActivities.single { it.call.id == "call-1" }.status,
        )
        client.close()
        executor.close()
    }

    // 5. send during the foreground wait: the handed-off call keeps running, started and
    // unstarted calls close as cancelled — never SUCCEEDED.
    @Test
    fun sendWhileWaitingPreemptsCallsWithCancelledActivities() = runTest {
        val store = InMemoryBackgroundExecutionStore()
        val executor = executor(store)
        val eligible = GatedTool("deep_lookup")
        val blocking = GatedTool("blocking_lookup", ToolBackgroundPolicy.BLOCKING)
        val provider = ScriptedProvider("test")
        provider.script += {
            chunk(
                toolCalls = listOf(
                    toolCall("call-1", "deep_lookup"),
                    toolCall("call-2", "blocking_lookup"),
                ),
            )
        }
        val persistence = InMemoryAgentPersistence()
        val client = client(
            runner(provider, listOf(eligible, blocking), executor, persistence),
            persistence,
            executor,
            backgroundConfig(handoffWait = 60_000),
        )
        val session = client.createSession(testChatbotConfiguration(provider = "test"))

        session.send("first")
        runCurrent()
        session.send("second")
        runCurrent()

        val snapshot = session.snapshot()
        val eligibleActivity = snapshot.toolActivities.single { it.call.id == "call-1" }
        assertEquals(ChatbotToolActivityStatus.RUNNING, eligibleActivity.status)
        assertNotNull(eligibleActivity.background)
        val blockingActivity = snapshot.toolActivities.single { it.call.id == "call-2" }
        assertEquals(ChatbotToolActivityStatus.CANCELLED, blockingActivity.status)
        assertNull(blockingActivity.result)
        assertTrue(
            snapshot.toolActivities.none {
                it.status == ChatbotToolActivityStatus.SUCCEEDED
            },
        )
        client.close()
        executor.close()
    }

    // 7. A client without an executor has a null background projection and unchanged behavior.
    @Test
    fun clientWithoutExecutorProjectsNoBackground() = runTest {
        val provider = ScriptedProvider("test")
        provider.script += { chunk(text = "answer") }
        val persistence = InMemoryAgentPersistence()
        val runner = DefaultAgentRunner(
            providerRegistry = InMemoryProviderRegistry(listOf(provider)),
            toolRegistry = InMemoryToolRegistry(emptyList()),
            persistence = persistence,
            dispatcher = StandardTestDispatcher(testScheduler),
            timeSource = VirtualTimeSource(testScheduler),
        )
        val client = createChatbotClient(
            runner = runner,
            requestFactory = DefaultChatbotRequestFactory(),
            persistence = persistence,
            closeResources = {},
            sessionDispatcher = StandardTestDispatcher(testScheduler),
        )
        val session = client.createSession(testChatbotConfiguration(provider = "test"))

        session.send("hi")
        advanceUntilIdle()

        val snapshot = session.snapshot()
        assertNull(snapshot.background)
        assertEquals(ChatbotStatus.COMPLETED, snapshot.status)
        assertEquals("answer", snapshot.messages.last().text)
        client.close()
    }

    // ---- harness ----

    private fun backgroundConfig(
        handoffWait: Long = 10,
        policy: BackgroundConsumptionPolicy = BackgroundConsumptionPolicy.LATEST_CONTEXT,
    ) = BackgroundExecutionConfig(
        handoffWaitMillis = handoffWait,
        explicitWaitAllowanceMillis = 10_000,
        maxAutomaticActivationsPerSource = 4,
        maxQueuedLifetimeMillis = 60_000,
        consumptionPolicy = policy,
    )

    private fun TestScope.executor(store: InMemoryBackgroundExecutionStore) =
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
        executor: BackgroundToolExecutor,
        persistence: InMemoryAgentPersistence,
    ) = DefaultAgentRunner(
        providerRegistry = InMemoryProviderRegistry(listOf(provider)),
        toolRegistry = InMemoryToolRegistry(tools),
        persistence = persistence,
        dispatcher = StandardTestDispatcher(testScheduler),
        background = executor,
        timeSource = VirtualTimeSource(testScheduler),
    )

    private fun TestScope.client(
        runner: DefaultAgentRunner,
        persistence: InMemoryAgentPersistence,
        executor: BackgroundToolExecutor,
        config: BackgroundExecutionConfig,
    ): ChatbotClient = createChatbotClient(
        runner = runner,
        requestFactory = DefaultChatbotRequestFactory(
            tools = listOf(
                "deep_lookup", "slow_lookup", "blocking_lookup",
            ).map { ToolDefinition(it, "tool $it", buildJsonObject { put("type", "object") }) },
            configure = {
                copy(
                    engine = engine.copy(
                        runtime = engine.runtime.copy(background = config),
                    ),
                )
            },
        ),
        persistence = persistence,
        closeResources = {},
        sessionDispatcher = StandardTestDispatcher(testScheduler),
        background = executor,
    )

    private fun toolCall(id: String, name: String, arguments: String = "{}") = ToolCallPart(
        toolCallId = id,
        toolName = name,
        arguments = kotlinx.serialization.json.Json.parseToJsonElement(arguments).jsonObject,
        partial = false,
    )

    private fun chunk(
        text: String? = null,
        toolCalls: List<ToolCallPart> = emptyList(),
    ) = ProviderChunk(
        buildList {
            text?.let {
                add(ProviderEvent.TextStart())
                add(ProviderEvent.TextDelta(it))
                add(ProviderEvent.TextEnd())
            }
            toolCalls.forEach { toolCall ->
                add(ProviderEvent.ToolCallStart(toolCall.copy(partial = true)))
                add(ProviderEvent.ToolCallEnd(toolCall))
            }
            add(
                ProviderEvent.Completed(
                    finishReason = if (toolCalls.isEmpty()) "COMPLETED" else "TOOL_CALLS",
                    stopReason = if (toolCalls.isEmpty()) StopReason.COMPLETED else StopReason.TOOL_CALLS,
                    usage = null,
                ),
            )
        },
    )

    private class ScriptedProvider(
        override val key: String,
    ) : ProviderAdapter {
        val requests = mutableListOf<ProviderRequest>()
        val script = mutableListOf<(ProviderRequest) -> ProviderChunk>()
        private var calls = 0

        override suspend fun generate(request: ProviderRequest): Flow<ProviderChunk> = flow {
            requests += request
            calls += 1
            emit(
                script.getOrNull(calls - 1)?.invoke(request)
                    ?: ProviderChunk(
                        listOf(
                            ProviderEvent.TextStart(),
                            ProviderEvent.TextDelta("done"),
                            ProviderEvent.TextEnd(),
                            ProviderEvent.Completed("COMPLETED", StopReason.COMPLETED, null),
                        ),
                    ),
            )
        }
    }

    private class GatedTool(
        name: String,
        override val backgroundPolicy: ToolBackgroundPolicy = ToolBackgroundPolicy.ELIGIBLE,
    ) : ToolExecutor {
        override val definition = ToolDefinition(
            name = name,
            description = "gated",
            schema = buildJsonObject { put("type", "object") },
        )
        val gate = CompletableDeferred<Unit>()

        override suspend fun execute(request: ToolExecutionRequest): ToolExecutionResult {
            gate.await()
            return ToolExecutionResult(
                toolCallId = request.toolCall.toolCallId,
                toolName = request.toolCall.toolName,
                result = JsonPrimitive("result-${request.toolCall.toolName}"),
                metadata = buildJsonObject {
                    putJsonArray("citations") {
                        addJsonObject {
                            put("title", "Atlas")
                            put("url", "https://atlas")
                            put("snippet", "note")
                        }
                    }
                },
            )
        }
    }

    private class VirtualTimeSource(
        private val scheduler: TestCoroutineScheduler,
    ) : TimeSource.WithComparableMarks {
        override fun markNow(): kotlin.time.ComparableTimeMark =
            VirtualMark(scheduler.currentTime.milliseconds, scheduler)
    }

    private class VirtualMark(
        private val at: Duration,
        private val scheduler: TestCoroutineScheduler,
    ) : kotlin.time.ComparableTimeMark {
        override fun elapsedNow(): Duration = scheduler.currentTime.milliseconds - at
        override fun plus(duration: Duration) = VirtualMark(at + duration, scheduler)
        override fun minus(duration: Duration) = VirtualMark(at - duration, scheduler)
        override fun minus(other: kotlin.time.ComparableTimeMark) = at - (other as VirtualMark).at
        override fun compareTo(other: kotlin.time.ComparableTimeMark) =
            at.compareTo((other as VirtualMark).at)
        override fun equals(other: Any?) =
            other is VirtualMark && at == other.at && scheduler == other.scheduler
        override fun hashCode() = at.hashCode()
        override fun hasPassedNow() = elapsedNow() >= Duration.ZERO
        override fun hasNotPassedNow() = elapsedNow() < Duration.ZERO
    }
}
