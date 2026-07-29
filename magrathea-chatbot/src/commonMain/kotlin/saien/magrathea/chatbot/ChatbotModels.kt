package saien.magrathea.chatbot

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import saien.magrathea.core.AgentFailureCode
import saien.magrathea.core.AgentInterruption
import saien.magrathea.core.AgentInterruptionReason
import saien.magrathea.core.AgentMessage
import saien.magrathea.core.AttachmentPart
import saien.magrathea.core.CredentialRef
import saien.magrathea.core.ContextManagementState
import saien.magrathea.core.MessageBlockPhase
import saien.magrathea.core.MessageRole
import saien.magrathea.core.ModelDescriptor
import saien.magrathea.core.ReasoningContentKind
import saien.magrathea.core.ReasoningPart
import saien.magrathea.core.StopReason
import saien.magrathea.core.TextPart
import saien.magrathea.core.TokenUsage
import saien.magrathea.core.ToolCallPart
import saien.magrathea.core.ToolExecutionResult
import saien.magrathea.core.ToolResultPart
import saien.magrathea.core.citations
import saien.magrathea.core.outputText
import saien.magrathea.core.text

enum class ChatbotStatus {
    IDLE,
    RUNNING,
    WAITING_FOR_TOOL,
    COMPLETED,
    FAILED,
    CANCELLED,
    INTERRUPTED,
    RECOVERY_BLOCKED,
}

enum class ChatbotMessageRole {
    USER,
    ASSISTANT,
    TOOL,
    SYSTEM,
}

enum class ChatbotMessagePhase {
    COMMENTARY,
    FINAL,
}

enum class ChatbotStopReason {
    COMPLETED,
    TOOL_CALLS,
    CANCELLED,
    ERROR,
    MAX_TURNS,
    MAX_TOKENS,
    RETRY,
    INTERRUPTED,
}

enum class ChatbotInterruptionReason {
    HOST_REQUESTED,
    PROVIDER_NETWORK,
    PROVIDER_TIMEOUT,
    ORPHANED,
}

data class ChatbotInterruption(
    val reason: ChatbotInterruptionReason,
    val occurredAtEpochMs: Long,
)

data class ChatbotTextBlock(
    val text: String,
    val phase: ChatbotMessagePhase? = null,
)

data class ChatbotReasoningBlock(
    val text: String,
    val redacted: Boolean,
    val kind: ChatbotReasoningKind = ChatbotReasoningKind.PROVIDER_DEFINED,
    val phase: ChatbotMessagePhase? = null,
)

enum class ChatbotReasoningKind {
    PROVIDER_DEFINED,
    SUMMARY,
    TEXT,
}

data class ChatbotAttachment(
    val uri: String,
    val mimeType: String,
    val fileName: String? = null,
)

data class ChatbotSendOptions(
    val metadata: JsonObject = buildJsonObject { },
)

/** Provider profile/model selection owned by one Chatbot session. */
data class ChatbotSessionConfiguration(
    val model: ModelDescriptor,
    val credentialRef: CredentialRef? = null,
) {
    init {
        require(model.provider.isNotBlank()) { "Chatbot provider must not be blank" }
        require(model.model.isNotBlank()) { "Chatbot model must not be blank" }
        require(credentialRef == null || credentialRef.provider == model.provider) {
            "Chatbot credential provider must match the model provider"
        }
    }
}

data class ChatbotCitation(
    val title: String,
    val url: String,
    val snippet: String,
)

data class ChatbotToolCall(
    val id: String,
    val name: String,
    val arguments: String,
    val partial: Boolean,
)

data class ChatbotToolResult(
    val id: String,
    val name: String,
    val text: String,
    val isError: Boolean,
    val errorCode: String? = null,
    val citations: List<ChatbotCitation> = emptyList(),
    val metadata: JsonObject = buildJsonObject { },
)

enum class ChatbotToolActivityStatus {
    PREPARING,
    PENDING,
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCELLED,
    INTERRUPTED,
}

/** Stable identity for one Tool call, including providers that reuse call IDs across turns. */
data class ChatbotToolActivityKey(
    val messageId: String,
    val callOrdinal: Int,
)

/** Derived lifecycle for one Tool call. Canonical messages remain the persisted source of truth. */
data class ChatbotToolActivitySnapshot(
    val key: ChatbotToolActivityKey,
    val call: ChatbotToolCall,
    val status: ChatbotToolActivityStatus,
    val resultMessageId: String? = null,
    val result: ChatbotToolResult? = null,
)

data class ChatbotMessageSnapshot(
    val id: String,
    val role: ChatbotMessageRole,
    val text: String,
    val textBlocks: List<ChatbotTextBlock> = emptyList(),
    val reasoning: List<ChatbotReasoningBlock> = emptyList(),
    val attachments: List<ChatbotAttachment> = emptyList(),
    val toolCalls: List<ChatbotToolCall> = emptyList(),
    val toolResults: List<ChatbotToolResult> = emptyList(),
    val createdAtEpochMs: Long,
    val stopReason: ChatbotStopReason? = null,
)

