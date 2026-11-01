@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class, kotlin.uuid.ExperimentalUuidApi::class)
@file:Suppress("REDUNDANT_CALL_OF_CONVERSION_METHOD")

package saien.magrathea.storage.web

import kotlin.js.JsString
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid
import kotlinx.coroutines.await
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import saien.magrathea.catalog.CatalogCoverage
import saien.magrathea.catalog.DefaultSessionCatalogProjector
import saien.magrathea.catalog.SessionCatalogException
import saien.magrathea.catalog.SessionCatalogFailure
import saien.magrathea.catalog.SessionCollection
import saien.magrathea.catalog.SessionOrganization
import saien.magrathea.core.AgentMessage
import saien.magrathea.core.AgentRequest
import saien.magrathea.core.AgentRunId
import saien.magrathea.core.AgentSessionId
import saien.magrathea.core.AgentSessionSnapshot
import saien.magrathea.core.AgentStateSnapshot
import saien.magrathea.core.MessageRole
import saien.magrathea.core.ModelDescriptor
import saien.magrathea.core.TextPart

class WebCatalogConcurrencyBrowserTest {
    @Test
    fun legacyBulkReadRetainsOneSnapshotWhenAnotherTransactionDeletesAListedRow() = runTest {
        withConcurrencyDatabase { name ->
            val corruptions = mutableListOf<WebStoredRecordCorruption>()
            val store = createMagratheaWebStore(
                MagratheaWebStoreConfiguration(name),
                corruptionReporter = WebStoredRecordCorruptionReporter(corruptions::add),
            )
            try {
                store.persistence.commit(concurrencySnapshot("a"), null)
                store.persistence.commit(concurrencySnapshot("b"), null)
                val deleted = queueWriterAfterFirstRead(name, "bulk")
                val listed = store.persistence.listSessions()
                assertEquals("done", deleted.await().toString())
                assertEquals(listOf("a", "b"), listed.map { it.sessionId.value }.sorted())
                assertTrue(corruptions.isEmpty())
                assertEquals(1, concurrencyTransactionCount())
                restoreConcurrencyObserver()
                assertNull(store.persistence.load(AgentSessionId("b")))
            } finally {
                restoreConcurrencyObserver()
                store.close()
            }
        }
    }

    @Test
    fun referenceCandidatesAndCoverageShareOneReadTransactionUnderConcurrentChanges() = runTest {
        withConcurrencyDatabase { name ->
            val store = createMagratheaWebStore(MagratheaWebStoreConfiguration(name))
            val database = IndexedDbRecordDatabase(name)
            try {
                store.catalog.state()
                val before = database.executeCatalog("read", wireObject { }).jsonObject
                database.executeCatalog("atomicWrite", wireObject {
                    put("expectedState", before.getValue("state"))
                    put("puts", JsonArray(listOf("one", "two").map { uri ->
                        putRecord(CATALOG_REFS, uri, wireObject { put("uri", uri) })
                    }))
                })
                val changed = queueWriterAfterFirstRead(name, "refs")
                val result = store.catalog.referencedUris(setOf("one", "two", "absent"))
                assertEquals("done", changed.await().toString())
                assertEquals(setOf("one", "two"), result.referencedUris)
                assertEquals(CatalogCoverage.COMPLETE, result.coverage)
                assertEquals(1, concurrencyTransactionCount())
                restoreConcurrencyObserver()
                val latest = store.catalog.referencedUris(setOf("one", "two"))
                assertTrue(latest.referencedUris.isEmpty())
                assertEquals(CatalogCoverage.PARTIAL, latest.coverage)
            } finally {
                restoreConcurrencyObserver()
                store.close()
            }
        }
    }

    @Test
    fun canonicalCommitSurvivesUnrelatedControlChangesBeforeEveryWrite() = runTest {
        withConcurrencyDatabase { name ->
            val store = createMagratheaWebStore(MagratheaWebStoreConfiguration(name))
            try {
                val initial = store.catalog.state()
                injectControlChurn(name, once = false, puts = "[]")
                store.persistence.commit(concurrencySnapshot("first"), null)
                store.persistence.commit(concurrencySnapshot("second"), null)
                assertEquals(2, concurrencyTransactionCount())
                restoreConcurrencyObserver()
                val source = IndexedDbRecordDatabase(name).executeCatalog("read", wireObject { }).jsonObject
                val control = WebCatalogControl.from(source.getValue("state").jsonObject)
                assertEquals(initial.revision + 4, control.revision)
                assertEquals(2L, control.sequence)
                assertEquals(2L, control.processed)
                assertNotNull(store.persistence.load(AgentSessionId("first")))
                assertNotNull(store.persistence.load(AgentSessionId("second")))
            } finally {
                restoreConcurrencyObserver()
                store.close()
            }
        }
    }

