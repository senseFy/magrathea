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

class BackgroundToolRuntimeContractTest {

    // 1: handoff produces a receipt in the TOOL message, ToolHandedOff without ToolCompleted,
    // a RUNNING record plus the Run's origin context, and the handoff wait on the counter.
    @Test
    fun handoffWritesAReceiptRecordAndOriginContext() = runTest {
        val store = InMemoryBackgroundExecutionStore()
        val executor = executor(store)
        val sessionId = AgentSessionId("handoff-session")
        executor.register(sessionId, NoopListener)
        val tool = GatedTool("slow_lookup", ToolBackgroundPolicy.ELIGIBLE)
        val provider = scriptedProvider("handoff", toolCall("call-1", "slow_lookup"))
        val runner = runner(provider = provider, tools = listOf(tool), background = executor)
        val request = request("handoff", sessionId, config = backgroundConfig(handoffWait = 1_000))

        val collected = async { runner.run(request).toList() }
        runCurrent()
        advanceTimeBy(1_100)
        runCurrent()
        val events = collected.await()

        val handedOff = events.filterIsInstance<AgentEvent.ToolHandedOff>().single()
        assertEquals("call-1", handedOff.toolCallId)
        assertEquals("slow_lookup", handedOff.toolName)
        assertTrue(handedOff.receipt.handle.startsWith("bg_"))
        assertTrue(
            events.filterIsInstance<AgentEvent.ToolCompleted>().none {
                it.result.toolCallId == "call-1"
            },
        )
        val state = events.filterIsInstance<AgentEvent.Completed>().single().state
        val toolMessage = state.messages.single { it.role == MessageRole.TOOL }
        val part = toolMessage.parts.filterIsInstance<ToolResultPart>().single()
        assertFalse(part.isError)
        assertEquals("Still running in the background", part.displayText)
        assertEquals(handedOff.receipt, part.backgroundReceipt)
        assertEquals(ToolResultRetention.PINNED, part.contextHints?.retention)
        assertEquals(
            buildJsonObject {
                put("status", "running")
                put("handle", handedOff.receipt.handle)
                put("retrieve", "read_background_result")
                put(
                    "note",
                    "This work is still running in the background. You do not have its " +
                        "result yet. Answer now with what you already have, or call " +
                        "await_background_results if you need the result first. Do not " +
                        "cancel it or call the Tool again for it; a runtime notice will " +
                        "report the result if you answer first.",
                )
            },
            part.result,
        )
        val records = store.load(sessionId)
        val record = records.records.single()
        assertEquals(BackgroundExecutionState.RUNNING, record.state)
        assertEquals(handedOff.receipt.handle, record.handle)
        assertEquals("call-1", record.originCallId)
        assertEquals("slow_lookup", record.toolName)
        assertEquals(request.messages.last { it.role == MessageRole.USER }.id, record.sourceId)
        val origin = records.origins.single()
        assertEquals(request.sessionId, origin.sessionId)
        assertTrue(origin.request.messages.isEmpty())
        assertEquals(1_000L, state.foregroundToolWaitMillis)
        executor.close()
    }

    // 2: an eligible call that settles inside the handoff allowance returns its real result.
    @Test
    fun eligibleSettlingInsideTheBudgetReturnsItsResult() = runTest {
        val store = InMemoryBackgroundExecutionStore()
        val executor = executor(store)
        val sessionId = AgentSessionId("inside-budget")
        executor.register(sessionId, NoopListener)
        val tool = DelayedTool("fast_lookup", 400, ToolBackgroundPolicy.ELIGIBLE)
        val runner = runner(provider = scriptedProvider("budget", toolCall("call-1", "fast_lookup")), tools = listOf(tool), background = executor)
        val request = request("budget", sessionId, config = backgroundConfig(handoffWait = 1_000))

        val events = runner.run(request).toList()

        assertTrue(events.none { it is AgentEvent.ToolHandedOff })
        val completed = events.filterIsInstance<AgentEvent.ToolCompleted>().single()
        assertEquals(JsonPrimitive("result-fast_lookup"), completed.result.result)
        assertNull(completed.result.backgroundReceipt)
        assertTrue(store.load(sessionId).records.isEmpty())
        val toolMessage = events.filterIsInstance<AgentEvent.Completed>().single().state.messages
            .single { it.role == MessageRole.TOOL }
        assertEquals(
            JsonPrimitive("result-fast_lookup"),
            toolMessage.parts.filterIsInstance<ToolResultPart>().single().result,
        )
        executor.close()
    }

    // 3: a mixed batch waits for the blocking call, then hands off the eligible one; the
    // counter records the whole batch duration. A never-started eligible call stays QUEUED.
    @Test
    fun mixedBatchWaitsForBlockingThenHandsOffEligible() = runTest {
        val store = InMemoryBackgroundExecutionStore()
        val executor = executor(store)
        val sessionId = AgentSessionId("mixed-batch")
        executor.register(sessionId, NoopListener)
        val blocking = DelayedTool("blocking_lookup", 3_000, ToolBackgroundPolicy.BLOCKING)
        val eligible = PermitGatedTool("patient_lookup", ToolBackgroundPolicy.ELIGIBLE)
        val runner = runner(
            provider = scriptedProvider(
                "mixed",
                toolCall("call-block", "blocking_lookup"),
                toolCall("call-patient", "patient_lookup"),
            ),
            tools = listOf(blocking, eligible),
            background = executor,
        )
        val request = request("mixed", sessionId, config = backgroundConfig(handoffWait = 1_000))

        val collected = async { runner.run(request).toList() }
        runCurrent()
        advanceTimeBy(3_100)
        runCurrent()
        val events = collected.await()

        val state = events.filterIsInstance<AgentEvent.Completed>().single().state
        assertEquals(3_000L, state.foregroundToolWaitMillis)
        val handedOff = events.filterIsInstance<AgentEvent.ToolHandedOff>().single()
        assertEquals("call-patient", handedOff.toolCallId)
        assertEquals(BackgroundExecutionState.QUEUED, handedOff.receipt.state)
        val record = store.load(sessionId).records.single()
        assertEquals(BackgroundExecutionState.QUEUED, record.state)
        val part = state.messages.single { it.role == MessageRole.TOOL }
            .parts.filterIsInstance<ToolResultPart>()
            .single { it.toolCallId == "call-patient" }
        assertEquals("queued", part.result.jsonObject.getValue("status").jsonPrimitive.content)
        assertTrue(
            events.filterIsInstance<AgentEvent.ToolCompleted>().any {
                it.result.toolCallId == "call-block"
            },
        )
        executor.close()
    }

    // 4: late settlement after the Run completed applies the same finalization (an
    // afterToolCall interceptor sees the empty-message origin request), emits
    // BackgroundExecutionSettled, and derives the session projection.
    @Test
    fun lateSettlementFinalizesAndProjects() = runTest {
        val store = InMemoryBackgroundExecutionStore()
        val executor = executor(store)
        val observed = mutableListOf<List<AgentMessage>?>()
        val interceptor = object : AgentInterceptor {
            override suspend fun afterToolCall(
                context: saien.magrathea.core.ToolRuntimeContext,
                result: ToolExecutionResult,
            ): ToolExecutionResult {
                observed += context.request.messages
                return result
            }
        }
        val sessionId = AgentSessionId("late-settlement")
        val tool = GatedTool("deep_lookup", ToolBackgroundPolicy.ELIGIBLE)
        val provider = scriptedProvider("late", toolCall("call-1", "deep_lookup"))
        val runner = runner(
            provider = provider, tools = listOf(tool),
            background = executor, interceptors = listOf(interceptor),
        )
        val manager = DefaultAgentSessionManager(
            runner = runner,
            persistence = InMemoryAgentPersistence(),
            dispatcher = StandardTestDispatcher(testScheduler),
            background = executor,
        )
        val lease = manager.create(sessionId)
        val events = mutableListOf<AgentEvent>()
        backgroundScope.launch { lease.events.toList(events) }
        lease.start(request("late", sessionId, config = backgroundConfig(handoffWait = 500)))
        advanceTimeBy(600)
        runCurrent()
        lease.awaitIdle()
        runCurrent()

        assertTrue(lease.state.value.background?.requiresExecutionHost == true)
        tool.gate.complete(Unit)
        runCurrent()

        val record = store.load(sessionId).records.single()
        assertEquals(BackgroundExecutionState.TERMINAL, record.state)
        assertEquals(BackgroundOutcome.SUCCEEDED, record.outcome)
        assertEquals(JsonPrimitive("result-deep_lookup"), record.result?.result)
        assertNotNull(record.settledAtEpochMs)
        // afterToolCall ran once, at settlement, with the origin request's empty history.
        assertEquals(1, observed.size)
        assertTrue(observed.single()?.isEmpty() == true)
        assertTrue(
            events.filterIsInstance<AgentEvent.BackgroundExecutionSettled>().any {
                it.handle == record.handle && it.outcome == BackgroundOutcome.SUCCEEDED
            },
        )
        val background = lease.state.value.background
        assertNotNull(background)
        assertFalse(background.requiresExecutionHost)
        assertNotNull(background.pendingActivation)
        assertEquals(listOf(record.sourceId), background.pendingActivation?.sourceIds)
        manager.close()
    }

    @Test
    fun passivePolicyShowsNoPendingActivation() = runTest {
        val store = InMemoryBackgroundExecutionStore()
        val executor = executor(store)
        val sessionId = AgentSessionId("passive-session")
        val tool = GatedTool("deep_lookup", ToolBackgroundPolicy.ELIGIBLE)
        val provider = scriptedProvider("passive", toolCall("call-1", "deep_lookup"))
        val runner = runner(provider = provider, tools = listOf(tool), background = executor)
        val manager = DefaultAgentSessionManager(
            runner = runner,
            persistence = InMemoryAgentPersistence(),
            dispatcher = StandardTestDispatcher(testScheduler),
            background = executor,
        )
        val lease = manager.create(sessionId)
        lease.start(
            request(
                "passive", sessionId,
                config = backgroundConfig(
                    handoffWait = 500,
                    policy = BackgroundConsumptionPolicy.PASSIVE,
                ),
            ),
        )
        advanceTimeBy(600)
        runCurrent()
        lease.awaitIdle()
        tool.gate.complete(Unit)
        runCurrent()

        assertEquals(BackgroundOutcome.SUCCEEDED, store.load(sessionId).records.single().outcome)
        val background = lease.state.value.background
        assertNotNull(background)
        assertNull(background.pendingActivation)
        manager.close()
    }

    // 5: a result settling during a user Run's Tool loop is delivered at the next turn as a
    // COALESCED Runtime notice and is never repeated.
    @Test
    fun coalescedDeliveryLandsOnceAtTurnPreparing() = runTest {
        val store = InMemoryBackgroundExecutionStore()
        val executor = executor(store)
        val sessionId = AgentSessionId("coalesced")
        val first = GatedTool("deep_lookup", ToolBackgroundPolicy.ELIGIBLE)
        val blocker = DelayedTool("blocker", 500, ToolBackgroundPolicy.BLOCKING)
        val provider = object : ProviderAdapter {
            override val key = "coalesced-provider"
            val requests = mutableListOf<ProviderRequest>()
            override suspend fun generate(request: ProviderRequest): Flow<ProviderChunk> = flow {
                requests += request
                val isSecondRun = request.messages.any { message ->
                    message.role == MessageRole.USER &&
                        message.parts.filterIsInstance<TextPart>().any { it.text == "second question" }
                }
                val last = request.messages.lastOrNull()?.role
                when {
                    last == MessageRole.TOOL || last == MessageRole.RUNTIME ->
                        emit(providerChunk(text = "done", completed = true))
                    isSecondRun -> emit(
                        providerChunk(
                            toolCalls = listOf(toolCall("block-1", "blocker")),
                            completed = true,
                        ),
                    )
                    else -> emit(
                        providerChunk(
                            toolCalls = listOf(toolCall("call-1", "deep_lookup")),
                            completed = true,
                        ),
                    )
                }
            }
        }
        val runner = runner(provider = provider, tools = listOf(first, blocker), background = executor)
        val manager = DefaultAgentSessionManager(
            runner = runner,
            persistence = InMemoryAgentPersistence(),
            dispatcher = StandardTestDispatcher(testScheduler),
            background = executor,
        )
        val lease = manager.create(sessionId)
        lease.start(
            request("coalesced-provider", sessionId, config = backgroundConfig(handoffWait = 300)),
        )
        advanceTimeBy(400)
        runCurrent()
        lease.awaitIdle()
        runCurrent()

        backgroundScope.launch {
            delay(100)
            first.gate.complete(Unit)
        }
        lease.start(
            request(
                "coalesced-provider",
                sessionId,
                config = backgroundConfig(handoffWait = 300),
                messages = checkNotNull(lease.state.value.state).messages +
                    userMessage("second question"),
            ),
        )
        advanceTimeBy(700)
        runCurrent()
        lease.awaitIdle()
        runCurrent()

        assertEquals(4, provider.requests.size)
        val noticed = provider.requests[3].messages.single { it.role == MessageRole.RUNTIME }
        val noticePart = noticed.parts.filterIsInstance<RuntimeNoticePart>().single()
        assertEquals(RuntimeNoticeDelivery.COALESCED, noticePart.delivery)
        assertTrue(noticePart.text.startsWith("<runtime_notice>"))
        assertTrue("read_background_result" in noticePart.text)
        assertTrue(noticePart.entries.isNotEmpty())
        manager.close()
    }

    // 6: read returns stored payloads for settled handles and statuses for live ones.
    @Test
    fun readControlReturnsStoredOutcomes() = runTest {
        val store = InMemoryBackgroundExecutionStore()
        val executor = executor(store)
        val sessionId = AgentSessionId("read-session")
        executor.register(sessionId, NoopListener)
        val otherSession = AgentSessionId("other-session")
        val settled = seededRecord(sessionId, "bg_settled", outcome = BackgroundOutcome.SUCCEEDED)
        val running = seededRecord(sessionId, "bg_running", state = BackgroundExecutionState.RUNNING)
        val foreign = seededRecord(otherSession, "bg_other", outcome = BackgroundOutcome.SUCCEEDED)
        store.upsert(settled)
        store.upsert(running)
        store.upsert(foreign)
        val provider = scriptedProvider(
            "read",
            toolCall("r1", "read_background_result", """{"handle":"bg_settled"}"""),
            toolCall("r2", "read_background_result", """{"handle":"bg_running"}"""),
            toolCall("r3", "read_background_result", """{"handle":"bg_missing"}"""),
            toolCall("r4", "read_background_result", """{"handle":"bg_other"}"""),
        )
        val runner = runner(provider = provider, tools = emptyList(), background = executor)
        val request = request("read", sessionId, config = backgroundConfig())

        val events = runner.run(request).toList()

        val parts = events.filterIsInstance<AgentEvent.Completed>().single().state.messages
            .single { it.role == MessageRole.TOOL }.parts.filterIsInstance<ToolResultPart>()
        val succeeded = parts.single { it.toolCallId == "r1" }
        assertFalse(succeeded.isError)
        val succeededJson = succeeded.result.jsonObject
        assertEquals("bg_settled", succeededJson.getValue("handle").jsonPrimitive.content)
        assertEquals("deep_lookup", succeededJson.getValue("tool").jsonPrimitive.content)
        assertEquals("succeeded", succeededJson.getValue("status").jsonPrimitive.content)
        assertEquals(JsonPrimitive("found-it"), succeededJson.getValue("result").jsonPrimitive)
        assertEquals(settled.result?.content, succeeded.content)
        assertEquals(settled.result?.origin, succeeded.origin)
        assertEquals(
            "bg_settled",
            succeeded.metadata.getValue("magrathea.background.handle").jsonPrimitive.content,
        )
        assertEquals(
            settled.executionId,
            succeeded.metadata.getValue("magrathea.background.execution_id").jsonPrimitive.content,
        )
        val runningPart = parts.single { it.toolCallId == "r2" }
        assertFalse(runningPart.isError)
        assertEquals(
            "running",
            runningPart.result.jsonObject.getValue("status").jsonPrimitive.content,
        )
        val missing = parts.single { it.toolCallId == "r3" }
        assertTrue(missing.isError)
        assertEquals(
            "Unknown background handle.",
            missing.result.jsonObject.getValue("error").jsonPrimitive.content,
        )
        // A handle owned by another Session is unknown, never a cross-Session read.
        val foreignPart = parts.single { it.toolCallId == "r4" }
        assertTrue(foreignPart.isError)
        assertEquals(
            "Unknown background handle.",
            foreignPart.result.jsonObject.getValue("error").jsonPrimitive.content,
        )
        executor.close()
    }

    // 7: a host-triggered continuation prepends the notice, anchors to it, rejects business
    // calls without executing them, and finish_without_reply ends the Run after two model calls.
    @Test
    fun continuationRunNoticesRestrictsAndFinishes() = runTest {
        val store = InMemoryBackgroundExecutionStore()
        val executor = executor(store)
        val sessionId = AgentSessionId("continuation")
        val first = GatedTool("deep_lookup", ToolBackgroundPolicy.ELIGIBLE)
        val business = CountingTool("business_tool")
        val anchors = mutableListOf<String?>()
        val provider = object : ProviderAdapter {
            override val key = "continuation-provider"
            val requests = mutableListOf<ProviderRequest>()
            override suspend fun generate(request: ProviderRequest): Flow<ProviderChunk> = flow {
                requests += request
                val isContinuation = request.messages.any { it.role == MessageRole.RUNTIME }
                when {
                    isContinuation && request.messages.lastOrNull()?.role == MessageRole.TOOL ->
                        emit(
                            providerChunk(
                                toolCalls = listOf(toolCall("fin-1", "finish_without_reply")),
                                completed = true,
                            ),
                        )
                    isContinuation -> emit(
                        providerChunk(
                            toolCalls = listOf(toolCall("biz-1", "business_tool")),
                            completed = true,
                        ),
                    )
                    request.messages.lastOrNull()?.role == MessageRole.TOOL ->
                        emit(providerChunk(text = "ack", completed = true))
                    else -> emit(
                        providerChunk(
                            toolCalls = listOf(toolCall("call-1", "deep_lookup")),
                            completed = true,
                        ),
                    )
                }
            }
        }
        val runner = runner(
            provider = provider,
            tools = listOf(first, business),
            background = executor,
            contextManager = RecordingContextManager(anchors),
        )
        val manager = DefaultAgentSessionManager(
            runner = runner,
            persistence = InMemoryAgentPersistence(),
            dispatcher = StandardTestDispatcher(testScheduler),
            background = executor,
        )
        val lease = manager.create(sessionId)
        val request = request(
            "continuation-provider",
            sessionId,
            config = backgroundConfig(handoffWait = 300),
            tools = listOf(first.definition, business.definition),
        )
        lease.start(request)
        advanceTimeBy(400)
        runCurrent()
        lease.awaitIdle()
        first.gate.complete(Unit)
        runCurrent()
        assertNotNull(lease.state.value.background?.pendingActivation)

        val events = mutableListOf<AgentEvent>()
        backgroundScope.launch { lease.events.toList(events) }
        lease.startContinuation(
            request.copy(messages = checkNotNull(lease.state.value.state).messages),
        )
        advanceTimeBy(300)
        runCurrent()
        lease.awaitIdle()
        runCurrent()

        // The continuation made exactly two Provider invocations; the notice leads its history.
        assertEquals(4, provider.requests.size)
        val notice = provider.requests[2].messages.last { it.role == MessageRole.RUNTIME }
        val noticePart = notice.parts.filterIsInstance<RuntimeNoticePart>().single()
        assertEquals(RuntimeNoticeDelivery.CONTINUATION, noticePart.delivery)
        assertTrue("only these Tools are available" in noticePart.text)
        assertEquals(notice.id, anchors.last())
        // The business call was rejected without executing; finish ended the Run.
        assertEquals(0, business.executions)
        val lastState = checkNotNull(lease.state.value.state)
        assertEquals(StopReason.COMPLETED, lastState.stopReason)
        val rejected = lastState.messages
            .filter { it.role == MessageRole.TOOL }
            .flatMap { it.parts.filterIsInstance<ToolResultPart>() }
            .single { it.toolCallId == "biz-1" }
        assertTrue(rejected.isError)
        assertEquals("Not available in this follow-up", rejected.displayText)
        val finished = lastState.messages
            .filter { it.role == MessageRole.TOOL }
            .flatMap { it.parts.filterIsInstance<ToolResultPart>() }
            .single { it.toolCallId == "fin-1" }
        assertFalse(finished.isError)
        assertTrue(finished.result.jsonObject.getValue("finished").jsonPrimitive.booleanOrNull == true)
        assertNull(lease.state.value.background?.pendingActivation)
        manager.close()
    }

