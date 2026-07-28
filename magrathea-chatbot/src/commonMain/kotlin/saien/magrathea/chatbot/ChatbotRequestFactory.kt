package saien.magrathea.chatbot

import saien.magrathea.core.AgentMessage
import saien.magrathea.core.AgentRequest
import saien.magrathea.core.AgentSessionId
import saien.magrathea.core.ToolDefinition

/** Immutable input for building one request from a session-owned Provider profile/model selection. */
data class ChatbotRequestContext(
    val sessionId: AgentSessionId,
    val configuration: ChatbotSessionConfiguration,
    val messages: List<AgentMessage>,
)

/** Builds a Provider-neutral [AgentRequest] from the current Chatbot session and conversation. */
fun interface ChatbotRequestFactory {
    fun create(context: ChatbotRequestContext): AgentRequest
}

/** Builds requests from the session model with fixed system/tool defaults and a final hook. */
class DefaultChatbotRequestFactory(
    private val systemPrompt: String = "",
    private val tools: List<ToolDefinition> = emptyList(),
    private val configure: AgentRequest.() -> AgentRequest = { this },
) : ChatbotRequestFactory {
    override fun create(context: ChatbotRequestContext): AgentRequest = AgentRequest(
        sessionId = context.sessionId,
        systemPrompt = systemPrompt,
        messages = context.messages,
        model = context.configuration.model,
        tools = tools,
    ).configure()
}
