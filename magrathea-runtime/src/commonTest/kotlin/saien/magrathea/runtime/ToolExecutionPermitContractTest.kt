package saien.magrathea.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import saien.magrathea.core.AgentEngineConfig
import saien.magrathea.core.AgentEvent
import saien.magrathea.core.AgentMessage
import saien.magrathea.core.AgentRequest
import saien.magrathea.core.AgentRunId
import saien.magrathea.core.AgentSessionId
import saien.magrathea.core.MessageRole
import saien.magrathea.core.ModelDescriptor
import saien.magrathea.core.RuntimeConfig
import saien.magrathea.core.SharedToolExecutionPermit
import saien.magrathea.core.TextPart
import saien.magrathea.core.ToolCallPart
import saien.magrathea.core.ToolDefinition
import saien.magrathea.core.ToolExecutionMode
import saien.magrathea.core.ToolExecutionPermit
import saien.magrathea.core.ToolExecutionRequest
import saien.magrathea.core.ToolExecutionResult
import saien.magrathea.core.ToolExecutionState
import saien.magrathea.core.ToolExecutor
import saien.magrathea.core.UnlimitedToolExecutionPermit
import saien.magrathea.provider.api.InMemoryProviderRegistry
import saien.magrathea.provider.api.ProviderAdapter
import saien.magrathea.provider.api.ProviderChunk
import saien.magrathea.provider.api.ProviderRequest

