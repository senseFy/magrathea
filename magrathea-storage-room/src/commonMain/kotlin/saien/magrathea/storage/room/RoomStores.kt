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
) : AgentPersistence {
    private val snapshotCodec = AgentSessionSnapshotCodec(json)
    private val checkpointCodec = AgentCheckpointCodec(json)
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
        val entities: Pair<AgentSessionEntity, AgentCheckpointEntity?> =
            database.useReaderConnection { connection ->
                connection.deferredTransaction<Pair<AgentSessionEntity, AgentCheckpointEntity?>?> {
                    val session = sessionDao.findById(sessionId.value)
                    if (session == null) {
                        null
                    } else {
                        session to checkpointDao.findById(sessionId.value)
                    }
                }
            }
                ?: return null
        val snapshot = decodeSession(entities.first)
        val checkpoint = entities.second?.let(::decodeCheckpoint)
        if (
            checkpoint != null &&
            (checkpoint.sessionId != snapshot.sessionId || checkpoint.runId != snapshot.runId)
        ) {
            throw corruption(
                StoredRecordKind.CHECKPOINT,
                sessionId.value,
                StoredRecordCorruptionReason.INDEX_MISMATCH,
            )
        }
        return AgentPersistenceRecord(snapshot, checkpoint)
    }

    override suspend fun listSessions(): List<AgentSessionSnapshot> {
        return sessionDao.listAll().mapNotNull { entity ->
            try {
                decodeSession(entity)
            } catch (_: StoredRecordCorruptionException) {
                null
            }
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

    private fun decodeSession(entity: AgentSessionEntity): AgentSessionSnapshot {
        val snapshot = try {
            snapshotCodec.decode(entity.payload)
        } catch (_: Throwable) {
            throw corruption(
                StoredRecordKind.SESSION,
                entity.sessionId,
                StoredRecordCorruptionReason.INVALID_PAYLOAD,
            )
        }
        if (
            snapshot.sessionId.value != entity.sessionId ||
            snapshot.updatedAtEpochMs != entity.updatedAtEpochMs
        ) {
            throw corruption(
                StoredRecordKind.SESSION,
                entity.sessionId,
                StoredRecordCorruptionReason.INDEX_MISMATCH,
            )
        }
        return snapshot
    }

    private fun decodeCheckpoint(entity: AgentCheckpointEntity): AgentCheckpoint {
        val checkpoint = try {
            checkpointCodec.decode(entity.payload)
        } catch (_: Throwable) {
            throw corruption(
                StoredRecordKind.CHECKPOINT,
                entity.sessionId,
                StoredRecordCorruptionReason.INVALID_PAYLOAD,
            )
        }
        if (checkpoint.sessionId.value != entity.sessionId || checkpoint.turn != entity.turn) {
            throw corruption(
                StoredRecordKind.CHECKPOINT,
                entity.sessionId,
                StoredRecordCorruptionReason.INDEX_MISMATCH,
            )
        }
        return checkpoint
    }

    private fun corruption(
        kind: StoredRecordKind,
        sessionId: String,
        reason: StoredRecordCorruptionReason,
    ): StoredRecordCorruptionException {
        val corruption = StoredRecordCorruption(kind, sessionId, reason)
        try {
            reporter.report(corruption)
        } catch (_: Throwable) {
            // Diagnostics must not replace the stable storage failure.
        }
        return StoredRecordCorruptionException(corruption)
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
