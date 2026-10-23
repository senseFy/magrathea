package saien.magrathea.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Whether a [ToolExecutor] may continue its work as Session-owned background execution after a
 * Run-local handoff. Executors stay [BLOCKING] unless they explicitly opt in (ADR-019).
 */
enum class ToolBackgroundPolicy {
    BLOCKING,
    ELIGIBLE,
}

/** How stored background results may reach the model after their originating Run ends. */
@Serializable
enum class BackgroundConsumptionPolicy {
    /** Results activate supplement Runs that see the latest conversation. */
    LATEST_CONTEXT,

    /** Results activate supplement Runs anchored to their originating context. */
    ORIGIN_DIRECTED,

    /** Results wait until some other Run consumes them; no automatic activation. */
    PASSIVE,
}

/** Host-configured bounds for Session-owned background execution (ADR-019 O-03). */
@Serializable
data class BackgroundExecutionConfig(
    /** Cumulative per-Run wait for eligible calls before they receive receipts. */
    val handoffWaitMillis: Long,
    /** Cumulative per-Run allowance for explicit model waits. */
    val explicitWaitAllowanceMillis: Long,
    /** Per-source bound on automatic activations across Runs. */
    val maxAutomaticActivationsPerSource: Int,
    /** Maximum queued lifetime before an unstarted execution is interrupted. */
    val maxQueuedLifetimeMillis: Long,
    val consumptionPolicy: BackgroundConsumptionPolicy = BackgroundConsumptionPolicy.LATEST_CONTEXT,
    /** Whether continuation Runs may call business Tools. */
    val allowBusinessToolsInContinuations: Boolean = false,
) {
    init {
        require(handoffWaitMillis >= 0) { "handoffWaitMillis must not be negative" }
        require(explicitWaitAllowanceMillis >= 0) { "explicitWaitAllowanceMillis must not be negative" }
        require(maxAutomaticActivationsPerSource >= 0) {
            "maxAutomaticActivationsPerSource must not be negative"
        }
        require(maxQueuedLifetimeMillis > 0) { "maxQueuedLifetimeMillis must be greater than zero" }
    }
}

@Serializable
enum class BackgroundExecutionState {
    QUEUED,
    RUNNING,
    TERMINAL,
}

@Serializable
enum class BackgroundOutcome {
    SUCCEEDED,
    ERROR,
    TIMEOUT,
    CANCELLED,
    INTERRUPTED,
}

/**
 * Non-guessable identity returned as the ordinary Tool result when a call hands off to background
 * execution. It acknowledges execution ownership, not success or failure.
 */
@Serializable
data class ToolBackgroundReceipt(
    val handle: String,
    val executionId: String,
    val state: BackgroundExecutionState,
) {
    init {
        require(handle.isNotBlank()) { "Background receipt handle must not be blank" }
        require(executionId.isNotBlank()) { "Background receipt execution ID must not be blank" }
        require(state == BackgroundExecutionState.QUEUED || state == BackgroundExecutionState.RUNNING) {
            "Background receipt state must be QUEUED or RUNNING"
        }
    }
}

/** How a Runtime notice reached the model. */
@Serializable
enum class RuntimeNoticeDelivery {
    /** Appended at a turn boundary of a Run that invoked the model anyway. */
    COALESCED,

    /** Appended as the first new message of a host-triggered continuation Run. */
    CONTINUATION,
}

/** Structured facts about one settled execution carried by a [RuntimeNoticePart]. */
@Serializable
data class RuntimeNoticeEntry(
    val handle: String,
    val toolName: String,
    /** Structured runtime data only; never rendered into the notice text. */
    val callId: String,
    val outcome: BackgroundOutcome,
    val resultVersion: Int,
) {
    init {
        require(handle.isNotBlank()) { "Runtime notice handle must not be blank" }
        require(toolName.isNotBlank()) { "Runtime notice Tool name must not be blank" }
        require(callId.isNotBlank()) { "Runtime notice call ID must not be blank" }
        require(outcome != BackgroundOutcome.CANCELLED) {
            "Cancelled background executions never produce a Runtime notice"
        }
        require(resultVersion >= 0) { "Runtime notice result version must not be negative" }
    }
}

/**
 * A metadata-only Runtime notice [MessagePart]. [text] is rendered once from a fixed template of
 * runtime-owned values and is what Providers receive inside a [MessageRole.RUNTIME] message.
 */
