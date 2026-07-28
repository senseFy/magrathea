@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package saien.magrathea.core

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.SerializationException
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement

/** Executes, resumes, and cancels Agent sessions while streaming canonical lifecycle events. */
interface AgentRunner {
    fun run(request: AgentRequest): Flow<AgentEvent>
    suspend fun resume(sessionId: AgentSessionId): Flow<AgentEvent>
    suspend fun cancel(sessionId: AgentSessionId)
}

interface AgentInterceptor {
    suspend fun beforeModelCall(context: AgentRuntimeContext): AgentRuntimeContext = context
    suspend fun onModelChunk(context: AgentRuntimeContext, message: AgentMessage): AgentMessage = message
    suspend fun afterModelCall(context: AgentRuntimeContext, message: AgentMessage): AgentMessage = message
    suspend fun beforeToolCall(context: ToolRuntimeContext): ToolRuntimeContext = context
    suspend fun afterToolCall(context: ToolRuntimeContext, result: ToolExecutionResult): ToolExecutionResult = result
}

fun interface ContextTransformer {
    suspend fun transform(messages: List<AgentMessage>): List<AgentMessage>
}

fun interface ReplayPolicy {
    suspend fun transform(messages: List<AgentMessage>, model: ModelDescriptor): List<AgentMessage>
}

@Serializable
enum class ToolCallLifecycle {
    STARTED,
    DELTA,
    FINALIZED,
    EXECUTED,
    RESOLVED,
    REPLAYED,
}

fun interface FollowUpMessageProvider {
    suspend fun nextMessages(context: AgentRuntimeContext): List<AgentMessage>
}

fun interface SteeringMessageProvider {
    suspend fun nextMessages(context: AgentRuntimeContext): List<AgentMessage>
}

/** Why the runtime is preparing or rebuilding the Provider context projection. */
enum class ContextPreparationReason {
    PROACTIVE,
    OVERFLOW_RECOVERY,
}

/** Observable outcome of one context preparation pass. */
enum class ContextPreparationAction {
    UNCHANGED,
    REUSED,
    COMPACTED,
    FAILED_OPEN,
}

/** Non-sensitive reason why proactive context preparation continued without a new summary. */
enum class ContextPreparationFailure {
    SUMMARY_FAILED,
    NO_SAFE_CUT,
}

data class ContextPreparationRequest(
    val request: AgentRequest,
    val state: AgentStateSnapshot,
    val turn: Int,
    val reason: ContextPreparationReason = ContextPreparationReason.PROACTIVE,
)

data class ContextPreparationResult(
    val messages: List<AgentMessage>,
    val state: ContextManagementState,
    val estimatedInputTokens: Long?,
    val inputLimitTokens: Long?,
    val action: ContextPreparationAction,
    val summaryUsage: TokenUsage = TokenUsage(),
    val failure: ContextPreparationFailure? = null,
)

/**
 * Provider-neutral, safely serialized input for a semantic context summary. Implementations may
 * use the active model, a cheaper dedicated model, or an entirely local summarizer.
 */
data class ContextSummaryRequest(
    val sessionId: AgentSessionId,
    val model: ModelDescriptor,
    val provider: ProviderConfig,
    val conversation: String,
    val previousSummary: String?,
    val maxOutputTokens: Int,
    val generation: Long,
    val turn: Int,
)

data class ContextSummaryResult(
    val summary: String,
    val usage: TokenUsage = TokenUsage(),
)

fun interface ContextSummarizer {
    suspend fun summarize(request: ContextSummaryRequest): ContextSummaryResult
}

/** Builds the bounded Provider context while leaving authoritative conversation history intact. */
fun interface ContextManager {
    suspend fun prepare(request: ContextPreparationRequest): ContextPreparationResult
}

interface RetryPolicy {
    suspend fun shouldRetry(attempt: Int, error: Throwable): Boolean
    suspend fun backoffDelayMs(attempt: Int): Long = (attempt * 250L).coerceAtMost(2_000L)
}

/** Persistent or ephemeral storage for the authoritative snapshot of each Agent session. */
interface SessionStore {
    suspend fun saveSession(snapshot: AgentSessionSnapshot)
    suspend fun loadSession(sessionId: AgentSessionId): AgentSessionSnapshot?
    suspend fun listSessions(): List<AgentSessionSnapshot>
    suspend fun deleteSession(sessionId: AgentSessionId)
    suspend fun clear()
}

/** Storage for the latest resumable execution checkpoint of each Agent session. */
interface CheckpointStore {
    suspend fun saveCheckpoint(checkpoint: AgentCheckpoint)
    suspend fun loadLatestCheckpoint(sessionId: AgentSessionId): AgentCheckpoint?
    suspend fun deleteSession(sessionId: AgentSessionId)
    suspend fun clear()
}

