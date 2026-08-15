package saien.magrathea.provider.gemini

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import saien.magrathea.core.StopReason
import saien.magrathea.core.ReasoningContentKind
import saien.magrathea.core.ToolCallPart
import saien.magrathea.provider.api.ProviderAuthException
import saien.magrathea.provider.api.ProviderChunk
import saien.magrathea.provider.api.ProviderClientException
import saien.magrathea.provider.api.ProviderContextLimitException
import saien.magrathea.provider.api.ProviderEvent
import saien.magrathea.provider.api.ProviderNetworkException
import saien.magrathea.provider.api.ProviderPermissionException
import saien.magrathea.provider.api.ProviderProtocolException
import saien.magrathea.provider.api.ProviderRateLimitException
import saien.magrathea.provider.api.ProviderServerException
import saien.magrathea.provider.api.ProviderTimeoutException
import saien.magrathea.provider.api.ProviderTimeoutPhase
import saien.magrathea.provider.api.ProviderUsage
import saien.magrathea.provider.api.isProviderContextLimitError
import saien.magrathea.provider.api.validateSemantics

internal const val GEMINI_INTERACTION_STEPS_METADATA = "gemini.interactions.steps"
internal const val GEMINI_INTERACTION_ID_METADATA = "gemini.interactions.id"
internal const val GEMINI_INTERACTION_STATUS_METADATA = "gemini.interactions.status"

