package saien.magrathea.storage.room

import saien.magrathea.core.AgentCheckpoint
import saien.magrathea.core.AgentEngineConfig
import saien.magrathea.core.AgentMessage
import saien.magrathea.core.AgentRequest
import saien.magrathea.core.AgentResumeCursor
import saien.magrathea.core.AgentResumePhase
import saien.magrathea.core.AgentRunId
import saien.magrathea.core.AgentSessionId
import saien.magrathea.core.AgentSessionSnapshot
import saien.magrathea.core.AgentStateSnapshot
import saien.magrathea.core.MessageRole
import saien.magrathea.core.ModelDescriptor
import saien.magrathea.core.ProviderConfig
import saien.magrathea.core.StoredEnvelopeDecodeFailure
import saien.magrathea.core.TextPart
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RoomStoreContractTest {
    @Test
    fun platformStorageComponents_rejectTraversalAndAbsolutePaths() {
        listOf("", ".hidden", "../escape", "nested/path", "/absolute", "秘密").forEach { value ->
            assertFailsWith<IllegalArgumentException> {
                value.requireSafeStorageComponent("databaseName")
            }
        }
        assertEquals("chatbot-v1.db", "chatbot-v1.db".requireSafeStorageComponent("databaseName"))
    }

    @Test
    fun fixtureCheckpointSharesTheSessionRunIdentityAndExactTurn() {
        val snapshot = roomTestSnapshot("identity")
        val checkpoint = roomTestCheckpoint(snapshot, turn = 3)

        assertEquals(snapshot.sessionId, checkpoint.sessionId)
        assertEquals(snapshot.runId, checkpoint.runId)
        assertEquals(3, checkpoint.cursor.turn)
        assertEquals(AgentResumePhase.MODEL_PENDING, checkpoint.cursor.phase)
        assertEquals(3, checkpoint.state.turn)
    }

    @Test
    fun schemaIssueRequiresSanitizedIdentityAndConsistentPositiveVersions() {
        val valid = StoredRecordSchemaIssue(
            kind = StoredRecordKind.SESSION,
            sessionId = "safe-session",
            failure = StoredEnvelopeDecodeFailure.UNSUPPORTED_OLDER_SCHEMA,
            storedSchemaVersion = 6,
            currentSchemaVersion = 7,
        )
        assertEquals("safe-session", valid.sessionId)

        listOf<() -> Unit>(
            {
                StoredRecordSchemaIssue(
                    StoredRecordKind.SESSION,
                    "unsafe/session",
                    StoredEnvelopeDecodeFailure.UNSUPPORTED_OLDER_SCHEMA,
                    6,
                    7,
                )
            },
            {
                StoredRecordSchemaIssue(
                    StoredRecordKind.SESSION,
                    null,
                    StoredEnvelopeDecodeFailure.CORRUPT,
                    6,
                    7,
                )
            },
            {
                StoredRecordSchemaIssue(
                    StoredRecordKind.SESSION,
                    null,
                    StoredEnvelopeDecodeFailure.UNSUPPORTED_OLDER_SCHEMA,
                    7,
                    7,
                )
            },
            {
                StoredRecordSchemaIssue(
                    StoredRecordKind.SESSION,
                    null,
                    StoredEnvelopeDecodeFailure.UNSUPPORTED_NEWER_SCHEMA,
                    7,
                    7,
                )
            },
            {
                StoredRecordSchemaIssue(
                    StoredRecordKind.SESSION,
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
}

internal fun roomTestSnapshot(
    sessionIdValue: String,
    providerConfig: ProviderConfig = ProviderConfig(),
    updatedAtEpochMs: Long = 1L,
): AgentSessionSnapshot {
    val sessionId = AgentSessionId(sessionIdValue)
    val request = AgentRequest(
        sessionId = sessionId,
        messages = listOf(
            AgentMessage(role = MessageRole.USER, parts = listOf(TextPart("hello"))),
        ),
        model = ModelDescriptor(provider = "test-provider", model = "test-model"),
        engine = AgentEngineConfig(provider = providerConfig),
    )
    return AgentSessionSnapshot(
        sessionId = sessionId,
        runId = AgentRunId("$sessionIdValue-run"),
        request = request,
        state = AgentStateSnapshot(messages = request.messages),
        updatedAtEpochMs = updatedAtEpochMs,
    )
}

internal fun roomTestCheckpoint(
    snapshot: AgentSessionSnapshot,
    turn: Int,
): AgentCheckpoint = AgentCheckpoint(
    sessionId = snapshot.sessionId,
    runId = snapshot.runId,
    cursor = AgentResumeCursor(turn, AgentResumePhase.MODEL_PENDING),
    state = snapshot.state.copy(turn = turn),
)
