package saien.magrathea.runtime

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.Test
import saien.magrathea.core.AgentCheckpoint
import saien.magrathea.core.AgentEngineConfig
import saien.magrathea.core.AgentEvent
import saien.magrathea.core.AgentFailureCode
import saien.magrathea.core.AgentInterceptor
import saien.magrathea.core.AgentMessage
import saien.magrathea.core.AgentRequest
import saien.magrathea.core.AgentResumeCursor
import saien.magrathea.core.AgentResumePhase
import saien.magrathea.core.AgentRunId
import saien.magrathea.core.AgentSessionId
import saien.magrathea.core.AgentSessionSnapshot
import saien.magrathea.core.AgentStateSnapshot
import saien.magrathea.core.FollowUpMessageProvider
import saien.magrathea.core.MessageRole
import saien.magrathea.core.ModelDescriptor
import saien.magrathea.core.ProviderConfig
import saien.magrathea.core.RuntimeConfig
import saien.magrathea.core.StopReason
import saien.magrathea.core.TextPart
import saien.magrathea.core.ToolApprovalDecision
import saien.magrathea.core.ToolApprovalGateway
import saien.magrathea.core.ToolApprovalRequest
import saien.magrathea.core.ToolCallPart
import saien.magrathea.core.ToolDefinition
import saien.magrathea.core.ToolExecutionMode
import saien.magrathea.core.ToolExecutionRequest
import saien.magrathea.core.ToolExecutionResult
import saien.magrathea.core.ToolExecutor
import saien.magrathea.core.ToolRuntimeContext
import saien.magrathea.provider.api.InMemoryProviderRegistry
import saien.magrathea.provider.api.ProviderAdapter
import saien.magrathea.provider.api.ProviderChunk
import saien.magrathea.provider.api.ProviderRequest
import saien.magrathea.provider.api.ProviderUsage

class RuntimeBehaviorContractTest {
    @Test
    fun beforeModelCallAppliesRequestAndState() = runTest {
        val original = RecordingProvider("original")
        val selected = RecordingProvider("selected")
        val persistence = InMemoryAgentPersistence()
        val interceptor = object : AgentInterceptor {
            override suspend fun beforeModelCall(context: saien.magrathea.core.AgentRuntimeContext) = context.copy(
                request = context.request.copy(
                    model = ModelDescriptor(provider = selected.key, model = "selected-model"),
                    systemPrompt = "intercepted-system",
                ),
                state = context.state.copy(
                    messages = listOf(userMessage("intercepted-history")),
                ),
            )
        }
        val runner = runner(
            providers = listOf(original, selected),
            persistence = persistence,
            interceptors = listOf(interceptor),
        )
        val request = request(provider = original.key, text = "original-history")

        runner.run(request).toList()

        assertEquals(0, original.requests.size)
        assertEquals(1, selected.requests.size)
        assertEquals("selected-model", selected.requests.single().model.model)
        val outboundText = selected.requests.single().messages.flatMap { it.parts }
            .filterIsInstance<TextPart>()
            .map { it.text }
        assertEquals(listOf("intercepted-system", "intercepted-history"), outboundText)
        val persisted = requireNotNull(persistence.load(request.sessionId)?.snapshot)
        assertEquals("selected", persisted.request.model.provider)
        assertTrue(persisted.state.messages.any { messageText(it) == "intercepted-history" })
        assertFalse(persisted.state.messages.any { messageText(it) == "original-history" })
    }

    @Test
    fun onModelChunkRunsPerChunkAndAfterModelCallRunsOnce() = runTest {
        val provider = object : ProviderAdapter {
            override val key = "chunks"

            override suspend fun generate(request: ProviderRequest): Flow<ProviderChunk> = flow {
                emit(providerChunk(text = "one"))
                emit(providerChunk(text = "two", completed = true))
            }
        }
        var chunkCalls = 0
        var afterCalls = 0
        val interceptor = object : AgentInterceptor {
            override suspend fun onModelChunk(
                context: saien.magrathea.core.AgentRuntimeContext,
                message: AgentMessage,
            ): AgentMessage {
                chunkCalls += 1
                return message
            }

            override suspend fun afterModelCall(
                context: saien.magrathea.core.AgentRuntimeContext,
                message: AgentMessage,
            ): AgentMessage {
                afterCalls += 1
                return message
            }
        }

        runner(listOf(provider), interceptors = listOf(interceptor))
            .run(request(provider = provider.key))
            .toList()

        assertEquals(2, chunkCalls)
        assertEquals(1, afterCalls)
    }

