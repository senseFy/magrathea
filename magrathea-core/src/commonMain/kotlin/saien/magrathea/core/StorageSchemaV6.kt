package saien.magrathea.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject

/** Frozen envelope revision selected explicitly by the schema-v6 codecs. */
internal const val STORAGE_SCHEMA_V6_VERSION: Int = 6

/**
 * Schema-v6 owns the envelope and the conversion boundary independently from runtime codecs.
 *
 * The payload remains a JSON object so the still-large Agent domain graph can be isolated behind
 * two explicit mapper functions. A later schema must add a new adapter instead of editing this one.
 */
@Serializable
private data class StoredSessionEnvelopeV6(
    val schemaVersion: Int,
    val sdkVersion: String,
    val payload: JsonObject,
)

@Serializable
private data class StoredCheckpointEnvelopeV6(
    val schemaVersion: Int,
    val sdkVersion: String,
    val payload: JsonObject,
)

internal data class DecodedStorageEnvelopeV6<T>(
    val schemaVersion: Int,
    val sdkVersion: String,
    val payload: T,
    val canonicalPayload: String,
    val canonicalDocument: JsonObject,
)

/** The only production serialization path for logical schema-v6 storage envelopes. */
internal object StorageSchemaV6Adapter {
    fun encodeSession(
        json: Json,
        sdkVersion: String,
        snapshot: AgentSessionSnapshot,
    ): String = json.encodeToString(
        StoredSessionEnvelopeV6.serializer(),
        sessionEnvelope(json, sdkVersion, snapshot),
    )

    fun decodeSession(
        json: Json,
        document: JsonObject,
    ): DecodedStorageEnvelopeV6<AgentSessionSnapshot> {
        val encoded = json.decodeFromJsonElement(StoredSessionEnvelopeV6.serializer(), document)
        val snapshot = encoded.payload.toSessionSnapshotV6(json)
        val canonical = sessionEnvelope(json, encoded.sdkVersion, snapshot)
        return DecodedStorageEnvelopeV6(
            schemaVersion = encoded.schemaVersion,
            sdkVersion = encoded.sdkVersion,
            payload = snapshot,
            canonicalPayload = json.encodeToString(StoredSessionEnvelopeV6.serializer(), canonical),
            canonicalDocument = json.encodeToJsonElement(
                StoredSessionEnvelopeV6.serializer(),
                canonical,
            ).jsonObject,
        )
    }

    fun encodeCheckpoint(
        json: Json,
        sdkVersion: String,
        checkpoint: AgentCheckpoint,
    ): String = json.encodeToString(
        StoredCheckpointEnvelopeV6.serializer(),
        checkpointEnvelope(json, sdkVersion, checkpoint),
    )

    fun decodeCheckpoint(
        json: Json,
        document: JsonObject,
    ): DecodedStorageEnvelopeV6<AgentCheckpoint> {
        val encoded = json.decodeFromJsonElement(StoredCheckpointEnvelopeV6.serializer(), document)
        val checkpoint = encoded.payload.toCheckpointV6(json)
        val canonical = checkpointEnvelope(json, encoded.sdkVersion, checkpoint)
        return DecodedStorageEnvelopeV6(
            schemaVersion = encoded.schemaVersion,
            sdkVersion = encoded.sdkVersion,
            payload = checkpoint,
            canonicalPayload = json.encodeToString(StoredCheckpointEnvelopeV6.serializer(), canonical),
            canonicalDocument = json.encodeToJsonElement(
                StoredCheckpointEnvelopeV6.serializer(),
                canonical,
            ).jsonObject,
        )
    }

    private fun sessionEnvelope(
        json: Json,
        sdkVersion: String,
        snapshot: AgentSessionSnapshot,
    ) = StoredSessionEnvelopeV6(
        schemaVersion = STORAGE_SCHEMA_V6_VERSION,
        sdkVersion = sdkVersion,
        payload = snapshot.toPersistedV6(json),
    )

    private fun checkpointEnvelope(
        json: Json,
        sdkVersion: String,
        checkpoint: AgentCheckpoint,
    ) = StoredCheckpointEnvelopeV6(
        schemaVersion = STORAGE_SCHEMA_V6_VERSION,
        sdkVersion = sdkVersion,
        payload = checkpoint.toPersistedV6(json),
    )
}

/** Explicit schema-v6 mapping seam; replace with immutable leaf DTOs as the graph is decomposed. */
private fun AgentSessionSnapshot.toPersistedV6(json: Json): JsonObject =
    json.encodeToJsonElement(AgentSessionSnapshot.serializer(), this).jsonObject

private fun JsonObject.toSessionSnapshotV6(json: Json): AgentSessionSnapshot =
    json.decodeFromJsonElement(AgentSessionSnapshot.serializer(), this)

/** Explicit schema-v6 mapping seam; replace with immutable leaf DTOs as the graph is decomposed. */
private fun AgentCheckpoint.toPersistedV6(json: Json): JsonObject =
    json.encodeToJsonElement(AgentCheckpoint.serializer(), this).jsonObject

private fun JsonObject.toCheckpointV6(json: Json): AgentCheckpoint =
    json.decodeFromJsonElement(AgentCheckpoint.serializer(), this)
