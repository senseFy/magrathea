package saien.magrathea.storage.room

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import saien.magrathea.core.AgentMessage
import saien.magrathea.core.AgentRunId
import saien.magrathea.core.AgentSessionId
import saien.magrathea.core.BackgroundExecutionRecord
import saien.magrathea.core.BackgroundExecutionState
import saien.magrathea.core.BackgroundOriginContext
import saien.magrathea.core.AgentRequest
import saien.magrathea.core.MessageRole
import saien.magrathea.core.ModelDescriptor
import saien.magrathea.core.ToolCallPart
import saien.magrathea.core.ToolExecutionRequest

class JvmRoomBackgroundStoreTest {
    @Test
    fun upsertLoadReplaceAndDelete() = runTest {
        val directory = Files.createTempDirectory("magrathea-room-background-")
        val reporter = StoredRecordCorruptionReporter { error("Unexpected corruption: $it") }
        val store = JvmMagratheaRoom.open(
            directory.resolve("magrathea.db").toString(),
            reporter,
        )
        try {
            val running = roomBackgroundRecord("exec-1", BackgroundExecutionState.RUNNING)
            val queued = roomBackgroundRecord("exec-2", BackgroundExecutionState.QUEUED)
            store.backgroundStore.upsert(running)
            store.backgroundStore.upsert(queued)

            val loaded = store.backgroundStore.load(running.sessionId)
            assertEquals(listOf(running, queued), loaded.records)

            val replacement = running.copy(startedAtEpochMs = 99L)
            store.backgroundStore.upsert(replacement)
            assertEquals(replacement, store.backgroundStore.load(running.sessionId).records
                .single { it.executionId == running.executionId })

            store.backgroundStore.delete(running.sessionId, running.executionId)
            assertEquals(
                listOf(queued),
                store.backgroundStore.load(running.sessionId).records,
            )
        } finally {
            store.close()
            check(directory.toFile().deleteRecursively()) {
                "Failed to remove Room background-test directory"
            }
        }
    }

    @Test
    fun originsRoundTripAndAreScopedBySession() = runTest {
        val directory = Files.createTempDirectory("magrathea-room-background-origin-")
        val reporter = StoredRecordCorruptionReporter { error("Unexpected corruption: $it") }
        val store = JvmMagratheaRoom.open(
            directory.resolve("magrathea.db").toString(),
            reporter,
        )
        try {
            val sessionId = AgentSessionId("origin-session")
            val origin = roomBackgroundOrigin(sessionId, AgentRunId("origin-run-1"))
            val other = roomBackgroundOrigin(sessionId, AgentRunId("origin-run-2"))
            store.backgroundStore.putOrigin(origin)
            store.backgroundStore.putOrigin(other)

            assertEquals(
                listOf(origin, other),
                store.backgroundStore.load(sessionId).origins,
            )
            assertEquals(
                emptyList(),
                store.backgroundStore.load(AgentSessionId("other-session")).origins,
            )

            store.backgroundStore.deleteOrigin(sessionId, origin.runId)
            assertEquals(
                listOf(other),
                store.backgroundStore.load(sessionId).origins,
            )
        } finally {
            store.close()
            check(directory.toFile().deleteRecursively()) {
                "Failed to remove Room origin-test directory"
            }
        }
    }