    @Test
    fun beforeModelCallRejectsConflictingRequestAndStateHistory() = runTest {
        val provider = RecordingProvider("conflicting-history")
        val interceptor = object : AgentInterceptor {
            override suspend fun beforeModelCall(context: saien.magrathea.core.AgentRuntimeContext) = context.copy(
                request = context.request.copy(messages = listOf(userMessage("request-history"))),
                state = context.state.copy(messages = listOf(userMessage("state-history"))),
            )
        }

        val events = runner(listOf(provider), interceptors = listOf(interceptor))
            .run(request(provider = provider.key))
            .toList()

        assertTrue(provider.requests.isEmpty())
        assertEquals(AgentFailureCode.INTERNAL, events.filterIsInstance<AgentEvent.Failed>().single().code)
    }

    @Test
    fun emptyProviderFlowFailsButUsageOnlyTerminalCompletes() = runTest {
        val empty = object : ProviderAdapter {
            override val key = "empty"
            override suspend fun generate(request: ProviderRequest): Flow<ProviderChunk> = flow { }
        }
        val usageOnly = object : ProviderAdapter {
            override val key = "usage-only"
            override suspend fun generate(request: ProviderRequest): Flow<ProviderChunk> = flow {
                emit(
                    providerChunk(
                        completed = true,
                        usage = ProviderUsage(inputTokens = 3, outputTokens = 0),
                    ),
                )
            }
        }

        val emptyEvents = runner(listOf(empty)).run(request(provider = empty.key)).toList()
        val usageEvents = runner(listOf(usageOnly)).run(request(provider = usageOnly.key)).toList()

        assertTrue(emptyEvents.single { it is AgentEvent.Failed } is AgentEvent.Failed)
        assertTrue(usageEvents.single { it is AgentEvent.Completed } is AgentEvent.Completed)
        assertEquals(
            3L,
            usageEvents.filterIsInstance<AgentEvent.Completed>().single().state.usage.inputTokens,
        )
    }

    @Test
    fun followUpMessageForcesAnotherTurn() = runTest {
        val provider = RecordingProvider("follow-up")
        var followUpCalls = 0
        val followUp = FollowUpMessageProvider { context ->
            followUpCalls += 1
            if (context.turn == 0) listOf(userMessage("follow-up-message")) else emptyList()
        }

        val events = runner(listOf(provider), followUpMessageProvider = followUp)
            .run(request(provider = provider.key, maxTurns = 3))
            .toList()

        assertEquals(2, provider.requests.size)
        assertTrue(provider.requests[1].messages.any { messageText(it) == "follow-up-message" })
        assertEquals(2, followUpCalls)
        assertTrue(events.last() is AgentEvent.Completed)
    }

    @Test
    fun providerInvocationIdentityIsDistinctForEachLogicalRun() = runTest {
        val provider = RecordingProvider("invocation-identity")
        val runner = runner(listOf(provider))
        val sessionId = AgentSessionId("shared-chat-session")
        val first = request(provider = provider.key, sessionId = sessionId).copy(
            messages = listOf(userMessage("first").copy(id = "user-message-1")),
        )
        val second = request(provider = provider.key, sessionId = sessionId).copy(
            messages = listOf(userMessage("second").copy(id = "user-message-2")),
        )

        runner.run(first).toList()
        runner.run(second).toList()
        runner.run(second).toList()

        val requestIds = provider.requests.map { requireNotNull(it.invocation).requestId }
        assertEquals(3, requestIds.distinct().size)
        assertTrue(requestIds.all { it.endsWith(":0:0") })
        assertTrue(provider.requests.all { it.invocation?.sessionId == sessionId })
    }

