@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
@file:Suppress("REDUNDANT_CALL_OF_CONVERSION_METHOD")

package saien.magrathea.storage.web

import kotlin.js.JsString
import kotlin.js.Promise
import kotlinx.coroutines.await
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import saien.magrathea.catalog.*
import saien.magrathea.core.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Acceptance tests use actual browser IndexedDB, including its versionchange and abort events. */
class WebCatalogBrowserContractTest {
    @Test
    fun preparedOrderAboveJavaScriptIntegerRangeSurvivesReverseCommitReopenAndRegeneration() = runTest {
        isolated { name ->
            val projector = BrowserProjector(mode = SessionActivityMode.PREPARED)
            var store = open(name, projector)
            try {
                store.catalog.createCollection(SessionCollection("work", "Work", "work", "work"))
                val high = 9_007_199_254_740_992L
                seedCounters(name, high)
                val first = snapshot("a", "u1", high + 17)
                val second = snapshot("b", "u2", high + 17)
                val a = store.catalog.prepareNewSession(first.sessionId, "a-life", SessionOrganization("My title", "work"))
                val b = store.catalog.prepareNewSession(second.sessionId, "b-life")
                val earlier = store.catalog.prepareActivity(a, "u1")
                val later = store.catalog.prepareActivity(b, "u2")
                assertEquals(high + 1, earlier.sequence)
                assertEquals(high + 2, later.sequence)
                store.persistence.commit(second, null)
                store.persistence.commit(first, checkpoint(first, 3))
                assertEquals(listOf("b", "a"), ids(store.catalog.pageSessions(SessionCatalogQuery(), limit = 10)))
                val original = assertNotNull(store.catalog.sessionHeader(first.sessionId))
                assertEquals(high + 17, original.activityAtEpochMs)
                assertEquals(SessionActivityOrder.Accepted(high + 1), original.activity.order)
                assertEquals("My title", original.label)
                assertEquals("work", original.collectionId)
                store.close()

                store = open(name, projector)
                assertEquals(original, store.catalog.sessionHeader(first.sessionId))
                assertEquals(3, store.persistence.load(first.sessionId)?.checkpoint?.turn)
                val next = snapshot("a", "u3", high + 17)
                val newest = store.catalog.prepareActivity(a, "u3")
                store.persistence.commit(next, null)
                store.catalog.preparePreservedActivity(a, "u1")
                store.persistence.commit(first.copy(updatedAtEpochMs = high + 99), null)
                val regenerated = assertNotNull(store.catalog.sessionHeader(first.sessionId))
                assertEquals("u1", regenerated.activity.observedIdentity)
                assertEquals("u3", regenerated.activity.lastAcceptedIdentity)
                assertEquals(SessionActivityOrder.Accepted(newest.sequence), regenerated.activity.order)
                assertEquals(newest, store.catalog.prepareActivity(a, "u3"))
                store.persistence.commit(next, null)
                assertEquals(listOf("a", "b"), ids(store.catalog.pageSessions(SessionCatalogQuery(), limit = 10)))
                assertEquals(regenerated.activity.order, store.catalog.sessionHeader(first.sessionId)?.activity?.order)
            } finally { store.close() }
        }
    }

