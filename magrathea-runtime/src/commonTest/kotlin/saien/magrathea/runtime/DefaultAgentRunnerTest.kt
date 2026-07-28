package saien.magrathea.runtime

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.Test
import saien.magrathea.core.AgentEvent
import saien.magrathea.core.AgentFailureCode
import saien.magrathea.core.AgentEngineConfig
import saien.magrathea.core.AgentMessage
import saien.magrathea.core.AgentRequest
import saien.magrathea.core.AttachmentPart
import saien.magrathea.core.ContextTransformer
import saien.magrathea.core.CredentialProvider
import saien.magrathea.core.CredentialRef
import saien.magrathea.core.MessageRole
import saien.magrathea.core.ModelDescriptor
import saien.magrathea.core.ProviderConfig
import saien.magrathea.core.ProviderCredential
import saien.magrathea.core.ReasoningPart
import saien.magrathea.core.StopReason
import saien.magrathea.core.TextPart
import saien.magrathea.core.ToolApprovalDecision
import saien.magrathea.core.ToolApprovalGateway
import saien.magrathea.core.ToolApprovalRequest
import saien.magrathea.core.ToolCallPart
import saien.magrathea.core.ToolDefinition
import saien.magrathea.core.ToolExecutionRequest
import saien.magrathea.core.ToolExecutionResult
import saien.magrathea.core.ToolResultPart
import saien.magrathea.core.TypedTool
import saien.magrathea.core.citations
import saien.magrathea.provider.api.InMemoryProviderRegistry
import saien.magrathea.provider.api.ProviderAdapter
import saien.magrathea.provider.api.ProviderChunk
import saien.magrathea.provider.api.ProviderEvent

class DefaultAgentRunnerTest {
    class FakeEchoProvider : ProviderAdapter {
        override val key: String = "fake"

        override suspend fun generate(request: saien.magrathea.provider.api.ProviderRequest): Flow<ProviderChunk> = flow {
            val text = request.messages.lastOrNull()?.parts.orEmpty()
                .filterIsInstance<TextPart>()
                .joinToString("") { it.text }
            emit(providerChunk(text = text, completed = true))
        }
    }

    @Serializable
    data class EchoArgs(val value: String)

    class EchoTool : TypedTool<EchoArgs>(EchoArgs.serializer()) {
        override val definition: ToolDefinition = ToolDefinition(
            name = "echo_tool",
            description = "Echo",
            schema = kotlinx.serialization.json.buildJsonObject { },
        )

        override suspend fun executeTyped(request: ToolExecutionRequest, args: EchoArgs): ToolExecutionResult {
            return ToolExecutionResult(request.toolCall.toolCallId, definition.name, JsonPrimitive(args.value))
        }
    }

    class CitationTool : TypedTool<EchoArgs>(EchoArgs.serializer()) {
        override val definition: ToolDefinition = ToolDefinition(
            name = "citation_tool",
            description = "Citation",
            schema = kotlinx.serialization.json.buildJsonObject { },
        )
        var executionCount: Int = 0

        override suspend fun executeTyped(request: ToolExecutionRequest, args: EchoArgs): ToolExecutionResult {
            executionCount += 1
            return ToolExecutionResult(
                toolCallId = request.toolCall.toolCallId,
                toolName = definition.name,
                result = JsonPrimitive(args.value),
                displayText = "display: ${args.value}",
                metadata = buildJsonObject {
                    put("citations", buildJsonArray {
                        add(buildJsonObject {
                            put("title", "Doc")
                            put("url", "https://example.com")
                            put("snippet", "Excerpt")
                        })
                    })
                },
            )
        }
    }

    class ApprovalRequiredTool : TypedTool<EchoArgs>(EchoArgs.serializer()) {
        override val definition: ToolDefinition = ToolDefinition(
            name = "citation_tool",
            description = "Citation",
            schema = kotlinx.serialization.json.buildJsonObject { },
            requiresApproval = true,
        )

