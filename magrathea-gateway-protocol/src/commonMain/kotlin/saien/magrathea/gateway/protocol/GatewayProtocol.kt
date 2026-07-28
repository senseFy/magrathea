@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package saien.magrathea.gateway.protocol

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import saien.magrathea.core.AgentMessage
import saien.magrathea.core.AttachmentPart
import saien.magrathea.core.JsonPart
import saien.magrathea.core.MessagePart
import saien.magrathea.core.ReasoningPart
import saien.magrathea.core.StopReason
import saien.magrathea.core.TextPart
import saien.magrathea.core.ToolCallPart
import saien.magrathea.core.ToolDefinition
import saien.magrathea.core.ToolResultPart
import saien.magrathea.provider.api.ProviderEvent
import saien.magrathea.provider.api.ProviderUsage

const val GATEWAY_PROTOCOL_VERSION: Int = 1
const val GATEWAY_SSE_EVENT: String = "magrathea.gateway.v1"
const val GATEWAY_ATTACHMENT_URI_PREFIX: String = "magrathea-attachment:"
const val GATEWAY_VERSION_HEADER: String = "X-Magrathea-Gateway-Version"
const val GATEWAY_IDEMPOTENCY_HEADER: String = "Idempotency-Key"
const val GATEWAY_CSRF_HEADER: String = "X-CSRF-Token"

data class GatewayProtocolLimits(
    val maxIdChars: Int = 128,
    val maxRequestIdChars: Int = 160,
    val maxModelChars: Int = 256,
    val maxMessages: Int = 256,
    val maxTools: Int = 128,
    val maxAttachments: Int = 32,
    val maxAttachmentBytes: Long = 25L * 1024 * 1024,
    val maxRequestTextChars: Int = 4 * 1024 * 1024,
    val maxJsonChars: Int = 2 * 1024 * 1024,
    val maxDescriptionChars: Int = 16 * 1024,
    val maxSignatureChars: Int = 64 * 1024,
    val maxFailureMessageChars: Int = 1_024,
) {
    init {
        require(maxIdChars > 0)
        require(maxRequestIdChars >= maxIdChars)
        require(maxModelChars > 0)
        require(maxMessages > 0)
        require(maxTools > 0)
        require(maxAttachments > 0)
        require(maxAttachmentBytes > 0)
        require(maxRequestTextChars > 0)
        require(maxJsonChars > 0)
        require(maxDescriptionChars > 0)
        require(maxSignatureChars > 0)
        require(maxFailureMessageChars > 0)
    }
}

@Serializable
data class GatewayGenerationOptions(
    val temperature: Double? = null,
    val maxTokens: Int? = null,
) {
    fun validate() {
        protocolCheck(temperature == null || temperature.isFinite() && temperature in 0.0..2.0) {
            "temperature must be finite and between 0 and 2"
        }
        protocolCheck(maxTokens == null || maxTokens > 0) { "maxTokens must be positive" }
    }
}

@Serializable
data class GatewayAttachmentReference(
    val id: String,
    val mediaType: String,
    val sizeBytes: Long,
    val sha256: String? = null,
)

@Serializable
data class GatewayModelReference(
    val provider: String,
    val model: String,
) {
    fun validate(limits: GatewayProtocolLimits = GatewayProtocolLimits()) {
        protocolCheck(
            provider.length in 1..limits.maxIdChars && provider.all { it.isAsciiRegistryKeyCharacter() },
        ) { "model.provider is invalid" }
        protocolCheck(model.length in 1..limits.maxModelChars && model.all(Char::isGatewayModelCharacter)) {
            "model.model is invalid"
        }
    }
}

