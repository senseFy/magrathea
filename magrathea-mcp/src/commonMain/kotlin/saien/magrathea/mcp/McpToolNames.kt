package saien.magrathea.mcp

private const val MAX_PORTABLE_TOOL_NAME_LENGTH = 64
private const val MCP_TOOL_PREFIX = "mcp__"

/**
 * Produces a deterministic Provider-portable name for a Tool exposed by an MCP server.
 *
 * OpenAI-compatible and Anthropic Tool contracts commonly cap names at 64 characters and accept
 * only ASCII letters, digits, `_`, and `-`. The suffix keeps names distinct after sanitization or
 * truncation.
 */
object McpToolNames {
    fun runtimeName(serverId: String, remoteName: String): String {
        require(serverId.isNotBlank()) { "MCP server ID must not be blank" }
        require(remoteName.isNotBlank()) { "MCP Tool name must not be blank" }

        val server = serverId.portableSegment()
        val tool = remoteName.portableSegment()
        val readable = "$MCP_TOOL_PREFIX${server}__${tool}"
        if (
            readable.length <= MAX_PORTABLE_TOOL_NAME_LENGTH &&
            serverId == server &&
            remoteName == tool
        ) {
            return readable
        }

        val suffix = stableHash("$serverId\u0000$remoteName")
        val prefixLength = MAX_PORTABLE_TOOL_NAME_LENGTH - suffix.length - 2
        return readable.take(prefixLength).trimEnd('_', '-') + "__" + suffix
    }
}

private fun String.portableSegment(): String {
    val sanitized = buildString(length) {
        this@portableSegment.forEach { character ->
            append(
                when {
                    character in 'a'..'z' ||
                        character in 'A'..'Z' ||
                        character in '0'..'9' ||
                        character == '_' ||
                        character == '-' -> character
                    else -> '_'
                },
            )
        }
    }.trim('_', '-')
    return sanitized.ifBlank { "tool" }
}

private fun stableHash(value: String): String {
    var hash = 0xcbf29ce484222325uL
    value.encodeToByteArray().forEach { byte ->
        hash = (hash xor byte.toUByte().toULong()) * 0x100000001b3uL
    }
    return hash.toString(16).padStart(16, '0')
}
