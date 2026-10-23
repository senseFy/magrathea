package saien.magrathea.runtime

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.coroutineContext
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

private const val HANDLE_ALPHABET = "abcdefghjkmnpqrstuvwxyz23456789"
private const val HANDLE_RANDOM_CHARS = 10
private const val SETTLEMENT_POLL_MILLIS = 25L

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
    private val executions = LinkedHashMap<String, AdmittedExecution>()

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
        val execution = AdmittedExecution(spec, queuedAtEpochMs = epochClock())
        guard.withLock { executions[spec.executionRequest.executionId] = execution }
        execution.job = scope.launch { runExecution(execution, sessionMutex) }
        return execution
    }

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
            store.upsert(record)
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
    internal suspend fun cancel(sessionId: AgentSessionId, handles: List<String>): CancelBatch {
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
                        store.upsert(updated)
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
        return CancelBatch(cancelled, alreadyFinished, unknown)
    }

    /** Waits until every referenced known record is TERMINAL or [waitMillis] elapses. */
    internal suspend fun waitForSettlement(
        sessionId: AgentSessionId,
        handles: List<String>,
        waitMillis: Long,
    ) {
        if (waitMillis <= 0) return
        val deadline = timeSource.markNow() + waitMillis.milliseconds
        while (coroutineContext.isActive && timeSource.markNow() < deadline) {
            val records = store.load(sessionId).records
            val pending = handles.any { handle ->
                records.any { it.handle == handle && it.state != BackgroundExecutionState.TERMINAL }
            }
            if (!pending) return
            delay(SETTLEMENT_POLL_MILLIS)
        }
    }

    private suspend fun sessionMutex(sessionId: AgentSessionId): Mutex =
        guard.withLock { sessionMutexes.getOrPut(sessionId.value) { Mutex() } }

    private suspend fun runExecution(execution: AdmittedExecution, sessionMutex: Mutex) {
        val spec = execution.spec
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
            markStarted(execution, sessionMutex)
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
            settle(execution, sessionMutex, Result.failure(failure))
            return
        }
        settle(execution, sessionMutex, Result.success(executionOutcome.getOrNull()))
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
                    store.upsert(updated)
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

    private suspend fun settle(
        execution: AdmittedExecution,
        sessionMutex: Mutex,
        outcome: Result<ToolExecutionResult?>,
    ) {
        // Executor-scope cancellation (control cancel or executor close) never produces an
        // outcome; the record was already settled or is left for host-loss recovery.
        if (outcome.exceptionOrNull() is CancellationException) {
            cleanup(execution)
            return
        }
        var settled: BackgroundExecutionRecord? = null
        sessionMutex.withLock {
            val existing = execution.record
            if (existing != null && existing.state == BackgroundExecutionState.TERMINAL) {
                // A callback for a record that is already TERMINAL is discarded.
                return@withLock
            }
            if (!execution.handedOff) {
                // Completed under the Session mutex so a concurrent handoff cannot double-answer.
                execution.foreground.complete(outcome)
            } else {
                val record = checkNotNull(existing)
                val origin = store.load(execution.sessionId).origins
                    .firstOrNull { it.runId == record.originRunId }
                val (settleOutcome, result) = execution.spec.lateFinalize(outcome, record, origin)
                val updated = record.copy(
                    state = BackgroundExecutionState.TERMINAL,
                    outcome = settleOutcome,
                    result = result,
                    resultVersion = maxOf(1, record.resultVersion),
                    settledAtEpochMs = epochClock(),
                )
                store.upsert(updated)
                execution.record = updated
                settled = updated
            }
        }
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

    internal class CancelBatch(
        val cancelled: List<String>,
        val alreadyFinished: List<String>,
        val unknown: List<String>,
    )
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
    val onExecutionStarted: suspend () -> Unit,
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
}

private class ExecutionCancelled : CancellationException()
