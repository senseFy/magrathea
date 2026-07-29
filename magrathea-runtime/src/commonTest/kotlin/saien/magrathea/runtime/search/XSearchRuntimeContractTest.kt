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
import saien.magrathea.core.Citation
import saien.magrathea.core.MessageRole
import saien.magrathea.core.ModelDescriptor
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
import kotlin.test.assertTrue

class XSearchRuntimeContractTest {
    @Test
    fun anyToolCallingModelCanUseProviderBackedXSearchEvidence() = runTest {
        var backendCalls = 0
        val search = XSearchTool(
            backend = XSearchBackend {
                backendCalls += 1
                XSearchEvidence(
                    text = "KMP discussion is active.",
                    citations = listOf(
                        Citation("Post", "https://x.com/kotlin/status/1", "KMP update"),
                    ),
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
                sessionId = AgentSessionId("x-search-runtime"),
                messages = listOf(
                    AgentMessage(
                        role = MessageRole.USER,
                        parts = listOf(TextPart("What is happening with KMP on X?")),
                    ),
                ),
                model = ModelDescriptor(
                    provider = provider.key,
                    model = "foreign-main-model",
                    supportsToolCalls = true,
                ),
                tools = listOf(search.definition),
            ),
        ).toList()

        val completedTool = events.filterIsInstance<AgentEvent.ToolCompleted>().single().result
        assertEquals(1, backendCalls)
        assertEquals("https://x.com/kotlin/status/1", completedTool.citations().single().url)
        assertEquals(2, provider.calls)
        assertEquals(listOf(1, 0), provider.advertisedToolCounts)
        assertTrue(events.last() is AgentEvent.Completed)
    }

    private class SearchThenAnswerProvider : ProviderAdapter {
        override val key = "foreign-provider"
        var calls = 0
        val advertisedToolCounts = mutableListOf<Int>()

        override suspend fun generate(
            request: saien.magrathea.provider.api.ProviderRequest,
        ): Flow<ProviderChunk> = flow {
            calls += 1
            advertisedToolCounts += request.tools.size
            if (request.messages.lastOrNull()?.role == MessageRole.TOOL) {
                assertTrue(request.tools.isEmpty())
                val payload = request.messages.last().parts.single()
                    .let { it as saien.magrathea.core.ToolResultPart }
                    .result.jsonObject
                assertEquals(
                    "KMP discussion is active.",
                    payload.getValue("text").jsonPrimitive.content,
                )
                assertEquals(
                    "https://x.com/kotlin/status/1",
                    payload.getValue("sources").jsonArray.single().jsonObject
                        .getValue("url").jsonPrimitive.content,
                )
                emit(providerChunk(text = "Grounded answer", completed = true))
            } else {
                assertEquals(XSearchTool.NAME, request.tools.single().name)
                emit(
                    providerChunk(
                        toolCalls = listOf(
                            ToolCallPart(
                                toolCallId = "x-search-1",
                                toolName = XSearchTool.NAME,
                                arguments = buildJsonObject {
                                    put("query", "Kotlin Multiplatform")
                                },
                            ),
                        ),
                        completed = true,
                    ),
                )
            }
        }
    }
}