    @Test
    fun rebasedRevisionReachesLongMaximumExactlyAndOverflowRollsBackCanonicalWrites() = runTest {
        withConcurrencyDatabase { name ->
            val store = createMagratheaWebStore(MagratheaWebStoreConfiguration(name))
            val database = IndexedDbRecordDatabase(name)
            try {
                store.catalog.state()
                val before = database.executeCatalog("read", wireObject { }).jsonObject.getValue("state").jsonObject
                val high = JsonObject(before + ("revision" to JsonPrimitive((Long.MAX_VALUE - 2).toString())))
                database.executeCatalog("atomicWrite", wireObject {
                    put("expectedState", before)
                    put("state", high)
                })
                injectControlChurn(name, once = false, puts = "[]")
                store.persistence.commit(concurrencySnapshot("last"), null)
                restoreConcurrencyObserver()
                assertEquals(Long.MAX_VALUE, store.catalog.state().revision)
                assertNotNull(store.persistence.load(AgentSessionId("last")))

                // A plan computed before the counter reached its maximum must fail in the bridge,
                // using the same typed failure as Kotlin's preflight overflow check.
                val failure = assertFailsWith<SessionCatalogException> {
                    database.executeCatalog("atomicWrite", wireObject {
                        put("expectedState", high)
                        put("state", JsonObject(high + ("revision" to JsonPrimitive((Long.MAX_VALUE - 1).toString()))))
                        put("rebaseRevision", true)
                        put("puts", JsonArray(listOf(putRecord(WEB_SESSION_STORE, "must-not-exist", JsonPrimitive("payload")))))
                    })
                }
                assertEquals(SessionCatalogFailure.COUNTER_EXHAUSTED, failure.failure)
                assertFalse(store.catalog.exists(AgentSessionId("must-not-exist")))
                assertEquals(Long.MAX_VALUE, store.catalog.state().revision)
            } finally {
                restoreConcurrencyObserver()
                store.close()
            }
        }
    }

    @Test
    fun canonicalCommitRechecksMissingOrganizationAndCollectionBeforeInstallingProjection() = runTest {
        for (collection in listOf(false, true)) {
            withConcurrencyDatabase { name ->
                val store = createMagratheaWebStore(MagratheaWebStoreConfiguration(name))
                try {
                    val snapshot = concurrencySnapshot("target")
                    val identity = store.catalog.prepareNewSession(
                        snapshot.sessionId,
                        "life",
                        if (collection) SessionOrganization(collectionId = "folder") else SessionOrganization(),
                    )
                    val concurrentPut = if (collection) {
                        putRecord(
                            CATALOG_COLLECTIONS,
                            "folder",
                            SessionCollection("folder", "Folder", "folder", "folder").wire(DefaultSessionCatalogProjector()),
                        )
                    } else {
                        putRecord(
                            CATALOG_ORGANIZATION,
                            "target",
                            JsonObject(SessionOrganization(title = "Concurrent title").wire() +
                                ("incarnation" to JsonPrimitive(identity.incarnation))),
                        )
                    }
                    injectControlChurn(name, once = true, puts = JsonArray(listOf(concurrentPut)).toString())
                    store.persistence.commit(snapshot, null)
                    assertEquals(2, concurrencyTransactionCount())
                    restoreConcurrencyObserver()
                    val header = assertNotNull(store.catalog.sessionHeader(snapshot.sessionId))
                    if (collection) assertEquals("folder", header.collectionId)
                    else assertEquals("Concurrent title", header.label)
                } finally {
                    restoreConcurrencyObserver()
                    store.close()
                }
            }
        }
    }

    @Test
    fun rebasedPlansRejectChangedTargetMetadataAndGeneration() = runTest {
        withConcurrencyDatabase { name ->
            val first = createMagratheaWebStore(MagratheaWebStoreConfiguration(name))
            val second = createMagratheaWebStore(MagratheaWebStoreConfiguration(name))
            val database = IndexedDbRecordDatabase(name)
            try {
                val snapshot = concurrencySnapshot("same")
                first.persistence.commit(snapshot, null)
                val source = database.executeCatalog("read", wireObject {
                    put("metadataIds", buildJsonArray { add("same") })
                }).jsonObject
                val state = source.getValue("state").jsonObject
                val stalePlan = wireObject {
                    put("expectedState", state)
                    put("state", WebCatalogControl.from(state).copy(revision = nextCatalogLong(WebCatalogControl.from(state).revision)).wire())
                    put("rebaseRevision", true)
                    put("checks", JsonArray(listOf(wireObject {
                        put("store", WEB_SESSION_STORE)
                        put("key", "same")
                        put("expectedMetadata", source.array("metadata").single())
                    })))
                    put("puts", JsonArray(listOf(putRecord(WEB_SESSION_STORE, "must-not-exist", JsonPrimitive("payload")))))
                }
                second.persistence.commit(snapshot.copy(updatedAtEpochMs = 2), null)
                assertFalse(database.executeCatalog("atomicWrite", stalePlan).jsonPrimitive.boolean)
                assertFalse(first.catalog.exists(AgentSessionId("must-not-exist")))

                val withoutTargetCheck = JsonObject(stalePlan - "checks")
                val current = second.catalog.state()
                second.maintenance.rebuild(current.policy, current.generation)
                assertFalse(database.executeCatalog("atomicWrite", withoutTargetCheck).jsonPrimitive.boolean)
                assertFalse(first.catalog.exists(AgentSessionId("must-not-exist")))
            } finally {
                first.close()
                second.close()
            }
        }
    }
}

