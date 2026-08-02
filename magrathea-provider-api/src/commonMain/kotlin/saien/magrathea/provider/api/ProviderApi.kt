package saien.magrathea.provider.api

import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import saien.magrathea.core.AgentMessage
import saien.magrathea.core.AgentModelFactory
import saien.magrathea.core.AgentSessionId
import saien.magrathea.core.CredentialRef
import saien.magrathea.core.MessageBlockPhase
import saien.magrathea.core.MessagePart
import saien.magrathea.core.MessageRole
import saien.magrathea.core.ModelDescriptor
import saien.magrathea.core.ModelInputModality
import saien.magrathea.core.ProviderOptions
import saien.magrathea.core.ProviderCredential
import saien.magrathea.core.ProviderTimeoutConfig
import saien.magrathea.core.ReplayPolicy
import saien.magrathea.core.ReasoningContentKind
import saien.magrathea.core.ReasoningPart
import saien.magrathea.core.StopReason
import saien.magrathea.core.TextPart
import saien.magrathea.core.ToolCallPart
import saien.magrathea.core.ToolCallLifecycle
import saien.magrathea.core.ToolDefinition
import saien.magrathea.core.ToolResultAudience
import saien.magrathea.core.ToolResultContent
import saien.magrathea.core.ToolResultImageContent
import saien.magrathea.core.ToolResultPart
import saien.magrathea.core.ToolResultTextContent

/** Model-visible canonical and typed representations of one Tool result. */
class ToolResultModelProjection internal constructor(
    val content: List<ToolResultContent>,
    val canonicalResult: JsonElement?,
) {
    init {
        require(content.isNotEmpty() || canonicalResult != null) {
            "Tool result projection must contain at least one representation"
        }
    }
}

/**
 * Selects every model-visible representation without duplicating an equivalent JSON text block.
 * Canonical structured data and independent typed content remain composable.
 */
fun ToolResultPart.modelProjection(
    inputModalities: Set<ModelInputModality>,
    inputCapabilities: ProviderInputCapabilities,
): ToolResultModelProjection {
    val acceptedContent = content
        .filter { ToolResultAudience.MODEL in it.audiences }
        .filter { item ->
            if (item !is ToolResultImageContent) return@filter true
            val mimeType = item.mimeType ?: return@filter false
            ModelInputModality.IMAGE in inputModalities &&
                inputCapabilities.supportsAttachment(mimeType)
        }
    val canonicalResult = if (modelResultVisible) {
        result
    } else if (acceptedContent.isEmpty()) {
        neutralModelResult()
    } else {
        null
    }
    val deduplicatedContent = acceptedContent.filterNot { item ->
        item is ToolResultTextContent &&
            canonicalResult != null &&
            item.text.isStructurallyEquivalentJson(canonicalResult)
    }
    return ToolResultModelProjection(
        content = deduplicatedContent,
        canonicalResult = canonicalResult,
    )
}

/**
 * Removes product-only and USER-audience data before a Tool result crosses a model boundary.
 * Provider-specific modality filtering remains the responsibility of [modelProjection].
 */
fun ToolResultPart.sanitizedForModelBoundary(): ToolResultPart = copy(
    result = if (modelResultVisible) result else neutralModelResult(),
    displayText = null,
    userErrorCode = null,
    metadata = JsonObject(emptyMap()),
    content = content.mapNotNull { item ->
        if (ToolResultAudience.MODEL !in item.audiences) return@mapNotNull null
        when (item) {
            is ToolResultTextContent -> item.copy(
                audiences = setOf(ToolResultAudience.MODEL),
            )
            is ToolResultImageContent -> item.copy(
                previewSource = null,
                previewMimeType = null,
                attribution = null,
                audiences = setOf(ToolResultAudience.MODEL),
                reference = null,
            )
        }
    },
    providerMetadata = null,
    origin = null,
)

private fun ToolResultPart.neutralModelResult(): JsonPrimitive = JsonPrimitive(
    if (isError) {
        "Tool failed without model-visible error details."
    } else {
        "Tool completed without model-visible output."
    },
)

private fun String.isStructurallyEquivalentJson(canonical: JsonElement): Boolean =
    (canonical as? JsonPrimitive)?.contentOrNull == this ||
        runCatching { Json.parseToJsonElement(this) }.getOrNull() == canonical

/** How a Provider resumes an invocation interrupted while its model response was pending. */
enum class ProviderInvocationResumeMode {
    /** Start another physical Provider attempt under the same logical Agent run. */
    NEW_ATTEMPT,

    /** Reuse the invocation identity to reattach to a durable remote stream. */
    REATTACH,
}

/** Runtime intent for one physical Provider invocation identity. */
@Serializable
enum class ProviderInvocationIntent {
    /** Begin work for a newly claimed invocation identity. */
    @SerialName("create")
    CREATE,

    /** Resolve and continue existing work without creating it when the identity is unknown. */
    @SerialName("reattach")
    REATTACH,
}

/** Terminal versus recoverable intent behind cancellation of an active Provider collection. */
enum class ProviderCancellationIntent {
    /** Request terminal abandonment and best-effort cleanup of durable remote resources. */
    CANCEL,

    /** Pause local collection while leaving a durable invocation available for reattachment. */
    INTERRUPT,
}