    @Test
    fun keysetPagesSearchAndAnchorRespectPortableOrderAndCollectionRemoval() = runTest {
        isolated { name ->
            val store = open(name, BrowserProjector())
            try {
                store.catalog.createCollection(SessionCollection("folder", "Folder", "folder", "folder"))
                val base = 9_007_199_254_740_992L
                val sessions = listOf(
                    snapshot("a", "a", base), snapshot("b", "b", base),
                    snapshot("c", "c", base + 1, "x".repeat(600) + " needle_%[tail]"),
                    snapshot("d", "d", base - 1), snapshot("😀", "emoji", base), snapshot("\uE000", "private", base),
                )
                for (session in sessions) store.persistence.commit(session, null)
                val identities = sessions.associate { it.sessionId.value to assertNotNull(store.catalog.sessionIdentity(it.sessionId)) }
                store.catalog.updateOrganization(identities.getValue("d"), SessionOrganizationMutation.Pin(Long.MAX_VALUE))
                store.catalog.updateOrganization(identities.getValue("a"), SessionOrganizationMutation.Pin(Long.MIN_VALUE))
                for (id in listOf("c", "😀")) store.catalog.updateOrganization(identities.getValue(id), SessionOrganizationMutation.Collection("folder"))
                for (direction in CatalogPageDirection.entries) {
                    assertEquals(listOf("d", "a", "c", "b", "😀", "\uE000"), collect(store, SessionCatalogQuery(), direction))
                    assertEquals(listOf("c", "b", "😀", "\uE000"), collect(store,
                        SessionCatalogQuery(pin = SessionPinFilter.UNPINNED, sort = SessionCatalogSort.ACTIVITY_DESC), direction))
                    assertEquals(listOf("c", "😀"), collect(store, SessionCatalogQuery(scope = SessionCatalogScope.Collection("folder")), direction))
                }
                store.catalog.updateOrganization(identities.getValue("b"), SessionOrganizationMutation.Title("Visible title"))
                assertEquals(listOf("c"), collect(store, SessionCatalogQuery(search = "needle_%[tail]")))
                assertTrue(collect(store, SessionCatalogQuery(search = "needleZZ[tail]")).isEmpty())
                assertEquals(listOf("b"), collect(store, SessionCatalogQuery(search = "VISIBLE TITLE")))
                assertTrue(collect(store, SessionCatalogQuery(search = "Fallback b")).isEmpty())
                assertFalse(assertNotNull(store.catalog.sessionHeader(AgentSessionId("c"))).previewExcerpt.contains("needle"))
                val anchor = store.catalog.windowAround(AgentSessionId("b"), SessionCatalogQuery(), limit = 3)
                assertTrue(anchor.entries.size <= 3)
                assertTrue("b" in ids(anchor))
                assertTrue(store.catalog.windowAround(AgentSessionId("absent"), SessionCatalogQuery(), limit = 3).entries.isEmpty())

                store.catalog.updateOrganization(identities.getValue("c"), SessionOrganizationMutation.Title("Keep title"))
                store.catalog.updateOrganization(identities.getValue("c"), SessionOrganizationMutation.Pin(7))
                store.catalog.deleteCollection("folder")
                assertEquals(SessionOrganization("Keep title", null, 7), store.catalog.organization(identities.getValue("c")))
                assertNull(store.catalog.sessionHeader(AgentSessionId("c"))?.collectionId)
                assertTrue(collect(store, SessionCatalogQuery(scope = SessionCatalogScope.Collection("folder"))).isEmpty())
            } finally { store.close() }
        }
    }

    @Test
    fun sparseAndEmptySearchContinueAcrossBoundedScansInsteadOfTreatingEmptyPagesAsEnd() = runTest {
        isolated { name ->
            val store = open(name, BrowserProjector())
            try {
                // More than the bridge's fixed scan budget, while each public result page stays small.
                repeat(205) { index ->
                    val id = "s${index.toString().padStart(3, '0')}"
                    store.persistence.commit(snapshot(id, id, 0, if (index == 204) "rare-tail-needle" else "ordinary"), null)
                }
                val query = SessionCatalogQuery(search = "rare-tail-needle")
                val first = store.catalog.pageSessions(query, limit = 5)
                assertTrue(first.entries.isEmpty())
                assertFalse(first.exhausted)
                assertNotNull(first.nextCursor)
                assertEquals(listOf("s204"), collect(store, query))
                for (direction in CatalogPageDirection.entries) {
                    var cursor: SessionCatalogCursor? = null
                    var pages = 0
                    do {
                        val page = store.catalog.pageSessions(SessionCatalogQuery(search = "no-such-content"), cursor, direction, 5)
                        assertTrue(page.entries.isEmpty())
                        cursor = if (direction == CatalogPageDirection.AFTER) page.nextCursor else page.previousCursor
                        if (cursor != null) assertFalse(page.exhausted)
                        check(++pages < 10) { "Empty search did not terminate" }
                    } while (cursor != null)
                    assertTrue(pages >= 2)
                }
            } finally { store.close() }
        }
    }