@Serializable
data class GatewayCreateStreamRequest(
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val protocolVersion: Int = GATEWAY_PROTOCOL_VERSION,
    val requestId: String,
    val sessionId: String,
    val turn: Int,
    val model: GatewayModelReference,
    val messages: List<AgentMessage>,
    val tools: List<ToolDefinition> = emptyList(),
    val options: GatewayGenerationOptions = GatewayGenerationOptions(),
    val attachments: List<GatewayAttachmentReference> = emptyList(),
) {
    fun validate(limits: GatewayProtocolLimits = GatewayProtocolLimits()) {
        validateVersion(protocolVersion)
        validateIdentifier("requestId", requestId, limits.maxRequestIdChars)
        validateIdentifier("sessionId", sessionId, limits.maxIdChars)
        protocolCheck(turn >= 0) { "turn must not be negative" }
        model.validate(limits)
        protocolCheck(messages.size <= limits.maxMessages) { "message count exceeds configured limit" }
        protocolCheck(tools.size <= limits.maxTools) { "tool count exceeds configured limit" }
        protocolCheck(attachments.size <= limits.maxAttachments) { "attachment count exceeds configured limit" }
        protocolCheck(tools.map { it.name }.distinct().size == tools.size) { "tool names must be unique" }
        options.validate()

        val attachmentIds = attachments.map { reference ->
            reference.validate(limits)
            reference.id
        }
        protocolCheck(attachmentIds.distinct().size == attachmentIds.size) { "attachment IDs must be unique" }

        var textChars = 0L
        val referencedAttachmentIds = mutableListOf<String>()
        protocolCheck(messages.map { it.id }.distinct().size == messages.size) { "message IDs must be unique" }
        val attachmentMediaTypes = attachments.associate { it.id to it.mediaType }
        messages.forEach { message ->
            validateIdentifier("message.id", message.id, limits.maxIdChars)
            protocolCheck(message.createdAtEpochMs >= 0) { "message.createdAtEpochMs must not be negative" }
            validateSafeMetadata(message.metadata, limits, "message.metadata")
            message.parts.forEach { part ->
                textChars += part.textWeight()
                part.providerMetadata?.let { validateSafeMetadata(it, limits, "part.providerMetadata") }
                part.validateWireFields(limits)
                if (part is ToolResultPart) validateSafeMetadata(part.metadata, limits, "toolResult.metadata")
                if (part is AttachmentPart) {
                    protocolCheck(part.uri.startsWith(GATEWAY_ATTACHMENT_URI_PREFIX)) {
                        "Gateway attachment URI must use $GATEWAY_ATTACHMENT_URI_PREFIX"
                    }
                    val attachmentId = part.uri.removePrefix(GATEWAY_ATTACHMENT_URI_PREFIX)
                    referencedAttachmentIds += attachmentId
                    protocolCheck(attachmentMediaTypes[attachmentId] == part.mimeType) {
                        "message attachment mediaType does not match its descriptor"
                    }
                }
            }
        }
        protocolCheck(textChars <= limits.maxRequestTextChars) { "request text exceeds configured limit" }
        protocolCheck(referencedAttachmentIds.distinct().size == referencedAttachmentIds.size) {
            "attachment reference may appear only once"
        }
        protocolCheck(referencedAttachmentIds.toSet() == attachmentIds.toSet()) {
            "message attachment references and attachment descriptors must match exactly"
        }
        tools.forEach { tool ->
            validateIdentifier("tool.name", tool.name, limits.maxIdChars)
            protocolCheck(tool.description.length <= limits.maxDescriptionChars) {
                "tool description exceeds configured limit"
            }
            protocolCheck(tool.schema.toString().length <= limits.maxJsonChars) {
                "tool schema exceeds configured limit"
            }
            tool.requiresPermission?.let {
                validateIdentifier("tool.requiresPermission", it, limits.maxIdChars)
            }
            tool.timeoutMs?.let { timeoutMs ->
                protocolCheck(timeoutMs > 0) { "tool timeout must be positive" }
            }
            tool.maxCallsPerTurn?.let { maxCalls ->
                protocolCheck(maxCalls in 1..limits.maxTools) { "tool call limit is invalid" }
            }
        }
    }
}

@Serializable
data class GatewayStreamDescriptor(
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val protocolVersion: Int = GATEWAY_PROTOCOL_VERSION,
    val streamId: String,
    val requestId: String,
    val sessionId: String,
    val expiresAtEpochMs: Long,
) {
    fun validate(limits: GatewayProtocolLimits = GatewayProtocolLimits()) {
        validateVersion(protocolVersion)
        validateIdentifier("streamId", streamId, limits.maxIdChars)
        validateIdentifier("requestId", requestId, limits.maxRequestIdChars)
        validateIdentifier("sessionId", sessionId, limits.maxIdChars)
        protocolCheck(expiresAtEpochMs > 0) { "expiresAtEpochMs must be positive" }
    }
}

