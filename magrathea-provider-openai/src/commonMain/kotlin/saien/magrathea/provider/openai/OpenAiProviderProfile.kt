package saien.magrathea.provider.openai

import saien.magrathea.core.ReasoningEffort
import saien.magrathea.provider.api.OpenAiWireProtocol
import saien.magrathea.provider.api.requireValidHttpEndpoint

/** Provider behavior layered on top of an OpenAI wire protocol. */
enum class OpenAiProtocolDialect {
    OPENAI,

    OPENROUTER,

    XAI,

    /** OpenAI-compatible protocol without Provider-specific normalization. */
    COMPATIBLE,
}

/** Wire shape used for reasoning effort by one Chat Completions dialect. */
enum class OpenAiChatCompletionsReasoningFormat {
    REASONING_EFFORT,
    REASONING_OBJECT,
}

/**
 * Binds a Provider identity to its OpenAI-family defaults.
 *
 * [providerId] is the Runtime routing key and credential namespace. [defaultProtocol] selects the
 * wire codec when a request has no override, and [dialect] supplies Provider-specific behavior.
 */
data class OpenAiProviderProfile(
    val providerId: String,
    val defaultProtocol: OpenAiWireProtocol,
    val dialect: OpenAiProtocolDialect,
    val chatCompletionsReasoningFormat: OpenAiChatCompletionsReasoningFormat? = null,
    val reasoningEffortMapping: Map<ReasoningEffort, String> = emptyMap(),
    val disabledReasoningValue: String? = null,
    val responsesEndpoint: String? = null,
    val chatCompletionsEndpoint: String? = null,
) {
    init {
        require(PROVIDER_ID.matches(providerId)) {
            "OpenAI Provider profile id must use lowercase letters, digits, dots, underscores, or hyphens"
        }
        responsesEndpoint?.let(::requireValidHttpEndpoint)
        chatCompletionsEndpoint?.let(::requireValidHttpEndpoint)
        require(dialect == OpenAiProtocolDialect.COMPATIBLE || reasoningEffortMapping.isEmpty()) {
            "Only compatible OpenAI-family profiles may customize reasoning effort values"
        }
        require(reasoningEffortMapping.values.all { value -> value.isNotBlank() && value == value.trim() }) {
            "Reasoning effort values must be non-blank and trimmed"
        }
        when (dialect) {
            OpenAiProtocolDialect.OPENAI ->
                require(chatCompletionsReasoningFormat == OpenAiChatCompletionsReasoningFormat.REASONING_EFFORT) {
                    "$dialect requires the reasoning_effort Chat Completions shape"
                }
            OpenAiProtocolDialect.XAI -> {
                require(chatCompletionsReasoningFormat == OpenAiChatCompletionsReasoningFormat.REASONING_EFFORT) {
                    "$dialect requires the reasoning_effort Chat Completions shape"
                }
                require(disabledReasoningValue == null) {
                    "xAI reasoning models do not support disabling reasoning"
                }
            }
            OpenAiProtocolDialect.OPENROUTER ->
                require(chatCompletionsReasoningFormat == OpenAiChatCompletionsReasoningFormat.REASONING_OBJECT) {
                    "OpenRouter requires the reasoning object Chat Completions shape"
                }
            OpenAiProtocolDialect.COMPATIBLE -> Unit
        }
        disabledReasoningValue?.let { value ->
            require(value.isNotBlank() && value == value.trim()) {
                "Disabled reasoning value must be non-blank and trimmed"
            }
        }
    }

    fun defaultEndpoint(protocol: OpenAiWireProtocol): String? = when (protocol) {
        OpenAiWireProtocol.RESPONSES -> responsesEndpoint
        OpenAiWireProtocol.CHAT_COMPLETIONS -> chatCompletionsEndpoint
    }

    override fun toString(): String =
        "OpenAiProviderProfile(" +
            "providerId=$providerId, " +
            "defaultProtocol=$defaultProtocol, " +
            "dialect=$dialect, " +
            "chatCompletionsReasoningFormat=$chatCompletionsReasoningFormat, " +
            "reasoningEffortLevels=${reasoningEffortMapping.keys}, " +
            "disabledReasoningValue=$disabledReasoningValue, " +
            "responsesEndpoint=${if (responsesEndpoint == null) "none" else "<configured>"}, " +
            "chatCompletionsEndpoint=${if (chatCompletionsEndpoint == null) "none" else "<configured>"}" +
            ")"

    companion object {
        fun openAi(providerId: String = "openai"): OpenAiProviderProfile = OpenAiProviderProfile(
            providerId = providerId,
            defaultProtocol = OpenAiWireProtocol.RESPONSES,
            dialect = OpenAiProtocolDialect.OPENAI,
            chatCompletionsReasoningFormat = OpenAiChatCompletionsReasoningFormat.REASONING_EFFORT,
            disabledReasoningValue = "none",
            responsesEndpoint = "https://api.openai.com/v1/responses",
            chatCompletionsEndpoint = "https://api.openai.com/v1/chat/completions",
        )

        fun openRouter(providerId: String = "openrouter"): OpenAiProviderProfile = OpenAiProviderProfile(
            providerId = providerId,
            defaultProtocol = OpenAiWireProtocol.CHAT_COMPLETIONS,
            dialect = OpenAiProtocolDialect.OPENROUTER,
            chatCompletionsReasoningFormat = OpenAiChatCompletionsReasoningFormat.REASONING_OBJECT,
            disabledReasoningValue = "none",
            responsesEndpoint = "https://openrouter.ai/api/v1/responses",
            chatCompletionsEndpoint = "https://openrouter.ai/api/v1/chat/completions",
        )

        fun xAi(providerId: String = "xai"): OpenAiProviderProfile = OpenAiProviderProfile(
            providerId = providerId,
            defaultProtocol = OpenAiWireProtocol.RESPONSES,
            dialect = OpenAiProtocolDialect.XAI,
            chatCompletionsReasoningFormat = OpenAiChatCompletionsReasoningFormat.REASONING_EFFORT,
            responsesEndpoint = "https://api.x.ai/v1/responses",
            chatCompletionsEndpoint = "https://api.x.ai/v1/chat/completions",
        )

        fun compatible(
            providerId: String,
            defaultProtocol: OpenAiWireProtocol = OpenAiWireProtocol.CHAT_COMPLETIONS,
            responsesEndpoint: String? = null,
            chatCompletionsEndpoint: String? = null,
            chatCompletionsReasoningFormat: OpenAiChatCompletionsReasoningFormat? = null,
            reasoningEffortMapping: Map<ReasoningEffort, String> = emptyMap(),
            disabledReasoningValue: String? = null,
        ): OpenAiProviderProfile = OpenAiProviderProfile(
            providerId = providerId,
            defaultProtocol = defaultProtocol,
            dialect = OpenAiProtocolDialect.COMPATIBLE,
            chatCompletionsReasoningFormat = chatCompletionsReasoningFormat,
            reasoningEffortMapping = reasoningEffortMapping,
            disabledReasoningValue = disabledReasoningValue,
            responsesEndpoint = responsesEndpoint,
            chatCompletionsEndpoint = chatCompletionsEndpoint,
        )
    }
}

private val PROVIDER_ID = Regex("[a-z0-9](?:[a-z0-9._-]{0,62}[a-z0-9])?")
