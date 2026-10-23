package saien.magrathea.runtime

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.cancellation.CancellationException
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource
import saien.magrathea.core.AgentRunId
import saien.magrathea.core.AgentSessionId
import saien.magrathea.core.BackgroundExecutionRecord
import saien.magrathea.core.BackgroundExecutionState
import saien.magrathea.core.BackgroundExecutionStore
import saien.magrathea.core.BackgroundOriginContext
import saien.magrathea.core.BackgroundOutcome
import saien.magrathea.core.BackgroundSessionRecords
import saien.magrathea.core.SystemEpochClock
import saien.magrathea.core.ToolExecutionRequest
import saien.magrathea.core.ToolExecutionResult
import saien.magrathea.core.ToolExecutor
import saien.magrathea.core.ToolRecoveryPolicy
import saien.magrathea.core.TraceStatus

private const val HANDLE_ALPHABET = "abcdefghjkmnpqrstuvwxyz23456789"
private const val HANDLE_RANDOM_CHARS = 10

/**
 * Process-local owner of Session-owned background Tool executions (ADR-019).
 *
 * Jobs live in a [SupervisorJob]-rooted scope that never belongs to a Run, so Run cancellation
 * never cancels them. Record writes for one Session are serialized by a per-Session mutex, and
 * admitted calls are launched FIFO. The manager registers and unregisters Sessions; the Runner
 * hands off only registered Sessions, so a direct `AgentRunner` consumer always stays blocking.
 */
