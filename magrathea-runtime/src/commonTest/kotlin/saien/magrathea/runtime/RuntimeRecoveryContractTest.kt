package saien.magrathea.runtime

import kotlin.coroutines.coroutineContext
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import saien.magrathea.core.AgentCheckpoint
import saien.magrathea.core.AgentEngineConfig
import saien.magrathea.core.AgentEvent
import saien.magrathea.core.AgentFailureCode
import saien.magrathea.core.AgentInterruption
import saien.magrathea.core.AgentInterruptionReason
import saien.magrathea.core.AgentMessage
import saien.magrathea.core.AgentPersistence
import saien.magrathea.core.AgentPersistenceRecord
import saien.magrathea.core.AgentPendingProviderInvocation
import saien.magrathea.core.AgentProviderInvocationCursor
import saien.magrathea.core.AgentRecoveryBlockReason
import saien.magrathea.core.AgentRecoveryDisposition
import saien.magrathea.core.AgentRequest
import saien.magrathea.core.AgentResumeCursor
import saien.magrathea.core.AgentResumePhase
import saien.magrathea.core.AgentRunId
import saien.magrathea.core.AgentSessionId
import saien.magrathea.core.AgentSessionSnapshot
import saien.magrathea.core.AgentStateSnapshot
import saien.magrathea.core.AgentStatus
import saien.magrathea.core.MessageRole
import saien.magrathea.core.ModelDescriptor
import saien.magrathea.core.ProviderInterruption
import saien.magrathea.core.ProviderInterruptionPhase
import saien.magrathea.core.ProviderConfig
import saien.magrathea.core.ProviderRequestPurpose
import saien.magrathea.core.ProviderTimeoutConfig
import saien.magrathea.core.RetryPolicy
import saien.magrathea.core.RuntimeConfig
import saien.magrathea.core.StopReason
import saien.magrathea.core.TextPart
import saien.magrathea.core.TokenUsage
import saien.magrathea.core.ToolCallPart
import saien.magrathea.core.ToolDefinition
import saien.magrathea.core.ToolExecutionRecord
import saien.magrathea.core.ToolExecutionRequest
import saien.magrathea.core.ToolExecutionResult
import saien.magrathea.core.ToolExecutionState
import saien.magrathea.core.ToolExecutor
import saien.magrathea.core.ToolRecoveryPolicy
import saien.magrathea.provider.api.InMemoryProviderRegistry
import saien.magrathea.provider.api.ProviderAdapter
import saien.magrathea.provider.api.ProviderCancellationIntent
import saien.magrathea.provider.api.ProviderChunk
import saien.magrathea.provider.api.ProviderInvocation
import saien.magrathea.provider.api.ProviderInvocationIntent
import saien.magrathea.provider.api.ProviderInvocationInvalidatedException
import saien.magrathea.provider.api.ProviderInvocationResumeMode
import saien.magrathea.provider.api.ProviderNetworkException
import saien.magrathea.provider.api.ProviderProtocolException
import saien.magrathea.provider.api.ProviderRequest
import saien.magrathea.provider.api.ProviderUsage
import saien.magrathea.provider.api.providerCancellationIntent

class RuntimeRecoveryContractTest {
    @Test
    fun hostInterruptionRollsBackPartialOutputAndResumeUsesANewProviderAttempt() = runTest {
        val sessionId = AgentSessionId("host-interruption")
        val persistence = InMemoryAgentPersistence()
        val provider = PartialThenCompleteProvider()
        val runner = runner(provider, persistence)
        val request = request(sessionId)
        val observed = mutableListOf<AgentEvent>()
        val collection = launch {
            try {
                runner.run(request).collect(observed::add)
            } catch (_: CancellationException) {
                // The runtime converts host interruption into durable recovery state.
            }
        }
        provider.partialObserved.await()

        val recovery = runner.interrupt(sessionId)
        withTimeout(2_000) { collection.join() }

        assertEquals(AgentRecoveryDisposition.RESUMABLE, recovery.disposition)
        assertEquals(AgentInterruptionReason.HOST_REQUESTED, recovery.interruption?.reason)
        assertEquals(ProviderCancellationIntent.INTERRUPT, provider.cancellationIntent.await())
        val interrupted = assertNotNull(persistence.load(sessionId))
        assertEquals(AgentStatus.INTERRUPTED, interrupted.snapshot.state.status)
        assertEquals(AgentResumePhase.MODEL_PENDING, interrupted.checkpoint?.cursor?.phase)
        assertFalse(interrupted.snapshot.state.allText().contains(PARTIAL_TEXT))
        assertEquals(INTERRUPTED_USAGE, interrupted.snapshot.state.usage)
        assertEquals(INTERRUPTED_USAGE, interrupted.snapshot.state.latestRequestUsage)
        assertEquals(TokenUsage(), interrupted.checkpoint?.state?.usage)

        val resumed = runner.resume(sessionId).toList()
        val completed = resumed.filterIsInstance<AgentEvent.Completed>().single().state
        assertEquals(AgentStatus.COMPLETED, completed.status)
        assertTrue(completed.allText().contains(FINAL_TEXT))
        assertFalse(completed.allText().contains(PARTIAL_TEXT))
        assertEquals(2, provider.requests.size)
        val invocationIds = provider.requests.map { assertNotNull(it.invocation).requestId }
        assertTrue(invocationIds[0].endsWith(":0:0"))
        assertTrue(invocationIds[1].endsWith(":0:1"))
        assertEquals(CUMULATIVE_RESUMED_USAGE, completed.usage)
        assertEquals(RESUMED_ATTEMPT_USAGE, completed.latestRequestUsage)
        assertEquals(
            invocationIds[0].substringBeforeLast(':'),
            invocationIds[1].substringBeforeLast(':'),
        )
    }

    @Test
    fun terminalCancellationDetachesProviderBeforeAbandoningAfterCommit() = runTest {
        val sessionId = AgentSessionId("terminal-provider-cancel")
        val persistence = InMemoryAgentPersistence()
        val provider = GatewayLikeCancellationProvider(persistence, sessionId)
        val runner = runner(provider, persistence)
        val collection = launch {
            try {
                runner.run(request(sessionId)).collect()
            } catch (_: CancellationException) {
                // The explicit user cancellation remains coroutine cancellation to the collector.
            }
        }
        provider.started.await()
        val pendingId = assertNotNull(
            persistence.load(sessionId)?.checkpoint?.cursor?.provider?.pending?.requestId,
        )

        runner.cancel(sessionId)
        withTimeout(2_000) { collection.join() }

        assertEquals(ProviderCancellationIntent.INTERRUPT, provider.cancellationIntent.await())
        assertFalse(provider.inlineRemoteCleanup)
        assertEquals(listOf(pendingId), provider.abandoned.map(ProviderInvocation::requestId))
        val observed = assertNotNull(provider.recordWhenAbandoned)
        assertEquals(AgentStatus.CANCELLED, observed.snapshot.state.status)
        assertNull(observed.checkpoint)
    }

    @Test
    fun activeTerminalCancellationAbandonsItsPendingInvocation() = runTest {
        val sessionId = AgentSessionId("active-terminal-abandon")
        val persistence = InMemoryAgentPersistence()
        val provider = AbandonTrackingProvider()
        val runner = runner(provider, persistence)
        val collection = launch {
            try {
                runner.run(request(sessionId)).collect()
            } catch (_: CancellationException) {
                // Terminal cancellation remains coroutine cancellation to the collector.
            }
        }
        provider.started.await()
        val pendingId = assertNotNull(
            persistence.load(sessionId)?.checkpoint?.cursor?.provider?.pending?.requestId,
        )

        runner.cancel(sessionId)
        withTimeout(2_000) { collection.join() }

        assertEquals(listOf(pendingId), provider.abandoned.map(ProviderInvocation::requestId))
        val cancelled = assertNotNull(persistence.load(sessionId))
        assertEquals(AgentStatus.CANCELLED, cancelled.snapshot.state.status)
        assertNull(cancelled.checkpoint)
    }

    @Test
    fun terminalCancellationDuringReattachBackoffAbandonsItsPendingInvocation() = runTest {
        val sessionId = AgentSessionId("reattach-backoff-terminal-cancel")
        val persistence = InMemoryAgentPersistence()
        val provider = RetryableReattachProvider()
        val retryPolicy = BlockingBackoffRetryPolicy()
        val runner = runner(provider, persistence, retryPolicy)
        val collection = launch {
            try {
                runner.run(request(sessionId)).collect()
            } catch (_: CancellationException) {
                // Terminal cancellation remains coroutine cancellation to the collector.
            }
        }
        retryPolicy.backoffStarted.await()
        val pendingId = assertNotNull(
            persistence.load(sessionId)?.checkpoint?.cursor?.provider?.pending?.requestId,
        )

        runner.cancel(sessionId)
        withTimeout(2_000) { collection.join() }

        assertEquals(1, provider.requests.size)
        assertEquals(listOf(pendingId), provider.abandoned.map(ProviderInvocation::requestId))
        val cancelled = assertNotNull(persistence.load(sessionId))
        assertEquals(AgentStatus.CANCELLED, cancelled.snapshot.state.status)
        assertNull(cancelled.checkpoint)
    }

    @Test
    fun collectorCancellationDuringReattachBackoffAbandonsItsPendingInvocation() = runTest {
        val sessionId = AgentSessionId("reattach-backoff-collector-cancel")
        val persistence = InMemoryAgentPersistence()
        val provider = RetryableReattachProvider()
        val retryPolicy = BlockingBackoffRetryPolicy()
        val runner = runner(provider, persistence, retryPolicy)
        val collection = launch { runner.run(request(sessionId)).collect() }
        retryPolicy.backoffStarted.await()
        val pendingId = assertNotNull(
            persistence.load(sessionId)?.checkpoint?.cursor?.provider?.pending?.requestId,
        )

        collection.cancelAndJoin()

        assertEquals(listOf(pendingId), provider.abandoned.map(ProviderInvocation::requestId))
        val cancelled = assertNotNull(persistence.load(sessionId))
        assertEquals(AgentStatus.CANCELLED, cancelled.snapshot.state.status)
        assertNull(cancelled.checkpoint)
    }

