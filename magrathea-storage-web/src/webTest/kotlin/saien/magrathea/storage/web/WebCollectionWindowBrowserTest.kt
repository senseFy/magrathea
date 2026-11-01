@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class, kotlin.uuid.ExperimentalUuidApi::class)
@file:Suppress("REDUNDANT_CALL_OF_CONVERSION_METHOD")

package saien.magrathea.storage.web

import kotlin.js.JsString
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid
import kotlinx.coroutines.await
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import saien.magrathea.catalog.CatalogPageDirection
import saien.magrathea.catalog.DefaultSessionCatalogProjector
import saien.magrathea.catalog.SessionCatalogPolicy
import saien.magrathea.catalog.SessionCatalogProjector
import saien.magrathea.catalog.SessionCollection
import saien.magrathea.catalog.SessionCollectionQuery
import saien.magrathea.catalog.SessionOrganizationMutation
import saien.magrathea.core.AgentMessage
import saien.magrathea.core.AgentRequest
import saien.magrathea.core.AgentRunId
import saien.magrathea.core.AgentSessionId
import saien.magrathea.core.AgentSessionSnapshot
import saien.magrathea.core.AgentSessionSnapshotCodec
import saien.magrathea.core.AgentStateSnapshot
import saien.magrathea.core.MessageRole
import saien.magrathea.core.ModelDescriptor
import saien.magrathea.core.TextPart

class WebCollectionWindowBrowserTest {
    @Test
    fun versionTwoUpgradePreservesEveryStoreAndFencesOldWriters() = runTest {
        withCollectionDatabase { name ->
            val projector = DefaultSessionCatalogProjector()
            val oldControl = JsonObject(
                WebCatalogControl(projector.policy, projector.activityMode, revision = 41).wire() - "collectionsRevision",
            )
            val snapshot = collectionSnapshot()
            val payload = AgentSessionSnapshotCodec().encode(snapshot)
            assertEquals("seeded", seedCollectionV2(name, oldControl.toString(), payload).await().toString())
            val store = openCollectionStore(name)
            try {
                val state = store.catalog.state()
                assertEquals(41L, state.revision)
                assertEquals(0L, state.collectionsRevision)
                assertEquals(snapshot, assertNotNull(store.persistence.load(snapshot.sessionId)).snapshot)
                assertEquals("preserved", inspectCollectionUpgrade(name, oldControl.toString(), payload).await().toString())
                assertEquals("unsupported_database_version", attemptVersionTwoWriter(name).await().toString())
            } finally {
                store.close()
            }
        }
    }

    @Test
    fun collectionTokenChangesOnlyForCollectionsAndGenerationBoundariesAcrossHandles() = runTest {
        withCollectionDatabase { name ->
            val first = openCollectionStore(name)
            var second = openCollectionStore(name)
            try {
                val folder = collection("a")
                first.catalog.createCollection(folder)
                val created = second.catalog.state()
                assertEquals(created.revision, created.collectionsRevision)
                second.catalog.renameCollection(folder.id, folder.name, folder.nameSortKey, folder.nameUniqueKey)
                second.catalog.deleteCollection("absent")
                assertEquals(created, first.catalog.state())

                val snapshot = collectionSnapshot()
                first.persistence.commit(snapshot, null)
                val identity = assertNotNull(first.catalog.sessionIdentity(snapshot.sessionId))
                first.catalog.updateOrganization(identity, SessionOrganizationMutation.Collection(folder.id))
                first.catalog.updateOrganization(identity, SessionOrganizationMutation.Pin(4))
                first.catalog.setTitleIfAbsent(identity, "Title")
                first.persistence.deleteSession(snapshot.sessionId)
                assertEquals(created.collectionsRevision, second.catalog.state().collectionsRevision)

                second.catalog.renameCollection(folder.id, "Renamed", "z", "renamed")
                val renamed = first.catalog.state()
                assertEquals(renamed.revision, renamed.collectionsRevision)
                assertTrue(renamed.collectionsRevision > created.collectionsRevision)
                second.close()
                second = openCollectionStore(name)
                assertEquals(renamed.collectionsRevision, second.catalog.state().collectionsRevision)
                second.catalog.deleteCollection(folder.id)
                val deleted = first.catalog.state()
                assertEquals(deleted.revision, deleted.collectionsRevision)
                assertTrue(deleted.collectionsRevision > renamed.collectionsRevision)

                first.persistence.clear()
                val cleared = second.catalog.state()
                assertEquals(cleared.revision, cleared.collectionsRevision)
                assertTrue(cleared.collectionsRevision > deleted.collectionsRevision)
                val rebuilt = first.maintenance.rebuild(cleared.policy, cleared.generation).state
                assertEquals(rebuilt.revision, rebuilt.collectionsRevision)
                assertTrue(rebuilt.collectionsRevision > cleared.collectionsRevision)
            } finally {
                first.close()
                second.close()
            }
        }
    }