data class ChatbotUsage(
    val inputTokens: Long? = null,
    val outputTokens: Long? = null,
    val reasoningTokens: Long? = null,
)

/**
 * Product-safe context-management metadata.
 *
 * The generated summary remains an internal runtime detail. Consumers can observe whether
 * compaction occurred without receiving the summary text or persistence identifiers.
 */
data class ChatbotContextManagementSnapshot(
    val compactionGeneration: Long = 0,
    val tokensBeforeLastCompaction: Long? = null,
) {
    val isCompacted: Boolean
        get() = compactionGeneration > 0
}

/** Immutable product-facing state for an observed Chatbot session. */
data class ChatbotSnapshot(
    val configuration: ChatbotSessionConfiguration,
    val sessionId: String? = null,
    val messages: List<ChatbotMessageSnapshot> = emptyList(),
    val status: ChatbotStatus = ChatbotStatus.IDLE,
    val failure: ChatbotFailure? = null,
    val interruption: ChatbotInterruption? = null,
    val usage: ChatbotUsage = ChatbotUsage(),
    val latestRequestUsage: ChatbotUsage = ChatbotUsage(),
    val contextManagement: ChatbotContextManagementSnapshot = ChatbotContextManagementSnapshot(),
    val toolActivities: List<ChatbotToolActivitySnapshot> = emptyList(),
) {
    val isRunning: Boolean
        get() = status == ChatbotStatus.RUNNING || status == ChatbotStatus.WAITING_FOR_TOOL
}

internal fun ContextManagementState.toChatbotContextManagementSnapshot() =
    ChatbotContextManagementSnapshot(
        compactionGeneration = compaction?.generation ?: 0,
        tokensBeforeLastCompaction = compaction?.tokensBefore,
    )

data class ChatbotHistoryItem(
    val sessionId: String,
    val configuration: ChatbotSessionConfiguration,
    val updatedAtEpochMs: Long,
    val status: ChatbotStatus,
    val lastMessageText: String,
)

enum class ChatbotFailure {
    INVALID_ARGUMENT,
    BUSY,
    CLOSED,
    NOT_FOUND,
    AUTHENTICATION,
    RATE_LIMITED,
    CONTEXT_LIMIT,
    TIMEOUT,
    NETWORK,
    PROTOCOL,
    PROVIDER,
    STORAGE,
    RECOVERY_BLOCKED,
    OPERATION_FAILED,
}

class ChatbotException(
    val failure: ChatbotFailure,
) : IllegalStateException("Chatbot operation failed (${failure.name.lowercase()})")

internal fun AgentMessage.toChatbotMessageSnapshot(): ChatbotMessageSnapshot = ChatbotMessageSnapshot(
    id = id,
    role = role.toChatbotRole(),
    text = text(),
    textBlocks = parts.filterIsInstance<TextPart>().map {
        ChatbotTextBlock(text = it.text, phase = it.phase?.toChatbotPhase())
    },
    reasoning = parts.filterIsInstance<ReasoningPart>().map {
        ChatbotReasoningBlock(
            text = it.text,
            redacted = it.redacted,
            kind = it.kind.toChatbotReasoningKind(),
            phase = it.phase?.toChatbotPhase(),
        )
    },
    attachments = parts.filterIsInstance<AttachmentPart>().map {
        ChatbotAttachment(uri = it.uri, mimeType = it.mimeType, fileName = it.fileName)
    },
    toolCalls = parts.filterIsInstance<ToolCallPart>().map(ToolCallPart::toChatbotToolCall),
    toolResults = parts.filterIsInstance<ToolResultPart>().map(ToolResultPart::toChatbotToolResult),
    createdAtEpochMs = createdAtEpochMs,
    stopReason = stopReason?.toChatbotStopReason(),
)

internal fun ToolResultPart.toChatbotToolResult(): ChatbotToolResult = ChatbotToolResult(
    id = toolCallId,
    name = toolName,
    text = outputText(),
    isError = isError,
    errorCode = result.errorCode(),
    citations = citations().toChatbotCitations(),
    metadata = metadata,
)

internal fun ToolExecutionResult.toChatbotToolResult(): ChatbotToolResult = ChatbotToolResult(
    id = toolCallId,
    name = toolName,
    text = displayText ?: result.let { value ->
        (value as? JsonPrimitive)?.contentOrNull ?: value.toString()
    },
    isError = isError,
    errorCode = result.errorCode(),
    citations = citations().toChatbotCitations(),
    metadata = metadata,
)

internal fun ToolCallPart.toChatbotToolCall(): ChatbotToolCall = ChatbotToolCall(
    id = toolCallId,
    name = toolName,
    arguments = arguments.toString(),
    partial = partial,
)

private fun kotlinx.serialization.json.JsonElement.errorCode(): String? =
    (this as? JsonObject)
        ?.get("code")
        ?.let { value -> (value as? JsonPrimitive)?.contentOrNull }
        ?.takeIf(String::isNotBlank)

