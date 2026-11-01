package saien.magrathea.storage.web

import kotlinx.serialization.json.*
import saien.magrathea.catalog.*
import saien.magrathea.core.AgentSessionId
import saien.magrathea.core.AgentStatus

internal const val CATALOG_ROWS = "session_catalog"
internal const val CATALOG_SEARCH = "session_search"
internal const val CATALOG_REFS = "session_attachment_refs"
internal const val CATALOG_COLLECTIONS = "session_collections"
internal const val CATALOG_ORGANIZATION = "session_organization"
internal const val CATALOG_STATE = "catalog_state"

internal fun wireObject(block: JsonObjectBuilder.() -> Unit): JsonObject = buildJsonObject(block)
internal fun JsonObject.string(name: String): String = getValue(name).jsonPrimitive.content
internal fun JsonObject.optionalString(name: String): String? = get(name)?.jsonPrimitive?.contentOrNull
internal fun JsonObject.long(name: String): Long = string(name).toLong()
internal fun JsonObject.boolean(name: String): Boolean = getValue(name).jsonPrimitive.boolean
internal fun JsonObject.array(name: String): JsonArray = getValue(name).jsonArray
internal fun nullableToken(value: String?): String = value?.let { "1$it" } ?: "0"
internal fun decodeNullableToken(value: String): String? = if (value == "0") null else {
    require(value.startsWith("1"))
    value.substring(1)
}
internal fun SessionActivityOrder.orderWire(): String = when (this) {
    is SessionActivityOrder.Accepted -> "A:$sequence"
    is SessionActivityOrder.Legacy -> "L:$timestampEpochMs"
}
internal fun String.activityOrder(): SessionActivityOrder = when {
    startsWith("A:") -> SessionActivityOrder.Accepted(substring(2).toLong())
    startsWith("L:") -> SessionActivityOrder.Legacy(substring(2).toLong())
    else -> error("Invalid catalog activity order")
}
internal fun nextCatalogLong(value: Long): Long {
    if (value == Long.MAX_VALUE) throw SessionCatalogException(SessionCatalogFailure.COUNTER_EXHAUSTED)
    return value + 1
}

/** Every Long on the JS JSON bridge is a decimal string, including non-index authority and CAS. */
internal data class WebCatalogControl(
    val policy: SessionCatalogPolicy,
    val activityMode: SessionActivityMode,
    val revision: Long = 0,
    val generation: Long = 1,
    val sequence: Long = 0,
    val coverage: CatalogCoverage = CatalogCoverage.COMPLETE,
    val afterSession: String? = null,
    val afterCollection: String? = null,
    val processed: Long = 0,
    val failed: Long = 0,
    val sessionsComplete: Boolean = true,
    val collectionsComplete: Boolean = true,
    val recoveryIndexed: Boolean = true,
    val unindexedSources: Long = 0,
    val afterSessionKey: JsonElement? = null,
    val migrationStoreId: String? = null,
    val migrationLifecycle: Long = 0,
    val organizationImport: String? = null,
    val organizationImportLastBatch: String? = null,
    val attachmentNameScan: String? = null,
) {
    fun state() = SessionCatalogState(policy, revision, generation, coverage)
    fun progress() = SessionCatalogMaintenanceProgress(
        state = state(),
        afterSessionId = afterSession?.let(::AgentSessionId),
        processed = processed,
        failed = failed,
        complete = sessionsComplete && collectionsComplete,
    )
    fun wire() = wireObject {
        put("policyId", policy.id)
        put("policyVersion", policy.version)
        put("activityMode", activityMode.name)
        put("revision", revision.toString())
        put("generation", generation.toString())
        put("sequence", sequence.toString())
        put("coverage", coverage.name)
        put("afterSession", afterSession)
        put("afterCollection", afterCollection)
        put("processed", processed.toString())
        put("failed", failed.toString())
        put("sessionsComplete", sessionsComplete)
        put("collectionsComplete", collectionsComplete)
        put("recoveryIndexed", recoveryIndexed)
        put("unindexedSources", unindexedSources.toString())
        put("afterSessionKey", afterSessionKey ?: JsonNull)
        put("migrationStoreId", migrationStoreId)
        put("migrationLifecycle", migrationLifecycle.toString())
        put("organizationImport", organizationImport)
        put("organizationImportLastBatch", organizationImportLastBatch)
        put("attachmentNameScan", attachmentNameScan)
    }
    companion object {
        fun from(value: JsonObject) = WebCatalogControl(
            policy = SessionCatalogPolicy(
                value.string("policyId"),
                value.getValue("policyVersion").jsonPrimitive.int,
            ),
            activityMode = SessionActivityMode.valueOf(value.string("activityMode")),
            revision = value.long("revision"),
            generation = value.long("generation"),
            sequence = value.long("sequence"),
            coverage = CatalogCoverage.valueOf(value.string("coverage")),
            afterSession = value.optionalString("afterSession"),
            afterCollection = value.optionalString("afterCollection"),
            processed = value.long("processed"),
            failed = value.long("failed"),
            sessionsComplete = value.boolean("sessionsComplete"),
            collectionsComplete = value.boolean("collectionsComplete"),
            recoveryIndexed = value.boolean("recoveryIndexed"),
            unindexedSources = value.long("unindexedSources"),
            afterSessionKey = value["afterSessionKey"]?.takeUnless { it == JsonNull },
            migrationStoreId = value.optionalString("migrationStoreId"),
            migrationLifecycle = value.optionalString("migrationLifecycle")?.toLong() ?: 0L,
            organizationImport = value.optionalString("organizationImport"),
            organizationImportLastBatch = value.optionalString("organizationImportLastBatch"),
            attachmentNameScan = value.optionalString("attachmentNameScan"),
        )
    }
}

