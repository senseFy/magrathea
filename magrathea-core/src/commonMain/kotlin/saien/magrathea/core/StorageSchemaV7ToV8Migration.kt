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
 * Frozen additive migration that introduces Tool-result context management.
 *
 * Schema-7 runs projected every Tool result unchanged and estimated UTF-16 characters, so migrated
 * requests receive an explicitly disabled Tool-result policy and that estimation unit. Persisted
 * usage observations are dropped because they lack the new projection anchors.
 */
internal object StorageSchemaV7ToV8Migration : AdjacentStorageSchemaMigration {
    override fun validateSource(document: JsonObject) {
        require(document.getValue("schemaVersion").jsonPrimitive.int == STORAGE_SCHEMA_V7_VERSION)
        // Every consumed or introduced field is validated by the transformation itself, so this
        // frozen step never inherits behavior from a later live serializer.
        document.addToolResultContext()
    }

    override fun migrate(document: JsonObject): JsonObject = document.addToolResultContext()
}

private fun JsonObject.addToolResultContext(): JsonObject {
    val payload = getValue("payload").jsonObject
    val isSession = "request" in payload && "cursor" !in payload
    val isCheckpoint = "cursor" in payload && "request" !in payload
    require(isSession || isCheckpoint) { "Unknown stored envelope payload" }
    return replaceObject("payload") { current ->
        if (isSession) {
            current
                .replaceObject("request") { request ->
                    request
                        .replaceArray("messages") { messages -> messages.withToolResultHints() }
                        .replaceObject("engine") { engine ->
                            engine.replaceObject("runtime") { runtime ->
                                runtime.replaceObject("contextManagement", JsonObject::withDisabledToolResultPolicy)
                            }
                        }
                }
                .replaceObject("state", JsonObject::withMigratedState)
        } else {
            current
                .replaceObject("state", JsonObject::withMigratedState)
                .replaceArray("toolExecutions") { records ->
                    JsonArray(records.map { record -> record.jsonObject.withJournalResultHints() })
                }
        }
    }
}

private fun JsonObject.withDisabledToolResultPolicy(): JsonObject {
    require("toolResults" !in this) { "Schema-v7 context configuration already contains toolResults" }
    require("estimationUnit" !in this) { "Schema-v7 context configuration already contains estimationUnit" }
    val disabledPolicy = JsonObject(
        mapOf(
            "budget" to JsonNull,
            "clearing" to JsonNull,
            "maxStubChars" to JsonPrimitive(DISABLED_POLICY_MAX_STUB_CHARS),
        ),
    )
    return JsonObject(
        toMutableMap().apply {
            put("toolResults", disabledPolicy)
            put("estimationUnit", JsonPrimitive("UTF16_CHARS"))
        },
    )
}

private fun JsonObject.withMigratedState(): JsonObject =
    replaceArray("messages") { messages -> messages.withToolResultHints() }
        .replaceObject("contextManagement") { contextManagement ->
            require("toolResultClearing" !in contextManagement) {
                "Schema-v7 context state already contains toolResultClearing"
            }
            val observation = contextManagement.getValue("usageObservation")
            require(observation === JsonNull || observation.jsonObject.keys == SCHEMA_V7_USAGE_OBSERVATION_KEYS) {
                "Schema-v7 usage observation has an unexpected shape"
            }
            JsonObject(
                contextManagement.toMutableMap().apply {
                    put("usageObservation", JsonNull)
                    put("toolResultClearing", JsonNull)
                },
            )
        }

private fun JsonArray.withToolResultHints(): JsonArray = JsonArray(
    map { message ->
        message.jsonObject.replaceArray("parts") { parts ->
            JsonArray(
                parts.map { part ->
                    val value = part.jsonObject
                    if (value.getValue("type").jsonPrimitive.content == "tool_result") {
                        value.withNullContextHints()
                    } else {
                        value
                    }
                },
            )
        }
    },
)

private fun JsonObject.withJournalResultHints(): JsonObject {
    val result = getValue("result")
    if (result === JsonNull) return this
    return JsonObject(toMutableMap().apply { put("result", result.jsonObject.withNullContextHints()) })
}

private fun JsonObject.withNullContextHints(): JsonObject {
    require("contextHints" !in this) { "Schema-v7 Tool result already contains contextHints" }
    return JsonObject(toMutableMap().apply { put("contextHints", JsonNull) })
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

private const val DISABLED_POLICY_MAX_STUB_CHARS = 2_000

private val SCHEMA_V7_USAGE_OBSERVATION_KEYS = setOf(
    "inputTokens",
    "throughMessageId",
    "historyPrefixDigest",
    "compactionGeneration",
    "provider",
    "model",
    "requestFingerprint",
)