private fun List<saien.magrathea.core.Citation>.toChatbotCitations(): List<ChatbotCitation> =
    filter { citation -> citation.title.isNotBlank() && citation.url.isNotBlank() }
        .map { citation -> ChatbotCitation(citation.title, citation.url, citation.snippet) }

internal fun AgentFailureCode.toChatbotFailure(): ChatbotFailure = when (this) {
    AgentFailureCode.NOT_FOUND -> ChatbotFailure.NOT_FOUND
    AgentFailureCode.PROVIDER_AUTH, AgentFailureCode.CREDENTIAL_UNAVAILABLE -> ChatbotFailure.AUTHENTICATION
    AgentFailureCode.PROVIDER_RATE_LIMIT -> ChatbotFailure.RATE_LIMITED
    AgentFailureCode.CONTEXT_LIMIT -> ChatbotFailure.CONTEXT_LIMIT
    AgentFailureCode.TIMEOUT -> ChatbotFailure.TIMEOUT
    AgentFailureCode.PROVIDER_NETWORK -> ChatbotFailure.NETWORK
    AgentFailureCode.PROVIDER_PROTOCOL -> ChatbotFailure.PROTOCOL
    AgentFailureCode.PROVIDER_NOT_FOUND,
    AgentFailureCode.PROVIDER_CLIENT,
    AgentFailureCode.PROVIDER_SERVER -> ChatbotFailure.PROVIDER
    AgentFailureCode.STORAGE -> ChatbotFailure.STORAGE
    AgentFailureCode.INVALID_STATE, AgentFailureCode.INTERNAL -> ChatbotFailure.OPERATION_FAILED
}

internal fun TokenUsage.toChatbotUsage(): ChatbotUsage = ChatbotUsage(
    inputTokens = inputTokens,
    outputTokens = outputTokens,
    reasoningTokens = reasoningTokens,
)

internal fun userChatbotMessage(
    text: String,
    attachments: List<ChatbotAttachment> = emptyList(),
    metadata: JsonObject = buildJsonObject { },
): AgentMessage = AgentMessage(
    role = MessageRole.USER,
    parts = buildList {
        if (text.isNotBlank()) add(TextPart(text))
        addAll(attachments.map {
            AttachmentPart(uri = it.uri, mimeType = it.mimeType, fileName = it.fileName)
        })
    },
    metadata = metadata,
)

private fun MessageBlockPhase.toChatbotPhase(): ChatbotMessagePhase = when (this) {
    MessageBlockPhase.COMMENTARY -> ChatbotMessagePhase.COMMENTARY
    MessageBlockPhase.FINAL -> ChatbotMessagePhase.FINAL
}

private fun ReasoningContentKind.toChatbotReasoningKind(): ChatbotReasoningKind = when (this) {
    ReasoningContentKind.PROVIDER_DEFINED -> ChatbotReasoningKind.PROVIDER_DEFINED
    ReasoningContentKind.SUMMARY -> ChatbotReasoningKind.SUMMARY
    ReasoningContentKind.TEXT -> ChatbotReasoningKind.TEXT
}

private fun StopReason.toChatbotStopReason(): ChatbotStopReason = when (this) {
    StopReason.COMPLETED -> ChatbotStopReason.COMPLETED
    StopReason.TOOL_CALLS -> ChatbotStopReason.TOOL_CALLS
    StopReason.CANCELLED -> ChatbotStopReason.CANCELLED
    StopReason.ERROR -> ChatbotStopReason.ERROR
    StopReason.MAX_TURNS -> ChatbotStopReason.MAX_TURNS
    StopReason.MAX_TOKENS -> ChatbotStopReason.MAX_TOKENS
    StopReason.RETRY -> ChatbotStopReason.RETRY
    StopReason.INTERRUPTED -> ChatbotStopReason.INTERRUPTED
}

internal fun AgentInterruption.toChatbotInterruption(): ChatbotInterruption =
    ChatbotInterruption(
        reason = when (reason) {
            AgentInterruptionReason.HOST_REQUESTED ->
                ChatbotInterruptionReason.HOST_REQUESTED
            AgentInterruptionReason.PROVIDER_NETWORK ->
                ChatbotInterruptionReason.PROVIDER_NETWORK
            AgentInterruptionReason.PROVIDER_TIMEOUT ->
                ChatbotInterruptionReason.PROVIDER_TIMEOUT
            AgentInterruptionReason.ORPHANED ->
                ChatbotInterruptionReason.ORPHANED
        },
        occurredAtEpochMs = occurredAtEpochMs,
    )

private fun MessageRole.toChatbotRole(): ChatbotMessageRole = when (this) {
    MessageRole.USER -> ChatbotMessageRole.USER
    MessageRole.ASSISTANT -> ChatbotMessageRole.ASSISTANT
    MessageRole.TOOL -> ChatbotMessageRole.TOOL
    MessageRole.SYSTEM -> ChatbotMessageRole.SYSTEM
}