/**
 * Read-only cancellation intent propagated through the Provider coroutine context.
 *
 * Runtime-owned collection always uses [ProviderCancellationIntent.INTERRUPT] for local detach;
 * durable terminal cleanup begins only through [ProviderAdapter.abandon] after terminal state is
 * committed. Direct Provider collection has no signal and therefore defaults to terminal
 * [ProviderCancellationIntent.CANCEL]. Providers without durable remote work may retain normal
 * coroutine cancellation behavior.
 */
interface ProviderCancellationContext : CoroutineContext.Element {
    val intent: ProviderCancellationIntent

    override val key: CoroutineContext.Key<*>
        get() = Key

    companion object Key : CoroutineContext.Key<ProviderCancellationContext>
}

/** Returns the explicit Runtime intent, defaulting direct collection cancellation to terminal. */
fun CoroutineContext.providerCancellationIntent(): ProviderCancellationIntent =
    this[ProviderCancellationContext]?.intent ?: ProviderCancellationIntent.CANCEL

/** Converts one Provider wire protocol into the canonical Magrathea event lifecycle. */
interface ProviderAdapter {
    /** Runtime routing key and credential namespace for this configured Provider instance. */
    val key: String

    /** Invocation identity behavior when Runtime resumes a pending model response. */
    val invocationResumeMode: ProviderInvocationResumeMode
        get() = ProviderInvocationResumeMode.NEW_ATTEMPT

    /** Typed configuration family consumed by this adapter, independent from [key]. */
    val optionsFamily: String?
        get() = null

    /** Protocol encoder capabilities for the effective request configuration. */
    fun inputCapabilities(config: ProviderTransportConfig? = null): ProviderInputCapabilities =
        ProviderInputCapabilities()

    /**
     * Best-effort terminal abandonment of durable work identified by [invocation].
     *
     * Direct Providers normally have no detached work and keep the default no-op. Adapters that
     * support [ProviderInvocationResumeMode.REATTACH] may override this to release a persisted
     * invocation after Runtime commits the terminal state that discards it.
     */
    suspend fun abandon(invocation: ProviderInvocation) = Unit

    suspend fun generate(request: ProviderRequest): Flow<ProviderChunk>
    fun close() = Unit
}

@Serializable
data class ProviderInvocation(
    val requestId: String,
    val sessionId: AgentSessionId,
    val turn: Int,
) {
    init {
        require(requestId.isNotBlank()) { "Provider invocation requestId must not be blank" }
        require(sessionId.value.isNotBlank()) { "Provider invocation sessionId must not be blank" }
        require(turn >= 0) { "Provider invocation turn must not be negative" }
    }
}

/** Sanitized request passed from Runtime to a Provider adapter. */
@Serializable
data class ProviderRequest(
    val invocation: ProviderInvocation? = null,
    val invocationIntent: ProviderInvocationIntent = ProviderInvocationIntent.CREATE,
    val model: ModelDescriptor,
    val messages: List<AgentMessage>,
    val tools: List<ToolDefinition> = emptyList(),
    val credentialRef: CredentialRef? = null,
    @Transient
    val credential: ProviderCredential? = null,
    val temperature: Double? = null,
    val maxTokens: Int? = null,
    @Transient
    val endpoint: String? = null,
    @Transient
    val headers: Map<String, String> = emptyMap(),
    val typedConfig: ProviderTransportConfig? = null,
    val timeouts: ProviderTimeoutConfig = ProviderTimeoutConfig(),
) {
    init {
        endpoint?.let(::requireValidHttpEndpoint)
        require(invocationIntent != ProviderInvocationIntent.REATTACH || invocation != null) {
            "Provider reattachment requires an invocation identity"
        }
    }

    override fun toString(): String {
        val safeEndpoint = redactHttpUrl(endpoint)
        return "ProviderRequest(" +
            "invocation=$invocation, " +
            "invocationIntent=$invocationIntent, " +
            "model=$model, " +
            "messages=${messages.size}, " +
            "tools=${tools.size}, " +
            "credentialRef=$credentialRef, " +
            "credential=${if (credential == null) "none" else "<redacted>"}, " +
            "temperature=$temperature, " +
            "maxTokens=$maxTokens, " +
            "endpoint=$safeEndpoint, " +
            "headerNames=${headers.keys}, " +
            "typedConfig=${typedConfig?.familyName() ?: "none"}, " +
            "timeouts=$timeouts" +
            ")"
    }
}

private fun ProviderTransportConfig.familyName(): String = when (this) {
    is OpenAiTransportConfig -> "openai"
    is GeminiTransportConfig -> "gemini"
    is AnthropicTransportConfig -> "anthropic"
}

/** Maps the Provider deadline contract onto Ktor's transport-level timeout controls. */
fun ProviderTimeoutConfig.toHttpTimeoutConfig(): HttpTimeoutConfig = HttpTimeoutConfig(
    requestTimeoutMillis = callTimeoutMillis,
    connectTimeoutMillis = connectTimeoutMillis,
    // Runtime enforces the shorter canonical-event idle deadline. The socket must also permit the
    // configured first-event window before the first canonical event exists.
    socketTimeoutMillis = maxOf(firstEventTimeoutMillis, streamIdleTimeoutMillis),
)

@Serializable
sealed interface ProviderTransportConfig

