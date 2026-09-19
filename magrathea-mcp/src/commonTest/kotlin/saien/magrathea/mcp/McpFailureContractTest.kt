package saien.magrathea.mcp

import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpError
import io.modelcontextprotocol.kotlin.sdk.shared.Transport
import io.modelcontextprotocol.kotlin.sdk.shared.TransportSendOptions
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.InitializeResult
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCMessage
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCRequest
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCResponse
import io.modelcontextprotocol.kotlin.sdk.types.ListToolsResult
import io.modelcontextprotocol.kotlin.sdk.types.McpException
import io.modelcontextprotocol.kotlin.sdk.types.RPCError
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.IOException
import kotlinx.serialization.json.buildJsonObject
import saien.magrathea.core.AgentMessage
import saien.magrathea.core.AgentRunId
import saien.magrathea.core.AgentSessionId
import saien.magrathea.core.MessageRole
import saien.magrathea.core.ToolCallPart
import saien.magrathea.core.ToolExecutionRequest

class McpFailureContractTest {
    @Test
    fun wrappedHttpFailuresRemainTypedAndContentFreeAcrossOperations() = runTest {
        for ((status, expected) in listOf(
            429 to McpConnectionFailure.RATE_LIMITED,
            401 to McpConnectionFailure.AUTHENTICATION,
            403 to McpConnectionFailure.AUTHENTICATION,
            503 to McpConnectionFailure.TRANSPORT,
        )) {
            for (operation in McpOperation.entries) {
                val transport = FixtureTransport()
                val connection = connection(transport)
                try {
                    if (operation != McpOperation.CONNECT) connection.connect()
                    transport.beforeRequest = {
                        throw IllegalStateException("outer-canary", StreamableHttpError(status, "body-canary"))
                    }
                    val failure = assertFailsWith<McpOperationException> { connection.invoke(operation) }
                    assertEquals(operation, failure.operation)
                    assertEquals(expected, failure.reason)
                    assertNull(failure.cause)
                    assertTrue(failure.suppressedExceptions.isEmpty())
                    assertTrue("canary" !in failure.toString())
                } finally {
                    connection.close()
                }
            }
        }
    }

    @Test
    fun ownInitializationTimeoutIsSanitizedButParentTimeoutRemainsCancellation() = runTest {
        for (parentTimeout in listOf(false, true)) {
            for (cleanupFails in listOf(false, true)) {
                val transport = FixtureTransport().apply {
                    beforeRequest = { awaitCancellation() }
                    if (cleanupFails) closeAction = { throw IOException("cleanup-canary") }
                }
                val connection = connection(transport, initializeTimeoutMs = if (parentTimeout) 100 else 10)
                try {
                    if (parentTimeout) {
                        val result = withTimeoutOrNull(10) { connection.connect(); true }
                        assertNull(result)
                        assertEquals(McpConnectionState.Disconnected, connection.state.value)
                    } else {
                        val failure = assertFailsWith<McpOperationException> { connection.connect() }
                        assertEquals(McpConnectionFailure.TRANSPORT, failure.reason)
                        assertIs<McpConnectionState.Failed>(connection.state.value)
                    }
                } finally {
                    connection.close()
                }
            }
        }
    }

    @Test
    fun initializationFailureSurvivesOrdinaryCleanupFailure() = runTest {
        for (cleanupCancelled in listOf(false, true)) {
            for ((primary, expected) in listOf(
                CancellationException("cancelled") to null,
                StreamableHttpError(429, "body-canary") to McpConnectionFailure.RATE_LIMITED,
                StreamableHttpError(401, "body-canary") to McpConnectionFailure.AUTHENTICATION,
                IOException("network-canary") to McpConnectionFailure.TRANSPORT,
                McpException(RPCError.ErrorCode.INTERNAL_ERROR, "protocol-canary") to McpConnectionFailure.PROTOCOL,
            )) {
                var released = false
                val transport = FixtureTransport().apply {
                    beforeRequest = { throw primary }
                    closeAction = {
                        if (cleanupCancelled) throw CancellationException("cleanup-canary")
                        throw IllegalStateException("cleanup-canary")
                    }
                }
                val connection = connection(transport, release = { released = true })
                try {
                    if (primary is CancellationException) {
                        val actual = assertFailsWith<CancellationException> { connection.connect() }
                        assertTrue(actual.mcpCauseChain().any { it === primary })
                        assertEquals(McpConnectionState.Disconnected, connection.state.value)
                    } else {
                        val actual = assertFailsWith<McpOperationException> { connection.connect() }
                        assertEquals(McpOperation.CONNECT, actual.operation)
                        assertEquals(expected, actual.reason)
                        assertNull(actual.cause)
                        assertTrue(actual.suppressedExceptions.isEmpty())
                        assertTrue("canary" !in actual.toString())
                    }
                    assertTrue(released)
                } finally {
                    connection.close()
                }
            }
        }
    }

