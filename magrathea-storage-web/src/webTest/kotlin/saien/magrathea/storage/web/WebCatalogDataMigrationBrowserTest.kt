@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package saien.magrathea.storage.web

import kotlin.js.JsString
import kotlin.js.Promise
import kotlinx.coroutines.await
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import saien.magrathea.catalog.*
import saien.magrathea.core.*
import kotlin.test.*

class WebCatalogDataMigrationBrowserTest {
    @Test fun nameScanResumesPhysicalKeysAcrossReopenAndCompletedPassSurvivesRebuild() = runTest {
        isolated { name ->
            val snapshot = snapshot()
            val checkpoint = AgentCheckpoint(snapshot.sessionId, snapshot.runId, AgentResumeCursor(0, AgentResumePhase.MODEL_PENDING), snapshot.state)
            assertEquals("ok", seedNameMigrationDatabase(name, AgentSessionSnapshotCodec().encode(snapshot), AgentCheckpointCodec().encode(checkpoint)).await().toString())
            assertEquals("ok", invalidNameMigrationKeys(name, true).await().toString())
            var store = open(name)
            try {
                var progress = store.migration.beginAttachmentNameScan("local-metadata", 1)
                val number = store.migration.attachmentNameScanPage(progress, 1)
                assertFalse(number.exhausted)
                assertNull(number.sources.single().identity)
                assertNull(number.issues.single().sessionId)
                assertEquals(SessionCatalogFailure.STALE_MIGRATION, assertFailsWith<SessionCatalogException> {
                    store.migration.finishAttachmentNameScan(progress)
                }.failure)
                progress = store.migration.advanceAttachmentNameScan(progress, number.sources.single(), SessionAttachmentNameScanOutcome.FAILED)
                store.close(); store = open(name)
                assertEquals(progress, store.migration.beginAttachmentNameScan("local-metadata", 1))
                val date = store.migration.attachmentNameScanPage(progress, 1)
                assertNull(date.sources.single().identity)
                assertNotEquals(number.sources.single().cursor, date.sources.single().cursor)
                progress = store.migration.advanceAttachmentNameScan(progress, date.sources.single(), SessionAttachmentNameScanOutcome.FAILED)
                val valid = store.migration.attachmentNameScanPage(progress, 1)
                val patched = store.migration.patchAttachmentNames(valid.entries.single(), listOf(SessionAttachmentName("attachment://photo", "photo.png")))
                progress = store.migration.advanceAttachmentNameScan(progress, valid.sources.single().copy(rowVersion = patched.rowVersion))
                val opaque = store.migration.attachmentNameScanPage(progress, 1)
                assertTrue(opaque.exhausted)
                assertTrue(opaque.entries.isEmpty())
                assertEquals(AgentSessionId("z-opaque"), opaque.sources.single().identity?.sessionId)
                progress = store.migration.advanceAttachmentNameScan(progress, opaque.sources.single(), SessionAttachmentNameScanOutcome.FAILED)
                val completed = store.migration.finishAttachmentNameScan(progress)
                assertEquals(4L, completed.processed)
                assertEquals(3L, completed.failed)
                assertEquals("numeric opaque|date opaque|opaque unchanged bytes", invalidNameMigrationKeys(name, false).await().toString())
                store.close(); store = open(name)
                assertEquals(completed, store.migration.beginAttachmentNameScan("local-metadata", 1))
                val state = store.catalog.state()
                store.maintenance.rebuild(state.policy, state.generation)
                assertEquals(completed, store.migration.beginAttachmentNameScan("local-metadata", 1))
                assertTrue(store.migration.attachmentNameScanPage(completed).sources.isEmpty())
                val restarted = store.migration.restartAttachmentNameScan(completed)
                assertEquals(completed.token.generation + 1, restarted.token.generation)
                assertEquals(SessionCatalogFailure.STALE_MIGRATION, assertFailsWith<SessionCatalogException> {
                    store.migration.attachmentNameScanPage(completed)
                }.failure)
                store.persistence.clear()
                assertEquals(SessionCatalogFailure.STALE_MIGRATION, assertFailsWith<SessionCatalogException> {
                    store.migration.finishAttachmentNameScan(restarted)
                }.failure)
                val empty = store.migration.beginAttachmentNameScan("local-metadata", 1)
                assertNotEquals(restarted.token.lifecycle, empty.token.lifecycle)
                assertTrue(store.migration.finishAttachmentNameScan(empty).complete)
            } finally { store.close() }
        }
    }

