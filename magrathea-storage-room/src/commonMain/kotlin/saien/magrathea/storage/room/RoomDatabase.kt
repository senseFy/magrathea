package saien.magrathea.storage.room

import androidx.room.ConstructedBy
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor
import androidx.room.migration.Migration

@Entity(tableName = "agent_sessions")
internal data class AgentSessionEntity(
    @PrimaryKey val sessionId: String,
    val payload: String,
    val updatedAtEpochMs: Long,
)

@Entity(tableName = "agent_checkpoints")
internal data class AgentCheckpointEntity(
    @PrimaryKey val sessionId: String,
    val payload: String,
    val turn: Int,
)

@Dao
internal interface AgentSessionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: AgentSessionEntity)

    @Query("SELECT * FROM agent_sessions WHERE sessionId = :sessionId")
    suspend fun findById(sessionId: String): AgentSessionEntity?

    @Query("SELECT * FROM agent_sessions ORDER BY updatedAtEpochMs DESC")
    suspend fun listAll(): List<AgentSessionEntity>

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

    @Query("DELETE FROM agent_checkpoints WHERE sessionId = :sessionId")
    suspend fun deleteById(sessionId: String)

    @Query("DELETE FROM agent_checkpoints")
    suspend fun deleteAll()
}

/** One durable background execution envelope keyed by (session, execution) (ADR-019). */
@Entity(tableName = "agent_background_executions", primaryKeys = ["sessionId", "executionId"])
internal data class AgentBackgroundExecutionEntity(
    val sessionId: String,
    val executionId: String,
    val envelope: String,
    val updatedAtEpochMs: Long,
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

    @Query("SELECT * FROM agent_background_executions WHERE sessionId = :sessionId")
    suspend fun listBySession(sessionId: String): List<AgentBackgroundExecutionEntity>

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

    @Query("SELECT * FROM agent_background_origins WHERE sessionId = :sessionId")
    suspend fun listBySession(sessionId: String): List<AgentBackgroundOriginEntity>

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
    ],
    version = 2,
    exportSchema = true,
)
@ConstructedBy(MagratheaDatabaseConstructor::class)
internal abstract class MagratheaDatabase : RoomDatabase() {
    abstract fun sessionDao(): AgentSessionDao
    abstract fun checkpointDao(): AgentCheckpointDao
    abstract fun backgroundExecutionDao(): AgentBackgroundExecutionDao
    abstract fun backgroundOriginDao(): AgentBackgroundOriginDao
}

@Suppress("KotlinNoActualForExpect")
internal expect object MagratheaDatabaseConstructor : RoomDatabaseConstructor<MagratheaDatabase> {
    override fun initialize(): MagratheaDatabase
}
