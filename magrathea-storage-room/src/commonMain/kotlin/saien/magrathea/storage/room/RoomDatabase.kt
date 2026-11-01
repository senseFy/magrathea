package saien.magrathea.storage.room

import androidx.room.ColumnInfo
import androidx.room.ConstructedBy
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor
import androidx.room.Upsert
import androidx.room.migration.Migration

@Entity(
    tableName = "agent_sessions",
    indices = [
        Index(
            name = "index_agent_sessions_catalog_identity",
            value = [
                "sessionId",
                "incarnation",
                "rowVersion",
                "catalogObservedActivityIdentity",
                "catalogActivityIdentity",
                "catalogActivityOrder",
                "catalogRecoveryStatus",
            ],
        ),
        Index(
            name = "index_agent_sessions_catalogRecoveryCandidate_sessionId",
            value = [
                "catalogRecoveryCandidate",
                "sessionId",
                "catalogRecoveryStatus",
            ],
        ),
    ],
)
internal data class AgentSessionEntity(
    @PrimaryKey val sessionId: String,
    val updatedAtEpochMs: Long,
    @ColumnInfo(defaultValue = "'legacy'") val incarnation: String = "legacy",
    @ColumnInfo(defaultValue = "0") val rowVersion: Long = 0L,
    val catalogObservedActivityIdentity: String? = null,
    val catalogActivityIdentity: String? = null,
    val catalogActivityOrder: String? = null,
    val catalogRecoveryStatus: String? = null,
    @ColumnInfo(defaultValue = "1") val catalogRecoveryCandidate: Boolean = true,
    val payload: String,
)

/** Original migration envelopes are cold data and must not enlarge every canonical row rewrite. */
@Entity(tableName = "session_attachment_name_backups")
internal data class SessionAttachmentNameBackupEntity(
    @PrimaryKey val sessionId: String,
    val incarnation: String,
    val payload: String,
)

/** Recovery queries never select a canonical envelope. Null status means the source is unknown. */
internal data class AgentSessionRecoveryEntity(
    val sessionId: String,
    val incarnation: String,
    val rowVersion: Long,
    val catalogRecoveryStatus: String?,
)

/** Canonical identity and activity authority, without loading its snapshot payload. */
internal data class AgentSessionIdentityEntity(
    val sessionId: String,
    val incarnation: String,
    val rowVersion: Long,
    val catalogObservedActivityIdentity: String?,
    val catalogActivityIdentity: String?,
    val catalogActivityOrder: String?,
)

@Entity(tableName = "agent_checkpoints")
internal data class AgentCheckpointEntity(
    @PrimaryKey val sessionId: String,
    val payload: String,
    val turn: Int,
)

@Dao
internal interface AgentSessionDao {
    @Upsert
    suspend fun upsert(entity: AgentSessionEntity)

    @Query("SELECT * FROM agent_sessions WHERE sessionId = :sessionId")
    suspend fun findById(sessionId: String): AgentSessionEntity?

    @Query("SELECT sessionId, incarnation, rowVersion, catalogObservedActivityIdentity, " +
        "catalogActivityIdentity, catalogActivityOrder FROM agent_sessions " +
        "INDEXED BY index_agent_sessions_catalog_identity WHERE sessionId = :sessionId")
    suspend fun identity(sessionId: String): AgentSessionIdentityEntity?

    @Query("SELECT sessionId, incarnation, rowVersion, catalogRecoveryStatus FROM agent_sessions " +
        "INDEXED BY index_agent_sessions_catalog_identity WHERE sessionId = :sessionId")
    suspend fun recovery(sessionId: String): AgentSessionRecoveryEntity?

    @Query("SELECT EXISTS(SELECT 1 FROM agent_sessions WHERE catalogRecoveryCandidate = 1 AND (catalogRecoveryStatus IS NULL OR " +
        "catalogRecoveryStatus NOT IN ('IDLE','RUNNING','WAITING_FOR_TOOLS','COMPLETED','FAILED','CANCELLED','INTERRUPTED')))")
    suspend fun hasUnknownRecovery(): Boolean