@Serializable
data class GatewayStreamEnvelope(
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val protocolVersion: Int = GATEWAY_PROTOCOL_VERSION,
    val streamId: String,
    val requestId: String,
    val sessionId: String,
    val sequence: Long,
    val event: GatewayEvent,
)

@Serializable
sealed interface GatewayEvent {
    @Serializable
    @SerialName("stream_opened")
    data class StreamOpened(val replayFromSequence: Long = 0) : GatewayEvent

    @Serializable
    @SerialName("text_start")
    data class TextStart(val signature: String? = null) : GatewayEvent

    @Serializable
    @SerialName("text_delta")
    data class TextDelta(val delta: String, val signature: String? = null) : GatewayEvent

    @Serializable
    @SerialName("text_end")
    data class TextEnd(val text: String? = null, val signature: String? = null) : GatewayEvent

    @Serializable
    @SerialName("reasoning_start")
    data class ReasoningStart(val signature: String? = null, val redacted: Boolean = false) : GatewayEvent

    @Serializable
    @SerialName("reasoning_delta")
    data class ReasoningDelta(val delta: String, val signature: String? = null) : GatewayEvent

    @Serializable
    @SerialName("reasoning_end")
    data class ReasoningEnd(
        val text: String? = null,
        val signature: String? = null,
        val redacted: Boolean = false,
    ) : GatewayEvent

    @Serializable
    @SerialName("tool_call_start")
    data class ToolCallStart(val toolCall: GatewayToolCall) : GatewayEvent

    @Serializable
    @SerialName("tool_call_delta")
    data class ToolCallDelta(val toolCallId: String, val delta: String) : GatewayEvent

    @Serializable
    @SerialName("tool_call_end")
    data class ToolCallEnd(val toolCall: GatewayToolCall) : GatewayEvent

    @Serializable
    @SerialName("usage_delta")
    data class UsageDelta(val usage: GatewayUsage) : GatewayEvent

    @Serializable
    @SerialName("completed")
    data class Completed(
        val finishReason: String? = null,
        val stopReason: StopReason? = null,
        val usage: GatewayUsage? = null,
        val providerMetadata: JsonObject? = null,
    ) : GatewayEvent

    @Serializable
    @SerialName("failed")
    data class Failed(
        val code: GatewayFailureCode,
        val retryable: Boolean = false,
        val retryAfterMillis: Long? = null,
    ) : GatewayEvent

    @Serializable
    @SerialName("cancelled")
    data class Cancelled(val reason: String? = null) : GatewayEvent
}

@Serializable
data class GatewayToolCall(
    val id: String,
    val name: String,
    val arguments: JsonElement,
    val partial: Boolean,
    val thoughtSignature: String? = null,
    val providerCallId: String? = null,
    val providerMetadata: JsonObject? = null,
)

@Serializable
data class GatewayUsage(
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
    val reasoningTokens: Int? = null,
)

@Serializable
enum class GatewayFailureCode {
    UPSTREAM_FAILURE,
    CONTEXT_LIMIT,
    QUOTA_EXCEEDED,
    PROTOCOL_FAILURE,
    REPLAY_WINDOW_EXHAUSTED,
    INTERNAL_FAILURE,
}

@Serializable
data class GatewayProblem(
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val protocolVersion: Int = GATEWAY_PROTOCOL_VERSION,
    val code: String,
    val message: String,
    val requestId: String? = null,
    val retryAfterMillis: Long? = null,
)

class GatewayProtocolException(message: String, cause: Throwable? = null) :
    IllegalArgumentException(message, cause)

class GatewayStreamValidator(
    descriptor: GatewayStreamDescriptor,
    firstExpectedSequence: Long = 0,
    private val requireOpenedEvent: Boolean = firstExpectedSequence == 0L,
    private val limits: GatewayProtocolLimits = GatewayProtocolLimits(),
) {
    private val streamId = descriptor.streamId
    private val requestId = descriptor.requestId
    private val sessionId = descriptor.sessionId
    private var nextSequence = firstExpectedSequence
    private var terminal = false

    init {
        descriptor.validate(limits)
        protocolCheck(firstExpectedSequence >= 0) { "firstExpectedSequence must not be negative" }
    }

    val isTerminal: Boolean
        get() = terminal

    val expectedSequence: Long
        get() = nextSequence

    fun accept(envelope: GatewayStreamEnvelope): GatewayEvent {
        validateVersion(envelope.protocolVersion)
        protocolCheck(envelope.streamId == streamId) { "stream identity changed" }
        protocolCheck(envelope.requestId == requestId) { "request identity changed" }
        protocolCheck(envelope.sessionId == sessionId) { "session identity changed" }
        protocolCheck(!terminal) { "event received after terminal event" }
        protocolCheck(envelope.sequence == nextSequence) {
            "expected sequence $nextSequence but received ${envelope.sequence}"
        }
        if (requireOpenedEvent && nextSequence == 0L) {
            protocolCheck(envelope.event is GatewayEvent.StreamOpened) { "sequence 0 must open the stream" }
        } else {
            protocolCheck(envelope.event !is GatewayEvent.StreamOpened) { "stream_opened may appear only at sequence 0" }
        }
        envelope.event.validate(limits)
        nextSequence += 1
        terminal = envelope.event.isTerminal()
        return envelope.event
    }
}

