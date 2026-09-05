package saien.magrathea.runtime

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
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
import saien.magrathea.core.MagratheaTracer
import saien.magrathea.core.MessageRole
import saien.magrathea.core.ModelDescriptor
import saien.magrathea.core.NoopMagratheaTracer
import saien.magrathea.core.ProviderConfig
import saien.magrathea.core.ProviderRequestPurpose
import saien.magrathea.core.RetryPolicy
import saien.magrathea.core.RuntimeConfig
import saien.magrathea.core.TextPart
import saien.magrathea.core.TokenUsage
import saien.magrathea.core.TraceStatus
import saien.magrathea.provider.api.InMemoryProviderRegistry
import saien.magrathea.provider.api.OpenAiTransportConfig
import saien.magrathea.provider.api.OpenAiXSearchToolConfig
import saien.magrathea.provider.api.ProviderAdapter
import saien.magrathea.provider.api.ProviderChunk
import saien.magrathea.provider.api.ProviderContextLimitException
import saien.magrathea.provider.api.ProviderInvocationIntent
import saien.magrathea.provider.api.ProviderInvocationInvalidatedException
import saien.magrathea.provider.api.ProviderInvocationResumeMode
import saien.magrathea.provider.api.ProviderNetworkException
import saien.magrathea.provider.api.ProviderRequest
import saien.magrathea.provider.api.ProviderUsage
import saien.magrathea.provider.api.toProviderOptions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ContextManagementRuntimeIntegrationTest {
    @Test
    fun semanticCompaction_usesToolFreeProviderCallAndPersistsOnlyCanonicalHistory() = runTest {
        val provider = SummaryAwareProvider()
        val persistence = InMemoryAgentPersistence()
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
        val runner = runner(provider, persistence)

        val events = runner.run(request).toList()

        val completed = events.filterIsInstance<AgentEvent.Completed>().single()
        assertEquals(2, provider.requests.size)
        val summaryRequest = provider.requests.single { it.isContextSummary() }
        val modelRequest = provider.requests.single { !it.isContextSummary() }
        assertEquals(1, summaryRequest.maxTokens)
        assertEquals(20, modelRequest.maxTokens)
        assertTrue(summaryRequest.tools.isEmpty())
        val summaryTransport = summaryRequest.typedConfig as OpenAiTransportConfig
        assertTrue(summaryTransport.hostedTools.isEmpty())
        assertNull(summaryTransport.maxToolTurns)
        assertTrue(
            modelRequest.messages.first().parts.filterIsInstance<TextPart>().single().text
                .contains("compacted summary"),
        )

        val saved = assertNotNull(persistence.load(request.sessionId)?.snapshot)
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
    fun completedContextSummaryRemainsAuthoritativeAfterALateNetworkFailure() = runTest {
        val provider = SummaryThenDisconnectProvider()
        val request = request(
            sessionId = AgentSessionId("summary-late-disconnect"),
            messages = longHistory(),
            contextWindowTokens = 220,
        )

        val events = runner(provider, InMemoryAgentPersistence()).run(request).toList()

        assertEquals(2, provider.requests.size)
        assertTrue(events.any { it is AgentEvent.Completed })
        assertTrue(events.none { it is AgentEvent.Interrupted || it is AgentEvent.Failed })
    }

    @Test
    fun wrappedFatalAfterCompletedContextSummaryEscapesExactly() = runTest {
        val fatal = TestFatalError(Any())
        val provider = TerminalSummaryThenFatalProvider(fatal)
        val request = request(
            sessionId = AgentSessionId("summary-late-fatal"),
            messages = longHistory(),
            contextWindowTokens = 220,
        )

        val escaped = runCatching {
            runner(provider, InMemoryAgentPersistence()).run(request).toList()
        }.exceptionOrNull()

        assertSame(fatal, escaped)
    }

    @Test
    fun successfulSummaryTracingKeepsRequestUsageSeparateFromSessionCumulativeUsage() = runTest {
        val traceSink = RecordingTraceSink()
        val provider = SummaryAwareProvider()
        val events = runner(
            provider = provider,
            persistence = InMemoryAgentPersistence(),
            tracer = traceSink.tracer(),
        ).run(
            request(
                sessionId = AgentSessionId("summary-success-accounting"),
                messages = longHistory(),
                contextWindowTokens = 220,
            ),
        ).toList()

        val requestFinished = traceSink.spans
            .filter { it.name == RuntimeTraceNames.PROVIDER_REQUEST }
        assertEquals(
            listOf(100L to 12L, 50L to 5L),
            requestFinished.map {
                it.longAttribute("magrathea.usage.input_tokens") to
                    it.longAttribute("magrathea.usage.output_tokens")
            },
        )
        assertTrue(requestFinished.all { it.status == TraceStatus.OK })
        assertEquals(
            listOf("context_summary", "model"),
            requestFinished.map { it.stringAttribute("magrathea.provider.purpose") },
        )
        assertEquals(
            listOf("context_summary", "model"),
            requestFinished.filter {
                it.events.any { event -> event.name == RuntimeTraceEvents.PROVIDER_FIRST_EVENT }
            }.map { it.stringAttribute("magrathea.provider.purpose") },
        )
        val completed = events.filterIsInstance<AgentEvent.Completed>().single().state
        assertEquals(TokenUsage(inputTokens = 150, outputTokens = 17), completed.usage)
        assertEquals(TokenUsage(inputTokens = 50, outputTokens = 5), completed.latestRequestUsage)
        val execution = traceSink.spans
            .single { it.name == RuntimeTraceNames.AGENT_EXECUTION }
        assertEquals(150L, execution.longAttribute("magrathea.usage.input_tokens"))
        assertEquals(17L, execution.longAttribute("magrathea.usage.output_tokens"))

    }

    @Test
    fun failedSummaryStillAccountsObservedUsageBeforeFailingOpen() = runTest {
        val traceSink = RecordingTraceSink()
        val persistence = InMemoryAgentPersistence()
        val provider = SummaryUsageThenFailureProvider()
        val request = request(
            sessionId = AgentSessionId("summary-failure-accounting"),
            messages = longHistory(),
            contextWindowTokens = 220,
        )

        val events = runner(
            provider = provider,
            persistence = persistence,
            tracer = traceSink.tracer(),
        ).run(request).toList()

        val requestFinished = traceSink.spans
            .filter { it.name == RuntimeTraceNames.PROVIDER_REQUEST }
        assertEquals(
            listOf(TraceStatus.ERROR, TraceStatus.OK),
            requestFinished.map { it.status },
        )
        assertEquals(
            listOf("context_summary", "model"),
            requestFinished.map { it.stringAttribute("magrathea.provider.purpose") },
        )
        assertEquals(
            listOf(SUMMARY_USAGE, MODEL_USAGE).map { it.inputTokens to it.outputTokens },
            requestFinished.map {
                it.longAttribute("magrathea.usage.input_tokens") to
                    it.longAttribute("magrathea.usage.output_tokens")
            },
        )
        val completed = events.filterIsInstance<AgentEvent.Completed>().single().state
        assertEquals(CUMULATIVE_USAGE, completed.usage)
        assertEquals(MODEL_USAGE, completed.latestRequestUsage)
        assertEquals(
            CUMULATIVE_USAGE,
            assertNotNull(persistence.load(request.sessionId)).snapshot.state.usage,
        )
        assertNull(completed.contextManagement.compaction)
    }

    @Test
    fun hostInterruptionPreservesObservedSummaryUsageAcrossCheckpointRecovery() = runTest {
        val traceSink = RecordingTraceSink()
        val persistence = InMemoryAgentPersistence()
        val provider = InterruptibleSummaryProvider()
        val runner = runner(provider, persistence, tracer = traceSink.tracer())
        val sessionId = AgentSessionId("summary-host-interruption-accounting")
        val request = request(
            sessionId = sessionId,
            messages = longHistory(),
            contextWindowTokens = 220,
        )
        val collection = launch {
            try {
                runner.run(request).collect()
            } catch (_: CancellationException) {
                // Host interruption remains coroutine cancellation for the active collector.
            }
        }
        provider.firstSummaryUsageObserved.await()

        val recovery = runner.interrupt(sessionId)
        withTimeout(2_000) { collection.join() }

        assertEquals(SUMMARY_USAGE, assertNotNull(recovery.state).usage)
        val persisted = assertNotNull(persistence.load(sessionId))
        assertEquals(SUMMARY_USAGE, persisted.snapshot.state.usage)
        assertEquals(TokenUsage(), assertNotNull(persisted.checkpoint).state.usage)
        assertEquals(TokenUsage(), persisted.snapshot.state.latestRequestUsage)
        val firstRequest = traceSink.spans
            .filter { it.name == RuntimeTraceNames.PROVIDER_REQUEST }
            .single()
        assertEquals(TraceStatus.UNSET, firstRequest.status)
        assertEquals("cancelled", firstRequest.stringAttribute("magrathea.outcome"))
        assertEquals("context_summary", firstRequest.stringAttribute("magrathea.provider.purpose"))
        assertEquals(SUMMARY_USAGE.inputTokens, firstRequest.longAttribute("magrathea.usage.input_tokens"))
        assertEquals(SUMMARY_USAGE.outputTokens, firstRequest.longAttribute("magrathea.usage.output_tokens"))
        val interruptedExecution = traceSink.spans
            .filter { it.name == RuntimeTraceNames.AGENT_EXECUTION }
            .single()
        assertEquals("interrupted", interruptedExecution.stringAttribute("magrathea.outcome"))

        val resumed = runner.resume(sessionId).toList()
            .filterIsInstance<AgentEvent.Completed>()
            .single()
            .state

        assertEquals(RESUMED_CUMULATIVE_USAGE, resumed.usage)
        assertEquals(MODEL_USAGE, resumed.latestRequestUsage)
        assertEquals(3, provider.requests.size)
    }

    @Test
    fun reattachedSummaryReplayUsesTheExactRequestIdentityWithoutDoubleCountingUsage() = runTest {
        val persistence = InMemoryAgentPersistence()
        val provider = ReattachSummaryProvider()
        val runner = runner(provider, persistence)
        val sessionId = AgentSessionId("reattach-summary-replay")
        val collection = launch {
            try {
                runner.run(
                    request(
                        sessionId = sessionId,
                        messages = longHistory(),
                        contextWindowTokens = 220,
                    ),
                ).collect()
            } catch (_: CancellationException) {
                // The partial summary remains attached to the durable invocation.
            }
        }
        provider.partialSummaryObserved.await()

        runner.interrupt(sessionId)
        collection.join()
        val pending = assertNotNull(
            persistence.load(sessionId)?.checkpoint?.cursor?.provider?.pending,
        )
        assertEquals(ProviderRequestPurpose.CONTEXT_SUMMARY, pending.purpose)

        val completed = runner.resume(sessionId).toList()
            .filterIsInstance<AgentEvent.Completed>()
            .single()
            .state

        val summaries = provider.requests.filter(ProviderRequest::isContextSummary)
        assertEquals(2, summaries.size)
        assertEquals(summaries[0].invocation?.requestId, summaries[1].invocation?.requestId)
        assertEquals(
            listOf(ProviderInvocationIntent.CREATE, ProviderInvocationIntent.REATTACH),
            summaries.map(ProviderRequest::invocationIntent),
        )
        assertEquals(pending.requestId, summaries[0].invocation?.requestId)
        assertTrue(pending.requestId.endsWith(pending.inputIdentity))
        assertEquals(
            pending.inputIdentity,
            providerRequestInputIdentity(summaries[0].copy(invocation = null)),
        )
        assertTrue(
            pending.inputIdentity != providerRequestInputIdentity(
                summaries[0].copy(
                    invocation = null,
                    messages = summaries[0].messages + message("different", "different input"),
                ),
            ),
        )
        assertEquals(CUMULATIVE_USAGE, completed.usage)
        assertEquals(MODEL_USAGE, completed.latestRequestUsage)
    }

    @Test
    fun invalidatedSummaryClaimsANewPhysicalIdentityBeforeRetrying() = runTest {
        val provider = InvalidatedSummaryThenCompleteProvider()

        val completed = runner(
            provider = provider,
            persistence = InMemoryAgentPersistence(),
            retryPolicy = RetryOncePolicy(),
        ).run(
            request(
                sessionId = AgentSessionId("invalidated-summary"),
                messages = longHistory(),
                contextWindowTokens = 220,
            ),
        ).toList().filterIsInstance<AgentEvent.Completed>().single().state

        val summaries = provider.requests.filter(ProviderRequest::isContextSummary)
        assertEquals(2, summaries.size)
        assertTrue(summaries[0].invocation?.requestId != summaries[1].invocation?.requestId)
        assertTrue(summaries[0].invocation?.requestId?.contains(":0:0:context-summary:") == true)
        assertTrue(summaries[1].invocation?.requestId?.contains(":0:1:context-summary:") == true)
        assertEquals(
            listOf(ProviderInvocationIntent.CREATE, ProviderInvocationIntent.CREATE),
            summaries.map(ProviderRequest::invocationIntent),
        )
        assertEquals(CUMULATIVE_USAGE, completed.usage)
    }

    @Test
    fun cancellingRetryPolicyObservesInvalidatedSummaryAlreadyCleared() = runTest {
        val sessionId = AgentSessionId("invalidated-summary-policy-cancel")
        val persistence = InMemoryAgentPersistence()
        val provider = InvalidatedSummaryThenCompleteProvider()
        var clearedBeforePolicy = false
        val retryPolicy = object : RetryPolicy {
            override suspend fun shouldRetry(attempt: Int, error: Throwable): Boolean {
                assertNull(
                    persistence.load(sessionId)?.checkpoint?.cursor?.provider?.pending,
                )
                clearedBeforePolicy = true
                throw CancellationException("retry policy cancelled")
            }

            override suspend fun backoffDelayMs(attempt: Int, error: Throwable): Long = 0L
        }

        assertFailsWith<CancellationException> {
            runner(provider, persistence, retryPolicy = retryPolicy).run(
                request(
                    sessionId = sessionId,
                    messages = longHistory(),
                    contextWindowTokens = 220,
                ),
            ).toList()
        }

        assertTrue(clearedBeforePolicy)
        assertEquals(1, provider.requests.size)
    }

    @Test
    fun retryExhaustedSummaryInvalidationClearsPendingBeforeFailingOpen() = runTest {
        val provider = InvalidatedSummaryThenCompleteProvider()

        val events = runner(
            provider = provider,
            persistence = InMemoryAgentPersistence(),
            retryPolicy = RetryOncePolicy(),
        ).run(
            request(
                sessionId = AgentSessionId("invalidated-summary-fail-open"),
                messages = longHistory(),
                contextWindowTokens = 220,
                maxProviderRetries = 0,
            ),
        ).toList()

        assertTrue(events.any { it is AgentEvent.Completed })
        val checkpoints = events.filterIsInstance<AgentEvent.CheckpointSaved>()
        val summaryPendingIndex = checkpoints.indexOfFirst {
            it.checkpoint.cursor.provider.pending?.purpose == ProviderRequestPurpose.CONTEXT_SUMMARY
        }
        assertTrue(summaryPendingIndex >= 0)
        val clearedIndex = summaryPendingIndex + 1 + checkpoints
            .drop(summaryPendingIndex + 1)
            .indexOfFirst { it.checkpoint.cursor.provider.pending == null }
        assertTrue(clearedIndex > summaryPendingIndex)
        val modelPendingIndex = clearedIndex + 1 + checkpoints
            .drop(clearedIndex + 1)
            .indexOfFirst {
                it.checkpoint.cursor.provider.pending?.purpose == ProviderRequestPurpose.MODEL
            }
        assertTrue(modelPendingIndex > clearedIndex)
        assertEquals(
            listOf(ProviderInvocationIntent.CREATE, ProviderInvocationIntent.CREATE),
            provider.requests.map(ProviderRequest::invocationIntent),
        )
    }

    @Test
    fun overflowCompactionInterruptionReattachesTheExactCompactedModelInvocation() = runTest {
        val persistence = InMemoryAgentPersistence()
        val provider = InterruptibleOverflowRecoveryProvider()
        val runner = runner(provider, persistence)
        val sessionId = AgentSessionId("overflow-partial-resume")
        val collection = launch {
            try {
                runner.run(
                    request(
                        sessionId = sessionId,
                        messages = longHistory(),
                        contextWindowTokens = null,
                    ),
                ).collect()
            } catch (_: CancellationException) {
                // The compacted model invocation remains reattachable.
            }
        }
        provider.partialModelObserved.await()

        runner.interrupt(sessionId)
        collection.join()
        val pendingId = assertNotNull(
            persistence.load(sessionId)?.checkpoint?.cursor?.provider?.pending?.requestId,
        )

        val completed = runner.resume(sessionId).toList()
            .filterIsInstance<AgentEvent.Completed>()
            .single()
            .state

        val modelRequests = provider.requests.filterNot(ProviderRequest::isContextSummary)
        assertEquals(3, modelRequests.size)
        assertTrue(modelRequests[0].invocation?.requestId != pendingId)
        assertEquals(pendingId, modelRequests[1].invocation?.requestId)
        assertEquals(pendingId, modelRequests[2].invocation?.requestId)
        assertEquals(1, provider.requests.count(ProviderRequest::isContextSummary))
        assertEquals(CUMULATIVE_USAGE, completed.usage)
        assertEquals(MODEL_USAGE, completed.latestRequestUsage)
    }

    @Test
    fun aNewRunnerForTheSameSession_restoresPersistentContextState() = runTest {
        val persistence = InMemoryAgentPersistence()
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
        runner(firstProvider, persistence, firstManager).run(firstRequest).toList()

        val persisted = assertNotNull(persistence.load(firstRequest.sessionId)?.snapshot)
        assertEquals(sentinel, persisted.state.contextManagement)
        val secondMessages = persisted.state.messages + message("u2", "second")
        val secondManager = RecordingContextManager()
        val secondRequest = firstRequest.copy(messages = secondMessages)

        runner(CompleteProvider(), persistence, secondManager).run(secondRequest).toList()

        assertEquals(sentinel, secondManager.seenStates.single())
    }

    @Test
    fun contextLimitBeforeAnyOutput_forcesOneSemanticCompactionAndRetriesOnce() = runTest {
        val provider = OverflowThenCompleteProvider()
        val persistence = InMemoryAgentPersistence()
        val request = request(
            sessionId = AgentSessionId("overflow-recovery-session"),
            messages = longHistory(),
            contextWindowTokens = null,
        )

        val events = runner(
            provider = provider,
            persistence = persistence,
        ).run(request).toList()

        assertTrue(events.any { it is AgentEvent.Completed })
        assertTrue(events.none { it is AgentEvent.Failed })
        assertEquals(
            listOf("model", "summary", "model"),
            provider.requests.map { if (it.isContextSummary()) "summary" else "model" },
        )
        assertNotNull(persistence.load(request.sessionId)?.snapshot?.state?.contextManagement?.compaction)
    }

    @Test
    fun contextLimitWithoutRecoveryIsATerminalFailure() = runTest {
        val provider = OverflowThenCompleteProvider()
        val request = request(
            sessionId = AgentSessionId("terminal-overflow-session"),
            messages = longHistory(),
            contextWindowTokens = null,
            overflowRetryLimit = 0,
        )

        val events = runner(
            provider = provider,
            persistence = InMemoryAgentPersistence(),
        ).run(request).toList()

        assertEquals(
            saien.magrathea.core.AgentFailureCode.CONTEXT_LIMIT,
            events.filterIsInstance<AgentEvent.Failed>().single().code,
        )
    }

    @Test
    fun contextLimitAfterOutput_doesNotRetryOrDuplicatePartialAnswer() = runTest {
        val provider = PartialThenOverflowProvider()
        val request = request(
            sessionId = AgentSessionId("post-output-overflow-session"),
            messages = longHistory(),
            contextWindowTokens = null,
        )

        val events = runner(
            provider = provider,
            persistence = InMemoryAgentPersistence(),
        ).run(request).toList()

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
        persistence: InMemoryAgentPersistence,
        contextManager: saien.magrathea.core.ContextManager? = null,
        tracer: MagratheaTracer = NoopMagratheaTracer,
        retryPolicy: RetryPolicy = saien.magrathea.core.NoopRetryPolicy,
    ) = DefaultAgentRunner(
        providerRegistry = InMemoryProviderRegistry(listOf(provider)),
        toolRegistry = InMemoryToolRegistry(),
        persistence = persistence,
        contextManager = contextManager,
        tracer = tracer,
        retryPolicy = retryPolicy,
    )

    private fun request(
        sessionId: AgentSessionId = AgentSessionId("semantic-compaction-session"),
        messages: List<AgentMessage>,
        contextWindowTokens: Long? = null,
        providerConfig: ProviderConfig = ProviderConfig(maxTokens = 20),
        maxProviderRetries: Int = 2,
        overflowRetryLimit: Int = 1,
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
                maxProviderRetries = maxProviderRetries,
                contextManagement = ContextManagementConfig(
                    reserveTokens = 40,
                    keepRecentTokens = 60,
                    summaryMaxTokens = 32,
                    charsPerTokenEstimate = 4,
                    overflowRetryLimit = overflowRetryLimit,
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
        override val optionsFamily: String = "openai"
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

    private class SummaryThenDisconnectProvider : ProviderAdapter {
        override val key: String = PROVIDER
        val requests = mutableListOf<ProviderRequest>()

        override suspend fun generate(request: ProviderRequest): Flow<ProviderChunk> = flow {
            requests += request
            if (request.isContextSummary()) {
                emit(providerChunk(text = "completed summary", completed = true))
                throw ProviderNetworkException("late disconnect")
            }
            emit(providerChunk(text = "answer", completed = true))
        }
    }

    private class TerminalSummaryThenFatalProvider(
        private val fatal: TestFatalError,
    ) : ProviderAdapter {
        override val key: String = PROVIDER

        override suspend fun generate(request: ProviderRequest): Flow<ProviderChunk> = flow {
            if (request.isContextSummary()) {
                emit(providerChunk(text = "completed summary", completed = true))
                throw ProviderNetworkException("late summary failure", fatal)
            }
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

    private class SummaryUsageThenFailureProvider : ProviderAdapter {
        override val key: String = PROVIDER

        override suspend fun generate(request: ProviderRequest): Flow<ProviderChunk> = flow {
            if (request.isContextSummary()) {
                emit(
                    providerChunk(
                        text = "partial summary",
                        usage = SUMMARY_PROVIDER_USAGE,
                    ),
                )
                throw ProviderNetworkException("summary connection lost")
            }
            emit(
                providerChunk(
                    text = "answer",
                    completed = true,
                    usage = MODEL_PROVIDER_USAGE,
                ),
            )
        }
    }

    private class InterruptibleSummaryProvider : ProviderAdapter {
        override val key: String = PROVIDER
        val requests = mutableListOf<ProviderRequest>()
        val firstSummaryUsageObserved = CompletableDeferred<Unit>()
        private var summaryCalls = 0

        override suspend fun generate(request: ProviderRequest): Flow<ProviderChunk> = flow {
            requests += request
            if (request.isContextSummary()) {
                summaryCalls += 1
                emit(
                    providerChunk(
                        text = "summary-$summaryCalls",
                        completed = summaryCalls > 1,
                        usage = SUMMARY_PROVIDER_USAGE,
                    ),
                )
                if (summaryCalls == 1) {
                    firstSummaryUsageObserved.complete(Unit)
                    awaitCancellation()
                }
                return@flow
            }
            emit(
                providerChunk(
                    text = "answer",
                    completed = true,
                    usage = MODEL_PROVIDER_USAGE,
                ),
            )
        }
    }

    private class ReattachSummaryProvider : ProviderAdapter {
        override val key: String = PROVIDER
        override val invocationResumeMode: ProviderInvocationResumeMode =
            ProviderInvocationResumeMode.REATTACH
        val requests = mutableListOf<ProviderRequest>()
        val partialSummaryObserved = CompletableDeferred<Unit>()
        private var summaryCalls = 0

        override suspend fun generate(request: ProviderRequest): Flow<ProviderChunk> = flow {
            requests += request
            if (request.isContextSummary()) {
                summaryCalls += 1
                emit(
                    providerChunk(
                        text = "summary-$summaryCalls",
                        completed = summaryCalls > 1,
                        usage = SUMMARY_PROVIDER_USAGE,
                    ),
                )
                if (summaryCalls == 1) {
                    partialSummaryObserved.complete(Unit)
                    awaitCancellation()
                }
                return@flow
            }
            emit(
                providerChunk(
                    text = "answer",
                    completed = true,
                    usage = MODEL_PROVIDER_USAGE,
                ),
            )
        }
    }

    private class InvalidatedSummaryThenCompleteProvider : ProviderAdapter {
        override val key: String = PROVIDER
        override val invocationResumeMode: ProviderInvocationResumeMode =
            ProviderInvocationResumeMode.REATTACH
        val requests = mutableListOf<ProviderRequest>()
        private var summaryCalls = 0

        override suspend fun generate(request: ProviderRequest): Flow<ProviderChunk> = flow {
            requests += request
            if (request.isContextSummary()) {
                summaryCalls += 1
                if (summaryCalls == 1) {
                    throw ProviderInvocationInvalidatedException(
                        ProviderNetworkException("summary invocation expired"),
                        retryable = true,
                    )
                }
                emit(
                    providerChunk(
                        text = "summary",
                        completed = true,
                        usage = SUMMARY_PROVIDER_USAGE,
                    ),
                )
                return@flow
            }
            emit(
                providerChunk(
                    text = "answer",
                    completed = true,
                    usage = MODEL_PROVIDER_USAGE,
                ),
            )
        }
    }

    private class InterruptibleOverflowRecoveryProvider : ProviderAdapter {
        override val key: String = PROVIDER
        override val invocationResumeMode: ProviderInvocationResumeMode =
            ProviderInvocationResumeMode.REATTACH
        val requests = mutableListOf<ProviderRequest>()
        val partialModelObserved = CompletableDeferred<Unit>()
        private var modelCalls = 0

        override suspend fun generate(request: ProviderRequest): Flow<ProviderChunk> = flow {
            requests += request
            if (request.isContextSummary()) {
                emit(
                    providerChunk(
                        text = "overflow summary",
                        completed = true,
                        usage = SUMMARY_PROVIDER_USAGE,
                    ),
                )
                return@flow
            }
            modelCalls += 1
            when (modelCalls) {
                1 -> throw ProviderContextLimitException()
                2 -> {
                    emit(providerChunk(text = "partial", usage = MODEL_PROVIDER_USAGE))
                    partialModelObserved.complete(Unit)
                    awaitCancellation()
                }
                else -> emit(
                    providerChunk(
                        text = "answer",
                        completed = true,
                        usage = MODEL_PROVIDER_USAGE,
                    ),
                )
            }
        }
    }

    private class RetryOncePolicy : RetryPolicy {
        override suspend fun shouldRetry(attempt: Int, error: Throwable): Boolean = attempt == 1

        override suspend fun backoffDelayMs(attempt: Int, error: Throwable): Long = 0
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
        val SUMMARY_PROVIDER_USAGE = ProviderUsage(inputTokens = 40, outputTokens = 4)
        val MODEL_PROVIDER_USAGE = ProviderUsage(inputTokens = 10, outputTokens = 2)
        val SUMMARY_USAGE = TokenUsage(inputTokens = 40, outputTokens = 4)
        val MODEL_USAGE = TokenUsage(inputTokens = 10, outputTokens = 2)
        val CUMULATIVE_USAGE = TokenUsage(inputTokens = 50, outputTokens = 6)
        val RESUMED_CUMULATIVE_USAGE = TokenUsage(inputTokens = 90, outputTokens = 10)
    }
}

private fun ProviderRequest.isContextSummary(): Boolean =
    invocation?.requestId?.contains(":context-summary:") == true
