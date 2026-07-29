@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package saien.magrathea.core

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

@Serializable
data class AgentSessionId(val value: String) {
    companion object {
        fun create(): AgentSessionId = create(SystemIdGenerator)

        fun create(idGenerator: IdGenerator): AgentSessionId = AgentSessionId(idGenerator.nextId())
    }
}

/** Stable identity for one logical run within a longer-lived Agent session. */
@Serializable
data class AgentRunId(val value: String) {
    init {
        require(value.isNotBlank()) { "Agent run ID must not be blank" }
    }

    companion object {
        fun create(): AgentRunId = create(SystemIdGenerator)

        fun create(idGenerator: IdGenerator): AgentRunId = AgentRunId(idGenerator.nextId())
    }
}

@Serializable
enum class ToolExecutionMode {
    SEQUENTIAL,
    PARALLEL,
}

/** Non-secret lookup identity resolved by a host-owned [CredentialProvider]. */
@Serializable
data class CredentialRef(
    val provider: String,
    val profile: String = "default",
) {
    init {
        require(provider.isNotBlank()) { "Credential provider must not be blank" }
        require(profile.isNotBlank()) { "Credential profile must not be blank" }
    }
}

/**
 * Transient Provider authentication material returned by a host-owned [CredentialProvider].
 *
 * Instances are never serializable and their string representation is always redacted.
 */
class ProviderCredential(
    val value: String,
    val endpoint: String? = null,
    val headers: Map<String, String> = emptyMap(),
) {
    init {
        require(value.isNotBlank()) { "Provider credential must not be blank" }
    }

    override fun toString(): String = "ProviderCredential(<redacted>)"
}

/**
 * Non-secret Provider configuration persisted with an Agent request.
 *
 * Credential values are resolved separately through [CredentialRef]. Custom endpoint and header
 * values are runtime-only and are deliberately omitted from [toString].
 */
@Serializable
data class ProviderConfig(
    @Transient
    val endpoint: String? = null,
    val temperature: Double? = null,
    val maxTokens: Int? = null,
    @Transient
    val headers: Map<String, String> = emptyMap(),
    val options: ProviderOptions? = null,
    val credentialRef: CredentialRef? = null,
    val timeouts: ProviderTimeoutConfig = ProviderTimeoutConfig(),
) {
    override fun toString(): String =
        "ProviderConfig(" +
            "endpoint=${if (endpoint == null) "default" else "<custom>"}, " +
            "temperature=$temperature, " +
            "maxTokens=$maxTokens, " +
            "headerNames=${headers.keys.sorted()}, " +
            "optionsFamily=${options?.family}, " +
            "credentialRef=$credentialRef, " +
            "timeouts=$timeouts" +
            ")"
}

/** Deadlines for one Provider invocation, including streamed responses. */
@Serializable
data class ProviderTimeoutConfig(
    val connectTimeoutMillis: Long = 15_000,
    val firstEventTimeoutMillis: Long = 120_000,
    val streamIdleTimeoutMillis: Long = 90_000,
    val callTimeoutMillis: Long = 600_000,
) {
    init {
        require(connectTimeoutMillis > 0) { "connectTimeoutMillis must be greater than zero" }
        require(firstEventTimeoutMillis > 0) { "firstEventTimeoutMillis must be greater than zero" }
        require(streamIdleTimeoutMillis > 0) { "streamIdleTimeoutMillis must be greater than zero" }
        require(callTimeoutMillis > 0) { "callTimeoutMillis must be greater than zero" }
        require(connectTimeoutMillis <= callTimeoutMillis) {
            "connectTimeoutMillis must not exceed callTimeoutMillis"
        }
        require(firstEventTimeoutMillis <= callTimeoutMillis) {
            "firstEventTimeoutMillis must not exceed callTimeoutMillis"
        }
        require(streamIdleTimeoutMillis <= callTimeoutMillis) {
            "streamIdleTimeoutMillis must not exceed callTimeoutMillis"
        }
    }
}