/** Wire protocols supported by the OpenAI protocol family. */
@Serializable
enum class OpenAiWireProtocol {
    @SerialName("responses")
    RESPONSES,

    @SerialName("chat-completions")
    CHAT_COMPLETIONS,
}

/** Credential placement supported by the OpenAI provider family. */
@Serializable
enum class OpenAiAuthentication {
    @SerialName("bearer")
    BEARER,

    @SerialName("api-key")
    API_KEY,
}

/** Strongly typed server-side Tools supported by OpenAI Responses-compatible transports. */
@Serializable
sealed interface OpenAiResponsesHostedTool

/** xAI X Search extension carried by the OpenAI Responses wire protocol. */
@Serializable
@SerialName("x-search")
data class OpenAiXSearchToolConfig(
    val allowedHandles: List<String> = emptyList(),
    val excludedHandles: List<String> = emptyList(),
    val fromDate: String? = null,
    val toDate: String? = null,
    val enableImageUnderstanding: Boolean = false,
    val enableVideoUnderstanding: Boolean = false,
) : OpenAiResponsesHostedTool {
    init {
        require(allowedHandles.isEmpty() || excludedHandles.isEmpty()) {
            "OpenAI Responses X Search allowed and excluded handles are mutually exclusive"
        }
        validateXHandles(allowedHandles)
        validateXHandles(excludedHandles)
        require(fromDate == null || fromDate.isValidIsoDate()) {
            "OpenAI Responses X Search fromDate must use YYYY-MM-DD"
        }
        require(toDate == null || toDate.isValidIsoDate()) {
            "OpenAI Responses X Search toDate must use YYYY-MM-DD"
        }
        require(fromDate == null || toDate == null || fromDate <= toDate) {
            "OpenAI Responses X Search date range must be ordered"
        }
    }
}

/** OpenAI-family request and transport settings. */
@Serializable
@SerialName("openai")
data class OpenAiTransportConfig(
    /** Per-request protocol selection; `null` uses the Provider profile default. */
    val protocol: OpenAiWireProtocol? = null,
    val authentication: OpenAiAuthentication = OpenAiAuthentication.BEARER,
    val instructions: String? = null,
    val reasoningEffort: String? = null,
    val reasoningSummary: String? = null,
    val serviceTier: String? = null,
    val promptCacheKey: String? = null,
    val promptCacheRetention: String? = null,
    val hostedTools: List<OpenAiResponsesHostedTool> = emptyList(),
    val maxToolTurns: Int? = null,
) : ProviderTransportConfig {
    init {
        require(protocol != OpenAiWireProtocol.CHAT_COMPLETIONS || (hostedTools.isEmpty() && maxToolTurns == null)) {
            "OpenAI hosted Tools are supported only by the Responses API"
        }
        require(hostedTools.size <= MAX_OPENAI_HOSTED_TOOLS) {
            "OpenAI Responses hosted Tool count exceeds $MAX_OPENAI_HOSTED_TOOLS"
        }
        require(hostedTools.filterIsInstance<OpenAiXSearchToolConfig>().size <= 1) {
            "OpenAI Responses accepts at most one X Search Tool configuration"
        }
        require(maxToolTurns == null || maxToolTurns in 1..MAX_OPENAI_TOOL_TURNS) {
            "OpenAI Responses maxToolTurns must be between 1 and $MAX_OPENAI_TOOL_TURNS"
        }
    }
}

@Serializable
@SerialName("gemini")
data class GeminiTransportConfig(
    val thinkingLevel: String? = null,
    val thinkingSummaries: String? = null,
) : ProviderTransportConfig

/** Credential placement supported by the Anthropic Messages adapter. */
@Serializable
enum class AnthropicAuthentication {
    @SerialName("x-api-key")
    X_API_KEY,

    @SerialName("bearer")
    BEARER,
}

/** Anthropic Messages request and transport settings. */
@Serializable
@SerialName("anthropic")
data class AnthropicTransportConfig(
    val authentication: AnthropicAuthentication = AnthropicAuthentication.X_API_KEY,
    val thinkingMode: String? = null,
    val thinkingBudgetTokens: Int? = null,
    val thinkingDisplay: String? = null,
    val effort: String? = null,
) : ProviderTransportConfig

@Serializable
data class ProviderChunk(
    val events: List<ProviderEvent> = emptyList(),
)

fun ProviderChunk.validateSemantics() {
    require(events.isNotEmpty()) { "ProviderChunk must contain canonical events" }
    val completedIndexes = events.mapIndexedNotNull { index, event -> index.takeIf { event is ProviderEvent.Completed } }
    require(completedIndexes.size <= 1) { "ProviderChunk cannot contain multiple Completed events" }
    require(completedIndexes.isEmpty() || completedIndexes.single() == events.lastIndex) {
        "ProviderEvent.Completed must be the final event in a chunk"
    }
}

fun ToolCallPart.lifecycle(): ToolCallLifecycle = if (partial) ToolCallLifecycle.DELTA else ToolCallLifecycle.FINALIZED

/** Canonical Provider metadata key for normalized source citations. */
const val PROVIDER_CITATIONS_METADATA_KEY: String = "citations"

/** Canonical streaming lifecycle emitted by every Provider adapter. */
@Serializable
sealed interface ProviderEvent {
    @Serializable
    @SerialName("text_start")
    data class TextStart(val signature: String? = null) : ProviderEvent

