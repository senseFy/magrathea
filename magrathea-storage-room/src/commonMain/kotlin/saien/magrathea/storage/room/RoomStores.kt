package saien.magrathea.storage.room

import androidx.room.RoomDatabase
import androidx.room.deferredTransaction
import androidx.room.immediateTransaction
import androidx.room.useReaderConnection
import androidx.room.useWriterConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import saien.magrathea.catalog.DefaultSessionCatalogProjector
import saien.magrathea.catalog.SessionActivityMode
import saien.magrathea.catalog.SessionCatalog
import saien.magrathea.catalog.SessionCatalogMaintenance
import saien.magrathea.catalog.SessionCatalogMigration
import saien.magrathea.catalog.SessionCatalogProjector
import saien.magrathea.catalog.extractBackgroundExecutionAttachmentUris
import saien.magrathea.catalog.extractBackgroundOriginAttachmentUris
import saien.magrathea.catalog.extractSessionAttachmentUris
import saien.magrathea.core.AgentCheckpoint
import saien.magrathea.core.AgentCheckpointCodec
import saien.magrathea.core.AgentPersistence
import saien.magrathea.core.AgentPersistenceRecord
import saien.magrathea.core.AgentRunId
import saien.magrathea.core.AgentSessionId
import saien.magrathea.core.AgentSessionSnapshot
import saien.magrathea.core.AgentSessionSnapshotCodec
import saien.magrathea.core.BackgroundExecutionRecord
import saien.magrathea.core.BackgroundExecutionRecordCodec
import saien.magrathea.core.BackgroundExecutionStore
import saien.magrathea.core.BackgroundOriginContext
import saien.magrathea.core.BackgroundOriginContextCodec
import saien.magrathea.core.BackgroundSessionRecords
import saien.magrathea.core.StoredEnvelopeDecodeException
import saien.magrathea.core.StoredEnvelopeDecodeFailure
import saien.magrathea.core.StoredEnvelopeDecodeResult

internal data class DecodedStoredRecord<T>(
    val value: T,
    val rewritePayload: String?,
)

internal fun <T> StoredEnvelopeDecodeResult<T>.toStoredRecord(): DecodedStoredRecord<T> =
    DecodedStoredRecord(value, rewritePayload)

enum class StoredRecordKind {
    SESSION,
    CHECKPOINT,
    BACKGROUND_EXECUTION,
    BACKGROUND_ORIGIN,
}

enum class StoredRecordCorruptionReason {
    INVALID_PAYLOAD,
    INDEX_MISMATCH,
}

data class StoredRecordCorruption(
    val kind: StoredRecordKind,
    val sessionId: String,
    val reason: StoredRecordCorruptionReason,
)

fun interface StoredRecordCorruptionReporter {
    /** Implementations must not throw. Reports intentionally contain no persisted payload or decoder message. */
    fun report(corruption: StoredRecordCorruption)
}

class StoredRecordCorruptionException(
    val corruption: StoredRecordCorruption,
) : IllegalStateException(
    "Stored ${corruption.kind.name.lowercase()} record is corrupt (${corruption.reason.name.lowercase()})",
)

/** Payload-free, sanitized identity and version details for a logical schema failure. */
data class StoredRecordSchemaIssue(
    val kind: StoredRecordKind,
    val sessionId: String?,
    val failure: StoredEnvelopeDecodeFailure,
    val storedSchemaVersion: Int,
    val currentSchemaVersion: Int,
) {
    init {
        require(failure != StoredEnvelopeDecodeFailure.CORRUPT) {
            "Corrupt records must use StoredRecordCorruption"
        }
        require(sessionId == null || sessionId.isSafeRecordIdentifier()) {
            "Room schema issue session identity must be sanitized"
        }
        require(currentSchemaVersion > 0) { "Current schema version must be positive" }
        require(storedSchemaVersion > 0) { "Stored schema version must be positive" }
        when (failure) {
            StoredEnvelopeDecodeFailure.UNSUPPORTED_OLDER_SCHEMA,
            StoredEnvelopeDecodeFailure.MIGRATION_FAILED ->
                require(storedSchemaVersion < currentSchemaVersion) {
                    "Older and migrated source schemas must precede the current schema"
                }
            StoredEnvelopeDecodeFailure.UNSUPPORTED_NEWER_SCHEMA ->
                require(storedSchemaVersion > currentSchemaVersion) {
                    "A newer source schema must follow the current schema"
                }
            StoredEnvelopeDecodeFailure.CORRUPT -> error("Handled above")
        }
    }
}