@Serializable
data class ProviderOptions(
    val family: String,
    val values: JsonObject = buildJsonObject { },
) {
    init {
        require(family.isNotBlank()) { "Provider options family must not be blank" }
        val sensitiveKey = values.findSensitiveOptionKey()
        require(sensitiveKey == null) {
            "Provider options must not contain credential-like key '${sensitiveKey.orEmpty()}'"
        }
    }
}

private fun JsonElement.findSensitiveOptionKey(): String? {
    return when (this) {
        is JsonObject -> entries.firstNotNullOfOrNull { (key, value) ->
            key.takeIf(::isSensitiveProviderOptionKey) ?: value.findSensitiveOptionKey()
        }
        is JsonArray -> firstNotNullOfOrNull(JsonElement::findSensitiveOptionKey)
        else -> null
    }
}

private fun isSensitiveProviderOptionKey(key: String): Boolean {
    val normalized = key.lowercase().filter { it.isLetterOrDigit() }
    return normalized in setOf("authorization", "credential", "credentials", "headers") ||
        normalized.endsWith("apikey") ||
        normalized.endsWith("password") ||
        normalized.endsWith("secret") ||
        normalized.endsWith("token")
}

@Serializable
data class RuntimeConfig(
    val maxTurns: Int = 8,
    val contextManagement: ContextManagementConfig = ContextManagementConfig(),
    val maxProviderRetries: Int = 2,
    val maxToolResultChars: Int = 1_048_576,
    val maxInlineAttachmentBytes: Int = 20 * 1_024 * 1_024,
    val toolExecutionMode: ToolExecutionMode = ToolExecutionMode.PARALLEL,
    val defaultToolTimeoutMillis: Long = 120_000,
    val runTimeoutMillis: Long = 1_800_000,
) {
    init {
        require(maxTurns > 0) { "maxTurns must be greater than zero" }
        require(maxProviderRetries >= 0) { "maxProviderRetries must not be negative" }
        require(maxToolResultChars > 0) { "maxToolResultChars must be greater than zero" }
        require(maxInlineAttachmentBytes > 0) { "maxInlineAttachmentBytes must be greater than zero" }
        require(defaultToolTimeoutMillis > 0) { "defaultToolTimeoutMillis must be greater than zero" }
        require(runTimeoutMillis > 0) { "runTimeoutMillis must be greater than zero" }
    }
}

/**
 * Token-budgeted context management for long-running sessions.
 *
 * [contextWindowTokensOverride] is useful for compatible or dynamically discovered models whose
 * context window is not present in [ModelDescriptor]. Automatic compaction is skipped when both
 * values are unknown; an actual Provider context-limit response can still force one recovery
 * attempt.
 */
@Serializable
data class ContextManagementConfig(
    val enabled: Boolean = true,
    val reserveTokens: Long = 16_384,
    val keepRecentTokens: Long = 20_000,
    val summaryMaxTokens: Int = 4_096,
    val charsPerTokenEstimate: Int = 4,
    val toolResultSummaryMaxChars: Int = 2_000,
    val contextWindowTokensOverride: Long? = null,
    val overflowRetryLimit: Int = 1,
) {
    init {
        require(reserveTokens > 0) { "reserveTokens must be greater than zero" }
        require(keepRecentTokens > 0) { "keepRecentTokens must be greater than zero" }
        require(summaryMaxTokens > 0) { "summaryMaxTokens must be greater than zero" }
        require(charsPerTokenEstimate > 0) { "charsPerTokenEstimate must be greater than zero" }
        require(toolResultSummaryMaxChars > 0) {
            "toolResultSummaryMaxChars must be greater than zero"
        }
        require(contextWindowTokensOverride == null || contextWindowTokensOverride > reserveTokens) {
            "contextWindowTokensOverride must exceed reserveTokens"
        }
        require(overflowRetryLimit >= 0) { "overflowRetryLimit must not be negative" }
    }
}

/** Provider and Runtime configuration applied to one Agent request. */
@Serializable
data class AgentEngineConfig(
    val provider: ProviderConfig = ProviderConfig(),
    val runtime: RuntimeConfig = RuntimeConfig(),
)

