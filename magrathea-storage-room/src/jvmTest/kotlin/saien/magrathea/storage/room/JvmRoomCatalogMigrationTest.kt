package saien.magrathea.storage.room

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import saien.magrathea.catalog.CatalogCoverage
import saien.magrathea.catalog.SessionCatalogQuery
import saien.magrathea.core.AgentCheckpointCodec
import saien.magrathea.core.AgentSessionId
import saien.magrathea.core.AgentSessionSnapshotCodec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JvmRoomCatalogMigrationTest {
    @Test
    fun structuralUpgradeKeepsEveryEnvelopeAndDoesNotDecodeOldHistory() = runTest {
        val directory = Files.createTempDirectory("magrathea-catalog-v2-")
        val path = directory.resolve("store.db").toString()
        val session = roomTestSnapshot("existing", updatedAtEpochMs = 41L)
        val checkpoint = roomTestCheckpoint(session, turn = 2)
        val sessionPayload = AgentSessionSnapshotCodec().encode(session)
        val checkpointPayload = AgentCheckpointCodec().encode(checkpoint)
        val opaquePayload = "unknown future or corrupt envelope: 留存原始数据"
        try {
            BundledSQLiteDriver().open(path).use { db ->
                createVersionTwo(db)
                db.prepare("INSERT INTO agent_sessions VALUES (?, ?, ?)").use {
                    it.bindText(1, session.sessionId.value)
                    it.bindText(2, sessionPayload)
                    it.bindLong(3, session.updatedAtEpochMs)
                    it.step()
                }
                db.prepare("INSERT INTO agent_sessions VALUES (?, ?, ?)").use {
                    it.bindText(1, "unreadable")
                    it.bindText(2, opaquePayload)
                    it.bindLong(3, 42L)
                    it.step()
                }
                db.prepare("INSERT INTO agent_checkpoints VALUES (?, ?, ?)").use {
                    it.bindText(1, session.sessionId.value)
                    it.bindText(2, checkpointPayload)
                    it.bindLong(3, checkpoint.turn.toLong())
                    it.step()
                }
                db.execute("INSERT INTO agent_background_executions VALUES ('existing', 'work', 'opaque work', 1)")
                db.execute("INSERT INTO agent_background_origins VALUES ('existing', 'run', 'opaque origin')")
            }

            val store = JvmMagratheaRoom.open(path, StoredRecordCorruptionReporter {
                error("A structural upgrade or catalog page must not decode canonical envelopes")
            })
            try {
                assertEquals(CatalogCoverage.INDEXING, store.catalog.state().coverage)
                assertTrue(store.catalog.exists(session.sessionId))
                assertEquals("legacy", assertNotNull(store.catalog.sessionIdentity(session.sessionId)).incarnation)
                assertNull(store.catalog.sessionHeader(session.sessionId))
                assertNull(store.catalog.sessionIdentity(AgentSessionId("missing")))
                assertTrue(store.catalog.exists(AgentSessionId("unreadable")))
                assertFalse(store.catalog.exists(AgentSessionId("missing")))
                val page = store.catalog.pageSessions(SessionCatalogQuery(), limit = 100)
                assertTrue(page.entries.isEmpty())
                assertEquals(CatalogCoverage.INDEXING, page.state.coverage)
            } finally {
                store.close()
            }

            BundledSQLiteDriver().open(path).use { db ->
                assertEquals("4", db.single("PRAGMA user_version"))
                assertEquals(sessionPayload, db.single("SELECT payload FROM agent_sessions WHERE sessionId = 'existing'"))
                assertEquals(opaquePayload, db.single("SELECT payload FROM agent_sessions WHERE sessionId = 'unreadable'"))
                assertEquals(checkpointPayload, db.single("SELECT payload FROM agent_checkpoints WHERE sessionId = 'existing'"))
                assertEquals("opaque work", db.single("SELECT envelope FROM agent_background_executions"))
                assertEquals("opaque origin", db.single("SELECT envelope FROM agent_background_origins"))
                assertEquals("legacy", db.single("SELECT incarnation FROM agent_sessions WHERE sessionId = 'existing'"))
                assertEquals("0", db.single("SELECT rowVersion FROM agent_sessions WHERE sessionId = 'existing'"))
                val names = mutableSetOf<String>()
                db.prepare("SELECT name FROM sqlite_master WHERE type = 'table'").use { statement ->
                    while (statement.step()) names += statement.getText(0)
                }
                assertTrue(names.containsAll(setOf(
                    "session_catalog", "session_search", "session_attachment_refs",
                    "session_collections", "session_organization", "catalog_state",
                )))
            }
        } finally {
            check(directory.toFile().deleteRecursively())
        }
    }

    private fun createVersionTwo(db: SQLiteConnection) {
        listOf(
            "CREATE TABLE agent_sessions (sessionId TEXT NOT NULL PRIMARY KEY, payload TEXT NOT NULL, updatedAtEpochMs INTEGER NOT NULL)",
            "CREATE TABLE agent_checkpoints (sessionId TEXT NOT NULL PRIMARY KEY, payload TEXT NOT NULL, turn INTEGER NOT NULL)",
            "CREATE TABLE agent_background_executions (sessionId TEXT NOT NULL, executionId TEXT NOT NULL, envelope TEXT NOT NULL, updatedAtEpochMs INTEGER NOT NULL, PRIMARY KEY(sessionId, executionId))",
            "CREATE TABLE agent_background_origins (sessionId TEXT NOT NULL, runId TEXT NOT NULL, envelope TEXT NOT NULL, PRIMARY KEY(sessionId, runId))",
            "CREATE TABLE room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)",
            "INSERT INTO room_master_table VALUES (42, '239cc537c0ab11b527f338737480b847')",
            "PRAGMA user_version = 2",
        ).forEach { db.execute(it) }
    }

    private fun SQLiteConnection.execute(sql: String) {
        prepare(sql).use { it.step() }
    }

    private fun SQLiteConnection.single(sql: String): String = prepare(sql).use {
        check(it.step())
        it.getText(0)
    }
}
