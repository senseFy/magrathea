package saien.magrathea.policy

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.Test

class ToolPolicyEngineTest {
    @Test
    fun policyForShouldUseExactPolicyBeforeDefault() {
        val engine = ToolPolicyEngine(
            policies = listOf(
                ToolPolicy(
                    toolName = "delete_file",
                    riskLevel = ToolRiskLevel.HIGH,
                    approvalMode = ToolApprovalMode.ASK_EVERY_TIME,
                ),
            ),
        )

        val exact = engine.policyFor("delete_file")
        val fallback = engine.policyFor("echo")

        assertEquals(ToolRiskLevel.HIGH, exact.riskLevel)
        assertEquals(ToolApprovalMode.ASK_EVERY_TIME, exact.approvalMode)
        assertEquals("echo", fallback.toolName)
        assertEquals(ToolRiskLevel.HIGH, fallback.riskLevel)
        assertEquals(ToolApprovalMode.DENY, fallback.approvalMode)
    }

    @Test
    fun unknownPermission_defaultPolicyDenies() {
        val engine = ToolPolicyEngine(
            permissionPolicies = listOf(PermissionPolicy("contacts", allowed = false)),
        )

        assertFalse(engine.isPermissionAllowed("contacts"))
        assertFalse(engine.isPermissionAllowed("network"))
    }

    @Test
    fun explicitPermissionPolicyCanAllow() {
        val engine = ToolPolicyEngine(
            permissionPolicies = listOf(PermissionPolicy("network", allowed = true)),
        )

        assertTrue(engine.isPermissionAllowed("network"))
    }
}
