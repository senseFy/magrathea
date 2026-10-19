package saien.magrathea.storage.room

import androidx.room.Room
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import saien.magrathea.core.AgentRunId
import saien.magrathea.core.AgentCheckpointCodec
import saien.magrathea.core.AgentEvent
import saien.magrathea.core.AgentMessage
import saien.magrathea.core.AgentRequest
import saien.magrathea.core.AgentSessionId
import saien.magrathea.core.AgentSessionSnapshot
import saien.magrathea.core.AgentSessionSnapshotCodec
import saien.magrathea.core.CURRENT_STORAGE_SCHEMA_VERSION
import saien.magrathea.core.ContextEstimationUnit
import saien.magrathea.core.MINIMUM_READABLE_STORAGE_SCHEMA_VERSION
import saien.magrathea.core.MessageRole
import saien.magrathea.core.ModelDescriptor
import saien.magrathea.core.StopReason
import saien.magrathea.core.StoredEnvelopeDecodeFailure
import saien.magrathea.core.TextPart
import saien.magrathea.core.ToolCallPart
import saien.magrathea.core.ToolResultContextConfig
import saien.magrathea.core.ToolResultPart
import saien.magrathea.core.citations
import saien.magrathea.provider.api.InMemoryProviderRegistry
import saien.magrathea.provider.api.ProviderAdapter
import saien.magrathea.provider.api.ProviderChunk
import saien.magrathea.provider.api.ProviderEvent
import saien.magrathea.provider.api.ProviderNetworkException
import saien.magrathea.provider.api.ProviderRequest
import saien.magrathea.runtime.DefaultAgentRunner
import saien.magrathea.runtime.InMemoryToolRegistry
import saien.magrathea.runtime.search.WebSearchBackend
import saien.magrathea.runtime.search.WebSearchBackendResponse
import saien.magrathea.runtime.search.WebSearchSource
import saien.magrathea.runtime.search.WebSearchTool