class GatewayProtocolCodec(
    json: Json = Json,
    private val limits: GatewayProtocolLimits = GatewayProtocolLimits(),
) {
    private val json = Json(json) {
        classDiscriminator = "type"
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = false
    }

    fun encodeCreateRequest(value: GatewayCreateStreamRequest): String {
        value.validate(limits)
        return json.encodeToString(GatewayCreateStreamRequest.serializer(), value)
    }

    fun decodeCreateRequest(value: String): GatewayCreateStreamRequest = decode("create request") {
        json.decodeFromString(GatewayCreateStreamRequest.serializer(), value).also { it.validate(limits) }
    }

    fun encodeDescriptor(value: GatewayStreamDescriptor): String {
        value.validate(limits)
        return json.encodeToString(GatewayStreamDescriptor.serializer(), value)
    }

    fun decodeDescriptor(value: String): GatewayStreamDescriptor = decode("stream descriptor") {
        json.decodeFromString(GatewayStreamDescriptor.serializer(), value).also { it.validate(limits) }
    }

    fun encodeEnvelope(value: GatewayStreamEnvelope): String {
        validateEnvelope(value, limits)
        return json.encodeToString(GatewayStreamEnvelope.serializer(), value)
    }

    fun decodeEnvelope(value: String): GatewayStreamEnvelope = decode("stream envelope") {
        json.decodeFromString(GatewayStreamEnvelope.serializer(), value).also {
            validateEnvelope(it, limits)
        }
    }

    fun encodeProblem(value: GatewayProblem): String {
        value.validate(limits)
        return json.encodeToString(GatewayProblem.serializer(), value)
    }

    fun decodeProblem(value: String): GatewayProblem = decode("problem") {
        json.decodeFromString(GatewayProblem.serializer(), value).also {
            it.validate(limits)
        }
    }

    private inline fun <T> decode(label: String, block: () -> T): T = try {
        block()
    } catch (failure: GatewayProtocolException) {
        throw failure
    } catch (failure: SerializationException) {
        throw GatewayProtocolException("Invalid Gateway $label", failure)
    } catch (failure: IllegalArgumentException) {
        throw GatewayProtocolException("Invalid Gateway $label", failure)
    }
}

fun ProviderEvent.toGatewayEvent(): GatewayEvent = when (this) {
    is ProviderEvent.TextStart -> GatewayEvent.TextStart(signature)
    is ProviderEvent.TextDelta -> GatewayEvent.TextDelta(delta, signature)
    is ProviderEvent.TextEnd -> GatewayEvent.TextEnd(text, signature)
    is ProviderEvent.ReasoningStart -> GatewayEvent.ReasoningStart(signature, redacted)
    is ProviderEvent.ReasoningDelta -> GatewayEvent.ReasoningDelta(delta, signature)
    is ProviderEvent.ReasoningEnd -> GatewayEvent.ReasoningEnd(text, signature, redacted)
    is ProviderEvent.ToolCallStart -> GatewayEvent.ToolCallStart(toolCall.toGatewayToolCall())
    is ProviderEvent.ToolCallDelta -> GatewayEvent.ToolCallDelta(toolCallId, delta)
    is ProviderEvent.ToolCallEnd -> GatewayEvent.ToolCallEnd(toolCall.toGatewayToolCall())
    is ProviderEvent.UsageDelta -> GatewayEvent.UsageDelta(usage.toGatewayUsage())
    is ProviderEvent.Completed -> GatewayEvent.Completed(
        finishReason = finishReason,
        stopReason = stopReason,
        usage = usage?.toGatewayUsage(),
        providerMetadata = providerMetadata,
    )
}