    @Test fun nameScanVersionCasAllowsManagedProgressButRejectsSkippingRowsAndReusedIds() = runTest {
        isolated { name ->
            val store = open(name)
            try {
                val a = snapshot("a")
                val b = snapshot("b")
                store.persistence.commit(a, null)
                store.persistence.commit(b, null)
                var progress = store.migration.beginAttachmentNameScan("metadata", 1)
                val sources = store.migration.attachmentNameScanPage(progress).sources
                assertEquals(SessionCatalogFailure.STALE_MIGRATION, assertFailsWith<SessionCatalogException> {
                    store.migration.advanceAttachmentNameScan(progress, sources[1])
                }.failure)
                store.persistence.commit(a, null)
                assertEquals(SessionCatalogFailure.STALE_MIGRATION, assertFailsWith<SessionCatalogException> {
                    store.migration.advanceAttachmentNameScan(progress, sources[0])
                }.failure)
                progress = store.migration.advanceAttachmentNameScan(progress, sources[0], SessionAttachmentNameScanOutcome.SKIPPED_MANAGED)
                assertEquals(0L, progress.failed)
                store.persistence.deleteSession(b.sessionId)
                store.persistence.commit(b, null)
                assertEquals(SessionCatalogFailure.STALE_MIGRATION, assertFailsWith<SessionCatalogException> {
                    store.migration.advanceAttachmentNameScan(progress, sources[1], SessionAttachmentNameScanOutcome.SKIPPED_MANAGED)
                }.failure)
                val fresh = store.migration.attachmentNameScanPage(progress).sources.single()
                store.persistence.deleteSession(b.sessionId)
                progress = store.migration.advanceAttachmentNameScan(progress, fresh)
                assertTrue(store.migration.finishAttachmentNameScan(progress).complete)
            } finally { store.close() }
        }
    }

    @Test fun clearBeforeFirstImportKeepsItsCutoffAcrossReopenWithoutAReceipt() = runTest {
        isolated { name ->
            var store = open(name)
            try {
                assertTrue(store.migration.organizationImportAllowed())
                assertNull(store.migration.organizationImportStatus())
                store.persistence.clear()
                assertFalse(store.migration.organizationImportAllowed())
                assertNull(store.migration.organizationImportStatus())
                store.close(); store = open(name)
                assertFalse(store.migration.organizationImportAllowed())
                assertNull(store.migration.organizationImportStatus())
                assertEquals(SessionCatalogFailure.STALE_MIGRATION, assertFailsWith<SessionCatalogException> {
                    store.migration.beginOrganizationImport("old-local-storage", "fingerprint")
                }.failure)
                store.catalog.createCollection(SessionCollection("new", "New", "new", "new"))
                assertNotNull(store.catalog.collection("new"))
            } finally { store.close() }
        }
    }