internal data class WebSessionMetadata(
    val identity: SessionIdentity,
    val rowVersion: Long,
    val activity: SessionActivityFact?,
    val status: AgentStatus?,
    val hasCheckpoint: Boolean,
) {
    fun key(): JsonArray = buildJsonArray {
        add(identity.sessionId.value)
        add(identity.incarnation)
        add(rowVersion.toString())
        add(nullableToken(activity?.observedIdentity))
        add(nullableToken(activity?.lastAcceptedIdentity))
        add(nullableToken(activity?.order?.orderWire()))
        add(nullableToken(status?.name))
        add(if (hasCheckpoint) 1 else 0)
    }
    fun record(payload: String): JsonObject = wireObject {
        put("payload", payload)
        put("metadataKey", key())
        put("recoveryKey", buildJsonArray {
            add(if (status == null || sessionRecoveryReasons(status, hasCheckpoint).isNotEmpty()) 1 else 0)
            add(identity.sessionId.value)
        })
    }
    companion object {
        fun fromLookup(value: JsonObject): WebSessionMetadata? {
            if (!value.boolean("found")) return null
            val id = AgentSessionId(value.string("id"))
            val key = value["key"] as? JsonArray ?: return WebSessionMetadata(
                identity = SessionIdentity(id, "legacy"),
                rowVersion = 0,
                activity = null,
                status = null,
                hasCheckpoint = value["checkpointFound"]?.jsonPrimitive?.booleanOrNull == true,
            )
            val order = decodeNullableToken(key[5].jsonPrimitive.content)
            return WebSessionMetadata(
                identity = SessionIdentity(id, key[1].jsonPrimitive.content),
                rowVersion = key[2].jsonPrimitive.content.toLong(),
                activity = order?.let {
                    SessionActivityFact(
                        observedIdentity = decodeNullableToken(key[3].jsonPrimitive.content),
                        lastAcceptedIdentity = decodeNullableToken(key[4].jsonPrimitive.content),
                        order = it.activityOrder(),
                    )
                },
                status = decodeNullableToken(key[6].jsonPrimitive.content)?.let { name ->
                    AgentStatus.entries.firstOrNull { it.name == name }
                },
                hasCheckpoint = key[7].jsonPrimitive.int == 1,
            )
        }
    }
}

internal fun SessionOrganization.wire(): JsonObject = wireObject {
    put("title", title)
    put("collectionId", collectionId)
    put("pinnedAt", pinnedAtEpochMs?.toString())
}
internal fun JsonObject.organization() = SessionOrganization(optionalString("title"), optionalString("collectionId"), optionalString("pinnedAt")?.toLong())
internal fun JsonElement?.organizationFor(identity: SessionIdentity): SessionOrganization? = (this as? JsonObject)
    ?.takeIf { it.optionalString("incarnation") == identity.incarnation }?.organization()
internal fun SessionCollection.wire(projector: SessionCatalogProjector): JsonObject = wireObject {
    put("id", id)
    put("name", name)
    put("nameSortKey", nameSortKey)
    put("nameUniqueKey", nameUniqueKey)
    put("idSortKey", CatalogKeyEncoding.stringKey(id))
    put("normalizedName", projector.normalizeSearch(name))
}
internal fun JsonObject.collection() = SessionCollection(string("id"), string("name"), string("nameSortKey"), string("nameUniqueKey"))