fun GatewayEvent.toProviderEventOrNull(): ProviderEvent? = when (this) {
    is GatewayEvent.StreamOpened -> null
    is GatewayEvent.TextStart -> ProviderEvent.TextStart(signature)
    is GatewayEvent.TextDelta -> ProviderEvent.TextDelta(delta, signature)
    is GatewayEvent.TextEnd -> ProviderEvent.TextEnd(text, signature)
    is GatewayEvent.ReasoningStart -> ProviderEvent.ReasoningStart(signature, redacted)
    is GatewayEvent.ReasoningDelta -> ProviderEvent.ReasoningDelta(delta, signature)
    is GatewayEvent.ReasoningEnd -> ProviderEvent.ReasoningEnd(text, signature, redacted)
    is GatewayEvent.ToolCallStart -> ProviderEvent.ToolCallStart(toolCall.toProviderToolCall())
    is GatewayEvent.ToolCallDelta -> ProviderEvent.ToolCallDelta(toolCallId, delta)
    is GatewayEvent.ToolCallEnd -> ProviderEvent.ToolCallEnd(toolCall.toProviderToolCall())
    is GatewayEvent.UsageDelta -> ProviderEvent.UsageDelta(usage.toProviderUsage())
    is GatewayEvent.Completed -> ProviderEvent.Completed(
        finishReason = finishReason,
        stopReason = stopReason,
        usage = usage?.toProviderUsage(),
        providerMetadata = providerMetadata,
    )
    is GatewayEvent.Failed -> null
    is GatewayEvent.Cancelled -> null
}

private fun ToolCallPart.toGatewayToolCall() = GatewayToolCall(
    id = toolCallId,
    name = toolName,
    arguments = arguments,
    partial = partial,
    thoughtSignature = thoughtSignature,
    providerCallId = providerCallId,
    providerMetadata = providerMetadata,
)

private fun GatewayToolCall.toProviderToolCall() = ToolCallPart(
    toolCallId = id,
    toolName = name,
    arguments = arguments,
    partial = partial,
    thoughtSignature = thoughtSignature,
    providerCallId = providerCallId,
    providerMetadata = providerMetadata,
)

private fun ProviderUsage.toGatewayUsage() = GatewayUsage(inputTokens, outputTokens, reasoningTokens)
private fun GatewayUsage.toProviderUsage() = ProviderUsage(inputTokens, outputTokens, reasoningTokens)

private fun validateEnvelope(value: GatewayStreamEnvelope, limits: GatewayProtocolLimits) {
    validateVersion(value.protocolVersion)
    validateIdentifier("streamId", value.streamId, limits.maxIdChars)
    validateIdentifier("requestId", value.requestId, limits.maxRequestIdChars)
    validateIdentifier("sessionId", value.sessionId, limits.maxIdChars)
    protocolCheck(value.sequence >= 0) { "sequence must not be negative" }
    value.event.validate(limits)
}

