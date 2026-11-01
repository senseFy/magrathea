package saien.magrathea.storage.room

import androidx.room.Room
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JvmRoomScalarQueryPlanTest {
    @Test
    fun identityRecoveryAndUnknownSourceQueriesUseCoveringIndexes() = runTest {
        assertLayoutUsesCoveringIndexes(upgradeVersionTwo = false)
        assertLayoutUsesCoveringIndexes(upgradeVersionTwo = true)
    }

    private suspend fun assertLayoutUsesCoveringIndexes(upgradeVersionTwo: Boolean) {
        val directory = Files.createTempDirectory("room-scalar-query-plan-")
        val path = directory.resolve("store.db").toString()
        if (upgradeVersionTwo) {
            BundledSQLiteDriver().open(path).use { connection ->
                createVersionTwoRoomFixture(connection)
                connection.prepare("INSERT INTO agent_sessions VALUES ('source', 'opaque old canonical', 0)").use { it.step() }
                connection.prepare("INSERT INTO agent_background_executions VALUES ('source', 'work', 'opaque old background', 0)").use { it.step() }
            }
        }
        val queries = CopyOnWriteArrayList<String>()
        val database = Room.databaseBuilder<MagratheaDatabase>(name = path)
            .setDriver(RecordingDriver(queries))
            .setQueryCoroutineContext(Dispatchers.Default)
            .addMigrations(MAGRATHEA_ROOM_MIGRATION_2_3)
            .build()
        val store = MagratheaRoomStoreHandle(database, StoredRecordCorruptionReporter { error("Scalar query decoded history") })
        try {
            store.catalog.state()
            // Materialize the added authority columns too; defaults alone need no overflow reads.
            database.sessionDao().upsert(AgentSessionEntity(
                sessionId = "source",
                payload = "opaque canonical:" + "x".repeat(200 * 1024),
                updatedAtEpochMs = 1,
                incarnation = "current-life",
                rowVersion = 2,
                catalogObservedActivityIdentity = "u1",
                catalogActivityIdentity = "u1",
                catalogActivityOrder = "A:1",
                catalogRecoveryStatus = "COMPLETED",
            ))
            database.backgroundExecutionDao().upsert(AgentBackgroundExecutionEntity(
                sessionId = "source",
                executionId = "work",
                envelope = "opaque background:" + "y".repeat(20 * 1024),
                updatedAtEpochMs = 1,
                rowVersion = 2,
                catalogHandle = "background-work",
                catalogState = "TERMINAL",
                catalogResultVersion = 1,
                catalogAcknowledged = false,
            ))
            BundledSQLiteDriver().open(path).use { connection ->
                val columns = connection.prepare("PRAGMA table_info(agent_sessions)").use { statement ->
                    buildList { while (statement.step()) add(statement.getText(1)) }
                }
                assertEquals(if (upgradeVersionTwo) 1 else columns.lastIndex, columns.indexOf("payload"))
            }

            suspend fun assertCovering(label: String, operation: suspend () -> Unit) {
                queries.clear()
                operation()
                val selects = queries.filter {
                    it.trimStart().startsWith("SELECT", ignoreCase = true) &&
                        (it.contains("agent_sessions") || it.contains("agent_background_executions"))
                }
                assertTrue(selects.isNotEmpty(), "$label executed no SELECT")
                BundledSQLiteDriver().open(path).use { connection ->
                    selects.forEach { sql ->
                        val plan = connection.prepare("EXPLAIN QUERY PLAN $sql").use { statement ->
                            buildList { while (statement.step()) add(statement.getText(3)) }
                        }
                        val sourceAccess = plan.filter {
                            it.contains("agent_sessions") || it.contains("agent_background_executions")
                        }
                        val context = "upgraded=$upgradeVersionTwo $label: $sql: $plan"
                        assertTrue(sourceAccess.isNotEmpty(), context)
                        assertTrue(sourceAccess.all { it.contains("USING COVERING INDEX") }, context)
                        assertTrue(plan.none { it.contains("TEMP B-TREE") }, context)
                    }
                }
            }

            val sessions = database.sessionDao()
            val backgrounds = database.backgroundExecutionDao()
            assertCovering("identity") { sessions.identity("source") }
            assertCovering("canonical recovery") { sessions.recovery("source") }
            assertCovering("unknown canonical") { sessions.hasUnknownRecovery() }
            assertCovering("recovery candidate first page") { sessions.recoveryCandidateIds(10) }
            assertCovering("recovery candidate continuation") { sessions.recoveryCandidateIdsAfter("before", 10) }
            assertCovering("exists") { sessions.exists("source") }
            assertCovering("has sessions") { sessions.hasSessions() }
            assertCovering("primary key page") { sessions.pageIds("before", 10) }
            assertCovering("background facts") { backgrounds.recoveryBySession("source") }
            assertCovering("unknown background source") { backgrounds.hasUnknownRecoverySource() }
            assertCovering("unknown background facts") { backgrounds.hasUnknownRecoveryFacts() }
        } finally {
            store.close()
            check(directory.toFile().deleteRecursively())
        }
    }

    private class RecordingDriver(private val queries: MutableList<String>) : SQLiteDriver {
        private val delegate = BundledSQLiteDriver()

        override fun open(fileName: String): SQLiteConnection {
            val connection = delegate.open(fileName)
            return object : SQLiteConnection by connection {
                override fun prepare(sql: String): SQLiteStatement {
                    queries += sql
                    return connection.prepare(sql)
                }
            }
        }
    }
}
