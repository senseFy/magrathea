package saien.magrathea.storage.room

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection

/** Structural upgrade only: legacy payloads are decoded by resumable maintenance, never here. */
internal val MAGRATHEA_ROOM_MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(connection: SQLiteConnection) {
        val statements = listOf(
            "ALTER TABLE agent_sessions ADD COLUMN incarnation TEXT NOT NULL DEFAULT 'legacy'",
            "ALTER TABLE agent_sessions ADD COLUMN rowVersion INTEGER NOT NULL DEFAULT 0",
            "ALTER TABLE agent_sessions ADD COLUMN catalogObservedActivityIdentity TEXT",
            "ALTER TABLE agent_sessions ADD COLUMN catalogActivityIdentity TEXT",
            "ALTER TABLE agent_sessions ADD COLUMN catalogActivityOrder TEXT",
            "ALTER TABLE agent_sessions ADD COLUMN catalogRecoveryStatus TEXT",
            "ALTER TABLE agent_sessions ADD COLUMN catalogRecoveryCandidate INTEGER NOT NULL DEFAULT 1",
            "ALTER TABLE agent_sessions ADD COLUMN catalogAttachmentNameBackup TEXT",
            "CREATE INDEX IF NOT EXISTS index_agent_sessions_catalogRecoveryCandidate_sessionId ON agent_sessions(catalogRecoveryCandidate, sessionId)",
            "ALTER TABLE agent_background_executions ADD COLUMN rowVersion INTEGER NOT NULL DEFAULT 0",
            "ALTER TABLE agent_background_executions ADD COLUMN catalogHandle TEXT",
            "ALTER TABLE agent_background_executions ADD COLUMN catalogState TEXT",
            "ALTER TABLE agent_background_executions ADD COLUMN catalogResultVersion INTEGER",
            "ALTER TABLE agent_background_executions ADD COLUMN catalogAcknowledged INTEGER",
            "ALTER TABLE agent_background_executions ADD COLUMN catalogRecoveryCandidate INTEGER NOT NULL DEFAULT 1",
            "CREATE INDEX IF NOT EXISTS index_agent_background_executions_catalogRecoveryCandidate_sessionId " +
                "ON agent_background_executions(catalogRecoveryCandidate, sessionId)",
            "CREATE TABLE IF NOT EXISTS session_catalog (" +
                "sessionId TEXT NOT NULL PRIMARY KEY, incarnation TEXT NOT NULL, rowVersion INTEGER NOT NULL, " +
                "policyId TEXT NOT NULL, policyVersion INTEGER NOT NULL, fallbackLabel TEXT NOT NULL, " +
                "effectiveLabel TEXT NOT NULL, previewExcerpt TEXT NOT NULL, provider TEXT NOT NULL, " +
                "modelId TEXT NOT NULL, modelDisplayName TEXT NOT NULL, persistedStatus TEXT NOT NULL, " +
                "activityAt INTEGER NOT NULL, activityOrder TEXT NOT NULL, observedActivityIdentity TEXT, " +
                "lastAcceptedActivityIdentity TEXT, collectionId TEXT, pinnedAt INTEGER, " +
                "collectionKey TEXT NOT NULL, pinBucket INTEGER NOT NULL, pinRank TEXT NOT NULL, " +
                "activityRank TEXT NOT NULL, idSortKey TEXT NOT NULL)",
            "CREATE TABLE IF NOT EXISTS session_search (" +
                "sessionId TEXT NOT NULL PRIMARY KEY, incarnation TEXT NOT NULL, rowVersion INTEGER NOT NULL, " +
                "policyId TEXT NOT NULL, policyVersion INTEGER NOT NULL, effectiveLabel TEXT NOT NULL, fallbackLabel TEXT NOT NULL, " +
                "preview TEXT NOT NULL, modelName TEXT NOT NULL)",
            "CREATE TABLE IF NOT EXISTS session_attachment_refs (" +
                "uri TEXT NOT NULL, sessionId TEXT NOT NULL, sourceKey TEXT NOT NULL, isCoverageMarker INTEGER NOT NULL, " +
                "PRIMARY KEY(uri, sessionId, sourceKey, isCoverageMarker))",
            "CREATE INDEX IF NOT EXISTS index_session_attachment_refs_sessionId_sourceKey_isCoverageMarker " +
                "ON session_attachment_refs(sessionId, sourceKey, isCoverageMarker)",
            "CREATE TABLE IF NOT EXISTS session_collections (" +
                "collectionId TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, nameSortKey TEXT NOT NULL, " +
                "idSortKey TEXT NOT NULL, normalizedName TEXT NOT NULL, uniqueKey TEXT NOT NULL, revision INTEGER NOT NULL)",
            "CREATE INDEX IF NOT EXISTS index_session_collections_nameSortKey_idSortKey " +
                "ON session_collections(nameSortKey, idSortKey)",
            "CREATE UNIQUE INDEX IF NOT EXISTS index_session_collections_uniqueKey ON session_collections(uniqueKey)",
            "CREATE TABLE IF NOT EXISTS session_organization (" +
                "sessionId TEXT NOT NULL PRIMARY KEY, incarnation TEXT NOT NULL, title TEXT, collectionId TEXT, pinnedAt INTEGER)",
            "CREATE INDEX IF NOT EXISTS index_session_organization_collectionId ON session_organization(collectionId)",
            "CREATE TABLE IF NOT EXISTS catalog_state (`key` TEXT NOT NULL PRIMARY KEY, value TEXT NOT NULL)",
            "INSERT INTO catalog_state (`key`, value) VALUES ('coverage', 'incomplete')",
        )
        val indexes = listOf(
            listOf("pinBucket", "pinRank", "activityRank", "idSortKey"),
            listOf("pinBucket", "activityRank", "idSortKey"),
            listOf("collectionKey", "pinBucket", "pinRank", "activityRank", "idSortKey"),
            listOf("collectionKey", "pinBucket", "activityRank", "idSortKey"),
        ).map { columns ->
            "CREATE INDEX IF NOT EXISTS index_session_catalog_${columns.joinToString("_")} " +
                "ON session_catalog(${columns.joinToString(", ")})"
        }
        (statements + indexes).forEach { sql -> connection.prepare(sql).use { it.step() } }
    }
}
