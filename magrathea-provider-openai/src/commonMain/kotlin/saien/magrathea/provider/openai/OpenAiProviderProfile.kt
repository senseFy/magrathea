package saien.magrathea.provider.openai

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
    val responsesEndpoint: String? = null,
    val chatCompletionsEndpoint: String? = null,
) {
    init {
        require(PROVIDER_ID.matches(providerId)) {
            "OpenAI Provider profile id must use lowercase letters, digits, dots, underscores, or hyphens"
        }
        responsesEndpoint?.let(::requireValidHttpEndpoint)
        chatCompletionsEndpoint?.let(::requireValidHttpEndpoint)
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
            "responsesEndpoint=${if (responsesEndpoint == null) "none" else "<configured>"}, " +
            "chatCompletionsEndpoint=${if (chatCompletionsEndpoint == null) "none" else "<configured>"}" +
            ")"

    companion object {
        fun openAi(providerId: String = "openai"): OpenAiProviderProfile = OpenAiProviderProfile(
            providerId = providerId,
            defaultProtocol = OpenAiWireProtocol.RESPONSES,
            dialect = OpenAiProtocolDialect.OPENAI,
            responsesEndpoint = "https://api.openai.com/v1/responses",
            chatCompletionsEndpoint = "https://api.openai.com/v1/chat/completions",
        )

        fun openRouter(providerId: String = "openrouter"): OpenAiProviderProfile = OpenAiProviderProfile(
            providerId = providerId,
            defaultProtocol = OpenAiWireProtocol.CHAT_COMPLETIONS,
            dialect = OpenAiProtocolDialect.OPENROUTER,
            responsesEndpoint = "https://openrouter.ai/api/v1/responses",
            chatCompletionsEndpoint = "https://openrouter.ai/api/v1/chat/completions",
        )

        fun xAi(providerId: String = "xai"): OpenAiProviderProfile = OpenAiProviderProfile(
            providerId = providerId,
            defaultProtocol = OpenAiWireProtocol.RESPONSES,
            dialect = OpenAiProtocolDialect.XAI,
            responsesEndpoint = "https://api.x.ai/v1/responses",
            chatCompletionsEndpoint = "https://api.x.ai/v1/chat/completions",
        )

        fun compatible(
            providerId: String,
            defaultProtocol: OpenAiWireProtocol = OpenAiWireProtocol.CHAT_COMPLETIONS,
            responsesEndpoint: String? = null,
            chatCompletionsEndpoint: String? = null,
        ): OpenAiProviderProfile = OpenAiProviderProfile(
            providerId = providerId,
            defaultProtocol = defaultProtocol,
            dialect = OpenAiProtocolDialect.COMPATIBLE,
            responsesEndpoint = responsesEndpoint,
            chatCompletionsEndpoint = chatCompletionsEndpoint,
        )
    }
}

private val PROVIDER_ID = Regex("[a-z0-9](?:[a-z0-9._-]{0,62}[a-z0-9])?")
