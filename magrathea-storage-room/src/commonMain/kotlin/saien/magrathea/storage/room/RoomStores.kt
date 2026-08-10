package saien.magrathea.storage.room

import androidx.room.RoomDatabase
import androidx.room.deferredTransaction
import androidx.room.immediateTransaction
import androidx.room.useReaderConnection
import androidx.room.useWriterConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import saien.magrathea.core.AgentPersistence
import saien.magrathea.core.AgentPersistenceRecord
import saien.magrathea.core.AgentCheckpoint
import saien.magrathea.core.AgentCheckpointCodec
import saien.magrathea.core.AgentSessionId
import saien.magrathea.core.AgentSessionSnapshot
import saien.magrathea.core.AgentSessionSnapshotCodec
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
) {
    val persistence: AgentPersistence = RoomAgentPersistence(database, reporter, json)

    private val closeMutex = Mutex()
    private var closed = false

    suspend fun close() {
        closeMutex.withLock {
            if (!closed) {
                closed = true
                database.close()
            }
        }
    }
}

internal fun buildMagratheaRoomStore(
    builder: RoomDatabase.Builder<MagratheaDatabase>,
    reporter: StoredRecordCorruptionReporter,
    json: Json = Json,
): MagratheaRoomStoreHandle {
    return MagratheaRoomStoreHandle(buildMagratheaRoomDatabase(builder), reporter, json)
}

internal fun buildMagratheaRoomDatabase(
    builder: RoomDatabase.Builder<MagratheaDatabase>,
): MagratheaDatabase {
    return builder
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.Default)
        .build()
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
) : AgentPersistence {
    private val sessionDao = database.sessionDao()
    private val checkpointDao = database.checkpointDao()

    override suspend fun commit(
        snapshot: AgentSessionSnapshot,
        checkpoint: AgentCheckpoint?,
    ) {
        val record = AgentPersistenceRecord(snapshot, checkpoint)
        val sessionEntity = AgentSessionEntity(
            sessionId = snapshot.sessionId.value,
            payload = snapshotCodec.encode(snapshot),
            updatedAtEpochMs = snapshot.updatedAtEpochMs,
        )
        val checkpointEntity = checkpoint?.let {
            AgentCheckpointEntity(
                sessionId = it.sessionId.value,
                payload = checkpointCodec.encode(it),
                turn = it.turn,
            )
        }
        database.useWriterConnection { connection ->
            connection.immediateTransaction {
                sessionDao.upsert(sessionEntity)
                if (record.checkpoint == null) {
                    checkpointDao.deleteById(snapshot.sessionId.value)
                } else {
                    checkpointDao.upsert(requireNotNull(checkpointEntity))
                }
            }
        }
    }

    override suspend fun load(sessionId: AgentSessionId): AgentPersistenceRecord? {
        return try {
            database.useWriterConnection { connection ->
                connection.immediateTransaction<AgentPersistenceRecord?> {
                    val sessionEntity = sessionDao.findById(sessionId.value)
                        ?: return@immediateTransaction null
                    val checkpointEntity = checkpointDao.findById(sessionId.value)
                    val snapshot = decodeSession(sessionEntity)
                    val checkpoint = checkpointEntity?.let(::decodeCheckpoint)
                    if (
                        checkpoint != null &&
                        (
                            checkpoint.value.sessionId != snapshot.value.sessionId ||
                                checkpoint.value.runId != snapshot.value.runId
                        )
                    ) {
                        throw corruption(
                            StoredRecordKind.CHECKPOINT,
                            sessionId.value,
                            StoredRecordCorruptionReason.INDEX_MISMATCH,
                        )
                    }
                    val record = AgentPersistenceRecord(snapshot.value, checkpoint?.value)
                    rewriteSessionIfNeeded(sessionEntity, snapshot)
                    if (checkpointEntity != null && checkpoint != null) {
                        rewriteCheckpointIfNeeded(checkpointEntity, checkpoint)
                    }
                    record
                }
            }
        } catch (failure: StoredRecordCorruptionException) {
            reportCorruption(failure.corruption)
            throw failure
        }
    }

    override suspend fun listSessions(): List<AgentSessionSnapshot> {
        val corruptions = mutableListOf<StoredRecordCorruption>()
        return try {
            database.useReaderConnection { connection ->
                connection.deferredTransaction<List<AgentSessionSnapshot>> {
                    sessionDao.listAll().mapNotNull { entity ->
                        try {
                            decodeSession(entity).value
                        } catch (failure: StoredRecordCorruptionException) {
                            corruptions += failure.corruption
                            null
                        }
                    }
                }
            }
        } finally {
            corruptions.forEach(::reportCorruption)
        }
    }

    override suspend fun deleteSession(sessionId: AgentSessionId) {
        database.useWriterConnection { connection ->
            connection.immediateTransaction {
                checkpointDao.deleteById(sessionId.value)
                sessionDao.deleteById(sessionId.value)
            }
        }
    }

    override suspend fun clear() {
        database.useWriterConnection { connection ->
            connection.immediateTransaction {
                checkpointDao.deleteAll()
                sessionDao.deleteAll()
            }
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

    private suspend fun rewriteSessionIfNeeded(
        entity: AgentSessionEntity,
        decoded: DecodedStoredRecord<AgentSessionSnapshot>,
    ) {
        decoded.rewritePayload?.let { payload ->
            sessionDao.upsert(entity.copy(payload = payload))
        }
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
