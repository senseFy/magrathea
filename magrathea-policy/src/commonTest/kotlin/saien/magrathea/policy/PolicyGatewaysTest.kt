package saien.magrathea.policy

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.Test
import saien.magrathea.core.AgentSessionId
import saien.magrathea.core.ToolApprovalDecision
import saien.magrathea.core.ToolApprovalRequest
import saien.magrathea.core.ToolCallPart

class PolicyGatewaysTest {
    @Test
    fun policyRequiredPermissions_areAllEnforced() = runTest {
        val audit = InMemoryToolAuditLog()
        val engine = ToolPolicyEngine(
            policies = listOf(
                ToolPolicy(
                    toolName = "send_email",
                    riskLevel = ToolRiskLevel.HIGH,
                    approvalMode = ToolApprovalMode.ALLOW,
                    requiredPermissions = setOf("network", "contacts"),
                ),
            ),
            permissionPolicies = listOf(
                PermissionPolicy("network", allowed = true),
                PermissionPolicy("contacts", allowed = false),
            ),
        )
        val gateway = PolicyBackedApprovalGateway(engine = engine, auditLog = audit)

        val decision = gateway.requestApproval(requestFor("send_email"))

        assertTrue(decision is ToolApprovalDecision.Deny)
        assertEquals("Required permission denied: contacts", decision.reason)
        assertEquals(
            listOf(ToolAuditOutcome.PERMISSION_DENIED, ToolAuditOutcome.PERMISSION_ALLOWED, ToolAuditOutcome.TOOL_DENIED),
            audit.entries().map { it.outcome },
        )
    }

    @Test
    fun permissionGatewayShouldApplyPolicyAndAuditDecision() = runTest {
        val auditLog = InMemoryToolAuditLog()
        val gateway = PolicyBackedPermissionGateway(
            engine = ToolPolicyEngine(
                permissionPolicies = listOf(
                    PermissionPolicy("calendar", allowed = false),
                    PermissionPolicy("network", allowed = true),
                ),
            ),
            auditLog = auditLog,
        )

        assertEquals(false, gateway.ensurePermission("calendar"))
        assertEquals(true, gateway.ensurePermission("network"))

        val entries = auditLog.entries()
        assertEquals(
            listOf(ToolAuditOutcome.PERMISSION_DENIED, ToolAuditOutcome.PERMISSION_ALLOWED),
            entries.map { it.outcome },
        )
    }

    @Test
    fun approvalGatewayShouldAllowAndDenyWithoutPresenter() = runTest {
        val auditLog = InMemoryToolAuditLog()
        val gateway = PolicyBackedApprovalGateway(
            engine = ToolPolicyEngine(
                policies = listOf(
                    ToolPolicy("safe", ToolRiskLevel.LOW, ToolApprovalMode.ALLOW),
                    ToolPolicy("dangerous", ToolRiskLevel.CRITICAL, ToolApprovalMode.DENY),
                ),
            ),
            auditLog = auditLog,
        )

        assertEquals(ToolApprovalDecision.Approve, gateway.requestApproval(requestFor("safe")))
        assertTrue(gateway.requestApproval(requestFor("dangerous")) is ToolApprovalDecision.Deny)

        assertEquals(
            listOf(ToolAuditOutcome.TOOL_ALLOWED, ToolAuditOutcome.TOOL_DENIED),
            auditLog.entries(AgentSessionId("session-1")).map { it.outcome },
        )
    }

    @Test
    fun approvalGatewayShouldAskOnceAndCacheApprovedTool() = runTest {
        var presenterCalls = 0
        val auditLog = InMemoryToolAuditLog()
        val gateway = PolicyBackedApprovalGateway(
            engine = ToolPolicyEngine(
                policies = listOf(ToolPolicy("send_email", ToolRiskLevel.HIGH, ToolApprovalMode.ASK_ONCE_PER_SESSION)),
            ),
            presenter = ToolApprovalPresenter { _, _ ->
                presenterCalls += 1
                ToolApprovalDecision.Approve
            },
            auditLog = auditLog,
        )

        assertEquals(ToolApprovalDecision.Approve, gateway.requestApproval(requestFor("send_email", "call-1")))
        assertEquals(ToolApprovalDecision.Approve, gateway.requestApproval(requestFor("send_email", "call-2")))

        assertEquals(1, presenterCalls)
        assertEquals(
            listOf(
                ToolAuditOutcome.APPROVAL_REQUESTED,
                ToolAuditOutcome.TOOL_APPROVED,
                ToolAuditOutcome.TOOL_ALLOWED,
            ),
            auditLog.entries(AgentSessionId("session-1")).map { it.outcome },
        )
    }

