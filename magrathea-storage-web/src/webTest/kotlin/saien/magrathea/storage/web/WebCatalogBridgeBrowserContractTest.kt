@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class, kotlin.uuid.ExperimentalUuidApi::class)
@file:Suppress("REDUNDANT_CALL_OF_CONVERSION_METHOD")

package saien.magrathea.storage.web

import kotlinx.coroutines.await
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import saien.magrathea.catalog.*
import saien.magrathea.core.*
import kotlin.js.JsString
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/** Real IndexedDB transaction/interop contracts shared by JS and Wasm browser tests. */
class WebCatalogBridgeBrowserContractTest {
    @Test
    fun nonJsonStructuredCloneSourcesFailWithoutHangingOrChangingStoredValues() = runTest {
        withBridgeDatabase { name ->
            assertEquals("ok", createNonJsonBridgeSources(name).await().toString())
            val store = createMagratheaWebStore(MagratheaWebStoreConfiguration(name))
            try {
                assertEquals(CatalogCoverage.INDEXING, store.catalog.state().coverage)
                for (id in listOf("cycle", "bigint")) {
                    assertEquals(WebStorageFailure.OPERATION_FAILED,
                        assertFailsWith<WebStorageException> { store.persistence.load(AgentSessionId(id)) }.failure)
                }
                assertEquals(CatalogCoverage.INDEXING, store.catalog.state().coverage)
                assertEquals("preserved", inspectNonJsonBridgeSources(name).await().toString())
            } finally { store.close() }
        }
    }

    @Test
    fun cancellationAfterCanonicalCommitConsumesPreparedContextBeforePropagating() = runTest {
        withBridgeDatabase { name ->
            val store = createMagratheaWebStore(MagratheaWebStoreConfiguration(name), projector = BridgePreparedProjector())
            try {
                val first = bridgePreparedSnapshot("u1")
                val identity = store.catalog.prepareNewSession(first.sessionId, "life", SessionOrganization(title = "Initial title"))
                store.catalog.prepareActivity(identity, "u1")
                cancelAfterBridgeWriteCompletes(name) { store.persistence.commit(first, null) }
                assertEquals(identity, store.catalog.sessionIdentity(first.sessionId))
                assertEquals("Initial title", store.catalog.organization(identity)?.title)

                val second = bridgePreparedSnapshot("u2")
                val latest = store.catalog.prepareActivity(identity, "u2")
                store.persistence.commit(second, null)
                // The committed-but-cancelled u1 must no longer occupy the transient Advance slot.
                store.catalog.preparePreservedActivity(identity, "u1")
                store.persistence.commit(first, null)
                assertEquals(SessionActivityOrder.Accepted(latest.sequence), store.catalog.sessionHeader(first.sessionId)?.activity?.order)
            } finally { store.close() }
        }
    }

    @Test
    fun cancellationAfterDeleteOrClearReleasesUnsavedInitializationBeforePropagating() = runTest {
        for (clear in listOf(false, true)) {
            withBridgeDatabase { name ->
                val store = createMagratheaWebStore(MagratheaWebStoreConfiguration(name), projector = BridgePreparedProjector())
                try {
                    val id = AgentSessionId("reused")
                    val abandoned = store.catalog.prepareNewSession(id, "old-life")
                    store.catalog.prepareActivity(abandoned, "old-input")
                    cancelAfterBridgeWriteCompletes(name) {
                        if (clear) store.persistence.clear() else store.persistence.deleteSession(id)
                    }
                    val fresh = store.catalog.prepareNewSession(id, "new-life")
                    assertEquals("new-life", fresh.incarnation)
                    assertEquals(fresh, store.catalog.prepareActivity(fresh, "new-input").identity)
                } finally { store.close() }
            }
        }
    }

