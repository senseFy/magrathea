package saien.magrathea.storage.room

import androidx.room.RoomDatabase
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import saien.magrathea.core.AgentCheckpoint
import saien.magrathea.core.AgentCheckpointCodec
import saien.magrathea.core.AgentSessionId
import saien.magrathea.core.AgentSessionSnapshot
import saien.magrathea.core.AgentSessionSnapshotCodec
import saien.magrathea.core.CheckpointStore
import saien.magrathea.core.SessionStore

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

/** Owns one Room database together with its Session and Checkpoint store views. */
class MagratheaRoomStoreHandle internal constructor(
    private val database: MagratheaDatabase,
    reporter: StoredRecordCorruptionReporter,
    json: Json = Json,
) {
    val sessionStore: SessionStore = RoomSessionStore(database.sessionDao(), reporter, json)
    val checkpointStore: CheckpointStore = RoomCheckpointStore(database.checkpointDao(), reporter, json)

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

internal class RoomSessionStore(
    private val dao: AgentSessionDao,
    private val reporter: StoredRecordCorruptionReporter,
    json: Json = Json,
) : SessionStore {
    private val snapshotCodec = AgentSessionSnapshotCodec(json)

    override suspend fun saveSession(snapshot: AgentSessionSnapshot) {
        dao.upsert(
            AgentSessionEntity(
                sessionId = snapshot.sessionId.value,
                payload = snapshotCodec.encode(snapshot),
                updatedAtEpochMs = snapshot.updatedAtEpochMs,
            ),
        )
    }

    override suspend fun loadSession(sessionId: AgentSessionId): AgentSessionSnapshot? {
        val entity = dao.findById(sessionId.value) ?: return null
        return decode(entity)
    }

    override suspend fun listSessions(): List<AgentSessionSnapshot> {
        return dao.listAll().mapNotNull { entity ->
            try {
                decode(entity)
            } catch (_: StoredRecordCorruptionException) {
                null
            }
        }
    }

    override suspend fun deleteSession(sessionId: AgentSessionId) {
        dao.deleteById(sessionId.value)
    }

    override suspend fun clear() {
        dao.deleteAll()
    }

    private fun decode(entity: AgentSessionEntity): AgentSessionSnapshot {
        val snapshot = try {
            snapshotCodec.decode(entity.payload)
        } catch (_: Throwable) {
            throw corruption(entity.sessionId, StoredRecordCorruptionReason.INVALID_PAYLOAD)
        }
        if (
            snapshot.sessionId.value != entity.sessionId ||
            snapshot.updatedAtEpochMs != entity.updatedAtEpochMs
        ) {
            throw corruption(entity.sessionId, StoredRecordCorruptionReason.INDEX_MISMATCH)
        }
        return snapshot
    }

    private fun corruption(
        sessionId: String,
        reason: StoredRecordCorruptionReason,
    ): StoredRecordCorruptionException {
        val corruption = StoredRecordCorruption(StoredRecordKind.SESSION, sessionId, reason)
        try {
            reporter.report(corruption)
        } catch (_: Throwable) {
            // A diagnostic sink must not turn per-row isolation into a full history read failure.
        }
        return StoredRecordCorruptionException(corruption)
    }
}

internal class RoomCheckpointStore(
    private val dao: AgentCheckpointDao,
    private val reporter: StoredRecordCorruptionReporter,
    json: Json = Json,
) : CheckpointStore {
    private val checkpointCodec = AgentCheckpointCodec(json)

    override suspend fun saveCheckpoint(checkpoint: AgentCheckpoint) {
        dao.upsert(
            AgentCheckpointEntity(
                sessionId = checkpoint.sessionId.value,
                payload = checkpointCodec.encode(checkpoint),
                turn = checkpoint.turn,
            ),
        )
    }

    override suspend fun loadLatestCheckpoint(sessionId: AgentSessionId): AgentCheckpoint? {
        val entity = dao.findById(sessionId.value) ?: return null
        val checkpoint = try {
            checkpointCodec.decode(entity.payload)
        } catch (_: Throwable) {
            throw corruption(entity.sessionId, StoredRecordCorruptionReason.INVALID_PAYLOAD)
        }
        if (checkpoint.sessionId.value != entity.sessionId || checkpoint.turn != entity.turn) {
            throw corruption(entity.sessionId, StoredRecordCorruptionReason.INDEX_MISMATCH)
        }
        return checkpoint
    }

    override suspend fun deleteSession(sessionId: AgentSessionId) {
        dao.deleteById(sessionId.value)
    }

    override suspend fun clear() {
        dao.deleteAll()
    }

    private fun corruption(
        sessionId: String,
        reason: StoredRecordCorruptionReason,
    ): StoredRecordCorruptionException {
        val corruption = StoredRecordCorruption(StoredRecordKind.CHECKPOINT, sessionId, reason)
        try {
            reporter.report(corruption)
        } catch (_: Throwable) {
            // A diagnostic sink must not replace the stable storage exception.
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
