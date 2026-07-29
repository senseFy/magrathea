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
        first.persistence.commit(session, checkpoint)
        first.close()
        first.close()

        val reopened = JvmMagratheaRoom.open(database.toString(), reporter)
        assertEquals(session, reopened.persistence.load(session.sessionId)?.snapshot)
        assertEquals(listOf(session), reopened.persistence.listSessions())
        assertEquals(checkpoint, reopened.persistence.load(session.sessionId)?.checkpoint)
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
        source.persistence.commit(session, checkpoint)
        source.close()

        Files.copy(database, backup, StandardCopyOption.REPLACE_EXISTING)
        Files.copy(backup, restoredDatabase, StandardCopyOption.REPLACE_EXISTING)

        val restored = JvmMagratheaRoom.open(restoredDatabase.toString(), reporter)
        assertEquals(session, restored.persistence.load(session.sessionId)?.snapshot)
        assertEquals(checkpoint, restored.persistence.load(session.sessionId)?.checkpoint)
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
        assertTrue(store.persistence.listSessions().isEmpty())
        assertFailsWith<StoredRecordCorruptionException> {
            store.persistence.load(saien.magrathea.core.AgentSessionId(corruptId))
        }

        store.persistence.clear()
        store.persistence.clear()
        store.close()

        val rebuilt = JvmMagratheaRoom.open(databasePath.toString(), reporter)
        assertTrue(rebuilt.persistence.listSessions().isEmpty())
        assertEquals(
            null,
            rebuilt.persistence.load(saien.magrathea.core.AgentSessionId(corruptId)),
        )
        rebuilt.close()

        check(directory.toFile().deleteRecursively()) { "Failed to remove Room rebuild-test directory" }
    }

    @Test
    fun atomicCommitCanReplaceAResumableRecordWithATerminalSnapshot() = runTest {
        val directory = Files.createTempDirectory("magrathea-room-atomic-")
        val database = directory.resolve("magrathea.db")
        val snapshot = roomTestSnapshot("atomic-replacement")
        val checkpoint = roomTestCheckpoint(snapshot, turn = 1)
        val store = JvmMagratheaRoom.open(
            database.toString(),
            StoredRecordCorruptionReporter { error("Unexpected corruption: $it") },
        )

        store.persistence.commit(snapshot, checkpoint)
        assertEquals(checkpoint, store.persistence.load(snapshot.sessionId)?.checkpoint)

        store.persistence.commit(snapshot, null)
        val terminalRecord = requireNotNull(store.persistence.load(snapshot.sessionId))
        assertEquals(snapshot, terminalRecord.snapshot)
        assertEquals(null, terminalRecord.checkpoint)

        store.close()
        check(directory.toFile().deleteRecursively()) {
            "Failed to remove Room atomic-test directory"
        }
    }
}