    @Test
    fun importsAndChangedSearchNormalizationAdvanceTheTokenWithoutReceiptOnlyNoise() = runTest {
        withCollectionDatabase { name ->
            var store = openCollectionStore(name)
            try {
                val receipt = store.migration.beginOrganizationImport("collections", "source")
                val initial = store.catalog.state().collectionsRevision
                val folder = collection("a")
                store.migration.importOrganizationBatch(receipt.token, 0, listOf(folder), emptyList())
                val added = store.catalog.state()
                assertEquals(added.revision, added.collectionsRevision)
                assertTrue(added.collectionsRevision > initial)
                store.migration.importOrganizationBatch(receipt.token, 1, listOf(folder), emptyList(), true)
                assertEquals(added.collectionsRevision, store.catalog.state().collectionsRevision)
                store.migration.importOrganizationBatch(receipt.token, 1, listOf(folder), emptyList(), true)
                assertEquals(added.collectionsRevision, store.catalog.state().collectionsRevision)

                val before = store.catalog.state()
                store.close()
                val original = DefaultSessionCatalogProjector()
                val updated = object : SessionCatalogProjector by original {
                    override val policy = SessionCatalogPolicy(original.policy.id, original.policy.version + 1)
                    override fun normalizeSearch(text: String) = "normalized:" + original.normalizeSearch(text)
                }
                store = openCollectionStore(name, updated)
                val rebuilt = store.maintenance.rebuild(before.policy, before.generation).state
                val normalized = store.maintenance.runBatch(1).progress.state
                assertEquals(normalized.revision, normalized.collectionsRevision)
                assertTrue(normalized.collectionsRevision > rebuilt.collectionsRevision)
                while (!store.maintenance.status().complete) store.maintenance.runBatch(1)
                assertEquals(normalized.collectionsRevision, store.catalog.state().collectionsRevision)
                assertEquals(listOf(folder), store.catalog.windowAroundCollection(folder.id, limit = 1).entries)

                val unchanged = store.catalog.state()
                val samePolicyRebuild = store.maintenance.rebuild(unchanged.policy, unchanged.generation).state
                while (!store.maintenance.status().complete) store.maintenance.runBatch(1)
                assertEquals(samePolicyRebuild.collectionsRevision, store.catalog.state().collectionsRevision)
            } finally {
                store.close()
            }
        }
    }

    @Test
    fun collectionWindowsRetainBothContinuationsAndBoundSparseSearch() = runTest {
        withCollectionDatabase { name ->
            val store = openCollectionStore(name)
            try {
                val rows = (0..260).map { index ->
                    val id = index.toString().padStart(3, '0')
                    collection(id, if (index in setOf(0, 130, 260)) "Needle $id" else "Ordinary $id")
                }
                val source = store.migration.beginOrganizationImport("window", "seed")
                rows.chunked(50).forEachIndexed { index, batch ->
                    store.migration.importOrganizationBatch(source.token, index.toLong(), batch, emptyList(), index == 5)
                }
                val one = store.catalog.windowAroundCollection("130", limit = 1)
                assertEquals(listOf("130"), one.entries.map { it.id })
                assertNotNull(one.previousCursor)
                assertNotNull(one.nextCursor)
                assertFalse(one.exhausted)
                assertEquals("129", store.catalog.pageCollections(cursor = one.previousCursor, direction = CatalogPageDirection.BEFORE, limit = 1).entries.single().id)
                assertEquals("131", store.catalog.pageCollections(cursor = one.nextCursor, limit = 1).entries.single().id)

                val query = SessionCollectionQuery("NEEDLE")
                val window = store.catalog.windowAroundCollection("130", query, limit = 3)
                assertEquals(listOf("130"), window.entries.map { it.id })
                assertFalse(window.exhausted)
                assertEquals("000", store.catalog.pageCollections(query, assertNotNull(window.previousCursor), CatalogPageDirection.BEFORE, 3).entries.single().id)
                assertEquals("260", store.catalog.pageCollections(query, assertNotNull(window.nextCursor), limit = 3).entries.single().id)
                for (id in listOf("missing", "129")) {
                    val absent = store.catalog.windowAroundCollection(id, query, limit = 3)
                    assertTrue(absent.entries.isEmpty())
                    assertTrue(absent.exhausted)
                    assertNull(absent.previousCursor)
                    assertNull(absent.nextCursor)
                }
            } finally {
                store.close()
            }
        }
    }