    @Serializable
    @SerialName("text_delta")
    data class TextDelta(val delta: String, val signature: String? = null) : ProviderEvent

    @Serializable
    @SerialName("text_end")
    data class TextEnd(val text: String? = null, val signature: String? = null) : ProviderEvent

    @Serializable
    @SerialName("reasoning_start")
    data class ReasoningStart(
        val signature: String? = null,
        val redacted: Boolean = false,
        val kind: ReasoningContentKind = ReasoningContentKind.PROVIDER_DEFINED,
    ) : ProviderEvent

    @Serializable
    @SerialName("reasoning_delta")
    data class ReasoningDelta(val delta: String, val signature: String? = null) : ProviderEvent

    @Serializable
    @SerialName("reasoning_end")
    data class ReasoningEnd(val text: String? = null, val signature: String? = null, val redacted: Boolean = false) : ProviderEvent

    @Serializable
    @SerialName("tool_call_start")
    data class ToolCallStart(val toolCall: ToolCallPart) : ProviderEvent

    @Serializable
    @SerialName("tool_call_delta")
    data class ToolCallDelta(val toolCallId: String, val delta: String) : ProviderEvent

    @Serializable
    @SerialName("tool_call_end")
    data class ToolCallEnd(val toolCall: ToolCallPart) : ProviderEvent

    @Serializable
    @SerialName("usage_delta")
    data class UsageDelta(val usage: ProviderUsage) : ProviderEvent

    @Serializable
    @SerialName("completed")
    data class Completed(
        val finishReason: String? = null,
        val stopReason: StopReason? = null,
        val usage: ProviderUsage? = null,
        val providerMetadata: JsonObject? = null,
    ) : ProviderEvent
}

@Serializable
data class ProviderUsage(
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
    val reasoningTokens: Int? = null,
)

open class ProviderException(message: String, cause: Throwable? = null) : RuntimeException(message, cause) {
    /** Whether another physical Provider invocation may succeed without changing the request. */
    open val retryable: Boolean
        get() = false
}

open class ProviderHttpException(
    message: String,
    cause: Throwable? = null,
    val statusCode: Int,
    val retryAfterMillis: Long? = null,
) : ProviderException(message, cause)

class ProviderRateLimitException(
    message: String,
    cause: Throwable? = null,
    statusCode: Int = 429,
    retryAfterMillis: Long? = null,
) : ProviderHttpException(message, cause, statusCode, retryAfterMillis) {
    override val retryable: Boolean
        get() = true
}

class ProviderAuthException(
    message: String,
    cause: Throwable? = null,
    statusCode: Int = 401,
    retryAfterMillis: Long? = null,
) : ProviderHttpException(message, cause, statusCode, retryAfterMillis)

class ProviderClientException(
    message: String,
    cause: Throwable? = null,
    statusCode: Int,
    retryAfterMillis: Long? = null,
) : ProviderHttpException(message, cause, statusCode, retryAfterMillis)

/**
 * The Provider rejected the request because the submitted input exceeded its context budget.
 *
 * This is intentionally distinct from a generic client error: Runtime may recover once by
 * rebuilding a smaller semantic context projection.
 */
class ProviderContextLimitException(
    message: String = "Provider context limit exceeded",
    cause: Throwable? = null,
    statusCode: Int = 400,
) : ProviderHttpException(message, cause, statusCode)

class ProviderServerException(
    message: String,
    cause: Throwable? = null,
    statusCode: Int,
    retryAfterMillis: Long? = null,
) : ProviderHttpException(message, cause, statusCode, retryAfterMillis) {
    override val retryable: Boolean
        get() = true
}

open class ProviderNetworkException(message: String, cause: Throwable? = null) : ProviderException(message, cause) {
    override val retryable: Boolean
        get() = true
}

/**
 * A remote terminal invalidated one physical invocation while leaving the logical request valid.
 * Runtime may retry only when [retryable] is true, and any retry must use a new physical identity.
 */
open class ProviderInvocationInvalidatedException(
    val failure: ProviderException,
    override val retryable: Boolean = failure.retryable,
) : ProviderException(failure.message ?: "Provider invocation was invalidated", failure)

/** The transport ended cleanly before the Provider emitted its semantic terminal event. */
class ProviderStreamInterruptedException(
    cause: Throwable? = null,
) : ProviderNetworkException("Provider stream ended before semantic completion", cause)

enum class ProviderTimeoutPhase {
    CONNECT,
    FIRST_EVENT,
    STREAM_IDLE,
    PROVIDER_CALL,
}

class ProviderTimeoutException(
    val phase: ProviderTimeoutPhase,
    cause: Throwable? = null,
) : ProviderNetworkException("Provider timeout (${phase.name.lowercase()})", cause)

class ProviderProtocolException(message: String, cause: Throwable? = null) : ProviderException(message, cause)

interface ProviderRegistry {
    fun get(key: String): ProviderAdapter?
    fun all(): List<ProviderAdapter>
}

class InMemoryProviderRegistry(
    adapters: List<ProviderAdapter> = emptyList(),
) : ProviderRegistry {
    private val map = adapters.associateBy { it.key }

    init {
        require(map.size == adapters.size) { "Provider registry contains duplicate provider keys" }
    }

    override fun get(key: String): ProviderAdapter? = map[key]
    override fun all(): List<ProviderAdapter> = map.values.toList()
}

