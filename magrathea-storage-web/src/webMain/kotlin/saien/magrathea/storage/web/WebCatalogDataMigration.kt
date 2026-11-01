package saien.magrathea.storage.web

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import saien.magrathea.catalog.LegacySessionOrganization
import saien.magrathea.catalog.MAX_CATALOG_MIGRATION_BATCH_SIZE
import saien.magrathea.catalog.OrganizationImportReceipt
import saien.magrathea.catalog.OrganizationImportToken
import saien.magrathea.catalog.SessionActivityFact
import saien.magrathea.catalog.SessionActivityMode
import saien.magrathea.catalog.SessionActivityOrder
import saien.magrathea.catalog.SessionAttachmentName
import saien.magrathea.catalog.SessionAttachmentNameBackup
import saien.magrathea.catalog.SessionAttachmentNamePage
import saien.magrathea.catalog.SessionAttachmentNamePatchResult
import saien.magrathea.catalog.SessionAttachmentNameScanOutcome
import saien.magrathea.catalog.SessionAttachmentNameScanProgress
import saien.magrathea.catalog.SessionAttachmentNameScanToken
import saien.magrathea.catalog.SessionAttachmentNameSource
import saien.magrathea.catalog.SessionAttachmentNameTarget
import saien.magrathea.catalog.SessionCatalogException
import saien.magrathea.catalog.SessionCatalogFailure
import saien.magrathea.catalog.SessionCatalogMaintenanceIssue
import saien.magrathea.catalog.SessionCatalogMaintenanceIssueKind
import saien.magrathea.catalog.SessionCatalogMigration
import saien.magrathea.catalog.SessionCatalogMigrationEncoding
import saien.magrathea.catalog.SessionCollection
import saien.magrathea.catalog.SessionIdentity
import saien.magrathea.catalog.SessionOrganization
import saien.magrathea.catalog.advanceAttachmentNameScanProgress
import saien.magrathea.catalog.attachmentNameBackupUris
import saien.magrathea.catalog.attachmentNameMap
import saien.magrathea.catalog.attachmentNameSessionCursor
import saien.magrathea.catalog.extractSessionAttachmentUris
import saien.magrathea.catalog.fillAttachmentNames
import saien.magrathea.catalog.materializeCatalogEntry
import saien.magrathea.catalog.sessionAttachmentNameTarget
import saien.magrathea.catalog.validateOrganizationImportBatch
import saien.magrathea.core.AgentCheckpointCodec
import saien.magrathea.core.AgentPersistenceRecord
import saien.magrathea.core.AgentSessionId
import saien.magrathea.core.AgentSessionSnapshotCodec
import saien.magrathea.core.SystemIdGenerator