internal fun SessionCatalogEntry.rowWire(projection: SessionCatalogProjection): JsonObject = header.rowWire(projection.fallbackLabel, projection.normalizedFallbackLabel)
internal fun SessionCatalogHeader.rowWire(fallbackLabel: String, normalizedFallbackLabel: String): JsonObject = wireObject {
    put("id", identity.sessionId.value)
    put("incarnation", identity.incarnation)
    put("rowVersion", rowVersion.toString())
    put("label", label)
    put("previewExcerpt", previewExcerpt)
    put("providerId", providerId)
    put("modelId", modelId)
    put("modelDisplayName", modelDisplayName)
    put("status", status.name)
    put("activityAt", activityAtEpochMs.toString())
    put("observedIdentity", activity.observedIdentity)
    put("lastAcceptedIdentity", activity.lastAcceptedIdentity)
    put("activityOrder", activity.order.orderWire())
    put("collectionId", collectionId)
    put("pinnedAt", pinnedAtEpochMs?.toString())
    put("fallbackLabel", fallbackLabel)
    put("normalizedFallbackLabel", normalizedFallbackLabel)
    put("collectionKey", CatalogKeyEncoding.collectionKey(collectionId))
    val key = sessionCatalogSortKey(this@rowWire, SessionCatalogSort.PIN_THEN_ACTIVITY)
    put("pinBucket", key.pinBucket)
    put("pinRank", key.pinRank)
    put("activityRank", key.activityRank)
    put("idSortKey", key.idSortKey)
}
internal fun JsonObject.header() = SessionCatalogHeader(
    identity = SessionIdentity(AgentSessionId(string("id")), string("incarnation")),
    rowVersion = long("rowVersion"),
    label = string("label"),
    previewExcerpt = string("previewExcerpt"),
    providerId = string("providerId"),
    modelId = string("modelId"),
    modelDisplayName = string("modelDisplayName"),
    status = AgentStatus.valueOf(string("status")),
    activityAtEpochMs = long("activityAt"),
    activity = SessionActivityFact(
        observedIdentity = optionalString("observedIdentity"),
        lastAcceptedIdentity = optionalString("lastAcceptedIdentity"),
        order = string("activityOrder").activityOrder(),
    ),
    collectionId = optionalString("collectionId"),
    pinnedAtEpochMs = optionalString("pinnedAt")?.toLong(),
)
internal fun SessionCatalogEntry.searchWire(): JsonObject = wireObject {
    put("title", normalizedTitle)
    put("preview", normalizedPreview)
    put("model", normalizedModelName)
}

internal fun lookup(store: String, key: String): JsonObject = wireObject { put("store", store); put("key", key) }
internal fun putRecord(store: String, key: JsonElement, value: JsonElement): JsonObject = wireObject {
    put("store", store)
    put("key", key)
    put("value", value)
    if (store == WEB_SESSION_STORE && value is JsonObject) put("preserveAttachmentNameBackup", true)
}
internal fun putRecord(store: String, key: String, value: JsonElement) = putRecord(store, JsonPrimitive(key), value)
internal fun onlyRange(key: JsonElement): JsonObject = wireObject { put("only", key) }
internal fun JsonObject.stringPrimaryKey(): String? {
    val token = get("primaryKeyToken") as? JsonArray ?: return null
    return if (token.firstOrNull() == JsonPrimitive("s")) token[1].jsonPrimitive.content else null
}

internal data class WebCatalogView(val stateWire: JsonObject, val control: WebCatalogControl, val result: JsonObject) {
    fun value(store: String, key: String): JsonElement? = result["values"]?.jsonArray?.firstOrNull {
        it.jsonObject.string("store") == store && it.jsonObject["key"] == JsonPrimitive(key)
    }?.jsonObject?.takeIf { it.boolean("found") }?.get("value")
    fun metadata(id: String): WebSessionMetadata? = result["metadata"]?.jsonArray?.firstOrNull {
        it.jsonObject.string("id") == id
    }?.jsonObject?.let(WebSessionMetadata::fromLookup)
}

internal class WebCatalogMutation(val expected: WebCatalogView, val control: WebCatalogControl) {
    val checks = mutableListOf<JsonObject>()
    val puts = mutableListOf<JsonObject>()
    val deletes = mutableListOf<JsonObject>()
    val clears = mutableListOf<String>()
    val patches = mutableListOf<JsonObject>()
    val deleteRanges = mutableListOf<JsonObject>()
    fun wire() = wireObject {
        put("expectedState", expected.stateWire)
        put("state", control.wire())
        put("checks", JsonArray(checks))
        put("puts", JsonArray(puts))
        put("deletes", JsonArray(deletes))
        put("clears", JsonArray(clears.map(::JsonPrimitive)))
        put("patches", JsonArray(patches))
        put("deleteRanges", JsonArray(deleteRanges))
    }
}
