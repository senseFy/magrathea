package saien.magrathea.storage.room

import androidx.room.Room
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import saien.magrathea.catalog.DefaultSessionCatalogProjector
import saien.magrathea.catalog.SessionCatalogException
import saien.magrathea.catalog.SessionCatalogFailure
import saien.magrathea.catalog.SessionCatalogProjector
import saien.magrathea.catalog.SessionCatalogQuery
import saien.magrathea.core.AgentSessionSnapshot
import saien.magrathea.core.AgentSessionSnapshotCodec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JvmRoomConcurrencyTest {
    private val reporter = StoredRecordCorruptionReporter { error("Unexpected corruption") }

    @Test
    fun slowDecoderDoesNotBlockCatalogReadsOrCanonicalWritesOnTheSameHandle() = runTest {
        val directory = Files.createTempDirectory("room-decoder-concurrency-")
        val database = buildMagratheaRoomDatabase(
            Room.databaseBuilder<MagratheaDatabase>(name = directory.resolve("store.db").toString()),
        )
        val catalog = RoomSessionCatalog(database)
        val writer = RoomAgentPersistence(database, reporter, catalog = catalog)
        val gate = Gate()
        val decoder = RoomAgentPersistence(
            database,
            reporter,
            snapshotDecoder = { payload ->
                gate.block()
                AgentSessionSnapshotCodec().decodeResult(payload).toStoredRecord()
            },
            catalog = catalog,
        )
        try {
            val original = roomTestSnapshot("slow-read")
            writer.commit(original, null)
            val reading = async(Dispatchers.Default) { decoder.listSessions() }
            gate.awaitEntered()
            try {
                withContext(Dispatchers.Default) {
                    withTimeout(5_000) {
                        assertEquals(1, catalog.pageSessions(SessionCatalogQuery(), limit = 10).entries.size)
                        writer.commit(roomTestSnapshot("parallel-write"), null)
                        assertEquals(2, catalog.pageSessions(SessionCatalogQuery(), limit = 10).entries.size)
                    }
                }
            } finally {
                gate.release()
            }
            assertEquals(listOf(original), reading.await())
        } finally {
            gate.release()
            catalog.close()
            check(directory.toFile().deleteRecursively())
        }
    }

    @Test
    fun slowProjectionAllowsOtherOperationsAndCancelledCloseStillDrainsAdmittedWork() = runTest {
        val directory = Files.createTempDirectory("room-projector-concurrency-")
        val gate = Gate()
        val delegate = DefaultSessionCatalogProjector()
        val projector = object : SessionCatalogProjector by delegate {
            override fun project(snapshot: AgentSessionSnapshot) = run {
                if (snapshot.sessionId.value == "slow-write") gate.block()
                delegate.project(snapshot)
            }
        }
        val store = JvmMagratheaRoom.open(directory.resolve("store.db").toString(), reporter, projector)
        try {
            val writing = async(Dispatchers.Default) { store.persistence.commit(roomTestSnapshot("slow-write"), null) }
            gate.awaitEntered()
            try {
                withContext(Dispatchers.Default) {
                    withTimeout(5_000) {
                        assertTrue(store.catalog.pageSessions(SessionCatalogQuery(), limit = 10).entries.isEmpty())
                        store.persistence.commit(roomTestSnapshot("parallel-write"), null)
                    }
                }
                val closing = async(start = CoroutineStart.UNDISPATCHED) { store.close() }
                assertFalse(closing.isCompleted)
                assertEquals(SessionCatalogFailure.CLOSED, assertFailsWith<SessionCatalogException> {
                    store.catalog.state()
                }.failure)
                closing.cancel()
                val repeatedClose = async(start = CoroutineStart.UNDISPATCHED) { store.close() }
                assertFalse(repeatedClose.isCompleted)
                gate.release()
                writing.await()
                closing.join()
                repeatedClose.await()
                assertTrue(closing.isCancelled)
                assertEquals(SessionCatalogFailure.CLOSED, assertFailsWith<SessionCatalogException> {
                    store.persistence.load(roomTestSnapshot("slow-write").sessionId)
                }.failure)
            } finally {
                gate.release()
            }
        } finally {
            gate.release()
            store.close()
            check(directory.toFile().deleteRecursively())
        }
    }

    private class Gate {
        private val entered = CountDownLatch(1)
        private val released = CountDownLatch(1)

        fun block() {
            entered.countDown()
            check(released.await(10, TimeUnit.SECONDS)) { "Test did not release the gate" }
        }

        suspend fun awaitEntered() = withContext(Dispatchers.IO) {
            check(entered.await(10, TimeUnit.SECONDS)) { "Operation did not enter the gate" }
        }

        fun release() = released.countDown()
    }
}