/** An incompatible logical payload schema that must not be silently treated as row corruption. */
class StoredRecordSchemaException(
    val issue: StoredRecordSchemaIssue,
) : IllegalStateException(
    "Stored ${issue.kind.name.lowercase()} record uses an incompatible schema " +
        "(${issue.failure.name.lowercase()})",
)

/** Owns one Room database and its atomic Agent persistence boundary. */
class MagratheaRoomStoreHandle internal constructor(
    private val database: MagratheaDatabase,
    reporter: StoredRecordCorruptionReporter,
    json: Json = Json,
    projector: SessionCatalogProjector = DefaultSessionCatalogProjector(),
) {
    private val roomCatalog = RoomSessionCatalog(database, projector)
    val catalog: SessionCatalog = roomCatalog
    val persistence: AgentPersistence = RoomAgentPersistence(database, reporter, json, catalog = roomCatalog)
    val backgroundStore: BackgroundExecutionStore =
        RoomBackgroundExecutionStore(database, reporter, json, catalog = roomCatalog)
    val maintenance: SessionCatalogMaintenance = RoomCatalogMaintenance(database, roomCatalog, json)
    val migration: SessionCatalogMigration = RoomCatalogDataMigration(database, roomCatalog, json)

    /**
     * Rejects new operations, drains admitted work, and closes the database. Repeated calls join the same close.
     * Cancelling the closing coroutine does not cancel admitted operations or abandon their drain.
     * A slow or non-returning host projector/decoder can delay completion. Stop producers and
     * cancel/join maintenance before closing; caller cancellation is not a close timeout.
     */
    suspend fun close() = roomCatalog.close()
}

internal fun buildMagratheaRoomStore(
    builder: RoomDatabase.Builder<MagratheaDatabase>,
    reporter: StoredRecordCorruptionReporter,
    json: Json = Json,
    projector: SessionCatalogProjector = DefaultSessionCatalogProjector(),
): MagratheaRoomStoreHandle {
    return MagratheaRoomStoreHandle(buildMagratheaRoomDatabase(builder), reporter, json, projector)
}

internal fun buildMagratheaRoomDatabase(
    builder: RoomDatabase.Builder<MagratheaDatabase>,
): MagratheaDatabase {
    return builder
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.Default)
        .addMigrations(
            MAGRATHEA_ROOM_MIGRATION_1_2,
            MAGRATHEA_ROOM_MIGRATION_2_3,
            MAGRATHEA_ROOM_MIGRATION_3_4,
        )
        .build()
}

