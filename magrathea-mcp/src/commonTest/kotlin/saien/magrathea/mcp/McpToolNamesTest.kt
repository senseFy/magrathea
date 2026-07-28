package saien.magrathea.mcp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class McpToolNamesTest {
    @Test
    fun preservesPortableNamesWithinProviderLimit() {
        assertEquals(
            "mcp__github__search_code",
            McpToolNames.runtimeName("github", "search_code"),
        )
    }

    @Test
    fun sanitizesAndHashesNamesDeterministically() {
        val first = McpToolNames.runtimeName(
            serverId = "workspace/server",
            remoteName = "query files with an exceptionally long and provider-incompatible name",
        )
        val second = McpToolNames.runtimeName(
            serverId = "workspace/server",
            remoteName = "query files with an exceptionally long and provider-incompatible name",
        )
        val different = McpToolNames.runtimeName(
            serverId = "workspace-server",
            remoteName = "query files with an exceptionally long and provider-incompatible name",
        )

        assertEquals(first, second)
        assertNotEquals(first, different)
        assertTrue(first.length <= 64)
        assertTrue(first.all { it.isLetterOrDigit() || it == '_' || it == '-' })
    }
}
