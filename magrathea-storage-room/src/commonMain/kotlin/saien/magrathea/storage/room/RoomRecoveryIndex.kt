package saien.magrathea.storage.room

import androidx.room.deferredTransaction
import androidx.room.immediateTransaction
import androidx.room.useReaderConnection
import androidx.room.useWriterConnection
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import saien.magrathea.catalog.CatalogCoverage
import saien.magrathea.catalog.MAX_CATALOG_MAINTENANCE_BATCH_SIZE
import saien.magrathea.catalog.SessionCatalogException
import saien.magrathea.catalog.SessionCatalogFailure
import saien.magrathea.catalog.SessionCatalogMaintenanceIssue
import saien.magrathea.catalog.SessionCatalogMaintenanceIssueKind
import saien.magrathea.catalog.SessionCatalogPolicy
import saien.magrathea.catalog.SessionCatalogState
import saien.magrathea.catalog.SessionIdentity
import saien.magrathea.catalog.SessionRecoveryCandidate
import saien.magrathea.catalog.SessionRecoveryCursor
import saien.magrathea.catalog.SessionRecoveryPage
import saien.magrathea.catalog.SessionRecoveryReason
import saien.magrathea.catalog.extractBackgroundExecutionAttachmentUris
import saien.magrathea.catalog.sessionRecoveryReasons
import saien.magrathea.catalog.validateCatalogPageLimit
import saien.magrathea.core.AgentSessionId
import saien.magrathea.core.AgentSessionSnapshot
import saien.magrathea.core.AgentSessionSnapshotCodec
import saien.magrathea.core.AgentStatus
import saien.magrathea.core.BackgroundExecutionRecord
import saien.magrathea.core.BackgroundExecutionRecordCodec
import saien.magrathea.core.BackgroundExecutionState
import saien.magrathea.core.StoredEnvelopeDecodeException
import saien.magrathea.core.StoredEnvelopeDecodeFailure
import saien.magrathea.core.backgroundResultDeliveryFacts

internal data class RoomBackgroundRecoveryPreparation(
    val canonical: AgentSessionEntity?,
    val checkCanonical: Boolean,
    val acknowledged: Boolean?,
)

internal data class RoomBackgroundRecoveryBackfillResult(
    val processed: Int,
    val indexed: Int,
    val skipped: Int,
    val complete: Boolean,
    val issues: List<SessionCatalogMaintenanceIssue>,
)

/**
 * Source indexes are independent of catalog header coverage. All methods that mutate facts, except
 * [backfillBackgroundBatch], run inside the caller's existing writer transaction. No query decodes
 * an envelope. Background writers decode a captured canonical snapshot outside their transaction,
 * then compare its incarnation/version before publishing the new acknowledgement fact.
 */
internal class RoomRecoveryIndex(private val database: MagratheaDatabase, json: Json = Json) {
    private val sessions = database.sessionDao()
    private val checkpoints = database.checkpointDao()
    private val backgrounds = database.backgroundExecutionDao()
    private val controls = database.catalogDao()
    private val snapshotCodec = AgentSessionSnapshotCodec(json)
    private val backgroundCodec = BackgroundExecutionRecordCodec(json)

    /** Canonical recovery facts were stored with the row; reconcile only changed background facts. */
    suspend fun refreshSnapshot(snapshot: AgentSessionSnapshot) {
        backgrounds.recoveryBySession(snapshot.sessionId.value).forEach { row ->
            val known = row.hasKnownSource()
            val acknowledged = if (known) {
                backgroundResultDeliveryFacts(requireNotNull(row.catalogHandle), requireNotNull(row.catalogResultVersion),
                    snapshot.state.messages).acknowledged
            } else null
            val candidate = row.catalogState != BackgroundExecutionState.TERMINAL.name || acknowledged != true
            if (row.catalogAcknowledged != acknowledged || row.catalogRecoveryCandidate != candidate) {
                backgrounds.updateRecovery(row.sessionId, row.executionId, row.catalogHandle, row.catalogState,
                    row.catalogResultVersion, acknowledged, candidate)
            }
        }
    }

    /** Reconciles the candidate bit from current lightweight facts; never decodes a source. */
    suspend fun refresh(sessionId: AgentSessionId) {
        backgrounds.recoveryBySession(sessionId.value).forEach { row ->
            val candidate = !row.hasKnownSource() || row.catalogState != BackgroundExecutionState.TERMINAL.name || row.catalogAcknowledged != true
            if (candidate != row.catalogRecoveryCandidate) {
                backgrounds.updateRecovery(row.sessionId, row.executionId, row.catalogHandle, row.catalogState,
                    row.catalogResultVersion, row.catalogAcknowledged, candidate)
            }
        }
    }

