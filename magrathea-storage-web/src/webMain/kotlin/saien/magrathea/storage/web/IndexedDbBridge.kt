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

internal data class WebRawPersistenceRecord(
    val session: WebRawRecord,
    val checkpoint: WebRawRecord?,
)

internal interface WebRecordDatabase {
    suspend fun commit(key: String, sessionPayload: String, checkpointPayload: String?)
    suspend fun get(key: String): WebRawPersistenceRecord?
    suspend fun getAllSessions(): List<WebRawRecord>
    suspend fun delete(key: String)
    suspend fun clear()
}

internal class IndexedDbRecordDatabase(
    private val databaseName: String,
) : WebRecordDatabase {
    private val json = Json { ignoreUnknownKeys = false }

    override suspend fun commit(
        key: String,
        sessionPayload: String,
        checkpointPayload: String?,
    ) {
        execute(
            operation = if (checkpointPayload == null) "commit" else "commit_with_checkpoint",
            key = key,
            value = sessionPayload,
            secondaryValue = checkpointPayload.orEmpty(),
        )
    }

    override suspend fun get(key: String): WebRawPersistenceRecord? {
        val payload = execute(operation = "get", key = key, value = "")
            ?: throw WebStorageException(WebStorageFailure.OPERATION_FAILED)
        val result = decodeBridgePayload<BridgePersistenceLookup>(payload)
        if (!result.found) return null
        return WebRawPersistenceRecord(
            session = WebRawRecord(key, result.sessionValue),
            checkpoint = if (result.checkpointFound) {
                WebRawRecord(key, result.checkpointValue)
            } else {
                null
            },
        )
    }

    override suspend fun getAllSessions(): List<WebRawRecord> {
        val payload = execute(operation = "get_all_sessions", key = "", value = "")
            ?: throw WebStorageException(WebStorageFailure.OPERATION_FAILED)
        return decodeBridgePayload<List<BridgeRecord>>(payload).map { WebRawRecord(it.key, it.value) }
    }

    override suspend fun delete(key: String) {
        execute(operation = "delete", key = key, value = "")
    }

    override suspend fun clear() {
        execute(operation = "clear", key = "", value = "")
    }

    private suspend fun execute(
        operation: String,
        key: String,
        value: String,
        secondaryValue: String = "",
    ): String? {
        val rawResponse = try {
            val operationPromise = runIndexedDbOperation(
                databaseName = databaseName,
                operation = operation,
                key = key,
                value = value,
                secondaryValue = secondaryValue,
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
private data class BridgePersistenceLookup(
    val found: Boolean,
    val sessionValue: String? = null,
    val checkpointFound: Boolean = false,
    val checkpointValue: String? = null,
)

@Serializable
private data class BridgeRecord(
    val key: String? = null,
    val value: String? = null,
)

private fun runIndexedDbOperation(
    databaseName: String,
    operation: String,
    key: String,
    value: String,
    secondaryValue: String,
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
          if (operation === "commit" || operation === "commit_with_checkpoint") {
            transaction = database.transaction(
              ["sessions", "checkpoints"],
              "readwrite",
              { durability: "strict" }
            );
            transaction.objectStore("sessions").put(value, key);
            if (operation === "commit_with_checkpoint") {
              transaction.objectStore("checkpoints").put(secondaryValue, key);
            } else {
              transaction.objectStore("checkpoints").delete(key);
            }
          } else if (operation === "delete") {
            transaction = database.transaction(
              ["sessions", "checkpoints"],
              "readwrite",
              { durability: "strict" }
            );
            transaction.objectStore("sessions").delete(key);
            transaction.objectStore("checkpoints").delete(key);
          } else if (operation === "clear") {
            transaction = database.transaction(
              ["sessions", "checkpoints"],
              "readwrite",
              { durability: "strict" }
            );
            transaction.objectStore("sessions").clear();
            transaction.objectStore("checkpoints").clear();
          } else if (operation === "get") {
            transaction = database.transaction(["sessions", "checkpoints"], "readonly");
            let sessionResult;
            let checkpointResult;
            const sessionRequest = transaction.objectStore("sessions").get(key);
            const checkpointRequest = transaction.objectStore("checkpoints").get(key);
            sessionRequest.onsuccess = () => { sessionResult = sessionRequest.result; };
            checkpointRequest.onsuccess = () => { checkpointResult = checkpointRequest.result; };
            transaction.oncomplete = () => {
              database.close();
              const found = sessionResult !== undefined;
              finish(true, null, JSON.stringify({
                found: found,
                sessionValue:
                  found && typeof sessionResult === "string" ? sessionResult : null,
                checkpointFound: checkpointResult !== undefined,
                checkpointValue:
                  checkpointResult !== undefined && typeof checkpointResult === "string"
                    ? checkpointResult
                    : null
              }));
            };
          } else if (operation === "get_all_sessions") {
            transaction = database.transaction(["sessions"], "readonly");
            const records = [];
            const request = transaction.objectStore("sessions").openCursor();
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
        if (operation !== "get") {
          transaction.oncomplete = () => {
            database.close();
            finish(true, null, resultPayload);
          };
        }
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
