package saien.magrathea.policy

import saien.magrathea.core.AgentSessionId
import saien.magrathea.core.SystemEpochClock
import saien.magrathea.core.ToolApprovalDecision
import saien.magrathea.core.ToolApprovalRequest

enum class ToolRiskLevel {
    LOW,
    MEDIUM,
    HIGH,
    CRITICAL,
}

enum class ToolApprovalMode {
    ALLOW,
    DENY,
    ASK_ONCE_PER_SESSION,
    ASK_EVERY_TIME,
}

data class ToolPolicy(
    val toolName: String,
    val riskLevel: ToolRiskLevel,
    val approvalMode: ToolApprovalMode,
    val requiredPermissions: Set<String> = emptySet(),
)

data class PermissionPolicy(
    val permission: String,
    val allowed: Boolean,
)

enum class ToolAuditOutcome {
    PERMISSION_ALLOWED,
    PERMISSION_DENIED,
    TOOL_ALLOWED,
    TOOL_DENIED,
    APPROVAL_REQUESTED,
    TOOL_APPROVED,
}

data class ToolAuditEntry(
    val sessionId: AgentSessionId?,
    val toolCallId: String?,
    val toolName: String?,
    val outcome: ToolAuditOutcome,
    val riskLevel: ToolRiskLevel? = null,
    val approvalMode: ToolApprovalMode? = null,
    val permission: String? = null,
    val reason: String? = null,
    val createdAtEpochMs: Long = SystemEpochClock.nowEpochMs(),
)

fun interface ToolApprovalPresenter {
    suspend fun requestApproval(request: ToolApprovalRequest, policy: ToolPolicy): ToolApprovalDecision
}