@Serializable
enum class AgentFailureCode {
    NOT_FOUND,
    INVALID_STATE,
    CONTEXT_LIMIT,
    CREDENTIAL_UNAVAILABLE,
    PROVIDER_NOT_FOUND,
    PROVIDER_AUTH,
    PROVIDER_RATE_LIMIT,
    TIMEOUT,
    PROVIDER_NETWORK,
    PROVIDER_PROTOCOL,
    PROVIDER_CLIENT,
    PROVIDER_SERVER,
    STORAGE,
    INTERNAL,
}

interface ToolApprovalGateway {
    suspend fun requestApproval(request: ToolApprovalRequest): ToolApprovalDecision
}

interface ToolPermissionGateway {
    suspend fun ensurePermission(permission: String): Boolean
}

/** Resolves a credential reference at call time without placing the credential in Agent state. */
fun interface CredentialProvider {
    suspend fun resolve(ref: CredentialRef): ProviderCredential
}

/** A registered Tool definition and its side-effecting execution boundary. */
interface ToolExecutor {
    val definition: ToolDefinition
    suspend fun execute(request: ToolExecutionRequest): ToolExecutionResult
}

/**
 * Resolves the current set of Tools available to an Agent runner.
 *
 * Implementations may update the set between turns. A Tool advertised in an [AgentRequest] must
 * still resolve through [find] when the runtime executes it.
 */
interface ToolRegistry {
    fun definitions(): List<ToolDefinition>
    fun find(name: String): ToolExecutor?
}

@Serializable
data class AgentSessionSnapshot(
    val sessionId: AgentSessionId,
    val request: AgentRequest,
    val state: AgentStateSnapshot,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val updatedAtEpochMs: Long = SystemEpochClock.nowEpochMs(),
)

const val CURRENT_STORAGE_SCHEMA_VERSION: Int = 3

@Serializable
data class StoredSessionEnvelope(
    val schemaVersion: Int,
    val sdkVersion: String,
    val payload: AgentSessionSnapshot,
)

@Serializable
data class StoredCheckpointEnvelope(
    val schemaVersion: Int,
    val sdkVersion: String,
    val payload: AgentCheckpoint,
)

class AgentSessionSnapshotCodec(
    json: Json = Json,
    private val sdkVersion: String = MAGRATHEA_CORE_SDK_VERSION,
) {
    private val json = Json(json) {
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = false
        isLenient = false
        coerceInputValues = false
    }

    init {
        require(sdkVersion.isNotBlank()) { "sdkVersion must not be blank" }
    }

    fun encode(snapshot: AgentSessionSnapshot): String {
        validateSessionIdentity(snapshot)
        return json.encodeToString(
            StoredSessionEnvelope.serializer(),
            StoredSessionEnvelope(
                schemaVersion = CURRENT_STORAGE_SCHEMA_VERSION,
                sdkVersion = sdkVersion,
                payload = snapshot,
            ),
        )
    }

    fun decode(payload: String): AgentSessionSnapshot {
        val encoded = json.parseToJsonElement(payload)
        val envelope = json.decodeFromJsonElement(StoredSessionEnvelope.serializer(), encoded)
        validateEnvelope(envelope.schemaVersion, envelope.sdkVersion)
        validateSessionIdentity(envelope.payload)
        validateCanonicalEnvelope(
            encoded = encoded,
            canonical = json.encodeToJsonElement(StoredSessionEnvelope.serializer(), envelope),
        )
        return envelope.payload
    }
}

class AgentCheckpointCodec(
    json: Json = Json,
    private val sdkVersion: String = MAGRATHEA_CORE_SDK_VERSION,
) {
    private val json = Json(json) {
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = false
        isLenient = false
        coerceInputValues = false
    }

    init {
        require(sdkVersion.isNotBlank()) { "sdkVersion must not be blank" }
    }

    fun encode(checkpoint: AgentCheckpoint): String {
        validateCheckpointIdentity(checkpoint)
        return json.encodeToString(
            StoredCheckpointEnvelope.serializer(),
            StoredCheckpointEnvelope(
                schemaVersion = CURRENT_STORAGE_SCHEMA_VERSION,
                sdkVersion = sdkVersion,
                payload = checkpoint,
            ),
        )
    }

    fun decode(payload: String): AgentCheckpoint {
        val encoded = json.parseToJsonElement(payload)
        val envelope = json.decodeFromJsonElement(StoredCheckpointEnvelope.serializer(), encoded)
        validateEnvelope(envelope.schemaVersion, envelope.sdkVersion)
        validateCheckpointIdentity(envelope.payload)
        validateCanonicalEnvelope(
            encoded = encoded,
            canonical = json.encodeToJsonElement(StoredCheckpointEnvelope.serializer(), envelope),
        )
        return envelope.payload
    }
}

