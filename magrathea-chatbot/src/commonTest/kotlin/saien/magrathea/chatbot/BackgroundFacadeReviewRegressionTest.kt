@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package saien.magrathea.chatbot

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import saien.magrathea.core.AgentEngineConfig
import saien.magrathea.core.AgentMessage
import saien.magrathea.core.BackgroundConsumptionPolicy
import saien.magrathea.core.BackgroundExecutionConfig
import saien.magrathea.core.BackgroundExecutionState
import saien.magrathea.core.BackgroundOutcome
import saien.magrathea.core.MessageRole
import saien.magrathea.core.RemoteToolImageSource
import saien.magrathea.core.RuntimeConfig
import saien.magrathea.core.StopReason
import saien.magrathea.core.ToolBackgroundPolicy
import saien.magrathea.core.ToolBackgroundReceipt
import saien.magrathea.core.ToolCallPart
import saien.magrathea.core.ToolDefinition
import saien.magrathea.core.ToolExecutionRequest
import saien.magrathea.core.ToolExecutionResult
import saien.magrathea.core.ToolOrigin
import saien.magrathea.core.ToolResultAudience
import saien.magrathea.core.ToolResultImageContent
import saien.magrathea.core.ToolResultPart
import saien.magrathea.core.ToolResultTextContent
import saien.magrathea.runtime.BackgroundExecutionView
import saien.magrathea.runtime.BackgroundSessionSnapshot
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
class BackgroundFacadeReviewRegressionTest {
    @Test
    fun mixedControlAndBusinessBatchKeepsOriginalBackgroundIdentities() = runTest {
        val store = InMemoryBackgroundExecutionStore()
        val executor = executor(store)
        val first = GatedTool("deep_lookup")
        val second = GatedTool("slow_lookup")
        val provider = ScriptedProvider("test")
        provider.script += {
            chunk(toolCalls = listOf(
                toolCall("read-1", "read_background_result", """{"handle":"bg_missing"}"""),
                toolCall("call-1", "deep_lookup"),
                toolCall("call-2", "slow_lookup"),
            ))
        }
        provider.script += { chunk(text = "done") }
        val persistence = InMemoryAgentPersistence()
        val runner = runner(provider, listOf(first, second), executor, persistence)
        val client = client(runner, persistence, executor, backgroundConfig())
        try {
            val session = client.createSession(testChatbotConfiguration(provider = "test"))
            session.send("parallel lookups")
            advanceTimeBy(50)
            runCurrent()
            val snapshot = session.snapshot()
            assertEquals(ChatbotStatus.COMPLETED, snapshot.status)
            val activities = snapshot.toolActivities
            assertEquals(listOf("call-1", "call-2"), activities.map { it.call.id })
            val records = store.load(saien.magrathea.core.AgentSessionId(checkNotNull(snapshot.sessionId))).records
            assertEquals(2, records.size)
            val expected = activities.map { activity -> records.single { it.originCallId == activity.call.id }.handle }
            assertEquals(expected, activities.map { it.background?.handle }, "Hidden controls must not renumber business call identities")
            assertTrue(activities.all { it.status == ChatbotToolActivityStatus.RUNNING })
        } finally {
            client.close()
            executor.close()
        }
    }

    @Test
    fun enablingBackgroundPreservesCompletedHistoricalBusinessCall() {
        val call = AgentMessage(role = MessageRole.ASSISTANT, parts = listOf(toolCall("legacy-1", "finish_without_reply")))
        val result = AgentMessage(role = MessageRole.TOOL, parts = listOf(ToolResultPart(
            toolCallId = "legacy-1",
            toolName = "finish_without_reply",
            result = JsonPrimitive("Business result"),
        )))
        val messages = listOf(call, result)
        assertEquals(1, messages.toChatbotMessageSnapshots(false).sumOf { it.toolCalls.size })
        val enabled = messages.toChatbotMessageSnapshots(true)
        assertEquals(1, enabled.sumOf { it.toolCalls.size }, "The latest request's control namespace must not hide an already answered historical business call")
        assertEquals(1, reconcileToolActivities(enabled).size)
    }