    @Test
    fun failedTerminalCommitPreservesThePendingInvocationDuringReattachBackoff() = runTest {
        val sessionId = AgentSessionId("reattach-backoff-failed-terminal-commit")
        val delegate = InMemoryAgentPersistence()
        val persistence = FailingTerminalCommitPersistence(delegate, AgentStatus.CANCELLED)
        val provider = RetryableReattachProvider()
        val retryPolicy = BlockingBackoffRetryPolicy()
        val runner = runner(provider, persistence, retryPolicy)
        val collection = launch { runner.run(request(sessionId)).collect() }
        retryPolicy.backoffStarted.await()
        val pendingId = assertNotNull(
            delegate.load(sessionId)?.checkpoint?.cursor?.provider?.pending?.requestId,
        )

        collection.cancelAndJoin()

        assertTrue(provider.abandoned.isEmpty())
        val recoverable = assertNotNull(delegate.load(sessionId))
        assertEquals(AgentStatus.RUNNING, recoverable.snapshot.state.status)
        assertEquals(
            pendingId,
            recoverable.checkpoint?.cursor?.provider?.pending?.requestId,
        )
    }

    @Test
    fun failedActiveTerminalCommitDoesNotTriggerGatewayLikeRemoteCleanup() = runTest {
        val sessionId = AgentSessionId("gateway-like-failed-terminal-commit")
        val delegate = InMemoryAgentPersistence()
        val persistence = FailingTerminalCommitPersistence(delegate, AgentStatus.CANCELLED)
        val provider = GatewayLikeCancellationProvider(delegate, sessionId)
        val runner = runner(provider, persistence)
        val collection = launch { runner.run(request(sessionId)).collect() }
        provider.started.await()
        val pendingId = assertNotNull(
            delegate.load(sessionId)?.checkpoint?.cursor?.provider?.pending?.requestId,
        )

        collection.cancelAndJoin()

        assertEquals(ProviderCancellationIntent.INTERRUPT, provider.cancellationIntent.await())
        assertFalse(provider.inlineRemoteCleanup)
        assertTrue(provider.abandoned.isEmpty())
        val recoverable = assertNotNull(delegate.load(sessionId))
        assertEquals(AgentStatus.RUNNING, recoverable.snapshot.state.status)
        assertEquals(
            pendingId,
            recoverable.checkpoint?.cursor?.provider?.pending?.requestId,
        )
    }

    @Test
    fun terminalCancellationBoundsPendingInvocationAbandonDuringReattachBackoff() = runTest {
        val sessionId = AgentSessionId("reattach-backoff-bounded-abandon")
        val persistence = InMemoryAgentPersistence()
        val provider = RetryableReattachProvider(abandonNeverCompletes = true)
        val retryPolicy = BlockingBackoffRetryPolicy()
        val runner = runner(provider, persistence, retryPolicy)
        val collection = launch {
            runner.run(
                request(
                    sessionId = sessionId,
                    providerTimeouts = ProviderTimeoutConfig(
                        connectTimeoutMillis = 25,
                        firstEventTimeoutMillis = 25,
                        streamIdleTimeoutMillis = 25,
                        callTimeoutMillis = 100,
                    ),
                ),
            ).collect()
        }
        retryPolicy.backoffStarted.await()

        collection.cancelAndJoin()

        assertTrue(provider.abandonStarted.isCompleted)
        assertTrue(provider.abandonCancelled.isCompleted)
        val cancelled = assertNotNull(persistence.load(sessionId))
        assertEquals(AgentStatus.CANCELLED, cancelled.snapshot.state.status)
        assertNull(cancelled.checkpoint)
    }

    @Test
    fun resumeReturnedBeforeCollectionObservesALaterPersistedCancellation() = runTest {
        val sessionId = AgentSessionId("cold-resume-cancel")
        val persistence = InMemoryAgentPersistence()
        val request = request(sessionId)
        val runId = AgentRunId("cold-resume-run")
        val checkpointState = AgentStateSnapshot(
            messages = request.messages,
            status = AgentStatus.RUNNING,
        )
        persistence.commit(
            AgentSessionSnapshot(
                sessionId = sessionId,
                runId = runId,
                request = request,
                state = checkpointState.copy(
                    status = AgentStatus.INTERRUPTED,
                    stopReason = StopReason.INTERRUPTED,
                ),
                interruption = AgentInterruption(AgentInterruptionReason.ORPHANED),
            ),
            AgentCheckpoint(
                sessionId = sessionId,
                runId = runId,
                cursor = AgentResumeCursor(0, AgentResumePhase.TURN_PREPARING),
                state = checkpointState,
            ),
        )
        val provider = RecordingCompleteProvider()
        val runner = runner(provider, persistence)

        val coldResume = runner.resume(sessionId)
        runner.cancel(sessionId)
        val events = coldResume.toList()

        assertTrue(events.single() is AgentEvent.Cancelled)
        assertTrue(provider.requests.isEmpty())
        assertEquals(AgentStatus.CANCELLED, persistence.load(sessionId)?.snapshot?.state?.status)
    }

    @Test
    fun coldResumeWaitsForARacingCancellationToBecomeDurable() = runTest {
        val sessionId = AgentSessionId("cold-resume-during-cancel")
        val delegate = InMemoryAgentPersistence()
        persistRecoverableSession(delegate, sessionId)
        val persistence = BlockingFirstLoadPersistence(delegate)
        val provider = RecordingCompleteProvider()
        val runner = runner(provider, persistence)

        val cancellation = async { runner.cancel(sessionId) }
        persistence.firstLoadStarted.await()
        val resumed = async { runner.resume(sessionId).toList() }
        yield()

        assertFalse(resumed.isCompleted)
        assertTrue(provider.requests.isEmpty())

        persistence.allowFirstLoad.complete(Unit)
        cancellation.await()
        val events = resumed.await()

        assertTrue(events.single() is AgentEvent.Cancelled)
        assertTrue(provider.requests.isEmpty())
        val cancelled = assertNotNull(delegate.load(sessionId))
        assertEquals(AgentStatus.CANCELLED, cancelled.snapshot.state.status)
        assertEquals(null, cancelled.checkpoint)
    }

    @Test
    fun coldResumeWaitsForARacingInterruptionToBecomeDurable() = runTest {
        val sessionId = AgentSessionId("cold-resume-during-interrupt")
        val delegate = InMemoryAgentPersistence()
        persistRecoverableSession(delegate, sessionId)
        val persistence = BlockingFirstLoadPersistence(delegate)
        val provider = RecordingCompleteProvider()
        val runner = runner(provider, persistence)

        val interruption = async { runner.interrupt(sessionId) }
        persistence.firstLoadStarted.await()
        val resumed = async { runner.resume(sessionId).toList() }
        yield()

        assertFalse(resumed.isCompleted)
        assertTrue(provider.requests.isEmpty())

        persistence.allowFirstLoad.complete(Unit)
        assertEquals(AgentRecoveryDisposition.RESUMABLE, interruption.await().disposition)
        val events = resumed.await()

        assertTrue(events.any { it is AgentEvent.Completed })
        assertEquals(1, provider.requests.size)
        assertEquals(AgentStatus.COMPLETED, delegate.load(sessionId)?.snapshot?.state?.status)
    }

    @Test
    fun separatelyCreatedResumeFlowsLoadStateOnlyWhenCollected() = runTest {
        val sessionId = AgentSessionId("two-cold-resumes")
        val persistence = InMemoryAgentPersistence()
        val request = request(sessionId)
        val runId = AgentRunId("two-cold-resumes-run")
        val checkpointState = AgentStateSnapshot(
            messages = request.messages,
            status = AgentStatus.RUNNING,
        )
        persistence.commit(
            AgentSessionSnapshot(
                sessionId = sessionId,
                runId = runId,
                request = request,
                state = checkpointState.copy(
                    status = AgentStatus.INTERRUPTED,
                    stopReason = StopReason.INTERRUPTED,
                ),
                interruption = AgentInterruption(AgentInterruptionReason.ORPHANED),
            ),
            AgentCheckpoint(
                sessionId = sessionId,
                runId = runId,
                cursor = AgentResumeCursor(0, AgentResumePhase.TURN_PREPARING),
                state = checkpointState,
            ),
        )
        val provider = RecordingCompleteProvider()
        val runner = runner(provider, persistence)

        val first = runner.resume(sessionId)
        val second = runner.resume(sessionId)
        assertTrue(first.toList().single { it is AgentEvent.Completed } is AgentEvent.Completed)
        assertTrue(second.toList().single() is AgentEvent.Completed)

        assertEquals(1, provider.requests.size)
    }

    @Test
    fun cancellingAnInterruptedSessionAbandonsItsExactPendingInvocation() = runTest {
        val sessionId = AgentSessionId("abandon-after-interrupt")
        val persistence = InMemoryAgentPersistence()
        val provider = AbandonTrackingProvider()
        val runner = runner(provider, persistence)
        val collection = launch {
            try {
                runner.run(request(sessionId)).collect()
            } catch (_: CancellationException) {
                // Host interruption keeps the durable invocation available for reattachment.
            }
        }
        provider.started.await()

        runner.interrupt(sessionId)
        collection.join()
        assertTrue(provider.abandoned.isEmpty())
        val pendingId = assertNotNull(
            persistence.load(sessionId)?.checkpoint?.cursor?.provider?.pending?.requestId,
        )

        runner.cancel(sessionId)

        assertEquals(listOf(pendingId), provider.abandoned.map(ProviderInvocation::requestId))
        assertEquals(AgentStatus.CANCELLED, persistence.load(sessionId)?.snapshot?.state?.status)
    }

