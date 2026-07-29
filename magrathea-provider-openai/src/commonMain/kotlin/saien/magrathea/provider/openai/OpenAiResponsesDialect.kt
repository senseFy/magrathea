package saien.magrathea.provider.openai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import saien.magrathea.provider.api.ProviderProtocolException

internal data class NormalizedOpenAiSseEvent(
    val eventName: String?,
    val payload: String,
)

/** Normalizes Provider dialect events before [OpenAiResponsesCodec] validates their lifecycle. */
internal class OpenAiResponsesDialectNormalizer(
    private val dialect: OpenAiProtocolDialect,
    sourceJson: Json = Json,
) {
    private val json = Json(sourceJson) {
        encodeDefaults = false
        ignoreUnknownKeys = false
    }

    fun normalize(eventName: String?, payload: String): NormalizedOpenAiSseEvent {
        if (dialect != OpenAiProtocolDialect.OPENROUTER || payload == "[DONE]") {
            return NormalizedOpenAiSseEvent(eventName, payload)
        }
        val root = parseDialectObject(payload)
        if (root["type"]?.jsonPrimitive?.contentOrNull != OPENROUTER_RESPONSE_ERROR) {
            return NormalizedOpenAiSseEvent(eventName, payload)
        }
        val normalized = JsonObject(root + ("type" to JsonPrimitive(STANDARD_ERROR)))
        return NormalizedOpenAiSseEvent(
            eventName = if (eventName == OPENROUTER_RESPONSE_ERROR) STANDARD_ERROR else eventName,
            payload = json.encodeToString(JsonObject.serializer(), normalized),
        )
    }

    private fun parseDialectObject(payload: String): JsonObject = try {
        json.parseToJsonElement(payload) as? JsonObject
            ?: throw ProviderProtocolException("OpenRouter Responses event must be a JSON object")
    } catch (failure: ProviderProtocolException) {
        throw failure
    } catch (failure: Throwable) {
        throw ProviderProtocolException("Malformed OpenRouter Responses event", failure)
    }
}

internal data class OpenAiResponsesDialectPolicy(
    val reconcileReasoningAtItemBoundary: Boolean,
    val allowXSearchOutput: Boolean,
    val allowServerManagedCustomToolCalls: Boolean,
)

internal fun OpenAiProtocolDialect.responsesPolicy(
    xSearchConfigured: Boolean,
): OpenAiResponsesDialectPolicy = OpenAiResponsesDialectPolicy(
    reconcileReasoningAtItemBoundary = when (this) {
        OpenAiProtocolDialect.OPENROUTER,
        OpenAiProtocolDialect.XAI,
        -> true
        OpenAiProtocolDialect.OPENAI,
        OpenAiProtocolDialect.COMPATIBLE,
        -> false
    },
    allowXSearchOutput = this == OpenAiProtocolDialect.XAI && xSearchConfigured,
    allowServerManagedCustomToolCalls = this == OpenAiProtocolDialect.XAI && xSearchConfigured,
)

private const val OPENROUTER_RESPONSE_ERROR = "response.error"
private const val STANDARD_ERROR = "error"
