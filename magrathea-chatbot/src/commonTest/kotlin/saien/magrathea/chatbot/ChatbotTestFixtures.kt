package saien.magrathea.chatbot

import saien.magrathea.core.ModelDescriptor

internal fun testChatbotConfiguration(
    provider: String = "test",
    model: String = "test-model",
): ChatbotSessionConfiguration = ChatbotSessionConfiguration(
    ModelDescriptor(provider = provider, model = model),
)
