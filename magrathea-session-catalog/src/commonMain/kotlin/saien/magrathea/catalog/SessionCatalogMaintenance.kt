package saien.magrathea.catalog

import saien.magrathea.core.AgentSessionId

const val MAX_CATALOG_MAINTENANCE_BATCH_SIZE: Int = 50

/**
 * Persisted progress within one policy/generation; failures never imply deletion of source data.
 * [afterSessionId], [processed] and [failed] describe canonical sessions. [complete] also includes
 * adapter-owned collection/background phases; their coverage is reported by the relevant query.
 */
data class SessionCatalogMaintenanceProgress(
    val state: SessionCatalogState,
    val afterSessionId: AgentSessionId?,
    val processed: Long,
    val failed: Long,
    val complete: Boolean,
)

enum class SessionCatalogMaintenanceIssueKind { UNREADABLE, INCOMPATIBLE_SCHEMA, PROJECTION_FAILED }

/**
 * Bounded diagnostics, deliberately excluding source payloads and arbitrary exception messages.
 * [sessionId] is null when a malformed physical key cannot name an Agent session.
 */
data class SessionCatalogMaintenanceIssue(
    val sessionId: AgentSessionId?,
    val kind: SessionCatalogMaintenanceIssueKind,
)

data class SessionCatalogMaintenanceBatch(
    val progress: SessionCatalogMaintenanceProgress,
    val indexed: Int,
    val skipped: Int,
    val issues: List<SessionCatalogMaintenanceIssue>,
)

/**
 * Optional, bounded storage maintenance. It never restores an Agent or owns a maintenance loop.
 * The host schedules steps off its UI thread and cancels/joins them before closing the handle.
 */
interface SessionCatalogMaintenance {
    suspend fun status(): SessionCatalogMaintenanceProgress

    /** Processes at most [limit] source records across all phases; 1..[MAX_CATALOG_MAINTENANCE_BATCH_SIZE]. */
    suspend fun runBatch(limit: Int = MAX_CATALOG_MAINTENANCE_BATCH_SIZE): SessionCatalogMaintenanceBatch

    /**
     * Clears only derived indexes and starts a new generation under this handle's projector.
     * The expected policy/generation are checked atomically; stale calls change nothing.
     * A policy change requires the owner to fence, drain and close all old-policy execution
     * writers first. Same-policy rebuilds allow ordinary commits to continue in the new generation.
     * Organization, canonical bytes, incarnations and accepted activity order survive.
     */
    suspend fun rebuild(expectedPolicy: SessionCatalogPolicy, expectedGeneration: Long): SessionCatalogMaintenanceProgress
}
