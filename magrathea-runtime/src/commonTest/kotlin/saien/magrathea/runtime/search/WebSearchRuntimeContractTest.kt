package saien.magrathea.runtime.search

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import saien.magrathea.core.AgentEvent
import saien.magrathea.core.AgentMessage
import saien.magrathea.core.AgentRequest
import saien.magrathea.core.AgentSessionId
import saien.magrathea.core.MessageRole
import saien.magrathea.core.ModelDescriptor
import saien.magrathea.core.TextPart
import saien.magrathea.core.ToolCallPart
import saien.magrathea.core.citations
import saien.magrathea.provider.api.InMemoryProviderRegistry
import saien.magrathea.provider.api.ProviderAdapter
import saien.magrathea.provider.api.ProviderChunk
import saien.magrathea.runtime.DefaultAgentRunner
import saien.magrathea.runtime.InMemoryCheckpointStore
import saien.magrathea.runtime.InMemorySessionStore
import saien.magrathea.runtime.InMemoryToolRegistry
import saien.magrathea.runtime.providerChunk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WebSearchRuntimeContractTest {
    @Test
    fun modelToolCallExecutesBackendAndCanonicalCitationsReachTheFollowUpTurn() = runTest {
        var backendCalls = 0
        val search = WebSearchTool(
            backend = WebSearchBackend {
                backendCalls += 1
                WebSearchBackendResponse(
                    listOf(WebSearchSource("Magrathea", "https://example.com/magrathea", "Current release notes")),
                )
            },
        )
        val provider = SearchThenAnswerProvider()
        val runner = DefaultAgentRunner(
            providerRegistry = InMemoryProviderRegistry(listOf(provider)),
            toolRegistry = InMemoryToolRegistry(listOf(search)),
            sessionStore = InMemorySessionStore(),
            checkpointStore = InMemoryCheckpointStore(),
        )

        val events = runner.run(
            AgentRequest(
                sessionId = AgentSessionId("search-runtime"),
                messages = listOf(AgentMessage(role = MessageRole.USER, parts = listOf(TextPart("latest release")))),
                model = ModelDescriptor(provider.key, "model", supportsToolCalls = true),
                tools = listOf(search.definition),
            ),
        ).toList()

        val completedTool = events.filterIsInstance<AgentEvent.ToolCompleted>().single().result
        assertEquals(1, backendCalls)
        assertEquals("Magrathea", completedTool.citations().single().title)
        assertEquals(2, provider.calls)
        assertTrue(events.last() is AgentEvent.Completed)
    }

    private class SearchThenAnswerProvider : ProviderAdapter {
        override val key = "search-then-answer"
        var calls = 0

        override suspend fun generate(request: saien.magrathea.provider.api.ProviderRequest): Flow<ProviderChunk> = flow {
            calls += 1
            assertEquals(WebSearchTool.NAME, request.tools.single().name)
            if (request.messages.lastOrNull()?.role == MessageRole.TOOL) {
                val payload = request.messages.last().parts.single()
                    .let { it as saien.magrathea.core.ToolResultPart }
                    .result.jsonObject
                assertEquals(
                    "https://example.com/magrathea",
                    payload.getValue("sources").jsonArray.single().jsonObject.getValue("url").jsonPrimitive.content,
                )
                emit(providerChunk(text = "Grounded answer", completed = true))
            } else {
                emit(
                    providerChunk(
                        toolCalls = listOf(
                            ToolCallPart(
                                toolCallId = "search-1",
                                toolName = WebSearchTool.NAME,
                                arguments = buildJsonObject { put("query", "Magrathea latest release") },
                            ),
                        ),
                        completed = true,
                    ),
                )
            }
        }
    }
}
