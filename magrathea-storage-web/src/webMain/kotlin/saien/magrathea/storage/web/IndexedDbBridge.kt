@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
@file:Suppress("REDUNDANT_CALL_OF_CONVERSION_METHOD")

package saien.magrathea.storage.web

import kotlin.coroutines.cancellation.CancellationException
import kotlin.js.JsString
import kotlin.js.Promise
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.await
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

internal data class WebRawRecord(
    val key: String?,
    val payload: String?,
)

internal interface WebRecordDatabase {
    suspend fun put(kind: WebStoredRecordKind, key: String, payload: String)
    suspend fun get(kind: WebStoredRecordKind, key: String): WebRawRecord?
    suspend fun getAll(kind: WebStoredRecordKind): List<WebRawRecord>
    suspend fun delete(kind: WebStoredRecordKind, key: String)
    suspend fun clear(kind: WebStoredRecordKind)
}

internal class IndexedDbRecordDatabase(
    private val databaseName: String,
) : WebRecordDatabase {
    private val json = Json { ignoreUnknownKeys = false }

    override suspend fun put(kind: WebStoredRecordKind, key: String, payload: String) {
        execute(operation = "put", kind = kind, key = key, value = payload)
    }

    override suspend fun get(kind: WebStoredRecordKind, key: String): WebRawRecord? {
        val payload = execute(operation = "get", kind = kind, key = key, value = "")
            ?: throw WebStorageException(WebStorageFailure.OPERATION_FAILED)
        val result = decodeBridgePayload<BridgeLookup>(payload)
        return if (result.found) WebRawRecord(key, result.value) else null
    }

    override suspend fun getAll(kind: WebStoredRecordKind): List<WebRawRecord> {
        val payload = execute(operation = "get_all", kind = kind, key = "", value = "")
            ?: throw WebStorageException(WebStorageFailure.OPERATION_FAILED)
        return decodeBridgePayload<List<BridgeRecord>>(payload).map { WebRawRecord(it.key, it.value) }
    }

    override suspend fun delete(kind: WebStoredRecordKind, key: String) {
        execute(operation = "delete", kind = kind, key = key, value = "")
    }

    override suspend fun clear(kind: WebStoredRecordKind) {
        execute(operation = "clear", kind = kind, key = "", value = "")
    }

    private suspend fun execute(
        operation: String,
        kind: WebStoredRecordKind,
        key: String,
        value: String,
    ): String? {
        val rawResponse = try {
            val operationPromise = runIndexedDbOperation(
                databaseName = databaseName,
                operation = operation,
                storeName = kind.storeName,
                key = key,
                value = value,
            )
            val response = withContext(NonCancellable) { operationPromise.await().toString() }
            currentCoroutineContext().ensureActive()
            response
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            throw WebStorageException(WebStorageFailure.OPERATION_FAILED)
        }
        val response = try {
            json.decodeFromString<BridgeResponse>(rawResponse)
        } catch (_: Throwable) {
            throw WebStorageException(WebStorageFailure.OPERATION_FAILED)
        }
        if (!response.ok) throw WebStorageException(response.code.toStorageFailure())
        return response.payload
    }

    private inline fun <reified T> decodeBridgePayload(payload: String): T = try {
        json.decodeFromString(payload)
    } catch (_: Throwable) {
        throw WebStorageException(WebStorageFailure.OPERATION_FAILED)
    }
}

private val WebStoredRecordKind.storeName: String
    get() = when (this) {
        WebStoredRecordKind.SESSION -> "sessions"
        WebStoredRecordKind.CHECKPOINT -> "checkpoints"
    }

private fun String?.toStorageFailure(): WebStorageFailure = when (this) {
    "unavailable" -> WebStorageFailure.UNAVAILABLE
    "blocked" -> WebStorageFailure.BLOCKED
    "quota_exceeded" -> WebStorageFailure.QUOTA_EXCEEDED
    "unsupported_database_version" -> WebStorageFailure.UNSUPPORTED_DATABASE_VERSION
    else -> WebStorageFailure.OPERATION_FAILED
}

@Serializable
private data class BridgeResponse(
    val ok: Boolean,
    val code: String? = null,
    val payload: String? = null,
)

