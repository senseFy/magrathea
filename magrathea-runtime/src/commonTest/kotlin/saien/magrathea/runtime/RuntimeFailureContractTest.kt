package saien.magrathea.runtime

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import saien.magrathea.core.AgentEvent
import saien.magrathea.core.AgentFailureCode
import saien.magrathea.core.AgentInterruptionReason
import saien.magrathea.core.AgentMessage
import saien.magrathea.core.AgentPersistence
import saien.magrathea.core.AgentPersistenceRecord
import saien.magrathea.core.AgentRequest
import saien.magrathea.core.AgentSessionId
import saien.magrathea.core.AgentSessionSnapshot
import saien.magrathea.core.AgentCheckpoint
import saien.magrathea.core.AgentStatus
import saien.magrathea.core.MessageRole
import saien.magrathea.core.ModelDescriptor
import saien.magrathea.core.ProviderInterruptionPhase
import saien.magrathea.core.RetryPolicy
import saien.magrathea.core.TextPart
import saien.magrathea.core.TraceStatus
import saien.magrathea.provider.api.InMemoryProviderRegistry
import saien.magrathea.provider.api.ProviderAdapter
import saien.magrathea.provider.api.ProviderAuthException
import saien.magrathea.provider.api.ProviderChunk
import saien.magrathea.provider.api.ProviderClientException
import saien.magrathea.provider.api.ProviderContextLimitException
import saien.magrathea.provider.api.ProviderException
import saien.magrathea.provider.api.ProviderNetworkException
import saien.magrathea.provider.api.ProviderProtocolException
import saien.magrathea.provider.api.ProviderRateLimitException
import saien.magrathea.provider.api.ProviderRequest
import saien.magrathea.provider.api.ProviderServerException
import saien.magrathea.provider.api.ProviderTimeoutException
import saien.magrathea.provider.api.ProviderTimeoutPhase

class RuntimeFailureContractTest {
    @Test
    fun providerAndUnknownFailuresExposeOnlyTypedCodes() = runTest {
        val canary = "runtime-failure-secret-canary"
        val cases = listOf(
            ProviderAuthException(canary) to AgentFailureCode.PROVIDER_AUTH,
            ProviderProtocolException(canary) to AgentFailureCode.PROVIDER_PROTOCOL,
            ProviderContextLimitException(canary) to AgentFailureCode.CONTEXT_LIMIT,
            ProviderClientException(canary, statusCode = 400) to AgentFailureCode.PROVIDER_CLIENT,
            ProviderException(canary) to AgentFailureCode.PROVIDER_SERVER,
            IllegalStateException(canary) to AgentFailureCode.INTERNAL,
        )

        cases.forEachIndexed { index, (throwable, expectedCode) ->
            val provider = ThrowingProvider("failure-$index", throwable)
            val events = runner(provider).run(request(provider.key)).toList()

            assertEquals(expectedCode, events.filterIsInstance<AgentEvent.Failed>().single().code)
            assertFalse(events.toString().contains(canary))
        }
    }

    @Test
    fun exhaustedTransientProviderFailuresBecomeTypedResumableInterruptions() = runTest {
        val canary = "interruption-secret-canary"
        val cases = listOf(
            ProviderTimeoutException(ProviderTimeoutPhase.STREAM_IDLE) to AgentFailureCode.TIMEOUT,
            ProviderNetworkException(canary) to AgentFailureCode.PROVIDER_NETWORK,
            ProviderRateLimitException(canary) to AgentFailureCode.PROVIDER_RATE_LIMIT,
            ProviderServerException(canary, statusCode = 503) to AgentFailureCode.PROVIDER_SERVER,
        )

        cases.forEachIndexed { index, (throwable, code) ->
            val provider = ThrowingProvider("interruption-$index", throwable)
            val events = runner(provider).run(request(provider.key)).toList()

            val interruption = events.filterIsInstance<AgentEvent.Interrupted>().single().interruption
            assertEquals(AgentInterruptionReason.PROVIDER_FAILURE, interruption.reason)
            assertEquals(code, interruption.provider?.code)
            assertEquals(ProviderInterruptionPhase.BEFORE_FIRST_EVENT, interruption.provider?.phase)
            assertFalse(events.toString().contains(canary))
        }
    }

