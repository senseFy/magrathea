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
import saien.magrathea.catalog.SessionCatalogMaintenance
import saien.magrathea.catalog.SessionCatalogMaintenanceBatch
import saien.magrathea.catalog.SessionCatalogMaintenanceIssue
import saien.magrathea.catalog.SessionCatalogMaintenanceIssueKind
import saien.magrathea.catalog.SessionCatalogMaintenanceProgress
import saien.magrathea.catalog.SessionCatalogMigrationEncoding
import saien.magrathea.catalog.SessionCatalogPolicy
import saien.magrathea.catalog.SessionCatalogProjection
import saien.magrathea.catalog.SessionCatalogState
import saien.magrathea.catalog.SessionIdentity
import saien.magrathea.catalog.attachmentNameBackupUris
import saien.magrathea.catalog.extractSessionAttachmentUris
import saien.magrathea.core.AgentCheckpoint
import saien.magrathea.core.AgentCheckpointCodec
import saien.magrathea.core.AgentPersistenceRecord
import saien.magrathea.core.AgentSessionId
import saien.magrathea.core.AgentSessionSnapshot
import saien.magrathea.core.AgentSessionSnapshotCodec
import saien.magrathea.core.StoredEnvelopeDecodeException
import saien.magrathea.core.StoredEnvelopeDecodeFailure
import saien.magrathea.core.StoredEnvelopeDecodeResult

private const val MAINTENANCE_GENERATION = "maintenance.generation"
private const val SESSION_CURSOR = "maintenance.afterSession"
private const val COLLECTION_CURSOR = "maintenance.afterCollection"
private const val SESSIONS_COMPLETE = "maintenance.sessionsComplete"
private const val COLLECTIONS_COMPLETE = "maintenance.collectionsComplete"
private const val PROCESSED = "maintenance.processed"
private const val FAILED = "maintenance.failed"

/** A finite control record, shared by clear and a controlled rebuild. Never stores source payloads. */
internal suspend fun resetRoomMaintenanceProgress(
    dao: SessionCatalogDao,
    generation: Long,
    complete: Boolean,
    collectionsComplete: Boolean = complete,
) {
    dao.upsertState(CatalogStateEntity(MAINTENANCE_GENERATION, generation.toString()))
    dao.deleteState(SESSION_CURSOR)
    dao.deleteState(COLLECTION_CURSOR)
    dao.upsertState(CatalogStateEntity(SESSIONS_COMPLETE, complete.toString()))
    dao.upsertState(CatalogStateEntity(COLLECTIONS_COMPLETE, collectionsComplete.toString()))
    dao.upsertState(CatalogStateEntity(PROCESSED, "0"))
    dao.upsertState(CatalogStateEntity(FAILED, "0"))
}

internal suspend fun roomMaintenanceCollectionsComplete(dao: SessionCatalogDao): Boolean =
    dao.state(COLLECTIONS_COMPLETE)?.value != "false"

