package saien.magrathea.runtime

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import saien.magrathea.core.AgentEngineConfig
import saien.magrathea.core.AgentEvent
import saien.magrathea.core.AgentMessage
import saien.magrathea.core.AgentRequest
import saien.magrathea.core.AgentSessionId
import saien.magrathea.core.ContextManagementConfig
import saien.magrathea.core.ContextManagementState
import saien.magrathea.core.ContextPreparationAction
import saien.magrathea.core.ContextPreparationResult
import saien.magrathea.core.ContextUsageObservation
import saien.magrathea.core.MessageRole
import saien.magrathea.core.ModelDescriptor
import saien.magrathea.core.ProviderConfig
import saien.magrathea.core.RuntimeConfig
import saien.magrathea.core.TextPart
import saien.magrathea.core.TokenUsage
import saien.magrathea.provider.api.InMemoryProviderRegistry
import saien.magrathea.provider.api.OpenAiTransportConfig
import saien.magrathea.provider.api.OpenAiXSearchToolConfig
import saien.magrathea.provider.api.ProviderAdapter
import saien.magrathea.provider.api.ProviderChunk
import saien.magrathea.provider.api.ProviderContextLimitException
import saien.magrathea.provider.api.ProviderRequest
import saien.magrathea.provider.api.ProviderUsage
import saien.magrathea.provider.api.toProviderOptions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ContextManagementRuntimeIntegrationTest {
    @Test
    fun semanticCompaction_usesToolFreeProviderCallAndPersistsOnlyCanonicalHistory() = runTest {
        val provider = SummaryAwareProvider()
        val sessions = InMemorySessionStore()
        val request = request(
            messages = longHistory(),
            contextWindowTokens = 220,
            providerConfig = ProviderConfig(
                maxTokens = 20,
                options = OpenAiTransportConfig(
                    hostedTools = listOf(OpenAiXSearchToolConfig()),
                    maxToolTurns = 2,
                ).toProviderOptions(),
            ),
        )
        val runner = runner(provider, sessions)

        val events = runner.run(request).toList()

        val completed = events.filterIsInstance<AgentEvent.Completed>().single()
        assertEquals(2, provider.requests.size)
        val summaryRequest = provider.requests.single { it.isContextSummary() }
        val modelRequest = provider.requests.single { !it.isContextSummary() }
        assertTrue(summaryRequest.tools.isEmpty())
        val summaryTransport = summaryRequest.typedConfig as OpenAiTransportConfig
        assertTrue(summaryTransport.hostedTools.isEmpty())
        assertNull(summaryTransport.maxToolTurns)
        assertTrue(
            modelRequest.messages.first().parts.filterIsInstance<TextPart>().single().text
                .contains("compacted summary"),
        )

        val saved = assertNotNull(sessions.loadSession(request.sessionId))
        assertNotNull(saved.state.contextManagement.compaction)
        assertTrue(longHistory().all { original -> saved.state.messages.any { it.id == original.id } })
        assertFalse(
            saved.state.messages.any { message ->
                message.metadata["magrathea.context"] != null
            },
        )
        assertEquals(TokenUsage(inputTokens = 150, outputTokens = 17), completed.state.usage)
        assertEquals(TokenUsage(inputTokens = 50, outputTokens = 5), completed.state.latestRequestUsage)
    }

    @Test
    fun aNewRunnerForTheSameSession_restoresPersistentContextState() = runTest {
        val sessions = InMemorySessionStore()
        val sentinel = ContextManagementState(
            usageObservation = ContextUsageObservation(
                inputTokens = 7,
                throughMessageId = null,
                historyPrefixDigest = historyPrefixDigest(emptyList()),
                compactionGeneration = 0,
                provider = PROVIDER,
                model = MODEL,
                requestFingerprint = "sentinel",
            ),
        )
        val firstManager = RecordingContextManager(stateToReturn = sentinel)
        val firstProvider = CompleteProvider()
        val firstRequest = request(
            sessionId = AgentSessionId("persistent-context-session"),
            messages = listOf(message("u1", "first")),
        )
        runner(firstProvider, sessions, firstManager).run(firstRequest).toList()

        val persisted = assertNotNull(sessions.loadSession(firstRequest.sessionId))
        assertEquals(sentinel, persisted.state.contextManagement)
        val secondMessages = persisted.state.messages + message("u2", "second")
        val secondManager = RecordingContextManager()
        val secondRequest = firstRequest.copy(messages = secondMessages)

        runner(CompleteProvider(), sessions, secondManager).run(secondRequest).toList()

        assertEquals(sentinel, secondManager.seenStates.single())
    }

    @Test
    fun contextLimitBeforeAnyOutput_forcesOneSemanticCompactionAndRetriesOnce() = runTest {
        val provider = OverflowThenCompleteProvider()
        val sessions = InMemorySessionStore()
        val request = request(
            sessionId = AgentSessionId("overflow-recovery-session"),
            messages = longHistory(),
            contextWindowTokens = null,
        )

        val events = runner(provider, sessions).run(request).toList()

        assertTrue(events.any { it is AgentEvent.Completed })
        assertTrue(events.none { it is AgentEvent.Failed })
        assertEquals(
            listOf("model", "summary", "model"),
            provider.requests.map { if (it.isContextSummary()) "summary" else "model" },
        )
        assertNotNull(sessions.loadSession(request.sessionId)?.state?.contextManagement?.compaction)
    }

    @Test
    fun contextLimitAfterOutput_doesNotRetryOrDuplicatePartialAnswer() = runTest {
        val provider = PartialThenOverflowProvider()
        val request = request(
            sessionId = AgentSessionId("post-output-overflow-session"),
            messages = longHistory(),
            contextWindowTokens = null,
        )

        val events = runner(provider, InMemorySessionStore()).run(request).toList()

        assertEquals(1, provider.requests.size)
        assertTrue(provider.requests.none(ProviderRequest::isContextSummary))
        assertEquals(
            saien.magrathea.core.AgentFailureCode.PROVIDER_PROTOCOL,
            events.filterIsInstance<AgentEvent.Failed>().single().code,
        )
        assertEquals(
            1,
            events.filterIsInstance<AgentEvent.MessageEmitted>()
                .map(AgentEvent.MessageEmitted::message)
                .distinctBy(AgentMessage::id)
                .size,
        )
    }

    private fun runner(
        provider: ProviderAdapter,
        sessions: InMemorySessionStore,
        contextManager: saien.magrathea.core.ContextManager? = null,
    ) = DefaultAgentRunner(
        providerRegistry = InMemoryProviderRegistry(listOf(provider)),
        toolRegistry = InMemoryToolRegistry(),
        sessionStore = sessions,
        checkpointStore = InMemoryCheckpointStore(),
        contextManager = contextManager,
    )

    private fun request(
        sessionId: AgentSessionId = AgentSessionId("semantic-compaction-session"),
        messages: List<AgentMessage>,
        contextWindowTokens: Long? = null,
        providerConfig: ProviderConfig = ProviderConfig(maxTokens = 20),
    ) = AgentRequest(
        sessionId = sessionId,
        messages = messages,
        model = ModelDescriptor(
            provider = PROVIDER,
            model = MODEL,
            contextWindowTokens = contextWindowTokens,
        ),
        engine = AgentEngineConfig(
            provider = providerConfig,
            runtime = RuntimeConfig(
                contextManagement = ContextManagementConfig(
                    reserveTokens = 40,
                    keepRecentTokens = 60,
                    summaryMaxTokens = 32,
                    charsPerTokenEstimate = 4,
                ),
            ),
        ),
    )

    private fun longHistory() = (1..5).map { index ->
        message("u$index", "turn-$index ${"x".repeat(220)}")
    }

    private fun message(id: String, text: String) = AgentMessage(
        id = id,
        role = MessageRole.USER,
        parts = listOf(TextPart(text)),
    )

    private class SummaryAwareProvider : ProviderAdapter {
        override val key: String = PROVIDER
        val requests = mutableListOf<ProviderRequest>()

        override suspend fun generate(request: ProviderRequest): Flow<ProviderChunk> = flow {
            requests += request
            emit(
                providerChunk(
                    text = if (request.isContextSummary()) {
                        "compacted summary"
                    } else {
                        "answer"
                    },
                    completed = true,
                    usage = if (request.isContextSummary()) {
                        ProviderUsage(inputTokens = 100, outputTokens = 12)
                    } else {
                        ProviderUsage(inputTokens = 50, outputTokens = 5)
                    },
                ),
            )
        }
    }

    private class CompleteProvider : ProviderAdapter {
        override val key: String = PROVIDER

        override suspend fun generate(request: ProviderRequest): Flow<ProviderChunk> = flow {
            emit(providerChunk(text = "answer", completed = true))
        }
    }

    private class OverflowThenCompleteProvider : ProviderAdapter {
        override val key: String = PROVIDER
        val requests = mutableListOf<ProviderRequest>()
        private var modelCalls = 0

        override suspend fun generate(request: ProviderRequest): Flow<ProviderChunk> = flow {
            requests += request
            if (request.isContextSummary()) {
                emit(providerChunk(text = "overflow recovery summary", completed = true))
                return@flow
            }
            modelCalls += 1
            if (modelCalls == 1) throw ProviderContextLimitException()
            emit(providerChunk(text = "recovered answer", completed = true))
        }
    }

    private class PartialThenOverflowProvider : ProviderAdapter {
        override val key: String = PROVIDER
        val requests = mutableListOf<ProviderRequest>()

        override suspend fun generate(request: ProviderRequest): Flow<ProviderChunk> = flow {
            requests += request
            emit(providerChunk(text = "partial", completed = false))
            throw ProviderContextLimitException()
        }
    }

    private class RecordingContextManager(
        private val stateToReturn: ContextManagementState? = null,
    ) : saien.magrathea.core.ContextManager {
        val seenStates = mutableListOf<ContextManagementState>()

        override suspend fun prepare(
            request: saien.magrathea.core.ContextPreparationRequest,
        ): ContextPreparationResult {
            seenStates += request.state.contextManagement
            return ContextPreparationResult(
                messages = request.state.messages,
                state = stateToReturn ?: request.state.contextManagement,
                estimatedInputTokens = null,
                inputLimitTokens = null,
                action = ContextPreparationAction.UNCHANGED,
            )
        }
    }

    private companion object {
        const val PROVIDER = "openai"
        const val MODEL = "context-model"
    }
}

private fun ProviderRequest.isContextSummary(): Boolean =
    invocation?.requestId?.contains(":context-summary:") == true
