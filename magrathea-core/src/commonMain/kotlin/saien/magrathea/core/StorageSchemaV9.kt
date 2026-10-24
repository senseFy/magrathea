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

/**
 * Background envelopes carry an explicit kind discriminator: a future migration must never
 * infer the payload type from field shapes (ADR-005/ADR-019).
 */
@Serializable
private data class StoredBackgroundExecutionEnvelopeV9(
    val schemaVersion: Int,
    val sdkVersion: String,
    val kind: String,
    val payload: JsonObject,
)

@Serializable
private data class StoredBackgroundOriginEnvelopeV9(
    val schemaVersion: Int,
    val sdkVersion: String,
    val kind: String,
    val payload: JsonObject,
)

private const val STORED_BACKGROUND_EXECUTION_KIND = "background_execution"
private const val STORED_BACKGROUND_ORIGIN_KIND = "background_origin"

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

    fun encodeBackgroundExecution(
        json: Json,
        sdkVersion: String,
        record: BackgroundExecutionRecord,
    ): String = json.encodeToString(
        StoredBackgroundExecutionEnvelopeV9.serializer(),
        backgroundExecutionEnvelope(json, sdkVersion, record),
    )

    fun decodeBackgroundExecution(
        json: Json,
        document: JsonObject,
    ): DecodedStorageEnvelopeV9<BackgroundExecutionRecord> {
        val encoded = json.decodeFromJsonElement(
            StoredBackgroundExecutionEnvelopeV9.serializer(),
            document,
        )
        require(encoded.kind == STORED_BACKGROUND_EXECUTION_KIND) {
            "Stored background execution envelope kind must be $STORED_BACKGROUND_EXECUTION_KIND"
        }
        val record = encoded.payload.toBackgroundExecutionV9(json)
        val canonical = backgroundExecutionEnvelope(json, encoded.sdkVersion, record)
        return DecodedStorageEnvelopeV9(
            schemaVersion = encoded.schemaVersion,
            sdkVersion = encoded.sdkVersion,
            payload = record,
            canonicalPayload = json.encodeToString(
                StoredBackgroundExecutionEnvelopeV9.serializer(),
                canonical,
            ),
            canonicalDocument = json.encodeToJsonElement(
                StoredBackgroundExecutionEnvelopeV9.serializer(),
                canonical,
            ).jsonObject,
        )
    }

    fun encodeBackgroundOrigin(
        json: Json,
        sdkVersion: String,
        origin: BackgroundOriginContext,
    ): String = json.encodeToString(
        StoredBackgroundOriginEnvelopeV9.serializer(),
        backgroundOriginEnvelope(json, sdkVersion, origin),
    )

    fun decodeBackgroundOrigin(
        json: Json,
        document: JsonObject,
    ): DecodedStorageEnvelopeV9<BackgroundOriginContext> {
        val encoded = json.decodeFromJsonElement(
            StoredBackgroundOriginEnvelopeV9.serializer(),
            document,
        )
        require(encoded.kind == STORED_BACKGROUND_ORIGIN_KIND) {
            "Stored background origin envelope kind must be $STORED_BACKGROUND_ORIGIN_KIND"
        }
        val origin = encoded.payload.toBackgroundOriginV9(json)
        val canonical = backgroundOriginEnvelope(json, encoded.sdkVersion, origin)
        return DecodedStorageEnvelopeV9(
            schemaVersion = encoded.schemaVersion,
            sdkVersion = encoded.sdkVersion,
            payload = origin,
            canonicalPayload = json.encodeToString(
                StoredBackgroundOriginEnvelopeV9.serializer(),
                canonical,
            ),
            canonicalDocument = json.encodeToJsonElement(
                StoredBackgroundOriginEnvelopeV9.serializer(),
                canonical,
            ).jsonObject,
        )
    }

    private fun backgroundExecutionEnvelope(
        json: Json,
        sdkVersion: String,
        record: BackgroundExecutionRecord,
    ) = StoredBackgroundExecutionEnvelopeV9(
        schemaVersion = STORAGE_SCHEMA_V9_VERSION,
        sdkVersion = sdkVersion,
        kind = STORED_BACKGROUND_EXECUTION_KIND,
        payload = record.toPersistedV9(json),
    )

    private fun backgroundOriginEnvelope(
        json: Json,
        sdkVersion: String,
        origin: BackgroundOriginContext,
    ) = StoredBackgroundOriginEnvelopeV9(
        schemaVersion = STORAGE_SCHEMA_V9_VERSION,
        sdkVersion = sdkVersion,
        kind = STORED_BACKGROUND_ORIGIN_KIND,
        payload = origin.toPersistedV9(json),
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

private fun BackgroundExecutionRecord.toPersistedV9(json: Json): JsonObject =
    json.encodeToJsonElement(BackgroundExecutionRecord.serializer(), this).jsonObject

private fun JsonObject.toBackgroundExecutionV9(json: Json): BackgroundExecutionRecord =
    json.decodeFromJsonElement(BackgroundExecutionRecord.serializer(), this)

private fun BackgroundOriginContext.toPersistedV9(json: Json): JsonObject =
    json.encodeToJsonElement(BackgroundOriginContext.serializer(), this).jsonObject

private fun JsonObject.toBackgroundOriginV9(json: Json): BackgroundOriginContext =
    json.decodeFromJsonElement(BackgroundOriginContext.serializer(), this)