internal class GeminiInteractionsCodec(
    private val model: String,
    sourceJson: Json = Json,
) {
    private val json = Json(sourceJson) { ignoreUnknownKeys = false }
    private var created = false
    private var interactionId: String? = null
    private var nextStepIndex = 0
    private var activeStep: ActiveStep? = null
    private val completedSteps = mutableListOf<JsonObject>()
    private var terminal = false
    private var doneSeen = false

    val completed: Boolean get() = terminal

    fun decodeServerSentEvent(eventName: String?, payload: String): ProviderChunk? {
        if (payload == "[DONE]") {
            if (eventName != null && eventName != "done") protocolFailure("Gemini [DONE] marker has unexpected event name")
            if (!terminal) protocolFailure("Gemini emitted [DONE] before interaction.completed")
            if (doneSeen) protocolFailure("Gemini emitted duplicate [DONE] marker")
            doneSeen = true
            return null
        }
        if (terminal) protocolFailure("Gemini emitted data after interaction.completed")

        val root = parseObject(payload, "Gemini streaming event")
        val payloadEventType = root.requiredString("event_type")
        if (eventName != null && eventName != payloadEventType) {
            protocolFailure("Gemini SSE event name does not match event_type")
        }
        val events = when (payloadEventType) {
            "interaction.created" -> decodeCreated(root)
            "interaction.status_update" -> decodeStatusUpdate(root)
            "step.start" -> decodeStepStart(root)
            "step.delta" -> decodeStepDelta(root)
            "step.stop" -> decodeStepStop(root)
            "interaction.completed" -> decodeCompleted(root.requiredObject("interaction"))
            "error" -> {
                val error = root["error"] as? JsonObject
                    ?: protocolFailure("Gemini error event is missing error details")
                throwGeminiInteractionFailure(error)
            }
            else -> protocolFailure("Unsupported Gemini interaction event type $payloadEventType")
        }
        return events.takeIf { it.isNotEmpty() }
            ?.let { ProviderChunk(events = it).also(ProviderChunk::validateSemantics) }
    }

    fun decodeNonStreaming(payload: String): ProviderChunk {
        checkPristine()
        val interaction = parseObject(payload, "Gemini interaction response")
        interactionId = interaction.requiredString("id", allowEmpty = true)
        requireModel(interaction.requiredString("model"))
        created = true
        val steps = interaction.requiredArray("steps")
        val events = buildList {
            steps.forEachIndexed { index, element ->
                val step = element as? JsonObject ?: protocolFailure("Gemini step $index must be an object")
                addAll(decodeCompleteStep(step))
                completedSteps += step
                nextStepIndex += 1
            }
            addAll(decodeCompleted(interaction))
        }
        return ProviderChunk(events = events).also(ProviderChunk::validateSemantics)
    }

    fun finish() {
        if (activeStep != null) protocolFailure("Gemini stream ended with an incomplete step")
        if (!terminal) protocolFailure("Gemini stream ended before interaction.completed")
    }

    private fun decodeCreated(root: JsonObject): List<ProviderEvent> {
        if (created) protocolFailure("Gemini emitted duplicate interaction.created")
        val interaction = root.requiredObject("interaction")
        interactionId = interaction.requiredString("id", allowEmpty = true)
        requireModel(interaction.requiredString("model"))
        requireStatus(interaction.requiredString("status"), setOf("in_progress"))
        created = true
        return emptyList()
    }

    private fun decodeStatusUpdate(root: JsonObject): List<ProviderEvent> {
        requireCreated()
        requireInteractionId(root.requiredString("interaction_id", allowEmpty = true))
        requireStatus(root.requiredString("status"), setOf("in_progress", "requires_action"))
        return emptyList()
    }

    private fun decodeStepStart(root: JsonObject): List<ProviderEvent> {
        requireCreated()
        if (activeStep != null) protocolFailure("Gemini started a step before stopping the previous step")
        val index = root.requiredIndex()
        if (index != nextStepIndex) protocolFailure("Gemini step index is out of order")
        val step = root.requiredObject("step")
        val type = step.requiredString("type")
        val active = ActiveStep(index, type, step)
        activeStep = active
        return when (type) {
            "model_output" -> listOf(ProviderEvent.TextStart())
            "thought" -> {
                active.signature = step.optionalString("signature")?.takeIf(String::isNotBlank)
                listOf(
                    ProviderEvent.ReasoningStart(
                        signature = active.signature,
                        kind = ReasoningContentKind.SUMMARY,
                    ),
                )
            }
            "function_call" -> {
                active.callId = step.requiredString("id")
                active.functionName = step.requiredString("name")
                active.initialArguments = step["arguments"] as? JsonObject ?: JsonObject(emptyMap())
                listOf(
                    ProviderEvent.ToolCallStart(
                        ToolCallPart(
                            toolCallId = active.callId!!,
                            toolName = active.functionName!!,
                            arguments = active.initialArguments,
                            partial = true,
                            providerCallId = active.callId,
                        ),
                    ),
                )
            }
            else -> protocolFailure("Unsupported Gemini interaction step type $type")
        }
    }

    private fun decodeStepDelta(root: JsonObject): List<ProviderEvent> {
        val active = activeStep ?: protocolFailure("Gemini emitted step.delta without an active step")
        if (root.requiredIndex() != active.index) protocolFailure("Gemini step.delta index does not match the active step")
        val delta = root.requiredObject("delta")
        return when (active.type) {
            "model_output" -> when (delta.requiredString("type")) {
                "text" -> {
                    val text = delta.requiredString("text", allowEmpty = true)
                    active.text.append(text)
                    listOf(ProviderEvent.TextDelta(text))
                }
                else -> protocolFailure("Unsupported Gemini model_output delta type")
            }
            "thought" -> when (delta.requiredString("type")) {
                "thought_summary" -> {
                    val content = delta.requiredObject("content")
                    if (content.requiredString("type") != "text") protocolFailure("Gemini thought summary must contain text")
                    val text = content.requiredString("text", allowEmpty = true)
                    active.summary += content
                    listOf(ProviderEvent.ReasoningDelta(text, active.signature))
                }
                "thought_signature" -> {
                    val signature = delta.requiredString("signature", allowEmpty = true)
                    if (signature.isEmpty()) return emptyList()
                    if (active.signature != null && active.signature != signature) {
                        protocolFailure("Gemini thought step changed signature")
                    }
                    active.signature = signature
                    listOf(ProviderEvent.ReasoningDelta("", signature))
                }
                else -> protocolFailure("Unsupported Gemini thought delta type")
            }
            "function_call" -> when (delta.requiredString("type")) {
                "arguments_delta" -> {
                    val arguments = delta.requiredString("arguments", allowEmpty = true)
                    active.argumentDeltas.append(arguments)
                    listOf(ProviderEvent.ToolCallDelta(active.callId!!, arguments))
                }
                else -> protocolFailure("Unsupported Gemini function_call delta type")
            }
            else -> protocolFailure("Unsupported active Gemini step type")
        }
    }

    private fun decodeStepStop(root: JsonObject): List<ProviderEvent> {
        val active = activeStep ?: protocolFailure("Gemini emitted step.stop without an active step")
        if (root.requiredIndex() != active.index) protocolFailure("Gemini step.stop index does not match the active step")
        val (step, events) = when (active.type) {
            "model_output" -> {
                val content = buildJsonArray {
                    if (active.text.isNotEmpty()) add(textContent(active.text.toString()))
                }
                JsonObject(active.start + ("content" to content)) to listOf(ProviderEvent.TextEnd())
            }
            "thought" -> {
                val assembled = buildJsonObject {
                    active.start.forEach { (key, value) -> put(key, value) }
                    active.signature?.let { put("signature", it) }
                    if (active.summary.isNotEmpty()) put("summary", JsonArray(active.summary))
                }
                assembled to listOf(ProviderEvent.ReasoningEnd(signature = active.signature))
            }
            "function_call" -> {
                val arguments = active.finalArguments(json)
                val toolCall = ToolCallPart(
                    toolCallId = active.callId!!,
                    toolName = active.functionName!!,
                    arguments = arguments,
                    partial = false,
                    providerCallId = active.callId,
                    providerMetadata = active.start,
                )
                JsonObject(active.start + ("arguments" to arguments)) to listOf(ProviderEvent.ToolCallEnd(toolCall))
            }
            else -> protocolFailure("Unsupported active Gemini step type")
        }
        completedSteps += step
        activeStep = null
        nextStepIndex += 1
        return events
    }

    private fun decodeCompleteStep(step: JsonObject): List<ProviderEvent> {
        return when (val type = step.requiredString("type")) {
            "model_output" -> buildList {
                add(ProviderEvent.TextStart())
                step.requiredArray("content").forEach { content ->
                    val item = content as? JsonObject ?: protocolFailure("Gemini model output content must be an object")
                    if (item.requiredString("type") != "text") protocolFailure("Unsupported Gemini model output content type")
                    add(ProviderEvent.TextDelta(item.requiredString("text", allowEmpty = true)))
                }
                add(ProviderEvent.TextEnd())
            }
            "thought" -> buildList {
                val signature = step.optionalString("signature")?.takeIf(String::isNotBlank)
                add(
                    ProviderEvent.ReasoningStart(
                        signature = signature,
                        kind = ReasoningContentKind.SUMMARY,
                    ),
                )
                (step["summary"] as? JsonArray).orEmpty().forEach { content ->
                    val item = content as? JsonObject ?: protocolFailure("Gemini thought summary must be an object")
                    if (item.requiredString("type") != "text") protocolFailure("Gemini thought summary must contain text")
                    add(ProviderEvent.ReasoningDelta(item.requiredString("text", allowEmpty = true), signature))
                }
                add(ProviderEvent.ReasoningEnd(signature = signature))
            }
            "function_call" -> {
                val id = step.requiredString("id")
                val name = step.requiredString("name")
                val arguments = step["arguments"] as? JsonObject
                    ?: protocolFailure("Gemini function call arguments must be an object")
                val toolCall = ToolCallPart(
                    toolCallId = id,
                    toolName = name,
                    arguments = arguments,
                    partial = false,
                    providerCallId = id,
                    providerMetadata = step,
                )
                listOf(
                    ProviderEvent.ToolCallStart(toolCall.copy(arguments = JsonObject(emptyMap()), partial = true)),
                    ProviderEvent.ToolCallEnd(toolCall),
                )
            }
            "user_input", "function_result" -> emptyList()
            else -> protocolFailure("Unsupported Gemini interaction step type $type")
        }
    }

    private fun decodeCompleted(interaction: JsonObject): List<ProviderEvent> {
        requireCreated()
        if (activeStep != null) protocolFailure("Gemini completed an interaction with an active step")
        requireInteractionId(interaction.requiredString("id", allowEmpty = true))
        val status = interaction.requiredString("status")
        requireStatus(status, setOf("completed", "requires_action", "incomplete"))
        val hasFunctionCall = completedSteps.any { it.optionalString("type") == "function_call" }
        if (status == "requires_action" && !hasFunctionCall) {
            protocolFailure("Gemini interaction status does not match its function-call steps")
        }
        if (status != "requires_action" && hasFunctionCall) {
            protocolFailure("Gemini interaction cannot finalize function-call steps with status $status")
        }
        val usage = (interaction["usage"] as? JsonObject)?.toProviderUsage()
        terminal = true
        return listOf(
            ProviderEvent.Completed(
                finishReason = status,
                stopReason = when (status) {
                    "requires_action" -> StopReason.TOOL_CALLS
                    "incomplete" -> StopReason.MAX_TOKENS
                    else -> StopReason.COMPLETED
                },
                usage = usage,
                providerMetadata = buildJsonObject {
                    put("provider", "gemini")
                    put("model", model)
                    interactionId!!.takeIf(String::isNotBlank)?.let {
                        put(GEMINI_INTERACTION_ID_METADATA, it)
                    }
                    put(GEMINI_INTERACTION_STATUS_METADATA, status)
                    put(GEMINI_INTERACTION_STEPS_METADATA, JsonArray(completedSteps))
                },
            ),
        )
    }

    private fun checkPristine() {
        if (created || activeStep != null || completedSteps.isNotEmpty() || terminal) {
            protocolFailure("Gemini codec instance cannot decode more than one interaction")
        }
    }

    private fun requireCreated() {
        if (!created) protocolFailure("Gemini event arrived before interaction.created")
    }

    private fun requireInteractionId(value: String) {
        if (value != interactionId) protocolFailure("Gemini interaction ID changed during the response")
    }

    private fun requireModel(value: String) {
        if (value.removePrefix("models/") != model.removePrefix("models/")) {
            protocolFailure("Gemini interaction model does not match the request")
        }
    }

    private fun requireStatus(status: String, accepted: Set<String>) {
        if (status !in accepted) protocolFailure("Unexpected Gemini interaction status $status")
    }
}

