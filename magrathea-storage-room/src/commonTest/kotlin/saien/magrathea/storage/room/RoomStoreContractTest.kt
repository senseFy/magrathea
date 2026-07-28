package saien.magrathea.storage.room

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import saien.magrathea.core.AgentCheckpoint
import saien.magrathea.core.AgentCheckpointCodec
import saien.magrathea.core.AgentEngineConfig
import saien.magrathea.core.AgentMessage
import saien.magrathea.core.AgentRequest
import saien.magrathea.core.AgentSessionId
import saien.magrathea.core.AgentSessionSnapshot
import saien.magrathea.core.AgentSessionSnapshotCodec
import saien.magrathea.core.AgentStateSnapshot
import saien.magrathea.core.CredentialRef
import saien.magrathea.core.CURRENT_STORAGE_SCHEMA_VERSION
import saien.magrathea.core.MessageRole
import saien.magrathea.core.ModelDescriptor
import saien.magrathea.core.ProviderConfig
import saien.magrathea.core.TextPart

class RoomStoreContractTest {
    @Test
    fun platformStorageComponents_rejectTraversalAndAbsolutePaths() {
        listOf("", ".hidden", "../escape", "nested/path", "/absolute", "秘密").forEach { value ->
            assertFailsWith<IllegalArgumentException> { value.requireSafeStorageComponent("databaseName") }
        }
        assertEquals("chatbot-v1.db", "chatbot-v1.db".requireSafeStorageComponent("databaseName"))
    }

    @Test
    fun sessionStore_persistsStrictCredentialReferenceEnvelope() = runTest {
        val dao = FakeSessionDao()
        val session = roomTestSnapshot(
            sessionIdValue = "credential-contract",
            providerConfig = ProviderConfig(credentialRef = CredentialRef("test-provider")),
        )
        val store = RoomSessionStore(dao, NoopReporter)

        store.saveSession(session)

        val payload = requireNotNull(dao.rows.singleOrNull()).payload
        val envelope = Json.parseToJsonElement(payload).jsonObject
        assertEquals(
            CURRENT_STORAGE_SCHEMA_VERSION,
            envelope.getValue("schemaVersion").jsonPrimitive.content.toInt(),
        )
        assertTrue(envelope.getValue("sdkVersion").jsonPrimitive.content.isNotBlank())
        assertTrue(payload.contains("credentialRef"))
        assertEquals(
            "test-provider",
            requireNotNull(store.loadSession(session.sessionId)).request.engine.provider.credentialRef?.provider,
        )
    }

    @Test
    fun checkpointStore_persistsStrictVersionedEnvelope() = runTest {
        val dao = FakeCheckpointDao()
        val session = roomTestSnapshot("checkpoint-contract")
        val checkpoint = roomTestCheckpoint(session, turn = 2)
        val store = RoomCheckpointStore(dao, NoopReporter)

        store.saveCheckpoint(checkpoint)

        val payload = requireNotNull(dao.row).payload
        val envelope = Json.parseToJsonElement(payload).jsonObject
        assertEquals(
            CURRENT_STORAGE_SCHEMA_VERSION,
            envelope.getValue("schemaVersion").jsonPrimitive.content.toInt(),
        )
        assertTrue(envelope.getValue("sdkVersion").jsonPrimitive.content.isNotBlank())
        assertEquals(checkpoint, store.loadLatestCheckpoint(session.sessionId))
    }

    @Test
    fun listSessions_corruptRowIsReportedAndDoesNotHideHealthyHistory() = runTest {
        val healthy = roomTestSnapshot("healthy", updatedAtEpochMs = 20L)
        val corruptSecret = "never-report-this-secret"
        val reports = mutableListOf<StoredRecordCorruption>()
        val dao = FakeSessionDao(
            mutableListOf(
                healthy.toEntity(),
                AgentSessionEntity("corrupt", "{\"secret\":\"$corruptSecret\"}", 10L),
            ),
        )
        val store = RoomSessionStore(dao, StoredRecordCorruptionReporter(reports::add))

        val sessions = store.listSessions()

        assertEquals(listOf(healthy), sessions)
        assertEquals(
            listOf(
                StoredRecordCorruption(
                    kind = StoredRecordKind.SESSION,
                    sessionId = "corrupt",
                    reason = StoredRecordCorruptionReason.INVALID_PAYLOAD,
                ),
            ),
            reports,
        )
        assertFalse(reports.toString().contains(corruptSecret))
    }

