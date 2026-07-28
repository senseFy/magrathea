package saien.magrathea.mcp

import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpError
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ListToolsRequest
import io.modelcontextprotocol.kotlin.sdk.types.ListToolsResult
import io.modelcontextprotocol.kotlin.sdk.types.McpJson
import io.modelcontextprotocol.kotlin.sdk.types.McpException
import io.modelcontextprotocol.kotlin.sdk.types.Method
import io.modelcontextprotocol.kotlin.sdk.types.PaginatedRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import io.modelcontextprotocol.kotlin.sdk.types.ToolListChangedNotification
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import saien.magrathea.core.ToolExecutionRequest
import saien.magrathea.core.ToolExecutionResult
import saien.magrathea.core.ToolExecutor
import saien.magrathea.core.ToolRegistry

/**
 * One initialized MCP client session exposed as a dynamic Magrathea [ToolRegistry].
 *
 * Tool definitions are refreshed on `notifications/tools/list_changed`. Host policy is evaluated
 * each time definitions are advertised or resolved, so enabling or disabling a Tool does not
 * require reconnecting the server.
 */
class McpServerConnection(
    val server: McpServer,
    private val transportFactory: McpTransportFactory,
    private val policyProvider: McpToolPolicyProvider = McpToolPolicyProvider { McpToolPolicy() },
    clientInfo: McpImplementationInfo = McpImplementationInfo(
        name = "magrathea",
        version = MAGRATHEA_MCP_SDK_VERSION,
        title = "Magrathea",
    ),
    private val options: McpConnectionOptions = McpConnectionOptions(),
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ToolRegistry {
    private val clientImplementation = clientInfo.toImplementation()
    private val lifecycleMutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val mutableState = MutableStateFlow<McpConnectionState>(McpConnectionState.Disconnected)
    private val mutableTools = MutableStateFlow<List<McpToolDescriptor>>(emptyList())
    private val mutableToolsByRuntimeName = MutableStateFlow<Map<String, McpToolDescriptor>>(emptyMap())
    private val refreshRequests = Channel<Unit>(Channel.CONFLATED)
    private val refreshWorker = scope.launch {
        for (ignored in refreshRequests) {
            try {
                refreshTools()
            } catch (_: TimeoutCancellationException) {
                // refreshTools already published a sanitized failure.
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                // refreshTools publishes a sanitized failure and clears stale Tool contracts.
            }
        }
    }

    private var activeClient: Client? = null
    private var activeTransport: McpTransportHandle? = null
    private var permanentlyClosed = false

    val state: StateFlow<McpConnectionState> = mutableState.asStateFlow()
    val tools: StateFlow<List<McpToolDescriptor>> = mutableTools.asStateFlow()

    suspend fun connect() {
        lifecycleMutex.withLock {
            check(!permanentlyClosed) { "MCP connection is closed" }
            if (activeClient != null) return
            mutableState.value = McpConnectionState.Connecting

            var handle: McpTransportHandle? = null
            var client: Client? = null
            try {
                handle = transportFactory.create()
                client = Client(clientImplementation)
                client.setNotificationHandler<ToolListChangedNotification>(
                    Method.Defined.NotificationsToolsListChanged,
                ) {
                    requestToolRefresh()
                    CompletableDeferred(Unit)
                }
                withTimeout(options.initializeTimeoutMs) {
                    client.connect(handle.transport)
                }
                activeTransport = handle
                activeClient = client
                val descriptors = listAllTools(client).map { it.toDescriptor(server) }
                val connectedState = connectedState(client, descriptors.size)
                publishTools(descriptors)
                mutableState.value = connectedState
            } catch (timeout: TimeoutCancellationException) {
                closeFailedConnection(client, handle)
                val reason = McpConnectionFailure.TRANSPORT
                mutableState.value = McpConnectionState.Failed(reason)
                throw McpOperationException(McpOperation.CONNECT, reason)
            } catch (cancelled: CancellationException) {
                closeFailedConnection(client, handle)
                mutableState.value = McpConnectionState.Disconnected
                throw cancelled
            } catch (failure: Throwable) {
                closeFailedConnection(client, handle)
                val reason = failure.toConnectionFailure()
                mutableState.value = McpConnectionState.Failed(reason)
                throw McpOperationException(McpOperation.CONNECT, reason)
            }
        }
    }

    suspend fun refreshTools() {
        lifecycleMutex.withLock {
            val client = activeClient ?: error("MCP server is not connected")
            publishTools(emptyList())
            try {
                val descriptors = listAllTools(client).map { it.toDescriptor(server) }
                val connectedState = connectedState(client, descriptors.size)
                publishTools(descriptors)
                mutableState.value = connectedState
            } catch (timeout: TimeoutCancellationException) {
                val reason = McpConnectionFailure.TRANSPORT
                mutableState.value = McpConnectionState.Failed(reason)
                throw McpOperationException(McpOperation.REFRESH_TOOLS, reason)
            } catch (cancelled: CancellationException) {
                mutableState.value = McpConnectionState.Failed(McpConnectionFailure.TRANSPORT)
                throw cancelled
            } catch (failure: Throwable) {
                val reason = failure.toConnectionFailure()
                mutableState.value = McpConnectionState.Failed(reason)
                throw McpOperationException(McpOperation.REFRESH_TOOLS, reason)
            }
        }
    }

    suspend fun disconnect() {
        lifecycleMutex.withLock {
            try {
                disconnectLocked()
            } finally {
                if (!permanentlyClosed) {
                    mutableState.value = McpConnectionState.Disconnected
                }
            }
        }
    }

    suspend fun close() {
        try {
            lifecycleMutex.withLock {
                if (permanentlyClosed) return
                permanentlyClosed = true
                try {
                    disconnectLocked()
                } finally {
                    mutableState.value = McpConnectionState.Failed(McpConnectionFailure.CLOSED)
                }
            }
        } finally {
            scope.cancel()
        }
    }

    override fun definitions() = mutableTools.value.mapNotNull { descriptor ->
        if (descriptor.compatibility != McpToolCompatibility.SUPPORTED) return@mapNotNull null
        val policy = policyProvider.policyFor(descriptor)
        descriptor.toToolDefinition(policy).takeIf { policy.enabled }
    }

    override fun find(name: String): ToolExecutor? {
        val descriptor = mutableToolsByRuntimeName.value[name]
            ?.takeIf { it.compatibility == McpToolCompatibility.SUPPORTED }
            ?: return null
        val policy = policyProvider.policyFor(descriptor)
        if (!policy.enabled) return null
        return McpToolExecutor(
            connection = this,
            descriptor = descriptor,
            policy = policy,
        )
    }

    private suspend fun callTool(
        descriptor: McpToolDescriptor,
        request: ToolExecutionRequest,
    ): ToolExecutionResult {
        val client = lifecycleMutex.withLock {
            val current = mutableToolsByRuntimeName.value[descriptor.runtimeName]
            check(current == descriptor) { "MCP Tool contract is no longer current" }
            check(current.compatibility == McpToolCompatibility.SUPPORTED) {
                "MCP Tool is not compatible with synchronous execution"
            }
            check(policyProvider.policyFor(current).enabled) { "MCP Tool is disabled" }
            activeClient ?: error("MCP server is not connected")
        }
        return try {
            val result = client.callTool(
                CallToolRequest(
                    CallToolRequestParams(
                        name = descriptor.remoteName,
                        arguments = request.toolCall.arguments as? JsonObject
                            ?: error("MCP Tool arguments must be a JSON object"),
                    ),
                ),
            )
            check(
                McpJson.encodeToString(CallToolResult.serializer(), result).length <=
                    options.maxToolResultChars,
            ) {
                "MCP Tool result exceeded the configured limit"
            }
            result.toMagratheaResult(
                server = server,
                remoteToolName = descriptor.remoteName,
                runtimeToolName = descriptor.runtimeName,
                toolTitle = descriptor.title,
                toolCallId = request.toolCall.toolCallId,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            throw McpOperationException(McpOperation.CALL_TOOL, failure.toConnectionFailure())
        }
    }

    private suspend fun listAllTools(client: Client): List<Tool> {
        if (client.serverCapabilities?.tools == null) return emptyList()
        return withTimeout(options.listToolsTimeoutMs) {
            collectMcpToolPages(options) { cursor ->
                client.listTools(ListToolsRequest(cursor?.let(::PaginatedRequestParams)))
            }
        }
    }

    private fun publishTools(descriptors: List<McpToolDescriptor>) {
        val byRuntimeName = descriptors.associateBy(McpToolDescriptor::runtimeName)
        check(byRuntimeName.size == descriptors.size) {
            "MCP Tool runtime names must be unique"
        }
        mutableTools.value = descriptors
        mutableToolsByRuntimeName.value = byRuntimeName
    }

    private fun requestToolRefresh() {
        refreshRequests.trySend(Unit)
    }

    private fun connectedState(client: Client, toolCount: Int): McpConnectionState.Connected {
        val implementation = checkNotNull(client.serverVersion) {
            "MCP server did not provide implementation information"
        }
        val instructions = client.serverInstructions?.also {
            check(it.length <= options.maxServerInstructionsChars) {
                "MCP server instructions exceeded the configured limit"
            }
        }
        return McpConnectionState.Connected(
            server = implementation.toInfo(),
            toolCount = toolCount,
            instructions = instructions,
        )
    }

    private suspend fun disconnectLocked() {
        val client = activeClient
        val handle = activeTransport
        activeClient = null
        activeTransport = null
        publishTools(emptyList())

        try {
            handle?.terminate()
        } catch (_: Throwable) {
            // Transport/session termination is best effort; resources are still closed below.
        }
        try {
            client?.close()
        } finally {
            handle?.release()
        }
    }

    private suspend fun closeFailedConnection(
        client: Client?,
        handle: McpTransportHandle?,
    ) {
        activeClient = null
        activeTransport = null
        publishTools(emptyList())
        try {
            handle?.terminate()
        } catch (_: Throwable) {
            // Preserve the connection failure.
        }
        try {
            client?.close()
        } catch (_: Throwable) {
            // Preserve the connection failure.
        } finally {
            try {
                handle?.release()
            } catch (_: Throwable) {
                // Preserve the connection failure.
            }
        }
    }

    private class McpToolExecutor(
        private val connection: McpServerConnection,
        private val descriptor: McpToolDescriptor,
        policy: McpToolPolicy,
    ) : ToolExecutor {
        override val definition = descriptor.toToolDefinition(policy)

        override suspend fun execute(request: ToolExecutionRequest): ToolExecutionResult =
            connection.callTool(descriptor, request)
    }
}

internal suspend fun collectMcpToolPages(
    options: McpConnectionOptions = McpConnectionOptions(),
    loadPage: suspend (cursor: String?) -> ListToolsResult,
): List<Tool> {
    val tools = mutableListOf<Tool>()
    val seenNames = mutableSetOf<String>()
    val seenCursors = mutableSetOf<String>()
    var encodedCharacters = 0L
    var cursor: String? = null
    var page = 0
    do {
        check(page++ < options.maxToolListPages) { "MCP Tool list exceeded the page limit" }
        val result = loadPage(cursor)
        encodedCharacters += McpJson.encodeToString(ListToolsResult.serializer(), result).length
        check(encodedCharacters <= options.maxToolListChars.toLong()) {
            "MCP Tool list exceeded the configured size limit"
        }
        result.tools.forEach { tool ->
            check(
                tool.name.isNotBlank() &&
                    tool.name.length <= 256 &&
                    tool.name.none(Char::isMcpControlCharacter),
            ) {
                "MCP server returned an invalid Tool name"
            }
            check(seenNames.add(tool.name)) {
                "MCP server returned duplicate Tool name '${tool.name}'"
            }
            check(tools.size < options.maxTools) {
                "MCP Tool list exceeded the Tool count limit"
            }
            val toolCharacters = McpJson.encodeToString(Tool.serializer(), tool).length
            check(toolCharacters <= options.maxToolDefinitionChars) {
                "MCP Tool definition exceeded the configured limit"
            }
            tools += tool
        }
        cursor = result.nextCursor
        if (cursor != null) {
            check(seenCursors.add(cursor)) { "MCP server repeated a Tool-list cursor" }
        }
    } while (cursor != null)
    return tools
}

/**
 * Aggregates a dynamic set of MCP connections without taking ownership of their lifecycle.
 */
class McpToolRegistry(
    private val connections: () -> Collection<McpServerConnection>,
) : ToolRegistry {
    override fun definitions() = connections().flatMap(McpServerConnection::definitions).also { definitions ->
        require(definitions.map { it.name }.distinct().size == definitions.size) {
            "MCP Tool registry contains duplicate runtime names"
        }
    }

    override fun find(name: String): ToolExecutor? {
        var match: ToolExecutor? = null
        connections().forEach { connection ->
            connection.find(name)?.let { candidate ->
                check(match == null) { "MCP Tool registry contains duplicate runtime name '$name'" }
                match = candidate
            }
        }
        return match
    }
}

private fun McpImplementationInfo.toImplementation() = Implementation(
    name = name,
    version = version,
    title = title,
    websiteUrl = websiteUrl,
)

private fun Implementation.toInfo() = McpImplementationInfo(
    name = name,
    version = version,
    title = title,
    websiteUrl = websiteUrl,
)

private fun Throwable.toConnectionFailure(): McpConnectionFailure = when (this) {
    is StreamableHttpError -> when (code) {
        401, 403 -> McpConnectionFailure.AUTHENTICATION
        else -> McpConnectionFailure.TRANSPORT
    }
    is McpException,
    is SerializationException,
    is IllegalArgumentException,
    is IllegalStateException,
    -> McpConnectionFailure.PROTOCOL
    else -> McpConnectionFailure.TRANSPORT
}
