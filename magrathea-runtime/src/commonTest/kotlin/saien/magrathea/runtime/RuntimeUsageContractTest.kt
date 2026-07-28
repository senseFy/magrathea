package saien.magrathea.runtime

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.Test
import saien.magrathea.core.AgentEvent
import saien.magrathea.core.AgentFailureCode
import saien.magrathea.core.AgentMessage
import saien.magrathea.core.AgentRequest
import saien.magrathea.core.AgentStateSnapshot
import saien.magrathea.core.MessageRole
import saien.magrathea.core.ModelDescriptor
import saien.magrathea.core.TextPart
import saien.magrathea.core.TokenUsage
import saien.magrathea.provider.api.InMemoryProviderRegistry
import saien.magrathea.provider.api.ProviderAdapter
import saien.magrathea.provider.api.ProviderChunk
import saien.magrathea.provider.api.ProviderEvent
import saien.magrathea.provider.api.ProviderRequest
import saien.magrathea.provider.api.ProviderUsage

class RuntimeUsageContractTest {
    @Test
    fun runtimeRejectsProviderFlowWithoutCompletedEvent() = runTest {
        val runner = runner(NonTerminalProvider())
        val events = runner.run(request(NonTerminalProvider.KEY)).toList()

        val failure = events.filterIsInstance<AgentEvent.Failed>().single()
        assertEquals(AgentFailureCode.PROVIDER_PROTOCOL, failure.code)
    }

    @Test
    fun runtimeRejectsEmptyProviderChunk() = runTest {
        val events = runner(EmptyChunkProvider()).run(request(EmptyChunkProvider.KEY)).toList()

        val failure = events.filterIsInstance<AgentEvent.Failed>().single()
        assertEquals(AgentFailureCode.PROVIDER_PROTOCOL, failure.code)
    }

    @Test
    fun runtimeRejectsChunkAfterCompletedEvent() = runTest {
        val events = runner(PostTerminalProvider()).run(request(PostTerminalProvider.KEY)).toList()

        val failure = events.filterIsInstance<AgentEvent.Failed>().single()
        assertEquals(AgentFailureCode.PROVIDER_PROTOCOL, failure.code)
    }

    @Test
    fun usageEvents_preserveKnownDimensionsAcrossChunks() = runTest {
        val state = run(IncrementalUsageProvider())

        assertEquals(TokenUsage(inputTokens = 10, outputTokens = 4), state.usage)
        assertEquals(TokenUsage(inputTokens = 10, outputTokens = 4), state.latestRequestUsage)
    }

    @Test
    fun canonicalTerminalUsage_isAuthoritativeForTheTurn() = runTest {
        val state = run(CanonicalUsageProvider())

        assertEquals(TokenUsage(inputTokens = 5, outputTokens = 3), state.usage)
        assertEquals(TokenUsage(inputTokens = 5, outputTokens = 3), state.latestRequestUsage)
    }

    private suspend fun run(provider: ProviderAdapter): AgentStateSnapshot {
        val events = runner(provider).run(request(provider.key)).toList()
        return events.filterIsInstance<AgentEvent.Completed>().single().state
    }

    private fun runner(provider: ProviderAdapter) = DefaultAgentRunner(
            providerRegistry = InMemoryProviderRegistry(listOf(provider)),
            toolRegistry = InMemoryToolRegistry(),
            sessionStore = InMemorySessionStore(),
            checkpointStore = InMemoryCheckpointStore(),
            dispatcher = Dispatchers.Unconfined,
        )

    private fun request(provider: String) = AgentRequest(
        messages = listOf(AgentMessage(role = MessageRole.USER, parts = listOf(TextPart("hello")))),
        model = ModelDescriptor(provider = provider, model = "usage-contract"),
    )

    private class IncrementalUsageProvider : ProviderAdapter {
        override val key: String = "incremental-usage"

        override suspend fun generate(request: ProviderRequest): Flow<ProviderChunk> = flow {
            emit(
                ProviderChunk(
                    events = listOf(
                        ProviderEvent.TextStart(),
                        ProviderEvent.TextDelta("done"),
                        ProviderEvent.UsageDelta(ProviderUsage(inputTokens = 10)),
                    ),
                ),
            )
            emit(
                ProviderChunk(
                    events = listOf(
                        ProviderEvent.TextEnd(),
                        ProviderEvent.Completed(
                            finishReason = "STOP",
                            usage = ProviderUsage(outputTokens = 4),
                        ),
                    ),
                ),
            )
        }
    }

    private class CanonicalUsageProvider : ProviderAdapter {
        override val key: String = "canonical-usage"

        override suspend fun generate(request: ProviderRequest): Flow<ProviderChunk> = flow {
            emit(
                ProviderChunk(
                    events = listOf(
                        ProviderEvent.TextStart(),
                        ProviderEvent.TextDelta("done"),
                        ProviderEvent.UsageDelta(ProviderUsage(inputTokens = 1, outputTokens = 1)),
                    ),
                ),
            )
            emit(
                ProviderChunk(
                    events = listOf(
                        ProviderEvent.TextEnd(),
                        ProviderEvent.Completed(
                            finishReason = "STOP",
                            usage = ProviderUsage(inputTokens = 5, outputTokens = 3),
                        ),
                    ),
                ),
            )
        }
    }

    private class NonTerminalProvider : ProviderAdapter {
        override val key: String = KEY

        override suspend fun generate(request: ProviderRequest): Flow<ProviderChunk> = flow {
            emit(
                ProviderChunk(
                    events = listOf(ProviderEvent.TextDelta("partial")),
                ),
            )
        }

        companion object {
            const val KEY = "non-terminal"
        }
    }

    private class EmptyChunkProvider : ProviderAdapter {
        override val key: String = KEY

        override suspend fun generate(request: ProviderRequest): Flow<ProviderChunk> = flow {
            emit(ProviderChunk())
        }

        companion object {
            const val KEY = "empty-chunk"
        }
    }

    private class PostTerminalProvider : ProviderAdapter {
        override val key: String = KEY

        override suspend fun generate(request: ProviderRequest): Flow<ProviderChunk> = flow {
            emit(ProviderChunk(events = listOf(ProviderEvent.Completed(finishReason = "STOP"))))
            emit(ProviderChunk(events = listOf(ProviderEvent.TextDelta("late"))))
        }

        companion object {
            const val KEY = "post-terminal"
        }
    }
}