    suspend fun prepareBackground(record: BackgroundExecutionRecord): RoomBackgroundRecoveryPreparation {
        if (record.state != BackgroundExecutionState.TERMINAL) {
            return RoomBackgroundRecoveryPreparation(null, checkCanonical = false, acknowledged = false)
        }
        val canonical = database.useReaderConnection { connection ->
            connection.deferredTransaction { sessions.findById(record.sessionId.value) }
        }
        return RoomBackgroundRecoveryPreparation(canonical, true, acknowledgement(record, canonical))
    }

    /** Must be checked in the same write transaction that stores the background envelope. */
    suspend fun matches(prepared: RoomBackgroundRecoveryPreparation, sessionId: AgentSessionId): Boolean =
        !prepared.checkCanonical || canonicalMatches(prepared.canonical, sessionId.value)

    /** The caller has already serialized this execution's update under SQLite's writer lock. */
    suspend fun backgroundEntity(
        record: BackgroundExecutionRecord,
        envelope: String,
        prepared: RoomBackgroundRecoveryPreparation,
    ): AgentBackgroundExecutionEntity {
        val previous = backgrounds.findById(record.sessionId.value, record.executionId)
        if (previous?.rowVersion == Long.MAX_VALUE) throw SessionCatalogException(SessionCatalogFailure.COUNTER_EXHAUSTED)
        return AgentBackgroundExecutionEntity(
            sessionId = record.sessionId.value,
            executionId = record.executionId,
            envelope = envelope,
            updatedAtEpochMs = record.settledAtEpochMs ?: record.startedAtEpochMs ?: record.queuedAtEpochMs,
            rowVersion = (previous?.rowVersion ?: 0) + 1,
            catalogHandle = record.handle,
            catalogState = record.state.name,
            catalogResultVersion = record.resultVersion,
            catalogAcknowledged = prepared.acknowledged,
            catalogRecoveryCandidate = record.state != BackgroundExecutionState.TERMINAL || prepared.acknowledged != true,
        )
    }

    /** Caller supplies one reader transaction, keeping state, candidate IDs, and reasons coherent. */
    suspend fun page(state: SessionCatalogState, cursor: SessionRecoveryCursor?, limit: Int): SessionRecoveryPage {
        validateCatalogPageLimit(limit)
        if (cursor != null && (cursor.policy != state.policy || cursor.generation != state.generation)) {
            throw SessionCatalogException(SessionCatalogFailure.INVALID_CURSOR)
        }
        val ids = if (cursor == null) sessions.recoveryCandidateIds(limit + 1)
            else sessions.recoveryCandidateIdsAfter(cursor.afterSessionId.value, limit + 1)
        val entries = ids.take(limit).map { id ->
            val canonical = sessions.recovery(id)
            val status = canonical?.catalogRecoveryStatus?.let { value -> AgentStatus.entries.firstOrNull { it.name == value } }
            val reasons = buildSet {
                if (checkpoints.exists(id)) add(SessionRecoveryReason.CHECKPOINT)
                if (canonical != null && status == null) add(SessionRecoveryReason.UNKNOWN_SOURCE)
                if (status != null) addAll(sessionRecoveryReasons(status, hasCheckpoint = false))
                backgrounds.recoveryBySession(id).forEach { row ->
                    if (!row.hasKnownSource()) add(SessionRecoveryReason.UNKNOWN_SOURCE)
                    when (row.catalogState) {
                        BackgroundExecutionState.QUEUED.name, BackgroundExecutionState.RUNNING.name ->
                            add(SessionRecoveryReason.BACKGROUND_PENDING)
                        BackgroundExecutionState.TERMINAL.name -> when (row.catalogAcknowledged) {
                            false -> add(SessionRecoveryReason.BACKGROUND_UNACKNOWLEDGED)
                            null -> add(SessionRecoveryReason.UNKNOWN_SOURCE)
                            true -> Unit
                        }
                    }
                }
            }
            SessionRecoveryCandidate(AgentSessionId(id), canonical?.let { SessionIdentity(AgentSessionId(id), it.incarnation) }, reasons)
        }
        val more = ids.size > limit
        val next = if (more) entries.lastOrNull()?.let { SessionRecoveryCursor(state.policy, state.generation, it.sessionId) } else null
        val unknownSessions = sessions.hasUnknownRecovery()
        val unknownBackgrounds = backgrounds.hasUnknownRecoveryFacts()
        val coverage = when {
            !unknownSessions && !unknownBackgrounds -> CatalogCoverage.COMPLETE
            (unknownSessions && state.coverage == CatalogCoverage.INDEXING) || !backgroundBackfillComplete() -> CatalogCoverage.INDEXING
            else -> CatalogCoverage.PARTIAL
        }
        return SessionRecoveryPage(entries, next, !more, state, coverage)
    }

