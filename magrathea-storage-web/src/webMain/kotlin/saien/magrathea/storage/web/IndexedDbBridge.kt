@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
@file:Suppress("REDUNDANT_CALL_OF_CONVERSION_METHOD")

package saien.magrathea.storage.web

import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.await
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import saien.magrathea.catalog.SessionCatalogException
import saien.magrathea.catalog.SessionCatalogFailure

internal data class WebRawRecord(val key: String?, val payload: String?)
internal data class WebRawPersistenceRecord(
    val session: WebRawRecord,
    val checkpoint: WebRawRecord?,
    val metadata: WebSessionMetadata? = null,
)

internal data class WebPayloadRewriteExpectation(
    val store: String,
    val key: String,
    val expectedPayload: String?,
    val rewritePayload: String?,
) {
    init {
        require(store == WEB_SESSION_STORE || store == WEB_CHECKPOINT_STORE)
    }
}

internal const val WEB_SESSION_STORE = "sessions"
internal const val WEB_CHECKPOINT_STORE = "checkpoints"

/** Raw injection seam retained for codec/corruption tests; public stores use catalog mutations. */
internal interface WebRecordDatabase {
    suspend fun commit(key: String, sessionPayload: String, checkpointPayload: String?)
    suspend fun get(key: String): WebRawPersistenceRecord?
    suspend fun getAllSessions(): List<WebRawRecord>
    suspend fun rewriteIfUnchanged(expectations: List<WebPayloadRewriteExpectation>): Boolean
    suspend fun delete(key: String)
    suspend fun clear()
}

internal class IndexedDbRecordDatabase(private val databaseName: String) : WebRecordDatabase {
    internal suspend fun executeCatalog(operation: String, payload: JsonObject): JsonElement {
        currentCoroutineContext().ensureActive()
        val response = try {
            val promise = runCatalogIndexedDbOperation(databaseName, operation, payload.toString())
            val raw = withContext(NonCancellable) { promise.await().toString() }
            // Successful writes must reach the caller's transient-context cleanup before cancellation.
            if (operation != "atomicWrite") currentCoroutineContext().ensureActive()
            Json.parseToJsonElement(raw).jsonObject
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            throw WebStorageException(WebStorageFailure.OPERATION_FAILED)
        }
        if (!response.boolean("ok")) {
            if (response.optionalString("code") == "catalog_counter_exhausted") {
                throw SessionCatalogException(SessionCatalogFailure.COUNTER_EXHAUSTED)
            }
            throw WebStorageException(
                when (response.optionalString("code")) {
                    "unavailable" -> WebStorageFailure.UNAVAILABLE
                    "blocked" -> WebStorageFailure.BLOCKED
                    "quota_exceeded" -> WebStorageFailure.QUOTA_EXCEEDED
                    "unsupported_database_version" -> WebStorageFailure.UNSUPPORTED_DATABASE_VERSION
                    else -> WebStorageFailure.OPERATION_FAILED
                },
            )
        }
        return response.optionalString("payload")?.let(Json::parseToJsonElement) ?: JsonNull
    }

    override suspend fun commit(key: String, sessionPayload: String, checkpointPayload: String?) {
        rawWrite(
            puts = listOf(putRecord(WEB_SESSION_STORE, key, JsonPrimitive(sessionPayload))) +
                listOfNotNull(checkpointPayload?.let { putRecord(WEB_CHECKPOINT_STORE, key, JsonPrimitive(it)) }),
            deletes = if (checkpointPayload == null) listOf(lookup(WEB_CHECKPOINT_STORE, key)) else emptyList(),
        )
    }

    override suspend fun get(key: String): WebRawPersistenceRecord? {
        val result = executeCatalog("read", wireObject {
            put("lookups", JsonArray(listOf(lookup(WEB_SESSION_STORE, key), lookup(WEB_CHECKPOINT_STORE, key))))
            put("metadataIds", buildJsonArray { add(key) })
        }).jsonObject
        val rows = result.array("values").map { it.jsonObject }
        val session = rows.first { it.string("store") == WEB_SESSION_STORE }
        if (!session.boolean("found")) return null
        val checkpoint = rows.first { it.string("store") == WEB_CHECKPOINT_STORE }
        return WebRawPersistenceRecord(
            WebRawRecord(key, session.getValue("value").rawPayload()),
            checkpoint.takeIf { it.boolean("found") }?.let { WebRawRecord(key, it.getValue("value").rawPayload()) },
            result.array("metadata").firstOrNull()?.jsonObject?.let(WebSessionMetadata::fromLookup),
        )
    }

    override suspend fun getAllSessions(): List<WebRawRecord> {
        // Explicit legacy bulk API only. Ordinary catalog reads always use a bounded index page.
        val result = executeCatalog("bulkSessions", wireObject { }).jsonObject
        return result.array("rows").map { item ->
            val row = item.jsonObject
            WebRawRecord(row.optionalString("key"), row.optionalString("payload"))
        }
    }

    override suspend fun rewriteIfUnchanged(expectations: List<WebPayloadRewriteExpectation>): Boolean {
        if (expectations.isEmpty()) return true
        require(expectations.map { it.store to it.key }.distinct().size == expectations.size)
        return rawWrite(
            checks = expectations.map { expected ->
                wireObject {
                    put("store", expected.store)
                    put("key", expected.key)
                    put("expectedPayload", expected.expectedPayload)
                }
            },
            puts = expectations.mapNotNull { expected ->
                expected.rewritePayload?.let {
                    putRecord(expected.store, expected.key, JsonPrimitive(it))
                }
            },
        )
    }

    override suspend fun delete(key: String) {
        rawWrite(deletes = listOf(lookup(WEB_SESSION_STORE, key), lookup(WEB_CHECKPOINT_STORE, key)))
    }

    override suspend fun clear() {
        rawWrite(clears = listOf(WEB_SESSION_STORE, WEB_CHECKPOINT_STORE))
    }

    private suspend fun rawWrite(
        puts: List<JsonObject> = emptyList(),
        deletes: List<JsonObject> = emptyList(),
        clears: List<String> = emptyList(),
        checks: List<JsonObject> = emptyList(),
    ): Boolean {
        repeat(5) {
            val read = executeCatalog("read", wireObject { }).jsonObject
            val result = executeCatalog("atomicWrite", wireObject {
                put("expectedState", read["state"] ?: JsonNull)
                put("puts", JsonArray(puts))
                put("deletes", JsonArray(deletes))
                put("checks", JsonArray(checks))
                put("clears", JsonArray(clears.map(::JsonPrimitive)))
            }).jsonPrimitive.boolean
            if (result) return true
            if (checks.isNotEmpty()) return false
        }
        throw WebStorageException(WebStorageFailure.OPERATION_FAILED)
    }
}

internal fun JsonElement.rawPayload(): String? = when (this) {
    is JsonPrimitive -> contentOrNull.takeIf { isString }
    is JsonObject -> (this["payload"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
    else -> null
}