    @Test
    fun collectionWindowIgnoresContinuousSessionRevisionChangesButRebasesARenamedAnchor() = runTest {
        for (rename in listOf(false, true)) {
            withCollectionDatabase { name ->
                val store = openCollectionStore(name)
                try {
                    for (id in listOf("a", "b", "c")) store.catalog.createCollection(collection(id))
                    val before = store.catalog.state()
                    injectCollectionReadChanges(name, rename)
                    val window = store.catalog.windowAroundCollection("b", limit = 3)
                    restoreCollectionReadObserver()
                    if (rename) {
                        assertEquals(listOf("c", "b"), window.entries.map { it.id })
                        assertEquals("Renamed b", window.entries.last().name)
                        assertTrue(window.state.collectionsRevision > before.collectionsRevision)
                    } else {
                        assertEquals(listOf("a", "b", "c"), window.entries.map { it.id })
                        assertEquals(3, collectionReadCount())
                        assertEquals(before.collectionsRevision, window.state.collectionsRevision)
                    }
                } finally {
                    restoreCollectionReadObserver()
                    store.close()
                }
            }
        }
    }
}

private fun openCollectionStore(
    name: String,
    projector: SessionCatalogProjector = DefaultSessionCatalogProjector(),
) = createMagratheaWebStore(MagratheaWebStoreConfiguration(name), projector = projector)

private fun collection(id: String, name: String = "Collection $id") = SessionCollection(id, name, id, id)

private fun collectionSnapshot(): AgentSessionSnapshot {
    val id = AgentSessionId("session")
    val message = AgentMessage("input", MessageRole.USER, listOf(TextPart("Body kept opaque")), 1)
    val request = AgentRequest(sessionId = id, messages = listOf(message), model = ModelDescriptor("offline", "model", "Model"))
    return AgentSessionSnapshot(id, AgentRunId("run"), request, AgentStateSnapshot(messages = request.messages), updatedAtEpochMs = 1)
}

private suspend fun withCollectionDatabase(block: suspend (String) -> Unit) {
    val name = "magrathea-collection-${Uuid.random()}"
    try {
        block(name)
    } finally {
        restoreCollectionReadObserver()
        assertEquals("complete", drainCollectionReadWrites().await().toString())
        assertEquals("deleted", deleteCollectionDatabase(name).await().toString())
    }
}

private fun deleteCollectionDatabase(name: String): Promise<JsString> = js("""
    new Promise((resolve) => {
      const request = indexedDB.deleteDatabase(name);
      request.onsuccess = () => resolve("deleted");
      request.onerror = () => resolve("error");
      request.onblocked = () => resolve("blocked");
    })
""")

private fun seedCollectionV2(name: String, stateJson: String, payload: String): Promise<JsString> = js("""
    new Promise((resolve) => {
      const request = indexedDB.open(name, 2);
      request.onupgradeneeded = () => {
        const db = request.result;
        for (const store of ["sessions", "checkpoints", "session_catalog", "session_search", "session_attachment_refs", "session_collections", "session_organization", "catalog_state"]) db.createObjectStore(store);
        const index = (store, key, path, unique) => request.transaction.objectStore(store).createIndex(key, path, {unique: unique === true});
        index("sessions", "metadata", "metadataKey", true); index("sessions", "recovery", "recoveryKey");
        index("session_catalog", "global_pin", ["pinBucket", "pinRank", "activityRank", "idSortKey"]);
        index("session_catalog", "global_activity", ["pinBucket", "activityRank", "idSortKey"]);
        index("session_catalog", "collection_pin", ["collectionKey", "pinBucket", "pinRank", "activityRank", "idSortKey"]);
        index("session_catalog", "collection_activity", ["collectionKey", "pinBucket", "activityRank", "idSortKey"]);
        index("session_catalog", "collection", "collectionKey");
        index("session_collections", "name", ["nameSortKey", "idSortKey"]);
        index("session_collections", "uniqueName", "nameUniqueKey", true);
        index("session_organization", "collection", "collectionId");
        index("session_attachment_refs", "session", "sessionId"); index("session_attachment_refs", "uri", "uri");
      };
      request.onsuccess = () => {
        const db = request.result;
        const tx = db.transaction(Array.from(db.objectStoreNames), "readwrite");
        for (const store of Array.from(db.objectStoreNames)) tx.objectStore(store).put("opaque-" + store, "sentinel");
        tx.objectStore("sessions").put(payload, "session");
        tx.objectStore("catalog_state").put(JSON.parse(stateJson), "state");
        db.onversionchange = () => { globalThis.__collectionOldWriterClosed = true; db.close(); };
        tx.oncomplete = () => resolve("seeded");
        tx.onabort = () => { db.close(); resolve("failed"); };
      };
      request.onerror = () => resolve("failed");
    })
""")

