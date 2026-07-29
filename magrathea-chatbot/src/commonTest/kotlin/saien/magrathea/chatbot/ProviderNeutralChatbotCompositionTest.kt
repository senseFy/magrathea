@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package saien.magrathea.chatbot

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import saien.magrathea.core.AgentEvent
import saien.magrathea.core.AgentMessage
import saien.magrathea.core.AgentRequest
import saien.magrathea.core.AgentRunId
import saien.magrathea.core.AgentSessionId
import saien.magrathea.core.AgentSessionSnapshot
import saien.magrathea.core.AgentStateSnapshot
import saien.magrathea.core.AgentStatus
import saien.magrathea.core.MessageRole
import saien.magrathea.core.ModelDescriptor
import saien.magrathea.core.StopReason
import saien.magrathea.core.TextPart
import saien.magrathea.runtime.InMemoryAgentPersistence
import kotlin.test.Test
import kotlin.test.assertEquals

class ProviderNeutralChatbotCompositionTest {
    @Test
    fun publicFactoryAcceptsArbitraryRunnerAndOwnsResourceClose() = runTest {
        val persistence = InMemoryAgentPersistence()
        val runner = RecordingRunner(persistence)
        var resourceCloses = 0
        val client = createChatbotClient(
            runner = runner,
            requestFactory = DefaultChatbotRequestFactory(),
            persistence = persistence,
            closeResources = { resourceCloses += 1 },
            sessionDispatcher = StandardTestDispatcher(testScheduler),
        )

        val session = client.createSession(
            ChatbotSessionConfiguration(
                ModelDescriptor(provider = "community-provider", model = "community-model"),
            ),
        )
        session.send("hello")
        advanceUntilIdle()

        assertEquals("community-provider", runner.lastProvider)
        assertEquals(ChatbotStatus.COMPLETED, session.snapshot().status)
        assertEquals("provider-neutral answer", session.snapshot().messages.last().text)
        assertEquals(1, client.history().size)

        client.close()
        client.close()
        assertEquals(1, resourceCloses)
    }

    private class RecordingRunner(
        private val persistence: InMemoryAgentPersistence,
    ) : TestAgentRunner() {
        var lastProvider: String? = null

        override fun run(request: AgentRequest): Flow<AgentEvent> = flow {
            lastProvider = request.model.provider
            val runId = AgentRunId("provider-neutral-run")
            emit(AgentEvent.Started(request.sessionId, runId))
            val state = AgentStateSnapshot(
                messages = request.messages + AgentMessage(
                    role = MessageRole.ASSISTANT,
                    parts = listOf(TextPart("provider-neutral answer")),
                    stopReason = StopReason.COMPLETED,
                ),
                status = AgentStatus.COMPLETED,
                stopReason = StopReason.COMPLETED,
            )
            persistence.commit(
                AgentSessionSnapshot(
                    sessionId = request.sessionId,
                    runId = runId,
                    request = request,
                    state = state,
                ),
                null,
            )
            emit(AgentEvent.Completed(request.sessionId, state))
        }

        override suspend fun resume(sessionId: AgentSessionId): Flow<AgentEvent> = flowOf(
            AgentEvent.Failed(sessionId, saien.magrathea.core.AgentFailureCode.NOT_FOUND),
        )

        override suspend fun cancel(sessionId: AgentSessionId) = Unit
    }
}