class BackgroundToolExecutor(
    private val store: BackgroundExecutionStore,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val timeSource: TimeSource.WithComparableMarks = TimeSource.Monotonic,
    private val epochClock: () -> Long = { SystemEpochClock.nowEpochMs() },
    private val random: Random = Random.Default,
) {
    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + dispatcher)
    private val guard = Mutex()
    private val listeners = LinkedHashMap<String, SessionListener>()
    private val sessionMutexes = LinkedHashMap<String, Mutex>()
    private val sessionVersions = LinkedHashMap<String, MutableStateFlow<Long>>()
    private val executions = LinkedHashMap<String, AdmittedExecution>()

    /**
     * Builds [AdmittedSpec]s for records that outlived their Run and resolves Tools by name.
     * Bound by `DefaultAgentRunner` when it is constructed with this executor.
     */
    internal interface JobFactory {
        suspend fun toolFor(record: BackgroundExecutionRecord): ToolExecutor?
        suspend fun specFor(record: BackgroundExecutionRecord): AdmittedSpec
    }

    internal var jobFactory: JobFactory? = null

    /** Receives record changes and terminal settlements of one managed Session. */
    internal interface SessionListener {
        suspend fun onRecordChanged(sessionId: AgentSessionId, record: BackgroundExecutionRecord)
        suspend fun onSettled(sessionId: AgentSessionId, record: BackgroundExecutionRecord)
    }

    suspend fun close() {
        job.cancelAndJoin()
    }

    internal suspend fun register(sessionId: AgentSessionId, listener: SessionListener) {
        guard.withLock { listeners[sessionId.value] = listener }
    }

    internal suspend fun unregister(sessionId: AgentSessionId) {
        guard.withLock { listeners.remove(sessionId.value) }
    }

    internal suspend fun isManaged(sessionId: AgentSessionId): Boolean =
        guard.withLock { sessionId.value in listeners }

    internal suspend fun records(sessionId: AgentSessionId): BackgroundSessionRecords =
        store.load(sessionId)

    internal suspend fun hasUnfinishedRecords(sessionId: AgentSessionId): Boolean =
        store.load(sessionId).records.any { it.state != BackgroundExecutionState.TERMINAL }

    internal fun newHandle(): String = buildString {
        append("bg_")
        repeat(HANDLE_RANDOM_CHARS) {
            append(HANDLE_ALPHABET[random.nextInt(HANDLE_ALPHABET.length)])
        }
    }

    /**
     * Admits an eligible call into this executor's scope. The job's start is reported through
     * [AdmittedSpec.onExecutionStarted] only while the Run still waits; after [handoff] the
     * record itself is updated. The returned [AdmittedExecution.foreground] completes only when
     * the execution settles before a handoff.
     */
    internal suspend fun admit(spec: AdmittedSpec): AdmittedExecution {
        val sessionMutex = guard.withLock {
            check(spec.executionRequest.executionId !in executions) {
                "Duplicate background execution ${spec.executionRequest.executionId}"
            }
            sessionMutexes.getOrPut(spec.sessionId.value) { Mutex() }
        }
        return launch(spec, sessionMutex)
    }

    private suspend fun launch(
        spec: AdmittedSpec,
        sessionMutex: Mutex,
        resumedRecord: BackgroundExecutionRecord? = null,
    ): AdmittedExecution {
        val execution = AdmittedExecution(spec, queuedAtEpochMs = spec.queuedAtEpochMs ?: epochClock())
        if (resumedRecord != null) {
            // Resumed records are already durable — the job updates them directly.
            execution.handedOff = true
            execution.record = resumedRecord
        }
        guard.withLock { executions[spec.executionRequest.executionId] = execution }
        execution.job = scope.launch { runExecution(execution, sessionMutex) }
        return execution
    }

    /** The live admission for [executionId], settled or not; null after [release]. */
    internal suspend fun liveExecution(executionId: String): AdmittedExecution? =
        guard.withLock { executions[executionId] }

    /** Drops an execution the Run has consumed. */
    internal suspend fun release(execution: AdmittedExecution) = cleanup(execution)

    /** A write that wakes Session-scoped waiters (used by tests and recovery paths). */
    internal suspend fun putRecord(record: BackgroundExecutionRecord) = writeRecord(record)

    /**
     * Stores the Run's origin context once, then writes the record and marks the execution handed
     * off. The caller commits the receipt into the Run journal only after this returns.
     */
    internal suspend fun handoff(
        execution: AdmittedExecution,
        sourceId: String,
        origin: BackgroundOriginContext,
    ): BackgroundExecutionRecord? {
        val sessionMutex = sessionMutex(execution.sessionId)
        val record = sessionMutex.withLock {
            if (execution.foreground.isCompleted) {
                // The raw outcome arrived before the receipt commit; the Run finalizes it.
                return@withLock null
            }
            val stored = store.load(execution.sessionId)
            if (stored.origins.none { it.runId == origin.runId }) {
                store.putOrigin(origin)
            }
            val record = BackgroundExecutionRecord(
                sessionId = execution.sessionId,
                executionId = execution.executionId,
                handle = newHandle(),
                originRunId = execution.runId,
                originCallId = execution.spec.toolCallId,
                toolName = execution.spec.toolName,
                sourceId = sourceId,
                state = if (execution.started) {
                    BackgroundExecutionState.RUNNING
                } else {
                    BackgroundExecutionState.QUEUED
                },
                executionRequest = execution.spec.executionRequest,
                effectiveTimeoutMillis = execution.spec.effectiveTimeoutMillis,
                queuedAtEpochMs = execution.queuedAtEpochMs,
                startedAtEpochMs = execution.startedAtEpochMs,
            )
            writeRecord(record)
            execution.record = record
            execution.handedOff = true
            record
        }
        record?.let { notifyRecordChanged(execution.sessionId, it) }
        return record
    }

    /**
     * Cancels the referenced live executions of one Session. Cancelled records become TERMINAL
     * with [BackgroundOutcome.CANCELLED] and `activationSuppressed`, so they never produce a
     * notice. Unknown handles are not records of this Session.
     */
    internal suspend fun cancel(
        sessionId: AgentSessionId,
        handles: List<String>,
    ): BackgroundCancellationResult {
        val cancelled = mutableListOf<String>()
        val alreadyFinished = mutableListOf<String>()
        val unknown = mutableListOf<String>()
        val sessionMutex = sessionMutex(sessionId)
        val settled = mutableListOf<BackgroundExecutionRecord>()
        sessionMutex.withLock {
            val records = store.load(sessionId).records
            for (handle in handles) {
                val record = records.firstOrNull { it.handle == handle }
                when {
                    record == null -> unknown += handle
                    record.state == BackgroundExecutionState.TERMINAL -> alreadyFinished += handle
                    else -> {
                        val updated = record.copy(
                            state = BackgroundExecutionState.TERMINAL,
                            outcome = BackgroundOutcome.CANCELLED,
                            result = null,
                            activationSuppressed = true,
                            settledAtEpochMs = epochClock(),
                        )
                        writeRecord(updated)
                        val live = guard.withLock { executions[record.executionId] }
                        live?.cancelRequested = true
                        live?.record = updated
                        live?.job?.cancel()
                        settled += updated
                        cancelled += handle
                    }
                }
            }
        }
        settled.forEach { notifySettled(sessionId, it) }
        return BackgroundCancellationResult(cancelled, alreadyFinished, unknown)
    }

    /**
     * Stops everything the Session has running: every live admission is cancelled and every
     * live record becomes TERMINAL CANCELLED + suppressed. Terminal records listed in
     * [suppressExecutionIds] (the manager's unacknowledged set) are suppressed so they never
     * activate. Runs after the writes so late callbacks see terminal records and are discarded.
     */
    internal suspend fun stopSession(sessionId: AgentSessionId, suppressExecutionIds: Set<String>) {
        val sessionMutex = sessionMutex(sessionId)
        val live = guard.withLock { executions.values.filter { it.sessionId == sessionId } }
        val settled = mutableListOf<BackgroundExecutionRecord>()
        val changed = mutableListOf<BackgroundExecutionRecord>()
        sessionMutex.withLock {
            val liveById = guard.withLock { executions.toMap() }
            val records = store.load(sessionId).records
            for (record in records) {
                when {
                    record.state != BackgroundExecutionState.TERMINAL -> {
                        val updated = record.copy(
                            state = BackgroundExecutionState.TERMINAL,
                            outcome = BackgroundOutcome.CANCELLED,
                            result = null,
                            activationSuppressed = true,
                            settledAtEpochMs = epochClock(),
                        )
                        writeRecord(updated)
                        liveById[record.executionId]?.record = updated
                        settled += updated
                    }
                    record.executionId in suppressExecutionIds && !record.activationSuppressed -> {
                        val updated = record.copy(activationSuppressed = true)
                        writeRecord(updated)
                        liveById[record.executionId]?.record = updated
                        changed += updated
                    }
                }
            }
            for (execution in live) {
                execution.cancelRequested = true
                execution.job?.cancel()
            }
        }
        settled.forEach { notifySettled(sessionId, it) }
        changed.forEach { notifyRecordChanged(sessionId, it) }
    }

    /**
     * Host-loss suspend: stops the Session's live jobs locally. QUEUED/RUNNING records whose
     * Tool is [ToolRecoveryPolicy.REPLAY_SAFE] return to QUEUED for a later resume; the rest
     * become TERMINAL INTERRUPTED with no result.
     */
    internal suspend fun suspendSession(sessionId: AgentSessionId) {
        val sessionMutex = sessionMutex(sessionId)
        val live = guard.withLock {
            executions.values.filter { it.sessionId == sessionId && it.handedOff }
        }
        val settled = mutableListOf<BackgroundExecutionRecord>()
        val changed = mutableListOf<BackgroundExecutionRecord>()
        sessionMutex.withLock {
            for (record in store.load(sessionId).records) {
                if (record.state == BackgroundExecutionState.TERMINAL) continue
                val tool = jobFactory?.toolFor(record)
                val updated = if (tool?.recoveryPolicy == ToolRecoveryPolicy.REPLAY_SAFE) {
                    record.copy(state = BackgroundExecutionState.QUEUED, startedAtEpochMs = null)
                } else {
                    record.copy(
                        state = BackgroundExecutionState.TERMINAL,
                        outcome = BackgroundOutcome.INTERRUPTED,
                        result = null,
                        settledAtEpochMs = epochClock(),
                    )
                }
                writeRecord(updated)
                guard.withLock { executions[record.executionId] }?.record = updated
                if (updated.state == BackgroundExecutionState.TERMINAL) {
                    settled += updated
                } else {
                    changed += updated
                }
            }
            for (execution in live) {
                execution.cancelRequested = true
                execution.job?.cancel()
            }
        }
        settled.forEach { notifySettled(sessionId, it) }
        changed.forEach { notifyRecordChanged(sessionId, it) }
    }

    /**
     * Re-admits every QUEUED record of the Session that has no live job, using the bound
     * [jobFactory]. Records queued longer than the origin request's `maxQueuedLifetimeMillis`
     * become TERMINAL INTERRUPTED without executing; records whose Tool left the registry become
     * TERMINAL ERROR with a `Tool is no longer available` result.
     */
    internal suspend fun resumeSession(sessionId: AgentSessionId) {
        val factory = jobFactory ?: return
        val sessionMutex = sessionMutex(sessionId)
        val settled = mutableListOf<BackgroundExecutionRecord>()
        val admissions = mutableListOf<Pair<AdmittedSpec, BackgroundExecutionRecord>>()
        sessionMutex.withLock {
            val stored = store.load(sessionId)
            val liveById = guard.withLock { executions.toMap() }
            for (record in stored.records) {
                if (record.state != BackgroundExecutionState.QUEUED) continue
                val live = liveById[record.executionId]
                if (live != null && live.job?.isActive == true) continue
                val origin = stored.origins.firstOrNull { it.runId == record.originRunId }
                val maxLifetime =
                    origin?.request?.engine?.runtime?.background?.maxQueuedLifetimeMillis
                if (maxLifetime != null && epochClock() - record.queuedAtEpochMs > maxLifetime) {
                    val updated = record.copy(
                        state = BackgroundExecutionState.TERMINAL,
                        outcome = BackgroundOutcome.INTERRUPTED,
                        result = null,
                        settledAtEpochMs = epochClock(),
                    )
                    writeRecord(updated)
                    settled += updated
                    continue
                }
                val spec = try {
                    if (factory.toolFor(record) == null) null else factory.specFor(record)
                } catch (failure: Throwable) {
                    null
                }
                if (spec == null) {
                    val updated = record.copy(
                        state = BackgroundExecutionState.TERMINAL,
                        outcome = BackgroundOutcome.ERROR,
                        result = ToolExecutionResult(
                            toolCallId = record.originCallId,
                            toolName = record.toolName,
                            result = kotlinx.serialization.json.JsonPrimitive(
                                "Tool is no longer available",
                            ),
                            isError = true,
                            displayText = "Tool is no longer available",
                        ),
                        resultVersion = maxOf(1, record.resultVersion),
                        settledAtEpochMs = epochClock(),
                    )
                    writeRecord(updated)
                    settled += updated
                    continue
                }
                admissions += spec to record
            }
        }
        for ((spec, record) in admissions) {
            launch(spec, sessionMutex, resumedRecord = record)
        }
        settled.forEach { notifySettled(sessionId, it) }
    }

    /**
     * Deletes the listed records and cancels their live jobs; records still live are first
     * marked TERMINAL in memory so a late settle callback is discarded without writing them
     * back. Origins unreferenced by remaining records are deleted too.
     */
    internal suspend fun deleteRecords(sessionId: AgentSessionId, executionIds: List<String>) {
        val sessionMutex = sessionMutex(sessionId)
        sessionMutex.withLock {
            for (executionId in executionIds) {
                val live = guard.withLock { executions[executionId] }
                live?.let {
                    it.cancelRequested = true
                    it.record = it.record?.copy(
                        state = BackgroundExecutionState.TERMINAL,
                        outcome = BackgroundOutcome.CANCELLED,
                        activationSuppressed = true,
                        settledAtEpochMs = epochClock(),
                    )
                    it.job?.cancel()
                }
                store.delete(sessionId, executionId)
            }
            val reloaded = store.load(sessionId)
            val referencedRuns = reloaded.records.map { it.originRunId }.toSet()
            for (origin in reloaded.origins) {
                if (origin.runId !in referencedRuns) {
                    store.deleteOrigin(sessionId, origin.runId)
                }
            }
            bumpVersion(sessionId)
        }
    }

    /**
     * Cancels and deletes everything the Session owns (manager `delete`). Live executions are
     * cancelled and marked terminal in memory so their late callbacks are discarded.
     */
    internal suspend fun deleteSession(sessionId: AgentSessionId) {
        val sessionMutex = sessionMutex(sessionId)
        sessionMutex.withLock {
            val live = guard.withLock { executions.values.filter { it.sessionId == sessionId } }
            for (execution in live) {
                execution.cancelRequested = true
                execution.record = execution.record?.copy(
                    state = BackgroundExecutionState.TERMINAL,
                    outcome = BackgroundOutcome.CANCELLED,
                    activationSuppressed = true,
                    settledAtEpochMs = epochClock(),
                )
                execution.job?.cancel()
            }
            store.deleteSession(sessionId)
            bumpVersion(sessionId)
        }
    }

    /** Cancels every live execution and clears all Sessions (manager `clear`). */
    internal suspend fun clearAll() {
        val sessionIds = guard.withLock {
            executions.values.map { it.sessionId } + listeners.keys.map(::AgentSessionId)
        }.toSet()
        for (sessionId in sessionIds) {
            deleteSession(sessionId)
        }
        store.clear()
    }

    /** Waits until every referenced known record is TERMINAL or [waitMillis] elapses. */
    internal suspend fun waitForSettlement(
        sessionId: AgentSessionId,
        handles: List<String>,
        waitMillis: Long,
    ) {
        if (waitMillis <= 0) return
        val version = sessionVersion(sessionId)
        withTimeoutOrNull(waitMillis) {
            while (true) {
                val observed = version.value
                val pending = handles.any { handle ->
                    store.load(sessionId).records.any {
                        it.handle == handle && it.state != BackgroundExecutionState.TERMINAL
                    }
                }
                if (!pending) return@withTimeoutOrNull
                version.first { it != observed }
            }
        }
    }

    private suspend fun sessionMutex(sessionId: AgentSessionId): Mutex =
        guard.withLock { sessionMutexes.getOrPut(sessionId.value) { Mutex() } }

    private suspend fun sessionVersion(sessionId: AgentSessionId): MutableStateFlow<Long> =
        guard.withLock { sessionVersions.getOrPut(sessionId.value) { MutableStateFlow(0L) } }

    /** Every record write wakes Session-scoped waiters. */
    private suspend fun writeRecord(record: BackgroundExecutionRecord) {
        store.upsert(record)
        bumpVersion(record.sessionId)
    }

    private suspend fun bumpVersion(sessionId: AgentSessionId) {
        sessionVersion(sessionId).value += 1
    }

    private suspend fun runExecution(execution: AdmittedExecution, sessionMutex: Mutex) {
        val spec = execution.spec
        val backgroundSpan = spec.spanFactory()
        val jobMark = timeSource.markNow()
        try {
            val permit = try {
                spec.executor.executionPermit(spec.executionRequest)
            } catch (failure: Throwable) {
                settle(execution, sessionMutex, Result.failure(failure))
                return
            }
            try {
                permit.acquire()
            } catch (failure: Throwable) {
                settle(execution, sessionMutex, Result.failure(failure))
                return
            }
            val executionOutcome = runCatching {
                val maxQueuedLifetime = spec.maxQueuedLifetimeMillis
                if (
                    maxQueuedLifetime != null &&
                    epochClock() - execution.queuedAtEpochMs > maxQueuedLifetime
                ) {
                    throw QueuedLifetimeExpired()
                }
                markStarted(execution, sessionMutex)
                execution.queuedWait = jobMark.elapsedNow()
                withTimeoutOrNull(spec.effectiveTimeoutMillis) {
                    spec.executor.execute(spec.executionRequest)
                }
            }
            val failures = CleanupFailureAccumulator()
            executionOutcome.exceptionOrNull()?.let(failures::record)
            failures.capture { permit.release() }
            failures.failureOrNull()?.let { failure ->
                if (execution.startPersistenceFailed && !execution.handedOff) {
                    execution.foreground.completeExceptionally(failure)
                    cleanup(execution)
                    return
                }
                if (failure is QueuedLifetimeExpired) {
                    settleInterrupted(execution, sessionMutex)
                    return
                }
                settle(execution, sessionMutex, Result.failure(failure))
                return
            }
            settle(execution, sessionMutex, Result.success(executionOutcome.getOrNull()))
        } finally {
            backgroundSpan.end(
                when (execution.spanOutcome) {
                    "succeeded" -> TraceStatus.OK
                    "error", "timeout" -> TraceStatus.ERROR
                    else -> TraceStatus.UNSET
                },
                traceAttributes(
                    "magrathea.outcome" to (execution.spanOutcome ?: "unsettled"),
                    "magrathea.tool.background.queued_wait_ms" to
                        (execution.queuedWait?.inWholeMilliseconds ?: 0L),
                    "magrathea.tool.background.execution_ms" to
                        jobMark.elapsedNow().inWholeMilliseconds,
                ),
            )
        }
    }

    /**
     * Marks the execution started. While the Run still waits, the journal STARTED write runs
     * through [AdmittedSpec.onExecutionStarted]; after a handoff the record itself moves to
     * RUNNING with its start timestamp.
     */
    private suspend fun markStarted(execution: AdmittedExecution, sessionMutex: Mutex) {
        var changed: BackgroundExecutionRecord? = null
        try {
            sessionMutex.withLock {
                if (execution.record?.state == BackgroundExecutionState.TERMINAL) {
                    throw ExecutionCancelled()
                }
                execution.started = true
                execution.startedAtEpochMs = epochClock()
                if (execution.handedOff) {
                    val current = checkNotNull(execution.record)
                    val updated = current.copy(
                        state = BackgroundExecutionState.RUNNING,
                        startedAtEpochMs = execution.startedAtEpochMs,
                    )
                    writeRecord(updated)
                    execution.record = updated
                    changed = updated
                } else {
                    // Journal STARTED inside the Session mutex so a handoff can never write
                    // COMPLETED before it.
                    execution.spec.onExecutionStarted()
                }
            }
        } catch (failure: Throwable) {
            if (failure !is ExecutionCancelled && !execution.handedOff) {
                execution.startPersistenceFailed = true
            }
            throw failure
        }
        changed?.let { notifyRecordChanged(execution.sessionId, it) }
    }

    /**
     * A queued execution that outlived its allowance never runs: a handed-off record becomes
     * TERMINAL INTERRUPTED; a still-foreground admission answers the waiting Run with failure.
     */
    private suspend fun settleInterrupted(execution: AdmittedExecution, sessionMutex: Mutex) =
        withContext(NonCancellable) {
            settleInterruptedLocked(execution, sessionMutex)
        }

    private suspend fun settleInterruptedLocked(execution: AdmittedExecution, sessionMutex: Mutex) {
        var settled: BackgroundExecutionRecord? = null
        execution.spanOutcome = "interrupted"
        sessionMutex.withLock {
            if (!execution.handedOff) {
                execution.foreground.complete(
                    Result.failure(ExecutionCancelled()),
                )
                return@withLock
            }
            val current = execution.record
            if (current != null && current.state != BackgroundExecutionState.TERMINAL) {
                val updated = current.copy(
                    state = BackgroundExecutionState.TERMINAL,
                    outcome = BackgroundOutcome.INTERRUPTED,
                    result = null,
                    settledAtEpochMs = epochClock(),
                )
                writeRecord(updated)
                execution.record = updated
                settled = updated
            }
        }
        execution.spanOutcome = "interrupted"
        settled?.let { notifySettled(execution.sessionId, it) }
        cleanup(execution)
    }

    private suspend fun settle(
        execution: AdmittedExecution,
        sessionMutex: Mutex,
        outcome: Result<ToolExecutionResult?>,
    ) = withContext(NonCancellable) {
        val failure = outcome.exceptionOrNull()
        val wasCancelled = failure is CancellationException &&
            (execution.cancelRequested || !job.isActive)
        if (wasCancelled) {
            // Actually-cancelled work never produces an outcome; the record was already settled
            // or is left for host-loss recovery. A still-waiting batch must not hang.
            execution.spanOutcome = "cancelled"
            execution.foreground.complete(outcome)
            if (execution.handedOff) cleanup(execution)
            return@withContext
        }
        var completedForeground = false
        if (!execution.handedOff) {
            sessionMutex.withLock {
                if (!execution.handedOff) {
                    // Completed under the Session mutex so a concurrent handoff cannot
                    // double-answer.
                    execution.spanOutcome = outcome.fold(
                        onSuccess = { if (it == null) "timeout" else "succeeded" },
                        onFailure = { "error" },
                    )
                    execution.foreground.complete(outcome)
                    completedForeground = true
                }
            }
            if (completedForeground) return@withContext
        }
        // Finalize outside the Session mutex so interceptors cannot block other Session writes.
        val record = execution.record ?: return@withContext cleanup(execution)
        val origin = store.load(execution.sessionId).origins
            .firstOrNull { it.runId == record.originRunId }
        val (settleOutcome, result) = execution.spec.lateFinalize(outcome, record, origin)
        var settled: BackgroundExecutionRecord? = null
        sessionMutex.withLock {
            val current = execution.record
            if (current != null && current.state != BackgroundExecutionState.TERMINAL) {
                val updated = current.copy(
                    state = BackgroundExecutionState.TERMINAL,
                    outcome = settleOutcome,
                    result = result,
                    resultVersion = maxOf(1, current.resultVersion),
                    settledAtEpochMs = epochClock(),
                )
                writeRecord(updated)
                execution.record = updated
                settled = updated
            }
        }
        execution.spanOutcome = settled?.outcome?.name?.lowercase()
        settled?.let { notifySettled(execution.sessionId, it) }
        cleanup(execution)
    }

    private suspend fun cleanup(execution: AdmittedExecution) {
        guard.withLock {
            if (executions[execution.executionId] === execution) {
                executions.remove(execution.executionId)
            }
        }
    }

    private suspend fun notifyRecordChanged(sessionId: AgentSessionId, record: BackgroundExecutionRecord) {
        guard.withLock { listeners[sessionId.value] }?.onRecordChanged(sessionId, record)
    }

    private suspend fun notifySettled(sessionId: AgentSessionId, record: BackgroundExecutionRecord) {
        guard.withLock { listeners[sessionId.value] }?.onSettled(sessionId, record)
    }
}

