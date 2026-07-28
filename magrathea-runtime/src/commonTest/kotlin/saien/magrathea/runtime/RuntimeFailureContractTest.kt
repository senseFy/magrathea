package saien.magrathea.runtime

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import saien.magrathea.core.AgentEvent
import saien.magrathea.core.AgentFailureCode
import saien.magrathea.core.AgentMessage
import saien.magrathea.core.AgentRequest
import saien.magrathea.core.AgentSessionId
import saien.magrathea.core.AgentSessionSnapshot
import saien.magrathea.core.AgentStateSnapshot
import saien.magrathea.core.AgentStatus
import saien.magrathea.core.MessageRole
import saien.magrathea.core.ModelDescriptor
import saien.magrathea.core.RetryPolicy
import saien.magrathea.core.SessionStore
import saien.magrathea.core.StopReason
import saien.magrathea.core.TextPart
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
            ProviderRateLimitException(canary) to AgentFailureCode.PROVIDER_RATE_LIMIT,
            ProviderTimeoutException(ProviderTimeoutPhase.STREAM_IDLE) to AgentFailureCode.TIMEOUT,
            ProviderNetworkException(canary) to AgentFailureCode.PROVIDER_NETWORK,
            ProviderProtocolException(canary) to AgentFailureCode.PROVIDER_PROTOCOL,
            ProviderContextLimitException(canary) to AgentFailureCode.CONTEXT_LIMIT,
            ProviderClientException(canary, statusCode = 400) to AgentFailureCode.PROVIDER_CLIENT,
            ProviderServerException(canary, statusCode = 500) to AgentFailureCode.PROVIDER_SERVER,
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
    fun retryAndTerminalFailureNeverExposeThrowableMessage() = runTest {
        val canary = "retry-secret-canary"
        val provider = ThrowingProvider("retry-failure", ProviderNetworkException(canary))
        val events = runner(provider, retryPolicy = RetryOnce).run(request(provider.key)).toList()

        assertEquals(AgentFailureCode.PROVIDER_NETWORK, events.filterIsInstance<AgentEvent.RetryScheduled>().single().code)
        assertEquals(AgentFailureCode.PROVIDER_NETWORK, events.filterIsInstance<AgentEvent.Failed>().single().code)
        assertFalse(events.toString().contains(canary))
    }

    @Test
    fun storageFailureIsClassifiedWithoutRenderingStoreMessage() = runTest {
        val canary = "storage-secret-canary"
        val provider = ThrowingProvider("unused-provider", IllegalStateException("must not run"))
        val store = object : SessionStore {
            override suspend fun saveSession(snapshot: AgentSessionSnapshot) {
                error(canary)
            }

            override suspend fun loadSession(sessionId: AgentSessionId): AgentSessionSnapshot? = null

            override suspend fun listSessions(): List<AgentSessionSnapshot> = emptyList()

            override suspend fun deleteSession(sessionId: AgentSessionId) = Unit

            override suspend fun clear() = Unit
        }
        val runner = DefaultAgentRunner(
            providerRegistry = InMemoryProviderRegistry(listOf(provider)),
            toolRegistry = InMemoryToolRegistry(),
            sessionStore = store,
            checkpointStore = InMemoryCheckpointStore(),
            dispatcher = Dispatchers.Unconfined,
        )

        val events = runner.run(request(provider.key)).toList()

        assertEquals(AgentFailureCode.STORAGE, events.filterIsInstance<AgentEvent.Failed>().single().code)
        assertFalse(events.toString().contains(canary))
    }

    @Test
    fun resumeTerminalPersistenceFailure_isReturnedAsTypedStorageFailure() = runTest {
        val canary = "resume-storage-secret-canary"
        val sessionId = AgentSessionId("resume-storage-failure")
        val request = request("unused-provider").copy(sessionId = sessionId)
        val snapshot = AgentSessionSnapshot(
            sessionId = sessionId,
            request = request,
            state = AgentStateSnapshot(
                messages = request.messages,
                status = AgentStatus.RUNNING,
                stopReason = StopReason.COMPLETED,
            ),
        )
        val store = object : SessionStore {
            override suspend fun saveSession(snapshot: AgentSessionSnapshot) {
                error(canary)
            }

            override suspend fun loadSession(sessionId: AgentSessionId): AgentSessionSnapshot = snapshot
            override suspend fun listSessions(): List<AgentSessionSnapshot> = listOf(snapshot)
            override suspend fun deleteSession(sessionId: AgentSessionId) = Unit
            override suspend fun clear() = Unit
        }
        val runner = DefaultAgentRunner(
            providerRegistry = InMemoryProviderRegistry(),
            toolRegistry = InMemoryToolRegistry(),
            sessionStore = store,
            checkpointStore = InMemoryCheckpointStore(),
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
        sessionStore = InMemorySessionStore(),
        checkpointStore = InMemoryCheckpointStore(),
        retryPolicy = retryPolicy,
        dispatcher = Dispatchers.Unconfined,
    )

    private fun request(provider: String) = AgentRequest(
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

    private object RetryOnce : RetryPolicy {
        private var decisions = 0

        override suspend fun shouldRetry(attempt: Int, error: Throwable): Boolean = decisions++ == 0

        override suspend fun backoffDelayMs(attempt: Int): Long = 0
    }
}
