package saien.magrathea.catalog

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import saien.magrathea.core.AgentCheckpointCodec
import saien.magrathea.core.AgentMessage
import saien.magrathea.core.AgentPersistenceRecord
import saien.magrathea.core.AgentSessionId
import saien.magrathea.core.AgentSessionSnapshotCodec
import saien.magrathea.core.AttachmentPart

/** Storage-adapter helpers for the fixed migration control record; not an arbitrary host KV codec. */
object SessionCatalogMigrationEncoding {
    fun scanProgress(value: SessionAttachmentNameScanProgress): String = buildJsonObject {
        put("store", value.token.storeId)
        put("lifecycle", value.token.lifecycle.toString())
        put("generation", value.token.generation.toString())
        put("source", value.token.sourceId)
        put("version", value.token.sourceVersion)
        put("after", value.afterSessionId?.value)
        put("cursor", value.cursor)
        put("processed", value.processed.toString())
        put("failed", value.failed.toString())
        put("complete", value.complete)
    }.toString()

    fun scanProgress(value: String): SessionAttachmentNameScanProgress {
        val fields = Json.parseToJsonElement(value).jsonObject
        fun text(key: String) = fields.getValue(key).jsonPrimitive.content
        return SessionAttachmentNameScanProgress(
            token = SessionAttachmentNameScanToken(
                text("store"),
                text("lifecycle").toLong(),
                text("generation").toLong(),
                text("source"),
                text("version").toInt(),
            ),
            afterSessionId = fields["after"]?.jsonPrimitive?.contentOrNull?.let(::AgentSessionId),
            cursor = fields["cursor"]?.jsonPrimitive?.contentOrNull,
            processed = text("processed").toLong(),
            failed = text("failed").toLong(),
            complete = text("complete").toBooleanStrict(),
        )
    }

    fun receipt(value: OrganizationImportReceipt): String = buildJsonObject {
        put("store", value.token.storeId)
        put("lifecycle", value.token.lifecycle.toString())
        put("source", value.token.sourceId)
        put("fingerprint", value.token.fingerprint)
        put("next", value.nextBatch.toString())
        put("imported", value.imported.toString())
        put("skipped", value.skipped.toString())
        put("complete", value.complete)
        put("invalidated", value.invalidated)
    }.toString()

    fun receipt(value: String): OrganizationImportReceipt {
        val fields = Json.parseToJsonElement(value).jsonObject
        fun text(key: String) = fields.getValue(key).jsonPrimitive.content
        return OrganizationImportReceipt(
            token = OrganizationImportToken(
                text("store"), text("lifecycle").toLong(), text("source"), text("fingerprint"),
            ),
            nextBatch = text("next").toLong(),
            imported = text("imported").toLong(),
            skipped = text("skipped").toLong(),
            complete = text("complete").toBooleanStrict(),
            invalidated = text("invalidated").toBooleanStrict(),
        )
    }

    fun batch(
        collections: List<SessionCollection>,
        organizations: List<LegacySessionOrganization>,
        complete: Boolean,
    ): String = buildJsonObject {
        put("complete", complete)
        put("collections", buildJsonArray {
            collections.forEach { collection ->
                add(buildJsonArray {
                    add(collection.id)
                    add(collection.name)
                    add(collection.nameSortKey)
                    add(collection.nameUniqueKey)
                })
            }
        })
        put("organizations", buildJsonArray {
            organizations.forEach { row ->
                add(buildJsonArray {
                    add(row.sessionId.value)
                    add(row.organization.title)
                    add(row.organization.collectionId)
                    add(row.organization.pinnedAtEpochMs?.toString())
                })
            }
        })
    }.toString()

    fun backup(value: SessionAttachmentNameBackup): String = buildJsonObject {
        put("id", value.identity.sessionId.value)
        put("incarnation", value.identity.incarnation)
        put("version", value.rowVersion.toString())
        put("snapshot", value.snapshotPayload)
        put("checkpoint", value.checkpointPayload)
    }.toString()

    fun backup(value: String): SessionAttachmentNameBackup {
        val fields = Json.parseToJsonElement(value).jsonObject
        fun text(key: String) = fields.getValue(key).jsonPrimitive.content
        return SessionAttachmentNameBackup(
            identity = SessionIdentity(AgentSessionId(text("id")), text("incarnation")),
            rowVersion = text("version").toLong(),
            snapshotPayload = text("snapshot"),
            checkpointPayload = fields["checkpoint"]?.jsonPrimitive?.contentOrNull,
        )
    }
}

fun attachmentNameSessionCursor(id: AgentSessionId): String = buildJsonArray {
    add("s")
    add(id.value)
}.toString()