    @Test
    fun retryAndTerminalInterruptionNeverExposeThrowableMessage() = runTest {
        val canary = "retry-secret-canary"
        val provider = ThrowingProvider("retry-failure", ProviderNetworkException(canary))
        val events = runner(provider, retryPolicy = RetryOncePolicy()).run(request(provider.key)).toList()

        assertEquals(AgentFailureCode.PROVIDER_NETWORK, events.filterIsInstance<AgentEvent.RetryScheduled>().single().code)
        val interruption = events.filterIsInstance<AgentEvent.Interrupted>().single().interruption
        assertEquals(AgentInterruptionReason.PROVIDER_FAILURE, interruption.reason)
        assertEquals(AgentFailureCode.PROVIDER_NETWORK, interruption.provider?.code)
        assertFalse(events.toString().contains(canary))
    }

    @Test
    fun providerRetryAfterBecomesAnAbsoluteRecoveryHint() = runTest {
        val provider = ThrowingProvider(
            "rate-limit-retry-after",
            ProviderRateLimitException("rate limited", retryAfterMillis = 2_500L),
        )

        val interruption = runner(provider)
            .run(request(provider.key))
            .toList()
            .filterIsInstance<AgentEvent.Interrupted>()
            .single()
            .interruption

        assertEquals(
            interruption.occurredAtEpochMs + 2_500L,
            interruption.provider?.retryAtEpochMs,
        )
    }

    @Test
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun providerRetryAfterIsAMinimumDelayForFreshRetries() = runTest {
        val provider = object : ProviderAdapter {
            override val key: String = "retry-after-minimum"
            var calls = 0

            override suspend fun generate(request: ProviderRequest): Flow<ProviderChunk> = flow {
                calls += 1
                if (calls == 1) {
                    throw ProviderRateLimitException(
                        "rate limited",
                        retryAfterMillis = 2_500L,
                    )
                }
                emit(providerChunk(text = "recovered", completed = true))
            }
        }
        val runner = DefaultAgentRunner(
            providerRegistry = InMemoryProviderRegistry(listOf(provider)),
            toolRegistry = InMemoryToolRegistry(),
            persistence = InMemoryAgentPersistence(),
            retryPolicy = RetryOncePolicy(),
            dispatcher = StandardTestDispatcher(testScheduler),
        )

        val events = runner.run(request(provider.key)).toList()

        assertEquals(2_500L, currentTime)
        assertEquals(1, events.filterIsInstance<AgentEvent.Completed>().size)
    }

    @Test
    fun transientFailureAfterOutputNeverFreshRetries() = runTest {
        val failures = listOf(
            ProviderRateLimitException("rate limited"),
            ProviderServerException("unavailable", statusCode = 503),
        )

        failures.forEachIndexed { index, failure ->
            val provider = PartialThenThrowingProvider("partial-transient-$index", failure)
            val retryPolicy = RecordingRetryPolicy()

            val events = runner(provider, retryPolicy)
                .run(request(provider.key))
                .toList()

            val interruption = events.filterIsInstance<AgentEvent.Interrupted>()
                .single()
                .interruption
            assertEquals(ProviderInterruptionPhase.AFTER_FIRST_EVENT, interruption.provider?.phase)
            assertEquals(1, provider.calls)
            assertEquals(0, retryPolicy.decisions)
            assertTrue(events.any { it is AgentEvent.MessageEmitted })
        }
    }

    @Test
    fun semanticCompletionIsAuthoritativeOverALaterNetworkFailure() = runTest {
        val provider = CompleteThenFailProvider(
            key = "completed-then-network",
            failure = ProviderNetworkException("late disconnect"),
        )

        val events = runner(provider).run(request(provider.key)).toList()

        assertEquals(1, events.filterIsInstance<AgentEvent.Completed>().size)
        assertTrue(events.none { it is AgentEvent.Interrupted || it is AgentEvent.Failed })
    }

    @Test
    fun canonicalEventAfterCompletionRemainsAProtocolFailure() = runTest {
        val provider = object : ProviderAdapter {
            override val key: String = "event-after-completed"

            override suspend fun generate(request: ProviderRequest): Flow<ProviderChunk> = flow {
                emit(providerChunk(text = "done", completed = true))
                emit(providerChunk(text = "unexpected"))
            }
        }

        val events = runner(provider).run(request(provider.key)).toList()

        assertEquals(
            AgentFailureCode.PROVIDER_PROTOCOL,
            events.filterIsInstance<AgentEvent.Failed>().single().code,
        )
    }

