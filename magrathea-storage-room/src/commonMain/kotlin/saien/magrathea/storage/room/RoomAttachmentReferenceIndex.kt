package saien.magrathea.storage.room

import androidx.room.deferredTransaction
import androidx.room.immediateTransaction
import androidx.room.useReaderConnection
import androidx.room.useWriterConnection
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import saien.magrathea.catalog.MAX_CATALOG_MAINTENANCE_BATCH_SIZE
import saien.magrathea.catalog.SessionCatalogException
import saien.magrathea.catalog.SessionCatalogFailure
import saien.magrathea.catalog.SessionCatalogMaintenanceIssue
import saien.magrathea.catalog.SessionCatalogMaintenanceIssueKind
import saien.magrathea.catalog.SessionCatalogPolicy
import saien.magrathea.catalog.extractBackgroundOriginAttachmentUris
import saien.magrathea.core.AgentSessionId
import saien.magrathea.core.BackgroundOriginContextCodec
import saien.magrathea.core.StoredEnvelopeDecodeException
import saien.magrathea.core.StoredEnvelopeDecodeFailure

internal const val ATTACHMENT_NAME_BACKUP_SOURCE = "attachment-name-backup"
internal fun executionReferenceSource(executionId: String) = "execution:$executionId"
internal fun originReferenceSource(runId: String) = "origin:$runId"

/** Source-scoped rows prevent replacing canonical references from deleting a background result's references. */
internal class RoomAttachmentReferenceIndex(private val database: MagratheaDatabase, json: Json = Json) {
    private val dao = database.catalogDao()
    private val origins = database.backgroundOriginDao()
    private val originCodec = BackgroundOriginContextCodec(json)

    /** Call in the same writer transaction as the source. An empty known source still needs its marker. */
    suspend fun replace(sessionId: String, sourceKey: String, uris: Set<String>) {
        dao.deleteSourceReferences(sessionId, sourceKey)
        dao.upsertReferences(
            uris.map { SessionAttachmentRefEntity(it, sessionId, sourceKey) } +
                SessionAttachmentRefEntity("", sessionId, sourceKey, isCoverageMarker = true),
        )
    }

    suspend fun originBackfillComplete(): Boolean {
        if (!dao.hasUnindexedOriginReferences()) return true
        return dao.state(GENERATION)?.value == dao.state("generation")?.value && dao.state(COMPLETE)?.value == "true"
    }

    suspend fun backfillOrigins(
        limit: Int,
        expectedPolicy: SessionCatalogPolicy,
        expectedGeneration: Long,
    ): RoomBackgroundRecoveryBackfillResult {
        require(limit in 1..MAX_CATALOG_MAINTENANCE_BATCH_SIZE)
        var processed = 0
        var indexed = 0
        var skipped = 0
        val issues = mutableListOf<SessionCatalogMaintenanceIssue>()
        while (processed < limit) {
            val captured = database.useReaderConnection { connection ->
                connection.deferredTransaction {
                    requireState(expectedPolicy, expectedGeneration)
                    val position = position(expectedGeneration)
                    position to if (position.complete) null else {
                        origins.referenceBackfillPage(position.afterSession, position.afterRun, 1).firstOrNull()
                    }
                }
            }
            if (captured.first.complete) break
            val source = captured.second
            if (source == null) {
                database.useWriterConnection { connection ->
                    connection.immediateTransaction {
                        requireState(expectedPolicy, expectedGeneration)
                        if (position(expectedGeneration) == captured.first) save(captured.first.copy(complete = true))
                    }
                }
                break
            }
            var issue: SessionCatalogMaintenanceIssue? = null
            val uris = try {
                val decoded = originCodec.decodeResult(source.envelope).value
                require(decoded.sessionId.value == source.sessionId && decoded.runId.value == source.runId)
                extractBackgroundOriginAttachmentUris(decoded)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                issue = SessionCatalogMaintenanceIssue(
                    AgentSessionId(source.sessionId),
                    if (failure is StoredEnvelopeDecodeException && failure.failure != StoredEnvelopeDecodeFailure.CORRUPT)
                        SessionCatalogMaintenanceIssueKind.INCOMPATIBLE_SCHEMA else SessionCatalogMaintenanceIssueKind.UNREADABLE,
                )
                null
            }
            processed++
            val applied = database.useWriterConnection { connection ->
                connection.immediateTransaction {
                    requireState(expectedPolicy, expectedGeneration)
                    if (position(expectedGeneration) != captured.first || origins.findById(source.sessionId, source.runId) != source) {
                        false
                    } else {
                        if (uris != null) {
                            replace(source.sessionId, originReferenceSource(source.runId), uris)
                            advanceRevision()
                        }
                        save(captured.first.copy(afterSession = source.sessionId, afterRun = source.runId))
                        true
                    }
                }
            }
            if (!applied) {
                skipped++
                break
            }
            if (uris != null) indexed++
            issue?.let(issues::add)
        }
        val complete = database.useWriterConnection { connection ->
            connection.immediateTransaction {
                requireState(expectedPolicy, expectedGeneration)
                val position = position(expectedGeneration)
                if (!position.complete && origins.referenceBackfillPage(position.afterSession, position.afterRun, 1).isEmpty()) {
                    save(position.copy(complete = true))
                }
                originBackfillComplete()
            }
        }
        return RoomBackgroundRecoveryBackfillResult(processed, indexed, skipped, complete, issues)
    }
    private data class Position(
        val generation: Long,
        val afterSession: String? = null,
        val afterRun: String? = null,
        val complete: Boolean = false,
    )

    private suspend fun position(generation: Long): Position {
        if (dao.state(GENERATION)?.value != generation.toString()) return Position(generation)
        return Position(generation, dao.state(AFTER_SESSION)?.value, dao.state(AFTER_RUN)?.value, dao.state(COMPLETE)?.value == "true")
    }
    private suspend fun save(position: Position) {
        dao.upsertState(CatalogStateEntity(GENERATION, position.generation.toString()))
        if (position.afterSession == null) dao.deleteState(AFTER_SESSION) else dao.upsertState(CatalogStateEntity(AFTER_SESSION, position.afterSession))
        if (position.afterRun == null) dao.deleteState(AFTER_RUN) else dao.upsertState(CatalogStateEntity(AFTER_RUN, position.afterRun))
        dao.upsertState(CatalogStateEntity(COMPLETE, position.complete.toString()))
    }
    private suspend fun requireState(policy: SessionCatalogPolicy, generation: Long) {
        if (dao.state("policyId")?.value != policy.id || dao.state("policyVersion")?.value != policy.version.toString() ||
            dao.state("generation")?.value != generation.toString()) throw SessionCatalogException(SessionCatalogFailure.STALE_MAINTENANCE)
    }
    private suspend fun advanceRevision() {
        val value = requireNotNull(dao.state("revision")).value.toLong()
        if (value == Long.MAX_VALUE) throw SessionCatalogException(SessionCatalogFailure.COUNTER_EXHAUSTED)
        dao.upsertState(CatalogStateEntity("revision", (value + 1).toString()))
    }
    private companion object {
        const val GENERATION = "references.origins.generation"
        const val AFTER_SESSION = "references.origins.afterSession"
        const val AFTER_RUN = "references.origins.afterRun"
        const val COMPLETE = "references.origins.complete"
    }
}
