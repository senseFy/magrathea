package saien.magrathea.storage.room

import androidx.room.RoomRawQuery
import androidx.room.deferredTransaction
import androidx.room.immediateTransaction
import androidx.room.useReaderConnection
import androidx.room.useWriterConnection
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import saien.magrathea.catalog.CatalogCoverage
import saien.magrathea.catalog.CatalogKeyEncoding
import saien.magrathea.catalog.CatalogPageDirection
import saien.magrathea.catalog.DefaultSessionCatalogProjector
import saien.magrathea.catalog.PreparedActivityIntent
import saien.magrathea.catalog.PreparedSessionInitialization
import saien.magrathea.catalog.SessionActivityFact
import saien.magrathea.catalog.SessionActivityMode
import saien.magrathea.catalog.SessionActivityOrder
import saien.magrathea.catalog.SessionActivityTicket
import saien.magrathea.catalog.SessionAttachmentReferences
import saien.magrathea.catalog.SessionCatalog
import saien.magrathea.catalog.SessionCatalogCursor
import saien.magrathea.catalog.SessionCatalogException
import saien.magrathea.catalog.SessionCatalogFailure
import saien.magrathea.catalog.SessionCatalogHeader
import saien.magrathea.catalog.SessionCatalogPage
import saien.magrathea.catalog.SessionCatalogPolicy
import saien.magrathea.catalog.SessionCatalogProjection
import saien.magrathea.catalog.SessionCatalogProjector
import saien.magrathea.catalog.SessionCatalogQuery
import saien.magrathea.catalog.SessionCatalogScope
import saien.magrathea.catalog.SessionCatalogSort
import saien.magrathea.catalog.SessionCatalogSortKey
import saien.magrathea.catalog.SessionCatalogState
import saien.magrathea.catalog.SessionCollection
import saien.magrathea.catalog.SessionCollectionCursor
import saien.magrathea.catalog.SessionCollectionPage
import saien.magrathea.catalog.SessionCollectionQuery
import saien.magrathea.catalog.SessionIdentity
import saien.magrathea.catalog.SessionOrganization
import saien.magrathea.catalog.SessionOrganizationMutation
import saien.magrathea.catalog.SessionPinFilter
import saien.magrathea.catalog.SessionRecoveryCursor
import saien.magrathea.catalog.SessionRecoveryPage
import saien.magrathea.catalog.materializeCatalogEntry
import saien.magrathea.catalog.normalizeCatalogQuery
import saien.magrathea.catalog.resolveSessionActivity
import saien.magrathea.catalog.sessionCatalogSortKey
import saien.magrathea.catalog.validateCatalogCursor
import saien.magrathea.catalog.validateCatalogPageLimit
import saien.magrathea.core.AgentSessionId
import saien.magrathea.core.AgentSessionSnapshot
import saien.magrathea.core.AgentStatus
import saien.magrathea.core.SystemIdGenerator

