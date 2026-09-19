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
import saien.magrathea.core.AgentEngineConfig
import saien.magrathea.core.AgentEvent
import saien.magrathea.core.AgentMessage
import saien.magrathea.core.AgentRequest
import saien.magrathea.core.AgentSessionId
import saien.magrathea.core.MessageRole
import saien.magrathea.core.ModelDescriptor
import saien.magrathea.core.RuntimeConfig
import saien.magrathea.core.StopReason
import saien.magrathea.core.TextPart
import saien.magrathea.core.ToolCallPart
import saien.magrathea.core.citations
import saien.magrathea.provider.api.InMemoryProviderRegistry
import saien.magrathea.provider.api.ProviderAdapter
import saien.magrathea.provider.api.ProviderChunk
import saien.magrathea.runtime.DefaultAgentRunner
import saien.magrathea.runtime.InMemoryAgentPersistence
import saien.magrathea.runtime.InMemoryToolRegistry
import saien.magrathea.runtime.providerChunk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
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
            persistence = InMemoryAgentPersistence(),
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

    @Test
    fun uncappedSearchRemainsAvailablePastFiveCallsAndFinishesNormally() = runTest {
        verifyRepeatedSearch(maxSearchCallsPerRun = 0, expectedSearches = 6)
    }

    @Test
    fun finiteSearchBudgetStillStopsAdvertisingAtItsConfiguredLimit() = runTest {
        verifyRepeatedSearch(maxSearchCallsPerRun = 5, expectedSearches = 5)
    }

    private suspend fun verifyRepeatedSearch(maxSearchCallsPerRun: Int, expectedSearches: Int) {
        var backendCalls = 0
        val search = WebSearchTool(
            backend = WebSearchBackend {
                backendCalls += 1
                WebSearchBackendResponse(listOf(WebSearchSource("Result", "https://example.com/$backendCalls")))
            },
            policy = WebSearchPolicy(maxSearchCallsPerRun = maxSearchCallsPerRun),
        )
        val provider = RepeatedSearchThenAnswerProvider()
        val runner = DefaultAgentRunner(
            providerRegistry = InMemoryProviderRegistry(listOf(provider)),
            toolRegistry = InMemoryToolRegistry(listOf(search)),
            persistence = InMemoryAgentPersistence(),
        )
        val events = runner.run(
            AgentRequest(
                sessionId = AgentSessionId("repeated-search-$maxSearchCallsPerRun"),
                messages = listOf(AgentMessage(role = MessageRole.USER, parts = listOf(TextPart("research six sources")))),
                model = ModelDescriptor(provider.key, "model", supportsToolCalls = true),
                tools = listOf(search.definition),
                engine = AgentEngineConfig(runtime = RuntimeConfig(maxTurns = 8)),
            ),
        ).toList()

        assertEquals(expectedSearches, backendCalls)
        val completedTools = events.filterIsInstance<AgentEvent.ToolCompleted>()
        assertEquals(expectedSearches, completedTools.size)
        completedTools.forEach { assertFalse(it.result.isError) }
        assertEquals(List(expectedSearches) { 1 } + if (maxSearchCallsPerRun == 0) 1 else 0, provider.advertisedToolCounts)
        val completed = assertIs<AgentEvent.Completed>(events.last())
        assertEquals(StopReason.COMPLETED, completed.state.stopReason)
        assertEquals(expectedSearches, completed.state.toolCallCounts[WebSearchTool.NAME])
        assertEquals("Grounded answer", (completed.state.messages.last().parts.single() as TextPart).text)
    }

    private class RepeatedSearchThenAnswerProvider : ProviderAdapter {
        override val key = "repeated-search-then-answer"
        val advertisedToolCounts = mutableListOf<Int>()

        override suspend fun generate(request: saien.magrathea.provider.api.ProviderRequest): Flow<ProviderChunk> = flow {
            advertisedToolCounts += request.tools.size
            if (request.tools.isNotEmpty() && advertisedToolCounts.size <= 6) {
                emit(providerChunk(
                    toolCalls = listOf(ToolCallPart(
                        toolCallId = "search-${advertisedToolCounts.size}",
                        toolName = WebSearchTool.NAME,
                        arguments = buildJsonObject { put("query", "research source ${advertisedToolCounts.size}") },
                    )),
                    completed = true,
                ))
            } else {
                emit(providerChunk(text = "Grounded answer", completed = true))
            }
        }
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
