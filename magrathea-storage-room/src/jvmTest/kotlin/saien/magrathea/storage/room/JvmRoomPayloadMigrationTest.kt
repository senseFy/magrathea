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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JvmRoomPayloadMigrationTest {
    @Test
    fun versionThreeUpgradeCopiesOpaqueSourcesAndSeparatesColdBackupWithoutChangingAuthority() = runTest {
        val directory = Files.createTempDirectory("room-payload-v3-")
        val path = directory.resolve("store.db").toString()
        val payload = "opaque future canonical " + "x".repeat(65_536)
        val backup = "opaque original backup " + "y".repeat(65_536)
        val envelope = "opaque background " + "z".repeat(65_536)
        try {
            BundledSQLiteDriver().open(path).use { db ->
                createVersionThree(db)
                db.insert("agent_sessions", mapOf(
                    "sessionId" to "kept", "payload" to payload, "updatedAtEpochMs" to "17",
                    "incarnation" to "kept-life", "rowVersion" to "9", "catalogObservedActivityIdentity" to "u1",
                    "catalogActivityIdentity" to "u2", "catalogActivityOrder" to "A:11",
                    "catalogRecoveryStatus" to "COMPLETED", "catalogRecoveryCandidate" to "0",
                    "catalogAttachmentNameBackup" to backup,
                ))
                db.insert("agent_checkpoints", mapOf("sessionId" to "kept", "payload" to "opaque checkpoint", "turn" to "3"))
                db.insert("agent_background_executions", mapOf(
                    "sessionId" to "kept", "executionId" to "work", "envelope" to envelope,
                    "updatedAtEpochMs" to "18", "rowVersion" to "7", "catalogHandle" to "bg-work",
                    "catalogState" to "TERMINAL", "catalogResultVersion" to "2", "catalogAcknowledged" to "0",
                    "catalogRecoveryCandidate" to "1",
                ))
                db.insert("agent_background_origins", mapOf("sessionId" to "kept", "runId" to "run", "envelope" to "opaque origin"))
                db.insert("session_organization", mapOf(
                    "sessionId" to "kept", "incarnation" to "kept-life", "title" to "Manual title",
                    "collectionId" to "folder", "pinnedAt" to "21",
                ))
                db.insert("session_collections", mapOf(
                    "collectionId" to "folder", "name" to "Folder", "nameSortKey" to "folder",
                    "idSortKey" to "folder", "normalizedName" to "folder", "uniqueKey" to "folder", "revision" to "12",
                ))
                db.insert("session_attachment_refs", mapOf(
                    "uri" to "attachment://kept", "sessionId" to "kept", "sourceKey" to "session", "isCoverageMarker" to "0",
                ))
                mapOf(
                    "policyId" to "magrathea.default", "policyVersion" to "1", "activityMode" to "CANONICAL_TIME",
                    "revision" to "23", "generation" to "5", "sequence" to "11", "coverage" to "partial",
                    "migration.attachmentNames.scan" to "opaque control retained",
                ).forEach { (key, value) -> db.insert("catalog_state", mapOf("key" to key, "value" to value)) }
            }

            val store = JvmMagratheaRoom.open(path, StoredRecordCorruptionReporter { error("Upgrade decoded a source") })
            try {
                assertTrue(store.catalog.exists(AgentSessionId("kept")))
                assertEquals("kept-life", store.catalog.sessionIdentity(AgentSessionId("kept"))?.incarnation)
                assertEquals(CatalogCoverage.PARTIAL, store.catalog.state().coverage)
                assertEquals(23L, store.catalog.state().revision)
                assertEquals("Folder", store.catalog.collection("folder")?.name)
                assertEquals(setOf("attachment://kept"), store.catalog.referencedUris(setOf("attachment://kept")).referencedUris)
            } finally {
                store.close()
            }

            BundledSQLiteDriver().open(path).use { db ->
                assertEquals("4", db.single("PRAGMA user_version"))
                assertEquals(payload, db.single("SELECT payload FROM agent_sessions"))
                assertEquals(backup, db.single("SELECT payload FROM session_attachment_name_backups"))
                assertEquals("kept-life", db.single("SELECT incarnation FROM session_attachment_name_backups"))
                assertEquals("9", db.single("SELECT rowVersion FROM agent_sessions"))
                assertEquals("u1|u2|A:11|COMPLETED|0", db.single(
                    "SELECT catalogObservedActivityIdentity || '|' || catalogActivityIdentity || '|' || catalogActivityOrder || '|' || catalogRecoveryStatus || '|' || catalogRecoveryCandidate FROM agent_sessions",
                ))
                assertEquals("opaque checkpoint", db.single("SELECT payload FROM agent_checkpoints"))
                assertEquals("3", db.single("SELECT turn FROM agent_checkpoints"))
                assertEquals(envelope, db.single("SELECT envelope FROM agent_background_executions"))
                assertEquals("7|bg-work|TERMINAL|2|0|1", db.single(
                    "SELECT rowVersion || '|' || catalogHandle || '|' || catalogState || '|' || catalogResultVersion || '|' || catalogAcknowledged || '|' || catalogRecoveryCandidate FROM agent_background_executions",
                ))
                assertEquals("opaque origin", db.single("SELECT envelope FROM agent_background_origins"))
                assertEquals("Manual title|folder|21", db.single("SELECT title || '|' || collectionId || '|' || pinnedAt FROM session_organization"))
                assertEquals("opaque control retained", db.single("SELECT value FROM catalog_state WHERE `key`='migration.attachmentNames.scan'"))
                val sessionColumns = db.columns("agent_sessions")
                assertEquals("payload", sessionColumns.last())
                assertFalse("catalogAttachmentNameBackup" in sessionColumns)
                assertEquals("envelope", db.columns("agent_background_executions").last())
                assertEquals("ok", db.single("PRAGMA quick_check"))
            }
        } finally {
            check(directory.toFile().deleteRecursively())
        }
    }

    private fun createVersionThree(db: SQLiteConnection) {
        val relative = "schemas/saien.magrathea.storage.room.MagratheaDatabase/3.json"
        val candidates = listOf(Path.of(relative), Path.of("magrathea-storage-room").resolve(relative))
        val schema = Json.parseToJsonElement(Files.readString(candidates.first { Files.exists(it) }))
            .jsonObject.getValue("database").jsonObject
        schema.getValue("entities").jsonArray.forEach { item ->
            val entity = item.jsonObject
            val name = entity.getValue("tableName").jsonPrimitive.content
            db.execute(entity.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", name))
            entity["indices"]?.jsonArray.orEmpty().forEach { index ->
                db.execute(index.jsonObject.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", name))
            }
        }
        schema.getValue("setupQueries").jsonArray.forEach { db.execute(it.jsonPrimitive.content) }
        db.execute("PRAGMA user_version = 3")
    }

    private fun SQLiteConnection.insert(table: String, fields: Map<String, String>) {
        prepare("INSERT INTO $table (${fields.keys.joinToString { "`$it`" }}) VALUES (${fields.keys.joinToString { "?" }})").use {
            fields.values.forEachIndexed { index, value -> it.bindText(index + 1, value) }
            it.step()
        }
    }

    private fun SQLiteConnection.execute(sql: String) = prepare(sql).use { it.step() }

    private fun SQLiteConnection.single(sql: String): String = prepare(sql).use {
        check(it.step())
        it.getText(0)
    }

    private fun SQLiteConnection.columns(table: String): List<String> = prepare("PRAGMA table_info($table)").use {
        buildList { while (it.step()) add(it.getText(1)) }
    }
}