        override suspend fun executeTyped(request: ToolExecutionRequest, args: EchoArgs): ToolExecutionResult {
            return ToolExecutionResult(request.toolCall.toolCallId, definition.name, JsonPrimitive(args.value))
        }
    }

    class ToolThenDoneProvider : ProviderAdapter {
        override val key: String = "tool-provider"

        override suspend fun generate(request: saien.magrathea.provider.api.ProviderRequest): Flow<ProviderChunk> = flow {
            if (request.messages.lastOrNull()?.role == MessageRole.TOOL) {
                emit(providerChunk(text = "done", completed = true))
            } else {
                emit(
                    providerChunk(
                        toolCalls = listOf(
                            ToolCallPart(
                                toolCallId = "call-1",
                                toolName = "citation_tool",
                                arguments = buildJsonObject { put("value", "raw") },
                            ),
                        ),
                        completed = true,
                    ),
                )
            }
        }
    }

    class DeltaTextProvider : ProviderAdapter {
        override val key: String = "delta-provider"

        override suspend fun generate(request: saien.magrathea.provider.api.ProviderRequest): Flow<ProviderChunk> = flow {
            emit(providerChunk(text = "O"))
            emit(providerChunk(text = "K", completed = true))
        }
    }

    class PartialToolStreamingProvider : ProviderAdapter {
        override val key: String = "partial-tool-streaming-provider"

        override suspend fun generate(request: saien.magrathea.provider.api.ProviderRequest): Flow<ProviderChunk> = flow {
            if (request.messages.lastOrNull()?.role == MessageRole.TOOL) {
                emit(providerChunk(text = "tool complete", completed = true))
            } else {
                emit(
                    ProviderChunk(
                        events = listOf(
                            ProviderEvent.ToolCallStart(
                                ToolCallPart(
                                    toolCallId = "tool_call_index_0",
                                    toolName = "citation_tool",
                                    arguments = buildJsonObject { put("partial_json", "") },
                                    partial = true,
                                    providerCallId = "tool_citation_tool_1",
                                ),
                            ),
                        ),
                    ),
                )
                emit(
                    ProviderChunk(
                        events = listOf(
                            ProviderEvent.ToolCallDelta("tool_call_index_0", "{\"value\":\"raw\"}"),
                        ),
                    ),
                )
                emit(
                    ProviderChunk(
                        events = listOf(
                            ProviderEvent.Completed(stopReason = StopReason.TOOL_CALLS),
                        ),
                    ),
                )
            }
        }
    }

    class RecordingProvider : ProviderAdapter {
        override val key: String = "recording-provider"
        val requests = mutableListOf<saien.magrathea.provider.api.ProviderRequest>()

        override suspend fun generate(request: saien.magrathea.provider.api.ProviderRequest): Flow<ProviderChunk> = flow {
            requests += request
            emit(providerChunk(text = "done", completed = true))
        }
    }

    class DenyingApprovalGateway : ToolApprovalGateway {
        var requestCount: Int = 0

        override suspend fun requestApproval(request: ToolApprovalRequest): ToolApprovalDecision {
            requestCount += 1
            return ToolApprovalDecision.Deny("Denied in test")
        }
    }

    @Test
    fun runShouldEmitCompleted() = runTest {
        val runner = DefaultAgentRunner(
            providerRegistry = InMemoryProviderRegistry(listOf(FakeEchoProvider())),
            toolRegistry = InMemoryToolRegistry(listOf(EchoTool())),
            sessionStore = InMemorySessionStore(),
            checkpointStore = InMemoryCheckpointStore(),
        )
        val events = runner.run(
            AgentRequest(
                messages = listOf(AgentMessage(role = MessageRole.USER, parts = listOf(TextPart("hello")))),
                model = ModelDescriptor(provider = "fake", model = "fake-model"),
            )
        ).toList()
        assertTrue(events.any { it is AgentEvent.Completed })
    }

