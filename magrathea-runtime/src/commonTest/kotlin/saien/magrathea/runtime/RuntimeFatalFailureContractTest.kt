package saien.magrathea.runtime

import kotlin.test.Test
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import saien.magrathea.core.AgentCheckpoint
import saien.magrathea.core.AgentEvent
import saien.magrathea.core.AgentMessage
import saien.magrathea.core.AgentPersistence
import saien.magrathea.core.AgentPersistenceRecord
import saien.magrathea.core.AgentRequest
import saien.magrathea.core.AgentSessionId
import saien.magrathea.core.AgentSessionSnapshot
import saien.magrathea.core.MessageRole
import saien.magrathea.core.ModelDescriptor
import saien.magrathea.core.RetryPolicy
import saien.magrathea.core.TextPart
import saien.magrathea.provider.api.InMemoryProviderRegistry
import saien.magrathea.provider.api.ProviderAdapter
import saien.magrathea.provider.api.ProviderChunk
import saien.magrathea.provider.api.ProviderRequest

class RuntimeFatalFailureContractTest {
    @Test
    fun directProviderErrorEscapesAndDoesNotPoisonTheRunner() = runTest {
        assertProviderFailureContract { fatal -> fatal }
    }

    @Test
    fun wrappedProviderErrorEscapesAndDoesNotPoisonTheRunner() = runTest {
        assertProviderFailureContract { fatal -> TestRecoverableException(fatal) }
    }

    @Test
    fun directPersistenceErrorEscapesAndDoesNotPoisonTheRunner() = runTest {
        assertPersistenceFailureContract { fatal -> fatal }
    }

    @Test
    fun wrappedPersistenceErrorEscapesAndDoesNotPoisonTheRunner() = runTest {
        assertPersistenceFailureContract { fatal -> TestRecoverableException(fatal) }
    }

    @Test
    fun wrappedFatalAfterTerminalProviderEventStillEscapesExactly() = runTest {
        val fatal = TestFatalError(Any())
        val provider = TerminalThenFailOnceProvider(fatal)
        val runner = DefaultAgentRunner(
            providerRegistry = InMemoryProviderRegistry(listOf(provider)),
            toolRegistry = InMemoryToolRegistry(),
            persistence = InMemoryAgentPersistence(),
        )
        val request = request(provider.key, "post-terminal-fatal")

        val escaped = runCatching { runner.run(request).toList() }.exceptionOrNull()

        assertSame(fatal, escaped)
        assertTrue(runner.run(request).toList().any { event -> event is AgentEvent.Completed })
    }

    @Test
    fun wrappedRetryPolicyFatalEscapesExactly() = runTest {
        val policyFatal = TestFatalError(Any())
        val provider = AlwaysFailingProvider()
        val runner = DefaultAgentRunner(
            providerRegistry = InMemoryProviderRegistry(listOf(provider)),
            toolRegistry = InMemoryToolRegistry(),
            persistence = InMemoryAgentPersistence(),
            retryPolicy = object : RetryPolicy {
                override suspend fun shouldRetry(attempt: Int, error: Throwable): Boolean =
                    throw TestRecoverableException(policyFatal)

                override suspend fun backoffDelayMs(attempt: Int, error: Throwable): Long = 0L
            },
        )

        val escaped = runCatching {
            runner.run(request(provider.key, "retry-policy-fatal")).toList()
        }.exceptionOrNull()

        assertSame(policyFatal, escaped)
    }

    private suspend fun assertProviderFailureContract(
        failure: (TestFatalError) -> Throwable,
    ) {
        val fatal = TestFatalError(Any())
        val provider = FailOnceProvider(failure(fatal))
        val runner = DefaultAgentRunner(
            providerRegistry = InMemoryProviderRegistry(listOf(provider)),
            toolRegistry = InMemoryToolRegistry(),
            persistence = InMemoryAgentPersistence(),
        )
        val request = request(provider.key, "provider-fatal")
        val observed = mutableListOf<AgentEvent>()

        val escaped = runCatching { runner.run(request).toList(observed) }.exceptionOrNull()

        assertSame(fatal, escaped)
        assertTrue(observed.none { event -> event is AgentEvent.Failed })
        assertTrue(runner.run(request).toList().any { event -> event is AgentEvent.Completed })
    }

    private suspend fun assertPersistenceFailureContract(
        failure: (TestFatalError) -> Throwable,
    ) {
        val fatal = TestFatalError(Any())
        val provider = CompletingProvider()
        val runner = DefaultAgentRunner(
            providerRegistry = InMemoryProviderRegistry(listOf(provider)),
            toolRegistry = InMemoryToolRegistry(),
            persistence = FailOncePersistence(failure(fatal)),
        )
        val request = request(provider.key, "persistence-fatal")
        val observed = mutableListOf<AgentEvent>()

        val escaped = runCatching { runner.run(request).toList(observed) }.exceptionOrNull()

        assertSame(fatal, escaped)
        assertTrue(observed.none { event -> event is AgentEvent.Failed })
        assertTrue(runner.run(request).toList().any { event -> event is AgentEvent.Completed })
    }

    private fun request(provider: String, suffix: String): AgentRequest = AgentRequest(
        sessionId = AgentSessionId("$suffix-session"),
        messages = listOf(
            AgentMessage(
                role = MessageRole.USER,
                parts = listOf(TextPart("hello")),
            ),
        ),
        model = ModelDescriptor(provider = provider, model = "test-model"),
    )
}

private class FailOnceProvider(
    private val failure: Throwable,
) : ProviderAdapter {
    override val key: String = "fatal-once"
    private var calls = 0

    override suspend fun generate(request: ProviderRequest): Flow<ProviderChunk> = flow {
        if (calls++ == 0) throw failure
        emit(providerChunk(text = "recovered", completed = true))
    }
}

private class CompletingProvider : ProviderAdapter {
    override val key: String = "complete"

    override suspend fun generate(request: ProviderRequest): Flow<ProviderChunk> = flow {
        emit(providerChunk(text = "completed", completed = true))
    }
}

private class TerminalThenFailOnceProvider(
    private val fatal: TestFatalError,
) : ProviderAdapter {
    override val key: String = "terminal-then-fatal"
    private var calls = 0

    override suspend fun generate(request: ProviderRequest): Flow<ProviderChunk> = flow {
        emit(providerChunk(text = "completed", completed = true))
        if (calls++ == 0) {
            throw saien.magrathea.provider.api.ProviderNetworkException(
                "late provider failure",
                fatal,
            )
        }
    }
}

private class AlwaysFailingProvider : ProviderAdapter {
    override val key: String = "always-failing"

    override suspend fun generate(request: ProviderRequest): Flow<ProviderChunk> = flow {
        throw saien.magrathea.provider.api.ProviderNetworkException("network failure")
    }
}

private class FailOncePersistence(
    private val failure: Throwable,
    private val delegate: InMemoryAgentPersistence = InMemoryAgentPersistence(),
) : AgentPersistence {
    private var failed = false

    override suspend fun commit(snapshot: AgentSessionSnapshot, checkpoint: AgentCheckpoint?) {
        if (!failed) {
            failed = true
            throw failure
        }
        delegate.commit(snapshot, checkpoint)
    }

    override suspend fun load(sessionId: AgentSessionId): AgentPersistenceRecord? =
        delegate.load(sessionId)

    override suspend fun listSessions(): List<AgentSessionSnapshot> = delegate.listSessions()

    override suspend fun deleteSession(sessionId: AgentSessionId) =
        delegate.deleteSession(sessionId)

    override suspend fun clear() = delegate.clear()
}