/** One call admitted by the Run whose execution belongs to the executor's scope. */
internal class AdmittedSpec(
    val sessionId: AgentSessionId,
    val runId: AgentRunId,
    val toolCallId: String,
    val toolName: String,
    val executor: ToolExecutor,
    val executionRequest: ToolExecutionRequest,
    val effectiveTimeoutMillis: Long,
    /** Origin-request queued allowance; a queued execution older than this never starts. */
    val maxQueuedLifetimeMillis: Long? = null,
    /** Timestamp reused for resumed records so queued lifetime is measured from the origin. */
    val queuedAtEpochMs: Long? = null,
    val onExecutionStarted: suspend () -> Unit,
    /** Opened at job start, closed at settlement; carries no Tool arguments or results. */
    val spanFactory: suspend () -> RuntimeTraceSpan = { RuntimeTraceSpan.noop() },
    val lateFinalize: suspend (
        outcome: Result<ToolExecutionResult?>,
        record: BackgroundExecutionRecord,
        origin: BackgroundOriginContext?,
    ) -> Pair<BackgroundOutcome, ToolExecutionResult?>,
)

/** Live state of one admitted execution; shared by the Run and the executor under a Session mutex. */
internal class AdmittedExecution(
    val spec: AdmittedSpec,
    val queuedAtEpochMs: Long,
) {
    val sessionId: AgentSessionId get() = spec.sessionId
    val runId: AgentRunId get() = spec.runId
    val executionId: String get() = spec.executionRequest.executionId

    /** Resolves while the Run still waits; stays incomplete once the call is handed off. */
    val foreground = CompletableDeferred<Result<ToolExecutionResult?>>()

    internal var job: Job? = null
    internal var started = false
    internal var startedAtEpochMs: Long? = null
    internal var handedOff = false
    internal var cancelRequested = false
    internal var startPersistenceFailed = false
    internal var record: BackgroundExecutionRecord? = null
    internal var queuedWait: kotlin.time.Duration? = null
    internal var spanOutcome: String? = null
}

private class ExecutionCancelled : CancellationException()

private class QueuedLifetimeExpired : CancellationException()