    @Test
    fun fakeProviderShouldReturnAssistantMessage() = runTest {
        val runner = DefaultAgentRunner(
            providerRegistry = InMemoryProviderRegistry(listOf(FakeEchoProvider())),
            toolRegistry = InMemoryToolRegistry(),
            sessionStore = InMemorySessionStore(),
            checkpointStore = InMemoryCheckpointStore(),
            contextTransformer = ContextTransformer { messages ->
                val message = messages.first()
                listOf(
                    message.copy(
                        parts = listOf(
                            ReasoningPart(text = "private reasoning", signature = "sig-1"),
                            ToolCallPart(toolCallId = "call|1", toolName = "echo_tool", arguments = JsonPrimitive("{}"), thoughtSignature = "thought-1"),
                        ),
                    ),
                ) + messages.drop(1)
            },
        )
        val events = runner.run(
            AgentRequest(
                messages = listOf(AgentMessage(role = MessageRole.USER, parts = listOf(TextPart("hello")))),
                model = ModelDescriptor(provider = "fake", model = "fake-model"),
            )
        ).toList()
        assertTrue(events.filterIsInstance<AgentEvent.MessageEmitted>().any { it.message.role == MessageRole.ASSISTANT && it.message.stopReason == StopReason.COMPLETED })
    }

    @Test
    fun systemPromptShouldBeInjectedIntoProviderRequestOnly() = runTest {
        val provider = RecordingProvider()
        val runner = DefaultAgentRunner(
            providerRegistry = InMemoryProviderRegistry(listOf(provider)),
            toolRegistry = InMemoryToolRegistry(),
            sessionStore = InMemorySessionStore(),
            checkpointStore = InMemoryCheckpointStore(),
        )

        val events = runner.run(
            AgentRequest(
                systemPrompt = "Use tools when explicitly requested.",
                messages = listOf(AgentMessage(role = MessageRole.USER, parts = listOf(TextPart("hello")))),
                model = ModelDescriptor(provider = "recording-provider", model = "recording-model"),
            )
        ).toList()

        val providerMessages = provider.requests.single().messages
        val finalMessages = events.filterIsInstance<AgentEvent.Completed>().single().state.messages

        assertEquals(MessageRole.SYSTEM, providerMessages.first().role)
        assertEquals("Use tools when explicitly requested.", providerMessages.first().parts.filterIsInstance<TextPart>().single().text)
        assertEquals(MessageRole.USER, finalMessages.first().role)
    }

    @Test
    fun deltaChunksShouldNotBeMergedTwice() = runTest {
        val runner = DefaultAgentRunner(
            providerRegistry = InMemoryProviderRegistry(listOf(DeltaTextProvider())),
            toolRegistry = InMemoryToolRegistry(),
            sessionStore = InMemorySessionStore(),
            checkpointStore = InMemoryCheckpointStore(),
        )

        val events = runner.run(
            AgentRequest(
                messages = listOf(AgentMessage(role = MessageRole.USER, parts = listOf(TextPart("hello")))),
                model = ModelDescriptor(provider = "delta-provider", model = "delta-model"),
            )
        ).toList()

        val finalAssistant = events
            .filterIsInstance<AgentEvent.Completed>()
            .single()
            .state
            .messages
            .last { it.role == MessageRole.ASSISTANT }
        val text = finalAssistant.parts.filterIsInstance<TextPart>().joinToString("") { it.text }

        assertEquals("OK", text)
    }