    @Test
    fun legacyUpgradeKeepsEightStoresAndOpaqueBytesWhileBackfillResumesInBoundedSteps() = runTest {
        isolated { name ->
            val a = snapshot("a", "u-a", 1)
            val z = snapshot("z", "u-z", 2)
            val codec = AgentSessionSnapshotCodec()
            val opaque = "opaque unknown bytes 中文"
            assertEquals("ok", createLegacy(name, buildJsonObject {
                put("a", codec.encode(a)); put("m-corrupt", opaque); put("n-null", JsonNull); put("z", codec.encode(z))
            }.toString(), AgentCheckpointCodec().encode(checkpoint(a, 2))).await().toString())
            var store = open(name, BrowserProjector())
            try {
                assertEquals("legacy", store.catalog.sessionIdentity(a.sessionId)?.incarnation)
                assertNull(store.catalog.sessionHeader(a.sessionId))
                assertEquals(CatalogCoverage.INDEXING, store.catalog.state().coverage)
                val before = inspect(name)
                assertEquals(2, before.getValue("version").jsonPrimitive.int)
                assertEquals(8, before.getValue("stores").jsonArray.size)
                assertEquals(codec.encode(a), before.getValue("sessions").jsonObject.getValue("a").jsonPrimitive.content)
                assertEquals(opaque, before.getValue("sessions").jsonObject.getValue("m-corrupt").jsonPrimitive.content)
                val first = store.maintenance.runBatch(3)
                assertEquals(1, first.indexed)
                assertEquals(2, first.issues.count { it.sessionId == null })
                assertEquals(AgentSessionId("a"), first.progress.afterSessionId)
                store.close()
                store = open(name, BrowserProjector())
                assertEquals(first.progress.afterSessionId, store.maintenance.status().afterSessionId)
                val issues = first.issues + finish(store, limit = 1)
                assertEquals(setOf("m-corrupt", "n-null"), issues.mapNotNull { it.sessionId?.value }.toSet())
                assertTrue(issues.all { it.kind == SessionCatalogMaintenanceIssueKind.UNREADABLE })
                assertEquals(6, store.maintenance.status().processed)
                assertEquals(4, store.maintenance.status().failed)
                assertEquals(CatalogCoverage.PARTIAL, store.catalog.state().coverage)
                assertEquals(listOf("z", "a"), collect(store, SessionCatalogQuery()))
                val after = inspect(name).getValue("sessions").jsonObject
                val preserved = after.getValue("m-corrupt")
                assertEquals(opaque, if (preserved is JsonObject) preserved.getValue("payload").jsonPrimitive.content else preserved.jsonPrimitive.content)
                assertEquals(JsonNull, after.getValue("n-null"))
                assertEquals("opaque-number", after.getValue("7").jsonPrimitive.content)
                assertEquals("opaque-date", after.getValue("1970-01-01T00:00:01.234Z").jsonPrimitive.content)
                assertEquals(codec.encode(a), after.getValue("a").jsonObject.getValue("payload").jsonPrimitive.content)
                assertEquals("legacy", store.catalog.sessionIdentity(a.sessionId)?.incarnation)
                assertEquals(2, store.persistence.load(a.sessionId)?.checkpoint?.turn)
                val recovery = store.catalog.pageRecoveryCandidates(limit = 20)
                assertEquals(CatalogCoverage.PARTIAL, recovery.coverage)
                assertTrue(recovery.entries.any { it.sessionId.value == "m-corrupt" && SessionRecoveryReason.UNKNOWN_SOURCE in it.reasons })
                val settled = store.maintenance.status()
                assertEquals(settled, store.maintenance.runBatch(1).progress)
            } finally { store.close() }
        }
    }