    @Test
    fun synchronousAsynchronousAndCallbackFailuresAbortEveryQueuedWrite() = runTest {
        withBridgeDatabase { name ->
            val state = control("0")
            assertEquals(JsonPrimitive(true), call(name, "atomicWrite", wireObject {
                put("expectedState", JsonNull); put("state", state)
                put("puts", JsonArray(listOf(putRecord("sessions", "stable", JsonPrimitive("original")))))
            }))
            val writes = listOf(
                wireObject { put("store", "forbidden"); put("key", "x"); put("value", "invalid") },
                putRecord("session_collections", "one", wireObject { put("nameUniqueKey", "duplicate") }),
            )
            for (kind in 0..2) {
                val result = response(name, "atomicWrite", wireObject {
                    put("expectedState", state); put("state", control("1"))
                    put("puts", JsonArray(listOf(putRecord("sessions", "stable", JsonPrimitive("must roll back"))) + when (kind) {
                        0 -> listOf(writes[0])
                        1 -> listOf(writes[1], putRecord("session_collections", "two", wireObject { put("nameUniqueKey", "duplicate") }))
                        else -> emptyList()
                    }))
                    if (kind == 2) put("patches", JsonArray(listOf(wireObject {
                        put("store", "sessions"); put("key", "stable")
                        put("fields", wireObject { put("payload", "a string cannot be patched as an object") })
                    })))
                })
                assertFalse(result.getValue("ok").jsonPrimitive.boolean)
                val persisted = call(name, "read", wireObject {
                    put("lookups", JsonArray(listOf(lookup("sessions", "stable"), lookup("session_collections", "one"))))
                }).jsonObject
                assertEquals(state, persisted["state"])
                assertEquals("original", persisted.getValue("values").jsonArray[0].jsonObject.getValue("value").jsonPrimitive.content)
                assertFalse(persisted.getValue("values").jsonArray[1].jsonObject.getValue("found").jsonPrimitive.boolean)
            }
        }
    }

    @Test
    fun metadataReadsOnlyKeysAndPreservesLongValuesAcrossLegacyUpgrade() = runTest {
        withBridgeDatabase { name ->
            assertEquals("ok", createLegacyBridgeDatabase(name, false).await().toString())
            val state = control(Long.MAX_VALUE.toString())
            val key = buildJsonArray {
                add("new"); add("incarnation"); add(Long.MAX_VALUE.toString())
                add("0"); add("1user"); add("1A:9007199254740993"); add("1COMPLETED"); add(0)
            }
            assertEquals(JsonPrimitive(true), call(name, "atomicWrite", wireObject {
                put("expectedState", JsonNull); put("state", state)
                put("puts", JsonArray(listOf(putRecord("sessions", "new", wireObject {
                    put("payload", "opaque canonical bytes"); put("metadataKey", key)
                    put("recoveryKey", buildJsonArray { add(0); add("new") })
                }))))
            }))
            installBridgeCanonicalReadTrap()
            val read = try {
                call(name, "read", wireObject { put("metadataIds", buildJsonArray { add("new"); add("legacy"); add("missing") }) }).jsonObject
            } finally { restoreBridgeCanonicalReadTrap() }
            val metadata = read.getValue("metadata").jsonArray
            assertEquals(key, metadata[0].jsonObject["key"])
            assertEquals(state, read["state"])
            assertEquals(JsonNull, metadata[1].jsonObject["key"])
            assertTrue(metadata[1].jsonObject.getValue("checkpointFound").jsonPrimitive.boolean)
            assertFalse(metadata[2].jsonObject.getValue("found").jsonPrimitive.boolean)
            val legacy = call(name, "read", wireObject { put("lookups", JsonArray(listOf(lookup("sessions", "legacy")))) }).jsonObject
            assertEquals("opaque legacy bytes", legacy.getValue("values").jsonArray[0].jsonObject.getValue("value").jsonPrimitive.content)
        }
    }

