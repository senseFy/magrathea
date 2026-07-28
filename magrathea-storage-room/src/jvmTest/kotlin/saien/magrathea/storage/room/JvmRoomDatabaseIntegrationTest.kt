package saien.magrathea.storage.room

import androidx.room.Room
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class JvmRoomDatabaseIntegrationTest {
    @Test
    fun realDatabase_closeAndReopen_preservesCurrentSessionAndCheckpoint() = runTest {
        val directory = Files.createTempDirectory("magrathea-room-")
        val database = directory.resolve("magrathea.db")
        val session = roomTestSnapshot("jvm-reopen", updatedAtEpochMs = 42L)
        val checkpoint = roomTestCheckpoint(session, turn = 2)
        val reporter = StoredRecordCorruptionReporter { error("Unexpected corruption: $it") }

        val first = JvmMagratheaRoom.open(database.toString(), reporter)
        first.sessionStore.saveSession(session)
        first.checkpointStore.saveCheckpoint(checkpoint)
        first.close()
        first.close()

        val reopened = JvmMagratheaRoom.open(database.toString(), reporter)
        assertEquals(session, reopened.sessionStore.loadSession(session.sessionId))
        assertEquals(listOf(session), reopened.sessionStore.listSessions())
        assertEquals(checkpoint, reopened.checkpointStore.loadLatestCheckpoint(session.sessionId))
        reopened.close()

        check(directory.toFile().deleteRecursively()) { "Failed to remove Room integration-test directory" }
    }

    @Test
    fun currentSchemaBackupAfterClose_restoresThroughStrictStores() = runTest {
        val directory = Files.createTempDirectory("magrathea-room-backup-")
        val database = directory.resolve("source.db")
        val backup = directory.resolve("current-schema.backup")
        val restoredDatabase = directory.resolve("restored.db")
        val session = roomTestSnapshot("jvm-backup-restore", updatedAtEpochMs = 77L)
        val checkpoint = roomTestCheckpoint(session, turn = 3)
        val reporter = StoredRecordCorruptionReporter { error("Unexpected corruption: $it") }

        val source = JvmMagratheaRoom.open(database.toString(), reporter)
        source.sessionStore.saveSession(session)
        source.checkpointStore.saveCheckpoint(checkpoint)
        source.close()

        Files.copy(database, backup, StandardCopyOption.REPLACE_EXISTING)
        Files.copy(backup, restoredDatabase, StandardCopyOption.REPLACE_EXISTING)

        val restored = JvmMagratheaRoom.open(restoredDatabase.toString(), reporter)
        assertEquals(session, restored.sessionStore.loadSession(session.sessionId))
        assertEquals(checkpoint, restored.checkpointStore.loadLatestCheckpoint(session.sessionId))
        restored.close()

        check(directory.toFile().deleteRecursively()) { "Failed to remove Room backup-test directory" }
    }

    @Test
    fun corruptCurrentRows_canBeClearedAndRebuiltWithoutDecoding() = runTest {
        val directory = Files.createTempDirectory("magrathea-room-rebuild-")
        val databasePath = directory.resolve("magrathea.db")
        val corruptId = "corrupt-current-row"
        val reporter = StoredRecordCorruptionReporter { }
        val rawDatabase = buildMagratheaRoomDatabase(
            Room.databaseBuilder<MagratheaDatabase>(name = databasePath.toString()),
        )
        rawDatabase.sessionDao().upsert(AgentSessionEntity(corruptId, "not-json", 1L))
        rawDatabase.checkpointDao().upsert(AgentCheckpointEntity(corruptId, "not-json", 1))
        rawDatabase.close()

        val store = JvmMagratheaRoom.open(databasePath.toString(), reporter)
        assertTrue(store.sessionStore.listSessions().isEmpty())
        assertFailsWith<StoredRecordCorruptionException> {
            store.checkpointStore.loadLatestCheckpoint(saien.magrathea.core.AgentSessionId(corruptId))
        }

        store.sessionStore.clear()
        store.checkpointStore.clear()
        store.sessionStore.clear()
        store.checkpointStore.clear()
        store.close()

        val rebuilt = JvmMagratheaRoom.open(databasePath.toString(), reporter)
        assertTrue(rebuilt.sessionStore.listSessions().isEmpty())
        assertEquals(
            null,
            rebuilt.checkpointStore.loadLatestCheckpoint(saien.magrathea.core.AgentSessionId(corruptId)),
        )
        rebuilt.close()

        check(directory.toFile().deleteRecursively()) { "Failed to remove Room rebuild-test directory" }
    }
}
