package saien.magrathea.chatbot

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import saien.magrathea.core.AgentMessage
import saien.magrathea.core.AgentSessionId

/**
 * An immutable user input prepared for one [ChatbotSession] facade lifetime.
 *
 * Preparing an input neither appends it to history nor starts execution. [messageId] is the
 * canonical message ID that [ChatbotSession.sendPrepared] will use, including on the first send.
 * Its canonical timestamp is captured at preparation time, not refreshed when sent.
 * The input does not capture history or model configuration; those are resolved when sent.
 *
 * A prepared input permits one send attempt on its originating facade. Cancellation while waiting
 * for command admission or for the previous execution to settle leaves it unused. Once the new
 * send attempt starts, it remains consumed even if sending fails or is cancelled: admission may
 * already have happened.
 * Reusing it fails with [ChatbotFailure.INVALID_ARGUMENT], rather than risking a duplicate send.
 * This is an admission guard, not a durable idempotency key or a guarantee of persistence.
 * Preparing another input after an uncertain outcome is a new send and must be an explicit choice.
 */
class ChatbotPreparedMessage internal constructor(
    val sessionId: AgentSessionId,
    private val owner: Any,
    private val message: AgentMessage,
) {
    val messageId: String
        get() = message.id

    private var consumed = false

    /** The originating controller holds its command mutex across validation and claim. */
    internal fun validateUsable(requestingOwner: Any) {
        if (owner !== requestingOwner || consumed) {
            throw ChatbotException(ChatbotFailure.INVALID_ARGUMENT)
        }
    }

    /** Only the originating controller may claim, while holding its command mutex. */
    internal fun claim(requestingOwner: Any): AgentMessage {
        validateUsable(requestingOwner)
        consumed = true
        return message
    }
}

/** JsonObject/JsonArray may wrap caller-owned mutable collections. */
internal fun JsonObject.copyPreparedMetadata(): JsonObject =
    JsonObject(mapValues { (_, value) -> value.copyPreparedElement() })

private fun JsonElement.copyPreparedElement(): JsonElement = when (this) {
    is JsonObject -> copyPreparedMetadata()
    is JsonArray -> JsonArray(map { it.copyPreparedElement() })
    else -> this
}