    @Test
    fun toolFailureAndTimeoutBecomeErrorResults() = runTest {
        val failing = object : ToolExecutor {
            override val definition = toolDefinition("fails")
            override suspend fun execute(request: ToolExecutionRequest): ToolExecutionResult {
                error("secret-executor-detail")
            }
        }
        val timingOut = object : ToolExecutor {
            override val definition = toolDefinition("times-out").copy(timeoutMs = 10)
            override suspend fun execute(request: ToolExecutionRequest): ToolExecutionResult {
                awaitCancellation()
            }
        }
        val provider = ToolThenCompleteProvider(listOf("fails", "times-out"))
        val events = runner(listOf(provider), tools = listOf(failing, timingOut))
            .run(
                request(
                    provider = provider.key,
                    tools = listOf(failing.definition, timingOut.definition),
                    maxTurns = 2,
                ),
            )
            .toList()

        val completedTools = events.filterIsInstance<AgentEvent.ToolCompleted>()
        assertEquals(listOf("fails", "times-out"), completedTools.map { it.result.toolName })
        assertTrue(completedTools.all { it.result.isError })
        assertTrue(events.last() is AgentEvent.Completed)
    }

    @Test
    fun toolInterceptorCannotChangeCallIdentity() = runTest {
        val tool = CountingTool("identity")
        val provider = ToolThenCompleteProvider(listOf(tool.definition.name))
        val interceptor = object : AgentInterceptor {
            override suspend fun beforeToolCall(context: ToolRuntimeContext): ToolRuntimeContext {
                return context.copy(
                    toolCall = context.toolCall.copy(
                        toolCallId = "changed-call-id",
                        toolName = "changed-tool-name",
                    ),
                )
            }
        }

        val events = runner(listOf(provider), tools = listOf(tool), interceptors = listOf(interceptor))
            .run(request(provider = provider.key, tools = listOf(tool.definition), maxTurns = 2))
            .toList()

        val result = events.filterIsInstance<AgentEvent.ToolCompleted>().single().result
        assertEquals(0, tool.executionCount)
        assertEquals("call-0", result.toolCallId)
        assertEquals("identity", result.toolName)
        assertTrue(result.isError)
    }

    @Test
    fun toolResultCannotChangeCallIdentity() = runTest {
        val tool = object : ToolExecutor {
            override val definition = toolDefinition("mismatch")
            override suspend fun execute(request: ToolExecutionRequest) = ToolExecutionResult(
                toolCallId = "wrong-id",
                toolName = "wrong-name",
                result = JsonPrimitive("wrong"),
            )
        }
        val provider = ToolThenCompleteProvider(listOf(tool.definition.name))

        val events = runner(listOf(provider), tools = listOf(tool))
            .run(request(provider = provider.key, tools = listOf(tool.definition), maxTurns = 2))
            .toList()

        val result = events.filterIsInstance<AgentEvent.ToolCompleted>().single().result
        assertEquals("call-0", result.toolCallId)
        assertEquals("mismatch", result.toolName)
        assertTrue(result.isError)
    }

    @Test
    fun installedApprovalGatewayIsConsultedForEveryTool() = runTest {
        val tool = CountingTool("policy-controlled")
        val provider = ToolThenCompleteProvider(listOf(tool.definition.name))
        var approvalCalls = 0
        val denyGateway = object : ToolApprovalGateway {
            override suspend fun requestApproval(request: ToolApprovalRequest): ToolApprovalDecision {
                approvalCalls += 1
                return ToolApprovalDecision.Deny("policy denied")
            }
        }

        val events = runner(
            providers = listOf(provider),
            tools = listOf(tool),
            approvalGateway = denyGateway,
        ).run(
            request(provider = provider.key, tools = listOf(tool.definition), maxTurns = 2),
        ).toList()

        assertEquals(1, approvalCalls)
        assertEquals(0, tool.executionCount)
        assertTrue(events.filterIsInstance<AgentEvent.ToolCompleted>().single().result.isError)
    }