    @Test
    fun deleteSessionAndClearRemoveRecordsAndOrigins() = runTest {
        val directory = Files.createTempDirectory("magrathea-room-background-clear-")
        val reporter = StoredRecordCorruptionReporter { error("Unexpected corruption: $it") }
        val store = JvmMagratheaRoom.open(
            directory.resolve("magrathea.db").toString(),
            reporter,
        )
        try {
            val first = roomBackgroundRecord("exec-first", BackgroundExecutionState.QUEUED)
            val secondSession = AgentSessionId("second-session")
            val second = roomBackgroundRecord(
                "exec-second",
                BackgroundExecutionState.RUNNING,
                sessionId = secondSession,
            )
            store.backgroundStore.upsert(first)
            store.backgroundStore.upsert(second)
            store.backgroundStore.putOrigin(
                roomBackgroundOrigin(first.sessionId, first.originRunId),
            )
            store.backgroundStore.putOrigin(
                roomBackgroundOrigin(secondSession, second.originRunId),
            )

            store.backgroundStore.deleteSession(first.sessionId)
            val remaining = store.backgroundStore.load(first.sessionId)
            assertTrue(remaining.records.isEmpty())
            assertTrue(remaining.origins.isEmpty())
            assertEquals(
                listOf(second),
                store.backgroundStore.load(secondSession).records,
            )

            store.backgroundStore.clear()
            assertTrue(store.backgroundStore.load(secondSession).records.isEmpty())
        } finally {
            store.close()
            check(directory.toFile().deleteRecursively()) {
                "Failed to remove Room clear-test directory"
            }
        }
    }

    @Test
    fun version1DatabaseMigratesToVersion2WithSessionsIntact() = runTest {
        val directory = Files.createTempDirectory("magrathea-room-v1-migration-")
        val databasePath = directory.resolve("magrathea.db")
        val session = roomTestSnapshot("v1-migrated-session", updatedAtEpochMs = 41L)
        val checkpoint = roomTestCheckpoint(session, turn = 2)
        val sessionEnvelope = saien.magrathea.core.AgentSessionSnapshotCodec().encode(session)
        val checkpointEnvelope = saien.magrathea.core.AgentCheckpointCodec().encode(checkpoint)

        val connection = BundledSQLiteDriver().open(databasePath.toString())
        connection.use { it ->
            it.execSql(
                "CREATE TABLE `agent_sessions` (`sessionId` TEXT NOT NULL, " +
                    "`payload` TEXT NOT NULL, `updatedAtEpochMs` INTEGER NOT NULL, " +
                    "PRIMARY KEY(`sessionId`))",
            )
            it.execSql(
                "CREATE TABLE `agent_checkpoints` (`sessionId` TEXT NOT NULL, " +
                    "`payload` TEXT NOT NULL, `turn` INTEGER NOT NULL, " +
                    "PRIMARY KEY(`sessionId`))",
            )
            it.execSql(
                "CREATE TABLE room_master_table " +
                    "(id INTEGER PRIMARY KEY, identity_hash TEXT)",
            )
            it.execSql(
                "INSERT INTO room_master_table VALUES(42, '$ROOM_V1_IDENTITY_HASH')",
            )
            it.execSql(
                "INSERT INTO `agent_sessions` VALUES(" +
                    "'${session.sessionId.value}', '${sessionEnvelope.replace("'", "''")}', " +
                    session.updatedAtEpochMs + ")",
            )
            it.execSql(
                "INSERT INTO `agent_checkpoints` VALUES(" +
                    "'${session.sessionId.value}', '${checkpointEnvelope.replace("'", "''")}', " +
                    checkpoint.turn + ")",
            )
            it.execSql("PRAGMA user_version = 1")
        }

        val reporter = StoredRecordCorruptionReporter { error("Unexpected corruption: $it") }
        val store = JvmMagratheaRoom.open(databasePath.toString(), reporter)
        try {
            val loaded = assertNotNull(store.persistence.load(session.sessionId))
            assertEquals(session, loaded.snapshot)
            assertEquals(checkpoint, loaded.checkpoint)
            // The migration created the background tables; the store reads empty for new rows.
            val background = store.backgroundStore.load(session.sessionId)
            assertTrue(background.records.isEmpty())
            assertTrue(background.origins.isEmpty())

            val record = roomBackgroundRecord(
                "exec-migrated",
                BackgroundExecutionState.QUEUED,
                sessionId = session.sessionId,
            )
            store.backgroundStore.upsert(record)
            assertEquals(
                listOf(record),
                store.backgroundStore.load(session.sessionId).records,
            )
        } finally {
            store.close()
            check(directory.toFile().deleteRecursively()) {
                "Failed to remove Room v1-migration directory"
            }
        }
    }