    @Test
    fun wrappedIoFailuresAreTransportFailuresButRpcErrorsRemainProtocolFailures() = runTest {
        for (operation in McpOperation.entries) {
            for (networkFailure in listOf(false, true)) {
                val transport = FixtureTransport()
                val connection = connection(transport)
                try {
                    if (operation != McpOperation.CONNECT) connection.connect()
                    transport.beforeRequest = {
                        throw McpException(
                            RPCError.ErrorCode.INTERNAL_ERROR,
                            "outer-canary",
                            cause = if (networkFailure) IOException("network-canary") else null,
                        )
                    }
                    val actual = assertFailsWith<McpOperationException> { connection.invoke(operation) }
                    assertEquals(operation, actual.operation)
                    assertEquals(
                        if (networkFailure) McpConnectionFailure.TRANSPORT else McpConnectionFailure.PROTOCOL,
                        actual.reason,
                    )
                    assertNull(actual.cause)
                    assertTrue(actual.suppressedExceptions.isEmpty())
                } finally {
                    connection.close()
                }
            }
        }
    }

    @Test
    fun closeFailureAfterInitializationIsNotHiddenByAnEarlierRequestFailure() = runTest {
        val transport = FixtureTransport()
        val connection = connection(transport)
        val closeFailure = IOException("close failure")
        try {
            connection.connect()
            transport.beforeRequest = { throw StreamableHttpError(429, "rate limited") }
            assertEquals(
                McpConnectionFailure.RATE_LIMITED,
                assertFailsWith<McpOperationException> { connection.refreshTools() }.reason,
            )
            transport.closeAction = { throw closeFailure }
            assertSame(closeFailure, assertFailsWith<IOException> { connection.disconnect() })
        } finally {
            connection.close()
        }
    }

    @Test
    fun ownRefreshTimeoutIsSanitizedButParentTimeoutRemainsCancellation() = runTest {
        for (parentTimeout in listOf(false, true)) {
            val transport = FixtureTransport()
            val connection = connection(transport, listToolsTimeoutMs = if (parentTimeout) 100 else 10)
            try {
                connection.connect()
                transport.beforeRequest = { awaitCancellation() }
                if (parentTimeout) {
                    assertNull(withTimeoutOrNull(10) { connection.refreshTools(); true })
                } else {
                    val failure = assertFailsWith<McpOperationException> { connection.refreshTools() }
                    assertEquals(McpOperation.REFRESH_TOOLS, failure.operation)
                    assertEquals(McpConnectionFailure.TRANSPORT, failure.reason)
                }
                assertTrue(connection.tools.value.isEmpty())
            } finally {
                connection.close()
            }
        }
    }

    @Test
    fun cancellationIdentityAndWrappedFatalErrorsSurviveEveryOperation() = runTest {
        for (operation in McpOperation.entries) {
            for (kind in listOf("cancel", "fatal", "wrapped-fatal", "cancelled-fatal")) {
                val fatal = object : Error("fatal-canary") {}
                val cancelled = CancellationException("cancelled")
                val thrown = when (kind) {
                    "cancel" -> cancelled
                    "fatal" -> fatal
                    "wrapped-fatal" -> IllegalStateException("wrapped", fatal)
                    else -> CancellationException("wrapped cancellation", fatal)
                }
                val transport = FixtureTransport()
                val connection = connection(transport)
                try {
                    if (operation != McpOperation.CONNECT) connection.connect()
                    transport.beforeRequest = { throw thrown }
                    val actual = assertFailsWith<Throwable> { connection.invoke(operation) }
                    if (kind == "cancel") {
                        assertIs<CancellationException>(actual)
                        // JVM coroutine stacktrace recovery may copy cancellation and retain its cause.
                        assertTrue(actual.mcpCauseChain().any { it === cancelled })
                    } else {
                        assertSame(fatal, actual)
                    }
                } finally {
                    val cleanupFailure = runCatching { connection.close() }.exceptionOrNull()
                    if (cleanupFailure != null) assertSame(fatal, cleanupFailure)
                }
            }
        }
    }