class ProviderEventAssembler(
    private val messageFactory: AgentModelFactory = AgentModelFactory(),
) {
    fun apply(previous: AgentMessage?, events: List<ProviderEvent>): AgentMessage? {
        if (events.isEmpty()) return previous
        var current = previous ?: messageFactory.createMessage(role = MessageRole.ASSISTANT, parts = emptyList())
        events.forEach { event ->
            current = when (event) {
                is ProviderEvent.TextStart -> current.startText(event.signature)
                is ProviderEvent.TextDelta -> current.appendText(event.delta, event.signature)
                is ProviderEvent.TextEnd -> current.endText(event.text, event.signature)
                is ProviderEvent.ReasoningStart -> current.startReasoning(event.signature, event.redacted, event.kind)
                is ProviderEvent.ReasoningDelta -> current.appendReasoning(event.delta, event.signature)
                is ProviderEvent.ReasoningEnd -> current.endReasoning(event.text, event.signature, event.redacted)
                is ProviderEvent.ToolCallStart -> current.upsertToolCall(event.toolCall.copy(partial = true), replaceArguments = false)
                is ProviderEvent.ToolCallDelta -> current.upsertToolCall(
                    ToolCallPart(
                        toolCallId = event.toolCallId,
                        toolName = "",
                        arguments = buildJsonObject { put("partial_json", JsonPrimitive(event.delta)) },
                        partial = true,
                    ),
                    replaceArguments = false,
                )
                is ProviderEvent.ToolCallEnd -> current.upsertToolCall(event.toolCall.copy(partial = false), replaceArguments = true)
                is ProviderEvent.UsageDelta -> current
                is ProviderEvent.Completed -> current.copy(
                    metadata = event.providerMetadata?.let { JsonObject(current.metadata + it) } ?: current.metadata,
                    stopReason = event.stopReason ?: normalizeProviderStopReason(event.finishReason),
                )
            }
        }
        return current
    }
}

private fun AgentMessage.startText(signature: String?): AgentMessage {
    if (signature == null) return this
    val last = parts.lastOrNull() as? TextPart
    return if (last != null && last.phase != MessageBlockPhase.FINAL) {
        copy(parts = parts.dropLast(1) + last.copy(signature = signature))
    } else {
        copy(parts = parts + TextPart(text = "", signature = signature, phase = MessageBlockPhase.COMMENTARY))
    }
}

private fun AgentMessage.appendText(delta: String, signature: String?): AgentMessage {
    if (delta.isEmpty() && signature == null) return this
    val last = parts.lastOrNull() as? TextPart
    return if (last != null && last.phase != MessageBlockPhase.FINAL) {
        copy(
            parts = parts.dropLast(1) + last.copy(
                text = last.text + delta,
                signature = signature ?: last.signature,
                phase = MessageBlockPhase.COMMENTARY,
            ),
        )
    } else {
        copy(parts = parts + TextPart(text = delta, signature = signature, phase = MessageBlockPhase.COMMENTARY))
    }
}

private fun AgentMessage.endText(text: String?, signature: String?): AgentMessage {
    val index = parts.indexOfLast { it is TextPart }
    if (index < 0) {
        return text?.let { copy(parts = parts + TextPart(it, signature, MessageBlockPhase.FINAL)) } ?: this
    }
    val previous = parts[index] as TextPart
    return replacePart(
        index,
        previous.copy(
            text = text ?: previous.text,
            signature = signature ?: previous.signature,
            phase = MessageBlockPhase.FINAL,
        ),
    )
}

private fun AgentMessage.startReasoning(
    signature: String?,
    redacted: Boolean,
    kind: ReasoningContentKind,
): AgentMessage {
    val last = parts.lastOrNull() as? ReasoningPart
    return if (last != null && last.phase != MessageBlockPhase.FINAL) {
        copy(
            parts = parts.dropLast(1) + last.copy(
                signature = signature ?: last.signature,
                redacted = redacted || last.redacted,
                kind = kind.takeUnless { it == ReasoningContentKind.PROVIDER_DEFINED } ?: last.kind,
                phase = MessageBlockPhase.COMMENTARY,
            ),
        )
    } else {
        copy(
            parts = parts + ReasoningPart(
                text = "",
                signature = signature,
                redacted = redacted,
                kind = kind,
                phase = MessageBlockPhase.COMMENTARY,
            ),
        )
    }
}

private fun AgentMessage.appendReasoning(delta: String, signature: String?): AgentMessage {
    if (delta.isEmpty() && signature == null) return this
    val last = parts.lastOrNull() as? ReasoningPart
    return if (last != null && last.phase != MessageBlockPhase.FINAL) {
        copy(
            parts = parts.dropLast(1) + last.copy(
                text = last.text + delta,
                signature = signature ?: last.signature,
                phase = MessageBlockPhase.COMMENTARY,
            ),
        )
    } else {
        copy(
            parts = parts + ReasoningPart(
                text = delta,
                signature = signature,
                redacted = false,
                phase = MessageBlockPhase.COMMENTARY,
            ),
        )
    }
}