/** One handle owns only transient admission context; every durable query reads SQLite. */
internal class RoomSessionCatalog(
    private val database: MagratheaDatabase,
    internal val projector: SessionCatalogProjector = DefaultSessionCatalogProjector(),
) : SessionCatalog {
    private val dao = database.catalogDao()
    private val sessions = database.sessionDao()
    private val mutex = Mutex()
    private var closed = false
    private var initialized = false
    private val initializations = mutableMapOf<AgentSessionId, PreparedSessionInitialization>()
    private val intents = mutableMapOf<SessionIdentity, MutableMap<String?, PreparedActivityIntent>>()

    internal suspend fun <T> operation(
        requireMatchingPolicy: Boolean = true,
        block: suspend () -> T,
    ): T = mutex.withLock {
        if (closed) fail(SessionCatalogFailure.CLOSED)
        if (!initialized) {
            database.useWriterConnection { connection ->
                connection.immediateTransaction {
                    if (dao.state("policyId") == null) {
                        put("policyId", projector.policy.id)
                        put("policyVersion", projector.policy.version.toString())
                        put("revision", "0")
                        put("generation", "1")
                        put("sequence", "0")
                        put("activityMode", projector.activityMode.name)
                        if (dao.state("coverage") == null) {
                            put("coverage", if (sessions.hasSessions()) "incomplete" else "complete")
                        }
                    }
                    val state = readState(requireMatchingPolicy)
                    if (state.policy == projector.policy && dao.state("activityMode") == null) {
                        put("activityMode", projector.activityMode.name)
                    }
                }
            }
            initialized = true
        }
        block()
    }

    internal suspend fun close() = mutex.withLock {
        if (!closed) {
            closed = true
            initializations.clear()
            intents.clear()
            database.close()
        }
    }

    private suspend fun <T> read(block: suspend (SessionCatalogState) -> T): T = operation {
        database.useReaderConnection { connection ->
            connection.deferredTransaction { block(readState()) }
        }
    }

    private suspend fun <T> write(block: suspend () -> T): T = operation {
        database.useWriterConnection { connection ->
            connection.immediateTransaction {
                readState()
                block()
            }
        }
    }

    private suspend fun put(key: String, value: String) = dao.upsertState(CatalogStateEntity(key, value))
    private suspend fun number(key: String): Long = requireNotNull(dao.state(key)).value.toLong()
    private fun increment(value: Long): Long {
        if (value == Long.MAX_VALUE) fail(SessionCatalogFailure.COUNTER_EXHAUSTED)
        return value + 1
    }

    internal suspend fun readState(requireMatchingPolicy: Boolean = true): SessionCatalogState {
        val policy = SessionCatalogPolicy(requireNotNull(dao.state("policyId")).value, number("policyVersion").toInt())
        if (requireMatchingPolicy && policy != projector.policy) fail(SessionCatalogFailure.POLICY_MISMATCH)
        val activityMode = dao.state("activityMode")?.value
        if (requireMatchingPolicy && activityMode != null && activityMode != projector.activityMode.name) fail(SessionCatalogFailure.POLICY_MISMATCH)
        return SessionCatalogState(
            policy = policy,
            revision = number("revision"),
            generation = number("generation"),
            coverage = when (dao.state("coverage")?.value) {
                "complete" -> CatalogCoverage.COMPLETE
                "partial" -> CatalogCoverage.PARTIAL
                else -> CatalogCoverage.INDEXING
            },
        )
    }

    internal suspend fun advanceRevision(): Long {
        val next = increment(number("revision"))
        put("revision", next.toString())
        return next
    }

    override suspend fun state(): SessionCatalogState = read { it }
    override suspend fun pageRecoveryCandidates(cursor: SessionRecoveryCursor?, limit: Int): SessionRecoveryPage = read { state ->
        RoomRecoveryIndex(database).page(state, cursor, limit)
    }
    override suspend fun sessionIdentity(id: AgentSessionId): SessionIdentity? = read {
        sessions.identity(id.value)?.let { SessionIdentity(id, it.incarnation) }
    }
    override suspend fun sessionHeader(id: AgentSessionId): SessionCatalogHeader? = read { dao.header(id.value)?.header() }
    override suspend fun exists(id: AgentSessionId): Boolean = read { sessions.exists(id.value) }
    override suspend fun organization(identity: SessionIdentity): SessionOrganization? = read {
        if (!sessions.exists(identity.sessionId.value)) return@read null
        requireIdentity(identity)
        dao.organization(identity.sessionId.value)?.organization() ?: SessionOrganization()
    }
    override suspend fun collection(id: String): SessionCollection? = read { dao.collection(id)?.collection() }

    private suspend fun requireIdentity(identity: SessionIdentity): AgentSessionIdentityEntity {
        val row = sessions.identity(identity.sessionId.value) ?: fail(SessionCatalogFailure.SESSION_NOT_FOUND)
        if (row.incarnation != identity.incarnation) fail(SessionCatalogFailure.STALE_IDENTITY)
        return row
    }

    private suspend fun requirePreparedIdentity(identity: SessionIdentity) {
        val existing = sessions.identity(identity.sessionId.value)
        if (existing != null) {
            if (existing.incarnation != identity.incarnation) fail(SessionCatalogFailure.STALE_IDENTITY)
        } else if (initializations[identity.sessionId]?.identity != identity) {
            fail(SessionCatalogFailure.STALE_IDENTITY)
        }
    }

    override suspend fun prepareNewSession(
        id: AgentSessionId,
        creationToken: String,
        initialOrganization: SessionOrganization,
    ): SessionIdentity = write {
        val identity = SessionIdentity(id, creationToken)
        val prepared = PreparedSessionInitialization(identity, initialOrganization)
        val durable = sessions.identity(id.value)
        if (durable != null) {
            if (durable.incarnation != identity.incarnation) fail(SessionCatalogFailure.STALE_IDENTITY)
            return@write identity
        }
        require(creationToken != "legacy") { "The legacy incarnation is reserved for physical schema migration" }
        val current = initializations[id]
        if (current == prepared) return@write identity
        if (current != null) fail(SessionCatalogFailure.INITIALIZATION_CONFLICT)
        initializations[id] = prepared
        identity
    }

    override suspend fun prepareActivity(identity: SessionIdentity, activityIdentity: String): SessionActivityTicket = operation {
        require(projector.activityMode == SessionActivityMode.PREPARED) { "Prepared activity requires a PREPARED projection policy" }
        require(activityIdentity.isNotBlank())
        database.useWriterConnection { connection ->
            connection.immediateTransaction {
                readState()
                requirePreparedIdentity(identity)
                val old = intents[identity]?.get(activityIdentity)
                if (old is PreparedActivityIntent.Advance) return@immediateTransaction old.ticket
                if (old is PreparedActivityIntent.Preserve) fail(SessionCatalogFailure.INITIALIZATION_CONFLICT)
                val durable = sessions.identity(identity.sessionId.value)?.activity()
                val accepted = durable?.order as? SessionActivityOrder.Accepted
                if (durable?.lastAcceptedIdentity == activityIdentity && accepted != null) {
                    return@immediateTransaction SessionActivityTicket(identity, activityIdentity, accepted.sequence)
                }
                val next = increment(number("sequence"))
                put("sequence", next.toString())
                SessionActivityTicket(identity, activityIdentity, next)
            }
        }.also { ticket ->
            intents.getOrPut(identity) { mutableMapOf() }[activityIdentity] = PreparedActivityIntent.Advance(ticket)
        }
    }

    override suspend fun preparePreservedActivity(identity: SessionIdentity, targetActivityIdentity: String?) = write {
        require(projector.activityMode == SessionActivityMode.PREPARED) { "Prepared activity requires a PREPARED projection policy" }
        requireIdentity(identity)
        if (intents[identity]?.get(targetActivityIdentity) is PreparedActivityIntent.Advance) fail(SessionCatalogFailure.INITIALIZATION_CONFLICT)
        intents.getOrPut(identity) { mutableMapOf() }[targetActivityIdentity] = PreparedActivityIntent.Preserve(targetActivityIdentity)
    }

    override suspend fun releaseActivity(identity: SessionIdentity, activityIdentity: String?) = operation {
        intents[identity]?.let { pending ->
            pending.remove(activityIdentity)
            if (pending.isEmpty()) intents.remove(identity)
        }
        Unit
    }

    override suspend fun releasePreparation(identity: SessionIdentity) = operation {
        if (initializations[identity.sessionId]?.identity == identity) initializations.remove(identity.sessionId)
        intents.remove(identity)
        Unit
    }

    /** Invoked inside the canonical transaction; projection and reference extraction are already done. */
    internal suspend fun storeSnapshot(
        snapshot: AgentSessionSnapshot,
        payload: String,
        projection: SessionCatalogProjection,
        references: Set<String>,
        legacy: Pair<AgentSessionEntity, SessionCatalogProjection>? = null,
    ): SessionIdentity {
        readState()
        val old = sessions.identity(snapshot.sessionId.value)
        val prepared = initializations[snapshot.sessionId]
        val identity = SessionIdentity(snapshot.sessionId, old?.incarnation ?: prepared?.identity?.incarnation ?: SystemIdGenerator.nextId())
        if (old != null && prepared != null && prepared.identity != identity) fail(SessionCatalogFailure.STALE_IDENTITY)
        val previous = old?.activity() ?: legacy?.let { (source, oldProjection) ->
            if (old == null || source.incarnation != old.incarnation || source.rowVersion != old.rowVersion) fail(SessionCatalogFailure.STALE_IDENTITY)
            SessionActivityFact(oldProjection.activityIdentity, null, SessionActivityOrder.Legacy(oldProjection.activityAtEpochMs))
        }
        val intent = intents[identity]?.get(projection.activityIdentity)
        val activity = resolveSessionActivity(projector.activityMode, projection, previous, intent)
        var organization = dao.organization(snapshot.sessionId.value)?.takeIf { it.incarnation == identity.incarnation }?.organization()
            ?: if (old == null) prepared?.initialOrganization ?: SessionOrganization() else SessionOrganization()
        if (organization.collectionId != null && dao.collection(requireNotNull(organization.collectionId)) == null) {
            organization = organization.copy(collectionId = null)
        }
        val version = increment(old?.rowVersion ?: 0)
        val entry = materializeCatalogEntry(identity, version, snapshot, projection, organization, activity, projector)
        sessions.upsert(
            AgentSessionEntity(
                sessionId = snapshot.sessionId.value,
                payload = payload,
                updatedAtEpochMs = snapshot.updatedAtEpochMs,
                incarnation = identity.incarnation,
                rowVersion = version,
                catalogObservedActivityIdentity = activity.observedIdentity,
                catalogActivityIdentity = activity.lastAcceptedIdentity,
                catalogActivityOrder = activity.order.encoded(),
            ),
        )
        storeOrganization(identity, organization)
        dao.upsert(entry.header.entity(projection.fallbackLabel, projector.policy))
        dao.upsertSearch(
            SessionSearchEntity(
                sessionId = snapshot.sessionId.value,
                incarnation = identity.incarnation,
                rowVersion = version,
                policyId = projector.policy.id,
                policyVersion = projector.policy.version.toLong(),
                effectiveLabel = entry.normalizedTitle,
                fallbackLabel = projection.normalizedFallbackLabel,
                preview = entry.normalizedPreview,
                modelName = entry.normalizedModelName,
            ),
        )
        dao.deleteSourceReferences(snapshot.sessionId.value, "session")
        if (references.isNotEmpty()) dao.upsertReferences(references.map { SessionAttachmentRefEntity(it, snapshot.sessionId.value) })
        advanceRevision()
        return identity
    }

    /** Context is consumed only after SQLite has successfully committed. */
    internal fun committed(identity: SessionIdentity, activityIdentity: String?) {
        if (initializations[identity.sessionId]?.identity == identity) initializations.remove(identity.sessionId)
        intents[identity]?.let { pending ->
            pending.remove(activityIdentity)
            if (pending.isEmpty()) intents.remove(identity)
        }
    }

    internal suspend fun deleteRows(id: AgentSessionId, canonicalExisted: Boolean) {
        readState()
        dao.deleteHeader(id.value)
        dao.deleteSearch(id.value)
        dao.deleteReferences(id.value)
        dao.deleteOrganization(id.value)
        database.backgroundExecutionDao().deleteBySession(id.value)
        database.backgroundOriginDao().deleteBySession(id.value)
        if (canonicalExisted) advanceRevision()
    }

    internal fun deleted(id: AgentSessionId) {
        initializations.remove(id)
        intents.keys.filter { it.sessionId == id }.forEach(intents::remove)
    }

    internal suspend fun clearRows() {
        readState()
        invalidateRoomOrganizationImport(dao)
        dao.clearHeaders()
        dao.clearSearch()
        dao.clearReferences()
        dao.clearOrganization()
        database.backgroundExecutionDao().deleteAll()
        database.backgroundOriginDao().deleteAll()
        val generation = increment(number("generation"))
        val collectionsComplete = roomMaintenanceCollectionsComplete(dao)
        put("generation", generation.toString())
        put("coverage", if (collectionsComplete) "complete" else "incomplete")
        resetRoomMaintenanceProgress(dao, generation, complete = true, collectionsComplete = collectionsComplete)
        advanceRevision()
    }

    internal fun cleared() {
        initializations.clear()
        intents.clear()
    }

    /** Caller has compared the complete canonical/checkpoint rows in this writer transaction. */
    internal suspend fun writeMaintenanceProjection(
        source: AgentSessionEntity,
        checkpoint: AgentCheckpointEntity?,
        snapshot: AgentSessionSnapshot,
        rewrittenSnapshot: String?,
        rewrittenCheckpoint: String?,
        projection: SessionCatalogProjection,
        references: Set<String>,
    ): Boolean {
        val previous = source.catalogActivityOrder?.let {
            SessionActivityFact(source.catalogObservedActivityIdentity, source.catalogActivityIdentity, decodeActivityOrder(it))
        }
        if (projector.activityMode == SessionActivityMode.PREPARED && previous != null && previous.observedIdentity != projection.activityIdentity) return false
        val activity = previous ?: SessionActivityFact(projection.activityIdentity, null, SessionActivityOrder.Legacy(projection.activityAtEpochMs))
        val changesAuthority = previous == null || rewrittenSnapshot != null || rewrittenCheckpoint != null
        val version = if (changesAuthority) increment(source.rowVersion) else source.rowVersion
        val identity = SessionIdentity(snapshot.sessionId, source.incarnation)
        val organization = dao.organization(source.sessionId)?.takeIf { it.incarnation == source.incarnation }?.organization() ?: SessionOrganization()
        // Title normalization is the only host operation remaining in this short transaction.
        // Finish it before writing anything so a bad policy can be recorded as a failed row.
        val entry = try {
            materializeCatalogEntry(identity, version, snapshot, projection, organization, activity, projector)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return false
        }
        if (changesAuthority) {
            sessions.upsert(
                source.copy(
                    payload = rewrittenSnapshot ?: source.payload,
                    rowVersion = version,
                    catalogObservedActivityIdentity = activity.observedIdentity,
                    catalogActivityIdentity = activity.lastAcceptedIdentity,
                    catalogActivityOrder = activity.order.encoded(),
                ),
            )
        }
        if (checkpoint != null && rewrittenCheckpoint != null) database.checkpointDao().upsert(checkpoint.copy(payload = rewrittenCheckpoint))
        dao.upsert(entry.header.entity(projection.fallbackLabel, projector.policy))
        dao.upsertSearch(
            SessionSearchEntity(
                sessionId = source.sessionId,
                incarnation = identity.incarnation,
                rowVersion = version,
                policyId = projector.policy.id,
                policyVersion = projector.policy.version.toLong(),
                effectiveLabel = entry.normalizedTitle,
                fallbackLabel = projection.normalizedFallbackLabel,
                preview = entry.normalizedPreview,
                modelName = entry.normalizedModelName,
            ),
        )
        dao.deleteSourceReferences(source.sessionId, "session")
        if (references.isNotEmpty()) dao.upsertReferences(references.map { SessionAttachmentRefEntity(it, source.sessionId) })
        RoomRecoveryIndex(database).refreshSnapshot(snapshot)
        return true
    }

    private suspend fun storeOrganization(identity: SessionIdentity, organization: SessionOrganization) {
        if (organization.isEmpty) dao.deleteOrganization(identity.sessionId.value)
        else dao.upsertOrganization(SessionOrganizationEntity(identity.sessionId.value, identity.incarnation,
            organization.title, organization.collectionId, organization.pinnedAtEpochMs))
    }

    internal suspend fun updateOrganizationRows(identity: SessionIdentity, organization: SessionOrganization) {
        storeOrganization(identity, organization)
        val header = dao.header(identity.sessionId.value) ?: return
        val updated = header.header().copy(label = organization.title ?: header.fallbackLabel,
            collectionId = organization.collectionId, pinnedAtEpochMs = organization.pinnedAtEpochMs)
        dao.upsert(updated.entity(header.fallbackLabel, projector.policy))
        dao.search(identity.sessionId.value)?.let { search ->
            dao.upsertSearch(search.copy(effectiveLabel = organization.title?.let(projector::normalizeSearch) ?: search.fallbackLabel))
        }
    }

    override suspend fun updateOrganization(
        identity: SessionIdentity,
        mutation: SessionOrganizationMutation,
    ): SessionOrganization? = write {
        if (!sessions.exists(identity.sessionId.value)) return@write null
        requireIdentity(identity)
        val current = dao.organization(identity.sessionId.value)?.organization() ?: SessionOrganization()
        val updated = when (mutation) {
            is SessionOrganizationMutation.Title -> current.copy(title = mutation.value)
            is SessionOrganizationMutation.Pin -> current.copy(pinnedAtEpochMs = mutation.pinnedAtEpochMs)
            is SessionOrganizationMutation.Collection -> {
                if (mutation.id != null && dao.collection(requireNotNull(mutation.id)) == null) fail(SessionCatalogFailure.COLLECTION_NOT_FOUND)
                current.copy(collectionId = mutation.id)
            }
        }
        if (updated != current) {
            updateOrganizationRows(identity, updated)
            advanceRevision()
        }
        updated
    }

    override suspend fun setTitleIfAbsent(identity: SessionIdentity, title: String): Boolean = write {
        if (!sessions.exists(identity.sessionId.value)) return@write false
        requireIdentity(identity)
        val current = dao.organization(identity.sessionId.value)?.organization() ?: SessionOrganization()
        if (current.title != null) false else {
            updateOrganizationRows(identity, current.copy(title = title))
            advanceRevision()
            true
        }
    }

    override suspend fun createCollection(collection: SessionCollection) = write {
        if (dao.collection(collection.id) != null || dao.collectionByUniqueKey(collection.nameUniqueKey) != null) fail(SessionCatalogFailure.COLLECTION_CONFLICT)
        dao.upsertCollection(collection.entity(advanceRevision(), projector))
    }

    override suspend fun renameCollection(id: String, name: String, nameSortKey: String, nameUniqueKey: String) = write {
        val previous = dao.collection(id) ?: fail(SessionCatalogFailure.COLLECTION_NOT_FOUND)
        val replacement = SessionCollection(id, name, nameSortKey, nameUniqueKey)
        if (previous.collection() == replacement) return@write
        val conflict = dao.collectionByUniqueKey(nameUniqueKey)
        if (conflict != null && conflict.collectionId != id) fail(SessionCatalogFailure.COLLECTION_CONFLICT)
        dao.upsertCollection(replacement.entity(advanceRevision(), projector))
    }

    override suspend fun deleteCollection(id: String) = write {
        if (dao.collection(id) != null) {
            dao.collectionMembers(id).forEach { sessionId ->
                val organization = requireNotNull(dao.organization(sessionId))
                updateOrganizationRows(SessionIdentity(AgentSessionId(sessionId), organization.incarnation), organization.organization().copy(collectionId = null))
            }
            dao.deleteCollection(id)
            advanceRevision()
        }
        Unit
    }

    override suspend fun referencedUris(candidates: Set<String>): SessionAttachmentReferences = read { state ->
        val coverage = if (state.coverage == CatalogCoverage.COMPLETE &&
            (dao.hasUnindexedBackgroundReferences() || dao.hasUnindexedOriginReferences() || dao.hasUnindexedBackupReferences())) {
            CatalogCoverage.PARTIAL
        } else state.coverage
        SessionAttachmentReferences(candidates.toList().chunked(900).flatMap { dao.referencedUris(it) }.toSet(), coverage)
    }

    override suspend fun pageSessions(
        query: SessionCatalogQuery,
        cursor: SessionCatalogCursor?,
        direction: CatalogPageDirection,
        limit: Int,
    ): SessionCatalogPage {
        validateCatalogPageLimit(limit)
        val normalized = normalizeCatalogQuery(projector, query)
        return read { state ->
            validateCatalogCursor(cursor, normalized, state)
            val rows = sessionRows(normalized, cursor?.key, direction, limit + 1)
            val extra = rows.size > limit
            val page = rows.take(limit).let { if (direction == CatalogPageDirection.BEFORE) it.reversed() else it }
            val previous = if (direction == CatalogPageDirection.BEFORE) extra else cursor != null
            val next = if (direction == CatalogPageDirection.AFTER) extra else cursor != null
            sessionPage(page, normalized, state, previous, next, !extra)
        }
    }

    override suspend fun windowAround(id: AgentSessionId, query: SessionCatalogQuery, limit: Int): SessionCatalogPage {
        validateCatalogPageLimit(limit)
        val normalized = normalizeCatalogQuery(projector, query)
        return read { state ->
            val anchor = sessionRows(normalized, null, CatalogPageDirection.AFTER, 1, id.value).firstOrNull()
                ?: return@read SessionCatalogPage(emptyList(), null, null, true, state)
            val key = sessionCatalogSortKey(anchor.header(), normalized.sort)
            val beforeCount = (limit - 1) / 2
            val before = sessionRows(normalized, key, CatalogPageDirection.BEFORE, beforeCount + 1)
            val selectedBefore = before.take(beforeCount)
            val afterCount = limit - selectedBefore.size - 1
            val after = sessionRows(normalized, key, CatalogPageDirection.AFTER, afterCount + 1)
            sessionPage(selectedBefore.reversed() + anchor + after.take(afterCount), normalized, state,
                before.size > beforeCount, after.size > afterCount, before.size <= beforeCount && after.size <= afterCount)
        }
    }

    private fun sessionPage(
        rows: List<SessionCatalogEntity>,
        query: SessionCatalogQuery,
        state: SessionCatalogState,
        previous: Boolean,
        next: Boolean,
        exhausted: Boolean,
    ): SessionCatalogPage {
        val headers = rows.map { it.header() }
        fun cursor(header: SessionCatalogHeader) = SessionCatalogCursor(query, state.policy, state.generation, sessionCatalogSortKey(header, query.sort))
        return SessionCatalogPage(headers, if (previous) headers.firstOrNull()?.let(::cursor) else null,
            if (next) headers.lastOrNull()?.let(::cursor) else null, exhausted, state)
    }

    private suspend fun sessionRows(
        query: SessionCatalogQuery,
        key: SessionCatalogSortKey?,
        direction: CatalogPageDirection,
        limit: Int,
        id: String? = null,
    ): List<SessionCatalogEntity> {
        val predicates = mutableListOf<String>()
        val args = mutableListOf<Any>()
        when (val scope = query.scope) {
            SessionCatalogScope.All -> Unit
            SessionCatalogScope.Unassigned -> {
                predicates += "c.collectionKey = ?"
                args += CatalogKeyEncoding.collectionKey(null)
            }
            is SessionCatalogScope.Collection -> {
                predicates += "c.collectionKey = ?"
                args += CatalogKeyEncoding.collectionKey(scope.id)
            }
        }
        when (query.pin) {
            SessionPinFilter.ANY -> Unit
            SessionPinFilter.PINNED -> {
                predicates += "c.pinBucket = ?"
                args += 0
            }
            SessionPinFilter.UNPINNED -> {
                predicates += "c.pinBucket = ?"
                args += 1
            }
        }
        if (id != null) {
            predicates += "c.sessionId = ?"
            args += id
        }
        if (query.search.isNotEmpty()) {
            predicates += "EXISTS (SELECT 1 FROM session_search s WHERE s.sessionId = c.sessionId AND " +
                "(instr(s.effectiveLabel, ?) > 0 OR instr(s.preview, ?) > 0 OR instr(s.modelName, ?) > 0))"
            repeat(3) { args += query.search }
        }
        val fields = if (query.sort == SessionCatalogSort.PIN_THEN_ACTIVITY) listOf("pinBucket", "pinRank", "activityRank", "idSortKey")
            else listOf("activityRank", "idSortKey")
        if (key != null) {
            val op = if (direction == CatalogPageDirection.AFTER) ">" else "<"
            predicates += "(${fields.joinToString { "c.$it" }}) $op (${fields.joinToString { "?" }})"
            if (query.sort == SessionCatalogSort.PIN_THEN_ACTIVITY) {
                args += key.pinBucket
                args += key.pinRank
            }
            args += key.activityRank
            args += key.idSortKey
        }
        val ordering = if (direction == CatalogPageDirection.AFTER) "ASC" else "DESC"
        val where = if (predicates.isEmpty()) "" else " WHERE ${predicates.joinToString(" AND ")}"
        val sql = "SELECT c.* FROM session_catalog c$where ORDER BY ${fields.joinToString { "c.$it $ordering" }} LIMIT ?"
        args += limit
        return dao.page(boundQuery(sql, args))
    }

    override suspend fun pageCollections(
        query: SessionCollectionQuery,
        cursor: SessionCollectionCursor?,
        direction: CatalogPageDirection,
        limit: Int,
    ): SessionCollectionPage {
        validateCatalogPageLimit(limit)
        val normalized = SessionCollectionQuery(projector.normalizeSearch(query.search))
        return read { state ->
            if (cursor != null && (cursor.query != normalized || cursor.policy != state.policy || cursor.generation != state.generation)) fail(SessionCatalogFailure.INVALID_CURSOR)
            val predicates = mutableListOf<String>()
            val args = mutableListOf<Any>()
            if (normalized.search.isNotEmpty()) {
                predicates += "instr(normalizedName, ?) > 0"
                args += normalized.search
            }
            if (cursor != null) {
                predicates += "(nameSortKey, idSortKey) ${if (direction == CatalogPageDirection.AFTER) ">" else "<"} (?, ?)"
                args += cursor.nameSortKey
                args += cursor.idSortKey
            }
            val where = if (predicates.isEmpty()) "" else " WHERE ${predicates.joinToString(" AND ")}"
            val order = if (direction == CatalogPageDirection.AFTER) "ASC" else "DESC"
            args += limit + 1
            val rows = dao.pageCollections(boundQuery("SELECT * FROM session_collections$where ORDER BY nameSortKey $order, idSortKey $order LIMIT ?", args))
            val extra = rows.size > limit
            val selected = rows.take(limit).let { if (direction == CatalogPageDirection.BEFORE) it.reversed() else it }
            fun token(row: SessionCollectionEntity) = SessionCollectionCursor(normalized, state.policy, state.generation, row.nameSortKey, row.idSortKey)
            val previous = if (direction == CatalogPageDirection.BEFORE) extra else cursor != null
            val next = if (direction == CatalogPageDirection.AFTER) extra else cursor != null
            SessionCollectionPage(selected.map { it.collection() }, if (previous) selected.firstOrNull()?.let(::token) else null,
                if (next) selected.lastOrNull()?.let(::token) else null, !extra, state)
        }
    }
}