/** Complete input for a new Agent run. Credential values are resolved separately at call time. */
@Serializable
data class AgentRequest(
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val sessionId: AgentSessionId = AgentSessionId.create(),
    val systemPrompt: String = "",
    val messages: List<AgentMessage>,
    val model: ModelDescriptor,
    val tools: List<ToolDefinition> = emptyList(),
    val metadata: JsonObject = buildJsonObject { },
    val engine: AgentEngineConfig = AgentEngineConfig(),
)

/** Provider and model capability identity used for routing and request validation. */
@Serializable
data class ModelDescriptor(
    val provider: String,
    val model: String,
    val displayName: String = model,
    val supportsToolCalls: Boolean = true,
    val supportsReasoning: Boolean = false,
    val supportsStreaming: Boolean = false,
    val contextWindowTokens: Long? = null,
)

@Serializable
data class AgentCheckpoint(
    val sessionId: AgentSessionId,
    val runId: AgentRunId,
    val cursor: AgentResumeCursor,
    val state: AgentStateSnapshot,
    val toolExecutions: List<ToolExecutionRecord> = emptyList(),
) {
    val turn: Int
        get() = cursor.turn

    init {
        require(state.turn == cursor.turn) {
            "Checkpoint state turn must match its resume cursor"
        }
        require(toolExecutions.map(ToolExecutionRecord::executionId).distinct().size == toolExecutions.size) {
            "Checkpoint Tool execution IDs must be unique"
        }
        require(
            toolExecutions
                .map { Triple(it.toolCallId, it.toolName, it.callOrdinal) }
                .distinct()
                .size == toolExecutions.size,
        ) {
            "Checkpoint Tool execution identities must be unique"
        }
    }
}

/** Exact durable boundary from which a logical run can be recovered. */
@Serializable
data class AgentResumeCursor(
    val turn: Int,
    val phase: AgentResumePhase,
    val providerAttempt: Int = 0,
) {
    init {
        require(turn >= 0) { "Resume cursor turn must not be negative" }
        require(providerAttempt >= 0) { "Provider attempt must not be negative" }
    }
}

@Serializable
enum class AgentResumePhase {
    TURN_PREPARING,
    MODEL_PENDING,
    TOOLS_PENDING,
    TURN_COMMITTED,
}

@Serializable
enum class ToolExecutionState {
    PENDING,
    STARTED,
    COMPLETED,
}

/**
 * Durable evidence for one Tool execution. A persisted `STARTED` record has an unknown outcome
 * after process death and is replayed only when the registered executor explicitly permits it.
 */
@Serializable
data class ToolExecutionRecord(
    val executionId: String,
    val toolCallId: String,
    val toolName: String,
    val callOrdinal: Int,
    val state: ToolExecutionState,
    val result: ToolExecutionResult? = null,
) {
    init {
        require(executionId.isNotBlank()) { "Tool execution ID must not be blank" }
        require(callOrdinal > 0) { "Tool call ordinal must be positive" }
        require((state == ToolExecutionState.COMPLETED) == (result != null)) {
            "Completed Tool executions require a result and incomplete executions must not carry one"
        }
        require(result == null || (result.toolCallId == toolCallId && result.toolName == toolName)) {
            "Tool execution result identity must match its journal record"
        }
    }
}

/**
 * Authoritative state persisted for one logical Agent run.
 *
 * Conversation history is never replaced by the compacted Provider projection. [toolCallCounts]
 * preserves run-level execution budgets across checkpoints and resume.
 */
@Serializable
data class AgentStateSnapshot(
    val messages: List<AgentMessage>,
    val pendingToolCalls: List<ToolCallPart> = emptyList(),
    /** Finalized Tool calls attempted in the current logical Agent run, keyed by Tool name. */
    val toolCallCounts: Map<String, Int> = emptyMap(),
    val metadata: JsonObject = buildJsonObject { },
    val turn: Int = 0,
    val status: AgentStatus = AgentStatus.IDLE,
    val retryCount: Int = 0,
    val stopReason: StopReason? = null,
    val usage: TokenUsage = TokenUsage(),
    val latestRequestUsage: TokenUsage = TokenUsage(),
    val contextManagement: ContextManagementState = ContextManagementState(),
)

