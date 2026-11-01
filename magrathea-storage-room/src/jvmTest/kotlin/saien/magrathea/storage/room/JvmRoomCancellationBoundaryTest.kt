package saien.magrathea.storage.room

import androidx.room.Room
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.test.runTest
import saien.magrathea.catalog.SessionActivityMode
import saien.magrathea.catalog.SessionActivityOrder
import saien.magrathea.catalog.SessionCatalogPolicy
import saien.magrathea.catalog.SessionCatalogProjection
import saien.magrathea.catalog.SessionCatalogProjector
import saien.magrathea.catalog.SessionCollection
import saien.magrathea.core.AgentMessage
import saien.magrathea.core.AgentSessionSnapshot
import saien.magrathea.core.MessageRole
import saien.magrathea.core.TextPart
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class JvmRoomCancellationBoundaryTest {
    @Test
    fun cancellationImmediatelyAfterCommitStillConsumesTheAcceptedIntent() = runTest {
        withStore { store, driver ->
            val first = snapshot("commit", "u1")
            val identity = store.catalog.prepareNewSession(first.sessionId, "commit-life")
            store.catalog.prepareActivity(identity, "u1")
            cancelAfterCommit(driver) { store.persistence.commit(first, null) }
            assertEquals(first, store.persistence.load(first.sessionId)?.snapshot)

            val second = snapshot("commit", "u2")
            val latest = store.catalog.prepareActivity(identity, "u2")
            store.persistence.commit(second, null)
            // A leaked Advance(u1) from the cancelled call would reject this Preserve as a conflict.
            store.catalog.preparePreservedActivity(identity, "u1")
            store.persistence.commit(first, null)
            val header = assertNotNull(store.catalog.sessionHeader(first.sessionId))
            assertEquals("u1", header.activity.observedIdentity)
            assertEquals("u2", header.activity.lastAcceptedIdentity)
            assertEquals(SessionActivityOrder.Accepted(latest.sequence), header.activity.order)
        }
    }

    @Test
    fun cancellationImmediatelyAfterDeleteStillReleasesAnUncommittedInitialization() = runTest {
        withStore { store, driver ->
            val session = snapshot("delete", "u1")
            val old = store.catalog.prepareNewSession(session.sessionId, "old-life")
            store.catalog.prepareActivity(old, "u1")
            cancelAfterCommit(driver) { store.persistence.deleteSession(session.sessionId) }
            assertFalse(store.catalog.exists(session.sessionId))
            val replacement = store.catalog.prepareNewSession(session.sessionId, "new-life")
            store.catalog.prepareActivity(replacement, "u1")
            store.persistence.commit(session, null)
            assertEquals(replacement, store.catalog.sessionIdentity(session.sessionId))
        }
    }

    @Test
    fun cancellationImmediatelyAfterClearStillInvalidatesEveryPreparedLifetime() = runTest {
        withStore { store, driver ->
            store.catalog.createCollection(SessionCollection("kept", "Kept", "kept", "kept"))
            val first = snapshot("pending-a", "u1")
            val second = snapshot("pending-b", "u2")
            val a = store.catalog.prepareNewSession(first.sessionId, "old-a")
            val b = store.catalog.prepareNewSession(second.sessionId, "old-b")
            store.catalog.prepareActivity(a, "u1")
            store.catalog.prepareActivity(b, "u2")
            val before = store.catalog.state()
            cancelAfterCommit(driver) { store.persistence.clear() }
            assertTrue(store.catalog.state().generation > before.generation)
            assertNotNull(store.catalog.collection("kept"))
            val newA = store.catalog.prepareNewSession(first.sessionId, "new-a")
            val newB = store.catalog.prepareNewSession(second.sessionId, "new-b")
            store.catalog.prepareActivity(newA, "u1")
            store.catalog.prepareActivity(newB, "u2")
            store.persistence.commit(first, null)
            store.persistence.commit(second, null)
            assertEquals(newA, store.catalog.sessionIdentity(first.sessionId))
            assertEquals(newB, store.catalog.sessionIdentity(second.sessionId))
        }
    }

    private suspend fun cancelAfterCommit(driver: CommitCancellationDriver, action: suspend () -> Unit) = coroutineScope {
        val before = driver.cancellations.get()
        val task = async {
            driver.arm(currentCoroutineContext().job)
            action()
        }
        assertFailsWith<CancellationException> { task.await() }
        assertEquals(before + 1, driver.cancellations.get(), "The real SQLite COMMIT must trigger cancellation")
    }

    private suspend fun withStore(block: suspend (MagratheaRoomStoreHandle, CommitCancellationDriver) -> Unit) {
        val directory = Files.createTempDirectory("room-cancel-after-commit-")
        val driver = CommitCancellationDriver()
        val database = Room.databaseBuilder<MagratheaDatabase>(name = directory.resolve("store.db").toString())
            .setDriver(driver)
            .setQueryCoroutineContext(Dispatchers.Default)
            .addMigrations(
                MAGRATHEA_ROOM_MIGRATION_1_2,
                MAGRATHEA_ROOM_MIGRATION_2_3,
                MAGRATHEA_ROOM_MIGRATION_3_4,
            )
            .build()
        val store = MagratheaRoomStoreHandle(database, StoredRecordCorruptionReporter { error("Unexpected corruption") }, projector = CancellationProjector())
        try {
            block(store, driver)
        } finally {
            store.close()
            check(directory.toFile().deleteRecursively())
        }
    }

    private fun snapshot(id: String, activity: String): AgentSessionSnapshot {
        val base = roomTestSnapshot(id)
        val message = AgentMessage(id = activity, role = MessageRole.USER, parts = listOf(TextPart(activity)), createdAtEpochMs = 1)
        return base.copy(request = base.request.copy(messages = listOf(message)), state = base.state.copy(messages = listOf(message)))
    }

    private class CancellationProjector : SessionCatalogProjector {
        override val policy = SessionCatalogPolicy("cancel-boundary", 1)
        override val activityMode = SessionActivityMode.PREPARED
        override fun normalizeSearch(text: String): String = text.lowercase()
        override fun project(snapshot: AgentSessionSnapshot) = SessionCatalogProjection(snapshot.sessionId.value,
            activityIdentity = snapshot.state.messages.lastOrNull()?.id, activityAtEpochMs = snapshot.updatedAtEpochMs,
            normalizedFallbackLabel = snapshot.sessionId.value.lowercase())
    }

    /** Cancels the caller only after the real driver has committed a transaction touching sessions. */
    private class CommitCancellationDriver : SQLiteDriver {
        private val delegate = BundledSQLiteDriver()
        private val armed = AtomicReference<Job?>()
        val cancellations = AtomicInteger()

        fun arm(job: Job) {
            check(armed.compareAndSet(null, job))
        }

        override fun open(fileName: String): SQLiteConnection {
            val connection = delegate.open(fileName)
            return object : SQLiteConnection by connection {
                private var canonicalWrite = false
                override fun prepare(sql: String): SQLiteStatement {
                    val statement = connection.prepare(sql)
                    val command = sql.trim().trimEnd(';').uppercase()
                    val writesCanonical = command.contains("AGENT_SESSIONS") &&
                        (command.startsWith("INSERT") || command.startsWith("UPDATE") || command.startsWith("DELETE"))
                    val commits = command == "END" || command == "COMMIT" || command.startsWith("END TRANSACTION") || command.startsWith("COMMIT TRANSACTION")
                    return object : SQLiteStatement by statement {
                        override fun step(): Boolean {
                            val result = statement.step()
                            if (writesCanonical && armed.get() != null) canonicalWrite = true
                            if (command.startsWith("ROLLBACK")) canonicalWrite = false
                            if (commits && canonicalWrite) {
                                canonicalWrite = false
                                armed.getAndSet(null)?.let { job ->
                                    cancellations.incrementAndGet()
                                    job.cancel(CancellationException("Injected after SQLite committed"))
                                }
                            }
                            return result
                        }
                    }
                }
            }
        }
    }
}
