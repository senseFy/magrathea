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

@Database(
    entities = [AgentSessionEntity::class, AgentCheckpointEntity::class],
    version = 1,
    exportSchema = true,
)
@ConstructedBy(MagratheaDatabaseConstructor::class)
internal abstract class MagratheaDatabase : RoomDatabase() {
    abstract fun sessionDao(): AgentSessionDao
    abstract fun checkpointDao(): AgentCheckpointDao
}

@Suppress("KotlinNoActualForExpect")
internal expect object MagratheaDatabaseConstructor : RoomDatabaseConstructor<MagratheaDatabase> {
    override fun initialize(): MagratheaDatabase
}