    @Test
    fun partialToolCallWithoutEndShouldNotExecuteTool() = runTest {
        val tool = CitationTool()
        val runner = DefaultAgentRunner(
            providerRegistry = InMemoryProviderRegistry(listOf(PartialToolStreamingProvider())),
            toolRegistry = InMemoryToolRegistry(listOf(tool)),
            sessionStore = InMemorySessionStore(),
            checkpointStore = InMemoryCheckpointStore(),
        )

        val events = runner.run(
            AgentRequest(
                messages = listOf(AgentMessage(role = MessageRole.USER, parts = listOf(TextPart("use tool")))),
                model = ModelDescriptor(provider = "partial-tool-streaming-provider", model = "tool-model"),
                tools = listOf(tool.definition),
            )
        ).toList()

        val toolRequested = events.filterIsInstance<AgentEvent.ToolRequested>().single()
        val toolCompleted = events.filterIsInstance<AgentEvent.ToolCompleted>().single()

        assertEquals("citation_tool", toolRequested.toolCall.toolName)
        assertEquals("tool_call_index_0", toolRequested.toolCall.toolCallId)
        assertEquals(0, tool.executionCount)
        assertTrue(toolCompleted.result.isError)
        assertEquals("Tool call is not finalized", toolCompleted.result.displayText)
    }

    @Test
    fun replayPolicyShouldTransformAssistantContextBeforeProviderCall() = runTest {
        val runner = DefaultAgentRunner(
            providerRegistry = InMemoryProviderRegistry(listOf(FakeEchoProvider())),
            toolRegistry = InMemoryToolRegistry(),
            sessionStore = InMemorySessionStore(),
            checkpointStore = InMemoryCheckpointStore(),
        )
        val events = runner.run(
            AgentRequest(
                messages = listOf(
                    AgentMessage(
                        role = MessageRole.ASSISTANT,
                        parts = listOf(
                            ReasoningPart(text = "private reasoning", signature = "sig-1"),
                            ToolCallPart(toolCallId = "call|1", toolName = "echo_tool", arguments = JsonPrimitive("{}"), thoughtSignature = "thought-1"),
                        ),
                        metadata = buildJsonObject {
                            put("provider", "fake")
                            put("model", "fake-model")
                        },
                    ),
                    AgentMessage(role = MessageRole.USER, parts = listOf(TextPart("hello"))),
                ),
                model = ModelDescriptor(provider = "fake", model = "fake-model"),
            )
        ).toList()
        assertTrue(events.any { it is AgentEvent.Completed })
    }

    @Test
    fun debugEventsShouldStayWithinContractLabels() = runTest {
        val runner = DefaultAgentRunner(
            providerRegistry = InMemoryProviderRegistry(listOf(FakeEchoProvider())),
            toolRegistry = InMemoryToolRegistry(),
            sessionStore = InMemorySessionStore(),
            checkpointStore = InMemoryCheckpointStore(),
        )

        val labels = runner.run(
            AgentRequest(
                messages = listOf(AgentMessage(role = MessageRole.USER, parts = listOf(TextPart("hello")))),
                model = ModelDescriptor(provider = "fake", model = "fake-model"),
            )
        ).toList().filterIsInstance<AgentEvent.Debug>().map { it.label }.toSet()

        assertEquals(
            setOf("provider-request-messages", "provider-request-config", "provider-selected", "provider-chunk", "merged-assistant", "state-after-chunk"),
            labels,
        )
    }

    @Test
    fun toolResultsShouldPreserveDisplayTextAndMetadataInState() = runTest {
        val tool = CitationTool()
        val runner = DefaultAgentRunner(
            providerRegistry = InMemoryProviderRegistry(listOf(ToolThenDoneProvider())),
            toolRegistry = InMemoryToolRegistry(listOf(tool)),
            sessionStore = InMemorySessionStore(),
            checkpointStore = InMemoryCheckpointStore(),
        )

        val events = runner.run(
            AgentRequest(
                messages = listOf(AgentMessage(role = MessageRole.USER, parts = listOf(TextPart("use tool")))),
                model = ModelDescriptor(provider = "tool-provider", model = "tool-model"),
                tools = listOf(tool.definition),
            )
        ).toList()

        val toolCompleted = events.filterIsInstance<AgentEvent.ToolCompleted>().single()
        val completed = events.filterIsInstance<AgentEvent.Completed>().single()
        val toolResult = completed.state.messages
            .filter { it.role == MessageRole.TOOL }
            .flatMap { it.parts }
            .filterIsInstance<ToolResultPart>()
            .single()

        assertEquals("display: raw", toolCompleted.result.displayText)
        assertEquals("display: raw", toolResult.displayText)
        assertEquals("Doc", toolResult.citations().single().title)
    }

