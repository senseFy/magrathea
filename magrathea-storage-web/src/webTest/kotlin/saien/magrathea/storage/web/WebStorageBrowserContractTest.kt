@file:OptIn(
    kotlin.js.ExperimentalWasmJsInterop::class,
    kotlin.uuid.ExperimentalUuidApi::class,
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
)
@file:Suppress("REDUNDANT_CALL_OF_CONVERSION_METHOD")

package saien.magrathea.storage.web

import kotlin.js.JsString
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.await
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import saien.magrathea.core.AgentCheckpoint
import saien.magrathea.core.AgentMessage
import saien.magrathea.core.AgentRequest
import saien.magrathea.core.AgentResumeCursor
import saien.magrathea.core.AgentResumePhase
import saien.magrathea.core.AgentRunId
import saien.magrathea.core.AgentSessionId
import saien.magrathea.core.AgentSessionSnapshot
import saien.magrathea.core.AgentSessionSnapshotCodec
import saien.magrathea.core.AgentStateSnapshot
import saien.magrathea.core.CURRENT_STORAGE_SCHEMA_VERSION
import saien.magrathea.core.MessageRole
import saien.magrathea.core.ModelDescriptor
import saien.magrathea.core.TextPart

class WebStorageBrowserContractTest {
    @Test
    fun sessionAndCheckpointPersistAcrossHandlesWithStrictEnvelope() = runTest {
        withIsolatedDatabase { databaseName ->
            val first = createMagratheaWebStore(configuration(databaseName))
            val older = testSnapshot("older", updatedAtEpochMs = 10L)
            val newer = testSnapshot("newer", updatedAtEpochMs = 20L)
            val checkpoint = testCheckpoint(newer, turn = 3)

            first.persistence.commit(older, checkpoint = null)
            first.persistence.commit(newer, checkpoint)

            val raw = assertNotNull(
                IndexedDbRecordDatabase(databaseName).get(newer.sessionId.value)?.session?.payload,
            )
            val envelope = Json.parseToJsonElement(raw).jsonObject
            assertEquals(setOf("schemaVersion", "sdkVersion", "payload"), envelope.keys)
            assertEquals(
                CURRENT_STORAGE_SCHEMA_VERSION,
                envelope.getValue("schemaVersion").jsonPrimitive.content.toInt(),
            )
            assertTrue(envelope.getValue("sdkVersion").jsonPrimitive.content.isNotBlank())
            first.close()

            val reopened = createMagratheaWebStore(configuration(databaseName))
            assertEquals(newer, reopened.persistence.load(newer.sessionId)?.snapshot)
            assertEquals(listOf(newer, older), reopened.persistence.listSessions())
            assertEquals(checkpoint, reopened.persistence.load(newer.sessionId)?.checkpoint)
            reopened.close()
        }
    }

    @Test
    fun terminalCommitAtomicallyRemovesThePreviousCheckpoint() = runTest {
        withIsolatedDatabase { databaseName ->
            val store = createMagratheaWebStore(configuration(databaseName))
            val running = testSnapshot("terminal-replacement")
            store.persistence.commit(running, testCheckpoint(running, turn = 1))

            val terminal = running.copy(state = running.state.copy(turn = 1))
            store.persistence.commit(terminal, checkpoint = null)

            assertEquals(terminal, store.persistence.load(running.sessionId)?.snapshot)
            assertEquals(null, store.persistence.load(running.sessionId)?.checkpoint)
            store.close()
        }
    }

    @Test
    fun listSessionsHasStableOrderingAndIsolatesCorruptRows() = runTest {
        withIsolatedDatabase { databaseName ->
            val reports = mutableListOf<WebStoredRecordCorruption>()
            val store = createMagratheaWebStore(
                configuration(databaseName),
                WebStoredRecordCorruptionReporter(reports::add),
            )
            val bravo = testSnapshot("bravo", updatedAtEpochMs = 20L)
            val alpha = testSnapshot("alpha", updatedAtEpochMs = 20L)
            val older = testSnapshot("older", updatedAtEpochMs = 10L)
            store.persistence.commit(bravo, checkpoint = null)
            store.persistence.commit(older, checkpoint = null)
            store.persistence.commit(alpha, checkpoint = null)
            val secret = "corrupt-payload-canary"
            IndexedDbRecordDatabase(databaseName)
                .commit("corrupt", "{\"token\":\"$secret\"}", checkpointPayload = null)

            assertEquals(listOf(alpha, bravo, older), store.persistence.listSessions())
            assertEquals(
                listOf(
                    WebStoredRecordCorruption(
                        kind = WebStoredRecordKind.SESSION,
                        recordId = "corrupt",
                        reason = WebStoredRecordCorruptionReason.INVALID_PAYLOAD,
                    ),
                ),
                reports,
            )
            assertFalse(reports.toString().contains(secret))
            store.close()
        }
    }