    @Test
    fun inactiveCancellationPersistsItsTerminalStateBeforeRemoteAbandon() = runTest {
        val sessionId = AgentSessionId("terminal-before-abandon")
        val persistence = InMemoryAgentPersistence()
        persistRecoverableSession(persistence, sessionId, pendingInvocation = true)
        val provider = PersistenceObservingAbandonProvider(persistence, sessionId)
        val runner = runner(provider, persistence)

        runner.cancel(sessionId)

        val observed = assertNotNull(provider.observedRecord)
        assertEquals(AgentStatus.CANCELLED, observed.snapshot.state.status)
        assertEquals(null, observed.checkpoint)
    }

    @Test
    fun inactiveCancellationBoundsAHangingRemoteAbandon() = runTest {
        val sessionId = AgentSessionId("bounded-hanging-abandon")
        val persistence = InMemoryAgentPersistence()
        persistRecoverableSession(
            persistence = persistence,
            sessionId = sessionId,
            pendingInvocation = true,
            providerTimeouts = ProviderTimeoutConfig(
                connectTimeoutMillis = 25,
                firstEventTimeoutMillis = 25,
                streamIdleTimeoutMillis = 25,
                callTimeoutMillis = 100,
            ),
        )
        val provider = HangingAbandonProvider()
        val runner = runner(provider, persistence)

        runner.cancel(sessionId)

        assertTrue(provider.abandonStarted.isCompleted)
        assertTrue(provider.abandonCancelled.isCompleted)
        val cancelled = assertNotNull(persistence.load(sessionId))
        assertEquals(AgentStatus.CANCELLED, cancelled.snapshot.state.status)
        assertEquals(null, cancelled.checkpoint)
    }

    @Test
    fun failedTerminalCancellationCommitDoesNotAbandonAResumableInvocation() = runTest {
        val sessionId = AgentSessionId("failed-terminal-before-abandon")
        val delegate = InMemoryAgentPersistence()
        persistRecoverableSession(delegate, sessionId, pendingInvocation = true)
        val persistence = FailingTerminalCommitPersistence(delegate, AgentStatus.CANCELLED)
        val provider = AbandonTrackingProvider()
        val runner = runner(provider, persistence)

        assertFailsWith<RuntimeException> {
            runner.cancel(sessionId)
        }

        assertTrue(provider.abandoned.isEmpty())
        val recoverable = assertNotNull(delegate.load(sessionId))
        assertEquals(AgentStatus.RUNNING, recoverable.snapshot.state.status)
        assertNotNull(recoverable.checkpoint)
    }

    @Test
    fun unspecifiedCollectorCancellationIsTerminal() = runTest {
        val sessionId = AgentSessionId("collector-cancel")
        val persistence = InMemoryAgentPersistence()
        val provider = PartialThenCompleteProvider()
        val runner = runner(provider, persistence)
        val collection = launch { runner.run(request(sessionId)).collect() }
        provider.partialObserved.await()

        collection.cancelAndJoin()

        assertEquals(ProviderCancellationIntent.INTERRUPT, provider.cancellationIntent.await())
        assertEquals(AgentStatus.CANCELLED, assertNotNull(persistence.load(sessionId)).snapshot.state.status)
    }

    @Test
    fun terminalCancelCannotBeOverwrittenByARacingInterrupt() = runTest {
        val sessionId = AgentSessionId("cancel-absorbs-interrupt")
        val persistence = InMemoryAgentPersistence()
        val provider = DelayedCancellationObservationProvider()
        val runner = runner(provider, persistence)
        val collection = launch {
            try {
                runner.run(request(sessionId)).collect()
            } catch (_: CancellationException) {
                // Expected terminal cancellation.
            }
        }
        provider.started.await()

        val cancel = async { runner.cancel(sessionId) }
        provider.cancellationCaught.await()
        val interrupt = async { runner.interrupt(sessionId) }
        provider.observeIntent.complete(Unit)
        cancel.await()
        interrupt.await()
        collection.join()

        assertEquals(ProviderCancellationIntent.INTERRUPT, provider.cancellationIntent.await())
        assertEquals(AgentStatus.CANCELLED, assertNotNull(persistence.load(sessionId)).snapshot.state.status)
    }

    @Test
    fun wholeRunTimeoutDetachesProviderBeforeAbandoningAfterTerminalCommit() = runTest {
        val sessionId = AgentSessionId("run-timeout-cancel")
        val persistence = InMemoryAgentPersistence()
        val provider = GatewayLikeCancellationProvider(persistence, sessionId)
        val runner = DefaultAgentRunner(
            providerRegistry = InMemoryProviderRegistry(listOf(provider)),
            toolRegistry = InMemoryToolRegistry(),
            persistence = persistence,
            dispatcher = StandardTestDispatcher(testScheduler),
        )

        val events = runner.run(
            request(
                sessionId = sessionId,
                runtime = RuntimeConfig(
                    maxTurns = 2,
                    runTimeoutMillis = 100,
                ),
            ),
        ).toList()

        assertTrue(events.any { it is AgentEvent.Failed && it.code == AgentFailureCode.TIMEOUT })
        assertEquals(ProviderCancellationIntent.INTERRUPT, provider.cancellationIntent.await())
        assertFalse(provider.inlineRemoteCleanup)
        assertEquals(1, provider.abandoned.size)
        val failed = assertNotNull(persistence.load(sessionId))
        assertEquals(AgentStatus.FAILED, failed.snapshot.state.status)
        assertNull(failed.checkpoint)
        val observed = assertNotNull(provider.recordWhenAbandoned)
        assertEquals(AgentStatus.FAILED, observed.snapshot.state.status)
        assertNull(observed.checkpoint)
    }

    @Test
    fun failedWholeRunTimeoutCommitDoesNotAbandonGatewayLikeInvocation() = runTest {
        val sessionId = AgentSessionId("run-timeout-failed-terminal-commit")
        val delegate = InMemoryAgentPersistence()
        val persistence = FailingTerminalCommitPersistence(delegate, AgentStatus.FAILED)
        val provider = GatewayLikeCancellationProvider(delegate, sessionId)
        val runner = DefaultAgentRunner(
            providerRegistry = InMemoryProviderRegistry(listOf(provider)),
            toolRegistry = InMemoryToolRegistry(),
            persistence = persistence,
            dispatcher = StandardTestDispatcher(testScheduler),
        )

        val events = runner.run(
            request(
                sessionId = sessionId,
                runtime = RuntimeConfig(
                    maxTurns = 2,
                    runTimeoutMillis = 100,
                ),
            ),
        ).toList()

        assertTrue(events.any { it is AgentEvent.Failed && it.code == AgentFailureCode.STORAGE })
        assertEquals(ProviderCancellationIntent.INTERRUPT, provider.cancellationIntent.await())
        assertFalse(provider.inlineRemoteCleanup)
        assertTrue(provider.abandoned.isEmpty())
        val recoverable = assertNotNull(delegate.load(sessionId))
        assertEquals(AgentStatus.RUNNING, recoverable.snapshot.state.status)
        assertNotNull(recoverable.checkpoint?.cursor?.provider?.pending)
    }

    @Test
    fun expiredReattachStartsANewPhysicalInvocation() = runTest {
        val sessionId = AgentSessionId("expired-reattach")
        val persistence = InMemoryAgentPersistence()
        val provider = ExpiredReattachThenCompleteProvider()
        val runner = runner(provider, persistence, RetryOncePolicy())
        val collection = launch {
            try {
                runner.run(request(sessionId)).collect()
            } catch (_: CancellationException) {
                // Explicit interruption leaves a resumable checkpoint.
            }
        }
        provider.firstStarted.await()
        runner.interrupt(sessionId)
        collection.join()

        val resumed = runner.resume(sessionId).toList()
        val completed = resumed
            .filterIsInstance<AgentEvent.Completed>()
            .single()
            .state

        assertEquals(3, provider.requests.size)
        val requestIds = provider.requests.map { assertNotNull(it.invocation).requestId }
        assertEquals(requestIds[0], requestIds[1])
        assertTrue(requestIds[2] != requestIds[1])
        assertEquals(
            listOf(
                ProviderInvocationIntent.CREATE,
                ProviderInvocationIntent.REATTACH,
                ProviderInvocationIntent.CREATE,
            ),
            provider.requests.map(ProviderRequest::invocationIntent),
        )
        val physicalAttemptCheckpoint = resumed
            .filterIsInstance<AgentEvent.CheckpointSaved>()
            .first {
                it.checkpoint.cursor.provider.pending?.requestId == requestIds[2]
            }
            .checkpoint
        assertEquals(2, physicalAttemptCheckpoint.cursor.provider.nextPhysicalAttempt)
        assertEquals(INTERRUPTED_USAGE, physicalAttemptCheckpoint.state.usage)
        assertEquals(CUMULATIVE_RESUMED_USAGE, completed.usage)
        assertEquals(RESUMED_ATTEMPT_USAGE, completed.latestRequestUsage)
    }