    @Test
    fun parallelToolEventsRemainInRequestOrder() = runTest {
        val secondFinished = CompletableDeferred<Unit>()
        val first = object : ToolExecutor {
            override val definition = toolDefinition("first")
            override suspend fun execute(request: ToolExecutionRequest): ToolExecutionResult {
                secondFinished.await()
                return successfulResult(request)
            }
        }
        val second = object : ToolExecutor {
            override val definition = toolDefinition("second")
            override suspend fun execute(request: ToolExecutionRequest): ToolExecutionResult {
                secondFinished.complete(Unit)
                return successfulResult(request)
            }
        }
        val provider = ToolThenCompleteProvider(listOf("first", "second"))

        val events = runner(listOf(provider), tools = listOf(first, second))
            .run(
                request(
                    provider = provider.key,
                    tools = listOf(first.definition, second.definition),
                    maxTurns = 2,
                    toolExecutionMode = ToolExecutionMode.PARALLEL,
                ),
            )
            .toList()

        assertEquals(listOf("first", "second"), events.filterIsInstance<AgentEvent.ToolRequested>().map { it.toolCall.toolName })
        assertEquals(listOf("first", "second"), events.filterIsInstance<AgentEvent.ToolCompleted>().map { it.result.toolName })
    }

    @Test
    fun runtimeDebugEventsDoNotExposeMessageEndpointOrToolPayload() = runTest {
        val userCanary = "USER_CONTENT_CANARY"
        val endpointCanary = "endpoint-canary"
        val argumentCanary = "TOOL_ARGUMENT_CANARY"
        val resultCanary = "TOOL_RESULT_CANARY"
        val tool = object : ToolExecutor {
            override val definition = toolDefinition("debug-tool")
            override suspend fun execute(request: ToolExecutionRequest) = ToolExecutionResult(
                toolCallId = request.toolCall.toolCallId,
                toolName = request.toolCall.toolName,
                result = JsonPrimitive(resultCanary),
                displayText = resultCanary,
            )
        }
        val provider = object : ProviderAdapter {
            override val key = "debug-provider"
            var calls = 0

            override suspend fun generate(request: ProviderRequest): Flow<ProviderChunk> = flow {
                calls += 1
                if (calls == 1) {
                    emit(
                        providerChunk(
                            toolCalls = listOf(
                                ToolCallPart(
                                    toolCallId = "debug-call",
                                    toolName = tool.definition.name,
                                    arguments = buildJsonObject { put("secret", argumentCanary) },
                                ),
                            ),
                            completed = true,
                        ),
                    )
                } else {
                    emit(completedChunk("final"))
                }
            }
        }
        val request = request(
            provider = provider.key,
            text = userCanary,
            tools = listOf(tool.definition),
            maxTurns = 2,
        ).copy(
            engine = AgentEngineConfig(
                provider = ProviderConfig(endpoint = "https://$endpointCanary.example.test/path?token=secret"),
                runtime = RuntimeConfig(maxTurns = 2),
            ),
        )

        val debug = runner(listOf(provider), tools = listOf(tool))
            .run(request)
            .toList()
            .filterIsInstance<AgentEvent.Debug>()
            .joinToString("|") { it.payload }

        assertFalse(debug.contains(userCanary))
        assertFalse(debug.contains(endpointCanary))
        assertFalse(debug.contains(argumentCanary))
        assertFalse(debug.contains(resultCanary))
    }

    @Test
    fun inMemoryStoresPreserveAllConcurrentWrites() = runTest {
        val persistence = InMemoryAgentPersistence()
        val total = 1_000

        withContext(Dispatchers.Default) {
            coroutineScope {
                (0 until total).map { index ->
                    async {
                        val sessionId = AgentSessionId("concurrent-$index")
                        val request = request(provider = "unused", sessionId = sessionId)
                        val state = AgentStateSnapshot(messages = request.messages, turn = index)
                        val runId = AgentRunId("concurrent-run-$index")
                        persistence.commit(
                            AgentSessionSnapshot(sessionId, runId, request, state),
                            AgentCheckpoint(
                                sessionId = sessionId,
                                runId = runId,
                                cursor = AgentResumeCursor(index, AgentResumePhase.MODEL_PENDING),
                                state = state,
                            ),
                        )
                    }
                }.awaitAll()
            }
        }

        assertEquals(total, persistence.listSessions().size)
        assertTrue((0 until total).all { index ->
            persistence.load(AgentSessionId("concurrent-$index"))?.checkpoint?.turn == index
        })
    }