    @Test
    fun policySwitchAndClearResumeCollectionNormalizationAndInvalidateOldCursors() = runTest {
        isolated { name ->
            var store = open(name, BrowserProjector(searchPrefix = "old:"))
            try {
                for (id in listOf("a", "b", "c")) store.catalog.createCollection(SessionCollection(id, "Name $id", id, id))
                store.persistence.commit(snapshot("kept", "u", 1), null)
                val before = store.catalog.state()
                val oldCursor = SessionCatalogCursor(SessionCatalogQuery(), before.policy, before.generation, SessionCatalogSortKey(1, "", "", ""))
                store.close()
                val replacement = BrowserProjector(policyVersion = 2, searchPrefix = "new:")
                store = open(name, replacement)
                assertCatalogFailure(SessionCatalogFailure.POLICY_MISMATCH) { store.catalog.state() }
                store.maintenance.rebuild(before.policy, before.generation)
                assertFalse(store.maintenance.runBatch(1).progress.complete)
                store.persistence.clear()
                assertEquals(CatalogCoverage.INDEXING, store.catalog.state().coverage)
                assertCatalogFailure(SessionCatalogFailure.INVALID_CURSOR) { store.catalog.pageSessions(SessionCatalogQuery(), oldCursor, limit = 2) }
                store.close()
                store = open(name, replacement)
                finish(store, limit = 1)
                assertEquals(CatalogCoverage.COMPLETE, store.catalog.state().coverage)
                assertTrue(store.catalog.pageSessions(SessionCatalogQuery(), limit = 10).entries.isEmpty())
                for (id in listOf("a", "b", "c")) {
                    assertEquals(listOf(id), store.catalog.pageCollections(SessionCollectionQuery("Name $id"), limit = 10).entries.map { it.id })
                }
                assertCatalogFailure(SessionCatalogFailure.STALE_MAINTENANCE) { store.maintenance.rebuild(before.policy, before.generation) }
            } finally { store.close() }
        }
    }

    @Test
    fun bridgeFailureAfterQueuedWritesRollsBackEveryStoreAndControlRecord() = runTest {
        isolated { name ->
            val store = open(name, BrowserProjector())
            try {
                val session = snapshot("rollback", "u", 1)
                store.persistence.commit(session, checkpoint(session, 2))
                val expected = inspect(name)
                val state = assertNotNull(rawRead(name)["state"]).jsonObject
                val response = rawOperation(name, "atomicWrite", buildJsonObject {
                    put("expectedState", state)
                    put("state", JsonObject(state + ("revision" to JsonPrimitive("999"))))
                    put("puts", buildJsonArray {
                        add(buildJsonObject { put("store", "sessions"); put("key", "rollback"); put("value", "should rollback") })
                        add(buildJsonObject { put("store", "checkpoints"); put("key", "rollback"); put("value", "should rollback") })
                    })
                    // A synchronous DataError after prior puts have already been enqueued.
                    put("patches", buildJsonArray { add(buildJsonObject {
                        put("store", "session_catalog"); put("key", "rollback"); put("fields", JsonNull)
                    }) })
                })
                assertFalse(response.getValue("ok").jsonPrimitive.boolean)
                assertEquals(expected, inspect(name))
                assertEquals(session, store.persistence.load(session.sessionId)?.snapshot)
                assertEquals(2, store.persistence.load(session.sessionId)?.checkpoint?.turn)
            } finally { store.close() }
        }
    }

    @Test
    fun headlessSnapshotsRecoveryReferencesAndClosedHandleNeedNoChatbot() = runTest {
        isolated { name ->
            val store = createMagratheaWebStore(MagratheaWebStoreConfiguration(name))
            try {
                val original = snapshot("headless", "system", 1)
                val message = AgentMessage(id = "system", role = MessageRole.SYSTEM,
                    parts = listOf(TextPart("tool-only harness"), AttachmentPart("attachment://headless", "image/png")))
                val session = original.copy(request = original.request.copy(messages = listOf(message)),
                    state = original.state.copy(messages = listOf(message), status = AgentStatus.RUNNING))
                store.persistence.commit(session, checkpoint(session, 1))
                assertEquals("headless", store.catalog.sessionHeader(session.sessionId)?.label)
                assertEquals(setOf("attachment://headless"), store.catalog.referencedUris(setOf("attachment://headless", "attachment://absent")).referencedUris)
                val candidate = store.catalog.pageRecoveryCandidates(limit = 10).entries.single()
                assertTrue(SessionRecoveryReason.CHECKPOINT in candidate.reasons)
                assertTrue(SessionRecoveryReason.RECOVERABLE_STATUS in candidate.reasons)
                store.persistence.commit(session.copy(state = session.state.copy(status = AgentStatus.COMPLETED)), null)
                assertTrue(store.catalog.pageRecoveryCandidates(limit = 10).entries.isEmpty())
                store.close()
                assertCatalogFailure(SessionCatalogFailure.CLOSED) { store.catalog.sessionIdentity(session.sessionId) }
                assertCatalogFailure(SessionCatalogFailure.CLOSED) { store.maintenance.status() }
                assertEquals(WebStorageFailure.CLOSED, assertFailsWith<WebStorageException> { store.persistence.load(session.sessionId) }.failure)
            } finally { store.close() }
        }
    }