private fun AgentMessage.endReasoning(text: String?, signature: String?, redacted: Boolean): AgentMessage {
    val index = parts.indexOfLast { it is ReasoningPart && it.phase != MessageBlockPhase.FINAL }
    if (index < 0) {
        if (text == null && signature == null && !redacted) return this
        return copy(
            parts = parts + ReasoningPart(
                text = text.orEmpty(),
                signature = signature,
                redacted = redacted,
                phase = MessageBlockPhase.FINAL,
            ),
        )
    }
    val previous = parts[index] as ReasoningPart
    return replacePart(
        index,
        previous.copy(
            text = text ?: previous.text,
            signature = signature ?: previous.signature,
            redacted = redacted || previous.redacted,
            phase = MessageBlockPhase.FINAL,
        ),
    )
}

private fun AgentMessage.upsertToolCall(toolCall: ToolCallPart, replaceArguments: Boolean): AgentMessage {
    val index = parts.indexOfLast { it is ToolCallPart && it.toolCallId == toolCall.toolCallId }
    if (index < 0) return copy(parts = parts + toolCall)
    val previous = parts[index] as ToolCallPart
    val updated = previous.copy(
        toolName = toolCall.toolName.ifBlank { previous.toolName },
        arguments = if (replaceArguments) toolCall.arguments else mergeToolArguments(previous.arguments, toolCall.arguments),
        partial = toolCall.partial,
        thoughtSignature = toolCall.thoughtSignature ?: previous.thoughtSignature,
        providerCallId = toolCall.providerCallId ?: previous.providerCallId,
        providerMetadata = toolCall.providerMetadata ?: previous.providerMetadata,
    )
    return replacePart(index, updated)
}

private fun AgentMessage.replacePart(index: Int, part: MessagePart): AgentMessage {
    return copy(parts = parts.toMutableList().apply { this[index] = part })
}

private fun normalizeProviderStopReason(finishReason: String?): StopReason = when (finishReason?.uppercase()) {
    "MAX_TOKENS", "MAX_OUTPUT_TOKENS", "LENGTH" -> StopReason.MAX_TOKENS
    "TOOL_CALLS", "TOOL_USE" -> StopReason.TOOL_CALLS
    "CANCELLED", "CANCELED" -> StopReason.CANCELLED
    "ERROR", "FAILED", "INCOMPLETE" -> StopReason.ERROR
    else -> StopReason.COMPLETED
}

class ToolCallAssembler {
    fun merge(toolCalls: List<ToolCallPart>): List<ToolCallPart> {
        return toolCalls.groupBy { it.toolCallId }.values.map { parts ->
            parts.reduce { acc, item ->
                acc.copy(
                    toolName = if (item.toolName.isNotBlank()) item.toolName else acc.toolName,
                    arguments = mergeToolArguments(acc.arguments, item.arguments),
                    partial = item.partial,
                    thoughtSignature = item.thoughtSignature ?: acc.thoughtSignature,
                    providerCallId = item.providerCallId ?: acc.providerCallId,
                    providerMetadata = item.providerMetadata ?: acc.providerMetadata,
                )
            }
        }
    }
}