    @Query("SELECT sessionId FROM agent_sessions WHERE catalogRecoveryCandidate = 1 " +
        "UNION SELECT sessionId FROM agent_checkpoints " +
        "UNION SELECT sessionId FROM agent_background_executions WHERE catalogRecoveryCandidate = 1 ORDER BY sessionId LIMIT :limit")
    suspend fun recoveryCandidateIds(limit: Int): List<String>

    @Query("SELECT sessionId FROM agent_sessions WHERE catalogRecoveryCandidate = 1 AND sessionId > :afterSessionId " +
        "UNION SELECT sessionId FROM agent_checkpoints WHERE sessionId > :afterSessionId " +
        "UNION SELECT sessionId FROM agent_background_executions WHERE catalogRecoveryCandidate = 1 AND sessionId > :afterSessionId " +
        "ORDER BY sessionId LIMIT :limit")
    suspend fun recoveryCandidateIdsAfter(afterSessionId: String, limit: Int): List<String>

    @Query("SELECT EXISTS(SELECT 1 FROM agent_sessions)")
    suspend fun hasSessions(): Boolean

    @Query("SELECT * FROM agent_sessions ORDER BY updatedAtEpochMs DESC")
    suspend fun listAll(): List<AgentSessionEntity>

    @Query("SELECT sessionId FROM agent_sessions WHERE sessionId > :afterId ORDER BY sessionId LIMIT :limit")
    suspend fun pageIds(afterId: String, limit: Int): List<String>

    @Query("SELECT EXISTS(SELECT 1 FROM agent_sessions WHERE sessionId = :sessionId)")
    suspend fun exists(sessionId: String): Boolean

    @Query("DELETE FROM agent_sessions WHERE sessionId = :sessionId")
    suspend fun deleteById(sessionId: String)

    @Query("DELETE FROM agent_sessions")
    suspend fun deleteAll()
}

@Dao
internal interface AgentCheckpointDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: AgentCheckpointEntity)

    @Query("SELECT * FROM agent_checkpoints WHERE sessionId = :sessionId")
    suspend fun findById(sessionId: String): AgentCheckpointEntity?

    @Query("SELECT EXISTS(SELECT 1 FROM agent_checkpoints WHERE sessionId = :sessionId)")
    suspend fun exists(sessionId: String): Boolean

    @Query("DELETE FROM agent_checkpoints WHERE sessionId = :sessionId")
    suspend fun deleteById(sessionId: String)

    @Query("DELETE FROM agent_checkpoints")
    suspend fun deleteAll()
}

/** One durable background execution envelope keyed by (session, execution) (ADR-019). */
@Entity(
    tableName = "agent_background_executions",
    primaryKeys = ["sessionId", "executionId"],
    indices = [
        Index(
            name = "index_agent_background_executions_catalog_facts",
            value = [
                "sessionId",
                "executionId",
                "rowVersion",
                "catalogHandle",
                "catalogState",
                "catalogResultVersion",
                "catalogAcknowledged",
                "catalogRecoveryCandidate",
            ],
        ),
        Index(
            name = "index_agent_background_executions_catalogRecoveryCandidate_sessionId",
            value = [
                "catalogRecoveryCandidate",
                "sessionId",
                "catalogHandle",
                "catalogState",
                "catalogResultVersion",
                "catalogAcknowledged",
            ],
        ),
    ],
)
internal data class AgentBackgroundExecutionEntity(
    val sessionId: String,
    val executionId: String,
    val updatedAtEpochMs: Long,
    @ColumnInfo(defaultValue = "0") val rowVersion: Long = 0,
    val catalogHandle: String? = null,
    val catalogState: String? = null,
    val catalogResultVersion: Int? = null,
    val catalogAcknowledged: Boolean? = null,
    @ColumnInfo(defaultValue = "1") val catalogRecoveryCandidate: Boolean = true,
    val envelope: String,
)

internal data class AgentBackgroundRecoveryEntity(
    val sessionId: String,
    val executionId: String,
    val rowVersion: Long,
    val catalogHandle: String?,
    val catalogState: String?,
    val catalogResultVersion: Int?,
    val catalogAcknowledged: Boolean?,
    val catalogRecoveryCandidate: Boolean,
)

