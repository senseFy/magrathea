@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package saien.magrathea.chatbot

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import saien.magrathea.core.AgentEngineConfig
import saien.magrathea.core.AgentEvent
import saien.magrathea.core.AgentMessage
import saien.magrathea.core.AgentSessionId
import saien.magrathea.core.AgentStateSnapshot
import saien.magrathea.core.MessageRole
import saien.magrathea.core.ModelDescriptor
import saien.magrathea.core.RuntimeConfig
import saien.magrathea.core.StopReason
import saien.magrathea.core.ToolCallPart
import saien.magrathea.provider.api.InMemoryProviderRegistry
import saien.magrathea.provider.api.ProviderAdapter
import saien.magrathea.provider.api.ProviderChunk
import saien.magrathea.provider.api.ProviderEvent
import saien.magrathea.provider.api.ProviderRequest
import saien.magrathea.runtime.DefaultAgentRunner
import saien.magrathea.runtime.InMemoryAgentPersistence
import saien.magrathea.runtime.InMemoryToolRegistry
import saien.magrathea.runtime.search.WebSearchBackend
import saien.magrathea.runtime.search.WebSearchBackendResponse
import saien.magrathea.runtime.search.WebSearchTool

class ChatbotStopReasonContractTest {
    @Test
    fun completionUsesRunReasonAndStartedClearsItWithoutRewritingMessageReasons() {
        val sessionId = AgentSessionId("stop-reason")
        val lastMessage = AgentMessage(
            role = MessageRole.TOOL,
            parts = emptyList(),
            stopReason = StopReason.TOOL_CALLS,
        )
        val reducer = ChatbotEventReducer()
        val completed = reducer.reduce(
            ChatbotSnapshot(configuration = testChatbotConfiguration()),
            AgentEvent.Completed(
                sessionId,
                AgentStateSnapshot(messages = listOf(lastMessage), stopReason = StopReason.MAX_TURNS),
            ),
        )

        assertEquals(ChatbotStatus.COMPLETED, completed.status)
        assertEquals(ChatbotStopReason.MAX_TURNS, completed.stopReason)
        assertEquals(ChatbotStopReason.TOOL_CALLS, completed.messages.single().stopReason)

        val started = reducer.reduce(completed, AgentEvent.Started(sessionId, TEST_RUN_ID))
        assertNull(started.stopReason)
        assertEquals(completed.messages, started.messages)

        val normal = reducer.reduce(
            started,
            AgentEvent.Completed(sessionId, AgentStateSnapshot(emptyList(), stopReason = StopReason.COMPLETED)),
        )
        assertEquals(ChatbotStopReason.COMPLETED, normal.stopReason)
    }

    @Test
    fun actualMaxTurnsSurvivesReopeningAndClearsOnSendOrRegenerate() = runTest {
        for (regenerate in listOf(false, true)) {
            val persistence = InMemoryAgentPersistence()
            val provider = SearchOrAnswerProvider()
            var toolExecutions = 0
            val search = WebSearchTool(WebSearchBackend {
                toolExecutions += 1
                WebSearchBackendResponse(emptyList())
            })
            val dispatcher = StandardTestDispatcher(testScheduler)
            fun createClient() = createChatbotClient(
                runner = DefaultAgentRunner(
                    providerRegistry = InMemoryProviderRegistry(listOf(provider)),
                    toolRegistry = InMemoryToolRegistry(listOf(search)),
                    persistence = persistence,
                    dispatcher = dispatcher,
                ),
                requestFactory = DefaultChatbotRequestFactory(
                    tools = listOf(search.definition),
                    configure = { copy(engine = AgentEngineConfig(runtime = RuntimeConfig(maxTurns = 1))) },
                ),
                persistence = persistence,
                closeResources = {},
                sessionDispatcher = dispatcher,
            )

            val firstClient = createClient()
            val original = firstClient.createSession(ChatbotSessionConfiguration(
                ModelDescriptor(provider.key, "model", supportsToolCalls = true),
            ))
            original.send("Search for a source")
            advanceUntilIdle()
            val stopped = original.snapshot()
            assertEquals(ChatbotStatus.COMPLETED, stopped.status)
            assertEquals(ChatbotStopReason.MAX_TURNS, stopped.stopReason)
            assertEquals(ChatbotStopReason.TOOL_CALLS, stopped.messages.last().stopReason)
            assertEquals(1, toolExecutions)
            assertEquals(1, provider.calls)
            val sessionId = requireNotNull(stopped.sessionId)
            assertEquals(StopReason.MAX_TURNS, persistence.load(AgentSessionId(sessionId))?.snapshot?.state?.stopReason)
            firstClient.close()

            val reopenedClient = createClient()
            try {
                val reopened = reopenedClient.restoreSession(sessionId)
                assertEquals(ChatbotStopReason.MAX_TURNS, reopened.snapshot().stopReason)
                assertEquals(stopped.messages, reopened.snapshot().messages)

                provider.answer = true
                val releaseAnswer = CompletableDeferred<Unit>()
                provider.beforeResponse = releaseAnswer
                if (regenerate) reopened.regenerate(stopped.messages.first().id) else reopened.send("Summarize now")
                runCurrent()
                assertEquals(ChatbotStatus.RUNNING, reopened.snapshot().status)
                assertNull(reopened.snapshot().stopReason)
                releaseAnswer.complete(Unit)
                advanceUntilIdle()

                assertEquals(ChatbotStatus.COMPLETED, reopened.snapshot().status)
                assertEquals(ChatbotStopReason.COMPLETED, reopened.snapshot().stopReason)
                assertEquals("Answer", reopened.snapshot().messages.last().text)
                assertEquals(2, provider.calls)
            } finally {
                reopenedClient.close()
            }
        }
    }

    private class SearchOrAnswerProvider : ProviderAdapter {
        override val key = "stop-reason-provider"
        var answer = false
        var calls = 0
        var beforeResponse: CompletableDeferred<Unit>? = null

        override suspend fun generate(request: ProviderRequest): Flow<ProviderChunk> = flow {
            calls += 1
            beforeResponse?.await()
            val events = if (answer) {
                listOf(
                    ProviderEvent.TextStart(),
                    ProviderEvent.TextDelta("Answer"),
                    ProviderEvent.TextEnd(),
                    ProviderEvent.Completed(stopReason = StopReason.COMPLETED),
                )
            } else {
                val call = ToolCallPart("search-$calls", WebSearchTool.NAME, buildJsonObject { put("query", "source") })
                listOf(
                    ProviderEvent.ToolCallStart(call.copy(partial = true)),
                    ProviderEvent.ToolCallEnd(call),
                    ProviderEvent.Completed(stopReason = StopReason.TOOL_CALLS),
                )
            }
            emit(ProviderChunk(events))
        }
    }
}