    @Test
    fun fatalCleanupWinsAfterEveryReleaseWasAttempted() = runTest {
        for (duringConnect in listOf(false, true)) {
            val steps = mutableListOf<String>()
            val fatal = object : Error("cleanup fatal") {}
            val transport = FixtureTransport().apply {
                closeAction = { steps += "close"; throw IllegalStateException("close failure") }
                if (duringConnect) beforeRequest = { throw StreamableHttpError(429, "rate limited") }
            }
            val connection = connection(
                transport,
                terminate = { steps += "terminate"; throw IllegalStateException("wrapped", fatal) },
                release = { steps += "release"; throw IllegalArgumentException("release failure") },
            )
            try {
                if (duringConnect) {
                    assertSame(fatal, assertFailsWith<Error> { connection.connect() })
                } else {
                    connection.connect()
                    assertSame(fatal, assertFailsWith<Error> { connection.disconnect() })
                }
                // The official client may close before our cleanup on initialization failure.
                assertEquals(listOf("close", "release", "terminate"), steps.sorted())
                assertEquals("release", steps.last())
            } finally {
                connection.close()
            }
        }
    }

    @Test
    fun fatalPrimaryIsNotReplacedByCleanupFailure() = runTest {
        val fatal = object : Error("primary fatal") {}
        var released = false
        val transport = FixtureTransport().apply {
            beforeRequest = { throw IllegalStateException("wrapped", fatal) }
            closeAction = { throw IllegalStateException("close failure") }
        }
        val connection = connection(
            transport,
            terminate = { throw IllegalStateException("terminate failure") },
            release = { released = true; throw object : Error("later fatal") {} },
        )
        try {
            assertSame(fatal, assertFailsWith<Error> { connection.connect() })
            assertTrue(released)
        } finally {
            connection.close()
        }
    }

    private fun TestScope.connection(
        transport: FixtureTransport,
        initializeTimeoutMs: Long = 1_000,
        listToolsTimeoutMs: Long = 1_000,
        terminate: suspend () -> Unit = {},
        release: suspend () -> Unit = {},
    ) = McpServerConnection(
        server = McpServer("fixture", "Fixture"),
        transportFactory = McpTransportFactory { McpTransportHandle(transport, terminate, release) },
        options = McpConnectionOptions(
            initializeTimeoutMs = initializeTimeoutMs,
            listToolsTimeoutMs = listToolsTimeoutMs,
        ),
        dispatcher = StandardTestDispatcher(testScheduler),
    )

    private suspend fun McpServerConnection.invoke(operation: McpOperation) {
        when (operation) {
            McpOperation.CONNECT -> connect()
            McpOperation.REFRESH_TOOLS -> refreshTools()
            McpOperation.CALL_TOOL -> {
                val tool = checkNotNull(find(definitions().single().name))
                tool.execute(
                    ToolExecutionRequest(
                        sessionId = AgentSessionId("session"),
                        runId = AgentRunId("run"),
                        executionId = "execution",
                        assistantMessage = AgentMessage(role = MessageRole.ASSISTANT, parts = emptyList()),
                        toolCall = ToolCallPart("call", tool.definition.name, buildJsonObject {}),
                    ),
                )
            }
        }
    }
}

private class FixtureTransport : Transport {
    var beforeRequest: suspend () -> Unit = {}
    var closeAction: suspend () -> Unit = {}
    private var receive: suspend (JSONRPCMessage) -> Unit = {}
    private var closed: () -> Unit = {}

    override suspend fun start() = Unit

    override suspend fun send(message: JSONRPCMessage, options: TransportSendOptions?) {
        if (message !is JSONRPCRequest) return
        beforeRequest()
        val result = when (message.method) {
            "initialize" -> InitializeResult(
                protocolVersion = "2025-11-25",
                capabilities = ServerCapabilities(tools = ServerCapabilities.Tools()),
                serverInfo = Implementation("fixture", "1"),
            )
            "tools/list" -> ListToolsResult(listOf(Tool("echo", inputSchema = ToolSchema())))
            "tools/call" -> CallToolResult(content = listOf(TextContent("ok")))
            else -> error("Unexpected method: ${message.method}")
        }
        receive(JSONRPCResponse(message.id, result))
    }

    override suspend fun close() {
        try {
            closeAction()
        } finally {
            closed()
        }
    }

    override fun onClose(block: () -> Unit) { closed = block }
    override fun onError(block: (Throwable) -> Unit) = Unit
    override fun onMessage(block: suspend (JSONRPCMessage) -> Unit) { receive = block }
}