    @Test
    fun credentialRef_resolvesAtProviderCallTime() = runTest {
        val canary = "MAG_RESOLVED_CREDENTIAL_CANARY"
        val endpointCanary = "https://profile.example.invalid/v1"
        val headerCanary = "profile-header-value"
        val ref = CredentialRef(provider = "recording-provider", profile = "work")
        val provider = RecordingProvider()
        val sessionStore = InMemorySessionStore()
        val request = AgentRequest(
            messages = listOf(AgentMessage(role = MessageRole.USER, parts = listOf(TextPart("hello")))),
            model = ModelDescriptor(provider = "recording-provider", model = "recording-model"),
            engine = AgentEngineConfig(provider = ProviderConfig(credentialRef = ref)),
        )
        val runner = DefaultAgentRunner(
            providerRegistry = InMemoryProviderRegistry(listOf(provider)),
            toolRegistry = InMemoryToolRegistry(),
            sessionStore = sessionStore,
            checkpointStore = InMemoryCheckpointStore(),
            credentialProvider = CredentialProvider { requestedRef ->
                assertEquals(ref, requestedRef)
                ProviderCredential(
                    value = canary,
                    endpoint = endpointCanary,
                    headers = mapOf("X-Profile" to headerCanary),
                )
            },
        )

        runner.run(request).toList()

        val providerRequest = provider.requests.single()
        assertEquals(canary, providerRequest.credential?.value)
        assertEquals(endpointCanary, providerRequest.endpoint)
        assertEquals(headerCanary, providerRequest.headers["X-Profile"])
        val persisted = requireNotNull(sessionStore.loadSession(request.sessionId))
        assertEquals(ref, persisted.request.engine.provider.credentialRef)
        assertNull(persisted.request.engine.provider.endpoint)
        assertTrue(persisted.request.engine.provider.headers.isEmpty())
    }

    @Test
    fun credentialRef_withoutResolverFailsBeforeProviderCall() = runTest {
        val provider = RecordingProvider()
        val request = AgentRequest(
            messages = listOf(AgentMessage(role = MessageRole.USER, parts = listOf(TextPart("hello")))),
            model = ModelDescriptor(provider = "recording-provider", model = "recording-model"),
            engine = AgentEngineConfig(
                provider = ProviderConfig(
                    credentialRef = CredentialRef(provider = "recording-provider", profile = "missing"),
                ),
            ),
        )
        val runner = DefaultAgentRunner(
            providerRegistry = InMemoryProviderRegistry(listOf(provider)),
            toolRegistry = InMemoryToolRegistry(),
            sessionStore = InMemorySessionStore(),
            checkpointStore = InMemoryCheckpointStore(),
        )

        val events = runner.run(request).toList()

        assertTrue(provider.requests.isEmpty())
        assertEquals(AgentFailureCode.CREDENTIAL_UNAVAILABLE, events.filterIsInstance<AgentEvent.Failed>().single().code)
    }

    @Test
    fun credentialRef_forDifferentProviderFailsBeforeResolutionOrProviderCall() = runTest {
        val provider = RecordingProvider()
        var resolverCalls = 0
        val request = AgentRequest(
            messages = listOf(AgentMessage(role = MessageRole.USER, parts = listOf(TextPart("hello")))),
            model = ModelDescriptor(provider = "recording-provider", model = "recording-model"),
            engine = AgentEngineConfig(
                provider = ProviderConfig(credentialRef = CredentialRef(provider = "other-provider")),
            ),
        )
        val runner = DefaultAgentRunner(
            providerRegistry = InMemoryProviderRegistry(listOf(provider)),
            toolRegistry = InMemoryToolRegistry(),
            sessionStore = InMemorySessionStore(),
            checkpointStore = InMemoryCheckpointStore(),
            credentialProvider = CredentialProvider {
                resolverCalls += 1
                ProviderCredential("must-not-be-used")
            },
        )

        val events = runner.run(request).toList()

        assertEquals(0, resolverCalls)
        assertTrue(provider.requests.isEmpty())
        assertEquals(AgentFailureCode.CREDENTIAL_UNAVAILABLE, events.filterIsInstance<AgentEvent.Failed>().single().code)
    }

