package saien.magrathea.storage.room

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import saien.magrathea.catalog.CatalogCoverage
import saien.magrathea.core.AgentSessionId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JvmRoomIncrementalMigrationTest {
    @Test
    fun largeVersionTwoUpgradeKeepsTablePagesAndPayloadsWithoutCopyingHistory() = runTest {
        val directory = Files.createTempDirectory("room-incremental-v2-")
        val path = directory.resolve("store.db")
        val canonicalBody = "x".repeat(200 * 1024)
        val backgroundBody = "y".repeat(20 * 1024)
        try {
            val roots = BundledSQLiteDriver().open(path.toString()).use { db ->
                createVersionTwoRoomFixture(db)
                db.execute("BEGIN")
                repeat(300) { index ->
                    val id = "session-$index"
                    db.insert("INSERT INTO agent_sessions VALUES (?, ?, ?)", id, "$index:$canonicalBody", "$index")
                    db.insert("INSERT INTO agent_checkpoints VALUES (?, ?, ?)", id, "opaque checkpoint $index", "3")
                    db.insert("INSERT INTO agent_background_executions VALUES (?, ?, ?, ?)", id, "work", "$index:$backgroundBody", "$index")
                    db.insert("INSERT INTO agent_background_origins VALUES (?, ?, ?)", id, "run", "opaque origin $index")
                }
                db.execute("COMMIT")
                db.rootPages()
            }
            val beforeBytes = Files.size(path)
            val store = JvmMagratheaRoom.open(path.toString(), StoredRecordCorruptionReporter { error("Upgrade decoded history") })
            try {
                assertEquals(CatalogCoverage.INDEXING, store.catalog.state().coverage)
                assertEquals("legacy", store.catalog.sessionIdentity(AgentSessionId("session-0"))?.incarnation)
                val wal = Path.of("$path-wal")
                val growth = Files.size(path) - beforeBytes + if (Files.exists(wal)) Files.size(wal) else 0
                // ~66 MiB of source payloads: a table copy/rewrite cannot fit within this allowance.
                assertTrue(growth < 4 * 1024 * 1024, "Structural upgrade wrote $growth bytes")
                BundledSQLiteDriver().open(path.toString()).use { db ->
                    assertEquals(roots, db.rootPages())
                    assertEquals("3", db.single("PRAGMA user_version"))
                    assertEquals(listOf("sessionId", "payload", "updatedAtEpochMs"), db.columns("agent_sessions").take(3))
                    assertFalse("catalogAttachmentNameBackup" in db.columns("agent_sessions"))
                    assertEquals("0", db.single("SELECT count(*) FROM session_attachment_name_backups"))
                    repeat(300) { index ->
                        assertEquals("$index:$canonicalBody", db.single("SELECT payload FROM agent_sessions WHERE sessionId='session-$index'"))
                        assertEquals("$index:$backgroundBody", db.single("SELECT envelope FROM agent_background_executions WHERE sessionId='session-$index'"))
                        assertEquals("opaque checkpoint $index", db.single("SELECT payload FROM agent_checkpoints WHERE sessionId='session-$index'"))
                        assertEquals("opaque origin $index", db.single("SELECT envelope FROM agent_background_origins WHERE sessionId='session-$index'"))
                    }
                    assertEquals("300", db.single("SELECT count(*) FROM agent_sessions WHERE incarnation='legacy' AND rowVersion=0 AND catalogRecoveryCandidate=1 AND catalogRecoveryStatus IS NULL"))
                    assertEquals("300", db.single("SELECT count(*) FROM agent_background_executions WHERE rowVersion=0 AND catalogRecoveryCandidate=1 AND catalogState IS NULL"))
                    assertEquals("ok", db.single("PRAGMA quick_check"))
                }
            } finally {
                store.close()
            }
            // Reopening proves Room accepts the historical physical column order by column name.
            val reopened = JvmMagratheaRoom.open(path.toString(), StoredRecordCorruptionReporter { error("Reopen decoded history") })
            try {
                assertTrue(reopened.catalog.exists(AgentSessionId("session-299")))
            } finally {
                reopened.close()
            }
        } finally {
            check(directory.toFile().deleteRecursively())
        }
    }

    @Test
    fun failedIndexDdlRollsBackAddedColumnsAndKeepsVersionTwoReopenable() = runTest {
        val directory = Files.createTempDirectory("room-migration-rollback-")
        val path = directory.resolve("store.db").toString()
        try {
            val roots = BundledSQLiteDriver().open(path).use { db ->
                createVersionTwoRoomFixture(db)
                db.insert("INSERT INTO agent_sessions VALUES (?, ?, ?)", "kept", "opaque canonical", "17")
                // A table occupying the index name causes real SQLite DDL failure after ALTERs.
                db.execute("CREATE TABLE index_agent_sessions_catalog_identity (value TEXT)")
                db.rootPages()
            }
            val failed = JvmMagratheaRoom.open(path, StoredRecordCorruptionReporter { error("Migration decoded history") })
            try {
                assertFailsWith<Exception> { failed.catalog.state() }
            } finally {
                failed.close()
            }
            BundledSQLiteDriver().open(path).use { db ->
                assertEquals("2", db.single("PRAGMA user_version"))
                assertEquals(roots, db.rootPages())
                assertEquals(listOf("sessionId", "payload", "updatedAtEpochMs"), db.columns("agent_sessions"))
                assertEquals("opaque canonical", db.single("SELECT payload FROM agent_sessions"))
                assertEquals("0", db.single("SELECT count(*) FROM sqlite_master WHERE name='session_attachment_name_backups'"))
                db.execute("DROP TABLE index_agent_sessions_catalog_identity")
            }
            val retry = JvmMagratheaRoom.open(path, StoredRecordCorruptionReporter { error("Retry decoded history") })
            try {
                assertTrue(retry.catalog.exists(AgentSessionId("kept")))
            } finally {
                retry.close()
            }
        } finally {
            check(directory.toFile().deleteRecursively())
        }
    }

    private fun SQLiteConnection.insert(sql: String, vararg values: String) = prepare(sql).use {
        values.forEachIndexed { index, value -> it.bindText(index + 1, value) }
        it.step()
    }

    private fun SQLiteConnection.execute(sql: String) = prepare(sql).use { it.step() }

    private fun SQLiteConnection.single(sql: String): String = prepare(sql).use {
        check(it.step())
        it.getText(0)
    }

    private fun SQLiteConnection.columns(table: String): List<String> = prepare("PRAGMA table_info($table)").use {
        buildList { while (it.step()) add(it.getText(1)) }
    }

    private fun SQLiteConnection.rootPages(): Map<String, Long> = prepare(
        "SELECT name,rootpage FROM sqlite_master WHERE type='table' AND name IN " +
            "('agent_sessions','agent_checkpoints','agent_background_executions','agent_background_origins')",
    ).use {
        buildMap { while (it.step()) put(it.getText(0), it.getLong(1)) }
    }
}

internal fun createVersionTwoRoomFixture(db: SQLiteConnection) {
    fun execute(sql: String) = db.prepare(sql).use { it.step() }
    val relative = "schemas/saien.magrathea.storage.room.MagratheaDatabase/2.json"
    val candidates = listOf(Path.of(relative), Path.of("magrathea-storage-room").resolve(relative))
    val schema = Json.parseToJsonElement(Files.readString(candidates.first { Files.exists(it) }))
        .jsonObject.getValue("database").jsonObject
    schema.getValue("entities").jsonArray.forEach { item ->
        val entity = item.jsonObject
        val name = entity.getValue("tableName").jsonPrimitive.content
        execute(entity.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", name))
        entity["indices"]?.jsonArray.orEmpty().forEach { index ->
            execute(index.jsonObject.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", name))
        }
    }
    schema.getValue("setupQueries").jsonArray.forEach { execute(it.jsonPrimitive.content) }
    execute("PRAGMA user_version = 2")
}