    // 8: the per-source activation allowance blocks a second continuation for the same source.
    @Test
    fun activationAllowanceBlocksASecondContinuation() = runTest {
        val store = InMemoryBackgroundExecutionStore()
        val executor = executor(store)
        val sessionId = AgentSessionId("cap")
        val first = GatedTool("deep_lookup", ToolBackgroundPolicy.ELIGIBLE)
        val second = GatedTool("other_lookup", ToolBackgroundPolicy.ELIGIBLE)
        val provider = object : ProviderAdapter {
            override val key = "cap-provider"
            override suspend fun generate(request: ProviderRequest): Flow<ProviderChunk> = flow {
                when {
                    request.messages.any { it.role == MessageRole.RUNTIME } ->
                        emit(providerChunk(text = "noted", completed = true))
                    request.messages.lastOrNull()?.role == MessageRole.TOOL ->
                        emit(providerChunk(text = "ack", completed = true))
                    else -> emit(
                        providerChunk(
                            toolCalls = listOf(
                                toolCall("call-1", "deep_lookup"),
                                toolCall("call-2", "other_lookup"),
                            ),
                            completed = true,
                        ),
                    )
                }
            }
        }
        val runner = runner(provider = provider, tools = listOf(first, second), background = executor)
        val manager = DefaultAgentSessionManager(
            runner = runner,
            persistence = InMemoryAgentPersistence(),
            dispatcher = StandardTestDispatcher(testScheduler),
            background = executor,
        )
        val lease = manager.create(sessionId)
        val request = request(
            "cap-provider",
            sessionId,
            config = backgroundConfig(handoffWait = 300, maxActivations = 1),
            tools = listOf(first.definition, second.definition),
        )
        lease.start(request)
        advanceTimeBy(400)
        runCurrent()
        lease.awaitIdle()
        first.gate.complete(Unit)
        runCurrent()
        assertNotNull(lease.state.value.background?.pendingActivation)

        lease.startContinuation(
            request.copy(messages = checkNotNull(lease.state.value.state).messages),
        )
        runCurrent()
        lease.awaitIdle()
        runCurrent()
        second.gate.complete(Unit)
        runCurrent()

        // The CONTINUATION notice for this source consumed the single automatic activation.
        assertNull(lease.state.value.background?.pendingActivation)
        assertEquals(
            BackgroundOutcome.SUCCEEDED,
            store.load(sessionId).records.single { it.toolName == "other_lookup" }.outcome,
        )
        manager.close()
    }

