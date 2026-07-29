package saien.magrathea.runtime

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.Test
import saien.magrathea.core.AgentEvent
import saien.magrathea.core.AgentEngineConfig
import saien.magrathea.core.AgentMessage
import saien.magrathea.core.AgentRequest
import saien.magrathea.core.MessageRole
import saien.magrathea.core.ModelDescriptor
import saien.magrathea.core.RuntimeConfig
import saien.magrathea.core.StopReason
import saien.magrathea.core.TextPart
import saien.magrathea.core.ToolApprovalDecision
import saien.magrathea.core.ToolApprovalGateway
import saien.magrathea.core.ToolCallPart
import saien.magrathea.core.ToolDefinition
import saien.magrathea.core.ToolExecutionRequest
import saien.magrathea.core.ToolExecutionResult
import saien.magrathea.core.ToolExecutor
import saien.magrathea.core.ToolPermissionGateway
import saien.magrathea.core.ToolRegistry
import saien.magrathea.provider.api.InMemoryProviderRegistry
import saien.magrathea.provider.api.ProviderAdapter
import saien.magrathea.provider.api.ProviderChunk

class ToolSafetyContractTest {
    @Test
    fun requiredPermission_withoutGateway_isDeniedAndNotExecuted() = runTest {
        val tool = CountingTool(requiresPermission = "write-files")
        val result = runToolCall(tool = tool, advertised = listOf(tool.definition))

        assertEquals(0, tool.executionCount)
        assertEquals("Permission gateway unavailable", result.displayText)
        assertTrue(result.isError)
    }

    @Test
    fun requiredApproval_withoutGateway_isDeniedAndNotExecuted() = runTest {
        val tool = CountingTool(requiresApproval = true)
        val result = runToolCall(tool = tool, advertised = listOf(tool.definition))

        assertEquals(0, tool.executionCount)
        assertEquals("Approval gateway unavailable", result.displayText)
        assertTrue(result.isError)
    }

    @Test
    fun explicitAllowGateways_executeOnce() = runTest {
        val tool = CountingTool(requiresPermission = "write-files", requiresApproval = true)
        val result = runToolCall(
            tool = tool,
            advertised = listOf(tool.definition),
            permissionGateway = object : ToolPermissionGateway {
                override suspend fun ensurePermission(permission: String): Boolean = true
            },
            approvalGateway = object : ToolApprovalGateway {
                override suspend fun requestApproval(request: saien.magrathea.core.ToolApprovalRequest): ToolApprovalDecision =
                    ToolApprovalDecision.Approve
            },
        )

        assertEquals(1, tool.executionCount)
        assertEquals("executed", result.displayText)
    }

    @Test
    fun unadvertisedRegisteredTool_isRejectedWithoutRegistryLookupOrExecution() = runTest {
        val tool = CountingTool()
        val registry = RecordingToolRegistry(tool)
        val result = runToolCall(tool = tool, advertised = emptyList(), registry = registry)

        assertEquals(0, registry.lookupCount)
        assertEquals(0, tool.executionCount)
        assertEquals("Tool not advertised", result.displayText)
        assertTrue(result.isError)
    }

    @Test
    fun advertisedButUnregisteredTool_returnsToolNotFound() = runTest {
        val advertised = CountingTool().definition
        val result = runToolCall(
            tool = null,
            advertised = listOf(advertised),
            registry = RecordingToolRegistry(null),
        )

        assertEquals("Tool not found", result.displayText)
        assertTrue(result.isError)
    }

    @Test
    fun advertisedRegisteredTool_executesOnce() = runTest {
        val tool = CountingTool()
        val result = runToolCall(tool = tool, advertised = listOf(tool.definition))

        assertEquals(1, tool.executionCount)
        assertEquals("executed", result.displayText)
    }

    @Test
    fun partialToolCall_isRejectedWithoutRegistryLookupOrExecution() = runTest {
        val tool = CountingTool()
        val registry = RecordingToolRegistry(tool)
        val result = runToolCall(
            tool = tool,
            advertised = listOf(tool.definition),
            registry = registry,
            toolCall = validToolCall.copy(partial = true),
        )

        assertEquals(0, registry.lookupCount)
        assertEquals(0, tool.executionCount)
        assertEquals("Tool call is not finalized", result.displayText)
        assertTrue(result.isError)
    }

    @Test
    fun malformedFinalArguments_areRejectedWithoutExecution() = runTest {
        val tool = CountingTool()
        val registry = RecordingToolRegistry(tool)
        val result = runToolCall(
            tool = tool,
            advertised = listOf(tool.definition),
            registry = registry,
            toolCall = validToolCall.copy(arguments = JsonPrimitive("not-an-object")),
        )

        assertEquals(0, registry.lookupCount)
        assertEquals(0, tool.executionCount)
        assertEquals("Tool arguments must be a JSON object", result.displayText)
        assertTrue(result.isError)
    }

