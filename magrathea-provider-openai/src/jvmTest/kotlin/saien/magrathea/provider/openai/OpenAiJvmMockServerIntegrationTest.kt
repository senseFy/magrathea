package saien.magrathea.provider.openai

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import saien.magrathea.core.AgentMessage
import saien.magrathea.core.CredentialRef
import saien.magrathea.core.MessageRole
import saien.magrathea.core.ModelDescriptor
import saien.magrathea.core.ProviderCredential
import saien.magrathea.core.TextPart
import saien.magrathea.provider.api.ProviderEvent
import saien.magrathea.provider.api.OpenAiApi
import saien.magrathea.provider.api.OpenAiTransportConfig
import saien.magrathea.provider.api.ProviderRequest

class OpenAiJvmMockServerIntegrationTest {
    @Test
    fun realJvmEngineAndSseFramerConsumeOfficialToolTranscript() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/responses") { exchange ->
            exchange.requestBody.use { it.readBytes() }
            val body = OPENAI_TOOL_STREAM.joinToString(separator = "") { (event, data) ->
                "event: $event\ndata: $data\n\n"
            }.encodeToByteArray()
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        val adapter = OpenAiProviderAdapter()
        try {
            val chunks = adapter.generate(
                ProviderRequest(
                    model = ModelDescriptor("openai", "gpt-contract", supportsStreaming = true),
                    messages = listOf(AgentMessage(role = MessageRole.USER, parts = listOf(TextPart("Weather?")))),
                    credentialRef = CredentialRef("openai"),
                    credential = ProviderCredential("mock-secret"),
                    endpoint = "http://127.0.0.1:${server.address.port}/v1/responses",
                ),
            ).toList()

            assertEquals(1, chunks.flatMap { it.events }.filterIsInstance<ProviderEvent.ToolCallEnd>().size)
            assertEquals(1, chunks.flatMap { it.events }.filterIsInstance<ProviderEvent.Completed>().size)
        } finally {
            adapter.close()
            server.stop(0)
        }
    }

    @Test
    fun realJvmEngineAndSseFramerConsumeChatCompletionsToolTranscript() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/chat/completions") { exchange ->
            exchange.requestBody.use { it.readBytes() }
            val body = OPENAI_CHAT_TOOL_STREAM.joinToString(separator = "") { data ->
                "data: $data\n\n"
            }.encodeToByteArray()
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        val adapter = OpenAiProviderAdapter()
        try {
            val chunks = adapter.generate(
                ProviderRequest(
                    model = ModelDescriptor("openai", "compatible-model", supportsStreaming = true),
                    messages = listOf(AgentMessage(role = MessageRole.USER, parts = listOf(TextPart("Weather?")))),
                    credentialRef = CredentialRef("openai"),
                    credential = ProviderCredential("mock-secret"),
                    endpoint = "http://127.0.0.1:${server.address.port}/v1/chat/completions",
                    typedConfig = OpenAiTransportConfig(api = OpenAiApi.CHAT_COMPLETIONS),
                ),
            ).toList()

            assertEquals(1, chunks.flatMap { it.events }.filterIsInstance<ProviderEvent.ToolCallEnd>().size)
            assertEquals(1, chunks.flatMap { it.events }.filterIsInstance<ProviderEvent.Completed>().size)
        } finally {
            adapter.close()
            server.stop(0)
        }
    }
}
