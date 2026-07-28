package saien.magrathea.mcp

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Safe, presentation-oriented identity attached to every MCP Tool result. */
data class McpToolIdentity(
    val serverId: String,
    val serverName: String,
    val remoteToolName: String,
    val toolTitle: String,
)

/**
 * Reads only the bounded identity fields owned by Magrathea. Transport details and arbitrary MCP
 * metadata are intentionally excluded from the returned value.
 */
fun JsonObject.mcpToolIdentityOrNull(): McpToolIdentity? {
    val serverId = boundedString(MCP_SERVER_ID_KEY) ?: return null
    val serverName = boundedString(MCP_SERVER_NAME_KEY) ?: return null
    val remoteToolName = boundedString(MCP_TOOL_NAME_KEY) ?: return null
    val toolTitle = boundedString(MCP_TOOL_TITLE_KEY) ?: return null
    return McpToolIdentity(
        serverId = serverId,
        serverName = serverName,
        remoteToolName = remoteToolName,
        toolTitle = toolTitle,
    )
}

private fun JsonObject.boundedString(key: String): String? =
    (get(key) as? JsonPrimitive)
        ?.takeIf(JsonPrimitive::isString)
        ?.contentOrNull
        ?.takeIf { value ->
            value.isNotBlank() &&
                value == value.trim() &&
                value.length <= MAX_IDENTITY_LENGTH &&
                value.none(Char::isMcpMetadataControlCharacter)
        }

private fun Char.isMcpMetadataControlCharacter(): Boolean = code in 0..31 || code == 127

internal const val MCP_SERVER_ID_KEY = "mcpServerId"
internal const val MCP_SERVER_NAME_KEY = "mcpServerName"
internal const val MCP_TOOL_NAME_KEY = "mcpToolName"
internal const val MCP_TOOL_TITLE_KEY = "mcpToolTitle"
private const val MAX_IDENTITY_LENGTH = 256