private fun inspectCollectionUpgrade(name: String, stateJson: String, payload: String): Promise<JsString> = js("""
    new Promise((resolve) => {
      const request = indexedDB.open(name, 3);
      request.onerror = () => resolve("failed");
      request.onsuccess = () => {
        const db = request.result;
        let valid = db.version === 3 && db.objectStoreNames.length === 8 && globalThis.__collectionOldWriterClosed === true;
        const tx = db.transaction(Array.from(db.objectStoreNames), "readonly");
        for (const store of Array.from(db.objectStoreNames)) {
          const read = tx.objectStore(store).get("sentinel");
          read.onsuccess = () => { valid = valid && read.result === "opaque-" + store; };
        }
        const state = tx.objectStore("catalog_state").get("state");
        state.onsuccess = () => {
          const old = JSON.parse(stateJson); const actual = Object.assign({}, state.result);
          valid = valid && actual.collectionsRevision === "0"; delete actual.collectionsRevision;
          valid = valid && JSON.stringify(actual) === JSON.stringify(old);
        };
        const body = tx.objectStore("sessions").get("session");
        body.onsuccess = () => { valid = valid && body.result === payload; };
        tx.oncomplete = () => { db.close(); resolve(valid ? "preserved" : "changed"); };
        tx.onabort = () => { db.close(); resolve("failed"); };
      };
    })
""")

private fun attemptVersionTwoWriter(name: String): Promise<JsString> = js("""
    new Promise((resolve) => {
      const request = indexedDB.open(name, 2);
      request.onerror = () => resolve(request.error.name === "VersionError" ? "unsupported_database_version" : "failed");
      request.onsuccess = () => { request.result.close(); resolve("old writer opened"); };
    })
""")

private fun injectCollectionReadChanges(name: String, rename: Boolean): Unit = js("""
    {
      const original = IDBDatabase.prototype.transaction;
      globalThis.__collectionOriginalTransaction = original;
      globalThis.__collectionReadCount = 0;
      globalThis.__collectionPendingWrites = [];
      IDBDatabase.prototype.transaction = function(stores, mode) {
        const reading = original.apply(this, arguments);
        if (this.name !== name || mode !== "readonly") return reading;
        const count = ++globalThis.__collectionReadCount;
        const writing = original.call(this, ["catalog_state", "session_collections"], "readwrite");
        globalThis.__collectionPendingWrites.push(new Promise((resolve) => {
          writing.addEventListener("complete", () => resolve(true));
          writing.addEventListener("abort", () => resolve(false));
        }));
        const state = writing.objectStore("catalog_state").get("state");
        state.onsuccess = () => {
          const next = Object.assign({}, state.result, {revision: String(BigInt(state.result.revision) + BigInt(1))});
          if (rename && count === 1) next.collectionsRevision = next.revision;
          writing.objectStore("catalog_state").put(next, "state");
        };
        if (rename && count === 1) {
          const row = writing.objectStore("session_collections").get("b");
          row.onsuccess = () => writing.objectStore("session_collections").put(Object.assign({}, row.result, {
            name: "Renamed b", nameSortKey: "z", normalizedName: "renamed b"
          }), "b");
        }
        return reading;
      };
    }
""")

private fun collectionReadCount(): Int = js("globalThis.__collectionReadCount")

private fun drainCollectionReadWrites(): Promise<JsString> = js("""
    Promise.all(globalThis.__collectionPendingWrites || []).then((results) => {
      delete globalThis.__collectionPendingWrites;
      return results.every(Boolean) ? "complete" : "failed";
    })
""")

private fun restoreCollectionReadObserver(): Unit = js("""
    {
      if (globalThis.__collectionOriginalTransaction) {
        IDBDatabase.prototype.transaction = globalThis.__collectionOriginalTransaction;
        delete globalThis.__collectionOriginalTransaction;
      }
    }
""")
