package saien.magrathea.tooling.mcp.conformance

import org.junit.Assert.assertEquals
import org.junit.Test

class McpConformanceClientTest {
    @Test
    fun supportedScenarioSetTracksTheExplicitHarnessContract() {
        assertEquals(setOf("initialize", "tools_call"), SUPPORTED_SCENARIOS)
    }
}