    @Test
    fun physicalKeyTokensResumeAcrossEveryLegacyIndexedDbKeyTypeWithoutReadingBodies() = runTest {
        withBridgeDatabase { name ->
            assertEquals("ok", createLegacyBridgeKeys(name).await().toString())
            val expected = Json.parseToJsonElement("""[
                ["n","-Infinity"], ["n","42"], ["n","Infinity"], ["d","0"],
                ["s","valid-session"], ["b",[0,255]],
                ["a",[["n","1"],["d","2"],["b",[3]],["a",[["s","nested"]]]]]
            ]""").jsonArray
            var after: JsonElement? = null
            installBridgeCanonicalReadTrap()
            try {
                for (token in expected) {
                    val page = call(name, "keys", wireObject {
                        put("store", "sessions"); put("limit", 1)
                        after?.let { put("range", wireObject { put("lowerToken", it); put("lowerOpen", true) }) }
                    }).jsonObject.getValue("keys").jsonArray
                    assertEquals(token, page.single().jsonObject["primaryKeyToken"])
                    val exact = call(name, "keys", wireObject {
                        put("store", "sessions"); put("limit", 2)
                        put("range", wireObject { put("onlyToken", token) })
                    }).jsonObject.getValue("keys").jsonArray
                    assertEquals(token, exact.single().jsonObject["primaryKeyToken"])
                    after = token
                }
                val end = call(name, "keys", wireObject {
                    put("store", "sessions"); put("limit", 1)
                    put("range", wireObject { put("lowerToken", requireNotNull(after)); put("lowerOpen", true) })
                }).jsonObject.getValue("keys").jsonArray
                assertTrue(end.isEmpty())
                val previous = call(name, "keys", wireObject {
                    put("store", "sessions"); put("limit", 1); put("direction", "prev")
                    put("range", wireObject { put("upperToken", requireNotNull(after)); put("upperOpen", true) })
                }).jsonObject.getValue("keys").jsonArray
                assertEquals(expected[expected.lastIndex - 1], previous.single().jsonObject["primaryKeyToken"])
            } finally { restoreBridgeCanonicalReadTrap() }
        }
    }

    @Test
    fun rangeDeletionCompletesBeforeReplacementReferencesAreInserted() = runTest {
        withBridgeDatabase { name ->
            val state = control("0")
            fun reference(uri: String, session: String) = putRecord("session_attachment_refs",
                buildJsonArray { add(uri); add(session) }, wireObject { put("uri", uri); put("sessionId", session) })
            call(name, "atomicWrite", wireObject {
                put("expectedState", JsonNull); put("state", state)
                put("puts", JsonArray(listOf(reference("old", "one"), reference("shared", "two"))))
            })
            assertEquals(JsonPrimitive(true), call(name, "atomicWrite", wireObject {
                put("expectedState", state); put("state", control("1"))
                put("deleteRanges", JsonArray(listOf(wireObject {
                    put("store", "session_attachment_refs"); put("index", "session"); put("range", onlyRange(JsonPrimitive("one")))
                })))
                put("puts", JsonArray(listOf(reference("new", "one"))))
            }))
            val keys = call(name, "keys", wireObject { put("store", "session_attachment_refs"); put("limit", 10) }).jsonObject.getValue("keys").jsonArray
            assertEquals(listOf("new", "shared"), keys.map { it.jsonObject.getValue("primaryKey").jsonArray[0].jsonPrimitive.content })
        }
    }

    @Test
    fun explicitExistenceCheckMatchesStoredNullAndMalformedControlIsNeverOverwritten() = runTest {
        withBridgeDatabase { name ->
            val state = control("0")
            call(name, "atomicWrite", wireObject {
                put("expectedState", JsonNull); put("state", state)
                put("puts", JsonArray(listOf(putRecord("sessions", "null-source", JsonNull))))
            })
            val check = wireObject {
                put("store", "sessions"); put("key", "null-source"); put("expectedFound", true); put("expectedValue", JsonNull)
            }
            assertEquals(JsonPrimitive(true), call(name, "atomicWrite", wireObject {
                put("expectedState", state); put("state", control("1")); put("checks", JsonArray(listOf(check)))
            }))
            val raw = call(name, "read", wireObject { put("lookups", JsonArray(listOf(lookup("sessions", "null-source")))) }).jsonObject
                .getValue("values").jsonArray[0].jsonObject
            assertTrue(raw.getValue("found").jsonPrimitive.boolean)
            assertEquals(JsonNull, raw["value"])
            call(name, "atomicWrite", wireObject {
                put("expectedState", control("1"))
                put("puts", JsonArray(listOf(putRecord("catalog_state", "state", JsonNull))))
            })
            val failure = response(name, "read", wireObject { })
            assertFalse(failure.getValue("ok").jsonPrimitive.boolean)
            assertEquals("operation_failed", failure.getValue("code").jsonPrimitive.content)
            assertEquals("null", readBridgeControlDirectly(name).await().toString())
        }
    }

