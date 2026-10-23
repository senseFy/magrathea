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
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import saien.magrathea.core.AgentEngineConfig
import saien.magrathea.core.AgentEvent
import saien.magrathea.core.AgentInterceptor
import saien.magrathea.core.AgentMessage
import saien.magrathea.core.AgentPersistence
import saien.magrathea.core.AgentRequest
import saien.magrathea.core.AgentRunId
import saien.magrathea.core.AgentSessionId
import saien.magrathea.core.BackgroundConsumptionPolicy
import saien.magrathea.core.BackgroundExecutionConfig
import saien.magrathea.core.BackgroundExecutionRecord
import saien.magrathea.core.BackgroundExecutionState
import saien.magrathea.core.BackgroundExecutionStore
import saien.magrathea.core.BackgroundOriginContext
import saien.magrathea.core.BackgroundOutcome
import saien.magrathea.core.ContextManager
import saien.magrathea.core.MessageRole
import saien.magrathea.core.ModelDescriptor
import saien.magrathea.core.RunActivation
import saien.magrathea.core.RunActivationSource
import saien.magrathea.core.RuntimeConfig
import saien.magrathea.core.RuntimeNoticeDelivery
import saien.magrathea.core.RuntimeNoticePart
import saien.magrathea.core.StopReason
import saien.magrathea.core.TextPart
import saien.magrathea.core.ToolBackgroundPolicy
import saien.magrathea.core.ToolCallPart
import saien.magrathea.core.ToolDefinition
import saien.magrathea.core.ToolExecutionMode
import saien.magrathea.core.ToolExecutionPermit
import saien.magrathea.core.ToolExecutionRequest
import saien.magrathea.core.ToolExecutionResult
import saien.magrathea.core.ToolExecutor
import saien.magrathea.core.ToolResultAudience
import saien.magrathea.core.ToolResultPart
import saien.magrathea.core.ToolResultRetention
import saien.magrathea.core.ToolResultTextContent
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
                    "This work is still running in the background. " +
                        "You do not have its result yet.",
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
            store.upsert(
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
    ) = DefaultAgentRunner(
        providerRegistry = InMemoryProviderRegistry(listOf(provider)),
        toolRegistry = InMemoryToolRegistry(tools),
        persistence = persistence,
        interceptors = interceptors,
        contextManager = contextManager,
        dispatcher = StandardTestDispatcher(testScheduler),
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

        override suspend fun execute(request: ToolExecutionRequest): ToolExecutionResult {
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
    ) : ToolExecutor {
        override val definition = ToolDefinition(
            name = name,
            description = "gated",
            schema = buildJsonObject { put("type", "object") },
        )
        val gate = CompletableDeferred<Unit>()
        var executions = 0

        override suspend fun execute(request: ToolExecutionRequest): ToolExecutionResult {
            executions += 1
            gate.await()
            return ToolExecutionResult(
                toolCallId = request.toolCall.toolCallId,
                toolName = request.toolCall.toolName,
                result = JsonPrimitive("result-${request.toolCall.toolName}"),
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

    private class CountingTool(name: String) : ToolExecutor {
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