    private suspend fun collect(store: MagratheaWebStore, query: SessionCatalogQuery,
        direction: CatalogPageDirection = CatalogPageDirection.AFTER): List<String> {
        val entries = mutableListOf<String>()
        var cursor: SessionCatalogCursor? = null
        var pages = 0
        do {
            val page = store.catalog.pageSessions(query, cursor, direction, limit = 2)
            if (direction == CatalogPageDirection.AFTER) entries += ids(page) else entries.addAll(0, ids(page))
            cursor = if (direction == CatalogPageDirection.AFTER) page.nextCursor else page.previousCursor
            check(++pages < 200) { "Catalog cursor did not terminate" }
        } while (cursor != null)
        return entries
    }

    private suspend fun finish(store: MagratheaWebStore, limit: Int): List<SessionCatalogMaintenanceIssue> {
        val issues = mutableListOf<SessionCatalogMaintenanceIssue>()
        repeat(30) {
            if (store.maintenance.status().complete) return issues
            issues += store.maintenance.runBatch(limit).issues
        }
        error("Bounded maintenance did not terminate")
    }

    private suspend fun seedCounters(name: String, sequence: Long) {
        val state = rawRead(name).getValue("state").jsonObject
        val response = rawOperation(name, "atomicWrite", buildJsonObject {
            put("expectedState", state)
            put("state", JsonObject(state + ("sequence" to JsonPrimitive(sequence.toString()))))
        })
        assertTrue(response.getValue("ok").jsonPrimitive.boolean)
    }

    private suspend fun rawRead(name: String): JsonObject {
        val response = rawOperation(name, "read", buildJsonObject { })
        assertTrue(response.getValue("ok").jsonPrimitive.boolean)
        return Json.parseToJsonElement(response.getValue("payload").jsonPrimitive.content).jsonObject
    }

    private suspend fun rawOperation(name: String, operation: String, input: JsonObject): JsonObject =
        Json.parseToJsonElement(runCatalogIndexedDbOperation(name, operation, input.toString()).await().toString()).jsonObject

    private suspend fun inspect(name: String): JsonObject = Json.parseToJsonElement(inspectDatabase(name).await().toString()).jsonObject
    private fun ids(page: SessionCatalogPage) = page.entries.map { it.identity.sessionId.value }
    private suspend fun assertCatalogFailure(failure: SessionCatalogFailure, block: suspend () -> Unit) {
        assertEquals(failure, assertFailsWith<SessionCatalogException> { block() }.failure)
    }
    private fun open(name: String, projector: SessionCatalogProjector) = createMagratheaWebStore(
        configuration = MagratheaWebStoreConfiguration(name), projector = projector)
    private suspend fun isolated(block: suspend (String) -> Unit) {
        val name = "catalog-test-${SystemIdGenerator.nextId()}"
        try { block(name) } finally { assertEquals("ok", deleteCatalogDatabase(name).await().toString()) }
    }
    private fun snapshot(id: String, activity: String, time: Long, text: String = activity): AgentSessionSnapshot {
        val sessionId = AgentSessionId(id)
        val message = AgentMessage(id = activity, role = MessageRole.USER, parts = listOf(TextPart(text)), createdAtEpochMs = time)
        val request = AgentRequest(sessionId = sessionId, messages = listOf(message), model = ModelDescriptor("offline", "test"))
        return AgentSessionSnapshot(sessionId, AgentRunId("$id-run"), request, AgentStateSnapshot(messages = request.messages), updatedAtEpochMs = time)
    }
    private fun checkpoint(snapshot: AgentSessionSnapshot, turn: Int) = AgentCheckpoint(snapshot.sessionId, snapshot.runId,
        AgentResumeCursor(turn, AgentResumePhase.MODEL_PENDING), snapshot.state.copy(turn = turn))
}