    @Test
    fun corruptBackgroundEnvelopeIsReportedAndSkipped() = runTest {
        val directory = Files.createTempDirectory("magrathea-room-background-corrupt-")
        val databasePath = directory.resolve("magrathea.db")
        val sessionId = AgentSessionId("corrupt-background-session")
        val valid = roomBackgroundRecord("exec-valid", BackgroundExecutionState.QUEUED,
            sessionId = sessionId)
        val reports = mutableListOf<StoredRecordCorruption>()
        val rawDatabase = buildMagratheaRoomDatabase(
            Room.databaseBuilder<MagratheaDatabase>(name = databasePath.toString()),
        )
        rawDatabase.backgroundExecutionDao().upsert(
            AgentBackgroundExecutionEntity(sessionId.value, "exec-corrupt", "not-json", 1L),
        )
        rawDatabase.backgroundOriginDao().upsert(
            AgentBackgroundOriginEntity(sessionId.value, "run-corrupt", "not-json"),
        )
        rawDatabase.close()

        val store = JvmMagratheaRoom.open(
            databasePath.toString(),
            StoredRecordCorruptionReporter(reports::add),
        )
        try {
            store.backgroundStore.upsert(valid)
            val loaded = store.backgroundStore.load(sessionId)
            assertEquals(listOf(valid), loaded.records)
            assertTrue(loaded.origins.isEmpty())
            assertEquals(
                setOf(
                    StoredRecordKind.BACKGROUND_EXECUTION,
                    StoredRecordKind.BACKGROUND_ORIGIN,
                ),
                reports.map { it.kind }.toSet(),
            )
            assertTrue(reports.all { it.reason == StoredRecordCorruptionReason.INVALID_PAYLOAD })
        } finally {
            store.close()
            check(directory.toFile().deleteRecursively()) {
                "Failed to remove Room corrupt-background directory"
            }
        }
    }

    private fun roomBackgroundRecord(
        executionId: String,
        state: BackgroundExecutionState,
        sessionId: AgentSessionId = AgentSessionId("background-session"),
    ): BackgroundExecutionRecord {
        val call = ToolCallPart(
            toolCallId = "call-$executionId",
            toolName = "deep_lookup",
            arguments = buildJsonObject { put("query", JsonPrimitive("atlas")) },
        )
        return BackgroundExecutionRecord(
            sessionId = sessionId,
            executionId = executionId,
            handle = "bg_$executionId",
            originRunId = AgentRunId("run-$executionId"),
            originCallId = "call-$executionId",
            toolName = "deep_lookup",
            sourceId = "user-$executionId",
            state = state,
            executionRequest = ToolExecutionRequest(
                sessionId = sessionId,
                runId = AgentRunId("run-$executionId"),
                executionId = executionId,
                assistantMessage = AgentMessage(
                    id = "assistant-$executionId",
                    role = MessageRole.ASSISTANT,
                    parts = listOf(call),
                    createdAtEpochMs = 1_700_000_000_001,
                ),
                toolCall = call,
            ),
            effectiveTimeoutMillis = 30_000,
            queuedAtEpochMs = 1_700_000_000_002,
            startedAtEpochMs = if (state == BackgroundExecutionState.RUNNING) {
                1_700_000_000_003
            } else {
                null
            },
        )
    }

    private fun roomBackgroundOrigin(
        sessionId: AgentSessionId,
        runId: AgentRunId,
    ) = BackgroundOriginContext(
        sessionId = sessionId,
        runId = runId,
        request = AgentRequest(
            sessionId = sessionId,
            messages = emptyList(),
            model = ModelDescriptor(provider = "test-provider", model = "test-model"),
        ),
    )

    private fun androidx.sqlite.SQLiteConnection.execSql(sql: String) {
        prepare(sql).use { it.step() }
    }

    private companion object {
        /** identityHash of the exported version-1 schema (schemas/.../1.json). */
        const val ROOM_V1_IDENTITY_HASH = "59697bf2b1b095dc1b64f68407ecd52e"
    }
}