/** Persistent Provider-context projection state; authoritative conversation messages remain intact. */
@Serializable
data class ContextManagementState(
    val compaction: ContextCompaction? = null,
    val usageObservation: ContextUsageObservation? = null,
)

/**
 * One cumulative semantic compaction. The summary covers the immutable message prefix ending at
 * [summarizedThroughMessageId], while raw messages resume at [firstKeptMessageId].
 */
@Serializable
data class ContextCompaction(
    val summary: String,
    val firstKeptMessageId: String,
    val summarizedThroughMessageId: String,
    val sourcePrefixDigest: String,
    val tokensBefore: Long,
    val generation: Long,
    val summaryModel: ModelDescriptor,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val createdAtEpochMs: Long = SystemEpochClock.nowEpochMs(),
) {
    init {
        require(summary.isNotBlank()) { "Context compaction summary must not be blank" }
        require(firstKeptMessageId.isNotBlank()) { "firstKeptMessageId must not be blank" }
        require(summarizedThroughMessageId.isNotBlank()) {
            "summarizedThroughMessageId must not be blank"
        }
        require(sourcePrefixDigest.isNotBlank()) { "sourcePrefixDigest must not be blank" }
        require(tokensBefore >= 0) { "tokensBefore must not be negative" }
        require(generation > 0) { "generation must be greater than zero" }
    }
}

/**
 * Provider-reported prompt usage anchored to one canonical history prefix and compaction
 * generation. The digest prevents stale usage from surviving regeneration or history edits.
 */
@Serializable
data class ContextUsageObservation(
    val inputTokens: Long,
    val throughMessageId: String?,
    val historyPrefixDigest: String,
    val compactionGeneration: Long,
    val provider: String,
    val model: String,
    val requestFingerprint: String,
) {
    init {
        require(inputTokens >= 0) { "Context usage inputTokens must not be negative" }
        require(historyPrefixDigest.isNotBlank()) { "historyPrefixDigest must not be blank" }
        require(compactionGeneration >= 0) { "compactionGeneration must not be negative" }
        require(provider.isNotBlank()) { "Context usage provider must not be blank" }
        require(model.isNotBlank()) { "Context usage model must not be blank" }
        require(requestFingerprint.isNotBlank()) { "requestFingerprint must not be blank" }
    }
}

@Serializable
data class TokenUsage(
    val inputTokens: Long? = null,
    val outputTokens: Long? = null,
    val reasoningTokens: Long? = null,
)

operator fun TokenUsage.plus(other: TokenUsage): TokenUsage = TokenUsage(
    inputTokens = addKnownTokenCounts(inputTokens, other.inputTokens),
    outputTokens = addKnownTokenCounts(outputTokens, other.outputTokens),
    reasoningTokens = addKnownTokenCounts(reasoningTokens, other.reasoningTokens),
)

private fun addKnownTokenCounts(first: Long?, second: Long?): Long? {
    return if (first == null && second == null) null else (first ?: 0L) + (second ?: 0L)
}

@Serializable
enum class AgentStatus {
    IDLE,
    RUNNING,
    WAITING_FOR_TOOLS,
    INTERRUPTED,
    COMPLETED,
    FAILED,
    CANCELLED,
}

/** Canonical conversation message shared by Runtime, Provider adapters, and persistence. */
@Serializable
data class AgentMessage(
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val id: String = SystemIdGenerator.nextId(),
    val role: MessageRole,
    val parts: List<MessagePart>,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val createdAtEpochMs: Long = SystemEpochClock.nowEpochMs(),
    val metadata: JsonObject = buildJsonObject { },
    val stopReason: StopReason? = null,
)

@Serializable
enum class MessageRole {
    SYSTEM,
    USER,
    ASSISTANT,
    TOOL,
}

@Serializable
enum class StopReason {
    COMPLETED,
    TOOL_CALLS,
    INTERRUPTED,
    CANCELLED,
    ERROR,
    MAX_TURNS,
    MAX_TOKENS,
    RETRY,
}

@Serializable
enum class MessageBlockPhase {
    COMMENTARY,
    FINAL,
}

