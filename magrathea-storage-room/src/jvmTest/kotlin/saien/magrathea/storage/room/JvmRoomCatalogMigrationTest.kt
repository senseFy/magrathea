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
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JvmRoomCatalogMigrationTest {
    @Test
    fun versionThreeUpgradeAddsOnlyTheCollectionControlAndPreservesExistingBytesAndPages() = runTest {
        val directory = Files.createTempDirectory("room-collection-protocol-v3-")
        val path = directory.resolve("store.db").toString()
        try {
            val before = BundledSQLiteDriver().open(path).use { db ->
                createVersionThreeRoomFixture(db)
                seedVersionThree(db)
                db.catalogContents() to db.tableRootPages()
            }
            val store = JvmMagratheaRoom.open(path, StoredRecordCorruptionReporter { error("Upgrade decoded history") })
            try {
                val state = store.catalog.state()
                assertEquals(17L, state.revision)
                assertEquals(2L, state.generation)
                assertEquals(0L, state.collectionsRevision)
                assertEquals("Folder", store.catalog.collection("folder")?.name)
                assertEquals("kept-life", store.catalog.sessionIdentity(AgentSessionId("existing"))?.incarnation)
            } finally {
                store.close()
            }
            BundledSQLiteDriver().open(path).use { db ->
                assertEquals("4", db.single("PRAGMA user_version"))
                assertEquals("0", db.single("SELECT value FROM catalog_state WHERE `key` = 'collectionsRevision'"))
                assertEquals(before.first, db.catalogContents())
                assertEquals(before.second, db.tableRootPages())
            }
        } finally {
            check(directory.toFile().deleteRecursively())
        }
    }

    @Test
    fun failedProtocolUpgradeRollsBackItsVersionAndLeavesExistingControlsUntouched() = runTest {
        val directory = Files.createTempDirectory("room-collection-protocol-failure-")
        val path = directory.resolve("store.db").toString()
        try {
            val before = BundledSQLiteDriver().open(path).use { db ->
                createVersionThreeRoomFixture(db)
                seedVersionThree(db)
                db.execute(
                    "CREATE TRIGGER reject_collection_protocol BEFORE INSERT ON catalog_state " +
                        "WHEN NEW.`key` = 'collectionsRevision' BEGIN SELECT RAISE(ABORT, 'fixture rejection'); END",
                )
                db.catalogContents()
            }
            val store = JvmMagratheaRoom.open(path, StoredRecordCorruptionReporter { error("Upgrade decoded history") })
            try {
                assertFailsWith<Exception> { store.catalog.state() }
            } finally {
                store.close()
            }
            BundledSQLiteDriver().open(path).use { db ->
                assertEquals("3", db.single("PRAGMA user_version"))
                assertEquals("0", db.single("SELECT COUNT(*) FROM catalog_state WHERE `key` = 'collectionsRevision'"))
                assertEquals(before, db.catalogContents())
            }
        } finally {
            check(directory.toFile().deleteRecursively())
        }
    }

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

    private fun seedVersionThree(db: SQLiteConnection) {
        val controls = mapOf(
            "policyId" to "magrathea.default",
            "policyVersion" to "1",
            "revision" to "17",
            "generation" to "2",
            "sequence" to "9",
            "activityMode" to "CANONICAL_TIME",
            "coverage" to "partial",
        )
        controls.forEach { (key, value) ->
            db.prepare("INSERT INTO catalog_state VALUES (?, ?)").use {
                it.bindText(1, key)
                it.bindText(2, value)
                it.step()
            }
        }
        db.prepare(
            "INSERT INTO agent_sessions (sessionId, updatedAtEpochMs, incarnation, rowVersion, " +
                "catalogObservedActivityIdentity, catalogActivityIdentity, catalogActivityOrder, " +
                "catalogRecoveryStatus, catalogRecoveryCandidate, payload) " +
                "VALUES ('existing', 12, 'kept-life', 8, 'u1', 'u2', 'A:9', 'COMPLETED', 0, ?)",
        ).use {
            it.bindText(1, "opaque canonical 留存:" + "x".repeat(200 * 1024))
            it.step()
        }
        listOf(
            "INSERT INTO agent_checkpoints VALUES ('existing', 'opaque checkpoint', 3)",
            "INSERT INTO agent_background_executions (sessionId, executionId, updatedAtEpochMs, " +
                "rowVersion, catalogHandle, catalogState, catalogResultVersion, catalogAcknowledged, " +
                "catalogRecoveryCandidate, envelope) VALUES " +
                "('existing', 'work', 12, 4, 'handle', 'TERMINAL', 2, 1, 0, 'opaque work')",
            "INSERT INTO agent_background_origins VALUES ('existing', 'run', 'opaque origin')",
            "INSERT INTO session_attachment_name_backups VALUES ('existing', 'kept-life', 'opaque backup')",
            "INSERT INTO session_collections VALUES ('folder', 'Folder', 'folder', '0066', 'folder', 'folder', 16)",
            "INSERT INTO session_organization VALUES ('existing', 'kept-life', 'Saved title', 'folder', 10)",
            "INSERT INTO session_attachment_refs VALUES ('fixture:uri', 'existing', 'session', 0)",
        ).forEach { db.execute(it) }
    }

    private fun SQLiteConnection.tableRootPages(): Map<String, Long> =
        prepare("SELECT name, rootpage FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%'").use {
            buildMap { while (it.step()) put(it.getText(0), it.getLong(1)) }
        }

    private fun SQLiteConnection.catalogContents(): Map<String, List<List<String>>> {
        val tables = tableRootPages().keys.filterNot { it.startsWith("room_") }
        return tables.associateWith { table ->
            val columns = prepare("PRAGMA table_info(`$table`)").use {
                buildList { while (it.step()) add(it.getText(1)) }
            }
            val fields = columns.joinToString { "quote(`$it`)" }
            val where = if (table == "catalog_state") " WHERE `key` != 'collectionsRevision'" else ""
            prepare("SELECT $fields FROM `$table`$where ORDER BY rowid").use { statement ->
                buildList {
                    while (statement.step()) add(columns.indices.map(statement::getText))
                }
            }
        }
    }

    private fun SQLiteConnection.execute(sql: String) {
        prepare(sql).use { it.step() }
    }

    private fun SQLiteConnection.single(sql: String): String = prepare(sql).use {
        check(it.step())
        it.getText(0)
    }
}