private data class ActiveStep(
    val index: Int,
    val type: String,
    val start: JsonObject,
    val text: StringBuilder = StringBuilder(),
    val summary: MutableList<JsonObject> = mutableListOf(),
    val argumentDeltas: StringBuilder = StringBuilder(),
    var signature: String? = null,
    var callId: String? = null,
    var functionName: String? = null,
    var initialArguments: JsonObject = JsonObject(emptyMap()),
) {
    fun finalArguments(json: Json): JsonObject {
        if (argumentDeltas.isEmpty()) return initialArguments
        if (initialArguments.isNotEmpty()) protocolFailure("Gemini function call mixed initial arguments and deltas")
        val decoded = try {
            json.parseToJsonElement(argumentDeltas.toString())
        } catch (failure: Throwable) {
            throw ProviderProtocolException("Malformed Gemini function-call arguments", failure)
        }
        return decoded as? JsonObject
            ?: protocolFailure("Gemini function-call arguments must decode to an object")
    }
}

private fun JsonObject.requiredObject(key: String): JsonObject {
    return this[key] as? JsonObject ?: protocolFailure("Gemini payload is missing object field $key")
}

private fun JsonObject.requiredArray(key: String): JsonArray {
    return this[key] as? JsonArray ?: protocolFailure("Gemini payload is missing array field $key")
}

