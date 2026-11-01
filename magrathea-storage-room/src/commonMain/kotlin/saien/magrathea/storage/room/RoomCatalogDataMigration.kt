package saien.magrathea.storage.room

import androidx.room.deferredTransaction
import androidx.room.immediateTransaction
import androidx.room.useReaderConnection
import androidx.room.useWriterConnection
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import saien.magrathea.catalog.CatalogKeyEncoding
import saien.magrathea.catalog.LegacySessionOrganization
import saien.magrathea.catalog.MAX_CATALOG_MIGRATION_BATCH_SIZE
import saien.magrathea.catalog.OrganizationImportReceipt
import saien.magrathea.catalog.OrganizationImportToken
import saien.magrathea.catalog.SessionAttachmentName
import saien.magrathea.catalog.SessionAttachmentNameBackup
import saien.magrathea.catalog.SessionAttachmentNamePage
import saien.magrathea.catalog.SessionAttachmentNamePatchResult
import saien.magrathea.catalog.SessionAttachmentNameScanOutcome
import saien.magrathea.catalog.SessionAttachmentNameScanProgress
import saien.magrathea.catalog.SessionAttachmentNameScanToken
import saien.magrathea.catalog.SessionAttachmentNameSource
import saien.magrathea.catalog.SessionAttachmentNameTarget
import saien.magrathea.catalog.SessionCatalogException
import saien.magrathea.catalog.SessionCatalogFailure
import saien.magrathea.catalog.SessionCatalogMaintenanceIssue
import saien.magrathea.catalog.SessionCatalogMaintenanceIssueKind
import saien.magrathea.catalog.SessionCatalogMigration
import saien.magrathea.catalog.SessionCatalogMigrationEncoding
import saien.magrathea.catalog.SessionCollection
import saien.magrathea.catalog.SessionIdentity
import saien.magrathea.catalog.SessionOrganization
import saien.magrathea.catalog.advanceAttachmentNameScanProgress
import saien.magrathea.catalog.attachmentNameBackupUris
import saien.magrathea.catalog.attachmentNameMap
import saien.magrathea.catalog.attachmentNameSessionCursor
import saien.magrathea.catalog.extractSessionAttachmentUris
import saien.magrathea.catalog.fillAttachmentNames
import saien.magrathea.catalog.sessionAttachmentNameTarget
import saien.magrathea.catalog.validateOrganizationImportBatch
import saien.magrathea.core.AgentCheckpointCodec
import saien.magrathea.core.AgentPersistenceRecord
import saien.magrathea.core.AgentSessionId
import saien.magrathea.core.AgentSessionSnapshotCodec
import saien.magrathea.core.SystemIdGenerator

private const val IMPORT_RECEIPT = "migration.organization.receipt"
private const val IMPORT_BATCH = "migration.organization.lastBatch"
private const val STORE_ID = "migration.storeId"
private const val LIFECYCLE = "migration.lifecycle"
private const val NAME_SCAN = "migration.attachmentNames.scan"

internal suspend fun invalidateRoomOrganizationImport(dao: SessionCatalogDao) {
    val lifecycle = dao.state(LIFECYCLE)?.value?.toLong() ?: 0L
    if (lifecycle == Long.MAX_VALUE) throw SessionCatalogException(SessionCatalogFailure.COUNTER_EXHAUSTED)
    dao.upsertState(CatalogStateEntity(LIFECYCLE, (lifecycle + 1).toString()))
    dao.state(IMPORT_RECEIPT)?.value?.let(SessionCatalogMigrationEncoding::receipt)?.takeUnless { it.complete }?.let {
        dao.upsertState(CatalogStateEntity(IMPORT_RECEIPT, SessionCatalogMigrationEncoding.receipt(it.copy(invalidated = true))))
    }
}

