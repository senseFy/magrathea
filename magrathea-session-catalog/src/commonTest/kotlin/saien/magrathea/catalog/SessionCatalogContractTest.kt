package saien.magrathea.catalog

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import saien.magrathea.core.AgentCheckpoint
import saien.magrathea.core.AgentMessage
import saien.magrathea.core.AgentRequest
import saien.magrathea.core.AgentResumeCursor
import saien.magrathea.core.AgentResumePhase
import saien.magrathea.core.AgentRunId
import saien.magrathea.core.AgentSessionId
import saien.magrathea.core.AgentSessionSnapshot
import saien.magrathea.core.AgentStateSnapshot
import saien.magrathea.core.AgentStatus
import saien.magrathea.core.AttachmentPart
import saien.magrathea.core.MessageRole
import saien.magrathea.core.ModelDescriptor
import saien.magrathea.core.TextPart
import saien.magrathea.core.ToolExecutionRecord
import saien.magrathea.core.ToolExecutionResult
import saien.magrathea.core.ToolExecutionState
import saien.magrathea.core.ToolImageAttachmentReference
import saien.magrathea.core.ToolResultAudience
import saien.magrathea.core.ToolResultImageContent
import saien.magrathea.core.ToolResultPart
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SessionCatalogContractTest {
    @Test
    fun recoveryPagesExposeFactsWithoutProjectionAndInvalidateAfterClear() = runTest {
        val projector = MessageProjector()
        val store = InMemorySessionCatalogStore(projector)
        val running = snapshot("a").let { it.copy(state = it.state.copy(status = AgentStatus.RUNNING)) }
        val completed = snapshot("b").let { it.copy(state = it.state.copy(status = AgentStatus.COMPLETED)) }
        val checkpoint = AgentCheckpoint(completed.sessionId, completed.runId,
            AgentResumeCursor(0, AgentResumePhase.TURN_PREPARING), completed.state)
        store.persistence.commit(running, null)
        store.persistence.commit(completed, checkpoint)
        store.persistence.commit(snapshot("c"), null)
        val projections = projector.projectionCount
        val first = store.catalog.pageRecoveryCandidates(limit = 1)
        assertEquals(listOf(running.sessionId), first.entries.map { it.sessionId })
        assertEquals(setOf(SessionRecoveryReason.RECOVERABLE_STATUS), first.entries.single().reasons)
        val cursor = assertNotNull(first.nextCursor)
        val second = store.catalog.pageRecoveryCandidates(cursor, 1)
        assertEquals(setOf(SessionRecoveryReason.CHECKPOINT), second.entries.single().reasons)
        assertTrue(second.exhausted)
        assertEquals(projections, projector.projectionCount)
        store.persistence.commit(completed, null)
        assertTrue(store.catalog.pageRecoveryCandidates(cursor, 1).entries.isEmpty())
        store.persistence.clear()
        assertFailure(SessionCatalogFailure.INVALID_CURSOR) { store.catalog.pageRecoveryCandidates(cursor, 1) }
        assertTrue(store.catalog.pageRecoveryCandidates(limit = 1).entries.isEmpty())
    }

    @Test
    fun boundedRebuildPreservesAuthorityAndMergesChangesMadeBetweenBatches() = runTest {
        val store = InMemorySessionCatalogStore(MessageProjector(prepared = true))
        store.catalog.createCollection(collection("folder", "Folder"))
        listOf("a", "b", "c").forEach { id ->
            val identity = store.catalog.prepareNewSession(AgentSessionId(id), "life-$id", SessionOrganization(collectionId = "folder", pinnedAtEpochMs = 7))
            store.catalog.prepareActivity(identity, "input-$id")
            store.persistence.commit(snapshot(id, messages = listOf(message("input-$id", id).copy(
                parts = listOf(AttachmentPart("file:$id", "image/png"))))), null)
        }
        val identity = assertNotNull(store.catalog.sessionIdentity(AgentSessionId("b")))
        val activity = assertNotNull(store.catalog.sessionHeader(identity.sessionId)).activity
        val old = store.catalog.state()
        val started = store.maintenance.rebuild(old.policy, old.generation)
        assertEquals(CatalogCoverage.INDEXING, started.state.coverage)
        assertFalse(started.complete)
        assertTrue(store.catalog.pageSessions(SessionCatalogQuery(), limit = 10).entries.isEmpty())
        assertEquals(CatalogCoverage.INDEXING, store.catalog.referencedUris(setOf("file:b")).coverage)
        store.catalog.updateOrganization(identity, SessionOrganizationMutation.Title("During rebuild"))
        val first = store.maintenance.runBatch(1)
        assertEquals(1, first.indexed)
        assertEquals(AgentSessionId("a"), first.progress.afterSessionId)
        store.catalog.deleteCollection("folder")
        val second = store.maintenance.runBatch(1)
        assertEquals(1, second.indexed)
        val header = assertNotNull(store.catalog.sessionHeader(identity.sessionId))
        assertEquals("During rebuild", header.label)
        assertNull(header.collectionId)
        assertEquals(7L, header.pinnedAtEpochMs)
        assertEquals(activity, header.activity)
        val last = store.maintenance.runBatch(1)
        assertTrue(last.progress.complete)
        assertEquals(3L, last.progress.processed)
        assertEquals(CatalogCoverage.COMPLETE, last.progress.state.coverage)
        assertEquals(setOf("file:b"), store.catalog.referencedUris(setOf("file:b")).referencedUris)
        assertEquals(0, store.maintenance.runBatch(1).indexed)
        assertFailure(SessionCatalogFailure.STALE_MAINTENANCE) { store.maintenance.rebuild(old.policy, old.generation) }
    }

    @Test
    fun failedProjectionFinishesPartialAndRetryRequiresExplicitRebuild() = runTest {
        val projector = MessageProjector()
        val store = InMemorySessionCatalogStore(projector)
        val original = snapshot("s")
        store.persistence.commit(original, null)
        val state = store.catalog.state()
        store.maintenance.rebuild(state.policy, state.generation)
        projector.failProjection = true
        val result = store.maintenance.runBatch(1)
        assertTrue(result.progress.complete)
        assertEquals(CatalogCoverage.PARTIAL, result.progress.state.coverage)
        assertEquals(1L, result.progress.failed)
        assertEquals(listOf(SessionCatalogMaintenanceIssue(original.sessionId, SessionCatalogMaintenanceIssueKind.PROJECTION_FAILED)), result.issues)
        assertEquals(original, store.persistence.load(original.sessionId)?.snapshot)
        assertTrue(store.maintenance.runBatch(1).issues.isEmpty())
        assertEquals(CatalogCoverage.PARTIAL, store.catalog.referencedUris(emptySet()).coverage)
        projector.failProjection = false
        val partial = store.catalog.state()
        store.maintenance.rebuild(partial.policy, partial.generation)
        assertEquals(CatalogCoverage.COMPLETE, store.maintenance.runBatch(1).progress.state.coverage)
        assertNotNull(store.catalog.sessionHeader(original.sessionId))
    }

    @Test
    fun clearCancelsMaintenanceAndBoundedOperationsRejectOversizedRequests() = runTest {
        val store = InMemorySessionCatalogStore()
        store.persistence.commit(snapshot("s"), null)
        val state = store.catalog.state()
        store.maintenance.rebuild(state.policy, state.generation)
        assertFailsWith<IllegalArgumentException> { store.maintenance.runBatch(51) }
        assertFailsWith<IllegalArgumentException> { store.catalog.pageRecoveryCandidates(limit = 201) }
        store.persistence.clear()
        val result = store.maintenance.runBatch(1)
        assertTrue(result.progress.complete)
        assertEquals(0, result.indexed)
        assertNull(store.catalog.sessionHeader(AgentSessionId("s")))
        assertEquals(CatalogCoverage.COMPLETE, result.progress.state.coverage)
    }

    @Test
    fun headlessPersistenceDoesNotRequireMessagesOrPreparation() = runTest {
        val store = InMemorySessionCatalogStore()
        val snapshot = snapshot("headless", time = 7)
        store.persistence.commit(snapshot, null)
        assertEquals(snapshot, store.persistence.load(snapshot.sessionId)?.snapshot)
        assertEquals(listOf(snapshot), store.persistence.listSessions())
        val header = assertNotNull(store.catalog.sessionHeader(snapshot.sessionId))
        assertEquals(header.identity, store.catalog.sessionIdentity(snapshot.sessionId))
        assertEquals("headless", header.label)
        assertEquals(SessionActivityOrder.Legacy(7), header.activity.order)
        assertEquals(SessionOrganization(), store.catalog.organization(header.identity))
        assertEquals(listOf(header), store.catalog.pageSessions(SessionCatalogQuery(), limit = 10).entries)
        store.persistence.deleteSession(snapshot.sessionId)
        assertFalse(store.catalog.exists(snapshot.sessionId))
        assertNull(store.catalog.sessionIdentity(snapshot.sessionId))
        assertNull(store.catalog.organization(header.identity))
        store.close()
        assertFailure(SessionCatalogFailure.CLOSED) { store.catalog.state() }
    }

    @Test
    fun keysetsTraverseTiesBothDirectionsAndAnchorWithoutReadingSnapshots() = runTest {
        val projector = MessageProjector()
        val store = InMemorySessionCatalogStore(projector)
        val ids = listOf("z", "a", "é", "中", "😀", "aa", "a\u0000", "\uFFFF")
        ids.forEach { store.persistence.commit(snapshot(it, time = 4), null) }
        val countAfterWrites = projector.projectionCount
        val query = SessionCatalogQuery(pin = SessionPinFilter.UNPINNED, sort = SessionCatalogSort.ACTIVITY_DESC)
        val forward = mutableListOf<String>()
        var cursor: SessionCatalogCursor? = null
        var lastPage: SessionCatalogPage
        do {
            lastPage = store.catalog.pageSessions(query, cursor, limit = 3)
            forward += lastPage.entries.map { it.identity.sessionId.value }
            cursor = lastPage.nextCursor
        } while (cursor != null)
        assertEquals(ids.sorted(), forward)
        val backward = lastPage.entries.map { it.identity.sessionId.value }.toMutableList()
        cursor = lastPage.previousCursor
        while (cursor != null) {
            val page = store.catalog.pageSessions(query, cursor, CatalogPageDirection.BEFORE, 3)
            backward.addAll(0, page.entries.map { it.identity.sessionId.value })
            cursor = page.previousCursor
        }
        assertEquals(forward, backward)
        val window = store.catalog.windowAround(AgentSessionId("中"), query, 3)
        assertEquals(3, window.entries.size)
        assertTrue(window.entries.any { it.identity.sessionId.value == "中" })
        assertFalse(window.exhausted)
        val missing = store.catalog.windowAround(AgentSessionId("missing"), query, 3)
        assertTrue(missing.entries.isEmpty())
        assertTrue(missing.exhausted)
        val filtered = store.catalog.windowAround(AgentSessionId("中"), query.copy(search = "not a match"), 3)
        assertTrue(filtered.entries.isEmpty())
        assertEquals(countAfterWrites, projector.projectionCount)
        assertFailure(SessionCatalogFailure.UNSUPPORTED_QUERY) {
            store.catalog.pageSessions(query.copy(pin = SessionPinFilter.ANY), limit = 3)
        }
    }

    @Test
    fun searchUsesCompleteSeparateFieldsAndOnlyEffectiveTitle() = runTest {
        val store = InMemorySessionCatalogStore(MessageProjector())
        val snapshot = snapshot("session", messages = listOf(message("m", "fallback prefix with very distant needle_%")), modelName = "Chosen Model")
        store.persistence.commit(snapshot, null)
        val identity = assertNotNull(store.catalog.sessionHeader(snapshot.sessionId)).identity
        assertEquals("fallback", store.catalog.sessionHeader(snapshot.sessionId)?.previewExcerpt)
        assertEquals(1, store.catalog.pageSessions(SessionCatalogQuery(search = " NEEDLE_% "), limit = 2).entries.size)
        store.catalog.updateOrganization(identity, SessionOrganizationMutation.Title("Override"))
        assertEquals(1, store.catalog.pageSessions(SessionCatalogQuery(search = "override"), limit = 2).entries.size)
        assertEquals(0, store.catalog.pageSessions(SessionCatalogQuery(search = "fallback-title"), limit = 2).entries.size)
        assertEquals(1, store.catalog.pageSessions(SessionCatalogQuery(search = "chosen model"), limit = 2).entries.size)
        assertEquals(0, store.catalog.pageSessions(SessionCatalogQuery(search = "overridefallback"), limit = 2).entries.size)
        assertEquals(0, store.catalog.pageSessions(SessionCatalogQuery(search = "nonexistent%"), limit = 2).entries.size)
    }

    @Test
    fun collectionRemovalPreservesIndependentFieldsAndAllIncludesMembers() = runTest {
        val store = InMemorySessionCatalogStore()
        val collection = collection("c", "Collection")
        store.catalog.createCollection(collection)
        val first = store.catalog.prepareNewSession(AgentSessionId("first"), "incarnation-first", SessionOrganization("Custom", "c", 10))
        store.persistence.commit(snapshot("first"), null)
        val second = store.catalog.prepareNewSession(AgentSessionId("second"), "incarnation-second", SessionOrganization(collectionId = "c"))
        store.persistence.commit(snapshot("second"), null)
        store.persistence.commit(snapshot("third"), null)
        val allUnpinned = SessionCatalogQuery(pin = SessionPinFilter.UNPINNED, sort = SessionCatalogSort.ACTIVITY_DESC)
        assertEquals(setOf("second", "third"), store.catalog.pageSessions(allUnpinned, limit = 10).entries.map { it.identity.sessionId.value }.toSet())
        assertEquals(listOf("third"), store.catalog.pageSessions(allUnpinned.copy(scope = SessionCatalogScope.Unassigned), limit = 10).entries.map { it.identity.sessionId.value })
        store.catalog.deleteCollection("c")
        assertEquals(SessionOrganization("Custom", pinnedAtEpochMs = 10), store.catalog.organization(first))
        assertEquals(SessionOrganization(), store.catalog.organization(second))
        assertFalse(store.catalog.setTitleIfAbsent(first, "Automatic"))
        assertTrue(store.catalog.setTitleIfAbsent(second, "Automatic"))
        store.catalog.updateOrganization(first, SessionOrganizationMutation.Title("Edited"))
        store.catalog.updateOrganization(first, SessionOrganizationMutation.Pin(20))
        assertEquals(SessionOrganization("Edited", pinnedAtEpochMs = 20), store.catalog.organization(first))
    }

    @Test
    fun initializationIsInvisibleConsumedOnceAndDeletedCollectionIsUnassigned() = runTest {
        val store = InMemorySessionCatalogStore()
        store.catalog.createCollection(collection("c", "Collection"))
        val initial = SessionOrganization("Initial", "c", 9)
        val identity = store.catalog.prepareNewSession(AgentSessionId("s"), "token", initial)
        assertFalse(store.catalog.exists(identity.sessionId))
        assertTrue(store.catalog.pageSessions(SessionCatalogQuery(), limit = 10).entries.isEmpty())
        store.catalog.deleteCollection("c")
        store.persistence.commit(snapshot("s"), null)
        assertEquals(initial.copy(collectionId = null), store.catalog.organization(identity))
        store.catalog.updateOrganization(identity, SessionOrganizationMutation.Title("Later edit"))
        assertEquals(identity, store.catalog.prepareNewSession(identity.sessionId, "token", initial))
        store.persistence.commit(snapshot("s", time = 2), null)
        assertEquals("Later edit", store.catalog.organization(identity)?.title)
        store.persistence.deleteSession(identity.sessionId)
        store.catalog.prepareNewSession(identity.sessionId, "new-token")
        store.persistence.commit(snapshot("s"), null)
        assertFailure(SessionCatalogFailure.STALE_IDENTITY) {
            store.catalog.updateOrganization(identity, SessionOrganizationMutation.Title("Stale"))
        }
    }

    @Test
    fun acceptedOrderSurvivesReverseCommitClockRollbackRegenerationAndRebuild() = runTest {
        val store = InMemorySessionCatalogStore(MessageProjector(prepared = true))
        val a = store.catalog.prepareNewSession(AgentSessionId("a"), "a-token")
        val b = store.catalog.prepareNewSession(AgentSessionId("b"), "b-token")
        val a1 = store.catalog.prepareActivity(a, "a1")
        val b1 = store.catalog.prepareActivity(b, "b1")
        assertEquals(a1, store.catalog.prepareActivity(a, "a1"))
        assertTrue(b1.sequence > a1.sequence)
        store.persistence.commit(snapshot("b", 1, listOf(message("b1", "B"))), null)
        store.persistence.commit(snapshot("a", 100, listOf(message("a1", "A"))), null)
        assertEquals(listOf("b", "a"), store.catalog.pageSessions(SessionCatalogQuery(), limit = 10).entries.map { it.identity.sessionId.value })

        val a2 = store.catalog.prepareActivity(a, "a2")
        store.persistence.commit(snapshot("a", -100, listOf(message("a1", "A"), message("a2", "A again"))), null)
        val accepted = assertNotNull(store.catalog.sessionHeader(a.sessionId)).activity
        assertEquals(SessionActivityOrder.Accepted(a2.sequence), accepted.order)
        store.catalog.preparePreservedActivity(a, "a1")
        val regenerated = snapshot("a", -200, listOf(message("a1", "A")))
        store.persistence.commit(regenerated, null)
        store.persistence.commit(regenerated.copy(updatedAtEpochMs = -300), null)
        assertEquals(accepted.copy(observedIdentity = "a1"), store.catalog.sessionHeader(a.sessionId)?.activity)
        store.catalog.updateOrganization(a, SessionOrganizationMutation.Title("Keep me"))
        store.rebuild(MessageProjector(prepared = true, version = 2))
        assertEquals(accepted.copy(observedIdentity = "a1"), store.catalog.sessionHeader(a.sessionId)?.activity)
        assertEquals("Keep me", store.catalog.organization(a)?.title)
        assertEquals(listOf("a", "b"), store.catalog.pageSessions(SessionCatalogQuery(), limit = 10).entries.map { it.identity.sessionId.value })

        // A duplicate preparation of the durable last accepted operation is still idempotent
        // after Preserve and rebuild; it restores observation without allocating or moving order.
        assertEquals(a2, store.catalog.prepareActivity(a, "a2"))
        store.persistence.commit(snapshot("a", -400, listOf(message("a2", "A again"))), null)
        assertEquals(accepted, store.catalog.sessionHeader(a.sessionId)?.activity)

        store.catalog.createCollection(collection("retained", "Retained"))
        store.persistence.clear()
        assertNotNull(store.catalog.collection("retained"))
        val c = store.catalog.prepareNewSession(AgentSessionId("c"), "c-token")
        assertTrue(store.catalog.prepareActivity(c, "c1").sequence > a2.sequence)
    }

    @Test
    fun unpreparedOrStaleActivityCannotPartiallyCommitAndPreserveNullIsNotWildcard() = runTest {
        val store = InMemorySessionCatalogStore(MessageProjector(prepared = true))
        val identity = store.catalog.prepareNewSession(AgentSessionId("s"), "token", SessionOrganization("Initial"))
        val old = store.catalog.prepareActivity(identity, "old")
        val newer = store.catalog.prepareActivity(identity, "new")
        val committed = snapshot("s", messages = listOf(message("new", "new")))
        store.persistence.commit(committed, null)
        val before = store.catalog.state()
        assertFailure(SessionCatalogFailure.STALE_ACTIVITY) {
            store.persistence.commit(snapshot("s", messages = listOf(message("old", "old"))), null)
        }
        assertTrue(newer.sequence > old.sequence)
        assertEquals(committed, store.persistence.load(identity.sessionId)?.snapshot)
        assertEquals(before, store.catalog.state())
        store.catalog.preparePreservedActivity(identity, null)
        assertFailure(SessionCatalogFailure.ACTIVITY_NOT_PREPARED) {
            store.persistence.commit(snapshot("s", messages = listOf(message("unknown", "unknown"))), null)
        }
        assertEquals(committed, store.persistence.load(identity.sessionId)?.snapshot)
        store.persistence.commit(snapshot("s"), null)
        assertNull(store.catalog.sessionHeader(identity.sessionId)?.activity?.observedIdentity)
        assertEquals("new", store.catalog.sessionHeader(identity.sessionId)?.activity?.lastAcceptedIdentity)
    }

    @Test
    fun reusedSequenceCannotAuthorizeAnotherIdentityOrAnOlderAcceptedSequence() {
        val identity = SessionIdentity(AgentSessionId("s"), "token")
        val previous = SessionActivityFact("u1", "u2", SessionActivityOrder.Accepted(2))
        val projection = MessageProjector(prepared = true).project(snapshot("s", messages = listOf(message("other", "Other"))))
        val otherIdentity = PreparedActivityIntent.Advance(SessionActivityTicket(identity, "other", 2))
        assertEquals(SessionCatalogFailure.STALE_ACTIVITY, assertFailsWith<SessionCatalogException> {
            resolveSessionActivity(SessionActivityMode.PREPARED, projection, previous, otherIdentity)
        }.failure)
        val olderTicket = PreparedActivityIntent.Advance(SessionActivityTicket(identity, "u2", 1))
        assertEquals(SessionCatalogFailure.STALE_ACTIVITY, assertFailsWith<SessionCatalogException> {
            resolveSessionActivity(SessionActivityMode.PREPARED, projection.copy(activityIdentity = "u2"), previous, olderTicket)
        }.failure)
    }

    @Test
    fun releasingRejectedActivityPreservesOtherPreparationAndInitialOrganization() = runTest {
        val store = InMemorySessionCatalogStore(MessageProjector(prepared = true))
        val identity = store.catalog.prepareNewSession(AgentSessionId("s"), "token", SessionOrganization("Initial"))
        val rejected = store.catalog.prepareActivity(identity, "rejected")
        val accepted = store.catalog.prepareActivity(identity, "accepted")
        store.catalog.releaseActivity(identity, "rejected")
        assertEquals(accepted, store.catalog.prepareActivity(identity, "accepted"))
        assertFailure(SessionCatalogFailure.ACTIVITY_NOT_PREPARED) {
            store.persistence.commit(snapshot("s", messages = listOf(message("rejected", "Rejected"))), null)
        }
        assertFalse(store.catalog.exists(identity.sessionId))
        store.persistence.commit(snapshot("s", messages = listOf(message("accepted", "Accepted"))), null)
        assertEquals("Initial", store.catalog.organization(identity)?.title)
        assertEquals(SessionActivityOrder.Accepted(accepted.sequence), store.catalog.sessionHeader(identity.sessionId)?.activity?.order)
        assertTrue(store.catalog.prepareActivity(identity, "next").sequence > rejected.sequence)
    }

    @Test
    fun failedProjectionLeavesCanonicalAndDerivedStateUntouchedAndRetryKeepsPreparation() = runTest {
        val projector = MessageProjector(prepared = true)
        val store = InMemorySessionCatalogStore(projector)
        val identity = store.catalog.prepareNewSession(AgentSessionId("s"), "token", SessionOrganization("Initial"))
        val ticket = store.catalog.prepareActivity(identity, "m")
        val snapshot = snapshot("s", messages = listOf(message("m", "text")))
        projector.failProjection = true
        assertFailsWith<IllegalArgumentException> { store.persistence.commit(snapshot, null) }
        assertNull(store.persistence.load(identity.sessionId))
        assertFalse(store.catalog.exists(identity.sessionId))
        assertEquals(0L, store.catalog.state().revision)
        projector.failProjection = false
        store.persistence.commit(snapshot, null)
        assertEquals(SessionActivityOrder.Accepted(ticket.sequence), store.catalog.sessionHeader(identity.sessionId)?.activity?.order)
        assertEquals("Initial", store.catalog.organization(identity)?.title)
    }

    @Test
    fun queryAndGenerationBindCursorsAndCollectionsHaveIndependentKeysets() = runTest {
        val store = InMemorySessionCatalogStore()
        (0..4).forEach {
            store.persistence.commit(snapshot("s$it", it.toLong()), null)
            store.catalog.createCollection(collection("c$it", "Name $it"))
        }
        val query = SessionCatalogQuery()
        val first = store.catalog.pageSessions(query, limit = 2)
        val cursor = assertNotNull(first.nextCursor)
        assertFailure(SessionCatalogFailure.INVALID_CURSOR) { store.catalog.pageSessions(query.copy(search = "s"), cursor, limit = 2) }
        store.rebuild()
        assertFailure(SessionCatalogFailure.INVALID_CURSOR) { store.catalog.pageSessions(query, cursor, limit = 2) }
        val collections = store.catalog.pageCollections(limit = 2)
        val next = store.catalog.pageCollections(cursor = assertNotNull(collections.nextCursor), limit = 2)
        assertEquals(listOf("c2", "c3"), next.entries.map { it.id })
        val prior = store.catalog.pageCollections(cursor = assertNotNull(next.previousCursor), direction = CatalogPageDirection.BEFORE, limit = 2)
        assertEquals(collections.entries, prior.entries)
        assertFailure(SessionCatalogFailure.COLLECTION_CONFLICT) { store.catalog.createCollection(collection("other", "Name 0")) }
        store.catalog.renameCollection("c1", "Renamed", CatalogKeyEncoding.stringKey("renamed"), "renamed")
        assertEquals(listOf("c1"), store.catalog.pageCollections(SessionCollectionQuery("RENAMED"), limit = 10).entries.map { it.id })
    }

    @Test
    fun allPageShapesRejectUnboundedOrEmptyLimits() = runTest {
        val store = InMemorySessionCatalogStore()
        store.persistence.commit(snapshot("s"), null)
        listOf(0, -1, MAX_CATALOG_PAGE_SIZE + 1, Int.MAX_VALUE).forEach { limit ->
            assertFailsWith<IllegalArgumentException> { store.catalog.pageSessions(SessionCatalogQuery(), limit = limit) }
            assertFailsWith<IllegalArgumentException> { store.catalog.pageCollections(limit = limit) }
            assertFailsWith<IllegalArgumentException> { store.catalog.windowAround(AgentSessionId("s"), SessionCatalogQuery(), limit) }
            assertFailsWith<IllegalArgumentException> { store.catalog.windowAroundCollection("c", limit = limit) }
        }
        assertEquals(1, store.catalog.pageSessions(SessionCatalogQuery(), limit = MAX_CATALOG_PAGE_SIZE).entries.size)
    }

    @Test
    fun collectionRevisionIgnoresSessionActivityMembershipAndNoopCollectionWrites() = runTest {
        val store = InMemorySessionCatalogStore()
        val catalog = store.catalog
        assertEquals(0L, catalog.state().collectionsRevision)
        val folder = collection("c", "Folder")
        catalog.createCollection(folder)
        val created = catalog.state()
        assertEquals(created.revision, created.collectionsRevision)
        repeat(10) { store.persistence.commit(snapshot("s", time = it.toLong()), null) }
        val identity = assertNotNull(catalog.sessionIdentity(AgentSessionId("s")))
        catalog.updateOrganization(identity, SessionOrganizationMutation.Collection(folder.id))
        catalog.updateOrganization(identity, SessionOrganizationMutation.Title("Custom"))
        catalog.updateOrganization(identity, SessionOrganizationMutation.Pin(7))
        store.persistence.deleteSession(identity.sessionId)
        catalog.renameCollection(folder.id, folder.name, folder.nameSortKey, folder.nameUniqueKey)
        catalog.deleteCollection("absent")
        assertFailure(SessionCatalogFailure.COLLECTION_CONFLICT) { catalog.createCollection(folder) }
        assertTrue(catalog.state().revision > created.revision)
        assertEquals(created.collectionsRevision, catalog.state().collectionsRevision)

        catalog.renameCollection(folder.id, "Renamed", "renamed", "renamed")
        val renamed = catalog.state()
        assertTrue(renamed.collectionsRevision > created.collectionsRevision)
        assertEquals(renamed.revision, renamed.collectionsRevision)
        catalog.deleteCollection(folder.id)
        val deleted = catalog.state()
        assertTrue(deleted.collectionsRevision > renamed.collectionsRevision)
        assertEquals(deleted.revision, deleted.collectionsRevision)
        store.close()
    }

    @Test
    fun collectionRevisionInvalidatesRetainedCollectionCursorsOnClearAndRebuild() = runTest {
        val store = InMemorySessionCatalogStore()
        repeat(3) { store.catalog.createCollection(collection("c$it", "Folder $it")) }
        val page = store.catalog.pageCollections(limit = 1)
        store.persistence.clear()
        val cleared = store.catalog.state()
        assertTrue(cleared.collectionsRevision > page.state.collectionsRevision)
        assertEquals(3, store.catalog.pageCollections(limit = 10).entries.size)
        assertFailure(SessionCatalogFailure.INVALID_CURSOR) {
            store.catalog.pageCollections(cursor = assertNotNull(page.nextCursor), limit = 1)
        }
        val rebuilt = store.maintenance.rebuild(cleared.policy, cleared.generation).state
        assertTrue(rebuilt.collectionsRevision > cleared.collectionsRevision)
        assertEquals(rebuilt.collectionsRevision, store.maintenance.runBatch(1).progress.state.collectionsRevision)
        store.rebuild()
        assertTrue(store.catalog.state().collectionsRevision > rebuilt.collectionsRevision)
        store.close()
    }

    @Test
    fun organizationImportOnlyInvalidatesCollectionsWhenItAddsDefinitions() = runTest {
        val store = InMemorySessionCatalogStore()
        val folder = collection("c", "Folder")
        store.catalog.createCollection(folder)
        val before = store.catalog.state().collectionsRevision
        val receipt = store.migration.beginOrganizationImport("host", "frozen-source")
        store.migration.importOrganizationBatch(receipt.token, 0, listOf(folder), emptyList())
        assertEquals(before, store.catalog.state().collectionsRevision)
        val added = collection("d", "Other")
        store.migration.importOrganizationBatch(receipt.token, 1, listOf(added), emptyList(), complete = true)
        val after = store.catalog.state()
        assertTrue(after.collectionsRevision > before)
        assertEquals(after.revision, after.collectionsRevision)
        store.migration.importOrganizationBatch(receipt.token, 1, listOf(added), emptyList(), complete = true)
        assertEquals(after, store.catalog.state())
        store.close()
    }

    @Test
    fun collectionWindowsOwnTheirCursorsAcrossTiesSearchRenameAndDeletion() = runTest {
        val store = InMemorySessionCatalogStore()
        val ids = listOf("a", "a\u0000", "é", "中", "😀", "\uFFFF")
        ids.reversed().forEach {
            store.catalog.createCollection(SessionCollection(it, "Group %", "same-sort", "unique-$it"))
        }
        val query = SessionCollectionQuery(" GROUP % ")
        val anchor = store.catalog.windowAroundCollection("中", query, 1)
        assertEquals(listOf("中"), anchor.entries.map { it.id })
        assertFalse(anchor.exhausted)
        val before = store.catalog.pageCollections(query, assertNotNull(anchor.previousCursor), CatalogPageDirection.BEFORE, 10)
        val after = store.catalog.pageCollections(query, assertNotNull(anchor.nextCursor), limit = 10)
        assertEquals(ids.sorted(), (before.entries + anchor.entries + after.entries).map { it.id })
        val full = store.catalog.windowAroundCollection("\uFFFF", query, 10)
        assertEquals(ids.sorted(), full.entries.map { it.id })
        assertTrue(full.exhausted)
        val missing = store.catalog.windowAroundCollection("absent", query, 3)
        assertTrue(missing.entries.isEmpty())
        assertTrue(missing.exhausted)
        assertNull(missing.previousCursor)
        assertNull(missing.nextCursor)
        assertTrue(store.catalog.windowAroundCollection("中", SessionCollectionQuery("none"), 3).entries.isEmpty())

        store.catalog.renameCollection("中", "Renamed", "zzz", "renamed")
        assertTrue(store.catalog.windowAroundCollection("中", query, 3).entries.isEmpty())
        assertEquals("Renamed", store.catalog.windowAroundCollection("中", limit = 1).entries.single().name)
        store.catalog.deleteCollection("中")
        assertTrue(store.catalog.windowAroundCollection("中", limit = 3).entries.isEmpty())
        store.close()
    }

    @Test
    fun attachmentReferenceLookupIsCandidateBoundedAndUpdatesWithCanonicalWrites() = runTest {
        val store = InMemorySessionCatalogStore()
        val requestAttachment = AgentMessage(role = MessageRole.USER, parts = listOf(AttachmentPart("file:request", "image/png")))
        val stateAttachment = AgentMessage(role = MessageRole.ASSISTANT, parts = listOf(
            AttachmentPart("file:state", "image/png"),
            ToolResultPart("call", "tool", JsonPrimitive("file:opaque"), content = listOf(
                ToolResultImageContent(
                    source = ToolImageAttachmentReference("file:tool-image"),
                    previewSource = ToolImageAttachmentReference("file:tool-preview"),
                    audiences = setOf(ToolResultAudience.USER),
                ),
            )),
        ))
        val withAttachments = snapshot("s", messages = listOf(requestAttachment)).let { it.copy(state = it.state.copy(messages = listOf(stateAttachment))) }
        val checkpoint = AgentCheckpoint(
            sessionId = withAttachments.sessionId,
            runId = withAttachments.runId,
            cursor = AgentResumeCursor(0, AgentResumePhase.TURN_COMMITTED),
            state = AgentStateSnapshot(listOf(AgentMessage(role = MessageRole.USER, parts = listOf(AttachmentPart("file:checkpoint", "image/png"))))),
            toolExecutions = listOf(ToolExecutionRecord(
                executionId = "execution", toolCallId = "call", toolName = "tool", callOrdinal = 1,
                state = ToolExecutionState.COMPLETED,
                result = ToolExecutionResult("call", "tool", JsonPrimitive("file:opaque"), content = listOf(
                    ToolResultImageContent(ToolImageAttachmentReference("file:journal"), audiences = setOf(ToolResultAudience.USER)),
                )),
            )),
        )
        store.persistence.commit(withAttachments, checkpoint)
        val expected = setOf("file:request", "file:state", "file:tool-image", "file:tool-preview", "file:checkpoint", "file:journal")
        assertEquals(SessionAttachmentReferences(expected, CatalogCoverage.COMPLETE),
            store.catalog.referencedUris(expected + setOf("file:unused", "file:opaque")))
        store.persistence.commit(snapshot("s"), null)
        assertTrue(store.catalog.referencedUris(expected).referencedUris.isEmpty())
    }

    @Test
    fun defaultSearchKeepsUnicodeTextAndTreatsWildcardsLiterally() = runTest {
        val store = InMemorySessionCatalogStore()
        val international = snapshot("international")
        store.persistence.commit(international, null)
        store.persistence.commit(snapshot("ordinary"), null)
        val identity = assertNotNull(store.catalog.sessionIdentity(international.sessionId))
        store.catalog.updateOrganization(identity, SessionOrganizationMutation.Title("İSTANBUL CAFÉ 中文 🐢_100%"))

        for (query in listOf("i\u0307stanbul", "CAFÉ", "中文", "🐢", "_", "%")) {
            val page = store.catalog.pageSessions(SessionCatalogQuery(search = query), limit = 10)
            assertEquals(listOf(identity), page.entries.map { it.identity }, query)
        }
        assertTrue(store.catalog.pageSessions(SessionCatalogQuery(search = "cafe"), limit = 10).entries.isEmpty())
        store.close()
    }

    @Test
    fun portableKeysPreserveSignedLongPrecisionAndUtf16Ordering() {
        val values = listOf(Long.MIN_VALUE, -1, 0, 1, 9_007_199_254_740_993, Long.MAX_VALUE)
        assertEquals(values, values.shuffled().sortedBy(CatalogKeyEncoding::ascendingLong))
        assertEquals(values.reversed(), values.shuffled().sortedBy(CatalogKeyEncoding::descendingLong))
        assertTrue(CatalogKeyEncoding.activityRank(SessionActivityOrder.Accepted(1)) < CatalogKeyEncoding.activityRank(SessionActivityOrder.Legacy(Long.MAX_VALUE)))
        val strings = listOf("", "a", "a\u0000", "é", "中", "😀", "\uFFFF")
        assertEquals(strings.sorted(), strings.reversed().sortedBy(CatalogKeyEncoding::stringKey))
        assertTrue(CatalogKeyEncoding.collectionKey(null) != CatalogKeyEncoding.collectionKey(""))
    }

    private class MessageProjector(
        private val prepared: Boolean = false,
        version: Int = 1,
    ) : SessionCatalogProjector {
        override val policy = SessionCatalogPolicy("test.messages", version)
        override val activityMode = if (prepared) SessionActivityMode.PREPARED else SessionActivityMode.CANONICAL_TIME
        var projectionCount = 0
        var failProjection = false

        override fun normalizeSearch(text: String): String = text.trim().lowercase()

        override fun project(snapshot: AgentSessionSnapshot): SessionCatalogProjection {
            projectionCount++
            require(!failProjection)
            val latest = snapshot.state.messages.lastOrNull { it.role == MessageRole.USER }
            val preview = latest?.parts?.filterIsInstance<TextPart>()?.joinToString("") { it.text }.orEmpty()
            return SessionCatalogProjection(
                fallbackLabel = "fallback-title",
                previewExcerpt = preview.take(8),
                modelDisplayName = snapshot.request.model.displayName,
                activityIdentity = latest?.id,
                activityAtEpochMs = snapshot.updatedAtEpochMs,
                normalizedFallbackLabel = "fallback-title",
                normalizedPreview = normalizeSearch(preview),
                normalizedModelName = normalizeSearch(snapshot.request.model.displayName),
            )
        }
    }

    private fun snapshot(id: String, time: Long = 1, messages: List<AgentMessage> = emptyList(), modelName: String = "Model"): AgentSessionSnapshot {
        val sessionId = AgentSessionId(id)
        return AgentSessionSnapshot(
            sessionId = sessionId,
            runId = AgentRunId("run-$id"),
            request = AgentRequest(sessionId = sessionId, messages = messages, model = ModelDescriptor("provider", "model", modelName)),
            state = AgentStateSnapshot(messages = messages),
            updatedAtEpochMs = time,
        )
    }

    private fun message(id: String, text: String) = AgentMessage(id = id, role = MessageRole.USER, parts = listOf(TextPart(text)), createdAtEpochMs = 1)

    private fun collection(id: String, name: String) = SessionCollection(id, name, CatalogKeyEncoding.stringKey(name.lowercase()), name.lowercase())

    private suspend fun assertFailure(failure: SessionCatalogFailure, block: suspend () -> Unit) {
        assertEquals(failure, assertFailsWith<SessionCatalogException> { block() }.failure)
    }
}