private fun JsonObject.requiredString(key: String, allowEmpty: Boolean = false): String {
    val value = (this[key] as? JsonPrimitive)?.contentOrNull
        ?: protocolFailure("Gemini payload is missing string field $key")
    if (!allowEmpty && value.isBlank()) protocolFailure("Gemini payload field $key must not be blank")
    return value
}

private fun JsonObject.optionalString(key: String): String? {
    return (this[key] as? JsonPrimitive)?.contentOrNull
}

private fun JsonObject.requiredIndex(): Int {
    return (this["index"] as? JsonPrimitive)?.intOrNull
        ?: protocolFailure("Gemini step event is missing an integer index")
}

private fun JsonObject.toProviderUsage(): ProviderUsage = ProviderUsage(
    inputTokens = (this["total_input_tokens"] as? JsonPrimitive)?.intOrNull,
    outputTokens = (this["total_output_tokens"] as? JsonPrimitive)?.intOrNull,
    reasoningTokens = (this["total_thought_tokens"] as? JsonPrimitive)?.intOrNull,
)

private fun throwGeminiInteractionFailure(error: JsonObject): Nothing {
    val code = error.optionalString("code")?.trim()?.takeIf(String::isNotEmpty)
    val status = error.optionalString("status")?.trim()?.takeIf(String::isNotEmpty)
    val providerMessage = error.optionalString("message")
    val classification = listOfNotNull(code, status, providerMessage).joinToString(" ")
    if (isProviderContextLimitError(classification)) {
        throw ProviderContextLimitException()
    }

    val numericCode = code?.toIntOrNull()
    val canonicalStatus = status?.uppercase()
        ?: code?.takeUnless { numericCode != null }?.uppercase()
    throw when (canonicalStatus) {
        "UNAUTHENTICATED" -> ProviderAuthException(
            message = "Gemini authentication failed",
            statusCode = 401,
        )
        "PERMISSION_DENIED" -> ProviderPermissionException(
            message = "Gemini permission was denied",
            statusCode = 403,
        )
        "RESOURCE_EXHAUSTED" -> ProviderRateLimitException(
            message = "Gemini rate limit exceeded",
            statusCode = 429,
        )
        "DEADLINE_EXCEEDED" -> ProviderTimeoutException(ProviderTimeoutPhase.PROVIDER_CALL)
        "CANCELLED" -> ProviderNetworkException("Gemini interaction was cancelled")
        "INVALID_ARGUMENT", "OUT_OF_RANGE" -> ProviderClientException(
            message = "Gemini request was rejected",
            statusCode = 400,
        )
        "NOT_FOUND" -> ProviderClientException(
            message = "Gemini resource was not found",
            statusCode = 404,
        )
        "ALREADY_EXISTS" -> ProviderClientException(
            message = "Gemini resource already exists",
            statusCode = 409,
        )
        "FAILED_PRECONDITION" -> ProviderClientException(
            message = "Gemini request precondition failed",
            statusCode = 412,
        )
        "UNIMPLEMENTED" -> ProviderClientException(
            message = "Gemini request is not supported",
            statusCode = 400,
        )
        "UNAVAILABLE" -> ProviderServerException(
            message = "Gemini service is unavailable",
            statusCode = 503,
        )
        "ABORTED" -> ProviderServerException(
            message = "Gemini interaction was aborted",
            statusCode = 503,
        )
        "UNKNOWN", "INTERNAL", "DATA_LOSS" -> ProviderServerException(
            message = "Gemini service failed",
            statusCode = 500,
        )
        else -> when {
            numericCode == 401 || numericCode == 403 -> ProviderAuthException(
                message = "Gemini authentication failed",
                statusCode = numericCode,
            )
            numericCode == 408 || numericCode == 504 ->
                ProviderTimeoutException(ProviderTimeoutPhase.PROVIDER_CALL)
            numericCode == 429 -> ProviderRateLimitException(
                message = "Gemini rate limit exceeded",
                statusCode = numericCode,
            )
            numericCode != null && numericCode in 400..499 -> ProviderClientException(
                message = "Gemini request was rejected",
                statusCode = numericCode,
            )
            numericCode != null && numericCode >= 500 -> ProviderServerException(
                message = "Gemini service failed",
                statusCode = numericCode,
            )
            else -> ProviderServerException(
                message = "Gemini service failed",
                statusCode = 500,
            )
        }
    }
}

private fun parseObject(payload: String, label: String): JsonObject {
    return try {
        Json.parseToJsonElement(payload) as? JsonObject
            ?: protocolFailure("$label must be a JSON object")
    } catch (failure: ProviderProtocolException) {
        throw failure
    } catch (failure: Throwable) {
        throw ProviderProtocolException("Malformed $label", failure)
    }
}

private fun textContent(text: String): JsonObject = buildJsonObject {
    put("type", "text")
    put("text", text)
}

private fun protocolFailure(message: String): Nothing = throw ProviderProtocolException(message)
