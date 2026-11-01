package saien.magrathea.storage.room

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection

/** Keep scalar query fields before overflow payloads and move cold backups out of hot rows. */
internal val MAGRATHEA_ROOM_MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(connection: SQLiteConnection) {
        val statements = listOf(
            "CREATE TABLE session_attachment_name_backups (" +
                "sessionId TEXT NOT NULL PRIMARY KEY, incarnation TEXT NOT NULL, payload TEXT NOT NULL)",
            "INSERT INTO session_attachment_name_backups (sessionId, incarnation, payload) " +
                "SELECT sessionId, incarnation, catalogAttachmentNameBackup FROM agent_sessions " +
                "WHERE catalogAttachmentNameBackup IS NOT NULL",
            "CREATE TABLE agent_sessions_v4 (" +
                "sessionId TEXT NOT NULL PRIMARY KEY, updatedAtEpochMs INTEGER NOT NULL, " +
                "incarnation TEXT NOT NULL DEFAULT 'legacy', rowVersion INTEGER NOT NULL DEFAULT 0, " +
                "catalogObservedActivityIdentity TEXT, catalogActivityIdentity TEXT, catalogActivityOrder TEXT, " +
                "catalogRecoveryStatus TEXT, catalogRecoveryCandidate INTEGER NOT NULL DEFAULT 1, payload TEXT NOT NULL)",
            "INSERT INTO agent_sessions_v4 (sessionId, updatedAtEpochMs, incarnation, rowVersion, " +
                "catalogObservedActivityIdentity, catalogActivityIdentity, catalogActivityOrder, " +
                "catalogRecoveryStatus, catalogRecoveryCandidate, payload) " +
                "SELECT sessionId, updatedAtEpochMs, incarnation, rowVersion, catalogObservedActivityIdentity, " +
                "catalogActivityIdentity, catalogActivityOrder, catalogRecoveryStatus, catalogRecoveryCandidate, payload " +
                "FROM agent_sessions",
            "DROP TABLE agent_sessions",
            "ALTER TABLE agent_sessions_v4 RENAME TO agent_sessions",
            "CREATE INDEX index_agent_sessions_catalogRecoveryCandidate_sessionId " +
                "ON agent_sessions(catalogRecoveryCandidate, sessionId)",
            "CREATE TABLE agent_background_executions_v4 (" +
                "sessionId TEXT NOT NULL, executionId TEXT NOT NULL, updatedAtEpochMs INTEGER NOT NULL, " +
                "rowVersion INTEGER NOT NULL DEFAULT 0, catalogHandle TEXT, catalogState TEXT, " +
                "catalogResultVersion INTEGER, catalogAcknowledged INTEGER, " +
                "catalogRecoveryCandidate INTEGER NOT NULL DEFAULT 1, envelope TEXT NOT NULL, " +
                "PRIMARY KEY(sessionId, executionId))",
            "INSERT INTO agent_background_executions_v4 (sessionId, executionId, updatedAtEpochMs, rowVersion, " +
                "catalogHandle, catalogState, catalogResultVersion, catalogAcknowledged, catalogRecoveryCandidate, envelope) " +
                "SELECT sessionId, executionId, updatedAtEpochMs, rowVersion, catalogHandle, catalogState, " +
                "catalogResultVersion, catalogAcknowledged, catalogRecoveryCandidate, envelope FROM agent_background_executions",
            "DROP TABLE agent_background_executions",
            "ALTER TABLE agent_background_executions_v4 RENAME TO agent_background_executions",
            "CREATE INDEX index_agent_background_executions_catalogRecoveryCandidate_sessionId " +
                "ON agent_background_executions(catalogRecoveryCandidate, sessionId)",
        )
        statements.forEach { sql -> connection.prepare(sql).use { it.step() } }
    }
}