    // 9: every startContinuation rejection surface returns INVALID_STATE.
    @Test
    fun startContinuationRejectsInadmissibleRequests() = runTest {
        val store = InMemoryBackgroundExecutionStore()
        val executor = executor(store)
        val sessionId = AgentSessionId("rejections")
        val tool = GatedTool("deep_lookup", ToolBackgroundPolicy.ELIGIBLE)
        val provider = scriptedProvider("reject", toolCall("call-1", "deep_lookup"))
        val runner = runner(provider = provider, tools = listOf(tool), background = executor)
        val manager = DefaultAgentSessionManager(
            runner = runner,
            persistence = InMemoryAgentPersistence(),
            dispatcher = StandardTestDispatcher(testScheduler),
            background = executor,
        )
        val noBackgroundManager = DefaultAgentSessionManager(
            runner = runner(provider = provider, tools = listOf(tool)),
            persistence = InMemoryAgentPersistence(),
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        val lease = manager.create(sessionId)
        val request = request("reject", sessionId, config = backgroundConfig())

        assertEquals(
            AgentSessionErrorCode.INVALID_STATE,
            rejectionCode { lease.startContinuation(request) },
        )
        assertEquals(
            AgentSessionErrorCode.INVALID_STATE,
            rejectionCode {
                lease.startContinuation(request.copy(sessionId = AgentSessionId("other")))
            },
        )
        assertEquals(
            AgentSessionErrorCode.INVALID_STATE,
            rejectionCode {
                lease.startContinuation(
                    request.copy(activation = RunActivation(RunActivationSource.USER_INPUT)),
                )
            },
        )
        assertEquals(
            AgentSessionErrorCode.INVALID_STATE,
            rejectionCode {
                lease.startContinuation(
                    request.copy(engine = AgentEngineConfig(runtime = RuntimeConfig())),
                )
            },
        )
        assertEquals(
            AgentSessionErrorCode.INVALID_STATE,
            rejectionCode {
                lease.startContinuation(request.copy(messages = request.messages + userMessage("x")))
            },
        )
        assertEquals(
            AgentSessionErrorCode.INVALID_STATE,
            rejectionCode {
                noBackgroundManager.create(AgentSessionId("plain"))
                    .startContinuation(request.copy(sessionId = AgentSessionId("plain")))
            },
        )

        // While the session executes, a continuation is also inadmissible.
        lease.start(request)
        assertEquals(
            AgentSessionErrorCode.INVALID_STATE,
            rejectionCode { lease.startContinuation(request) },
        )
        advanceTimeBy(400)
        runCurrent()
        lease.awaitIdle()
        manager.close()
        noBackgroundManager.close()
    }

    // 10: await draws on the explicit-wait allowance; a drained allowance returns immediately.
    @Test
    fun awaitControlsTheExplicitWaitAllowance() = runTest {
        val store = InMemoryBackgroundExecutionStore()
        val executor = executor(store)
        val sessionId = AgentSessionId("await-session")
        executor.register(sessionId, NoopListener)
        store.upsert(
            seededRecord(sessionId, "bg_pending", state = BackgroundExecutionState.RUNNING),
        )
        val provider = scriptedProvider(
            "await",
            toolCall("w1", "await_background_results", """{"handles":["bg_pending"]}"""),
            toolCall("w2", "await_background_results", """{"handles":["bg_pending"]}"""),
        )
        val runner = runner(provider = provider, tools = emptyList(), background = executor)
        val request = request(
            "await",
            sessionId,
            config = backgroundConfig(explicitAllowance = 2_000),
            toolExecutionMode = ToolExecutionMode.SEQUENTIAL,
        )

        val events = runner.run(request).toList()

        val parts = events.filterIsInstance<AgentEvent.Completed>().single().state.messages
            .single { it.role == MessageRole.TOOL }.parts.filterIsInstance<ToolResultPart>()
        val firstAwait = parts.single { it.toolCallId == "w1" }
        assertEquals(
            2_000L,
            firstAwait.result.jsonObject.getValue("waited_ms").jsonPrimitive.long,
        )
        assertEquals(
            "running",
            firstAwait.result.jsonObject["results"]!!.jsonArray.single()
                .jsonObject.getValue("status").jsonPrimitive.content,
        )
        val secondAwait = parts.single { it.toolCallId == "w2" }
        assertEquals(
            0L,
            secondAwait.result.jsonObject.getValue("waited_ms").jsonPrimitive.long,
        )
        assertEquals(
            2_000L,
            events.filterIsInstance<AgentEvent.Completed>().single().state.explicitWaitMillis,
        )
        executor.close()
    }

    @Test
    fun awaitReturnsEarlyWhenTheHandleSettles() = runTest {
        val store = InMemoryBackgroundExecutionStore()
        val executor = executor(store)
        val sessionId = AgentSessionId("await-early")
        executor.register(sessionId, NoopListener)
        val pending = seededRecord(
            sessionId,
            "bg_soon",
            state = BackgroundExecutionState.RUNNING,
        )
        store.upsert(pending)
        backgroundScope.launch {
            delay(100)
            executor.putRecord(
                pending.copy(
                    state = BackgroundExecutionState.TERMINAL,
                    outcome = BackgroundOutcome.SUCCEEDED,
                    result = seededResult(),
                    settledAtEpochMs = 100,
                ),
            )
        }
        val provider = scriptedProvider(
            "early",
            toolCall("w1", "await_background_results", """{"handles":["bg_soon"]}"""),
        )
        val runner = runner(provider = provider, tools = emptyList(), background = executor)
        val request = request(
            "early",
            sessionId,
            config = backgroundConfig(explicitAllowance = 60_000),
        )

        val events = runner.run(request).toList()

        val part = events.filterIsInstance<AgentEvent.Completed>().single().state.messages
            .single { it.role == MessageRole.TOOL }.parts.filterIsInstance<ToolResultPart>().single()
        assertTrue(part.result.jsonObject.getValue("waited_ms").jsonPrimitive.long < 60_000)
        assertEquals(
            "succeeded",
            part.result.jsonObject["results"]!!.jsonArray.single()
                .jsonObject.getValue("status").jsonPrimitive.content,
        )
        executor.close()
    }

    // Two awaits in one assistant message share one wait window: the Run is charged the
    // union (3 s) rather than the sum (6 s), leaving the rest of the allowance for the
    // next turn's await.
    @Test
    fun parallelAwaitsChargeTheSharedWindowOnce() = runTest {
        val store = InMemoryBackgroundExecutionStore()
        val executor = executor(store)
        val sessionId = AgentSessionId("await-parallel")
        executor.register(sessionId, NoopListener)
        val first = seededRecord(
            sessionId,
            "bg_first",
            state = BackgroundExecutionState.RUNNING,
        )
        val second = seededRecord(
            sessionId,
            "bg_second",
            state = BackgroundExecutionState.RUNNING,
        )
        store.upsert(first)
        store.upsert(second)
        backgroundScope.launch {
            delay(3_000)
            executor.putRecord(
                first.copy(
                    state = BackgroundExecutionState.TERMINAL,
                    outcome = BackgroundOutcome.SUCCEEDED,
                    result = seededResult(),
                    settledAtEpochMs = 3_000,
                ),
            )
            // The second handle settles A - X - 1 s after the second turn's await starts
            // (t = 3 s + 6 s = 9 s) — inside the remaining 7 s allowance.
            delay(6_000)
            executor.putRecord(
                second.copy(
                    state = BackgroundExecutionState.TERMINAL,
                    outcome = BackgroundOutcome.SUCCEEDED,
                    result = seededResult(),
                    settledAtEpochMs = 9_000,
                ),
            )
        }
        val provider = object : ProviderAdapter {
            override val key = "await-parallel"
            private var calls = 0
            override suspend fun generate(request: ProviderRequest): Flow<ProviderChunk> = flow {
                calls += 1
                emit(
                    when (calls) {
                        1 -> providerChunk(
                            toolCalls = listOf(
                                toolCall(
                                    "w1",
                                    "await_background_results",
                                    """{"handles":["bg_first"]}""",
                                ),
                                toolCall(
                                    "w2",
                                    "await_background_results",
                                    """{"handles":["bg_first"]}""",
                                ),
                            ),
                            completed = true,
                        )
                        2 -> providerChunk(
                            toolCalls = listOf(
                                toolCall(
                                    "w3",
                                    "await_background_results",
                                    """{"handles":["bg_second"]}""",
                                ),
                            ),
                            completed = true,
                        )
                        else -> providerChunk(text = "done", completed = true)
                    },
                )
            }
        }
        val runner = runner(provider = provider, tools = emptyList(), background = executor)

        val events = runner.run(
            request(
                "await-parallel",
                sessionId,
                config = backgroundConfig(explicitAllowance = 10_000),
            ),
        ).toList()

        val completed = events.filterIsInstance<AgentEvent.Completed>().single()
        val parts = completed.state.messages
            .flatMap { it.parts }.filterIsInstance<ToolResultPart>()
            .filter { it.toolName == "await_background_results" }
        val w3 = parts.single { it.toolCallId == "w3" }
        assertEquals("succeeded", w3.result.jsonObject["results"]!!.jsonArray.single()
            .jsonObject.getValue("status").jsonPrimitive.content)
        assertNull(w3.result.jsonObject["wait_allowance_exhausted"])
        // Charge is the union: 3 s for the overlapped turn + 6 s for the second turn.
        assertEquals(9_000L, completed.state.explicitWaitMillis)
        executor.close()
    }

    // Two parallel awaits on a handle that never settles each wait the full shared
    // allowance once: both report exhaustion, the Run is charged A (not 2A), and the next
    // turn's await is refused.
    @Test
    fun parallelAwaitsExhaustTheAllowanceOnce() = runTest {
        val store = InMemoryBackgroundExecutionStore()
        val executor = executor(store)
        val sessionId = AgentSessionId("await-parallel-exhausted")
        executor.register(sessionId, NoopListener)
        store.upsert(
            seededRecord(
                sessionId,
                "bg_never",
                state = BackgroundExecutionState.RUNNING,
            ),
        )
        val provider = object : ProviderAdapter {
            override val key = "await-parallel-exhausted"
            private var calls = 0
            override suspend fun generate(request: ProviderRequest): Flow<ProviderChunk> = flow {
                calls += 1
                emit(
                    when (calls) {
                        1 -> providerChunk(
                            toolCalls = listOf(
                                toolCall(
                                    "w1",
                                    "await_background_results",
                                    """{"handles":["bg_never"]}""",
                                ),
                                toolCall(
                                    "w2",
                                    "await_background_results",
                                    """{"handles":["bg_never"]}""",
                                ),
                            ),
                            completed = true,
                        )
                        2 -> providerChunk(
                            toolCalls = listOf(
                                toolCall(
                                    "w3",
                                    "await_background_results",
                                    """{"handles":["bg_never"]}""",
                                ),
                            ),
                            completed = true,
                        )
                        else -> providerChunk(text = "done", completed = true)
                    },
                )
            }
        }
        val runner = runner(provider = provider, tools = emptyList(), background = executor)

        val events = runner.run(
            request(
                "await-parallel-exhausted",
                sessionId,
                config = backgroundConfig(explicitAllowance = 10_000),
            ),
        ).toList()

        val completed = events.filterIsInstance<AgentEvent.Completed>().single()
        val parts = completed.state.messages
            .flatMap { it.parts }.filterIsInstance<ToolResultPart>()
            .filter { it.toolName == "await_background_results" }
        parts.filter { it.toolCallId == "w1" || it.toolCallId == "w2" }.forEach { part ->
            assertFalse(part.isError)
            assertEquals(
                true,
                part.result.jsonObject["wait_allowance_exhausted"]?.jsonPrimitive?.booleanOrNull,
            )
        }
        val refused = parts.single { it.toolCallId == "w3" }
        assertTrue(refused.isError)
        assertEquals(
            true,
            refused.result.jsonObject["wait_allowance_exhausted"]?.jsonPrimitive?.booleanOrNull,
        )
        assertEquals(10_000L, completed.state.explicitWaitMillis)
        executor.close()
    }

    // Sequential awaits never overlap, so the union equals the sum: two disjoint 2 s waits
    // charge the full 4 s.
    @Test
    fun sequentialAwaitsChargeTheSum() = runTest {
        val store = InMemoryBackgroundExecutionStore()
        val executor = executor(store)
        val sessionId = AgentSessionId("await-sequential")
        executor.register(sessionId, NoopListener)
        val first = seededRecord(
            sessionId,
            "bg_first",
            state = BackgroundExecutionState.RUNNING,
        )
        val second = seededRecord(
            sessionId,
            "bg_second",
            state = BackgroundExecutionState.RUNNING,
        )
        store.upsert(first)
        store.upsert(second)
        backgroundScope.launch {
            delay(2_000)
            executor.putRecord(
                first.copy(
                    state = BackgroundExecutionState.TERMINAL,
                    outcome = BackgroundOutcome.SUCCEEDED,
                    result = seededResult(),
                    settledAtEpochMs = 2_000,
                ),
            )
            delay(2_000)
            executor.putRecord(
                second.copy(
                    state = BackgroundExecutionState.TERMINAL,
                    outcome = BackgroundOutcome.SUCCEEDED,
                    result = seededResult(),
                    settledAtEpochMs = 4_000,
                ),
            )
        }
        val provider = scriptedProvider(
            "await-sequential",
            toolCall("w1", "await_background_results", """{"handles":["bg_first"]}"""),
            toolCall("w2", "await_background_results", """{"handles":["bg_second"]}"""),
        )
        val runner = runner(provider = provider, tools = emptyList(), background = executor)

        val events = runner.run(
            request(
                "await-sequential",
                sessionId,
                config = backgroundConfig(explicitAllowance = 10_000),
                toolExecutionMode = ToolExecutionMode.SEQUENTIAL,
            ),
        ).toList()

        val completed = events.filterIsInstance<AgentEvent.Completed>().single()
        assertEquals(4_000L, completed.state.explicitWaitMillis)
        executor.close()
    }

    // 11: cancel settles the referenced executions as suppressed CANCELLED results that are
    // never noticed.
    @Test
    fun cancelSuppressesAndNeverNotices() = runTest {
        val store = InMemoryBackgroundExecutionStore()
        val executor = executor(store)
        val sessionId = AgentSessionId("cancel-session")
        val tool = GatedTool("deep_lookup", ToolBackgroundPolicy.ELIGIBLE)
        val provider = scriptedProvider("cancel", toolCall("call-1", "deep_lookup"))
        val runner = runner(provider = provider, tools = listOf(tool), background = executor)
        val manager = DefaultAgentSessionManager(
            runner = runner,
            persistence = InMemoryAgentPersistence(),
            dispatcher = StandardTestDispatcher(testScheduler),
            background = executor,
        )
        val lease = manager.create(sessionId)
        lease.start(request("cancel", sessionId, config = backgroundConfig(handoffWait = 200)))
        advanceTimeBy(300)
        runCurrent()
        lease.awaitIdle()
        runCurrent()
        val handle = store.load(sessionId).records.single().handle

        val cancelProvider = scriptedProvider(
            "cancel-run",
            toolCall("cx", "cancel_background_executions", """{"handles":["$handle"]}"""),
        )
        val cancelRunner = runner(provider = cancelProvider, tools = emptyList(), background = executor)
        val cancelEvents = cancelRunner.run(
            request(
                "cancel-run",
                sessionId,
                config = backgroundConfig(),
                messages = checkNotNull(lease.state.value.state).messages + userMessage("stop it"),
            ),
        ).toList()

        val cancelled = store.load(sessionId).records.single()
        assertEquals(BackgroundExecutionState.TERMINAL, cancelled.state)
        assertEquals(BackgroundOutcome.CANCELLED, cancelled.outcome)
        assertTrue(cancelled.activationSuppressed)
        val part = cancelEvents.filterIsInstance<AgentEvent.Completed>().single().state.messages
            .last { it.role == MessageRole.TOOL }.parts.filterIsInstance<ToolResultPart>().single()
        assertEquals(
            handle,
            part.result.jsonObject["cancelled"]!!.jsonArray.single().jsonPrimitive.content,
        )
        // Suppressed and cancelled work is neither pending nor noticed.
        assertNull(lease.state.value.background?.pendingActivation)
        manager.close()
    }

    // 12: finish_without_reply without a Runtime notice is an error.
    @Test
    fun finishWithoutReplyRequiresANotice() = runTest {
        val store = InMemoryBackgroundExecutionStore()
        val executor = executor(store)
        val sessionId = AgentSessionId("finish-guard")
        executor.register(sessionId, NoopListener)
        val provider = scriptedProvider(
            "finish-guard",
            toolCall("f1", "finish_without_reply"),
        )
        val runner = runner(provider = provider, tools = emptyList(), background = executor)
        val events = runner.run(
            request("finish-guard", sessionId, config = backgroundConfig()),
        ).toList()

        val part = events.filterIsInstance<AgentEvent.Completed>().single().state.messages
            .single { it.role == MessageRole.TOOL }.parts.filterIsInstance<ToolResultPart>().single()
        assertTrue(part.isError)
        assertEquals(
            "finish_without_reply is only available after a runtime notice.",
            part.result.jsonObject.getValue("error").jsonPrimitive.content,
        )
        executor.close()
    }

    // 13: controls and the system section exist only in background-enabled Runs; a direct
    // (unregistered) Runner stays blocking and declares no controls.
    @Test
    fun controlsAndSystemSectionOnlyWhenEnabled() = runTest {
        val store = InMemoryBackgroundExecutionStore()
        val executor = executor(store)
        val sessionId = AgentSessionId("projection")
        executor.register(sessionId, NoopListener)
        val tool = DelayedTool("slow_lookup", 50, ToolBackgroundPolicy.ELIGIBLE)
        val provider = scriptedProvider("projection", toolCall("call-1", "slow_lookup"))
        val runner = runner(provider = provider, tools = listOf(tool), background = executor)
        runner.run(
            request(
                "projection",
                sessionId,
                config = backgroundConfig(handoffWait = 2_000),
                systemPrompt = "host prompt",
            ),
        ).toList()

        val projectedTools = provider.requests.first().tools.map { it.name }
        assertEquals(
            requestTools + listOf(
                "read_background_result",
                "await_background_results",
                "cancel_background_executions",
                "finish_without_reply",
            ),
            projectedTools,
        )
        val system = provider.requests.first().messages
            .single { it.role == MessageRole.SYSTEM }
            .parts.filterIsInstance<TextPart>().single().text
        assertEquals("host prompt\n\n" + BackgroundToolInstructions.SYSTEM_PROMPT_SECTION, system)

        // A direct, unregistered Runner stays blocking and declares no controls.
        val directProvider = scriptedProvider("direct", toolCall("d1", "slow_lookup"))
        val directRunner = runner(
            provider = directProvider,
            tools = listOf(tool),
            background = executor,
        )
        val unregistered = AgentSessionId("unregistered")
        val directEvents = directRunner.run(
            request(
                "direct",
                unregistered,
                config = backgroundConfig(handoffWait = 2_000),
                systemPrompt = "host prompt",
            ),
        ).toList()
        assertEquals(
            requestTools,
            directProvider.requests.first().tools.map { it.name },
        )
        assertEquals(
            "host prompt",
            directProvider.requests.first().messages.single { it.role == MessageRole.SYSTEM }
                .parts.filterIsInstance<TextPart>().single().text,
        )
        assertTrue(directEvents.none { it is AgentEvent.ToolHandedOff })
        assertTrue(store.load(unregistered).records.isEmpty())
        executor.close()
    }

    // 14: a runtime with a RUNNING record survives last-lease release until it settles.
    @Test
    fun runningRecordKeepsTheRuntimeAliveUntilSettlement() = runTest {
        val store = InMemoryBackgroundExecutionStore()
        val executor = executor(store)
        val sessionId = AgentSessionId("pinned-runtime")
        val tool = GatedTool("deep_lookup", ToolBackgroundPolicy.ELIGIBLE)
        val provider = scriptedProvider("pinned", toolCall("call-1", "deep_lookup"))
        val runner = runner(provider = provider, tools = listOf(tool), background = executor)
        val manager = DefaultAgentSessionManager(
            runner = runner,
            persistence = InMemoryAgentPersistence(),
            dispatcher = StandardTestDispatcher(testScheduler),
            background = executor,
        )
        val lease = manager.create(sessionId)
        lease.start(request("pinned", sessionId, config = backgroundConfig(handoffWait = 200)))
        advanceTimeBy(300)
        runCurrent()
        lease.awaitIdle()
        runCurrent()
        lease.release()
        runCurrent()

        assertTrue(manager.liveSessionIds.value.contains(sessionId))
        tool.gate.complete(Unit)
        runCurrent()
        advanceTimeBy(50)
        runCurrent()
        assertFalse(manager.liveSessionIds.value.contains(sessionId))
        manager.close()
    }

    // 15: origin context and record writes precede the receipt's journal commit.
    @Test
    fun originAndRecordWritesPrecedeTheReceiptCommit() = runTest {
        val operations = mutableListOf<String>()
        val innerStore = InMemoryBackgroundExecutionStore()
        val store = object : BackgroundExecutionStore {
            override suspend fun upsert(record: BackgroundExecutionRecord) {
                operations += "record"
                innerStore.upsert(record)
            }
            override suspend fun putOrigin(origin: BackgroundOriginContext) {
                operations += "origin"
                innerStore.putOrigin(origin)
            }
            override suspend fun load(sessionId: AgentSessionId) = innerStore.load(sessionId)
            override suspend fun delete(sessionId: AgentSessionId, executionId: String) =
                innerStore.delete(sessionId, executionId)
            override suspend fun deleteOrigin(sessionId: AgentSessionId, runId: AgentRunId) =
                innerStore.deleteOrigin(sessionId, runId)
            override suspend fun deleteSession(sessionId: AgentSessionId) =
                innerStore.deleteSession(sessionId)
            override suspend fun clear() = innerStore.clear()
        }
        val executor = executor(store)
        val sessionId = AgentSessionId("ordering")
        executor.register(sessionId, NoopListener)
        val tool = GatedTool("deep_lookup", ToolBackgroundPolicy.ELIGIBLE)
        val innerPersistence = InMemoryAgentPersistence()
        val persistence = object : AgentPersistence by innerPersistence {
            override suspend fun commit(
                snapshot: saien.magrathea.core.AgentSessionSnapshot,
                checkpoint: saien.magrathea.core.AgentCheckpoint?,
            ) {
                if (
                    checkpoint?.toolExecutions?.any {
                        it.result?.backgroundReceipt != null
                    } == true
                ) {
                    operations += "receipt-checkpoint"
                }
                innerPersistence.commit(snapshot, checkpoint)
            }
        }
        val provider = scriptedProvider("ordering", toolCall("call-1", "deep_lookup"))
        val runner = runner(
            provider = provider,
            tools = listOf(tool),
            background = executor,
            persistence = persistence,
        )
        val collected = async {
            runner.run(
                request("ordering", sessionId, config = backgroundConfig(handoffWait = 300)),
            ).toList()
        }
        runCurrent()
        advanceTimeBy(400)
        runCurrent()
        collected.await()

        val receiptCommit = operations.indexOf("receipt-checkpoint")
        assertTrue(receiptCommit >= 0)
        assertTrue(operations.indexOf("origin") in 0 until receiptCommit)
        assertTrue(operations.indexOf("record") in 0 until receiptCommit)
        executor.close()
    }

    // ---- S2: interruption, cancellation scopes, fences ----

    // S2-1: cancel during the foreground wait closes three calls in order: the eligible running
    // call gets a receipt, the started blocking call is cancelled, the approval-blocked call is
    // not_executed. The record settles later and no orphan reaches the next Provider request.
    @Test
    fun cancelDuringForegroundWaitClosesCallsInOrder() = runTest {
        val store = InMemoryBackgroundExecutionStore()
        val executor = executor(store)
        val sessionId = AgentSessionId("preempt-session")
        val eligible = GatedTool("deep_lookup", ToolBackgroundPolicy.ELIGIBLE)
        val blocking = GatedTool("blocking_lookup", ToolBackgroundPolicy.BLOCKING)
        val approvalGate = CompletableDeferred<Unit>()
        val gateway = object : saien.magrathea.core.ToolApprovalGateway {
            override suspend fun requestApproval(
                request: saien.magrathea.core.ToolApprovalRequest,
            ): saien.magrathea.core.ToolApprovalDecision {
                if (request.toolCall.toolName == "other_lookup") approvalGate.await()
                return saien.magrathea.core.ToolApprovalDecision.Approve
            }
        }
        val provider = scriptedProvider(
            "preempt",
            toolCall("call-1", "deep_lookup"),
            toolCall("call-2", "blocking_lookup"),
            toolCall("call-3", "other_lookup"),
        )
        val persistence = InMemoryAgentPersistence()
        val runner = runner(
            provider = provider,
            tools = listOf(eligible, blocking, CountingTool("other_lookup")),
            background = executor,
            persistence = persistence,
            approvalGateway = gateway,
        )
        val manager = DefaultAgentSessionManager(
            runner = runner,
            persistence = persistence,
            dispatcher = StandardTestDispatcher(testScheduler),
            background = executor,
        )
        val lease = manager.create(sessionId)
        val events = mutableListOf<AgentEvent>()
        backgroundScope.launch { lease.events.toList(events) }
        lease.start(
            request("preempt", sessionId, config = backgroundConfig(handoffWait = 60_000)),
        )
        runCurrent()
        val cancelJob = async { runner.cancel(sessionId) }
        runCurrent()
        cancelJob.await()

        val persisted = persistence.load(sessionId)!!.snapshot.state
        val toolMessage = persisted.messages.last { it.role == MessageRole.TOOL }
        val parts = toolMessage.parts.filterIsInstance<ToolResultPart>()
        assertEquals(listOf("call-1", "call-2", "call-3"), parts.map { it.toolCallId })
        // call-1: eligible running -> receipt
        assertNotNull(parts[0].backgroundReceipt)
        assertEquals("running", parts[0].result.jsonObject["status"]!!.jsonPrimitive.content)
        // call-2: blocking started -> cancelled
        assertEquals("cancelled", parts[1].result.jsonObject["status"]!!.jsonPrimitive.content)
        assertEquals(
            "New user input preempted this call.",
            parts[1].result.jsonObject["reason"]!!.jsonPrimitive.content,
        )
        assertEquals("Cancelled", parts[1].displayText)
        assertEquals(
            "cancelled",
            parts[1].metadata["magrathea.tool.preempted"]!!.jsonPrimitive.content,
        )
        // call-3: blocked in the approval gateway -> not_executed
        assertEquals(
            "not_executed",
            parts[2].result.jsonObject["status"]!!.jsonPrimitive.content,
        )
        assertEquals("Not run", parts[2].displayText)
        assertEquals(
            "not_executed",
            parts[2].metadata["magrathea.tool.preempted"]!!.jsonPrimitive.content,
        )

        // The handed-off record is still RUNNING; its late settlement emits the session event.
        val record = store.load(sessionId).records.single()
        assertEquals(BackgroundExecutionState.RUNNING, record.state)
        eligible.gate.complete(Unit)
        runCurrent()
        advanceTimeBy(10)
        runCurrent()
        val settled = store.load(sessionId).records.single()
        assertEquals(BackgroundExecutionState.TERMINAL, settled.state)
        assertEquals(BackgroundOutcome.SUCCEEDED, settled.outcome)
        assertTrue(events.any { it is AgentEvent.BackgroundExecutionSettled })

        // The next Run's Provider request carries the closure results, not orphan repairs.
        val persisted2 = persistence.load(sessionId)!!.snapshot.state
        val request2 = request(
            "preempt",
            sessionId,
            config = backgroundConfig(handoffWait = 60_000),
        ).copy(messages = persisted2.messages + userMessage("anything else?"))
        runner.run(request2).toList()
        val projected = provider.requests.last().messages
        val calls = projected.flatMap { it.parts.filterIsInstance<ToolCallPart>() }
            .filter { !it.partial }
        val results = projected.filter { it.role == MessageRole.TOOL }
            .flatMap { it.parts.filterIsInstance<ToolResultPart>() }
        val answeredIds = results.map { it.toolCallId }.toSet()
        calls.forEach { call ->
            assertTrue(
                call.toolCallId in answeredIds,
                "call ${call.toolCallId} is unanswered in the projected request",
            )
        }
        manager.close()
        executor.close()
    }

    // S2-2: an eligible call that settled in the foreground before the cancel contributes its
    // real finalized result.
    @Test
    fun cancelAfterForegroundSettlementReusesTheRealResult() = runTest {
        val store = InMemoryBackgroundExecutionStore()
        val executor = executor(store)
        val sessionId = AgentSessionId("settled-then-cancel")
        executor.register(sessionId, NoopListener)
        val eligible = DelayedTool("fast_lookup", 300, ToolBackgroundPolicy.ELIGIBLE)
        val blocking = GatedTool("blocking_lookup", ToolBackgroundPolicy.BLOCKING)
        val provider = scriptedProvider(
            "settled",
            toolCall("call-1", "fast_lookup"),
            toolCall("call-2", "blocking_lookup"),
        )
        val persistence = InMemoryAgentPersistence()
        val runner2 = runner(provider = provider, tools = listOf(eligible, blocking),
            background = executor, persistence = persistence)
        val collected = async {
            runner2.run(
                request("settled", sessionId, config = backgroundConfig(handoffWait = 60_000)),
            ).toList()
        }
        runCurrent()
        advanceTimeBy(400) // eligible settles; blocking still gated
        runCurrent()
        val cancelJob = async { runner2.cancel(sessionId) }
        runCurrent()
        cancelJob.await()
        runCatching { collected.await() }

        val persisted = persistence.load(sessionId)!!.snapshot.state
        val parts = persisted.messages.last { it.role == MessageRole.TOOL }.parts
            .filterIsInstance<ToolResultPart>()
        assertEquals(listOf("call-1", "call-2"), parts.map { it.toolCallId })
        assertEquals("result-fast_lookup", parts[0].result.jsonPrimitive.content)
        assertFalse(parts[0].isError)
        assertEquals("cancelled", parts[1].result.jsonObject["status"]!!.jsonPrimitive.content)
        executor.close()
    }

    // S2-3: stopAll during the foreground wait closes calls as cancelled (never receipts),
    // suppresses unacknowledged terminal records, and clears pending activations.
    @Test
    fun stopAllDuringForegroundWaitWritesCancelledCloses() = runTest {
        val store = InMemoryBackgroundExecutionStore()
        val executor = executor(store)
        val sessionId = AgentSessionId("stopall-session")
        val eligible = GatedTool("deep_lookup", ToolBackgroundPolicy.ELIGIBLE)
        val blocking = GatedTool("blocking_lookup", ToolBackgroundPolicy.BLOCKING)
        val provider = scriptedProvider(
            "stopall",
            toolCall("call-1", "deep_lookup"),
            toolCall("call-2", "blocking_lookup"),
        )
        val persistence = InMemoryAgentPersistence()
        val runner = runner(
            provider = provider,
            tools = listOf(eligible, blocking),
            background = executor,
            persistence = persistence,
        )
        val manager = DefaultAgentSessionManager(
            runner = runner,
            persistence = persistence,
            dispatcher = StandardTestDispatcher(testScheduler),
            background = executor,
        )
        val lease = manager.create(sessionId)
        lease.start(
            request("stopall", sessionId, config = backgroundConfig(handoffWait = 60_000)),
        )
        runCurrent()
        store.upsert(
            seededRecord(
                sessionId,
                "bg_prior",
                state = BackgroundExecutionState.TERMINAL,
                outcome = BackgroundOutcome.SUCCEEDED,
            ),
        )
        lease.stopAll()

        val persisted = persistence.load(sessionId)!!.snapshot.state
        val parts = persisted.messages.last { it.role == MessageRole.TOOL }.parts
            .filterIsInstance<ToolResultPart>()
        assertEquals(listOf("call-1", "call-2"), parts.map { it.toolCallId })
        parts.forEach {
            assertNull(it.backgroundReceipt)
            assertEquals("cancelled", it.result.jsonObject["status"]!!.jsonPrimitive.content)
        }
        val records = store.load(sessionId).records
        assertTrue(records.none { it.state != BackgroundExecutionState.TERMINAL })
        assertTrue(records.all { it.activationSuppressed })
        assertTrue(
            store.load(sessionId).records.single { it.handle == "bg_prior" }.activationSuppressed,
        )
        assertNull(lease.state.value.background?.pendingActivation)
        manager.close()
        executor.close()
    }

    // S2-4: stopAll with no Run still cancels every running record and suppresses it forever.
    @Test
    fun stopAllWithoutARunCancelsAndSuppressesRecords() = runTest {
        val store = InMemoryBackgroundExecutionStore()
        val executor = executor(store)
        val sessionId = AgentSessionId("stopall-idle")
        val tool = GatedTool("deep_lookup", ToolBackgroundPolicy.ELIGIBLE)
        val provider = scriptedProvider("idle", toolCall("call-1", "deep_lookup"))
        val persistence = InMemoryAgentPersistence()
        val runner = runner(
            provider = provider,
            tools = listOf(tool),
            background = executor,
            persistence = persistence,
        )
        val manager = DefaultAgentSessionManager(
            runner = runner,
            persistence = persistence,
            dispatcher = StandardTestDispatcher(testScheduler),
            background = executor,
        )
        val lease = manager.create(sessionId)
        lease.start(request("idle", sessionId, config = backgroundConfig(handoffWait = 500)))
        advanceTimeBy(600)
        runCurrent() // handed off; run completed
        val record = store.load(sessionId).records.single()
        assertEquals(BackgroundExecutionState.RUNNING, record.state)

        lease.stopAll()
        val cancelled = store.load(sessionId).records.single()
        assertEquals(BackgroundExecutionState.TERMINAL, cancelled.state)
        assertEquals(BackgroundOutcome.CANCELLED, cancelled.outcome)
        assertTrue(cancelled.activationSuppressed)
        assertNull(lease.state.value.background?.pendingActivation)

        // The cancelled record never produces a notice even after the job ends.
        tool.gate.complete(Unit)
        runCurrent()
        advanceTimeBy(50)
        runCurrent()
        manager.close()
        executor.close()
    }

    // S2-5: cancelBackground partitions handles into cancelled / alreadyFinished / unknown and
    // leaves unreferenced records alone.
    @Test
    fun cancelBackgroundPartitionsHandles() = runTest {
        val store = InMemoryBackgroundExecutionStore()
        val executor = executor(store)
        val sessionId = AgentSessionId("host-cancel")
        store.upsert(
            seededRecord(sessionId, "bg_live", state = BackgroundExecutionState.RUNNING),
        )
        store.upsert(
            seededRecord(
                sessionId,
                "bg_done",
                state = BackgroundExecutionState.TERMINAL,
                outcome = BackgroundOutcome.SUCCEEDED,
            ),
        )
        store.upsert(
            seededRecord(sessionId, "bg_other", state = BackgroundExecutionState.RUNNING),
        )
        // Registration applies the host-loss sweep to records with no live job; REPLAY_SAFE
        // Tools keep their records queued, so deep_lookup is registered as replay-safe here.
        val runner = runner(
            provider = scriptedProvider("hostcancel"),
            tools = listOf(
                CountingTool(
                    "deep_lookup",
                    recoveryPolicy = ToolRecoveryPolicy.REPLAY_SAFE,
                ),
            ),
            background = executor,
        )
        val manager = DefaultAgentSessionManager(
            runner = runner,
            persistence = InMemoryAgentPersistence(),
            dispatcher = StandardTestDispatcher(testScheduler),
            background = executor,
        )
        val lease = manager.create(sessionId)

        val result = lease.cancelBackground(listOf("bg_live", "bg_done", "bg_missing"))
        assertEquals(listOf("bg_live"), result.cancelled)
        assertEquals(listOf("bg_done"), result.alreadyFinished)
        assertEquals(listOf("bg_missing"), result.unknown)
        val records = store.load(sessionId).records.associateBy { it.handle }
        assertEquals(BackgroundOutcome.CANCELLED, records["bg_live"]?.outcome)
        assertTrue(records["bg_live"]?.activationSuppressed == true)
        assertEquals(BackgroundOutcome.SUCCEEDED, records["bg_done"]?.outcome)
        assertEquals(BackgroundExecutionState.QUEUED, records["bg_other"]?.state)
        manager.close()
        executor.close()
    }

    // S2-6: a Tool's own cancellation is a failure, not a discard (F1) — after handoff the
    // record becomes TERMINAL ERROR; before handoff the foreground result is the failure; an
    // executor-cancelled admission closes the batch with a cancelled result.
    @Test
    fun toolSelfCancellationIsAFailureNotADiscard() = runTest {
        val store = InMemoryBackgroundExecutionStore()
        val executor = executor(store)
        val sessionId = AgentSessionId("self-cancel")
        executor.register(sessionId, NoopListener)
        val tool = object : ToolExecutor {
            override val definition = ToolDefinition(
                name = "flaky_lookup",
                description = "cancels itself",
                schema = buildJsonObject { put("type", "object") },
            )
            override val backgroundPolicy = ToolBackgroundPolicy.ELIGIBLE
            override suspend fun execute(request: ToolExecutionRequest): ToolExecutionResult {
                delay(50)
                throw kotlinx.coroutines.CancellationException("inner self cancel")
            }
        }
        val provider = scriptedProvider("selfcancel", toolCall("call-1", "flaky_lookup"))
        val runner = runner(provider = provider, tools = listOf(tool), background = executor)
        val request = request(
            "selfcancel",
            sessionId,
            config = backgroundConfig(handoffWait = 200),
            tools = listOf(tool.definition),
        )
        // Settles at 50 inside the 200 allowance -> failure, no record, no discard.
        val events = runner.run(request).toList()
        val part = events.filterIsInstance<AgentEvent.Completed>().single().state.messages
            .single { it.role == MessageRole.TOOL }.parts.filterIsInstance<ToolResultPart>().single()
        assertTrue(part.isError)
        assertEquals("Tool execution failed", part.result.jsonPrimitive.content)
        assertTrue(store.load(sessionId).records.isEmpty())

        // The same failure after handoff becomes TERMINAL ERROR with the standard result.
        val sessionId2 = AgentSessionId("self-cancel-late")
        executor.register(sessionId2, NoopListener)
        val provider2 = scriptedProvider("selfcancel2", toolCall("call-1", "flaky_lookup"))
        val runner2 = runner(provider = provider2, tools = listOf(tool), background = executor)
        val request2 = request(
            "selfcancel2",
            sessionId2,
            config = backgroundConfig(handoffWait = 10),
            tools = listOf(tool.definition),
        )
        runner2.run(request2).toList()
        advanceTimeBy(100)
        runCurrent()
        val late = store.load(sessionId2).records.single()
        assertEquals(BackgroundExecutionState.TERMINAL, late.state)
        assertEquals(BackgroundOutcome.ERROR, late.outcome)
        assertEquals("Tool execution failed", late.result!!.result.jsonPrimitive.content)

        // An admission cancelled by the executor before handoff completes the batch with a
        // cancelled result instead of hanging.
        val sessionId3 = AgentSessionId("executor-cancel")
        executor.register(sessionId3, NoopListener)
        val gated = GatedTool("gated_lookup", ToolBackgroundPolicy.ELIGIBLE)
        val provider3 = scriptedProvider("cancelled", toolCall("call-1", "gated_lookup"))
        val runner3 = runner(provider = provider3, tools = listOf(gated), background = executor)
        val request3 = request(
            "cancelled",
            sessionId3,
            config = backgroundConfig(handoffWait = 60_000),
            tools = listOf(gated.definition),
        )
        val collected = async { runner3.run(request3).toList() }
        runCurrent()
        executor.stopSession(sessionId3, emptySet())
        runCurrent()
        val events3 = collected.await()
        val close = events3.filterIsInstance<AgentEvent.Completed>().single().state.messages
            .single { it.role == MessageRole.TOOL }.parts.filterIsInstance<ToolResultPart>().single()
        assertEquals("cancelled", close.result.jsonObject["status"]!!.jsonPrimitive.content)
        executor.close()
    }

    // S2-7: await returns exactly at the settlement instant with a bounded number of store
    // loads (no polling).
    @Test
    fun awaitReturnsAtTheSettlementInstant() = runTest {
        var loads = 0
        val innerStore = InMemoryBackgroundExecutionStore()
        val store = object : BackgroundExecutionStore {
            override suspend fun upsert(record: BackgroundExecutionRecord) =
                innerStore.upsert(record)
            override suspend fun putOrigin(origin: BackgroundOriginContext) =
                innerStore.putOrigin(origin)
            override suspend fun load(sessionId: AgentSessionId) =
                innerStore.load(sessionId).also { loads += 1 }
            override suspend fun delete(sessionId: AgentSessionId, executionId: String) =
                innerStore.delete(sessionId, executionId)
            override suspend fun deleteOrigin(sessionId: AgentSessionId, runId: AgentRunId) =
                innerStore.deleteOrigin(sessionId, runId)
            override suspend fun deleteSession(sessionId: AgentSessionId) =
                innerStore.deleteSession(sessionId)
            override suspend fun clear() = innerStore.clear()
        }
        val executor = executor(store)
        val sessionId = AgentSessionId("signal-await")
        executor.register(sessionId, NoopListener)
        val pending = seededRecord(
            sessionId,
            "bg_waited",
            state = BackgroundExecutionState.RUNNING,
        )
        store.upsert(pending)
        loads = 0
        backgroundScope.launch {
            delay(100)
            executor.putRecord(
                pending.copy(
                    state = BackgroundExecutionState.TERMINAL,
                    outcome = BackgroundOutcome.SUCCEEDED,
                    result = seededResult(),
                    settledAtEpochMs = 100,
                ),
            )
        }
        val provider = scriptedProvider(
            "signalawait",
            toolCall("w1", "await_background_results", """{"handles":["bg_waited"]}"""),
        )
        val runner = runner(provider = provider, tools = emptyList(), background = executor)
        runner.run(
            request("signalawait", sessionId, config = backgroundConfig(explicitAllowance = 60_000)),
        ).toList()

        assertTrue(loads <= 6, "settlement waits must be signalled, not polled: loads=$loads")
        executor.close()
    }

    // S2-8: a suspending afterToolCall interceptor runs outside the Session mutex and cannot
    // block cancellation of another record (F3).
    @Test
    fun suspendingInterceptorDoesNotBlockSessionWrites() = runTest {
        val store = InMemoryBackgroundExecutionStore()
        val executor = executor(store)
        val sessionId = AgentSessionId("finalize-outside")
        val interceptorGate = CompletableDeferred<Unit>()
        val interceptor = object : AgentInterceptor {
            override suspend fun afterToolCall(
                context: saien.magrathea.core.ToolRuntimeContext,
                result: ToolExecutionResult,
            ): ToolExecutionResult {
                if (result.toolName == "fast_lookup") interceptorGate.await()
                return result
            }
        }
        val fast = DelayedTool("fast_lookup", 50, ToolBackgroundPolicy.ELIGIBLE)
        val provider = scriptedProvider(
            "finalize",
            toolCall("call-1", "fast_lookup"),
        )
        val persistence = InMemoryAgentPersistence()
        val runner = runner(
            provider = provider,
            tools = listOf(fast),
            background = executor,
            persistence = persistence,
            interceptors = listOf(interceptor),
        )
        val manager = DefaultAgentSessionManager(
            runner = runner,
            persistence = persistence,
            dispatcher = StandardTestDispatcher(testScheduler),
            background = executor,
        )
        val lease = manager.create(sessionId)
        lease.start(request("finalize", sessionId, config = backgroundConfig(handoffWait = 10)))
        advanceTimeBy(100)
        runCurrent() // handed off at ~10; execution finished at 50; interceptor now suspended
        store.upsert(
            seededRecord(sessionId, "bg_victim", state = BackgroundExecutionState.RUNNING),
        )

        // The settlement is stuck inside afterToolCall — the Session mutex is free.
        val cancelled = lease.cancelBackground(listOf("bg_victim"))
        assertEquals(listOf("bg_victim"), cancelled.cancelled)

        interceptorGate.complete(Unit)
        runCurrent()
        val settled = store.load(sessionId).records
            .single { it.toolName == "fast_lookup" }
        assertEquals(BackgroundExecutionState.TERMINAL, settled.state)
        manager.close()
        executor.close()
    }

    // S2-9: SEQUENTIAL mode — the next call starts only after the eligible call settles or is
    // handed off (F4).
    @Test
    fun sequentialModeWaitsForTheEligibleCallBeforeTheNext() = runTest {
        val store = InMemoryBackgroundExecutionStore()
        val executor = executor(store)
        val sessionId = AgentSessionId("sequential")
        executor.register(sessionId, NoopListener)
        val eligible = GatedTool("deep_lookup", ToolBackgroundPolicy.ELIGIBLE)
        val second = CountingTool("other_lookup")
        val provider = scriptedProvider(
            "seq",
            toolCall("call-1", "deep_lookup"),
            toolCall("call-2", "other_lookup"),
        )
        val runner = runner(
            provider = provider,
            tools = listOf(eligible, second),
            background = executor,
        )
        val collected = async {
            runner.run(
                request(
                    "seq",
                    sessionId,
                    config = backgroundConfig(handoffWait = 500),
                    toolExecutionMode = ToolExecutionMode.SEQUENTIAL,
                ),
            ).toList()
        }
        runCurrent()
        assertEquals(0, second.executions) // still gated on call-1's foreground wait
        advanceTimeBy(600)
        runCurrent() // eligible handed off at 500; the second call then runs

        val events = collected.await()
        assertTrue(
            events.any {
                it is AgentEvent.ToolHandedOff && it.toolCallId == "call-1"
            },
        )
        assertTrue(
            events.any {
                it is AgentEvent.ToolCompleted && it.result.toolCallId == "call-2"
            },
        )
        executor.close()
    }

    // S2-10: tracing — a handed-off call's TOOL_CALL span ends with handed_off; the
    // background_execution span ends at settlement with outcome and timings (F5).
    @Test
    fun handedOffCallsProduceAHandedOffSpanAndABackgroundSpan() = runTest {
        val store = InMemoryBackgroundExecutionStore()
        val executor = executor(store)
        val sessionId = AgentSessionId("tracing-session")
        executor.register(sessionId, NoopListener)
        val tool = GatedTool("deep_lookup", ToolBackgroundPolicy.ELIGIBLE)
        val sink = RecordingTraceSink()
        val provider = scriptedProvider("tracing", toolCall("call-1", "deep_lookup"))
        val runner = runner(
            provider = provider,
            tools = listOf(tool),
            background = executor,
            tracer = sink.tracer(monotonicClock = saien.magrathea.core.MonotonicClock {
                testScheduler.currentTime
            }),
        )
        val collected = async {
            runner.run(
                request("tracing", sessionId, config = backgroundConfig(handoffWait = 300)),
            ).toList()
        }
        runCurrent()
        advanceTimeBy(400)
        runCurrent()
        collected.await()

        val toolSpans = sink.spans.filter { it.name == RuntimeTraceNames.TOOL_CALL }
        assertEquals(1, toolSpans.size)
        assertEquals(true, toolSpans.single().booleanAttribute("magrathea.tool.handed_off"))
        assertEquals("handed_off", toolSpans.single().stringAttribute("magrathea.outcome"))

        tool.gate.complete(Unit)
        runCurrent()
        advanceTimeBy(20)
        runCurrent()
        val backgroundSpans = sink.spans.filter {
            it.name == RuntimeTraceNames.BACKGROUND_EXECUTION
        }
        assertEquals(1, backgroundSpans.size)
        val backgroundSpan = backgroundSpans.single()
        assertEquals("succeeded", backgroundSpan.stringAttribute("magrathea.outcome"))
        assertEquals(sessionId.value, backgroundSpan.stringAttribute("magrathea.agent.session_id"))
        assertEquals("deep_lookup", backgroundSpan.stringAttribute("magrathea.tool.name"))
        assertNotNull(backgroundSpan.longAttribute("magrathea.tool.background.queued_wait_ms"))
        assertNotNull(backgroundSpan.longAttribute("magrathea.tool.background.execution_ms"))
        executor.close()
    }

    // S2-11: suspend/resume — REPLAY_SAFE records return to QUEUED and re-execute with the same
    // executionId; FAIL_CLOSED records become INTERRUPTED.
    @Test
    fun suspendAndResumeReplaysSafeRecords() = runTest {
        val store = InMemoryBackgroundExecutionStore()
        val executor = executor(store)
        val sessionId = AgentSessionId("suspend-resume")
        val replayable = GatedTool(
            "deep_lookup",
            ToolBackgroundPolicy.ELIGIBLE,
            saien.magrathea.core.ToolRecoveryPolicy.REPLAY_SAFE,
        )
        val provider = scriptedProvider("susp", toolCall("call-1", "deep_lookup"))
        val persistence = InMemoryAgentPersistence()
        val runner = runner(
            provider = provider,
            tools = listOf(replayable),
            background = executor,
            persistence = persistence,
        )
        val manager = DefaultAgentSessionManager(
            runner = runner,
            persistence = persistence,
            dispatcher = StandardTestDispatcher(testScheduler),
            background = executor,
        )
        val lease = manager.create(sessionId)
        lease.start(request("susp", sessionId, config = backgroundConfig(handoffWait = 200)))
        advanceTimeBy(300)
        runCurrent()
        val running = store.load(sessionId).records.single()
        assertEquals(BackgroundExecutionState.RUNNING, running.state)
        assertEquals(1, replayable.executions)

        lease.suspendBackground()
        val suspended = store.load(sessionId).records.single()
        assertEquals(BackgroundExecutionState.QUEUED, suspended.state)
        assertNull(suspended.startedAtEpochMs)

        lease.resumeBackground()
        runCurrent()
        replayable.gate.complete(Unit)
        advanceTimeBy(20)
        runCurrent()
        val resumed = store.load(sessionId).records.single()
        assertEquals(2, replayable.executions)
        assertEquals(BackgroundExecutionState.TERMINAL, resumed.state)
        assertEquals(BackgroundOutcome.SUCCEEDED, resumed.outcome)
        assertEquals(running.executionId, resumed.executionId)

        // FAIL_CLOSED records are interrupted instead of requeued.
        store.upsert(
            seededRecord(sessionId, "bg_closed", state = BackgroundExecutionState.RUNNING)
                .copy(toolName = "ghost_tool"),
        )
        lease.suspendBackground()
        val closed = store.load(sessionId).records.single { it.handle == "bg_closed" }
        assertEquals(BackgroundExecutionState.TERMINAL, closed.state)
        assertEquals(BackgroundOutcome.INTERRUPTED, closed.outcome)
        manager.close()
        executor.close()
    }

    // S2-12: a QUEUED record past its queued lifetime is INTERRUPTED at resume without
    // executing.
    @Test
    fun queuedLifetimeExceededAtResumeInterrupts() = runTest {
        val store = InMemoryBackgroundExecutionStore()
        val executor = executor(store)
        val sessionId = AgentSessionId("expired-queue")
        val tool = CountingTool("deep_lookup")
        val runner = runner(
            provider = scriptedProvider("expired"),
            tools = listOf(tool),
            background = executor,
        )
        val manager = DefaultAgentSessionManager(
            runner = runner,
            persistence = InMemoryAgentPersistence(),
            dispatcher = StandardTestDispatcher(testScheduler),
            background = executor,
        )
        val lease = manager.create(sessionId)
        val origin = BackgroundOriginContext(
            sessionId = sessionId,
            runId = AgentRunId("origin-run"),
            request = request(
                "expired",
                sessionId,
                config = backgroundConfig(),
            ).copy(messages = emptyList()),
        )
        store.putOrigin(origin)
        val record = seededRecord(sessionId, "bg_stale", state = BackgroundExecutionState.QUEUED)
        store.upsert(record)

        advanceTimeBy(120_000) // past maxQueuedLifetimeMillis = 60_000
        lease.resumeBackground()
        runCurrent()
        val expired = store.load(sessionId).records.single()
        assertEquals(BackgroundExecutionState.TERMINAL, expired.state)
        assertEquals(BackgroundOutcome.INTERRUPTED, expired.outcome)
        assertEquals(0, tool.executions)
        manager.close()
        executor.close()
    }

    // S2-13: the regeneration fence — starting with a history that drops the origin call
    // deletes the record and cancels its job; a full-history start leaves everything.
    @Test
    fun startWithTruncatedHistoryDeletesStaleRecords() = runTest {
        val store = InMemoryBackgroundExecutionStore()
        val executor = executor(store)
        val sessionId = AgentSessionId("fence-session")
        val tool = GatedTool("deep_lookup", ToolBackgroundPolicy.ELIGIBLE)
        val provider = scriptedProvider("fence", toolCall("call-1", "deep_lookup"))
        val persistence = InMemoryAgentPersistence()
        val runner = runner(
            provider = provider,
            tools = listOf(tool),
            background = executor,
            persistence = persistence,
        )
        val manager = DefaultAgentSessionManager(
            runner = runner,
            persistence = persistence,
            dispatcher = StandardTestDispatcher(testScheduler),
            background = executor,
        )
        val lease = manager.create(sessionId)
        lease.start(request("fence", sessionId, config = backgroundConfig(handoffWait = 200)))
        advanceTimeBy(300)
        runCurrent()
        assertEquals(1, store.load(sessionId).records.size)
        assertEquals(1, store.load(sessionId).origins.size)
        val fullHistory = persistence.load(sessionId)!!.snapshot.state.messages

        // A start carrying the full history changes nothing.
        lease.start(
            request("fence", sessionId, config = backgroundConfig(handoffWait = 200))
                .copy(messages = fullHistory + userMessage("keep it")),
        )
        runCurrent()
        assertEquals(1, store.load(sessionId).records.size)
        manager.close()

        // A fresh session: truncated history removes the record and its origin.
        val sessionId2 = AgentSessionId("fence-truncated")
        val tool2 = GatedTool("deep_lookup", ToolBackgroundPolicy.ELIGIBLE)
        val provider2 = scriptedProvider("fence2", toolCall("call-2", "deep_lookup"))
        val manager2 = DefaultAgentSessionManager(
            runner = runner(provider = provider2, tools = listOf(tool2), background = executor),
            persistence = InMemoryAgentPersistence(),
            dispatcher = StandardTestDispatcher(testScheduler),
            background = executor,
        )
        val lease2 = manager2.create(sessionId2)
        lease2.start(
            request("fence2", sessionId2, config = backgroundConfig(handoffWait = 200)),
        )
        advanceTimeBy(300)
        runCurrent()
        assertEquals(1, store.load(sessionId2).records.size)
        lease2.start(
            request("fence2", sessionId2, config = backgroundConfig(handoffWait = 200))
                .copy(messages = listOf(userMessage("regenerate"))),
        )
        runCurrent()
        assertTrue(store.load(sessionId2).records.isEmpty())
        assertTrue(store.load(sessionId2).origins.isEmpty())
        manager2.close()
        executor.close()
    }

    // S2-14: manager delete removes records and cancels jobs; a late settle callback is
    // discarded without recreating the record.
    @Test
    fun deleteCancelsJobsAndDiscardsLateCallbacks() = runTest {
        val store = InMemoryBackgroundExecutionStore()
        val executor = executor(store)
        val sessionId = AgentSessionId("delete-session")
        val tool = GatedTool("deep_lookup", ToolBackgroundPolicy.ELIGIBLE)
        val provider = scriptedProvider("delete", toolCall("call-1", "deep_lookup"))
        val manager = DefaultAgentSessionManager(
            runner = runner(
                provider = provider,
                tools = listOf(tool),
                background = executor,
            ),
            persistence = InMemoryAgentPersistence(),
            dispatcher = StandardTestDispatcher(testScheduler),
            background = executor,
        )
        val lease = manager.create(sessionId)
        lease.start(request("delete", sessionId, config = backgroundConfig(handoffWait = 200)))
        advanceTimeBy(300)
        runCurrent()
        assertEquals(1, store.load(sessionId).records.size)

        manager.delete(sessionId)
        assertTrue(store.load(sessionId).records.isEmpty())
        assertTrue(store.load(sessionId).origins.isEmpty())

        // The job's late settle callback is discarded — nothing is written back.
        tool.gate.complete(Unit)
        runCurrent()
        advanceTimeBy(20)
        runCurrent()
        assertTrue(store.load(sessionId).records.isEmpty())
        manager.close()
        executor.close()
    }

    // S2-15: markPersistedCancelled closes a persisted nonterminal Run without a live runner —
    // journal COMPLETED -> real result, STARTED -> cancelled, PENDING -> receipt from the stored
    // background record, PENDING without one -> not_executed.
    @Test
    fun persistedCancelClosesJournalFromTheStore() = runTest {
        val store = InMemoryBackgroundExecutionStore()
        val executor = executor(store)
        val sessionId = AgentSessionId("persisted-cancel")
        executor.register(sessionId, NoopListener)
        val fast = CountingTool("fast_lookup")
        val gated = GatedTool("blocking_lookup", ToolBackgroundPolicy.BLOCKING)
        val approvalGate = CompletableDeferred<Unit>()
        val gateway = object : saien.magrathea.core.ToolApprovalGateway {
            override suspend fun requestApproval(
                request: saien.magrathea.core.ToolApprovalRequest,
            ): saien.magrathea.core.ToolApprovalDecision {
                if (request.toolCall.toolName == "other_lookup") approvalGate.await()
                return saien.magrathea.core.ToolApprovalDecision.Approve
            }
        }
        val provider = scriptedProvider(
            "persisted",
            toolCall("call-1", "fast_lookup"),
            toolCall("call-2", "blocking_lookup"),
            toolCall("call-3", "other_lookup"),
        )
        val persistence = InMemoryAgentPersistence()
        val runner = runner(
            provider = provider,
            tools = listOf(fast, gated, CountingTool("other_lookup")),
            background = executor,
            persistence = persistence,
            approvalGateway = gateway,
        )
        val collected = async {
            runner.run(
                request("persisted", sessionId, config = backgroundConfig(handoffWait = 60_000)),
            ).toList()
        }
        runCurrent()
        advanceTimeBy(50)
        runCurrent()
        val interruptJob = async { runner.interrupt(sessionId) }
        runCurrent()
        interruptJob.await()
        runCatching { collected.await() }

        // Seed a background record for the still-pending call so the closure emits a receipt.
        val stored = persistence.load(sessionId)!!
        val pending = stored.checkpoint?.toolExecutions
        val runId = stored.snapshot.runId
        val pendingRecord = pending!!.single {
            it.toolCallId == "call-3" && it.state == saien.magrathea.core.ToolExecutionState.PENDING
        }
        store.upsert(
            BackgroundExecutionRecord(
                sessionId = sessionId,
                executionId = pendingRecord.executionId,
                handle = "bg_seeded",
                originRunId = runId,
                originCallId = "call-3",
                toolName = "other_lookup",
                sourceId = "source",
                state = BackgroundExecutionState.RUNNING,
                executionRequest = ToolExecutionRequest(
                    sessionId = sessionId,
                    runId = runId,
                    executionId = pendingRecord.executionId,
                    assistantMessage = AgentMessage(
                        role = MessageRole.ASSISTANT,
                        parts = emptyList(),
                    ),
                    toolCall = toolCall("call-3", "other_lookup"),
                ),
                effectiveTimeoutMillis = 1_000,
                queuedAtEpochMs = 0,
            ),
        )

        runner.cancel(sessionId)
        val closed = persistence.load(sessionId)!!.snapshot.state
        assertEquals(saien.magrathea.core.AgentStatus.CANCELLED, closed.status)
        val toolMessage = closed.messages.last { it.role == MessageRole.TOOL }
        val parts = toolMessage.parts.filterIsInstance<ToolResultPart>()
        assertEquals(listOf("call-1", "call-2", "call-3"), parts.map { it.toolCallId })
        assertEquals("counted", parts[0].result.jsonPrimitive.content)
        assertEquals("cancelled", parts[1].result.jsonObject["status"]!!.jsonPrimitive.content)
        assertNotNull(parts[2].backgroundReceipt)
        assertEquals("running", parts[2].result.jsonObject["status"]!!.jsonPrimitive.content)
        executor.close()
    }

    // S2-16: feature-off cancellation leaves pending calls unanswered — the preemption
    // closure applies only to background-enabled Runs.
    @Test
    fun cancelWithoutBackgroundConfigLeavesCallsUnanswered() = runTest {
        val store = InMemoryBackgroundExecutionStore()
        val executor = executor(store)
        val sessionId = AgentSessionId("off-cancel")
        executor.register(sessionId, NoopListener)
        val tool = GatedTool("deep_lookup", ToolBackgroundPolicy.BLOCKING)
        val provider = scriptedProvider("offcancel", toolCall("call-1", "deep_lookup"))
        val persistence = InMemoryAgentPersistence()
        val runner = runner(
            provider = provider,
            tools = listOf(tool),
            background = executor,
            persistence = persistence,
        )
        val collected = async {
            runner.run(request("offcancel", sessionId, config = null)).toList()
        }
        runCurrent()
        val cancelJob = async { runner.cancel(sessionId) }
        runCurrent()
        cancelJob.await()
        runCatching { collected.await() }

        val persisted = persistence.load(sessionId)!!.snapshot.state
        assertEquals(saien.magrathea.core.AgentStatus.CANCELLED, persisted.status)
        assertTrue(persisted.messages.none { it.role == MessageRole.TOOL })
        assertEquals(1, persisted.pendingToolCalls.size)
    }

    // S3-1: a terminal await result counts as delivery — the next turn appends no
    // COALESCED notice and the completed Run leaves no pending activation.
    @Test
    fun terminalAwaitResultCountsAsDelivery() = runTest {
        val store = InMemoryBackgroundExecutionStore()
        val executor = executor(store)
        val sessionId = AgentSessionId("await-delivers")
        val tool = DelayedTool("deep_lookup", 400, ToolBackgroundPolicy.ELIGIBLE)
        val provider = object : ProviderAdapter {
            override val key = "await-delivering"
            val requests = mutableListOf<ProviderRequest>()
            private var calls = 0
            override suspend fun generate(request: ProviderRequest): Flow<ProviderChunk> = flow {
                requests += request
                calls += 1
                when (calls) {
                    1 -> emit(
                        providerChunk(
                            toolCalls = listOf(toolCall("call-1", "deep_lookup")),
                            completed = true,
                        ),
                    )
                    2 -> {
                        val handle = request.messages
                            .flatMap { it.parts }
                            .filterIsInstance<ToolResultPart>()
                            .firstNotNullOf { it.backgroundReceipt?.handle }
                        emit(
                            providerChunk(
                                toolCalls = listOf(
                                    toolCall(
                                        "call-2",
                                        "await_background_results",
                                        """{"handles":["$handle"],"max_wait_ms":5000}""",
                                    ),
                                ),
                                completed = true,
                            ),
                        )
                    }
                    else -> emit(providerChunk(text = "done", completed = true))
                }
            }
        }
        val runner = runner(provider = provider, tools = listOf(tool), background = executor)
        val manager = DefaultAgentSessionManager(
            runner = runner,
            persistence = InMemoryAgentPersistence(),
            dispatcher = StandardTestDispatcher(testScheduler),
            background = executor,
        )
        val lease = manager.create(sessionId)
        lease.start(
            request(
                "await-delivering",
                sessionId,
                config = backgroundConfig(handoffWait = 200, explicitAllowance = 10_000),
            ),
        )
        advanceUntilIdle()
        runCurrent()
        lease.awaitIdle()

        val messages = checkNotNull(lease.state.value.state).messages
        assertTrue(messages.none { it.role == MessageRole.RUNTIME })
        assertEquals(3, provider.requests.size)
        assertNull(lease.state.value.background?.pendingActivation)
        manager.close()
    }

    // S3-2: a terminal read result delivered without any Runtime notice also counts as
    // delivery — the model reads a handle from its receipt while the execution settles
    // mid-turn.
    @Test
    fun terminalReadResultCountsAsDelivery() = runTest {
        val store = InMemoryBackgroundExecutionStore()
        val executor = executor(store)
        val sessionId = AgentSessionId("read-delivers")
        val search = DelayedTool("deep_lookup", 400, ToolBackgroundPolicy.ELIGIBLE)
        val provider = object : ProviderAdapter {
            override val key = "read-delivering"
            val requests = mutableListOf<ProviderRequest>()
            private var calls = 0
            override suspend fun generate(request: ProviderRequest): Flow<ProviderChunk> = flow {
                requests += request
                calls += 1
                when (calls) {
                    1 -> emit(
                        providerChunk(
                            toolCalls = listOf(toolCall("call-1", "deep_lookup")),
                            completed = true,
                        ),
                    )
                    2 -> {
                        // The execution settles while this response streams in; the model
                        // already knows the handle from the receipt.
                        delay(300)
                        val handle = request.messages
                            .flatMap { it.parts }
                            .filterIsInstance<ToolResultPart>()
                            .firstNotNullOf { it.backgroundReceipt?.handle }
                        emit(
                            providerChunk(
                                toolCalls = listOf(
                                    toolCall(
                                        "call-2",
                                        "read_background_result",
                                        """{"handle":"$handle"}""",
                                    ),
                                ),
                                completed = true,
                            ),
                        )
                    }
                    else -> emit(providerChunk(text = "done", completed = true))
                }
            }
        }
        val runner = runner(
            provider = provider,
            tools = listOf(search),
            background = executor,
        )
        val manager = DefaultAgentSessionManager(
            runner = runner,
            persistence = InMemoryAgentPersistence(),
            dispatcher = StandardTestDispatcher(testScheduler),
            background = executor,
        )
        val lease = manager.create(sessionId)
        lease.start(
            request(
                "read-delivering",
                sessionId,
                config = backgroundConfig(handoffWait = 200, explicitAllowance = 10_000),
            ),
        )
        advanceUntilIdle()
        runCurrent()
        lease.awaitIdle()

        val messages = checkNotNull(lease.state.value.state).messages
        assertTrue(messages.none { it.role == MessageRole.RUNTIME })
        assertEquals(3, provider.requests.size)
        assertNull(lease.state.value.background?.pendingActivation)
        manager.close()
    }

    // S3-3: a non-terminal retrieval leaves the record undelivered — settling later still
    // produces a pending activation.
    @Test
    fun nonTerminalReadDoesNotCountAsDelivery() = runTest {
        val store = InMemoryBackgroundExecutionStore()
        val executor = executor(store)
        val sessionId = AgentSessionId("read-running")
        val tool = DelayedTool("deep_lookup", 400, ToolBackgroundPolicy.ELIGIBLE)
        val provider = object : ProviderAdapter {
            override val key = "read-running"
            val requests = mutableListOf<ProviderRequest>()
            private var calls = 0
            override suspend fun generate(request: ProviderRequest): Flow<ProviderChunk> = flow {
                requests += request
                calls += 1
                when (calls) {
                    1 -> emit(
                        providerChunk(
                            toolCalls = listOf(toolCall("call-1", "deep_lookup")),
                            completed = true,
                        ),
                    )
                    2 -> {
                        val handle = request.messages
                            .flatMap { it.parts }
                            .filterIsInstance<ToolResultPart>()
                            .firstNotNullOf { it.backgroundReceipt?.handle }
                        emit(
                            providerChunk(
                                toolCalls = listOf(
                                    toolCall(
                                        "call-2",
                                        "read_background_result",
                                        """{"handle":"$handle"}""",
                                    ),
                                ),
                                completed = true,
                            ),
                        )
                    }
                    else -> emit(providerChunk(text = "done", completed = true))
                }
            }
        }
        val runner = runner(provider = provider, tools = listOf(tool), background = executor)
        val manager = DefaultAgentSessionManager(
            runner = runner,
            persistence = InMemoryAgentPersistence(),
            dispatcher = StandardTestDispatcher(testScheduler),
            background = executor,
        )
        val lease = manager.create(sessionId)
        lease.start(
            request(
                "read-running",
                sessionId,
                config = backgroundConfig(handoffWait = 200, explicitAllowance = 10_000),
            ),
        )
        advanceUntilIdle()
        runCurrent()
        lease.awaitIdle()

        val messages = checkNotNull(lease.state.value.state).messages
        assertTrue(messages.none { it.role == MessageRole.RUNTIME })
        assertNotNull(lease.state.value.background?.pendingActivation)
        manager.close()
    }

    // S3-4: a zero wait allowance removes await_background_results from the declarations
    // and swaps the system-prompt section byte-for-byte.
    @Test
    fun zeroAllowanceDropsAwaitFromDeclarationsAndPrompt() = runTest {
        val store = InMemoryBackgroundExecutionStore()
        val executor = executor(store)
        val sessionId = AgentSessionId("no-wait")
        executor.register(sessionId, NoopListener)
        val tool = DelayedTool("slow_lookup", 50, ToolBackgroundPolicy.ELIGIBLE)
        val provider = scriptedProvider("no-wait", toolCall("call-1", "slow_lookup"))
        val runner = runner(provider = provider, tools = listOf(tool), background = executor)
        runner.run(
            request(
                "no-wait",
                sessionId,
                config = backgroundConfig(handoffWait = 2_000, explicitAllowance = 0),
                systemPrompt = "host prompt",
            ),
        ).toList()

        assertEquals(
            requestTools + listOf(
                "read_background_result",
                "cancel_background_executions",
                "finish_without_reply",
            ),
            provider.requests.first().tools.map { it.name },
        )
        val system = provider.requests.first().messages
            .single { it.role == MessageRole.SYSTEM }
            .parts.filterIsInstance<TextPart>().single().text
        assertEquals(
            "host prompt\n\n" + BackgroundToolInstructions.SYSTEM_PROMPT_SECTION_NO_WAIT,
            system,
        )
        executor.close()
    }

    // S3-5: an await call made while it is not declared falls through to the unknown-Tool
    // rejection like any other name the model was not offered.
    @Test
    fun undeclaredAwaitIsRejectedLikeAnyUnknownTool() = runTest {
        val store = InMemoryBackgroundExecutionStore()
        val executor = executor(store)
        val sessionId = AgentSessionId("await-undeclared")
        val tool = DelayedTool("deep_lookup", 100, ToolBackgroundPolicy.ELIGIBLE)
        val provider = object : ProviderAdapter {
            override val key = "await-undeclared"
            private var calls = 0
            override suspend fun generate(request: ProviderRequest): Flow<ProviderChunk> = flow {
                calls += 1
                when (calls) {
                    1 -> emit(
                        providerChunk(
                            toolCalls = listOf(
                                toolCall(
                                    "call-1",
                                    "await_background_results",
                                    """{"handles":["bg_any"],"max_wait_ms":100}""",
                                ),
                            ),
                            completed = true,
                        ),
                    )
                    else -> emit(providerChunk(text = "done", completed = true))
                }
            }
        }
        val runner = runner(provider = provider, tools = listOf(tool), background = executor)
        val manager = DefaultAgentSessionManager(
            runner = runner,
            persistence = InMemoryAgentPersistence(),
            dispatcher = StandardTestDispatcher(testScheduler),
            background = executor,
        )
        val lease = manager.create(sessionId)
        lease.start(
            request(
                "await-undeclared",
                sessionId,
                config = backgroundConfig(handoffWait = 200, explicitAllowance = 0),
            ),
        )
        advanceUntilIdle()
        runCurrent()
        lease.awaitIdle()

        val rejected = checkNotNull(lease.state.value.state).messages
            .filter { it.role == MessageRole.TOOL }
            .flatMap { it.parts.filterIsInstance<ToolResultPart>() }
            .single { it.toolCallId == "call-1" }
        assertTrue(rejected.isError)
        assertEquals("Tool not advertised", rejected.displayText)
        manager.close()
    }

    // S3-6: the CONTINUATION restriction line lists only the declared controls.
    @Test
    fun continuationRestrictionLineListsOnlyDeclaredControls() {
        val entries = listOf(
            RuntimeNoticeEntry(
                handle = "bg_x",
                toolName = "deep_lookup",
                callId = "call-1",
                outcome = BackgroundOutcome.SUCCEEDED,
                resultVersion = 1,
            ),
        )
        val withoutAwait = renderRuntimeNotice(
            entries,
            RuntimeNoticeDelivery.CONTINUATION,
            BackgroundConsumptionPolicy.LATEST_CONTEXT,
            businessToolsAllowed = false,
            awaitDeclared = false,
        )
        assertTrue(
            "only these Tools are available: read_background_result, " +
                "cancel_background_executions, finish_without_reply." in withoutAwait,
        )
        assertTrue("await_background_results," !in withoutAwait)

        val withAwait = renderRuntimeNotice(
            entries,
            RuntimeNoticeDelivery.CONTINUATION,
            BackgroundConsumptionPolicy.LATEST_CONTEXT,
            businessToolsAllowed = false,
            awaitDeclared = true,
        )
        assertTrue(
            "read_background_result, await_background_results, " +
                "cancel_background_executions, finish_without_reply." in withAwait,
        )
    }

    // S3-7: an await that finds no remaining allowance returns immediately with the
    // exhaustion marker.
    @Test
    fun exhaustedAllowanceAwaitReportsTheExhaustion() = runTest {
        val store = InMemoryBackgroundExecutionStore()
        val executor = executor(store)
        val sessionId = AgentSessionId("await-exhausted")
        val tool = DelayedTool("deep_lookup", 60_000, ToolBackgroundPolicy.ELIGIBLE)
        val provider = object : ProviderAdapter {
            override val key = "await-exhausted"
            val requests = mutableListOf<ProviderRequest>()
            private var calls = 0
            override suspend fun generate(request: ProviderRequest): Flow<ProviderChunk> = flow {
                requests += request
                calls += 1
                when (calls) {
                    1 -> emit(
                        providerChunk(
                            toolCalls = listOf(toolCall("call-1", "deep_lookup")),
                            completed = true,
                        ),
                    )
                    2 -> {
                        val handle = request.messages
                            .flatMap { it.parts }
                            .filterIsInstance<ToolResultPart>()
                            .firstNotNullOf { it.backgroundReceipt?.handle }
                        emit(
                            providerChunk(
                                toolCalls = listOf(
                                    toolCall(
                                        "call-2",
                                        "await_background_results",
                                        """{"handles":["$handle"],"max_wait_ms":50}""",
                                    ),
                                ),
                                completed = true,
                            ),
                        )
                    }
                    3 -> {
                        val handle = request.messages
                            .flatMap { it.parts }
                            .filterIsInstance<ToolResultPart>()
                            .firstNotNullOf { it.backgroundReceipt?.handle }
                        emit(
                            providerChunk(
                                toolCalls = listOf(
                                    toolCall(
                                        "call-3",
                                        "await_background_results",
                                        """{"handles":["$handle"],"max_wait_ms":50}""",
                                    ),
                                ),
                                completed = true,
                            ),
                        )
                    }
                    else -> emit(providerChunk(text = "done", completed = true))
                }
            }
        }
        val runner = runner(provider = provider, tools = listOf(tool), background = executor)
        val manager = DefaultAgentSessionManager(
            runner = runner,
            persistence = InMemoryAgentPersistence(),
            dispatcher = StandardTestDispatcher(testScheduler),
            background = executor,
        )
        val lease = manager.create(sessionId)
        lease.start(
            request(
                "await-exhausted",
                sessionId,
                config = backgroundConfig(handoffWait = 200, explicitAllowance = 50),
            ),
        )
        advanceUntilIdle()
        runCurrent()
        lease.awaitIdle()

        val results = checkNotNull(lease.state.value.state).messages
            .filter { it.role == MessageRole.TOOL }
            .flatMap { it.parts.filterIsInstance<ToolResultPart>() }
        val firstAwait = results.single { it.toolCallId == "call-2" }
        assertFalse(firstAwait.isError)
        assertEquals(
            JsonPrimitive(true),
            firstAwait.result.jsonObject["wait_allowance_exhausted"],
        )
        assertEquals(
            "No waiting time is left in this Run. Answer now with what you have; " +
                "a runtime notice will report results that arrive later.",
            firstAwait.result.jsonObject["note"]!!.jsonPrimitive.content,
        )
        assertEquals(50L, firstAwait.result.jsonObject["waited_ms"]!!.jsonPrimitive.long)
        // A later await after exhaustion is refused outright.
        val secondAwait = results.single { it.toolCallId == "call-3" }
        assertTrue(secondAwait.isError)
        assertEquals(
            JsonPrimitive(true),
            secondAwait.result.jsonObject["wait_allowance_exhausted"],
        )
        manager.close()
    }

    // S4-1: the receipt note tells the model plainly what to do — with and without the
    // wait control.
    @Test
    fun receiptNoteFollowsTheWaitAllowance() = runTest {
        val store = InMemoryBackgroundExecutionStore()
        val executor = executor(store)

        suspend fun receiptJson(explicitAllowance: Long): JsonObject {
            val sessionId = AgentSessionId("receipt-note-$explicitAllowance")
            executor.register(sessionId, NoopListener)
            val tool = DelayedTool("slow_lookup", 60_000, ToolBackgroundPolicy.ELIGIBLE)
            val provider = scriptedProvider(
                "receipt-note-$explicitAllowance",
                toolCall("call-1", "slow_lookup"),
            )
            val runner = runner(
                provider = provider,
                tools = listOf(tool),
                background = executor,
            )
            val events = runner.run(
                request(
                    "receipt-note-$explicitAllowance",
                    sessionId,
                    config = backgroundConfig(
                        handoffWait = 100,
                        explicitAllowance = explicitAllowance,
                    ),
                ),
            ).toList()
            val state = events.filterIsInstance<AgentEvent.Completed>().single().state
            return state.messages
                .single { it.role == MessageRole.TOOL }
                .parts.filterIsInstance<ToolResultPart>().single().result.jsonObject
        }

        assertEquals(
            "This work is still running in the background. You do not have its result " +
                "yet. Answer now with what you already have, or call " +
                "await_background_results if you need the result first. Do not cancel it " +
                "or call the Tool again for it; a runtime notice will report the result " +
                "if you answer first.",
            receiptJson(10_000)["note"]!!.jsonPrimitive.content,
        )
        assertEquals(
            "This work is still running in the background. You do not have its result " +
                "yet. Answer now with what you already have; a runtime notice will " +
                "report the result when it arrives. Do not cancel it or call the Tool " +
                "again for it.",
            receiptJson(0)["note"]!!.jsonPrimitive.content,
        )
        executor.close()
    }

    // S4-2: a read against a still-running record carries the matching not-ready note.
    @Test
    fun runningReadNoteFollowsTheWaitAllowance() = runTest {
        val store = InMemoryBackgroundExecutionStore()
        val executor = executor(store)

        suspend fun readJson(explicitAllowance: Long): JsonObject {
            val sessionId = AgentSessionId("read-note-$explicitAllowance")
            val tool = DelayedTool("deep_lookup", 60_000, ToolBackgroundPolicy.ELIGIBLE)
            val provider = object : ProviderAdapter {
                override val key = "read-note-$explicitAllowance"
                private var calls = 0
                override suspend fun generate(
                    request: ProviderRequest,
                ): Flow<ProviderChunk> = flow {
                    calls += 1
                    when (calls) {
                        1 -> emit(
                            providerChunk(
                                toolCalls = listOf(toolCall("call-1", "deep_lookup")),
                                completed = true,
                            ),
                        )
                        2 -> {
                            val handle = request.messages
                                .flatMap { it.parts }
                                .filterIsInstance<ToolResultPart>()
                                .firstNotNullOf { it.backgroundReceipt?.handle }
                            emit(
                                providerChunk(
                                    toolCalls = listOf(
                                        toolCall(
                                            "call-2",
                                            "read_background_result",
                                            """{"handle":"$handle"}""",
                                        ),
                                    ),
                                    completed = true,
                                ),
                            )
                        }
                        else -> emit(providerChunk(text = "done", completed = true))
                    }
                }
            }
            val runner = runner(provider = provider, tools = listOf(tool), background = executor)
            val manager = DefaultAgentSessionManager(
                runner = runner,
                persistence = InMemoryAgentPersistence(),
                dispatcher = StandardTestDispatcher(testScheduler),
                background = executor,
            )
            val lease = manager.create(sessionId)
            lease.start(
                request(
                    "read-note-$explicitAllowance",
                    sessionId,
                    config = backgroundConfig(
                        handoffWait = 100,
                        explicitAllowance = explicitAllowance,
                    ),
                ),
            )
            advanceUntilIdle()
            runCurrent()
            lease.awaitIdle()
            val part = checkNotNull(lease.state.value.state).messages
                .filter { it.role == MessageRole.TOOL }
                .flatMap { it.parts.filterIsInstance<ToolResultPart>() }
                .single { it.toolCallId == "call-2" }
            return part.result.jsonObject
        }

        val running = readJson(10_000)
        assertEquals("running", running["status"]!!.jsonPrimitive.content)
        assertEquals(
            "This result is not ready yet. Answer now with what you already have, or " +
                "call await_background_results if you need it first. Do not cancel it " +
                "or call the Tool again for it.",
            running["note"]!!.jsonPrimitive.content,
        )
        val noWait = readJson(0)
        assertEquals("running", noWait["status"]!!.jsonPrimitive.content)
        assertEquals(
            "This result is not ready yet. Answer now with what you already have; a " +
                "runtime notice will report the result when it arrives. Do not cancel " +
                "it or call the Tool again for it.",
            noWait["note"]!!.jsonPrimitive.content,
        )
        executor.close()
    }

    // S4-3: an await that ends with work still running and allowance left carries the
    // not-ready note instead of the exhausted one.
    @Test
    fun awaitWithWorkStillRunningCarriesTheNotReadyNote() = runTest {
        val store = InMemoryBackgroundExecutionStore()
        val executor = executor(store)
        val sessionId = AgentSessionId("await-not-ready")
        val tool = DelayedTool("deep_lookup", 60_000, ToolBackgroundPolicy.ELIGIBLE)
        val provider = object : ProviderAdapter {
            override val key = "await-not-ready"
            private var calls = 0
            override suspend fun generate(request: ProviderRequest): Flow<ProviderChunk> = flow {
                calls += 1
                when (calls) {
                    1 -> emit(
                        providerChunk(
                            toolCalls = listOf(toolCall("call-1", "deep_lookup")),
                            completed = true,
                        ),
                    )
                    2 -> {
                        val handle = request.messages
                            .flatMap { it.parts }
                            .filterIsInstance<ToolResultPart>()
                            .firstNotNullOf { it.backgroundReceipt?.handle }
                        emit(
                            providerChunk(
                                toolCalls = listOf(
                                    toolCall(
                                        "call-2",
                                        "await_background_results",
                                        """{"handles":["$handle"],"max_wait_ms":50}""",
                                    ),
                                ),
                                completed = true,
                            ),
                        )
                    }
                    else -> emit(providerChunk(text = "done", completed = true))
                }
            }
        }
        val runner = runner(provider = provider, tools = listOf(tool), background = executor)
        val manager = DefaultAgentSessionManager(
            runner = runner,
            persistence = InMemoryAgentPersistence(),
            dispatcher = StandardTestDispatcher(testScheduler),
            background = executor,
        )
        val lease = manager.create(sessionId)
        lease.start(
            request(
                "await-not-ready",
                sessionId,
                config = backgroundConfig(handoffWait = 100, explicitAllowance = 10_000),
            ),
        )
        advanceUntilIdle()
        runCurrent()
        lease.awaitIdle()

        val awaitResult = checkNotNull(lease.state.value.state).messages
            .filter { it.role == MessageRole.TOOL }
            .flatMap { it.parts.filterIsInstance<ToolResultPart>() }
            .single { it.toolCallId == "call-2" }
        val json = awaitResult.result.jsonObject
        assertNull(json["wait_allowance_exhausted"])
        assertEquals(
            "Some results are not ready yet. Answer now with what you already have; a " +
                "runtime notice will report them when they arrive. Do not cancel them " +
                "or call the Tool again for them.",
            json["note"]!!.jsonPrimitive.content,
        )
        manager.close()
    }

    // S5-1: a single-handle settled await carries the record's stored result fields exactly
    // like a direct read — content, origin, contextHints, and the background metadata.
    @Test
    fun singleHandleAwaitCarriesTheReadResultFields() = runTest {
        val store = InMemoryBackgroundExecutionStore()
        val executor = executor(store)
        val sessionId = AgentSessionId("await-carries")
        executor.register(sessionId, NoopListener)
        val stored = seededResult().copy(
            origin = ToolOrigin("src-1", "source one", "tool-1", "tool one"),
            contextHints = ToolResultContextHints(retention = ToolResultRetention.PINNED),
        )
        store.upsert(
            seededRecord(sessionId, "bg_settled", outcome = BackgroundOutcome.SUCCEEDED)
                .copy(result = stored),
        )
        val provider = scriptedProvider(
            "await-carries",
            toolCall(
                "await-1",
                "await_background_results",
                """{"handles":["bg_settled"],"max_wait_ms":50}""",
            ),
            toolCall("read-1", "read_background_result", """{"handle":"bg_settled"}"""),
        )
        val runner = runner(provider = provider, tools = emptyList(), background = executor)
        val events = runner.run(
            request("await-carries", sessionId, config = backgroundConfig()),
        ).toList()
        val parts = events.filterIsInstance<AgentEvent.Completed>().single().state.messages
            .single { it.role == MessageRole.TOOL }.parts.filterIsInstance<ToolResultPart>()
        val awaitPart = parts.single { it.toolCallId == "await-1" }
        val readPart = parts.single { it.toolCallId == "read-1" }
        assertFalse(awaitPart.isError)
        assertEquals(stored.content, awaitPart.content)
        assertEquals(stored.origin, awaitPart.origin)
        assertEquals(stored.contextHints, awaitPart.contextHints)
        // The single-handle await keeps the read result's identity metadata and adds its
        // settled-results slice.
        assertEquals(
            JsonObject(
                readPart.metadata.toMap() + (
                    "magrathea.background.results" to
                        awaitPart.metadata.getValue("magrathea.background.results")
                    ),
            ),
            awaitPart.metadata,
        )
        val singleEntry = awaitPart.metadata
            .getValue("magrathea.background.results").jsonArray.single().jsonObject
        assertEquals("bg_settled", singleEntry["handle"]?.jsonPrimitive?.content)
        assertEquals(0, singleEntry["content_offset"]?.jsonPrimitive?.long)
        assertEquals(
            stored.content.size.toLong(),
            singleEntry["content_count"]?.jsonPrimitive?.long,
        )
        val json = awaitPart.result.jsonObject
        assertNotNull(json["results"])
        assertNotNull(json["waited_ms"])
        executor.close()
    }

    // A multi-handle await attributes each settled record's slice in handle order: every
    // entry carries its own citations/origin/display text, while the top-level metadata
    // stays without citations.
    @Test
    fun multiHandleAwaitAttributesEachSettledResult() = runTest {
        val store = InMemoryBackgroundExecutionStore()
        val executor = executor(store)
        val sessionId = AgentSessionId("await-multi")
        executor.register(sessionId, NoopListener)
        val firstResult = seededResult().copy(
            content = listOf(
                ToolResultTextContent("first text", setOf(ToolResultAudience.MODEL)),
            ),
            origin = ToolOrigin("src-1", "source one", "tool-1", "tool one"),
            metadata = buildJsonObject {
                putJsonArray("citations") {
                    add(
                        buildJsonObject {
                            put("title", JsonPrimitive("Atlas"))
                            put("url", JsonPrimitive("https://atlas"))
                            put("snippet", JsonPrimitive("atlas note"))
                        },
                    )
                }
            },
        )
        val secondResult = seededResult().copy(
            content = listOf(
                ToolResultTextContent("second text", setOf(ToolResultAudience.MODEL)),
                ToolResultImageContent(
                    source = RemoteToolImageSource("https://images.example.com/beta.jpg"),
                    mimeType = "image/jpeg",
                    title = "Beta image",
                    audiences = setOf(ToolResultAudience.USER),
                ),
            ),
            displayText = "9 results",
            metadata = buildJsonObject {
                putJsonArray("citations") {
                    add(
                        buildJsonObject {
                            put("title", JsonPrimitive("Beta"))
                            put("url", JsonPrimitive("https://beta"))
                            put("snippet", JsonPrimitive("beta note"))
                        },
                    )
                }
            },
        )
        store.upsert(
            seededRecord(sessionId, "bg_first", outcome = BackgroundOutcome.SUCCEEDED)
                .copy(result = firstResult),
        )
        store.upsert(
            seededRecord(sessionId, "bg_second", outcome = BackgroundOutcome.SUCCEEDED)
                .copy(result = secondResult),
        )
        val provider = scriptedProvider(
            "await-multi",
            toolCall(
                "await-1",
                "await_background_results",
                """{"handles":["bg_first","bg_second"],"max_wait_ms":50}""",
            ),
        )
        val runner = runner(provider = provider, tools = emptyList(), background = executor)
        val events = runner.run(
            request("await-multi", sessionId, config = backgroundConfig()),
        ).toList()

        val awaitPart = events.filterIsInstance<AgentEvent.Completed>().single().state.messages
            .single { it.role == MessageRole.TOOL }.parts.filterIsInstance<ToolResultPart>()
            .single { it.toolCallId == "await-1" }
        assertEquals(firstResult.content + secondResult.content, awaitPart.content)
        assertNull(awaitPart.metadata["citations"])
        val entries = awaitPart.metadata
            .getValue("magrathea.background.results").jsonArray
        assertEquals(2, entries.size)

        entries[0].jsonObject.let { entry ->
            assertEquals("bg_first", entry["handle"]?.jsonPrimitive?.content)
            assertEquals("exec-bg_first", entry["execution_id"]?.jsonPrimitive?.content)
            assertEquals("deep_lookup", entry["tool_name"]?.jsonPrimitive?.content)
            assertEquals(0, entry["content_offset"]?.jsonPrimitive?.long)
            assertEquals(1, entry["content_count"]?.jsonPrimitive?.long)
            assertEquals(firstResult.metadata, entry["metadata"]?.jsonObject)
            assertEquals(
                Json.encodeToJsonElement(ToolOrigin.serializer(), checkNotNull(firstResult.origin)),
                entry["origin"],
            )
            assertNull(entry["display_text"])
        }
        entries[1].jsonObject.let { entry ->
            assertEquals("bg_second", entry["handle"]?.jsonPrimitive?.content)
            assertEquals("exec-bg_second", entry["execution_id"]?.jsonPrimitive?.content)
            assertEquals(1, entry["content_offset"]?.jsonPrimitive?.long)
            assertEquals(2, entry["content_count"]?.jsonPrimitive?.long)
            assertEquals(secondResult.metadata, entry["metadata"]?.jsonObject)
            assertEquals("9 results", entry["display_text"]?.jsonPrimitive?.content)
            assertNull(entry["origin"])
        }
        executor.close()
    }

    // S5-2: a second read of the same still-running handle inside one Run is refused
    // instead of returned again.
    @Test
    fun repeatedPendingReadIsRefused() = runTest {
        val store = InMemoryBackgroundExecutionStore()
        val executor = executor(store)
        val sessionId = AgentSessionId("read-poll")
        val tool = DelayedTool("deep_lookup", 60_000, ToolBackgroundPolicy.ELIGIBLE)
        val provider = object : ProviderAdapter {
            override val key = "read-poll"
            private var calls = 0
            override suspend fun generate(request: ProviderRequest): Flow<ProviderChunk> = flow {
                calls += 1
                when (calls) {
                    1 -> emit(
                        providerChunk(
                            toolCalls = listOf(toolCall("call-1", "deep_lookup")),
                            completed = true,
                        ),
                    )
                    2, 3 -> {
                        val handle = request.messages
                            .flatMap { it.parts }
                            .filterIsInstance<ToolResultPart>()
                            .firstNotNullOf { it.backgroundReceipt?.handle }
                        emit(
                            providerChunk(
                                toolCalls = listOf(
                                    toolCall(
                                        "call-$calls",
                                        "read_background_result",
                                        """{"handle":"$handle"}""",
                                    ),
                                ),
                                completed = true,
                            ),
                        )
                    }
                    else -> emit(providerChunk(text = "done", completed = true))
                }
            }
        }
        val runner = runner(provider = provider, tools = listOf(tool), background = executor)
        val manager = DefaultAgentSessionManager(
            runner = runner,
            persistence = InMemoryAgentPersistence(),
            dispatcher = StandardTestDispatcher(testScheduler),
            background = executor,
        )
        val lease = manager.create(sessionId)
        lease.start(
            request(
                "read-poll",
                sessionId,
                config = backgroundConfig(handoffWait = 100, explicitAllowance = 0),
            ),
        )
        advanceUntilIdle()
        runCurrent()
        lease.awaitIdle()

        val handle = store.load(sessionId).records.single().handle
        val parts = checkNotNull(lease.state.value.state).messages
            .filter { it.role == MessageRole.TOOL }
            .flatMap { it.parts.filterIsInstance<ToolResultPart>() }
        val first = parts.single { it.toolCallId == "call-2" }
        assertFalse(first.isError)
        assertEquals("running", first.result.jsonObject["status"]!!.jsonPrimitive.content)
        val second = parts.single { it.toolCallId == "call-3" }
        assertTrue(second.isError)
        val json = second.result.jsonObject
        assertEquals(handle, json["handle"]!!.jsonPrimitive.content)
        assertEquals("running", json["status"]!!.jsonPrimitive.content)
        assertEquals(
            "Already checked: this result is still not ready. Stop checking and answer " +
                "now; a runtime notice will report it when it arrives.",
            json["error"]!!.jsonPrimitive.content,
        )
        manager.close()
    }

    // S5-3: an await after the allowance was already exhausted returns an error instead of
    // another exhausted payload.
    @Test
    fun awaitAfterExhaustionIsRefused() = runTest {
        val store = InMemoryBackgroundExecutionStore()
        val executor = executor(store)
        val sessionId = AgentSessionId("await-poll")
        val tool = DelayedTool("deep_lookup", 60_000, ToolBackgroundPolicy.ELIGIBLE)
        val provider = object : ProviderAdapter {
            override val key = "await-poll"
            private var calls = 0
            override suspend fun generate(request: ProviderRequest): Flow<ProviderChunk> = flow {
                calls += 1
                when (calls) {
                    1 -> emit(
                        providerChunk(
                            toolCalls = listOf(toolCall("call-1", "deep_lookup")),
                            completed = true,
                        ),
                    )
                    2, 3, 4 -> {
                        val handle = request.messages
                            .flatMap { it.parts }
                            .filterIsInstance<ToolResultPart>()
                            .firstNotNullOf { it.backgroundReceipt?.handle }
                        emit(
                            providerChunk(
                                toolCalls = listOf(
                                    toolCall(
                                        "call-$calls",
                                        "await_background_results",
                                        """{"handles":["$handle"],"max_wait_ms":50}""",
                                    ),
                                ),
                                completed = true,
                            ),
                        )
                    }
                    else -> emit(providerChunk(text = "done", completed = true))
                }
            }
        }
        val runner = runner(provider = provider, tools = listOf(tool), background = executor)
        val manager = DefaultAgentSessionManager(
            runner = runner,
            persistence = InMemoryAgentPersistence(),
            dispatcher = StandardTestDispatcher(testScheduler),
            background = executor,
        )
        val lease = manager.create(sessionId)
        lease.start(
            request(
                "await-poll",
                sessionId,
                config = backgroundConfig(handoffWait = 100, explicitAllowance = 50),
            ),
        )
        advanceUntilIdle()
        runCurrent()
        lease.awaitIdle()

        val parts = checkNotNull(lease.state.value.state).messages
            .filter { it.role == MessageRole.TOOL }
            .flatMap { it.parts.filterIsInstance<ToolResultPart>() }
        // The first await consumes the 50 ms allowance and reports the exhaustion itself;
        // every later await in the Run is refused outright.
        val waited = parts.single { it.toolCallId == "call-2" }
        assertFalse(waited.isError)
        assertEquals(
            JsonPrimitive(true),
            waited.result.jsonObject["wait_allowance_exhausted"],
        )
        val refused3 = parts.single { it.toolCallId == "call-3" }
        val refused4 = parts.single { it.toolCallId == "call-4" }
        for (refused in listOf(refused3, refused4)) {
            assertTrue(refused.isError)
            val json = refused.result.jsonObject
            assertEquals(
                "No waiting time is left in this Run. Stop waiting and answer now; a " +
                    "runtime notice will report results that arrive later.",
                json["error"]!!.jsonPrimitive.content,
            )
            assertEquals(JsonPrimitive(true), json["wait_allowance_exhausted"])
        }
        manager.close()
    }

    // S5-4: the polling guard counts only non-terminal reads — a read after the record
    // settles still returns the payload.
    @Test
    fun readAfterSettleReturnsThePayload() = runTest {
        val store = InMemoryBackgroundExecutionStore()
        val executor = executor(store)
        val sessionId = AgentSessionId("read-after-settle")
        val tool = DelayedTool("deep_lookup", 400, ToolBackgroundPolicy.ELIGIBLE)
        val provider = object : ProviderAdapter {
            override val key = "read-after-settle"
            private var calls = 0
            override suspend fun generate(request: ProviderRequest): Flow<ProviderChunk> = flow {
                calls += 1
                when (calls) {
                    1 -> emit(
                        providerChunk(
                            toolCalls = listOf(toolCall("call-1", "deep_lookup")),
                            completed = true,
                        ),
                    )
                    2 -> {
                        val handle = request.messages
                            .flatMap { it.parts }
                            .filterIsInstance<ToolResultPart>()
                            .firstNotNullOf { it.backgroundReceipt?.handle }
                        emit(
                            providerChunk(
                                toolCalls = listOf(
                                    toolCall(
                                        "call-2",
                                        "read_background_result",
                                        """{"handle":"$handle"}""",
                                    ),
                                ),
                                completed = true,
                            ),
                        )
                    }
                    3 -> {
                        delay(400)
                        val handle = request.messages
                            .flatMap { it.parts }
                            .filterIsInstance<ToolResultPart>()
                            .firstNotNullOf { it.backgroundReceipt?.handle }
                        emit(
                            providerChunk(
                                toolCalls = listOf(
                                    toolCall(
                                        "call-3",
                                        "read_background_result",
                                        """{"handle":"$handle"}""",
                                    ),
                                ),
                                completed = true,
                            ),
                        )
                    }
                    else -> emit(providerChunk(text = "done", completed = true))
                }
            }
        }
        val runner = runner(provider = provider, tools = listOf(tool), background = executor)
        val manager = DefaultAgentSessionManager(
            runner = runner,
            persistence = InMemoryAgentPersistence(),
            dispatcher = StandardTestDispatcher(testScheduler),
            background = executor,
        )
        val lease = manager.create(sessionId)
        lease.start(
            request(
                "read-after-settle",
                sessionId,
                config = backgroundConfig(handoffWait = 100, explicitAllowance = 0),
            ),
        )
        advanceUntilIdle()
        runCurrent()
        lease.awaitIdle()

        val parts = checkNotNull(lease.state.value.state).messages
            .filter { it.role == MessageRole.TOOL }
            .flatMap { it.parts.filterIsInstance<ToolResultPart>() }
        assertFalse(parts.single { it.toolCallId == "call-2" }.isError)
        assertEquals(
            "running",
            parts.single { it.toolCallId == "call-2" }
                .result.jsonObject["status"]!!.jsonPrimitive.content,
        )
        val settledRead = parts.single { it.toolCallId == "call-3" }
        assertFalse(settledRead.isError)
        val json = settledRead.result.jsonObject
        assertEquals("succeeded", json["status"]!!.jsonPrimitive.content)
        assertEquals(JsonPrimitive("result-deep_lookup"), json["result"])
        manager.close()
    }

    // S6-1: the settle-after-last-turn race — the record settles while the Provider streams
    // the Run's final answer, so no COALESCED notice can be appended; pendingActivation must
    // still surface when the Run completes.
    @Test
    fun settleDuringFinalAnswerSurfacesPendingActivation() = runTest {
        val store = InMemoryBackgroundExecutionStore()
        val executor = executor(store)
        val sessionId = AgentSessionId("late-settle")
        val tool = DelayedTool("deep_lookup", 400, ToolBackgroundPolicy.ELIGIBLE)
        val provider = object : ProviderAdapter {
            override val key = "late-settle"
            private var calls = 0
            override suspend fun generate(request: ProviderRequest): Flow<ProviderChunk> = flow {
                calls += 1
                if (calls == 1) {
                    emit(
                        providerChunk(
                            toolCalls = listOf(toolCall("call-1", "deep_lookup")),
                            completed = true,
                        ),
                    )
                } else {
                    // The execution settles while this answer streams.
                    delay(400)
                    emit(providerChunk(text = "done", completed = true))
                }
            }
        }
        val runner = runner(provider = provider, tools = listOf(tool), background = executor)
        val manager = DefaultAgentSessionManager(
            runner = runner,
            persistence = InMemoryAgentPersistence(),
            dispatcher = StandardTestDispatcher(testScheduler),
            background = executor,
        )
        val lease = manager.create(sessionId)
        lease.start(
            request(
                "late-settle",
                sessionId,
                config = backgroundConfig(handoffWait = 100),
            ),
        )
        advanceUntilIdle()
        runCurrent()
        lease.awaitIdle()

        assertEquals(
            BackgroundOutcome.SUCCEEDED,
            store.load(sessionId).records.single().outcome,
        )
        val messages = checkNotNull(lease.state.value.state).messages
        assertTrue(messages.none { it.role == MessageRole.RUNTIME })
        assertNotNull(assertNotNull(lease.state.value.background).pendingActivation)
        manager.close()
    }

    // S6-2: a record that settles mid-Run is still delivered by a COALESCED notice and
    // acknowledged — pendingActivation stays null after completion.
    @Test
    fun midRunSettleDeliveredByNoticeStaysAcknowledged() = runTest {
        val store = InMemoryBackgroundExecutionStore()
        val executor = executor(store)
        val sessionId = AgentSessionId("mid-settle")
        val tool = DelayedTool("deep_lookup", 300, ToolBackgroundPolicy.ELIGIBLE)
        val blocker = DelayedTool("blocker", 50, ToolBackgroundPolicy.BLOCKING)
        val provider = object : ProviderAdapter {
            override val key = "mid-settle"
            val requests = mutableListOf<ProviderRequest>()
            private var calls = 0
            override suspend fun generate(request: ProviderRequest): Flow<ProviderChunk> = flow {
                requests += request
                calls += 1
                when (calls) {
                    1 -> emit(
                        providerChunk(
                            toolCalls = listOf(toolCall("call-1", "deep_lookup")),
                            completed = true,
                        ),
                    )
                    2 -> {
                        // The execution settles while this response streams.
                        delay(250)
                        emit(
                            providerChunk(
                                toolCalls = listOf(toolCall("call-2", "blocker")),
                                completed = true,
                            ),
                        )
                    }
                    else -> emit(providerChunk(text = "done", completed = true))
                }
            }
        }
        val runner = runner(
            provider = provider,
            tools = listOf(tool, blocker),
            background = executor,
        )
        val manager = DefaultAgentSessionManager(
            runner = runner,
            persistence = InMemoryAgentPersistence(),
            dispatcher = StandardTestDispatcher(testScheduler),
            background = executor,
        )
        val lease = manager.create(sessionId)
        lease.start(
            request(
                "mid-settle",
                sessionId,
                config = backgroundConfig(handoffWait = 100),
            ),
        )
        advanceUntilIdle()
        runCurrent()
        lease.awaitIdle()

        val messages = checkNotNull(lease.state.value.state).messages
        assertEquals(1, messages.count { it.role == MessageRole.RUNTIME })
        assertNull(assertNotNull(lease.state.value.background).pendingActivation)
        manager.close()
    }

    // ---- S3: durable restore across process restarts ----

    // A new executor+manager over the same store: RUNNING REPLAY_SAFE becomes QUEUED on
    // registration and resumeBackground() re-executes with the same execution ID.
    @Test
    fun restartRequeuesReplaySafeWorkForResume() = runTest {
        val sessionId = AgentSessionId("restart-replay")
        val store = InMemoryBackgroundExecutionStore()
        store.upsert(
            seededRecord(
                sessionId,
                "bg_live",
                state = BackgroundExecutionState.RUNNING,
            ),
        )
        store.putOrigin(
            BackgroundOriginContext(
                sessionId = sessionId,
                runId = AgentRunId("origin-run"),
                request = AgentRequest(
                    sessionId = sessionId,
                    messages = emptyList(),
                    model = ModelDescriptor(provider = "restart-replay", model = "model"),
                ),
            ),
        )
        val tool = CountingTool("deep_lookup", recoveryPolicy = ToolRecoveryPolicy.REPLAY_SAFE)
        val provider = scriptedProvider("restart-replay", toolCall("call-1", "deep_lookup"))
        val executor = executor(store)
        val runner = runner(provider = provider, tools = listOf(tool), background = executor)
        val manager = DefaultAgentSessionManager(
            runner = runner,
            persistence = InMemoryAgentPersistence(),
            dispatcher = StandardTestDispatcher(testScheduler),
            background = executor,
        )

        val lease = manager.create(sessionId)
        runCurrent()

        val requeued = store.load(sessionId).records.single()
        assertEquals(BackgroundExecutionState.QUEUED, requeued.state)
        assertNull(requeued.startedAtEpochMs)
        assertEquals(0, tool.executions)

        lease.resumeBackground()
        advanceUntilIdle()
        runCurrent()

        val settled = store.load(sessionId).records.single()
        assertEquals(BackgroundExecutionState.TERMINAL, settled.state)
        assertEquals(BackgroundOutcome.SUCCEEDED, settled.outcome)
        assertEquals("exec-bg_live", settled.executionId)
        assertEquals(1, tool.executions)
        manager.close()
    }

    // A FAIL_CLOSED record without a live job settles TERMINAL INTERRUPTED at registration.
    @Test
    fun restartInterruptsFailClosedWork() = runTest {
        val sessionId = AgentSessionId("restart-fail-closed")
        val store = InMemoryBackgroundExecutionStore()
        store.upsert(
            seededRecord(
                sessionId,
                "bg_closed",
                state = BackgroundExecutionState.RUNNING,
            ),
        )
        val tool = CountingTool("deep_lookup", recoveryPolicy = ToolRecoveryPolicy.FAIL_CLOSED)
        val provider = scriptedProvider("restart-closed", toolCall("call-1", "deep_lookup"))
        val executor = executor(store)
        val runner = runner(provider = provider, tools = listOf(tool), background = executor)
        val manager = DefaultAgentSessionManager(
            runner = runner,
            persistence = InMemoryAgentPersistence(),
            dispatcher = StandardTestDispatcher(testScheduler),
            background = executor,
        )

        manager.create(sessionId)
        runCurrent()

        val record = store.load(sessionId).records.single()
        assertEquals(BackgroundExecutionState.TERMINAL, record.state)
        assertEquals(BackgroundOutcome.INTERRUPTED, record.outcome)
        assertNotNull(record.settledAtEpochMs)
        assertEquals(0, tool.executions)
        manager.close()
    }

    // An unacknowledged TERMINAL record surfaces as a pending activation after restore.
    @Test
    fun restartProjectsPendingActivationForAnUnacknowledgedResult() = runTest {
        val sessionId = AgentSessionId("restart-activation")
        val store = InMemoryBackgroundExecutionStore()
        store.upsert(
            seededRecord(
                sessionId,
                "bg_done",
                state = BackgroundExecutionState.TERMINAL,
                outcome = BackgroundOutcome.SUCCEEDED,
            ),
        )
        val persistence = InMemoryAgentPersistence()
        persistence.commit(
            AgentSessionSnapshot(
                sessionId = sessionId,
                runId = AgentRunId("restored-run"),
                request = request(
                    "restart-activation",
                    sessionId,
                    config = backgroundConfig(),
                ),
                state = AgentStateSnapshot(messages = emptyList()),
                updatedAtEpochMs = 1,
            ),
            checkpoint = null,
        )
        val provider = scriptedProvider("restart-activation")
        val executor = executor(store)
        val runner = runner(
            provider = provider,
            tools = emptyList(),
            background = executor,
            persistence = persistence,
        )
        val manager = DefaultAgentSessionManager(
            runner = runner,
            persistence = persistence,
            dispatcher = StandardTestDispatcher(testScheduler),
            background = executor,
        )

        val lease = manager.acquire(sessionId)
        runCurrent()

        assertNotNull(assertNotNull(lease.state.value.background).pendingActivation)
        manager.close()
    }

    // A resumed Run whose STARTED journal entry already owns a stored record reuses the
    // receipt built from that record; the Tool is never executed a second time.
    @Test
    fun resumeWithStartedJournalAndStoredRecordReusesTheReceipt() = runTest {
        val sessionId = AgentSessionId("restart-receipt")
        val store = InMemoryBackgroundExecutionStore()
        val call = toolCall("call-crash", "deep_lookup")
        val assistant = AgentMessage(
            id = "assistant-crash",
            role = MessageRole.ASSISTANT,
            parts = listOf(call),
            createdAtEpochMs = 1,
        )
        val storedRecord = seededRecord(
            sessionId,
            "bg_crash",
            state = BackgroundExecutionState.RUNNING,
        ).copy(
            executionId = "exec-crash",
            originCallId = call.toolCallId,
            executionRequest = ToolExecutionRequest(
                sessionId = sessionId,
                runId = AgentRunId("crash-run"),
                executionId = "exec-crash",
                assistantMessage = assistant,
                toolCall = call,
            ),
        )
        store.upsert(storedRecord)
        val user = userMessage("look it up")
        val persistence = InMemoryAgentPersistence()
        persistence.commit(
            AgentSessionSnapshot(
                sessionId = sessionId,
                runId = AgentRunId("crash-run"),
                request = request(
                    "restart-receipt",
                    sessionId,
                    config = backgroundConfig(handoffWait = 100),
                ).copy(messages = listOf(user, assistant)),
                state = AgentStateSnapshot(
                    messages = listOf(user, assistant),
                    turn = 1,
                    pendingToolCalls = listOf(call),
                    status = saien.magrathea.core.AgentStatus.INTERRUPTED,
                    stopReason = StopReason.INTERRUPTED,
                ),
                interruption = saien.magrathea.core.AgentInterruption(
                    reason = saien.magrathea.core.AgentInterruptionReason.ORPHANED,
                    occurredAtEpochMs = 1,
                ),
                updatedAtEpochMs = 1,
            ),
            AgentCheckpoint(
                sessionId = sessionId,
                runId = AgentRunId("crash-run"),
                cursor = AgentResumeCursor(
                    turn = 1,
                    phase = AgentResumePhase.TOOLS_PENDING,
                ),
                state = AgentStateSnapshot(
                    messages = listOf(user, assistant),
                    turn = 1,
                    pendingToolCalls = listOf(call),
                    status = saien.magrathea.core.AgentStatus.WAITING_FOR_TOOLS,
                ),
                toolExecutions = listOf(
                    ToolExecutionRecord(
                        executionId = "exec-crash",
                        toolCallId = call.toolCallId,
                        toolName = call.toolName,
                        callOrdinal = 1,
                        state = ToolExecutionState.STARTED,
                    ),
                ),
            ),
        )
        val tool = CountingTool(
            "deep_lookup",
            recoveryPolicy = ToolRecoveryPolicy.REPLAY_SAFE,
            backgroundPolicy = ToolBackgroundPolicy.ELIGIBLE,
        )
        val provider = scriptedProvider("restart-receipt")
        val executor = executor(store)
        val runner = runner(
            provider = provider,
            tools = listOf(tool),
            background = executor,
            persistence = persistence,
        )
        val manager = DefaultAgentSessionManager(
            runner = runner,
            persistence = persistence,
            dispatcher = StandardTestDispatcher(testScheduler),
            background = executor,
        )

        val lease = manager.acquire(sessionId)
        runCurrent()
        // The Tool executed once before the crash; the resumed Run must not execute it again.
        tool.executions = 1
        lease.resume()
        advanceUntilIdle()
        runCurrent()
        lease.awaitIdle()

        val messages = checkNotNull(lease.state.value.state).messages
        val receiptPart = messages.filter { it.role == MessageRole.TOOL }
            .flatMap { it.parts.filterIsInstance<ToolResultPart>() }
            .single { it.toolCallId == call.toolCallId }
        assertEquals("bg_crash", receiptPart.backgroundReceipt?.handle)
        assertEquals("exec-crash", receiptPart.backgroundReceipt?.executionId)
        assertEquals(1, tool.executions)
        manager.close()
    }


    // ---- review fixes ----

    // RF-1: a Run deadline during the handoff wait cancels and releases the admitted
    // execution; no record is written and nothing stays live.
    @Test
    fun runTimeoutDuringHandoffWaitReleasesTheAdmission() = runTest {
        val store = InMemoryBackgroundExecutionStore()
        val executor = executor(store)
        val sessionId = AgentSessionId("orphan-timeout")
        executor.register(sessionId, NoopListener)
        val tool = GatedTool("deep_lookup", ToolBackgroundPolicy.ELIGIBLE)
        val provider = scriptedProvider("orphan-timeout", toolCall("call-1", "deep_lookup"))
        val persistence = InMemoryAgentPersistence()
        val runner = runner(
            provider = provider,
            tools = listOf(tool),
            background = executor,
            persistence = persistence,
        )

        val collected = async {
            runner.run(
                request(
                    "orphan-timeout",
                    sessionId,
                    config = backgroundConfig(handoffWait = 10_000),
                    runTimeoutMillis = 500,
                ),
            ).toList()
        }
        runCurrent()
        advanceTimeBy(600)
        runCurrent()
        val events = collected.await()

        assertTrue(
            events.any {
                it is AgentEvent.Failed && it.code == saien.magrathea.core.AgentFailureCode.TIMEOUT
            },
        )
        assertTrue(tool.cancelled)
        assertNull(executor.liveExecution(checkNotNull(tool.lastExecutionId)))
        assertTrue(store.load(sessionId).records.isEmpty())
        executor.close()
    }

    // RF-2: an interrupt during the handoff wait releases the admission; the resumed Run
    // re-admits the same execution ID instead of failing on a live duplicate.
    @Test
    fun interruptDuringHandoffWaitReleasesAndResumeReAdmits() = runTest {
        val store = InMemoryBackgroundExecutionStore()
        val executor = executor(store)
        val sessionId = AgentSessionId("orphan-interrupt")
        executor.register(sessionId, NoopListener)
        val tool = GatedTool(
            "deep_lookup",
            ToolBackgroundPolicy.ELIGIBLE,
            recoveryPolicy = ToolRecoveryPolicy.REPLAY_SAFE,
        )
        val provider = scriptedProvider("orphan-interrupt", toolCall("call-1", "deep_lookup"))
        val persistence = InMemoryAgentPersistence()
        val runner = runner(
            provider = provider,
            tools = listOf(tool),
            background = executor,
            persistence = persistence,
        )
        val collected = async {
            runner.run(
                request(
                    "orphan-interrupt",
                    sessionId,
                    config = backgroundConfig(handoffWait = 60_000),
                ),
            ).toList()
        }
        runCurrent()
        advanceTimeBy(50)
        runCurrent()

        val interruptJob = async { runner.interrupt(sessionId) }
        runCurrent()
        interruptJob.await()
        runCatching { collected.await() }
        runCurrent()

        assertTrue(tool.cancelled)
        val firstExecutionId = checkNotNull(tool.lastExecutionId)
        assertNull(executor.liveExecution(firstExecutionId))
        assertTrue(store.load(sessionId).records.isEmpty())

        tool.gate.complete(Unit)
        val resumed = async { runner.resume(sessionId).toList() }
        advanceUntilIdle()
        val completed = resumed.await().filterIsInstance<AgentEvent.Completed>().single()
        val part = completed.state.messages
            .filter { it.role == MessageRole.TOOL }
            .flatMap { it.parts.filterIsInstance<ToolResultPart>() }
            .single { it.toolCallId == "call-1" }
        assertNull(part.backgroundReceipt)
        assertEquals("result-deep_lookup", part.result.jsonPrimitive.content)
        assertEquals(2, tool.executions)
        assertNull(executor.liveExecution(firstExecutionId))
        executor.close()
    }

    // RF-3: a store failure thrown out of the tool batch takes the same cleanup: the live
    // admission is cancelled and released instead of orphaned.
    @Test
    fun storageFailureDuringHandoffReleasesTheAdmission() = runTest {
        val inner = InMemoryBackgroundExecutionStore()
        var failLoads = false
        val store = object : BackgroundExecutionStore by inner {
            override suspend fun load(sessionId: AgentSessionId): BackgroundSessionRecords {
                if (failLoads) throw IllegalStateException("store read failed")
                return inner.load(sessionId)
            }
        }
        val executor = executor(store)
        val sessionId = AgentSessionId("orphan-storage")
        executor.register(sessionId, NoopListener)
        val tool = GatedTool("deep_lookup", ToolBackgroundPolicy.ELIGIBLE)
        val provider = scriptedProvider("orphan-storage", toolCall("call-1", "deep_lookup"))
        val persistence = InMemoryAgentPersistence()
        val runner = runner(
            provider = provider,
            tools = listOf(tool),
            background = executor,
            persistence = persistence,
        )
        val collected = async {
            runner.run(
                request(
                    "orphan-storage",
                    sessionId,
                    config = backgroundConfig(handoffWait = 100),
                ),
            ).toList()
        }
        runCurrent()
        failLoads = true
        advanceTimeBy(200)
        runCurrent()
        val events = collected.await()

        assertTrue(events.any { it is AgentEvent.Failed })
        assertTrue(tool.cancelled)
        assertNull(executor.liveExecution(checkNotNull(tool.lastExecutionId)))
        failLoads = false
        assertTrue(store.load(sessionId).records.isEmpty())
        executor.close()
    }

    // RF-4: a throwing afterToolCall on a late settlement still settles TERMINAL ERROR,
    // notifies, and frees the admission instead of leaving the record RUNNING.
    @Test
    fun lateFinalizationFailureSettlesTerminalError() = runTest {
        val store = InMemoryBackgroundExecutionStore()
        val executor = executor(store)
        val sessionId = AgentSessionId("late-finalize")
        executor.register(sessionId, NoopListener)
        val tool = DelayedTool("deep_lookup", 200, ToolBackgroundPolicy.ELIGIBLE)
        val provider = scriptedProvider("late-finalize", toolCall("call-1", "deep_lookup"))
        val runner = runner(
            provider = provider,
            tools = listOf(tool),
            background = executor,
            interceptors = listOf(
                object : AgentInterceptor {
                    override suspend fun afterToolCall(
                        context: saien.magrathea.core.ToolRuntimeContext,
                        result: ToolExecutionResult,
                    ): ToolExecutionResult = throw IllegalStateException("interceptor failed")
                },
            ),
        )

        runner.run(
            request(
                "late-finalize",
                sessionId,
                config = backgroundConfig(handoffWait = 100),
            ),
        ).toList()
        advanceTimeBy(400)
        runCurrent()

        val record = store.load(sessionId).records.single()
        assertEquals(BackgroundExecutionState.TERMINAL, record.state)
        assertEquals(BackgroundOutcome.ERROR, record.outcome)
        assertEquals("Tool result finalization failed", record.result?.displayText)
        assertNull(executor.liveExecution(checkNotNull(tool.lastExecutionId)))
        assertFalse(
            deriveBackgroundSessionSnapshot(
                records = store.load(sessionId).records,
                request = null,
                state = null,
                isExecuting = false,
            ).requiresExecutionHost,
        )
        executor.close()
    }

    // RF-5: a store read failure inside registerBackground releases the Session's admitted
    // operation; the next acquire succeeds and delete/close complete.
    @Test
    fun registerBackgroundFailureReleasesTheSessionOperation() = runTest {
        val inner = InMemoryBackgroundExecutionStore()
        var failNextLoad = true
        val store = object : BackgroundExecutionStore by inner {
            override suspend fun load(sessionId: AgentSessionId): BackgroundSessionRecords {
                if (failNextLoad) {
                    failNextLoad = false
                    throw IllegalStateException("store read failed")
                }
                return inner.load(sessionId)
            }
        }
        val executor = executor(store)
        val provider = scriptedProvider("register-failure")
        val persistence = InMemoryAgentPersistence()
        val runner = runner(
            provider = provider,
            tools = emptyList(),
            background = executor,
            persistence = persistence,
        )
        val manager = DefaultAgentSessionManager(
            runner = runner,
            persistence = persistence,
            dispatcher = StandardTestDispatcher(testScheduler),
            background = executor,
        )
        val sessionId = AgentSessionId("register-failure")

        val failure = runCatching { manager.create(sessionId) }.exceptionOrNull()
        assertIs<IllegalStateException>(failure)

        // The slot and its admitted operation were released: a re-create succeeds, the
        // Session is usable, and delete/close complete.
        val lease = manager.create(sessionId)
        runCurrent()
        lease.start(request("register-failure", sessionId, config = backgroundConfig()))
        advanceUntilIdle()
        lease.awaitIdle()
        assertEquals(
            saien.magrathea.core.AgentStatus.COMPLETED,
            lease.state.value.state?.status,
        )
        manager.delete(sessionId)
        manager.close()
        executor.close()
    }

    // RF-6: a BUSY-rejected start never runs the regeneration fence; records and live jobs
    // survive unchanged.
    @Test
    fun busyStartLeavesBackgroundRecordsUntouched() = runTest {
        val store = InMemoryBackgroundExecutionStore()
        val executor = executor(store)
        val sessionId = AgentSessionId("busy-fence")
        val tool = GatedTool("deep_lookup", ToolBackgroundPolicy.ELIGIBLE)
        val provider = scriptedProvider("busy-fence", toolCall("call-1", "deep_lookup"))
        val persistence = InMemoryAgentPersistence()
        val runner = runner(
            provider = provider,
            tools = listOf(tool),
            background = executor,
            persistence = persistence,
        )
        val manager = DefaultAgentSessionManager(
            runner = runner,
            persistence = persistence,
            dispatcher = StandardTestDispatcher(testScheduler),
            background = executor,
        )
        val lease = manager.create(sessionId)
        lease.start(
            request("busy-fence", sessionId, config = backgroundConfig(handoffWait = 60_000)),
        )
        runCurrent()
        advanceTimeBy(50)
        runCurrent()

        // A stale record whose origin call is absent from the next request's messages.
        store.upsert(
            seededRecord(
                sessionId,
                "bg_stale",
                state = BackgroundExecutionState.RUNNING,
            ),
        )

        val code = rejectionCode {
            lease.start(
                request(
                    "busy-fence",
                    sessionId,
                    config = backgroundConfig(handoffWait = 60_000),
                ).copy(messages = listOf(userMessage("regenerate"))),
            )
        }
        assertEquals(AgentSessionErrorCode.BUSY, code)
        assertEquals(1, store.load(sessionId).records.size)
        tool.gate.complete(Unit)
        manager.close()
        executor.close()
    }

    // RF-7: sequential eligible calls share the Run's handoff budget; only their own wait
    // intervals count, so a blocking call's time is never charged.
    @Test
    fun sequentialEligibleCallsChargeTheSharedBudget() = runTest {
        val store = InMemoryBackgroundExecutionStore()
        val executor = executor(store)
        val sessionId = AgentSessionId("seq-budget")
        executor.register(sessionId, NoopListener)
        val tool = DelayedTool("deep_lookup", 600, ToolBackgroundPolicy.ELIGIBLE)
        val provider = object : ProviderAdapter {
            override val key = "seq-budget"
            private var calls = 0
            override suspend fun generate(request: ProviderRequest): Flow<ProviderChunk> = flow {
                calls += 1
                when (calls) {
                    1 -> emit(providerChunk(toolCalls = listOf(toolCall("call-1", "deep_lookup")), completed = true))
                    2 -> emit(providerChunk(toolCalls = listOf(toolCall("call-2", "deep_lookup")), completed = true))
                    else -> emit(providerChunk(text = "done", completed = true))
                }
            }
        }
        val runner = runner(provider = provider, tools = listOf(tool), background = executor)
        val collected = async {
            runner.run(
                request(
                    "seq-budget",
                    sessionId,
                    config = backgroundConfig(handoffWait = 1_000),
                    toolExecutionMode = ToolExecutionMode.SEQUENTIAL,
                ),
            ).toList()
        }
        runCurrent()
        advanceTimeBy(700)
        runCurrent()
        advanceTimeBy(600)
        runCurrent()
        val events = collected.await()

        val toolParts = events.filterIsInstance<AgentEvent.Completed>().single()
            .state.messages
            .filter { it.role == MessageRole.TOOL }
            .flatMap { it.parts.filterIsInstance<ToolResultPart>() }
        // call-1 waited 600 of the 1000 budget and returned its real result; call-2 had
        // 400 left, handed off at the deadline, and carries a receipt.
        val first = toolParts.single { it.toolCallId == "call-1" }
        assertNull(first.backgroundReceipt)
        assertEquals("result-deep_lookup", first.result.jsonPrimitive.content)
        val second = toolParts.single { it.toolCallId == "call-2" }
        assertNotNull(second.backgroundReceipt)
        assertEquals(
            1_000L,
            events.filterIsInstance<AgentEvent.Completed>().single()
                .state.foregroundToolWaitMillis,
        )
        executor.close()
    }

    @Test
    fun sequentialBlockingTimeDoesNotChargeTheHandoffBudget() = runTest {
        val store = InMemoryBackgroundExecutionStore()
        val executor = executor(store)
        val sessionId = AgentSessionId("seq-blocking")
        executor.register(sessionId, NoopListener)
        val blocking = DelayedTool("blocking_lookup", 300, ToolBackgroundPolicy.BLOCKING)
        val eligible = DelayedTool("deep_lookup", 600, ToolBackgroundPolicy.ELIGIBLE)
        val provider = scriptedProvider(
            "seq-blocking",
            toolCall("call-1", "blocking_lookup"),
            toolCall("call-2", "deep_lookup"),
        )
        val runner = runner(
            provider = provider,
            tools = listOf(blocking, eligible),
            background = executor,
        )
        val collected = async {
            runner.run(
                request(
                    "seq-blocking",
                    sessionId,
                    config = backgroundConfig(handoffWait = 1_000),
                    toolExecutionMode = ToolExecutionMode.SEQUENTIAL,
                ),
            ).toList()
        }
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()
        val events = collected.await()

        val state = events.filterIsInstance<AgentEvent.Completed>().single().state
        val parts = state.messages.filter { it.role == MessageRole.TOOL }
            .flatMap { it.parts.filterIsInstance<ToolResultPart>() }
        val eligiblePart = parts.single { it.toolCallId == "call-2" }
        // The 300ms blocking call did not reduce the eligible call's 1000ms window.
        assertNull(eligiblePart.backgroundReceipt)
        assertEquals("result-deep_lookup", eligiblePart.result.jsonPrimitive.content)
        assertEquals(600L, state.foregroundToolWaitMillis)
        executor.close()
    }

    // RF-8: a business Tool's isError result settles the background record as ERROR; the
    // stored error result stays readable and carries its error semantics.
    @Test
    fun errorResultSettlesBackgroundExecutionAsFailed() = runTest {
        val store = InMemoryBackgroundExecutionStore()
        val executor = executor(store)
        val sessionId = AgentSessionId("error-outcome")
        executor.register(sessionId, NoopListener)
        val tool = ErrorTool("deep_lookup", delayMillis = 200)
        val provider = scriptedProvider(
            "error-outcome",
            toolCall("call-1", "deep_lookup"),
            toolCall("read-1", "read_background_result", "{}"),
        )
        // The read call needs the real handle, so script the second turn lazily.
        val lateProvider = object : ProviderAdapter {
            override val key = "error-outcome"
            private var calls = 0
            override suspend fun generate(request: ProviderRequest): Flow<ProviderChunk> = flow {
                calls += 1
                when (calls) {
                    1 -> emit(
                        providerChunk(
                            toolCalls = listOf(toolCall("call-1", "deep_lookup")),
                            completed = true,
                        ),
                    )
                    2 -> {
                        val handle = request.messages
                            .flatMap { it.parts }
                            .filterIsInstance<ToolResultPart>()
                            .firstNotNullOf { it.backgroundReceipt?.handle }
                        delay(300)
                        emit(
                            providerChunk(
                                toolCalls = listOf(
                                    toolCall(
                                        "read-1",
                                        "read_background_result",
                                        """{"handle":"$handle"}""",
                                    ),
                                ),
                                completed = true,
                            ),
                        )
                    }
                    else -> emit(providerChunk(text = "done", completed = true))
                }
            }
        }
        val runner = runner(provider = lateProvider, tools = listOf(tool), background = executor)
        val collected = async {
            runner.run(
                request(
                    "error-outcome",
                    sessionId,
                    config = backgroundConfig(handoffWait = 100),
                ),
            ).toList()
        }
        runCurrent()
        advanceTimeBy(300)
        runCurrent()
        advanceTimeBy(50)
        runCurrent()
        val events = collected.await()

        val record = store.load(sessionId).records.single()
        assertEquals(BackgroundExecutionState.TERMINAL, record.state)
        assertEquals(BackgroundOutcome.ERROR, record.outcome)
        assertEquals(true, record.result?.isError)
        assertEquals("LOOKUP_FAILED", record.result?.userErrorCode)

        val readPart = events.filterIsInstance<AgentEvent.Completed>().single()
            .state.messages
            .filter { it.role == MessageRole.TOOL }
            .flatMap { it.parts.filterIsInstance<ToolResultPart>() }
            .single { it.toolCallId == "read-1" }
        assertFalse(readPart.isError)
        assertEquals("failed", readPart.result.jsonObject["status"]!!.jsonPrimitive.content)
        assertEquals(
            true,
            readPart.metadata[
                BackgroundToolInstructions.RESULT_IS_ERROR_METADATA_KEY,
            ]?.jsonPrimitive?.booleanOrNull,
        )
        assertEquals(
            "LOOKUP_FAILED",
            readPart.metadata[
                BackgroundToolInstructions.RESULT_ERROR_CODE_METADATA_KEY,
            ]?.jsonPrimitive?.content,
        )
        executor.close()
    }

    // RF-9: with background enabled, the four control names are reserved — a business Tool
    // declaring one fails validation instead of being shadowed.
    @Test
    fun reservedControlNamesRejectTheRequest() = runTest {
        val store = InMemoryBackgroundExecutionStore()
        val executor = executor(store)
        val sessionId = AgentSessionId("reserved-names")
        executor.register(sessionId, NoopListener)
        val provider = scriptedProvider("reserved-names")
        val runner = runner(provider = provider, tools = emptyList(), background = executor)
        val tools = listOf(
            ToolDefinition(
                name = "read_background_result",
                description = "business tool",
                schema = buildJsonObject { put("type", "object") },
            ),
        )
        val events = runner.run(
            request(
                "reserved-names",
                sessionId,
                config = backgroundConfig(),
                tools = tools,
            ),
        ).toList()
        val failed = events.filterIsInstance<AgentEvent.Failed>().single()
        assertEquals(saien.magrathea.core.AgentFailureCode.INVALID_STATE, failed.code)
        executor.close()
    }

    // ---- harness ----

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