    @Test fun legacyOrganizationAndCanonicalNamesSurviveReopenCommitAndRebuild() = runTest {
        isolated { name ->
            val snapshot = snapshot()
            val checkpoint = AgentCheckpoint(snapshot.sessionId, snapshot.runId, AgentResumeCursor(0, AgentResumePhase.MODEL_PENDING), snapshot.state)
            val fileName = "照片-ملف-é-😀.png"
            val title = "归档 - résumé"
            val original = AgentSessionSnapshotCodec().encode(snapshot)
            val originalCheckpoint = AgentCheckpointCodec().encode(checkpoint)
            assertEquals("ok", seedNameMigrationDatabase(name, original, originalCheckpoint).await().toString())
            var store = open(name)
            try {
                val receipt = store.migration.beginOrganizationImport("host-v1", "source-fingerprint")
                val folder = SessionCollection("folder", "项目 - ملفات", "folder", "folder")
                val first = store.migration.importOrganizationBatch(receipt.token, 0, listOf(folder), emptyList())
                store.close(); store = open(name)
                assertEquals(first, store.migration.organizationImportStatus())
                val assignments = listOf("a-photo", "z-opaque", "deleted").map {
                    LegacySessionOrganization(AgentSessionId(it), SessionOrganization(title, "folder", Long.MAX_VALUE))
                }
                val done = store.migration.importOrganizationBatch(first.token, 1, emptyList(), assignments, true)
                assertEquals(2L, done.imported); assertEquals(1L, done.skipped)
                assertEquals(done, store.migration.importOrganizationBatch(first.token, 1, emptyList(), assignments, true))
                assertEquals(
                    folder,
                    store.catalog.pageCollections(SessionCollectionQuery("ملفات"), limit = 50).entries.single(),
                )
                assertNull(store.catalog.sessionHeader(AgentSessionId("z-opaque")))
                assertEquals(title, store.catalog.organization(SessionIdentity(AgentSessionId("z-opaque"), "legacy"))?.title)
                val page = store.migration.attachmentNameTargets(limit = 1)
                assertFalse(page.exhausted)
                val target = page.entries.single()
                val result = store.migration.patchAttachmentNames(target, listOf(SessionAttachmentName("attachment://photo", fileName)))
                assertEquals(target.rowVersion + 1, result.rowVersion)
                val backup = assertNotNull(store.migration.attachmentNameBackup(target.identity))
                assertEquals(original, backup.snapshotPayload); assertEquals(originalCheckpoint, backup.checkpointPayload)
                var loaded = assertNotNull(store.persistence.load(snapshot.sessionId))
                assertEquals(fileName, loaded.checkpoint?.state?.messages?.single()?.parts?.filterIsInstance<AttachmentPart>()?.single()?.fileName)
                store.persistence.commit(loaded.snapshot, loaded.checkpoint)
                val state = store.catalog.state()
                store.maintenance.rebuild(state.policy, state.generation)
                repeat(10) { if (!store.maintenance.status().complete) store.maintenance.runBatch(1) }
                assertTrue(store.maintenance.status().complete)
                assertEquals(CatalogCoverage.PARTIAL, store.catalog.state().coverage)
                assertEquals(backup, store.migration.attachmentNameBackup(target.identity))
                assertEquals(SessionCatalogMaintenanceIssueKind.UNREADABLE, store.migration.attachmentNameTargets(page.nextSessionId, 1).issues.single().kind)
                store.close(); store = open(name)
                assertEquals(backup, store.migration.attachmentNameBackup(target.identity))
                assertEquals(
                    listOf(snapshot.sessionId),
                    store.catalog.pageSessions(SessionCatalogQuery(search = "RÉSUMÉ"), limit = 50)
                        .entries.map { it.identity.sessionId },
                )
                loaded = assertNotNull(store.persistence.load(snapshot.sessionId))
                assertEquals(fileName, loaded.snapshot.request.messages.single().parts.filterIsInstance<AttachmentPart>().single().fileName)
                val withoutAttachments = loaded.snapshot.copy(request = loaded.snapshot.request.copy(messages = emptyList()),
                    state = loaded.snapshot.state.copy(messages = emptyList()))
                store.persistence.commit(withoutAttachments, null)
                val rebuilt = store.catalog.state()
                store.maintenance.rebuild(rebuilt.policy, rebuilt.generation)
                repeat(10) { if (!store.maintenance.status().complete) store.maintenance.runBatch(1) }
                assertEquals(setOf("attachment://photo"), store.catalog.referencedUris(setOf("attachment://photo")).referencedUris)
                assertEquals(SessionCatalogFailure.STALE_MIGRATION, assertFailsWith<SessionCatalogException> {
                    store.migration.patchAttachmentNames(target, listOf(SessionAttachmentName("attachment://photo", "stale.png")))
                }.failure)
                store.migration.restoreAttachmentNameBackup(assertNotNull(store.migration.attachmentNameTarget(snapshot.sessionId)))
                assertEquals(snapshot, store.persistence.load(snapshot.sessionId)?.snapshot)
                store.persistence.clear()
                assertEquals(done, store.migration.organizationImportStatus())
                assertEquals(done, store.migration.beginOrganizationImport("host-v1", "source-fingerprint"))
            } finally { store.close() }
        }
    }

