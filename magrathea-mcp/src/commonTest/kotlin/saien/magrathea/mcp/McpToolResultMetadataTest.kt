package saien.magrathea.mcp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class McpToolResultMetadataTest {
    @Test
    fun parserShouldExposeOnlyCompleteBoundedIdentity() {
        val metadata = buildJsonObject {
            put("mcpServerId", "filesystem")
            put("mcpServerName", "Files")
            put("mcpToolName", "read_file")
            put("mcpToolTitle", "Read file")
            put("mcpMeta", buildJsonObject { put("token", "must-not-be-projected") })
        }

        assertEquals(
            McpToolIdentity("filesystem", "Files", "read_file", "Read file"),
            metadata.mcpToolIdentityOrNull(),
        )
    }

    @Test
    fun parserShouldFailClosedForMissingOrUnsafeIdentity() {
        assertNull(
            buildJsonObject {
                put("mcpServerId", "filesystem")
                put("mcpServerName", "Files")
                put("mcpToolName", "read_file")
            }.mcpToolIdentityOrNull(),
        )
        assertNull(
            buildJsonObject {
                put("mcpServerId", 42)
                put("mcpServerName", "Files")
                put("mcpToolName", "read_file")
                put("mcpToolTitle", "Read file")
            }.mcpToolIdentityOrNull(),
        )
        assertNull(
            buildJsonObject {
                put("mcpServerId", "filesystem")
                put("mcpServerName", "Files\nInjected")
                put("mcpToolName", "read_file")
                put("mcpToolTitle", "Read file")
            }.mcpToolIdentityOrNull(),
        )
    }
}
