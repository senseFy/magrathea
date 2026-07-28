package saien.magrathea.policy

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import saien.magrathea.core.EpochClock
import saien.magrathea.core.SystemEpochClock
import saien.magrathea.core.ToolApprovalDecision
import saien.magrathea.core.ToolApprovalGateway
import saien.magrathea.core.ToolApprovalRequest
import saien.magrathea.core.ToolPermissionGateway

class PolicyBackedPermissionGateway(
    private val engine: ToolPolicyEngine,
    private val auditLog: ToolAuditLog? = null,
    private val clock: EpochClock = SystemEpochClock,
) : ToolPermissionGateway {
    override suspend fun ensurePermission(permission: String): Boolean {
        val allowed = engine.isPermissionAllowed(permission)
        auditLog?.record(
            ToolAuditEntry(
                sessionId = null,
                toolCallId = null,
                toolName = null,
                outcome = if (allowed) ToolAuditOutcome.PERMISSION_ALLOWED else ToolAuditOutcome.PERMISSION_DENIED,
                permission = permission,
                createdAtEpochMs = clock.nowEpochMs(),
            )
        )
        return allowed
    }
}

class PolicyBackedApprovalGateway(
    private val engine: ToolPolicyEngine,
    private val presenter: ToolApprovalPresenter? = null,
    private val auditLog: ToolAuditLog? = null,
    private val policyVersionProvider: () -> String = { engine.policyVersion },
    private val clock: EpochClock = SystemEpochClock,
) : ToolApprovalGateway {
    private val mutex = Mutex()
    private val approvedOnceKeys = mutableSetOf<ApprovalCacheKey>()

    override suspend fun requestApproval(request: ToolApprovalRequest): ToolApprovalDecision {
        val policy = engine.policyFor(request.toolCall.toolName)
        val permissionResults = policy.requiredPermissions.sorted().map { permission ->
            permission to engine.isPermissionAllowed(permission)
        }
        permissionResults.forEach { (permission, allowed) ->
            auditPermission(request, policy, permission, allowed)
        }
        val deniedPermissions = permissionResults.filterNot { it.second }.map { it.first }
        if (deniedPermissions.isNotEmpty()) {
            val reason = "Required permission denied: ${deniedPermissions.joinToString()}"
            val decision = ToolApprovalDecision.Deny(reason)
            audit(request, policy, ToolAuditOutcome.TOOL_DENIED, reason)
            return decision
        }
        return when (policy.approvalMode) {
            ToolApprovalMode.ALLOW -> {
                audit(request, policy, ToolAuditOutcome.TOOL_ALLOWED)
                ToolApprovalDecision.Approve
            }
            ToolApprovalMode.DENY -> {
                val decision = ToolApprovalDecision.Deny("Denied by policy")
                audit(request, policy, ToolAuditOutcome.TOOL_DENIED, decision.reason)
                decision
            }
            ToolApprovalMode.ASK_ONCE_PER_SESSION -> requestAskOnce(request, policy)
            ToolApprovalMode.ASK_EVERY_TIME -> requestUserApproval(request, policy)
        }
    }

    private suspend fun requestAskOnce(request: ToolApprovalRequest, policy: ToolPolicy): ToolApprovalDecision {
        val key = ApprovalCacheKey(
            sessionId = request.sessionId.value,
            toolName = request.toolCall.toolName,
            policyVersion = policyVersionProvider(),
        )
        return mutex.withLock {
            if (key in approvedOnceKeys) {
                audit(request, policy, ToolAuditOutcome.TOOL_ALLOWED, "Previously approved for this session and policy version")
                return@withLock ToolApprovalDecision.Approve
            }
            val decision = requestUserApproval(request, policy)
            if (decision == ToolApprovalDecision.Approve) {
                approvedOnceKeys += key
            }
            decision
        }
    }

    private suspend fun requestUserApproval(
        request: ToolApprovalRequest,
        policy: ToolPolicy,
    ): ToolApprovalDecision {
        audit(request, policy, ToolAuditOutcome.APPROVAL_REQUESTED)
        val decision = presenter?.requestApproval(request, policy)
            ?: ToolApprovalDecision.Deny("Approval presenter is not available")
        when (decision) {
            ToolApprovalDecision.Approve -> {
                audit(request, policy, ToolAuditOutcome.TOOL_APPROVED)
            }
            is ToolApprovalDecision.Deny -> audit(request, policy, ToolAuditOutcome.TOOL_DENIED, decision.reason)
        }
        return decision
    }

    private suspend fun audit(
        request: ToolApprovalRequest,
        policy: ToolPolicy,
        outcome: ToolAuditOutcome,
        reason: String? = null,
    ) {
        auditLog?.record(
            ToolAuditEntry(
                sessionId = request.sessionId,
                toolCallId = request.toolCall.toolCallId,
                toolName = request.toolCall.toolName,
                outcome = outcome,
                riskLevel = policy.riskLevel,
                approvalMode = policy.approvalMode,
                reason = reason,
                createdAtEpochMs = clock.nowEpochMs(),
            )
        )
    }

    private suspend fun auditPermission(
        request: ToolApprovalRequest,
        policy: ToolPolicy,
        permission: String,
        allowed: Boolean,
    ) {
        auditLog?.record(
            ToolAuditEntry(
                sessionId = request.sessionId,
                toolCallId = request.toolCall.toolCallId,
                toolName = request.toolCall.toolName,
                outcome = if (allowed) ToolAuditOutcome.PERMISSION_ALLOWED else ToolAuditOutcome.PERMISSION_DENIED,
                riskLevel = policy.riskLevel,
                approvalMode = policy.approvalMode,
                permission = permission,
                createdAtEpochMs = clock.nowEpochMs(),
            ),
        )
    }

    private data class ApprovalCacheKey(
        val sessionId: String,
        val toolName: String,
        val policyVersion: String,
    )
}