/** The originating request context of a handed-off Run, keyed by (session, run) (ADR-019). */
@Entity(tableName = "agent_background_origins", primaryKeys = ["sessionId", "runId"])
internal data class AgentBackgroundOriginEntity(
    val sessionId: String,
    val runId: String,
    val envelope: String,
)

@Dao
internal interface AgentBackgroundExecutionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: AgentBackgroundExecutionEntity)

    @Query("SELECT EXISTS(SELECT 1 FROM agent_background_executions)")
    suspend fun hasRecords(): Boolean

    @Query("SELECT * FROM agent_background_executions WHERE sessionId = :sessionId")
    suspend fun listBySession(sessionId: String): List<AgentBackgroundExecutionEntity>

    @Query("SELECT * FROM agent_background_executions WHERE sessionId = :sessionId AND executionId = :executionId")
    suspend fun findById(sessionId: String, executionId: String): AgentBackgroundExecutionEntity?

    @Query("SELECT sessionId, executionId, rowVersion, catalogHandle, catalogState, catalogResultVersion, " +
        "catalogAcknowledged, catalogRecoveryCandidate FROM agent_background_executions WHERE sessionId = :sessionId")
    suspend fun recoveryBySession(sessionId: String): List<AgentBackgroundRecoveryEntity>

    @Query("UPDATE agent_background_executions SET catalogHandle = :handle, catalogState = :state, " +
        "catalogResultVersion = :resultVersion, catalogAcknowledged = :acknowledged, catalogRecoveryCandidate = :candidate " +
        "WHERE sessionId = :sessionId AND executionId = :executionId")
    suspend fun updateRecovery(
        sessionId: String,
        executionId: String,
        handle: String?,
        state: String?,
        resultVersion: Int?,
        acknowledged: Boolean?,
        candidate: Boolean,
    )

    @Query("SELECT EXISTS(SELECT 1 FROM agent_background_executions WHERE catalogRecoveryCandidate = 1 AND (catalogHandle IS NULL OR catalogState IS NULL OR " +
        "catalogResultVersion IS NULL OR catalogState NOT IN ('QUEUED','RUNNING','TERMINAL')))")
    suspend fun hasUnknownRecoverySource(): Boolean

    @Query("SELECT EXISTS(SELECT 1 FROM agent_background_executions WHERE catalogRecoveryCandidate = 1 AND (catalogHandle IS NULL OR catalogState IS NULL OR " +
        "catalogResultVersion IS NULL OR catalogState NOT IN ('QUEUED','RUNNING','TERMINAL') OR " +
        "(catalogState = 'TERMINAL' AND catalogAcknowledged IS NULL)))")
    suspend fun hasUnknownRecoveryFacts(): Boolean

    @Query("SELECT * FROM agent_background_executions b WHERE ((catalogRecoveryCandidate = 1 AND " +
        "(catalogHandle IS NULL OR catalogState IS NULL OR catalogResultVersion IS NULL OR catalogState NOT IN ('QUEUED','RUNNING','TERMINAL'))) " +
        "OR NOT EXISTS(SELECT 1 FROM session_attachment_refs r WHERE r.sessionId=b.sessionId AND r.sourceKey='execution:' || b.executionId AND r.isCoverageMarker=1)) " +
        "AND (:afterSessionId IS NULL OR sessionId > :afterSessionId OR (sessionId = :afterSessionId AND executionId > :afterExecutionId)) " +
        "ORDER BY sessionId, executionId LIMIT :limit")
    suspend fun recoveryBackfillPage(afterSessionId: String?, afterExecutionId: String?, limit: Int): List<AgentBackgroundExecutionEntity>

    @Query(
        "DELETE FROM agent_background_executions " +
            "WHERE sessionId = :sessionId AND executionId = :executionId",
    )
    suspend fun deleteById(sessionId: String, executionId: String)

    @Query("DELETE FROM agent_background_executions WHERE sessionId = :sessionId")
    suspend fun deleteBySession(sessionId: String)

    @Query("DELETE FROM agent_background_executions")
    suspend fun deleteAll()
}