    /** Read-only; may be called inside the maintenance status reader transaction. */
    suspend fun backgroundBackfillComplete(): Boolean {
        if (!backgrounds.hasUnknownRecoverySource() && !controls.hasUnindexedBackgroundReferences()) return true
        return controls.state(BACKFILL_GENERATION)?.value == generation() && controls.state(BACKFILL_COMPLETE)?.value == "true"
    }

    /**
     * Bounded legacy-source maintenance. A corrupt row remains a conservative candidate but does
     * not starve later rows. Source-envelope and canonical-version CAS protect both independent
     * writers; a conflict stops this step before advancing the affected source's cursor.
     */
    suspend fun backfillBackgroundBatch(
        limit: Int,
        expectedPolicy: SessionCatalogPolicy,
        expectedGeneration: Long,
    ): RoomBackgroundRecoveryBackfillResult {
        require(limit in 1..MAX_CATALOG_MAINTENANCE_BATCH_SIZE)
        var processed = 0
        var indexed = 0
        var skipped = 0
        val issues = mutableListOf<SessionCatalogMaintenanceIssue>()
        while (processed < limit) {
            val captured = database.useReaderConnection { connection ->
                connection.deferredTransaction {
                    requireMaintenanceState(expectedPolicy, expectedGeneration)
                    val progress = progress()
                    val source = if (progress.complete) null else backgrounds.recoveryBackfillPage(progress.afterSession, progress.afterExecution, 1).firstOrNull()
                    BackgroundBackfillCapture(progress, source, source?.let { sessions.findById(it.sessionId) })
                }
            }
            if (captured.progress.complete) break
            if (captured.source == null) {
                val completed = database.useWriterConnection { connection ->
                    connection.immediateTransaction {
                        requireMaintenanceState(expectedPolicy, expectedGeneration)
                        if (progress() != captured.progress) false else {
                            writeProgress(captured.progress.copy(complete = true))
                            true
                        }
                    }
                }
                if (!completed) skipped += 1
                break
            }
            val source = captured.source
            var issue: SessionCatalogMaintenanceIssue? = null
            val decoded = try {
                backgroundCodec.decode(source.envelope).also {
                    require(it.sessionId.value == source.sessionId && it.executionId == source.executionId)
                }
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                val kind = if (failure is StoredEnvelopeDecodeException && failure.failure != StoredEnvelopeDecodeFailure.CORRUPT) {
                    SessionCatalogMaintenanceIssueKind.INCOMPATIBLE_SCHEMA
                } else SessionCatalogMaintenanceIssueKind.UNREADABLE
                issue = SessionCatalogMaintenanceIssue(AgentSessionId(source.sessionId), kind)
                null
            }
            val acknowledged = decoded?.let { acknowledgement(it, captured.canonical) }
            val references = decoded?.let(::extractBackgroundExecutionAttachmentUris)
            processed += 1
            val installed = database.useWriterConnection { connection ->
                connection.immediateTransaction {
                    requireMaintenanceState(expectedPolicy, expectedGeneration)
                    if (progress() != captured.progress || backgrounds.findById(source.sessionId, source.executionId) != source ||
                        (decoded?.state == BackgroundExecutionState.TERMINAL && !canonicalMatches(captured.canonical, source.sessionId))) {
                        false
                    } else {
                        if (decoded != null) {
                            backgrounds.updateRecovery(source.sessionId, source.executionId, decoded.handle, decoded.state.name,
                                decoded.resultVersion, acknowledged, decoded.state != BackgroundExecutionState.TERMINAL || acknowledged != true)
                            RoomAttachmentReferenceIndex(database).replace(source.sessionId, executionReferenceSource(source.executionId), requireNotNull(references))
                            advanceRevision()
                        }
                        writeProgress(captured.progress.copy(afterSession = source.sessionId, afterExecution = source.executionId))
                        true
                    }
                }
            }
            if (!installed) {
                skipped += 1
                break
            }
            if (decoded != null) indexed += 1
            issue?.let(issues::add)
        }
        val complete = database.useWriterConnection { connection ->
            connection.immediateTransaction {
                requireMaintenanceState(expectedPolicy, expectedGeneration)
                val progress = progress()
                if (!progress.complete && backgrounds.recoveryBackfillPage(progress.afterSession, progress.afterExecution, 1).isEmpty()) {
                    writeProgress(progress.copy(complete = true))
                }
                backgroundBackfillComplete()
            }
        }
        return RoomBackgroundRecoveryBackfillResult(processed, indexed, skipped, complete, issues)
    }