private class BrowserProjector(
    policyVersion: Int = 1,
    mode: SessionActivityMode = SessionActivityMode.CANONICAL_TIME,
    private val searchPrefix: String = "",
) : SessionCatalogProjector {
    override val policy = SessionCatalogPolicy("browser-test", policyVersion)
    override val activityMode = mode
    override fun normalizeSearch(text: String) = searchPrefix + text.trim().lowercase()
    override fun project(snapshot: AgentSessionSnapshot): SessionCatalogProjection {
        val user = snapshot.state.messages.lastOrNull { it.role == MessageRole.USER }
        val preview = user?.parts?.filterIsInstance<TextPart>()?.joinToString(" ") { it.text }.orEmpty()
        val label = "Fallback ${snapshot.sessionId.value}"
        return SessionCatalogProjection(label, preview.take(12), "Catalog Model", user?.id, snapshot.updatedAtEpochMs,
            normalizeSearch(label), normalizeSearch(preview), normalizeSearch("Catalog Model"))
    }
}

private fun createLegacy(name: String, rowsJson: String, checkpointPayload: String): Promise<JsString> = js(
    """
    new Promise((resolve) => {
      const request = indexedDB.open(name, 1);
      request.onupgradeneeded = () => { request.result.createObjectStore("sessions"); request.result.createObjectStore("checkpoints"); };
      request.onerror = () => resolve("error");
      request.onsuccess = () => {
        const database = request.result;
        const transaction = database.transaction(["sessions", "checkpoints"], "readwrite");
        const rows = JSON.parse(rowsJson);
        Object.keys(rows).forEach((id) => transaction.objectStore("sessions").put(rows[id], id));
        transaction.objectStore("sessions").put("opaque-number", 7);
        transaction.objectStore("sessions").put("opaque-date", new Date(1234));
        transaction.objectStore("checkpoints").put(checkpointPayload, "a");
        transaction.oncomplete = () => { database.close(); resolve("ok"); };
        transaction.onabort = () => { database.close(); resolve("error"); };
      };
    })
    """,
)

private fun inspectDatabase(name: String): Promise<JsString> = js(
    """
    new Promise((resolve) => {
      const request = indexedDB.open(name);
      request.onerror = () => resolve("{}");
      request.onsuccess = () => {
        const database = request.result;
        const names = Array.from(database.objectStoreNames).sort();
        const result = { version: database.version, stores: names };
        const transaction = database.transaction(names, "readonly");
        names.forEach((store) => {
          const values = {};
          result[store] = values;
          const cursorRequest = transaction.objectStore(store).openCursor();
          cursorRequest.onsuccess = () => {
            const cursor = cursorRequest.result;
            if (cursor) { values[JSON.stringify(cursor.primaryKey)] = cursor.value; cursor.continue(); }
          };
        });
        transaction.oncomplete = () => {
          for (const store of names) {
            const values = result[store];
            const readable = {};
            for (const key of Object.keys(values)) {
              const parsed = JSON.parse(key);
              readable[typeof parsed === "string" ? parsed : key] = values[key];
            }
            result[store] = readable;
          }
          database.close(); resolve(JSON.stringify(result));
        };
        transaction.onabort = () => { database.close(); resolve("{}"); };
      };
    })
    """,
)

private fun deleteCatalogDatabase(name: String): Promise<JsString> = js(
    """
    new Promise((resolve) => {
      const request = indexedDB.deleteDatabase(name);
      request.onsuccess = () => resolve("ok");
      request.onerror = () => resolve("error");
      request.onblocked = () => resolve("blocked");
    })
    """,
)