class ToolExecutionPermitContractTest {
    @Test
    fun sharedPermitRejectsNonPositiveCapacity() {
        assertFailsWith<IllegalArgumentException> {
            SharedToolExecutionPermit(maxConcurrentExecutions = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            SharedToolExecutionPermit(maxConcurrentExecutions = -1)
        }
    }

    @Test
    fun queuedTimeDoesNotConsumeTheExecutorTimeout() = runTest {
        val permit = SharedToolExecutionPermit(maxConcurrentExecutions = 1)
        assertEquals(1, permit.maxConcurrentExecutions)
        var activeExecutions = 0
        var maxActiveExecutions = 0
        suspend fun delayedExecution(request: ToolExecutionRequest): ToolExecutionResult {
            activeExecutions += 1
            maxActiveExecutions = maxOf(maxActiveExecutions, activeExecutions)
            return try {
                delay(75)
                successfulResult(request)
            } finally {
                activeExecutions -= 1
            }
        }
        val first = permittedTool("queued_first", permit, ::delayedExecution)
        val second = permittedTool("queued_second", permit, ::delayedExecution)
        val provider = ToolThenDoneProvider(listOf(first.definition, second.definition))

        val events = runner(
            provider = provider,
            tools = listOf(first, second),
            dispatcher = StandardTestDispatcher(testScheduler),
        ).run(request(provider, listOf(first, second))).toList()

        val results = events.filterIsInstance<AgentEvent.ToolCompleted>().map { it.result }
        assertEquals(2, results.size)
        assertEquals(1, maxActiveExecutions)
        assertTrue(results.none(ToolExecutionResult::isError))
    }

    @Test
    fun executorsAreUnlimitedByDefault() = runTest {
        val bothStarted = CompletableDeferred<Unit>()
        var startedCount = 0
        fun barrierTool(name: String) = defaultPermitTool(name) { request ->
            startedCount += 1
            if (startedCount == 2) bothStarted.complete(Unit)
            bothStarted.await()
            successfulResult(request)
        }
        val first = barrierTool("default_first")
        val second = barrierTool("default_second")
        val provider = ToolThenDoneProvider(listOf(first.definition, second.definition))

        assertSame(UnlimitedToolExecutionPermit, first.executionPermit(executionRequest(first)))
        assertSame(UnlimitedToolExecutionPermit, second.executionPermit(executionRequest(second)))

        val events = runner(
            provider = provider,
            tools = listOf(first, second),
            dispatcher = StandardTestDispatcher(testScheduler),
        ).run(request(provider, listOf(first, second))).toList()

        assertEquals(2, startedCount)
        assertTrue(
            events.filterIsInstance<AgentEvent.ToolCompleted>()
                .none { it.result.isError },
        )
    }

    @Test
    fun admissionReceivesTheConcreteExecutionRequest() = runTest {
        var admittedRequest: ToolExecutionRequest? = null
        var executedRequest: ToolExecutionRequest? = null
        val tool = object : ToolExecutor {
            override val definition = definition("request_aware")

            override fun executionPermit(request: ToolExecutionRequest): ToolExecutionPermit {
                admittedRequest = request
                return UnlimitedToolExecutionPermit
            }

            override suspend fun execute(request: ToolExecutionRequest): ToolExecutionResult {
                executedRequest = request
                return successfulResult(request)
            }
        }

        val result = runSingleTool(tool, StandardTestDispatcher(testScheduler))

        assertFalse(result.isError)
        assertSame(admittedRequest, executedRequest)
    }

    @Test
    fun timeoutReleasesThePermitForReuse() = runTest {
        val permit = SharedToolExecutionPermit(maxConcurrentExecutions = 1)
        val timedOut = permittedTool("timed_out", permit) { request ->
            delay(101)
            successfulResult(request)
        }

        val timedOutResult = runSingleTool(timedOut, StandardTestDispatcher(testScheduler))
        assertTrue(timedOutResult.isError)

        val succeeding = permittedTool("after_timeout", permit, ::successfulResult)
        val succeedingResult = runSingleTool(succeeding, StandardTestDispatcher(testScheduler))
        assertFalse(succeedingResult.isError)
    }

    @Test
    fun failureReleasesThePermitForReuse() = runTest {
        val permit = SharedToolExecutionPermit(maxConcurrentExecutions = 1)
        val failing = permittedTool("failed", permit) {
            throw IllegalStateException("expected test failure")
        }

        val failedResult = runSingleTool(failing, StandardTestDispatcher(testScheduler))
        assertTrue(failedResult.isError)

        val succeeding = permittedTool("after_failure", permit, ::successfulResult)
        val succeedingResult = runSingleTool(succeeding, StandardTestDispatcher(testScheduler))
        assertFalse(succeedingResult.isError)
    }

    @Test
    fun cancellationReleasesThePermitForReuse() = runTest {
        val permit = SharedToolExecutionPermit(maxConcurrentExecutions = 1)
        val executionStarted = CompletableDeferred<Unit>()
        val cancelled = permittedTool("cancelled", permit) {
            executionStarted.complete(Unit)
            awaitCancellation()
        }
        val provider = ToolThenDoneProvider(listOf(cancelled.definition))
        val runner = runner(
            provider = provider,
            tools = listOf(cancelled),
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        val collection = launch {
            runner.run(request(provider, listOf(cancelled))).collect()
        }
        executionStarted.await()

        collection.cancelAndJoin()

        val succeeding = permittedTool("after_cancellation", permit, ::successfulResult)
        val succeedingResult = runSingleTool(succeeding, StandardTestDispatcher(testScheduler))
        assertFalse(succeedingResult.isError)
    }

    @Test
    fun cancellingAQueuedAcquisitionDoesNotReleaseOrExpandThePermit() = runTest {
        val gate = SharedToolExecutionPermit(maxConcurrentExecutions = 1)
        gate.acquire()
        val acquireStarted = CompletableDeferred<Unit>()
        val queuedPermit = object : ToolExecutionPermit {
            override suspend fun acquire() {
                acquireStarted.complete(Unit)
                gate.acquire()
            }

            override fun release() {
                gate.release()
            }
        }
        var executed = false
        val queued = permittedTool("cancelled_waiter", queuedPermit) { request ->
            executed = true
            successfulResult(request)
        }
        val provider = ToolThenDoneProvider(listOf(queued.definition))
        val collection = launch {
            runner(
                provider = provider,
                tools = listOf(queued),
                dispatcher = StandardTestDispatcher(testScheduler),
            ).run(request(provider, listOf(queued))).collect()
        }
        acquireStarted.await()

        collection.cancelAndJoin()

        assertFalse(executed)
        gate.release()
        gate.acquire()
        val nextAcquired = CompletableDeferred<Unit>()
        val next = launch {
            gate.acquire()
            nextAcquired.complete(Unit)
            gate.release()
        }
        yield()
        assertFalse(nextAcquired.isCompleted)

        gate.release()
        next.join()
        assertTrue(nextAcquired.isCompleted)
    }

    @Test
    fun queuedAcquisitionRemainsDurablyPendingUntilAdmitted() = runTest {
        val acquisitionStarted = CompletableDeferred<Unit>()
        var releaseCount = 0
        var executed = false
        val permit = object : ToolExecutionPermit {
            override suspend fun acquire() {
                acquisitionStarted.complete(Unit)
                awaitCancellation()
            }

            override fun release() {
                releaseCount += 1
            }
        }
        val queued = permittedTool("durably_pending", permit) { request ->
            executed = true
            successfulResult(request)
        }
        val provider = ToolThenDoneProvider(listOf(queued.definition))
        val persistence = InMemoryAgentPersistence()
        val agentRequest = request(provider, listOf(queued))
        val collection = launch {
            runner(
                provider = provider,
                tools = listOf(queued),
                dispatcher = StandardTestDispatcher(testScheduler),
                persistence = persistence,
            ).run(agentRequest).collect()
        }
        acquisitionStarted.await()

        val record = persistence.load(agentRequest.sessionId)
            ?.checkpoint
            ?.toolExecutions
            ?.single()
        assertEquals(ToolExecutionState.PENDING, record?.state)

        collection.cancelAndJoin()
        assertFalse(executed)
        assertEquals(0, releaseCount)
    }

    @Test
    fun failedAcquisitionDoesNotReleaseOrExecute() = runTest {
        var releaseCount = 0
        var executed = false
        val permit = object : ToolExecutionPermit {
            override suspend fun acquire() {
                throw IllegalStateException("expected acquisition failure")
            }

            override fun release() {
                releaseCount += 1
            }
        }
        val tool = permittedTool("failed_acquisition", permit) { request ->
            executed = true
            successfulResult(request)
        }

        val result = runSingleTool(tool, StandardTestDispatcher(testScheduler))

        assertTrue(result.isError)
        assertFalse(executed)
        assertEquals(0, releaseCount)
    }

    private suspend fun runSingleTool(
        tool: ToolExecutor,
        dispatcher: CoroutineDispatcher,
    ): ToolExecutionResult {
        val provider = ToolThenDoneProvider(listOf(tool.definition))
        val events = runner(provider, listOf(tool), dispatcher)
            .run(request(provider, listOf(tool)))
            .toList()
        return events.filterIsInstance<AgentEvent.ToolCompleted>().single().result
    }

    private fun runner(
        provider: ProviderAdapter,
        tools: List<ToolExecutor>,
        dispatcher: CoroutineDispatcher,
        persistence: InMemoryAgentPersistence = InMemoryAgentPersistence(),
    ) = DefaultAgentRunner(
        providerRegistry = InMemoryProviderRegistry(listOf(provider)),
        toolRegistry = InMemoryToolRegistry(tools),
        persistence = persistence,
        dispatcher = dispatcher,
    )

    private fun request(
        provider: ProviderAdapter,
        tools: List<ToolExecutor>,
    ) = AgentRequest(
        sessionId = AgentSessionId("tool-execution-permit-${provider.key}"),
        messages = listOf(
            AgentMessage(role = MessageRole.USER, parts = listOf(TextPart("hello"))),
        ),
        model = ModelDescriptor(provider = provider.key, model = "permit-contract"),
        tools = tools.map(ToolExecutor::definition),
        engine = AgentEngineConfig(
            runtime = RuntimeConfig(
                maxTurns = 2,
                maxProviderRetries = 0,
                toolExecutionMode = ToolExecutionMode.PARALLEL,
                defaultToolTimeoutMillis = 100,
                runTimeoutMillis = 1_000,
            ),
        ),
    )

    private fun executionRequest(tool: ToolExecutor): ToolExecutionRequest {
        val toolCall = ToolCallPart(
            toolCallId = "direct-permit-call",
            toolName = tool.definition.name,
            arguments = buildJsonObject { },
        )
        return ToolExecutionRequest(
            sessionId = AgentSessionId("direct-permit-session"),
            runId = AgentRunId("direct-permit-run"),
            executionId = "direct-permit-execution",
            assistantMessage = AgentMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(toolCall),
            ),
            toolCall = toolCall,
        )
    }

    private class ToolThenDoneProvider(
        private val definitions: List<ToolDefinition>,
    ) : ProviderAdapter {
        override val key: String = "permit-${definitions.joinToString("-") { it.name }}"

        override suspend fun generate(request: ProviderRequest): Flow<ProviderChunk> = flow {
            if (request.messages.lastOrNull()?.role == MessageRole.TOOL) {
                emit(providerChunk(text = "done", completed = true))
            } else {
                emit(
                    providerChunk(
                        toolCalls = definitions.mapIndexed { index, definition ->
                            ToolCallPart(
                                toolCallId = "permit-call-$index",
                                toolName = definition.name,
                                arguments = buildJsonObject { },
                            )
                        },
                        completed = true,
                    ),
                )
            }
        }
    }

    private companion object {
        fun definition(name: String) = ToolDefinition(
            name = name,
            description = "Tool execution permit contract Tool",
            schema = buildJsonObject { },
            timeoutMs = 100,
        )

        fun permittedTool(
            name: String,
            permit: ToolExecutionPermit,
            execute: suspend (ToolExecutionRequest) -> ToolExecutionResult,
        ): ToolExecutor = object : ToolExecutor {
            override val definition = definition(name)
            override fun executionPermit(request: ToolExecutionRequest) = permit

            override suspend fun execute(request: ToolExecutionRequest) = execute(request)
        }

        fun defaultPermitTool(
            name: String,
            execute: suspend (ToolExecutionRequest) -> ToolExecutionResult,
        ): ToolExecutor = object : ToolExecutor {
            override val definition = definition(name)

            override suspend fun execute(request: ToolExecutionRequest) = execute(request)
        }

        fun successfulResult(request: ToolExecutionRequest) = ToolExecutionResult(
            toolCallId = request.toolCall.toolCallId,
            toolName = request.toolCall.toolName,
            result = JsonPrimitive("done"),
        )
    }
}
