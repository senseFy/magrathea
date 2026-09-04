package saien.magrathea.core

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Frozen additive migration that makes the newly persisted output capability explicit. */
internal object StorageSchemaV6ToV7Migration : AdjacentStorageSchemaMigration {
    override fun validateSource(document: JsonObject) {
        require(document.getValue("schemaVersion").jsonPrimitive.int == STORAGE_SCHEMA_V6_VERSION)
        // Validate every field this transition consumes or introduces. Full document validation is
        // deliberately deferred until the complete chain reaches the current schema, so this
        // frozen migration never inherits behavior from a later live serializer.
        document.addModelOutputCapabilities()
    }

    override fun migrate(document: JsonObject): JsonObject = document.addModelOutputCapabilities()
}

private enum class StoredEnvelopeKind {
    SESSION,
    CHECKPOINT,
}

private fun JsonObject.envelopeKind(): StoredEnvelopeKind {
    val payload = getValue("payload").jsonObject
    return when {
        "request" in payload && "cursor" !in payload -> StoredEnvelopeKind.SESSION
        "cursor" in payload && "request" !in payload -> StoredEnvelopeKind.CHECKPOINT
        else -> error("Unknown stored envelope payload")
    }
}

private fun JsonObject.addModelOutputCapabilities(): JsonObject {
    val kind = envelopeKind()
    return replaceObject("payload") { payload ->
        when (kind) {
            StoredEnvelopeKind.SESSION -> payload
                .replaceObject("request") { request ->
                    request.replaceObject("model", JsonObject::withNullOutputCapability)
                }
                .replaceObject("state", JsonObject::withMigratedContextState)
            StoredEnvelopeKind.CHECKPOINT ->
                payload.replaceObject("state", JsonObject::withMigratedContextState)
        }
    }
}

private fun JsonObject.withMigratedContextState(): JsonObject =
    replaceObject("contextManagement") { contextManagement ->
        val compaction = contextManagement.getValue("compaction")
        if (compaction === JsonNull) {
            contextManagement
        } else {
            contextManagement.replaceObject("compaction") { value ->
                value.replaceObject("summaryModel", JsonObject::withNullOutputCapability)
            }
        }
    }

private fun JsonObject.withNullOutputCapability(): JsonObject {
    require("maxOutputTokens" !in this) { "Schema-v6 model already contains maxOutputTokens" }
    return JsonObject(toMutableMap().apply { put("maxOutputTokens", JsonNull) })
}

private fun JsonObject.replaceObject(
    key: String,
    transform: (JsonObject) -> JsonObject,
): JsonObject {
    val value = getValue(key).jsonObject
    return JsonObject(toMutableMap().apply { put(key, transform(value)) })
}