private fun GatewayEvent.validate(limits: GatewayProtocolLimits) {
    when (this) {
        is GatewayEvent.StreamOpened -> protocolCheck(replayFromSequence == 0L) {
            "replayFromSequence must be zero in Gateway v1"
        }
        is GatewayEvent.TextStart -> validateOptionalOpaque("text signature", signature, limits.maxSignatureChars)
        is GatewayEvent.TextDelta -> {
            protocolCheck(delta.length <= limits.maxRequestTextChars) { "text delta exceeds configured limit" }
            validateOptionalOpaque("text signature", signature, limits.maxSignatureChars)
        }
        is GatewayEvent.TextEnd -> {
            protocolCheck((text?.length ?: 0) <= limits.maxRequestTextChars) { "text end exceeds configured limit" }
            validateOptionalOpaque("text signature", signature, limits.maxSignatureChars)
        }
        is GatewayEvent.ReasoningStart -> validateOptionalOpaque(
            "reasoning signature",
            signature,
            limits.maxSignatureChars,
        )
        is GatewayEvent.ReasoningDelta -> {
            protocolCheck(delta.length <= limits.maxRequestTextChars) { "reasoning delta exceeds configured limit" }
            validateOptionalOpaque("reasoning signature", signature, limits.maxSignatureChars)
        }
        is GatewayEvent.ReasoningEnd -> {
            protocolCheck((text?.length ?: 0) <= limits.maxRequestTextChars) { "reasoning end exceeds configured limit" }
            validateOptionalOpaque("reasoning signature", signature, limits.maxSignatureChars)
        }
        is GatewayEvent.ToolCallStart -> toolCall.validate(limits)
        is GatewayEvent.ToolCallDelta -> {
            validateIdentifier("toolCallId", toolCallId, limits.maxIdChars)
            protocolCheck(delta.length <= limits.maxJsonChars) { "tool-call delta exceeds configured limit" }
        }
        is GatewayEvent.ToolCallEnd -> toolCall.validate(limits)
        is GatewayEvent.UsageDelta -> usage.validate()
        is GatewayEvent.Completed -> {
            validateOptionalOpaque("finishReason", finishReason, limits.maxFailureMessageChars)
            usage?.validate()
            providerMetadata?.let { validateSafeMetadata(it, limits, "completed.providerMetadata") }
        }
        is GatewayEvent.Failed -> {
            protocolCheck(retryAfterMillis == null || retryAfterMillis >= 0) { "retryAfterMillis must not be negative" }
        }
        is GatewayEvent.Cancelled -> protocolCheck((reason?.length ?: 0) <= limits.maxFailureMessageChars) {
            "cancellation reason exceeds configured limit"
        }
    }
}

private fun GatewayToolCall.validate(limits: GatewayProtocolLimits) {
    validateIdentifier("toolCall.id", id, limits.maxIdChars)
    validateIdentifier("toolCall.name", name, limits.maxIdChars)
    protocolCheck(arguments.toString().length <= limits.maxJsonChars) { "tool-call arguments exceed configured limit" }
    validateOptionalOpaque("toolCall.thoughtSignature", thoughtSignature, limits.maxSignatureChars)
    validateOptionalOpaque("toolCall.providerCallId", providerCallId, limits.maxSignatureChars)
    providerMetadata?.let { validateSafeMetadata(it, limits, "toolCall.providerMetadata") }
}

private fun GatewayUsage.validate() {
    protocolCheck(inputTokens == null || inputTokens >= 0) { "inputTokens must not be negative" }
    protocolCheck(outputTokens == null || outputTokens >= 0) { "outputTokens must not be negative" }
    protocolCheck(reasoningTokens == null || reasoningTokens >= 0) { "reasoningTokens must not be negative" }
}

private fun GatewayAttachmentReference.validate(limits: GatewayProtocolLimits) {
    validateIdentifier("attachment.id", id, limits.maxIdChars)
    protocolCheck(MEDIA_TYPE.matches(mediaType)) { "attachment mediaType is invalid" }
    protocolCheck(sizeBytes in 1..limits.maxAttachmentBytes) { "attachment size exceeds configured limit" }
    protocolCheck(sha256 == null || SHA_256.matches(sha256)) { "attachment sha256 must be lowercase hexadecimal" }
}

private fun MessagePart.textWeight(): Long = when (this) {
    is TextPart -> text.length.toLong()
    is ReasoningPart -> text.length.toLong()
    is ToolCallPart -> arguments.toString().length.toLong()
    is ToolResultPart -> result.toString().length.toLong()
    else -> 0L
}

private fun MessagePart.validateWireFields(limits: GatewayProtocolLimits) {
    when (this) {
        is TextPart -> validateOptionalOpaque("text.signature", signature, limits.maxSignatureChars)
        is ReasoningPart -> validateOptionalOpaque("reasoning.signature", signature, limits.maxSignatureChars)
        is JsonPart -> protocolCheck(value.toString().length <= limits.maxJsonChars) {
            "json part exceeds configured limit"
        }
        is ToolCallPart -> {
            validateIdentifier("toolCall.id", toolCallId, limits.maxIdChars)
            validateIdentifier("toolCall.name", toolName, limits.maxIdChars)
            protocolCheck(arguments.toString().length <= limits.maxJsonChars) {
                "tool-call arguments exceed configured limit"
            }
            validateOptionalOpaque("toolCall.thoughtSignature", thoughtSignature, limits.maxSignatureChars)
            validateOptionalOpaque("toolCall.providerCallId", providerCallId, limits.maxSignatureChars)
        }
        is ToolResultPart -> {
            validateIdentifier("toolResult.id", toolCallId, limits.maxIdChars)
            validateIdentifier("toolResult.name", toolName, limits.maxIdChars)
            protocolCheck(result.toString().length <= limits.maxJsonChars) {
                "tool result exceeds configured limit"
            }
            protocolCheck((displayText?.length ?: 0) <= limits.maxRequestTextChars) {
                "tool result display text exceeds configured limit"
            }
        }
        is AttachmentPart -> protocolCheck(MEDIA_TYPE.matches(mimeType)) { "attachment mediaType is invalid" }
    }
}

