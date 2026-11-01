package saien.magrathea.catalog

import kotlinx.coroutines.test.runTest
import saien.magrathea.core.*
import kotlin.test.*

class SessionCatalogMigrationContractTest {
    @Test fun nameScanResumesCompletedPassSurvivesRebuildAndRestartInvalidatesOldTokens() = runTest {
        val store = InMemorySessionCatalogStore()
        val first = snapshot("a")
        store.persistence.commit(first, null)
        store.persistence.commit(snapshot("b"), null)
        val begun = store.migration.beginAttachmentNameScan("metadata", 1)
        val page = store.migration.attachmentNameScanPage(begun, 1)
        assertFalse(page.exhausted)
        assertEquals(SessionCatalogFailure.STALE_MIGRATION, assertFailsWith<SessionCatalogException> {
            store.migration.finishAttachmentNameScan(begun)
        }.failure)
        val patched = store.migration.patchAttachmentNames(page.entries.single(), listOf(SessionAttachmentName("attachment://unnamed", "photo.png")))
        val saved = store.migration.advanceAttachmentNameScan(begun, page.sources.single().copy(rowVersion = patched.rowVersion))
        assertEquals(saved, store.migration.beginAttachmentNameScan("metadata", 1))
        assertEquals(first.sessionId, saved.afterSessionId)
        val tail = store.migration.attachmentNameScanPage(saved, 1)
        assertEquals(AgentSessionId("b"), tail.sources.single().identity?.sessionId)
        val attempted = store.migration.advanceAttachmentNameScan(saved, tail.sources.single(), SessionAttachmentNameScanOutcome.FAILED)
        val completed = store.migration.finishAttachmentNameScan(attempted)
        assertEquals(2L, completed.processed)
        assertEquals(1L, completed.failed)
        store.rebuild()
        assertEquals(completed, store.migration.beginAttachmentNameScan("metadata", 1))
        assertTrue(store.migration.attachmentNameScanPage(completed).sources.isEmpty())
        val restarted = store.migration.restartAttachmentNameScan(completed)
        assertEquals(completed.token.generation + 1, restarted.token.generation)
        assertEquals(0L, restarted.processed)
        assertNull(restarted.cursor)
        assertEquals(SessionCatalogFailure.STALE_MIGRATION, assertFailsWith<SessionCatalogException> {
            store.migration.attachmentNameScanPage(completed)
        }.failure)
        store.persistence.clear()
        assertEquals(SessionCatalogFailure.STALE_MIGRATION, assertFailsWith<SessionCatalogException> {
            store.migration.finishAttachmentNameScan(restarted)
        }.failure)
        val empty = store.migration.beginAttachmentNameScan("metadata", 1)
        assertNotEquals(restarted.token.lifecycle, empty.token.lifecycle)
        assertTrue(store.migration.finishAttachmentNameScan(empty).complete)
        store.close()
    }

    @Test fun nameScanRequiresVersionExceptManagedSkipAndNeverSkipsEarlierOrReusedRows() = runTest {
        val store = InMemorySessionCatalogStore()
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
        store.close()
    }

    @Test fun clearBeforeFirstImportIsReadableWithoutInventingAReceipt() = runTest {
        val store = InMemorySessionCatalogStore()
        assertTrue(store.migration.organizationImportAllowed())
        assertNull(store.migration.organizationImportStatus())
        store.persistence.clear()
        assertFalse(store.migration.organizationImportAllowed())
        assertNull(store.migration.organizationImportStatus())
        assertEquals(SessionCatalogFailure.STALE_MIGRATION, assertFailsWith<SessionCatalogException> {
            store.migration.beginOrganizationImport("old-host-file", "old-fingerprint")
        }.failure)
        store.catalog.createCollection(SessionCollection("new", "New", "new", "new"))
        assertNotNull(store.catalog.collection("new"))
        store.close()
        assertEquals(SessionCatalogFailure.CLOSED, assertFailsWith<SessionCatalogException> { store.migration.organizationImportAllowed() }.failure)
    }

    @Test fun missingNameUrisHaveAnExplicitBoundedContinuationAcrossUnresolvablePages() = runTest {
        val store = InMemorySessionCatalogStore()
        val source = snapshot()
        val parts = (0..54).map { AttachmentPart("attachment://${it.toString().padStart(3, '0')}", "image/png") }
        val message = AgentMessage("many", MessageRole.USER, parts)
        val snapshot = source.copy(request = source.request.copy(messages = listOf(message)), state = source.state.copy(messages = listOf(message)))
        store.persistence.commit(snapshot, null)
        val first = assertNotNull(store.migration.attachmentNameTarget(snapshot.sessionId))
        assertEquals(50, first.missingUris.size)
        assertNotNull(first.nextUri)
        // The first page has no trusted host metadata; callers still advance its URI cursor.
        val last = assertNotNull(store.migration.attachmentNameTarget(snapshot.sessionId, first.nextUri))
        assertEquals(5, last.missingUris.size)
        assertNull(last.nextUri)
        store.migration.patchAttachmentNames(last, last.missingUris.map { SessionAttachmentName(it, "known.png") })
        assertTrue(assertNotNull(store.migration.attachmentNameTarget(snapshot.sessionId, first.nextUri)).missingUris.isEmpty())
        assertEquals(50, store.migration.attachmentNameTargets(limit = 1).entries.single().missingUris.size)
        store.close()
    }

