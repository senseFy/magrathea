package saien.magrathea.storage.web

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import saien.magrathea.core.AgentCheckpoint
import saien.magrathea.core.AgentCheckpointCodec
import saien.magrathea.core.AgentPersistence
import saien.magrathea.core.AgentPersistenceRecord
import saien.magrathea.core.AgentSessionId
import saien.magrathea.core.AgentSessionSnapshot
import saien.magrathea.core.AgentSessionSnapshotCodec

const val MAGRATHEA_WEB_DATABASE_VERSION: Int = 1
const val DEFAULT_MAGRATHEA_WEB_DATABASE_NAME: String = "magrathea-core"

data class MagratheaWebStoreConfiguration(
    val databaseName: String = DEFAULT_MAGRATHEA_WEB_DATABASE_NAME,
) {
    init {
        databaseName.requireSafeDatabaseName()
    }
}

enum class WebStorageFailure {
    UNAVAILABLE,
    BLOCKED,
    QUOTA_EXCEEDED,
    UNSUPPORTED_DATABASE_VERSION,
    INVALID_RECORD,
    CORRUPT_RECORD,
    CLOSED,
    OPERATION_FAILED,
}

enum class WebStoredRecordKind {
    SESSION,
    CHECKPOINT,
}

enum class WebStoredRecordCorruptionReason {
    INVALID_PAYLOAD,
    INDEX_MISMATCH,
}

data class WebStoredRecordCorruption(
    val kind: WebStoredRecordKind,
    val recordId: String?,
    val reason: WebStoredRecordCorruptionReason,
)

fun interface WebStoredRecordCorruptionReporter {
    /** Implementations must not throw. Reports never include a stored payload or decoder message. */
    fun report(corruption: WebStoredRecordCorruption)
}

class WebStorageException internal constructor(
    val failure: WebStorageFailure,
    val corruption: WebStoredRecordCorruption? = null,
) : IllegalStateException(
    when (failure) {
        WebStorageFailure.CORRUPT_RECORD -> "Web storage record is corrupt"
        else -> "Web storage operation failed (${failure.name.lowercase()})"
    },
)

class MagratheaWebStore internal constructor(
    database: WebRecordDatabase,
    reporter: WebStoredRecordCorruptionReporter,
    json: Json,
) {
    private val lifecycle = WebStoreLifecycle()

    val persistence: AgentPersistence =
        IndexedDbAgentPersistence(database, lifecycle, reporter, json)

    suspend fun close() {
        lifecycle.close()
    }
}

fun createMagratheaWebStore(
    configuration: MagratheaWebStoreConfiguration = MagratheaWebStoreConfiguration(),
    corruptionReporter: WebStoredRecordCorruptionReporter = WebStoredRecordCorruptionReporter { },
    json: Json = Json,
): MagratheaWebStore = MagratheaWebStore(
    database = IndexedDbRecordDatabase(configuration.databaseName),
    reporter = corruptionReporter,
    json = json,
)

private class WebStoreLifecycle {
    private val mutex = Mutex()
    private var closed = false

    suspend inline fun <T> withOpenOperation(crossinline operation: suspend () -> T): T {
        return mutex.withLock {
            if (closed) throw WebStorageException(WebStorageFailure.CLOSED)
            operation()
        }
    }

    suspend fun close() {
        mutex.withLock { closed = true }
    }
}