/**
 * The visible reasoning representation selected by a Provider.
 *
 * This is independent from opaque continuity data such as signatures or encrypted reasoning.
 * [SUMMARY] is a Provider-authored digest of hidden reasoning, [TEXT] is reasoning text the
 * Provider explicitly exposed, and [PROVIDER_DEFINED] is used when the wire protocol does not
 * distinguish the two.
 */
@Serializable
enum class ReasoningContentKind {
    PROVIDER_DEFINED,
    SUMMARY,
    TEXT,
}

@Serializable
sealed interface MessagePart {
    val providerMetadata: JsonObject?
}

@Serializable
@SerialName("text")
data class TextPart(
    val text: String,
    val signature: String? = null,
    val phase: MessageBlockPhase? = null,
    override val providerMetadata: JsonObject? = null,
) : MessagePart

@Serializable
@SerialName("reasoning")
data class ReasoningPart(
    /** Provider-approved visible reasoning representation; it may be a summary rather than raw thoughts. */
    val text: String,
    /** Opaque Provider continuity data. Consumers must never render, parse, or modify it. */
    val signature: String? = null,
    val redacted: Boolean = false,
    val kind: ReasoningContentKind = ReasoningContentKind.PROVIDER_DEFINED,
    val phase: MessageBlockPhase? = null,
    override val providerMetadata: JsonObject? = null,
) : MessagePart

@Serializable
@SerialName("json")
data class JsonPart(val value: JsonElement, override val providerMetadata: JsonObject? = null) : MessagePart

@Serializable
@SerialName("tool_call")
data class ToolCallPart(
    val toolCallId: String,
    val toolName: String,
    val arguments: JsonElement,
    val partial: Boolean = false,
    val thoughtSignature: String? = null,
    val providerCallId: String? = null,
    override val providerMetadata: JsonObject? = null,
) : MessagePart

@Serializable
@SerialName("tool_result")
data class ToolResultPart(
    val toolCallId: String,
    val toolName: String,
    val result: JsonElement,
    val isError: Boolean = false,
    val displayText: String? = null,
    val metadata: JsonObject = buildJsonObject { },
    override val providerMetadata: JsonObject? = null,
) : MessagePart

@Serializable
@SerialName("attachment")
data class AttachmentPart(
    val uri: String,
    val mimeType: String,
    val fileName: String? = null,
    override val providerMetadata: JsonObject? = null,
) : MessagePart

data class AttachmentDataUrl(
    val mediaType: String?,
    val data: String,
)

private val supportedImageMimeTypes = setOf(
    "image/jpeg",
    "image/png",
    "image/webp",
    "image/gif",
)

fun AttachmentPart.normalizedMimeType(): String {
    return mimeType.substringBefore(';').trim().lowercase()
}

fun AttachmentPart.dataUrlPayload(): AttachmentDataUrl? {
    if (!uri.startsWith("data:", ignoreCase = true)) return null
    val headerEnd = uri.indexOf(',')
    if (headerEnd <= "data:".length) return null
    val header = uri.substring("data:".length, headerEnd)
    if (header.split(';').none { it.equals("base64", ignoreCase = true) }) return null
    val data = uri.substring(headerEnd + 1).takeIf { it.isNotBlank() } ?: return null
    val mediaType = header.substringBefore(';').trim().lowercase().takeIf { it.isNotBlank() }
    return AttachmentDataUrl(mediaType = mediaType, data = data)
}

fun AttachmentPart.imageMimeTypeOrNull(): String? {
    return sequenceOf(
        normalizedMimeType(),
        dataUrlPayload()?.mediaType,
        inferImageMimeTypeFromUri(uri),
    )
        .filterNotNull()
        .firstOrNull { it in supportedImageMimeTypes }
}

fun AttachmentPart.isHttpsUrl(): Boolean {
    return uri.startsWith("https://", ignoreCase = true)
}

fun AttachmentPart.textReference(): String {
    if (!uri.startsWith("data:", ignoreCase = true)) return uri
    val mediaType = normalizedMimeType()
        .ifBlank { dataUrlPayload()?.mediaType.orEmpty() }
        .ifBlank { "application/octet-stream" }
    return "[attachment: $mediaType data-url]"
}

