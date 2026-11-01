package saien.magrathea.storage.room

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import java.nio.file.Files
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import saien.magrathea.catalog.CatalogCoverage
import saien.magrathea.catalog.SessionActivityMode
import saien.magrathea.catalog.SessionActivityOrder
import saien.magrathea.catalog.SessionAttachmentReferences
import saien.magrathea.catalog.SessionCatalogCursor
import saien.magrathea.catalog.SessionCatalogException
import saien.magrathea.catalog.SessionCatalogFailure
import saien.magrathea.catalog.SessionCatalogPolicy
import saien.magrathea.catalog.SessionCatalogProjection
import saien.magrathea.catalog.SessionCatalogProjector
import saien.magrathea.catalog.SessionCatalogQuery
import saien.magrathea.catalog.SessionCatalogSortKey
import saien.magrathea.catalog.SessionCollection
import saien.magrathea.catalog.SessionOrganization
import saien.magrathea.catalog.SessionOrganizationMutation
import saien.magrathea.core.AgentMessage
import saien.magrathea.core.AgentRunId
import saien.magrathea.core.AgentSessionId
import saien.magrathea.core.AgentSessionSnapshot
import saien.magrathea.core.AttachmentPart
import saien.magrathea.core.MessageRole
import saien.magrathea.core.TextPart
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JvmRoomSessionCatalogTest {
    private val reporter = StoredRecordCorruptionReporter { error("Unexpected corruption: $it") }
    private val projector = PreparedRoomProjector()

    @Test
    fun firstCommitPersistsOrganizationAndAdmissionOrderAcrossReopen() = runTest {
        val directory = Files.createTempDirectory("room-catalog-reopen-")
        val path = directory.resolve("store.db").toString()
        var store = JvmMagratheaRoom.open(path, reporter, projector)
        try {
            val folder = SessionCollection("folder", "Work", "work", "work")
            store.catalog.createCollection(folder)
            val a = snapshot("a", "a-user", 100)
            val b = snapshot("b", "b-user", 1)
            val identityA = store.catalog.prepareNewSession(a.sessionId, "lifetime-a", SessionOrganization("Custom", folder.id, 7))
            val identityB = store.catalog.prepareNewSession(b.sessionId, "lifetime-b")
            val ticketA = store.catalog.prepareActivity(identityA, "a-user")
            val ticketB = store.catalog.prepareActivity(identityB, "b-user")
            assertTrue(ticketB.sequence > ticketA.sequence)
            store.persistence.commit(b, null)
            store.persistence.commit(a, roomTestCheckpoint(a, 2))
            assertEquals(listOf("b", "a"), store.catalog.pageSessions(SessionCatalogQuery(), limit = 10).entries
                .sortedByDescending { (it.activity.order as SessionActivityOrder.Accepted).sequence }.map { it.identity.sessionId.value })
            assertEquals(listOf("a", "b"), store.catalog.pageSessions(SessionCatalogQuery(), limit = 10).entries.map { it.identity.sessionId.value })
            val header = assertNotNull(store.catalog.sessionHeader(a.sessionId))
            assertEquals(identityA, header.identity)
            assertEquals("Custom", header.label)
            assertEquals(folder.id, header.collectionId)
            assertEquals(SessionActivityOrder.Accepted(ticketA.sequence), header.activity.order)
            store.close()
            store = JvmMagratheaRoom.open(path, reporter, projector)
            assertEquals(header, store.catalog.sessionHeader(a.sessionId))
            assertEquals(a, store.persistence.load(a.sessionId)?.snapshot)
            assertEquals(2, store.persistence.load(a.sessionId)?.checkpoint?.turn)
            assertEquals(folder, store.catalog.collection(folder.id))
            assertEquals(CatalogCoverage.COMPLETE, store.catalog.state().coverage)
            // An uncertain first-commit outcome can be retried without resetting current organization.
            store.catalog.updateOrganization(identityA, SessionOrganizationMutation.Title("Renamed"))
            assertEquals(identityA, store.catalog.prepareNewSession(a.sessionId, "lifetime-a", SessionOrganization("Old")))
            store.persistence.commit(a.copy(updatedAtEpochMs = 200), null)
            assertEquals("Renamed", store.catalog.sessionHeader(a.sessionId)?.label)
        } finally {
            store.close()
            check(directory.toFile().deleteRecursively())
        }
    }

    @Test
    fun sameSessionReverseCommitAndRegenerationPreserveAcceptedAuthority() = runTest {
        withStore { store, _ ->
            val first = snapshot("session", "u1", 1)
            val second = snapshot("session", "u2", 2)
            val identity = store.catalog.prepareNewSession(first.sessionId, "incarnation")
            val firstTicket = store.catalog.prepareActivity(identity, "u1")
            val secondTicket = store.catalog.prepareActivity(identity, "u2")
            store.persistence.commit(second, null)
            assertFailure(SessionCatalogFailure.STALE_ACTIVITY) { store.persistence.commit(first, roomTestCheckpoint(first, 3)) }
            assertEquals(second, store.persistence.load(first.sessionId)?.snapshot)
            assertNull(store.persistence.load(first.sessionId)?.checkpoint)
            store.catalog.releaseActivity(identity, "u1")
            store.catalog.preparePreservedActivity(identity, "u1")
            store.persistence.commit(first.copy(updatedAtEpochMs = 300), null)
            val regenerated = assertNotNull(store.catalog.sessionHeader(first.sessionId))
            assertEquals("u1", regenerated.activity.observedIdentity)
            assertEquals("u2", regenerated.activity.lastAcceptedIdentity)
            assertEquals(SessionActivityOrder.Accepted(secondTicket.sequence), regenerated.activity.order)
            assertTrue(secondTicket.sequence > firstTicket.sequence)
            assertEquals(secondTicket, store.catalog.prepareActivity(identity, "u2"))
            store.persistence.commit(second.copy(updatedAtEpochMs = 400), null)
            assertEquals("u2", store.catalog.sessionHeader(first.sessionId)?.activity?.observedIdentity)
            assertEquals(regenerated.activity.order, store.catalog.sessionHeader(first.sessionId)?.activity?.order)
        }
    }

    @Test
    fun sqliteFailureRollsBackCanonicalCheckpointOrganizationReferencesAndRevision() = runTest {
        withStore { store, path ->
            store.catalog.createCollection(SessionCollection("folder", "Folder", "folder", "folder"))
            val session = snapshot("rollback", "u1", 1).let { value ->
                value.copy(state = value.state.copy(messages = value.state.messages.map {
                    it.copy(parts = it.parts + AttachmentPart("attachment://one", "image/png"))
                }))
            }
            val identity = store.catalog.prepareNewSession(session.sessionId, "rollback-life", SessionOrganization("Initial", "folder"))
            val ticket = store.catalog.prepareActivity(identity, "u1")
            val checkpoint = roomTestCheckpoint(session, 4).let { value ->
                value.copy(state = value.state.copy(messages = value.state.messages.map {
                    it.copy(parts = it.parts + AttachmentPart("attachment://checkpoint-only", "image/png"))
                }))
            }
            val before = store.catalog.state()
            execute(path, "CREATE TRIGGER fail_search BEFORE INSERT ON session_search BEGIN SELECT RAISE(ABORT, 'injected'); END")
            assertFailsWith<Exception> { store.persistence.commit(session, checkpoint) }
            assertNull(store.persistence.load(session.sessionId))
            assertFalse(store.catalog.exists(session.sessionId))
            assertNull(store.catalog.sessionHeader(session.sessionId))
            assertTrue(store.catalog.referencedUris(setOf("attachment://one")).referencedUris.isEmpty())
            assertEquals(before, store.catalog.state())
            assertEquals(ticket, store.catalog.prepareActivity(identity, "u1"))
            execute(path, "DROP TRIGGER fail_search")
            store.persistence.commit(session, checkpoint)
            assertEquals("Initial", store.catalog.sessionHeader(session.sessionId)?.label)
            assertEquals(SessionOrganization("Initial", "folder"), store.catalog.organization(identity))
            assertEquals(setOf("attachment://one", "attachment://checkpoint-only"),
                store.catalog.referencedUris(setOf("attachment://one", "attachment://checkpoint-only")).referencedUris)
            assertEquals(4, store.persistence.load(session.sessionId)?.checkpoint?.turn)
            assertEquals(before.revision + 1, store.catalog.state().revision)
        }
    }

    @Test
    fun independentHandlesMergeOrganizationFieldsAndLateSnapshotUsesLatestValues() = runTest {
        withStore { first, path ->
            val session = snapshot("organization", "u1", 1)
            val identity = first.catalog.prepareNewSession(session.sessionId, "organization-life")
            first.catalog.prepareActivity(identity, "u1")
            first.persistence.commit(session, null)
            first.catalog.createCollection(SessionCollection("folder", "Folder", "folder", "folder"))
            val second = JvmMagratheaRoom.open(path, reporter, projector)
            try {
                awaitAll(
                    async { first.catalog.updateOrganization(identity, SessionOrganizationMutation.Title("Manual")) },
                    async { second.catalog.updateOrganization(identity, SessionOrganizationMutation.Pin(99)) },
                    async { second.catalog.updateOrganization(identity, SessionOrganizationMutation.Collection("folder")) },
                )
                first.persistence.commit(session.copy(updatedAtEpochMs = 999), null)
                assertEquals(SessionOrganization("Manual", "folder", 99), first.catalog.organization(identity))
                assertFalse(second.catalog.setTitleIfAbsent(identity, "Generated"))
                assertEquals("Manual", first.catalog.sessionHeader(session.sessionId)?.label)
                second.catalog.deleteCollection("folder")
                assertEquals(SessionOrganization("Manual", null, 99), first.catalog.organization(identity))
                first.catalog.updateOrganization(identity, SessionOrganizationMutation.Title(null))
                assertEquals("Fallback u1", first.catalog.sessionHeader(session.sessionId)?.label)
                assertEquals(1, first.catalog.pageSessions(SessionCatalogQuery(search = "fallback u1"), limit = 10).entries.size)
                assertTrue(first.catalog.pageSessions(SessionCatalogQuery(search = "manual"), limit = 10).entries.isEmpty())
            } finally {
                second.close()
            }
        }
    }

    @Test
    fun clearInvalidatesPreparedContextAndCursorsWhileKeepingCollections() = runTest {
        withStore { store, _ ->
            store.catalog.createCollection(SessionCollection("folder", "Folder", "folder", "folder"))
            val session = snapshot("clear", "u1", 1)
            val identity = store.catalog.prepareNewSession(session.sessionId, "before-clear")
            store.catalog.prepareActivity(identity, "u1")
            val state = store.catalog.state()
            store.persistence.clear()
            assertFailure(SessionCatalogFailure.STALE_IDENTITY) { store.catalog.prepareActivity(identity, "u1") }
            assertFailure(SessionCatalogFailure.ACTIVITY_NOT_PREPARED) { store.persistence.commit(session, null) }
            val query = SessionCatalogQuery()
            val stale = SessionCatalogCursor(query, state.policy, state.generation, SessionCatalogSortKey(1, "", "", ""))
            assertFailure(SessionCatalogFailure.INVALID_CURSOR) { store.catalog.pageSessions(query, stale, limit = 10) }
            assertNotNull(store.catalog.collection("folder"))
            val fresh = store.catalog.prepareNewSession(session.sessionId, "after-clear")
            store.catalog.prepareActivity(fresh, "u1")
            store.persistence.commit(session, null)
            assertEquals(fresh, store.catalog.sessionHeader(session.sessionId)?.identity)
            store.close()
            assertFailure(SessionCatalogFailure.CLOSED) { store.catalog.state() }
            assertFailure(SessionCatalogFailure.CLOSED) { store.catalog.releasePreparation(fresh) }
            assertFailure(SessionCatalogFailure.CLOSED) { store.persistence.commit(session, null) }
        }
    }

    @Test
    fun deleteClearsCanonicalBackgroundAndCatalogAtomicallyAndProtectsReusedIdentity() = runTest {
        val directory = Files.createTempDirectory("room-catalog-delete-")
        val path = directory.resolve("store.db").toString()
        val database = buildMagratheaRoomDatabase(Room.databaseBuilder<MagratheaDatabase>(name = path))
        val store = MagratheaRoomStoreHandle(database, reporter, projector = projector)
        try {
            val session = snapshot("delete", "u1", 1)
            val identity = store.catalog.prepareNewSession(session.sessionId, "old-life", SessionOrganization("Old"))
            store.catalog.prepareActivity(identity, "u1")
            store.persistence.commit(session, roomTestCheckpoint(session, 1))
            database.backgroundExecutionDao().upsert(AgentBackgroundExecutionEntity("delete", "work", "opaque", 1))
            database.backgroundOriginDao().upsert(AgentBackgroundOriginEntity("delete", "run", "opaque"))
            val beforeDelete = store.catalog.state()
            execute(path, "CREATE TRIGGER fail_delete BEFORE DELETE ON session_catalog BEGIN SELECT RAISE(ABORT, 'injected'); END")
            assertFailsWith<Exception> { store.persistence.deleteSession(session.sessionId) }
            assertEquals(beforeDelete, store.catalog.state())
            assertNotNull(database.sessionDao().findById("delete"))
            assertNotNull(database.checkpointDao().findById("delete"))
            assertEquals(1, database.backgroundExecutionDao().listBySession("delete").size)
            assertEquals(1, database.backgroundOriginDao().listBySession("delete").size)
            execute(path, "DROP TRIGGER fail_delete")
            store.persistence.deleteSession(session.sessionId)
            assertNull(database.sessionDao().findById("delete"))
            assertNull(database.checkpointDao().findById("delete"))
            assertTrue(database.backgroundExecutionDao().listBySession("delete").isEmpty())
            assertTrue(database.backgroundOriginDao().listBySession("delete").isEmpty())
            assertNull(store.catalog.organization(identity))
            val fresh = store.catalog.prepareNewSession(session.sessionId, "new-life")
            store.catalog.prepareActivity(fresh, "u1")
            store.persistence.commit(session, null)
            assertFailure(SessionCatalogFailure.STALE_IDENTITY) { store.catalog.updateOrganization(identity, SessionOrganizationMutation.Title("Stale")) }
            assertEquals(SessionOrganization(), store.catalog.organization(fresh))
        } finally {
            store.close()
            check(directory.toFile().deleteRecursively())
        }
    }

    @Test
    fun backgroundEnvelopesConservativelyLimitReferenceCoverageWithoutDecoding() = runTest {
        withStore { store, path ->
            val session = snapshot("references", "u1", 1).let { value ->
                value.copy(state = value.state.copy(messages = value.state.messages.map {
                    it.copy(parts = it.parts + AttachmentPart("attachment://known", "image/png"))
                }))
            }
            val identity = store.catalog.prepareNewSession(session.sessionId, "references-life")
            store.catalog.prepareActivity(identity, "u1")
            store.persistence.commit(session, null)
            val candidates = setOf("attachment://known", "attachment://potential-background")
            assertEquals(CatalogCoverage.COMPLETE, store.catalog.referencedUris(candidates).coverage)

            // These opaque records must never be decoded to answer a reference-coverage query.
            execute(path, "INSERT INTO agent_background_executions (sessionId, executionId, envelope, updatedAtEpochMs) VALUES ('other-session', 'work', 'opaque execution', 1)")
            execute(path, "INSERT INTO agent_background_origins VALUES ('other-session', 'run', 'opaque origin')")
            val partial = store.catalog.referencedUris(candidates)
            assertEquals(CatalogCoverage.PARTIAL, partial.coverage)
            assertEquals(setOf("attachment://known"), partial.referencedUris)
            assertEquals(CatalogCoverage.PARTIAL, store.catalog.referencedUris(emptySet()).coverage)
            assertEquals(CatalogCoverage.COMPLETE, store.catalog.pageSessions(SessionCatalogQuery(), limit = 10).state.coverage)

            store.backgroundStore.delete(AgentSessionId("other-session"), "work")
            assertEquals(CatalogCoverage.PARTIAL, store.catalog.referencedUris(candidates).coverage)
            store.backgroundStore.deleteOrigin(AgentSessionId("other-session"), AgentRunId("run"))
            assertEquals(SessionAttachmentReferences(setOf("attachment://known"), CatalogCoverage.COMPLETE),
                store.catalog.referencedUris(candidates))
        }
    }

    @Test
    fun policyMismatchIsCheckedAgainBeforeEveryWrite() = runTest {
        withStore { store, path ->
            val session = snapshot("policy", "u1", 1)
            val identity = store.catalog.prepareNewSession(session.sessionId, "life")
            store.catalog.prepareActivity(identity, "u1")
            execute(path, "UPDATE catalog_state SET value = 'other-policy' WHERE `key` = 'policyId'")
            assertFailure(SessionCatalogFailure.POLICY_MISMATCH) { store.persistence.commit(session, null) }
            assertFailure(SessionCatalogFailure.POLICY_MISMATCH) { store.catalog.state() }
            execute(path, "UPDATE catalog_state SET value = 'test.prepared' WHERE `key` = 'policyId'")
            assertFalse(store.catalog.exists(session.sessionId))
            store.persistence.commit(session, null)
            assertTrue(store.catalog.exists(session.sessionId))
        }
    }

    private suspend fun withStore(block: suspend (MagratheaRoomStoreHandle, String) -> Unit) {
        val directory = Files.createTempDirectory("room-catalog-")
        val path = directory.resolve("store.db").toString()
        val store = JvmMagratheaRoom.open(path, reporter, projector)
        try {
            block(store, path)
        } finally {
            store.close()
            check(directory.toFile().deleteRecursively())
        }
    }

    private suspend fun assertFailure(failure: SessionCatalogFailure, block: suspend () -> Unit) {
        assertEquals(failure, assertFailsWith<SessionCatalogException> { block() }.failure)
    }

    private fun execute(path: String, sql: String) = BundledSQLiteDriver().open(path).use { connection ->
        // This fixture connection bypasses Room's pool and can briefly compete with its
        // asynchronous invalidation writes. Wait at most five seconds; persistent locks still fail.
        connection.prepare("PRAGMA busy_timeout = 5000").use { it.step() }
        connection.prepare(sql).use { it.step() }
    }

    private fun snapshot(id: String, activity: String, updated: Long): AgentSessionSnapshot {
        val original = roomTestSnapshot(id, updatedAtEpochMs = updated)
        val message = AgentMessage(id = activity, role = MessageRole.USER, parts = listOf(TextPart(activity)), createdAtEpochMs = updated)
        return original.copy(request = original.request.copy(messages = listOf(message)), state = original.state.copy(messages = listOf(message)))
    }
}

private class PreparedRoomProjector : SessionCatalogProjector {
    override val policy = SessionCatalogPolicy("test.prepared", 1)
    override val activityMode = SessionActivityMode.PREPARED
    override fun normalizeSearch(text: String): String = text.trim().lowercase()
    override fun project(snapshot: AgentSessionSnapshot): SessionCatalogProjection {
        val activity = snapshot.state.messages.lastOrNull { it.role == MessageRole.USER }?.id
        return SessionCatalogProjection("Fallback $activity", "Preview $activity", "Test Model", activity,
            snapshot.updatedAtEpochMs, normalizeSearch("Fallback $activity"), normalizeSearch("Preview $activity"), "test model")
    }
}