private class IndexedDbAgentPersistence(
    private val database: WebRecordDatabase,
    private val lifecycle: WebStoreLifecycle,
    private val reporter: WebStoredRecordCorruptionReporter,
    json: Json,
) : AgentPersistence {
    private val sessionCodec = AgentSessionSnapshotCodec(json)
    private val checkpointCodec = AgentCheckpointCodec(json)

    override suspend fun commit(
        snapshot: AgentSessionSnapshot,
        checkpoint: AgentCheckpoint?,
    ) = lifecycle.withOpenOperation {
        val record = try {
            AgentPersistenceRecord(snapshot, checkpoint)
        } catch (_: Throwable) {
            throw WebStorageException(WebStorageFailure.INVALID_RECORD)
        }
        val sessionPayload = try {
            sessionCodec.encode(record.snapshot)
        } catch (_: Throwable) {
            throw WebStorageException(WebStorageFailure.INVALID_RECORD)
        }
        val checkpointPayload = try {
            record.checkpoint?.let(checkpointCodec::encode)
        } catch (_: Throwable) {
            throw WebStorageException(WebStorageFailure.INVALID_RECORD)
        }
        database.commit(snapshot.sessionId.value, sessionPayload, checkpointPayload)
    }

    override suspend fun load(
        sessionId: AgentSessionId,
    ): AgentPersistenceRecord? = lifecycle.withOpenOperation {
        val record = database.get(sessionId.value)
            ?: return@withOpenOperation null
        val snapshot = decodeSession(record.session)
        val checkpoint = record.checkpoint?.let(::decodeCheckpoint)
        if (
            checkpoint != null &&
            (checkpoint.sessionId != snapshot.sessionId || checkpoint.runId != snapshot.runId)
        ) {
            throw corruption(
                kind = WebStoredRecordKind.CHECKPOINT,
                recordId = record.checkpoint.key,
                reason = WebStoredRecordCorruptionReason.INDEX_MISMATCH,
            )
        }
        AgentPersistenceRecord(snapshot, checkpoint)
    }

    override suspend fun listSessions(): List<AgentSessionSnapshot> = lifecycle.withOpenOperation {
        database.getAllSessions()
            .mapNotNull { record ->
                try {
                    decodeSession(record)
                } catch (error: WebStorageException) {
                    if (error.failure != WebStorageFailure.CORRUPT_RECORD) throw error
                    null
                }
            }
            .sortedWith(
                compareByDescending<AgentSessionSnapshot> { it.updatedAtEpochMs }
                    .thenBy { it.sessionId.value },
            )
    }

    override suspend fun deleteSession(sessionId: AgentSessionId) = lifecycle.withOpenOperation {
        database.delete(sessionId.value)
    }

    override suspend fun clear() = lifecycle.withOpenOperation {
        database.clear()
    }

    private fun decodeSession(record: WebRawRecord): AgentSessionSnapshot {
        val snapshot = try {
            sessionCodec.decode(record.payload ?: throw InvalidStoredPayload)
        } catch (_: Throwable) {
            throw corruption(
                kind = WebStoredRecordKind.SESSION,
                recordId = record.key,
                reason = WebStoredRecordCorruptionReason.INVALID_PAYLOAD,
            )
        }
        if (record.key != snapshot.sessionId.value) {
            throw corruption(
                kind = WebStoredRecordKind.SESSION,
                recordId = record.key,
                reason = WebStoredRecordCorruptionReason.INDEX_MISMATCH,
            )
        }
        return snapshot
    }

    private fun decodeCheckpoint(record: WebRawRecord): AgentCheckpoint {
        val checkpoint = try {
            checkpointCodec.decode(record.payload ?: throw InvalidStoredPayload)
        } catch (_: Throwable) {
            throw corruption(
                kind = WebStoredRecordKind.CHECKPOINT,
                recordId = record.key,
                reason = WebStoredRecordCorruptionReason.INVALID_PAYLOAD,
            )
        }
        if (record.key != checkpoint.sessionId.value) {
            throw corruption(
                kind = WebStoredRecordKind.CHECKPOINT,
                recordId = record.key,
                reason = WebStoredRecordCorruptionReason.INDEX_MISMATCH,
            )
        }
        return checkpoint
    }

    private fun corruption(
        kind: WebStoredRecordKind,
        recordId: String?,
        reason: WebStoredRecordCorruptionReason,
    ): WebStorageException = reportCorruption(reporter, kind, recordId, reason)
}

private fun reportCorruption(
    reporter: WebStoredRecordCorruptionReporter,
    kind: WebStoredRecordKind,
    recordId: String?,
    reason: WebStoredRecordCorruptionReason,
): WebStorageException {
    val corruption = WebStoredRecordCorruption(
        kind = kind,
        recordId = recordId?.takeIf(String::isSafeRecordIdentifier),
        reason = reason,
    )
    try {
        reporter.report(corruption)
    } catch (_: Throwable) {
        // A diagnostic sink must never replace the stable storage failure or break list isolation.
    }
    return WebStorageException(WebStorageFailure.CORRUPT_RECORD, corruption)
}

private object InvalidStoredPayload : Throwable()

private fun String.requireSafeDatabaseName(): String = apply {
    require(length in 1..128 && first().isAsciiLetterOrDigit() && all(Char::isSafeStorageCharacter)) {
        "databaseName must be a single safe ASCII component"
    }
}

private fun String.isSafeRecordIdentifier(): Boolean =
    length in 1..128 && all(Char::isSafeStorageCharacter)

private fun Char.isAsciiLetterOrDigit(): Boolean =
    this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9'

private fun Char.isSafeStorageCharacter(): Boolean =
    isAsciiLetterOrDigit() || this == '.' || this == '_' || this == '-'