    @Test
    fun toolApprovalShouldUseRequestToolDefinition() = runTest {
        val approvalGateway = DenyingApprovalGateway()
        val runner = DefaultAgentRunner(
            providerRegistry = InMemoryProviderRegistry(listOf(ToolThenDoneProvider())),
            toolRegistry = InMemoryToolRegistry(listOf(CitationTool())),
            sessionStore = InMemorySessionStore(),
            checkpointStore = InMemoryCheckpointStore(),
            approvalGateway = approvalGateway,
        )

        val events = runner.run(
            AgentRequest(
                messages = listOf(AgentMessage(role = MessageRole.USER, parts = listOf(TextPart("use tool")))),
                model = ModelDescriptor(provider = "tool-provider", model = "tool-model"),
                tools = listOf(
                    ToolDefinition(
                        name = "citation_tool",
                        description = "Citation",
                        schema = buildJsonObject { },
                        requiresApproval = true,
                    ),
                ),
            )
        ).toList()

        val toolCompleted = events.filterIsInstance<AgentEvent.ToolCompleted>().single()
        assertEquals(1, approvalGateway.requestCount)
        assertTrue(toolCompleted.result.isError)
        assertEquals("Tool denied", toolCompleted.result.displayText)
    }

    @Test
    fun requestToolDefinitionShouldNotDowngradeExecutorApproval() = runTest {
        val approvalGateway = DenyingApprovalGateway()
        val runner = DefaultAgentRunner(
            providerRegistry = InMemoryProviderRegistry(listOf(ToolThenDoneProvider())),
            toolRegistry = InMemoryToolRegistry(listOf(ApprovalRequiredTool())),
            sessionStore = InMemorySessionStore(),
            checkpointStore = InMemoryCheckpointStore(),
            approvalGateway = approvalGateway,
        )

        val events = runner.run(
            AgentRequest(
                messages = listOf(AgentMessage(role = MessageRole.USER, parts = listOf(TextPart("use tool")))),
                model = ModelDescriptor(provider = "tool-provider", model = "tool-model"),
                tools = listOf(
                    ToolDefinition(
                        name = "citation_tool",
                        description = "Citation",
                        schema = buildJsonObject { },
                        requiresApproval = false,
                    ),
                ),
            )
        ).toList()

        val toolCompleted = events.filterIsInstance<AgentEvent.ToolCompleted>().single()
        assertEquals(1, approvalGateway.requestCount)
        assertTrue(toolCompleted.result.isError)
        assertEquals("Tool denied", toolCompleted.result.displayText)
    }

    @Test
    fun debugEventsShouldSummarizeAttachmentsWithoutDataUrls() = runTest {
        val runner = DefaultAgentRunner(
            providerRegistry = InMemoryProviderRegistry(listOf(FakeEchoProvider())),
            toolRegistry = InMemoryToolRegistry(),
            sessionStore = InMemorySessionStore(),
            checkpointStore = InMemoryCheckpointStore(),
        )

        val events = runner.run(
            AgentRequest(
                messages = listOf(
                    AgentMessage(
                        role = MessageRole.USER,
                        parts = listOf(AttachmentPart("data:image/png;base64,SU1BR0VfREFUQQ==", "image/png")),
                    ),
                ),
                model = ModelDescriptor(provider = "fake", model = "fake-model"),
            )
        ).toList()

        val debugPayload = events.filterIsInstance<AgentEvent.Debug>().joinToString("\n") { it.payload }

        assertTrue(debugPayload.contains("attachments=1"))
        assertFalse(debugPayload.contains("IMAGE_DATA"))
    }
}
