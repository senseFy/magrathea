package saien.magrathea.policy

class ToolPolicyEngine(
    policies: List<ToolPolicy> = emptyList(),
    permissionPolicies: List<PermissionPolicy> = emptyList(),
    val policyVersion: String = "v1",
    private val defaultPolicy: ToolPolicy = ToolPolicy(
        toolName = "*",
        riskLevel = ToolRiskLevel.HIGH,
        approvalMode = ToolApprovalMode.DENY,
    ),
) {
    init {
        require(policyVersion.isNotBlank()) { "policyVersion must not be blank" }
    }

    private val policiesByToolName = policies.associateBy { it.toolName }
    private val permissionPolicyByName = permissionPolicies.associateBy { it.permission }

    fun policyFor(toolName: String): ToolPolicy {
        return policiesByToolName[toolName] ?: defaultPolicy.copy(toolName = toolName)
    }

    fun isPermissionAllowed(permission: String): Boolean {
        return permissionPolicyByName[permission]?.allowed ?: false
    }
}
