package saien.magrathea.runtime.search

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
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
import saien.magrathea.core.MediaReference
import saien.magrathea.core.ModelDescriptor
import saien.magrathea.core.TextPart
import saien.magrathea.core.ToolCallPart
import saien.magrathea.core.ToolResultPart
import saien.magrathea.core.ToolResultImageContent
import saien.magrathea.provider.api.InMemoryProviderRegistry
import saien.magrathea.provider.api.ProviderAdapter
import saien.magrathea.provider.api.ProviderChunk
import saien.magrathea.runtime.DefaultAgentRunner
import saien.magrathea.runtime.InMemoryAgentPersistence
import saien.magrathea.runtime.InMemoryToolRegistry
import saien.magrathea.runtime.providerChunk

class ImageSearchRuntimeContractTest {
    @Test
    fun canonicalStateKeepsUserImagesWhileProviderReceivesOnlyStructuredMetadata() = runTest {
        val search = ImageSearchTool(
            backend = ImageSearchBackend {
                ImageSearchBackendResponse(
                    listOf(
                        ImageSearchSource(
                            imageUrl = "https://cdn.example.com/result.jpg",
                            sourcePageUrl = "https://example.com/article",
                            title = "Result",
                            mimeType = "image/jpeg",
                        ),
                    ),
                )
            },
        )
        val provider = ImageSearchThenAnswerProvider()
        val persistence = InMemoryAgentPersistence()
        val runner = DefaultAgentRunner(
            providerRegistry = InMemoryProviderRegistry(listOf(provider)),
            toolRegistry = InMemoryToolRegistry(listOf(search)),
            persistence = persistence,
        )
        val sessionId = AgentSessionId("image-search-runtime")

        val events = runner.run(
            AgentRequest(
                sessionId = sessionId,
                messages = listOf(
                    AgentMessage(
                        role = MessageRole.USER,
                        parts = listOf(TextPart("show current design references")),
                    ),
                ),
                model = ModelDescriptor(provider.key, "model", supportsToolCalls = true),
                tools = listOf(search.definition),
            ),
        ).toList()

        assertEquals(2, provider.calls)
        assertTrue(events.last() is AgentEvent.Completed)
        val completed = events.filterIsInstance<AgentEvent.ToolCompleted>().single().result
        assertEquals(1, completed.content.size)
        val completedImage = assertIs<ToolResultImageContent>(completed.content.single())
        val reference = assertNotNull(completedImage.reference)
        assertEquals(reference, MediaReference.parseUri(assertNotNull(provider.mediaReference)))
        val storedTool = persistence.load(sessionId)!!.snapshot.state.messages
            .flatMap(AgentMessage::parts)
            .filterIsInstance<ToolResultPart>()
            .single()
        assertEquals(1, storedTool.content.size)
        assertEquals(
            reference,
            assertIs<ToolResultImageContent>(storedTool.content.single()).reference,
        )
        val finalAnswer = persistence.load(sessionId)!!.snapshot.state.messages.last()
            .parts.filterIsInstance<TextPart>().joinToString("") { it.text }
        assertTrue(reference.toUri() in finalAnswer)
    }

    private class ImageSearchThenAnswerProvider : ProviderAdapter {
        override val key: String = "image-search-then-answer"
        var calls: Int = 0
        var mediaReference: String? = null

        override suspend fun generate(
            request: saien.magrathea.provider.api.ProviderRequest,
        ): Flow<ProviderChunk> = flow {
            calls += 1
            if (request.messages.lastOrNull()?.role == MessageRole.TOOL) {
                val result = request.messages.last().parts.single() as ToolResultPart
                assertTrue(result.content.isEmpty())
                assertEquals(
                    "https://cdn.example.com/result.jpg",
                    result.result.jsonObject.getValue("sources").jsonArray.single()
                        .jsonObject.getValue("imageUrl").jsonPrimitive.content,
                )
                mediaReference = result.result.jsonObject.getValue("sources").jsonArray.single()
                    .jsonObject.getValue("mediaReference").jsonPrimitive.content
                emit(
                    providerChunk(
                        text = "Here are the images.\n\n![Result](${requireNotNull(mediaReference)})",
                        completed = true,
                    ),
                )
            } else {
                emit(
                    providerChunk(
                        toolCalls = listOf(
                            ToolCallPart(
                                toolCallId = "image-search-1",
                                toolName = ImageSearchTool.NAME,
                                arguments = buildJsonObject { put("query", "current design references") },
                            ),
                        ),
                        completed = true,
                    ),
                )
            }
        }
    }
}