    @Test
    fun retryPolicyFailureObservesInvalidatedInvocationAlreadyCleared() = runTest {
        val sessionId = AgentSessionId("invalidated-before-policy-failure")
        val persistence = RecordingAgentPersistence()
        val provider = RetryableInvalidatedThenCompleteProvider()
        var clearedBeforePolicy = false
        val retryPolicy = object : RetryPolicy {
            override suspend fun shouldRetry(attempt: Int, error: Throwable): Boolean {
                assertNull(
                    persistence.load(sessionId)?.checkpoint?.cursor?.provider?.pending,
                )
                clearedBeforePolicy = true
                throw IllegalStateException("retry policy failed")
            }

            override suspend fun backoffDelayMs(attempt: Int, error: Throwable): Long = 0L
        }

        val events = runner(provider, persistence, retryPolicy)
            .run(request(sessionId))
            .toList()

        assertTrue(clearedBeforePolicy)
        assertTrue(events.any { it is AgentEvent.Failed })
        assertEquals(1, provider.requests.size)
        val pendingIndex = persistence.commits.indexOfFirst {
            it.checkpoint?.cursor?.provider?.pending != null
        }
        val clearedIndex = persistence.commits.indexOfFirstAfter(pendingIndex) {
            it.checkpoint?.cursor?.provider?.pending == null && it.checkpoint != null
        }
        assertTrue(pendingIndex >= 0)
        assertTrue(clearedIndex > pendingIndex)
    }

    @Test
    fun interruptionDuringInvalidationBackoffResumesWithAFreshCreate() = runTest {
        val sessionId = AgentSessionId("invalidated-backoff-interruption")
        val persistence = InMemoryAgentPersistence()
        val provider = RetryableInvalidatedThenCompleteProvider()
        val retryPolicy = BlockingBackoffRetryPolicy()
        val runner = runner(provider, persistence, retryPolicy)
        val observed = mutableListOf<AgentEvent>()
        val collection = launch {
            try {
                runner.run(request(sessionId)).collect(observed::add)
            } catch (_: CancellationException) {
                // The interruption deliberately cancels the scheduled retry backoff.
            }
        }

        retryPolicy.backoffStarted.await()
        assertTrue(observed.any { it is AgentEvent.RetryScheduled })
        assertNull(persistence.load(sessionId)?.checkpoint?.cursor?.provider?.pending)

        runner.interrupt(sessionId)
        withTimeout(2_000) { collection.join() }
        assertNull(persistence.load(sessionId)?.checkpoint?.cursor?.provider?.pending)

        val completed = runner.resume(sessionId).toList()
        assertTrue(completed.any { it is AgentEvent.Completed })
        assertEquals(2, provider.requests.size)
        val requestIds = provider.requests.map { assertNotNull(it.invocation).requestId }
        assertTrue(requestIds[0] != requestIds[1])
        assertEquals(
            listOf(ProviderInvocationIntent.CREATE, ProviderInvocationIntent.CREATE),
            provider.requests.map(ProviderRequest::invocationIntent),
        )
    }

    @Test
    fun recoverableRetryReattachesTheSamePhysicalInvocation() = runTest {
        val sessionId = AgentSessionId("recoverable-reattach-retry")
        val persistence = InMemoryAgentPersistence()
        val provider = NetworkFailureThenReattachProvider()

        val events = runner(provider, persistence, RetryOncePolicy())
            .run(request(sessionId))
            .toList()

        assertTrue(events.any { it is AgentEvent.Completed })
        assertEquals(2, provider.requests.size)
        val requestIds = provider.requests.map { assertNotNull(it.invocation).requestId }
        assertEquals(requestIds[0], requestIds[1])
        assertEquals(
            listOf(ProviderInvocationIntent.CREATE, ProviderInvocationIntent.REATTACH),
            provider.requests.map(ProviderRequest::invocationIntent),
        )
    }

    @Test
    fun invalidationAfterPartialReplayCommitsUsageBeforeTheNextResume() = runTest {
        val sessionId = AgentSessionId("invalidation-after-replay")
        val persistence = InMemoryAgentPersistence()
        val provider = ReplayedUsageThenInvalidatedProvider()
        val runner = runner(provider, persistence, RetryOncePolicy())
        val collection = launch {
            try {
                runner.run(request(sessionId)).collect()
            } catch (_: CancellationException) {
                // Explicit interruption leaves the first physical invocation reattachable.
            }
        }
        provider.firstStarted.await()
        runner.interrupt(sessionId)
        collection.join()

        val invalidated = runner.resume(sessionId).toList()

        assertEquals(
            INTERRUPTED_USAGE,
            invalidated.filterIsInstance<AgentEvent.Interrupted>().single().state.usage,
        )
        val invalidatedRecord = assertNotNull(persistence.load(sessionId))
        val invalidatedCheckpoint = assertNotNull(invalidatedRecord.checkpoint)
        assertEquals(1, invalidatedCheckpoint.cursor.provider.nextPhysicalAttempt)
        assertEquals(null, invalidatedCheckpoint.cursor.provider.pending)
        assertEquals(INTERRUPTED_USAGE, invalidatedCheckpoint.state.usage)

        val completed = runner.resume(sessionId).toList()
            .filterIsInstance<AgentEvent.Completed>()
            .single()
            .state

        val requestIds = provider.requests.map { assertNotNull(it.invocation).requestId }
        assertEquals(requestIds[0], requestIds[1])
        assertTrue(requestIds[2] != requestIds[1])
        assertEquals(
            listOf(
                ProviderInvocationIntent.CREATE,
                ProviderInvocationIntent.REATTACH,
                ProviderInvocationIntent.CREATE,
            ),
            provider.requests.map(ProviderRequest::invocationIntent),
        )
        assertEquals(CUMULATIVE_RESUMED_USAGE, completed.usage)
        assertEquals(RESUMED_ATTEMPT_USAGE, completed.latestRequestUsage)
    }

    @Test
    fun permanentInvalidatedInvocationDoesNotRetryOrResume() = runTest {
        val sessionId = AgentSessionId("permanent-invalidation")
        val persistence = InMemoryAgentPersistence()
        val provider = PermanentlyInvalidatedProvider()
        val runner = runner(provider, persistence, RetryOncePolicy())

        val first = runner.run(request(sessionId)).toList()
        val resumed = runner.resume(sessionId).toList()

        assertTrue(first.any { it is AgentEvent.Failed })
        assertTrue(first.none { it is AgentEvent.RetryScheduled })
        assertTrue(resumed.any { it is AgentEvent.Failed })
        assertEquals(1, provider.requests.size)
        assertEquals(null, persistence.load(sessionId)?.checkpoint)
    }

    @Test
    fun networkInterruptionPreservesObservedPartialWhileResumeUsesTheSafeCheckpoint() = runTest {
        val sessionId = AgentSessionId("network-interruption")
        val persistence = InMemoryAgentPersistence()
        val provider = PartialNetworkFailureThenCompleteProvider()
        val runner = runner(provider, persistence)

        val interruptedEvents = runner.run(request(sessionId)).toList()

        val interrupted = interruptedEvents.filterIsInstance<AgentEvent.Interrupted>().single()
        assertEquals(AgentInterruptionReason.PROVIDER_FAILURE, interrupted.interruption.reason)
        assertEquals(AgentFailureCode.PROVIDER_NETWORK, interrupted.interruption.provider?.code)
        assertEquals(
            ProviderInterruptionPhase.AFTER_FIRST_EVENT,
            interrupted.interruption.provider?.phase,
        )
        assertTrue(interrupted.state.allText().contains(PARTIAL_TEXT))
        val persisted = assertNotNull(persistence.load(sessionId))
        assertTrue(persisted.snapshot.state.allText().contains(PARTIAL_TEXT))
        assertFalse(assertNotNull(persisted.checkpoint).state.allText().contains(PARTIAL_TEXT))
        assertTrue(assertNotNull(runner.inspectRecovery(sessionId).state).allText().contains(PARTIAL_TEXT))

        val resumedEvents = runner.resume(sessionId).toList()
        val completed = resumedEvents.filterIsInstance<AgentEvent.Completed>().single().state
        assertTrue(completed.allText().contains(FINAL_TEXT))
        assertFalse(completed.allText().contains(PARTIAL_TEXT))
        assertEquals(2, provider.requests.size)
    }

    @Test
    fun providerUsageSurvivesCheckpointRollbackAndResumeWithoutDoubleCounting() = runTest {
        val sessionId = AgentSessionId("usage-resume")
        val persistence = InMemoryAgentPersistence()
        val provider = UsageThenNetworkFailureThenCompleteProvider()
        val runner = runner(provider, persistence)

        val interrupted = runner.run(request(sessionId)).toList()
            .filterIsInstance<AgentEvent.Interrupted>()
            .single()

        assertEquals(INTERRUPTED_USAGE, interrupted.state.usage)
        assertEquals(INTERRUPTED_USAGE, interrupted.state.latestRequestUsage)
        val persisted = assertNotNull(persistence.load(sessionId))
        assertEquals(INTERRUPTED_USAGE, persisted.snapshot.state.usage)
        assertEquals(TokenUsage(), assertNotNull(persisted.checkpoint).state.usage)

        val completed = runner.resume(sessionId).toList()
            .filterIsInstance<AgentEvent.Completed>()
            .single()
            .state

        assertEquals(CUMULATIVE_RESUMED_USAGE, completed.usage)
        assertEquals(RESUMED_ATTEMPT_USAGE, completed.latestRequestUsage)
        assertEquals(2, provider.requests.size)
    }

    @Test
    fun retryCountSurvivesInterruptionCheckpointRollbackAndResume() = runTest {
        val sessionId = AgentSessionId("retry-count-resume")
        val persistence = InMemoryAgentPersistence()
        val provider = RetryThenPartialFailureThenCompleteProvider()
        val runner = runner(provider, persistence, RetryOncePolicy())

        val interruptedEvents = runner.run(request(sessionId)).toList()

        val interrupted = interruptedEvents.filterIsInstance<AgentEvent.Interrupted>().single()
        assertEquals(1, interrupted.state.retryCount)
        val persisted = assertNotNull(persistence.load(sessionId))
        assertEquals(1, persisted.snapshot.state.retryCount)
        assertEquals(0, assertNotNull(persisted.checkpoint).state.retryCount)

        val completed = runner.resume(sessionId).toList()
            .filterIsInstance<AgentEvent.Completed>()
            .single()
            .state

        assertEquals(1, completed.retryCount)
        assertEquals(3, provider.requests.size)
    }

