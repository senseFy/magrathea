package saien.magrathea.tooling.mcp.conformance

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.sse.SSE
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import saien.magrathea.core.AgentMessage
import saien.magrathea.core.AgentSessionId
import saien.magrathea.core.MessageRole
import saien.magrathea.core.ToolCallPart
import saien.magrathea.core.ToolExecutionRequest
import saien.magrathea.mcp.McpServer
import saien.magrathea.mcp.McpServerConnection
import saien.magrathea.mcp.McpToolPolicy
import saien.magrathea.mcp.McpToolPolicyProvider
import saien.magrathea.mcp.streamableHttpMcpTransportFactory

fun main(args: Array<String>) = runBlocking {
    require(args.size == 1) { "Expected the MCP conformance server URL" }
    val scenario = System.getenv("MCP_CONFORMANCE_SCENARIO").orEmpty()
    require(scenario in SUPPORTED_SCENARIOS) {
        "Unsupported MCP conformance scenario: $scenario"
    }
    val client = HttpClient(OkHttp) {
        install(SSE)
        followRedirects = false
    }
    val connection = McpServerConnection(
        server = McpServer("conformance", "MCP conformance"),
        transportFactory = streamableHttpMcpTransportFactory(client, args.single()),
        policyProvider = McpToolPolicyProvider {
            McpToolPolicy(enabled = true, requiresApproval = false)
        },
    )
    try {
        connection.connect()
        if (scenario == "tools_call") {
            val definition = connection.tools.value
                .single { descriptor -> descriptor.remoteName == "add_numbers" }
            val executor = requireNotNull(connection.find(definition.runtimeName))
            val result = executor.execute(
                ToolExecutionRequest(
                    sessionId = AgentSessionId("mcp-conformance"),
                    assistantMessage = AgentMessage(
                        role = MessageRole.ASSISTANT,
                        parts = emptyList(),
                    ),
                    toolCall = ToolCallPart(
                        toolCallId = "add-numbers",
                        toolName = definition.runtimeName,
                        arguments = buildJsonObject {
                            put("a", 2)
                            put("b", 3)
                        },
                    ),
                ),
            )
            check(!result.isError) { "Conformance Tool returned an error" }
        }
    } finally {
        connection.close()
        client.close()
    }
}

internal val SUPPORTED_SCENARIOS = setOf("initialize", "tools_call")