/** Fixed typed migrations. Raw recovery envelopes live with canonical authority, never in catalog_state. */
internal class RoomCatalogDataMigration(
    private val database: MagratheaDatabase,
    private val catalog: RoomSessionCatalog,
    private val json: Json,
) : SessionCatalogMigration {
    private val dao = database.catalogDao()
    private val sessions = database.sessionDao()
    private val checkpoints = database.checkpointDao()
    private val snapshotCodec = AgentSessionSnapshotCodec(json)
    private val checkpointCodec = AgentCheckpointCodec(json)
    private data class Source(
        val session: AgentSessionEntity,
        val checkpoint: AgentCheckpointEntity?,
        val backup: SessionAttachmentNameBackupEntity?,
    )

    private suspend fun <T> read(block: suspend () -> T): T = catalog.operation {
        database.useReaderConnection { connection ->
            connection.deferredTransaction {
                catalog.readState()
                block()
            }
        }
    }

    private suspend fun <T> write(block: suspend () -> T): T = catalog.operation {
        database.useWriterConnection { connection ->
            connection.immediateTransaction {
                catalog.readState()
                block()
            }
        }
    }

    private suspend fun receipt() = dao.state(IMPORT_RECEIPT)?.value?.let(SessionCatalogMigrationEncoding::receipt)

    private suspend fun save(receipt: OrganizationImportReceipt) = dao.upsertState(
        CatalogStateEntity(IMPORT_RECEIPT, SessionCatalogMigrationEncoding.receipt(receipt)),
    )

    override suspend fun organizationImportAllowed(): Boolean = read {
        (dao.state(LIFECYCLE)?.value?.toLong() ?: 0L) == 0L && receipt()?.invalidated != true
    }
    override suspend fun organizationImportStatus(): OrganizationImportReceipt? = read { receipt() }

    override suspend fun beginOrganizationImport(sourceId: String, fingerprint: String): OrganizationImportReceipt = write {
        require(sourceId.isNotBlank() && fingerprint.isNotBlank())
        receipt()?.let {
            if (it.token.sourceId != sourceId || it.token.fingerprint != fingerprint) fail(SessionCatalogFailure.MIGRATION_CONFLICT)
            if (it.invalidated) fail(SessionCatalogFailure.STALE_MIGRATION)
            return@write it
        }
        val lifecycle = dao.state(LIFECYCLE)?.value?.toLong() ?: 0L
        if (lifecycle != 0L) fail(SessionCatalogFailure.STALE_MIGRATION)
        val storeId = dao.state(STORE_ID)?.value ?: SystemIdGenerator.nextId().also { dao.upsertState(CatalogStateEntity(STORE_ID, it)) }
        OrganizationImportReceipt(OrganizationImportToken(storeId, lifecycle, sourceId, fingerprint)).also {
            save(it)
            catalog.advanceRevision()
        }
    }

    override suspend fun importOrganizationBatch(
        token: OrganizationImportToken,
        batchIndex: Long,
        collections: List<SessionCollection>,
        organizations: List<LegacySessionOrganization>,
        complete: Boolean,
    ): OrganizationImportReceipt {
        validateOrganizationImportBatch(collections, organizations)
        require(batchIndex >= 0)
        val signature = SessionCatalogMigrationEncoding.batch(collections, organizations, complete)
        val normalized = collections.associate { it.id to catalog.projector.normalizeSearch(it.name) }
        return write {
            val previous = receipt() ?: fail(SessionCatalogFailure.STALE_MIGRATION)
            if (previous.token != token || previous.invalidated) fail(SessionCatalogFailure.STALE_MIGRATION)
            if (batchIndex < previous.nextBatch) {
                if (batchIndex == previous.nextBatch - 1 && dao.state(IMPORT_BATCH)?.value != signature) fail(SessionCatalogFailure.MIGRATION_CONFLICT)
                return@write previous
            }
            if (previous.complete || batchIndex != previous.nextBatch) fail(SessionCatalogFailure.MIGRATION_CONFLICT)
            var imported = 0L
            var skipped = 0L
            val revision = catalog.advanceRevision()
            collections.forEach { value ->
                val old = dao.collection(value.id)
                if (old != null && (old.name != value.name || old.nameSortKey != value.nameSortKey || old.uniqueKey != value.nameUniqueKey)) {
                    fail(SessionCatalogFailure.COLLECTION_CONFLICT)
                }
                if (dao.collectionByUniqueKey(value.nameUniqueKey)?.collectionId?.let { it != value.id } == true) fail(SessionCatalogFailure.COLLECTION_CONFLICT)
                if (old == null) dao.upsertCollection(SessionCollectionEntity(value.id, value.name, value.nameSortKey,
                    CatalogKeyEncoding.stringKey(value.id), normalized.getValue(value.id), value.nameUniqueKey, revision))
            }
            organizations.forEach { value ->
                val canonical = sessions.identity(value.sessionId.value)
                if (canonical == null || canonical.incarnation != "legacy") {
                    skipped++
                    return@forEach
                }
                val organization = value.organization
                organization.collectionId?.let { if (dao.collection(it) == null) fail(SessionCatalogFailure.COLLECTION_NOT_FOUND) }
                val old = dao.organization(value.sessionId.value)
                if (old != null && (old.incarnation != "legacy" || SessionOrganization(old.title, old.collectionId, old.pinnedAt) != organization)) {
                    fail(SessionCatalogFailure.MIGRATION_CONFLICT)
                }
                catalog.updateOrganizationRows(SessionIdentity(value.sessionId, "legacy"), organization)
                imported++
            }
            previous.copy(
                nextBatch = next(previous.nextBatch),
                imported = add(previous.imported, imported),
                skipped = add(previous.skipped, skipped),
                complete = complete,
            ).also {
                save(it)
                dao.upsertState(CatalogStateEntity(IMPORT_BATCH, signature))
            }
        }
    }

    private suspend fun scanProgress() = dao.state(NAME_SCAN)?.value?.let(SessionCatalogMigrationEncoding::scanProgress)

    private suspend fun saveScan(progress: SessionAttachmentNameScanProgress) = dao.upsertState(
        CatalogStateEntity(NAME_SCAN, SessionCatalogMigrationEncoding.scanProgress(progress)),
    )

    private suspend fun checkScan(expected: SessionAttachmentNameScanProgress) {
        if (scanProgress() != expected || (dao.state(LIFECYCLE)?.value?.toLong() ?: 0L) != expected.token.lifecycle) fail(SessionCatalogFailure.STALE_MIGRATION)
    }

    override suspend fun beginAttachmentNameScan(sourceId: String, sourceVersion: Int): SessionAttachmentNameScanProgress = write {
        require(sourceId.isNotBlank() && sourceVersion > 0)
        val lifecycle = dao.state(LIFECYCLE)?.value?.toLong() ?: 0L
        val old = scanProgress()
        if (old != null && old.token.lifecycle == lifecycle && old.token.sourceId == sourceId && old.token.sourceVersion == sourceVersion) return@write old
        val storeId = dao.state(STORE_ID)?.value ?: SystemIdGenerator.nextId().also { dao.upsertState(CatalogStateEntity(STORE_ID, it)) }
        val progress = SessionAttachmentNameScanProgress(SessionAttachmentNameScanToken(storeId, lifecycle, next(old?.token?.generation ?: 0L), sourceId, sourceVersion))
        saveScan(progress)
        catalog.advanceRevision()
        progress
    }

    override suspend fun restartAttachmentNameScan(expected: SessionAttachmentNameScanProgress): SessionAttachmentNameScanProgress = write {
        checkScan(expected)
        val progress = SessionAttachmentNameScanProgress(expected.token.copy(generation = next(expected.token.generation)))
        saveScan(progress)
        catalog.advanceRevision()
        progress
    }

    override suspend fun attachmentNameScanPage(
        expected: SessionAttachmentNameScanProgress,
        limit: Int,
    ): SessionAttachmentNamePage {
        require(limit in 1..MAX_CATALOG_MIGRATION_BATCH_SIZE)
        val ids = read {
            checkScan(expected)
            if (expected.complete) emptyList() else maintenanceIds(expected.afterSessionId, limit + 1)
        }
        return namePage(ids, limit, expected)
    }
    override suspend fun advanceAttachmentNameScan(
        expected: SessionAttachmentNameScanProgress,
        source: SessionAttachmentNameSource,
        outcome: SessionAttachmentNameScanOutcome,
    ): SessionAttachmentNameScanProgress = write {
        checkScan(expected)
        val identity = source.identity ?: fail(SessionCatalogFailure.STALE_MIGRATION)
        val version = source.rowVersion ?: fail(SessionCatalogFailure.STALE_MIGRATION)
        val after = expected.afterSessionId
        if (source.cursor != attachmentNameSessionCursor(identity.sessionId)) fail(SessionCatalogFailure.STALE_MIGRATION)
        val nextSource = if (after == null) dao.canAdvanceFirstAttachmentNameSource(identity.sessionId.value)
            else dao.canAdvanceAttachmentNameSource(after.value, identity.sessionId.value)
        if (!nextSource) fail(SessionCatalogFailure.STALE_MIGRATION)
        sessions.identity(identity.sessionId.value)?.let { current ->
            if (current.incarnation != identity.incarnation || current.rowVersion < version ||
                (outcome != SessionAttachmentNameScanOutcome.SKIPPED_MANAGED && current.rowVersion != version)) fail(SessionCatalogFailure.STALE_MIGRATION)
        }
        val progress = advanceAttachmentNameScanProgress(expected, source, outcome)
        saveScan(progress)
        catalog.advanceRevision()
        progress
    }

    override suspend fun finishAttachmentNameScan(expected: SessionAttachmentNameScanProgress): SessionAttachmentNameScanProgress = write {
        checkScan(expected)
        if (expected.complete) return@write expected
        if (maintenanceIds(expected.afterSessionId, 1).isNotEmpty()) fail(SessionCatalogFailure.STALE_MIGRATION)
        val progress = expected.copy(complete = true)
        saveScan(progress)
        catalog.advanceRevision()
        progress
    }

    private suspend fun maintenanceIds(after: AgentSessionId?, limit: Int) =
        if (after == null) dao.firstMaintenanceSessionIds(limit) else dao.maintenanceSessionIds(after.value, limit)

    private suspend fun source(
        id: AgentSessionId,
        expected: SessionAttachmentNameScanProgress? = null,
    ): Source? = read {
        if (expected != null) checkScan(expected)
        sessions.findById(id.value)?.let { Source(it, checkpoints.findById(id.value), dao.attachmentNameBackup(id.value)) }
    }

    private fun record(source: Source): AgentPersistenceRecord = decode {
        val snapshot = snapshotCodec.decodeResult(source.session.payload).value
        val checkpoint = source.checkpoint?.let { checkpointCodec.decodeResult(it.payload).value }
        require(snapshot.sessionId.value == source.session.sessionId && snapshot.updatedAtEpochMs == source.session.updatedAtEpochMs)
        require(checkpoint == null || checkpoint.turn == source.checkpoint.turn)
        AgentPersistenceRecord(snapshot, checkpoint)
    }
    private fun target(source: Source, afterUri: String?): SessionAttachmentNameTarget = sessionAttachmentNameTarget(
        SessionIdentity(AgentSessionId(source.session.sessionId), source.session.incarnation),
        source.session.rowVersion,
        record(source),
        afterUri,
    )

    override suspend fun attachmentNameTarget(id: AgentSessionId, afterUri: String?): SessionAttachmentNameTarget? = source(id)?.let { target(it, afterUri) }
    override suspend fun attachmentNameTargets(afterSessionId: AgentSessionId?, limit: Int): SessionAttachmentNamePage {
        require(limit in 1..MAX_CATALOG_MIGRATION_BATCH_SIZE)
        return namePage(read { maintenanceIds(afterSessionId, limit + 1) }, limit)
    }
    private suspend fun namePage(
        ids: List<String>,
        limit: Int,
        expected: SessionAttachmentNameScanProgress? = null,
    ): SessionAttachmentNamePage {
        val entries = mutableListOf<SessionAttachmentNameTarget>()
        val issues = mutableListOf<SessionCatalogMaintenanceIssue>()
        val sources = mutableListOf<SessionAttachmentNameSource>()
        ids.take(limit).forEach { id ->
            val captured = source(AgentSessionId(id), expected) ?: return@forEach
            sources += SessionAttachmentNameSource(
                SessionIdentity(AgentSessionId(id), captured.session.incarnation),
                captured.session.rowVersion,
                attachmentNameSessionCursor(AgentSessionId(id)),
            )
            try {
                entries += target(captured, null)
            } catch (failure: SessionCatalogException) {
                if (failure.failure != SessionCatalogFailure.UNREADABLE_MIGRATION_SOURCE) throw failure
                issues += SessionCatalogMaintenanceIssue(AgentSessionId(id), SessionCatalogMaintenanceIssueKind.UNREADABLE)
            }
        }
        return SessionAttachmentNamePage(entries, ids.take(limit).lastOrNull()?.let(::AgentSessionId), ids.size <= limit, issues, sources)
    }
    override suspend fun patchAttachmentNames(
        target: SessionAttachmentNameTarget,
        names: List<SessionAttachmentName>,
    ): SessionAttachmentNamePatchResult {
        val map = attachmentNameMap(names)
        val source = source(target.identity.sessionId) ?: fail(SessionCatalogFailure.STALE_MIGRATION)
        checkTarget(source, target)
        val original = record(source)
        val updated = fillAttachmentNames(original, map)
        return replace(source, target, updated, changed = updated != original)
    }
    override suspend fun attachmentNameBackup(identity: SessionIdentity): SessionAttachmentNameBackup? = read {
        val current = sessions.identity(identity.sessionId.value) ?: return@read null
        if (current.incarnation != identity.incarnation) fail(SessionCatalogFailure.STALE_IDENTITY)
        val entity = dao.attachmentNameBackup(identity.sessionId.value) ?: return@read null
        decode {
            require(entity.incarnation == identity.incarnation)
            SessionCatalogMigrationEncoding.backup(entity.payload).also {
                require(it.identity == identity)
            }
        }
    }
    override suspend fun restoreAttachmentNameBackup(target: SessionAttachmentNameTarget): SessionAttachmentNamePatchResult {
        val source = source(target.identity.sessionId) ?: fail(SessionCatalogFailure.STALE_MIGRATION)
        checkTarget(source, target)
        val backup = source.backup?.payload?.let(SessionCatalogMigrationEncoding::backup)
            ?: return SessionAttachmentNamePatchResult(target.identity, target.rowVersion, false)
        val restored = decode { AgentPersistenceRecord(snapshotCodec.decodeResult(backup.snapshotPayload).value,
            backup.checkpointPayload?.let { checkpointCodec.decodeResult(it).value }) }
        if (backup.identity != target.identity || restored.snapshot.sessionId != target.identity.sessionId) fail(SessionCatalogFailure.UNREADABLE_MIGRATION_SOURCE)
        return replace(source, target, restored, restored != record(source))
    }
    private suspend fun replace(
        source: Source,
        target: SessionAttachmentNameTarget,
        updated: AgentPersistenceRecord,
        changed: Boolean,
    ): SessionAttachmentNamePatchResult {
        val projection = catalog.projector.project(updated.snapshot)
        val snapshot = snapshotCodec.encode(updated.snapshot)
        val checkpoint = updated.checkpoint?.let(checkpointCodec::encode)
        val backup = source.backup?.payload ?: SessionCatalogMigrationEncoding.backup(
            SessionAttachmentNameBackup(target.identity, target.rowVersion, source.session.payload, source.checkpoint?.payload))
        val backupReferences = decode {
            val value = SessionCatalogMigrationEncoding.backup(backup)
            require(value.identity == target.identity)
            attachmentNameBackupUris(value, json)
        }
        return write {
            if (sessions.findById(source.session.sessionId) != source.session ||
                checkpoints.findById(source.session.sessionId) != source.checkpoint ||
                dao.attachmentNameBackup(source.session.sessionId) != source.backup) {
                fail(SessionCatalogFailure.STALE_MIGRATION)
            }
            if (!changed) return@write SessionAttachmentNamePatchResult(target.identity, target.rowVersion, false)
            val restoredSource = source.session.copy(updatedAtEpochMs = updated.snapshot.updatedAtEpochMs)
            if (!catalog.writeMaintenanceProjection(restoredSource, source.checkpoint, updated.snapshot, snapshot, checkpoint, projection,
                    extractSessionAttachmentUris(updated.snapshot, updated.checkpoint))) fail(SessionCatalogFailure.MAINTENANCE_PROJECTION_FAILED)
            if (updated.checkpoint == null) checkpoints.deleteById(source.session.sessionId)
            else checkpoints.upsert(AgentCheckpointEntity(source.session.sessionId, requireNotNull(checkpoint), requireNotNull(updated.checkpoint).turn))
            if (source.backup == null) {
                dao.upsertAttachmentNameBackup(SessionAttachmentNameBackupEntity(source.session.sessionId, target.identity.incarnation, backup))
            }
            RoomAttachmentReferenceIndex(database).replace(source.session.sessionId, ATTACHMENT_NAME_BACKUP_SOURCE, backupReferences)
            catalog.advanceRevision()
            SessionAttachmentNamePatchResult(target.identity, next(target.rowVersion), true)
        }
    }
    private fun checkTarget(source: Source, target: SessionAttachmentNameTarget) {
        if (source.session.incarnation != target.identity.incarnation || source.session.rowVersion != target.rowVersion) fail(SessionCatalogFailure.STALE_MIGRATION)
    }
    private fun <T> decode(block: () -> T): T = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        fail(SessionCatalogFailure.UNREADABLE_MIGRATION_SOURCE)
    }

    private fun add(value: Long, amount: Long): Long {
        if (value > Long.MAX_VALUE - amount) fail(SessionCatalogFailure.COUNTER_EXHAUSTED)
        return value + amount
    }

    private fun next(value: Long) = add(value, 1)
    private fun fail(failure: SessionCatalogFailure): Nothing = throw SessionCatalogException(failure)
}