    @Test
    fun listSessions_throwingReporterStillCannotBreakPerRowIsolation() = runTest {
        val healthy = roomTestSnapshot("healthy-after-reporter-failure")
        val dao = FakeSessionDao(
            mutableListOf(
                AgentSessionEntity("corrupt", "not-json", 1L),
                healthy.toEntity(),
            ),
        )
        val store = RoomSessionStore(dao, StoredRecordCorruptionReporter { error("reporter failed") })

        assertEquals(listOf(healthy), store.listSessions())
    }

    @Test
    fun loadSession_corruptPayloadFailsClosedWithSanitizedException() = runTest {
        val secret = "payload-canary-secret"
        val dao = FakeSessionDao(
            mutableListOf(AgentSessionEntity("corrupt-load", "{\"token\":\"$secret\"}", 1L)),
        )
        val store = RoomSessionStore(dao, NoopReporter)

        val error = assertFailsWith<StoredRecordCorruptionException> {
            store.loadSession(AgentSessionId("corrupt-load"))
        }

        assertEquals(StoredRecordCorruptionReason.INVALID_PAYLOAD, error.corruption.reason)
        assertFalse(error.toString().contains(secret))
        assertEquals(null, error.cause)
    }

    @Test
    fun sessionIndexColumnsMustMatchDecodedPayload() = runTest {
        val snapshot = roomTestSnapshot("payload-id", updatedAtEpochMs = 11L)
        val reports = mutableListOf<StoredRecordCorruption>()
        val dao = FakeSessionDao(
            mutableListOf(
                AgentSessionEntity(
                    sessionId = "indexed-id",
                    payload = AgentSessionSnapshotCodec().encode(snapshot),
                    updatedAtEpochMs = 12L,
                ),
            ),
        )
        val store = RoomSessionStore(dao, StoredRecordCorruptionReporter(reports::add))

        val error = assertFailsWith<StoredRecordCorruptionException> {
            store.loadSession(AgentSessionId("indexed-id"))
        }

        assertEquals(StoredRecordCorruptionReason.INDEX_MISMATCH, error.corruption.reason)
        assertEquals(listOf(error.corruption), reports)
    }

    @Test
    fun checkpointIndexColumnsMustMatchDecodedPayload() = runTest {
        val session = roomTestSnapshot("checkpoint-index")
        val checkpoint = roomTestCheckpoint(session, turn = 3)
        val dao = FakeCheckpointDao(
            AgentCheckpointEntity(
                sessionId = checkpoint.sessionId.value,
                payload = AgentCheckpointCodec().encode(checkpoint),
                turn = 4,
            ),
        )
        val store = RoomCheckpointStore(dao, NoopReporter)

        val error = assertFailsWith<StoredRecordCorruptionException> {
            store.loadLatestCheckpoint(checkpoint.sessionId)
        }

        assertEquals(StoredRecordCorruptionReason.INDEX_MISMATCH, error.corruption.reason)
    }

    @Test
    fun unknownEnvelopeFieldIsReportedAsInvalidPayload() = runTest {
        val snapshot = roomTestSnapshot("unknown-field")
        val payload = AgentSessionSnapshotCodec().encode(snapshot).replaceFirst(
            "{",
            "{\"futureField\":true,",
        )
        val dao = FakeSessionDao(mutableListOf(AgentSessionEntity(snapshot.sessionId.value, payload, snapshot.updatedAtEpochMs)))
        val store = RoomSessionStore(dao, NoopReporter)

        val error = assertFailsWith<StoredRecordCorruptionException> {
            store.loadSession(snapshot.sessionId)
        }

        assertEquals(StoredRecordCorruptionReason.INVALID_PAYLOAD, error.corruption.reason)
    }