    @Test
    fun reattachProviderResumesWithTheSameInvocationIdentity() = runTest {
        val sessionId = AgentSessionId("reattach-interruption")
        val persistence = InMemoryAgentPersistence()
        val provider = PartialThenCompleteProvider(ProviderInvocationResumeMode.REATTACH)
        val runner = runner(provider, persistence)
        val collection = launch {
            try {
                runner.run(request(sessionId)).collect()
            } catch (_: CancellationException) {
                // The runtime converts host interruption into durable recovery state.
            }
        }
        provider.partialObserved.await()

        runner.interrupt(sessionId)
        withTimeout(2_000) { collection.join() }
        val completed = runner.resume(sessionId).toList()
            .filterIsInstance<AgentEvent.Completed>()
            .single()
            .state

        assertEquals(2, provider.requests.size)
        val invocationIds = provider.requests.map { assertNotNull(it.invocation).requestId }
        assertEquals(invocationIds[0], invocationIds[1])
        assertTrue(invocationIds[0].endsWith(":0:0"))
        assertEquals(
            listOf(ProviderInvocationIntent.CREATE, ProviderInvocationIntent.REATTACH),
            provider.requests.map(ProviderRequest::invocationIntent),
        )
        assertEquals(INTERRUPTED_USAGE, completed.usage)
        assertEquals(INTERRUPTED_USAGE, completed.latestRequestUsage)
    }

    @Test
    fun coldResumeWithUnknownDurableInvocationFailsClosedWithoutSwitchingIdentity() = runTest {
        val sessionId = AgentSessionId("unknown-durable-invocation")
        val persistence = InMemoryAgentPersistence()
        val provider = UnknownOnReattachProvider()
        val runner = runner(provider, persistence, RetryOncePolicy())
        val collection = launch {
            try {
                runner.run(request(sessionId)).collect()
            } catch (_: CancellationException) {
                // The detached invocation remains the only identity a cold resume may resolve.
            }
        }
        provider.firstStarted.await()
        runner.interrupt(sessionId)
        collection.join()

        val firstResume = runner.resume(sessionId).toList()
        val secondResume = runner.resume(sessionId).toList()

        assertTrue(firstResume.any { it is AgentEvent.Failed })
        assertTrue(firstResume.none { it is AgentEvent.RetryScheduled })
        assertTrue(secondResume.any { it is AgentEvent.Failed })
        assertEquals(2, provider.requests.size)
        val invocationIds = provider.requests.map { assertNotNull(it.invocation).requestId }
        assertEquals(invocationIds[0], invocationIds[1])
        assertEquals(
            listOf(ProviderInvocationIntent.CREATE, ProviderInvocationIntent.REATTACH),
            provider.requests.map(ProviderRequest::invocationIntent),
        )
    }

    @Test
    fun completedProviderAndTerminalStateShareOneDurableTransition() = runTest {
        val sessionId = AgentSessionId("provider-terminal-atomicity")
        val persistence = RecordingAgentPersistence()
        val provider = ReplayableCompletedProvider()

        runner(provider, persistence).run(request(sessionId)).toList()

        val pendingIndex = persistence.commits.indexOfFirst { record ->
            record.checkpoint?.cursor?.provider?.pending != null
        }
        assertTrue(pendingIndex >= 0)
        val pendingRecord = persistence.commits[pendingIndex]
        assertEquals(
            listOf(null),
            persistence.commits.drop(pendingIndex + 1).map(AgentPersistenceRecord::checkpoint),
        )

        val crashedPersistence = InMemoryAgentPersistence()
        crashedPersistence.commit(pendingRecord.snapshot, pendingRecord.checkpoint)
        val completed = runner(provider, crashedPersistence)
            .resume(sessionId)
            .toList()
            .filterIsInstance<AgentEvent.Completed>()
            .single()

        val pendingRequestId = assertNotNull(
            pendingRecord.checkpoint?.cursor?.provider?.pending?.requestId,
        )
        assertEquals(
            pendingRequestId,
            assertNotNull(provider.requests.last().invocation).requestId,
        )
        assertEquals(1, provider.requests.map { assertNotNull(it.invocation).requestId }.distinct().size)
        assertTrue(completed.state.allText().contains(FINAL_TEXT))
    }

    @Test
    fun completedProviderAndToolPhaseShareOneDurableTransition() = runTest {
        val sessionId = AgentSessionId("provider-tool-atomicity")
        val persistence = RecordingAgentPersistence()
        val initialTool = CountingRecoveryTool()
        val provider = ReplayableToolThenCompleteProvider()
        val agentRequest = request(
            sessionId = sessionId,
            tools = listOf(initialTool.definition),
        )

        runner(provider, persistence, tools = listOf(initialTool))
            .run(agentRequest)
            .toList()

        val pendingIndex = persistence.commits.indexOfFirst { record ->
            record.checkpoint?.cursor?.provider?.pending != null
        }
        assertTrue(pendingIndex >= 0)
        val pendingRecord = persistence.commits[pendingIndex]
        val toolPhase = assertNotNull(persistence.commits[pendingIndex + 1].checkpoint)
        assertEquals(AgentResumePhase.TOOLS_PENDING, toolPhase.cursor.phase)
        assertEquals(null, toolPhase.cursor.provider.pending)
        assertEquals(listOf(TOOL_CALL_ID), toolPhase.state.pendingToolCalls.map { it.toolCallId })

        val crashedPersistence = InMemoryAgentPersistence()
        crashedPersistence.commit(pendingRecord.snapshot, pendingRecord.checkpoint)
        val resumedTool = CountingRecoveryTool()
        val completed = runner(
            provider = provider,
            persistence = crashedPersistence,
            tools = listOf(resumedTool),
        ).resume(sessionId).toList()

        val pendingRequestId = assertNotNull(
            pendingRecord.checkpoint?.cursor?.provider?.pending?.requestId,
        )
        assertEquals(
            pendingRequestId,
            assertNotNull(provider.requests[2].invocation).requestId,
        )
        assertEquals(
            provider.requests.take(2).map { assertNotNull(it.invocation).requestId },
            provider.requests.drop(2).map { assertNotNull(it.invocation).requestId },
        )
        assertEquals(1, resumedTool.executionCount)
        assertTrue(completed.any { it is AgentEvent.Completed })
    }

    @Test
    fun orphanedRunningSnapshotIsDetectedAndCanResumeFromItsCheckpoint() = runTest {
        val sessionId = AgentSessionId("orphaned-process")
        val persistence = InMemoryAgentPersistence()
        val request = request(sessionId)
        val runId = AgentRunId("orphaned-run")
        val state = AgentStateSnapshot(
            messages = request.messages,
            status = AgentStatus.RUNNING,
            stopReason = StopReason.COMPLETED,
        )
        persistence.commit(
            AgentSessionSnapshot(sessionId, runId, request, state),
            AgentCheckpoint(
                sessionId,
                runId,
                AgentResumeCursor(0, AgentResumePhase.MODEL_PENDING),
                state,
            ),
        )
        val provider = RecordingCompleteProvider()
        val runner = runner(provider, persistence)

        val recovery = runner.inspectRecovery(sessionId)

        assertEquals(AgentRecoveryDisposition.RESUMABLE, recovery.disposition)
        assertEquals(AgentInterruptionReason.ORPHANED, recovery.interruption?.reason)
        runner.resume(sessionId).toList()
        assertEquals("orphaned-run:0:0", provider.requests.single().invocation?.requestId)
    }

    @Test
    fun inconsistentCheckpointPhaseIsBlockedBeforeAnyWorkRuns() = runTest {
        val sessionId = AgentSessionId("inconsistent-checkpoint")
        val call = ToolCallPart(
            toolCallId = TOOL_CALL_ID,
            toolName = TOOL_NAME,
            arguments = buildJsonObject { put("value", "recover") },
        )
        val request = request(sessionId, tools = listOf(CountingRecoveryTool().definition))
        val state = AgentStateSnapshot(
            messages = request.messages,
            pendingToolCalls = listOf(call),
            status = AgentStatus.WAITING_FOR_TOOLS,
            stopReason = StopReason.TOOL_CALLS,
        )
        val runId = AgentRunId("inconsistent-run")
        val persistence = InMemoryAgentPersistence()
        persistence.commit(
            AgentSessionSnapshot(sessionId, runId, request, state),
            AgentCheckpoint(
                sessionId = sessionId,
                runId = runId,
                cursor = AgentResumeCursor(0, AgentResumePhase.TOOLS_PENDING),
                state = state,
                toolExecutions = emptyList(),
            ),
        )
        val provider = RecordingCompleteProvider()
        val runner = runner(provider, persistence)

        val recovery = runner.inspectRecovery(sessionId)
        val resumed = runner.resume(sessionId).toList()

        assertEquals(AgentRecoveryDisposition.BLOCKED, recovery.disposition)
        assertEquals(AgentRecoveryBlockReason.CHECKPOINT_MISMATCH, recovery.blockedReason)
        assertEquals(
            AgentRecoveryBlockReason.CHECKPOINT_MISMATCH,
            resumed.filterIsInstance<AgentEvent.RecoveryBlocked>().single().reason,
        )
        assertTrue(provider.requests.isEmpty())
    }