class JvmRoomDatabaseIntegrationTest {
    @Test
    fun searchSnippetCutAtAnEmojiSurvivesRoomReopenAndRuntimeResume() = runTest {
        val directory = Files.createTempDirectory("magrathea-room-search-unicode-")
        val database = directory.resolve("magrathea.db")
        val sessionId = AgentSessionId("search-unicode-resume")
        val prefix = "a".repeat(1_199)
        var backendCalls = 0
        val search = WebSearchTool(WebSearchBackend {
            backendCalls++
            WebSearchBackendResponse(listOf(WebSearchSource(
                title = "Search result",
                url = "https://example.com/result",
                // The default 1200-unit snippet budget falls between this emoji's surrogates.
                snippet = prefix + "😀 trailing text",
                publishedAt = "2026-10-18",
            )))
        })
        var providerCalls = 0
        val provider = object : ProviderAdapter {
            override val key = "search-unicode-provider"

            override suspend fun generate(request: ProviderRequest): Flow<ProviderChunk> = flow {
                when (++providerCalls) {
                    1 -> {
                        val call = ToolCallPart(
                            toolCallId = "search-1",
                            toolName = WebSearchTool.NAME,
                            arguments = buildJsonObject { put("query", "latest release") },
                        )
                        emit(ProviderChunk(listOf(
                            ProviderEvent.ToolCallStart(call.copy(partial = true)),
                            ProviderEvent.ToolCallEnd(call),
                            ProviderEvent.Completed(stopReason = StopReason.TOOL_CALLS),
                        )))
                    }
                    2 -> throw ProviderNetworkException("Offline after search")
                    else -> {
                        val result = assertIs<ToolResultPart>(request.messages.last().parts.single())
                        assertEquals(prefix, result.result.jsonObject.getValue("sources").jsonArray
                            .single().jsonObject.getValue("snippet").jsonPrimitive.content)
                        // Match transports which reject malformed UTF-16 rather than replacing it.
                        result.result.toString().encodeToByteArray(throwOnInvalidSequence = true)
                        emit(ProviderChunk(listOf(
                            ProviderEvent.TextStart(),
                            ProviderEvent.TextDelta("Grounded answer"),
                            ProviderEvent.TextEnd(),
                            ProviderEvent.Completed(stopReason = StopReason.COMPLETED),
                        )))
                    }
                }
            }
        }
        val providers = InMemoryProviderRegistry(listOf(provider))
        val tools = InMemoryToolRegistry(listOf(search))
        val reporter = StoredRecordCorruptionReporter { error("Unexpected corruption: $it") }
        try {
            val first = JvmMagratheaRoom.open(database.toString(), reporter)
            try {
                val events = DefaultAgentRunner(providers, tools, first.persistence).run(AgentRequest(
                    sessionId = sessionId,
                    messages = listOf(AgentMessage(
                        role = MessageRole.USER,
                        parts = listOf(TextPart("Find the latest release")),
                    )),
                    model = ModelDescriptor(provider.key, "model", supportsToolCalls = true),
                    tools = listOf(search.definition),
                )).toList()
                assertIs<AgentEvent.Interrupted>(events.last())
            } finally {
                first.close()
            }

            val reopened = JvmMagratheaRoom.open(database.toString(), reporter)
            try {
                val record = assertNotNull(reopened.persistence.load(sessionId))
                val checkpoint = assertNotNull(record.checkpoint)
                for (state in listOf(record.snapshot.state, checkpoint.state)) {
                    val result = assertIs<ToolResultPart>(state.messages
                        .single { it.role == MessageRole.TOOL }.parts.single())
                    assertEquals(prefix, result.citations().single().snippet)
                    Json.encodeToString(ToolResultPart.serializer(), result)
                        .encodeToByteArray(throwOnInvalidSequence = true)
                }

                val resumed = DefaultAgentRunner(providers, tools, reopened.persistence)
                    .resume(sessionId).toList()
                val completed = assertIs<AgentEvent.Completed>(resumed.last())
                assertEquals("Grounded answer", assertIs<TextPart>(completed.state.messages.last().parts.single()).text)
                assertEquals(1, backendCalls)
                assertEquals(3, providerCalls)
                assertNull(assertNotNull(reopened.persistence.load(sessionId)).checkpoint)
            } finally {
                reopened.close()
            }
        } finally {
            check(directory.toFile().deleteRecursively()) { "Failed to remove Room Unicode-test directory" }
        }
    }

    @Test
    fun realDatabase_closeAndReopen_preservesCurrentSessionAndCheckpoint() = runTest {
        val directory = Files.createTempDirectory("magrathea-room-")
        val database = directory.resolve("magrathea.db")
        val session = roomTestSnapshot("jvm-reopen", updatedAtEpochMs = 42L)
        val checkpoint = roomTestCheckpoint(session, turn = 2)
        val reporter = StoredRecordCorruptionReporter { error("Unexpected corruption: $it") }

        val first = JvmMagratheaRoom.open(database.toString(), reporter)
        first.persistence.commit(session, checkpoint)
        first.close()
        first.close()

        val reopened = JvmMagratheaRoom.open(database.toString(), reporter)
        assertEquals(session, reopened.persistence.load(session.sessionId)?.snapshot)
        assertEquals(listOf(session), reopened.persistence.listSessions())
        assertEquals(checkpoint, reopened.persistence.load(session.sessionId)?.checkpoint)
        reopened.close()

        check(directory.toFile().deleteRecursively()) { "Failed to remove Room integration-test directory" }
    }

