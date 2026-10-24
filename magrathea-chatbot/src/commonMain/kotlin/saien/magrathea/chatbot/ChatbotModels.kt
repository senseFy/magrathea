package saien.magrathea.chatbot

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import saien.magrathea.core.AgentFailureCode
import saien.magrathea.core.AgentInterruption
import saien.magrathea.core.AgentInterruptionReason
import saien.magrathea.core.AgentMessage
import saien.magrathea.core.AgentSessionSnapshot
import saien.magrathea.core.AttachmentPart
import saien.magrathea.core.CredentialRef
import saien.magrathea.core.ContextManagementState
import saien.magrathea.core.InlineToolImageSource
import saien.magrathea.core.MessageBlockPhase
import saien.magrathea.core.MessageRole
import saien.magrathea.core.MediaReference
import saien.magrathea.core.ModelDescriptor
import saien.magrathea.core.ProviderInterruptionPhase
import saien.magrathea.core.RemoteToolImageSource
import saien.magrathea.core.ReasoningContentKind
import saien.magrathea.core.ReasoningPreference
import saien.magrathea.core.ReasoningPart
import saien.magrathea.core.ReplayReport
import saien.magrathea.core.StopReason
import saien.magrathea.core.TextPart
import saien.magrathea.core.TokenUsage
import saien.magrathea.core.ToolCallPart
import saien.magrathea.core.ToolExecutionResult
import saien.magrathea.core.ToolImageAttachmentReference
import saien.magrathea.core.ToolImageSource
import saien.magrathea.core.ToolOrigin
import saien.magrathea.core.ToolResultAudience
import saien.magrathea.core.ToolResultContent
import saien.magrathea.core.ToolResultImageContent
import saien.magrathea.core.ToolResultPart
import saien.magrathea.core.citations
import saien.magrathea.core.requireSupports
import saien.magrathea.core.text
import saien.magrathea.runtime.BackgroundToolInstructions

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
    PROVIDER_FAILURE,
    ORPHANED,
}

enum class ChatbotProviderInterruptionPhase {
    BEFORE_FIRST_EVENT,
    AFTER_FIRST_EVENT,
}

data class ChatbotProviderInterruption(
    val failure: ChatbotFailure,
    val phase: ChatbotProviderInterruptionPhase,
    val retryAtEpochMs: Long? = null,
) {
    init {
        require(
            failure == ChatbotFailure.NETWORK ||
                failure == ChatbotFailure.TIMEOUT ||
                failure == ChatbotFailure.RATE_LIMITED ||
                failure == ChatbotFailure.PROVIDER,
        ) { "Provider interruption must describe a recoverable Provider failure" }
        require(retryAtEpochMs == null || retryAtEpochMs >= 0)
    }
}

