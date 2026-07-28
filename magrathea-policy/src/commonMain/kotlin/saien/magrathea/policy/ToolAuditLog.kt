package saien.magrathea.policy

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import saien.magrathea.core.AgentSessionId

interface ToolAuditLog {
    suspend fun record(entry: ToolAuditEntry)
    suspend fun entries(sessionId: AgentSessionId? = null): List<ToolAuditEntry>
}

class InMemoryToolAuditLog : ToolAuditLog {
    private val mutex = Mutex()
    private val entries = mutableListOf<ToolAuditEntry>()

    override suspend fun record(entry: ToolAuditEntry) {
        mutex.withLock { entries += entry }
    }

    override suspend fun entries(sessionId: AgentSessionId?): List<ToolAuditEntry> {
        return mutex.withLock {
            entries.filter { sessionId == null || it.sessionId == sessionId }
        }
    }
}
