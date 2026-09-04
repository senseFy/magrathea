package saien.magrathea.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject

/** Frozen envelope revision selected explicitly by the schema-v7 codecs. */
internal const val STORAGE_SCHEMA_V7_VERSION: Int = 7

@Serializable
private data class StoredSessionEnvelopeV7(
    val schemaVersion: Int,
    val sdkVersion: String,
    val payload: JsonObject,
)

@Serializable
private data class StoredCheckpointEnvelopeV7(
    val schemaVersion: Int,
    val sdkVersion: String,
    val payload: JsonObject,
)

internal data class DecodedStorageEnvelopeV7<T>(
    val schemaVersion: Int,
    val sdkVersion: String,
    val payload: T,
    val canonicalPayload: String,
    val canonicalDocument: JsonObject,
)

/** The production serialization path while schema 7 is current; migrations never call it. */
internal object StorageSchemaV7Adapter {
    fun encodeSession(
        json: Json,
        sdkVersion: String,
        snapshot: AgentSessionSnapshot,
    ): String = json.encodeToString(
        StoredSessionEnvelopeV7.serializer(),
        sessionEnvelope(json, sdkVersion, snapshot),
    )

    fun decodeSession(
        json: Json,
        document: JsonObject,
    ): DecodedStorageEnvelopeV7<AgentSessionSnapshot> {
        val encoded = json.decodeFromJsonElement(StoredSessionEnvelopeV7.serializer(), document)
        val snapshot = encoded.payload.toSessionSnapshotV7(json)
        val canonical = sessionEnvelope(json, encoded.sdkVersion, snapshot)
        return DecodedStorageEnvelopeV7(
            schemaVersion = encoded.schemaVersion,
            sdkVersion = encoded.sdkVersion,
            payload = snapshot,
            canonicalPayload = json.encodeToString(StoredSessionEnvelopeV7.serializer(), canonical),
            canonicalDocument = json.encodeToJsonElement(
                StoredSessionEnvelopeV7.serializer(),
                canonical,
            ).jsonObject,
        )
    }

    fun encodeCheckpoint(
        json: Json,
        sdkVersion: String,
        checkpoint: AgentCheckpoint,
    ): String = json.encodeToString(
        StoredCheckpointEnvelopeV7.serializer(),
        checkpointEnvelope(json, sdkVersion, checkpoint),
    )

    fun decodeCheckpoint(
        json: Json,
        document: JsonObject,
    ): DecodedStorageEnvelopeV7<AgentCheckpoint> {
        val encoded = json.decodeFromJsonElement(StoredCheckpointEnvelopeV7.serializer(), document)
        val checkpoint = encoded.payload.toCheckpointV7(json)
        val canonical = checkpointEnvelope(json, encoded.sdkVersion, checkpoint)
        return DecodedStorageEnvelopeV7(
            schemaVersion = encoded.schemaVersion,
            sdkVersion = encoded.sdkVersion,
            payload = checkpoint,
            canonicalPayload = json.encodeToString(StoredCheckpointEnvelopeV7.serializer(), canonical),
            canonicalDocument = json.encodeToJsonElement(
                StoredCheckpointEnvelopeV7.serializer(),
                canonical,
            ).jsonObject,
        )
    }

    private fun sessionEnvelope(
        json: Json,
        sdkVersion: String,
        snapshot: AgentSessionSnapshot,
    ) = StoredSessionEnvelopeV7(
        schemaVersion = STORAGE_SCHEMA_V7_VERSION,
        sdkVersion = sdkVersion,
        payload = snapshot.toPersistedV7(json),
    )

    private fun checkpointEnvelope(
        json: Json,
        sdkVersion: String,
        checkpoint: AgentCheckpoint,
    ) = StoredCheckpointEnvelopeV7(
        schemaVersion = STORAGE_SCHEMA_V7_VERSION,
        sdkVersion = sdkVersion,
        payload = checkpoint.toPersistedV7(json),
    )
}

private fun AgentSessionSnapshot.toPersistedV7(json: Json): JsonObject =
    json.encodeToJsonElement(AgentSessionSnapshot.serializer(), this).jsonObject

private fun JsonObject.toSessionSnapshotV7(json: Json): AgentSessionSnapshot =
    json.decodeFromJsonElement(AgentSessionSnapshot.serializer(), this)

private fun AgentCheckpoint.toPersistedV7(json: Json): JsonObject =
    json.encodeToJsonElement(AgentCheckpoint.serializer(), this).jsonObject

private fun JsonObject.toCheckpointV7(json: Json): AgentCheckpoint =
    json.decodeFromJsonElement(AgentCheckpoint.serializer(), this)