fun advanceAttachmentNameScanProgress(
    expected: SessionAttachmentNameScanProgress,
    source: SessionAttachmentNameSource,
    outcome: SessionAttachmentNameScanOutcome,
): SessionAttachmentNameScanProgress {
    if (expected.complete) throw SessionCatalogException(SessionCatalogFailure.STALE_MIGRATION)
    if (expected.processed == Long.MAX_VALUE || (outcome == SessionAttachmentNameScanOutcome.FAILED && expected.failed == Long.MAX_VALUE)) {
        throw SessionCatalogException(SessionCatalogFailure.COUNTER_EXHAUSTED)
    }
    return expected.copy(
        afterSessionId = source.identity?.sessionId ?: expected.afterSessionId,
        cursor = source.cursor,
        processed = expected.processed + 1,
        failed = expected.failed + if (outcome == SessionAttachmentNameScanOutcome.FAILED) 1 else 0,
    )
}

fun validateOrganizationImportBatch(collections: List<SessionCollection>, organizations: List<LegacySessionOrganization>) {
    require(collections.size + organizations.size <= MAX_CATALOG_MIGRATION_BATCH_SIZE)
    require(collections.map { it.id }.distinct().size == collections.size)
    require(collections.map { it.nameUniqueKey }.distinct().size == collections.size)
    require(organizations.map { it.sessionId }.distinct().size == organizations.size)
}

fun attachmentNameMap(names: List<SessionAttachmentName>): Map<String, String> {
    require(names.size <= MAX_CATALOG_MIGRATION_BATCH_SIZE)
    require(names.all { it.uri.isNotBlank() && it.fileName.isNotBlank() })
    require(names.map { it.uri }.distinct().size == names.size)
    return names.associate { it.uri to it.fileName }
}

fun missingAttachmentNameUris(record: AgentPersistenceRecord): Set<String> = buildSet {
    sequenceOf(
        record.snapshot.request.messages,
        record.snapshot.state.messages,
        record.checkpoint?.state?.messages.orEmpty(),
    ).flatten().forEach { message ->
        message.parts.filterIsInstance<AttachmentPart>()
            .filter { it.fileName.isNullOrBlank() }
            .forEach { add(it.uri) }
    }
}

fun sessionAttachmentNameTarget(
    identity: SessionIdentity,
    rowVersion: Long,
    record: AgentPersistenceRecord,
    afterUri: String? = null,
): SessionAttachmentNameTarget {
    val uris = missingAttachmentNameUris(record).asSequence().filter { afterUri == null || it > afterUri }.sorted()
        .take(MAX_CATALOG_MIGRATION_BATCH_SIZE + 1).toList()
    val selected = uris.take(MAX_CATALOG_MIGRATION_BATCH_SIZE)
    return SessionAttachmentNameTarget(
        identity,
        rowVersion,
        selected.toSet(),
        if (uris.size > MAX_CATALOG_MIGRATION_BATCH_SIZE) selected.last() else null,
    )
}

/** Pure canonical migration. The same URI gets the same supplied name in every snapshot/checkpoint occurrence. */
fun fillAttachmentNames(record: AgentPersistenceRecord, names: Map<String, String>): AgentPersistenceRecord {
    fun messages(values: List<AgentMessage>) = values.map { message ->
        message.copy(parts = message.parts.map { part ->
            if (part is AttachmentPart && part.fileName.isNullOrBlank()) {
                names[part.uri]?.let { part.copy(fileName = it) } ?: part
            } else {
                part
            }
        })
    }
    val snapshot = record.snapshot.copy(
        request = record.snapshot.request.copy(messages = messages(record.snapshot.request.messages)),
        state = record.snapshot.state.copy(messages = messages(record.snapshot.state.messages)),
    )
    val checkpoint = record.checkpoint?.let { it.copy(state = it.state.copy(messages = messages(it.state.messages))) }
    return AgentPersistenceRecord(snapshot, checkpoint)
}

/** Backups are persisted attachment-bearing sources too, so rebuild and GC must retain their URIs. */
fun attachmentNameBackupUris(backup: SessionAttachmentNameBackup, json: Json = Json): Set<String> {
    val snapshot = AgentSessionSnapshotCodec(json).decodeResult(backup.snapshotPayload).value
    val checkpoint = backup.checkpointPayload?.let { AgentCheckpointCodec(json).decodeResult(it).value }
    require(snapshot.sessionId == backup.identity.sessionId)
    AgentPersistenceRecord(snapshot, checkpoint)
    return extractSessionAttachmentUris(snapshot, checkpoint)
}
