package saien.magrathea.storage.room

import androidx.room.Room
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import saien.magrathea.catalog.CatalogCoverage
import saien.magrathea.catalog.SessionActivityMode
import saien.magrathea.catalog.SessionCatalogException
import saien.magrathea.catalog.SessionCatalogFailure
import saien.magrathea.catalog.SessionCatalogMaintenanceIssue
import saien.magrathea.catalog.SessionCatalogMaintenanceIssueKind
import saien.magrathea.catalog.SessionCatalogPolicy
import saien.magrathea.catalog.SessionCatalogProjection
import saien.magrathea.catalog.SessionCatalogProjector
import saien.magrathea.catalog.SessionCatalogQuery
import saien.magrathea.catalog.SessionCollection
import saien.magrathea.catalog.SessionCollectionQuery
import saien.magrathea.catalog.SessionOrganization
import saien.magrathea.catalog.SessionOrganizationMutation
import saien.magrathea.core.AgentCheckpoint
import saien.magrathea.core.AgentCheckpointCodec
import saien.magrathea.core.AgentMessage
import saien.magrathea.core.AgentSessionId
import saien.magrathea.core.AgentSessionSnapshot
import saien.magrathea.core.AgentSessionSnapshotCodec
import saien.magrathea.core.AttachmentPart
import saien.magrathea.core.CURRENT_STORAGE_SCHEMA_VERSION
import saien.magrathea.core.MessageRole
import saien.magrathea.core.TextPart
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JvmRoomCatalogMaintenanceTest {
    private val reporter = StoredRecordCorruptionReporter { error("Unexpected corruption report: $it") }
    private val codec = AgentSessionSnapshotCodec()

    @Test
    fun boundedBackfillResumesAfterReopenAndRetainsUnreadableBytes() = runTest {
        val projector = TestProjector()
        withFixture(projector) { fixture ->
            val a = snapshot("a", "u-a", 1)
            val c = snapshot("c", "u-c", 3)
            fixture.seed(a)
            fixture.database.sessionDao().upsert(AgentSessionEntity(sessionId = "b-corrupt", payload = "opaque corrupt bytes", updatedAtEpochMs = 2))
            fixture.seed(c)
            val future = JsonObject(Json.parseToJsonElement(codec.encode(snapshot("d-future", "u-d", 4))).jsonObject +
                ("schemaVersion" to JsonPrimitive(CURRENT_STORAGE_SCHEMA_VERSION + 100))).toString()
            fixture.database.sessionDao().upsert(AgentSessionEntity(sessionId = "d-future", payload = future, updatedAtEpochMs = 4))
            assertEquals(CatalogCoverage.INDEXING, fixture.store.catalog.state().coverage)
            val first = fixture.store.maintenance.runBatch(1)
            assertEquals(1, first.indexed)
            assertEquals(AgentSessionId("a"), first.progress.afterSessionId)
            assertEquals(1, first.progress.processed)
            assertFalse(first.progress.complete)
            assertEquals(1, projector.calls.get())
            fixture.store.close()

            val reopened = JvmMagratheaRoom.open(fixture.path, reporter, projector)
            try {
                assertEquals(first.progress.afterSessionId, reopened.maintenance.status().afterSessionId)
                val bad = reopened.maintenance.runBatch(1)
                assertEquals(SessionCatalogMaintenanceIssueKind.UNREADABLE, bad.issues.single().kind)
                assertEquals(1, bad.progress.failed)
                assertEquals(CatalogCoverage.PARTIAL, bad.progress.state.coverage)
                val results = mutableListOf<SessionCatalogMaintenanceIssue>()
                repeat(10) {
                    if (!reopened.maintenance.status().complete) results += reopened.maintenance.runBatch(1).issues
                }
                val done = reopened.maintenance.status()
                assertTrue(done.complete)
                assertEquals(4, done.processed)
                assertEquals(2, done.failed)
                assertEquals(CatalogCoverage.PARTIAL, done.state.coverage)
                assertEquals(SessionCatalogMaintenanceIssueKind.INCOMPATIBLE_SCHEMA, results.single().kind)
                assertEquals(setOf(a.sessionId, c.sessionId), reopened.catalog.pageSessions(SessionCatalogQuery(), limit = 10).entries.map { it.identity.sessionId }.toSet())
                val calls = projector.calls.get()
                assertTrue(reopened.maintenance.runBatch(1).progress.complete)
                assertEquals(calls, projector.calls.get())
            } finally {
                reopened.close()
            }

            val raw = buildMagratheaRoomDatabase(Room.databaseBuilder<MagratheaDatabase>(name = fixture.path))
            try {
                assertEquals("opaque corrupt bytes", raw.sessionDao().findById("b-corrupt")?.payload)
                assertEquals(future, raw.sessionDao().findById("d-future")?.payload)
                assertEquals("legacy", raw.sessionDao().findById("a")?.incarnation)
                assertEquals(codec.encode(a), raw.sessionDao().findById("a")?.payload)
            } finally {
                raw.close()
            }
        }
    }

    @Test
    fun concurrentCanonicalCommitWinsCasAndTheUnadvancedRowIsRetried() = runTest {
        val gate = GatedProjector(TestProjector())
        withFixture(gate) { fixture ->
            val old = snapshot("race", "old", 1)
            fixture.seed(old, roomTestCheckpoint(old, 1))
            val writer = JvmMagratheaRoom.open(fixture.path, reporter, TestProjector())
            try {
                val pending = async(Dispatchers.Default) { fixture.store.maintenance.runBatch(1) }
                gate.awaitEntered()
                val newest = snapshot("race", "new", 2)
                writer.persistence.commit(newest, roomTestCheckpoint(newest, 9))
                gate.release()
                val raced = pending.await()
                assertEquals(0, raced.indexed)
                assertEquals(0, raced.progress.processed)
                assertNull(raced.progress.afterSessionId)
                assertEquals(newest, writer.persistence.load(newest.sessionId)?.snapshot)
                assertEquals(9, writer.persistence.load(newest.sessionId)?.checkpoint?.turn)
                finish(fixture.store)
                assertEquals("Title new", fixture.store.catalog.sessionHeader(newest.sessionId)?.label)
                assertEquals(1, fixture.store.maintenance.status().processed)
            } finally {
                gate.release()
                writer.close()
            }
        }
    }

    @Test
    fun checkpointOnlyRaceCannotInstallStaleReferences() = runTest {
        val gate = GatedProjector(TestProjector())
        withFixture(gate) { fixture ->
            val session = snapshot("checkpoint", "u", 1)
            val oldCheckpoint = roomTestCheckpoint(session, 1)
            fixture.seed(session, oldCheckpoint)
            val pending = async(Dispatchers.Default) { fixture.store.maintenance.runBatch(1) }
            gate.awaitEntered()
            val replacement = oldCheckpoint.copy(state = oldCheckpoint.state.copy(messages = listOf(
                AgentMessage(id = "checkpoint-reference", role = MessageRole.USER,
                    parts = listOf(AttachmentPart("attachment://new-checkpoint", "image/png"))),
            )))
            fixture.database.checkpointDao().upsert(AgentCheckpointEntity(session.sessionId.value, AgentCheckpointCodec().encode(replacement), replacement.turn))
            gate.release()
            assertEquals(0, pending.await().progress.processed)
            assertNull(fixture.store.catalog.sessionHeader(session.sessionId))
            finish(fixture.store)
            assertEquals(setOf("attachment://new-checkpoint"), fixture.store.catalog.referencedUris(setOf("attachment://new-checkpoint")).referencedUris)
        }
    }

    @Test
    fun clearDuringProjectionRejectsOldWorkAndDoesNotResurrectRows() = runTest {
        val gate = GatedProjector(TestProjector())
        withFixture(gate) { fixture ->
            fixture.seed(snapshot("deleted", "u", 1))
            val pending = async(Dispatchers.Default) {
                assertFailsWith<SessionCatalogException> { fixture.store.maintenance.runBatch(1) }
            }
            gate.awaitEntered()
            fixture.store.persistence.clear()
            val cleared = fixture.store.catalog.state()
            gate.release()
            assertEquals(SessionCatalogFailure.STALE_MAINTENANCE, pending.await().failure)
            assertEquals(cleared, fixture.store.catalog.state())
            assertFalse(fixture.store.catalog.exists(AgentSessionId("deleted")))
            assertTrue(fixture.store.maintenance.status().complete)
            assertTrue(fixture.store.catalog.pageSessions(SessionCatalogQuery(), limit = 10).entries.isEmpty())
        }
    }

    @Test
    fun samePolicyRebuildPreservesOrganizationActivityAndAllowsCurrentWriterCommits() = runTest {
        val projector = TestProjector(mode = SessionActivityMode.PREPARED)
        withFixture(projector) { fixture ->
            val session = snapshot("kept", "u1", 1)
            fixture.store.catalog.createCollection(SessionCollection("folder", "Folder", "folder", "folder"))
            val identity = fixture.store.catalog.prepareNewSession(session.sessionId, "kept-life", SessionOrganization("Manual", "folder", 10))
            fixture.store.catalog.prepareActivity(identity, "u1")
            fixture.store.persistence.commit(session, null)
            val activity = assertNotNull(fixture.store.catalog.sessionHeader(session.sessionId)).activity
            val before = fixture.store.catalog.state()
            val started = fixture.store.maintenance.rebuild(before.policy, before.generation)
            assertTrue(started.state.generation > before.generation)
            assertNull(fixture.store.catalog.sessionHeader(session.sessionId))
            fixture.store.catalog.updateOrganization(identity, SessionOrganizationMutation.Title("During rebuild"))
            // A normal same-policy commit takes the transaction's generation and is still accepted.
            fixture.store.persistence.commit(session.copy(updatedAtEpochMs = 99), null)
            finish(fixture.store)
            val header = assertNotNull(fixture.store.catalog.sessionHeader(session.sessionId))
            assertEquals("During rebuild", header.label)
            assertEquals("folder", header.collectionId)
            assertEquals(10, header.pinnedAtEpochMs)
            assertEquals(activity, header.activity)
            assertEquals(identity, header.identity)
            assertEquals(CatalogCoverage.COMPLETE, fixture.store.catalog.state().coverage)
            assertEquals(SessionCatalogFailure.STALE_MAINTENANCE,
                assertFailsWith<SessionCatalogException> { fixture.store.maintenance.rebuild(before.policy, before.generation) }.failure)
        }
    }

    @Test
    fun policySwitchContinuesCollectionNormalizationAfterClearAndReopen() = runTest {
        val oldPolicy = TestProjector(policyVersion = 1, searchPrefix = "old:")
        withFixture(oldPolicy) { fixture ->
            for (id in listOf("a", "b", "c")) fixture.store.catalog.createCollection(SessionCollection(id, "Name $id", id, id))
            val before = fixture.store.catalog.state()
            fixture.store.close()
            val newPolicy = TestProjector(policyVersion = 2, searchPrefix = "new:")
            var switched = JvmMagratheaRoom.open(fixture.path, reporter, newPolicy)
            try {
                assertEquals(SessionCatalogFailure.POLICY_MISMATCH,
                    assertFailsWith<SessionCatalogException> { switched.catalog.state() }.failure)
                val rebuilt = switched.maintenance.rebuild(before.policy, before.generation).state
                assertEquals(rebuilt.revision, rebuilt.collectionsRevision)
                assertTrue(rebuilt.collectionsRevision > before.collectionsRevision)
                assertFalse(switched.maintenance.runBatch(1).progress.complete)
                val normalized = switched.catalog.state()
                assertEquals(normalized.revision, normalized.collectionsRevision)
                assertTrue(normalized.collectionsRevision > rebuilt.collectionsRevision)
                switched.persistence.clear()
                val cleared = switched.catalog.state()
                assertEquals(cleared.revision, cleared.collectionsRevision)
                assertTrue(cleared.collectionsRevision > normalized.collectionsRevision)
                assertEquals(CatalogCoverage.INDEXING, switched.catalog.state().coverage)
                switched.close()
                switched = JvmMagratheaRoom.open(fixture.path, reporter, newPolicy)
                finish(switched, limit = 1)
                assertTrue(switched.catalog.state().collectionsRevision > cleared.collectionsRevision)
                assertEquals(CatalogCoverage.COMPLETE, switched.catalog.state().coverage)
                for (id in listOf("a", "b", "c")) {
                    assertEquals(listOf(id), switched.catalog.pageCollections(SessionCollectionQuery("Name $id"), limit = 10).entries.map { it.id })
                }
                assertTrue(switched.catalog.pageSessions(SessionCatalogQuery(), limit = 10).entries.isEmpty())
            } finally {
                switched.close()
            }
        }
    }

    @Test
    fun incompatiblePreparedActivityProjectionIsPartialWithoutChangingAuthority() = runTest {
        val oldPolicy = TestProjector(mode = SessionActivityMode.PREPARED)
        withFixture(oldPolicy) { fixture ->
            val session = snapshot("identity", "u1", 1)
            val identity = fixture.store.catalog.prepareNewSession(session.sessionId, "life")
            fixture.store.catalog.prepareActivity(identity, "u1")
            fixture.store.persistence.commit(session, null)
            val canonical = assertNotNull(fixture.database.sessionDao().findById("identity"))
            val before = fixture.store.catalog.state()
            fixture.store.close()
            val incompatible = TestProjector(policyVersion = 2, mode = SessionActivityMode.PREPARED, identityPrefix = "changed-")
            val replacement = JvmMagratheaRoom.open(fixture.path, reporter, incompatible)
            try {
                replacement.maintenance.rebuild(before.policy, before.generation)
                val issues = finish(replacement)
                assertEquals(SessionCatalogMaintenanceIssueKind.PROJECTION_FAILED, issues.single().kind)
                assertEquals(CatalogCoverage.PARTIAL, replacement.catalog.state().coverage)
                assertNull(replacement.catalog.sessionHeader(session.sessionId))
            } finally {
                replacement.close()
            }
            val raw = buildMagratheaRoomDatabase(Room.databaseBuilder<MagratheaDatabase>(name = fixture.path))
            try {
                assertEquals(canonical, raw.sessionDao().findById("identity"))
            } finally {
                raw.close()
            }
        }
    }

    private suspend fun finish(store: MagratheaRoomStoreHandle, limit: Int = 2): List<SessionCatalogMaintenanceIssue> {
        val issues = mutableListOf<SessionCatalogMaintenanceIssue>()
        repeat(30) {
            if (store.maintenance.status().complete) return issues
            issues += store.maintenance.runBatch(limit).issues
        }
        error("Bounded maintenance failed to finish")
    }

    private suspend fun withFixture(projector: SessionCatalogProjector, block: suspend (Fixture) -> Unit) {
        val directory = Files.createTempDirectory("room-catalog-maintenance-")
        val path = directory.resolve("store.db").toString()
        val database = buildMagratheaRoomDatabase(Room.databaseBuilder<MagratheaDatabase>(name = path))
        val store = MagratheaRoomStoreHandle(database, reporter, projector = projector)
        try {
            block(Fixture(directory, path, database, store))
        } finally {
            store.close()
            check(directory.toFile().deleteRecursively())
        }
    }

    private inner class Fixture(val directory: Path, val path: String, val database: MagratheaDatabase, val store: MagratheaRoomStoreHandle) {
        suspend fun seed(snapshot: AgentSessionSnapshot, checkpoint: AgentCheckpoint? = null) {
            database.sessionDao().upsert(AgentSessionEntity(sessionId = snapshot.sessionId.value, payload = codec.encode(snapshot), updatedAtEpochMs = snapshot.updatedAtEpochMs))
            if (checkpoint != null) database.checkpointDao().upsert(AgentCheckpointEntity(snapshot.sessionId.value, AgentCheckpointCodec().encode(checkpoint), checkpoint.turn))
        }
    }

    private fun snapshot(id: String, activity: String, time: Long): AgentSessionSnapshot {
        val original = roomTestSnapshot(id, updatedAtEpochMs = time)
        val message = AgentMessage(id = activity, role = MessageRole.USER, parts = listOf(TextPart(activity)), createdAtEpochMs = time)
        return original.copy(request = original.request.copy(messages = listOf(message)), state = original.state.copy(messages = listOf(message)))
    }

    private class TestProjector(
        policyVersion: Int = 1,
        private val searchPrefix: String = "",
        mode: SessionActivityMode = SessionActivityMode.CANONICAL_TIME,
        private val identityPrefix: String = "",
    ) : SessionCatalogProjector {
        override val policy = SessionCatalogPolicy("maintenance-test", policyVersion)
        override val activityMode = mode
        val calls = AtomicInteger()
        override fun normalizeSearch(text: String): String = searchPrefix + text.lowercase()
        override fun project(snapshot: AgentSessionSnapshot): SessionCatalogProjection {
            calls.incrementAndGet()
            val identity = snapshot.state.messages.lastOrNull()?.id
            return SessionCatalogProjection("Title $identity", activityIdentity = identity?.let { identityPrefix + it },
                activityAtEpochMs = snapshot.updatedAtEpochMs, normalizedFallbackLabel = normalizeSearch("Title $identity"))
        }
    }

    private class GatedProjector(private val delegate: SessionCatalogProjector) : SessionCatalogProjector by delegate {
        private val first = AtomicBoolean(true)
        private val entered = CountDownLatch(1)
        private val proceed = CountDownLatch(1)
        override fun project(snapshot: AgentSessionSnapshot): SessionCatalogProjection {
            if (first.compareAndSet(true, false)) {
                entered.countDown()
                check(proceed.await(10, TimeUnit.SECONDS)) { "Test did not release the projection gate" }
            }
            return delegate.project(snapshot)
        }
        suspend fun awaitEntered() = withContext(Dispatchers.IO) {
            check(entered.await(10, TimeUnit.SECONDS)) { "Projection did not start" }
        }
        fun release() = proceed.countDown()
    }
}
