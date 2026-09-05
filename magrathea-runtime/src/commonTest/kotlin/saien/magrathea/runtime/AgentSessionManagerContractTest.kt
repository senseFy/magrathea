@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package saien.magrathea.runtime

import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import saien.magrathea.core.AgentCheckpoint
import saien.magrathea.core.AgentEvent
import saien.magrathea.core.AgentFailureCode
import saien.magrathea.core.AgentInterruption
import saien.magrathea.core.AgentInterruptionReason
import saien.magrathea.core.AgentPersistence
import saien.magrathea.core.AgentPersistenceRecord
import saien.magrathea.core.AgentRecoveryBlockReason
import saien.magrathea.core.AgentRecoveryDisposition
import saien.magrathea.core.AgentRecoveryInfo
import saien.magrathea.core.AgentRequest
import saien.magrathea.core.AgentRunId
import saien.magrathea.core.AgentRunner
import saien.magrathea.core.AgentSessionId
import saien.magrathea.core.AgentSessionSnapshot
import saien.magrathea.core.AgentStateSnapshot
import saien.magrathea.core.AgentStatus
import saien.magrathea.core.ModelDescriptor

class AgentSessionManagerContractTest {
    @Test
    fun concurrentAcquireCoalescesPerIdWithoutBlockingOtherIds() = runTest {
        val persistence = GatedAgentPersistence()
        val firstId = AgentSessionId("coalesced")
        val otherId = AgentSessionId("independent")
        persistence.seed(terminalSnapshot(firstId))
        persistence.seed(terminalSnapshot(otherId))
        val blockedLoad = persistence.blockNextLoad(firstId)
        val manager = DefaultAgentSessionManager(
            runner = GatedAgentRunner(),
            persistence = persistence,
            dispatcher = StandardTestDispatcher(testScheduler),
        )

        try {
            val firstAcquire = async { manager.acquire(firstId) }
            blockedLoad.entered.await()
            val followers = List(15) { async { manager.acquire(firstId) } }
            val otherAcquire = async { manager.acquire(otherId) }

            runCurrent()

            assertTrue(otherAcquire.isCompleted)
            assertFalse(firstAcquire.isCompleted)
            assertTrue(followers.none { it.isCompleted })
            assertEquals(1, persistence.loadCalls(firstId))
            assertEquals(2, persistence.loadCalls(otherId))

            blockedLoad.release.complete(Unit)
            val firstLease = firstAcquire.await()
            val coalescedLeases = listOf(firstLease) + followers.map { it.await() }
            val otherLease = otherAcquire.await()

            assertEquals(2, persistence.loadCalls(firstId))
            assertTrue(coalescedLeases.drop(1).all { lease ->
                lease !== firstLease &&
                    lease.state === firstLease.state &&
                    lease.events === firstLease.events
            })

            coalescedLeases.forEach { it.release() }
            otherLease.release()
        } finally {
            blockedLoad.release.complete(Unit)
            manager.close()
        }
    }

    @Test
    fun deleteWaitsForOpeningAndExistingLeaseHandoffs() = runTest {
        val persistence = GatedAgentPersistence()
        val runner = GatedAgentRunner()
        val manager = DefaultAgentSessionManager(
            runner = runner,
            persistence = persistence,
            dispatcher = StandardTestDispatcher(testScheduler),
        )

        try {
            listOf(false, true).forEachIndexed { index, useExistingRuntime ->
                val sessionId = AgentSessionId("handoff-fence-$index")
                persistence.seed(terminalSnapshot(sessionId))
                val retainingLease = if (useExistingRuntime) manager.acquire(sessionId) else null
                val deletionResult = CompletableDeferred<Result<Unit>>()
                var deletionStarted = false
                var deletionWasFenced = false
                val handoffContext = JobLookupContext {
                    if (deletionStarted || sessionId !in manager.liveSessionIds.value) return@JobLookupContext
                    deletionStarted = true
                    suspend { manager.delete(sessionId) }.startCoroutine(
                        object : Continuation<Unit> {
                            override val context: CoroutineContext = EmptyCoroutineContext

                            override fun resumeWith(result: Result<Unit>) {
                                deletionResult.complete(result)
                            }
                        },
                    )
                    deletionWasFenced = !deletionResult.isCompleted
                }
                var acquireResult: Result<AgentSessionLease>? = null
                suspend { manager.acquire(sessionId) }.startCoroutine(
                    object : Continuation<AgentSessionLease> {
                        override val context: CoroutineContext = handoffContext

                        override fun resumeWith(result: Result<AgentSessionLease>) {
                            acquireResult = result
                        }
                    },
                )

                assertTrue(handoffContext.jobLookups >= 1)
                assertTrue(deletionStarted)
                assertTrue(deletionWasFenced)

                val deliveredLease = assertNotNull(acquireResult).getOrThrow()
                deletionResult.await().getOrThrow()

                assertEquals(AgentSessionPhase.DELETED, deliveredLease.state.value.phase)
                assertRuntimeFailure(
                    AgentSessionErrorCode.DELETED,
                    assertNotNull(
                        runCatching { deliveredLease.inspectRecovery() }.exceptionOrNull(),
                    ),
                )
                retainingLease?.release()
                deliveredLease.release()
            }
        } finally {
            manager.close()
        }
    }

    @Test
    fun releasingLastLeaseAndObserverDoesNotInterruptActiveRunAndCanReconnect() = runTest {
        val persistence = GatedAgentPersistence()
        val runner = GatedAgentRunner()
        val sessionId = AgentSessionId("reconnect-active")
        val run = runner.planRun(sessionId)
        persistence.seed(terminalSnapshot(sessionId))
        val manager = DefaultAgentSessionManager(
            runner = runner,
            persistence = persistence,
            dispatcher = StandardTestDispatcher(testScheduler),
        )

        try {
            val original = manager.acquire(sessionId)
            original.start(request(sessionId))
            assertEquals(AgentSessionPhase.ACTIVE, original.state.value.phase)

            val observer = backgroundScope.launch {
                original.state.collect { }
            }
            runCurrent()
            observer.cancelAndJoin()
            original.release()
            runCurrent()

            assertEquals(0, runner.interruptCalls(sessionId))
            assertEquals(0, runner.cancelCalls(sessionId))
            assertTrue(sessionId in manager.liveSessionIds.value)

            val reconnected = manager.acquire(sessionId)
            assertTrue(reconnected.state === original.state)
            assertTrue(reconnected.events === original.events)
            assertEquals(AgentSessionPhase.ACTIVE, reconnected.state.value.phase)
            assertEquals(1, runner.runCalls(sessionId))

            run.allowCompletion.complete(Unit)
            reconnected.awaitIdle()
            assertEquals(AgentSessionPhase.TERMINAL, reconnected.state.value.phase)
            assertEquals(AgentStatus.COMPLETED, reconnected.state.value.state?.status)
            reconnected.release()
        } finally {
            run.allowFirstEvent.complete(Unit)
            run.allowCompletion.complete(Unit)
            manager.close()
        }
    }

