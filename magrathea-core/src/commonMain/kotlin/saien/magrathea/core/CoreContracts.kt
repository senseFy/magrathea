@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package saien.magrathea.core

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Semaphore
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

/**
 * Executes, resumes, and controls Agent sessions while streaming canonical lifecycle events.
 *
 * A returned execution Flow is cold. On collection, an implementation must make the run
 * addressable by [interrupt] and [cancel] before its first suspending I/O. This lets a runtime
 * owner stop work during preflight without waiting for the first business event.
 */
interface AgentRunner {
    fun run(request: AgentRequest): Flow<AgentEvent>
    suspend fun resume(sessionId: AgentSessionId): Flow<AgentEvent>
    suspend fun interrupt(sessionId: AgentSessionId): AgentRecoveryInfo
    suspend fun inspectRecovery(sessionId: AgentSessionId): AgentRecoveryInfo
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
    val contextWindowTokens: Long? = model.contextWindowTokens,
    val charsPerTokenEstimate: Int = 4,
) {
    init {
        require(maxOutputTokens > 0) { "Summary maximum output tokens must be positive" }
        require(charsPerTokenEstimate > 0) { "Summary chars-per-token estimate must be positive" }
    }
}

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

/**
 * Decides whether Runtime may start another Provider request before any canonical event was seen.
 *
 * `attempt` is the one-based retry ordinal for the current Provider invocation: `1` is the first
 * retry after its initial request. The ordinal resets when Runtime starts a later Provider
 * invocation, such as after a tool result.
 * Runtime enforces [RuntimeConfig.maxProviderRetries] and any Provider `Retry-After` minimum in
 * addition to this policy.
 */
interface RetryPolicy {
    suspend fun shouldRetry(attempt: Int, error: Throwable): Boolean

    /** Returns the host-selected delay before the retry identified by `attempt`. */
    suspend fun backoffDelayMs(attempt: Int, error: Throwable): Long =
        (attempt * 250L).coerceAtMost(2_000L)
}

/**
 * Atomically persists and loads the authoritative session snapshot together with its latest
 * recovery checkpoint.
 */
interface AgentPersistence {
    suspend fun commit(
        snapshot: AgentSessionSnapshot,
        checkpoint: AgentCheckpoint?,
    )

    suspend fun load(sessionId: AgentSessionId): AgentPersistenceRecord?
    suspend fun listSessions(): List<AgentSessionSnapshot>
    suspend fun deleteSession(sessionId: AgentSessionId)
    suspend fun clear()
}

data class AgentPersistenceRecord(
    val snapshot: AgentSessionSnapshot,
    val checkpoint: AgentCheckpoint?,
) {
    init {
        require(checkpoint == null || snapshot.sessionId == checkpoint.sessionId) {
            "Session and checkpoint identity must match"
        }
        require(checkpoint == null || snapshot.runId == checkpoint.runId) {
            "Session and checkpoint run identity must match"
        }
    }
}

@Serializable
enum class AgentFailureCode {
    NOT_FOUND,
    INVALID_STATE,
    CONTEXT_LIMIT,
    CREDENTIAL_UNAVAILABLE,
    PROVIDER_NOT_FOUND,
    PROVIDER_AUTH,

    /** The credential was accepted but the Provider refused this request's access. */
    PROVIDER_PERMISSION,
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

/**
 * Coordinates admission to a [ToolExecutor]'s side-effecting execution boundary.
 *
 * A single instance may be shared by multiple executors. The Runtime acquires admission before
 * starting the executor timeout and releases it after execution finishes for any reason. Custom
 * implementations must be safe for concurrent use, and calls intended to share one limit must
 * reuse the same permit instance.
 */
interface ToolExecutionPermit {
    /** Waits until one execution is admitted. A cancelled or failed acquisition is not released. */
    suspend fun acquire()

    /** Returns one admission after a successful [acquire]. */
    fun release()
}

/** The default Tool execution permit, which never limits concurrent executions. */
object UnlimitedToolExecutionPermit : ToolExecutionPermit {
    override suspend fun acquire() = Unit
    override fun release() = Unit
}

/**
 * A Kotlin Multiplatform concurrency gate that may be shared by multiple [ToolExecutor]s.
 *
 * At most [maxConcurrentExecutions] successful acquisitions may remain unreleased at once.
 */
class SharedToolExecutionPermit(
    val maxConcurrentExecutions: Int,
) : ToolExecutionPermit {
    init {
        require(maxConcurrentExecutions > 0) {
            "maxConcurrentExecutions must be greater than zero"
        }
    }

    private val semaphore = Semaphore(maxConcurrentExecutions)

    override suspend fun acquire() {
        semaphore.acquire()
    }

    override fun release() {
        semaphore.release()
    }
}

/** Resolves a credential reference at call time without placing the credential in Agent state. */
fun interface CredentialProvider {
    suspend fun resolve(ref: CredentialRef): ProviderCredential
}

/** A registered Tool definition and its side-effecting execution boundary. */
interface ToolExecutor {
    val definition: ToolDefinition
    val recoveryPolicy: ToolRecoveryPolicy
        get() = ToolRecoveryPolicy.FAIL_CLOSED