/** Room-backed [BackgroundExecutionStore]; every write and delete runs in its own transaction. */
internal class RoomBackgroundExecutionStore(
    private val database: MagratheaDatabase,
    private val reporter: StoredRecordCorruptionReporter,
    json: Json = Json,
    private val recordCodec: BackgroundExecutionRecordCodec = BackgroundExecutionRecordCodec(json),
    private val originCodec: BackgroundOriginContextCodec = BackgroundOriginContextCodec(json),
    private val recordDecoder: (String) -> DecodedStoredRecord<BackgroundExecutionRecord> =
        { payload -> recordCodec.decodeResult(payload).toStoredRecord() },
    private val originDecoder: (String) -> DecodedStoredRecord<BackgroundOriginContext> =
        { payload -> originCodec.decodeResult(payload).toStoredRecord() },
    private val catalog: RoomSessionCatalog = RoomSessionCatalog(database),
) : BackgroundExecutionStore {
    private val executionsDao = database.backgroundExecutionDao()
    private val originsDao = database.backgroundOriginDao()
    private val recovery = RoomRecoveryIndex(database, json)
    private val references = RoomAttachmentReferenceIndex(database, json)

    override suspend fun upsert(record: BackgroundExecutionRecord) = catalog.operation {
        val envelope = recordCodec.encode(record)
        val uris = extractBackgroundExecutionAttachmentUris(record)
        while (true) {
            val prepared = recovery.prepareBackground(record)
            val committed = database.useWriterConnection { connection ->
                connection.immediateTransaction {
                    catalog.readState()
                    if (!recovery.matches(prepared, record.sessionId)) false else {
                        executionsDao.upsert(recovery.backgroundEntity(record, envelope, prepared))
                        references.replace(record.sessionId.value, executionReferenceSource(record.executionId), uris)
                        catalog.advanceRevision()
                        true
                    }
                }
            }
            if (committed) break
        }
    }

    override suspend fun putOrigin(origin: BackgroundOriginContext) = catalog.operation {
        val entity = AgentBackgroundOriginEntity(
            sessionId = origin.sessionId.value,
            runId = origin.runId.value,
            envelope = originCodec.encode(origin),
        )
        val uris = extractBackgroundOriginAttachmentUris(origin)
        database.useWriterConnection { connection ->
            connection.immediateTransaction {
                catalog.readState()
                originsDao.upsert(entity)
                references.replace(origin.sessionId.value, originReferenceSource(origin.runId.value), uris)
                catalog.advanceRevision()
            }
        }
        Unit
    }

    override suspend fun load(sessionId: AgentSessionId): BackgroundSessionRecords {
        val corruptions = mutableListOf<StoredRecordCorruption>()
        return try {
            catalog.operation {
                val (executions, origins) = database.useReaderConnection { connection ->
                    connection.deferredTransaction {
                        catalog.readState()
                        executionsDao.listBySession(sessionId.value) to originsDao.listBySession(sessionId.value)
                    }
                }
                BackgroundSessionRecords(
                    records = executions.mapNotNull { entity ->
                        try {
                            decodeRecord(entity).value
                        } catch (failure: StoredRecordCorruptionException) {
                            corruptions += failure.corruption
                            null
                        }
                    },
                    origins = origins.mapNotNull { entity ->
                        try {
                            decodeOrigin(entity).value
                        } catch (failure: StoredRecordCorruptionException) {
                            corruptions += failure.corruption
                            null
                        }
                    },
                )
            }
        } finally {
            corruptions.forEach(::reportCorruption)
        }
    }

    override suspend fun delete(sessionId: AgentSessionId, executionId: String) = catalog.operation {
        database.useWriterConnection { connection ->
            connection.immediateTransaction {
                catalog.readState()
                executionsDao.deleteById(sessionId.value, executionId)
                database.catalogDao().deleteSourceReferences(sessionId.value, executionReferenceSource(executionId))
                catalog.advanceRevision()
            }
        }
        Unit
    }

    override suspend fun deleteOrigin(sessionId: AgentSessionId, runId: AgentRunId) = catalog.operation {
        database.useWriterConnection { connection ->
            connection.immediateTransaction {
                catalog.readState()
                originsDao.deleteById(sessionId.value, runId.value)
                database.catalogDao().deleteSourceReferences(sessionId.value, originReferenceSource(runId.value))
                catalog.advanceRevision()
            }
        }
        Unit
    }

    override suspend fun deleteSession(sessionId: AgentSessionId) = catalog.operation {
        database.useWriterConnection { connection ->
            connection.immediateTransaction {
                catalog.readState()
                originsDao.deleteBySession(sessionId.value)
                executionsDao.deleteBySession(sessionId.value)
                database.catalogDao().deleteBackgroundReferences(sessionId.value)
                catalog.advanceRevision()
            }
        }
        Unit
    }

    override suspend fun clear() = catalog.operation {
        database.useWriterConnection { connection ->
            connection.immediateTransaction {
                catalog.readState()
                originsDao.deleteAll()
                executionsDao.deleteAll()
                database.catalogDao().clearBackgroundReferences()
                catalog.advanceRevision()
            }
        }
        Unit
    }

    private fun decodeRecord(
        entity: AgentBackgroundExecutionEntity,
    ): DecodedStoredRecord<BackgroundExecutionRecord> {
        val decoded = try {
            recordDecoder(entity.envelope)
        } catch (failure: StoredEnvelopeDecodeException) {
            throw decodeFailure(
                StoredRecordKind.BACKGROUND_EXECUTION,
                entity.sessionId,
                failure,
            )
        }
        if (
            decoded.value.sessionId.value != entity.sessionId ||
            decoded.value.executionId != entity.executionId
        ) {
            throw corruption(
                StoredRecordKind.BACKGROUND_EXECUTION,
                entity.sessionId,
                StoredRecordCorruptionReason.INDEX_MISMATCH,
            )
        }
        return decoded
    }

    private fun decodeOrigin(
        entity: AgentBackgroundOriginEntity,
    ): DecodedStoredRecord<BackgroundOriginContext> {
        val decoded = try {
            originDecoder(entity.envelope)
        } catch (failure: StoredEnvelopeDecodeException) {
            throw decodeFailure(StoredRecordKind.BACKGROUND_ORIGIN, entity.sessionId, failure)
        }
        if (
            decoded.value.sessionId.value != entity.sessionId ||
            decoded.value.runId.value != entity.runId
        ) {
            throw corruption(
                StoredRecordKind.BACKGROUND_ORIGIN,
                entity.sessionId,
                StoredRecordCorruptionReason.INDEX_MISMATCH,
            )
        }
        return decoded
    }

    private fun decodeFailure(
        kind: StoredRecordKind,
        sessionId: String,
        failure: StoredEnvelopeDecodeException,
    ): IllegalStateException = if (failure.failure == StoredEnvelopeDecodeFailure.CORRUPT) {
        corruption(kind, sessionId, StoredRecordCorruptionReason.INVALID_PAYLOAD)
    } else {
        StoredRecordSchemaException(
            StoredRecordSchemaIssue(
                kind = kind,
                sessionId = sessionId.takeIf(String::isSafeRecordIdentifier),
                failure = failure.failure,
                storedSchemaVersion = requireNotNull(failure.storedSchemaVersion),
                currentSchemaVersion = failure.currentSchemaVersion,
            ),
        )
    }

    private fun corruption(
        kind: StoredRecordKind,
        sessionId: String,
        reason: StoredRecordCorruptionReason,
    ): StoredRecordCorruptionException = StoredRecordCorruptionException(
        StoredRecordCorruption(kind, sessionId, reason),
    )

    /** Never invoke a host callback while a Room connection or transaction is held. */
    private fun reportCorruption(corruption: StoredRecordCorruption) {
        try {
            reporter.report(corruption)
        } catch (_: Throwable) {
            // Diagnostics must not replace the stable storage failure.
        }
    }
}