@Serializable
private data class BridgeLookup(
    val found: Boolean,
    val value: String? = null,
)

@Serializable
private data class BridgeRecord(
    val key: String? = null,
    val value: String? = null,
)

private fun runIndexedDbOperation(
    databaseName: String,
    operation: String,
    storeName: String,
    key: String,
    value: String,
): Promise<JsString> = js(
    """
    new Promise((resolve) => {
      let settled = false;
      const finish = (ok, code, payload) => {
        if (settled) return;
        settled = true;
        resolve(JSON.stringify({
          ok: ok,
          code: code === undefined ? null : code,
          payload: payload === undefined ? null : payload
        }));
      };
      const classifyError = (error) => {
        const name = error && typeof error.name === "string" ? error.name : "";
        if (name === "QuotaExceededError") return "quota_exceeded";
        if (name === "VersionError") return "unsupported_database_version";
        if (name === "NotFoundError") return "unsupported_database_version";
        if (name === "SecurityError" || name === "InvalidStateError" || name === "NotSupportedError") {
          return "unavailable";
        }
        return "operation_failed";
      };
      if (!globalThis.indexedDB) {
        finish(false, "unavailable", null);
        return;
      }
      let openRequest;
      try {
        openRequest = globalThis.indexedDB.open(databaseName, 1);
      } catch (error) {
        finish(false, classifyError(error), null);
        return;
      }
      openRequest.onblocked = () => finish(false, "blocked", null);
      openRequest.onerror = () => finish(false, classifyError(openRequest.error), null);
      openRequest.onupgradeneeded = (event) => {
        if (event.oldVersion !== 0) {
          try { openRequest.transaction.abort(); } catch (_) {}
          finish(false, "unsupported_database_version", null);
          return;
        }
        try {
          const database = openRequest.result;
          database.createObjectStore("sessions");
          database.createObjectStore("checkpoints");
        } catch (error) {
          try { openRequest.transaction.abort(); } catch (_) {}
          finish(false, classifyError(error), null);
        }
      };
      openRequest.onsuccess = () => {
        const database = openRequest.result;
        database.onversionchange = () => database.close();
        if (settled) {
          database.close();
          return;
        }
        if (database.version !== 1) {
          database.close();
          finish(false, "unsupported_database_version", null);
          return;
        }
        let transaction;
        let resultPayload = null;
        try {
          if (operation === "put") {
            transaction = database.transaction([storeName], "readwrite", { durability: "strict" });
            transaction.objectStore(storeName).put(value, key);
          } else if (operation === "delete") {
            transaction = database.transaction([storeName], "readwrite", { durability: "strict" });
            transaction.objectStore(storeName).delete(key);
          } else if (operation === "clear") {
            transaction = database.transaction([storeName], "readwrite", { durability: "strict" });
            transaction.objectStore(storeName).clear();
          } else if (operation === "get") {
            transaction = database.transaction([storeName], "readonly");
            const request = transaction.objectStore(storeName).get(key);
            request.onsuccess = () => {
              const found = request.result !== undefined;
              resultPayload = JSON.stringify({
                found: found,
                value: found && typeof request.result === "string" ? request.result : null
              });
            };
          } else if (operation === "get_all") {
            transaction = database.transaction([storeName], "readonly");
            const records = [];
            const request = transaction.objectStore(storeName).openCursor();
            request.onsuccess = () => {
              const cursor = request.result;
              if (cursor) {
                records.push({
                  key: typeof cursor.key === "string" ? cursor.key : null,
                  value: typeof cursor.value === "string" ? cursor.value : null
                });
                cursor.continue();
              } else {
                resultPayload = JSON.stringify(records);
              }
            };
          } else {
            database.close();
            finish(false, "operation_failed", null);
            return;
          }
        } catch (error) {
          database.close();
          finish(false, classifyError(error), null);
          return;
        }
        transaction.oncomplete = () => {
          database.close();
          finish(true, null, resultPayload);
        };
        transaction.onerror = () => {
          database.close();
          finish(false, classifyError(transaction.error), null);
        };
        transaction.onabort = () => {
          database.close();
          finish(false, classifyError(transaction.error), null);
        };
      };
    })
    """,
)