    @Test
    fun partialJsonMarkerWithFinalFlag_isRejectedWithoutExecution() = runTest {
        val tool = CountingTool()
        val registry = RecordingToolRegistry(tool)
        val result = runToolCall(
            tool = tool,
            advertised = listOf(tool.definition),
            registry = registry,
            toolCall = validToolCall.copy(
                arguments = buildJsonObject { put("partial_json", "{\"value\":") },
                partial = false,
            ),
        )

        assertEquals(0, registry.lookupCount)
        assertEquals(0, tool.executionCount)
        assertEquals("Tool call is not finalized", result.displayText)
        assertTrue(result.isError)
    }

    @Test
    fun missingToolIdentity_isRejectedBeforeRegistryLookup() = runTest {
        val tool = CountingTool()
        val blankIdRegistry = RecordingToolRegistry(tool)
        val blankIdResult = runToolCall(
            tool = tool,
            advertised = listOf(tool.definition),
            registry = blankIdRegistry,
            toolCall = validToolCall.copy(toolCallId = ""),
        )
        val blankNameRegistry = RecordingToolRegistry(tool)
        val blankNameResult = runToolCall(
            tool = tool,
            advertised = listOf(tool.definition),
            registry = blankNameRegistry,
            toolCall = validToolCall.copy(toolName = ""),
        )

        assertEquals(0, blankIdRegistry.lookupCount)
        assertEquals("Tool call ID is missing", blankIdResult.displayText)
        assertEquals(0, blankNameRegistry.lookupCount)
        assertEquals("Tool call name is missing", blankNameResult.displayText)
        assertEquals(0, tool.executionCount)
    }

    @Test
    fun oversizedToolResult_isReplacedBeforeItCanEnterConversationState() = runTest {
        val oversized = "x".repeat(512)
        val tool = CountingTool(resultText = oversized)
        val result = runToolCall(
            tool = tool,
            advertised = listOf(tool.definition),
            runtimeConfig = RuntimeConfig(maxToolResultChars = 128),
        )

        assertEquals(1, tool.executionCount)
        assertTrue(result.isError)
        assertEquals("Tool result exceeded runtime limit", result.displayText)
        assertFalse(result.toString().contains(oversized))
    }

    private suspend fun runToolCall(
        tool: CountingTool?,
        advertised: List<ToolDefinition>,
        registry: ToolRegistry = RecordingToolRegistry(tool),
        toolCall: ToolCallPart = validToolCall,
        permissionGateway: ToolPermissionGateway? = null,
        approvalGateway: ToolApprovalGateway? = null,
        runtimeConfig: RuntimeConfig = RuntimeConfig(),
    ): ToolExecutionResult {
        val provider = SingleToolCallProvider(toolCall)
        val runner = DefaultAgentRunner(
            providerRegistry = InMemoryProviderRegistry(listOf(provider)),
            toolRegistry = registry,
            persistence = InMemoryAgentPersistence(),
            permissionGateway = permissionGateway,
            approvalGateway = approvalGateway,
        )
        val events = runner.run(
            AgentRequest(
                messages = listOf(AgentMessage(role = MessageRole.USER, parts = listOf(TextPart("use tool")))),
                model = ModelDescriptor(provider = provider.key, model = "contract-model"),
                tools = advertised,
                engine = AgentEngineConfig(runtime = runtimeConfig),
            ),
        ).toList()
        return events.filterIsInstance<AgentEvent.ToolCompleted>().single().result
    }

    private class SingleToolCallProvider(
        private val toolCall: ToolCallPart,
    ) : ProviderAdapter {
        override val key: String = "tool-safety-contract-provider"

        override suspend fun generate(request: saien.magrathea.provider.api.ProviderRequest): Flow<ProviderChunk> = flow {
            if (request.messages.lastOrNull()?.role == MessageRole.TOOL) {
                emit(providerChunk(text = "done", completed = true))
            } else {
                emit(providerChunk(toolCalls = listOf(toolCall), completed = true))
            }
        }
    }

    private class CountingTool(
        requiresPermission: String? = null,
        requiresApproval: Boolean = false,
        private val resultText: String = "executed",
    ) : ToolExecutor {
        override val definition = ToolDefinition(
            name = "contract_tool",
            description = "Tool safety contract",
            schema = buildJsonObject { },
            requiresPermission = requiresPermission,
            requiresApproval = requiresApproval,
        )
        var executionCount: Int = 0

        override suspend fun execute(request: ToolExecutionRequest): ToolExecutionResult {
            executionCount += 1
            return ToolExecutionResult(
                toolCallId = request.toolCall.toolCallId,
                toolName = request.toolCall.toolName,
                result = JsonPrimitive(resultText),
                displayText = resultText,
            )
        }
    }

    private class RecordingToolRegistry(
        private val tool: ToolExecutor?,
    ) : ToolRegistry {
        var lookupCount: Int = 0

        override fun definitions(): List<ToolDefinition> = listOfNotNull(tool?.definition)

        override fun find(name: String): ToolExecutor? {
            lookupCount += 1
            return tool?.takeIf { it.definition.name == name }
        }
    }

    companion object {
        private val validArguments: JsonElement = buildJsonObject { put("value", "safe") }
        private val validToolCall = ToolCallPart(
            toolCallId = "contract-call-1",
            toolName = "contract_tool",
            arguments = validArguments,
        )
    }
}