    @Test
    fun blockedUpgradeDoesNotUpgradeLaterOrLeaveAnOpenConnection() = runTest {
        withBridgeDatabase { name ->
            assertEquals("ok", createLegacyBridgeDatabase(name, true).await().toString())
            try {
                val result = response(name, "read", wireObject { })
                assertFalse(result.getValue("ok").jsonPrimitive.boolean)
                assertEquals("blocked", result.getValue("code").jsonPrimitive.content)
            } finally { releaseHeldBridgeDatabase(name) }
            assertEquals("1", openBridgeVersionOne(name).await().toString())
        }
    }

    @Test
    fun upgradeFailureRollsBackSchemaBeforeRetrying() = runTest {
        withBridgeDatabase { name ->
            installBridgeUpgradeFailure()
            val result = try { response(name, "read", wireObject { }) } finally { restoreBridgeUpgradeFailure() }
            assertFalse(result.getValue("ok").jsonPrimitive.boolean)
            assertEquals("quota_exceeded", result.getValue("code").jsonPrimitive.content)
            assertEquals(JsonNull, call(name, "read", wireObject { }).jsonObject["state"])
        }
    }

    private fun control(revision: String) = wireObject { put("revision", revision); put("generation", "9007199254740993") }

    private suspend fun response(name: String, operation: String, input: JsonObject): JsonObject =
        Json.parseToJsonElement(runCatalogIndexedDbOperation(name, operation, input.toString()).await().toString()).jsonObject

    private suspend fun call(name: String, operation: String, input: JsonObject): JsonElement {
        val response = response(name, operation, input)
        assertTrue(response.getValue("ok").jsonPrimitive.boolean, "IndexedDB bridge operation failed")
        return Json.parseToJsonElement(response.getValue("payload").jsonPrimitive.content)
    }
}

private class BridgePreparedProjector : SessionCatalogProjector {
    override val policy = SessionCatalogPolicy("bridge-prepared-test", 1)
    override val activityMode = SessionActivityMode.PREPARED
    override fun normalizeSearch(text: String) = text
    override fun project(snapshot: AgentSessionSnapshot) = SessionCatalogProjection(
        fallbackLabel = "Session", activityIdentity = snapshot.state.messages.last().id,
        activityAtEpochMs = snapshot.updatedAtEpochMs, normalizedFallbackLabel = "Session",
    )
}

private fun bridgePreparedSnapshot(activity: String): AgentSessionSnapshot {
    val id = AgentSessionId("prepared-cancellation")
    val message = AgentMessage(id = activity, role = MessageRole.USER, parts = listOf(TextPart(activity)), createdAtEpochMs = 1)
    val request = AgentRequest(sessionId = id, messages = listOf(message), model = ModelDescriptor("offline", "test"))
    return AgentSessionSnapshot(id, AgentRunId("run"), request, AgentStateSnapshot(messages = request.messages), updatedAtEpochMs = 1)
}

private suspend fun cancelAfterBridgeWriteCompletes(name: String, block: suspend () -> Unit) = coroutineScope {
    val committed = observeNextBridgeWriteCompletion(name)
    try {
        val writing = launch { block() }
        // This listener is installed before the bridge's oncomplete handler. Its Promise resumes
        // first, so cancellation is observed after durable completion but before the write returns.
        val cancelling = launch(start = CoroutineStart.UNDISPATCHED) {
            assertEquals("committed", committed.await().toString())
            writing.cancel()
        }
        writing.join()
        cancelling.join()
        assertTrue(writing.isCancelled)
    } finally { restoreBridgeTransactionObserver() }
}

