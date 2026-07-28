package saien.magrathea.mcp

import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import saien.magrathea.core.AgentMessage
import saien.magrathea.core.AgentSessionId
import saien.magrathea.core.MessageRole
import saien.magrathea.core.ToolCallPart
import saien.magrathea.core.ToolExecutionRequest

class JvmMcpStdioProcessTest {
    @Test
    fun initializesDiscoversAndCallsThroughARealChildProcess() = runBlocking {
        val javaExecutable = Paths.get(
            System.getProperty("java.home"),
            "bin",
            if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java",
        ).toAbsolutePath().toString()
        val fixtureClasses = Paths.get(
            McpStdioFixtureMain::class.java.protectionDomain.codeSource.location.toURI(),
        ).toAbsolutePath().toString()
        val connection = McpServerConnection(
            server = McpServer("stdio", "Stdio fixture"),
            transportFactory = jvmStdioMcpTransportFactory(
                JvmMcpStdioProcess(
                    command = javaExecutable,
                    arguments = listOf(
                        "-cp",
                        fixtureClasses,
                        McpStdioFixtureMain::class.java.name,
                    ),
                ),
            ),
            policyProvider = McpToolPolicyProvider {
                McpToolPolicy(enabled = true, requiresApproval = false)
            },
        )

        try {
            withTimeout(10_000) { connection.connect() }
            val definition = connection.definitions().single()
            val result = withTimeout(10_000) {
                assertNotNull(connection.find(definition.name)).execute(
                    ToolExecutionRequest(
                        sessionId = AgentSessionId("stdio-session"),
                        assistantMessage = AgentMessage(
                            role = MessageRole.ASSISTANT,
                            parts = emptyList(),
                        ),
                        toolCall = ToolCallPart(
                            toolCallId = "stdio-call",
                            toolName = definition.name,
                            arguments = buildJsonObject { put("value", "stdio") },
                        ),
                    ),
                )
            }

            assertFalse(result.isError)
            assertEquals(
                "stdio",
                (result.result as JsonObject).getValue("echo").jsonPrimitive.content,
            )
            assertEquals("Echo: stdio", result.displayText)
        } finally {
            withTimeout(10_000) { connection.close() }
        }
    }

    @Test
    fun childEnvironmentContainsOnlyExplicitEntries() {
        val builder = JvmMcpStdioProcess(
            command = "/usr/bin/example-mcp",
            environment = mapOf("VISIBLE_TO_MCP" to "value"),
        ).toProcessBuilder()

        assertEquals(setOf("VISIBLE_TO_MCP"), builder.environment().keys)
        assertEquals("value", builder.environment().getValue("VISIBLE_TO_MCP"))
    }

    @Test
    fun processToStringOmitsArgumentsEnvironmentValuesAndWorkingDirectory() {
        val canary = "MAG_MCP_STDIO_TOSTRING_CANARY"
        val process = JvmMcpStdioProcess(
            command = "/usr/bin/example-mcp",
            arguments = listOf("--token", canary),
            workingDirectory = "/tmp/$canary",
            environment = mapOf("MCP_API_KEY" to canary),
        )

        val rendered = process.toString()

        assertFalse(rendered.contains(canary))
        assertFalse(rendered.contains("--token"))
        assertTrue(rendered.contains("command=<configured>"))
        assertTrue(rendered.contains("environmentNames=[MCP_API_KEY]"))
    }

    @Test
    fun rejectsInvalidProcessShape() {
        assertFailsWith<IllegalArgumentException> {
            JvmMcpStdioProcess(command = " command ")
        }
        assertFailsWith<IllegalArgumentException> {
            JvmMcpStdioProcess(
                command = "/usr/bin/example-mcp",
                environment = mapOf("BAD=NAME" to "value"),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            JvmMcpStdioProcess(
                command = "/usr/bin/example-mcp",
                arguments = listOf("bad\u0000argument"),
            )
        }
    }
}