    @Test
    fun pendingAndCompletedToolJournalEntriesNeverDuplicateSideEffects() = runTest {
        val pendingTool = CountingRecoveryTool(ToolRecoveryPolicy.FAIL_CLOSED)
        val pending = toolFixture(ToolExecutionState.PENDING, pendingTool)

        val pendingEvents = pending.runner.resume(pending.sessionId).toList()

        assertEquals(1, pendingTool.executionCount)
        assertEquals(1, pendingEvents.filterIsInstance<AgentEvent.ToolCompleted>().size)

        val completedResult = ToolExecutionResult(
            toolCallId = TOOL_CALL_ID,
            toolName = TOOL_NAME,
            result = JsonPrimitive("already completed"),
        )
        val completedTool = CountingRecoveryTool(ToolRecoveryPolicy.FAIL_CLOSED)
        val completed = toolFixture(
            state = ToolExecutionState.COMPLETED,
            tool = completedTool,
            result = completedResult,
        )

        val completedEvents = completed.runner.resume(completed.sessionId).toList()

        assertEquals(0, completedTool.executionCount)
        assertEquals(
            completedResult,
            completedEvents.filterIsInstance<AgentEvent.ToolCompleted>().single().result,
        )
    }

    @Test
    fun startedToolBlocksByDefaultButExplicitReplaySafeToolCanResume() = runTest {
        val unsafeTool = CountingRecoveryTool(ToolRecoveryPolicy.FAIL_CLOSED)
        val unsafe = toolFixture(ToolExecutionState.STARTED, unsafeTool)

        val blocked = unsafe.runner.inspectRecovery(unsafe.sessionId)
        val blockedEvents = unsafe.runner.resume(unsafe.sessionId).toList()

        assertEquals(AgentRecoveryDisposition.BLOCKED, blocked.disposition)
        assertEquals(AgentRecoveryBlockReason.TOOL_OUTCOME_UNKNOWN, blocked.blockedReason)
        assertEquals(0, unsafeTool.executionCount)
        assertEquals(
            AgentRecoveryBlockReason.TOOL_OUTCOME_UNKNOWN,
            blockedEvents.filterIsInstance<AgentEvent.RecoveryBlocked>().single().reason,
        )

        val replaySafeTool = CountingRecoveryTool(ToolRecoveryPolicy.REPLAY_SAFE)
        val replaySafe = toolFixture(ToolExecutionState.STARTED, replaySafeTool)

        assertEquals(
            AgentRecoveryDisposition.RESUMABLE,
            replaySafe.runner.inspectRecovery(replaySafe.sessionId).disposition,
        )
        replaySafe.runner.resume(replaySafe.sessionId).toList()
        assertEquals(1, replaySafeTool.executionCount)
    }

    @Test
    fun cancellingAnInactiveInterruptedRunMakesItTerminalAndRemovesItsCheckpoint() = runTest {
        val sessionId = AgentSessionId("cancel-interrupted")
        val persistence = InMemoryAgentPersistence()
        val request = request(sessionId)
        val runId = AgentRunId("cancel-interrupted-run")
        val interruption = AgentInterruption(
            reason = AgentInterruptionReason.PROVIDER_FAILURE,
            provider = ProviderInterruption(
                code = AgentFailureCode.PROVIDER_NETWORK,
                phase = ProviderInterruptionPhase.AFTER_FIRST_EVENT,
            ),
        )
        val state = AgentStateSnapshot(
            messages = request.messages,
            status = AgentStatus.INTERRUPTED,
            stopReason = StopReason.INTERRUPTED,
        )
        persistence.commit(
            AgentSessionSnapshot(
                sessionId = sessionId,
                runId = runId,
                request = request,
                state = state,
                interruption = interruption,
            ),
            AgentCheckpoint(
                sessionId = sessionId,
                runId = runId,
                cursor = AgentResumeCursor(0, AgentResumePhase.MODEL_PENDING),
                state = state.copy(
                    status = AgentStatus.RUNNING,
                    stopReason = null,
                ),
            ),
        )
        val runner = runner(RecordingCompleteProvider(), persistence)

        runner.cancel(sessionId)

        val cancelled = assertNotNull(persistence.load(sessionId))
        assertEquals(AgentStatus.CANCELLED, cancelled.snapshot.state.status)
        assertEquals(StopReason.CANCELLED, cancelled.snapshot.state.stopReason)
        assertEquals(null, cancelled.snapshot.interruption)
        assertEquals(null, cancelled.checkpoint)
        assertEquals(
            AgentRecoveryDisposition.TERMINAL,
            runner.inspectRecovery(sessionId).disposition,
        )
    }

    private suspend fun toolFixture(
        state: ToolExecutionState,
        tool: CountingRecoveryTool,
        result: ToolExecutionResult? = null,
    ): ToolFixture {
        val sessionId = AgentSessionId("tool-${state.name.lowercase()}-${tool.recoveryPolicy.name.lowercase()}")
        val call = ToolCallPart(
            toolCallId = TOOL_CALL_ID,
            toolName = TOOL_NAME,
            arguments = buildJsonObject { put("value", "recover") },
        )
        val request = request(
            sessionId = sessionId,
            tools = listOf(tool.definition),
            maxTurns = 1,
        )
        val assistant = AgentMessage(
            id = "tool-assistant",
            role = MessageRole.ASSISTANT,
            parts = listOf(call),
            stopReason = StopReason.TOOL_CALLS,
        )
        val agentState = AgentStateSnapshot(
            messages = request.messages + assistant,
            pendingToolCalls = listOf(call),
            status = AgentStatus.WAITING_FOR_TOOLS,
            stopReason = StopReason.TOOL_CALLS,
        )
        val runId = AgentRunId("tool-run-${state.name.lowercase()}")
        val journal = ToolExecutionRecord(
            executionId = "tool-execution",
            toolCallId = TOOL_CALL_ID,
            toolName = TOOL_NAME,
            callOrdinal = 1,
            state = state,
            result = result,
        )
        val persistence = InMemoryAgentPersistence()
        persistence.commit(
            AgentSessionSnapshot(sessionId, runId, request, agentState),
            AgentCheckpoint(
                sessionId = sessionId,
                runId = runId,
                cursor = AgentResumeCursor(0, AgentResumePhase.TOOLS_PENDING),
                state = agentState,
                toolExecutions = listOf(journal),
            ),
        )
        val runner = DefaultAgentRunner(
            providerRegistry = InMemoryProviderRegistry(listOf(NoCallProvider())),
            toolRegistry = InMemoryToolRegistry(listOf(tool)),
            persistence = persistence,
        )
        return ToolFixture(sessionId, runner)
    }

    private fun runner(
        provider: ProviderAdapter,
        persistence: AgentPersistence,
        retryPolicy: RetryPolicy = saien.magrathea.core.NoopRetryPolicy,
        tools: List<ToolExecutor> = emptyList(),
    ) = DefaultAgentRunner(
        providerRegistry = InMemoryProviderRegistry(listOf(provider)),
        toolRegistry = InMemoryToolRegistry(tools),
        persistence = persistence,
        retryPolicy = retryPolicy,
    )

    private fun request(
        sessionId: AgentSessionId,
        tools: List<ToolDefinition> = emptyList(),
        maxTurns: Int = 2,
        runtime: RuntimeConfig = RuntimeConfig(maxTurns = maxTurns),
        providerTimeouts: ProviderTimeoutConfig = ProviderTimeoutConfig(),
    ) = AgentRequest(
        sessionId = sessionId,
        messages = listOf(
            AgentMessage(role = MessageRole.USER, parts = listOf(TextPart("recover me"))),
        ),
        model = ModelDescriptor(PROVIDER_KEY, "recovery-model", supportsStreaming = true),
        tools = tools,
        engine = AgentEngineConfig(
            provider = ProviderConfig(timeouts = providerTimeouts),
            runtime = runtime,
        ),
    )

    private suspend fun persistRecoverableSession(
        persistence: AgentPersistence,
        sessionId: AgentSessionId,
        pendingInvocation: Boolean = false,
        providerTimeouts: ProviderTimeoutConfig = ProviderTimeoutConfig(),
    ) {
        val request = request(sessionId, providerTimeouts = providerTimeouts)
        val runId = AgentRunId("${sessionId.value}-run")
        val state = AgentStateSnapshot(
            messages = request.messages,
            status = AgentStatus.RUNNING,
        )
        val providerCursor = if (pendingInvocation) {
            AgentProviderInvocationCursor(
                nextPhysicalAttempt = 1,
                pending = AgentPendingProviderInvocation(
                    requestId = "${runId.value}:0:0",
                    purpose = ProviderRequestPurpose.MODEL,
                    inputIdentity = "durable-input",
                ),
            )
        } else {
            AgentProviderInvocationCursor()
        }
        persistence.commit(
            snapshot = AgentSessionSnapshot(
                sessionId = sessionId,
                runId = runId,
                request = request,
                state = state,
            ),
            checkpoint = AgentCheckpoint(
                sessionId = sessionId,
                runId = runId,
                cursor = AgentResumeCursor(
                    turn = 0,
                    phase = if (pendingInvocation) {
                        AgentResumePhase.MODEL_PENDING
                    } else {
                        AgentResumePhase.TURN_PREPARING
                    },
                    provider = providerCursor,
                ),
                state = state,
            ),
        )
    }

    private data class ToolFixture(
        val sessionId: AgentSessionId,
        val runner: DefaultAgentRunner,
    )

