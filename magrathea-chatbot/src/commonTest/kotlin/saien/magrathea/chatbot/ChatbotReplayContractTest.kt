package saien.magrathea.chatbot

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import saien.magrathea.core.AgentMessage
import saien.magrathea.core.AgentRequest
import saien.magrathea.core.AgentRunId
import saien.magrathea.core.AgentSessionId
import saien.magrathea.core.AgentSessionSnapshot
import saien.magrathea.core.AgentStateSnapshot
import saien.magrathea.core.AgentStatus
import saien.magrathea.core.MessageBlockPhase
import saien.magrathea.core.MessageRole
import saien.magrathea.core.ModelDescriptor
import saien.magrathea.core.ReasoningContentKind
import saien.magrathea.core.ReasoningPart
import saien.magrathea.core.ReplayExclusion
import saien.magrathea.core.ReplayExclusionReason
import saien.magrathea.core.StopReason
import saien.magrathea.core.TextPart
import saien.magrathea.provider.api.InMemoryProviderRegistry
import saien.magrathea.provider.api.ProviderAdapter
import saien.magrathea.provider.api.ProviderChunk
import saien.magrathea.provider.api.ProviderEvent
import saien.magrathea.provider.api.ProviderRequest
import saien.magrathea.runtime.DefaultAgentRunner
import saien.magrathea.runtime.InMemoryAgentPersistence
import saien.magrathea.runtime.InMemoryToolRegistry

@OptIn(ExperimentalCoroutinesApi::class)
class ChatbotReplayContractTest {
    @Test
    fun restoredPartialHistoryRemainsVisibleAndLatestReplayIsAvailableToLateFacades() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val provider = CompletingProvider()
        val persistence = InMemoryAgentPersistence()
        val sessionId = AgentSessionId("partial-session")
        val model = ModelDescriptor(provider.key, "model")
        val partial = AgentMessage(
            id = "partial",
            role = MessageRole.ASSISTANT,
            parts = listOf(ReasoningPart("Unfinished", kind = ReasoningContentKind.TEXT, phase = MessageBlockPhase.COMMENTARY)),
        )
        val messages = listOf(
            AgentMessage(id = "user", role = MessageRole.USER, parts = listOf(TextPart("Question"))),
            partial,
        )
        val request = AgentRequest(sessionId = sessionId, messages = messages, model = model)
        persistence.commit(
            AgentSessionSnapshot(sessionId, AgentRunId("old-run"), request, AgentStateSnapshot(
                messages = messages, status = AgentStatus.CANCELLED, stopReason = StopReason.CANCELLED,
            )),
            checkpoint = null,
        )
        val client = createChatbotClient(
            runner = DefaultAgentRunner(
                providerRegistry = InMemoryProviderRegistry(listOf(provider)),
                toolRegistry = InMemoryToolRegistry(),
                persistence = persistence,
                dispatcher = dispatcher,
            ),
            requestFactory = DefaultChatbotRequestFactory(),
            persistence = persistence,
            closeResources = { provider.close() },
            sessionDispatcher = dispatcher,
        )
        try {
            val session = client.restoreSession(sessionId.value)
            assertNull(session.snapshot().replay)
            session.send("Continue")
            advanceUntilIdle()
            assertEquals(ChatbotStatus.COMPLETED, session.snapshot().status)
            val report = assertNotNull(session.snapshot().replay)
            assertEquals(listOf(ReplayExclusion(partial.id, ReplayExclusionReason.EMPTY_AFTER_TRANSFORMATION)), report.exclusions)
            assertEquals(provider.key, report.provider)
            assertEquals(model.model, report.model)
            assertEquals(0, report.turn)
            assertTrue(provider.requests.single().messages.none { it.id == partial.id })
            assertTrue(session.snapshot().messages.any { it.id == partial.id && it.reasoning.isNotEmpty() })
            assertTrue(assertNotNull(persistence.load(sessionId)).snapshot.state.messages.contains(partial))

            val late = client.restoreSession(sessionId.value)
            assertEquals(report, late.snapshot().replay)
            assertEquals(session.snapshot().messages, late.snapshot().messages)
            late.close()
            session.close()
            val restored = client.restoreSession(sessionId.value)
            assertNull(restored.snapshot().replay)
            assertTrue(restored.snapshot().messages.any { it.id == partial.id })
            restored.close()
        } finally {
            client.close()
        }
    }

    private class CompletingProvider : ProviderAdapter {
        override val key = "replay-provider"
        val requests = mutableListOf<ProviderRequest>()

        override suspend fun generate(request: ProviderRequest): Flow<ProviderChunk> = flow {
            requests += request
            emit(ProviderChunk(listOf(
                ProviderEvent.TextStart(), ProviderEvent.TextDelta("Answer"), ProviderEvent.TextEnd("Answer"),
                ProviderEvent.Completed(stopReason = StopReason.COMPLETED),
            )))
        }
    }
}