/** A stepper, not a worker: each call does bounded work and never owns a maintenance loop. */
internal class RoomCatalogMaintenance(
    private val database: MagratheaDatabase,
    private val catalog: RoomSessionCatalog,
    private val json: Json = Json,
) : SessionCatalogMaintenance {
    private val dao = database.catalogDao()
    private val sessions = database.sessionDao()
    private val checkpoints = database.checkpointDao()
    private val snapshotCodec = AgentSessionSnapshotCodec(json)
    private val checkpointCodec = AgentCheckpointCodec(json)
    private val recovery = RoomRecoveryIndex(database, json)
    private val referenceIndex = RoomAttachmentReferenceIndex(database, json)

    private data class Position(
        val state: SessionCatalogState,
        val afterSession: String?,
        val afterCollection: String?,
        val sessionsComplete: Boolean,
        val collectionsComplete: Boolean,
        val processed: Long,
        val failed: Long,
    )

    private data class Source(
        val session: AgentSessionEntity?,
        val checkpoint: AgentCheckpointEntity?,
        val backup: SessionAttachmentNameBackupEntity?,
    )

    private sealed interface Computed {
        data class Projected(
            val snapshot: AgentSessionSnapshot,
            val snapshotRewrite: String?,
            val checkpointRewrite: String?,
            val projection: SessionCatalogProjection,
            val references: Set<String>,
            val backupReferences: Set<String>?,
        ) : Computed
        data class Failed(val kind: SessionCatalogMaintenanceIssueKind) : Computed
        data object Missing : Computed
    }

    private data class Outcome(val indexed: Boolean, val skipped: Boolean, val issue: SessionCatalogMaintenanceIssue? = null)

    override suspend fun status(): SessionCatalogMaintenanceProgress = catalog.operation {
        database.useReaderConnection { connection -> connection.deferredTransaction { progress(position(catalog.readState())) } }
    }

    override suspend fun rebuild(expectedPolicy: SessionCatalogPolicy, expectedGeneration: Long): SessionCatalogMaintenanceProgress =
        catalog.operation(requireMatchingPolicy = false) {
            database.useWriterConnection { connection ->
                connection.immediateTransaction {
                    val before = catalog.readState(requireMatchingPolicy = false)
                    if (before.policy != expectedPolicy || before.generation != expectedGeneration) stale()
                    // Activity identity/order is authority. A projector replacement is not its migration.
                    if (dao.state("activityMode")?.value != catalog.projector.activityMode.name) {
                        throw SessionCatalogException(SessionCatalogFailure.POLICY_MISMATCH)
                    }
                    val generation = increment(before.generation)
                    dao.clearHeaders()
                    dao.clearSearch()
                    dao.clearReferences()
                    put("policyId", catalog.projector.policy.id)
                    put("policyVersion", catalog.projector.policy.version.toString())
                    put("generation", generation.toString())
                    put("coverage", "incomplete")
                    resetRoomMaintenanceProgress(dao, generation, complete = false)
                    catalog.markCollectionsChanged(catalog.advanceRevision())
                    progress(position(catalog.readState()))
                }
            }
        }

    override suspend fun runBatch(limit: Int): SessionCatalogMaintenanceBatch {
        require(limit in 1..MAX_CATALOG_MAINTENANCE_BATCH_SIZE)
        val initial = catalog.operation {
            database.useWriterConnection { connection ->
                connection.immediateTransaction {
                    val state = catalog.readState()
                    if (dao.state(MAINTENANCE_GENERATION)?.value != state.generation.toString()) {
                        resetRoomMaintenanceProgress(dao, state.generation, complete = state.coverage == CatalogCoverage.COMPLETE)
                    }
                    position(state)
                }
            }
        }
        var remaining = limit
        var indexed = 0
        var skipped = 0
        val issues = mutableListOf<SessionCatalogMaintenanceIssue>()
        var expected = initial

        // Collection names are authoritative; only their policy-normalized search value is rebuilt.
        while (remaining > 0 && !expected.collectionsComplete) {
            val captured = readStep(expected) {
                val id = collectionIds(expected.afterCollection, 1).firstOrNull()
                id to id?.let { dao.collection(it) }
            }
            val id = captured.first
            if (id == null) {
                expected = writeStep(expected) {
                    put(COLLECTIONS_COMPLETE, "true")
                    if (expected.sessionsComplete) {
                        put("coverage", if (expected.failed == 0L) "complete" else "partial")
                        catalog.advanceRevision()
                    }
                }
                continue
            }
            val source = captured.second
            val normalized = source?.let {
                try {
                    catalog.projector.normalizeSearch(it.name)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    throw SessionCatalogException(SessionCatalogFailure.MAINTENANCE_PROJECTION_FAILED)
                }
            }
            remaining--
            var raced = false
            expected = writeStep(expected) {
                if (dao.collection(id) != source) raced = true else {
                    val revision = catalog.advanceRevision()
                    if (source != null && source.normalizedName != normalized) {
                        dao.upsertCollection(
                            source.copy(normalizedName = requireNotNull(normalized), revision = revision),
                        )
                        catalog.markCollectionsChanged(revision)
                    }
                    put(COLLECTION_CURSOR, id)
                }
            }
            if (raced) return SessionCatalogMaintenanceBatch(currentProgress(expected), indexed, skipped, issues)
        }

        while (remaining > 0 && !expected.sessionsComplete) {
            val captured = readStep(expected) {
                val id = sessionIds(expected.afterSession, 1).firstOrNull()
                id to if (id == null) null else Source(sessions.findById(id), checkpoints.findById(id), dao.attachmentNameBackup(id))
            }
            val id = captured.first
            if (id == null) {
                expected = writeStep(expected) {
                    put(SESSIONS_COMPLETE, "true")
                    put("coverage", if (expected.failed == 0L) "complete" else "partial")
                    catalog.advanceRevision()
                }
                continue
            }
            val source = requireNotNull(captured.second)
            // No database connection or catalog mutex is held during potentially large decoding.
            val computed = compute(source)
            remaining--
            var raced = false
            var outcome: Outcome? = null
            expected = writeStep(expected) {
                if (sessions.findById(id) != source.session || checkpoints.findById(id) != source.checkpoint ||
                    dao.attachmentNameBackup(id) != source.backup) {
                    raced = true
                } else {
                    outcome = when (computed) {
                        Computed.Missing -> Outcome(indexed = false, skipped = true)
                        is Computed.Failed -> Outcome(false, false, SessionCatalogMaintenanceIssue(AgentSessionId(id), computed.kind))
                        is Computed.Projected -> {
                            val success = catalog.writeMaintenanceProjection(requireNotNull(source.session), source.checkpoint,
                                computed.snapshot, computed.snapshotRewrite, computed.checkpointRewrite, computed.projection, computed.references)
                            if (success && computed.backupReferences != null) referenceIndex.replace(id, ATTACHMENT_NAME_BACKUP_SOURCE, computed.backupReferences)
                            if (success) Outcome(indexed = true, skipped = false) else Outcome(false, false,
                                SessionCatalogMaintenanceIssue(AgentSessionId(id), SessionCatalogMaintenanceIssueKind.PROJECTION_FAILED))
                        }
                    }
                    put(SESSION_CURSOR, id)
                    put(PROCESSED, increment(expected.processed).toString())
                    if (requireNotNull(outcome).issue != null) {
                        put(FAILED, increment(expected.failed).toString())
                        put("coverage", "partial")
                    }
                    catalog.advanceRevision()
                }
            }
            // A changing source is retried on the next bounded call; it is never silently skipped.
            if (raced) return SessionCatalogMaintenanceBatch(currentProgress(expected), indexed, skipped, issues)
            requireNotNull(outcome).let {
                if (it.indexed) indexed++
                if (it.skipped) skipped++
                it.issue?.let(issues::add)
            }
        }

        if (remaining > 0 && expected.sessionsComplete && expected.collectionsComplete) {
            val background = catalog.operation {
                recovery.backfillBackgroundBatch(remaining, expected.state.policy, expected.state.generation)
            }
            indexed += background.indexed
            skipped += background.skipped
            issues += background.issues
            remaining -= background.processed
            if (remaining > 0 && background.complete) {
                val origins = catalog.operation {
                    referenceIndex.backfillOrigins(remaining, expected.state.policy, expected.state.generation)
                }
                indexed += origins.indexed
                skipped += origins.skipped
                issues += origins.issues
            }
        }
        return SessionCatalogMaintenanceBatch(currentProgress(expected), indexed, skipped, issues)
    }

    private fun compute(source: Source): Computed {
        val session = source.session ?: return Computed.Missing
        val snapshot: StoredEnvelopeDecodeResult<AgentSessionSnapshot>
        val checkpoint: StoredEnvelopeDecodeResult<AgentCheckpoint>?
        val backupReferences: Set<String>?
        try {
            snapshot = snapshotCodec.decodeResult(session.payload)
            checkpoint = source.checkpoint?.let { checkpointCodec.decodeResult(it.payload) }
            if (snapshot.value.sessionId.value != session.sessionId || snapshot.value.updatedAtEpochMs != session.updatedAtEpochMs ||
                (checkpoint != null && (checkpoint.value.sessionId.value != session.sessionId || checkpoint.value.turn != source.checkpoint.turn ||
                    checkpoint.value.runId != snapshot.value.runId))) return Computed.Failed(SessionCatalogMaintenanceIssueKind.UNREADABLE)
            AgentPersistenceRecord(snapshot.value, checkpoint?.value)
            backupReferences = source.backup?.let {
                require(it.incarnation == session.incarnation)
                val backup = SessionCatalogMigrationEncoding.backup(it.payload)
                require(backup.identity == SessionIdentity(AgentSessionId(session.sessionId), session.incarnation))
                attachmentNameBackupUris(backup, json)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: StoredEnvelopeDecodeException) {
            return Computed.Failed(if (failure.failure == StoredEnvelopeDecodeFailure.CORRUPT) SessionCatalogMaintenanceIssueKind.UNREADABLE
                else SessionCatalogMaintenanceIssueKind.INCOMPATIBLE_SCHEMA)
        } catch (_: Exception) {
            return Computed.Failed(SessionCatalogMaintenanceIssueKind.UNREADABLE)
        }
        val projection = try {
            catalog.projector.project(snapshot.value)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return Computed.Failed(SessionCatalogMaintenanceIssueKind.PROJECTION_FAILED)
        }
        return Computed.Projected(snapshot.value, snapshot.rewritePayload, checkpoint?.rewritePayload, projection,
            extractSessionAttachmentUris(snapshot.value, checkpoint?.value), backupReferences)
    }

    private suspend fun <T> readStep(expected: Position, block: suspend () -> T): T = catalog.operation {
        database.useReaderConnection { connection ->
            connection.deferredTransaction {
                checkPosition(expected)
                block()
            }
        }
    }

    private suspend fun writeStep(expected: Position, block: suspend () -> Unit): Position = catalog.operation {
        database.useWriterConnection { connection ->
            connection.immediateTransaction {
                checkPosition(expected)
                block()
                position(catalog.readState())
            }
        }
    }

    private suspend fun currentProgress(expected: Position): SessionCatalogMaintenanceProgress = readStep(expected) {
        progress(position(catalog.readState()))
    }

    private suspend fun checkPosition(expected: Position) {
        val current = position(catalog.readState())
        // Revision deliberately does not participate: ordinary commits and organization edits proceed.
        if (current.state.generation != expected.state.generation || current.state.policy != expected.state.policy ||
            current.afterSession != expected.afterSession || current.afterCollection != expected.afterCollection ||
            current.sessionsComplete != expected.sessionsComplete || current.collectionsComplete != expected.collectionsComplete ||
            current.processed != expected.processed || current.failed != expected.failed) stale()
    }

    private suspend fun position(state: SessionCatalogState): Position {
        if (dao.state(MAINTENANCE_GENERATION)?.value != state.generation.toString()) {
            val complete = state.coverage == CatalogCoverage.COMPLETE
            return Position(state, null, null, complete, complete, 0, 0)
        }
        return Position(state, dao.state(SESSION_CURSOR)?.value, dao.state(COLLECTION_CURSOR)?.value,
            dao.state(SESSIONS_COMPLETE)?.value == "true", dao.state(COLLECTIONS_COMPLETE)?.value == "true",
            dao.state(PROCESSED)?.value?.toLong() ?: 0, dao.state(FAILED)?.value?.toLong() ?: 0)
    }

    private suspend fun progress(position: Position) = SessionCatalogMaintenanceProgress(position.state,
        position.afterSession?.let(::AgentSessionId), position.processed, position.failed,
        position.sessionsComplete && position.collectionsComplete && recovery.backgroundBackfillComplete() && referenceIndex.originBackfillComplete())

    private suspend fun sessionIds(after: String?, limit: Int): List<String> =
        if (after == null) dao.firstMaintenanceSessionIds(limit) else dao.maintenanceSessionIds(after, limit)

    private suspend fun collectionIds(after: String?, limit: Int): List<String> =
        if (after == null) dao.firstMaintenanceCollectionIds(limit) else dao.maintenanceCollectionIds(after, limit)

    private suspend fun put(key: String, value: String) = dao.upsertState(CatalogStateEntity(key, value))
    private fun increment(value: Long): Long {
        if (value == Long.MAX_VALUE) throw SessionCatalogException(SessionCatalogFailure.COUNTER_EXHAUSTED)
        return value + 1
    }
    private fun stale(): Nothing = throw SessionCatalogException(SessionCatalogFailure.STALE_MAINTENANCE)
}