@Dao
internal interface AgentBackgroundOriginDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: AgentBackgroundOriginEntity)

    @Query("SELECT EXISTS(SELECT 1 FROM agent_background_origins)")
    suspend fun hasRecords(): Boolean

    @Query("SELECT * FROM agent_background_origins WHERE sessionId = :sessionId")
    suspend fun listBySession(sessionId: String): List<AgentBackgroundOriginEntity>

    @Query("SELECT * FROM agent_background_origins WHERE sessionId=:sessionId AND runId=:runId")
    suspend fun findById(sessionId: String, runId: String): AgentBackgroundOriginEntity?

    @Query("SELECT * FROM agent_background_origins b WHERE NOT EXISTS(SELECT 1 FROM session_attachment_refs r " +
        "WHERE r.sessionId=b.sessionId AND r.sourceKey='origin:' || b.runId AND r.isCoverageMarker=1) " +
        "AND (:afterSessionId IS NULL OR sessionId > :afterSessionId OR (sessionId=:afterSessionId AND runId>:afterRunId)) " +
        "ORDER BY sessionId,runId LIMIT :limit")
    suspend fun referenceBackfillPage(afterSessionId: String?, afterRunId: String?, limit: Int): List<AgentBackgroundOriginEntity>

    @Query(
        "DELETE FROM agent_background_origins WHERE sessionId = :sessionId AND runId = :runId",
    )
    suspend fun deleteById(sessionId: String, runId: String)

    @Query("DELETE FROM agent_background_origins WHERE sessionId = :sessionId")
    suspend fun deleteBySession(sessionId: String)

    @Query("DELETE FROM agent_background_origins")
    suspend fun deleteAll()
}

/** Adds the Session-owned background tables; no existing rows are touched. */
internal val MAGRATHEA_ROOM_MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(connection: androidx.sqlite.SQLiteConnection) {
        connection.prepare(
            "CREATE TABLE IF NOT EXISTS `agent_background_executions` (" +
                "`sessionId` TEXT NOT NULL, " +
                "`executionId` TEXT NOT NULL, " +
                "`envelope` TEXT NOT NULL, " +
                "`updatedAtEpochMs` INTEGER NOT NULL, " +
                "PRIMARY KEY(`sessionId`, `executionId`))",
        ).use { it.step() }
        connection.prepare(
            "CREATE TABLE IF NOT EXISTS `agent_background_origins` (" +
                "`sessionId` TEXT NOT NULL, " +
                "`runId` TEXT NOT NULL, " +
                "`envelope` TEXT NOT NULL, " +
                "PRIMARY KEY(`sessionId`, `runId`))",
        ).use { it.step() }
    }
}

@Database(
    entities = [
        AgentSessionEntity::class,
        AgentCheckpointEntity::class,
        AgentBackgroundExecutionEntity::class,
        AgentBackgroundOriginEntity::class,
        SessionCatalogEntity::class,
        SessionSearchEntity::class,
        SessionAttachmentRefEntity::class,
        SessionCollectionEntity::class,
        SessionOrganizationEntity::class,
        CatalogStateEntity::class,
        SessionAttachmentNameBackupEntity::class,
    ],
    version = 3,
    exportSchema = true,
)
@ConstructedBy(MagratheaDatabaseConstructor::class)
internal abstract class MagratheaDatabase : RoomDatabase() {
    abstract fun sessionDao(): AgentSessionDao
    abstract fun checkpointDao(): AgentCheckpointDao
    abstract fun backgroundExecutionDao(): AgentBackgroundExecutionDao
    abstract fun backgroundOriginDao(): AgentBackgroundOriginDao
    abstract fun catalogDao(): SessionCatalogDao
}

@Suppress("KotlinNoActualForExpect")
internal expect object MagratheaDatabaseConstructor : RoomDatabaseConstructor<MagratheaDatabase> {
    override fun initialize(): MagratheaDatabase
}
