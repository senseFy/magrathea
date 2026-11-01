package saien.magrathea.storage.web

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import saien.magrathea.catalog.CatalogCoverage
import saien.magrathea.catalog.CatalogKeyEncoding
import saien.magrathea.catalog.CatalogPageDirection
import saien.magrathea.catalog.MAX_CATALOG_MAINTENANCE_BATCH_SIZE
import saien.magrathea.catalog.MAX_CATALOG_PAGE_SIZE
import saien.magrathea.catalog.PreparedActivityIntent
import saien.magrathea.catalog.PreparedSessionInitialization
import saien.magrathea.catalog.SessionActivityFact
import saien.magrathea.catalog.SessionActivityMode
import saien.magrathea.catalog.SessionActivityOrder
import saien.magrathea.catalog.SessionActivityTicket
import saien.magrathea.catalog.SessionAttachmentReferences
import saien.magrathea.catalog.SessionCatalog
import saien.magrathea.catalog.SessionCatalogCursor
import saien.magrathea.catalog.SessionCatalogEntry
import saien.magrathea.catalog.SessionCatalogException
import saien.magrathea.catalog.SessionCatalogFailure
import saien.magrathea.catalog.SessionCatalogHeader
import saien.magrathea.catalog.SessionCatalogMaintenance
import saien.magrathea.catalog.SessionCatalogMaintenanceBatch
import saien.magrathea.catalog.SessionCatalogMaintenanceIssue
import saien.magrathea.catalog.SessionCatalogMaintenanceIssueKind
import saien.magrathea.catalog.SessionCatalogMaintenanceProgress
import saien.magrathea.catalog.SessionCatalogMigration
import saien.magrathea.catalog.SessionCatalogMigrationEncoding
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
import saien.magrathea.catalog.SessionRecoveryCandidate
import saien.magrathea.catalog.SessionRecoveryCursor
import saien.magrathea.catalog.SessionRecoveryPage
import saien.magrathea.catalog.SessionRecoveryReason
import saien.magrathea.catalog.attachmentNameBackupUris
import saien.magrathea.catalog.extractSessionAttachmentUris
import saien.magrathea.catalog.materializeCatalogEntry
import saien.magrathea.catalog.normalizeCatalogQuery
import saien.magrathea.catalog.resolveSessionActivity
import saien.magrathea.catalog.sessionCatalogSortKey
import saien.magrathea.catalog.sessionRecoveryReasons
import saien.magrathea.catalog.validateCatalogCursor
import saien.magrathea.catalog.validateCatalogPageLimit
import saien.magrathea.core.AgentCheckpoint
import saien.magrathea.core.AgentCheckpointCodec
import saien.magrathea.core.AgentPersistenceRecord
import saien.magrathea.core.AgentSessionId
import saien.magrathea.core.AgentSessionSnapshot
import saien.magrathea.core.AgentSessionSnapshotCodec
import saien.magrathea.core.StoredEnvelopeDecodeException
import saien.magrathea.core.StoredEnvelopeDecodeFailure
import saien.magrathea.core.StoredEnvelopeDecodeResult
import saien.magrathea.core.SystemIdGenerator

