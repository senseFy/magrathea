package saien.magrathea.core

import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class StoredEnvelopeContractTest {
    private val json = Json {
        classDiscriminator = "type"
        encodeDefaults = false
        ignoreUnknownKeys = true
    }
    private val sessionCodec = AgentSessionSnapshotCodec(json, sdkVersion = "test-sdk")
    private val checkpointCodec = AgentCheckpointCodec(json, sdkVersion = "test-sdk")

    @Test
    fun sessionCodec_roundTripsCurrentVersionedEnvelope() {
        val expected = snapshot()

        val encoded = sessionCodec.encode(expected)
        val envelope = json.parseToJsonElement(encoded).jsonObject

        assertEquals(CURRENT_STORAGE_SCHEMA_VERSION, envelope.getValue("schemaVersion").jsonPrimitive.content.toInt())
        assertEquals("test-sdk", envelope.getValue("sdkVersion").jsonPrimitive.content)
        assertEquals(expected, sessionCodec.decode(encoded))
    }

    @Test
    fun checkpointCodec_roundTripsCurrentVersionedEnvelope() {
        val snapshot = snapshot()
        val expected = AgentCheckpoint(snapshot.sessionId, turn = 3, state = snapshot.state.copy(turn = 3))

        val encoded = checkpointCodec.encode(expected)
        val envelope = json.parseToJsonElement(encoded).jsonObject

        assertEquals(CURRENT_STORAGE_SCHEMA_VERSION, envelope.getValue("schemaVersion").jsonPrimitive.content.toInt())
        assertEquals("test-sdk", envelope.getValue("sdkVersion").jsonPrimitive.content)
        assertEquals(expected, checkpointCodec.decode(encoded))
    }

    @Test
    fun codecs_rejectPayloadsMissingAnyCanonicalCurrentSchemaField() {
        val session = sessionCodec.encode(snapshot())
        val missingUsage = session.replace(
            ",\"usage\":{\"inputTokens\":null,\"outputTokens\":null,\"reasoningTokens\":null}",
            "",
        )
        val missingTools = session.replace(",\"tools\":[]", "")

        assertTrue(missingUsage != session)
        assertTrue(missingTools != session)
        assertFailsWith<SerializationException> { sessionCodec.decode(missingUsage) }
        assertFailsWith<SerializationException> { sessionCodec.decode(missingTools) }

        val checkpoint = AgentCheckpoint(snapshot().sessionId, turn = 0, state = snapshot().state)
        val encodedCheckpoint = checkpointCodec.encode(checkpoint)
        val missingRetryCount = encodedCheckpoint.replace(",\"retryCount\":0", "")
        assertTrue(missingRetryCount != encodedCheckpoint)
        assertFailsWith<SerializationException> { checkpointCodec.decode(missingRetryCount) }
    }

    @Test
    fun sessionCodec_alwaysPersistsGeneratedIdentityAndTimestamps() {
        val message = AgentMessage(role = MessageRole.USER, parts = listOf(TextPart("hello")))
        val request = AgentRequest(
            messages = listOf(message),
            model = ModelDescriptor(provider = "test", model = "test"),
        )
        val snapshot = AgentSessionSnapshot(
            sessionId = request.sessionId,
            request = request,
            state = AgentStateSnapshot(messages = request.messages),
        )

        val encoded = sessionCodec.encode(snapshot)
        val payload = json.parseToJsonElement(encoded).jsonObject.getValue("payload").jsonObject
        val requestPayload = payload.getValue("request").jsonObject
        val messagePayload = requestPayload.getValue("messages").jsonArray.single().jsonObject

        assertTrue("updatedAtEpochMs" in payload)
        assertTrue("sessionId" in requestPayload)
        assertTrue("id" in messagePayload)
        assertTrue("createdAtEpochMs" in messagePayload)
        assertEquals(snapshot, sessionCodec.decode(encoded))
    }

    @Test
    fun sessionCodec_rejectsRawSnapshotWithoutEnvelope() {
        val rawSnapshot = json.encodeToString(AgentSessionSnapshot.serializer(), snapshot())

        assertFailsWith<SerializationException> {
            sessionCodec.decode(rawSnapshot)
        }
    }

    @Test
    fun sessionCodec_rejectsUnsupportedSchemaVersion() {
        val unsupported = sessionCodec.encode(snapshot())
            .replaceFirst("\"schemaVersion\":3", "\"schemaVersion\":4")

        assertFailsWith<SerializationException> {
            sessionCodec.decode(unsupported)
        }
    }

    @Test
    fun sessionCodec_rejectsUnknownEnvelopeAndPayloadFieldsEvenWithPermissiveJson() {
        val encoded = sessionCodec.encode(snapshot())
        val unknownEnvelope = encoded.dropLast(1) + ",\"futureEnvelopeField\":true}"
        val unknownPayload = encoded.replaceFirst("\"payload\":{", "\"payload\":{\"futurePayloadField\":true,")

        assertFailsWith<SerializationException> {
            sessionCodec.decode(unknownEnvelope)
        }
        assertFailsWith<SerializationException> {
            sessionCodec.decode(unknownPayload)
        }
    }

    @Test
    fun codecs_rejectCorruptJson() {
        assertFailsWith<SerializationException> {
            sessionCodec.decode("{\"schemaVersion\":3")
        }
        assertFailsWith<SerializationException> {
            checkpointCodec.decode("not-json")
        }
    }

    @Test
    fun sessionCodec_rejectsMismatchedSnapshotIdentityOnEncodeAndDecode() {
        val mismatched = snapshot(requestSessionId = AgentSessionId("different-request-session"))

        assertFailsWith<SerializationException> {
            sessionCodec.encode(mismatched)
        }

        val encoded = sessionCodec.encode(snapshot())
        val corrupted = encoded.replaceFirst("session-contract", "different-envelope-session")
        assertFailsWith<SerializationException> {
            sessionCodec.decode(corrupted)
        }
    }

    @Test
    fun checkpointCodec_rejectsMismatchedAndNegativeTurnsOnEncodeAndDecode() {
        val snapshot = snapshot()
        val mismatched = AgentCheckpoint(snapshot.sessionId, turn = 2, state = snapshot.state.copy(turn = 1))
        assertFailsWith<SerializationException> { checkpointCodec.encode(mismatched) }

        val valid = AgentCheckpoint(snapshot.sessionId, turn = 1, state = snapshot.state.copy(turn = 1))
        val corrupt = checkpointCodec.encode(valid).replaceFirst("\"turn\":1", "\"turn\":-1")
        assertFailsWith<SerializationException> { checkpointCodec.decode(corrupt) }
    }

    private fun snapshot(
        requestSessionId: AgentSessionId = AgentSessionId("session-contract"),
    ): AgentSessionSnapshot {
        val sessionId = AgentSessionId("session-contract")
        val message = AgentMessage(
            id = "message-contract",
            role = MessageRole.USER,
            parts = listOf(TextPart("hello")),
            createdAtEpochMs = 1L,
        )
        val request = AgentRequest(
            sessionId = requestSessionId,
            messages = listOf(message),
            model = ModelDescriptor(provider = "test", model = "test"),
        )
        return AgentSessionSnapshot(
            sessionId = sessionId,
            request = request,
            state = AgentStateSnapshot(messages = listOf(message)),
            updatedAtEpochMs = 2L,
        )
    }
}