    @Test
    fun nonRecoverableFailuresNeverConsultRetryPolicy() = runTest {
        val cases = listOf(
            ProviderAuthException("auth"),
            ProviderClientException("client", statusCode = 400),
            ProviderProtocolException("protocol"),
            IllegalStateException("internal"),
        )

        cases.forEachIndexed { index, failure ->
            val provider = ThrowingProvider("non-recoverable-$index", failure)
            val retryPolicy = RecordingRetryPolicy()

            val events = runner(provider, retryPolicy).run(request(provider.key)).toList()

            assertEquals(1, events.filterIsInstance<AgentEvent.Failed>().size)
            assertEquals(0, retryPolicy.decisions)
        }
    }

    @Test
    fun rejectedAndNonRecoverableAttemptsDoNotIncreaseRetryCount() = runTest {
        val cases = listOf(
            ThrowingProvider("retry-rejected", ProviderNetworkException("offline")),
            ThrowingProvider("not-retryable", ProviderAuthException("invalid credential")),
        )

        cases.forEachIndexed { index, provider ->
            val sessionId = AgentSessionId("no-started-retry-$index")
            val persistence = InMemoryAgentPersistence()
            val runner = DefaultAgentRunner(
                providerRegistry = InMemoryProviderRegistry(listOf(provider)),
                toolRegistry = InMemoryToolRegistry(),
                persistence = persistence,
                dispatcher = Dispatchers.Unconfined,
            )

            runner.run(request(provider.key, sessionId)).toList()

            assertEquals(
                0,
                assertNotNull(persistence.load(sessionId)).snapshot.state.retryCount,
            )
        }
    }

    @Test
    fun interruptionCommitFailureBecomesTypedStorageFailure() = runTest {
        val provider = ThrowingProvider(
            "interruption-storage-failure",
            ProviderNetworkException("offline"),
        )
        val traceSink = RecordingTraceSink()
        val persistence = RejectInterruptedPersistence()
        val runner = DefaultAgentRunner(
            providerRegistry = InMemoryProviderRegistry(listOf(provider)),
            toolRegistry = InMemoryToolRegistry(),
            persistence = persistence,
            tracer = traceSink.tracer(),
            dispatcher = Dispatchers.Unconfined,
        )

        val events = runner.run(request(provider.key)).toList()

        assertEquals(
            AgentFailureCode.STORAGE,
            events.filterIsInstance<AgentEvent.Failed>().single().code,
        )
        assertTrue(events.none { it is AgentEvent.Interrupted })
        val execution = traceSink.spans.single { it.name == RuntimeTraceNames.AGENT_EXECUTION }
        assertEquals(TraceStatus.ERROR, execution.status)
        assertEquals(
            AgentFailureCode.STORAGE.name,
            execution.stringAttribute("magrathea.error.code"),
        )
    }

    @Test
    fun storageFailureIsClassifiedWithoutRenderingStoreMessage() = runTest {
        val canary = "storage-secret-canary"
        val provider = ThrowingProvider("unused-provider", IllegalStateException("must not run"))
        val persistence = object : AgentPersistence {
            override suspend fun commit(
                snapshot: AgentSessionSnapshot,
                checkpoint: AgentCheckpoint?,
            ) {
                error(canary)
            }

            override suspend fun load(sessionId: AgentSessionId): AgentPersistenceRecord? = null
            override suspend fun listSessions(): List<AgentSessionSnapshot> = emptyList()
            override suspend fun deleteSession(sessionId: AgentSessionId) = Unit
            override suspend fun clear() = Unit
        }
        val runner = DefaultAgentRunner(
            providerRegistry = InMemoryProviderRegistry(listOf(provider)),
            toolRegistry = InMemoryToolRegistry(),
            persistence = persistence,
            dispatcher = Dispatchers.Unconfined,
        )

        val events = runner.run(request(provider.key)).toList()

        assertEquals(AgentFailureCode.STORAGE, events.filterIsInstance<AgentEvent.Failed>().single().code)
        assertFalse(events.toString().contains(canary))
    }

