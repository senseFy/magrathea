@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package saien.magrathea.storage.room

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID

class IosRoomDatabaseIntegrationTest {
    @Test
    fun realDatabase_closeAndReopen_preservesCurrentSessionAndCheckpoint() = runTest {
        val databasePath = NSTemporaryDirectory() + "magrathea-${NSUUID().UUIDString}.db"
        val session = roomTestSnapshot("ios-reopen", updatedAtEpochMs = 42L)
        val checkpoint = roomTestCheckpoint(session, turn = 2)
        val reporter = StoredRecordCorruptionReporter { error("Unexpected corruption: $it") }

        val first = IosMagratheaRoom.open(databasePath, reporter)
        first.sessionStore.saveSession(session)
        first.checkpointStore.saveCheckpoint(checkpoint)
        first.close()
        first.close()

        val reopened = IosMagratheaRoom.open(databasePath, reporter)
        assertEquals(session, reopened.sessionStore.loadSession(session.sessionId))
        assertEquals(listOf(session), reopened.sessionStore.listSessions())
        assertEquals(checkpoint, reopened.checkpointStore.loadLatestCheckpoint(session.sessionId))
        reopened.close()

        NSFileManager.defaultManager.removeItemAtPath(databasePath, error = null)
        NSFileManager.defaultManager.removeItemAtPath("$databasePath-wal", error = null)
        NSFileManager.defaultManager.removeItemAtPath("$databasePath-shm", error = null)
    }
}
