package saien.magrathea.storage.room

import androidx.room.Room
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import saien.magrathea.catalog.LegacySessionOrganization
import saien.magrathea.catalog.SessionAttachmentName
import saien.magrathea.catalog.SessionAttachmentNameScanOutcome
import saien.magrathea.catalog.SessionCatalogException
import saien.magrathea.catalog.SessionCatalogFailure
import saien.magrathea.catalog.SessionCatalogMaintenanceIssueKind
import saien.magrathea.catalog.SessionCatalogPolicy
import saien.magrathea.catalog.SessionCatalogProjection
import saien.magrathea.catalog.SessionCatalogProjector
import saien.magrathea.catalog.SessionCollection
import saien.magrathea.catalog.SessionIdentity
import saien.magrathea.catalog.SessionOrganization
import saien.magrathea.core.AgentCheckpoint
import saien.magrathea.core.AgentCheckpointCodec
import saien.magrathea.core.AgentMessage
import saien.magrathea.core.AgentResumeCursor
import saien.magrathea.core.AgentResumePhase
import saien.magrathea.core.AgentSessionId
import saien.magrathea.core.AgentSessionSnapshot
import saien.magrathea.core.AgentSessionSnapshotCodec
import saien.magrathea.core.AttachmentPart
import saien.magrathea.core.MessageRole
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JvmRoomCatalogDataMigrationTest {
    private val reporter = StoredRecordCorruptionReporter { error("Unexpected corruption callback") }

    @Test
    fun nameScanUsesSqlitePhysicalOrderForSupplementaryUnicodeSessionIds() = runTest {
        fixture { _, store, _ ->
            val bmp = roomTestSnapshot("\uE000")
            val supplementary = roomTestSnapshot("\uD800\uDC00")
            store.persistence.commit(bmp, null)
            store.persistence.commit(supplementary, null)
            var progress = store.migration.beginAttachmentNameScan("metadata", 1)
            val sources = store.migration.attachmentNameScanPage(progress).sources
            assertEquals(listOf(bmp.sessionId, supplementary.sessionId), sources.map { it.identity?.sessionId })
            assertEquals(SessionCatalogFailure.STALE_MIGRATION, assertFailsWith<SessionCatalogException> {
                store.migration.advanceAttachmentNameScan(progress, sources[1])
            }.failure)
            progress = store.migration.advanceAttachmentNameScan(progress, sources[0])
            progress = store.migration.advanceAttachmentNameScan(progress, sources[1])
            assertTrue(store.migration.finishAttachmentNameScan(progress).complete)
        }
    }

    @Test
    fun nameScanResumesAfterReopenAndKeepsCompletedFailuresAcrossCatalogRebuild() = runTest {
        fixture { database, store, path ->
            val snapshot = snapshot()
            val original = AgentSessionSnapshotCodec().encode(snapshot)
            database.sessionDao().upsert(AgentSessionEntity(sessionId = snapshot.sessionId.value, payload = original, updatedAtEpochMs = snapshot.updatedAtEpochMs))
            database.sessionDao().upsert(AgentSessionEntity(sessionId = "z-bad", payload = "opaque unchanged scan source", updatedAtEpochMs = 1))
            val begun = store.migration.beginAttachmentNameScan("local-metadata", 1)
            val page = store.migration.attachmentNameScanPage(begun, 1)
            assertFalse(page.exhausted)
            assertEquals(SessionCatalogFailure.STALE_MIGRATION, assertFailsWith<SessionCatalogException> {
                store.migration.finishAttachmentNameScan(begun)
            }.failure)
            val result = store.migration.patchAttachmentNames(page.entries.single(), listOf(SessionAttachmentName("attachment://photo", "photo.png")))
            val saved = store.migration.advanceAttachmentNameScan(begun, page.sources.single().copy(rowVersion = result.rowVersion))
            store.close()
            val reopened = JvmMagratheaRoom.open(path, reporter, NameProjector())
            try {
                assertEquals(saved, reopened.migration.beginAttachmentNameScan("local-metadata", 1))
                val tail = reopened.migration.attachmentNameScanPage(saved, 1)
                assertEquals(AgentSessionId("z-bad"), tail.sources.single().identity?.sessionId)
                assertTrue(tail.entries.isEmpty())
                assertEquals(SessionCatalogMaintenanceIssueKind.UNREADABLE, tail.issues.single().kind)
                val attempted = reopened.migration.advanceAttachmentNameScan(saved, tail.sources.single(), SessionAttachmentNameScanOutcome.FAILED)
                val completed = reopened.migration.finishAttachmentNameScan(attempted)
                assertEquals(2L, completed.processed)
                assertEquals(1L, completed.failed)
                val state = reopened.catalog.state()
                reopened.maintenance.rebuild(state.policy, state.generation)
                assertEquals(completed, reopened.migration.beginAttachmentNameScan("local-metadata", 1))
                assertTrue(reopened.migration.attachmentNameScanPage(completed).sources.isEmpty())
                val restarted = reopened.migration.restartAttachmentNameScan(completed)
                assertEquals(completed.token.generation + 1, restarted.token.generation)
                assertNull(restarted.cursor)
                assertEquals(SessionCatalogFailure.STALE_MIGRATION, assertFailsWith<SessionCatalogException> {
                    reopened.migration.attachmentNameScanPage(completed)
                }.failure)
                reopened.persistence.clear()
                assertEquals(SessionCatalogFailure.STALE_MIGRATION, assertFailsWith<SessionCatalogException> {
                    reopened.migration.finishAttachmentNameScan(restarted)
                }.failure)
                val empty = reopened.migration.beginAttachmentNameScan("local-metadata", 1)
                assertNotEquals(restarted.token.lifecycle, empty.token.lifecycle)
                assertTrue(reopened.migration.finishAttachmentNameScan(empty).complete)
            } finally {
                reopened.close()
            }
        }
    }

    @Test
    fun nameScanVersionCasAllowsManagedProgressButRejectsReusedIncarnationsAndSkippingRows() = runTest {
        fixture { _, store, _ ->
            val a = roomTestSnapshot("a")
            val b = roomTestSnapshot("b")
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
        }
    }

    @Test
    fun clearBeforeFirstImportKeepsItsCutoffAcrossReopenWithoutReadingLegacyBytes() = runTest {
        fixture { _, store, path ->
            assertTrue(store.migration.organizationImportAllowed())
            assertNull(store.migration.organizationImportStatus())
            store.persistence.clear()
            assertFalse(store.migration.organizationImportAllowed())
            assertNull(store.migration.organizationImportStatus())
            store.close()
            val reopened = JvmMagratheaRoom.open(path, reporter, NameProjector())
            try {
                assertFalse(reopened.migration.organizationImportAllowed())
                assertNull(reopened.migration.organizationImportStatus())
                assertEquals(SessionCatalogFailure.STALE_MIGRATION, assertFailsWith<SessionCatalogException> {
                    reopened.migration.beginOrganizationImport("legacy-json", "old-fingerprint")
                }.failure)
                reopened.catalog.createCollection(SessionCollection("new", "New", "new", "new"))
                assertNotNull(reopened.catalog.collection("new"))
            } finally {
                reopened.close()
            }
        }
    }

    @Test
    fun legacyImportKeepsOpaqueRowsSkipsReusedIdsAndResumesWithDurableReceipts() = runTest {
        fixture { database, store, path ->
            database.sessionDao().upsert(AgentSessionEntity(sessionId = "opaque", payload = "original unknown bytes", updatedAtEpochMs = 1))
            database.sessionDao().upsert(AgentSessionEntity(sessionId = "reused", payload = "deleted legacy bytes", updatedAtEpochMs = 1))
            store.persistence.deleteSession(AgentSessionId("reused"))
            store.persistence.commit(roomTestSnapshot("reused"), null)
            val receipt = store.migration.beginOrganizationImport("host-json-v1", "sha256-of-frozen-source")
            val folder = SessionCollection("folder", "Folder", "folder", "folder")
            val first = store.migration.importOrganizationBatch(receipt.token, 0, listOf(folder), emptyList())
            assertEquals(first, store.migration.importOrganizationBatch(receipt.token, 0, listOf(folder), emptyList()))
            assertNull(store.catalog.sessionHeader(AgentSessionId("opaque")))
            store.close()
            val reopened = JvmMagratheaRoom.open(path, reporter, NameProjector())
            try {
                assertEquals(first, reopened.migration.organizationImportStatus())
                val rows = listOf("opaque", "reused", "deleted").map { LegacySessionOrganization(AgentSessionId(it), SessionOrganization("Saved title", "folder", 44)) }
                val completed = reopened.migration.importOrganizationBatch(first.token, 1, emptyList(), rows, true)
                assertEquals(1L, completed.imported)
                assertEquals(2L, completed.skipped)
                assertEquals(SessionOrganization("Saved title", "folder", 44), reopened.catalog.organization(SessionIdentity(AgentSessionId("opaque"), "legacy")))
                assertNull(reopened.catalog.sessionHeader(AgentSessionId("opaque")))
                assertNull(reopened.catalog.sessionHeader(AgentSessionId("reused"))?.collectionId)
                assertEquals(completed, reopened.migration.importOrganizationBatch(first.token, 1, emptyList(), rows, true))
                reopened.persistence.clear()
                assertEquals(completed, reopened.migration.beginOrganizationImport("host-json-v1", "sha256-of-frozen-source"))
                assertTrue(reopened.migration.organizationImportStatus()?.complete == true)
            } finally {
                reopened.close()
            }
        }
    }

    @Test
    fun conflictRollsBackCollectionsAndReceiptAndClearRejectsLateImport() = runTest {
        fixture { _, store, _ ->
            val receipt = store.migration.beginOrganizationImport("source", "fingerprint")
            store.catalog.createCollection(SessionCollection("existing", "Existing", "e", "unique"))
            assertEquals(SessionCatalogFailure.COLLECTION_CONFLICT, assertFailsWith<SessionCatalogException> {
                store.migration.importOrganizationBatch(receipt.token, 0,
                    listOf(SessionCollection("first", "First", "f", "first"), SessionCollection("conflict", "Conflict", "c", "unique")), emptyList())
            }.failure)
            assertNull(store.catalog.collection("first"))
            assertEquals(0L, store.migration.organizationImportStatus()?.nextBatch)
            store.persistence.clear()
            assertTrue(store.migration.organizationImportStatus()?.invalidated == true)
            assertEquals(SessionCatalogFailure.STALE_MIGRATION, assertFailsWith<SessionCatalogException> {
                store.migration.importOrganizationBatch(receipt.token, 0, emptyList(), emptyList(), true)
            }.failure)
        }
    }

    @Test
    fun canonicalNamePatchAndOriginalBytesSurviveCommitReopenAndRebuild() = runTest {
        fixture { database, store, path ->
            val snapshot = snapshot()
            val checkpoint = checkpoint(snapshot)
            val original = AgentSessionSnapshotCodec().encode(snapshot)
            val checkpointOriginal = AgentCheckpointCodec().encode(checkpoint)
            database.sessionDao().upsert(AgentSessionEntity(sessionId = snapshot.sessionId.value, payload = original, updatedAtEpochMs = snapshot.updatedAtEpochMs))
            database.checkpointDao().upsert(AgentCheckpointEntity(snapshot.sessionId.value, checkpointOriginal, checkpoint.turn))
            database.sessionDao().upsert(AgentSessionEntity(sessionId = "z-bad", payload = "opaque-name-source", updatedAtEpochMs = 1))
            val firstPage = store.migration.attachmentNameTargets(limit = 1)
            assertFalse(firstPage.exhausted)
            val target = firstPage.entries.single()
            val patch = store.migration.patchAttachmentNames(target, listOf(SessionAttachmentName("attachment://photo", "photo.png")))
            assertEquals(target.rowVersion + 1, patch.rowVersion)
            assertEquals("photo.png", store.catalog.sessionHeader(snapshot.sessionId)?.label)
            val backup = assertNotNull(store.migration.attachmentNameBackup(target.identity))
            assertEquals(original, backup.snapshotPayload)
            assertEquals(checkpointOriginal, backup.checkpointPayload)
            val record = assertNotNull(store.persistence.load(snapshot.sessionId))
            assertEquals("photo.png", record.checkpoint?.state?.messages?.single()?.parts?.filterIsInstance<AttachmentPart>()?.single()?.fileName)
            store.persistence.commit(record.snapshot, record.checkpoint)
            val state = store.catalog.state()
            store.maintenance.rebuild(state.policy, state.generation)
            while (!store.maintenance.status().complete) store.maintenance.runBatch(1)
            assertEquals("photo.png", store.catalog.sessionHeader(snapshot.sessionId)?.label)
            assertEquals(backup, store.migration.attachmentNameBackup(target.identity))
            assertEquals(SessionCatalogMaintenanceIssueKind.UNREADABLE, store.migration.attachmentNameTargets(firstPage.nextSessionId, 1).issues.single().kind)
            store.persistence.commit(record.snapshot.copy(request = record.snapshot.request.copy(messages = emptyList()),
                state = record.snapshot.state.copy(messages = emptyList())), null)
            val rebuild = store.catalog.state()
            store.maintenance.rebuild(rebuild.policy, rebuild.generation)
            while (!store.maintenance.status().complete) store.maintenance.runBatch(1)
            assertEquals(setOf("attachment://photo"), store.catalog.referencedUris(setOf("attachment://photo")).referencedUris)
            store.close()
            val reopened = JvmMagratheaRoom.open(path, reporter, NameProjector())
            try {
                assertEquals(backup, reopened.migration.attachmentNameBackup(target.identity))
                val current = assertNotNull(reopened.migration.attachmentNameTarget(snapshot.sessionId))
                assertTrue(current.missingUris.isEmpty())
                assertEquals(SessionCatalogFailure.STALE_MIGRATION, assertFailsWith<SessionCatalogException> {
                    reopened.migration.patchAttachmentNames(target, listOf(SessionAttachmentName("attachment://photo", "stale.png")))
                }.failure)
                reopened.migration.restoreAttachmentNameBackup(current)
                assertEquals(snapshot, reopened.persistence.load(snapshot.sessionId)?.snapshot)
            } finally {
                reopened.close()
            }
        }
    }

    @Test
    fun checkpointCasRejectsAConcurrentCheckpointWithoutChangingSnapshotOrBackup() = runTest {
        val gate = GatedProjector()
        fixture(gate) { database, store, _ ->
            val snapshot = snapshot()
            val checkpoint = checkpoint(snapshot)
            val original = AgentSessionSnapshotCodec().encode(snapshot)
            database.sessionDao().upsert(AgentSessionEntity(sessionId = snapshot.sessionId.value, payload = original, updatedAtEpochMs = snapshot.updatedAtEpochMs))
            database.checkpointDao().upsert(AgentCheckpointEntity(snapshot.sessionId.value, AgentCheckpointCodec().encode(checkpoint), checkpoint.turn))
            val target = assertNotNull(store.migration.attachmentNameTarget(snapshot.sessionId))
            val pending = async(Dispatchers.Default) {
                assertFailsWith<SessionCatalogException> { store.migration.patchAttachmentNames(target, listOf(SessionAttachmentName("attachment://photo", "photo.png"))) }
            }
            withContext(Dispatchers.IO) { assertTrue(gate.entered.await(10, TimeUnit.SECONDS)) }
            try {
                val replacement = checkpoint.copy(state = checkpoint.state.copy(messages = emptyList()))
                database.checkpointDao().upsert(AgentCheckpointEntity(snapshot.sessionId.value, AgentCheckpointCodec().encode(replacement), replacement.turn))
            } finally {
                gate.proceed.countDown()
            }
            assertEquals(SessionCatalogFailure.STALE_MIGRATION, pending.await().failure)
            assertEquals(original, database.sessionDao().findById(snapshot.sessionId.value)?.payload)
            assertNull(store.migration.attachmentNameBackup(target.identity))
        }
    }

    private suspend fun fixture(projector: SessionCatalogProjector = NameProjector(), block: suspend (MagratheaDatabase, MagratheaRoomStoreHandle, String) -> Unit) {
        val directory = Files.createTempDirectory("room-data-migration-")
        val path = directory.resolve("catalog.db").toString()
        val database = buildMagratheaRoomDatabase(Room.databaseBuilder<MagratheaDatabase>(name = path))
        val store = MagratheaRoomStoreHandle(database, reporter, projector = projector)
        try {
            block(database, store, path)
        } finally {
            store.close()
            check(directory.toFile().deleteRecursively())
        }
    }
    private fun snapshot(): AgentSessionSnapshot {
        val snapshot = roomTestSnapshot("a-photo", updatedAtEpochMs = 12)
        val message = AgentMessage("input", MessageRole.USER, listOf(AttachmentPart("attachment://photo", "image/png")), createdAtEpochMs = 1)
        return snapshot.copy(request = snapshot.request.copy(messages = listOf(message)), state = snapshot.state.copy(messages = listOf(message)))
    }
    private fun checkpoint(snapshot: AgentSessionSnapshot) = AgentCheckpoint(snapshot.sessionId, snapshot.runId,
        AgentResumeCursor(snapshot.state.turn, AgentResumePhase.MODEL_PENDING), snapshot.state)
    private open class NameProjector : SessionCatalogProjector {
        override val policy = SessionCatalogPolicy("name-migration-test", 1)
        override fun normalizeSearch(text: String) = text.lowercase()
        override fun project(snapshot: AgentSessionSnapshot): SessionCatalogProjection {
            val label = snapshot.state.messages.flatMap { it.parts }.filterIsInstance<AttachmentPart>().firstOrNull()?.fileName ?: "unnamed"
            return SessionCatalogProjection(label, activityAtEpochMs = snapshot.updatedAtEpochMs, normalizedFallbackLabel = label.lowercase())
        }
    }
    private class GatedProjector : NameProjector() {
        val entered = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        override fun project(snapshot: AgentSessionSnapshot): SessionCatalogProjection {
            entered.countDown()
            check(proceed.await(10, TimeUnit.SECONDS))
            return super.project(snapshot)
        }
    }
}