    @Test
    fun deleteAndClearAreIdempotentAndDoNotDecodeCorruptRecords() = runTest {
        withIsolatedDatabase { databaseName ->
            val store = createMagratheaWebStore(configuration(databaseName))
            val session = testSnapshot("delete-web")
            store.persistence.commit(session, testCheckpoint(session, turn = 0))

            store.persistence.deleteSession(session.sessionId)
            store.persistence.deleteSession(session.sessionId)
            assertEquals(null, store.persistence.load(session.sessionId))

            val database = IndexedDbRecordDatabase(databaseName)
            database.commit("corrupt", "not-json", "not-json")
            store.persistence.clear()
            store.persistence.clear()

            assertTrue(database.getAllSessions().isEmpty())
            assertEquals(null, database.get("corrupt"))
            store.close()
        }
    }

    @Test
    fun corruptPayloadUnknownVersionAndIndexMismatchAllFailClosed() = runTest {
        withIsolatedDatabase { databaseName ->
            val database = IndexedDbRecordDatabase(databaseName)
            val store = createMagratheaWebStore(configuration(databaseName))
            val secret = "never-surface-persisted-canary"
            database.commit("invalid", "{\"secret\":\"$secret\"}", checkpointPayload = null)

            val invalidError = assertFailsWith<WebStorageException> {
                store.persistence.load(AgentSessionId("invalid"))
            }
            assertEquals(WebStorageFailure.CORRUPT_RECORD, invalidError.failure)
            assertEquals(WebStoredRecordCorruptionReason.INVALID_PAYLOAD, invalidError.corruption?.reason)
            assertFalse(invalidError.toString().contains(secret))
            assertEquals(null, invalidError.cause)

            val unknown = testSnapshot("unknown-envelope")
            val unknownPayload = AgentSessionSnapshotCodec().encode(unknown)
                .replaceFirst(
                    "\"schemaVersion\":$CURRENT_STORAGE_SCHEMA_VERSION",
                    "\"schemaVersion\":${CURRENT_STORAGE_SCHEMA_VERSION + 1}",
                )
            database.commit(unknown.sessionId.value, unknownPayload, checkpointPayload = null)
            val unknownError = assertFailsWith<WebStorageException> {
                store.persistence.load(unknown.sessionId)
            }
            assertEquals(WebStoredRecordCorruptionReason.INVALID_PAYLOAD, unknownError.corruption?.reason)

            val payloadIdentity = testSnapshot("payload-id")
            database.commit(
                "indexed-id",
                AgentSessionSnapshotCodec().encode(payloadIdentity),
                checkpointPayload = null,
            )
            val mismatch = assertFailsWith<WebStorageException> {
                store.persistence.load(AgentSessionId("indexed-id"))
            }
            assertEquals(WebStoredRecordCorruptionReason.INDEX_MISMATCH, mismatch.corruption?.reason)
            store.close()
        }
    }

    @Test
    fun unsupportedDatabaseVersionIsRejected() = runTest {
        withIsolatedDatabase { databaseName ->
            assertEquals("ok", createUnsupportedDatabaseForTest(databaseName).await().toString())
            val store = createMagratheaWebStore(configuration(databaseName))

            val error = assertFailsWith<WebStorageException> {
                store.persistence.listSessions()
            }

            assertEquals(WebStorageFailure.UNSUPPORTED_DATABASE_VERSION, error.failure)
            assertEquals(null, error.cause)
            store.close()
        }
    }

    @Test
    fun closedStoreFailsBeforeIndexedDbWorkAndCloseIsIdempotent() = runTest {
        withIsolatedDatabase { databaseName ->
            val store = createMagratheaWebStore(configuration(databaseName))
            store.close()
            store.close()

            val error = assertFailsWith<WebStorageException> {
                store.persistence.load(AgentSessionId("closed"))
            }

            assertEquals(WebStorageFailure.CLOSED, error.failure)
        }
    }

