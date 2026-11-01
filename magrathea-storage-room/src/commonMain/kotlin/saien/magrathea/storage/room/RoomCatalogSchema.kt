package saien.magrathea.storage.room

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.RoomRawQuery
import androidx.room.Upsert

/** Small, disposable query rows. Canonical payloads and full search text never enter this table. */
@Entity(
    tableName = "session_catalog",
    indices = [
        Index(value = ["pinBucket", "pinRank", "activityRank", "idSortKey"]),
        Index(value = ["pinBucket", "activityRank", "idSortKey"]),
        Index(value = ["collectionKey", "pinBucket", "pinRank", "activityRank", "idSortKey"]),
        Index(value = ["collectionKey", "pinBucket", "activityRank", "idSortKey"]),
    ],
)
internal data class SessionCatalogEntity(
    @PrimaryKey val sessionId: String,
    val incarnation: String,
    val rowVersion: Long,
    val policyId: String,
    val policyVersion: Long,
    val fallbackLabel: String,
    val effectiveLabel: String,
    val previewExcerpt: String,
    val provider: String,
    val modelId: String,
    val modelDisplayName: String,
    val persistedStatus: String,
    val activityAt: Long,
    val activityOrder: String,
    val observedActivityIdentity: String?,
    val lastAcceptedActivityIdentity: String?,
    val collectionId: String?,
    val pinnedAt: Long?,
    val collectionKey: String,
    val pinBucket: Int,
    val pinRank: String,
    val activityRank: String,
    val idSortKey: String,
)

@Entity(tableName = "session_search")
internal data class SessionSearchEntity(
    @PrimaryKey val sessionId: String,
    val incarnation: String,
    val rowVersion: Long,
    val policyId: String,
    val policyVersion: Long,
    val effectiveLabel: String,
    val fallbackLabel: String,
    val preview: String,
    val modelName: String,
)

@Entity(
    tableName = "session_attachment_refs",
    primaryKeys = ["uri", "sessionId", "sourceKey", "isCoverageMarker"],
    indices = [Index(value = ["sessionId", "sourceKey", "isCoverageMarker"])],
)
internal data class SessionAttachmentRefEntity(
    val uri: String,
    val sessionId: String,
    val sourceKey: String = "session",
    val isCoverageMarker: Boolean = false,
)

@Entity(
    tableName = "session_collections",
    indices = [Index(value = ["nameSortKey", "idSortKey"]), Index(value = ["uniqueKey"], unique = true)],
)
internal data class SessionCollectionEntity(
    @PrimaryKey val collectionId: String,
    val name: String,
    val nameSortKey: String,
    val idSortKey: String,
    val normalizedName: String,
    val uniqueKey: String,
    val revision: Long,
)

@Entity(tableName = "session_organization", indices = [Index(value = ["collectionId"])])
internal data class SessionOrganizationEntity(
    @PrimaryKey val sessionId: String,
    val incarnation: String,
    val title: String?,
    val collectionId: String?,
    val pinnedAt: Long?,
)

/** Only backend-defined control keys are allowed; this is not a public key/value API. */
@Entity(tableName = "catalog_state")
internal data class CatalogStateEntity(
    @PrimaryKey val key: String,
    val value: String,
)

@Dao
internal interface SessionCatalogDao {
    @Upsert
    suspend fun upsert(entity: SessionCatalogEntity)

    @Upsert
    suspend fun upsertSearch(entity: SessionSearchEntity)

    @Upsert
    suspend fun upsertReferences(entities: List<SessionAttachmentRefEntity>)

    @Upsert
    suspend fun upsertCollection(entity: SessionCollectionEntity)

    @Upsert
    suspend fun upsertOrganization(entity: SessionOrganizationEntity)

    @Upsert
    suspend fun upsertState(entity: CatalogStateEntity)

    @Upsert
    suspend fun upsertAttachmentNameBackup(entity: SessionAttachmentNameBackupEntity)

    @Query("SELECT * FROM session_attachment_name_backups WHERE sessionId = :sessionId")
    suspend fun attachmentNameBackup(sessionId: String): SessionAttachmentNameBackupEntity?

    @Query("DELETE FROM session_attachment_name_backups WHERE sessionId = :sessionId")
    suspend fun deleteAttachmentNameBackup(sessionId: String)

    @Query("DELETE FROM session_attachment_name_backups")
    suspend fun clearAttachmentNameBackups()

    @Query("SELECT * FROM session_catalog WHERE sessionId = :sessionId")
    suspend fun header(sessionId: String): SessionCatalogEntity?

    @Query("SELECT * FROM session_search WHERE sessionId = :sessionId")
    suspend fun search(sessionId: String): SessionSearchEntity?

    @Query("SELECT * FROM session_organization WHERE sessionId = :sessionId")
    suspend fun organization(sessionId: String): SessionOrganizationEntity?

    @Query("SELECT * FROM session_collections WHERE collectionId = :collectionId")
    suspend fun collection(collectionId: String): SessionCollectionEntity?

    @Query("SELECT * FROM session_collections WHERE uniqueKey = :uniqueKey")
    suspend fun collectionByUniqueKey(uniqueKey: String): SessionCollectionEntity?

