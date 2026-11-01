package saien.magrathea.catalog

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import saien.magrathea.core.AgentCheckpoint
import saien.magrathea.core.AgentCheckpointCodec
import saien.magrathea.core.AgentPersistence
import saien.magrathea.core.AgentPersistenceRecord
import saien.magrathea.core.AgentSessionId
import saien.magrathea.core.AgentSessionSnapshot
import saien.magrathea.core.AgentSessionSnapshotCodec
import saien.magrathea.core.SystemIdGenerator

/**
 * Atomic reference implementation for headless consumers and contract tests. Its lifetime is purely
 * in memory; production adapters must use their own indexed storage, not this class as a cache.
 * Projection runs before the write lock. Canonical records and their authoritative sidecars are
 * separate from disposable derived entries; page reads never project or decode canonical records.
 * Callers must fence and drain runtime writers before delete, clear, releasePreparation or close.
 */
class InMemorySessionCatalogStore(
    private var projector: SessionCatalogProjector = DefaultSessionCatalogProjector(),
) {
    private data class StoredSession(
        val identity: SessionIdentity,
        val rowVersion: Long,
        val record: AgentPersistenceRecord,
        val activity: SessionActivityFact,
        val projection: SessionCatalogProjection,
        val attachmentUris: Set<String>,
    )

    private data class IntentKey(val identity: SessionIdentity, val activityIdentity: String?)

    private val mutex = Mutex()
    private val maintenanceMutex = Mutex()
    private val sessions = mutableMapOf<AgentSessionId, StoredSession>()
    private val organizations = mutableMapOf<AgentSessionId, SessionOrganization>()
    private val collections = mutableMapOf<String, SessionCollection>()
    private val entries = mutableMapOf<AgentSessionId, SessionCatalogEntry>()
    private val sortedPins = mutableListOf<SessionCatalogEntry>()
    private val sortedActivity = mutableListOf<SessionCatalogEntry>()
    private val sortedCollections = mutableListOf<SessionCollection>()
    private val references = mutableMapOf<String, MutableSet<AgentSessionId>>()
    private val recoveryCandidates = mutableMapOf<AgentSessionId, SessionRecoveryCandidate>()
    private val initializations = mutableMapOf<AgentSessionId, PreparedSessionInitialization>()
    private val intents = mutableMapOf<IntentKey, PreparedActivityIntent>()
    private var activityCounter = 0L
    private var revision = 0L
    private var generation = 0L
    private var closed = false
    private var coverage = CatalogCoverage.COMPLETE
    private var maintenanceCursor: AgentSessionId? = null
    private var maintenanceProcessed = 0L
    private var maintenanceFailed = 0L
    private var maintenanceComplete = true
    private val migrationStoreId = SystemIdGenerator.nextId()
    private var migrationLifecycle = 0L
    private var importReceipt: OrganizationImportReceipt? = null
    private var importLastBatch: String? = null
    private var nameScanProgress: SessionAttachmentNameScanProgress? = null
    private val attachmentBackups = mutableMapOf<AgentSessionId, SessionAttachmentNameBackup>()
    private val attachmentBackupReferences = mutableMapOf<AgentSessionId, Set<String>>()

    val persistence: AgentPersistence = object : AgentPersistence {
        override suspend fun commit(snapshot: AgentSessionSnapshot, checkpoint: AgentCheckpoint?) {
            val record = AgentPersistenceRecord(snapshot, checkpoint)
            val projectionPolicy = projector
            val projection = projectionPolicy.project(snapshot)
            val attachmentUris = extractSessionAttachmentUris(snapshot, checkpoint)
            mutex.withLock {
                requireOpen()
                if (projectionPolicy !== projector) fail(SessionCatalogFailure.POLICY_MISMATCH)
                val id = snapshot.sessionId
                val previous = sessions[id]
                val initialization = initializations[id]
                val identity = previous?.identity ?: initialization?.identity
                    ?: SessionIdentity(id, SystemIdGenerator.nextId())
                val intentKey = IntentKey(identity, projection.activityIdentity)
                val intent = intents[intentKey]
                val activity = resolveSessionActivity(
                    projectionPolicy.activityMode,
                    projection,
                    previous?.activity,
                    intent,
                )
                val organization = if (previous != null) {
                    organizations[id] ?: SessionOrganization()
                } else {
                    (initialization?.initialOrganization ?: SessionOrganization()).let {
                        if (it.collectionId != null && !collections.containsKey(it.collectionId)) {
                            it.copy(collectionId = null)
                        } else {
                            it
                        }
                    }
                }
                val rowVersion = increment(previous?.rowVersion ?: 0)
                val nextRevision = increment(revision)
                val entry = materializeCatalogEntry(
                    identity,
                    rowVersion,
                    snapshot,
                    projection,
                    organization,
                    activity,
                    projectionPolicy,
                )
                val allReferences = attachmentUris + attachmentBackupReferences[id].orEmpty()
                val stored = StoredSession(identity, rowVersion, record, activity, projection, allReferences)

                // Nothing above this line mutates the transaction's authoritative or derived state.
                previous?.attachmentUris?.forEach { removeReference(it, id) }
                sessions[id] = stored
                val recoveryReasons = sessionRecoveryReasons(snapshot.state.status, checkpoint != null)
                if (recoveryReasons.isEmpty()) {
                    recoveryCandidates.remove(id)
                } else {
                    recoveryCandidates[id] = SessionRecoveryCandidate(id, identity, recoveryReasons)
                }
                storeOrganization(id, organization)
                allReferences.forEach { references.getOrPut(it) { mutableSetOf() }.add(id) }
                replaceEntry(entry)
                revision = nextRevision
                initializations.remove(id)
                intents.remove(intentKey)
            }
        }

        override suspend fun load(sessionId: AgentSessionId): AgentPersistenceRecord? = mutex.withLock {
            requireOpen()
            sessions[sessionId]?.record
        }

        override suspend fun listSessions(): List<AgentSessionSnapshot> = mutex.withLock {
            requireOpen()
            sessions.values.map { it.record.snapshot }.sortedByDescending { it.updatedAtEpochMs }
        }

        override suspend fun deleteSession(sessionId: AgentSessionId) = mutex.withLock {
            requireOpen()
            val previous = sessions[sessionId]
            val nextRevision = if (previous != null) increment(revision) else revision
            previous?.attachmentUris?.forEach { removeReference(it, sessionId) }
            sessions.remove(sessionId)
            attachmentBackups.remove(sessionId)
            attachmentBackupReferences.remove(sessionId)
            recoveryCandidates.remove(sessionId)
            organizations.remove(sessionId)
            removeEntry(sessionId)
            initializations.remove(sessionId)
            intents.keys.removeAll { it.identity.sessionId == sessionId }
            revision = nextRevision
        }

        override suspend fun clear() = mutex.withLock {
            requireOpen()
            val nextRevision = increment(revision)
            val nextGeneration = increment(generation)
            val nextLifecycle = increment(migrationLifecycle)
            sessions.clear()
            attachmentBackups.clear()
            attachmentBackupReferences.clear()
            migrationLifecycle = nextLifecycle
            importReceipt = importReceipt?.let { if (it.complete) it else it.copy(invalidated = true) }
            organizations.clear()
            entries.clear()
            sortedPins.clear()
            sortedActivity.clear()
            references.clear()
            recoveryCandidates.clear()
            initializations.clear()
            intents.clear()
            revision = nextRevision
            generation = nextGeneration
            resetMaintenance(complete = true)
            // Collection definitions and the global activity counter deliberately survive clear.
        }
    }

    val catalog: SessionCatalog = object : SessionCatalog {
        override suspend fun state(): SessionCatalogState = mutex.withLock {
            requireOpen()
            currentState()
        }

        override suspend fun pageRecoveryCandidates(
            cursor: SessionRecoveryCursor?,
            limit: Int,
        ): SessionRecoveryPage = mutex.withLock {
            requireOpen()
            validateCatalogPageLimit(limit)
            val state = currentState()
            if (cursor != null && (cursor.policy != state.policy || cursor.generation != state.generation)) {
                fail(SessionCatalogFailure.INVALID_CURSOR)
            }
            val selected = recoveryCandidates.values.asSequence()
                .filter { cursor == null || it.sessionId.value > cursor.afterSessionId.value }
                .sortedBy { it.sessionId.value }.take(limit + 1).toList()
            val page = selected.take(limit)
            val more = selected.size > limit
            SessionRecoveryPage(
                page,
                if (more) SessionRecoveryCursor(state.policy, state.generation, page.last().sessionId) else null,
                !more,
                state,
                coverage,
            )
        }

        override suspend fun sessionIdentity(id: AgentSessionId): SessionIdentity? = mutex.withLock {
            requireOpen()
            sessions[id]?.identity
        }

        override suspend fun sessionHeader(id: AgentSessionId): SessionCatalogHeader? = mutex.withLock {
            requireOpen()
            entries[id]?.header
        }

        override suspend fun exists(id: AgentSessionId): Boolean = mutex.withLock {
            requireOpen()
            sessions.containsKey(id)
        }

        override suspend fun organization(identity: SessionIdentity): SessionOrganization? = mutex.withLock {
            requireOpen()
            val session = sessions[identity.sessionId] ?: return@withLock null
            requireIdentity(session, identity)
            organizations[identity.sessionId] ?: SessionOrganization()
        }

        override suspend fun collection(id: String): SessionCollection? = mutex.withLock {
            requireOpen()
            collections[id]
        }

        override suspend fun pageSessions(
            query: SessionCatalogQuery,
            cursor: SessionCatalogCursor?,
            direction: CatalogPageDirection,
            limit: Int,
        ): SessionCatalogPage = mutex.withLock {
            requireOpen()
            validateCatalogPageLimit(limit)
            val normalized = normalizeCatalogQuery(projector, query)
            val state = currentState()
            validateCatalogCursor(cursor, normalized, state)
            val index = sessionIndex(normalized.sort)
            val start = when (direction) {
                CatalogPageDirection.AFTER -> cursor?.let { upperBound(index, it.key, normalized.sort) } ?: 0
                CatalogPageDirection.BEFORE -> (cursor?.let { lowerBound(index, it.key, normalized.sort) } ?: index.size) - 1
            }
            val selected = scan(index, normalized, start, direction, limit)
            sessionPage(selected, index, normalized, state, direction)
        }

        override suspend fun windowAround(
            id: AgentSessionId,
            query: SessionCatalogQuery,
            limit: Int,
        ): SessionCatalogPage = mutex.withLock {
            requireOpen()
            validateCatalogPageLimit(limit)
            val normalized = normalizeCatalogQuery(projector, query)
            val anchor = entries[id]?.takeIf { it.matches(normalized) }
                ?: return@withLock SessionCatalogPage(emptyList(), null, null, true, currentState())
            val index = sessionIndex(normalized.sort)
            val position = lowerBound(index, sessionCatalogSortKey(anchor.header, normalized.sort), normalized.sort)
            val before = scan(index, normalized, position - 1, CatalogPageDirection.BEFORE, (limit - 1) / 2)
            val after = scan(index, normalized, position, CatalogPageDirection.AFTER, limit - before.size)
            val selected = if (before.size + after.size == limit) {
                before + after
            } else {
                scan(index, normalized, position - 1, CatalogPageDirection.BEFORE, limit - after.size) + after
            }
            sessionPage(selected, index, normalized, currentState(), CatalogPageDirection.AFTER).let {
                it.copy(exhausted = it.previousCursor == null && it.nextCursor == null)
            }
        }

        override suspend fun pageCollections(
            query: SessionCollectionQuery,
            cursor: SessionCollectionCursor?,
            direction: CatalogPageDirection,
            limit: Int,
        ): SessionCollectionPage = mutex.withLock {
            requireOpen()
            validateCatalogPageLimit(limit)
            val normalized = query.copy(search = projector.normalizeSearch(query.search))
            val state = currentState()
            if (
                cursor != null &&
                (cursor.query != normalized || cursor.policy != state.policy || cursor.generation != state.generation)
            ) {
                fail(SessionCatalogFailure.INVALID_CURSOR)
            }
            val anchor = cursor?.let { it.nameSortKey to it.idSortKey }
            val start = if (direction == CatalogPageDirection.AFTER) {
                anchor?.let { collectionBound(it, afterEqual = true) } ?: 0
            } else {
                (anchor?.let { collectionBound(it, afterEqual = false) } ?: sortedCollections.size) - 1
            }
            val selected = scanCollections(normalized, start, direction, limit)
            val previous = selected.firstOrNull()?.let { first ->
                val position = collectionBound(collectionSortKey(first), afterEqual = false)
                if (scanCollections(normalized, position - 1, CatalogPageDirection.BEFORE, 1).isNotEmpty()) {
                    collectionCursor(first, normalized, state)
                } else {
                    null
                }
            }
            val next = selected.lastOrNull()?.let { last ->
                val position = collectionBound(collectionSortKey(last), afterEqual = true)
                if (scanCollections(normalized, position, CatalogPageDirection.AFTER, 1).isNotEmpty()) {
                    collectionCursor(last, normalized, state)
                } else {
                    null
                }
            }
            SessionCollectionPage(
                selected,
                previous,
                next,
                if (direction == CatalogPageDirection.AFTER) next == null else previous == null,
                state,
            )
        }

        override suspend fun createCollection(collection: SessionCollection) = mutex.withLock {
            requireOpen()
            if (collections.containsKey(collection.id) ||
                collections.values.any { it.nameUniqueKey == collection.nameUniqueKey }
            ) {
                fail(SessionCatalogFailure.COLLECTION_CONFLICT)
            }
            val nextRevision = increment(revision)
            collections[collection.id] = collection
            insertCollection(collection)
            revision = nextRevision
        }

        override suspend fun renameCollection(
            id: String,
            name: String,
            nameSortKey: String,
            nameUniqueKey: String,
        ) = mutex.withLock {
            requireOpen()
            val previous = collections[id] ?: fail(SessionCatalogFailure.COLLECTION_NOT_FOUND)
            val replacement = SessionCollection(id, name, nameSortKey, nameUniqueKey)
            if (previous == replacement) return@withLock
            if (collections.values.any { it.id != id && it.nameUniqueKey == nameUniqueKey }) {
                fail(SessionCatalogFailure.COLLECTION_CONFLICT)
            }
            val nextRevision = increment(revision)
            collections[id] = replacement
            sortedCollections.removeAll { it.id == id }
            insertCollection(replacement)
            revision = nextRevision
        }

        override suspend fun deleteCollection(id: String) = mutex.withLock {
            requireOpen()
            if (!collections.containsKey(id)) return@withLock
            val replacements = organizations.filterValues { it.collectionId == id }.map { (sessionId, organization) ->
                val replacement = organization.copy(collectionId = null)
                Triple(sessionId, replacement, entryForOrganization(sessionId, replacement))
            }
            val nextRevision = increment(revision)
            collections.remove(id)
            sortedCollections.removeAll { it.id == id }
            replacements.forEach { (sessionId, organization, entry) ->
                storeOrganization(sessionId, organization)
                entry?.let(::replaceEntry)
            }
            revision = nextRevision
        }

        override suspend fun updateOrganization(
            identity: SessionIdentity,
            mutation: SessionOrganizationMutation,
        ): SessionOrganization? = mutex.withLock {
            requireOpen()
            val session = sessions[identity.sessionId] ?: return@withLock null
            requireIdentity(session, identity)
            val previous = organizations[identity.sessionId] ?: SessionOrganization()
            val replacement = when (mutation) {
                is SessionOrganizationMutation.Title -> previous.copy(title = mutation.value)
                is SessionOrganizationMutation.Collection -> {
                    if (mutation.id != null && !collections.containsKey(mutation.id)) {
                        fail(SessionCatalogFailure.COLLECTION_NOT_FOUND)
                    }
                    previous.copy(collectionId = mutation.id)
                }
                is SessionOrganizationMutation.Pin -> previous.copy(pinnedAtEpochMs = mutation.pinnedAtEpochMs)
            }
            if (previous != replacement) writeOrganization(identity.sessionId, replacement)
            replacement
        }

        override suspend fun setTitleIfAbsent(identity: SessionIdentity, title: String): Boolean = mutex.withLock {
            requireOpen()
            val session = sessions[identity.sessionId] ?: return@withLock false
            requireIdentity(session, identity)
            val previous = organizations[identity.sessionId] ?: SessionOrganization()
            if (previous.title != null) return@withLock false
            writeOrganization(identity.sessionId, previous.copy(title = title))
            true
        }

        override suspend fun referencedUris(
            candidates: Set<String>,
        ): SessionAttachmentReferences = mutex.withLock {
            requireOpen()
            SessionAttachmentReferences(
                candidates.filterTo(mutableSetOf()) { references[it]?.isNotEmpty() == true },
                coverage,
            )
        }

        override suspend fun prepareNewSession(
            id: AgentSessionId,
            creationToken: String,
            initialOrganization: SessionOrganization,
        ): SessionIdentity = mutex.withLock {
            requireOpen()
            val identity = SessionIdentity(id, creationToken)
            val existing = sessions[id]
            if (existing != null) {
                requireIdentity(existing, identity)
                return@withLock identity
            }
            require(creationToken != "legacy") { "The legacy incarnation is reserved for physical schema migration" }
            val prepared = initializations[id]
            if (prepared != null) {
                if (prepared.identity != identity || prepared.initialOrganization != initialOrganization) {
                    fail(SessionCatalogFailure.INITIALIZATION_CONFLICT)
                }
                return@withLock identity
            }
            initializations[id] = PreparedSessionInitialization(identity, initialOrganization)
            identity
        }

        override suspend fun prepareActivity(
            identity: SessionIdentity,
            activityIdentity: String,
        ): SessionActivityTicket = mutex.withLock {
            requireOpen()
            require(projector.activityMode == SessionActivityMode.PREPARED) {
                "Prepared activity requires a PREPARED projection policy"
            }
            require(activityIdentity.isNotBlank())
            requirePreparedIdentity(identity)
            val key = IntentKey(identity, activityIdentity)
            when (val existing = intents[key]) {
                is PreparedActivityIntent.Advance -> return@withLock existing.ticket
                is PreparedActivityIntent.Preserve -> fail(SessionCatalogFailure.INITIALIZATION_CONFLICT)
                null -> Unit
            }
            val previous = sessions[identity.sessionId]?.activity
            if (previous?.lastAcceptedIdentity == activityIdentity && previous.order is SessionActivityOrder.Accepted) {
                val ticket = SessionActivityTicket(identity, activityIdentity, previous.order.sequence)
                intents[key] = PreparedActivityIntent.Advance(ticket)
                return@withLock ticket
            }
            val nextCounter = increment(activityCounter)
            val ticket = SessionActivityTicket(identity, activityIdentity, nextCounter)
            activityCounter = nextCounter
            intents[key] = PreparedActivityIntent.Advance(ticket)
            ticket
        }

        override suspend fun preparePreservedActivity(
            identity: SessionIdentity,
            targetActivityIdentity: String?,
        ) = mutex.withLock {
            requireOpen()
            require(projector.activityMode == SessionActivityMode.PREPARED) {
                "Prepared activity requires a PREPARED projection policy"
            }
            val session = sessions[identity.sessionId] ?: fail(SessionCatalogFailure.SESSION_NOT_FOUND)
            requireIdentity(session, identity)
            val key = IntentKey(identity, targetActivityIdentity)
            if (intents[key] is PreparedActivityIntent.Advance) fail(SessionCatalogFailure.INITIALIZATION_CONFLICT)
            intents[key] = PreparedActivityIntent.Preserve(targetActivityIdentity)
        }

        override suspend fun releaseActivity(
            identity: SessionIdentity,
            activityIdentity: String?,
        ) = mutex.withLock {
            requireOpen()
            intents.remove(IntentKey(identity, activityIdentity))
            Unit
        }

        override suspend fun releasePreparation(identity: SessionIdentity) = mutex.withLock {
            requireOpen()
            if (initializations[identity.sessionId]?.identity == identity) initializations.remove(identity.sessionId)
            intents.keys.removeAll { it.identity == identity }
            Unit
        }
    }

    val migration: SessionCatalogMigration = object : SessionCatalogMigration {
        override suspend fun organizationImportAllowed(): Boolean = mutex.withLock {
            requireOpen()
            migrationLifecycle == 0L && importReceipt?.invalidated != true
        }

        override suspend fun organizationImportStatus(): OrganizationImportReceipt? = mutex.withLock {
            requireOpen()
            importReceipt
        }

        override suspend fun beginOrganizationImport(
            sourceId: String,
            fingerprint: String,
        ): OrganizationImportReceipt = mutex.withLock {
            requireOpen()
            require(sourceId.isNotBlank() && fingerprint.isNotBlank())
            importReceipt?.let {
                if (it.token.sourceId != sourceId || it.token.fingerprint != fingerprint) {
                    fail(SessionCatalogFailure.MIGRATION_CONFLICT)
                }
                if (it.invalidated) fail(SessionCatalogFailure.STALE_MIGRATION)
                return@withLock it
            }
            if (migrationLifecycle != 0L) fail(SessionCatalogFailure.STALE_MIGRATION)
            val nextRevision = increment(revision)
            OrganizationImportReceipt(
                OrganizationImportToken(migrationStoreId, migrationLifecycle, sourceId, fingerprint),
            ).also {
                importReceipt = it
                revision = nextRevision
            }
        }

        override suspend fun importOrganizationBatch(
            token: OrganizationImportToken,
            batchIndex: Long,
            collections: List<SessionCollection>,
            organizations: List<LegacySessionOrganization>,
            complete: Boolean,
        ): OrganizationImportReceipt = mutex.withLock {
            requireOpen()
            validateOrganizationImportBatch(collections, organizations)
            require(batchIndex >= 0)
            val previous = importReceipt ?: fail(SessionCatalogFailure.STALE_MIGRATION)
            if (previous.token != token || previous.invalidated) fail(SessionCatalogFailure.STALE_MIGRATION)
            val signature = SessionCatalogMigrationEncoding.batch(collections, organizations, complete)
            if (batchIndex < previous.nextBatch) {
                if (batchIndex == previous.nextBatch - 1 && importLastBatch != signature) {
                    fail(SessionCatalogFailure.MIGRATION_CONFLICT)
                }
                return@withLock previous
            }
            if (previous.complete || batchIndex != previous.nextBatch) fail(SessionCatalogFailure.MIGRATION_CONFLICT)
            val combined = this@InMemorySessionCatalogStore.collections.toMutableMap()
            collections.forEach { value ->
                if (combined[value.id]?.let { it != value } == true ||
                    combined.values.any { it.id != value.id && it.nameUniqueKey == value.nameUniqueKey }
                ) {
                    fail(SessionCatalogFailure.COLLECTION_CONFLICT)
                }
                combined[value.id] = value
            }
            var skipped = 0L
            val replacements = organizations.mapNotNull { value ->
                val canonical = sessions[value.sessionId]
                if (canonical == null || canonical.identity.incarnation != "legacy") {
                    skipped++
                    return@mapNotNull null
                }
                value.organization.collectionId?.let {
                    if (!combined.containsKey(it)) fail(SessionCatalogFailure.COLLECTION_NOT_FOUND)
                }
                this@InMemorySessionCatalogStore.organizations[value.sessionId]?.let {
                    if (it != value.organization) fail(SessionCatalogFailure.MIGRATION_CONFLICT)
                }
                Triple(value.sessionId, value.organization, entryForOrganization(value.sessionId, value.organization))
            }
            val nextRevision = increment(revision)
            if (previous.imported > Long.MAX_VALUE - replacements.size ||
                previous.skipped > Long.MAX_VALUE - skipped
            ) {
                fail(SessionCatalogFailure.COUNTER_EXHAUSTED)
            }
            val result = previous.copy(
                nextBatch = increment(previous.nextBatch),
                imported = previous.imported + replacements.size,
                skipped = previous.skipped + skipped,
                complete = complete,
            )
            collections.forEach { value ->
                if (!this@InMemorySessionCatalogStore.collections.containsKey(value.id)) {
                    this@InMemorySessionCatalogStore.collections[value.id] = value
                    insertCollection(value)
                }
            }
            replacements.forEach { (id, organization, entry) ->
                storeOrganization(id, organization)
                entry?.let(::replaceEntry)
            }
            importReceipt = result
            importLastBatch = signature
            revision = nextRevision
            result
        }

        override suspend fun beginAttachmentNameScan(
            sourceId: String,
            sourceVersion: Int,
        ): SessionAttachmentNameScanProgress = mutex.withLock {
            requireOpen()
            require(sourceId.isNotBlank() && sourceVersion > 0)
            val old = nameScanProgress
            if (old != null &&
                old.token.lifecycle == migrationLifecycle &&
                old.token.sourceId == sourceId &&
                old.token.sourceVersion == sourceVersion
            ) return@withLock old
            val progress = SessionAttachmentNameScanProgress(
                SessionAttachmentNameScanToken(
                    migrationStoreId,
                    migrationLifecycle,
                    increment(old?.token?.generation ?: 0L),
                    sourceId,
                    sourceVersion,
                ),
            )
            revision = increment(revision)
            nameScanProgress = progress
            progress
        }

        override suspend fun restartAttachmentNameScan(
            expected: SessionAttachmentNameScanProgress,
        ): SessionAttachmentNameScanProgress = mutex.withLock {
            checkNameScan(expected)
            val progress = SessionAttachmentNameScanProgress(
                expected.token.copy(generation = increment(expected.token.generation)),
            )
            revision = increment(revision)
            nameScanProgress = progress
            progress
        }

        override suspend fun attachmentNameScanPage(
            expected: SessionAttachmentNameScanProgress,
            limit: Int,
        ): SessionAttachmentNamePage = mutex.withLock {
            checkNameScan(expected)
            require(limit in 1..MAX_CATALOG_MIGRATION_BATCH_SIZE)
            val rows = if (expected.complete) {
                emptyList()
            } else {
                sessions.values
                    .filter { expected.afterSessionId == null || it.identity.sessionId.value > expected.afterSessionId.value }
                    .sortedBy { it.identity.sessionId.value }
                    .take(limit + 1)
            }
            val selected = rows.take(limit)
            SessionAttachmentNamePage(
                selected.map { sessionAttachmentNameTarget(it.identity, it.rowVersion, it.record) },
                selected.lastOrNull()?.identity?.sessionId,
                rows.size <= limit,
                sources = selected.map {
                    SessionAttachmentNameSource(
                        it.identity,
                        it.rowVersion,
                        attachmentNameSessionCursor(it.identity.sessionId),
                    )
                },
            )
        }

        override suspend fun advanceAttachmentNameScan(
            expected: SessionAttachmentNameScanProgress,
            source: SessionAttachmentNameSource,
            outcome: SessionAttachmentNameScanOutcome,
        ): SessionAttachmentNameScanProgress = mutex.withLock {
            checkNameScan(expected)
            val identity = source.identity ?: fail(SessionCatalogFailure.STALE_MIGRATION)
            val version = source.rowVersion ?: fail(SessionCatalogFailure.STALE_MIGRATION)
            if (source.cursor != attachmentNameSessionCursor(identity.sessionId) ||
                (expected.afterSessionId != null && identity.sessionId.value <= expected.afterSessionId.value)
            ) {
                fail(SessionCatalogFailure.STALE_MIGRATION)
            }
            val nextId = sessions.keys
                .filter { expected.afterSessionId == null || it.value > expected.afterSessionId.value }
                .minByOrNull { it.value }
            if (nextId != null && nextId.value < identity.sessionId.value) fail(SessionCatalogFailure.STALE_MIGRATION)
            sessions[identity.sessionId]?.let { current ->
                if (current.identity != identity ||
                    (outcome != SessionAttachmentNameScanOutcome.SKIPPED_MANAGED && current.rowVersion != version) ||
                    current.rowVersion < version
                ) {
                    fail(SessionCatalogFailure.STALE_MIGRATION)
                }
            }
            val progress = advanceAttachmentNameScanProgress(expected, source, outcome)
            revision = increment(revision)
            nameScanProgress = progress
            progress
        }

        override suspend fun finishAttachmentNameScan(
            expected: SessionAttachmentNameScanProgress,
        ): SessionAttachmentNameScanProgress = mutex.withLock {
            checkNameScan(expected)
            if (expected.complete) return@withLock expected
            if (sessions.keys.any { expected.afterSessionId == null || it.value > expected.afterSessionId.value }) {
                fail(SessionCatalogFailure.STALE_MIGRATION)
            }
            val progress = expected.copy(complete = true)
            revision = increment(revision)
            nameScanProgress = progress
            progress
        }

        override suspend fun attachmentNameTarget(
            id: AgentSessionId,
            afterUri: String?,
        ): SessionAttachmentNameTarget? = mutex.withLock {
            requireOpen()
            sessions[id]?.let { sessionAttachmentNameTarget(it.identity, it.rowVersion, it.record, afterUri) }
        }

        override suspend fun attachmentNameTargets(
            afterSessionId: AgentSessionId?,
            limit: Int,
        ): SessionAttachmentNamePage = mutex.withLock {
            requireOpen()
            require(limit in 1..MAX_CATALOG_MIGRATION_BATCH_SIZE)
            val values = sessions.values.filter { afterSessionId == null || it.identity.sessionId.value > afterSessionId.value }
                .sortedBy { it.identity.sessionId.value }.take(limit + 1)
            SessionAttachmentNamePage(
                values.take(limit).map { sessionAttachmentNameTarget(it.identity, it.rowVersion, it.record) },
                values.take(limit).lastOrNull()?.identity?.sessionId,
                values.size <= limit,
            )
        }

        override suspend fun patchAttachmentNames(
            target: SessionAttachmentNameTarget,
            names: List<SessionAttachmentName>,
        ): SessionAttachmentNamePatchResult {
            val map = attachmentNameMap(names)
            return migrateNames(target) { fillAttachmentNames(it, map) }
        }

        override suspend fun attachmentNameBackup(
            identity: SessionIdentity,
        ): SessionAttachmentNameBackup? = mutex.withLock {
            requireOpen()
            val current = sessions[identity.sessionId] ?: return@withLock null
            requireIdentity(current, identity)
            attachmentBackups[identity.sessionId]
        }

        override suspend fun restoreAttachmentNameBackup(
            target: SessionAttachmentNameTarget,
        ): SessionAttachmentNamePatchResult {
            val backup = attachmentNameBackup(target.identity) ?: return migrateNames(target) { it }
            return migrateNames(target) {
                AgentPersistenceRecord(
                    AgentSessionSnapshotCodec().decodeResult(backup.snapshotPayload).value,
                    backup.checkpointPayload?.let { AgentCheckpointCodec().decodeResult(it).value },
                )
            }
        }
    }

    private fun checkNameScan(expected: SessionAttachmentNameScanProgress) {
        requireOpen()
        if (nameScanProgress != expected || expected.token.lifecycle != migrationLifecycle) {
            fail(SessionCatalogFailure.STALE_MIGRATION)
        }
    }

    private suspend fun migrateNames(
        target: SessionAttachmentNameTarget,
        transform: (AgentPersistenceRecord) -> AgentPersistenceRecord,
    ): SessionAttachmentNamePatchResult {
        val captured = mutex.withLock {
            requireOpen()
            val source = sessions[target.identity.sessionId] ?: fail(SessionCatalogFailure.STALE_MIGRATION)
            if (source.identity != target.identity || source.rowVersion != target.rowVersion) {
                fail(SessionCatalogFailure.STALE_MIGRATION)
            }
            source to projector
        }
        val source = captured.first
        val record = transform(source.record)
        val projection = captured.second.project(record.snapshot)
        val refs = extractSessionAttachmentUris(record.snapshot, record.checkpoint)
        return mutex.withLock {
            requireOpen()
            if (sessions[target.identity.sessionId] != source || projector !== captured.second) {
                fail(SessionCatalogFailure.STALE_MIGRATION)
            }
            if (record == source.record) {
                return@withLock SessionAttachmentNamePatchResult(target.identity, target.rowVersion, false)
            }
            if (projector.activityMode == SessionActivityMode.PREPARED &&
                projection.activityIdentity != source.activity.observedIdentity
            ) {
                fail(SessionCatalogFailure.MAINTENANCE_PROJECTION_FAILED)
            }
            val version = increment(source.rowVersion)
            val nextRevision = increment(revision)
            val entry = materializeCatalogEntry(
                source.identity,
                version,
                record.snapshot,
                projection,
                organizations[target.identity.sessionId] ?: SessionOrganization(),
                source.activity,
                projector,
            )
            val backup = attachmentBackups[target.identity.sessionId] ?: SessionAttachmentNameBackup(
                source.identity,
                source.rowVersion,
                AgentSessionSnapshotCodec().encode(source.record.snapshot),
                source.record.checkpoint?.let { AgentCheckpointCodec().encode(it) },
            )
            val backupRefs = attachmentBackupReferences[target.identity.sessionId]
                ?: extractSessionAttachmentUris(source.record.snapshot, source.record.checkpoint)
            val allReferences = refs + backupRefs
            sessions[target.identity.sessionId] = source.copy(
                rowVersion = version,
                record = record,
                projection = projection,
                attachmentUris = allReferences,
            )
            source.attachmentUris.forEach { removeReference(it, target.identity.sessionId) }
            allReferences.forEach { references.getOrPut(it) { mutableSetOf() }.add(target.identity.sessionId) }
            val reasons = sessionRecoveryReasons(record.snapshot.state.status, record.checkpoint != null)
            if (reasons.isEmpty()) {
                recoveryCandidates.remove(target.identity.sessionId)
            } else {
                recoveryCandidates[target.identity.sessionId] = SessionRecoveryCandidate(
                    target.identity.sessionId,
                    target.identity,
                    reasons,
                )
            }
            attachmentBackups[target.identity.sessionId] = backup
            attachmentBackupReferences[target.identity.sessionId] = backupRefs
            replaceEntry(entry)
            revision = nextRevision
            SessionAttachmentNamePatchResult(target.identity, version, true)
        }
    }

    val maintenance: SessionCatalogMaintenance = object : SessionCatalogMaintenance {
        override suspend fun status(): SessionCatalogMaintenanceProgress = mutex.withLock {
            requireOpen()
            maintenanceProgress()
        }

        override suspend fun rebuild(
            expectedPolicy: SessionCatalogPolicy,
            expectedGeneration: Long,
        ): SessionCatalogMaintenanceProgress = mutex.withLock {
            requireOpen()
            if (expectedPolicy != projector.policy || expectedGeneration != generation) {
                fail(SessionCatalogFailure.STALE_MAINTENANCE)
            }
            val nextRevision = increment(revision)
            val nextGeneration = increment(generation)
            entries.clear()
            sortedPins.clear()
            sortedActivity.clear()
            references.clear()
            revision = nextRevision
            generation = nextGeneration
            resetMaintenance(complete = sessions.isEmpty())
            maintenanceProgress()
        }

        override suspend fun runBatch(limit: Int): SessionCatalogMaintenanceBatch = maintenanceMutex.withLock {
            require(limit in 1..MAX_CATALOG_MAINTENANCE_BATCH_SIZE)
            val captured = mutex.withLock {
                requireOpen()
                Triple(
                    generation,
                    projector,
                    if (maintenanceComplete) {
                        emptyList()
                    } else {
                        sessions.values
                            .filter {
                                maintenanceCursor == null ||
                                    it.identity.sessionId.value > requireNotNull(maintenanceCursor).value
                            }
                            .sortedBy { it.identity.sessionId.value }
                            .take(limit)
                    },
                )
            }
            var indexed = 0
            var skipped = 0
            val issues = mutableListOf<SessionCatalogMaintenanceIssue>()
            captured.third.forEach { source ->
                val projection = try {
                    captured.second.project(source.record.snapshot).also {
                        require(
                            captured.second.activityMode != SessionActivityMode.PREPARED ||
                                it.activityIdentity == source.activity.observedIdentity,
                        )
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                }
                mutex.withLock {
                    requireOpen()
                    if (generation != captured.first || projector !== captured.second) {
                        fail(SessionCatalogFailure.STALE_MAINTENANCE)
                    }
                    val id = source.identity.sessionId
                    val nextProcessed = increment(maintenanceProcessed)
                    val nextRevision = increment(revision)
                    if (sessions[id] != source) {
                        // A normal commit atomically supplied its own newer indexes; deletion needs no row.
                        skipped++
                    } else {
                        val entry = try {
                            projection?.let {
                                materializeCatalogEntry(
                                    source.identity,
                                    source.rowVersion,
                                    source.record.snapshot,
                                    it,
                                    organizations[id] ?: SessionOrganization(),
                                    source.activity,
                                    captured.second,
                                )
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            null
                        }
                        if (entry == null) {
                            maintenanceFailed = increment(maintenanceFailed)
                            issues += SessionCatalogMaintenanceIssue(id, SessionCatalogMaintenanceIssueKind.PROJECTION_FAILED)
                        } else {
                            sessions[id] = source.copy(projection = requireNotNull(projection))
                            replaceEntry(entry)
                            source.attachmentUris.forEach { references.getOrPut(it) { mutableSetOf() }.add(id) }
                            indexed++
                        }
                    }
                    maintenanceCursor = id
                    maintenanceProcessed = nextProcessed
                    revision = nextRevision
                }
            }
            mutex.withLock {
                requireOpen()
                if (generation != captured.first || projector !== captured.second) {
                    fail(SessionCatalogFailure.STALE_MAINTENANCE)
                }
                if (
                    !maintenanceComplete && sessions.keys.none {
                        maintenanceCursor == null || it.value > requireNotNull(maintenanceCursor).value
                    }
                ) {
                    revision = increment(revision)
                    maintenanceComplete = true
                    coverage = if (maintenanceFailed == 0L) CatalogCoverage.COMPLETE else CatalogCoverage.PARTIAL
                }
                SessionCatalogMaintenanceBatch(maintenanceProgress(), indexed, skipped, issues)
            }
        }
    }

    /** Reprojects every in-memory record atomically; authority and the accepted sequence counter survive. */
    suspend fun rebuild(newProjector: SessionCatalogProjector = projector) {
        val captured = mutex.withLock {
            requireOpen()
            Triple(revision, projector, sessions.toMap())
        }
        val projections = captured.third.mapValues { newProjector.project(it.value.record.snapshot) }
        mutex.withLock {
            requireOpen()
            if (revision != captured.first || projector !== captured.second) fail(SessionCatalogFailure.POLICY_MISMATCH)
            require(newProjector.activityMode == projector.activityMode) {
                "Changing activity mode requires explicit authority migration"
            }
            require(newProjector.activityMode != SessionActivityMode.PREPARED || captured.third.all { (id, stored) ->
                projections.getValue(id).activityIdentity == stored.activity.observedIdentity
            }) { "Changing activity identity requires explicit authority migration" }
            val replacements = captured.third.mapValues { (id, stored) ->
                materializeCatalogEntry(
                    stored.identity,
                    stored.rowVersion,
                    stored.record.snapshot,
                    projections.getValue(id),
                    organizations[id] ?: SessionOrganization(),
                    stored.activity,
                    newProjector,
                )
            }
            val nextRevision = increment(revision)
            val nextGeneration = increment(generation)
            projector = newProjector
            sessions.keys.toList().forEach { id ->
                sessions[id] = sessions.getValue(id).copy(projection = projections.getValue(id))
            }
            entries.clear()
            sortedPins.clear()
            sortedActivity.clear()
            references.clear()
            sessions.forEach { (id, stored) ->
                stored.attachmentUris.forEach { references.getOrPut(it) { mutableSetOf() }.add(id) }
            }
            replacements.values.forEach(::replaceEntry)
            revision = nextRevision
            generation = nextGeneration
            resetMaintenance(complete = true)
        }
    }

    /** Closing is not a runtime fence. The owner must settle all possible writes first. */
    suspend fun close() = mutex.withLock {
        closed = true
        initializations.clear()
        intents.clear()
    }

    private fun currentState() = SessionCatalogState(projector.policy, revision, generation, coverage)

    private fun maintenanceProgress() = SessionCatalogMaintenanceProgress(
        currentState(),
        maintenanceCursor,
        maintenanceProcessed,
        maintenanceFailed,
        maintenanceComplete,
    )

    private fun resetMaintenance(complete: Boolean) {
        maintenanceCursor = null
        maintenanceProcessed = 0
        maintenanceFailed = 0
        maintenanceComplete = complete
        coverage = if (complete) CatalogCoverage.COMPLETE else CatalogCoverage.INDEXING
    }

    private fun requireOpen() {
        if (closed) fail(SessionCatalogFailure.CLOSED)
    }

    private fun requireIdentity(session: StoredSession, identity: SessionIdentity) {
        if (session.identity != identity) fail(SessionCatalogFailure.STALE_IDENTITY)
    }

    private fun requirePreparedIdentity(identity: SessionIdentity) {
        sessions[identity.sessionId]?.let {
            requireIdentity(it, identity)
            return
        }
        if (initializations[identity.sessionId]?.identity != identity) fail(SessionCatalogFailure.STALE_IDENTITY)
    }

    private fun increment(value: Long): Long {
        if (value == Long.MAX_VALUE) fail(SessionCatalogFailure.COUNTER_EXHAUSTED)
        return value + 1
    }

    private fun storeOrganization(id: AgentSessionId, organization: SessionOrganization) {
        if (organization.isEmpty) organizations.remove(id) else organizations[id] = organization
    }

    private fun entryForOrganization(id: AgentSessionId, organization: SessionOrganization): SessionCatalogEntry? {
        val stored = sessions.getValue(id)
        val previous = entries[id] ?: return null
        return previous.copy(
            header = previous.header.copy(
                label = organization.title ?: stored.projection.fallbackLabel,
                collectionId = organization.collectionId,
                pinnedAtEpochMs = organization.pinnedAtEpochMs,
            ),
            normalizedTitle = organization.title?.let(projector::normalizeSearch) ?: stored.projection.normalizedFallbackLabel,
        )
    }

    private fun writeOrganization(id: AgentSessionId, organization: SessionOrganization) {
        val entry = entryForOrganization(id, organization)
        val nextRevision = increment(revision)
        storeOrganization(id, organization)
        entry?.let(::replaceEntry)
        revision = nextRevision
    }

    private fun removeReference(uri: String, id: AgentSessionId) {
        references[uri]?.let { ids ->
            if (ids.remove(id) && ids.isEmpty()) references.remove(uri)
        }
    }

    private fun removeEntry(id: AgentSessionId) {
        val previous = entries.remove(id) ?: return
        listOf(
            sortedPins to SessionCatalogSort.PIN_THEN_ACTIVITY,
            sortedActivity to SessionCatalogSort.ACTIVITY_DESC,
        ).forEach { (index, sort) ->
            val position = lowerBound(index, sessionCatalogSortKey(previous.header, sort), sort)
            if (position < index.size && index[position].header.identity.sessionId == id) index.removeAt(position)
        }
    }

    private fun replaceEntry(entry: SessionCatalogEntry) {
        removeEntry(entry.header.identity.sessionId)
        entries[entry.header.identity.sessionId] = entry
        listOf(
            sortedPins to SessionCatalogSort.PIN_THEN_ACTIVITY,
            sortedActivity to SessionCatalogSort.ACTIVITY_DESC,
        ).forEach { (index, sort) ->
            index.add(lowerBound(index, sessionCatalogSortKey(entry.header, sort), sort), entry)
        }
    }

    private fun sessionIndex(sort: SessionCatalogSort): List<SessionCatalogEntry> =
        if (sort == SessionCatalogSort.PIN_THEN_ACTIVITY) sortedPins else sortedActivity

    private fun lowerBound(
        index: List<SessionCatalogEntry>,
        key: SessionCatalogSortKey,
        sort: SessionCatalogSort,
    ): Int = bound(index, key, sort, false)

    private fun upperBound(
        index: List<SessionCatalogEntry>,
        key: SessionCatalogSortKey,
        sort: SessionCatalogSort,
    ): Int = bound(index, key, sort, true)

    private fun bound(
        index: List<SessionCatalogEntry>,
        key: SessionCatalogSortKey,
        sort: SessionCatalogSort,
        afterEqual: Boolean,
    ): Int {
        var low = 0
        var high = index.size
        while (low < high) {
            val mid = low + (high - low) / 2
            val comparison = sessionCatalogSortKey(index[mid].header, sort).compareTo(key)
            if (comparison < 0 || (afterEqual && comparison == 0)) low = mid + 1 else high = mid
        }
        return low
    }

    private fun scan(
        index: List<SessionCatalogEntry>,
        query: SessionCatalogQuery,
        start: Int,
        direction: CatalogPageDirection,
        limit: Int,
    ): List<SessionCatalogEntry> {
        val result = mutableListOf<SessionCatalogEntry>()
        var position = start
        val step = if (direction == CatalogPageDirection.AFTER) 1 else -1
        while (position in index.indices && result.size < limit) {
            index[position].takeIf { it.matches(query) }?.let(result::add)
            position += step
        }
        if (direction == CatalogPageDirection.BEFORE) result.reverse()
        return result
    }

    private fun sessionPage(
        selected: List<SessionCatalogEntry>,
        index: List<SessionCatalogEntry>,
        query: SessionCatalogQuery,
        state: SessionCatalogState,
        direction: CatalogPageDirection,
    ): SessionCatalogPage {
        val previous = selected.firstOrNull()?.let { first ->
            val key = sessionCatalogSortKey(first.header, query.sort)
            if (scan(index, query, lowerBound(index, key, query.sort) - 1, CatalogPageDirection.BEFORE, 1).isNotEmpty()) {
                SessionCatalogCursor(query, state.policy, state.generation, key)
            } else null
        }
        val next = selected.lastOrNull()?.let { last ->
            val key = sessionCatalogSortKey(last.header, query.sort)
            if (scan(index, query, upperBound(index, key, query.sort), CatalogPageDirection.AFTER, 1).isNotEmpty()) {
                SessionCatalogCursor(query, state.policy, state.generation, key)
            } else null
        }
        return SessionCatalogPage(
            selected.map { it.header },
            previous,
            next,
            if (direction == CatalogPageDirection.AFTER) next == null else previous == null,
            state,
        )
    }

    private fun collectionSortKey(collection: SessionCollection) =
        collection.nameSortKey to CatalogKeyEncoding.stringKey(collection.id)

    private fun collectionBound(key: Pair<String, String>, afterEqual: Boolean): Int {
        var low = 0
        var high = sortedCollections.size
        while (low < high) {
            val mid = low + (high - low) / 2
            val candidate = collectionSortKey(sortedCollections[mid])
            val comparison = compareValuesBy(candidate, key, { it.first }, { it.second })
            if (comparison < 0 || (afterEqual && comparison == 0)) low = mid + 1 else high = mid
        }
        return low
    }

    private fun insertCollection(collection: SessionCollection) {
        sortedCollections.add(collectionBound(collectionSortKey(collection), afterEqual = false), collection)
    }

    private fun scanCollections(
        query: SessionCollectionQuery,
        start: Int,
        direction: CatalogPageDirection,
        limit: Int,
    ): List<SessionCollection> {
        val result = mutableListOf<SessionCollection>()
        var position = start
        val step = if (direction == CatalogPageDirection.AFTER) 1 else -1
        while (position in sortedCollections.indices && result.size < limit) {
            val candidate = sortedCollections[position]
            if (query.search.isEmpty() || projector.normalizeSearch(candidate.name).contains(query.search)) result.add(candidate)
            position += step
        }
        if (direction == CatalogPageDirection.BEFORE) result.reverse()
        return result
    }

    private fun collectionCursor(
        collection: SessionCollection,
        query: SessionCollectionQuery,
        state: SessionCatalogState,
    ) = SessionCollectionCursor(
        query,
        state.policy,
        state.generation,
        collection.nameSortKey,
        CatalogKeyEncoding.stringKey(collection.id),
    )

    private fun fail(failure: SessionCatalogFailure): Nothing = throw SessionCatalogException(failure)
}