class DefaultReplayPolicy(
    private val normalizeToolCallId: ((String, ModelDescriptor, AgentMessage) -> String)? = null,
) : ReplayPolicy {
    override suspend fun transform(messages: List<AgentMessage>, model: ModelDescriptor): List<AgentMessage> {
        val toolCallIdMap = linkedMapOf<String, String>()
        val transformed = messages.mapNotNull { message ->
            when (message.role) {
                MessageRole.USER, MessageRole.SYSTEM, MessageRole.TOOL -> transformToolResultMessage(message, toolCallIdMap)
                MessageRole.ASSISTANT -> transformAssistantMessage(message, model, toolCallIdMap)
            }
        }

        val result = mutableListOf<AgentMessage>()
        var pendingToolCalls = emptyList<ToolCallPart>()
        val existingToolResultIds = linkedSetOf<String>()

        transformed.forEach { message ->
            when (message.role) {
                MessageRole.ASSISTANT -> {
                    flushOrphanedToolCalls(result, pendingToolCalls, existingToolResultIds)
                    pendingToolCalls = emptyList()
                    existingToolResultIds.clear()
                    if (message.stopReason == StopReason.ERROR || message.stopReason == StopReason.CANCELLED) return@forEach
                    pendingToolCalls = message.parts.filterIsInstance<ToolCallPart>()
                    result += message
                }
                MessageRole.TOOL -> {
                    message.parts.filterIsInstance<ToolResultPart>().forEach { existingToolResultIds += it.toolCallId }
                    result += message
                }
                MessageRole.USER, MessageRole.SYSTEM -> {
                    flushOrphanedToolCalls(result, pendingToolCalls, existingToolResultIds)
                    pendingToolCalls = emptyList()
                    existingToolResultIds.clear()
                    result += message
                }
            }
        }

        return result
    }

    private fun transformAssistantMessage(
        message: AgentMessage,
        model: ModelDescriptor,
        toolCallIdMap: MutableMap<String, String>,
    ): AgentMessage {
        val isSameModel = isSameModelReplay(message, model)
        val replayContext = ReplayContext.forModel(model)
        val transformedParts = message.parts.flatMap { part ->
            when (part) {
                is ReasoningPart -> transformReasoningPart(part, isSameModel, replayContext)
                is TextPart -> listOf(part)
                is ToolCallPart -> listOf(transformToolCallPart(part, isSameModel, replayContext, model, message, toolCallIdMap))
                else -> listOf(part)
            }
        }
        return message.copy(parts = transformedParts)
    }

    private fun isSameModelReplay(message: AgentMessage, model: ModelDescriptor): Boolean {
        val metadataProvider = message.metadata["provider"]?.jsonPrimitive?.contentOrNull
        val metadataModel = message.metadata["model"]?.jsonPrimitive?.contentOrNull
        if (metadataProvider != null || metadataModel != null) {
            return metadataProvider == model.provider && metadataModel == model.model
        }
        val providerNames = linkedSetOf<String>()
        val modelNames = linkedSetOf<String>()
        collectProviderHints(message.parts).forEach { hint ->
            hint.provider?.let { providerNames += it }
            hint.model?.let { modelNames += it }
        }
        return providerNames.size == 1 && modelNames.size == 1 && providerNames.single() == model.provider && modelNames.single() == model.model
    }

    private fun collectProviderHints(parts: List<MessagePart>): List<ReplayProviderHint> {
        return parts.mapNotNull { part ->
            part.providerMetadata?.let { metadata ->
                ReplayProviderHint(
                    provider = metadata["provider"]?.jsonPrimitive?.contentOrNull,
                    model = metadata["model"]?.jsonPrimitive?.contentOrNull,
                )
            }
        }
    }

    private fun transformReasoningPart(part: ReasoningPart, isSameModel: Boolean, replayContext: ReplayContext): List<MessagePart> {
        if (part.redacted) return if (isSameModel && replayContext.keepRedactedReasoning) listOf(part) else emptyList()
        if (isSameModel && part.signature != null) return listOf(part)
        if (part.text.isBlank()) return emptyList()
        return if (isSameModel) listOf(part) else emptyList()
    }

    private fun transformToolCallPart(
        part: ToolCallPart,
        isSameModel: Boolean,
        replayContext: ReplayContext,
        model: ModelDescriptor,
        source: AgentMessage,
        toolCallIdMap: MutableMap<String, String>,
    ): ToolCallPart {
        var transformed = if ((!isSameModel || !replayContext.keepThoughtSignatures) && part.thoughtSignature != null) part.copy(thoughtSignature = null) else part
        if (!isSameModel) {
            val normalizedId = normalizeToolCallId?.invoke(part.toolCallId, model, source)
                ?: replayContext.defaultToolCallNormalizer?.invoke(part.toolCallId)
                ?: part.toolCallId
            if (normalizedId != part.toolCallId) {
                toolCallIdMap[part.toolCallId] = normalizedId
                transformed = transformed.copy(toolCallId = normalizedId)
            }
        }
        return transformed
    }

    private fun transformToolResultMessage(
        message: AgentMessage,
        toolCallIdMap: Map<String, String>,
    ): AgentMessage {
        if (message.role != MessageRole.TOOL) return message
        val transformedParts = message.parts.map { part ->
            if (part is ToolResultPart) {
                val normalizedId = toolCallIdMap[part.toolCallId]
                if (normalizedId != null && normalizedId != part.toolCallId) part.copy(toolCallId = normalizedId) else part
            } else {
                part
            }
        }
        return message.copy(parts = transformedParts)
    }

    private fun flushOrphanedToolCalls(
        result: MutableList<AgentMessage>,
        pendingToolCalls: List<ToolCallPart>,
        existingToolResultIds: Set<String>,
    ) {
        pendingToolCalls
            .filterNot { existingToolResultIds.contains(it.toolCallId) }
            .mapTo(result) { orphanedToolResult(it) }
    }

    private fun orphanedToolResult(toolCall: ToolCallPart): AgentMessage {
        return AgentMessage(
            role = MessageRole.TOOL,
            parts = listOf(
                ToolResultPart(
                    toolCallId = toolCall.toolCallId,
                    toolName = toolCall.toolName,
                    result = JsonPrimitive("No result provided"),
                    isError = true,
                    displayText = "No result provided",
                ),
            ),
            stopReason = StopReason.TOOL_CALLS,
        )
    }
}

private data class ReplayProviderHint(
    val provider: String?,
    val model: String?,
)

private data class ReplayContext(
    val keepThoughtSignatures: Boolean,
    val keepRedactedReasoning: Boolean,
    val defaultToolCallNormalizer: ((String) -> String)? = null,
) {
    companion object {
        fun forModel(model: ModelDescriptor): ReplayContext {
            val capabilities = providerCapabilities(model)
            return ReplayContext(
                keepThoughtSignatures = capabilities.keepThoughtSignatures,
                keepRedactedReasoning = capabilities.keepRedactedReasoning,
                defaultToolCallNormalizer = capabilities.defaultToolCallNormalizer,
            )
        }
    }
}

private data class ProviderReplayCapabilities(
    val supportsReasoningReplay: Boolean,
    val keepThoughtSignatures: Boolean,
    val keepRedactedReasoning: Boolean,
    val supportsStreamingToolCalls: Boolean,
    val defaultToolCallNormalizer: ((String) -> String)? = null,
)