    @Test
    fun currentSchemaBackupAfterClose_restoresThroughStrictStores() = runTest {
        val directory = Files.createTempDirectory("magrathea-room-backup-")
        val database = directory.resolve("source.db")
        val backup = directory.resolve("current-schema.backup")
        val restoredDatabase = directory.resolve("restored.db")
        val session = roomTestSnapshot("jvm-backup-restore", updatedAtEpochMs = 77L)
        val checkpoint = roomTestCheckpoint(session, turn = 3)
        val reporter = StoredRecordCorruptionReporter { error("Unexpected corruption: $it") }

        val source = JvmMagratheaRoom.open(database.toString(), reporter)
        source.persistence.commit(session, checkpoint)
        source.close()

        Files.copy(database, backup, StandardCopyOption.REPLACE_EXISTING)
        Files.copy(backup, restoredDatabase, StandardCopyOption.REPLACE_EXISTING)

        val restored = JvmMagratheaRoom.open(restoredDatabase.toString(), reporter)
        assertEquals(session, restored.persistence.load(session.sessionId)?.snapshot)
        assertEquals(checkpoint, restored.persistence.load(session.sessionId)?.checkpoint)
        restored.close()

        check(directory.toFile().deleteRecursively()) { "Failed to remove Room backup-test directory" }
    }

    @Test
    fun schemaV6SessionAndCheckpointAreRewrittenAfterAValidatedLoad() = runTest {
        val directory = Files.createTempDirectory("magrathea-room-v6-migration-")
        val databasePath = directory.resolve("magrathea.db")
        val session = roomTestSnapshot("v6-migration", updatedAtEpochMs = 88L).withSchema7ContextManagement()
        val checkpoint = roomTestCheckpoint(session, turn = 2)
        val v6Session = AgentSessionSnapshotCodec().encode(session).toSchemaV6()
        val v6Checkpoint = AgentCheckpointCodec().encode(checkpoint).toSchemaV6()
        val rawDatabase = buildMagratheaRoomDatabase(
            Room.databaseBuilder<MagratheaDatabase>(name = databasePath.toString()),
        )
        rawDatabase.sessionDao().upsert(
            AgentSessionEntity(session.sessionId.value, v6Session, session.updatedAtEpochMs),
        )
        rawDatabase.checkpointDao().upsert(
            AgentCheckpointEntity(session.sessionId.value, v6Checkpoint, checkpoint.turn),
        )
        rawDatabase.close()

        val store = JvmMagratheaRoom.open(
            databasePath.toString(),
            StoredRecordCorruptionReporter { error("Unexpected corruption: $it") },
        )
        val loaded = assertNotNull(store.persistence.load(session.sessionId))
        assertEquals(session, loaded.snapshot)
        assertEquals(checkpoint, loaded.checkpoint)
        store.close()

        val rewrittenDatabase = buildMagratheaRoomDatabase(
            Room.databaseBuilder<MagratheaDatabase>(name = databasePath.toString()),
        )
        val rewrittenSession = assertNotNull(
            rewrittenDatabase.sessionDao().findById(session.sessionId.value),
        ).payload
        val rewrittenCheckpoint = assertNotNull(
            rewrittenDatabase.checkpointDao().findById(session.sessionId.value),
        ).payload
        assertTrue("\"schemaVersion\":8" in rewrittenSession)
        assertTrue("\"maxOutputTokens\":null" in rewrittenSession)
        assertTrue("\"estimationUnit\":\"UTF16_CHARS\"" in rewrittenSession)
        assertTrue("\"schemaVersion\":8" in rewrittenCheckpoint)
        rewrittenDatabase.close()

        check(directory.toFile().deleteRecursively()) {
            "Failed to remove Room schema-v6 migration directory"
        }
    }

    @Test
    fun corruptCurrentRows_canBeClearedAndRebuiltWithoutDecoding() = runTest {
        val directory = Files.createTempDirectory("magrathea-room-rebuild-")
        val databasePath = directory.resolve("magrathea.db")
        val corruptId = "corrupt-current-row"
        val reporter = StoredRecordCorruptionReporter { }
        val rawDatabase = buildMagratheaRoomDatabase(
            Room.databaseBuilder<MagratheaDatabase>(name = databasePath.toString()),
        )
        rawDatabase.sessionDao().upsert(AgentSessionEntity(corruptId, "not-json", 1L))
        rawDatabase.checkpointDao().upsert(AgentCheckpointEntity(corruptId, "not-json", 1))
        rawDatabase.close()

        val store = JvmMagratheaRoom.open(databasePath.toString(), reporter)
        assertTrue(store.persistence.listSessions().isEmpty())
        assertFailsWith<StoredRecordCorruptionException> {
            store.persistence.load(saien.magrathea.core.AgentSessionId(corruptId))
        }

        store.persistence.clear()
        store.persistence.clear()
        store.close()

        val rebuilt = JvmMagratheaRoom.open(databasePath.toString(), reporter)
        assertTrue(rebuilt.persistence.listSessions().isEmpty())
        assertEquals(
            null,
            rebuilt.persistence.load(saien.magrathea.core.AgentSessionId(corruptId)),
        )
        rebuilt.close()

        check(directory.toFile().deleteRecursively()) { "Failed to remove Room rebuild-test directory" }
    }

