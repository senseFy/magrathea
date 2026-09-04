package saien.magrathea.chatbot

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import saien.magrathea.core.AgentEngineConfig
import saien.magrathea.core.AgentMessage
import saien.magrathea.core.MessageRole
import saien.magrathea.core.ModelDescriptor
import saien.magrathea.core.ReasoningCapabilities
import saien.magrathea.core.RuntimeConfig
import saien.magrathea.core.SteeringMessageProvider
import saien.magrathea.core.StopReason
import saien.magrathea.core.TextPart
import saien.magrathea.core.ToolCallPart
import saien.magrathea.core.ToolDefinition
import saien.magrathea.core.ToolExecutionRequest
import saien.magrathea.core.ToolExecutionResult
import saien.magrathea.core.ToolExecutor
import saien.magrathea.core.ToolResultAudience
import saien.magrathea.core.ToolResultTextContent
import saien.magrathea.policy.InMemoryToolAuditLog
import saien.magrathea.policy.PermissionPolicy
import saien.magrathea.policy.PolicyBackedApprovalGateway
import saien.magrathea.policy.ToolApprovalMode
import saien.magrathea.policy.ToolAuditOutcome
import saien.magrathea.policy.ToolPolicy
import saien.magrathea.policy.ToolPolicyEngine
import saien.magrathea.policy.ToolRiskLevel
import saien.magrathea.provider.api.InMemoryProviderRegistry
import saien.magrathea.provider.api.ProviderAdapter
import saien.magrathea.provider.api.ProviderChunk
import saien.magrathea.provider.api.ProviderEvent
import saien.magrathea.provider.api.ProviderRequest
import saien.magrathea.provider.api.ProviderUsage
import saien.magrathea.runtime.DefaultAgentRunner
import saien.magrathea.runtime.InMemoryAgentPersistence
import saien.magrathea.runtime.InMemoryToolRegistry