    @Test fun importIsBoundedIdempotentAndNeverBindsFreshIncarnations() = runTest {
        val store = InMemorySessionCatalogStore()
        val session = snapshot()
        store.persistence.commit(session, null)
        val source = store.migration.beginOrganizationImport("legacy-host", "fingerprint")
        val folder = SessionCollection("folder", "Folder", "folder", "folder")
        val assignment = LegacySessionOrganization(session.sessionId, SessionOrganization("Old title", "folder"))
        val receipt = store.migration.importOrganizationBatch(source.token, 0, listOf(folder), listOf(assignment), complete = true)
        assertEquals(0, receipt.imported)
        assertEquals(1, receipt.skipped)
        assertEquals(receipt, store.migration.importOrganizationBatch(source.token, 0, listOf(folder), listOf(assignment), true))
        assertNull(store.catalog.sessionHeader(session.sessionId)?.collectionId)
        store.persistence.clear()
        assertEquals(receipt, store.migration.organizationImportStatus())
        assertEquals(receipt, store.migration.beginOrganizationImport("legacy-host", "fingerprint"))
        assertEquals(SessionCatalogFailure.MIGRATION_CONFLICT, assertFailsWith<SessionCatalogException> {
            store.migration.beginOrganizationImport("legacy-host", "changed")
        }.failure)
        assertFailsWith<IllegalArgumentException> {
            store.migration.importOrganizationBatch(source.token, 0, List(51) { SessionCollection("f$it", "f$it", "$it", "$it") }, emptyList())
        }
        store.close()
    }

    @Test fun clearInvalidatesImportAndConflictRollsBackTheWholeBatch() = runTest {
        val store = InMemorySessionCatalogStore()
        val source = store.migration.beginOrganizationImport("source", "hash")
        store.catalog.createCollection(SessionCollection("exists", "Existing", "a", "same"))
        assertEquals(SessionCatalogFailure.COLLECTION_CONFLICT, assertFailsWith<SessionCatalogException> {
            store.migration.importOrganizationBatch(source.token, 0,
                listOf(SessionCollection("new", "New", "b", "new"), SessionCollection("conflict", "Conflict", "c", "same")), emptyList())
        }.failure)
        assertNull(store.catalog.collection("new"))
        assertEquals(0, store.migration.organizationImportStatus()?.nextBatch)
        store.persistence.clear()
        assertTrue(store.migration.organizationImportStatus()?.invalidated == true)
        assertEquals(SessionCatalogFailure.STALE_MIGRATION, assertFailsWith<SessionCatalogException> {
            store.migration.importOrganizationBatch(source.token, 0, emptyList(), emptyList(), true)
        }.failure)
        store.close()
    }

    @Test fun namesAreCanonicalAndBackupSurvivesCommitRebuildAndExplicitRestore() = runTest {
        val store = InMemorySessionCatalogStore()
        val snapshot = snapshot()
        val checkpoint = AgentCheckpoint(snapshot.sessionId, snapshot.runId, AgentResumeCursor(0, AgentResumePhase.MODEL_PENDING), snapshot.state)
        store.persistence.commit(snapshot, checkpoint)
        val target = assertNotNull(store.migration.attachmentNameTarget(snapshot.sessionId))
        assertEquals(setOf("attachment://unnamed"), target.missingUris)
        val patch = store.migration.patchAttachmentNames(target, listOf(SessionAttachmentName("attachment://unnamed", "photo.png"), SessionAttachmentName("attachment://named", "overwrite.png")))
        assertTrue(patch.changed)
        val updated = assertNotNull(store.persistence.load(snapshot.sessionId))
        assertEquals(listOf("photo.png", "original.png"), updated.snapshot.request.messages.single().parts.filterIsInstance<AttachmentPart>().map { it.fileName })
        assertEquals("photo.png", updated.checkpoint?.state?.messages?.single()?.parts?.filterIsInstance<AttachmentPart>()?.first()?.fileName)
        val backup = assertNotNull(store.migration.attachmentNameBackup(target.identity))
        assertEquals(snapshot, AgentSessionSnapshotCodec().decodeResult(backup.snapshotPayload).value)
        store.persistence.commit(updated.snapshot, updated.checkpoint)
        store.rebuild()
        assertEquals(backup, store.migration.attachmentNameBackup(target.identity))
        assertTrue(assertNotNull(store.migration.attachmentNameTarget(snapshot.sessionId)).missingUris.isEmpty())
        store.persistence.commit(updated.snapshot.copy(request = updated.snapshot.request.copy(messages = emptyList()),
            state = updated.snapshot.state.copy(messages = emptyList())), null)
        store.rebuild()
        assertEquals(setOf("attachment://unnamed"), store.catalog.referencedUris(setOf("attachment://unnamed")).referencedUris)
        assertEquals(SessionCatalogFailure.STALE_MIGRATION, assertFailsWith<SessionCatalogException> {
            store.migration.patchAttachmentNames(target, listOf(SessionAttachmentName("attachment://unnamed", "old.png")))
        }.failure)
        store.migration.restoreAttachmentNameBackup(assertNotNull(store.migration.attachmentNameTarget(snapshot.sessionId)))
        assertEquals(snapshot, store.persistence.load(snapshot.sessionId)?.snapshot)
        store.persistence.deleteSession(snapshot.sessionId)
        assertNull(store.migration.attachmentNameBackup(target.identity))
        store.close()
    }

    private fun snapshot(name: String = "session"): AgentSessionSnapshot {
        val id = AgentSessionId(name)
        val messages = listOf(AgentMessage("message", MessageRole.USER, listOf(
            AttachmentPart("attachment://unnamed", "image/png"), AttachmentPart("attachment://named", "image/png", "original.png"))))
        return AgentSessionSnapshot(id, AgentRunId("run"), AgentRequest(sessionId = id, messages = messages, model = ModelDescriptor("provider", "model", "Model")),
            AgentStateSnapshot(messages = messages), updatedAtEpochMs = 123)
    }
}