    @Test
    fun deleteFencesOldGenerationAndAllowsASeparatedRecreate() = runTest {
        val persistence = GatedAgentPersistence()
        val runner = GatedAgentRunner()
        val sessionId = AgentSessionId("delete-fence")
        val activeRun = runner.planRun(sessionId)
        persistence.seed(terminalSnapshot(sessionId))
        val manager = DefaultAgentSessionManager(
            runner = runner,
            persistence = persistence,
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        val blockedDelete = persistence.blockDelete(sessionId)

        try {
            val oldLease = manager.acquire(sessionId)
            oldLease.start(request(sessionId))
            val deletion = async { manager.delete(sessionId) }
            blockedDelete.entered.await()
            val lateAcquire = async { runCatching { manager.acquire(sessionId) } }

            runCurrent()

            assertFalse(deletion.isCompleted)
            assertFalse(lateAcquire.isCompleted)
            assertEquals(1, runner.cancelCalls(sessionId))

            blockedDelete.release.complete(Unit)
            deletion.await()
            assertRuntimeFailure(
                AgentSessionErrorCode.NOT_FOUND,
                assertNotNull(lateAcquire.await().exceptionOrNull()),
            )

            val replacement = manager.create(sessionId)
            val companion = manager.acquire(sessionId)
            assertTrue(replacement.state === companion.state)
            assertRuntimeFailure(
                AgentSessionErrorCode.DELETED,
                assertNotNull(runCatching { oldLease.start(request(sessionId)) }.exceptionOrNull()),
            )

            oldLease.release()
            oldLease.release()
            replacement.release()
            runCurrent()

            assertTrue(sessionId in manager.liveSessionIds.value)
            assertTrue(companion.isAttached)
            companion.release()
            runCurrent()
            assertFalse(sessionId in manager.liveSessionIds.value)
        } finally {
            blockedDelete.release.complete(Unit)
            activeRun.allowCompletion.complete(Unit)
            manager.close()
        }
    }

    @Test
    fun failedPersistenceDeleteKeepsTheCommittedRuntimeInvalidation() = runTest {
        val persistence = GatedAgentPersistence()
        val sessionId = AgentSessionId("failed-delete-invalidation")
        persistence.seed(terminalSnapshot(sessionId))
        val blockedDelete = persistence.blockDelete(
            sessionId,
            failure = IllegalStateException("synthetic delete failure"),
        )
        val manager = DefaultAgentSessionManager(
            runner = GatedAgentRunner(),
            persistence = persistence,
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        val oldLease = manager.acquire(sessionId)

        try {
            val deletion = async { runCatching { manager.delete(sessionId) } }
            blockedDelete.entered.await()

            assertEquals(AgentSessionPhase.DELETED, oldLease.state.value.phase)
            assertTrue(manager.liveSessionIds.value.isEmpty())

            blockedDelete.release.complete(Unit)
            val failure = assertNotNull(deletion.await().exceptionOrNull())
            assertRuntimeFailure(AgentSessionErrorCode.STORAGE, failure)
            assertEquals(
                AgentSessionInvalidationScope.SESSION,
                assertIs<AgentSessionException>(failure).invalidationScope,
            )
            assertNotNull(persistence.load(sessionId))
            assertRuntimeFailure(
                AgentSessionErrorCode.DELETED,
                assertNotNull(runCatching { oldLease.inspectRecovery() }.exceptionOrNull()),
            )

            val replacement = manager.acquire(sessionId)
            assertTrue(replacement.state !== oldLease.state)
            assertEquals(AgentSessionPhase.TERMINAL, replacement.state.value.phase)
            oldLease.release()
            replacement.release()
        } finally {
            blockedDelete.release.complete(Unit)
            oldLease.release()
            manager.close()
        }
    }

    @Test
    fun failedPersistenceClearKeepsEveryCommittedRuntimeInvalidation() = runTest {
        val persistence = GatedAgentPersistence()
        val firstId = AgentSessionId("failed-clear-first")
        val secondId = AgentSessionId("failed-clear-second")
        persistence.seed(terminalSnapshot(firstId))
        persistence.seed(terminalSnapshot(secondId))
        val blockedClear = persistence.blockClear(
            failure = IllegalStateException("synthetic clear failure"),
        )
        val manager = DefaultAgentSessionManager(
            runner = GatedAgentRunner(),
            persistence = persistence,
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        val firstLease = manager.acquire(firstId)
        val secondLease = manager.acquire(secondId)

        try {
            val clearing = async { runCatching { manager.clear() } }
            blockedClear.entered.await()

            assertEquals(AgentSessionPhase.DELETED, firstLease.state.value.phase)
            assertEquals(AgentSessionPhase.DELETED, secondLease.state.value.phase)
            assertTrue(manager.liveSessionIds.value.isEmpty())

            blockedClear.release.complete(Unit)
            val failure = assertNotNull(clearing.await().exceptionOrNull())
            assertRuntimeFailure(AgentSessionErrorCode.STORAGE, failure)
            assertEquals(
                AgentSessionInvalidationScope.ALL_SESSIONS,
                assertIs<AgentSessionException>(failure).invalidationScope,
            )
            assertNotNull(persistence.load(firstId))
            assertNotNull(persistence.load(secondId))
            assertRuntimeFailure(
                AgentSessionErrorCode.DELETED,
                assertNotNull(runCatching { firstLease.inspectRecovery() }.exceptionOrNull()),
            )
            assertRuntimeFailure(
                AgentSessionErrorCode.DELETED,
                assertNotNull(runCatching { secondLease.inspectRecovery() }.exceptionOrNull()),
            )

            val replacement = manager.acquire(firstId)
            assertTrue(replacement.state !== firstLease.state)
            firstLease.release()
            secondLease.release()
            replacement.release()
        } finally {
            blockedClear.release.complete(Unit)
            firstLease.release()
            secondLease.release()
            manager.close()
        }
    }

    @Test
    fun commandsSerializePerSessionWhileOtherSessionsContinue() = runTest {
        val persistence = GatedAgentPersistence()
        val runner = GatedAgentRunner()
        val blockedId = AgentSessionId("blocked-command")
        val otherId = AgentSessionId("independent-command")
        val blockedRun = runner.planRun(blockedId, holdBeforeFirstEvent = true)
        val otherRun = runner.planRun(otherId)
        persistence.seed(terminalSnapshot(blockedId))
        persistence.seed(terminalSnapshot(otherId))
        val manager = DefaultAgentSessionManager(
            runner = runner,
            persistence = persistence,
            dispatcher = StandardTestDispatcher(testScheduler),
        )

        try {
            val blockedLease = manager.acquire(blockedId)
            val otherLease = manager.acquire(otherId)
            val firstStart = async { blockedLease.start(request(blockedId)) }
            blockedRun.entered.await()
            val duplicateStart = async {
                runCatching { blockedLease.start(request(blockedId)) }
            }
            val independentStart = async { otherLease.start(request(otherId)) }

            runCurrent()

            assertTrue(duplicateStart.isCompleted)
            assertRuntimeFailure(
                AgentSessionErrorCode.BUSY,
                assertNotNull(duplicateStart.await().exceptionOrNull()),
            )
            assertTrue(independentStart.isCompleted)
            independentStart.await()
            assertEquals(1, runner.runCalls(blockedId))
            assertEquals(1, runner.runCalls(otherId))

            blockedRun.allowFirstEvent.complete(Unit)
            firstStart.await()
            blockedRun.allowCompletion.complete(Unit)
            otherRun.allowCompletion.complete(Unit)
            blockedLease.awaitIdle()
            otherLease.awaitIdle()
            blockedLease.release()
            otherLease.release()
        } finally {
            blockedRun.allowFirstEvent.complete(Unit)
            blockedRun.allowCompletion.complete(Unit)
            otherRun.allowCompletion.complete(Unit)
            manager.close()
        }
    }

    @Test
    fun closeRejectsLateAdmissionBeforeActiveCleanupFinishes() = runTest {
        val persistence = GatedAgentPersistence()
        val runner = GatedAgentRunner()
        val firstId = AgentSessionId("close-first")
        val secondId = AgentSessionId("close-second")
        val lateId = AgentSessionId("close-late")
        val firstRun = runner.planRun(firstId)
        val secondRun = runner.planRun(secondId)
        val blockedInterrupt = runner.blockInterrupt(firstId)
        persistence.seed(terminalSnapshot(firstId))
        persistence.seed(terminalSnapshot(secondId))
        val manager = DefaultAgentSessionManager(
            runner = runner,
            persistence = persistence,
            dispatcher = StandardTestDispatcher(testScheduler),
        )

        val firstLease = manager.acquire(firstId)
        val secondLease = manager.acquire(secondId)
        firstLease.start(request(firstId))
        secondLease.start(request(secondId))

        try {
            val firstClose = async { runCatching { manager.close() } }
            blockedInterrupt.entered.await()
            val secondClose = async { runCatching { manager.close() } }
            val lateAcquire = async { runCatching { manager.acquire(firstId) } }
            val lateCreate = async { runCatching { manager.create(lateId) } }

            runCurrent()

            assertFalse(firstClose.isCompleted)
            assertFalse(secondClose.isCompleted)
            assertTrue(lateAcquire.isCompleted)
            assertTrue(lateCreate.isCompleted)
            assertRuntimeFailure(
                AgentSessionErrorCode.CLOSED,
                assertNotNull(lateAcquire.await().exceptionOrNull()),
            )
            assertRuntimeFailure(
                AgentSessionErrorCode.CLOSED,
                assertNotNull(lateCreate.await().exceptionOrNull()),
            )

            blockedInterrupt.release.complete(Unit)
            assertTrue(firstClose.await().isSuccess)
            assertTrue(secondClose.await().isSuccess)
            assertEquals(1, runner.interruptCalls(firstId))
            assertEquals(1, runner.interruptCalls(secondId))
        } finally {
            blockedInterrupt.release.complete(Unit)
            firstRun.allowCompletion.complete(Unit)
            secondRun.allowCompletion.complete(Unit)
            runCatching { manager.close() }
            firstLease.release()
            secondLease.release()
        }
    }

    @Test
    fun closeAttemptsEveryRuntimeAndGivesWrappedFatalCleanupPriority() = runTest {
        val persistence = GatedAgentPersistence()
        val runner = GatedAgentRunner()
        val firstId = AgentSessionId("close-ordinary-failure")
        val fatalId = AgentSessionId("close-fatal-failure")
        val finalId = AgentSessionId("close-final-attempt")
        val ordinary = IllegalStateException("synthetic ordinary cleanup failure")
        val fatal = TestFatalError(Any())
        val firstGate = runner.blockInterrupt(firstId, ordinary)
        val fatalGate = runner.blockInterrupt(fatalId, TestRecoverableException(fatal))
        firstGate.release.complete(Unit)
        fatalGate.release.complete(Unit)
        val manager = DefaultAgentSessionManager(
            runner = runner,
            persistence = persistence,
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        val leases = listOf(firstId, fatalId, finalId).map { sessionId ->
            persistence.seed(terminalSnapshot(sessionId))
            runner.planRun(sessionId)
            manager.acquire(sessionId).also { lease -> lease.start(request(sessionId)) }
        }

        val escaped = runCatching { manager.close() }.exceptionOrNull()

        assertSame(fatal, escaped)
        assertEquals(1, runner.interruptCalls(firstId))
        assertEquals(1, runner.interruptCalls(fatalId))
        assertEquals(1, runner.interruptCalls(finalId))
        assertTrue(
            fatal.suppressedExceptions.any { failure -> failure.cause === ordinary },
        )
        assertSame(
            fatal,
            runCatching { manager.close() }.exceptionOrNull(),
        )
        leases.forEach { lease -> lease.release() }
    }

    @Test
    fun stableShutdownSkipsRunnerControlWhileRecoverableDeleteStillCancels() = runTest {
        val closePersistence = GatedAgentPersistence()
        val closeRunner = GatedAgentRunner()
        val draftId = AgentSessionId("close-stable-draft")
        val terminalId = AgentSessionId("close-stable-terminal")
        closePersistence.seed(terminalSnapshot(terminalId))
        val closeManager = DefaultAgentSessionManager(
            runner = closeRunner,
            persistence = closePersistence,
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        val draftLease = closeManager.create(draftId)
        val terminalLease = closeManager.acquire(terminalId)

        closeManager.close()

        assertEquals(0, closeRunner.interruptCalls(draftId))
        assertEquals(0, closeRunner.interruptCalls(terminalId))
        assertEquals(AgentSessionPhase.CLOSED, draftLease.state.value.phase)
        assertEquals(AgentSessionPhase.CLOSED, terminalLease.state.value.phase)
        draftLease.release()
        terminalLease.release()

        val deletePersistence = GatedAgentPersistence()
        val deleteRunner = GatedAgentRunner()
        val deletedTerminalId = AgentSessionId("delete-stable-terminal")
        val deletedRecoverableId = AgentSessionId("delete-recoverable")
        val recoverable = interruptedSnapshot(deletedRecoverableId)
        deletePersistence.seed(terminalSnapshot(deletedTerminalId))
        deletePersistence.seed(recoverable)
        deleteRunner.setRecovery(resumableRecovery(recoverable))
        val deleteManager = DefaultAgentSessionManager(
            runner = deleteRunner,
            persistence = deletePersistence,
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        val deletedTerminalLease = deleteManager.acquire(deletedTerminalId)
        val deletedRecoverableLease = deleteManager.acquire(deletedRecoverableId)

        try {
            deleteManager.delete(deletedTerminalId)
            deleteManager.delete(deletedRecoverableId)

            assertEquals(0, deleteRunner.cancelCalls(deletedTerminalId))
            assertEquals(1, deleteRunner.cancelCalls(deletedRecoverableId))
            assertEquals(AgentSessionPhase.DELETED, deletedTerminalLease.state.value.phase)
            assertEquals(AgentSessionPhase.DELETED, deletedRecoverableLease.state.value.phase)
        } finally {
            deletedTerminalLease.release()
            deletedRecoverableLease.release()
            deleteManager.close()
        }
    }

    @Test
    fun shutdownFenceRejectsTerminalEventsEmittedDuringRunnerControl() = runTest {
        val persistence = GatedAgentPersistence()
        val runner = GatedAgentRunner()
        val sessionId = AgentSessionId("shutdown-publication-fence")
        val run = runner.planRun(sessionId)
        val interrupt = runner.blockInterrupt(sessionId)
        persistence.seed(terminalSnapshot(sessionId))
        val manager = DefaultAgentSessionManager(
            runner = runner,
            persistence = persistence,
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        val lease = manager.acquire(sessionId)
        lease.start(request(sessionId))

        try {
            val close = async { manager.close() }
            interrupt.entered.await()
            run.allowCompletion.complete(Unit)
            runCurrent()

            assertEquals(AgentSessionPhase.CLOSED, lease.state.value.phase)
            assertFalse(close.isCompleted)

            interrupt.release.complete(Unit)
            close.await()
            assertEquals(AgentSessionPhase.CLOSED, lease.state.value.phase)
            assertEquals(1, runner.interruptCalls(sessionId))
        } finally {
            interrupt.release.complete(Unit)
            run.allowCompletion.complete(Unit)
            runCatching { manager.close() }
            lease.release()
        }
    }

    @Test
    fun closeWaitsForAnAdmittedCatalogReadAndRejectsLateReads() = runTest {
        val persistence = GatedAgentPersistence()
        val sessionId = AgentSessionId("close-catalog-read")
        persistence.seed(terminalSnapshot(sessionId))
        val blockedList = persistence.blockList()
        val manager = DefaultAgentSessionManager(
            runner = GatedAgentRunner(),
            persistence = persistence,
            dispatcher = StandardTestDispatcher(testScheduler),
        )

        try {
            val admittedRead = async { manager.listSessions() }
            blockedList.entered.await()
            val close = async { manager.close() }
            runCurrent()
            val lateRead = async { runCatching { manager.listSessions() } }
            runCurrent()

            assertFalse(close.isCompleted)
            assertTrue(lateRead.isCompleted)
            assertRuntimeFailure(
                AgentSessionErrorCode.CLOSED,
                assertNotNull(lateRead.await().exceptionOrNull()),
            )

            blockedList.release.complete(Unit)
            assertEquals(listOf(sessionId), admittedRead.await().map { it.sessionId })
            close.await()
        } finally {
            blockedList.release.complete(Unit)
            runCatching { manager.close() }
        }
    }

    @Test
    fun cancelledFirstCloserDoesNotPoisonTheSharedCleanupOutcome() = runTest {
        val persistence = GatedAgentPersistence()
        val runner = GatedAgentRunner()
        val sessionId = AgentSessionId("cancelled-first-close")
        val run = runner.planRun(sessionId)
        val blockedInterrupt = runner.blockInterrupt(sessionId)
        persistence.seed(terminalSnapshot(sessionId))
        val manager = DefaultAgentSessionManager(
            runner = runner,
            persistence = persistence,
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        val lease = manager.acquire(sessionId)
        lease.start(request(sessionId))

        try {
            val firstClose = launch { manager.close() }
            blockedInterrupt.entered.await()
            firstClose.cancel()
            val secondClose = async { runCatching { manager.close() } }
            runCurrent()

            assertFalse(firstClose.isCompleted)
            assertFalse(secondClose.isCompleted)

            blockedInterrupt.release.complete(Unit)
            firstClose.join()

            assertTrue(firstClose.isCancelled)
            assertTrue(secondClose.await().isSuccess)
            assertEquals(AgentSessionPhase.CLOSED, lease.state.value.phase)
        } finally {
            blockedInterrupt.release.complete(Unit)
            run.allowCompletion.complete(Unit)
            runCatching { manager.close() }
            lease.release()
        }
    }

    @Test
    fun cancelledOpeningLeaderSettlesFollowersAndManagerClose() = runTest {
        val persistence = GatedAgentPersistence()
        val followerId = AgentSessionId("cancelled-opening-follower")
        val closeId = AgentSessionId("cancelled-opening-close")
        persistence.seed(terminalSnapshot(followerId))
        persistence.seed(terminalSnapshot(closeId))
        val manager = DefaultAgentSessionManager(
            runner = GatedAgentRunner(),
            persistence = persistence,
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        val followerLoad = persistence.blockNextLoad(followerId)
        val closeLoad = persistence.blockNextLoad(closeId)

        try {
            val cancelledLeader = async { manager.acquire(followerId) }
            followerLoad.entered.await()
            val follower = async { manager.acquire(followerId) }
            runCurrent()

            cancelledLeader.cancelAndJoin()
            val followerLease = follower.await()

            assertTrue(followerLease.isAttached)
            assertEquals(3, persistence.loadCalls(followerId))
            followerLease.release()
            runCurrent()
            assertFalse(followerId in manager.liveSessionIds.value)

            val closingLeader = async { manager.acquire(closeId) }
            closeLoad.entered.await()
            val closingFollower = async { runCatching { manager.acquire(closeId) } }
            runCurrent()
            val close = async { runCatching { manager.close() } }
            runCurrent()

            assertFalse(close.isCompleted)
            assertFalse(closingFollower.isCompleted)

            closingLeader.cancelAndJoin()

            assertTrue(close.await().isSuccess)
            assertRuntimeFailure(
                AgentSessionErrorCode.CLOSED,
                assertNotNull(closingFollower.await().exceptionOrNull()),
            )
            assertTrue(manager.liveSessionIds.value.isEmpty())
        } finally {
            followerLoad.release.complete(Unit)
            closeLoad.release.complete(Unit)
            runCatching { manager.close() }
        }
    }

    @Test
    fun cancelledDeleteCallerStillSettlesTheDestructiveFence() = runTest {
        val persistence = GatedAgentPersistence()
        val runner = GatedAgentRunner()
        val sessionId = AgentSessionId("cancelled-delete")
        persistence.seed(terminalSnapshot(sessionId))
        val manager = DefaultAgentSessionManager(
            runner = runner,
            persistence = persistence,
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        val blockedDelete = persistence.blockDelete(sessionId)

        try {
            val oldLease = manager.acquire(sessionId)
            val deletion = launch { manager.delete(sessionId) }
            blockedDelete.entered.await()
            val lateAcquire = async { runCatching { manager.acquire(sessionId) } }
            runCurrent()

            deletion.cancel()
            runCurrent()

            assertFalse(deletion.isCompleted)
            assertFalse(lateAcquire.isCompleted)

            blockedDelete.release.complete(Unit)
            deletion.join()

            assertTrue(deletion.isCancelled)
            assertRuntimeFailure(
                AgentSessionErrorCode.NOT_FOUND,
                assertNotNull(lateAcquire.await().exceptionOrNull()),
            )
            assertRuntimeFailure(
                AgentSessionErrorCode.DELETED,
                assertNotNull(runCatching { oldLease.inspectRecovery() }.exceptionOrNull()),
            )
            assertTrue(manager.liveSessionIds.value.isEmpty())
            oldLease.release()
        } finally {
            blockedDelete.release.complete(Unit)
            manager.close()
        }
    }

    @Test
    fun cancelledClearCallerStillSettlesTheGlobalFence() = runTest {
        val persistence = GatedAgentPersistence()
        val runner = GatedAgentRunner()
        val oldId = AgentSessionId("cancelled-clear-old")
        val lateId = AgentSessionId("cancelled-clear-late")
        persistence.seed(terminalSnapshot(oldId))
        val manager = DefaultAgentSessionManager(
            runner = runner,
            persistence = persistence,
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        val blockedClear = persistence.blockClear()

        try {
            val oldLease = manager.acquire(oldId)
            val clearing = launch { manager.clear() }
            blockedClear.entered.await()
            val lateCreate = async { manager.create(lateId) }
            runCurrent()

            clearing.cancel()
            runCurrent()

            assertFalse(clearing.isCompleted)
            assertFalse(lateCreate.isCompleted)

            blockedClear.release.complete(Unit)
            clearing.join()
            val replacement = lateCreate.await()

            assertTrue(clearing.isCancelled)
            assertRuntimeFailure(
                AgentSessionErrorCode.DELETED,
                assertNotNull(runCatching { oldLease.inspectRecovery() }.exceptionOrNull()),
            )
            assertTrue(replacement.isAttached)
            assertEquals(setOf(lateId), manager.liveSessionIds.value)
            oldLease.release()
            replacement.release()
        } finally {
            blockedClear.release.complete(Unit)
            manager.close()
        }
    }

    @Test
    fun nonTerminalRunnerCompletionPublishesFailureAndCanBeReleased() = runTest {
        val persistence = GatedAgentPersistence()
        val runner = GatedAgentRunner()
        val sessionId = AgentSessionId("missing-terminal-event")
        val run = runner.planRun(sessionId, emitTerminal = false)
        persistence.seed(terminalSnapshot(sessionId))
        val manager = DefaultAgentSessionManager(
            runner = runner,
            persistence = persistence,
            dispatcher = StandardTestDispatcher(testScheduler),
        )

        try {
            val lease = manager.acquire(sessionId)
            lease.start(request(sessionId))
            run.allowCompletion.complete(Unit)
            lease.awaitIdle()

            assertEquals(AgentSessionPhase.TERMINAL, lease.state.value.phase)
            assertEquals(AgentStatus.FAILED, lease.state.value.state?.status)
            assertEquals(AgentFailureCode.INTERNAL, lease.state.value.failure)
            assertIs<AgentEvent.Failed>(lease.state.value.lastEvent)

            lease.release()
            runCurrent()
            assertFalse(sessionId in manager.liveSessionIds.value)
        } finally {
            run.allowFirstEvent.complete(Unit)
            run.allowCompletion.complete(Unit)
            manager.close()
        }
    }

    @Test
    fun pendingRecoveryStatesRejectStartAndNonResumableStatesRejectResume() = runTest {
        val persistence = GatedAgentPersistence()
        val runner = GatedAgentRunner()
        val resumableId = AgentSessionId("guard-resumable")
        val blockedId = AgentSessionId("guard-blocked")
        val terminalId = AgentSessionId("guard-terminal")
        val resumable = interruptedSnapshot(resumableId)
        val blocked = runningSnapshot(blockedId)
        persistence.seed(resumable)
        persistence.seed(blocked)
        persistence.seed(terminalSnapshot(terminalId))
        runner.setRecovery(resumableRecovery(resumable))
        runner.setRecovery(blockedRecovery(blocked))
        val manager = DefaultAgentSessionManager(
            runner = runner,
            persistence = persistence,
            dispatcher = StandardTestDispatcher(testScheduler),
        )

        try {
            val resumableLease = manager.acquire(resumableId)
            val blockedLease = manager.acquire(blockedId)
            val terminalLease = manager.acquire(terminalId)

            assertEquals(AgentSessionPhase.RESUMABLE, resumableLease.state.value.phase)
            assertEquals(AgentSessionPhase.RECOVERY_BLOCKED, blockedLease.state.value.phase)
            assertRuntimeFailure(
                AgentSessionErrorCode.BUSY,
                assertNotNull(
                    runCatching { resumableLease.start(request(resumableId)) }.exceptionOrNull(),
                ),
            )
            assertRuntimeFailure(
                AgentSessionErrorCode.BUSY,
                assertNotNull(
                    runCatching { blockedLease.start(request(blockedId)) }.exceptionOrNull(),
                ),
            )
            assertRuntimeFailure(
                AgentSessionErrorCode.INVALID_STATE,
                assertNotNull(runCatching { blockedLease.resume() }.exceptionOrNull()),
            )
            assertRuntimeFailure(
                AgentSessionErrorCode.INVALID_STATE,
                assertNotNull(runCatching { terminalLease.resume() }.exceptionOrNull()),
            )

            resumableLease.release()
            blockedLease.release()
            terminalLease.release()
        } finally {
            manager.close()
        }
    }

    @Test
    fun terminalPublicationWinsOverAnOlderInFlightRecoveryInspection() = runTest {
        val persistence = GatedAgentPersistence()
        val runner = GatedAgentRunner()
        val sessionId = AgentSessionId("terminal-vs-inspect")
        val run = runner.planRun(sessionId)
        persistence.seed(terminalSnapshot(sessionId))
        val manager = DefaultAgentSessionManager(
            runner = runner,
            persistence = persistence,
            dispatcher = StandardTestDispatcher(testScheduler),
        )

        try {
            val lease = manager.acquire(sessionId)
            lease.start(request(sessionId))
            val staleActive = runner.blockInspectRecovery(
                AgentRecoveryInfo(
                    sessionId = sessionId,
                    runId = AgentRunId("run-${sessionId.value}"),
                    disposition = AgentRecoveryDisposition.ACTIVE,
                    status = AgentStatus.RUNNING,
                    state = AgentStateSnapshot(emptyList(), status = AgentStatus.RUNNING),
                ),
            )
            val inspection = async { lease.inspectRecovery() }
            staleActive.entered.await()

            run.allowCompletion.complete(Unit)
            runCurrent()
            assertEquals(AgentSessionPhase.TERMINAL, lease.state.value.phase)

            staleActive.release.complete(Unit)
            val observed = inspection.await()
            lease.awaitIdle()

            assertEquals(AgentRecoveryDisposition.TERMINAL, observed.disposition)
            assertEquals(AgentStatus.COMPLETED, observed.status)
            assertEquals(AgentSessionPhase.TERMINAL, lease.state.value.phase)
            assertEquals(AgentStatus.COMPLETED, lease.state.value.state?.status)
            lease.release()
        } finally {
            run.allowCompletion.complete(Unit)
            manager.close()
        }
    }

    @Test
    fun persistedCompletionWinsWhenCancelSettlesBeforeItsTerminalEventIsDelivered() = runTest {
        val persistence = GatedAgentPersistence()
        val runner = PersistedTerminalWinnerRunner(persistence)
        val sessionId = AgentSessionId("persisted-terminal-cancel-winner")
        val manager = DefaultAgentSessionManager(
            runner = runner,
            persistence = persistence,
            dispatcher = StandardTestDispatcher(testScheduler),
        )

        try {
            val lease = manager.create(sessionId)
            lease.start(request(sessionId))
            runner.terminalPersisted.await()

            assertEquals(AgentSessionPhase.ACTIVE, lease.state.value.phase)
            lease.cancel()

            assertEquals(AgentSessionPhase.TERMINAL, lease.state.value.phase)
            assertEquals(AgentStatus.COMPLETED, lease.state.value.state?.status)
            assertEquals(
                AgentStatus.COMPLETED,
                persistence.load(sessionId)?.snapshot?.state?.status,
            )
            lease.release()
        } finally {
            manager.close()
        }
    }

    @Test
    fun interruptSettlesBeforeImmediateResumeAndCloseStopsBeforeTheFirstBusinessEvent() = runTest {
        val interruptPersistence = GatedAgentPersistence()
        val interruptRunner = GatedAgentRunner()
        val interruptId = AgentSessionId("stop-before-first-interrupt")
        val interruptRun = interruptRunner.planRun(
            interruptId,
            holdBeforeFirstEvent = true,
            cancelCollectorOnControl = true,
        )
        interruptRunner.setInterruptRecovery(resumableRecovery(interruptedSnapshot(interruptId)))
        interruptPersistence.seed(terminalSnapshot(interruptId))
        val interruptManager = DefaultAgentSessionManager(
            runner = interruptRunner,
            persistence = interruptPersistence,
            dispatcher = StandardTestDispatcher(testScheduler),
        )

        try {
            val lease = interruptManager.acquire(interruptId)
            lease.start(request(interruptId))
            interruptRun.entered.await()

            val recovery = lease.interrupt()

            assertEquals(AgentRecoveryDisposition.RESUMABLE, recovery.disposition)
            assertEquals(AgentSessionPhase.RESUMABLE, lease.state.value.phase)
            assertFalse(interruptRun.allowFirstEvent.isCompleted)

            interruptPersistence.seed(interruptedSnapshot(interruptId))
            lease.resume()
            assertEquals(AgentSessionPhase.ACTIVE, lease.state.value.phase)
            lease.cancel()
            assertEquals(AgentSessionPhase.TERMINAL, lease.state.value.phase)
            assertEquals(AgentStatus.CANCELLED, lease.state.value.state?.status)
            lease.release()
        } finally {
            interruptRun.allowFirstEvent.complete(Unit)
            interruptRun.allowCompletion.complete(Unit)
            interruptManager.close()
        }

        val closePersistence = GatedAgentPersistence()
        val closeRunner = GatedAgentRunner()
        val closeId = AgentSessionId("stop-before-first-close")
        val closeRun = closeRunner.planRun(closeId, holdBeforeFirstEvent = true)
        closePersistence.seed(terminalSnapshot(closeId))
        val closeManager = DefaultAgentSessionManager(
            runner = closeRunner,
            persistence = closePersistence,
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        val closeLease = closeManager.acquire(closeId)
        closeLease.start(request(closeId))
        closeRun.entered.await()

        try {
            val close = async { closeManager.close() }
            runCurrent()

            assertTrue(close.isCompleted)
            close.await()
            assertEquals(AgentSessionPhase.CLOSED, closeLease.state.value.phase)
            assertFalse(closeRun.allowFirstEvent.isCompleted)
        } finally {
            closeRun.allowFirstEvent.complete(Unit)
            closeRun.allowCompletion.complete(Unit)
            runCatching { closeManager.close() }
            closeLease.release()
        }
    }

    @Test
    fun failingShutdownControlFencesTheOldLeaseBeforeReplacementAdmission() = runTest {
        val persistence = GatedAgentPersistence()
        val runner = GatedAgentRunner()
        val sessionId = AgentSessionId("shutdown-failure-fence")
        val run = runner.planRun(sessionId)
        val cancel = runner.blockCancel(
            sessionId,
            IllegalStateException("synthetic cancel failure"),
        )
        persistence.seed(terminalSnapshot(sessionId))
        val manager = DefaultAgentSessionManager(
            runner = runner,
            persistence = persistence,
            dispatcher = StandardTestDispatcher(testScheduler),
        )

        try {
            val oldLease = manager.acquire(sessionId)
            oldLease.start(request(sessionId))
            val deletion = async { runCatching { manager.delete(sessionId) } }
            cancel.entered.await()
            val lateAcquire = async { manager.acquire(sessionId) }
            runCurrent()

            assertFalse(deletion.isCompleted)
            assertFalse(lateAcquire.isCompleted)

            cancel.release.complete(Unit)
            assertRuntimeFailure(
                AgentSessionErrorCode.INVALID_STATE,
                assertNotNull(deletion.await().exceptionOrNull()),
            )
            val replacement = lateAcquire.await()

            assertRuntimeFailure(
                AgentSessionErrorCode.DELETED,
                assertNotNull(runCatching { oldLease.start(request(sessionId)) }.exceptionOrNull()),
            )
            assertTrue(replacement.state !== oldLease.state)
            assertEquals(setOf(sessionId), manager.liveSessionIds.value)
            oldLease.release()
            replacement.release()
        } finally {
            cancel.release.complete(Unit)
            run.allowCompletion.complete(Unit)
            manager.close()
        }
    }

    @Test
    fun releaseFromACancelledContextDoesNotLeakTheLease() = runTest {
        val persistence = GatedAgentPersistence()
        val sessionId = AgentSessionId("cancelled-release")
        persistence.seed(terminalSnapshot(sessionId))
        val manager = DefaultAgentSessionManager(
            runner = GatedAgentRunner(),
            persistence = persistence,
            dispatcher = StandardTestDispatcher(testScheduler),
        )

        try {
            val lease = manager.acquire(sessionId)
            val oldState = lease.state
            val releasing = launch(start = CoroutineStart.UNDISPATCHED) {
                currentCoroutineContext()[Job]?.cancel()
                lease.release()
            }
            releasing.join()
            runCurrent()

            assertTrue(releasing.isCancelled)
            assertFalse(lease.isAttached)
            assertFalse(sessionId in manager.liveSessionIds.value)

            val replacement = manager.acquire(sessionId)
            assertTrue(replacement.state !== oldState)
            replacement.release()
        } finally {
            manager.close()
        }
    }

    @Test
    fun cancelledExistingSessionAcquireReleasesItsAdmittedLease() = runTest {
        val persistence = GatedAgentPersistence()
        val sessionId = AgentSessionId("cancelled-existing-acquire")
        persistence.seed(terminalSnapshot(sessionId))
        val manager = DefaultAgentSessionManager(
            runner = GatedAgentRunner(),
            persistence = persistence,
            dispatcher = StandardTestDispatcher(testScheduler),
        )

        try {
            val retainingLease = manager.acquire(sessionId)
            val cancellation = CancelOnFirstJobLookupContext()
            var acquireResult: Result<AgentSessionLease>? = null
            suspend { manager.acquire(sessionId) }.startCoroutine(
                object : Continuation<AgentSessionLease> {
                    override val context: CoroutineContext = cancellation

                    override fun resumeWith(result: Result<AgentSessionLease>) {
                        acquireResult = result
                    }
                },
            )
            runCurrent()

            assertEquals(1, cancellation.jobLookups)
            assertIs<CancellationException>(
                assertNotNull(acquireResult).exceptionOrNull(),
            )

            retainingLease.release()
            runCurrent()
            assertFalse(sessionId in manager.liveSessionIds.value)
        } finally {
            manager.close()
        }
    }

    @Test
    fun slowReentrantEventObserverDoesNotBackpressureExecutionOrInterrupt() = runTest {
        val persistence = GatedAgentPersistence()
        val runner = GatedAgentRunner()
        val sessionId = AgentSessionId("slow-observer")
        val run = runner.planRun(
            sessionId,
            burstEvents = 256,
            cancelCollectorOnControl = true,
        )
        runner.setInterruptRecovery(resumableRecovery(interruptedSnapshot(sessionId)))
        persistence.seed(terminalSnapshot(sessionId))
        val manager = DefaultAgentSessionManager(
            runner = runner,
            persistence = persistence,
            dispatcher = StandardTestDispatcher(testScheduler),
        )

        try {
            val lease = manager.acquire(sessionId)
            val observerEntered = CompletableDeferred<Unit>()
            val interruptResult = CompletableDeferred<AgentRecoveryInfo>()
            val releaseObserver = CompletableDeferred<Unit>()
            val observer = backgroundScope.launch {
                var handled = false
                lease.events.collect {
                    if (!handled) {
                        handled = true
                        observerEntered.complete(Unit)
                        interruptResult.complete(lease.interrupt())
                        releaseObserver.await()
                    }
                }
            }
            runCurrent()

            lease.start(request(sessionId))
            run.burstEmitted.await()
            observerEntered.await()
            val recovery = interruptResult.await()
            lease.awaitIdle()

            assertEquals(256, run.emittedBurstEvents)
            assertEquals(AgentRecoveryDisposition.RESUMABLE, recovery.disposition)
            assertEquals(AgentSessionPhase.RESUMABLE, lease.state.value.phase)
            assertEquals(1, runner.interruptCalls(sessionId))

            releaseObserver.complete(Unit)
            observer.cancelAndJoin()
            lease.release()
        } finally {
            run.allowCompletion.complete(Unit)
            manager.close()
        }
    }

    private fun assertRuntimeFailure(
        expected: AgentSessionErrorCode,
        failure: Throwable,
    ) {
        val runtimeFailure = assertIs<AgentSessionException>(failure)
        assertEquals(expected, runtimeFailure.code)
    }
}

private class GatedAgentRunner : AgentRunner {
    private val plans = mutableMapOf<String, RunPlan>()
    private val runCounts = mutableMapOf<String, Int>()
    private val interruptCounts = mutableMapOf<String, Int>()
    private val cancelCounts = mutableMapOf<String, Int>()
    private val interruptGates = mutableMapOf<String, OperationGate>()
    private val cancelGates = mutableMapOf<String, OperationGate>()
    private val recoveries = mutableMapOf<String, AgentRecoveryInfo>()
    private val interruptRecoveries = mutableMapOf<String, AgentRecoveryInfo>()
    private val inspectGates = mutableMapOf<String, RecoveryGate>()

    fun planRun(
        sessionId: AgentSessionId,
        holdBeforeFirstEvent: Boolean = false,
        emitTerminal: Boolean = true,
        burstEvents: Int = 0,
        cancelCollectorOnControl: Boolean = false,
    ): RunPlan = RunPlan(
        holdBeforeFirstEvent = holdBeforeFirstEvent,
        emitTerminal = emitTerminal,
        burstEvents = burstEvents,
        cancelCollectorOnControl = cancelCollectorOnControl,
    ).also { plan ->
        plans[sessionId.value] = plan
    }

    fun blockInterrupt(
        sessionId: AgentSessionId,
        failure: Throwable? = null,
    ): OperationGate = OperationGate(failure).also { gate ->
        interruptGates[sessionId.value] = gate
    }

    fun blockCancel(
        sessionId: AgentSessionId,
        failure: Throwable? = null,
    ): OperationGate = OperationGate(failure).also { gate ->
        cancelGates[sessionId.value] = gate
    }

    fun setRecovery(recovery: AgentRecoveryInfo) {
        recoveries[recovery.sessionId.value] = recovery
    }

    fun setInterruptRecovery(recovery: AgentRecoveryInfo) {
        interruptRecoveries[recovery.sessionId.value] = recovery
    }

    fun blockInspectRecovery(recovery: AgentRecoveryInfo): RecoveryGate =
        RecoveryGate(recovery).also { gate ->
            inspectGates[recovery.sessionId.value] = gate
        }

    fun runCalls(sessionId: AgentSessionId): Int = runCounts[sessionId.value] ?: 0
    fun interruptCalls(sessionId: AgentSessionId): Int = interruptCounts[sessionId.value] ?: 0
    fun cancelCalls(sessionId: AgentSessionId): Int = cancelCounts[sessionId.value] ?: 0

    override fun run(request: AgentRequest): Flow<AgentEvent> = plannedFlow(request)

    override suspend fun resume(sessionId: AgentSessionId): Flow<AgentEvent> =
        plannedFlow(request(sessionId))

    override suspend fun interrupt(sessionId: AgentSessionId): AgentRecoveryInfo {
        interruptCounts.increment(sessionId.value)
        interruptGates[sessionId.value]?.let { gate ->
            gate.entered.complete(Unit)
            gate.release.await()
            gate.failure?.let { throw it }
        }
        plans[sessionId.value]
            ?.takeIf(RunPlan::cancelCollectorOnControl)
            ?.collectorJob
            ?.cancel()
        return interruptRecoveries[sessionId.value] ?: AgentRecoveryInfo(
            sessionId = sessionId,
            disposition = AgentRecoveryDisposition.NOT_FOUND,
        )
    }

    override suspend fun inspectRecovery(sessionId: AgentSessionId): AgentRecoveryInfo {
        inspectGates.remove(sessionId.value)?.let { gate ->
            gate.entered.complete(Unit)
            gate.release.await()
            return gate.recovery
        }
        return recoveries[sessionId.value] ?: AgentRecoveryInfo(
            sessionId = sessionId,
            disposition = AgentRecoveryDisposition.NOT_FOUND,
        )
    }

    override suspend fun cancel(sessionId: AgentSessionId) {
        cancelCounts.increment(sessionId.value)
        cancelGates[sessionId.value]?.let { gate ->
            gate.entered.complete(Unit)
            gate.release.await()
            gate.failure?.let { throw it }
        }
        plans[sessionId.value]
            ?.takeIf(RunPlan::cancelCollectorOnControl)
            ?.collectorJob
            ?.cancel()
    }

    private fun plannedFlow(request: AgentRequest): Flow<AgentEvent> = flow {
        runCounts.increment(request.sessionId.value)
        val plan = plans.getOrPut(request.sessionId.value) { RunPlan() }
        plan.collectorJob = currentCoroutineContext()[Job]
        plan.entered.complete(Unit)
        plan.allowFirstEvent.await()
        val runId = AgentRunId("run-${request.sessionId.value}")
        emit(AgentEvent.Started(request.sessionId, runId))
        repeat(plan.burstEvents) { index ->
            emit(AgentEvent.TurnStarted(request.sessionId, index + 1))
            plan.emittedBurstEvents += 1
        }
        plan.burstEmitted.complete(Unit)
        plan.allowCompletion.await()
        if (plan.emitTerminal) {
            emit(
                AgentEvent.Completed(
                    request.sessionId,
                    AgentStateSnapshot(
                        messages = request.messages,
                        status = AgentStatus.COMPLETED,
                    ),
                ),
            )
        }
    }
}

private class PersistedTerminalWinnerRunner(
    private val persistence: AgentPersistence,
) : AgentRunner {
    val terminalPersisted = CompletableDeferred<Unit>()
    private val holdCollector = CompletableDeferred<Unit>()
    private var collectorJob: Job? = null

    override fun run(request: AgentRequest): Flow<AgentEvent> = flow {
        collectorJob = currentCoroutineContext()[Job]
        val runId = AgentRunId("run-${request.sessionId.value}")
        emit(AgentEvent.Started(request.sessionId, runId))
        persistence.commit(
            snapshot = AgentSessionSnapshot(
                sessionId = request.sessionId,
                runId = runId,
                request = request,
                state = AgentStateSnapshot(
                    messages = request.messages,
                    status = AgentStatus.COMPLETED,
                ),
                updatedAtEpochMs = 2L,
            ),
            checkpoint = null,
        )
        terminalPersisted.complete(Unit)
        holdCollector.await()
    }

    override suspend fun resume(sessionId: AgentSessionId): Flow<AgentEvent> =
        error("PersistedTerminalWinnerRunner does not support resume")

    override suspend fun interrupt(sessionId: AgentSessionId): AgentRecoveryInfo =
        inspectRecovery(sessionId)

    override suspend fun inspectRecovery(sessionId: AgentSessionId): AgentRecoveryInfo {
        val record = persistence.load(sessionId) ?: return AgentRecoveryInfo(
            sessionId = sessionId,
            disposition = AgentRecoveryDisposition.NOT_FOUND,
        )
        return AgentRecoveryInfo(
            sessionId = sessionId,
            runId = record.snapshot.runId,
            disposition = AgentRecoveryDisposition.TERMINAL,
            status = record.snapshot.state.status,
            state = record.snapshot.state,
        )
    }

    override suspend fun cancel(sessionId: AgentSessionId) {
        requireNotNull(collectorJob).cancelAndJoin()
    }
}

private class RunPlan(
    holdBeforeFirstEvent: Boolean = false,
    val emitTerminal: Boolean = true,
    val burstEvents: Int = 0,
    val cancelCollectorOnControl: Boolean = false,
) {
    val entered = CompletableDeferred<Unit>()
    val allowFirstEvent = CompletableDeferred<Unit>().also { gate ->
        if (!holdBeforeFirstEvent) gate.complete(Unit)
    }
    val allowCompletion = CompletableDeferred<Unit>()
    val burstEmitted = CompletableDeferred<Unit>().also { gate ->
        if (burstEvents == 0) gate.complete(Unit)
    }
    var collectorJob: Job? = null
    var emittedBurstEvents: Int = 0
}

private class GatedAgentPersistence : AgentPersistence {
    private val delegate = InMemoryAgentPersistence()
    private val loadCounts = mutableMapOf<String, Int>()
    private val loadGates = mutableMapOf<String, OperationGate>()
    private val deleteGates = mutableMapOf<String, OperationGate>()
    private var listGate: OperationGate? = null
    private var clearGate: OperationGate? = null

    suspend fun seed(snapshot: AgentSessionSnapshot) {
        delegate.commit(snapshot, checkpoint = null)
    }

    fun blockNextLoad(sessionId: AgentSessionId): OperationGate = OperationGate().also { gate ->
        loadGates[sessionId.value] = gate
    }

    fun blockDelete(
        sessionId: AgentSessionId,
        failure: Throwable? = null,
    ): OperationGate = OperationGate(failure).also { gate ->
        deleteGates[sessionId.value] = gate
    }

    fun blockClear(failure: Throwable? = null): OperationGate = OperationGate(failure).also { gate ->
        clearGate = gate
    }

    fun blockList(): OperationGate = OperationGate().also { gate ->
        listGate = gate
    }

    fun loadCalls(sessionId: AgentSessionId): Int = loadCounts[sessionId.value] ?: 0

    override suspend fun commit(
        snapshot: AgentSessionSnapshot,
        checkpoint: AgentCheckpoint?,
    ) = delegate.commit(snapshot, checkpoint)

    override suspend fun load(sessionId: AgentSessionId): AgentPersistenceRecord? {
        loadCounts.increment(sessionId.value)
        loadGates.remove(sessionId.value)?.let { gate ->
            gate.entered.complete(Unit)
            gate.release.await()
        }
        return delegate.load(sessionId)
    }

    override suspend fun listSessions(): List<AgentSessionSnapshot> {
        listGate?.let { gate ->
            gate.entered.complete(Unit)
            gate.release.await()
            gate.failure?.let { throw it }
        }
        return delegate.listSessions()
    }

    override suspend fun deleteSession(sessionId: AgentSessionId) {
        deleteGates[sessionId.value]?.let { gate ->
            gate.entered.complete(Unit)
            gate.release.await()
            gate.failure?.let { throw it }
        }
        delegate.deleteSession(sessionId)
    }

    override suspend fun clear() {
        clearGate?.let { gate ->
            gate.entered.complete(Unit)
            gate.release.await()
            gate.failure?.let { throw it }
        }
        delegate.clear()
    }
}

private class OperationGate(val failure: Throwable? = null) {
    val entered = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
}

private class RecoveryGate(val recovery: AgentRecoveryInfo) {
    val entered = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
}

private class JobLookupContext(
    private val delegate: Job = Job(),
    private val onLookup: () -> Unit,
) : CoroutineContext {
    var jobLookups: Int = 0
        private set

    override fun <R> fold(
        initial: R,
        operation: (R, CoroutineContext.Element) -> R,
    ): R = operation(initial, delegate)

    @Suppress("UNCHECKED_CAST")
    override fun <E : CoroutineContext.Element> get(
        key: CoroutineContext.Key<E>,
    ): E? {
        if (key !== Job) return null
        jobLookups += 1
        onLookup()
        return delegate as E
    }

    override fun minusKey(key: CoroutineContext.Key<*>): CoroutineContext =
        if (key === Job) EmptyCoroutineContext else this
}

private class CancelOnFirstJobLookupContext(
    private val delegate: Job = Job(),
) : CoroutineContext {
    var jobLookups: Int = 0
        private set

    override fun <R> fold(
        initial: R,
        operation: (R, CoroutineContext.Element) -> R,
    ): R = operation(initial, delegate)

    @Suppress("UNCHECKED_CAST")
    override fun <E : CoroutineContext.Element> get(
        key: CoroutineContext.Key<E>,
    ): E? {
        if (key !== Job) return null
        jobLookups += 1
        if (jobLookups == 1) delegate.cancel()
        return delegate as E
    }

    override fun minusKey(key: CoroutineContext.Key<*>): CoroutineContext =
        if (key === Job) EmptyCoroutineContext else this
}

private fun MutableMap<String, Int>.increment(key: String) {
    this[key] = (this[key] ?: 0) + 1
}

private fun request(sessionId: AgentSessionId): AgentRequest = AgentRequest(
    sessionId = sessionId,
    messages = emptyList(),
    model = ModelDescriptor(provider = "test", model = "test-model"),
)

private fun terminalSnapshot(sessionId: AgentSessionId): AgentSessionSnapshot =
    AgentSessionSnapshot(
        sessionId = sessionId,
        runId = AgentRunId("stored-${sessionId.value}"),
        request = request(sessionId),
        state = AgentStateSnapshot(
            messages = emptyList(),
            status = AgentStatus.COMPLETED,
        ),
        updatedAtEpochMs = 1L,
    )

private fun interruptedSnapshot(sessionId: AgentSessionId): AgentSessionSnapshot {
    val interruption = AgentInterruption(
        reason = AgentInterruptionReason.HOST_REQUESTED,
        occurredAtEpochMs = 1L,
    )
    return AgentSessionSnapshot(
        sessionId = sessionId,
        runId = AgentRunId("stored-${sessionId.value}"),
        request = request(sessionId),
        state = AgentStateSnapshot(
            messages = emptyList(),
            status = AgentStatus.INTERRUPTED,
        ),
        interruption = interruption,
        updatedAtEpochMs = 1L,
    )
}

private fun runningSnapshot(sessionId: AgentSessionId): AgentSessionSnapshot =
    AgentSessionSnapshot(
        sessionId = sessionId,
        runId = AgentRunId("stored-${sessionId.value}"),
        request = request(sessionId),
        state = AgentStateSnapshot(
            messages = emptyList(),
            status = AgentStatus.RUNNING,
        ),
        updatedAtEpochMs = 1L,
    )

private fun resumableRecovery(snapshot: AgentSessionSnapshot): AgentRecoveryInfo =
    AgentRecoveryInfo(
        sessionId = snapshot.sessionId,
        runId = snapshot.runId,
        disposition = AgentRecoveryDisposition.RESUMABLE,
        status = snapshot.state.status,
        state = snapshot.state,
        interruption = snapshot.interruption,
    )

private fun blockedRecovery(snapshot: AgentSessionSnapshot): AgentRecoveryInfo =
    AgentRecoveryInfo(
        sessionId = snapshot.sessionId,
        runId = snapshot.runId,
        disposition = AgentRecoveryDisposition.BLOCKED,
        status = snapshot.state.status,
        state = snapshot.state,
        blockedReason = AgentRecoveryBlockReason.CHECKPOINT_MISMATCH,
    )