    @Test
    fun closeWaitsForAnEnteredStorageOperationAndRejectsAllLaterWork() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var commitCalls = 0
        val database = object : WebRecordDatabase {
            override suspend fun commit(
                key: String,
                sessionPayload: String,
                checkpointPayload: String?,
            ) {
                commitCalls += 1
                entered.complete(Unit)
                release.await()
            }

            override suspend fun get(key: String): WebRawPersistenceRecord? = null

            override suspend fun getAllSessions(): List<WebRawRecord> = emptyList()

            override suspend fun delete(key: String) = Unit

            override suspend fun clear() = Unit
        }
        val store = MagratheaWebStore(database, WebStoredRecordCorruptionReporter { }, Json)
        val save = async {
            store.persistence.commit(testSnapshot("in-flight"), checkpoint = null)
        }
        entered.await()
        val close = async { store.close() }
        runCurrent()

        assertFalse(close.isCompleted)
        release.complete(Unit)
        save.await()
        close.await()
        assertEquals(1, commitCalls)

        val closed = assertFailsWith<WebStorageException> {
            store.persistence.commit(testSnapshot("after-close"), checkpoint = null)
        }
        assertEquals(WebStorageFailure.CLOSED, closed.failure)
        assertEquals(1, commitCalls)
    }

    @Test
    fun databaseNameRejectsPathTraversalAndAmbiguousNames() {
        listOf("", ".hidden", "../escape", "nested/path", "/absolute", "秘密").forEach { value ->
            assertFailsWith<IllegalArgumentException> { MagratheaWebStoreConfiguration(value) }
        }
        assertEquals(
            "chatbot-v1.db",
            MagratheaWebStoreConfiguration("chatbot-v1.db").databaseName,
        )
    }

    private fun configuration(databaseName: String) = MagratheaWebStoreConfiguration(databaseName)
}

private suspend fun withIsolatedDatabase(block: suspend (String) -> Unit) {
    val databaseName = "magrathea-test-${Uuid.random()}"
    try {
        block(databaseName)
    } finally {
        val result = deleteDatabaseForTest(databaseName).await().toString()
        check(result == "ok") { "IndexedDB test cleanup failed" }
    }
}

private fun testSnapshot(
    id: String,
    updatedAtEpochMs: Long = 1L,
): AgentSessionSnapshot {
    val sessionId = AgentSessionId(id)
    val request = AgentRequest(
        sessionId = sessionId,
        messages = listOf(AgentMessage(role = MessageRole.USER, parts = listOf(TextPart("hello")))),
        model = ModelDescriptor(provider = "gateway", model = "test-model"),
    )
    return AgentSessionSnapshot(
        sessionId = sessionId,
        runId = AgentRunId("$id-run"),
        request = request,
        state = AgentStateSnapshot(messages = request.messages),
        updatedAtEpochMs = updatedAtEpochMs,
    )
}

private fun testCheckpoint(
    snapshot: AgentSessionSnapshot,
    turn: Int,
): AgentCheckpoint = AgentCheckpoint(
    sessionId = snapshot.sessionId,
    runId = snapshot.runId,
    cursor = AgentResumeCursor(turn, AgentResumePhase.MODEL_PENDING),
    state = snapshot.state.copy(turn = turn),
)

private fun deleteDatabaseForTest(databaseName: String): Promise<JsString> = js(
    """
    new Promise((resolve) => {
      if (!globalThis.indexedDB) {
        resolve("unavailable");
        return;
      }
      const request = globalThis.indexedDB.deleteDatabase(databaseName);
      request.onsuccess = () => resolve("ok");
      request.onerror = () => resolve("error");
      request.onblocked = () => resolve("blocked");
    })
    """,
)

private fun createUnsupportedDatabaseForTest(databaseName: String): Promise<JsString> = js(
    """
    new Promise((resolve) => {
      const request = globalThis.indexedDB.open(databaseName, 2);
      request.onupgradeneeded = () => {
        const database = request.result;
        database.createObjectStore("sessions");
        database.createObjectStore("checkpoints");
      };
      request.onsuccess = () => {
        request.result.close();
        resolve("ok");
      };
      request.onerror = () => resolve("error");
      request.onblocked = () => resolve("blocked");
    })
    """,
)
