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
import saien.magrathea.core.ReasoningCapabilities
import saien.magrathea.core.ReasoningEffort
import saien.magrathea.core.ReasoningPreference
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
import saien.magrathea.core.text
import saien.magrathea.provider.api.InMemoryProviderRegistry
import saien.magrathea.provider.api.OpenAiTransportConfig
import saien.magrathea.provider.api.OpenAiWireProtocol
import saien.magrathea.provider.api.ProviderAdapter
import saien.magrathea.provider.api.ProviderChunk
import saien.magrathea.provider.api.ProviderEvent
import saien.magrathea.provider.api.ProviderProtocolException
import saien.magrathea.provider.api.toProviderOptions

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

    class ProtocolFailingProvider : ProviderAdapter {
        override val key: String = "protocol-fail"

        override suspend fun generate(
            request: saien.magrathea.provider.api.ProviderRequest,
        ): Flow<ProviderChunk> = flow {
            throw ProviderProtocolException("PRIVATE-PROVIDER-BODY")
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

    class OpenAiFamilyRecordingProvider : ProviderAdapter {
        override val key: String = "openrouter"
        override val optionsFamily: String = "openai"
        var request: saien.magrathea.provider.api.ProviderRequest? = null

        override suspend fun generate(
            request: saien.magrathea.provider.api.ProviderRequest,
        ): Flow<ProviderChunk> =
            flow {
                this@OpenAiFamilyRecordingProvider.request = request
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
            persistence = InMemoryAgentPersistence(),
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
    fun providerIdentityCanConsumeADifferentTypedOptionsFamily() = runTest {
        val provider = OpenAiFamilyRecordingProvider()
        val runner = DefaultAgentRunner(
            providerRegistry = InMemoryProviderRegistry(listOf(provider)),
            toolRegistry = InMemoryToolRegistry(),
            persistence = InMemoryAgentPersistence(),
        )
        val options = OpenAiTransportConfig(
            protocol = OpenAiWireProtocol.RESPONSES,
            reasoningEffort = "high",
        )

        runner.run(
            AgentRequest(
                messages = listOf(AgentMessage(role = MessageRole.USER, parts = listOf(TextPart("hello")))),
                model = ModelDescriptor(provider = "openrouter", model = "provider/model"),
                engine = AgentEngineConfig(
                    provider = ProviderConfig(options = options.toProviderOptions()),
                ),
            ),
        ).toList()

        assertEquals(options, provider.request?.typedConfig)
    }

    @Test
    fun reasoningPreferenceReachesTheProviderBoundary() = runTest {
        val provider = RecordingProvider()
        val runner = DefaultAgentRunner(
            providerRegistry = InMemoryProviderRegistry(listOf(provider)),
            toolRegistry = InMemoryToolRegistry(),
            persistence = InMemoryAgentPersistence(),
        )
        val preference = ReasoningPreference.Effort(ReasoningEffort.XHIGH)

        runner.run(
            AgentRequest(
                messages = listOf(
                    AgentMessage(role = MessageRole.USER, parts = listOf(TextPart("hello"))),
                ),
                model = ModelDescriptor(
                    provider = provider.key,
                    model = "reasoning-model",
                    reasoningCapabilities = ReasoningCapabilities(
                        supportedEfforts = setOf(ReasoningEffort.XHIGH),
                    ),
                ),
                reasoningPreference = preference,
            ),
        ).toList()

        assertEquals(preference, provider.requests.single().reasoningPreference)
    }

    @Test
    fun modelOutputCapabilityBecomesTheDefaultProviderRequestBound() = runTest {
        val provider = RecordingProvider()
        val runner = DefaultAgentRunner(
            providerRegistry = InMemoryProviderRegistry(listOf(provider)),
            toolRegistry = InMemoryToolRegistry(),
            persistence = InMemoryAgentPersistence(),
        )

        runner.run(
            AgentRequest(
                messages = listOf(
                    AgentMessage(role = MessageRole.USER, parts = listOf(TextPart("hello"))),
                ),
                model = ModelDescriptor(
                    provider = provider.key,
                    model = "bounded-model",
                    maxOutputTokens = 16_384,
                ),
            ),
        ).toList()

        assertEquals(16_384, provider.requests.single().maxTokens)
    }

    @Test
    fun providerConfigOutputOverrideTakesPrecedenceOverModelCapability() = runTest {
        val provider = RecordingProvider()
        val runner = DefaultAgentRunner(
            providerRegistry = InMemoryProviderRegistry(listOf(provider)),
            toolRegistry = InMemoryToolRegistry(),
            persistence = InMemoryAgentPersistence(),
        )

        runner.run(
            AgentRequest(
                messages = listOf(
                    AgentMessage(role = MessageRole.USER, parts = listOf(TextPart("hello"))),
                ),
                model = ModelDescriptor(
                    provider = provider.key,
                    model = "bounded-model",
                    maxOutputTokens = 16_384,
                ),
                engine = AgentEngineConfig(provider = ProviderConfig(maxTokens = 4_096)),
            ),
        ).toList()

        assertEquals(4_096, provider.requests.single().maxTokens)
    }

    @Test
    fun outputBoundUsesTheExpandedFinalProviderProjection() = runTest {
        val provider = RecordingProvider()
        val expandedText = "x".repeat(360)
        val runner = DefaultAgentRunner(
            providerRegistry = InMemoryProviderRegistry(listOf(provider)),
            toolRegistry = InMemoryToolRegistry(),
            persistence = InMemoryAgentPersistence(),
            contextTransformer = ContextTransformer { messages ->
                messages.map { message ->
                    message.copy(parts = listOf(TextPart(expandedText)))
                }
            },
        )

        runner.run(outputBudgetRequest(text = "x")).toList()

        assertEquals(expandedText, provider.requests.single().messages.single().text())
        assertEquals(6, provider.requests.single().maxTokens)
    }

    @Test
    fun outputBoundUsesTheReducedFinalProviderProjection() = runTest {
        val provider = RecordingProvider()
        val runner = DefaultAgentRunner(
            providerRegistry = InMemoryProviderRegistry(listOf(provider)),
            toolRegistry = InMemoryToolRegistry(),
            persistence = InMemoryAgentPersistence(),
            contextTransformer = ContextTransformer { messages ->
                messages.map { message ->
                    message.copy(parts = listOf(TextPart("x")))
                }
            },
        )

        runner.run(outputBudgetRequest(text = "x".repeat(360))).toList()

        assertEquals("x", provider.requests.single().messages.single().text())
        assertEquals(80, provider.requests.single().maxTokens)
    }

    @Test
    fun fakeProviderShouldReturnAssistantMessage() = runTest {
        val runner = DefaultAgentRunner(
            providerRegistry = InMemoryProviderRegistry(listOf(FakeEchoProvider())),
            toolRegistry = InMemoryToolRegistry(),
            persistence = InMemoryAgentPersistence(),
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
            persistence = InMemoryAgentPersistence(),
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
            persistence = InMemoryAgentPersistence(),
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
            persistence = InMemoryAgentPersistence(),
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
            persistence = InMemoryAgentPersistence(),
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
    fun tracingClassifiesProviderFailureWithoutExceptionMessage() = runTest {
        val sink = RecordingTraceSink()
        val runner = DefaultAgentRunner(
            providerRegistry = InMemoryProviderRegistry(listOf(ProtocolFailingProvider())),
            toolRegistry = InMemoryToolRegistry(),
            persistence = InMemoryAgentPersistence(),
            tracer = sink.tracer(),
        )

        runner.run(
            AgentRequest(
                messages = listOf(
                    AgentMessage(role = MessageRole.USER, parts = listOf(TextPart("hello"))),
                ),
                model = ModelDescriptor(provider = "protocol-fail", model = "fake-model"),
            ),
        ).toList()

        val span = sink.spans.single { it.name == RuntimeTraceNames.PROVIDER_REQUEST }
        val failure = span.events.single { it.name == "magrathea.provider.failure" }
        assertEquals("protocol", (failure.attributes["type"] as saien.magrathea.core.TraceValue.StringValue).value)
        assertEquals("protocol-fail", span.stringAttribute("magrathea.provider.key"))
        assertFalse(sink.spans.toString().contains("PRIVATE-PROVIDER-BODY"))
    }


    @Test
    fun toolResultsShouldPreserveDisplayTextAndMetadataInState() = runTest {
        val tool = CitationTool()
        val runner = DefaultAgentRunner(
            providerRegistry = InMemoryProviderRegistry(listOf(ToolThenDoneProvider())),
            toolRegistry = InMemoryToolRegistry(listOf(tool)),
            persistence = InMemoryAgentPersistence(),
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
        val persistence = InMemoryAgentPersistence()
        val request = AgentRequest(
            messages = listOf(AgentMessage(role = MessageRole.USER, parts = listOf(TextPart("hello")))),
            model = ModelDescriptor(provider = "recording-provider", model = "recording-model"),
            engine = AgentEngineConfig(provider = ProviderConfig(credentialRef = ref)),
        )
        val runner = DefaultAgentRunner(
            providerRegistry = InMemoryProviderRegistry(listOf(provider)),
            toolRegistry = InMemoryToolRegistry(),
            persistence = persistence,
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
        val persisted = requireNotNull(persistence.load(request.sessionId)?.snapshot)
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
            persistence = InMemoryAgentPersistence(),
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
            persistence = InMemoryAgentPersistence(),
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
            persistence = InMemoryAgentPersistence(),
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
            persistence = InMemoryAgentPersistence(),
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
    fun tracingDoesNotRetainAttachmentData() = runTest {
        val sink = RecordingTraceSink()
        val runner = DefaultAgentRunner(
            providerRegistry = InMemoryProviderRegistry(listOf(FakeEchoProvider())),
            toolRegistry = InMemoryToolRegistry(),
            persistence = InMemoryAgentPersistence(),
            tracer = sink.tracer(),
        )

        runner.run(
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

        val debugPayload = sink.spans.joinToString("\n")

        assertTrue(debugPayload.contains("message_count=LongValue(value=1)"))
        assertFalse(debugPayload.contains("IMAGE_DATA"))
    }

    private fun outputBudgetRequest(text: String): AgentRequest = AgentRequest(
        messages = listOf(AgentMessage(role = MessageRole.USER, parts = listOf(TextPart(text)))),
        model = ModelDescriptor(
            provider = "recording-provider",
            model = "bounded-model",
            contextWindowTokens = 100,
            maxOutputTokens = 80,
        ),
        engine = AgentEngineConfig(
            runtime = saien.magrathea.core.RuntimeConfig(
                contextManagement = saien.magrathea.core.ContextManagementConfig(enabled = false),
            ),
        ),
    )
}