private suspend fun withBridgeDatabase(block: suspend (String) -> Unit) {
    val name = "magrathea-bridge-${Uuid.random()}"
    try { block(name) } finally {
        releaseHeldBridgeDatabase(name)
        assertEquals("ok", deleteBridgeDatabase(name).await().toString())
    }
}

private fun createLegacyBridgeDatabase(name: String, hold: Boolean): Promise<JsString> = js("""
    new Promise((resolve) => {
      const request = indexedDB.open(name, 1);
      request.onupgradeneeded = () => {
        request.result.createObjectStore("sessions"); request.result.createObjectStore("checkpoints");
      };
      request.onsuccess = () => {
        const database = request.result;
        const tx = database.transaction(["sessions", "checkpoints"], "readwrite");
        tx.objectStore("sessions").put("opaque legacy bytes", "legacy");
        tx.objectStore("checkpoints").put("opaque checkpoint bytes", "legacy");
        tx.oncomplete = () => {
          if (hold) {
            if (!globalThis.__magratheaBridgeHeld) globalThis.__magratheaBridgeHeld = new Map();
            globalThis.__magratheaBridgeHeld.set(name, database);
          } else database.close();
          resolve("ok");
        };
        tx.onabort = () => { database.close(); resolve("error"); };
      };
      request.onerror = () => resolve("error");
    })
""")

private fun createLegacyBridgeKeys(name: String): Promise<JsString> = js("""
    new Promise((resolve) => {
      const request = indexedDB.open(name, 1);
      request.onupgradeneeded = () => {
        request.result.createObjectStore("sessions"); request.result.createObjectStore("checkpoints");
      };
      request.onsuccess = () => {
        const database = request.result;
        const tx = database.transaction(["sessions"], "readwrite");
        const keys = [-Infinity, 42, Infinity, new Date(0), "valid-session", new Uint8Array([0, 255]),
          [1, new Date(2), new Uint8Array([3]), ["nested"]]];
        keys.forEach((key, index) => tx.objectStore("sessions").put("opaque legacy value " + index, key));
        tx.oncomplete = () => { database.close(); resolve("ok"); };
        tx.onabort = () => { database.close(); resolve("error"); };
      };
      request.onerror = () => resolve("error");
    })
""")

private fun createNonJsonBridgeSources(name: String): Promise<JsString> = js("""
    new Promise((resolve) => {
      const request = indexedDB.open(name, 1);
      request.onupgradeneeded = () => {
        request.result.createObjectStore("sessions"); request.result.createObjectStore("checkpoints");
      };
      request.onsuccess = () => {
        const database = request.result;
        const tx = database.transaction(["sessions"], "readwrite");
        const cycle = { marker: "must remain opaque" }; cycle.self = cycle;
        tx.objectStore("sessions").put(cycle, "cycle");
        tx.objectStore("sessions").put(BigInt("9007199254740993"), "bigint");
        tx.oncomplete = () => { database.close(); resolve("ok"); };
        tx.onabort = () => { database.close(); resolve("error"); };
      };
      request.onerror = () => resolve("error");
    })
""")

private fun inspectNonJsonBridgeSources(name: String): Promise<JsString> = js("""
    new Promise((resolve) => {
      const request = indexedDB.open(name, 2);
      request.onsuccess = () => {
        const database = request.result;
        const tx = database.transaction(["sessions"], "readonly");
        let cyclePreserved = false; let bigintPreserved = false;
        const cycle = tx.objectStore("sessions").get("cycle");
        cycle.onsuccess = () => { cyclePreserved = cycle.result.self === cycle.result && cycle.result.marker === "must remain opaque"; };
        const bigint = tx.objectStore("sessions").get("bigint");
        bigint.onsuccess = () => { bigintPreserved = bigint.result === BigInt("9007199254740993"); };
        tx.oncomplete = () => { database.close(); resolve(cyclePreserved && bigintPreserved ? "preserved" : "changed"); };
        tx.onabort = () => { database.close(); resolve("error"); };
      };
      request.onerror = () => resolve("error");
    })
""")

