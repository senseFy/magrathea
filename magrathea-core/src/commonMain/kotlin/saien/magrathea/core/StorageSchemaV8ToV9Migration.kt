package saien.magrathea.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Frozen additive migration that introduces Session-owned background Tool fields.
 *
 * Schema-9 envelopes may carry `MessageRole.RUNTIME`, `runtime_notice` parts, Tool-result
 * receipt markers, `RuntimeConfig.background`, `AgentRequest.activation`, and the Run wait
 * counters. Schema-8 payloads never contain any of them, so migrated documents receive every new
 * field at its default and nothing else changes.
 */
internal object StorageSchemaV8ToV9Migration : AdjacentStorageSchemaMigration {
    override fun validateSource(document: JsonObject) {
        require(document.getValue("schemaVersion").jsonPrimitive.int == STORAGE_SCHEMA_V8_VERSION)
        // Every consumed or introduced field is validated by the transformation itself, so this
        // frozen step never inherits behavior from a later live serializer.
        document.addBackgroundToolFields()
    }

    override fun migrate(document: JsonObject): JsonObject = document.addBackgroundToolFields()
}

private fun JsonObject.addBackgroundToolFields(): JsonObject {
    val payload = getValue("payload").jsonObject
    val isSession = "request" in payload && "cursor" !in payload
    val isCheckpoint = "cursor" in payload && "request" !in payload
    require(isSession || isCheckpoint) { "Unknown stored envelope payload" }
    return replaceObject("payload") { current ->
        if (isSession) {
            current
                .replaceObject("request") { request ->
                    request
                        .withNullActivation()
                        .replaceObject("engine") { engine ->
                            engine.replaceObject("runtime", JsonObject::withNullBackground)
                        }
                        .replaceArray("messages") { messages -> messages.withReceiptMarkers() }
                }
                .replaceObject("state", JsonObject::withMigratedWaitCounters)
        } else {
            current
                .replaceObject("state", JsonObject::withMigratedWaitCounters)
                .replaceArray("toolExecutions") { records ->
                    JsonArray(records.map { record -> record.jsonObject.withJournalReceiptMarker() })
                }
        }
    }
}

private fun JsonObject.withNullActivation(): JsonObject {
    require("activation" !in this) { "Schema-v8 request already contains activation" }
    return JsonObject(toMutableMap().apply { put("activation", JsonNull) })
}

private fun JsonObject.withNullBackground(): JsonObject {
    require("background" !in this) { "Schema-v8 runtime configuration already contains background" }
    return JsonObject(toMutableMap().apply { put("background", JsonNull) })
}

private fun JsonObject.withMigratedWaitCounters(): JsonObject {
    require("foregroundToolWaitMillis" !in this) {
        "Schema-v8 state already contains foregroundToolWaitMillis"
    }
    require("explicitWaitMillis" !in this) { "Schema-v8 state already contains explicitWaitMillis" }
    return JsonObject(
        toMutableMap().apply {
            put("foregroundToolWaitMillis", JsonPrimitive(0))
            put("explicitWaitMillis", JsonPrimitive(0))
        },
    ).replaceArray("messages") { messages -> messages.withReceiptMarkers() }
}

private fun JsonArray.withReceiptMarkers(): JsonArray = JsonArray(
    map { message ->
        message.jsonObject.replaceArray("parts") { parts ->
            JsonArray(
                parts.map { part ->
                    val value = part.jsonObject
                    if (value.getValue("type").jsonPrimitive.content == "tool_result") {
                        value.withNullReceiptMarker()
                    } else {
                        value
                    }
                },
            )
        }
    },
)

private fun JsonObject.withJournalReceiptMarker(): JsonObject {
    val result = getValue("result")
    if (result === JsonNull) return this
    return JsonObject(toMutableMap().apply { put("result", result.jsonObject.withNullReceiptMarker()) })
}

private fun JsonObject.withNullReceiptMarker(): JsonObject {
    require("backgroundReceipt" !in this) { "Schema-v8 Tool result already contains backgroundReceipt" }
    return JsonObject(toMutableMap().apply { put("backgroundReceipt", JsonNull) })
}

private fun JsonObject.replaceObject(
    key: String,
    transform: (JsonObject) -> JsonObject,
): JsonObject {
    val value = getValue(key).jsonObject
    return JsonObject(toMutableMap().apply { put(key, transform(value)) })
}

private fun JsonObject.replaceArray(
    key: String,
    transform: (JsonArray) -> JsonElement,
): JsonObject {
    val value = getValue(key).jsonArray
    return JsonObject(toMutableMap().apply { put(key, transform(value)) })
}