    private fun runner(
        providers: List<ProviderAdapter>,
        tools: List<ToolExecutor> = emptyList(),
        persistence: InMemoryAgentPersistence = InMemoryAgentPersistence(),
        interceptors: List<AgentInterceptor> = emptyList(),
        approvalGateway: ToolApprovalGateway? = null,
        followUpMessageProvider: FollowUpMessageProvider = FollowUpMessageProvider { emptyList() },
    ) = DefaultAgentRunner(
        providerRegistry = InMemoryProviderRegistry(providers),
        toolRegistry = InMemoryToolRegistry(tools),
        persistence = persistence,
        interceptors = interceptors,
        approvalGateway = approvalGateway,
        followUpMessageProvider = followUpMessageProvider,
    )

    private fun request(
        provider: String,
        text: String = "hello",
        sessionId: AgentSessionId = AgentSessionId("runtime-$provider-$text"),
        tools: List<ToolDefinition> = emptyList(),
        maxTurns: Int = 4,
        toolExecutionMode: ToolExecutionMode = ToolExecutionMode.PARALLEL,
    ) = AgentRequest(
        sessionId = sessionId,
        messages = listOf(userMessage(text)),
        model = ModelDescriptor(provider = provider, model = "model"),
        tools = tools,
        engine = AgentEngineConfig(
            runtime = RuntimeConfig(maxTurns = maxTurns, toolExecutionMode = toolExecutionMode),
        ),
    )

    private class RecordingProvider(override val key: String) : ProviderAdapter {
        val requests = mutableListOf<ProviderRequest>()

        override suspend fun generate(request: ProviderRequest): Flow<ProviderChunk> = flow {
            requests += request
            emit(completedChunk("reply-${requests.size}"))
        }
    }

    private class ToolThenCompleteProvider(
        private val toolNames: List<String>,
    ) : ProviderAdapter {
        override val key = "tool-then-complete-${toolNames.joinToString("-")}"
        private var calls = 0

        override suspend fun generate(request: ProviderRequest): Flow<ProviderChunk> = flow {
            calls += 1
            if (calls == 1) {
                emit(
                    providerChunk(
                        toolCalls = toolNames.mapIndexed { index, name ->
                            ToolCallPart(
                                toolCallId = "call-$index",
                                toolName = name,
                                arguments = buildJsonObject { put("index", index) },
                            )
                        },
                        completed = true,
                    ),
                )
            } else {
                emit(completedChunk("done"))
            }
        }
    }

    private class CountingTool(name: String) : ToolExecutor {
        override val definition = toolDefinition(name)
        var executionCount = 0

        override suspend fun execute(request: ToolExecutionRequest): ToolExecutionResult {
            executionCount += 1
            return successfulResult(request)
        }
    }

    companion object {
        private fun toolDefinition(name: String) = ToolDefinition(
            name = name,
            description = "Runtime contract tool $name",
            schema = buildJsonObject { },
        )

        private fun successfulResult(request: ToolExecutionRequest) = ToolExecutionResult(
            toolCallId = request.toolCall.toolCallId,
            toolName = request.toolCall.toolName,
            result = JsonPrimitive("ok"),
        )

        private fun userMessage(text: String) = AgentMessage(
            role = MessageRole.USER,
            parts = listOf(TextPart(text)),
        )

        private fun completedChunk(text: String) = providerChunk(text = text, completed = true)

        private fun messageText(message: AgentMessage): String = message.parts
            .filterIsInstance<TextPart>()
            .joinToString("") { it.text }
    }
}