private fun observeNextBridgeWriteCompletion(name: String): Promise<JsString> = js("""
    new Promise((resolve) => {
      const original = IDBDatabase.prototype.transaction;
      globalThis.__magratheaBridgeOriginalTransaction = original;
      let observed = false;
      IDBDatabase.prototype.transaction = function() {
        const tx = original.apply(this, arguments);
        if (!observed && this.name === name && tx.mode === "readwrite") {
          observed = true;
          tx.addEventListener("complete", () => resolve("committed"));
          tx.addEventListener("abort", () => resolve("aborted"));
        }
        return tx;
      };
    })
""")

private fun restoreBridgeTransactionObserver(): Unit = js("""
    {
      IDBDatabase.prototype.transaction = globalThis.__magratheaBridgeOriginalTransaction;
      delete globalThis.__magratheaBridgeOriginalTransaction;
    }
""")

private fun releaseHeldBridgeDatabase(name: String): Unit = js("""
    {
      const held = globalThis.__magratheaBridgeHeld && globalThis.__magratheaBridgeHeld.get(name);
      if (held) { held.close(); globalThis.__magratheaBridgeHeld.delete(name); }
    }
""")

private fun openBridgeVersionOne(name: String): Promise<JsString> = js("""
    new Promise((resolve) => {
      const request = indexedDB.open(name, 1);
      request.onsuccess = () => { const version = String(request.result.version); request.result.close(); resolve(version); };
      request.onerror = () => resolve("error");
      request.onblocked = () => resolve("blocked");
    })
""")

private fun installBridgeCanonicalReadTrap(): Unit = js("""
    {
      globalThis.__magratheaBridgeOriginalGet = IDBObjectStore.prototype.get;
      IDBObjectStore.prototype.get = function(key) {
        if (this.name === "sessions" || this.name === "checkpoints") throw new Error("Metadata read selected canonical values");
        return globalThis.__magratheaBridgeOriginalGet.call(this, key);
      };
    }
""")

private fun restoreBridgeCanonicalReadTrap(): Unit = js("""
    { IDBObjectStore.prototype.get = globalThis.__magratheaBridgeOriginalGet; delete globalThis.__magratheaBridgeOriginalGet; }
""")

private fun installBridgeUpgradeFailure(): Unit = js("""
    {
      globalThis.__magratheaBridgeOriginalCreate = IDBDatabase.prototype.createObjectStore;
      let count = 0;
      IDBDatabase.prototype.createObjectStore = function() {
        if (++count === 3) throw new DOMException("injected", "QuotaExceededError");
        return globalThis.__magratheaBridgeOriginalCreate.apply(this, arguments);
      };
    }
""")

private fun restoreBridgeUpgradeFailure(): Unit = js("""
    { IDBDatabase.prototype.createObjectStore = globalThis.__magratheaBridgeOriginalCreate; delete globalThis.__magratheaBridgeOriginalCreate; }
""")

private fun readBridgeControlDirectly(name: String): Promise<JsString> = js("""
    new Promise((resolve) => {
      const request = indexedDB.open(name, 2);
      request.onsuccess = () => {
        const database = request.result;
        const tx = database.transaction(["catalog_state"], "readonly");
        let result;
        const read = tx.objectStore("catalog_state").get("state");
        read.onsuccess = () => { result = JSON.stringify(read.result); };
        tx.oncomplete = () => { database.close(); resolve(result); };
        tx.onabort = () => { database.close(); resolve("error"); };
      };
      request.onerror = () => resolve("error");
    })
""")

private fun deleteBridgeDatabase(name: String): Promise<JsString> = js("""
    new Promise((resolve) => {
      const request = indexedDB.deleteDatabase(name);
      request.onsuccess = () => resolve("ok");
      request.onerror = () => resolve("error");
      request.onblocked = () => resolve("blocked");
    })
""")