internal class WebCatalogDataMigration(
    private val catalog: WebSessionCatalog,
    private val lifecycle: WebStoreLifecycle,
    private val json: Json,
) : SessionCatalogMigration {
    private val snapshotCodec = AgentSessionSnapshotCodec(json)
    private val checkpointCodec = AgentCheckpointCodec(json)
    private fun WebCatalogControl.receipt() = organizationImport?.let(SessionCatalogMigrationEncoding::receipt)

    override suspend fun organizationImportAllowed(): Boolean = lifecycle.withCatalogOperation {
        val control = catalog.read().control
        control.migrationLifecycle == 0L && control.receipt()?.invalidated != true
    }

    override suspend fun organizationImportStatus(): OrganizationImportReceipt? = lifecycle.withCatalogOperation {
        catalog.read().control.receipt()
    }

    override suspend fun beginOrganizationImport(
        sourceId: String,
        fingerprint: String,
    ): OrganizationImportReceipt = lifecycle.withCatalogOperation {
        require(sourceId.isNotBlank() && fingerprint.isNotBlank())
        repeat(8) {
            val source = catalog.read()
            source.control.receipt()?.let {
                if (it.token.sourceId != sourceId || it.token.fingerprint != fingerprint) fail(SessionCatalogFailure.MIGRATION_CONFLICT)
                if (it.invalidated) fail(SessionCatalogFailure.STALE_MIGRATION)
                return@withCatalogOperation it
            }
            if (source.control.migrationLifecycle != 0L) fail(SessionCatalogFailure.STALE_MIGRATION)
            val storeId = source.control.migrationStoreId ?: SystemIdGenerator.nextId()
            val receipt = OrganizationImportReceipt(OrganizationImportToken(storeId, 0L, sourceId, fingerprint))
            val next = source.control.copy(
                revision = nextCatalogLong(source.control.revision),
                migrationStoreId = storeId,
                organizationImport = SessionCatalogMigrationEncoding.receipt(receipt),
            )
            if (catalog.apply(catalog.mutation(source, next))) return@withCatalogOperation receipt
        }
        failure()
    }

    override suspend fun importOrganizationBatch(
        token: OrganizationImportToken,
        batchIndex: Long,
        collections: List<SessionCollection>,
        organizations: List<LegacySessionOrganization>,
        complete: Boolean,
    ): OrganizationImportReceipt = lifecycle.withCatalogOperation {
        validateOrganizationImportBatch(collections, organizations)
        require(batchIndex >= 0)
        val signature = SessionCatalogMigrationEncoding.batch(collections, organizations, complete)
        val collectionValues = collections.associate { it.id to it.wire(catalog.projector) }
        val lookups = (collections.map { it.id } + organizations.mapNotNull { it.organization.collectionId })
            .distinct().map { lookup(CATALOG_COLLECTIONS, it) } +
            organizations.flatMap { listOf(lookup(CATALOG_ORGANIZATION, it.sessionId.value), lookup(CATALOG_ROWS, it.sessionId.value)) }
        repeat(8) {
            val source = catalog.read(lookups, organizations.map { it.sessionId.value })
            val previous = source.control.receipt() ?: fail(SessionCatalogFailure.STALE_MIGRATION)
            if (previous.token != token || previous.invalidated) fail(SessionCatalogFailure.STALE_MIGRATION)
            if (batchIndex < previous.nextBatch) {
                if (batchIndex == previous.nextBatch - 1 && source.control.organizationImportLastBatch != signature) {
                    fail(SessionCatalogFailure.MIGRATION_CONFLICT)
                }
                return@withCatalogOperation previous
            }
            if (previous.complete || batchIndex != previous.nextBatch) fail(SessionCatalogFailure.MIGRATION_CONFLICT)
            var stable = true
            for (value in collections) {
                val old = (source.value(CATALOG_COLLECTIONS, value.id) as? JsonObject)?.collection()
                if (old != null && old != value) fail(SessionCatalogFailure.COLLECTION_CONFLICT)
                val unique = catalog.view("keys", wireObject {
                    put("store", CATALOG_COLLECTIONS)
                    put("index", "uniqueName")
                    put("range", onlyRange(JsonPrimitive(value.nameUniqueKey)))
                    put("direction", "next")
                    put("limit", 1)
                })
                if (source.stateWire != unique.stateWire) {
                    stable = false
                    break
                }
                if (unique.result.array("keys").any { it.jsonObject.stringPrimaryKey() != value.id }) {
                    fail(SessionCatalogFailure.COLLECTION_CONFLICT)
                }
            }
            if (!stable) return@repeat
            var imported = 0L
            var skipped = 0L
            val plan = catalog.mutation(source)
            collections.forEach { value -> plan.puts += putRecord(CATALOG_COLLECTIONS, value.id, collectionValues.getValue(value.id)) }
            organizations.forEach { value ->
                val id = value.sessionId.value
                val metadata = source.metadata(id)
                if (metadata == null || metadata.identity.incarnation != "legacy") {
                    skipped++
                    return@forEach
                }
                value.organization.collectionId?.let {
                    if (it !in collectionValues && source.value(CATALOG_COLLECTIONS, it) == null) {
                        fail(SessionCatalogFailure.COLLECTION_NOT_FOUND)
                    }
                }
                val old = source.value(CATALOG_ORGANIZATION, id).organizationFor(metadata.identity)
                if (old != null && old != value.organization) fail(SessionCatalogFailure.MIGRATION_CONFLICT)
                catalog.organizationPlan(plan, metadata.identity, value.organization)
                (source.value(CATALOG_ROWS, id) as? JsonObject)?.let { row ->
                    val updated = row.header().copy(
                        label = value.organization.title ?: row.string("fallbackLabel"),
                        collectionId = value.organization.collectionId,
                        pinnedAtEpochMs = value.organization.pinnedAtEpochMs,
                    )
                    plan.puts += putRecord(
                        CATALOG_ROWS,
                        id,
                        updated.rowWire(row.string("fallbackLabel"), row.string("normalizedFallbackLabel")),
                    )
                    plan.patches += wireObject {
                        put("store", CATALOG_SEARCH)
                        put("key", id)
                        put("fields", wireObject {
                            put(
                                "title",
                                value.organization.title?.let(catalog.projector::normalizeSearch)
                                    ?: row.string("normalizedFallbackLabel"),
                            )
                        })
                    }
                }
                imported++
            }
            val receipt = previous.copy(
                nextBatch = nextCatalogLong(previous.nextBatch),
                imported = add(previous.imported, imported),
                skipped = add(previous.skipped, skipped),
                complete = complete,
            )
            val next = plan.control.copy(
                organizationImport = SessionCatalogMigrationEncoding.receipt(receipt),
                organizationImportLastBatch = signature,
            )
            val completePlan = WebCatalogMutation(source, next).also {
                it.puts += plan.puts
                it.deletes += plan.deletes
                it.patches += plan.patches
            }
            if (catalog.apply(completePlan)) return@withCatalogOperation receipt
        }
        failure()
    }

    private fun WebCatalogControl.scanProgress() = attachmentNameScan?.let(SessionCatalogMigrationEncoding::scanProgress)
    private fun checkScan(control: WebCatalogControl, expected: SessionAttachmentNameScanProgress) {
        if (control.scanProgress() != expected || control.migrationLifecycle != expected.token.lifecycle) {
            fail(SessionCatalogFailure.STALE_MIGRATION)
        }
    }

    private fun scanPlan(source: WebCatalogView, progress: SessionAttachmentNameScanProgress) = catalog.mutation(
        source,
        source.control.copy(
            revision = nextCatalogLong(source.control.revision),
            attachmentNameScan = SessionCatalogMigrationEncoding.scanProgress(progress),
        ),
    )

    override suspend fun beginAttachmentNameScan(
        sourceId: String,
        sourceVersion: Int,
    ): SessionAttachmentNameScanProgress = lifecycle.withCatalogOperation {
        require(sourceId.isNotBlank() && sourceVersion > 0)
        repeat(8) {
            val source = catalog.read()
            val old = source.control.scanProgress()
            if (
                old != null && old.token.lifecycle == source.control.migrationLifecycle &&
                old.token.sourceId == sourceId && old.token.sourceVersion == sourceVersion
            ) {
                return@withCatalogOperation old
            }
            val storeId = source.control.migrationStoreId ?: SystemIdGenerator.nextId()
            val progress = SessionAttachmentNameScanProgress(
                SessionAttachmentNameScanToken(
                    storeId,
                    source.control.migrationLifecycle,
                    nextCatalogLong(old?.token?.generation ?: 0L),
                    sourceId,
                    sourceVersion,
                ),
            )
            val next = source.control.copy(
                revision = nextCatalogLong(source.control.revision),
                migrationStoreId = storeId,
                attachmentNameScan = SessionCatalogMigrationEncoding.scanProgress(progress),
            )
            if (catalog.apply(catalog.mutation(source, next))) return@withCatalogOperation progress
        }
        failure()
    }

    override suspend fun restartAttachmentNameScan(
        expected: SessionAttachmentNameScanProgress,
    ): SessionAttachmentNameScanProgress = lifecycle.withCatalogOperation {
        repeat(8) {
            val source = catalog.read()
            checkScan(source.control, expected)
            val progress = SessionAttachmentNameScanProgress(
                expected.token.copy(generation = nextCatalogLong(expected.token.generation)),
            )
            if (catalog.apply(scanPlan(source, progress))) return@withCatalogOperation progress
        }
        failure()
    }

    override suspend fun attachmentNameScanPage(
        expected: SessionAttachmentNameScanProgress,
        limit: Int,
    ): SessionAttachmentNamePage {
        require(limit in 1..MAX_CATALOG_MIGRATION_BATCH_SIZE)
        val captured = lifecycle.withCatalogOperation {
            catalog.view("keys", wireObject {
                put("store", WEB_SESSION_STORE)
                put("direction", "next")
                put("limit", limit + 1)
                expected.cursor?.let {
                    put("range", wireObject {
                        put("lowerToken", Json.parseToJsonElement(it))
                        put("lowerOpen", true)
                    })
                }
            }).also { checkScan(it.control, expected) }
        }
        if (expected.complete) return SessionAttachmentNamePage(emptyList(), expected.afterSessionId, true)
        val keys = captured.result.array("keys")
        val entries = mutableListOf<SessionAttachmentNameTarget>()
        val sources = mutableListOf<SessionAttachmentNameSource>()
        val issues = mutableListOf<SessionCatalogMaintenanceIssue>()
        for (key in keys.take(limit)) {
            val physical = key.jsonObject
            val cursor = physical.getValue("primaryKeyToken").toString()
            val id = physical.stringPrimaryKey()?.let(::AgentSessionId)
            if (id == null) {
                sources += SessionAttachmentNameSource(null, null, cursor)
                issues += SessionCatalogMaintenanceIssue(null, SessionCatalogMaintenanceIssueKind.UNREADABLE)
                continue
            }
            lifecycle.withCatalogOperation {
                val source = source(id, expected)
                val metadata = source.metadata(id.value) ?: return@withCatalogOperation
                sources += SessionAttachmentNameSource(metadata.identity, metadata.rowVersion, cursor)
                try {
                    entries += sessionAttachmentNameTarget(metadata.identity, metadata.rowVersion, record(source, id))
                } catch (failure: SessionCatalogException) {
                    if (failure.failure != SessionCatalogFailure.UNREADABLE_MIGRATION_SOURCE) throw failure
                    issues += SessionCatalogMaintenanceIssue(id, SessionCatalogMaintenanceIssueKind.UNREADABLE)
                }
            }
        }
        return SessionAttachmentNamePage(
            entries,
            sources.lastOrNull { it.identity != null }?.identity?.sessionId,
            keys.size <= limit,
            issues,
            sources,
        )
    }

    override suspend fun advanceAttachmentNameScan(
        expected: SessionAttachmentNameScanProgress,
        source: SessionAttachmentNameSource,
        outcome: SessionAttachmentNameScanOutcome,
    ): SessionAttachmentNameScanProgress = lifecycle.withCatalogOperation {
        val identity = source.identity
        if (identity == null) {
            if (
                outcome != SessionAttachmentNameScanOutcome.FAILED || source.rowVersion != null ||
                Json.parseToJsonElement(source.cursor).jsonArray.first() == JsonPrimitive("s")
            ) {
                fail(SessionCatalogFailure.STALE_MIGRATION)
            }
        } else if (source.cursor != attachmentNameSessionCursor(identity.sessionId) || source.rowVersion == null) {
            fail(SessionCatalogFailure.STALE_MIGRATION)
        }
        repeat(8) {
            val basis = catalog.read(ids = identity?.let { listOf(it.sessionId.value) }.orEmpty())
            checkScan(basis.control, expected)
            if (identity != null) {
                basis.metadata(identity.sessionId.value)?.let { current ->
                    val version = requireNotNull(source.rowVersion)
                    if (
                        current.identity != identity || current.rowVersion < version ||
                        (outcome != SessionAttachmentNameScanOutcome.SKIPPED_MANAGED && current.rowVersion != version)
                    ) {
                        fail(SessionCatalogFailure.STALE_MIGRATION)
                    }
                }
            }
            val progress = advanceAttachmentNameScanProgress(expected, source, outcome)
            val plan = scanPlan(basis, progress)
            plan.checks += scanTailCheck(expected.cursor, source.cursor)
            if (catalog.apply(plan)) return@withCatalogOperation progress
        }
        fail(SessionCatalogFailure.STALE_MIGRATION)
    }

    override suspend fun finishAttachmentNameScan(
        expected: SessionAttachmentNameScanProgress,
    ): SessionAttachmentNameScanProgress = lifecycle.withCatalogOperation {
        repeat(8) {
            val source = catalog.read()
            checkScan(source.control, expected)
            if (expected.complete) return@withCatalogOperation expected
            val progress = expected.copy(complete = true)
            val plan = scanPlan(source, progress)
            plan.checks += scanTailCheck(expected.cursor, null)
            if (catalog.apply(plan)) return@withCatalogOperation progress
        }
        fail(SessionCatalogFailure.STALE_MIGRATION)
    }

    private fun scanTailCheck(after: String?, expectedNext: String?) = wireObject {
        put("store", WEB_SESSION_STORE)
        put("afterKeyToken", after?.let(Json::parseToJsonElement) ?: JsonNull)
        put("expectedNextKeyToken", expectedNext?.let(Json::parseToJsonElement) ?: JsonNull)
    }

    private suspend fun source(
        id: AgentSessionId,
        expected: SessionAttachmentNameScanProgress? = null,
    ): WebCatalogView = catalog.read(
        listOf(lookup(WEB_SESSION_STORE, id.value), lookup(WEB_CHECKPOINT_STORE, id.value)),
        listOf(id.value),
    ).also {
        if (expected != null) checkScan(it.control, expected)
    }

    private fun record(source: WebCatalogView, id: AgentSessionId): AgentPersistenceRecord = decode {
        val snapshot = snapshotCodec.decodeResult(
            source.value(WEB_SESSION_STORE, id.value).payload() ?: error("Missing snapshot"),
        ).value
        val checkpoint = source.value(WEB_CHECKPOINT_STORE, id.value)?.let {
            checkpointCodec.decodeResult(it.payload() ?: error("Invalid checkpoint")).value
        }
        require(snapshot.sessionId == id)
        AgentPersistenceRecord(snapshot, checkpoint)
    }

    override suspend fun attachmentNameTarget(
        id: AgentSessionId,
        afterUri: String?,
    ): SessionAttachmentNameTarget? = lifecycle.withCatalogOperation {
        val source = source(id)
        val metadata = source.metadata(id.value) ?: return@withCatalogOperation null
        sessionAttachmentNameTarget(metadata.identity, metadata.rowVersion, record(source, id), afterUri)
    }

    override suspend fun attachmentNameTargets(
        afterSessionId: AgentSessionId?,
        limit: Int,
    ): SessionAttachmentNamePage {
        require(limit in 1..MAX_CATALOG_MIGRATION_BATCH_SIZE)
        val keys = lifecycle.withCatalogOperation {
            catalog.view("keys", wireObject {
                put("store", WEB_SESSION_STORE)
                put("direction", "next")
                put("limit", limit + 1)
                put("range", wireObject {
                    put("lower", afterSessionId?.value ?: "")
                    put("lowerOpen", afterSessionId != null)
                })
            }).result.array("keys")
        }
        // IndexedDB orders every string key before binary/array keys. Those malformed keys are
        // diagnosed by general maintenance; they cannot be represented as an AgentSessionId.
        val ids = keys.takeWhile { it.jsonObject.stringPrimaryKey() != null }
            .map { AgentSessionId(requireNotNull(it.jsonObject.stringPrimaryKey())) }
        val entries = mutableListOf<SessionAttachmentNameTarget>()
        val issues = mutableListOf<SessionCatalogMaintenanceIssue>()
        ids.take(limit).forEach { id ->
            try {
                attachmentNameTarget(id)?.let(entries::add)
            } catch (failure: SessionCatalogException) {
                if (failure.failure != SessionCatalogFailure.UNREADABLE_MIGRATION_SOURCE) throw failure
                issues += SessionCatalogMaintenanceIssue(id, SessionCatalogMaintenanceIssueKind.UNREADABLE)
            }
        }
        return SessionAttachmentNamePage(entries, ids.take(limit).lastOrNull(), ids.size <= limit, issues)
    }

    override suspend fun patchAttachmentNames(
        target: SessionAttachmentNameTarget,
        names: List<SessionAttachmentName>,
    ): SessionAttachmentNamePatchResult {
        val map = attachmentNameMap(names)
        return replace(target) { _, original -> fillAttachmentNames(original, map) }
    }

    override suspend fun attachmentNameBackup(
        identity: SessionIdentity,
    ): SessionAttachmentNameBackup? = lifecycle.withCatalogOperation {
        val source = source(identity.sessionId)
        val metadata = source.metadata(identity.sessionId.value) ?: return@withCatalogOperation null
        if (metadata.identity != identity) fail(SessionCatalogFailure.STALE_IDENTITY)
        (source.value(WEB_SESSION_STORE, identity.sessionId.value) as? JsonObject)
            ?.optionalString("attachmentNameBackup")
            ?.let(SessionCatalogMigrationEncoding::backup)
    }

    override suspend fun restoreAttachmentNameBackup(
        target: SessionAttachmentNameTarget,
    ): SessionAttachmentNamePatchResult = replace(target) { source, original ->
        val backup = (source.value(WEB_SESSION_STORE, target.identity.sessionId.value) as? JsonObject)
            ?.optionalString("attachmentNameBackup")
            ?.let(SessionCatalogMigrationEncoding::backup)
        if (backup == null) original else decode {
            require(backup.identity == target.identity)
            AgentPersistenceRecord(
                snapshotCodec.decodeResult(backup.snapshotPayload).value,
                backup.checkpointPayload?.let { checkpointCodec.decodeResult(it).value },
            ).also { require(it.snapshot.sessionId == target.identity.sessionId) }
        }
    }

    private suspend fun replace(
        target: SessionAttachmentNameTarget,
        transform: (WebCatalogView, AgentPersistenceRecord) -> AgentPersistenceRecord,
    ): SessionAttachmentNamePatchResult = lifecycle.withCatalogOperation {
        val id = target.identity.sessionId.value
        val captured = source(target.identity.sessionId)
        val metadata = captured.metadata(id) ?: fail(SessionCatalogFailure.STALE_MIGRATION)
        checkTarget(metadata, target)
        val original = record(captured, target.identity.sessionId)
        val updated = transform(captured, original)
        val projection = catalog.projector.project(updated.snapshot)
        val activity = metadata.activity ?: SessionActivityFact(
            projection.activityIdentity,
            null,
            SessionActivityOrder.Legacy(projection.activityAtEpochMs),
        )
        if (catalog.projector.activityMode == SessionActivityMode.PREPARED &&
            activity.observedIdentity != projection.activityIdentity
        ) {
            fail(SessionCatalogFailure.MAINTENANCE_PROJECTION_FAILED)
        }
        val raw = captured.value(WEB_SESSION_STORE, id)
        val checkpointRaw = captured.value(WEB_CHECKPOINT_STORE, id)
        val backup = (raw as? JsonObject)?.optionalString("attachmentNameBackup") ?: SessionCatalogMigrationEncoding.backup(
            SessionAttachmentNameBackup(
                target.identity,
                target.rowVersion,
                requireNotNull(raw.payload()),
                checkpointRaw?.payload(),
            ),
        )
        val encoded = snapshotCodec.encode(updated.snapshot)
        val checkpoint = updated.checkpoint?.let(checkpointCodec::encode)
        val references = extractSessionAttachmentUris(updated.snapshot, updated.checkpoint)
        val backupReferences = decode {
            val value = SessionCatalogMigrationEncoding.backup(backup)
            require(value.identity == target.identity)
            attachmentNameBackupUris(value, json)
        }
        repeat(8) {
            val source = catalog.read(listOf(lookup(CATALOG_ORGANIZATION, id)), listOf(id))
            checkTarget(source.metadata(id) ?: fail(SessionCatalogFailure.STALE_MIGRATION), target)
            if (updated == original) {
                return@withCatalogOperation SessionAttachmentNamePatchResult(target.identity, target.rowVersion, false)
            }
            val version = nextCatalogLong(target.rowVersion)
            val organization = source.value(CATALOG_ORGANIZATION, id).organizationFor(target.identity) ?: SessionOrganization()
            val entry = materializeCatalogEntry(
                target.identity,
                version,
                updated.snapshot,
                projection,
                organization,
                activity,
                catalog.projector,
            )
            val plan = catalog.mutation(source)
            plan.checks += catalog.exactCheck(WEB_SESSION_STORE, id, raw)
            plan.checks += catalog.exactCheck(WEB_CHECKPOINT_STORE, id, checkpointRaw)
            plan.puts += putRecord(
                WEB_SESSION_STORE,
                id,
                JsonObject(
                    WebSessionMetadata(
                        target.identity,
                        version,
                        activity,
                        updated.snapshot.state.status,
                        updated.checkpoint != null,
                    ).record(encoded) + ("attachmentNameBackup" to JsonPrimitive(backup)),
                ),
            )
            if (checkpoint == null) {
                plan.deletes += lookup(WEB_CHECKPOINT_STORE, id)
            } else {
                plan.puts += putRecord(WEB_CHECKPOINT_STORE, id, JsonPrimitive(checkpoint))
            }
            catalog.projectionPlan(plan, id, entry, projection, references)
            catalog.referenceSourcePlan(plan, id, "attachment-name-backup", backupReferences)
            if (catalog.apply(plan)) {
                return@withCatalogOperation SessionAttachmentNamePatchResult(target.identity, version, true)
            }
        }
        fail(SessionCatalogFailure.STALE_MIGRATION)
    }

    private fun checkTarget(metadata: WebSessionMetadata, target: SessionAttachmentNameTarget) {
        if (metadata.identity != target.identity || metadata.rowVersion != target.rowVersion) {
            fail(SessionCatalogFailure.STALE_MIGRATION)
        }
    }

    private fun JsonElement?.payload(): String? = when (this) {
        is JsonObject -> optionalString("payload")
        is JsonPrimitive -> if (isString) content else null
        else -> null
    }

    private fun <T> decode(block: () -> T): T = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        fail(SessionCatalogFailure.UNREADABLE_MIGRATION_SOURCE)
    }

    private fun add(value: Long, amount: Long): Long {
        if (value > Long.MAX_VALUE - amount) fail(SessionCatalogFailure.COUNTER_EXHAUSTED)
        return value + amount
    }

    private fun failure(): Nothing = throw WebStorageException(WebStorageFailure.OPERATION_FAILED)
    private fun fail(failure: SessionCatalogFailure): Nothing = throw SessionCatalogException(failure)
}