    private class PartialThenCompleteProvider(
        override val invocationResumeMode: ProviderInvocationResumeMode =
            ProviderInvocationResumeMode.NEW_ATTEMPT,
    ) : ProviderAdapter {
        override val key: String = PROVIDER_KEY
        val partialObserved = CompletableDeferred<Unit>()
        val cancellationIntent = CompletableDeferred<ProviderCancellationIntent>()
        val requests = mutableListOf<ProviderRequest>()

        override suspend fun generate(request: ProviderRequest): Flow<ProviderChunk> = flow {
            requests += request
            if (requests.size == 1) {
                emit(
                    providerChunk(
                        text = PARTIAL_TEXT,
                        usage = ProviderUsage(inputTokens = 7, outputTokens = 2),
                    ),
                )
                partialObserved.complete(Unit)
                try {
                    awaitCancellation()
                } catch (cancelled: CancellationException) {
                    cancellationIntent.complete(coroutineContext.providerCancellationIntent())
                    throw cancelled
                }
            } else if (invocationResumeMode == ProviderInvocationResumeMode.REATTACH) {
                // A durable reattachment starts from the canonical stream origin. Usage replay
                // must replace the rolled-back attempt accounting instead of adding to it.
                emit(providerChunk(usage = ProviderUsage(inputTokens = 7, outputTokens = 2)))
                emit(providerChunk(text = FINAL_TEXT, completed = true))
            } else {
                emit(
                    providerChunk(
                        text = FINAL_TEXT,
                        completed = true,
                        usage = ProviderUsage(inputTokens = 7, outputTokens = 3),
                    ),
                )
            }
        }
    }

    private class PartialNetworkFailureThenCompleteProvider : ProviderAdapter {
        override val key: String = PROVIDER_KEY
        val requests = mutableListOf<ProviderRequest>()

        override suspend fun generate(request: ProviderRequest): Flow<ProviderChunk> = flow {
            requests += request
            if (requests.size == 1) {
                emit(providerChunk(text = PARTIAL_TEXT))
                throw ProviderNetworkException("connection lost after partial output")
            }
            emit(providerChunk(text = FINAL_TEXT, completed = true))
        }
    }

    private class DelayedCancellationObservationProvider : ProviderAdapter {
        override val key: String = PROVIDER_KEY
        val started = CompletableDeferred<Unit>()
        val cancellationCaught = CompletableDeferred<Unit>()
        val observeIntent = CompletableDeferred<Unit>()
        val cancellationIntent = CompletableDeferred<ProviderCancellationIntent>()

