package saien.magrathea.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject

/** Frozen envelope revision selected explicitly by the schema-v8 codecs. */
internal const val STORAGE_SCHEMA_V8_VERSION: Int = 8

@Serializable
private data class StoredSessionEnvelopeV8(
    val schemaVersion: Int,
    val sdkVersion: String,
    val payload: JsonObject,
)

@Serializable
private data class StoredCheckpointEnvelopeV8(
    val schemaVersion: Int,
    val sdkVersion: String,
    val payload: JsonObject,
)

internal data class DecodedStorageEnvelopeV8<T>(
    val schemaVersion: Int,
    val sdkVersion: String,
    val payload: T,
    val canonicalPayload: String,
    val canonicalDocument: JsonObject,
)

/** The production serialization path while schema 8 is current; migrations never call it. */
internal object StorageSchemaV8Adapter {
    fun encodeSession(
        json: Json,
        sdkVersion: String,
        snapshot: AgentSessionSnapshot,
    ): String = json.encodeToString(
        StoredSessionEnvelopeV8.serializer(),
        sessionEnvelope(json, sdkVersion, snapshot),
    )

    fun decodeSession(
        json: Json,
        document: JsonObject,
    ): DecodedStorageEnvelopeV8<AgentSessionSnapshot> {
        val encoded = json.decodeFromJsonElement(StoredSessionEnvelopeV8.serializer(), document)
        val snapshot = encoded.payload.toSessionSnapshotV8(json)
        val canonical = sessionEnvelope(json, encoded.sdkVersion, snapshot)
        return DecodedStorageEnvelopeV8(
            schemaVersion = encoded.schemaVersion,
            sdkVersion = encoded.sdkVersion,
            payload = snapshot,
            canonicalPayload = json.encodeToString(StoredSessionEnvelopeV8.serializer(), canonical),
            canonicalDocument = json.encodeToJsonElement(
                StoredSessionEnvelopeV8.serializer(),
                canonical,
            ).jsonObject,
        )
    }

    fun encodeCheckpoint(
        json: Json,
        sdkVersion: String,
        checkpoint: AgentCheckpoint,
    ): String = json.encodeToString(
        StoredCheckpointEnvelopeV8.serializer(),
        checkpointEnvelope(json, sdkVersion, checkpoint),
    )

    fun decodeCheckpoint(
        json: Json,
        document: JsonObject,
    ): DecodedStorageEnvelopeV8<AgentCheckpoint> {
        val encoded = json.decodeFromJsonElement(StoredCheckpointEnvelopeV8.serializer(), document)
        val checkpoint = encoded.payload.toCheckpointV8(json)
        val canonical = checkpointEnvelope(json, encoded.sdkVersion, checkpoint)
        return DecodedStorageEnvelopeV8(
            schemaVersion = encoded.schemaVersion,
            sdkVersion = encoded.sdkVersion,
            payload = checkpoint,
            canonicalPayload = json.encodeToString(StoredCheckpointEnvelopeV8.serializer(), canonical),
            canonicalDocument = json.encodeToJsonElement(
                StoredCheckpointEnvelopeV8.serializer(),
                canonical,
            ).jsonObject,
        )
    }

    private fun sessionEnvelope(
        json: Json,
        sdkVersion: String,
        snapshot: AgentSessionSnapshot,
    ) = StoredSessionEnvelopeV8(
        schemaVersion = STORAGE_SCHEMA_V8_VERSION,
        sdkVersion = sdkVersion,
        payload = snapshot.toPersistedV8(json),
    )

    private fun checkpointEnvelope(
        json: Json,
        sdkVersion: String,
        checkpoint: AgentCheckpoint,
    ) = StoredCheckpointEnvelopeV8(
        schemaVersion = STORAGE_SCHEMA_V8_VERSION,
        sdkVersion = sdkVersion,
        payload = checkpoint.toPersistedV8(json),
    )
}

private fun AgentSessionSnapshot.toPersistedV8(json: Json): JsonObject =
    json.encodeToJsonElement(AgentSessionSnapshot.serializer(), this).jsonObject

private fun JsonObject.toSessionSnapshotV8(json: Json): AgentSessionSnapshot =
    json.decodeFromJsonElement(AgentSessionSnapshot.serializer(), this)

private fun AgentCheckpoint.toPersistedV8(json: Json): JsonObject =
    json.encodeToJsonElement(AgentCheckpoint.serializer(), this).jsonObject

private fun JsonObject.toCheckpointV8(json: Json): AgentCheckpoint =
    json.decodeFromJsonElement(AgentCheckpoint.serializer(), this)