    @Test
    fun failedRetrievedResultUsesFailureFallbackText() {
        val call = AgentMessage(role = MessageRole.ASSISTANT, parts = listOf(toolCall("call-1", "deep_lookup")))
        val receipt = AgentMessage(role = MessageRole.TOOL, parts = listOf(ToolResultPart(
            toolCallId = "call-1", toolName = "deep_lookup", result = JsonPrimitive("running"),
            backgroundReceipt = ToolBackgroundReceipt("bg_1", "exec-1", BackgroundExecutionState.RUNNING),
        )))
        val readCall = AgentMessage(role = MessageRole.ASSISTANT, parts = listOf(toolCall("read-1", "read_background_result", """{"handle":"bg_1"}""")))
        val read = AgentMessage(role = MessageRole.TOOL, parts = listOf(ToolResultPart(
            toolCallId = "read-1", toolName = "read_background_result", result = JsonPrimitive("failed"),
            isError = false,
            metadata = buildJsonObject {
                put("magrathea.background.handle", "bg_1")
                put("magrathea.background.result_is_error", true)
                put("magrathea.background.user_error_code", "LOOKUP_FAILED")
                put("magrathea.background.control", true)
            },
        )))
        val background = BackgroundSessionSnapshot(
            executions = listOf(BackgroundExecutionView("bg_1", "exec-1", "deep_lookup", "call-1", "source", BackgroundExecutionState.TERMINAL, BackgroundOutcome.ERROR)),
            pendingActivation = null, requiresExecutionHost = false,
        )
        val messages = listOf(call, receipt, readCall, read)
        val projected = reconcileToolActivities(messages.toChatbotMessageSnapshots())
            .applyBackgroundProjection(buildBackgroundProjection(background, messages, emptyList()))
            .single()
        assertEquals(ChatbotToolActivityStatus.FAILED, projected.status)
        assertTrue(projected.result!!.isError)
        assertEquals("Tool failed.", projected.result!!.text)
    }

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

    private fun activity(callId: String, toolName: String) = ChatbotToolActivitySnapshot(
        key = ChatbotToolActivityKey("msg-1", 0),
        call = ChatbotToolCall(callId, toolName, "{}", partial = false),
        status = ChatbotToolActivityStatus.RUNNING,
    )

    private class CitingTool(
        name: String,
        private val citationTitle: String,
        private val citationUrl: String,
        private val citationSnippet: String,
        private val withImage: Boolean = false,
    ) : ToolExecutor {
        override val definition = ToolDefinition(
            name = name,
            description = "citing",
            schema = buildJsonObject { put("type", "object") },
        )
        override val backgroundPolicy = ToolBackgroundPolicy.ELIGIBLE
        val gate = CompletableDeferred<Unit>()

        override suspend fun execute(request: ToolExecutionRequest): ToolExecutionResult {
            gate.await()
            return ToolExecutionResult(
                toolCallId = request.toolCall.toolCallId,
                toolName = request.toolCall.toolName,
                result = JsonPrimitive("result-${request.toolCall.toolName}"),
                content = buildList {
                    add(
                        ToolResultTextContent(
                            "body-${request.toolCall.toolName}",
                            setOf(ToolResultAudience.USER),
                        ),
                    )
                    if (withImage) {
                        add(
                            ToolResultImageContent(
                                source = RemoteToolImageSource("https://img.example.com/beta.jpg"),
                                mimeType = "image/jpeg",
                                title = "Beta image",
                                audiences = setOf(ToolResultAudience.USER),
                            ),
                        )
                    }
                },
                metadata = buildJsonObject {
                    putJsonArray("citations") {
                        addJsonObject {
                            put("title", citationTitle)
                            put("url", citationUrl)
                            put("snippet", citationSnippet)
                        }
                    }
                },
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

    private class ErrorResultTool(
        name: String,
        private val delayMillis: Long,
    ) : ToolExecutor {
        override val definition = ToolDefinition(
            name = name,
            description = "fails upstream",
            schema = buildJsonObject { put("type", "object") },
        )
        override val backgroundPolicy = ToolBackgroundPolicy.ELIGIBLE

        override suspend fun execute(request: ToolExecutionRequest): ToolExecutionResult {
            kotlinx.coroutines.delay(delayMillis)
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