        override suspend fun generate(request: ProviderRequest): Flow<ProviderChunk> = flow {
            started.complete(Unit)
            try {
                awaitCancellation()
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable) {
                    cancellationCaught.complete(Unit)
                    observeIntent.await()
                    cancellationIntent.complete(coroutineContext.providerCancellationIntent())
                }
                throw cancelled
            }
        }
    }

    private class GatewayLikeCancellationProvider(
        private val persistence: AgentPersistence,
        private val sessionId: AgentSessionId,
    ) : ProviderAdapter {
        override val key: String = PROVIDER_KEY
        override val invocationResumeMode: ProviderInvocationResumeMode =
            ProviderInvocationResumeMode.REATTACH
        val started = CompletableDeferred<Unit>()
        val cancellationIntent = CompletableDeferred<ProviderCancellationIntent>()
        val abandoned = mutableListOf<ProviderInvocation>()
        var inlineRemoteCleanup: Boolean = false
        var recordWhenAbandoned: AgentPersistenceRecord? = null

        override suspend fun generate(request: ProviderRequest): Flow<ProviderChunk> = flow {
            started.complete(Unit)
            try {
                awaitCancellation()
            } catch (cancelled: CancellationException) {
                val intent = coroutineContext.providerCancellationIntent()
                cancellationIntent.complete(intent)
                if (intent == ProviderCancellationIntent.CANCEL) {
                    inlineRemoteCleanup = true
                }
                throw cancelled
            }
        }

        override suspend fun abandon(invocation: ProviderInvocation) {
            abandoned += invocation
            recordWhenAbandoned = persistence.load(sessionId)
        }
    }

    private class ExpiredReattachThenCompleteProvider : ProviderAdapter {
        override val key: String = PROVIDER_KEY
        override val invocationResumeMode: ProviderInvocationResumeMode =
            ProviderInvocationResumeMode.REATTACH
        val firstStarted = CompletableDeferred<Unit>()
        val requests = mutableListOf<ProviderRequest>()

        override suspend fun generate(request: ProviderRequest): Flow<ProviderChunk> = flow {
            requests += request
            when (requests.size) {
                1 -> {
                    emit(
                        providerChunk(
                            text = PARTIAL_TEXT,
                            usage = ProviderUsage(inputTokens = 7, outputTokens = 2),
                        ),
                    )
                    firstStarted.complete(Unit)
                    awaitCancellation()
                }
                2 -> throw ProviderInvocationInvalidatedException(
                    ProviderNetworkException("durable invocation lease expired"),
                    retryable = true,
                )
                else -> emit(
                    providerChunk(
                        text = FINAL_TEXT,
                        completed = true,
                        usage = ProviderUsage(inputTokens = 7, outputTokens = 3),
                    ),
                )
            }
        }
    }

    private class NetworkFailureThenReattachProvider : ProviderAdapter {
        override val key: String = PROVIDER_KEY
        override val invocationResumeMode: ProviderInvocationResumeMode =
            ProviderInvocationResumeMode.REATTACH
        val requests = mutableListOf<ProviderRequest>()

        override suspend fun generate(request: ProviderRequest): Flow<ProviderChunk> = flow {
            requests += request
            if (requests.size == 1) {
                throw ProviderNetworkException("create response was lost")
            }
            emit(providerChunk(text = FINAL_TEXT, completed = true))
        }
    }

    private class RetryableReattachProvider(
        private val abandonNeverCompletes: Boolean = false,
    ) : ProviderAdapter {
        override val key: String = PROVIDER_KEY
        override val invocationResumeMode: ProviderInvocationResumeMode =
            ProviderInvocationResumeMode.REATTACH
        val requests = mutableListOf<ProviderRequest>()
        val abandoned = mutableListOf<ProviderInvocation>()
        val abandonStarted = CompletableDeferred<Unit>()
        val abandonCancelled = CompletableDeferred<Unit>()

        override suspend fun generate(request: ProviderRequest): Flow<ProviderChunk> = flow {
            requests += request
            throw ProviderNetworkException("durable invocation is temporarily unavailable")
        }

        override suspend fun abandon(invocation: ProviderInvocation) {
            abandonStarted.complete(Unit)
            if (abandonNeverCompletes) {
                try {
                    awaitCancellation()
                } finally {
                    abandonCancelled.complete(Unit)
                }
            } else {
                abandoned += invocation
            }
        }
    }

    private class RetryableInvalidatedThenCompleteProvider : ProviderAdapter {
        override val key: String = PROVIDER_KEY
        override val invocationResumeMode: ProviderInvocationResumeMode =
            ProviderInvocationResumeMode.REATTACH
        val requests = mutableListOf<ProviderRequest>()

        override suspend fun generate(request: ProviderRequest): Flow<ProviderChunk> = flow {
            requests += request
            if (requests.size == 1) {
                throw ProviderInvocationInvalidatedException(
                    ProviderNetworkException("invocation is no longer available"),
                    retryable = true,
                )
            }
            emit(providerChunk(text = FINAL_TEXT, completed = true))
        }
    }

    private class UnknownOnReattachProvider : ProviderAdapter {
        override val key: String = PROVIDER_KEY
        override val invocationResumeMode: ProviderInvocationResumeMode =
            ProviderInvocationResumeMode.REATTACH
        val firstStarted = CompletableDeferred<Unit>()
        val requests = mutableListOf<ProviderRequest>()

        override suspend fun generate(request: ProviderRequest): Flow<ProviderChunk> = flow {
            requests += request
            if (requests.size == 1) {
                firstStarted.complete(Unit)
                awaitCancellation()
            }
            throw ProviderInvocationInvalidatedException(
                ProviderProtocolException("durable invocation identity is unknown"),
                retryable = false,
            )
        }
    }

    private class PermanentlyInvalidatedProvider : ProviderAdapter {
        override val key: String = PROVIDER_KEY
        override val invocationResumeMode: ProviderInvocationResumeMode =
            ProviderInvocationResumeMode.REATTACH
        val requests = mutableListOf<ProviderRequest>()

        override suspend fun generate(request: ProviderRequest): Flow<ProviderChunk> = flow {
            requests += request
            throw ProviderInvocationInvalidatedException(
                ProviderProtocolException("permanent remote terminal"),
                retryable = false,
            )
        }
    }

    private class ReplayedUsageThenInvalidatedProvider : ProviderAdapter {
        override val key: String = PROVIDER_KEY
        override val invocationResumeMode: ProviderInvocationResumeMode =
            ProviderInvocationResumeMode.REATTACH
        val firstStarted = CompletableDeferred<Unit>()
        val requests = mutableListOf<ProviderRequest>()

        override suspend fun generate(request: ProviderRequest): Flow<ProviderChunk> = flow {
            requests += request
            when (requests.size) {
                1 -> {
                    emit(
                        providerChunk(
                            text = PARTIAL_TEXT,
                            usage = ProviderUsage(inputTokens = 7, outputTokens = 2),
                        ),
                    )
                    firstStarted.complete(Unit)
                    awaitCancellation()
                }
                2 -> {
                    emit(
                        providerChunk(
                            text = PARTIAL_TEXT,
                            usage = ProviderUsage(inputTokens = 7, outputTokens = 2),
                        ),
                    )
                    throw ProviderInvocationInvalidatedException(
                        ProviderNetworkException("invocation terminated during reattachment"),
                        retryable = true,
                    )
                }
                else -> emit(
                    providerChunk(
                        text = FINAL_TEXT,
                        completed = true,
                        usage = ProviderUsage(inputTokens = 7, outputTokens = 3),
                    ),
                )
            }
        }
    }

    private class UsageThenNetworkFailureThenCompleteProvider : ProviderAdapter {
        override val key: String = PROVIDER_KEY
        val requests = mutableListOf<ProviderRequest>()

        override suspend fun generate(request: ProviderRequest): Flow<ProviderChunk> = flow {
            requests += request
            if (requests.size == 1) {
                emit(
                    providerChunk(
                        text = PARTIAL_TEXT,
                        usage = ProviderUsage(inputTokens = 7, outputTokens = 2),
                    ),
                )
                throw ProviderNetworkException("connection lost after metered output")
            }
            emit(
                providerChunk(
                    text = FINAL_TEXT,
                    completed = true,
                    usage = ProviderUsage(inputTokens = 7, outputTokens = 3),
                ),
            )
        }
    }

    private class RetryThenPartialFailureThenCompleteProvider : ProviderAdapter {
        override val key: String = PROVIDER_KEY
        val requests = mutableListOf<ProviderRequest>()

        override suspend fun generate(request: ProviderRequest): Flow<ProviderChunk> = flow {
            requests += request
            when (requests.size) {
                1 -> throw ProviderNetworkException("retry before output")
                2 -> {
                    emit(providerChunk(text = PARTIAL_TEXT))
                    throw ProviderNetworkException("connection lost after retry output")
                }
                else -> emit(providerChunk(text = FINAL_TEXT, completed = true))
            }
        }
    }

    private class RetryOncePolicy : RetryPolicy {
        override suspend fun shouldRetry(attempt: Int, error: Throwable): Boolean = attempt == 1

        override suspend fun backoffDelayMs(attempt: Int, error: Throwable): Long = 0L
    }

    private class BlockingBackoffRetryPolicy : RetryPolicy {
        val backoffStarted = CompletableDeferred<Unit>()

        override suspend fun shouldRetry(attempt: Int, error: Throwable): Boolean = true

        override suspend fun backoffDelayMs(attempt: Int, error: Throwable): Long {
            backoffStarted.complete(Unit)
            awaitCancellation()
        }
    }

    private class RecordingCompleteProvider : ProviderAdapter {
        override val key: String = PROVIDER_KEY
        val requests = mutableListOf<ProviderRequest>()

        override suspend fun generate(request: ProviderRequest): Flow<ProviderChunk> = flow {
            requests += request
            emit(providerChunk(text = FINAL_TEXT, completed = true))
        }
    }

    private class ReplayableCompletedProvider : ProviderAdapter {
        override val key: String = PROVIDER_KEY
        override val invocationResumeMode: ProviderInvocationResumeMode =
            ProviderInvocationResumeMode.REATTACH
        val requests = mutableListOf<ProviderRequest>()

        override suspend fun generate(request: ProviderRequest): Flow<ProviderChunk> = flow {
            requests += request
            emit(providerChunk(text = FINAL_TEXT, completed = true))
        }
    }

    private class ReplayableToolThenCompleteProvider : ProviderAdapter {
        override val key: String = PROVIDER_KEY
        override val invocationResumeMode: ProviderInvocationResumeMode =
            ProviderInvocationResumeMode.REATTACH
        val requests = mutableListOf<ProviderRequest>()

        override suspend fun generate(request: ProviderRequest): Flow<ProviderChunk> = flow {
            requests += request
            if (request.messages.lastOrNull()?.role == MessageRole.TOOL) {
                emit(providerChunk(text = FINAL_TEXT, completed = true))
            } else {
                emit(
                    providerChunk(
                        toolCalls = listOf(
                            ToolCallPart(
                                toolCallId = TOOL_CALL_ID,
                                toolName = TOOL_NAME,
                                arguments = buildJsonObject { put("value", "recover") },
                            ),
                        ),
                        completed = true,
                    ),
                )
            }
        }
    }

    private class RecordingAgentPersistence : AgentPersistence {
        private val delegate = InMemoryAgentPersistence()
        val commits = mutableListOf<AgentPersistenceRecord>()

        override suspend fun commit(
            snapshot: AgentSessionSnapshot,
            checkpoint: AgentCheckpoint?,
        ) {
            commits += AgentPersistenceRecord(snapshot, checkpoint)
            delegate.commit(snapshot, checkpoint)
        }

        override suspend fun load(sessionId: AgentSessionId): AgentPersistenceRecord? =
            delegate.load(sessionId)

        override suspend fun listSessions(): List<AgentSessionSnapshot> = delegate.listSessions()

        override suspend fun deleteSession(sessionId: AgentSessionId) = delegate.deleteSession(sessionId)

        override suspend fun clear() = delegate.clear()
    }

    private inline fun <T> List<T>.indexOfFirstAfter(
        index: Int,
        predicate: (T) -> Boolean,
    ): Int {
        if (index < 0) return -1
        val relative = drop(index + 1).indexOfFirst(predicate)
        return if (relative < 0) -1 else index + 1 + relative
    }

    private class BlockingFirstLoadPersistence(
        private val delegate: AgentPersistence,
    ) : AgentPersistence {
        val firstLoadStarted = CompletableDeferred<Unit>()
        val allowFirstLoad = CompletableDeferred<Unit>()
        private var blockNextLoad = true

        override suspend fun commit(
            snapshot: AgentSessionSnapshot,
            checkpoint: AgentCheckpoint?,
        ) = delegate.commit(snapshot, checkpoint)

        override suspend fun load(sessionId: AgentSessionId): AgentPersistenceRecord? {
            if (blockNextLoad) {
                blockNextLoad = false
                firstLoadStarted.complete(Unit)
                allowFirstLoad.await()
            }
            return delegate.load(sessionId)
        }

        override suspend fun listSessions(): List<AgentSessionSnapshot> = delegate.listSessions()

        override suspend fun deleteSession(sessionId: AgentSessionId) = delegate.deleteSession(sessionId)

        override suspend fun clear() = delegate.clear()
    }

    private class FailingTerminalCommitPersistence(
        private val delegate: AgentPersistence,
        private val failedStatus: AgentStatus,
    ) : AgentPersistence {
        override suspend fun commit(
            snapshot: AgentSessionSnapshot,
            checkpoint: AgentCheckpoint?,
        ) {
            if (snapshot.state.status == failedStatus) {
                error("terminal commit unavailable")
            }
            delegate.commit(snapshot, checkpoint)
        }

        override suspend fun load(sessionId: AgentSessionId): AgentPersistenceRecord? =
            delegate.load(sessionId)

        override suspend fun listSessions(): List<AgentSessionSnapshot> = delegate.listSessions()

        override suspend fun deleteSession(sessionId: AgentSessionId) = delegate.deleteSession(sessionId)

        override suspend fun clear() = delegate.clear()
    }

    private class PersistenceObservingAbandonProvider(
        private val persistence: AgentPersistence,
        private val sessionId: AgentSessionId,
    ) : ProviderAdapter {
        override val key: String = PROVIDER_KEY
        override val invocationResumeMode: ProviderInvocationResumeMode =
            ProviderInvocationResumeMode.REATTACH
        var observedRecord: AgentPersistenceRecord? = null

        override suspend fun generate(request: ProviderRequest): Flow<ProviderChunk> =
            error("Provider must not be called while cancelling an inactive session")

        override suspend fun abandon(invocation: ProviderInvocation) {
            observedRecord = persistence.load(sessionId)
        }
    }

    private class HangingAbandonProvider : ProviderAdapter {
        override val key: String = PROVIDER_KEY
        override val invocationResumeMode: ProviderInvocationResumeMode =
            ProviderInvocationResumeMode.REATTACH
        val abandonStarted = CompletableDeferred<Unit>()
        val abandonCancelled = CompletableDeferred<Unit>()

        override suspend fun generate(request: ProviderRequest): Flow<ProviderChunk> =
            error("Provider must not be called while cancelling an inactive session")

        override suspend fun abandon(invocation: ProviderInvocation) {
            abandonStarted.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                abandonCancelled.complete(Unit)
            }
        }
    }

    private class AbandonTrackingProvider : ProviderAdapter {
        override val key: String = PROVIDER_KEY
        override val invocationResumeMode: ProviderInvocationResumeMode =
            ProviderInvocationResumeMode.REATTACH
        val started = CompletableDeferred<Unit>()
        val abandoned = mutableListOf<ProviderInvocation>()

        override suspend fun generate(request: ProviderRequest): Flow<ProviderChunk> = flow {
            started.complete(Unit)
            awaitCancellation()
        }

        override suspend fun abandon(invocation: ProviderInvocation) {
            abandoned += invocation
        }
    }

    private class NoCallProvider : ProviderAdapter {
        override val key: String = PROVIDER_KEY

        override suspend fun generate(request: ProviderRequest): Flow<ProviderChunk> =
            error("Provider must not be called while recovering a pending Tool phase")
    }

    private class CountingRecoveryTool(
        override val recoveryPolicy: ToolRecoveryPolicy = ToolRecoveryPolicy.FAIL_CLOSED,
    ) : ToolExecutor {
        override val definition = ToolDefinition(
            name = TOOL_NAME,
            description = "Recovery contract Tool",
            schema = buildJsonObject { },
        )
        var executionCount = 0

        override suspend fun execute(request: ToolExecutionRequest): ToolExecutionResult {
            executionCount += 1
            return ToolExecutionResult(
                toolCallId = request.toolCall.toolCallId,
                toolName = request.toolCall.toolName,
                result = JsonPrimitive("executed"),
            )
        }
    }

    private fun AgentStateSnapshot.allText(): String = messages
        .flatMap(AgentMessage::parts)
        .filterIsInstance<TextPart>()
        .joinToString(separator = "") { it.text }

    private companion object {
        const val PROVIDER_KEY = "recovery-provider"
        const val TOOL_NAME = "recovery_tool"
        const val TOOL_CALL_ID = "recovery-call"
        const val PARTIAL_TEXT = "provisional partial"
        const val FINAL_TEXT = "durable final"
        val INTERRUPTED_USAGE = TokenUsage(inputTokens = 7, outputTokens = 2)
        val RESUMED_ATTEMPT_USAGE = TokenUsage(inputTokens = 7, outputTokens = 3)
        val CUMULATIVE_RESUMED_USAGE = TokenUsage(inputTokens = 14, outputTokens = 5)
    }
}