private fun concurrencySnapshot(id: String): AgentSessionSnapshot {
    val sessionId = AgentSessionId(id)
    val message = AgentMessage("input", MessageRole.USER, listOf(TextPart("Offline")), createdAtEpochMs = 1)
    val request = AgentRequest(sessionId = sessionId, messages = listOf(message), model = ModelDescriptor("offline", "test"))
    return AgentSessionSnapshot(
        sessionId,
        AgentRunId("$id-run"),
        request,
        AgentStateSnapshot(messages = request.messages),
        updatedAtEpochMs = 1,
    )
}

private suspend fun withConcurrencyDatabase(block: suspend (String) -> Unit) {
    val name = "magrathea-concurrency-${Uuid.random()}"
    try {
        block(name)
    } finally {
        restoreConcurrencyObserver()
        assertEquals("deleted", deleteConcurrencyDatabase(name).await().toString())
    }
}

/** Queue a competing writer while the first readonly transaction still owns its snapshot. */
private fun queueWriterAfterFirstRead(name: String, kind: String): Promise<JsString> = js(
    """
    new Promise((resolve) => {
      const original = IDBDatabase.prototype.transaction;
      globalThis.__magratheaConcurrencyOriginal = original;
      globalThis.__magratheaConcurrencyCount = 0;
      let queued = false;
      IDBDatabase.prototype.transaction = function(stores, mode) {
        const reading = original.apply(this, arguments);
        if (this.name !== name || mode !== "readonly") return reading;
        globalThis.__magratheaConcurrencyCount += 1;
        if (queued) return reading;
        queued = true;
        const writing = original.call(this, kind === "bulk" ? ["sessions"] : ["session_attachment_refs", "catalog_state"], "readwrite");
        if (kind === "bulk") writing.objectStore("sessions").delete("b");
        else {
          writing.objectStore("session_attachment_refs").clear();
          const state = writing.objectStore("catalog_state").get("state");
          state.onsuccess = () => writing.objectStore("catalog_state").put(Object.assign({}, state.result, {
            revision: String(BigInt(state.result.revision) + BigInt(1)), coverage: "PARTIAL"
          }), "state");
        }
        writing.oncomplete = () => resolve("done");
        writing.onabort = () => resolve("failed");
        return reading;
      };
    })
    """,
)

/** The competitor is queued before each production write, after Kotlin computed its plan. */
private fun injectControlChurn(name: String, once: Boolean, puts: String): Unit = js(
    """
    {
      const original = IDBDatabase.prototype.transaction;
      globalThis.__magratheaConcurrencyOriginal = original;
      globalThis.__magratheaConcurrencyCount = 0;
      IDBDatabase.prototype.transaction = function(stores, mode) {
        if (this.name === name && mode === "readwrite") {
          globalThis.__magratheaConcurrencyCount += 1;
          if (!once || globalThis.__magratheaConcurrencyCount === 1) {
            const writing = original.call(this, ["catalog_state", "session_organization", "session_collections"], "readwrite");
            const state = writing.objectStore("catalog_state").get("state");
            state.onsuccess = () => writing.objectStore("catalog_state").put(Object.assign({}, state.result, {
              revision: String(BigInt(state.result.revision) + BigInt(1)),
              sequence: String(BigInt(state.result.sequence) + BigInt(1)),
              processed: String(BigInt(state.result.processed) + BigInt(1))
            }), "state");
            for (const item of JSON.parse(puts)) writing.objectStore(item.store).put(item.value, item.key);
          }
        }
        return original.apply(this, arguments);
      };
    }
    """,
)

private fun concurrencyTransactionCount(): Int = js("globalThis.__magratheaConcurrencyCount")

private fun restoreConcurrencyObserver(): Unit = js(
    """
    {
      if (globalThis.__magratheaConcurrencyOriginal) {
        IDBDatabase.prototype.transaction = globalThis.__magratheaConcurrencyOriginal;
        delete globalThis.__magratheaConcurrencyOriginal;
      }
    }
    """,
)

private fun deleteConcurrencyDatabase(name: String): Promise<JsString> = js(
    """
    new Promise((resolve) => {
      const request = indexedDB.deleteDatabase(name);
      request.onsuccess = () => resolve("deleted");
      request.onerror = () => resolve("failed");
      request.onblocked = () => resolve("blocked");
    })
    """,
)