    @Test
    fun deleteAndClearAreIdempotentAndDoNotDecodeCorruptRows() = runTest {
        val healthy = roomTestSnapshot("delete-healthy")
        val sessionDao = FakeSessionDao(
            mutableListOf(
                healthy.toEntity(),
                AgentSessionEntity("corrupt", "not-json", 1L),
            ),
        )
        val checkpointDao = FakeCheckpointDao(
            AgentCheckpointEntity("corrupt", "not-json", 1),
        )
        val sessions = RoomSessionStore(sessionDao, NoopReporter)
        val checkpoints = RoomCheckpointStore(checkpointDao, NoopReporter)

        sessions.deleteSession(healthy.sessionId)
        sessions.deleteSession(healthy.sessionId)
        assertEquals(listOf("corrupt"), sessionDao.rows.map { it.sessionId })

        sessions.clear()
        checkpoints.clear()
        sessions.clear()
        checkpoints.clear()

        assertTrue(sessionDao.rows.isEmpty())
        assertEquals(null, checkpointDao.row)
    }

    private object NoopReporter : StoredRecordCorruptionReporter {
        override fun report(corruption: StoredRecordCorruption) = Unit
    }
}

internal fun roomTestSnapshot(
    sessionIdValue: String,
    providerConfig: ProviderConfig = ProviderConfig(),
    updatedAtEpochMs: Long = 1L,
): AgentSessionSnapshot {
    val sessionId = AgentSessionId(sessionIdValue)
    val request = AgentRequest(
        sessionId = sessionId,
        messages = listOf(AgentMessage(role = MessageRole.USER, parts = listOf(TextPart("hello")))),
        model = ModelDescriptor(provider = "test-provider", model = "test-model"),
        engine = AgentEngineConfig(provider = providerConfig),
    )
    return AgentSessionSnapshot(
        sessionId = sessionId,
        request = request,
        state = AgentStateSnapshot(messages = request.messages),
        updatedAtEpochMs = updatedAtEpochMs,
    )
}

internal fun roomTestCheckpoint(
    snapshot: AgentSessionSnapshot,
    turn: Int,
): AgentCheckpoint = AgentCheckpoint(
    sessionId = snapshot.sessionId,
    turn = turn,
    state = snapshot.state.copy(turn = turn),
)

private fun AgentSessionSnapshot.toEntity(): AgentSessionEntity = AgentSessionEntity(
    sessionId = sessionId.value,
    payload = AgentSessionSnapshotCodec().encode(this),
    updatedAtEpochMs = updatedAtEpochMs,
)

private class FakeSessionDao(
    val rows: MutableList<AgentSessionEntity> = mutableListOf(),
) : AgentSessionDao {
    override suspend fun upsert(entity: AgentSessionEntity) {
        rows.removeAll { it.sessionId == entity.sessionId }
        rows += entity
    }

    override suspend fun findById(sessionId: String): AgentSessionEntity? =
        rows.firstOrNull { it.sessionId == sessionId }

    override suspend fun listAll(): List<AgentSessionEntity> =
        rows.sortedByDescending { it.updatedAtEpochMs }

    override suspend fun deleteById(sessionId: String) {
        rows.removeAll { it.sessionId == sessionId }
    }

    override suspend fun deleteAll() {
        rows.clear()
    }
}

private class FakeCheckpointDao(
    var row: AgentCheckpointEntity? = null,
) : AgentCheckpointDao {
    override suspend fun upsert(entity: AgentCheckpointEntity) {
        row = entity
    }

    override suspend fun findById(sessionId: String): AgentCheckpointEntity? =
        row?.takeIf { it.sessionId == sessionId }

    override suspend fun deleteById(sessionId: String) {
        if (row?.sessionId == sessionId) row = null
    }

    override suspend fun deleteAll() {
        row = null
    }
}