private fun inferImageMimeTypeFromUri(uri: String): String? {
    val path = uri.substringBefore('?').substringBefore('#').lowercase()
    return when {
        path.endsWith(".jpg") || path.endsWith(".jpeg") -> "image/jpeg"
        path.endsWith(".png") -> "image/png"
        path.endsWith(".webp") -> "image/webp"
        path.endsWith(".gif") -> "image/gif"
        else -> null
    }
}

/**
 * Immutable Tool contract advertised to a model and enforced again at execution time.
 *
 * [maxCallsPerTurn] bounds calls within one model response. [maxCallsPerRun] spans the complete
 * logical run, including later model turns, injected steering/follow-up messages, and resume.
 */
@Serializable
data class ToolDefinition(
    val name: String,
    val description: String,
    val schema: JsonObject,
    val requiresPermission: String? = null,
    val requiresApproval: Boolean = false,
    val timeoutMs: Long? = null,
    val maxCallsPerTurn: Int? = null,
    val maxCallsPerRun: Int? = null,
) {
    init {
        require(maxCallsPerTurn == null || maxCallsPerTurn > 0) {
            "Tool maxCallsPerTurn must be greater than zero"
        }
        require(maxCallsPerRun == null || maxCallsPerRun > 0) {
            "Tool maxCallsPerRun must be greater than zero"
        }
    }
}

/** One Tool invocation; [executionId] remains stable when a replay-safe call is recovered. */
@Serializable
data class ToolExecutionRequest(
    val sessionId: AgentSessionId,
    val runId: AgentRunId,
    val executionId: String,
    val assistantMessage: AgentMessage,
    val toolCall: ToolCallPart,
) {
    init {
        require(executionId.isNotBlank()) { "Tool execution ID must not be blank" }
    }
}

/** Host-owned assertion about replaying a Tool whose previous outcome is unknown. */
enum class ToolRecoveryPolicy {
    FAIL_CLOSED,
    REPLAY_SAFE,
}

@Serializable
data class ToolExecutionResult(
    val toolCallId: String,
    val toolName: String,
    val result: JsonElement,
    val isError: Boolean = false,
    val displayText: String? = null,
    val metadata: JsonObject = buildJsonObject { },
)

@Serializable
data class Citation(
    val title: String,
    val url: String,
    val snippet: String,
)

fun ToolExecutionResult.outputText(): String {
    return displayText ?: when (result) {
        is JsonPrimitive -> result.contentOrNull ?: result.toString()
        else -> result.toString()
    }
}

fun ToolExecutionResult.toMessagePart(providerMetadata: JsonObject? = null): ToolResultPart {
    return ToolResultPart(
        toolCallId = toolCallId,
        toolName = toolName,
        result = result,
        isError = isError,
        displayText = outputText(),
        metadata = metadata,
        providerMetadata = providerMetadata,
    )
}

fun ToolExecutionResult.citations(): List<Citation> {
    return metadata.citations()
}

fun ToolResultPart.outputText(): String {
    return displayText ?: when (result) {
        is JsonPrimitive -> result.contentOrNull ?: result.toString()
        else -> result.toString()
    }
}

fun ToolResultPart.citations(): List<Citation> {
    return metadata.citations()
}

private fun JsonObject.citations(): List<Citation> {
    return this["citations"]?.jsonArray?.mapNotNull { item ->
        val obj = item as? JsonObject ?: return@mapNotNull null
        Citation(
            title = obj["title"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            url = obj["url"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            snippet = obj["snippet"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        )
    }.orEmpty()
}

@Serializable
data class ToolApprovalRequest(
    val sessionId: AgentSessionId,
    val toolCall: ToolCallPart,
)

@Serializable
sealed interface ToolApprovalDecision {
    @Serializable
    @SerialName("approve")
    data object Approve : ToolApprovalDecision

    @Serializable
    @SerialName("deny")
    data class Deny(val reason: String? = null) : ToolApprovalDecision
}

fun AgentMessage.text(): String = parts.filterIsInstance<TextPart>().joinToString(separator = "") { it.text }
