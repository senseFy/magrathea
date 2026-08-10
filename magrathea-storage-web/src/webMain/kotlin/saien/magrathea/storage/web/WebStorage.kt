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
import saien.magrathea.core.StoredEnvelopeDecodeException
import saien.magrathea.core.StoredEnvelopeDecodeFailure
import saien.magrathea.core.StoredEnvelopeDecodeResult

internal data class WebDecodedStoredRecord<T>(
    val value: T,
    val rewritePayload: String?,
)

internal fun <T> StoredEnvelopeDecodeResult<T>.toWebStoredRecord(): WebDecodedStoredRecord<T> =
    WebDecodedStoredRecord(value, rewritePayload)

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
    UNSUPPORTED_OLDER_SCHEMA,
    UNSUPPORTED_NEWER_SCHEMA,
    MIGRATION_FAILED,
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

/** Payload-free identity and version details for a logical schema failure. */
data class WebStoredRecordSchemaIssue(
    val kind: WebStoredRecordKind,
    val recordId: String?,
    val failure: StoredEnvelopeDecodeFailure,
    val storedSchemaVersion: Int,
    val currentSchemaVersion: Int,
) {
    init {
        require(failure != StoredEnvelopeDecodeFailure.CORRUPT) {
            "Corrupt records must use WebStoredRecordCorruption"
        }
        require(recordId == null || recordId.isSafeRecordIdentifier()) {
            "Web schema issue record identity must be sanitized"
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

fun interface WebStoredRecordCorruptionReporter {
    /** Implementations must not throw. Reports never include a stored payload or decoder message. */
    fun report(corruption: WebStoredRecordCorruption)
}

class WebStorageException internal constructor(
    val failure: WebStorageFailure,
    val corruption: WebStoredRecordCorruption? = null,
    val storedSchemaVersion: Int? = null,
    val currentSchemaVersion: Int? = null,
    val schemaIssue: WebStoredRecordSchemaIssue? = null,
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
    snapshotDecoder: ((String) -> WebDecodedStoredRecord<AgentSessionSnapshot>)? = null,
    checkpointDecoder: ((String) -> WebDecodedStoredRecord<AgentCheckpoint>)? = null,
) {
    private val lifecycle = WebStoreLifecycle()

    val persistence: AgentPersistence =
        IndexedDbAgentPersistence(
            database,
            lifecycle,
            reporter,
            json,
            snapshotDecoder,
            checkpointDecoder,
        )

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
    snapshotDecoder: ((String) -> WebDecodedStoredRecord<AgentSessionSnapshot>)?,
    checkpointDecoder: ((String) -> WebDecodedStoredRecord<AgentCheckpoint>)?,
) : AgentPersistence {
    private val sessionCodec = AgentSessionSnapshotCodec(json)
    private val checkpointCodec = AgentCheckpointCodec(json)
    private val decodeSessionPayload = snapshotDecoder
        ?: { payload: String -> sessionCodec.decodeResult(payload).toWebStoredRecord() }
    private val decodeCheckpointPayload = checkpointDecoder
        ?: { payload: String -> checkpointCodec.decodeResult(payload).toWebStoredRecord() }

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
    ): AgentPersistenceRecord? {
        return try {
            lifecycle.withOpenOperation {
                repeat(MAX_REWRITE_ATTEMPTS) {
                    val rawRecord = database.get(sessionId.value)
                        ?: return@withOpenOperation null
                    val snapshot = decodeSession(rawRecord.session)
                    val checkpoint = rawRecord.checkpoint?.let(::decodeCheckpoint)
                    if (
                        checkpoint != null &&
                        (
                            checkpoint.value.sessionId != snapshot.value.sessionId ||
                                checkpoint.value.runId != snapshot.value.runId
                        )
                    ) {
                        throw corruption(
                            kind = WebStoredRecordKind.CHECKPOINT,
                            recordId = rawRecord.checkpoint.key,
                            reason = WebStoredRecordCorruptionReason.INDEX_MISMATCH,
                        )
                    }
                    val record = AgentPersistenceRecord(snapshot.value, checkpoint?.value)
                    if (snapshot.rewritePayload == null && checkpoint?.rewritePayload == null) {
                        return@withOpenOperation record
                    }
                    val rewritten = database.rewriteIfUnchanged(
                        listOf(
                            WebPayloadRewriteExpectation(
                                store = WEB_SESSION_STORE,
                                key = sessionId.value,
                                expectedPayload = rawRecord.session.payload,
                                rewritePayload = snapshot.rewritePayload,
                            ),
                            WebPayloadRewriteExpectation(
                                store = WEB_CHECKPOINT_STORE,
                                key = sessionId.value,
                                expectedPayload = rawRecord.checkpoint?.payload,
                                rewritePayload = checkpoint?.rewritePayload,
                            ),
                        ),
                    )
                    if (rewritten) return@withOpenOperation record
                }
                throw WebStorageException(WebStorageFailure.OPERATION_FAILED)
            }
        } catch (failure: WebStorageException) {
            failure.corruption?.let { reportCorruption(reporter, it) }
            throw failure
        }
    }

    override suspend fun listSessions(): List<AgentSessionSnapshot> {
        val corruptions = mutableListOf<WebStoredRecordCorruption>()
        return try {
            lifecycle.withOpenOperation {
                database.getAllSessions()
                    .mapNotNull { record ->
                        try {
                            decodeSession(record).value
                        } catch (error: WebStorageException) {
                            if (error.failure != WebStorageFailure.CORRUPT_RECORD) throw error
                            error.corruption?.let(corruptions::add)
                            null
                        }
                    }
                    .sortedWith(
                        compareByDescending<AgentSessionSnapshot> { it.updatedAtEpochMs }
                            .thenBy { it.sessionId.value },
                    )
            }
        } finally {
            corruptions.forEach { reportCorruption(reporter, it) }
        }
    }

    override suspend fun deleteSession(sessionId: AgentSessionId) = lifecycle.withOpenOperation {
        database.delete(sessionId.value)
    }

    override suspend fun clear() = lifecycle.withOpenOperation {
        database.clear()
    }

    private fun decodeSession(
        record: WebRawRecord,
    ): WebDecodedStoredRecord<AgentSessionSnapshot> {
        val decoded = try {
            decodeSessionPayload(record.payload ?: throw InvalidStoredPayload)
        } catch (_: InvalidStoredPayload) {
            throw corruption(
                WebStoredRecordKind.SESSION,
                record.key,
                WebStoredRecordCorruptionReason.INVALID_PAYLOAD,
            )
        } catch (failure: StoredEnvelopeDecodeException) {
            throw decodeFailure(WebStoredRecordKind.SESSION, record.key, failure)
        }
        if (record.key != decoded.value.sessionId.value) {
            throw corruption(
                kind = WebStoredRecordKind.SESSION,
                recordId = record.key,
                reason = WebStoredRecordCorruptionReason.INDEX_MISMATCH,
            )
        }
        return decoded
    }

    private fun decodeCheckpoint(
        record: WebRawRecord,
    ): WebDecodedStoredRecord<AgentCheckpoint> {
        val decoded = try {
            decodeCheckpointPayload(record.payload ?: throw InvalidStoredPayload)
        } catch (_: InvalidStoredPayload) {
            throw corruption(
                WebStoredRecordKind.CHECKPOINT,
                record.key,
                WebStoredRecordCorruptionReason.INVALID_PAYLOAD,
            )
        } catch (failure: StoredEnvelopeDecodeException) {
            throw decodeFailure(WebStoredRecordKind.CHECKPOINT, record.key, failure)
        }
        if (record.key != decoded.value.sessionId.value) {
            throw corruption(
                kind = WebStoredRecordKind.CHECKPOINT,
                recordId = record.key,
                reason = WebStoredRecordCorruptionReason.INDEX_MISMATCH,
            )
        }
        return decoded
    }

    private fun decodeFailure(
        kind: WebStoredRecordKind,
        recordId: String?,
        failure: StoredEnvelopeDecodeException,
    ): WebStorageException {
        if (failure.failure == StoredEnvelopeDecodeFailure.CORRUPT) {
            return corruption(
                kind,
                recordId,
                WebStoredRecordCorruptionReason.INVALID_PAYLOAD,
            )
        }
        val webFailure = when (failure.failure) {
            StoredEnvelopeDecodeFailure.UNSUPPORTED_OLDER_SCHEMA ->
                WebStorageFailure.UNSUPPORTED_OLDER_SCHEMA
            StoredEnvelopeDecodeFailure.UNSUPPORTED_NEWER_SCHEMA ->
                WebStorageFailure.UNSUPPORTED_NEWER_SCHEMA
            StoredEnvelopeDecodeFailure.MIGRATION_FAILED -> WebStorageFailure.MIGRATION_FAILED
            StoredEnvelopeDecodeFailure.CORRUPT -> error("Handled above")
        }
        return WebStorageException(
            failure = webFailure,
            storedSchemaVersion = failure.storedSchemaVersion,
            currentSchemaVersion = failure.currentSchemaVersion,
            schemaIssue = WebStoredRecordSchemaIssue(
                kind = kind,
                recordId = recordId?.takeIf(String::isSafeRecordIdentifier),
                failure = failure.failure,
                storedSchemaVersion = requireNotNull(failure.storedSchemaVersion),
                currentSchemaVersion = failure.currentSchemaVersion,
            ),
        )
    }

    private fun corruption(
        kind: WebStoredRecordKind,
        recordId: String?,
        reason: WebStoredRecordCorruptionReason,
    ): WebStorageException = WebStorageException(
        WebStorageFailure.CORRUPT_RECORD,
        WebStoredRecordCorruption(
            kind = kind,
            recordId = recordId?.takeIf(String::isSafeRecordIdentifier),
            reason = reason,
        ),
    )

    private companion object {
        const val MAX_REWRITE_ATTEMPTS = 3
    }
}

private fun reportCorruption(
    reporter: WebStoredRecordCorruptionReporter,
    corruption: WebStoredRecordCorruption,
) {
    try {
        reporter.report(corruption)
    } catch (_: Throwable) {
        // A diagnostic sink must never replace the stable storage failure or break list isolation.
    }
}

private object InvalidStoredPayload : RuntimeException()

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
