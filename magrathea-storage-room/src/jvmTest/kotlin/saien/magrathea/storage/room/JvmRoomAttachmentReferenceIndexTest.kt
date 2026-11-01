package saien.magrathea.storage.room

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import saien.magrathea.catalog.CatalogCoverage
import saien.magrathea.catalog.SessionCatalogException
import saien.magrathea.catalog.SessionCatalogFailure
import saien.magrathea.catalog.SessionCatalogMaintenanceIssue
import saien.magrathea.core.AgentMessage
import saien.magrathea.core.AgentRunId
import saien.magrathea.core.AgentSessionId
import saien.magrathea.core.AttachmentPart
import saien.magrathea.core.BackgroundExecutionRecord
import saien.magrathea.core.BackgroundExecutionRecordCodec
import saien.magrathea.core.BackgroundExecutionState
import saien.magrathea.core.BackgroundOriginContext
import saien.magrathea.core.BackgroundOriginContextCodec
import saien.magrathea.core.BackgroundOutcome
import saien.magrathea.core.MessageRole
import saien.magrathea.core.ToolCallPart
import saien.magrathea.core.ToolExecutionRequest
import saien.magrathea.core.ToolExecutionResult
import saien.magrathea.core.ToolImageAttachmentReference
import saien.magrathea.core.ToolResultAudience
import saien.magrathea.core.ToolResultImageContent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JvmRoomAttachmentReferenceIndexTest {
    @Test
    fun independentSourcesRetainResultPreviewAndRequestReferencesThroughCanonicalAndBackgroundReplacement() = runTest {
        fixture { _, store, _ ->
            val base = roomTestSnapshot("session")
            val snapshot = base.copy(state = base.state.copy(messages = listOf(AgentMessage(role = MessageRole.USER,
                parts = listOf(AttachmentPart("attachment://canonical", "image/png"), AttachmentPart("attachment://shared", "image/png"))))))
            store.persistence.commit(snapshot, null)
            val first = background("session", "a", "attachment://image-a", "attachment://shared")
            val second = background("session", "b", "attachment://image-b", "attachment://shared")
            store.backgroundStore.upsert(first)
            store.backgroundStore.upsert(second)
            store.backgroundStore.putOrigin(BackgroundOriginContext(base.sessionId, base.runId, base.request.copy(messages = emptyList())))
            val candidates = setOf("attachment://canonical", "attachment://shared", "attachment://request", "attachment://image-a", "attachment://image-b", "attachment://preview")
            assertEquals(candidates, store.catalog.referencedUris(candidates).referencedUris)
            assertEquals(CatalogCoverage.COMPLETE, store.catalog.referencedUris(candidates).coverage)
            store.persistence.commit(base, null)
            assertFalse("attachment://canonical" in store.catalog.referencedUris(candidates).referencedUris)
            assertTrue("attachment://shared" in store.catalog.referencedUris(candidates).referencedUris)
            store.backgroundStore.delete(base.sessionId, first.executionId)
            assertFalse("attachment://image-a" in store.catalog.referencedUris(candidates).referencedUris)
            assertTrue("attachment://shared" in store.catalog.referencedUris(candidates).referencedUris)
            store.backgroundStore.upsert(second.copy(result = ToolExecutionResult(second.originCallId, second.toolName, JsonPrimitive("no images"))))
            assertFalse("attachment://image-b" in store.catalog.referencedUris(candidates).referencedUris)
            store.persistence.commit(snapshot, null)
            store.backgroundStore.clear()
            assertEquals(setOf("attachment://canonical", "attachment://shared"), store.catalog.referencedUris(candidates).referencedUris)
            assertEquals(CatalogCoverage.COMPLETE, store.catalog.referencedUris(candidates).coverage)
        }
    }

    @Test
    fun boundedLegacyBackfillRetainsUnknownBytesAndRebuildReindexesKnownSources() = runTest {
        fixture { database, store, _ ->
            val valid = background("a", "work", "attachment://legacy", "attachment://shared")
            database.backgroundExecutionDao().upsert(AgentBackgroundExecutionEntity(sessionId = "a", executionId = "work", envelope = BackgroundExecutionRecordCodec().encode(valid), updatedAtEpochMs = 1))
            database.backgroundExecutionDao().upsert(AgentBackgroundExecutionEntity(sessionId = "z", executionId = "unknown", envelope = "opaque-execution", updatedAtEpochMs = 1))
            val snapshot = roomTestSnapshot("a")
            val origin = BackgroundOriginContext(snapshot.sessionId, snapshot.runId, snapshot.request.copy(messages = emptyList()))
            database.backgroundOriginDao().upsert(AgentBackgroundOriginEntity("a", origin.runId.value, BackgroundOriginContextCodec().encode(origin)))
            database.backgroundOriginDao().upsert(AgentBackgroundOriginEntity("z", "unknown-run", "opaque-origin"))
            val first = store.maintenance.runBatch(1)
            assertEquals(1, first.indexed)
            assertEquals(0L, first.progress.processed)
            assertFalse(first.progress.complete)
            val issues = mutableListOf<SessionCatalogMaintenanceIssue>()
            repeat(10) { if (!store.maintenance.status().complete) issues += store.maintenance.runBatch(1).issues }
            assertTrue(store.maintenance.status().complete)
            assertEquals(2, issues.size)
            assertEquals(CatalogCoverage.PARTIAL, store.catalog.referencedUris(setOf("attachment://legacy")).coverage)
            assertEquals(setOf("attachment://legacy"), store.catalog.referencedUris(setOf("attachment://legacy")).referencedUris)
            assertEquals("opaque-execution", database.backgroundExecutionDao().findById("z", "unknown")?.envelope)
            assertEquals("opaque-origin", database.backgroundOriginDao().findById("z", "unknown-run")?.envelope)
            assertTrue(store.maintenance.runBatch(1).issues.isEmpty())
            store.backgroundStore.delete(AgentSessionId("z"), "unknown")
            store.backgroundStore.deleteOrigin(AgentSessionId("z"), AgentRunId("unknown-run"))
            assertEquals(CatalogCoverage.COMPLETE, store.catalog.referencedUris(emptySet()).coverage)
            val state = store.catalog.state()
            store.maintenance.rebuild(state.policy, state.generation)
            repeat(10) { if (!store.maintenance.status().complete) store.maintenance.runBatch(1) }
            assertTrue(store.maintenance.status().complete)
            assertEquals(CatalogCoverage.COMPLETE, store.catalog.referencedUris(setOf("attachment://legacy")).coverage)
            assertEquals(setOf("attachment://legacy"), store.catalog.referencedUris(setOf("attachment://legacy")).referencedUris)
        }
    }

    @Test
    fun referenceWriteFailureRollsBackTheBackgroundEnvelopeAndCatalogRevision() = runTest {
        fixture { database, store, path ->
            val original = background("session", "work", "attachment://old", "attachment://shared")
            store.backgroundStore.upsert(original)
            val oldEntity = database.backgroundExecutionDao().findById("session", "work")
            val state = store.catalog.state()
            sql(path, "CREATE TRIGGER fail_reference BEFORE INSERT ON session_attachment_refs BEGIN SELECT RAISE(ABORT, 'reference failure'); END")
            try {
                assertFailsWith<Exception> { store.backgroundStore.upsert(original.copy(result = ToolExecutionResult(
                    original.originCallId, original.toolName, JsonPrimitive("replacement")), settledAtEpochMs = 99)) }
            } finally {
                sql(path, "DROP TRIGGER fail_reference")
            }
            assertEquals(oldEntity, database.backgroundExecutionDao().findById("session", "work"))
            assertEquals(state, store.catalog.state())
            assertEquals(setOf("attachment://old"), store.catalog.referencedUris(setOf("attachment://old")).referencedUris)
        }
    }

    @Test
    fun oldOriginMaintenanceCannotAdvanceThePostClearGeneration() = runTest {
        fixture { database, store, _ ->
            database.backgroundOriginDao().upsert(AgentBackgroundOriginEntity("opaque", "run", "opaque-origin"))
            val before = store.catalog.state()
            store.persistence.clear()
            assertEquals(SessionCatalogFailure.STALE_MAINTENANCE, assertFailsWith<SessionCatalogException> {
                RoomAttachmentReferenceIndex(database).backfillOrigins(1, before.policy, before.generation)
            }.failure)
            assertNull(database.catalogDao().state("references.origins.generation"))
            assertTrue(store.maintenance.status().complete)
        }
    }

    private suspend fun fixture(block: suspend (MagratheaDatabase, MagratheaRoomStoreHandle, String) -> Unit) {
        val directory = Files.createTempDirectory("room-attachment-index-")
        val path = directory.resolve("store.db").toString()
        val database = buildMagratheaRoomDatabase(Room.databaseBuilder<MagratheaDatabase>(name = path))
        val store = MagratheaRoomStoreHandle(database, StoredRecordCorruptionReporter { error("Unexpected corruption callback") })
        try {
            block(database, store, path)
        } finally {
            store.close()
            check(directory.toFile().deleteRecursively())
        }
    }
    private fun background(id: String, execution: String, image: String, shared: String): BackgroundExecutionRecord {
        val sessionId = AgentSessionId(id)
        val runId = AgentRunId("run-$id")
        val call = ToolCallPart("call-$execution", "image", buildJsonObject { })
        val content = ToolResultImageContent(ToolImageAttachmentReference(image), ToolImageAttachmentReference("attachment://preview"),
            audiences = setOf(ToolResultAudience.MODEL))
        return BackgroundExecutionRecord(sessionId, execution, "handle-$execution", runId, call.toolCallId, call.toolName, "source", BackgroundExecutionState.TERMINAL,
            outcome = BackgroundOutcome.SUCCEEDED,
            result = ToolExecutionResult(call.toolCallId, call.toolName, JsonPrimitive("result"), content = listOf(content)), resultVersion = 1,
            executionRequest = ToolExecutionRequest(sessionId, runId, execution, AgentMessage(role = MessageRole.ASSISTANT,
                parts = listOf(call, AttachmentPart("attachment://request", "image/png"), AttachmentPart(shared, "image/png"))), call),
            effectiveTimeoutMillis = 1000, queuedAtEpochMs = 1, settledAtEpochMs = 2)
    }
    private fun sql(path: String, text: String) = BundledSQLiteDriver().open(path).use { connection ->
        connection.prepare("PRAGMA busy_timeout=5000").use { it.step() }
        connection.prepare(text).use { it.step() }
    }
}