@Serializable
@SerialName("runtime_notice")
data class RuntimeNoticePart(
    val entries: List<RuntimeNoticeEntry>,
    val delivery: RuntimeNoticeDelivery,
    val text: String,
    override val providerMetadata: JsonObject? = null,
) : MessagePart {
    init {
        require(entries.isNotEmpty()) { "Runtime notice must carry at least one entry" }
        require(text.isNotBlank()) { "Runtime notice text must not be blank" }
    }
}

@Serializable
enum class RunActivationSource {
    USER_INPUT,
    BACKGROUND_RESULTS,
}

/** Why a Run exists, and what it is anchored to for context protection. */
@Serializable
data class RunActivation(
    val source: RunActivationSource,
    val anchorMessageId: String? = null,
    val sourceIds: List<String> = emptyList(),
) {
    init {
        if (source == RunActivationSource.BACKGROUND_RESULTS) {
            require(!anchorMessageId.isNullOrBlank()) {
                "A background-results activation requires an anchor message ID"
            }
            require(sourceIds.isNotEmpty()) {
                "A background-results activation requires at least one source ID"
            }
        }
    }
}

/**
 * Session-owned record for one background execution. The record's [executionId] matches the Run
 * journal record of the handed-off call, so recovery never executes it twice.
 */
@Serializable
data class BackgroundExecutionRecord(
    val sessionId: AgentSessionId,
    val executionId: String,
    val handle: String,
    val originRunId: AgentRunId,
    val originCallId: String,
    val toolName: String,
    val sourceId: String,
    val state: BackgroundExecutionState,
    val outcome: BackgroundOutcome? = null,
    val result: ToolExecutionResult? = null,
    val resultVersion: Int = 0,
    val executionRequest: ToolExecutionRequest,
    val effectiveTimeoutMillis: Long,
    val activationSuppressed: Boolean = false,
    val queuedAtEpochMs: Long,
    val startedAtEpochMs: Long? = null,
    val settledAtEpochMs: Long? = null,
) {
    init {
        require((state == BackgroundExecutionState.TERMINAL) == (outcome != null)) {
            "A terminal background record requires an outcome and a live record must not carry one"
        }
        require(result == null || state == BackgroundExecutionState.TERMINAL) {
            "A background record may carry a result only once it is terminal"
        }
        require(outcome != BackgroundOutcome.SUCCEEDED || result != null) {
            "A succeeded background outcome requires a result"
        }
        require(executionRequest.sessionId == sessionId) {
            "Background record session identity must match its execution request"
        }
        require(executionRequest.executionId == executionId) {
            "Background record execution identity must match its execution request"
        }
        require(effectiveTimeoutMillis > 0) { "effectiveTimeoutMillis must be greater than zero" }
    }
}

/**
 * The originating request of a Run that handed off work, stored with an empty message list so
 * late settlements can run `afterToolCall` interceptors (decision R1).
 */
@Serializable
data class BackgroundOriginContext(
    val sessionId: AgentSessionId,
    val runId: AgentRunId,
    val request: AgentRequest,
) {
    init {
        require(request.sessionId == sessionId) {
            "Background origin request identity must match its session"
        }
        require(request.messages.isEmpty()) {
            "Background origin requests are stored without their message history"
        }
    }
}

/** All background records and origin contexts of one session. */
data class BackgroundSessionRecords(
    val records: List<BackgroundExecutionRecord> = emptyList(),
    val origins: List<BackgroundOriginContext> = emptyList(),
)

/**
 * Optional per-execution record store for Session-owned background execution.
 *
 * Each [upsert] replaces one record, so there is no competing whole-snapshot writer. Runtimes
 * without an attached store keep blocking Tool execution (decision D3/R5).
 */
interface BackgroundExecutionStore {
    suspend fun upsert(record: BackgroundExecutionRecord)
    suspend fun putOrigin(origin: BackgroundOriginContext)
    suspend fun load(sessionId: AgentSessionId): BackgroundSessionRecords
    /**
     * Deletes one record of the Session; used when the host prunes regenerated history. A late
     * settlement callback for a deleted record is discarded by the executor.
     */
    suspend fun delete(sessionId: AgentSessionId, executionId: String)
    suspend fun deleteOrigin(sessionId: AgentSessionId, runId: AgentRunId)
    suspend fun deleteSession(sessionId: AgentSessionId)
    suspend fun clear()
}