    @Test
    fun incompatibleSchemaIsNotSilentlyFilteredOrRewritten() = runTest {
        val directory = Files.createTempDirectory("magrathea-room-incompatible-")
        val databasePath = directory.resolve("magrathea.db")
        val session = roomTestSnapshot("older-schema-row", updatedAtEpochMs = 12L)
        val currentPayload = AgentSessionSnapshotCodec().encode(session)
        val olderPayload = currentPayload.replaceFirst(
            "\"schemaVersion\":$CURRENT_STORAGE_SCHEMA_VERSION",
            "\"schemaVersion\":${MINIMUM_READABLE_STORAGE_SCHEMA_VERSION - 1}",
        )
        val reports = mutableListOf<StoredRecordCorruption>()
        val rawDatabase = buildMagratheaRoomDatabase(
            Room.databaseBuilder<MagratheaDatabase>(name = databasePath.toString()),
        )
        rawDatabase.sessionDao().upsert(
            AgentSessionEntity(session.sessionId.value, olderPayload, session.updatedAtEpochMs),
        )
        rawDatabase.close()

        val store = JvmMagratheaRoom.open(
            databasePath.toString(),
            StoredRecordCorruptionReporter(reports::add),
        )
        val failure = assertFailsWith<StoredRecordSchemaException> {
            store.persistence.listSessions()
        }
        assertEquals(
            StoredEnvelopeDecodeFailure.UNSUPPORTED_OLDER_SCHEMA,
            failure.issue.failure,
        )
        assertEquals(
            MINIMUM_READABLE_STORAGE_SCHEMA_VERSION - 1,
            failure.issue.storedSchemaVersion,
        )
        assertTrue(reports.isEmpty())
        store.close()

        val unchanged = buildMagratheaRoomDatabase(
            Room.databaseBuilder<MagratheaDatabase>(name = databasePath.toString()),
        )
        assertEquals(olderPayload, unchanged.sessionDao().findById(session.sessionId.value)?.payload)
        unchanged.close()

        check(directory.toFile().deleteRecursively()) {
            "Failed to remove Room incompatible-schema test directory"
        }
    }

    @Test
    fun incompatibleSchemaRedactsAnUnsafeDatabaseRowIdentity() = runTest {
        val directory = Files.createTempDirectory("magrathea-room-unsafe-schema-id-")
        val databasePath = directory.resolve("magrathea.db")
        val session = roomTestSnapshot("unsafe/id", updatedAtEpochMs = 13L)
        val futurePayload = AgentSessionSnapshotCodec().encode(session).replaceFirst(
            "\"schemaVersion\":$CURRENT_STORAGE_SCHEMA_VERSION",
            "\"schemaVersion\":${CURRENT_STORAGE_SCHEMA_VERSION + 1}",
        )
        val database = buildMagratheaRoomDatabase(
            Room.databaseBuilder<MagratheaDatabase>(name = databasePath.toString()),
        )
        database.sessionDao().upsert(
            AgentSessionEntity(session.sessionId.value, futurePayload, session.updatedAtEpochMs),
        )
        database.close()

        val store = JvmMagratheaRoom.open(
            databasePath.toString(),
            StoredRecordCorruptionReporter { error("Unexpected corruption: $it") },
        )
        val failure = assertFailsWith<StoredRecordSchemaException> {
            store.persistence.load(session.sessionId)
        }

        assertEquals(StoredEnvelopeDecodeFailure.UNSUPPORTED_NEWER_SCHEMA, failure.issue.failure)
        assertNull(failure.issue.sessionId)
        store.close()
        check(directory.toFile().deleteRecursively()) {
            "Failed to remove Room unsafe-schema-identity test directory"
        }
    }