@OptIn(ExperimentalCoroutinesApi::class)
class ChatbotScriptContractTest {
    @Test
    fun canonicalToolResultIsNotImplicitlyPromotedToChatbotDisplayText() = runTest {
        val secret = "PRIVATE_CANONICAL_TOOL_RESULT"
        val tool = PrivateResultTool(secret)
        val provider = ScriptedProvider(tool.definition.name)
        val persistence = InMemoryAgentPersistence()
        val runner = DefaultAgentRunner(
            providerRegistry = InMemoryProviderRegistry(listOf(provider)),
            toolRegistry = InMemoryToolRegistry(listOf(tool)),
            persistence = persistence,
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        val fixture = ManagedChatbotControllerFixture.create(
            runner = runner,
            scope = this,
            persistence = persistence,
            requestFactory = DefaultChatbotRequestFactory(
                tools = listOf(tool.definition),
                configure = {
                    copy(engine = AgentEngineConfig(runtime = RuntimeConfig(maxTurns = 3)))
                },
            ),
            configuration = ChatbotSessionConfiguration(
                ModelDescriptor(provider = provider.key, model = "scripted-model"),
            ),
        )
        val controller = fixture.controller

        controller.sendMessage("Use the private Tool")
        advanceUntilIdle()

        val toolResult = controller.state.value.messages.flatMap { it.toolResults }.single()
        assertEquals("Tool completed.", toolResult.text)
        assertFalse(controller.state.value.toString().contains(secret))
        assertFalse(provider.requests.last().toString().contains(secret))
        fixture.close()
    }

    @Test
    fun commonScriptReachesEquivalentFinalStateAndResumeDoesNotRepeatSideEffects() = runTest {
        val tool = WeatherTool()
        val provider = ScriptedProvider(tool.definition.name)
        val persistence = InMemoryAgentPersistence()
        val auditLog = InMemoryToolAuditLog()
        val steeringMessage = AgentMessage(
            id = "steering-tone",
            role = MessageRole.SYSTEM,
            parts = listOf(TextPart("Runtime steering:\n- tone: concise")),
            createdAtEpochMs = 1,
        )
        val policy = PolicyBackedApprovalGateway(
            engine = ToolPolicyEngine(
                policies = listOf(
                    ToolPolicy(
                        toolName = tool.definition.name,
                        riskLevel = ToolRiskLevel.LOW,
                        approvalMode = ToolApprovalMode.ALLOW,
                        requiredPermissions = setOf("network"),
                    ),
                ),
                permissionPolicies = listOf(PermissionPolicy("network", allowed = true)),
            ),
            auditLog = auditLog,
        )
        val runner = DefaultAgentRunner(
            providerRegistry = InMemoryProviderRegistry(listOf(provider)),
            toolRegistry = InMemoryToolRegistry(listOf(tool)),
            persistence = persistence,
            approvalGateway = policy,
            steeringMessageProvider = SteeringMessageProvider { context ->
                if (context.state.messages.any { it.id == steeringMessage.id }) emptyList()
                else listOf(steeringMessage)
            },
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        val fixture = ManagedChatbotControllerFixture.create(
            runner = runner,
            scope = this,
            persistence = persistence,
            requestFactory = DefaultChatbotRequestFactory(
                tools = listOf(tool.definition),
                configure = {
                    copy(engine = AgentEngineConfig(runtime = RuntimeConfig(maxTurns = 3)))
                },
            ),
            configuration = ChatbotSessionConfiguration(
                ModelDescriptor(
                    provider = provider.key,
                    model = "scripted-model",
                    supportsToolCalls = true,
                    reasoningCapabilities = ReasoningCapabilities(),
                    supportsStreaming = true,
                ),
            ),
        )
        val controller = fixture.controller

        controller.sendMessage("What is the weather?")
        advanceUntilIdle()

        val expected = ScriptFingerprint(
            status = ChatbotStatus.COMPLETED,
            roles = listOf(
                ChatbotMessageRole.USER,
                ChatbotMessageRole.SYSTEM,
                ChatbotMessageRole.ASSISTANT,
                ChatbotMessageRole.TOOL,
                ChatbotMessageRole.ASSISTANT,
            ),
            visibleText = listOf(
                "What is the weather?",
                "Runtime steering:\n- tone: concise",
                "",
                "",
                "It is sunny.",
            ),
            reasoning = listOf("I should check the weather."),
            toolCalls = listOf("weather"),
            toolResults = listOf("weather:sunny:false"),
            usage = ChatbotUsage(inputTokens = 13, outputTokens = 6, reasoningTokens = 1),
            finalStopReason = ChatbotStopReason.COMPLETED,
        )
        assertEquals(expected, controller.state.value.fingerprint())
        assertEquals(2, provider.requests.size)
        assertTrue(provider.requests.first().messages.any { it.role == MessageRole.SYSTEM && messageText(it).contains("tone: concise") })
        assertEquals(1, tool.executionCount)
        assertEquals(
            listOf(ToolAuditOutcome.PERMISSION_ALLOWED, ToolAuditOutcome.TOOL_ALLOWED),
            auditLog.entries().map { it.outcome },
        )

        val sessionId = requireNotNull(controller.state.value.sessionId)
        val attached = fixture.manager.acquire(saien.magrathea.core.AgentSessionId(sessionId))

        assertEquals(saien.magrathea.runtime.AgentSessionPhase.TERMINAL, attached.state.value.phase)
        assertEquals(expected, controller.state.value.fingerprint())
        assertEquals(2, provider.requests.size)
        assertEquals(1, tool.executionCount)
        attached.release()
        fixture.close()
    }

    private class ScriptedProvider(
        private val toolName: String,
    ) : ProviderAdapter {
        override val key: String = "scripted-chatbot"
        val requests = mutableListOf<ProviderRequest>()

        override suspend fun generate(request: ProviderRequest): Flow<ProviderChunk> = flow {
            requests += request
            if (requests.size == 1) {
                val startedCall = ToolCallPart(
                    toolCallId = "weather-call-1",
                    toolName = toolName,
                    arguments = buildJsonObject { },
                    partial = true,
                )
                val finalCall = startedCall.copy(
                    arguments = buildJsonObject { put("city", "Shanghai") },
                    partial = false,
                )
                emit(
                    ProviderChunk(
                        events = listOf(
                            ProviderEvent.ReasoningStart(),
                            ProviderEvent.ReasoningDelta("I should check the weather."),
                            ProviderEvent.ReasoningEnd(),
                            ProviderEvent.ToolCallStart(startedCall),
                        ),
                    ),
                )
                emit(
                    ProviderChunk(
                        events = listOf(
                            ProviderEvent.ToolCallEnd(finalCall),
                            ProviderEvent.Completed(
                                stopReason = StopReason.TOOL_CALLS,
                                usage = ProviderUsage(inputTokens = 5, outputTokens = 2, reasoningTokens = 1),
                            ),
                        ),
                    ),
                )
            } else {
                emit(
                    ProviderChunk(
                        events = listOf(
                            ProviderEvent.TextStart(),
                            ProviderEvent.TextDelta("It is sunny."),
                            ProviderEvent.TextEnd(),
                            ProviderEvent.Completed(
                                stopReason = StopReason.COMPLETED,
                                usage = ProviderUsage(inputTokens = 8, outputTokens = 4, reasoningTokens = 0),
                            ),
                        ),
                    ),
                )
            }
        }
    }

    private class WeatherTool : ToolExecutor {
        override val definition = ToolDefinition(
            name = "weather",
            description = "Returns deterministic test weather",
            schema = buildJsonObject { },
        )
        var executionCount: Int = 0

        override suspend fun execute(request: ToolExecutionRequest): ToolExecutionResult {
            executionCount += 1
            return ToolExecutionResult(
                toolCallId = request.toolCall.toolCallId,
                toolName = request.toolCall.toolName,
                result = JsonPrimitive("sunny"),
                displayText = "sunny",
            )
        }
    }

    private class PrivateResultTool(
        private val secret: String,
    ) : ToolExecutor {
        override val definition = ToolDefinition(
            name = "private_result",
            description = "Returns a private canonical value",
            schema = buildJsonObject { },
        )

        override suspend fun execute(request: ToolExecutionRequest): ToolExecutionResult =
            ToolExecutionResult(
                toolCallId = request.toolCall.toolCallId,
                toolName = request.toolCall.toolName,
                result = JsonPrimitive(secret),
                content = listOf(
                    ToolResultTextContent(
                        text = "model-safe-result",
                        audiences = setOf(ToolResultAudience.MODEL),
                    ),
                ),
                modelResultVisible = false,
            )
    }

    private data class ScriptFingerprint(
        val status: ChatbotStatus,
        val roles: List<ChatbotMessageRole>,
        val visibleText: List<String>,
        val reasoning: List<String>,
        val toolCalls: List<String>,
        val toolResults: List<String>,
        val usage: ChatbotUsage,
        val finalStopReason: ChatbotStopReason?,
    )

    private fun ChatbotSnapshot.fingerprint() = ScriptFingerprint(
        status = status,
        roles = messages.map { it.role },
        visibleText = messages.map { it.text },
        reasoning = messages.flatMap { message -> message.reasoning.map { it.text } },
        toolCalls = messages.flatMap { message -> message.toolCalls.map { it.name } },
        toolResults = messages.flatMap { message ->
            message.toolResults.map { "${it.name}:${it.text}:${it.isError}" }
        },
        usage = usage,
        finalStopReason = messages.lastOrNull()?.stopReason,
    )

    companion object {
        private fun messageText(message: AgentMessage): String = message.parts
            .filterIsInstance<TextPart>()
            .joinToString("") { it.text }
    }
}
