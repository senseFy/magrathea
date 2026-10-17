package saien.magrathea.provider.openai

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import saien.magrathea.core.AgentMessage
import saien.magrathea.core.MessageRole
import saien.magrathea.core.ModelDescriptor
import saien.magrathea.core.ReasoningPart
import saien.magrathea.core.ReplayExclusion
import saien.magrathea.core.ReplayExclusionReason
import saien.magrathea.core.TextPart
import saien.magrathea.provider.api.DefaultReplayPolicy
import saien.magrathea.provider.api.OpenAiTransportConfig
import saien.magrathea.provider.api.OpenAiWireProtocol
import saien.magrathea.provider.api.ProviderRequest

class ReplayRequestContractTest {
    @Test
    fun interruptedReasoningReplayBuildsChatCompletionsAndResponsesRequests() = runTest {
        val model = ModelDescriptor("openai", "test-model")
        val user = AgentMessage(id = "user", role = MessageRole.USER, parts = listOf(TextPart("Question")))
        val partial = AgentMessage(id = "partial", role = MessageRole.ASSISTANT, parts = listOf(ReasoningPart("Unfinished")))
        val history = listOf(user, partial, user.copy(id = "follow-up"))
        val replay = DefaultReplayPolicy().transformWithReport(history, model)

        for (protocol in listOf(OpenAiWireProtocol.CHAT_COMPLETIONS, OpenAiWireProtocol.RESPONSES)) {
            val request = ProviderRequest(model = model, messages = replay.messages, typedConfig = OpenAiTransportConfig(protocol = protocol))
            val payload = when (protocol) {
                OpenAiWireProtocol.CHAT_COMPLETIONS -> OpenAiChatCompletionsRequestBuilder().build(request)
                OpenAiWireProtocol.RESPONSES -> OpenAiResponsesRequestBuilder("openai").build(request)
            }
            val messages = payload[if (protocol == OpenAiWireProtocol.RESPONSES) "input" else "messages"]!!.jsonArray
            assertEquals(listOf("user", "user"), messages.map { it.jsonObject["role"]!!.jsonPrimitive.content })
        }
        assertEquals(listOf(ReplayExclusion(partial.id, ReplayExclusionReason.EMPTY_AFTER_TRANSFORMATION)), replay.exclusions)
        assertEquals(1, history[1].parts.size)
    }
}