data class ChatbotInterruption(
    val reason: ChatbotInterruptionReason,
    val provider: ChatbotProviderInterruption? = null,
    val occurredAtEpochMs: Long,
) {
    init {
        require((reason == ChatbotInterruptionReason.PROVIDER_FAILURE) == (provider != null))
        require(provider?.retryAtEpochMs == null || provider.retryAtEpochMs >= occurredAtEpochMs)
    }
}

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
    val reasoningPreference: ReasoningPreference = ReasoningPreference.Auto,
) {
    init {
        require(model.provider.isNotBlank()) { "Chatbot provider must not be blank" }
        require(model.model.isNotBlank()) { "Chatbot model must not be blank" }
        require(credentialRef == null || credentialRef.provider == model.provider) {
            "Chatbot credential provider must match the model provider"
        }
        model.requireSupports(reasoningPreference)
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

sealed interface ChatbotToolImageSource

data class ChatbotRemoteImageSource(
    val uri: String,
) : ChatbotToolImageSource

data class ChatbotInlineImageSource(
    val data: String,
) : ChatbotToolImageSource

data class ChatbotImageAttachmentReference(
    val uri: String,
) : ChatbotToolImageSource

data class ChatbotToolMediaAttribution(
    val title: String?,
    val url: String,
    val license: String?,
    val licenseUrl: String?,
)

/** Bounded presentation identity whose labels remain untrusted external text. */
data class ChatbotToolOrigin(
    val sourceId: String,
    val sourceLabel: String,
    val toolId: String,
    val toolLabel: String,
)

data class ChatbotToolImage(
    val source: ChatbotToolImageSource,
    val previewSource: ChatbotToolImageSource?,
    val previewMimeType: String?,
    val mimeType: String?,
    val title: String?,
    val altText: String?,
    val width: Int?,
    val height: Int?,
    val attribution: ChatbotToolMediaAttribution?,
    val reference: MediaReference? = null,
)

data class ChatbotToolResult(
    val id: String,
    val name: String,
    val text: String,
    val isError: Boolean,
    val errorCode: String? = null,
    val citations: List<ChatbotCitation> = emptyList(),
    val images: List<ChatbotToolImage> = emptyList(),
    val origin: ChatbotToolOrigin? = null,
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

/** Product-safe lifecycle of one Session-owned background Tool execution. */
enum class ChatbotBackgroundState {
    QUEUED,
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCELLED,
    INTERRUPTED,
}

/** Product-safe view of one Session-owned background execution tied to its originating call. */
data class ChatbotBackgroundExecution(
    val handle: String,
    val toolName: String,
    val originCallId: String,
    val state: ChatbotBackgroundState,
)

/** Session-owned background work projected for hosts; null while the runtime reports none. */
data class ChatbotBackgroundSnapshot(
    val executions: List<ChatbotBackgroundExecution>,
    val hasPendingActivation: Boolean,
    val requiresExecutionHost: Boolean,
)

/** Partitioned outcome of one cancelBackground command. */
data class ChatbotBackgroundCancellation(
    val cancelled: List<String>,
    val alreadyFinished: List<String>,
    val unknown: List<String>,
)

/** Derived lifecycle for one Tool call. Canonical messages remain the persisted source of truth. */
data class ChatbotToolActivitySnapshot(
    val key: ChatbotToolActivityKey,
    val call: ChatbotToolCall,
    val status: ChatbotToolActivityStatus,
    val resultMessageId: String? = null,
    val result: ChatbotToolResult? = null,
    /** Session-owned background execution that answers this call through a receipt. */
    val background: ChatbotBackgroundExecution? = null,
) {
    /** Retains the constructor published before background activities were projected. */
    constructor(
        key: ChatbotToolActivityKey,
        call: ChatbotToolCall,
        status: ChatbotToolActivityStatus,
        resultMessageId: String? = null,
        result: ChatbotToolResult? = null,
    ) : this(key, call, status, resultMessageId, result, background = null)

    /** Retains the pre-background copy/default-copy signatures while keeping the execution view. */
    fun copy(
        key: ChatbotToolActivityKey = this.key,
        call: ChatbotToolCall = this.call,
        status: ChatbotToolActivityStatus = this.status,
        resultMessageId: String? = this.resultMessageId,
        result: ChatbotToolResult? = this.result,
    ): ChatbotToolActivitySnapshot = ChatbotToolActivitySnapshot(
        key, call, status, resultMessageId, result, background = background,
    )

}

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
 * The generated summary and Tool-result stubs remain internal runtime details. Consumers can
 * observe whether compaction or clearing occurred without receiving content or persistence
 * identifiers.
 */
data class ChatbotContextManagementSnapshot(
    val compactionGeneration: Long = 0,
    val tokensBeforeLastCompaction: Long? = null,
    /** Generation of the Tool-result clearing watermark; zero when no result was cleared. */
    val clearingGeneration: Long = 0,
    /** Tool results currently replaced by stubs in the Provider projection. */
    val clearedToolResults: Int = 0,
) {
    /** Retains the constructor and default-argument signature published before clearing. */
    constructor(
        compactionGeneration: Long = 0,
        tokensBeforeLastCompaction: Long? = null,
    ) : this(compactionGeneration, tokensBeforeLastCompaction, clearingGeneration = 0, clearedToolResults = 0)

    /** Retains the original copy/default-copy signatures while preserving clearing state. */
    fun copy(
        compactionGeneration: Long = this.compactionGeneration,
        tokensBeforeLastCompaction: Long? = this.tokensBeforeLastCompaction,
    ): ChatbotContextManagementSnapshot = ChatbotContextManagementSnapshot(
        compactionGeneration,
        tokensBeforeLastCompaction,
        clearingGeneration = clearingGeneration,
        clearedToolResults = clearedToolResults,
    )

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
    /** Current run's canonical stop reason, independent of individual message reasons; cleared on a new run. */
    val stopReason: ChatbotStopReason? = null,
    /** Latest whole-message replay decisions, shared with the managed Runtime snapshot. */
    val replay: ReplayReport? = null,
    /** Session-owned background work; null while the runtime snapshot reports none. */
    val background: ChatbotBackgroundSnapshot? = null,
) {
    /** Retains the constructor published with the run stop reason. */
    constructor(
        configuration: ChatbotSessionConfiguration,
        sessionId: String? = null,
        messages: List<ChatbotMessageSnapshot> = emptyList(),
        status: ChatbotStatus = ChatbotStatus.IDLE,
        failure: ChatbotFailure? = null,
        interruption: ChatbotInterruption? = null,
        usage: ChatbotUsage = ChatbotUsage(),
        latestRequestUsage: ChatbotUsage = ChatbotUsage(),
        contextManagement: ChatbotContextManagementSnapshot = ChatbotContextManagementSnapshot(),
        toolActivities: List<ChatbotToolActivitySnapshot> = emptyList(),
        stopReason: ChatbotStopReason? = null,
    ) : this(
        configuration, sessionId, messages, status, failure, interruption, usage,
        latestRequestUsage, contextManagement, toolActivities, stopReason, replay = null,
    )

    /** Retains the replay-aware constructor published before the background projection. */
    constructor(
        configuration: ChatbotSessionConfiguration,
        sessionId: String? = null,
        messages: List<ChatbotMessageSnapshot> = emptyList(),
        status: ChatbotStatus = ChatbotStatus.IDLE,
        failure: ChatbotFailure? = null,
        interruption: ChatbotInterruption? = null,
        usage: ChatbotUsage = ChatbotUsage(),
        latestRequestUsage: ChatbotUsage = ChatbotUsage(),
        contextManagement: ChatbotContextManagementSnapshot = ChatbotContextManagementSnapshot(),
        toolActivities: List<ChatbotToolActivitySnapshot> = emptyList(),
        stopReason: ChatbotStopReason? = null,
        replay: ReplayReport? = null,
    ) : this(
        configuration, sessionId, messages, status, failure, interruption, usage,
        latestRequestUsage, contextManagement, toolActivities, stopReason, replay,
        background = null,
    )

    /** Retains the original constructor and its default-argument signature for compiled hosts. */
    constructor(
        configuration: ChatbotSessionConfiguration,
        sessionId: String? = null,
        messages: List<ChatbotMessageSnapshot> = emptyList(),
        status: ChatbotStatus = ChatbotStatus.IDLE,
        failure: ChatbotFailure? = null,
        interruption: ChatbotInterruption? = null,
        usage: ChatbotUsage = ChatbotUsage(),
        latestRequestUsage: ChatbotUsage = ChatbotUsage(),
        contextManagement: ChatbotContextManagementSnapshot = ChatbotContextManagementSnapshot(),
        toolActivities: List<ChatbotToolActivitySnapshot> = emptyList(),
    ) : this(
        configuration, sessionId, messages, status, failure, interruption, usage,
        latestRequestUsage, contextManagement, toolActivities, stopReason = null, replay = null,
    )

    /** Preserves the stop-reason-aware copy/default-copy signatures and the replay report. */
    fun copy(
        configuration: ChatbotSessionConfiguration = this.configuration,
        sessionId: String? = this.sessionId,
        messages: List<ChatbotMessageSnapshot> = this.messages,
        status: ChatbotStatus = this.status,
        failure: ChatbotFailure? = this.failure,
        interruption: ChatbotInterruption? = this.interruption,
        usage: ChatbotUsage = this.usage,
        latestRequestUsage: ChatbotUsage = this.latestRequestUsage,
        contextManagement: ChatbotContextManagementSnapshot = this.contextManagement,
        toolActivities: List<ChatbotToolActivitySnapshot> = this.toolActivities,
        stopReason: ChatbotStopReason? = this.stopReason,
    ): ChatbotSnapshot = ChatbotSnapshot(
        configuration, sessionId, messages, status, failure, interruption, usage,
        latestRequestUsage, contextManagement, toolActivities, stopReason, replay = replay,
        background = background,
    )

    /** Retains the original copy/default-copy signatures while preserving the run's stop reason. */
    fun copy(
        configuration: ChatbotSessionConfiguration = this.configuration,
        sessionId: String? = this.sessionId,
        messages: List<ChatbotMessageSnapshot> = this.messages,
        status: ChatbotStatus = this.status,
        failure: ChatbotFailure? = this.failure,
        interruption: ChatbotInterruption? = this.interruption,
        usage: ChatbotUsage = this.usage,
        latestRequestUsage: ChatbotUsage = this.latestRequestUsage,
        contextManagement: ChatbotContextManagementSnapshot = this.contextManagement,
        toolActivities: List<ChatbotToolActivitySnapshot> = this.toolActivities,
    ): ChatbotSnapshot = ChatbotSnapshot(
        configuration, sessionId, messages, status, failure, interruption, usage,
        latestRequestUsage, contextManagement, toolActivities, stopReason = stopReason,
        replay = replay, background = background,
    )

    /** Retains the replay-aware copy/default-copy signatures while keeping background state. */
    fun copy(
        configuration: ChatbotSessionConfiguration = this.configuration,
        sessionId: String? = this.sessionId,
        messages: List<ChatbotMessageSnapshot> = this.messages,
        status: ChatbotStatus = this.status,
        failure: ChatbotFailure? = this.failure,
        interruption: ChatbotInterruption? = this.interruption,
        usage: ChatbotUsage = this.usage,
        latestRequestUsage: ChatbotUsage = this.latestRequestUsage,
        contextManagement: ChatbotContextManagementSnapshot = this.contextManagement,
        toolActivities: List<ChatbotToolActivitySnapshot> = this.toolActivities,
        stopReason: ChatbotStopReason? = this.stopReason,
        replay: ReplayReport? = this.replay,
    ): ChatbotSnapshot = ChatbotSnapshot(
        configuration, sessionId, messages, status, failure, interruption, usage,
        latestRequestUsage, contextManagement, toolActivities, stopReason, replay,
        background = background,
    )

    val isRunning: Boolean
        get() = status == ChatbotStatus.RUNNING || status == ChatbotStatus.WAITING_FOR_TOOL
}

internal fun ContextManagementState.toChatbotContextManagementSnapshot() =
    ChatbotContextManagementSnapshot(
        compactionGeneration = compaction?.generation ?: 0,
        tokensBeforeLastCompaction = compaction?.tokensBefore,
        clearingGeneration = toolResultClearing?.generation ?: 0,
        clearedToolResults = toolResultClearing?.clearedResults ?: 0,
    )

data class ChatbotHistoryItem(
    val sessionId: String,
    val configuration: ChatbotSessionConfiguration,
    val updatedAtEpochMs: Long,
    val status: ChatbotStatus,
    val lastMessageText: String,
)

/**
 * A persisted session with the status [ChatbotClient.history] reports for it. Hosts that derive
 * their own summaries from stored messages read these instead of listing persistence again.
 */
data class ChatbotHistoryRecord(
    val snapshot: AgentSessionSnapshot,
    val status: ChatbotStatus,
)

enum class ChatbotFailure {
    INVALID_ARGUMENT,
    BUSY,
    CLOSED,
    NOT_FOUND,
    AUTHENTICATION,

    /** The credential works but the Provider refused access to the requested resource. */
    ACCESS_DENIED,
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

/** Chatbot facades invalidated before an operation reported its failure. */
enum class ChatbotInvalidationScope {
    NONE,
    SESSION,
    ALL_SESSIONS,
}

class ChatbotException(
    val failure: ChatbotFailure,
    /** Machine-readable facade effect; it does not claim that persistence was removed. */
    val invalidationScope: ChatbotInvalidationScope,
) : IllegalStateException("Chatbot operation failed (${failure.name.lowercase()})") {
    constructor(failure: ChatbotFailure) : this(failure, ChatbotInvalidationScope.NONE)
}

/**
 * Runtime notices are not conversation content; request-building lists keep them, snapshots skip
 * them. Control traffic hides by marker, not by name: a result disappears only when the Runtime
 * marked it, a persisted call only when its paired result was marked, and an unanswered call only
 * while the Session's request reserves control names (the `backgroundEnabled` flag).
 */
internal fun List<AgentMessage>.toChatbotMessageSnapshots(
    backgroundEnabled: Boolean = false,
): List<ChatbotMessageSnapshot> {
    val callIndex = chatbotCallIndex(backgroundEnabled)
    return filter { it.role != MessageRole.RUNTIME }
        .map { message ->
            message to message.toChatbotMessageSnapshot(callIndex)
        }
        .filter { (original, snapshot) ->
            // Runtime control calls never surface; drop only messages the filtering emptied.
            original.parts.filterIsInstance<ToolCallPart>()
                .withIndex()
                .none { (ordinal, _) -> callIndex.isHidden(original.id, ordinal) } &&
                original.parts.none { it is ToolResultPart && it.isBackgroundControlResult() } ||
                snapshot.text.isNotBlank() ||
                snapshot.textBlocks.isNotEmpty() ||
                snapshot.reasoning.isNotEmpty() ||
                snapshot.attachments.isNotEmpty() ||
                snapshot.toolCalls.isNotEmpty() ||
                snapshot.toolResults.isNotEmpty()
        }
        .map { it.second }
}

/**
 * One pass over canonical history that decides every call's visibility and identity: results
 * pair with the most recent preceding same-ID-and-name call, a call is hidden when its paired
 * result is runtime control traffic or it is an unanswered reserved-name call under
 * `backgroundEnabled`, and visible calls get their activity key (message + visible ordinal).
 */
internal class ChatbotCallIndex(
    val calls: List<ChatbotCallIndexEntry>,
    val resultBindings: List<ResultBinding>,
) {
    class ChatbotCallIndexEntry(
        val call: ToolCallPart,
        val messageId: String,
        val rawOrdinal: Int,
        val hidden: Boolean,
        val key: ChatbotToolActivityKey?,
    )

    class ResultBinding(
        val result: ToolResultPart,
        val call: ChatbotCallIndexEntry?,
    )

    private val hiddenCalls = calls.mapNotNullTo(mutableSetOf()) {
        if (it.hidden) it.messageId to it.rawOrdinal else null
    }

    fun isHidden(messageId: String, callOrdinal: Int): Boolean =
        (messageId to callOrdinal) in hiddenCalls

    fun lastKeyFor(toolCallId: String, toolName: String): ChatbotToolActivityKey? =
        calls.indexOfLast {
            it.call.toolCallId == toolCallId && it.call.toolName == toolName
        }.takeIf { it >= 0 }?.let { calls[it].key }
}

internal fun List<AgentMessage>.chatbotCallIndex(
    backgroundEnabled: Boolean,
): ChatbotCallIndex {
    val calls = mutableListOf<Triple<String, Int, ToolCallPart>>()
    val resultScan = mutableListOf<Pair<ToolResultPart, Int>>()
    val pairedCalls = mutableSetOf<Int>()
    val markedCalls = mutableSetOf<Int>()
    forEach { message ->
        message.parts.filterIsInstance<ToolCallPart>().forEachIndexed { ordinal, call ->
            calls += Triple(message.id, ordinal, call)
        }
        message.parts.filterIsInstance<ToolResultPart>().forEach { part ->
            val callIdx = calls.indexOfLast {
                it.third.toolCallId == part.toolCallId && it.third.toolName == part.toolName
            }
            if (callIdx >= 0) {
                pairedCalls += callIdx
                if (part.isBackgroundControlResult()) markedCalls += callIdx
            }
            resultScan += part to callIdx
        }
    }
    val visibleOrdinals = mutableMapOf<String, Int>()
    val entries = calls.mapIndexed { idx, (messageId, rawOrdinal, call) ->
        val hidden = idx in markedCalls ||
            (
                idx !in pairedCalls && backgroundEnabled &&
                    isBackgroundControlTool(call.toolName)
                )
        val key = if (hidden) {
            null
        } else {
            val ordinal = visibleOrdinals[messageId] ?: 0
            visibleOrdinals[messageId] = ordinal + 1
            ChatbotToolActivityKey(messageId, ordinal)
        }
        ChatbotCallIndex.ChatbotCallIndexEntry(call, messageId, rawOrdinal, hidden, key)
    }
    val bindings = resultScan.map { (result, callIdx) ->
        ChatbotCallIndex.ResultBinding(
            result,
            callIdx.takeIf { it >= 0 }?.let(entries::get),
        )
    }
    return ChatbotCallIndex(entries, bindings)
}

internal fun isBackgroundControlTool(toolName: String): Boolean =
    toolName in BackgroundToolInstructions.CONTROL_TOOL_NAMES

/** A Tool result the Runtime authored as background control traffic. */
internal fun ToolResultPart.isBackgroundControlResult(): Boolean =
    metadata[BackgroundToolInstructions.CONTROL_RESULT_METADATA_KEY]
        ?.jsonPrimitive?.booleanOrNull == true

/** Same marker on an event-time result. */
internal fun ToolExecutionResult.isBackgroundControlResult(): Boolean =
    metadata[BackgroundToolInstructions.CONTROL_RESULT_METADATA_KEY]
        ?.jsonPrimitive?.booleanOrNull == true

internal fun AgentMessage.toChatbotMessageSnapshot(
    callIndex: ChatbotCallIndex? = null,
    backgroundEnabled: Boolean = false,
): ChatbotMessageSnapshot {
    val index = callIndex ?: listOf(this).chatbotCallIndex(backgroundEnabled)
    return ChatbotMessageSnapshot(
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
        toolCalls = parts.filterIsInstance<ToolCallPart>()
            .withIndex()
            .filterNot { (ordinal, _) -> index.isHidden(id, ordinal) }
            .map { it.value.toChatbotToolCall() },
        toolResults = parts.filterIsInstance<ToolResultPart>()
            .filterNot { it.isBackgroundControlResult() }
            .map(ToolResultPart::toChatbotToolResult),
        createdAtEpochMs = createdAtEpochMs,
        stopReason = stopReason?.toChatbotStopReason(),
    )
}

internal fun ToolResultPart.toChatbotToolResult(): ChatbotToolResult = ChatbotToolResult(
    id = toolCallId,
    name = toolName,
    text = userVisibleText(),
    isError = isError,
    errorCode = userErrorCode,
    citations = citations().toChatbotCitations(),
    images = content.toChatbotToolImages(),
    origin = origin?.toChatbotToolOrigin(),
)

internal fun ToolExecutionResult.toChatbotToolResult(): ChatbotToolResult = ChatbotToolResult(
    id = toolCallId,
    name = toolName,
    text = userVisibleText(),
    isError = isError,
    errorCode = userErrorCode,
    citations = citations().toChatbotCitations(),
    images = content.toChatbotToolImages(),
    origin = origin?.toChatbotToolOrigin(),
)

private fun ToolResultPart.userVisibleText(): String = displayText
    ?: content.userVisibleText()
    ?: if (isError) "Tool failed." else "Tool completed."

private fun ToolExecutionResult.userVisibleText(): String = displayText
    ?: content.userVisibleText()
    ?: if (isError) "Tool failed." else "Tool completed."

private fun List<ToolResultContent>.userVisibleText(): String? =
    filterIsInstance<saien.magrathea.core.ToolResultTextContent>()
        .filter { ToolResultAudience.USER in it.audiences }
        .joinToString("\n", transform = saien.magrathea.core.ToolResultTextContent::text)
        .takeIf(String::isNotBlank)

private fun ToolOrigin.toChatbotToolOrigin(): ChatbotToolOrigin = ChatbotToolOrigin(
    sourceId = sourceId,
    sourceLabel = sourceLabel,
    toolId = toolId,
    toolLabel = toolLabel,
)

private fun List<ToolResultContent>.toChatbotToolImages(): List<ChatbotToolImage> =
    filterIsInstance<ToolResultImageContent>()
        .filter { ToolResultAudience.USER in it.audiences }
        .map { image ->
            ChatbotToolImage(
                reference = image.reference,
                source = image.source.toChatbotSource(),
                previewSource = image.previewSource?.toChatbotSource(),
                previewMimeType = image.previewMimeType,
                mimeType = image.mimeType,
                title = image.title,
                altText = image.altText,
                width = image.width,
                height = image.height,
                attribution = image.attribution?.let {
                    ChatbotToolMediaAttribution(
                        title = it.title,
                        url = it.url,
                        license = it.license,
                        licenseUrl = it.licenseUrl,
                    )
                },
            )
        }

private fun ToolImageSource.toChatbotSource(): ChatbotToolImageSource = when (this) {
    is RemoteToolImageSource -> ChatbotRemoteImageSource(uri)
    is InlineToolImageSource -> ChatbotInlineImageSource(data)
    is ToolImageAttachmentReference -> ChatbotImageAttachmentReference(uri)
}

internal fun ToolCallPart.toChatbotToolCall(): ChatbotToolCall = ChatbotToolCall(
    id = toolCallId,
    name = toolName,
    arguments = arguments.toString(),
    partial = partial,
)

private fun List<saien.magrathea.core.Citation>.toChatbotCitations(): List<ChatbotCitation> =
    filter { citation -> citation.title.isNotBlank() && citation.url.isNotBlank() }
        .map { citation -> ChatbotCitation(citation.title, citation.url, citation.snippet) }

internal fun AgentFailureCode.toChatbotFailure(): ChatbotFailure = when (this) {
    AgentFailureCode.NOT_FOUND -> ChatbotFailure.NOT_FOUND
    AgentFailureCode.PROVIDER_AUTH, AgentFailureCode.CREDENTIAL_UNAVAILABLE -> ChatbotFailure.AUTHENTICATION
    AgentFailureCode.PROVIDER_PERMISSION -> ChatbotFailure.ACCESS_DENIED
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

internal fun StopReason.toChatbotStopReason(): ChatbotStopReason = when (this) {
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
            AgentInterruptionReason.PROVIDER_FAILURE ->
                ChatbotInterruptionReason.PROVIDER_FAILURE
            AgentInterruptionReason.ORPHANED ->
                ChatbotInterruptionReason.ORPHANED
        },
        provider = provider?.let {
            ChatbotProviderInterruption(
                failure = it.code.toChatbotFailure(),
                phase = it.phase.toChatbotProviderInterruptionPhase(),
                retryAtEpochMs = it.retryAtEpochMs,
            )
        },
        occurredAtEpochMs = occurredAtEpochMs,
    )

private fun ProviderInterruptionPhase.toChatbotProviderInterruptionPhase(): ChatbotProviderInterruptionPhase =
    when (this) {
        ProviderInterruptionPhase.BEFORE_FIRST_EVENT ->
            ChatbotProviderInterruptionPhase.BEFORE_FIRST_EVENT
        ProviderInterruptionPhase.AFTER_FIRST_EVENT ->
            ChatbotProviderInterruptionPhase.AFTER_FIRST_EVENT
    }

private fun MessageRole.toChatbotRole(): ChatbotMessageRole = when (this) {
    MessageRole.USER -> ChatbotMessageRole.USER
    MessageRole.ASSISTANT -> ChatbotMessageRole.ASSISTANT
    MessageRole.TOOL -> ChatbotMessageRole.TOOL
    MessageRole.SYSTEM -> ChatbotMessageRole.SYSTEM
    MessageRole.RUNTIME -> error("Runtime notices are not Chatbot conversation messages")
}
