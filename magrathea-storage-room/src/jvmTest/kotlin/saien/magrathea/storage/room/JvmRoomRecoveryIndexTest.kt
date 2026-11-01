package saien.magrathea.storage.room

import androidx.room.Room
import androidx.room.immediateTransaction
import androidx.room.useWriterConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import saien.magrathea.catalog.CatalogCoverage
import saien.magrathea.catalog.SessionCatalogException
import saien.magrathea.catalog.SessionCatalogFailure
import saien.magrathea.catalog.SessionCatalogMaintenanceIssueKind
import saien.magrathea.catalog.SessionRecoveryReason
import saien.magrathea.core.AgentMessage
import saien.magrathea.core.AgentRunId
import saien.magrathea.core.AgentSessionId
import saien.magrathea.core.AgentSessionSnapshot
import saien.magrathea.core.AgentStatus
import saien.magrathea.core.BackgroundExecutionRecord
import saien.magrathea.core.BackgroundExecutionRecordCodec
import saien.magrathea.core.BackgroundExecutionState
import saien.magrathea.core.BackgroundOriginContext
import saien.magrathea.core.BackgroundOutcome
import saien.magrathea.core.MessageRole
import saien.magrathea.core.RuntimeNoticeDelivery
import saien.magrathea.core.RuntimeNoticeEntry
import saien.magrathea.core.RuntimeNoticePart
import saien.magrathea.core.StopReason
import saien.magrathea.core.TextPart
import saien.magrathea.core.ToolCallPart
import saien.magrathea.core.ToolExecutionRequest
import saien.magrathea.core.ToolExecutionResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JvmRoomRecoveryIndexTest {
    @Test
    fun completedSessionWithBackgroundWorkLeavesTheIndexOnlyAfterCurrentResultIsAcknowledged() = runTest {
        withStore { store, _, _ ->
            val original = completed("session")
            store.persistence.commit(original, null)
            val pending = background("session", "work", BackgroundExecutionState.RUNNING)
            store.backgroundStore.upsert(pending)
            assertReasons(store, "session", SessionRecoveryReason.BACKGROUND_PENDING)

            val terminal = terminal(pending)
            store.backgroundStore.upsert(terminal)
            assertReasons(store, "session", SessionRecoveryReason.BACKGROUND_UNACKNOWLEDGED)
            val acknowledged = acknowledge(original, terminal)
            store.persistence.commit(acknowledged, null)
            assertTrue(store.catalog.pageRecoveryCandidates(limit = 10).entries.isEmpty())

            // Independent background writes must retain the acknowledgement in current history.
            store.backgroundStore.upsert(terminal.copy(settledAtEpochMs = 9))
            assertTrue(store.catalog.pageRecoveryCandidates(limit = 10).entries.isEmpty())
            val newer = terminal.copy(resultVersion = terminal.resultVersion + 1)
            store.backgroundStore.upsert(newer)
            assertReasons(store, "session", SessionRecoveryReason.BACKGROUND_UNACKNOWLEDGED)
            store.persistence.commit(acknowledge(original, newer), null)
            assertTrue(store.catalog.pageRecoveryCandidates(limit = 10).entries.isEmpty())

            // Regeneration can remove the delivery/reply. Acknowledgement is not monotonic.
            store.persistence.commit(original.copy(updatedAtEpochMs = 10), null)
            assertReasons(store, "session", SessionRecoveryReason.BACKGROUND_UNACKNOWLEDGED)
        }
    }

    @Test
    fun checkpointAndBackgroundOnlyCandidatesPageWithoutReadingCanonicalEnvelopes() = runTest {
        withStore { store, database, path ->
            val original = completed("a-checkpoint")
            store.persistence.commit(original, roomTestCheckpoint(original, 1))
            store.backgroundStore.upsert(background("b-background-only", "work", BackgroundExecutionState.QUEUED))
            val canonical = assertNotNull(database.sessionDao().findById(original.sessionId.value))
            database.sessionDao().upsert(canonical.copy(payload = "unreadable canonical envelope"))
            execute(path, "UPDATE agent_background_executions SET envelope = 'unreadable background envelope'")

            val first = store.catalog.pageRecoveryCandidates(limit = 1)
            assertEquals(listOf("a-checkpoint"), first.entries.map { it.sessionId.value })
            assertEquals(setOf(SessionRecoveryReason.CHECKPOINT), first.entries.single().reasons)
            assertNotNull(first.entries.single().identity)
            val second = store.catalog.pageRecoveryCandidates(assertNotNull(first.nextCursor), 1)
            assertEquals(listOf("b-background-only"), second.entries.map { it.sessionId.value })
            assertEquals(setOf(SessionRecoveryReason.BACKGROUND_PENDING), second.entries.single().reasons)
            assertNull(second.entries.single().identity)
            assertTrue(second.exhausted)
            assertEquals(CatalogCoverage.COMPLETE, second.coverage)

            store.persistence.clear()
            assertTrue(store.catalog.pageRecoveryCandidates(limit = 10).entries.isEmpty())
            assertEquals(SessionCatalogFailure.INVALID_CURSOR,
                assertFailsWith<SessionCatalogException> { store.catalog.pageRecoveryCandidates(first.nextCursor, 1) }.failure)
        }
    }

    @Test
    fun legacyBackgroundBackfillIsBoundedAndDoesNotLoseUnknownSources() = runTest {
        withStore { store, database, _ ->
            val value = terminal(background("b-known", "work", BackgroundExecutionState.RUNNING))
            store.persistence.commit(acknowledge(completed("b-known"), value), null)
            database.backgroundExecutionDao().upsert(AgentBackgroundExecutionEntity("a-bad", "bad", "opaque unknown schema", 1))
            database.backgroundExecutionDao().upsert(AgentBackgroundExecutionEntity("b-known", "work", BackgroundExecutionRecordCodec().encode(value), 1))
            val state = store.catalog.state()
            val index = RoomRecoveryIndex(database)
            val before = store.catalog.pageRecoveryCandidates(limit = 10)
            assertEquals(CatalogCoverage.INDEXING, before.coverage)
            assertEquals(setOf("a-bad", "b-known"), before.entries.map { it.sessionId.value }.toSet())

            val first = index.backfillBackgroundBatch(1, state.policy, state.generation)
            assertEquals(1, first.processed)
            assertEquals(0, first.indexed)
            assertEquals(SessionCatalogMaintenanceIssueKind.UNREADABLE, first.issues.single().kind)
            assertFalse(first.complete)
            val second = index.backfillBackgroundBatch(1, state.policy, state.generation)
            assertEquals(1, second.processed)
            assertEquals(1, second.indexed)
            assertTrue(second.complete)
            val page = store.catalog.pageRecoveryCandidates(limit = 10)
            assertEquals(listOf("a-bad"), page.entries.map { it.sessionId.value })
            assertEquals(setOf(SessionRecoveryReason.UNKNOWN_SOURCE), page.entries.single().reasons)
            assertEquals(CatalogCoverage.PARTIAL, page.coverage)
            assertEquals("opaque unknown schema", database.backgroundExecutionDao().findById("a-bad", "bad")?.envelope)
        }
    }

    @Test
    fun snapshotAcknowledgementFailureRollsBackBothCanonicalAndRecoveryFacts() = runTest {
        withStore { store, _, path ->
            val original = completed("rollback")
            val value = terminal(background("rollback", "work", BackgroundExecutionState.RUNNING))
            store.persistence.commit(original, null)
            store.backgroundStore.upsert(value)
            val before = store.catalog.state()
            execute(path, "CREATE TRIGGER fail_ack BEFORE UPDATE OF catalogAcknowledged ON agent_background_executions BEGIN SELECT RAISE(ABORT, 'injected'); END")
            assertFailsWith<Exception> { store.persistence.commit(acknowledge(original, value), null) }
            assertEquals(original, store.persistence.load(original.sessionId)?.snapshot)
            assertEquals(before, store.catalog.state())
            assertReasons(store, "rollback", SessionRecoveryReason.BACKGROUND_UNACKNOWLEDGED)
            execute(path, "DROP TRIGGER fail_ack")
            store.persistence.commit(acknowledge(original, value), null)
            assertTrue(store.catalog.pageRecoveryCandidates(limit = 10).entries.isEmpty())
        }
    }

    @Test
    fun backgroundPreparedAgainstOldCanonicalVersionCannotPublishItsStaleAcknowledgement() = runTest {
        withStore { store, database, path ->
            val original = completed("race")
            val value = terminal(background("race", "work", BackgroundExecutionState.RUNNING))
            store.persistence.commit(original, null)
            val index = RoomRecoveryIndex(database)
            val prepared = index.prepareBackground(value)
            assertEquals(false, prepared.acknowledged)
            val other = JvmMagratheaRoom.open(path, StoredRecordCorruptionReporter { error("Unexpected corruption") })
            try {
                other.persistence.commit(acknowledge(original, value), null)
                database.useWriterConnection { connection ->
                    connection.immediateTransaction { assertFalse(index.matches(prepared, value.sessionId)) }
                }
                store.backgroundStore.upsert(value)
                assertTrue(store.catalog.pageRecoveryCandidates(limit = 10).entries.isEmpty())
                other.persistence.deleteSession(value.sessionId)
                assertTrue(store.catalog.pageRecoveryCandidates(limit = 10).entries.isEmpty())
            } finally {
                other.close()
            }
        }
    }

    @Test
    fun oldMaintenanceGenerationCannotAdvanceNewBackgroundProgressAfterClear() = runTest {
        withStore { store, database, _ ->
            val old = store.catalog.state()
            store.persistence.clear()
            database.backgroundExecutionDao().upsert(AgentBackgroundExecutionEntity("fresh", "work", "unknown", 1))
            val before = store.catalog.state()
            val failure = assertFailsWith<SessionCatalogException> {
                RoomRecoveryIndex(database).backfillBackgroundBatch(1, old.policy, old.generation)
            }
            assertEquals(SessionCatalogFailure.STALE_MAINTENANCE, failure.failure)
            assertEquals(before, store.catalog.state())
            assertNull(database.catalogDao().state("recovery.background.generation"))
            assertNull(database.backgroundExecutionDao().findById("fresh", "work")?.catalogState)
            assertReasons(store, "fresh", SessionRecoveryReason.UNKNOWN_SOURCE)
        }
    }

    @Test
    fun backgroundWritersUseTheSamePolicyAndCloseGateAndOriginsInvalidateCoverage() = runTest {
        withStore { store, _, path ->
            val record = background("guarded", "work", BackgroundExecutionState.RUNNING)
            val origin = BackgroundOriginContext(record.sessionId, record.originRunId,
                roomTestSnapshot("guarded").request.copy(messages = emptyList()))
            val before = store.catalog.state().revision
            store.backgroundStore.putOrigin(origin)
            assertTrue(store.catalog.state().revision > before)
            val afterPut = store.catalog.state().revision
            store.backgroundStore.deleteOrigin(origin.sessionId, origin.runId)
            assertTrue(store.catalog.state().revision > afterPut)
            execute(path, "UPDATE catalog_state SET value = 'another-policy' WHERE `key` = 'policyId'")
            assertEquals(SessionCatalogFailure.POLICY_MISMATCH,
                assertFailsWith<SessionCatalogException> { store.backgroundStore.upsert(record) }.failure)
            execute(path, "UPDATE catalog_state SET value = 'magrathea.default' WHERE `key` = 'policyId'")
            store.close()
            assertEquals(SessionCatalogFailure.CLOSED,
                assertFailsWith<SessionCatalogException> { store.backgroundStore.upsert(record) }.failure)
        }
    }

    @Test
    fun recoveryPageSeeksEachSourceIndexAndMergesWithoutSortingAllCandidates() = runTest {
        withStore { store, _, path ->
            store.catalog.state()
            val sql = "SELECT sessionId FROM agent_sessions WHERE catalogRecoveryCandidate = 1 AND sessionId > ? " +
                "UNION SELECT sessionId FROM agent_checkpoints WHERE sessionId > ? " +
                "UNION SELECT sessionId FROM agent_background_executions WHERE catalogRecoveryCandidate = 1 AND sessionId > ? " +
                "ORDER BY sessionId LIMIT ?"
            val plan = BundledSQLiteDriver().open(path).use { connection ->
                connection.prepare("EXPLAIN QUERY PLAN $sql").use { statement ->
                    for (index in 1..3) statement.bindText(index, "after-session")
                    statement.bindLong(4, 21)
                    buildList {
                        while (statement.step()) add(statement.getText(3))
                    }
                }
            }
            assertTrue(plan.any { "index_agent_sessions_catalogRecoveryCandidate_sessionId" in it && "sessionId>?" in it })
            assertTrue(plan.any { "index_agent_background_executions_catalogRecoveryCandidate_sessionId" in it && "sessionId>?" in it })
            assertTrue(plan.any { "agent_checkpoints" in it && "sessionId>?" in it })
            assertFalse(plan.any { "TEMP B-TREE" in it })
        }
    }

    private suspend fun assertReasons(store: MagratheaRoomStoreHandle, session: String, vararg expected: SessionRecoveryReason) {
        val candidate = store.catalog.pageRecoveryCandidates(limit = 20).entries.single { it.sessionId.value == session }
        assertEquals(expected.toSet(), candidate.reasons)
    }

    private suspend fun withStore(block: suspend (MagratheaRoomStoreHandle, MagratheaDatabase, String) -> Unit) {
        val directory = Files.createTempDirectory("room-recovery-")
        val path = directory.resolve("store.db").toString()
        val database = buildMagratheaRoomDatabase(Room.databaseBuilder<MagratheaDatabase>(name = path))
        val store = MagratheaRoomStoreHandle(database, StoredRecordCorruptionReporter { error("A recovery query must not decode envelopes") })
        try {
            block(store, database, path)
        } finally {
            store.close()
            check(directory.toFile().deleteRecursively())
        }
    }

    private fun completed(id: String): AgentSessionSnapshot = roomTestSnapshot(id).let {
        it.copy(state = it.state.copy(status = AgentStatus.COMPLETED, stopReason = StopReason.COMPLETED))
    }

    private fun acknowledge(original: AgentSessionSnapshot, record: BackgroundExecutionRecord): AgentSessionSnapshot {
        val notice = AgentMessage(role = MessageRole.RUNTIME, parts = listOf(RuntimeNoticePart(
            entries = listOf(RuntimeNoticeEntry(record.handle, record.toolName, record.originCallId, BackgroundOutcome.SUCCEEDED, record.resultVersion)),
            delivery = RuntimeNoticeDelivery.COALESCED,
            text = "Result available",
        )))
        val reply = AgentMessage(role = MessageRole.ASSISTANT, parts = listOf(TextPart("done")), stopReason = StopReason.COMPLETED)
        return original.copy(state = original.state.copy(messages = original.state.messages + notice + reply), updatedAtEpochMs = original.updatedAtEpochMs + 1)
    }

    private fun terminal(record: BackgroundExecutionRecord): BackgroundExecutionRecord = record.copy(
        state = BackgroundExecutionState.TERMINAL,
        outcome = BackgroundOutcome.SUCCEEDED,
        result = ToolExecutionResult(record.originCallId, record.toolName, JsonPrimitive("result")),
        resultVersion = 1,
        settledAtEpochMs = 3,
    )

    private fun background(id: String, execution: String, state: BackgroundExecutionState): BackgroundExecutionRecord {
        val sessionId = AgentSessionId(id)
        val runId = AgentRunId("run-$id")
        val call = ToolCallPart("call-$execution", "lookup", buildJsonObject { })
        return BackgroundExecutionRecord(
            sessionId, execution, "handle-$execution", runId, call.toolCallId, call.toolName, "source-$execution", state,
            executionRequest = ToolExecutionRequest(sessionId, runId, execution,
                AgentMessage(role = MessageRole.ASSISTANT, parts = listOf(call)), call),
            effectiveTimeoutMillis = 1000,
            queuedAtEpochMs = 1,
        )
    }

    private fun execute(path: String, sql: String) = BundledSQLiteDriver().open(path).use { connection ->
        // Bound the wait for Room's asynchronous invalidation writes; persistent locks still fail.
        connection.prepare("PRAGMA busy_timeout=5000").use { it.step() }
        connection.prepare(sql).use { it.step() }
    }
}