    @Test
    fun validatedMigrationMetadataRewritesSessionAndCheckpointInOneTransaction() = runTest {
        val directory = Files.createTempDirectory("magrathea-room-rewrite-")
        val databasePath = directory.resolve("magrathea.db")
        val session = roomTestSnapshot("migrated-row", updatedAtEpochMs = 14L)
        val checkpoint = roomTestCheckpoint(session, turn = 2)
        val database = buildMagratheaRoomDatabase(
            Room.databaseBuilder<MagratheaDatabase>(name = databasePath.toString()),
        )
        database.sessionDao().upsert(
            AgentSessionEntity(session.sessionId.value, "legacy-session", session.updatedAtEpochMs),
        )
        database.checkpointDao().upsert(
            AgentCheckpointEntity(checkpoint.sessionId.value, "legacy-checkpoint", checkpoint.turn),
        )
        val persistence = RoomAgentPersistence(
            database = database,
            reporter = StoredRecordCorruptionReporter { error("Unexpected corruption: $it") },
            snapshotDecoder = {
                DecodedStoredRecord(session, "canonical-session")
            },
            checkpointDecoder = {
                DecodedStoredRecord(checkpoint, "canonical-checkpoint")
            },
        )

        val loaded = assertNotNull(persistence.load(session.sessionId))

        assertEquals(session, loaded.snapshot)
        assertEquals(checkpoint, loaded.checkpoint)
        assertEquals(
            "canonical-session",
            database.sessionDao().findById(session.sessionId.value)?.payload,
        )
        assertEquals(
            "canonical-checkpoint",
            database.checkpointDao().findById(session.sessionId.value)?.payload,
        )
        database.close()
        check(directory.toFile().deleteRecursively()) {
            "Failed to remove Room rewrite-test directory"
        }
    }

    @Test
    fun listingMigratedSessionNeverPerformsAOneSidedRewrite() = runTest {
        val directory = Files.createTempDirectory("magrathea-room-list-read-only-")
        val databasePath = directory.resolve("magrathea.db")
        val session = roomTestSnapshot("migrated-list-row", updatedAtEpochMs = 19L)
        val checkpoint = roomTestCheckpoint(session, turn = 2)
        val database = buildMagratheaRoomDatabase(
            Room.databaseBuilder<MagratheaDatabase>(name = databasePath.toString()),
        )
        database.sessionDao().upsert(
            AgentSessionEntity(session.sessionId.value, "legacy-session", session.updatedAtEpochMs),
        )
        database.checkpointDao().upsert(
            AgentCheckpointEntity(checkpoint.sessionId.value, "legacy-checkpoint", checkpoint.turn),
        )
        val persistence = RoomAgentPersistence(
            database = database,
            reporter = StoredRecordCorruptionReporter { error("Unexpected corruption: $it") },
            snapshotDecoder = {
                DecodedStoredRecord(session, "canonical-session")
            },
            checkpointDecoder = {
                error("listSessions must not decode or rewrite checkpoints")
            },
        )

        assertEquals(listOf(session), persistence.listSessions())
        assertEquals(
            "legacy-session",
            database.sessionDao().findById(session.sessionId.value)?.payload,
        )
        assertEquals(
            "legacy-checkpoint",
            database.checkpointDao().findById(session.sessionId.value)?.payload,
        )

        database.close()
        check(directory.toFile().deleteRecursively()) {
            "Failed to remove Room read-only-list test directory"
        }
    }

