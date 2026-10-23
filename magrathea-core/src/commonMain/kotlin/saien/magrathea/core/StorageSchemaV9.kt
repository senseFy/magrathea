package saien.magrathea.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject

/** Frozen envelope revision selected explicitly by the schema-v9 codecs. */
internal const val STORAGE_SCHEMA_V9_VERSION: Int = 9

@Serializable
private data class StoredSessionEnvelopeV9(
    val schemaVersion: Int,
    val sdkVersion: String,
    val payload: JsonObject,
)

@Serializable
private data class StoredCheckpointEnvelopeV9(
    val schemaVersion: Int,
    val sdkVersion: String,
    val payload: JsonObject,
)

internal data class DecodedStorageEnvelopeV9<T>(
    val schemaVersion: Int,
    val sdkVersion: String,
    val payload: T,
    val canonicalPayload: String,
    val canonicalDocument: JsonObject,
)

/** The production serialization path while schema 9 is current; migrations never call it. */
internal object StorageSchemaV9Adapter {
    fun encodeSession(
        json: Json,
        sdkVersion: String,
        snapshot: AgentSessionSnapshot,
    ): String = json.encodeToString(
        StoredSessionEnvelopeV9.serializer(),
        sessionEnvelope(json, sdkVersion, snapshot),
    )

    fun decodeSession(
        json: Json,
        document: JsonObject,
    ): DecodedStorageEnvelopeV9<AgentSessionSnapshot> {
        val encoded = json.decodeFromJsonElement(StoredSessionEnvelopeV9.serializer(), document)
        val snapshot = encoded.payload.toSessionSnapshotV9(json)
        val canonical = sessionEnvelope(json, encoded.sdkVersion, snapshot)
        return DecodedStorageEnvelopeV9(
            schemaVersion = encoded.schemaVersion,
            sdkVersion = encoded.sdkVersion,
            payload = snapshot,
            canonicalPayload = json.encodeToString(StoredSessionEnvelopeV9.serializer(), canonical),
            canonicalDocument = json.encodeToJsonElement(
                StoredSessionEnvelopeV9.serializer(),
                canonical,
            ).jsonObject,
        )
    }

    fun encodeCheckpoint(
        json: Json,
        sdkVersion: String,
        checkpoint: AgentCheckpoint,
    ): String = json.encodeToString(
        StoredCheckpointEnvelopeV9.serializer(),
        checkpointEnvelope(json, sdkVersion, checkpoint),
    )

    fun decodeCheckpoint(
        json: Json,
        document: JsonObject,
    ): DecodedStorageEnvelopeV9<AgentCheckpoint> {
        val encoded = json.decodeFromJsonElement(StoredCheckpointEnvelopeV9.serializer(), document)
        val checkpoint = encoded.payload.toCheckpointV9(json)
        val canonical = checkpointEnvelope(json, encoded.sdkVersion, checkpoint)
        return DecodedStorageEnvelopeV9(
            schemaVersion = encoded.schemaVersion,
            sdkVersion = encoded.sdkVersion,
            payload = checkpoint,
            canonicalPayload = json.encodeToString(StoredCheckpointEnvelopeV9.serializer(), canonical),
            canonicalDocument = json.encodeToJsonElement(
                StoredCheckpointEnvelopeV9.serializer(),
                canonical,
            ).jsonObject,
        )
    }

    private fun sessionEnvelope(
        json: Json,
        sdkVersion: String,
        snapshot: AgentSessionSnapshot,
    ) = StoredSessionEnvelopeV9(
        schemaVersion = STORAGE_SCHEMA_V9_VERSION,
        sdkVersion = sdkVersion,
        payload = snapshot.toPersistedV9(json),
    )

    private fun checkpointEnvelope(
        json: Json,
        sdkVersion: String,
        checkpoint: AgentCheckpoint,
    ) = StoredCheckpointEnvelopeV9(
        schemaVersion = STORAGE_SCHEMA_V9_VERSION,
        sdkVersion = sdkVersion,
        payload = checkpoint.toPersistedV9(json),
    )
}

private fun AgentSessionSnapshot.toPersistedV9(json: Json): JsonObject =
    json.encodeToJsonElement(AgentSessionSnapshot.serializer(), this).jsonObject

private fun JsonObject.toSessionSnapshotV9(json: Json): AgentSessionSnapshot =
    json.decodeFromJsonElement(AgentSessionSnapshot.serializer(), this)

private fun AgentCheckpoint.toPersistedV9(json: Json): JsonObject =
    json.encodeToJsonElement(AgentCheckpoint.serializer(), this).jsonObject

private fun JsonObject.toCheckpointV9(json: Json): AgentCheckpoint =
    json.decodeFromJsonElement(AgentCheckpoint.serializer(), this)