private fun fail(failure: SessionCatalogFailure): Nothing = throw SessionCatalogException(failure)

private fun boundQuery(sql: String, args: List<Any>) = RoomRawQuery(sql) { statement ->
    args.forEachIndexed { index, arg ->
        when (arg) {
            is String -> statement.bindText(index + 1, arg)
            is Int -> statement.bindLong(index + 1, arg.toLong())
            is Long -> statement.bindLong(index + 1, arg)
            else -> error("Unsupported catalog query parameter")
        }
    }
}

private fun SessionActivityOrder.encoded(): String = when (this) {
    is SessionActivityOrder.Legacy -> "L:$timestampEpochMs"
    is SessionActivityOrder.Accepted -> "A:$sequence"
}

private fun decodeActivityOrder(value: String): SessionActivityOrder = when {
    value.startsWith("A:") -> SessionActivityOrder.Accepted(value.substring(2).toLong())
    value.startsWith("L:") -> SessionActivityOrder.Legacy(value.substring(2).toLong())
    else -> error("Invalid stored catalog activity order")
}

private fun AgentSessionIdentityEntity.activity(): SessionActivityFact? = catalogActivityOrder?.let {
    SessionActivityFact(catalogObservedActivityIdentity, catalogActivityIdentity, decodeActivityOrder(it))
}