    @Test
    fun crossRecordValidationFailurePreservesEveryLegacyPayload() = runTest {
        val directory = Files.createTempDirectory("magrathea-room-rewrite-failure-")
        val databasePath = directory.resolve("magrathea.db")
        val session = roomTestSnapshot("migration-validation-failure", updatedAtEpochMs = 15L)
        val mismatchedCheckpoint = roomTestCheckpoint(
            session.copy(runId = AgentRunId("different-run")),
            turn = 2,
        )
        val database = buildMagratheaRoomDatabase(
            Room.databaseBuilder<MagratheaDatabase>(name = databasePath.toString()),
        )
        database.sessionDao().upsert(
            AgentSessionEntity(session.sessionId.value, "legacy-session", session.updatedAtEpochMs),
        )
        database.checkpointDao().upsert(
            AgentCheckpointEntity(
                mismatchedCheckpoint.sessionId.value,
                "legacy-checkpoint",
                mismatchedCheckpoint.turn,
            ),
        )
        val persistence = RoomAgentPersistence(
            database = database,
            reporter = StoredRecordCorruptionReporter { },
            snapshotDecoder = {
                DecodedStoredRecord(session, "canonical-session")
            },
            checkpointDecoder = {
                DecodedStoredRecord(mismatchedCheckpoint, "canonical-checkpoint")
            },
        )

        assertFailsWith<StoredRecordCorruptionException> {
            persistence.load(session.sessionId)
        }

        assertEquals(
            "legacy-session",
            database.sessionDao().findById(session.sessionId.value)?.payload,
        )
        assertEquals(
            "legacy-checkpoint",
            database.checkpointDao().findById(session.sessionId.value)?.payload,
        )
        database.close()
        check(directory.toFile().deleteRecursively()) {
            "Failed to remove Room rewrite-failure-test directory"
        }
    }

    @Test
    fun listingSchemaFailureDoesNotPartiallyRewriteEarlierRows() = runTest {
        val directory = Files.createTempDirectory("magrathea-room-list-rewrite-failure-")
        val databasePath = directory.resolve("magrathea.db")
        val migratable = roomTestSnapshot("migratable-list-row", updatedAtEpochMs = 20L)
        val incompatible = roomTestSnapshot("incompatible-list-row", updatedAtEpochMs = 10L)
        val codec = AgentSessionSnapshotCodec()
        val incompatiblePayload = codec.encode(incompatible).replaceFirst(
            "\"schemaVersion\":$CURRENT_STORAGE_SCHEMA_VERSION",
            "\"schemaVersion\":${MINIMUM_READABLE_STORAGE_SCHEMA_VERSION - 1}",
        )
        val database = buildMagratheaRoomDatabase(
            Room.databaseBuilder<MagratheaDatabase>(name = databasePath.toString()),
        )
        database.sessionDao().upsert(
            AgentSessionEntity(
                migratable.sessionId.value,
                "legacy-migratable-session",
                migratable.updatedAtEpochMs,
            ),
        )
        database.sessionDao().upsert(
            AgentSessionEntity(
                incompatible.sessionId.value,
                incompatiblePayload,
                incompatible.updatedAtEpochMs,
            ),
        )
        val persistence = RoomAgentPersistence(
            database = database,
            reporter = StoredRecordCorruptionReporter { error("Unexpected corruption: $it") },
            snapshotDecoder = { payload ->
                if (payload == "legacy-migratable-session") {
                    DecodedStoredRecord(migratable, "canonical-migratable-session")
                } else {
                    codec.decodeResult(payload).toStoredRecord()
                }
            },
        )

        val failure = assertFailsWith<StoredRecordSchemaException> {
            persistence.listSessions()
        }

        assertEquals(StoredEnvelopeDecodeFailure.UNSUPPORTED_OLDER_SCHEMA, failure.issue.failure)
        assertEquals(
            "legacy-migratable-session",
            database.sessionDao().findById(migratable.sessionId.value)?.payload,
        )
        assertEquals(
            incompatiblePayload,
            database.sessionDao().findById(incompatible.sessionId.value)?.payload,
        )
        database.close()
        check(directory.toFile().deleteRecursively()) {
            "Failed to remove Room list-rewrite-failure test directory"
        }
    }