/** Kotlin owns policy and semantic decisions; the JS bridge only executes finite storage plans. */
internal class WebSessionCatalog(
    private val database: IndexedDbRecordDatabase,
    private val lifecycle: WebStoreLifecycle,
    internal val projector: SessionCatalogProjector,
    private val json: Json,
) : SessionCatalog {
    private val snapshotCodec = AgentSessionSnapshotCodec(json)
    private val checkpointCodec = AgentCheckpointCodec(json)
    private val initializations = mutableMapOf<AgentSessionId, PreparedSessionInitialization>()
    private val intents = mutableMapOf<Pair<SessionIdentity, String?>, PreparedActivityIntent>()
    private val maintenanceMutex = Mutex()
    private var initialized = false

    private suspend fun initialize(): Unit {
        if (initialized) return
        repeat(ATTEMPTS) {
            val result = database.executeCatalog("keys", wireObject {
                put("store", WEB_SESSION_STORE)
                put("direction", "next")
                put("limit", 1)
            }).jsonObject
            if (result["state"] is JsonObject) {
                initialized = true
                return
            }
            val empty = result.array("keys").isEmpty()
            val state = WebCatalogControl(
                projector.policy,
                projector.activityMode,
                coverage = if (empty) CatalogCoverage.COMPLETE else CatalogCoverage.INDEXING,
                sessionsComplete = empty,
                recoveryIndexed = empty,
            )
            if (database.executeCatalog("atomicWrite", wireObject {
                put("expectedState", JsonNull)
                put("state", state.wire())
            }).jsonPrimitive.boolean) {
                initialized = true
                return
            }
        }
        storageFailure()
    }

    internal suspend fun view(
        operation: String = "read",
        arguments: JsonObject = wireObject { },
        allowPolicyMismatch: Boolean = false,
    ): WebCatalogView {
        initialize()
        val result = database.executeCatalog(operation, arguments).jsonObject
        val raw = result.getValue("state").jsonObject
        val control = WebCatalogControl.from(raw)
        if (!allowPolicyMismatch && (control.policy != projector.policy || control.activityMode != projector.activityMode)) {
            fail(SessionCatalogFailure.POLICY_MISMATCH)
        }
        return WebCatalogView(raw, control, result)
    }

    internal suspend fun read(
        lookups: List<JsonObject> = emptyList(),
        ids: List<String> = emptyList(),
        allowPolicyMismatch: Boolean = false,
    ): WebCatalogView = view(
        arguments = wireObject {
            put("lookups", JsonArray(lookups))
            put("metadataIds", JsonArray(ids.map(::JsonPrimitive)))
        },
        allowPolicyMismatch = allowPolicyMismatch,
    )

    internal suspend fun apply(plan: WebCatalogMutation): Boolean =
        database.executeCatalog("atomicWrite", plan.wire()).jsonPrimitive.boolean

    private suspend fun <T> mutate(
        lookups: List<JsonObject> = emptyList(),
        ids: List<String> = emptyList(),
        block: (WebCatalogView) -> Pair<WebCatalogMutation?, T>,
    ): T {
        repeat(ATTEMPTS) {
            currentCoroutineContext().ensureActive()
            val source = read(lookups, ids)
            val (plan, result) = block(source)
            if (plan == null || apply(plan)) return result
        }
        storageFailure()
    }

    internal fun mutation(
        view: WebCatalogView,
        control: WebCatalogControl = view.control.copy(revision = nextCatalogLong(view.control.revision)),
    ) = WebCatalogMutation(view, control)

    val migration: SessionCatalogMigration = WebCatalogDataMigration(this, lifecycle, json)

    override suspend fun state(): SessionCatalogState = lifecycle.withCatalogOperation { read().control.state() }
    override suspend fun sessionIdentity(id: AgentSessionId): SessionIdentity? = lifecycle.withCatalogOperation {
        read(ids = listOf(id.value)).metadata(id.value)?.identity
    }

    override suspend fun exists(id: AgentSessionId): Boolean = sessionIdentity(id) != null
    override suspend fun sessionHeader(
        id: AgentSessionId,
    ): SessionCatalogHeader? = lifecycle.withCatalogOperation {
        (read(listOf(lookup(CATALOG_ROWS, id.value))).value(CATALOG_ROWS, id.value) as? JsonObject)?.header()
    }

    override suspend fun organization(
        identity: SessionIdentity,
    ): SessionOrganization? = lifecycle.withCatalogOperation {
        val value = read(listOf(lookup(CATALOG_ORGANIZATION, identity.sessionId.value)), listOf(identity.sessionId.value))
        val metadata = value.metadata(identity.sessionId.value) ?: return@withCatalogOperation null
        checkIdentity(metadata, identity)
        value.value(CATALOG_ORGANIZATION, identity.sessionId.value).organizationFor(identity) ?: SessionOrganization()
    }

    override suspend fun collection(id: String): SessionCollection? = lifecycle.withCatalogOperation {
        (read(listOf(lookup(CATALOG_COLLECTIONS, id))).value(CATALOG_COLLECTIONS, id) as? JsonObject)?.collection()
    }

    override suspend fun prepareNewSession(
        id: AgentSessionId,
        creationToken: String,
        initialOrganization: SessionOrganization,
    ): SessionIdentity = lifecycle.withCatalogOperation {
        val identity = SessionIdentity(id, creationToken)
        val durable = read(ids = listOf(id.value)).metadata(id.value)
        if (durable != null) {
            checkIdentity(durable, identity)
            return@withCatalogOperation identity
        }
        require(creationToken != "legacy") { "The legacy incarnation is reserved for physical schema migration" }
        val prepared = PreparedSessionInitialization(identity, initialOrganization)
        val previous = initializations[id]
        if (previous != null && previous != prepared) fail(SessionCatalogFailure.INITIALIZATION_CONFLICT)
        initializations[id] = prepared
        identity
    }

    override suspend fun prepareActivity(
        identity: SessionIdentity,
        activityIdentity: String,
    ): SessionActivityTicket = lifecycle.withCatalogOperation {
        require(projector.activityMode == SessionActivityMode.PREPARED)
        require(activityIdentity.isNotBlank())
        val result = mutate(ids = listOf(identity.sessionId.value)) { source ->
            val metadata = source.metadata(identity.sessionId.value)
            if (metadata != null) {
                checkIdentity(metadata, identity)
            } else if (initializations[identity.sessionId]?.identity != identity) {
                fail(SessionCatalogFailure.STALE_IDENTITY)
            }
            when (val pending = intents[identity to activityIdentity]) {
                is PreparedActivityIntent.Advance -> return@mutate null to pending.ticket
                is PreparedActivityIntent.Preserve -> fail(SessionCatalogFailure.INITIALIZATION_CONFLICT)
                null -> Unit
            }
            val activity = metadata?.activity
            val accepted = activity?.order as? SessionActivityOrder.Accepted
            if (activity?.lastAcceptedIdentity == activityIdentity && accepted != null) {
                null to SessionActivityTicket(identity, activityIdentity, accepted.sequence)
            } else {
                val sequence = nextCatalogLong(source.control.sequence)
                mutation(source, source.control.copy(sequence = sequence)) to
                    SessionActivityTicket(identity, activityIdentity, sequence)
            }
        }
        intents[identity to activityIdentity] = PreparedActivityIntent.Advance(result)
        result
    }

    override suspend fun preparePreservedActivity(
        identity: SessionIdentity,
        targetActivityIdentity: String?,
    ) = lifecycle.withCatalogOperation {
        require(projector.activityMode == SessionActivityMode.PREPARED)
        val source = read(ids = listOf(identity.sessionId.value)).metadata(identity.sessionId.value)
            ?: fail(SessionCatalogFailure.SESSION_NOT_FOUND)
        checkIdentity(source, identity)
        if (intents[identity to targetActivityIdentity] is PreparedActivityIntent.Advance) {
            fail(SessionCatalogFailure.INITIALIZATION_CONFLICT)
        }
        intents[identity to targetActivityIdentity] = PreparedActivityIntent.Preserve(targetActivityIdentity)
    }

    override suspend fun releaseActivity(
        identity: SessionIdentity,
        activityIdentity: String?,
    ) = lifecycle.withCatalogOperation {
        intents.remove(identity to activityIdentity)
        Unit
    }

    override suspend fun releasePreparation(identity: SessionIdentity) = lifecycle.withCatalogOperation {
        if (initializations[identity.sessionId]?.identity == identity) initializations.remove(identity.sessionId)
        intents.keys.removeAll { it.first == identity }
        Unit
    }

    /** Called by the owning persistence under its shared lifecycle lock. */
    internal suspend fun commit(
        snapshot: AgentSessionSnapshot,
        checkpoint: AgentCheckpoint?,
        payload: String,
        checkpointPayload: String?,
        rewriteExpected: WebRawPersistenceRecord? = null,
    ): Boolean {
        val projection = projector.project(snapshot)
        val references = extractSessionAttachmentUris(snapshot, checkpoint)
        val id = snapshot.sessionId.value
        repeat(ATTEMPTS) {
            val source = read(listOf(lookup(CATALOG_ORGANIZATION, id)), listOf(id))
            val metadata = source.metadata(id)
            if (rewriteExpected != null && (metadata == null || metadata != rewriteExpected.metadata)) return false
            val initialization = initializations[snapshot.sessionId]
            val identity = metadata?.identity ?: initialization?.identity
                ?: SessionIdentity(snapshot.sessionId, SystemIdGenerator.nextId())
            if (initialization != null && initialization.identity != identity) fail(SessionCatalogFailure.STALE_IDENTITY)
            var previous = metadata?.activity
            var legacyExpected: WebRawPersistenceRecord? = null
            if (metadata != null && previous == null && projector.activityMode == SessionActivityMode.PREPARED) {
                val raw = database.get(id) ?: return@repeat
                if (raw.metadata != metadata) return@repeat
                val old = snapshotCodec.decode(raw.session.payload ?: throw WebStorageException(WebStorageFailure.CORRUPT_RECORD))
                require(old.sessionId == snapshot.sessionId)
                val oldProjection = projector.project(old)
                previous = SessionActivityFact(
                    oldProjection.activityIdentity,
                    null,
                    SessionActivityOrder.Legacy(oldProjection.activityAtEpochMs),
                )
                legacyExpected = raw
            }
            if (
                rewriteExpected != null && projector.activityMode == SessionActivityMode.PREPARED &&
                previous?.observedIdentity != projection.activityIdentity
            ) {
                fail(SessionCatalogFailure.MAINTENANCE_PROJECTION_FAILED)
            }
            val activity = if (rewriteExpected != null && previous != null) previous else resolveSessionActivity(
                projector.activityMode,
                projection,
                previous,
                intents[identity to projection.activityIdentity],
            )
            var organization = source.value(CATALOG_ORGANIZATION, id).organizationFor(identity)
                ?: if (metadata == null) initialization?.initialOrganization ?: SessionOrganization() else SessionOrganization()
            val collectionId = organization.collectionId
            if (collectionId != null) {
                val collection = read(listOf(lookup(CATALOG_COLLECTIONS, collectionId)))
                if (collection.stateWire != source.stateWire) return@repeat
                if (collection.value(CATALOG_COLLECTIONS, collectionId) == null) organization = organization.copy(collectionId = null)
            }
            val version = nextCatalogLong(metadata?.rowVersion ?: 0)
            val entry = materializeCatalogEntry(identity, version, snapshot, projection, organization, activity, projector)
            val plan = mutation(source)
            val expected = rewriteExpected ?: legacyExpected
            if (expected != null) addPayloadChecks(plan, id, expected)
            plan.puts += putRecord(
                WEB_SESSION_STORE,
                id,
                WebSessionMetadata(identity, version, activity, snapshot.state.status, checkpoint != null).record(payload),
            )
            if (checkpointPayload == null) {
                plan.deletes += lookup(WEB_CHECKPOINT_STORE, id)
            } else {
                plan.puts += putRecord(WEB_CHECKPOINT_STORE, id, JsonPrimitive(checkpointPayload))
            }
            organizationPlan(plan, identity, organization)
            projectionPlan(plan, id, entry, projection, references)
            if (apply(plan)) {
                initializations.remove(snapshot.sessionId)
                intents.remove(identity to projection.activityIdentity)
                return true
            }
            if (rewriteExpected != null) return false
        }
        storageFailure()
    }

    private fun addPayloadChecks(plan: WebCatalogMutation, id: String, raw: WebRawPersistenceRecord) {
        listOf(WEB_SESSION_STORE to raw.session.payload, WEB_CHECKPOINT_STORE to raw.checkpoint?.payload).forEach { (store, payload) ->
            plan.checks += wireObject {
                put("store", store)
                put("key", id)
                put("expectedPayload", payload)
            }
        }
    }

    internal fun organizationPlan(
        plan: WebCatalogMutation,
        identity: SessionIdentity,
        organization: SessionOrganization,
    ) {
        if (organization.isEmpty) {
            plan.deletes += lookup(CATALOG_ORGANIZATION, identity.sessionId.value)
        } else {
            plan.puts += putRecord(
                CATALOG_ORGANIZATION,
                identity.sessionId.value,
                JsonObject(organization.wire() + ("incarnation" to JsonPrimitive(identity.incarnation))),
            )
        }
    }

    internal fun projectionPlan(
        plan: WebCatalogMutation,
        id: String,
        entry: SessionCatalogEntry,
        projection: SessionCatalogProjection,
        references: Set<String>,
    ) {
        plan.puts += putRecord(CATALOG_ROWS, id, entry.rowWire(projection))
        plan.puts += putRecord(CATALOG_SEARCH, id, entry.searchWire())
        referenceSourcePlan(plan, id, "session", references)
    }

    internal fun referenceSourcePlan(
        plan: WebCatalogMutation,
        id: String,
        sourceKey: String,
        references: Set<String>,
    ) {
        plan.deleteRanges += wireObject {
            put("store", CATALOG_REFS)
            put("index", "session")
            put("sourceKey", sourceKey)
            put("range", onlyRange(JsonPrimitive(id)))
        }
        references.forEach { uri ->
            plan.puts += putRecord(
                CATALOG_REFS,
                buildJsonArray {
                    add(uri)
                    add(id)
                    add(sourceKey)
                },
                wireObject {
                    put("uri", uri)
                    put("sessionId", id)
                    put("sourceKey", sourceKey)
                },
            )
        }
    }

    internal suspend fun delete(id: AgentSessionId) {
        mutate(ids = listOf(id.value)) { source ->
            val exists = source.metadata(id.value) != null
            val plan = mutation(
                source,
                if (exists) source.control.copy(revision = nextCatalogLong(source.control.revision)) else source.control,
            )
            listOf(WEB_SESSION_STORE, WEB_CHECKPOINT_STORE, CATALOG_ROWS, CATALOG_SEARCH, CATALOG_ORGANIZATION)
                .forEach { plan.deletes += lookup(it, id.value) }
            plan.deleteRanges += wireObject {
                put("store", CATALOG_REFS)
                put("index", "session")
                put("range", onlyRange(JsonPrimitive(id.value)))
            }
            plan to Unit
        }
        initializations.remove(id)
        intents.keys.removeAll { it.first.sessionId == id }
    }

    internal suspend fun clear() {
        mutate { source ->
            val collectionsDone = source.control.collectionsComplete
            val next = source.control.copy(
                revision = nextCatalogLong(source.control.revision),
                generation = nextCatalogLong(source.control.generation),
                coverage = if (collectionsDone) CatalogCoverage.COMPLETE else CatalogCoverage.INDEXING,
                afterSession = null,
                afterSessionKey = null,
                afterCollection = null,
                processed = 0,
                failed = 0,
                sessionsComplete = true,
                recoveryIndexed = true,
                unindexedSources = 0,
            ).copy(
                migrationLifecycle = nextCatalogLong(source.control.migrationLifecycle),
                organizationImport = source.control.organizationImport?.let {
                    val receipt = SessionCatalogMigrationEncoding.receipt(it)
                    SessionCatalogMigrationEncoding.receipt(if (receipt.complete) receipt else receipt.copy(invalidated = true))
                },
            )
            mutation(source, next).apply {
                clears += listOf(WEB_SESSION_STORE, WEB_CHECKPOINT_STORE, CATALOG_ROWS, CATALOG_SEARCH, CATALOG_REFS, CATALOG_ORGANIZATION)
            } to Unit
        }
        initializations.clear()
        intents.clear()
    }

    override suspend fun updateOrganization(
        identity: SessionIdentity,
        mutation: SessionOrganizationMutation,
    ): SessionOrganization? =
        lifecycle.withCatalogOperation { updateOrganizationLocked(identity, mutation, onlyIfAbsent = false).second }
    override suspend fun setTitleIfAbsent(
        identity: SessionIdentity,
        title: String,
    ): Boolean = lifecycle.withCatalogOperation {
        updateOrganizationLocked(identity, SessionOrganizationMutation.Title(title), onlyIfAbsent = true).first
    }

    private suspend fun updateOrganizationLocked(
        identity: SessionIdentity,
        change: SessionOrganizationMutation,
        onlyIfAbsent: Boolean,
    ): Pair<Boolean, SessionOrganization?> {
        val id = identity.sessionId.value
        val lookups = mutableListOf(lookup(CATALOG_ORGANIZATION, id), lookup(CATALOG_ROWS, id))
        if (change is SessionOrganizationMutation.Collection && change.id != null) {
            lookups += lookup(CATALOG_COLLECTIONS, requireNotNull(change.id))
        }
        return mutate(lookups, listOf(id)) { source ->
            val metadata = source.metadata(id) ?: return@mutate null to (false to null)
            checkIdentity(metadata, identity)
            val previous = source.value(CATALOG_ORGANIZATION, id).organizationFor(identity) ?: SessionOrganization()
            if (onlyIfAbsent && previous.title != null) return@mutate null to (false to previous)
            val updated = when (change) {
                is SessionOrganizationMutation.Title -> previous.copy(title = change.value)
                is SessionOrganizationMutation.Pin -> previous.copy(pinnedAtEpochMs = change.pinnedAtEpochMs)
                is SessionOrganizationMutation.Collection -> {
                    if (change.id != null && source.value(CATALOG_COLLECTIONS, requireNotNull(change.id)) == null) {
                        fail(SessionCatalogFailure.COLLECTION_NOT_FOUND)
                    }
                    previous.copy(collectionId = change.id)
                }
            }
            if (previous == updated) return@mutate null to (onlyIfAbsent to updated)
            val plan = mutation(source)
            organizationPlan(plan, identity, updated)
            val row = source.value(CATALOG_ROWS, id) as? JsonObject
            if (row != null) {
                val header = row.header().copy(
                    label = updated.title ?: row.string("fallbackLabel"),
                    collectionId = updated.collectionId,
                    pinnedAtEpochMs = updated.pinnedAtEpochMs,
                )
                plan.puts += putRecord(CATALOG_ROWS, id, header.rowWire(row.string("fallbackLabel"), row.string("normalizedFallbackLabel")))
                val title = updated.title?.let(projector::normalizeSearch) ?: row.string("normalizedFallbackLabel")
                plan.patches += wireObject {
                    put("store", CATALOG_SEARCH)
                    put("key", id)
                    put("fields", wireObject { put("title", title) })
                }
            }
            plan to (true to updated)
        }
    }

    override suspend fun createCollection(collection: SessionCollection) = lifecycle.withCatalogOperation {
        writeCollection(collection, creating = true)
    }

    override suspend fun renameCollection(
        id: String,
        name: String,
        nameSortKey: String,
        nameUniqueKey: String,
    ) = lifecycle.withCatalogOperation {
        writeCollection(SessionCollection(id, name, nameSortKey, nameUniqueKey), creating = false)
    }

    private suspend fun writeCollection(replacement: SessionCollection, creating: Boolean) {
        val row = replacement.wire(projector)
        repeat(ATTEMPTS) {
            val source = read(listOf(lookup(CATALOG_COLLECTIONS, replacement.id)))
            val previous = source.value(CATALOG_COLLECTIONS, replacement.id) as? JsonObject
            if (creating && previous != null) fail(SessionCatalogFailure.COLLECTION_CONFLICT)
            if (!creating && previous == null) fail(SessionCatalogFailure.COLLECTION_NOT_FOUND)
            if (!creating && previous?.collection() == replacement) return
            val unique = view("keys", wireObject {
                put("store", CATALOG_COLLECTIONS)
                put("index", "uniqueName")
                put("range", onlyRange(JsonPrimitive(replacement.nameUniqueKey)))
                put("direction", "next")
                put("limit", 1)
            })
            if (unique.stateWire != source.stateWire) return@repeat
            if (unique.result.array("keys").any { it.jsonObject.getValue("primaryKey") != JsonPrimitive(replacement.id) }) {
                fail(SessionCatalogFailure.COLLECTION_CONFLICT)
            }
            if (apply(mutation(source).apply { puts += putRecord(CATALOG_COLLECTIONS, replacement.id, row) })) return
        }
        storageFailure()
    }

    override suspend fun deleteCollection(id: String) = lifecycle.withCatalogOperation {
        mutate(listOf(lookup(CATALOG_COLLECTIONS, id))) { source ->
            if (source.value(CATALOG_COLLECTIONS, id) == null) return@mutate null to Unit
            mutation(source).apply {
                deletes += lookup(CATALOG_COLLECTIONS, id)
                patches += wireObject {
                    put("store", CATALOG_ORGANIZATION)
                    put("index", "collection")
                    put("range", onlyRange(JsonPrimitive(id)))
                    put("fields", wireObject { put("collectionId", JsonNull) })
                }
                patches += wireObject {
                    put("store", CATALOG_ROWS)
                    put("index", "collection")
                    put("range", onlyRange(JsonPrimitive(CatalogKeyEncoding.collectionKey(id))))
                    put("fields", wireObject {
                        put("collectionId", JsonNull)
                        put("collectionKey", CatalogKeyEncoding.collectionKey(null))
                    })
                }
            } to Unit
        }
    }

    override suspend fun referencedUris(
        candidates: Set<String>,
    ): SessionAttachmentReferences = lifecycle.withCatalogOperation {
        repeat(ATTEMPTS) {
            val initial = read()
            val found = mutableSetOf<String>()
            var stable = true
            for (uri in candidates) {
                val result = view("keys", wireObject {
                    put("store", CATALOG_REFS)
                    put("index", "uri")
                    put("range", onlyRange(JsonPrimitive(uri)))
                    put("direction", "next")
                    put("limit", 1)
                })
                if (result.stateWire != initial.stateWire) {
                    stable = false
                    break
                }
                if (result.result.array("keys").isNotEmpty()) found += uri
            }
            if (stable) return@withCatalogOperation SessionAttachmentReferences(found, initial.control.coverage)
        }
        storageFailure()
    }

    override suspend fun pageSessions(
        query: SessionCatalogQuery,
        cursor: SessionCatalogCursor?,
        direction: CatalogPageDirection,
        limit: Int,
    ): SessionCatalogPage =
        lifecycle.withCatalogOperation {
            validateCatalogPageLimit(limit)
            val normalized = normalizeCatalogQuery(projector, query)
            val initial = read()
            validateCatalogCursor(cursor, normalized, initial.control.state())
            val result = sessionRows(normalized, cursor?.key, direction, limit + 1)
            validateCatalogCursor(cursor, normalized, result.control.state())
            val raw = result.result.array("values").map { it.jsonObject.header() }
            val selected = raw.take(limit).let { if (direction == CatalogPageDirection.BEFORE) it.reversed() else it }
            val state = result.control.state()
            val continuation = sessionContinuation(result, normalized, raw, limit, cursor?.key)
            fun edge(header: SessionCatalogHeader?) = header?.let {
                SessionCatalogCursor(normalized, state.policy, state.generation, sessionCatalogSortKey(it, normalized.sort))
            }
            SessionCatalogPage(
                selected,
                if (direction == CatalogPageDirection.BEFORE) continuation
                else if (cursor != null) edge(selected.firstOrNull()) ?: cursor else null,
                if (direction == CatalogPageDirection.AFTER) continuation
                else if (cursor != null) edge(selected.lastOrNull()) ?: cursor else null,
                continuation == null,
                state,
            )
        }

    private fun sessionKey(query: SessionCatalogQuery, key: SessionCatalogSortKey): JsonArray = buildJsonArray {
        when (val scope = query.scope) {
            SessionCatalogScope.All -> Unit
            SessionCatalogScope.Unassigned -> add(CatalogKeyEncoding.collectionKey(null))
            is SessionCatalogScope.Collection -> add(CatalogKeyEncoding.collectionKey(scope.id))
        }
        if (query.sort == SessionCatalogSort.PIN_THEN_ACTIVITY) {
            add(key.pinBucket)
            add(key.pinRank)
        } else add(if (query.pin == SessionPinFilter.PINNED) 0 else 1)
        add(key.activityRank)
        add(key.idSortKey)
    }

    private suspend fun sessionRows(
        query: SessionCatalogQuery,
        key: SessionCatalogSortKey?,
        direction: CatalogPageDirection,
        limit: Int,
        only: Boolean = false,
    ): WebCatalogView {
        val hasCollection = query.scope != SessionCatalogScope.All
        val index = (if (hasCollection) "collection" else "global") +
            (if (query.sort == SessionCatalogSort.PIN_THEN_ACTIVITY) "_pin" else "_activity")
        val prefix = buildJsonArray {
            when (val scope = query.scope) {
                SessionCatalogScope.All -> Unit
                SessionCatalogScope.Unassigned -> add(CatalogKeyEncoding.collectionKey(null))
                is SessionCatalogScope.Collection -> add(CatalogKeyEncoding.collectionKey(scope.id))
            }
            if (query.pin != SessionPinFilter.ANY) add(if (query.pin == SessionPinFilter.PINNED) 0 else 1)
        }
        val range = if (only) onlyRange(sessionKey(query, requireNotNull(key))) else wireObject {
            if (prefix.isNotEmpty()) {
                put("lower", prefix)
                put("upper", JsonArray(prefix + listOf(JsonArray(emptyList()))))
                put("upperOpen", true)
            }
            if (key != null) {
                val name = if (direction == CatalogPageDirection.AFTER) "lower" else "upper"
                put(name, sessionKey(query, key))
                put("${name}Open", true)
            }
        }
        return view("page", wireObject {
            put("store", CATALOG_ROWS)
            put("index", index)
            put("range", range)
            put("direction", if (direction == CatalogPageDirection.AFTER) "next" else "prev")
            put("limit", limit)
            if (query.search.isNotEmpty()) put("search", wireObject {
                put("store", CATALOG_SEARCH)
                put("needle", query.search)
                put("fields", JsonArray(listOf("title", "preview", "model").map(::JsonPrimitive)))
            })
        })
    }

    private fun sessionContinuation(
        result: WebCatalogView,
        query: SessionCatalogQuery,
        rows: List<SessionCatalogHeader>,
        limit: Int,
        startingKey: SessionCatalogSortKey?,
    ): SessionCatalogCursor? {
        val key = if (rows.size > limit) {
            if (limit == 0) startingKey else sessionCatalogSortKey(rows[limit - 1], query.sort)
        } else if (!result.result.boolean("scanExhausted")) {
            (result.result["lastScannedKey"] as? JsonArray)?.let { scanned ->
                val end = scanned.size
                SessionCatalogSortKey(
                    if (query.sort == SessionCatalogSort.PIN_THEN_ACTIVITY) scanned[end - 4].jsonPrimitive.int else 0,
                    if (query.sort == SessionCatalogSort.PIN_THEN_ACTIVITY) scanned[end - 3].jsonPrimitive.content else "",
                    scanned[end - 2].jsonPrimitive.content,
                    scanned[end - 1].jsonPrimitive.content,
                )
            }
        } else null
        return key?.let { SessionCatalogCursor(query, result.control.policy, result.control.generation, it) }
    }

    override suspend fun windowAround(
        id: AgentSessionId,
        query: SessionCatalogQuery,
        limit: Int,
    ): SessionCatalogPage = lifecycle.withCatalogOperation {
        validateCatalogPageLimit(limit)
        val normalized = normalizeCatalogQuery(projector, query)
        repeat(ATTEMPTS) {
            val initial = read(listOf(lookup(CATALOG_ROWS, id.value)))
            val header = (initial.value(CATALOG_ROWS, id.value) as? JsonObject)?.header()
                ?: return@withCatalogOperation SessionCatalogPage(emptyList(), null, null, true, initial.control.state())
            val scopeMatches = when (val scope = normalized.scope) {
                SessionCatalogScope.All -> true
                SessionCatalogScope.Unassigned -> header.collectionId == null
                is SessionCatalogScope.Collection -> header.collectionId == scope.id
            }
            val pinMatches = normalized.pin == SessionPinFilter.ANY ||
                (normalized.pin == SessionPinFilter.PINNED) == (header.pinnedAtEpochMs != null)
            if (!scopeMatches || !pinMatches) {
                return@withCatalogOperation SessionCatalogPage(emptyList(), null, null, true, initial.control.state())
            }
            val key = sessionCatalogSortKey(header, normalized.sort)
            val anchor = sessionRows(normalized, key, CatalogPageDirection.AFTER, 1, only = true)
            if (anchor.stateWire != initial.stateWire) return@repeat
            if (anchor.result.array("values").isEmpty()) {
                return@withCatalogOperation SessionCatalogPage(emptyList(), null, null, true, initial.control.state())
            }
            val beforeCount = (limit - 1) / 2
            val before = sessionRows(normalized, key, CatalogPageDirection.BEFORE, beforeCount + 1)
            if (before.stateWire != initial.stateWire) return@repeat
            val preceding = before.result.array("values").map { it.jsonObject.header() }
            val afterCount = limit - preceding.take(beforeCount).size - 1
            val after = sessionRows(normalized, key, CatalogPageDirection.AFTER, afterCount + 1)
            if (after.stateWire != initial.stateWire) return@repeat
            val following = after.result.array("values").map { it.jsonObject.header() }
            val previousCursor = sessionContinuation(before, normalized, preceding, beforeCount, key)
            val nextCursor = sessionContinuation(after, normalized, following, afterCount, key)
            return@withCatalogOperation SessionCatalogPage(
                preceding.take(beforeCount).reversed() + header + following.take(afterCount),
                previousCursor,
                nextCursor,
                previousCursor == null && nextCursor == null,
                initial.control.state(),
            )
        }
        storageFailure()
    }

    override suspend fun pageCollections(
        query: SessionCollectionQuery,
        cursor: SessionCollectionCursor?,
        direction: CatalogPageDirection,
        limit: Int,
    ): SessionCollectionPage =
        lifecycle.withCatalogOperation {
            validateCatalogPageLimit(limit)
            val normalized = SessionCollectionQuery(projector.normalizeSearch(query.search))
            val range = wireObject {
                if (cursor != null) {
                    val name = if (direction == CatalogPageDirection.AFTER) "lower" else "upper"
                    put(name, buildJsonArray {
                        add(cursor.nameSortKey)
                        add(cursor.idSortKey)
                    })
                    put("${name}Open", true)
                }
            }
            val result = view("page", wireObject {
                put("store", CATALOG_COLLECTIONS)
                put("index", "name")
                put("range", range)
                put("direction", if (direction == CatalogPageDirection.AFTER) "next" else "prev")
                put("limit", limit + 1)
                if (normalized.search.isNotEmpty()) put("search", wireObject {
                    put("store", CATALOG_COLLECTIONS)
                    put("needle", normalized.search)
                    put("fields", buildJsonArray { add("normalizedName") })
                })
            })
            val state = result.control.state()
            if (
                cursor != null &&
                (cursor.query != normalized || cursor.policy != state.policy || cursor.generation != state.generation)
            ) {
                fail(SessionCatalogFailure.INVALID_CURSOR)
            }
            val rows = result.result.array("values").map { it.jsonObject.collection() }
            val extra = rows.size > limit
            val selected = rows.take(limit).let { if (direction == CatalogPageDirection.BEFORE) it.reversed() else it }
            fun token(row: SessionCollection) = SessionCollectionCursor(
                normalized,
                state.policy,
                state.generation,
                row.nameSortKey,
                CatalogKeyEncoding.stringKey(row.id),
            )
            val continuation = if (extra) token(rows[limit - 1]) else if (!result.result.boolean("scanExhausted")) {
                (result.result["lastScannedKey"] as? JsonArray)?.let {
                    SessionCollectionCursor(
                        normalized,
                        state.policy,
                        state.generation,
                        it[0].jsonPrimitive.content,
                        it[1].jsonPrimitive.content,
                    )
                }
            } else null
            SessionCollectionPage(
                selected,
                if (direction == CatalogPageDirection.BEFORE) continuation
                else if (cursor != null) selected.firstOrNull()?.let(::token) ?: cursor else null,
                if (direction == CatalogPageDirection.AFTER) continuation
                else if (cursor != null) selected.lastOrNull()?.let(::token) ?: cursor else null,
                continuation == null,
                state,
            )
        }

    override suspend fun pageRecoveryCandidates(
        cursor: SessionRecoveryCursor?,
        limit: Int,
    ): SessionRecoveryPage = lifecycle.withCatalogOperation {
        validateCatalogPageLimit(limit)
        repeat(ATTEMPTS) {
            val initial = read()
            val state = initial.control.state()
            if (cursor != null && (cursor.policy != state.policy || cursor.generation != state.generation)) {
                fail(SessionCatalogFailure.INVALID_CURSOR)
            }
            val entries = mutableListOf<SessionRecoveryCandidate>()
            val after = cursor?.afterSessionId?.value
            val indexed = initial.control.recoveryIndexed
            val scanLimit = if (indexed) limit + 1 else MAX_CATALOG_PAGE_SIZE
            val ids = view("keys", wireObject {
                put("store", WEB_SESSION_STORE)
                put("direction", "next")
                put("limit", scanLimit)
                if (indexed) put("index", "recovery")
                put("range", wireObject {
                    if (indexed) {
                        put("lower", if (after == null) buildJsonArray { add(1) } else buildJsonArray {
                            add(1)
                            add(after)
                        })
                        put("lowerOpen", after != null)
                        put("upper", buildJsonArray {
                            add(1)
                            add(JsonArray(emptyList()))
                        })
                        put("upperOpen", true)
                    } else {
                        put("lower", after ?: "")
                        put("lowerOpen", after != null)
                    }
                })
            })
            if (ids.stateWire != initial.stateWire) return@repeat
            val physicalKeys = ids.result.array("keys")
            val keys = physicalKeys.mapNotNull { it.jsonObject.stringPrimaryKey() }
            val metadata = read(ids = keys)
            if (metadata.stateWire != initial.stateWire) return@repeat
            keys.forEach { id ->
                val row = metadata.metadata(id) ?: return@forEach
                val reasons = buildSet {
                    if (row.status == null) add(SessionRecoveryReason.UNKNOWN_SOURCE)
                    else addAll(sessionRecoveryReasons(row.status, row.hasCheckpoint))
                    if (row.hasCheckpoint) add(SessionRecoveryReason.CHECKPOINT)
                }
                if (reasons.isNotEmpty()) entries += SessionRecoveryCandidate(AgentSessionId(id), row.identity, reasons)
            }
            val selected = entries.take(limit)
            val nextId = if (entries.size > limit) selected.last().sessionId
                else if (physicalKeys.size == scanLimit) keys.lastOrNull()?.let(::AgentSessionId) else null
            return@withCatalogOperation SessionRecoveryPage(selected,
                nextId?.let { SessionRecoveryCursor(state.policy, state.generation, it) }, nextId == null, state, initial.control.coverage)
        }
        storageFailure()
    }

    val maintenance: SessionCatalogMaintenance = object : SessionCatalogMaintenance {
        override suspend fun status(): SessionCatalogMaintenanceProgress = lifecycle.withCatalogOperation {
            read().control.progress()
        }

        override suspend fun rebuild(
            expectedPolicy: SessionCatalogPolicy,
            expectedGeneration: Long,
        ): SessionCatalogMaintenanceProgress = lifecycle.withCatalogOperation {
            repeat(ATTEMPTS) {
                val source = read(allowPolicyMismatch = true)
                if (source.control.policy != expectedPolicy || source.control.generation != expectedGeneration) {
                    fail(SessionCatalogFailure.STALE_MAINTENANCE)
                }
                if (source.control.activityMode != projector.activityMode) fail(SessionCatalogFailure.POLICY_MISMATCH)
                val next = source.control.copy(
                    policy = projector.policy,
                    revision = nextCatalogLong(source.control.revision),
                    generation = nextCatalogLong(source.control.generation),
                    coverage = CatalogCoverage.INDEXING,
                    afterSession = null,
                    afterSessionKey = null,
                    afterCollection = null,
                    processed = 0,
                    failed = 0,
                    sessionsComplete = false,
                    collectionsComplete = false,
                    unindexedSources = 0,
                )
                val plan = mutation(source, next).apply { clears += listOf(CATALOG_ROWS, CATALOG_SEARCH, CATALOG_REFS) }
                if (apply(plan)) return@withCatalogOperation next.progress()
            }
            storageFailure()
        }

        override suspend fun runBatch(limit: Int): SessionCatalogMaintenanceBatch = maintenanceMutex.withLock {
            require(limit in 1..MAX_CATALOG_MAINTENANCE_BATCH_SIZE)
            var expected = lifecycle.withCatalogOperation { read().control }
            var remaining = limit
            var indexed = 0
            var skipped = 0
            val issues = mutableListOf<SessionCatalogMaintenanceIssue>()
            while (remaining > 0 && (!expected.collectionsComplete || !expected.sessionsComplete)) {
                currentCoroutineContext().ensureActive()
                val collectionPhase = !expected.collectionsComplete
                val store = if (collectionPhase) CATALOG_COLLECTIONS else WEB_SESSION_STORE
                val after = if (collectionPhase) expected.afterCollection else expected.afterSession
                val keyResult = lifecycle.withCatalogOperation {
                    view("keys", wireObject {
                        put("store", store)
                        put("direction", "next")
                        put("limit", 1)
                        if (!collectionPhase && expected.afterSessionKey != null) put("range", wireObject {
                            put("lowerToken", requireNotNull(expected.afterSessionKey))
                            put("lowerOpen", true)
                        }) else if (after != null) put("range", wireObject {
                            put("lower", after)
                            put("lowerOpen", true)
                        })
                    }).also { checkMaintenancePosition(expected, it.control) }
                }
                val physicalKey = keyResult.result.array("keys").firstOrNull()?.jsonObject
                if (physicalKey == null) {
                    expected = lifecycle.withCatalogOperation {
                        maintenanceMutation(expected) { source ->
                            val next = if (collectionPhase) source.control.copy(collectionsComplete = true)
                            else source.control.copy(
                                sessionsComplete = true,
                                recoveryIndexed = source.control.unindexedSources == 0L,
                            )
                            mutation(source, finishCoverage(next.copy(revision = nextCatalogLong(next.revision))))
                        }.control
                    }
                    continue
                }
                val id = physicalKey.stringPrimaryKey()
                if (id == null) {
                    // Legacy SDKs only address string IDs. Preserve malformed physical sources byte for byte.
                    if (collectionPhase) fail(SessionCatalogFailure.MAINTENANCE_PROJECTION_FAILED)
                    expected = lifecycle.withCatalogOperation {
                        maintenanceMutation(expected) { source ->
                            mutation(
                                source,
                                source.control.copy(
                                    revision = nextCatalogLong(source.control.revision),
                                    afterSessionKey = physicalKey.getValue("primaryKeyToken"),
                                    processed = nextCatalogLong(source.control.processed),
                                    failed = nextCatalogLong(source.control.failed),
                                    coverage = CatalogCoverage.PARTIAL,
                                ),
                            )
                        }.control
                    }
                    remaining--
                    issues += SessionCatalogMaintenanceIssue(null, SessionCatalogMaintenanceIssueKind.UNREADABLE)
                    continue
                }
                val captured = lifecycle.withCatalogOperation {
                    read(
                        listOf(lookup(store, id)) +
                            if (collectionPhase) emptyList() else listOf(lookup(WEB_CHECKPOINT_STORE, id)),
                        if (collectionPhase) emptyList() else listOf(id),
                    ).also { checkMaintenancePosition(expected, it.control) }
                }
                val raw = captured.value(store, id)
                val checkpointRaw = captured.value(WEB_CHECKPOINT_STORE, id)
                val computed = if (collectionPhase) null else computeMaintenance(id, raw, checkpointRaw)
                if (collectionPhase && raw != null && raw !is JsonObject) fail(SessionCatalogFailure.MAINTENANCE_PROJECTION_FAILED)
                val normalizedCollection = if (collectionPhase && raw is JsonObject) try {
                    projector.normalizeSearch(raw.string("name"))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    fail(SessionCatalogFailure.MAINTENANCE_PROJECTION_FAILED)
                } else null
                remaining--
                var raced = false
                var issue: SessionCatalogMaintenanceIssue? = null
                var installed = false
                val result = lifecycle.withCatalogOperation {
                    var result: WebCatalogView? = null
                    repeat(ATTEMPTS) attempts@{
                        if (result != null || raced) return@attempts
                        val source = read(
                            listOf(lookup(CATALOG_ORGANIZATION, id)) +
                                if (collectionPhase) listOf(lookup(store, id)) else emptyList(),
                            if (collectionPhase) emptyList() else listOf(id),
                        )
                        checkMaintenancePosition(expected, source.control)
                        if (if (collectionPhase) source.value(store, id) != raw else source.metadata(id) != captured.metadata(id)) {
                            raced = true
                            result = source
                            return@attempts
                        }
                        var next = source.control.copy(revision = nextCatalogLong(source.control.revision))
                        if (collectionPhase) next = next.copy(afterCollection = id)
                        else next = next.copy(
                            afterSession = id,
                            afterSessionKey = physicalKey.getValue("primaryKeyToken"),
                            processed = nextCatalogLong(next.processed),
                        )
                        var plan = mutation(source, next)
                        plan.checks += exactCheck(store, id, raw)
                        if (!collectionPhase) plan.checks += exactCheck(WEB_CHECKPOINT_STORE, id, checkpointRaw)
                        if (collectionPhase) {
                            if (raw != null) plan.puts += putRecord(
                                store,
                                id,
                                JsonObject(raw.jsonObject + ("normalizedName" to JsonPrimitive(requireNotNull(normalizedCollection)))),
                            )
                        } else if (computed is MaintenanceComputed.Projected && raw != null) {
                            val old = captured.metadata(id) ?: fail(SessionCatalogFailure.STALE_IDENTITY)
                            val activity = old.activity ?: SessionActivityFact(
                                computed.projection.activityIdentity,
                                null,
                                SessionActivityOrder.Legacy(computed.projection.activityAtEpochMs),
                            )
                            val projectionConsistent = projector.activityMode != SessionActivityMode.PREPARED || old.activity == null ||
                                old.activity.observedIdentity == computed.projection.activityIdentity
                            val organization = source.value(CATALOG_ORGANIZATION, id).organizationFor(old.identity) ?: SessionOrganization()
                            val version = if (
                                old.activity == null || computed.snapshotRewrite != null || computed.checkpointRewrite != null
                            ) nextCatalogLong(old.rowVersion) else old.rowVersion
                            val entry = if (projectionConsistent) try {
                                materializeCatalogEntry(
                                    old.identity,
                                    version,
                                    computed.snapshot,
                                    computed.projection,
                                    organization,
                                    activity,
                                    projector,
                                )
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                null
                            } else null
                            if (entry != null) {
                                plan.puts += putRecord(
                                    WEB_SESSION_STORE,
                                    id,
                                    WebSessionMetadata(
                                        old.identity,
                                        version,
                                        activity,
                                        computed.snapshot.state.status,
                                        checkpointRaw != null,
                                    ).record(computed.snapshotRewrite ?: requireNotNull(raw.rawPayload())),
                                )
                                computed.checkpointRewrite?.let { plan.puts += putRecord(WEB_CHECKPOINT_STORE, id, JsonPrimitive(it)) }
                                projectionPlan(plan, id, entry, computed.projection, computed.references)
                                computed.backupReferences?.let { referenceSourcePlan(plan, id, "attachment-name-backup", it) }
                                installed = true
                            } else issue = SessionCatalogMaintenanceIssue(
                                AgentSessionId(id),
                                SessionCatalogMaintenanceIssueKind.PROJECTION_FAILED,
                            )
                        } else if (computed is MaintenanceComputed.Failed) {
                            issue = SessionCatalogMaintenanceIssue(AgentSessionId(id), computed.kind)
                        }

                        if (issue != null) {
                            next = next.copy(failed = nextCatalogLong(next.failed), coverage = CatalogCoverage.PARTIAL)
                            // Adding an IDB wrapper is structural only: opaque payload bytes remain exact.
                            // It makes unreadable legacy strings discoverable by the recovery index.
                            if (captured.metadata(id)?.status == null && raw is JsonPrimitive && raw.isString) {
                                val old = requireNotNull(captured.metadata(id))
                                plan.puts += putRecord(
                                    WEB_SESSION_STORE,
                                    id,
                                    old.copy(rowVersion = nextCatalogLong(old.rowVersion)).record(raw.content),
                                )
                            } else if (raw !is JsonObject || raw["metadataKey"] !is JsonArray) {
                                next = next.copy(unindexedSources = nextCatalogLong(next.unindexedSources))
                            }
                            plan = WebCatalogMutation(source, next).also { replacement ->
                                replacement.checks += plan.checks
                                replacement.puts += plan.puts
                            }
                        }
                        if (apply(plan)) result = WebCatalogView(next.wire(), next, wireObject { })
                        else {
                            issue = null
                            installed = false
                        }
                    }
                    result ?: return@withCatalogOperation read().also {
                        checkMaintenancePosition(expected, it.control)
                        raced = true
                    }
                }
                expected = result.control
                if (raced) {
                    skipped++
                    break
                }
                if (installed) indexed++
                if (computed is MaintenanceComputed.Missing) skipped++
                issue?.let(issues::add)
            }
            val current = lifecycle.withCatalogOperation { read().also { checkMaintenancePosition(expected, it.control) } }
            SessionCatalogMaintenanceBatch(current.control.progress(), indexed, skipped, issues)
        }
    }

    private sealed interface MaintenanceComputed {
        data class Projected(
            val snapshot: AgentSessionSnapshot,
            val snapshotRewrite: String?,
            val checkpointRewrite: String?,
            val projection: SessionCatalogProjection,
            val references: Set<String>,
            val backupReferences: Set<String>?,
        ) : MaintenanceComputed
        data class Failed(val kind: SessionCatalogMaintenanceIssueKind) : MaintenanceComputed
        data object Missing : MaintenanceComputed
    }

    private fun computeMaintenance(
        id: String,
        raw: JsonElement?,
        checkpointRaw: JsonElement?,
    ): MaintenanceComputed {
        if (raw == null) return MaintenanceComputed.Missing
        val snapshot: StoredEnvelopeDecodeResult<AgentSessionSnapshot>
        val checkpoint: StoredEnvelopeDecodeResult<AgentCheckpoint>?
        val backupReferences: Set<String>?
        try {
            snapshot = snapshotCodec.decodeResult(
                raw.rawPayload() ?: return MaintenanceComputed.Failed(SessionCatalogMaintenanceIssueKind.UNREADABLE),
            )
            checkpoint = checkpointRaw?.let {
                checkpointCodec.decodeResult(
                    it.rawPayload() ?: return MaintenanceComputed.Failed(SessionCatalogMaintenanceIssueKind.UNREADABLE),
                )
            }
            if (snapshot.value.sessionId.value != id) return MaintenanceComputed.Failed(SessionCatalogMaintenanceIssueKind.UNREADABLE)
            AgentPersistenceRecord(snapshot.value, checkpoint?.value)
            backupReferences = (raw as? JsonObject)?.optionalString("attachmentNameBackup")?.let {
                val backup = SessionCatalogMigrationEncoding.backup(it)
                require(
                    backup.identity.sessionId.value == id &&
                        backup.identity.incarnation == raw.array("metadataKey")[1].jsonPrimitive.content,
                )
                attachmentNameBackupUris(backup, json)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: StoredEnvelopeDecodeException) {
            return MaintenanceComputed.Failed(
                if (failure.failure == StoredEnvelopeDecodeFailure.CORRUPT) SessionCatalogMaintenanceIssueKind.UNREADABLE
                else SessionCatalogMaintenanceIssueKind.INCOMPATIBLE_SCHEMA,
            )
        } catch (_: Exception) {
            return MaintenanceComputed.Failed(SessionCatalogMaintenanceIssueKind.UNREADABLE)
        }
        val projection = try {
            projector.project(snapshot.value)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return MaintenanceComputed.Failed(SessionCatalogMaintenanceIssueKind.PROJECTION_FAILED)
        }
        return MaintenanceComputed.Projected(
            snapshot.value,
            snapshot.rewritePayload,
            checkpoint?.rewritePayload,
            projection,
            extractSessionAttachmentUris(snapshot.value, checkpoint?.value),
            backupReferences,
        )
    }

    internal fun exactCheck(store: String, id: String, value: JsonElement?) = wireObject {
        put("store", store)
        put("key", id)
        put("expectedFound", value != null)
        put("expectedValue", value ?: JsonNull)
    }

    private suspend fun maintenanceMutation(
        expected: WebCatalogControl,
        block: (WebCatalogView) -> WebCatalogMutation,
    ): WebCatalogView {
        repeat(ATTEMPTS) {
            val source = read()
            checkMaintenancePosition(expected, source.control)
            val plan = block(source)
            if (apply(plan)) return WebCatalogView(plan.control.wire(), plan.control, wireObject { })
        }
        storageFailure()
    }

    private fun finishCoverage(control: WebCatalogControl): WebCatalogControl =
        if (control.sessionsComplete && control.collectionsComplete) {
            control.copy(coverage = if (control.failed == 0L) CatalogCoverage.COMPLETE else CatalogCoverage.PARTIAL)
        } else control

    private fun checkMaintenancePosition(expected: WebCatalogControl, current: WebCatalogControl) {
        if (
            expected.policy != current.policy || expected.generation != current.generation ||
            expected.afterSession != current.afterSession || expected.afterCollection != current.afterCollection ||
            expected.afterSessionKey != current.afterSessionKey || expected.sessionsComplete != current.sessionsComplete ||
            expected.collectionsComplete != current.collectionsComplete || expected.processed != current.processed ||
            expected.failed != current.failed
        ) {
            fail(SessionCatalogFailure.STALE_MAINTENANCE)
        }
    }

    internal fun closed() {
        initializations.clear()
        intents.clear()
    }

    private fun checkIdentity(metadata: WebSessionMetadata, identity: SessionIdentity) {
        if (metadata.identity != identity) fail(SessionCatalogFailure.STALE_IDENTITY)
    }

    private fun fail(failure: SessionCatalogFailure): Nothing = throw SessionCatalogException(failure)
    private fun storageFailure(): Nothing = throw WebStorageException(WebStorageFailure.OPERATION_FAILED)
    private companion object {
        const val ATTEMPTS = 8
    }
}
