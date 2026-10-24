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

    // 1b. A single-handle await that returns the settled payload lands the stored result —
    // citations included — on the original activity.
    @Test
    fun singleHandleAwaitCarriesTheResultOntoTheOriginalActivity() = runTest {
        val store = InMemoryBackgroundExecutionStore()
        val executor = executor(store)
        val tool = GatedTool("deep_lookup")
        val provider = ScriptedProvider("test")
        provider.script += { chunk(toolCalls = listOf(toolCall("call-1", "deep_lookup"))) }
        provider.script += { request ->
            val handle = request.messages
                .flatMap { it.parts }
                .filterIsInstance<ToolResultPart>()
                .firstNotNullOf { it.backgroundReceipt?.handle }
            chunk(
                toolCalls = listOf(
                    toolCall(
                        "await-1",
                        "await_background_results",
                        """{"handles":["$handle"],"max_wait_ms":5000}""",
                    ),
                ),
            )
        }
        provider.script += { chunk(text = "answer") }
        val persistence = InMemoryAgentPersistence()
        val runner = runner(provider, listOf(tool), executor, persistence)
        val client = client(runner, persistence, executor, backgroundConfig())
        val session = client.createSession(testChatbotConfiguration(provider = "test"))

        session.send("look up project atlas")
        advanceTimeBy(50)
        runCurrent()
        tool.gate.complete(Unit)
        advanceUntilIdle()

        val snapshot = session.snapshot()
        assertEquals(ChatbotStatus.COMPLETED, snapshot.status)
        assertFalse(assertNotNull(snapshot.background).hasPendingActivation)
        val activity = snapshot.toolActivities.single { it.call.id == "call-1" }
        assertEquals(ChatbotToolActivityStatus.SUCCEEDED, activity.status)
        val result = assertNotNull(activity.result)
        assertEquals("call-1", result.id)
        assertEquals("deep_lookup", result.name)
        assertEquals(listOf(ChatbotCitation("Atlas", "https://atlas", "note")), result.citations)
        assertTrue(snapshot.toolActivities.none { it.call.id == "await-1" })

        client.close()
        executor.close()
    }

    // A multi-handle await attributes each settled slice to its own origin activity — each
    // search keeps its own citations and images instead of losing them to the await part.
    @Test
    fun multiHandleAwaitAttachesEachResultToItsOriginActivity() = runTest {
        val store = InMemoryBackgroundExecutionStore()
        val executor = executor(store)
        val lookup = CitingTool("deep_lookup", "Atlas", "https://atlas", "note-a")
        val images = CitingTool("slow_lookup", "Beta", "https://beta", "note-b", withImage = true)
        val provider = ScriptedProvider("test")
        provider.script += {
            chunk(
                toolCalls = listOf(
                    toolCall("call-1", "deep_lookup"),
                    toolCall("call-2", "slow_lookup"),
                ),
            )
        }
        provider.script += { request ->
            val handles = request.messages
                .flatMap { it.parts }
                .filterIsInstance<ToolResultPart>()
                .mapNotNull { it.backgroundReceipt?.handle }
            chunk(
                toolCalls = listOf(
                    toolCall(
                        "await-1",
                        "await_background_results",
                        handles.joinToString(
                            ",",
                            prefix = """{"handles":[""",
                            postfix = """],"max_wait_ms":5000}""",
                        ) { "\"$it\"" },
                    ),
                ),
            )
        }
        provider.script += { chunk(text = "answer") }
        val persistence = InMemoryAgentPersistence()
        val runner = runner(provider, listOf(lookup, images), executor, persistence)
        val client = client(runner, persistence, executor, backgroundConfig())
        val session = client.createSession(testChatbotConfiguration(provider = "test"))

        session.send("search text and images")
        advanceTimeBy(50)
        runCurrent()
        lookup.gate.complete(Unit)
        images.gate.complete(Unit)
        advanceUntilIdle()

        val snapshot = session.snapshot()
        assertEquals(ChatbotStatus.COMPLETED, snapshot.status)
        val first = snapshot.toolActivities.single { it.call.id == "call-1" }
        assertEquals(ChatbotToolActivityStatus.SUCCEEDED, first.status)
        val firstResult = assertNotNull(first.result)
        assertEquals("call-1", firstResult.id)
        assertEquals("deep_lookup", firstResult.name)
        assertEquals(
            listOf(ChatbotCitation("Atlas", "https://atlas", "note-a")),
            firstResult.citations,
        )
        assertTrue(firstResult.images.isEmpty())
        val second = snapshot.toolActivities.single { it.call.id == "call-2" }
        assertEquals(ChatbotToolActivityStatus.SUCCEEDED, second.status)
        val secondResult = assertNotNull(second.result)
        assertEquals("call-2", secondResult.id)
        assertEquals(
            listOf(ChatbotCitation("Beta", "https://beta", "note-b")),
            secondResult.citations,
        )
        assertEquals(1, secondResult.images.size)
        assertEquals("Beta image", secondResult.images.single().title)
        assertTrue(snapshot.toolActivities.none { it.call.id == "await-1" })

        client.close()
        executor.close()
    }

    // Retrieval attribution stays safe on hand-built history: a legacy single-handle await
    // without the slices array still attaches, the read path is unchanged, and an
    // out-of-range slice reports empty content instead of failing.
    @Test
    fun retrievalProjectionKeepsLegacyAndReadPaths() {
        val receipt = { callId: String, handle: String ->
            ToolResultPart(
                toolCallId = callId,
                toolName = "web_search",
                result = JsonPrimitive(""),
                backgroundReceipt = ToolBackgroundReceipt(
                    handle,
                    "exec-$handle",
                    BackgroundExecutionState.RUNNING,
                ),
            )
        }
        val text = ToolResultTextContent("body", setOf(ToolResultAudience.USER))
        val origin = ToolOrigin("src", "source", "tool", "tool label")
        val legacyAwait = ToolResultPart(
            toolCallId = "await-legacy",
            toolName = "await_background_results",
            result = buildJsonObject {
                putJsonArray("results") {
                    addJsonObject {
                        put("handle", "bg_legacy")
                        put("status", "succeeded")
                    }
                }
            },
            content = listOf(text),
            metadata = buildJsonObject {
                put("magrathea.background.handle", "bg_legacy")
                putJsonArray("citations") {
                    addJsonObject {
                        put("title", "Legacy")
                        put("url", "https://legacy")
                        put("snippet", "old")
                    }
                }
            },
        )
        val outOfRangeAwait = ToolResultPart(
            toolCallId = "await-slices",
            toolName = "await_background_results",
            result = buildJsonObject {
                putJsonArray("results") {
                    addJsonObject {
                        put("handle", "bg_slice")
                        put("status", "succeeded")
                    }
                }
            },
            content = listOf(text),
            metadata = buildJsonObject {
                putJsonArray("magrathea.background.results") {
                    addJsonObject {
                        put("handle", "bg_slice")
                        put("execution_id", "exec-bg_slice")
                        put("tool_name", "web_search")
                        put("content_offset", 99)
                        put("content_count", 5)
                        put("display_text", "9 results")
                        put(
                            "origin",
                            Json.encodeToJsonElement(ToolOrigin.serializer(), origin),
                        )
                        put("metadata", buildJsonObject {
                            putJsonArray("citations") {
                                addJsonObject {
                                    put("title", "Sliced")
                                    put("url", "https://sliced")
                                    put("snippet", "slice")
                                }
                            }
                        })
                    }
                }
            },
        )
        val read = ToolResultPart(
            toolCallId = "read-1",
            toolName = "read_background_result",
            result = JsonPrimitive("read body"),
            content = listOf(text),
            metadata = buildJsonObject {
                put("magrathea.background.handle", "bg_read")
                putJsonArray("citations") {
                    addJsonObject {
                        put("title", "Read")
                        put("url", "https://read")
                        put("snippet", "read note")
                    }
                }
            },
        )
        // Each receipt pairs with its assistant call under message-id + call-ordinal binding.
        fun callMessage(id: String, callId: String) = AgentMessage(
            id = id,
            role = MessageRole.ASSISTANT,
            parts = listOf(toolCall(callId, "web_search")),
        )
        val messages = listOf(
            callMessage("msg-legacy", "call-legacy"),
            AgentMessage(role = MessageRole.TOOL, parts = listOf(receipt("call-legacy", "bg_legacy"))),
            callMessage("msg-slice", "call-slice"),
            AgentMessage(role = MessageRole.TOOL, parts = listOf(receipt("call-slice", "bg_slice"))),
            callMessage("msg-read", "call-read"),
            AgentMessage(role = MessageRole.TOOL, parts = listOf(receipt("call-read", "bg_read"))),
            AgentMessage(role = MessageRole.TOOL, parts = listOf(legacyAwait)),
            AgentMessage(role = MessageRole.TOOL, parts = listOf(outOfRangeAwait)),
            AgentMessage(role = MessageRole.TOOL, parts = listOf(read)),
        )
        val view = { handle: String, callId: String ->
            BackgroundExecutionView(
                handle = handle,
                executionId = "exec-$handle",
                toolName = "web_search",
                originCallId = callId,
                sourceId = "src-$handle",
                state = BackgroundExecutionState.TERMINAL,
                outcome = BackgroundOutcome.SUCCEEDED,
            )
        }
        val projection = buildBackgroundProjection(
            background = BackgroundSessionSnapshot(
                executions = listOf(
                    view("bg_legacy", "call-legacy"),
                    view("bg_slice", "call-slice"),
                    view("bg_read", "call-read"),
                ),
                pendingActivation = null,
                requiresExecutionHost = false,
            ),
            messages = messages,
            toolExecutions = emptyList(),
        )
        val projected = listOf(
            activity("call-legacy", "web_search")
                .copy(key = ChatbotToolActivityKey("msg-legacy", 0)),
            activity("call-slice", "web_search")
                .copy(key = ChatbotToolActivityKey("msg-slice", 0)),
            activity("call-read", "web_search")
                .copy(key = ChatbotToolActivityKey("msg-read", 0)),
        ).applyBackgroundProjection(projection)

        val legacy = assertNotNull(projected[0].result)
        assertEquals("call-legacy", legacy.id)
        assertEquals(listOf(ChatbotCitation("Legacy", "https://legacy", "old")), legacy.citations)
        val sliced = assertNotNull(projected[1].result)
        assertEquals("call-slice", sliced.id)
        assertEquals(listOf(ChatbotCitation("Sliced", "https://sliced", "slice")), sliced.citations)
        assertEquals("9 results", sliced.text)
        assertNotNull(sliced.origin)
        val readResult = assertNotNull(projected[2].result)
        assertEquals("call-read", readResult.id)
        assertEquals(listOf(ChatbotCitation("Read", "https://read", "read note")), readResult.citations)
    }

    // 1c. The record settles while the final answer streams — too late for a COALESCED
    // notice; hasPendingActivation still becomes true and the continuation starts.
    @Test
    fun settleDuringFinalAnswerFlagsPendingActivationForContinuation() = runTest {
        val store = InMemoryBackgroundExecutionStore()
        val executor = executor(store)
        val tool = GatedTool("deep_lookup")
        val provider = ScriptedProvider("test")
        provider.script += { chunk(toolCalls = listOf(toolCall("call-1", "deep_lookup"))) }
        provider.script += {
            tool.gate.complete(Unit)
            chunk(text = "answer")
        }
        provider.script += { chunk(text = "supplement") }
        val persistence = InMemoryAgentPersistence()
        val runner = runner(provider, listOf(tool), executor, persistence)
        val client = client(runner, persistence, executor, backgroundConfig())
        val session = client.createSession(testChatbotConfiguration(provider = "test"))

        session.send("look up project atlas")
        advanceUntilIdle()

        val snapshot = session.snapshot()
        assertEquals(ChatbotStatus.COMPLETED, snapshot.status)
        assertTrue(assertNotNull(snapshot.background).hasPendingActivation)
        assertTrue(session.continueWithBackgroundResults())
        advanceUntilIdle()

        val continued = session.snapshot()
        assertFalse(assertNotNull(continued.background).hasPendingActivation)
        assertEquals("supplement", continued.messages.last().text)

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


    // 8. Reused Provider call IDs across turns: each receipt binds to its own call, so both
    // activities carry their own background execution and result identity.
    @Test
    fun reusedCallIdsBindReceiptsToTheirOwnCalls() = runTest {
        val store = InMemoryBackgroundExecutionStore()
        val executor = executor(store)
        val tool = GatedTool("deep_lookup")
        val provider = ScriptedProvider("test")
        provider.script += { chunk(toolCalls = listOf(toolCall("call-1", "deep_lookup"))) }
        provider.script += { chunk(toolCalls = listOf(toolCall("call-1", "deep_lookup"))) }
        provider.script += { chunk(text = "done") }
        val persistence = InMemoryAgentPersistence()
        val runner = runner(provider, listOf(tool), executor, persistence)
        val client = client(runner, persistence, executor, backgroundConfig())
        val session = client.createSession(testChatbotConfiguration(provider = "test"))

        session.send("two lookups, same call id")
        advanceTimeBy(50)
        runCurrent()

        val snapshot = session.snapshot()
        val activities = snapshot.toolActivities.filter { it.call.id == "call-1" }
        assertEquals(2, activities.size)
        val records = store.load(
            saien.magrathea.core.AgentSessionId(checkNotNull(snapshot.sessionId)),
        ).records
        assertEquals(2, records.size)
        assertNotEquals(records[0].handle, records[1].handle)
        assertEquals(records[0].handle, activities[0].background?.handle)
        assertEquals(records[1].handle, activities[1].background?.handle)
        client.close()
        executor.close()
    }

    // 9. A failed background execution restores its error semantics on the origin activity:
    // FAILED with the Tool's own isError and errorCode from the read's metadata.
    @Test
    fun failedBackgroundResultRestoresErrorSemantics() = runTest {
        val store = InMemoryBackgroundExecutionStore()
        val executor = executor(store)
        val tool = ErrorResultTool("deep_lookup", 100)
        val provider = ScriptedProvider("test")
        provider.script += { chunk(toolCalls = listOf(toolCall("call-1", "deep_lookup"))) }
        provider.script += { chunk(text = "first answer") }
        // The continuation's model turn reads the settled handle.
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
        advanceTimeBy(200)
        runCurrent()
        assertTrue(session.continueWithBackgroundResults())
        advanceUntilIdle()

        val snapshot = session.snapshot()
        val activity = snapshot.toolActivities.single { it.call.id == "call-1" }
        assertEquals(ChatbotToolActivityStatus.FAILED, activity.status)
        val result = assertNotNull(activity.result)
        assertTrue(result.isError)
        assertEquals("LOOKUP_FAILED", result.errorCode)
        assertEquals("call-1", result.id)
        assertEquals("deep_lookup", result.name)
        client.close()
        executor.close()
    }

    // 10. With background off, a business Tool that happens to share a control name is an
    // ordinary Tool: its call, result, and activity stay visible.
    @Test
    fun featureOffBusinessToolNamedLikeAControlStaysVisible() = runTest {
        val provider = ScriptedProvider("test")
        provider.script += {
            chunk(toolCalls = listOf(toolCall("call-1", "finish_without_reply")))
        }
        provider.script += { chunk(text = "done") }
        val tool = object : ToolExecutor {
            override val definition = ToolDefinition(
                name = "finish_without_reply",
                description = "business tool",
                schema = buildJsonObject { put("type", "object") },
            )

            override suspend fun execute(
                request: ToolExecutionRequest,
            ): ToolExecutionResult = ToolExecutionResult(
                toolCallId = request.toolCall.toolCallId,
                toolName = request.toolCall.toolName,
                result = JsonPrimitive("business result"),
                displayText = "Business result",
            )
        }
        val persistence = InMemoryAgentPersistence()
        val runner = DefaultAgentRunner(
            providerRegistry = InMemoryProviderRegistry(listOf(provider)),
            toolRegistry = InMemoryToolRegistry(listOf(tool)),
            persistence = persistence,
            dispatcher = StandardTestDispatcher(testScheduler),
            timeSource = VirtualTimeSource(testScheduler),
        )
        val client = createChatbotClient(
            runner = runner,
            requestFactory = DefaultChatbotRequestFactory(
                tools = listOf(tool.definition),
            ),
            persistence = persistence,
            closeResources = {},
            sessionDispatcher = StandardTestDispatcher(testScheduler),
        )
        val session = client.createSession(testChatbotConfiguration(provider = "test"))

        session.send("hi")
        advanceUntilIdle()

        val snapshot = session.snapshot()
        assertEquals(ChatbotStatus.COMPLETED, snapshot.status)
        val activity = snapshot.toolActivities.single { it.call.id == "call-1" }
        assertEquals("finish_without_reply", activity.call.name)
        assertEquals(ChatbotToolActivityStatus.SUCCEEDED, activity.status)
        assertEquals("Business result", activity.result?.text)
        assertTrue(
            snapshot.messages.any { message ->
                message.toolCalls.any { it.id == "call-1" } &&
                    message.toolCalls.any { it.name == "finish_without_reply" }
            },
        )
        assertTrue(
            snapshot.messages.any { message ->
                message.toolResults.any { it.id == "call-1" }
            },
        )
        client.close()
    }

    // 11. A legacy copy overload keeps the background projection (F5).
    @Test
    fun legacyCopyOverloadPreservesTheBackgroundProjection() {
        val background = ChatbotBackgroundSnapshot(
            executions = listOf(
                ChatbotBackgroundExecution(
                    handle = "bg_1",
                    toolName = "deep_lookup",
                    originCallId = "call-1",
                    state = ChatbotBackgroundState.RUNNING,
                ),
            ),
            hasPendingActivation = true,
            requiresExecutionHost = true,
        )
        val snapshot = ChatbotSnapshot(
            configuration = testChatbotConfiguration(),
            background = background,
        )
        // Resolves to the shortest legacy copy signature (no stopReason/replay parameters).
        val copied = snapshot.copy(status = ChatbotStatus.RUNNING)
        assertEquals(background, copied.background)
        assertEquals(ChatbotStatus.RUNNING, copied.status)
        val stopCopied = snapshot.copy(stopReason = ChatbotStopReason.COMPLETED)
        assertEquals(background, stopCopied.background)
    }


    // 12. A marked control result hides only its own call: a later business call that reuses
    // the same Provider call ID stays visible with its result (the F9 marker pairs calls and
    // results by call identity, not by bare ID).
    @Test
    fun markedControlResultHidesOnlyItsOwnCall() = runTest {
        val store = InMemoryBackgroundExecutionStore()
        val executor = executor(store)
        val tool = GatedTool("deep_lookup")
        val provider = ScriptedProvider("test")
        provider.script += {
            chunk(
                toolCalls = listOf(
                    toolCall("call_0", "read_background_result", """{"handle":"bg_missing"}"""),
                ),
            )
        }
        provider.script += { chunk(toolCalls = listOf(toolCall("call_0", "deep_lookup"))) }
        provider.script += { chunk(text = "done") }
        val persistence = InMemoryAgentPersistence()
        val runner = runner(provider, listOf(tool), executor, persistence)
        val client = client(runner, persistence, executor, backgroundConfig())
        val session = client.createSession(testChatbotConfiguration(provider = "test"))

        tool.gate.complete(Unit)
        session.send("hi")
        advanceUntilIdle()

        val snapshot = session.snapshot()
        assertEquals(ChatbotStatus.COMPLETED, snapshot.status)
        // The business call reusing call_0 stays fully visible.
        val activity = snapshot.toolActivities.single { it.call.id == "call_0" }
        assertEquals("deep_lookup", activity.call.name)
        assertEquals(ChatbotToolActivityStatus.SUCCEEDED, activity.status)
        assertNotNull(activity.result)
        assertTrue(
            snapshot.messages.any { message ->
                message.toolCalls.any { it.id == "call_0" && it.name == "deep_lookup" }
            },
        )
        assertTrue(
            snapshot.messages.any { message ->
                message.toolResults.any { it.id == "call_0" && it.name == "deep_lookup" }
            },
        )
        // The turn-1 control call and its marked result stay hidden.
        assertTrue(
            snapshot.messages.none { message ->
                message.toolCalls.any { it.name == "read_background_result" } ||
                    message.toolResults.any { it.name == "read_background_result" }
            },
        )
        assertTrue(
            snapshot.toolActivities.none { it.call.name == "read_background_result" },
        )
        client.close()
        executor.close()
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