private fun SessionCatalogEntity.header() = SessionCatalogHeader(
    identity = SessionIdentity(AgentSessionId(sessionId), incarnation),
    rowVersion = rowVersion,
    label = effectiveLabel,
    previewExcerpt = previewExcerpt,
    providerId = provider,
    modelId = modelId,
    modelDisplayName = modelDisplayName,
    status = AgentStatus.valueOf(persistedStatus),
    activityAtEpochMs = activityAt,
    activity = SessionActivityFact(
        observedActivityIdentity,
        lastAcceptedActivityIdentity,
        decodeActivityOrder(activityOrder),
    ),
    collectionId = collectionId,
    pinnedAtEpochMs = pinnedAt,
)

private fun SessionCatalogHeader.entity(fallbackLabel: String, policy: SessionCatalogPolicy): SessionCatalogEntity {
    val key = sessionCatalogSortKey(this, SessionCatalogSort.PIN_THEN_ACTIVITY)
    return SessionCatalogEntity(
        sessionId = identity.sessionId.value,
        incarnation = identity.incarnation,
        rowVersion = rowVersion,
        policyId = policy.id,
        policyVersion = policy.version.toLong(),
        fallbackLabel = fallbackLabel,
        effectiveLabel = label,
        previewExcerpt = previewExcerpt,
        provider = providerId,
        modelId = modelId,
        modelDisplayName = modelDisplayName,
        persistedStatus = status.name,
        activityAt = activityAtEpochMs,
        activityOrder = activity.order.encoded(),
        observedActivityIdentity = activity.observedIdentity,
        lastAcceptedActivityIdentity = activity.lastAcceptedIdentity,
        collectionId = collectionId,
        pinnedAt = pinnedAtEpochMs,
        collectionKey = CatalogKeyEncoding.collectionKey(collectionId),
        pinBucket = key.pinBucket,
        pinRank = key.pinRank,
        activityRank = key.activityRank,
        idSortKey = key.idSortKey,
    )
}

private fun SessionOrganizationEntity.organization() = SessionOrganization(title, collectionId, pinnedAt)
private fun SessionCollectionEntity.collection() = SessionCollection(collectionId, name, nameSortKey, uniqueKey)
private fun SessionCollection.entity(revision: Long, projector: SessionCatalogProjector) = SessionCollectionEntity(
    collectionId = id,
    name = name,
    nameSortKey = nameSortKey,
    idSortKey = CatalogKeyEncoding.stringKey(id),
    normalizedName = projector.normalizeSearch(name),
    uniqueKey = nameUniqueKey,
    revision = revision,
)
