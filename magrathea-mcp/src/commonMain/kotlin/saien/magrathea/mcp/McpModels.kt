package saien.magrathea.mcp

import kotlinx.serialization.json.JsonObject
import saien.magrathea.core.ToolDefinition

/**
 * Stable host identity for one configured MCP server.
 *
 * The identifier participates in runtime Tool names and therefore must remain stable across
 * reconnects. Human-readable naming belongs in [displayName].
 */
data class McpServer(
    val id: String,
    val displayName: String,
) {
    init {
        require(id.isNotBlank() && id == id.trim() && id.length <= 256) {
            "MCP server ID is invalid"
        }
        require(
            displayName.isNotBlank() &&
                displayName == displayName.trim() &&
                displayName.length <= 256,
        ) {
            "MCP server display name is invalid"
        }
        require(
            id.none(Char::isMcpControlCharacter) &&
                displayName.none(Char::isMcpControlCharacter),
        ) {
            "MCP server identity must not contain control characters"
        }
    }
}

data class McpImplementationInfo(
    val name: String,
    val version: String,
    val title: String? = null,
    val websiteUrl: String? = null,
) {
    init {
        require(
            name.isNotBlank() &&
                name.length <= 256 &&
                name.none(Char::isMcpControlCharacter),
        ) {
            "MCP implementation name is invalid"
        }
        require(
            version.isNotBlank() &&
                version.length <= 128 &&
                version.none(Char::isMcpControlCharacter),
        ) {
            "MCP implementation version is invalid"
        }
        require(title == null || title.length <= 256 && title.none(Char::isMcpControlCharacter)) {
            "MCP implementation title is invalid"
        }
        require(
            websiteUrl == null ||
                websiteUrl.length <= 4_096 &&
                websiteUrl.none(Char::isMcpControlCharacter),
        ) {
            "MCP implementation website URL is invalid"
        }
    }
}

/**
 * Resource limits for one MCP client session.
 *
 * The transport parses protocol messages before this adapter can inspect them, so hosts should
 * still configure suitable network-level limits. These bounds prevent a successfully decoded
 * server response from expanding the Agent Tool surface without limit.
 */
data class McpConnectionOptions(
    val initializeTimeoutMs: Long = 30_000,
    val listToolsTimeoutMs: Long = 30_000,
    val maxToolListPages: Int = 100,
    val maxTools: Int = 256,
    val maxToolDefinitionChars: Int = 131_072,
    val maxToolListChars: Int = 2_097_152,
    val maxToolResultChars: Int = 1_048_576,
    val maxServerInstructionsChars: Int = 65_536,
) {
    init {
        require(initializeTimeoutMs > 0) { "MCP initialize timeout must be greater than zero" }
        require(listToolsTimeoutMs > 0) { "MCP Tool-list timeout must be greater than zero" }
        require(maxToolListPages > 0) { "MCP Tool-list page limit must be greater than zero" }
        require(maxTools > 0) { "MCP Tool count limit must be greater than zero" }
        require(maxToolDefinitionChars > 0) {
            "MCP Tool-definition limit must be greater than zero"
        }
        require(maxToolListChars > 0) { "MCP Tool-list size limit must be greater than zero" }
        require(maxToolResultChars > 0) { "MCP Tool-result limit must be greater than zero" }
        require(maxServerInstructionsChars > 0) {
            "MCP server-instructions limit must be greater than zero"
        }
    }
}

/** Observable, content-free lifecycle state for one MCP connection. */
sealed interface McpConnectionState {
    data object Disconnected : McpConnectionState

    data object Connecting : McpConnectionState

    data class Connected(
        val server: McpImplementationInfo,
        val toolCount: Int,
        val instructions: String? = null,
    ) : McpConnectionState

    data class Failed(
        val reason: McpConnectionFailure,
    ) : McpConnectionState
}

/** Stable MCP failure categories that contain no remote payload or transport detail. */
enum class McpConnectionFailure {
    TRANSPORT,
    AUTHENTICATION,
    PROTOCOL,
    CLOSED,
}

/** Public MCP operation names used by sanitized failures. */
enum class McpOperation {
    CONNECT,
    REFRESH_TOOLS,
    CALL_TOOL,
}

/**
 * Stable MCP failure exposed to hosts without retaining a raw transport or server exception.
 *
 * The original exception can contain response bodies, endpoints, or header values and is therefore
 * deliberately not attached as a cause.
 */
class McpOperationException(
    val operation: McpOperation,
    val reason: McpConnectionFailure,
) : IllegalStateException("MCP ${operation.name.lowercase()} failed (${reason.name.lowercase()})")

enum class McpTaskSupport {
    FORBIDDEN,
    OPTIONAL,
    REQUIRED,
}

enum class McpToolCompatibility {
    SUPPORTED,
    REQUIRES_TASKS,
}

/**
 * Security hints reported by an MCP server.
 *
 * Per the MCP specification these values are untrusted presentation hints. Magrathea never uses
 * them to bypass approval, permission, or host policy.
 */
data class McpToolHints(
    val readOnly: Boolean? = null,
    val destructive: Boolean? = null,
    val idempotent: Boolean? = null,
    val openWorld: Boolean? = null,
)

data class McpToolIcon(
    val source: String,
    val mimeType: String? = null,
    val sizes: List<String> = emptyList(),
    val theme: String? = null,
)

data class McpToolDescriptor(
    val server: McpServer,
    val remoteName: String,
    val runtimeName: String,
    val title: String,
    val description: String,
    val inputSchema: JsonObject,
    val outputSchema: JsonObject? = null,
    val hints: McpToolHints = McpToolHints(),
    val icons: List<McpToolIcon> = emptyList(),
    val taskSupport: McpTaskSupport = McpTaskSupport.FORBIDDEN,
    val compatibility: McpToolCompatibility = McpToolCompatibility.SUPPORTED,
    val metadata: JsonObject = JsonObject(emptyMap()),
)

data class McpToolPolicy(
    val enabled: Boolean = true,
    val requiresPermission: String? = null,
    val requiresApproval: Boolean = true,
    val timeoutMs: Long? = 60_000,
    val maxCallsPerTurn: Int? = null,
    val maxCallsPerRun: Int? = null,
) {
    init {
        require(timeoutMs == null || timeoutMs > 0) { "MCP Tool timeout must be greater than zero" }
        require(maxCallsPerTurn == null || maxCallsPerTurn > 0) {
            "MCP Tool maxCallsPerTurn must be greater than zero"
        }
        require(maxCallsPerRun == null || maxCallsPerRun > 0) {
            "MCP Tool maxCallsPerRun must be greater than zero"
        }
    }
}

fun interface McpToolPolicyProvider {
    fun policyFor(tool: McpToolDescriptor): McpToolPolicy
}

fun McpToolDescriptor.toToolDefinition(policy: McpToolPolicy): ToolDefinition = ToolDefinition(
    name = runtimeName,
    description = description,
    schema = inputSchema,
    requiresPermission = policy.requiresPermission,
    requiresApproval = policy.requiresApproval,
    timeoutMs = policy.timeoutMs,
    maxCallsPerTurn = policy.maxCallsPerTurn,
    maxCallsPerRun = policy.maxCallsPerRun,
)

internal fun Char.isMcpControlCharacter(): Boolean = code in 0x00..0x1f || code == 0x7f