    @Test
    fun listingReportsEarlierCorruptionAfterALaterSchemaIssueAbortsTheTransaction() = runTest {
        val directory = Files.createTempDirectory("magrathea-room-list-report-order-")
        val databasePath = directory.resolve("magrathea.db")
        val incompatible = roomTestSnapshot("schema-list-row", updatedAtEpochMs = 20L)
        val incompatiblePayload = AgentSessionSnapshotCodec().encode(incompatible).replaceFirst(
            "\"schemaVersion\":$CURRENT_STORAGE_SCHEMA_VERSION",
            "\"schemaVersion\":${MINIMUM_READABLE_STORAGE_SCHEMA_VERSION - 1}",
        )
        val database = buildMagratheaRoomDatabase(
            Room.databaseBuilder<MagratheaDatabase>(name = databasePath.toString()),
        )
        database.sessionDao().upsert(
            AgentSessionEntity("corrupt-list-row", "not-json", updatedAtEpochMs = 30L),
        )
        database.sessionDao().upsert(
            AgentSessionEntity(
                incompatible.sessionId.value,
                incompatiblePayload,
                incompatible.updatedAtEpochMs,
            ),
        )
        val reports = mutableListOf<StoredRecordCorruption>()
        val persistence = RoomAgentPersistence(
            database = database,
            reporter = StoredRecordCorruptionReporter(reports::add),
        )

        val failure = assertFailsWith<StoredRecordSchemaException> {
            persistence.listSessions()
        }

        assertEquals(StoredEnvelopeDecodeFailure.UNSUPPORTED_OLDER_SCHEMA, failure.issue.failure)
        assertEquals(
            listOf(
                StoredRecordCorruption(
                    StoredRecordKind.SESSION,
                    "corrupt-list-row",
                    StoredRecordCorruptionReason.INVALID_PAYLOAD,
                ),
            ),
            reports,
        )
        database.close()
        check(directory.toFile().deleteRecursively()) {
            "Failed to remove Room list-report-order test directory"
        }
    }

    @Test
    fun atomicCommitCanReplaceAResumableRecordWithATerminalSnapshot() = runTest {
        val directory = Files.createTempDirectory("magrathea-room-atomic-")
        val database = directory.resolve("magrathea.db")
        val snapshot = roomTestSnapshot("atomic-replacement")
        val checkpoint = roomTestCheckpoint(snapshot, turn = 1)
        val store = JvmMagratheaRoom.open(
            database.toString(),
            StoredRecordCorruptionReporter { error("Unexpected corruption: $it") },
        )

        store.persistence.commit(snapshot, checkpoint)
        assertEquals(checkpoint, store.persistence.load(snapshot.sessionId)?.checkpoint)

        store.persistence.commit(snapshot, null)
        val terminalRecord = requireNotNull(store.persistence.load(snapshot.sessionId))
        assertEquals(snapshot, terminalRecord.snapshot)
        assertEquals(null, terminalRecord.checkpoint)

        store.close()
        check(directory.toFile().deleteRecursively()) {
            "Failed to remove Room atomic-test directory"
        }
    }
}

private fun String.toSchemaV6(): String {
    val schema7Fields = listOf(
        ",\"toolResults\":{\"budget\":null,\"clearing\":null,\"maxStubChars\":2000},\"estimationUnit\":\"UTF16_CHARS\"",
        ",\"toolResultClearing\":null",
    )
    return schema7Fields
        .fold(replaceFirst("\"schemaVersion\":8", "\"schemaVersion\":6")) { payload, field -> payload.replace(field, "") }
        .replace(",\"maxOutputTokens\":null", "")
}

/** What a session persisted before schema 8 decodes to: Tool-result stages off, UTF-16 estimation. */
private fun AgentSessionSnapshot.withSchema7ContextManagement(): AgentSessionSnapshot {
    val runtime = request.engine.runtime
    return copy(
        request = request.copy(
            engine = request.engine.copy(
                runtime = runtime.copy(
                    contextManagement = runtime.contextManagement.copy(
                        toolResults = ToolResultContextConfig.DISABLED,
                        estimationUnit = ContextEstimationUnit.UTF16_CHARS,
                    ),
                ),
            ),
        ),
    )
}