internal class RoomAgentPersistence(
    private val database: MagratheaDatabase,
    private val reporter: StoredRecordCorruptionReporter,
    json: Json = Json,
    private val snapshotCodec: AgentSessionSnapshotCodec = AgentSessionSnapshotCodec(json),
    private val checkpointCodec: AgentCheckpointCodec = AgentCheckpointCodec(json),
    private val snapshotDecoder: (String) -> DecodedStoredRecord<AgentSessionSnapshot> =
        { payload -> snapshotCodec.decodeResult(payload).toStoredRecord() },
    private val checkpointDecoder: (String) -> DecodedStoredRecord<AgentCheckpoint> =
        { payload -> checkpointCodec.decodeResult(payload).toStoredRecord() },
    private val catalog: RoomSessionCatalog = RoomSessionCatalog(database),
) : AgentPersistence {
    private val sessionDao = database.sessionDao()
    private val checkpointDao = database.checkpointDao()
    private val recovery = RoomRecoveryIndex(database, json)

    override suspend fun commit(
        snapshot: AgentSessionSnapshot,
        checkpoint: AgentCheckpoint?,
    ) = catalog.operation {
        val record = AgentPersistenceRecord(snapshot, checkpoint)
        val payload = snapshotCodec.encode(snapshot)
        val projection = catalog.projector.project(snapshot)
        val references = extractSessionAttachmentUris(snapshot, checkpoint)
        val legacy = if (catalog.projector.activityMode == SessionActivityMode.PREPARED &&
            sessionDao.identity(snapshot.sessionId.value)?.catalogActivityOrder == null) {
            sessionDao.findById(snapshot.sessionId.value)?.let { it to catalog.projector.project(decodeSession(it).value) }
        } else null
        val checkpointEntity = checkpoint?.let {
            AgentCheckpointEntity(
                sessionId = it.sessionId.value,
                payload = checkpointCodec.encode(it),
                turn = it.turn,
            )
        }
        completeWrite {
            val identity = database.useWriterConnection { connection ->
                connection.immediateTransaction {
                    val identity = catalog.storeSnapshot(snapshot, payload, projection, references, legacy)
                    if (record.checkpoint == null) {
                        checkpointDao.deleteById(snapshot.sessionId.value)
                    } else {
                        checkpointDao.upsert(requireNotNull(checkpointEntity))
                    }
                    recovery.refreshSnapshot(snapshot)
                    identity
                }
            }
            catalog.committed(identity, projection.activityIdentity)
        }
    }

    override suspend fun load(sessionId: AgentSessionId): AgentPersistenceRecord? {
        return try {
            catalog.operation { loadAndRewrite(sessionId) }
        } catch (failure: StoredRecordCorruptionException) {
            reportCorruption(failure.corruption)
            throw failure
        }
    }

    private suspend fun loadAndRewrite(sessionId: AgentSessionId): AgentPersistenceRecord? {
        while (true) {
            val (sessionEntity, checkpointEntity) = database.useReaderConnection { connection ->
                connection.deferredTransaction {
                    catalog.readState()
                    sessionDao.findById(sessionId.value) to checkpointDao.findById(sessionId.value)
                }
            }
            if (sessionEntity == null) return null
            val snapshot = decodeSession(sessionEntity)
            val checkpoint = checkpointEntity?.let(::decodeCheckpoint)
            if (checkpoint != null && (checkpoint.value.sessionId != snapshot.value.sessionId || checkpoint.value.runId != snapshot.value.runId)) {
                throw corruption(StoredRecordKind.CHECKPOINT, sessionId.value, StoredRecordCorruptionReason.INDEX_MISMATCH)
            }
            val record = AgentPersistenceRecord(snapshot.value, checkpoint?.value)
            if (snapshot.rewritePayload == null && checkpoint?.rewritePayload == null) return record
            // Host projection runs before acquiring the writer. CAS retries if another handle wrote.
            val projection = catalog.projector.project(snapshot.value)
            val references = extractSessionAttachmentUris(snapshot.value, checkpoint?.value)
            val rewritten = catalog.contextMutation {
                database.useWriterConnection { connection ->
                    connection.immediateTransaction {
                        catalog.readState()
                        if (sessionDao.findById(sessionId.value) != sessionEntity || checkpointDao.findById(sessionId.value) != checkpointEntity) {
                            false
                        } else {
                            catalog.storeSnapshot(snapshot.value, snapshot.rewritePayload ?: sessionEntity.payload,
                                projection, references, sessionEntity to projection)
                            if (checkpointEntity != null && checkpoint != null) rewriteCheckpointIfNeeded(checkpointEntity, checkpoint)
                            recovery.refreshSnapshot(snapshot.value)
                            true
                        }
                    }
                }
            }
            if (rewritten) return record
        }
    }

    override suspend fun listSessions(): List<AgentSessionSnapshot> {
        val corruptions = mutableListOf<StoredRecordCorruption>()
        return try {
            catalog.operation {
                val entities = database.useReaderConnection { connection ->
                    connection.deferredTransaction {
                        catalog.readState()
                        sessionDao.listAll()
                    }
                }
                entities.mapNotNull { entity ->
                    try {
                        decodeSession(entity).value
                    } catch (failure: StoredRecordCorruptionException) {
                        corruptions += failure.corruption
                        null
                    }
                }
            }
        } finally {
            corruptions.forEach(::reportCorruption)
        }
    }

    override suspend fun deleteSession(sessionId: AgentSessionId) = catalog.operation {
        completeWrite {
            database.useWriterConnection { connection ->
                connection.immediateTransaction {
                    val existed = sessionDao.exists(sessionId.value)
                    checkpointDao.deleteById(sessionId.value)
                    sessionDao.deleteById(sessionId.value)
                    catalog.deleteRows(sessionId, existed)
                }
            }
            catalog.deleted(sessionId)
        }
    }

    override suspend fun clear() = catalog.operation {
        completeWrite {
            database.useWriterConnection { connection ->
                connection.immediateTransaction {
                    checkpointDao.deleteAll()
                    sessionDao.deleteAll()
                    catalog.clearRows()
                }
            }
            catalog.cleared()
        }
    }

    /** A committed write must consume its transient context before cancellation reaches the caller. */
    private suspend fun completeWrite(block: suspend () -> Unit) {
        catalog.contextMutation {
            currentCoroutineContext().ensureActive()
            withContext(NonCancellable) { block() }
            currentCoroutineContext().ensureActive()
        }
    }

    private fun decodeSession(
        entity: AgentSessionEntity,
    ): DecodedStoredRecord<AgentSessionSnapshot> {
        val decoded = try {
            snapshotDecoder(entity.payload)
        } catch (failure: StoredEnvelopeDecodeException) {
            throw decodeFailure(StoredRecordKind.SESSION, entity.sessionId, failure)
        }
        if (
            decoded.value.sessionId.value != entity.sessionId ||
            decoded.value.updatedAtEpochMs != entity.updatedAtEpochMs
        ) {
            throw corruption(
                StoredRecordKind.SESSION,
                entity.sessionId,
                StoredRecordCorruptionReason.INDEX_MISMATCH,
            )
        }
        return decoded
    }

    private fun decodeCheckpoint(
        entity: AgentCheckpointEntity,
    ): DecodedStoredRecord<AgentCheckpoint> {
        val decoded = try {
            checkpointDecoder(entity.payload)
        } catch (failure: StoredEnvelopeDecodeException) {
            throw decodeFailure(StoredRecordKind.CHECKPOINT, entity.sessionId, failure)
        }
        if (
            decoded.value.sessionId.value != entity.sessionId ||
            decoded.value.turn != entity.turn
        ) {
            throw corruption(
                StoredRecordKind.CHECKPOINT,
                entity.sessionId,
                StoredRecordCorruptionReason.INDEX_MISMATCH,
            )
        }
        return decoded
    }

    private suspend fun rewriteCheckpointIfNeeded(
        entity: AgentCheckpointEntity,
        decoded: DecodedStoredRecord<AgentCheckpoint>,
    ) {
        decoded.rewritePayload?.let { payload ->
            checkpointDao.upsert(entity.copy(payload = payload))
        }
    }

    private fun decodeFailure(
        kind: StoredRecordKind,
        sessionId: String,
        failure: StoredEnvelopeDecodeException,
    ): IllegalStateException = if (failure.failure == StoredEnvelopeDecodeFailure.CORRUPT) {
        corruption(kind, sessionId, StoredRecordCorruptionReason.INVALID_PAYLOAD)
    } else {
        StoredRecordSchemaException(
            StoredRecordSchemaIssue(
                kind = kind,
                sessionId = sessionId.takeIf(String::isSafeRecordIdentifier),
                failure = failure.failure,
                storedSchemaVersion = requireNotNull(failure.storedSchemaVersion),
                currentSchemaVersion = failure.currentSchemaVersion,
            ),
        )
    }

    private fun corruption(
        kind: StoredRecordKind,
        sessionId: String,
        reason: StoredRecordCorruptionReason,
    ): StoredRecordCorruptionException = StoredRecordCorruptionException(
        StoredRecordCorruption(kind, sessionId, reason),
    )

    /** Never invoke a host callback while a Room connection or transaction is held. */
    private fun reportCorruption(corruption: StoredRecordCorruption) {
        try {
            reporter.report(corruption)
        } catch (_: Throwable) {
            // Diagnostics must not replace the stable storage failure.
        }
    }
}

internal fun String.requireSafeStorageComponent(label: String): String = apply {
    require(length in 1..128 && first().isAsciiLetterOrDigit() && all { it.isAsciiStorageCharacter() }) {
        "$label must be a single safe ASCII path component"
    }
}

private fun Char.isAsciiLetterOrDigit(): Boolean =
    this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9'

private fun Char.isAsciiStorageCharacter(): Boolean =
    isAsciiLetterOrDigit() || this == '.' || this == '_' || this == '-'

private fun String.isSafeRecordIdentifier(): Boolean =
    length in 1..128 && all(Char::isAsciiStorageCharacter)
