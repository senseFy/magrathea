package saien.magrathea.chatbot

import saien.magrathea.core.AgentRecoveryDisposition
import saien.magrathea.core.AgentRecoveryInfo
import saien.magrathea.core.AgentRunId
import saien.magrathea.core.AgentRunner
import saien.magrathea.core.AgentSessionId

internal val TEST_RUN_ID = AgentRunId("chatbot-test-run")

internal abstract class TestAgentRunner : AgentRunner {
    override suspend fun interrupt(sessionId: AgentSessionId): AgentRecoveryInfo {
        cancel(sessionId)
        return inspectRecovery(sessionId)
    }

    override suspend fun inspectRecovery(sessionId: AgentSessionId): AgentRecoveryInfo =
        AgentRecoveryInfo(
            sessionId = sessionId,
            disposition = AgentRecoveryDisposition.NOT_FOUND,
        )
}