private fun validateEnvelope(schemaVersion: Int, sdkVersion: String) {
    if (schemaVersion != CURRENT_STORAGE_SCHEMA_VERSION) {
        throw SerializationException(
            "Unsupported storage schema version $schemaVersion; expected $CURRENT_STORAGE_SCHEMA_VERSION"
        )
    }
    if (sdkVersion.isBlank()) {
        throw SerializationException("Stored sdkVersion must not be blank")
    }
}

private fun validateSessionIdentity(snapshot: AgentSessionSnapshot) {
    if (snapshot.sessionId.value.isBlank()) {
        throw SerializationException("Stored sessionId must not be blank")
    }
    if (snapshot.sessionId != snapshot.request.sessionId) {
        throw SerializationException("Stored sessionId does not match request.sessionId")
    }
}

private fun validateCheckpointIdentity(checkpoint: AgentCheckpoint) {
    if (checkpoint.sessionId.value.isBlank()) {
        throw SerializationException("Stored checkpoint sessionId must not be blank")
    }
    if (checkpoint.turn < 0 || checkpoint.state.turn != checkpoint.turn) {
        throw SerializationException("Stored checkpoint turn does not match state.turn")
    }
}

private fun validateCanonicalEnvelope(encoded: JsonElement, canonical: JsonElement) {
    if (encoded != canonical) {
        throw SerializationException("Stored envelope does not match the canonical current schema")
    }
}

@Serializable
data class AgentRuntimeContext(
    val request: AgentRequest,
    val state: AgentStateSnapshot,
    val turn: Int,
)

@Serializable
data class ToolRuntimeContext(
    val request: AgentRequest,
    val assistantMessage: AgentMessage,
    val toolCall: ToolCallPart,
)

@Serializable
sealed interface AgentEvent {
    @Serializable
    @SerialName("started")
    data class Started(val sessionId: AgentSessionId) : AgentEvent

    @Serializable
    @SerialName("turn_started")
    data class TurnStarted(val sessionId: AgentSessionId, val turn: Int) : AgentEvent

    @Serializable
    @SerialName("context_transformed")
    data class ContextTransformed(val sessionId: AgentSessionId, val turn: Int, val messageCount: Int) : AgentEvent

    @Serializable
    @SerialName("debug")
    data class Debug(val sessionId: AgentSessionId, val label: String, val payload: String) : AgentEvent

    @Serializable
    @SerialName("message_emitted")
    data class MessageEmitted(val sessionId: AgentSessionId, val message: AgentMessage) : AgentEvent

    @Serializable
    @SerialName("tool_requested")
    data class ToolRequested(val sessionId: AgentSessionId, val toolCall: ToolCallPart) : AgentEvent

    @Serializable
    @SerialName("tool_completed")
    data class ToolCompleted(val sessionId: AgentSessionId, val result: ToolExecutionResult) : AgentEvent

    @Serializable
    @SerialName("retry_scheduled")
    data class RetryScheduled(
        val sessionId: AgentSessionId,
        val attempt: Int,
        val code: AgentFailureCode,
    ) : AgentEvent

    @Serializable
    @SerialName("checkpoint_saved")
    data class CheckpointSaved(val checkpoint: AgentCheckpoint) : AgentEvent

    @Serializable
    @SerialName("completed")
    data class Completed(val sessionId: AgentSessionId, val state: AgentStateSnapshot) : AgentEvent

    @Serializable
    @SerialName("failed")
    data class Failed(val sessionId: AgentSessionId, val code: AgentFailureCode) : AgentEvent

    @Serializable
    @SerialName("cancelled")
    data class Cancelled(val sessionId: AgentSessionId) : AgentEvent
}

abstract class TypedTool<Args>(
    private val serializer: KSerializer<Args>,
    protected val json: Json = Json,
) : ToolExecutor {
    final override suspend fun execute(request: ToolExecutionRequest): ToolExecutionResult {
        val args = json.decodeFromJsonElement(serializer, request.toolCall.arguments)
        return executeTyped(request, args)
    }

    protected abstract suspend fun executeTyped(
        request: ToolExecutionRequest,
        args: Args,
    ): ToolExecutionResult

    protected fun encodeResult(value: Any?, serializer: SerializationStrategy<Any?>? = null): JsonElement {
        return when {
            value == null -> JsonObject(emptyMap())
            serializer != null -> json.encodeToJsonElement(serializer, value)
            value is String -> json.encodeToJsonElement(value)
            value is Boolean -> json.encodeToJsonElement(value)
            value is Int -> json.encodeToJsonElement(value)
            value is Long -> json.encodeToJsonElement(value)
            value is Double -> json.encodeToJsonElement(value)
            else -> JsonObject(emptyMap())
        }
    }
}

object NoopRetryPolicy : RetryPolicy {
    override suspend fun shouldRetry(attempt: Int, error: Throwable): Boolean = false
}
