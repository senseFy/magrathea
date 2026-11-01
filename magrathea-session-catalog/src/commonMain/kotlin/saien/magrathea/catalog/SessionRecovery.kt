package saien.magrathea.catalog

import saien.magrathea.core.AgentSessionId
import saien.magrathea.core.AgentStatus

/** Durable facts that invite inspection; none authorize execution or imply a live running Agent. */
enum class SessionRecoveryReason {
    CHECKPOINT,
    RECOVERABLE_STATUS,
    BACKGROUND_PENDING,
    BACKGROUND_UNACKNOWLEDGED,
    UNKNOWN_SOURCE,
}

/** Independent background work can exist before its first canonical snapshot. */
data class SessionRecoveryCandidate(
    val sessionId: AgentSessionId,
    val identity: SessionIdentity?,
    val reasons: Set<SessionRecoveryReason>,
)

/** Opaque, backend-local primary-key position. Rebuilding invalidates older generations. */
data class SessionRecoveryCursor(
    val policy: SessionCatalogPolicy,
    val generation: Long,
    val afterSessionId: AgentSessionId,
)

data class SessionRecoveryPage(
    val entries: List<SessionRecoveryCandidate>,
    val nextCursor: SessionRecoveryCursor?,
    val exhausted: Boolean,
    val state: SessionCatalogState,
    /** Also accounts for unindexed/unknown background sources, independently of header coverage. */
    val coverage: CatalogCoverage,
)

fun sessionRecoveryReasons(status: AgentStatus, hasCheckpoint: Boolean): Set<SessionRecoveryReason> = buildSet {
    if (hasCheckpoint) add(SessionRecoveryReason.CHECKPOINT)
    if (status == AgentStatus.RUNNING || status == AgentStatus.WAITING_FOR_TOOLS || status == AgentStatus.INTERRUPTED) {
        add(SessionRecoveryReason.RECOVERABLE_STATUS)
    }
}