    @Test
    fun askOnce_doesNotCrossSession() = runTest {
        var presenterCalls = 0
        val gateway = PolicyBackedApprovalGateway(
            engine = ToolPolicyEngine(
                policies = listOf(ToolPolicy("send_email", ToolRiskLevel.HIGH, ToolApprovalMode.ASK_ONCE_PER_SESSION)),
            ),
            presenter = ToolApprovalPresenter { _, _ ->
                presenterCalls += 1
                ToolApprovalDecision.Approve
            },
        )

        gateway.requestApproval(requestFor("send_email", "call-1", "session-1"))
        gateway.requestApproval(requestFor("send_email", "call-2", "session-1"))
        gateway.requestApproval(requestFor("send_email", "call-3", "session-2"))

        assertEquals(2, presenterCalls)
    }

    @Test
    fun policyVersionChange_invalidatesAskOnceApproval() = runTest {
        var presenterCalls = 0
        var policyVersion = "v1"
        val gateway = PolicyBackedApprovalGateway(
            engine = ToolPolicyEngine(
                policies = listOf(ToolPolicy("send_email", ToolRiskLevel.HIGH, ToolApprovalMode.ASK_ONCE_PER_SESSION)),
            ),
            presenter = ToolApprovalPresenter { _, _ ->
                presenterCalls += 1
                ToolApprovalDecision.Approve
            },
            policyVersionProvider = { policyVersion },
        )

        gateway.requestApproval(requestFor("send_email", "call-1", "session-1"))
        gateway.requestApproval(requestFor("send_email", "call-2", "session-1"))
        policyVersion = "v2"
        gateway.requestApproval(requestFor("send_email", "call-3", "session-1"))

        assertEquals(2, presenterCalls)
    }

    @Test
    fun concurrentAskOnce_requestsPresenterOnlyOnce() = runTest {
        var presenterCalls = 0
        val gateway = PolicyBackedApprovalGateway(
            engine = ToolPolicyEngine(
                policies = listOf(ToolPolicy("send_email", ToolRiskLevel.HIGH, ToolApprovalMode.ASK_ONCE_PER_SESSION)),
            ),
            presenter = ToolApprovalPresenter { _, _ ->
                presenterCalls += 1
                ToolApprovalDecision.Approve
            },
        )

        listOf("call-1", "call-2", "call-3")
            .map { callId -> async { gateway.requestApproval(requestFor("send_email", callId, "session-1")) } }
            .awaitAll()

        assertEquals(1, presenterCalls)
    }

    @Test
    fun approvalGatewayShouldAskEveryTimeAndAuditDenials() = runTest {
        var presenterCalls = 0
        val auditLog = InMemoryToolAuditLog()
        val gateway = PolicyBackedApprovalGateway(
            engine = ToolPolicyEngine(
                policies = listOf(ToolPolicy("post_publicly", ToolRiskLevel.CRITICAL, ToolApprovalMode.ASK_EVERY_TIME)),
            ),
            presenter = ToolApprovalPresenter { _, _ ->
                presenterCalls += 1
                ToolApprovalDecision.Deny("No")
            },
            auditLog = auditLog,
        )

        assertTrue(gateway.requestApproval(requestFor("post_publicly", "call-1")) is ToolApprovalDecision.Deny)
        assertTrue(gateway.requestApproval(requestFor("post_publicly", "call-2")) is ToolApprovalDecision.Deny)

        assertEquals(2, presenterCalls)
        assertEquals(
            listOf(
                ToolAuditOutcome.APPROVAL_REQUESTED,
                ToolAuditOutcome.TOOL_DENIED,
                ToolAuditOutcome.APPROVAL_REQUESTED,
                ToolAuditOutcome.TOOL_DENIED,
            ),
            auditLog.entries(AgentSessionId("session-1")).map { it.outcome },
        )
    }

    private fun requestFor(
        toolName: String,
        callId: String = "call-1",
        sessionId: String = "session-1",
    ): ToolApprovalRequest {
        return ToolApprovalRequest(
            sessionId = AgentSessionId(sessionId),
            toolCall = ToolCallPart(
                toolCallId = callId,
                toolName = toolName,
                arguments = buildJsonObject { },
            ),
        )
    }
}