    @Test fun importConflictIsAtomicAndClearRejectsAnUnfinishedSource() = runTest {
        isolated { name ->
            val store = open(name)
            try {
                val source = store.migration.beginOrganizationImport("host", "hash")
                store.catalog.createCollection(SessionCollection("exists", "Existing", "a", "same"))
                assertEquals(SessionCatalogFailure.COLLECTION_CONFLICT, assertFailsWith<SessionCatalogException> {
                    store.migration.importOrganizationBatch(source.token, 0,
                        listOf(SessionCollection("new", "New", "b", "new"), SessionCollection("collision", "Collision", "c", "same")), emptyList())
                }.failure)
                assertNull(store.catalog.collection("new"))
                assertEquals(0L, store.migration.organizationImportStatus()?.nextBatch)
                store.persistence.clear()
                assertTrue(store.migration.organizationImportStatus()?.invalidated == true)
                assertEquals(SessionCatalogFailure.STALE_MIGRATION, assertFailsWith<SessionCatalogException> {
                    store.migration.importOrganizationBatch(source.token, 0, emptyList(), emptyList(), true)
                }.failure)
            } finally { store.close() }
        }
    }

    private fun open(name: String) = createMagratheaWebStore(MagratheaWebStoreConfiguration(name), projector = NameProjector())
    private suspend fun isolated(block: suspend (String) -> Unit) {
        val name = "catalog-name-migration-${SystemIdGenerator.nextId()}"
        try { block(name) } finally { assertEquals("ok", deleteNameMigrationDatabase(name).await().toString()) }
    }
    private fun snapshot(name: String = "a-photo"): AgentSessionSnapshot {
        val id = AgentSessionId(name)
        val message = AgentMessage("input", MessageRole.USER, listOf(AttachmentPart("attachment://photo", "image/png")), createdAtEpochMs = 1)
        return AgentSessionSnapshot(id, AgentRunId("run"), AgentRequest(sessionId = id, messages = listOf(message), model = ModelDescriptor("provider", "model", "Model")),
            AgentStateSnapshot(messages = listOf(message)), updatedAtEpochMs = 123)
    }
    private class NameProjector : SessionCatalogProjector {
        override val policy = SessionCatalogPolicy("web-name-migration-test", 1)
        override fun normalizeSearch(text: String) = text.lowercase()
        override fun project(snapshot: AgentSessionSnapshot): SessionCatalogProjection {
            val label = snapshot.state.messages.flatMap { it.parts }.filterIsInstance<AttachmentPart>().firstOrNull()?.fileName ?: "unnamed"
            return SessionCatalogProjection(label, activityAtEpochMs = snapshot.updatedAtEpochMs, normalizedFallbackLabel = label.lowercase())
        }
    }
}

private fun invalidNameMigrationKeys(name: String, seed: Boolean): Promise<JsString> = js("""
  new Promise((resolve) => {
    const request = indexedDB.open(name);
    request.onerror = () => resolve("error");
    request.onsuccess = () => {
      const database = request.result;
      const transaction = database.transaction(["sessions"], seed ? "readwrite" : "readonly");
      const store = transaction.objectStore("sessions");
      let result = "ok";
      if (seed) {
        store.put("numeric opaque", 7);
        store.put("date opaque", new Date(123));
      } else {
        const values = [];
        [7, new Date(123), "z-opaque"].forEach((key, index) => {
          store.get(key).onsuccess = (event) => { values[index] = event.target.result; result = values.join("|"); };
        });
      }
      transaction.oncomplete = () => { database.close(); resolve(result); };
      transaction.onabort = () => { database.close(); resolve("error"); };
    };
  })
""")

private fun seedNameMigrationDatabase(name: String, snapshot: String, checkpoint: String): Promise<JsString> = js("""
  new Promise((resolve) => {
    const request = indexedDB.open(name, 1);
    request.onupgradeneeded = () => { request.result.createObjectStore("sessions"); request.result.createObjectStore("checkpoints"); };
    request.onerror = () => resolve("error");
    request.onsuccess = () => {
      const database = request.result;
      const transaction = database.transaction(["sessions", "checkpoints"], "readwrite");
      transaction.objectStore("sessions").put(snapshot, "a-photo");
      transaction.objectStore("sessions").put("opaque unchanged bytes", "z-opaque");
      transaction.objectStore("checkpoints").put(checkpoint, "a-photo");
      transaction.oncomplete = () => { database.close(); resolve("ok"); };
      transaction.onabort = () => { database.close(); resolve("error"); };
    };
  })
""")
private fun deleteNameMigrationDatabase(name: String): Promise<JsString> = js("""
  new Promise((resolve) => {
    const request = indexedDB.deleteDatabase(name);
    request.onsuccess = () => resolve("ok"); request.onerror = () => resolve("error"); request.onblocked = () => resolve("blocked");
  })
""")
