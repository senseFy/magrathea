@file:OptIn(io.modelcontextprotocol.kotlin.sdk.ExperimentalMcpApi::class)

package saien.magrathea.mcp

import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.testing.ChannelTransport
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ListToolsResult
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TaskSupport
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import io.modelcontextprotocol.kotlin.sdk.types.ToolExecution
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import saien.magrathea.core.AgentMessage
import saien.magrathea.core.AgentSessionId
import saien.magrathea.core.MessageRole
import saien.magrathea.core.ToolCallPart
import saien.magrathea.core.ToolExecutionRequest

class McpServerConnectionTest {
    @Test
    fun connectionFailureDoesNotExposeRawTransportMessage() = runTest {
        val canary = "MAG_MCP_RAW_FAILURE_CANARY"
        val connection = McpServerConnection(
            server = McpServer("failure", "Failure"),
            transportFactory = McpTransportFactory {
                throw RuntimeException("raw response body: $canary")
            },
        )

        val failure = assertFailsWith<McpOperationException> {
            connection.connect()
        }

        assertEquals(McpOperation.CONNECT, failure.operation)
        assertEquals(McpConnectionFailure.TRANSPORT, failure.reason)
        assertFalse(failure.toString().contains(canary))
        connection.close()
    }

    @Test
    fun collectsEveryToolPageAndRejectsRepeatedServerState() = runTest {
        val first = Tool(
            name = "first",
            description = "First",
            inputSchema = ToolSchema(),
        )
        val second = Tool(
            name = "second",
            description = "Second",
            inputSchema = ToolSchema(),
        )

        val tools = collectMcpToolPages { cursor ->
            when (cursor) {
                null -> ListToolsResult(listOf(first), nextCursor = "next")
                "next" -> ListToolsResult(listOf(second))
                else -> error("Unexpected cursor")
            }
        }

        assertEquals(listOf("first", "second"), tools.map(Tool::name))
        assertFailsWith<IllegalStateException> {
            collectMcpToolPages(McpConnectionOptions(maxTools = 1)) { cursor ->
                when (cursor) {
                    null -> ListToolsResult(listOf(first), nextCursor = "next")
                    else -> ListToolsResult(listOf(second))
                }
            }
        }
        assertFailsWith<IllegalStateException> {
            collectMcpToolPages {
                ListToolsResult(listOf(first), nextCursor = "same")
            }
        }
        assertFailsWith<IllegalStateException> {
            collectMcpToolPages(McpConnectionOptions(maxToolListChars = 128)) {
                ListToolsResult(
                    tools = emptyList(),
                    nextCursor = "x".repeat(256),
                )
            }
        }
        var duplicatePage = 0
        assertFailsWith<IllegalStateException> {
            collectMcpToolPages {
                duplicatePage += 1
                ListToolsResult(
                    tools = listOf(first),
                    nextCursor = "page-$duplicatePage".takeIf { duplicatePage == 1 },
                )
            }
        }
    }

    @Test
    fun discoversCallsAndRefreshesOfficialMcpTools() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val linked = ChannelTransport.createLinkedPair(dispatcher = dispatcher)
        val server = testServer()
        val serverSession = async {
            server.createSession(linked.serverTransport)
        }
        var enabledTools = setOf("echo")
        val connection = McpServerConnection(
            server = McpServer("test-server", "Test server"),
            transportFactory = McpTransportFactory {
                McpTransportHandle(linked.clientTransport)
            },
            policyProvider = McpToolPolicyProvider { descriptor ->
                McpToolPolicy(
                    enabled = descriptor.remoteName in enabledTools,
                    requiresApproval = false,
                    timeoutMs = 5_000,
                )
            },
            dispatcher = dispatcher,
        )