private fun providerCapabilities(model: ModelDescriptor): ProviderReplayCapabilities = when (model.provider.lowercase()) {
    "gemini" -> ProviderReplayCapabilities(
        supportsReasoningReplay = true,
        keepThoughtSignatures = true,
        keepRedactedReasoning = false,
        supportsStreamingToolCalls = true,
    )
    "anthropic" -> ProviderReplayCapabilities(
        supportsReasoningReplay = true,
        keepThoughtSignatures = true,
        keepRedactedReasoning = true,
        supportsStreamingToolCalls = true,
        defaultToolCallNormalizer = ::normalizeAnthropicToolCallId,
    )
    else -> ProviderReplayCapabilities(
        supportsReasoningReplay = true,
        keepThoughtSignatures = true,
        keepRedactedReasoning = true,
        supportsStreamingToolCalls = true,
    )
}

private fun normalizeAnthropicToolCallId(id: String): String {
    val sanitized = id.map { ch -> if (ch.isLetterOrDigit() || ch == '_' || ch == '-') ch else '-' }.joinToString(separator = "")
    return sanitized.take(64).ifBlank { "tool_call" }
}

private fun mergeToolArguments(previous: JsonElement, delta: JsonElement): JsonElement {
    val previousPartial = extractPartialJson(previous)
    val deltaPartial = extractPartialJson(delta)
    if (previousPartial != null || deltaPartial != null) {
        val mergedPartial = (previousPartial ?: jsonStringifyObject(previous)) + (deltaPartial ?: jsonStringifyObject(delta))
        return parsePartialJsonObject(mergedPartial)
    }
    val previousObject = previous as? JsonObject
    val deltaObject = delta as? JsonObject
    return if (previousObject != null && deltaObject != null) JsonObject(previousObject + deltaObject) else delta
}

private fun extractPartialJson(value: JsonElement): String? = (value as? JsonObject)?.get("partial_json")?.jsonPrimitive?.contentOrNull

private fun parsePartialJsonObject(value: String): JsonElement {
    val trimmed = value.trim()
    return try {
        toolArgumentJson.parseToJsonElement(trimmed)
    } catch (_: Throwable) {
        buildJsonObject { put("partial_json", JsonPrimitive(trimmed)) }
    }
}

private fun jsonStringifyObject(value: JsonElement): String = when (value) {
    is JsonObject -> toolArgumentJson.encodeToString(JsonElement.serializer(), value)
    else -> ""
}

private val toolArgumentJson = Json

fun compileProviderTransportConfig(
    config: saien.magrathea.core.ProviderConfig,
    expectedFamily: String?,
): ProviderTransportConfig? = when (val options = config.options) {
    null -> null
    else -> {
        require(expectedFamily != null) {
            "Provider options family ${options.family} cannot be used because the adapter declares no options family"
        }
        require(options.family == expectedFamily) {
            "Provider options family ${options.family} cannot be used with adapter family $expectedFamily"
        }
        when (options.family) {
            "openai" -> {
                providerOptionsJson.decodeFromJsonElement(OpenAiTransportConfig.serializer(), options.values)
            }
            "gemini" -> {
                providerOptionsJson.decodeFromJsonElement(GeminiTransportConfig.serializer(), options.values)
            }
            "anthropic" -> {
                providerOptionsJson.decodeFromJsonElement(AnthropicTransportConfig.serializer(), options.values)
            }
            else -> throw IllegalArgumentException("Unknown provider options family ${options.family}")
        }
    }
}

fun ProviderTransportConfig.toProviderOptions(): ProviderOptions = when (this) {
    is OpenAiTransportConfig -> ProviderOptions(
        family = "openai",
        values = providerOptionsJson.encodeToJsonElement(OpenAiTransportConfig.serializer(), this).jsonObject,
    )
    is GeminiTransportConfig -> ProviderOptions(
        family = "gemini",
        values = providerOptionsJson.encodeToJsonElement(GeminiTransportConfig.serializer(), this).jsonObject,
    )
    is AnthropicTransportConfig -> ProviderOptions(
        family = "anthropic",
        values = providerOptionsJson.encodeToJsonElement(AnthropicTransportConfig.serializer(), this).jsonObject,
    )
}

private val providerOptionsJson = Json {
    encodeDefaults = false
    ignoreUnknownKeys = false
}

private fun validateXHandles(handles: List<String>) {
    require(handles.size <= MAX_X_SEARCH_HANDLES) {
        "OpenAI Responses X Search accepts at most $MAX_X_SEARCH_HANDLES handles"
    }
    require(handles.distinct().size == handles.size && handles.all(X_HANDLE_PATTERN::matches)) {
        "OpenAI Responses X Search handles must be unique names without an @ prefix"
    }
}

private fun String.isValidIsoDate(): Boolean {
    if (!ISO_DATE_PATTERN.matches(this)) return false
    val year = substring(0, 4).toInt()
    val month = substring(5, 7).toInt()
    val day = substring(8, 10).toInt()
    if (month !in 1..12) return false
    val days = when (month) {
        2 -> if (year % 400 == 0 || (year % 4 == 0 && year % 100 != 0)) 29 else 28
        4, 6, 9, 11 -> 30
        else -> 31
    }
    return day in 1..days
}

private val X_HANDLE_PATTERN = Regex("[A-Za-z0-9_]{1,64}")
private val ISO_DATE_PATTERN = Regex("\\d{4}-\\d{2}-\\d{2}")
private const val MAX_X_SEARCH_HANDLES = 20
private const val MAX_OPENAI_HOSTED_TOOLS = 16
private const val MAX_OPENAI_TOOL_TURNS = 100