    private fun acknowledgement(record: BackgroundExecutionRecord, canonical: AgentSessionEntity?): Boolean? {
        if (record.state != BackgroundExecutionState.TERMINAL || canonical == null) return false
        val snapshot = try {
            snapshotCodec.decode(canonical.payload)
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Exception) {
            return null
        }
        if (snapshot.sessionId.value != canonical.sessionId || snapshot.updatedAtEpochMs != canonical.updatedAtEpochMs) return null
        return backgroundResultDeliveryFacts(record.handle, record.resultVersion, snapshot.state.messages).acknowledged
    }

    private suspend fun canonicalMatches(captured: AgentSessionEntity?, sessionId: String): Boolean {
        val current = sessions.identity(sessionId)
        return if (captured == null) current == null else current != null &&
            current.incarnation == captured.incarnation && current.rowVersion == captured.rowVersion
    }

    private suspend fun generation(): String = requireNotNull(controls.state("generation")).value

    private suspend fun requireMaintenanceState(policy: SessionCatalogPolicy, expectedGeneration: Long) {
        if (controls.state("policyId")?.value != policy.id || controls.state("policyVersion")?.value != policy.version.toString() ||
            controls.state("generation")?.value != expectedGeneration.toString()) {
            throw SessionCatalogException(SessionCatalogFailure.STALE_MAINTENANCE)
        }
    }

    private suspend fun progress(): BackgroundBackfillProgress {
        val generation = generation()
        if (controls.state(BACKFILL_GENERATION)?.value != generation) return BackgroundBackfillProgress(generation)
        return BackgroundBackfillProgress(generation, controls.state(BACKFILL_AFTER_SESSION)?.value,
            controls.state(BACKFILL_AFTER_EXECUTION)?.value, controls.state(BACKFILL_COMPLETE)?.value == "true")
    }

    private suspend fun writeProgress(progress: BackgroundBackfillProgress) {
        controls.upsertState(CatalogStateEntity(BACKFILL_GENERATION, progress.generation))
        controls.upsertState(CatalogStateEntity(BACKFILL_AFTER_SESSION, progress.afterSession ?: ""))
        controls.upsertState(CatalogStateEntity(BACKFILL_AFTER_EXECUTION, progress.afterExecution ?: ""))
        controls.upsertState(CatalogStateEntity(BACKFILL_COMPLETE, progress.complete.toString()))
    }

    private suspend fun advanceRevision() {
        val revision = requireNotNull(controls.state("revision")).value.toLong()
        if (revision == Long.MAX_VALUE) throw SessionCatalogException(SessionCatalogFailure.COUNTER_EXHAUSTED)
        controls.upsertState(CatalogStateEntity("revision", (revision + 1).toString()))
    }

    private fun AgentBackgroundRecoveryEntity.hasKnownSource(): Boolean = catalogHandle != null && catalogResultVersion != null &&
        catalogState in setOf(BackgroundExecutionState.QUEUED.name, BackgroundExecutionState.RUNNING.name, BackgroundExecutionState.TERMINAL.name)

    private data class BackgroundBackfillProgress(
        val generation: String,
        val afterSession: String? = null,
        val afterExecution: String? = null,
        val complete: Boolean = false,
    )

    private data class BackgroundBackfillCapture(
        val progress: BackgroundBackfillProgress,
        val source: AgentBackgroundExecutionEntity?,
        val canonical: AgentSessionEntity?,
    )

    private companion object {
        const val BACKFILL_GENERATION = "recovery.background.generation"
        const val BACKFILL_AFTER_SESSION = "recovery.background.afterSession"
        const val BACKFILL_AFTER_EXECUTION = "recovery.background.afterExecution"
        const val BACKFILL_COMPLETE = "recovery.background.complete"
    }
}