private fun GatewayProblem.validate(limits: GatewayProtocolLimits) {
    validateVersion(protocolVersion)
    validateIdentifier("problem.code", code, limits.maxIdChars)
    protocolCheck(message.isNotBlank()) { "problem message must not be blank" }
    protocolCheck(message.length <= limits.maxFailureMessageChars) { "problem message exceeds limit" }
    requestId?.let { validateIdentifier("problem.requestId", it, limits.maxRequestIdChars) }
    protocolCheck(retryAfterMillis == null || retryAfterMillis >= 0) { "retryAfterMillis must not be negative" }
}

private fun validateOptionalOpaque(label: String, value: String?, maxChars: Int) {
    if (value == null) return
    protocolCheck(value.length <= maxChars && value.none { it.isISOControl() }) { "$label is invalid" }
}

private fun validateSafeMetadata(value: JsonElement, limits: GatewayProtocolLimits, label: String) {
    protocolCheck(value.toString().length <= limits.maxJsonChars) { "$label exceeds configured limit" }
    val sensitiveKey = value.findSensitiveKey()
    protocolCheck(sensitiveKey == null) { "$label contains forbidden key '${sensitiveKey.orEmpty()}'" }
}

private fun JsonElement.findSensitiveKey(): String? = when (this) {
    is JsonObject -> entries.firstNotNullOfOrNull { (key, value) ->
        key.takeIf(::isSensitiveMetadataKey) ?: value.findSensitiveKey()
    }
    is JsonArray -> firstNotNullOfOrNull(JsonElement::findSensitiveKey)
    else -> null
}

private fun isSensitiveMetadataKey(key: String): Boolean {
    val normalized = key.lowercase().filter(Char::isLetterOrDigit)
    return normalized in SENSITIVE_METADATA_KEYS ||
        normalized.endsWith("authorization") ||
        normalized.endsWith("apikey") ||
        normalized.endsWith("password") ||
        normalized.endsWith("secret") ||
        normalized.endsWith("token") ||
        normalized.endsWith("cookie")
}

private fun GatewayEvent.isTerminal(): Boolean =
    this is GatewayEvent.Completed || this is GatewayEvent.Failed || this is GatewayEvent.Cancelled

private fun validateVersion(version: Int) {
    protocolCheck(version == GATEWAY_PROTOCOL_VERSION) {
        "unsupported Gateway protocol version $version"
    }
}

private fun validateIdentifier(label: String, value: String, maxChars: Int) {
    protocolCheck(value.length in 1..maxChars && value.all { it.isAsciiIdentifierCharacter() }) {
        "$label is invalid"
    }
}

private fun Char.isAsciiIdentifierCharacter(): Boolean =
    this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9' || this == '.' || this == '_' || this == '-' || this == ':'

private fun Char.isAsciiRegistryKeyCharacter(): Boolean =
    this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9' || this == '.' || this == '_' || this == '-'

private fun Char.isGatewayModelCharacter(): Boolean =
    isAsciiIdentifierCharacter() || this == '/' || this == '+' || this == '@'

private inline fun protocolCheck(condition: Boolean, lazyMessage: () -> String) {
    if (!condition) throw GatewayProtocolException(lazyMessage())
}

private val MEDIA_TYPE = Regex("^[a-z0-9][a-z0-9!#$&^_.+-]{0,126}/[a-z0-9][a-z0-9!#$&^_.+-]{0,126}$")
private val SHA_256 = Regex("^[0-9a-f]{64}$")
private val SENSITIVE_METADATA_KEYS = setOf(
    "authorization",
    "credential",
    "credentials",
    "headers",
    "endpoint",
    "accesstoken",
    "refreshtoken",
    "bearertoken",
    "cookie",
    "setcookie",
    "csrf",
    "csrftoken",
)