    /**
     * Selects the admission boundary for this concrete execution request.
     *
     * Runtime brackets [execute] with this permit. Direct callers own the same acquire/release
     * boundary, and [execute] implementations must not acquire the selected permit again.
     */
    fun executionPermit(request: ToolExecutionRequest): ToolExecutionPermit =
        UnlimitedToolExecutionPermit

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
    val runId: AgentRunId,
    val request: AgentRequest,
    val state: AgentStateSnapshot,
    val interruption: AgentInterruption? = null,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val updatedAtEpochMs: Long = SystemEpochClock.nowEpochMs(),
) {
    init {
        require(sessionId == request.sessionId) { "Session and request identity must match" }
        require((state.status == AgentStatus.INTERRUPTED) == (interruption != null)) {
            "Only interrupted sessions may carry interruption metadata"
        }
    }
}

const val CURRENT_STORAGE_SCHEMA_VERSION: Int = 7

/** The first schema covered by the SDK-owned migration chain; older schemas are a clean break. */
const val MINIMUM_READABLE_STORAGE_SCHEMA_VERSION: Int = 6

private val storageSchemaEvolution = StorageSchemaEvolution(
    minimumReadableVersion = MINIMUM_READABLE_STORAGE_SCHEMA_VERSION,
    currentVersion = CURRENT_STORAGE_SCHEMA_VERSION,
    migrations = listOf(
        RegisteredStorageSchemaMigration(
            id = "agent-storage-v6-to-v7-max-output-tokens",
            fromVersion = 6,
            migration = StorageSchemaV6ToV7Migration,
        ),
    ),
)

/**
 * Legacy generic session envelope retained for compatibility.
 *
 * This type is not the SDK's canonical durable wire contract. Use [AgentSessionSnapshotCodec] so
 * version dispatch, strict validation, and migration metadata cannot be bypassed.
 */
@Deprecated(
    message = "Use AgentSessionSnapshotCodec for durable session envelopes.",
    level = DeprecationLevel.WARNING,
)
@Serializable
data class StoredSessionEnvelope(
    val schemaVersion: Int,
    val sdkVersion: String,
    val payload: AgentSessionSnapshot,
)

/**
 * Legacy generic checkpoint envelope retained for compatibility.
 *
 * This type is not the SDK's canonical durable wire contract. Use [AgentCheckpointCodec] so
 * version dispatch, strict validation, and migration metadata cannot be bypassed.
 */
@Deprecated(
    message = "Use AgentCheckpointCodec for durable checkpoint envelopes.",
    level = DeprecationLevel.WARNING,
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
    private val json = fixedStorageJson(json)

    init {
        require(sdkVersion.isNotBlank()) { "sdkVersion must not be blank" }
    }

    fun encode(snapshot: AgentSessionSnapshot): String {
        validateSessionIdentity(snapshot)
        return StorageSchemaV7Adapter.encodeSession(json, sdkVersion, snapshot)
    }

    fun decode(payload: String): AgentSessionSnapshot {
        return decodeResult(payload).value
    }

    /** Decodes completely and reports a canonical payload when an adjacent migration was applied. */
    fun decodeResult(payload: String): StoredEnvelopeDecodeResult<AgentSessionSnapshot> {
        val evolved = storageSchemaEvolution.evolve(payload, json)
        return evolved.decodeCurrentResult {
            val envelope = StorageSchemaV7Adapter.decodeSession(json, evolved.document)
            validateEnvelope(envelope.schemaVersion, envelope.sdkVersion)
            validateSessionIdentity(envelope.payload)
            validateCanonicalEnvelope(
                encoded = evolved.document,
                canonical = envelope.canonicalDocument,
            )
            ValidatedCurrentStoredEnvelope(envelope.payload, envelope.canonicalPayload)
        }
    }
}

class AgentCheckpointCodec(
    json: Json = Json,
    private val sdkVersion: String = MAGRATHEA_CORE_SDK_VERSION,
) {
    private val json = fixedStorageJson(json)

    init {
        require(sdkVersion.isNotBlank()) { "sdkVersion must not be blank" }
    }

    fun encode(checkpoint: AgentCheckpoint): String {
        validateCheckpointIdentity(checkpoint)
        return StorageSchemaV7Adapter.encodeCheckpoint(json, sdkVersion, checkpoint)
    }

    fun decode(payload: String): AgentCheckpoint {
        return decodeResult(payload).value
    }

    /** Decodes completely and reports a canonical payload when an adjacent migration was applied. */
    fun decodeResult(payload: String): StoredEnvelopeDecodeResult<AgentCheckpoint> {
        val evolved = storageSchemaEvolution.evolve(payload, json)
        return evolved.decodeCurrentResult {
            val envelope = StorageSchemaV7Adapter.decodeCheckpoint(json, evolved.document)
            validateEnvelope(envelope.schemaVersion, envelope.sdkVersion)
            validateCheckpointIdentity(envelope.payload)
            validateCanonicalEnvelope(
                encoded = evolved.document,
                canonical = envelope.canonicalDocument,
            )
            ValidatedCurrentStoredEnvelope(envelope.payload, envelope.canonicalPayload)
        }
    }
}

/** The storage wire format is SDK-owned; caller JSON settings must not create same-version variants. */
private fun fixedStorageJson(
    @Suppress("UNUSED_PARAMETER") source: Json,
): Json = Json {
    encodeDefaults = true
    explicitNulls = true
    ignoreUnknownKeys = false
    isLenient = false
    coerceInputValues = false
    classDiscriminator = "type"
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
    if (snapshot.runId.value.isBlank()) {
        throw SerializationException("Stored runId must not be blank")
    }
    if (snapshot.sessionId != snapshot.request.sessionId) {
        throw SerializationException("Stored sessionId does not match request.sessionId")
    }
}

private fun validateCheckpointIdentity(checkpoint: AgentCheckpoint) {
    if (checkpoint.sessionId.value.isBlank()) {
        throw SerializationException("Stored checkpoint sessionId must not be blank")
    }
    if (checkpoint.runId.value.isBlank()) {
        throw SerializationException("Stored checkpoint runId must not be blank")
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
    data class Started(val sessionId: AgentSessionId, val runId: AgentRunId) : AgentEvent

    @Serializable
    @SerialName("turn_started")
    data class TurnStarted(val sessionId: AgentSessionId, val turn: Int) : AgentEvent

    @Serializable
    @SerialName("context_transformed")
    data class ContextTransformed(val sessionId: AgentSessionId, val turn: Int, val messageCount: Int) : AgentEvent

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
    /** `attempt` is the one-based retry ordinal for the current Provider invocation. */
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

    @Serializable
    @SerialName("interrupted")
    data class Interrupted(
        val sessionId: AgentSessionId,
        val runId: AgentRunId,
        val interruption: AgentInterruption,
        val state: AgentStateSnapshot,
    ) : AgentEvent

    @Serializable
    @SerialName("recovery_blocked")
    data class RecoveryBlocked(
        val sessionId: AgentSessionId,
        val runId: AgentRunId,
        val reason: AgentRecoveryBlockReason,
    ) : AgentEvent
}

@Serializable
enum class AgentInterruptionReason {
    HOST_REQUESTED,
    PROVIDER_FAILURE,
    ORPHANED,
}

@Serializable
enum class ProviderInterruptionPhase {
    BEFORE_FIRST_EVENT,
    AFTER_FIRST_EVENT,
}

@Serializable
data class ProviderInterruption(
    val code: AgentFailureCode,
    val phase: ProviderInterruptionPhase,
    val retryAtEpochMs: Long? = null,
) {
    init {
        require(
            code == AgentFailureCode.PROVIDER_NETWORK ||
                code == AgentFailureCode.TIMEOUT ||
                code == AgentFailureCode.PROVIDER_RATE_LIMIT ||
                code == AgentFailureCode.PROVIDER_SERVER,
        ) { "Provider interruption must describe a recoverable Provider failure" }
        require(retryAtEpochMs == null || retryAtEpochMs >= 0) {
            "Provider retry time must not be negative"
        }
    }
}

@Serializable
data class AgentInterruption(
    val reason: AgentInterruptionReason,
    val provider: ProviderInterruption? = null,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val occurredAtEpochMs: Long = SystemEpochClock.nowEpochMs(),
) {
    init {
        require((reason == AgentInterruptionReason.PROVIDER_FAILURE) == (provider != null)) {
            "Provider interruption details must match the interruption reason"
        }
        require(provider?.retryAtEpochMs == null || provider.retryAtEpochMs >= occurredAtEpochMs) {
            "Provider retry time must not precede the interruption"
        }
    }
}

enum class AgentRecoveryDisposition {
    ACTIVE,
    RESUMABLE,
    BLOCKED,
    TERMINAL,
    NOT_FOUND,
}

enum class AgentRecoveryBlockReason {
    TOOL_OUTCOME_UNKNOWN,
    CHECKPOINT_MISMATCH,
}

data class AgentRecoveryInfo(
    val sessionId: AgentSessionId,
    val runId: AgentRunId? = null,
    val disposition: AgentRecoveryDisposition,
    val status: AgentStatus? = null,
    val state: AgentStateSnapshot? = null,
    val cursor: AgentResumeCursor? = null,
    val interruption: AgentInterruption? = null,
    val blockedReason: AgentRecoveryBlockReason? = null,
)

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
