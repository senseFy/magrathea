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
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.await
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import saien.magrathea.core.AgentCheckpoint
import saien.magrathea.core.AgentCheckpointCodec
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
import saien.magrathea.core.MINIMUM_READABLE_STORAGE_SCHEMA_VERSION
import saien.magrathea.core.MessageRole
import saien.magrathea.core.ModelDescriptor
import saien.magrathea.core.StoredEnvelopeDecodeFailure
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
    fun schemaV6SessionAndCheckpointAreRewrittenAfterAValidatedLoad() = runTest {
        withIsolatedDatabase { databaseName ->
            val database = IndexedDbRecordDatabase(databaseName)
            val session = testSnapshot("web-v6-migration", updatedAtEpochMs = 25L)
            val checkpoint = testCheckpoint(session, turn = 2)
            val v6Session = AgentSessionSnapshotCodec().encode(session).toSchemaV6()
            val v6Checkpoint = AgentCheckpointCodec().encode(checkpoint).toSchemaV6()
            database.commit(session.sessionId.value, v6Session, v6Checkpoint)
            val store = createMagratheaWebStore(configuration(databaseName))

            val loaded = assertNotNull(store.persistence.load(session.sessionId))
            assertEquals(session, loaded.snapshot)
            assertEquals(checkpoint, loaded.checkpoint)

            val rewritten = assertNotNull(database.get(session.sessionId.value))
            val rewrittenSession = assertNotNull(rewritten.session.payload)
            val rewrittenCheckpoint = assertNotNull(assertNotNull(rewritten.checkpoint).payload)
            assertTrue("\"schemaVersion\":7" in rewrittenSession)
            assertTrue("\"maxOutputTokens\":null" in rewrittenSession)
            assertTrue("\"schemaVersion\":7" in rewrittenCheckpoint)
            store.close()
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
    fun validatedMigrationMetadataAtomicallyRewritesSessionAndCheckpoint() = runTest {
        withIsolatedDatabase { databaseName ->
            val session = testSnapshot("web-migrated", updatedAtEpochMs = 17L)
            val checkpoint = testCheckpoint(session, turn = 2)
            val database = IndexedDbRecordDatabase(databaseName)
            database.commit(session.sessionId.value, "legacy-session", "legacy-checkpoint")
            val store = MagratheaWebStore(
                database = database,
                reporter = WebStoredRecordCorruptionReporter { error("Unexpected corruption: $it") },
                json = Json,
                snapshotDecoder = { payload ->
                    assertEquals("legacy-session", payload)
                    WebDecodedStoredRecord(session, "canonical-session")
                },
                checkpointDecoder = { payload ->
                    assertEquals("legacy-checkpoint", payload)
                    WebDecodedStoredRecord(checkpoint, "canonical-checkpoint")
                },
            )

            val loaded = assertNotNull(store.persistence.load(session.sessionId))

            assertEquals(session, loaded.snapshot)
            assertEquals(checkpoint, loaded.checkpoint)
            val rewritten = assertNotNull(database.get(session.sessionId.value))
            assertEquals("canonical-session", rewritten.session.payload)
            assertEquals("canonical-checkpoint", rewritten.checkpoint?.payload)
            store.close()
        }
    }

    @Test
    fun listingMigratedSessionNeverPerformsAOneSidedRewrite() = runTest {
        withIsolatedDatabase { databaseName ->
            val session = testSnapshot("web-migrated-list", updatedAtEpochMs = 19L)
            val database = IndexedDbRecordDatabase(databaseName)
            database.commit(session.sessionId.value, "legacy-session", "legacy-checkpoint")
            val store = MagratheaWebStore(
                database = database,
                reporter = WebStoredRecordCorruptionReporter { error("Unexpected corruption: $it") },
                json = Json,
                snapshotDecoder = {
                    WebDecodedStoredRecord(session, "canonical-session")
                },
                checkpointDecoder = {
                    error("listSessions must not decode or rewrite checkpoints")
                },
            )

            assertEquals(listOf(session), store.persistence.listSessions())
            val unchanged = assertNotNull(database.get(session.sessionId.value))
            assertEquals("legacy-session", unchanged.session.payload)
            assertEquals("legacy-checkpoint", unchanged.checkpoint?.payload)
            store.close()
        }
    }

    @Test
    fun crossRecordValidationFailureDoesNotPublishOrRewriteEitherPayload() = runTest {
        withIsolatedDatabase { databaseName ->
            val session = testSnapshot("web-migration-failure", updatedAtEpochMs = 18L)
            val mismatchedCheckpoint = testCheckpoint(
                session.copy(runId = AgentRunId("different-run")),
                turn = 2,
            )
            val database = IndexedDbRecordDatabase(databaseName)
            database.commit(session.sessionId.value, "legacy-session", "legacy-checkpoint")
            val store = MagratheaWebStore(
                database = database,
                reporter = WebStoredRecordCorruptionReporter { },
                json = Json,
                snapshotDecoder = {
                    WebDecodedStoredRecord(session, "canonical-session")
                },
                checkpointDecoder = {
                    WebDecodedStoredRecord(mismatchedCheckpoint, "canonical-checkpoint")
                },
            )

            val failure = assertFailsWith<WebStorageException> {
                store.persistence.load(session.sessionId)
            }

            assertEquals(WebStorageFailure.CORRUPT_RECORD, failure.failure)
            val unchanged = assertNotNull(database.get(session.sessionId.value))
            assertEquals("legacy-session", unchanged.session.payload)
            assertEquals("legacy-checkpoint", unchanged.checkpoint?.payload)
            store.close()
        }
    }

    @Test
    fun indexedDbRewriteCompareAndSetIsAllOrNothing() = runTest {
        withIsolatedDatabase { databaseName ->
            val database = IndexedDbRecordDatabase(databaseName)
            database.commit("atomic-rewrite", "legacy-session", "legacy-checkpoint")

            val stale = database.rewriteIfUnchanged(
                listOf(
                    WebPayloadRewriteExpectation(
                        WEB_SESSION_STORE,
                        "atomic-rewrite",
                        "legacy-session",
                        "canonical-session",
                    ),
                    WebPayloadRewriteExpectation(
                        WEB_CHECKPOINT_STORE,
                        "atomic-rewrite",
                        "stale-checkpoint",
                        "canonical-checkpoint",
                    ),
                ),
            )

            assertFalse(stale)
            val unchanged = assertNotNull(database.get("atomic-rewrite"))
            assertEquals("legacy-session", unchanged.session.payload)
            assertEquals("legacy-checkpoint", unchanged.checkpoint?.payload)

            val rewritten = database.rewriteIfUnchanged(
                listOf(
                    WebPayloadRewriteExpectation(
                        WEB_SESSION_STORE,
                        "atomic-rewrite",
                        "legacy-session",
                        "canonical-session",
                    ),
                    WebPayloadRewriteExpectation(
                        WEB_CHECKPOINT_STORE,
                        "atomic-rewrite",
                        "legacy-checkpoint",
                        "canonical-checkpoint",
                    ),
                ),
            )

            assertTrue(rewritten)
            val current = assertNotNull(database.get("atomic-rewrite"))
            assertEquals("canonical-session", current.session.payload)
            assertEquals("canonical-checkpoint", current.checkpoint?.payload)
        }
    }

    @Test
    fun listingSchemaFailureDoesNotPartiallyRewriteEarlierRecords() = runTest {
        withIsolatedDatabase { databaseName ->
            val migratable = testSnapshot("web-migratable-list", updatedAtEpochMs = 20L)
            val incompatible = testSnapshot("web-incompatible-list", updatedAtEpochMs = 10L)
            val codec = AgentSessionSnapshotCodec()
            val incompatiblePayload = codec.encode(incompatible).replaceFirst(
                "\"schemaVersion\":$CURRENT_STORAGE_SCHEMA_VERSION",
                "\"schemaVersion\":${MINIMUM_READABLE_STORAGE_SCHEMA_VERSION - 1}",
            )
            val database = IndexedDbRecordDatabase(databaseName)
            database.commit(
                migratable.sessionId.value,
                "legacy-migratable-session",
                checkpointPayload = null,
            )
            database.commit(
                incompatible.sessionId.value,
                incompatiblePayload,
                checkpointPayload = null,
            )
            val store = MagratheaWebStore(
                database = database,
                reporter = WebStoredRecordCorruptionReporter { error("Unexpected corruption: $it") },
                json = Json,
                snapshotDecoder = { payload ->
                    if (payload == "legacy-migratable-session") {
                        WebDecodedStoredRecord(
                            migratable,
                            "canonical-migratable-session",
                        )
                    } else {
                        codec.decodeResult(payload).toWebStoredRecord()
                    }
                },
            )

            val failure = assertFailsWith<WebStorageException> {
                store.persistence.listSessions()
            }

            assertEquals(WebStorageFailure.UNSUPPORTED_OLDER_SCHEMA, failure.failure)
            assertEquals(
                "legacy-migratable-session",
                assertNotNull(database.get(migratable.sessionId.value)).session.payload,
            )
            assertEquals(
                incompatiblePayload,
                assertNotNull(database.get(incompatible.sessionId.value)).session.payload,
            )
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
            assertEquals(WebStorageFailure.UNSUPPORTED_NEWER_SCHEMA, unknownError.failure)
            assertEquals(null, unknownError.corruption)
            assertEquals(CURRENT_STORAGE_SCHEMA_VERSION + 1, unknownError.storedSchemaVersion)
            assertEquals(CURRENT_STORAGE_SCHEMA_VERSION, unknownError.currentSchemaVersion)
            assertEquals(WebStoredRecordKind.SESSION, unknownError.schemaIssue?.kind)
            assertEquals(unknown.sessionId.value, unknownError.schemaIssue?.recordId)
            assertEquals(
                StoredEnvelopeDecodeFailure.UNSUPPORTED_NEWER_SCHEMA,
                unknownError.schemaIssue?.failure,
            )
            assertEquals(
                unknownPayload,
                assertNotNull(database.get(unknown.sessionId.value)).session.payload,
            )

            val checkpointSession = testSnapshot("unknown-checkpoint")
            val checkpoint = testCheckpoint(checkpointSession, turn = 2)
            val unknownCheckpointPayload = AgentCheckpointCodec().encode(checkpoint)
                .replaceFirst(
                    "\"schemaVersion\":$CURRENT_STORAGE_SCHEMA_VERSION",
                    "\"schemaVersion\":${CURRENT_STORAGE_SCHEMA_VERSION + 1}",
                )
            database.commit(
                checkpointSession.sessionId.value,
                AgentSessionSnapshotCodec().encode(checkpointSession),
                unknownCheckpointPayload,
            )
            val checkpointError = assertFailsWith<WebStorageException> {
                store.persistence.load(checkpointSession.sessionId)
            }
            assertEquals(WebStorageFailure.UNSUPPORTED_NEWER_SCHEMA, checkpointError.failure)
            assertEquals(WebStoredRecordKind.CHECKPOINT, checkpointError.schemaIssue?.kind)
            assertEquals(checkpointSession.sessionId.value, checkpointError.schemaIssue?.recordId)

            val unsafe = testSnapshot("unsafe/id")
            val unsafePayload = AgentSessionSnapshotCodec().encode(unsafe)
                .replaceFirst(
                    "\"schemaVersion\":$CURRENT_STORAGE_SCHEMA_VERSION",
                    "\"schemaVersion\":${CURRENT_STORAGE_SCHEMA_VERSION + 1}",
                )
            database.commit(unsafe.sessionId.value, unsafePayload, checkpointPayload = null)
            val unsafeError = assertFailsWith<WebStorageException> {
                store.persistence.load(unsafe.sessionId)
            }
            assertEquals(WebStoredRecordKind.SESSION, unsafeError.schemaIssue?.kind)
            assertEquals(null, unsafeError.schemaIssue?.recordId)

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
    fun olderSchemaIsNotSilentlyFilteredOrRewrittenByListing() = runTest {
        withIsolatedDatabase { databaseName ->
            val database = IndexedDbRecordDatabase(databaseName)
            val store = createMagratheaWebStore(configuration(databaseName))
            val older = testSnapshot("older-schema")
            val olderPayload = AgentSessionSnapshotCodec().encode(older)
                .replaceFirst(
                    "\"schemaVersion\":$CURRENT_STORAGE_SCHEMA_VERSION",
                    "\"schemaVersion\":${MINIMUM_READABLE_STORAGE_SCHEMA_VERSION - 1}",
                )
            database.commit(older.sessionId.value, olderPayload, checkpointPayload = null)

            val failure = assertFailsWith<WebStorageException> {
                store.persistence.listSessions()
            }

            assertEquals(WebStorageFailure.UNSUPPORTED_OLDER_SCHEMA, failure.failure)
            assertEquals(null, failure.corruption)
            assertEquals(
                MINIMUM_READABLE_STORAGE_SCHEMA_VERSION - 1,
                failure.storedSchemaVersion,
            )
            assertEquals(CURRENT_STORAGE_SCHEMA_VERSION, failure.currentSchemaVersion)
            assertEquals(WebStoredRecordKind.SESSION, failure.schemaIssue?.kind)
            assertEquals(older.sessionId.value, failure.schemaIssue?.recordId)
            assertEquals(
                StoredEnvelopeDecodeFailure.UNSUPPORTED_OLDER_SCHEMA,
                failure.schemaIssue?.failure,
            )
            assertEquals(
                olderPayload,
                assertNotNull(database.get(older.sessionId.value)).session.payload,
            )
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

            override suspend fun rewriteIfUnchanged(
                expectations: List<WebPayloadRewriteExpectation>,
            ): Boolean = true

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

    @Test
    fun schemaIssueRequiresSanitizedIdentityAndConsistentPositiveVersions() {
        val valid = WebStoredRecordSchemaIssue(
            kind = WebStoredRecordKind.SESSION,
            recordId = "safe-session",
            failure = StoredEnvelopeDecodeFailure.UNSUPPORTED_NEWER_SCHEMA,
            storedSchemaVersion = 7,
            currentSchemaVersion = 6,
        )
        assertEquals("safe-session", valid.recordId)

        listOf<() -> Unit>(
            {
                WebStoredRecordSchemaIssue(
                    WebStoredRecordKind.SESSION,
                    "unsafe/session",
                    StoredEnvelopeDecodeFailure.UNSUPPORTED_NEWER_SCHEMA,
                    7,
                    6,
                )
            },
            {
                WebStoredRecordSchemaIssue(
                    WebStoredRecordKind.SESSION,
                    null,
                    StoredEnvelopeDecodeFailure.CORRUPT,
                    6,
                    7,
                )
            },
            {
                WebStoredRecordSchemaIssue(
                    WebStoredRecordKind.SESSION,
                    null,
                    StoredEnvelopeDecodeFailure.UNSUPPORTED_OLDER_SCHEMA,
                    7,
                    7,
                )
            },
            {
                WebStoredRecordSchemaIssue(
                    WebStoredRecordKind.SESSION,
                    null,
                    StoredEnvelopeDecodeFailure.UNSUPPORTED_NEWER_SCHEMA,
                    7,
                    7,
                )
            },
            {
                WebStoredRecordSchemaIssue(
                    WebStoredRecordKind.SESSION,
                    null,
                    StoredEnvelopeDecodeFailure.MIGRATION_FAILED,
                    0,
                    7,
                )
            },
        ).forEach { construct ->
            assertFailsWith<IllegalArgumentException> { construct() }
        }
    }

    @Test
    fun corruptionReporterRunsAfterTheLifecycleMutexIsReleased() = runTest {
        val testScope = this
        var clearCalls = 0
        var reentrantOperationCompletedInsideReporter = false
        val database = object : WebRecordDatabase {
            override suspend fun commit(
                key: String,
                sessionPayload: String,
                checkpointPayload: String?,
            ) = Unit

            override suspend fun get(key: String): WebRawPersistenceRecord =
                WebRawPersistenceRecord(WebRawRecord(key, "not-json"), checkpoint = null)

            override suspend fun getAllSessions(): List<WebRawRecord> = emptyList()

            override suspend fun rewriteIfUnchanged(
                expectations: List<WebPayloadRewriteExpectation>,
            ): Boolean = true

            override suspend fun delete(key: String) = Unit

            override suspend fun clear() {
                clearCalls += 1
            }
        }
        lateinit var store: MagratheaWebStore
        store = MagratheaWebStore(
            database = database,
            reporter = WebStoredRecordCorruptionReporter {
                val reentrant = testScope.async(start = CoroutineStart.UNDISPATCHED) {
                    store.persistence.clear()
                }
                reentrantOperationCompletedInsideReporter = reentrant.isCompleted
            },
            json = Json,
        )

        val failure = assertFailsWith<WebStorageException> {
            store.persistence.load(AgentSessionId("corrupt-reentrant"))
        }

        assertEquals(WebStorageFailure.CORRUPT_RECORD, failure.failure)
        assertTrue(reentrantOperationCompletedInsideReporter)
        assertEquals(1, clearCalls)
        store.close()
    }

    private fun configuration(databaseName: String) = MagratheaWebStoreConfiguration(databaseName)
}

private fun String.toSchemaV6(): String =
    replaceFirst("\"schemaVersion\":7", "\"schemaVersion\":6")
        .replace(",\"maxOutputTokens\":null", "")

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