    @Query("SELECT * FROM catalog_state WHERE `key` = :key")
    suspend fun state(key: String): CatalogStateEntity?

    @Query("DELETE FROM catalog_state WHERE `key` = :key")
    suspend fun deleteState(key: String)

    @Query("SELECT sessionId FROM agent_sessions ORDER BY sessionId LIMIT :limit")
    suspend fun firstMaintenanceSessionIds(limit: Int): List<String>

    @Query("SELECT sessionId FROM agent_sessions WHERE sessionId > :afterId ORDER BY sessionId LIMIT :limit")
    suspend fun maintenanceSessionIds(afterId: String, limit: Int): List<String>

    // Compare with the same SQLite BINARY collation as the physical scan, not Kotlin UTF-16 order.
    @Query("SELECT NOT EXISTS(SELECT 1 FROM agent_sessions WHERE sessionId < :targetId)")
    suspend fun canAdvanceFirstAttachmentNameSource(targetId: String): Boolean

    @Query("SELECT :targetId > :afterId AND NOT EXISTS(SELECT 1 FROM agent_sessions WHERE sessionId > :afterId AND sessionId < :targetId)")
    suspend fun canAdvanceAttachmentNameSource(afterId: String, targetId: String): Boolean

    @Query("SELECT collectionId FROM session_collections ORDER BY collectionId LIMIT :limit")
    suspend fun firstMaintenanceCollectionIds(limit: Int): List<String>

    @Query("SELECT collectionId FROM session_collections WHERE collectionId > :afterId ORDER BY collectionId LIMIT :limit")
    suspend fun maintenanceCollectionIds(afterId: String, limit: Int): List<String>

    @RawQuery
    suspend fun page(query: RoomRawQuery): List<SessionCatalogEntity>

    @RawQuery
    suspend fun pageCollections(query: RoomRawQuery): List<SessionCollectionEntity>

    @Query("SELECT DISTINCT uri FROM session_attachment_refs WHERE uri IN (:candidates) AND isCoverageMarker = 0")
    suspend fun referencedUris(candidates: List<String>): List<String>

    @Query("SELECT EXISTS(SELECT 1 FROM agent_background_executions b WHERE NOT EXISTS(" +
        "SELECT 1 FROM session_attachment_refs r WHERE r.sessionId=b.sessionId AND r.sourceKey='execution:' || b.executionId AND r.isCoverageMarker=1))")
    suspend fun hasUnindexedBackgroundReferences(): Boolean

    @Query("SELECT EXISTS(SELECT 1 FROM agent_background_origins b WHERE NOT EXISTS(" +
        "SELECT 1 FROM session_attachment_refs r WHERE r.sessionId=b.sessionId AND r.sourceKey='origin:' || b.runId AND r.isCoverageMarker=1))")
    suspend fun hasUnindexedOriginReferences(): Boolean

    @Query("SELECT EXISTS(SELECT 1 FROM session_attachment_name_backups s WHERE NOT EXISTS(" +
        "SELECT 1 FROM session_attachment_refs r WHERE r.sessionId=s.sessionId AND r.sourceKey='attachment-name-backup' AND r.isCoverageMarker=1))")
    suspend fun hasUnindexedBackupReferences(): Boolean

    @Query("SELECT sessionId FROM session_organization WHERE collectionId = :collectionId")
    suspend fun collectionMembers(collectionId: String): List<String>

    @Query("DELETE FROM session_catalog WHERE sessionId = :sessionId")
    suspend fun deleteHeader(sessionId: String)
    @Query("DELETE FROM session_search WHERE sessionId = :sessionId")
    suspend fun deleteSearch(sessionId: String)
    @Query("DELETE FROM session_attachment_refs WHERE sessionId = :sessionId")
    suspend fun deleteReferences(sessionId: String)
    @Query("DELETE FROM session_attachment_refs WHERE sessionId = :sessionId AND sourceKey = :sourceKey")
    suspend fun deleteSourceReferences(sessionId: String, sourceKey: String)
    @Query("DELETE FROM session_attachment_refs WHERE sessionId = :sessionId AND sourceKey != 'session' AND sourceKey != 'attachment-name-backup'")
    suspend fun deleteBackgroundReferences(sessionId: String)
    @Query("DELETE FROM session_attachment_refs WHERE sourceKey != 'session' AND sourceKey != 'attachment-name-backup'")
    suspend fun clearBackgroundReferences()
    @Query("DELETE FROM session_organization WHERE sessionId = :sessionId")
    suspend fun deleteOrganization(sessionId: String)
    @Query("DELETE FROM session_collections WHERE collectionId = :collectionId")
    suspend fun deleteCollection(collectionId: String)

    @Query("DELETE FROM session_catalog")
    suspend fun clearHeaders()

    @Query("DELETE FROM session_search")
    suspend fun clearSearch()

    @Query("DELETE FROM session_attachment_refs")
    suspend fun clearReferences()

    @Query("DELETE FROM session_organization")
    suspend fun clearOrganization()
}