        try {
            connection.connect()
            val connectedServerSession = serverSession.await()

            val connected = assertIs<McpConnectionState.Connected>(connection.state.value)
            assertEquals("fixture-server", connected.server.name)
            assertEquals(2, connected.toolCount)
            assertEquals(2, connection.tools.value.size)

            val definition = connection.definitions().single()
            assertEquals("mcp__test-server__echo", definition.name)
            assertFalse(definition.requiresApproval)
            assertEquals("object", definition.schema.getValue("type").jsonPrimitive.content)
            assertNull(connection.find(McpToolNames.runtimeName("test-server", "task-only")))

            val executor = assertNotNull(connection.find(definition.name))
            val result = executor.execute(
                ToolExecutionRequest(
                    sessionId = AgentSessionId("session"),
                    assistantMessage = AgentMessage(
                        role = MessageRole.ASSISTANT,
                        parts = emptyList(),
                    ),
                    toolCall = ToolCallPart(
                        toolCallId = "call-1",
                        toolName = definition.name,
                        arguments = buildJsonObject { put("value", "hello") },
                    ),
                ),
            )
            assertFalse(result.isError)
            assertEquals("hello", result.result.jsonObjectValue("echo").jsonPrimitive.content)
            assertEquals("Echo: hello", result.displayText)
            assertEquals(
                "echo",
                result.metadata.getValue("mcpToolName").jsonPrimitive.content,
            )
            assertEquals(
                McpToolIdentity(
                    serverId = "test-server",
                    serverName = "Test server",
                    remoteToolName = "echo",
                    toolTitle = "echo",
                ),
                result.metadata.mcpToolIdentityOrNull(),
            )

            server.removeTool("echo")
            server.addTool(
                name = "echo",
                description = "Changed contract",
                inputSchema = ToolSchema(
                    properties = buildJsonObject {
                        put("replacement", buildJsonObject { put("type", "string") })
                    },
                ),
            ) {
                CallToolResult(content = listOf(TextContent("replacement")))
            }
            connectedServerSession.sendToolListChanged()
            testScheduler.advanceUntilIdle()
            assertEquals(
                "Changed contract",
                connection.tools.value.single { it.remoteName == "echo" }.description,
            )
            assertFailsWith<IllegalStateException> {
                executor.execute(
                    ToolExecutionRequest(
                        sessionId = AgentSessionId("session"),
                        assistantMessage = AgentMessage(
                            role = MessageRole.ASSISTANT,
                            parts = emptyList(),
                        ),
                        toolCall = ToolCallPart(
                            toolCallId = "stale-call",
                            toolName = definition.name,
                            arguments = buildJsonObject { put("value", "stale") },
                        ),
                    ),
                )
            }

            enabledTools = setOf("echo", "later")
            server.addTool(
                name = "later",
                description = "Added after initialization",
            ) {
                CallToolResult(content = listOf(TextContent("later")))
            }
            connectedServerSession.sendToolListChanged()
            testScheduler.advanceUntilIdle()
            assertTrue(connection.tools.value.any { it.remoteName == "later" })
            assertTrue(connection.definitions().any { it.name.endsWith("__later") })
        } finally {
            connection.close()
            server.close()
        }
    }

    @Test
    fun failedRefreshClearsPreviouslyAdvertisedTools() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val linked = ChannelTransport.createLinkedPair(dispatcher = dispatcher)
        val server = testServer()
        val serverSession = async { server.createSession(linked.serverTransport) }
        val connection = McpServerConnection(
            server = McpServer("refresh", "Refresh failure"),
            transportFactory = McpTransportFactory {
                McpTransportHandle(linked.clientTransport)
            },
            options = McpConnectionOptions(listToolsTimeoutMs = 1_000),
            dispatcher = dispatcher,
        )

        try {
            connection.connect()
            serverSession.await()
            assertTrue(connection.tools.value.isNotEmpty())

            server.close()
            assertFailsWith<Throwable> { connection.refreshTools() }

            assertTrue(connection.tools.value.isEmpty())
            assertIs<McpConnectionState.Failed>(connection.state.value)
        } finally {
            connection.close()
            server.close()
        }
    }

    @Test
    fun rejectsToolResultsAboveTheConnectionLimit() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val linked = ChannelTransport.createLinkedPair(dispatcher = dispatcher)
        val server = Server(
            serverInfo = Implementation("large-result", "1.0.0"),
            options = ServerOptions(
                capabilities = ServerCapabilities(
                    tools = ServerCapabilities.Tools(),
                ),
            ),
        ).apply {
            addTool(name = "large", description = "Returns too much data") {
                CallToolResult(content = listOf(TextContent("x".repeat(512))))
            }
        }
        val serverSession = async { server.createSession(linked.serverTransport) }
        val connection = McpServerConnection(
            server = McpServer("limits", "Limits"),
            transportFactory = McpTransportFactory {
                McpTransportHandle(linked.clientTransport)
            },
            policyProvider = McpToolPolicyProvider {
                McpToolPolicy(enabled = true, requiresApproval = false)
            },
            options = McpConnectionOptions(maxToolResultChars = 128),
            dispatcher = dispatcher,
        )

        try {
            connection.connect()
            serverSession.await()
            val definition = connection.definitions().single()
            val executor = assertNotNull(connection.find(definition.name))

            assertFailsWith<IllegalStateException> {
                executor.execute(
                    ToolExecutionRequest(
                        sessionId = AgentSessionId("limits"),
                        assistantMessage = AgentMessage(
                            role = MessageRole.ASSISTANT,
                            parts = emptyList(),
                        ),
                        toolCall = ToolCallPart(
                            toolCallId = "large-call",
                            toolName = definition.name,
                            arguments = buildJsonObject { },
                        ),
                    ),
                )
            }
        } finally {
            connection.close()
            server.close()
        }
    }

    @Test
    fun defaultPolicyRequiresHostApproval() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val linked = ChannelTransport.createLinkedPair(dispatcher = dispatcher)
        val server = testServer()
        val serverSession = async { server.createSession(linked.serverTransport) }
        val connection = McpServerConnection(
            server = McpServer("safe", "Safe default"),
            transportFactory = McpTransportFactory {
                McpTransportHandle(linked.clientTransport)
            },
            dispatcher = dispatcher,
        )

        try {
            connection.connect()
            serverSession.await()
            assertTrue(connection.definitions().single { it.name.endsWith("__echo") }.requiresApproval)
        } finally {
            connection.close()
            server.close()
        }
    }

    private fun testServer(): Server = Server(
        serverInfo = Implementation("fixture-server", "1.0.0", title = "Fixture server"),
        options = ServerOptions(
            capabilities = ServerCapabilities(
                tools = ServerCapabilities.Tools(listChanged = true),
            ),
        ),
    ).apply {
        addTool(
            name = "echo",
            description = "Echo one value",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    put(
                        "value",
                        buildJsonObject {
                            put("type", "string")
                        },
                    )
                },
                required = listOf("value"),
            ),
        ) { request ->
            val value = request.arguments?.get("value")?.jsonPrimitive?.content.orEmpty()
            CallToolResult(
                content = listOf(TextContent("Echo: $value")),
                structuredContent = buildJsonObject { put("echo", value) },
            )
        }
        addTool(
            name = "task-only",
            description = "Requires MCP Tasks",
            execution = ToolExecution(TaskSupport.Required),
        ) {
            CallToolResult(content = listOf(TextContent("task")))
        }
    }
}

private fun kotlinx.serialization.json.JsonElement.jsonObjectValue(name: String) =
    (this as kotlinx.serialization.json.JsonObject).getValue(name)