    @Test
    fun resumeLoadFailure_isReturnedAsTypedStorageFailure() = runTest {
        val canary = "resume-storage-secret-canary"
        val sessionId = AgentSessionId("resume-storage-failure")
        val persistence = object : AgentPersistence {
            override suspend fun commit(
                snapshot: AgentSessionSnapshot,
                checkpoint: AgentCheckpoint?,
            ) = Unit

            override suspend fun load(sessionId: AgentSessionId): AgentPersistenceRecord? {
                error(canary)
            }

            override suspend fun listSessions(): List<AgentSessionSnapshot> = emptyList()
            override suspend fun deleteSession(sessionId: AgentSessionId) = Unit
            override suspend fun clear() = Unit
        }
        val runner = DefaultAgentRunner(
            providerRegistry = InMemoryProviderRegistry(),
            toolRegistry = InMemoryToolRegistry(),
            persistence = persistence,
            dispatcher = Dispatchers.Unconfined,
        )

        val events = runner.resume(sessionId).toList()

        assertEquals(AgentFailureCode.STORAGE, events.filterIsInstance<AgentEvent.Failed>().single().code)
        assertFalse(events.toString().contains(canary))
    }

    private fun runner(
        provider: ProviderAdapter,
        retryPolicy: RetryPolicy = saien.magrathea.core.NoopRetryPolicy,
    ) = DefaultAgentRunner(
        providerRegistry = InMemoryProviderRegistry(listOf(provider)),
        toolRegistry = InMemoryToolRegistry(),
        persistence = InMemoryAgentPersistence(),
        retryPolicy = retryPolicy,
        dispatcher = Dispatchers.Unconfined,
    )

    private fun request(
        provider: String,
        sessionId: AgentSessionId = AgentSessionId.create(),
    ) = AgentRequest(
        sessionId = sessionId,
        messages = listOf(AgentMessage(role = MessageRole.USER, parts = listOf(TextPart("hello")))),
        model = ModelDescriptor(provider = provider, model = "failure-contract"),
    )

    private class ThrowingProvider(
        override val key: String,
        private val failure: Throwable,
    ) : ProviderAdapter {
        override suspend fun generate(request: ProviderRequest): Flow<ProviderChunk> = flow {
            throw failure
        }
    }

    private class PartialThenThrowingProvider(
        override val key: String,
        private val failure: Throwable,
    ) : ProviderAdapter {
        var calls: Int = 0

        override suspend fun generate(request: ProviderRequest): Flow<ProviderChunk> = flow {
            calls += 1
            emit(providerChunk(text = "partial"))
            throw failure
        }
    }

    private class CompleteThenFailProvider(
        override val key: String,
        private val failure: Throwable,
    ) : ProviderAdapter {
        override suspend fun generate(request: ProviderRequest): Flow<ProviderChunk> = flow {
            emit(providerChunk(text = "complete", completed = true))
            throw failure
        }
    }

    private class RejectInterruptedPersistence(
        private val delegate: InMemoryAgentPersistence = InMemoryAgentPersistence(),
    ) : AgentPersistence {
        override suspend fun commit(
            snapshot: AgentSessionSnapshot,
            checkpoint: AgentCheckpoint?,
        ) {
            if (snapshot.state.status == AgentStatus.INTERRUPTED) error("storage unavailable")
            delegate.commit(snapshot, checkpoint)
        }

        override suspend fun load(sessionId: AgentSessionId): AgentPersistenceRecord? =
            delegate.load(sessionId)

        override suspend fun listSessions(): List<AgentSessionSnapshot> = delegate.listSessions()

        override suspend fun deleteSession(sessionId: AgentSessionId) = delegate.deleteSession(sessionId)

        override suspend fun clear() = delegate.clear()
    }

    private class RecordingRetryPolicy : RetryPolicy {
        var decisions: Int = 0

        override suspend fun shouldRetry(attempt: Int, error: Throwable): Boolean {
            decisions += 1
            return true
        }

        override suspend fun backoffDelayMs(attempt: Int, error: Throwable): Long = 0L
    }

    private class RetryOncePolicy : RetryPolicy {
        private var decisions = 0

        override suspend fun shouldRetry(attempt: Int, error: Throwable): Boolean = decisions++ == 0

        override suspend fun backoffDelayMs(attempt: Int, error: Throwable): Long = 0
    }
}
