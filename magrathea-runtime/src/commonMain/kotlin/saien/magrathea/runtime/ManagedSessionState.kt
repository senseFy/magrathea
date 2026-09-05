package saien.magrathea.runtime

import kotlinx.coroutines.CompletableDeferred
import saien.magrathea.core.AgentEvent
import saien.magrathea.core.AgentFailureCode
import saien.magrathea.core.AgentPersistenceRecord
import saien.magrathea.core.AgentRecoveryDisposition
import saien.magrathea.core.AgentRecoveryInfo
import saien.magrathea.core.AgentRequest
import saien.magrathea.core.AgentRunId
import saien.magrathea.core.AgentSessionId
import saien.magrathea.core.AgentSessionSnapshot
import saien.magrathea.core.AgentStateSnapshot
import saien.magrathea.core.AgentStatus
import saien.magrathea.core.StopReason

/** Result knowledge only: execution and lifetime are deliberately not result states. */
internal enum class SessionResolution {
    UNKNOWN,
    NEW,
    INACTIVE,
    RESUMABLE,
    BLOCKED,
    TERMINAL;

    val allowsStart: Boolean
        get() = this == NEW || this == INACTIVE || this == TERMINAL
}

internal enum class SessionLifecycle {
    OPEN,
    CLOSED,
    DELETED,
}

internal data class ActiveExecution(
    val token: Long,
    val completion: CompletableDeferred<Unit>,
    val baseline: SessionResult,
)

/** Pure transitions. Only the manager performs I/O, locks, publication, and completion delivery. */
internal class SessionMachine private constructor(
    val sessionId: AgentSessionId,
    val result: SessionResult,
    val execution: ActiveExecution?,
    val generation: Long,
    val resultVersion: Long,
    val lifecycle: SessionLifecycle,
) {
    constructor(sessionId: AgentSessionId, result: SessionResult = SessionResult()) :
        this(sessionId, result, null, 0L, 0L, SessionLifecycle.OPEN)

    val isExecuting: Boolean
        get() = lifecycle == SessionLifecycle.OPEN && execution != null && !result.isConfirmed
    val hasPendingWork: Boolean get() = !result.resolution.allowsStart

    fun snapshot(revision: Long): AgentSessionRuntimeSnapshot = AgentSessionRuntimeSnapshot(
        revision = revision,
        sessionId = sessionId,
        request = result.request,
        runId = result.runId,
        state = result.state,
        phase = when (lifecycle) {
            SessionLifecycle.CLOSED -> AgentSessionPhase.CLOSED
            SessionLifecycle.DELETED -> AgentSessionPhase.DELETED
            SessionLifecycle.OPEN -> when (result.resolution) {
                SessionResolution.UNKNOWN -> if (execution != null) {
                    AgentSessionPhase.ACTIVE
                } else {
                    AgentSessionPhase.RECOVERY_BLOCKED
                }
                SessionResolution.NEW -> AgentSessionPhase.NEW
                SessionResolution.INACTIVE -> AgentSessionPhase.INACTIVE
                SessionResolution.RESUMABLE -> AgentSessionPhase.RESUMABLE
                SessionResolution.BLOCKED -> AgentSessionPhase.RECOVERY_BLOCKED
                SessionResolution.TERMINAL -> AgentSessionPhase.TERMINAL
            }
        },
        recovery = result.recovery,
        failure = result.failure,
        lastEvent = result.lastEvent,
    )

    fun requireOpen() {
        when (lifecycle) {
            SessionLifecycle.OPEN -> Unit
            SessionLifecycle.CLOSED -> throw AgentSessionException(AgentSessionErrorCode.CLOSED)
            SessionLifecycle.DELETED -> throw AgentSessionException(AgentSessionErrorCode.DELETED)
        }
    }

    fun requireAdmission(resume: Boolean) {
        requireOpen()
        if (execution != null) throw AgentSessionException(AgentSessionErrorCode.BUSY)
        if (resume) {
            if (result.resolution != SessionResolution.RESUMABLE) {
                throw AgentSessionException(AgentSessionErrorCode.INVALID_STATE)
            }
        } else if (!result.resolution.allowsStart) {
            throw AgentSessionException(AgentSessionErrorCode.BUSY)
        }
    }

    fun begin(request: AgentRequest?, resume: Boolean): SessionMachine {
        requireAdmission(resume)
        val effectiveRequest = request ?: result.request ?: throw AgentSessionException(AgentSessionErrorCode.NOT_FOUND)
        check(effectiveRequest.sessionId == sessionId)
        check(generation != Long.MAX_VALUE) { "Agent session execution tokens are exhausted" }
        val attempt = ActiveExecution(generation + 1, CompletableDeferred(), result)
        val previous = result.state
        val provisional = if (resume && previous != null) {
            previous.copy(messages = effectiveRequest.messages, status = AgentStatus.RUNNING, stopReason = null)
        } else {
            AgentStateSnapshot(
                messages = effectiveRequest.messages,
                status = AgentStatus.RUNNING,
                usage = previous?.usage ?: saien.magrathea.core.TokenUsage(),
                latestRequestUsage = previous?.latestRequestUsage ?: saien.magrathea.core.TokenUsage(),
                contextManagement = previous?.contextManagement ?: saien.magrathea.core.ContextManagementState(),
            )
        }
        return update(
            execution = attempt,
            generation = attempt.token,
            result = SessionResult(
                request = effectiveRequest,
                runId = result.runId.takeIf { resume },
                state = provisional,
                resolution = SessionResolution.UNKNOWN,
                executionToken = attempt.token,
            ),
        )
    }

    /** Null means the event was rejected, not that the result is unknown. */
    fun recordEvent(attempt: ActiveExecution, event: AgentEvent): SessionMachine? {
        if (!owns(attempt) || (result.isConfirmed && !event.isTerminal())) return null
        return update(result = result.recordEvent(event, attempt.token))
    }

    fun recordUnexpectedFailure(attempt: ActiveExecution): SessionMachine? {
        if (!owns(attempt) || result.isConfirmed) return null
        val failure = AgentEvent.Failed(sessionId, AgentFailureCode.INTERNAL)
        return update(result = result.copy(
            state = reduceSessionState(result.state, result.request, failure),
            resolution = SessionResolution.UNKNOWN,
            recovery = null,
            failure = failure.code,
            lastEvent = failure,
        ))
    }

    fun interrupted(attempt: ActiveExecution, recovery: AgentRecoveryInfo): SessionMachine {
        if (lifecycle != SessionLifecycle.OPEN || generation != attempt.token) return this
        if (result.isTerminalWinner(attempt)) return this
        if (recovery.disposition == AgentRecoveryDisposition.NOT_FOUND) {
            throw AgentSessionException(AgentSessionErrorCode.INVALID_STATE, "Runner lost the interrupted session")
        }
        return update(result = result.withRecovery(recovery, attempt.token))
    }

    fun observeRunner(observed: SessionMachine, recovery: AgentRecoveryInfo): SessionMachine {
        if (
            !acceptsObservationFrom(observed) ||
            recovery.disposition == AgentRecoveryDisposition.ACTIVE ||
            recovery.disposition == AgentRecoveryDisposition.NOT_FOUND ||
            (result.runId != null && recovery.runId != result.runId)
        ) return this
        return update(result = result.withRecovery(
            recovery,
            result.executionToken.takeIf { recovery.runId != null && recovery.runId == result.runId },
        ))
    }

    fun observePersistence(observed: SessionMachine, observation: RecoveryObservation): SessionMachine =
        if (acceptsObservationFrom(observed)) {
            update(result = result.resolve(observation, absent = SessionResult()))
        } else {
            this
        }

    fun settle(
        attempt: ActiveExecution,
        observed: SessionMachine,
        observation: RecoveryObservation,
    ): SessionMachine {
        if (execution?.token != attempt.token) return this
        val resolved = if (acceptsObservationFrom(observed)) {
            result.resolve(observation, absent = attempt.baseline)
        } else {
            result
        }
        // No stale/failed observation can prevent this owner from settling, or reopen a lifetime.
        return update(execution = null, result = resolved)
    }

    fun recoveryInfo(): AgentRecoveryInfo {
        if (execution == null && !result.isConfirmed) {
            throw AgentSessionException(
                AgentSessionErrorCode.STORAGE,
                "Agent session recovery could not be established; inspect again before starting",
            )
        }
        return AgentRecoveryInfo(
            sessionId = sessionId,
            runId = result.runId,
            disposition = when (result.resolution) {
                SessionResolution.UNKNOWN -> AgentRecoveryDisposition.ACTIVE
                SessionResolution.NEW -> AgentRecoveryDisposition.NOT_FOUND
                SessionResolution.INACTIVE, SessionResolution.TERMINAL -> AgentRecoveryDisposition.TERMINAL
                SessionResolution.RESUMABLE -> AgentRecoveryDisposition.RESUMABLE
                SessionResolution.BLOCKED -> AgentRecoveryDisposition.BLOCKED
            },
            status = result.state?.status,
            state = result.state,
            cursor = result.recovery?.cursor,
            interruption = result.recovery?.interruption,
            blockedReason = result.recovery?.blockedReason,
        )
    }

    /** Chooses the winner and required commit without performing storage or publishing success. */
    fun planCancellation(
        before: SessionMachine,
        persisted: AgentPersistenceRecord?,
        nowEpochMs: Long,
    ): SessionCancellation {
        check(execution == null && generation == before.generation)
        val attempt = before.execution
        if (result.isTerminalWinner(attempt)) return SessionCancellation(generation, result)
        val currentRun = result.takeIf { it.belongsTo(attempt) }
        val knownRunId = currentRun?.runId ?: before.result.runId
        val record = persisted?.takeIf {
            knownRunId?.let { id -> it.snapshot.runId == id }
                ?: (before.result.request != null && it.snapshot.request == before.result.request)
        }
        val terminal = record?.snapshot?.takeIf {
            knownRunId != null && it.state.status in NON_CANCELLED_TERMINAL_STATUSES
        }
        val token = attempt?.token ?: result.executionToken
        if (terminal != null) {
            return SessionCancellation(generation, SessionResult.fromStored(terminal).copy(
                executionToken = token,
                lastEvent = if (terminal.state.status == AgentStatus.COMPLETED) {
                    AgentEvent.Completed(sessionId, terminal.state)
                } else {
                    null
                },
            ))
        }
        val baseState = if (before.isExecuting) {
            record?.snapshot?.state?.takeIf { it.status == AgentStatus.CANCELLED }
                ?: currentRun?.state ?: before.result.state ?: record?.snapshot?.state
        } else {
            before.result.recovery?.state ?: before.result.state
                ?: record?.checkpoint?.state ?: record?.snapshot?.state
        }
        val cancelled = (baseState ?: AgentStateSnapshot(before.result.request?.messages.orEmpty())).copy(
            status = AgentStatus.CANCELLED,
            stopReason = StopReason.CANCELLED,
        )
        val request = (before.result.request ?: result.request ?: record?.snapshot?.request)
            ?.copy(messages = cancelled.messages)
        val commit = record?.takeIf {
            it.checkpoint != null || it.snapshot.request != request ||
                it.snapshot.state != cancelled || it.snapshot.interruption != null
        }?.snapshot?.copy(
            request = request ?: record.snapshot.request,
            state = cancelled,
            interruption = null,
            updatedAtEpochMs = nowEpochMs,
        )
        return SessionCancellation(
            generation = generation,
            result = result.copy(
                request = request ?: result.request,
                runId = record?.snapshot?.runId ?: knownRunId,
                state = cancelled,
                resolution = SessionResolution.TERMINAL,
                recovery = null,
                failure = null,
                lastEvent = AgentEvent.Cancelled(sessionId),
                executionToken = token,
            ),
            commit = commit,
            removesPendingWork = record?.let {
                it.checkpoint != null || it.snapshot.state.status in RECOVERABLE_STORED_STATUSES
            } ?: false,
        )
    }

    fun prepareCancellation(plan: SessionCancellation): SessionMachine =
        if (acceptsCancellation(plan) && plan.removesPendingWork) {
            update(result = result.copy(resolution = SessionResolution.UNKNOWN, recovery = null))
        } else {
            this
        }

    fun completeCancellation(plan: SessionCancellation): SessionMachine =
        if (acceptsCancellation(plan)) update(result = plan.result) else this

    private fun acceptsCancellation(plan: SessionCancellation): Boolean =
        lifecycle == SessionLifecycle.OPEN && execution == null && generation == plan.generation

    fun replaceRequest(request: AgentRequest): SessionMachine {
        requireAdmission(resume = false)
        return update(result = result.copy(request = request))
    }

    fun close(deleted: Boolean): SessionMachine = if (lifecycle == SessionLifecycle.OPEN) {
        update(lifecycle = if (deleted) SessionLifecycle.DELETED else SessionLifecycle.CLOSED)
    } else {
        this
    }

    fun acceptsObservationFrom(observed: SessionMachine): Boolean =
        lifecycle == SessionLifecycle.OPEN && sessionId == observed.sessionId &&
            generation == observed.generation && resultVersion == observed.resultVersion

    private fun owns(attempt: ActiveExecution): Boolean =
        lifecycle == SessionLifecycle.OPEN && execution?.token == attempt.token

    private fun update(
        result: SessionResult = this.result,
        execution: ActiveExecution? = this.execution,
        generation: Long = this.generation,
        lifecycle: SessionLifecycle = this.lifecycle,
    ): SessionMachine {
        val version = if (this.result.sameOutcomeAs(result)) resultVersion else {
            check(resultVersion != Long.MAX_VALUE) { "Agent session result versions are exhausted" }
            resultVersion + 1
        }
        return SessionMachine(sessionId, result, execution, generation, version, lifecycle)
    }
}

internal data class SessionCancellation(
    val generation: Long,
    val result: SessionResult,
    val commit: AgentSessionSnapshot? = null,
    val removesPendingWork: Boolean = false,
)

/** Internal facts. Public snapshots are outputs and cannot be converted back into this type. */
internal data class SessionResult(
    val request: AgentRequest? = null,
    val runId: AgentRunId? = null,
    val state: AgentStateSnapshot? = null,
    val resolution: SessionResolution = SessionResolution.NEW,
    val recovery: AgentRecoveryInfo? = null,
    /** Domain failure, independent of the diagnostic last event. */
    val failure: AgentFailureCode? = null,
    val lastEvent: AgentEvent? = null,
    val executionToken: Long? = null,
) {
    val isConfirmed: Boolean get() = resolution != SessionResolution.UNKNOWN

    fun belongsTo(attempt: ActiveExecution?): Boolean = attempt == null || executionToken == attempt.token

    fun isTerminalWinner(attempt: ActiveExecution?): Boolean =
        resolution == SessionResolution.TERMINAL &&
            belongsTo(attempt) && state?.status in NON_CANCELLED_TERMINAL_STATUSES

    /** Presentation changes and equal-value copies do not invalidate an asynchronous observation. */
    fun sameOutcomeAs(other: SessionResult): Boolean =
        request == other.request && runId == other.runId && state == other.state &&
            resolution == other.resolution && recovery == other.recovery &&
            failure == other.failure && executionToken == other.executionToken

    fun recordEvent(event: AgentEvent, token: Long): SessionResult = copy(
        runId = when (event) {
            is AgentEvent.Started -> event.runId
            is AgentEvent.CheckpointSaved -> event.checkpoint.runId
            is AgentEvent.Interrupted -> event.runId
            is AgentEvent.RecoveryBlocked -> event.runId
            else -> runId
        },
        state = reduceSessionState(state, request, event),
        resolution = when (event) {
            is AgentEvent.Interrupted -> SessionResolution.RESUMABLE
            is AgentEvent.RecoveryBlocked -> SessionResolution.BLOCKED
            is AgentEvent.Completed, is AgentEvent.Cancelled -> SessionResolution.TERMINAL
            is AgentEvent.Failed -> if (event.code == AgentFailureCode.STORAGE) {
                SessionResolution.UNKNOWN
            } else {
                SessionResolution.TERMINAL
            }
            else -> SessionResolution.UNKNOWN
        },
        recovery = when (event) {
            is AgentEvent.Interrupted -> AgentRecoveryInfo(
                sessionId = event.sessionId,
                runId = event.runId,
                disposition = AgentRecoveryDisposition.RESUMABLE,
                status = event.state.status,
                state = event.state,
                interruption = event.interruption,
            )
            is AgentEvent.RecoveryBlocked -> AgentRecoveryInfo(
                sessionId = event.sessionId,
                runId = event.runId,
                disposition = AgentRecoveryDisposition.BLOCKED,
                status = state?.status,
                state = state,
                blockedReason = event.reason,
            )
            else -> if (event.isTerminal()) null else recovery
        },
        failure = (event as? AgentEvent.Failed)?.code,
        lastEvent = event,
        executionToken = token,
    )

    fun withRecovery(info: AgentRecoveryInfo, token: Long?): SessionResult = copy(
        runId = info.runId ?: runId,
        state = info.state ?: state,
        resolution = info.resolution(resolution),
        recovery = info,
        failure = null,
        executionToken = token,
    )

    fun resolve(observation: RecoveryObservation, absent: SessionResult): SessionResult = when (observation) {
        RecoveryObservation.Absent -> when {
            isConfirmed -> this
            failure != null -> copy(resolution = SessionResolution.TERMINAL)
            else -> absent.takeIf { it.resolution.allowsStart } ?: SessionResult()
        }
        RecoveryObservation.Unavailable -> this
        is RecoveryObservation.Present -> {
            val snapshot = observation.record.snapshot
            when {
                isConfirmed && snapshot.runId != runId -> this
                snapshot.runId != runId && observation.recovery == null && failure != null ->
                    copy(resolution = SessionResolution.TERMINAL)
                else -> copy(
                    request = snapshot.request,
                    runId = snapshot.runId,
                    state = observation.recovery?.state ?: snapshot.state,
                    resolution = observation.recovery?.resolution(snapshot.state.resolution())
                        ?: snapshot.state.resolution(),
                    recovery = observation.recovery,
                    executionToken = executionToken.takeIf { snapshot.runId == runId },
                )
            }
        }
    }

    companion object {
        fun fromStored(
            snapshot: AgentSessionSnapshot,
            recovery: AgentRecoveryInfo? = null,
        ): SessionResult = SessionResult(
            request = snapshot.request,
            runId = recovery?.runId ?: snapshot.runId,
            state = recovery?.state ?: snapshot.state,
            resolution = recovery?.resolution(snapshot.state.resolution()) ?: snapshot.state.resolution(),
            recovery = recovery,
        )
    }
}

internal sealed interface RecoveryObservation {
    data object Absent : RecoveryObservation
    data object Unavailable : RecoveryObservation
    data class Present(val record: AgentPersistenceRecord, val recovery: AgentRecoveryInfo?) : RecoveryObservation
}

private fun AgentStateSnapshot.resolution(): SessionResolution = when (status) {
    AgentStatus.IDLE -> SessionResolution.INACTIVE
    AgentStatus.RUNNING, AgentStatus.WAITING_FOR_TOOLS -> SessionResolution.UNKNOWN
    AgentStatus.INTERRUPTED -> SessionResolution.RESUMABLE
    AgentStatus.COMPLETED, AgentStatus.FAILED, AgentStatus.CANCELLED -> SessionResolution.TERMINAL
}

private fun AgentRecoveryInfo.resolution(fallback: SessionResolution): SessionResolution = when (disposition) {
    AgentRecoveryDisposition.ACTIVE -> SessionResolution.UNKNOWN
    AgentRecoveryDisposition.RESUMABLE -> SessionResolution.RESUMABLE
    AgentRecoveryDisposition.BLOCKED -> SessionResolution.BLOCKED
    AgentRecoveryDisposition.TERMINAL -> SessionResolution.TERMINAL
    AgentRecoveryDisposition.NOT_FOUND -> fallback
}

private val NON_CANCELLED_TERMINAL_STATUSES = setOf(AgentStatus.COMPLETED, AgentStatus.FAILED)

private fun reduceSessionState(
    current: AgentStateSnapshot?,
    request: AgentRequest?,
    event: AgentEvent,
): AgentStateSnapshot? {
    val state = current ?: request?.let { AgentStateSnapshot(messages = it.messages) }
    return when (event) {
        is AgentEvent.Started -> state?.copy(status = AgentStatus.RUNNING)
        is AgentEvent.TurnStarted -> state?.copy(turn = event.turn, status = AgentStatus.RUNNING)
        is AgentEvent.ContextTransformed -> state
        is AgentEvent.MessageEmitted -> state?.copy(
            messages = state.messages.replaceOrAppend(event.message),
            status = AgentStatus.RUNNING,
        )
        is AgentEvent.ToolRequested -> state?.copy(
            pendingToolCalls = state.pendingToolCalls
                .filterNot { call -> call.toolCallId == event.toolCall.toolCallId } + event.toolCall,
            status = AgentStatus.WAITING_FOR_TOOLS,
        )
        is AgentEvent.ToolCompleted -> state?.copy(
            pendingToolCalls = state.pendingToolCalls
                .filterNot { call -> call.toolCallId == event.result.toolCallId },
            status = if (
                state.pendingToolCalls.any { call -> call.toolCallId != event.result.toolCallId }
            ) {
                AgentStatus.WAITING_FOR_TOOLS
            } else {
                AgentStatus.RUNNING
            },
        )
        is AgentEvent.RetryScheduled -> state?.copy(
            retryCount = if (state.retryCount == Int.MAX_VALUE) Int.MAX_VALUE else state.retryCount + 1,
        )
        is AgentEvent.CheckpointSaved -> event.checkpoint.state
        is AgentEvent.Completed -> event.state
        is AgentEvent.Failed -> state?.copy(
            status = AgentStatus.FAILED,
            stopReason = saien.magrathea.core.StopReason.ERROR,
        )
        is AgentEvent.Cancelled -> state?.copy(
            status = AgentStatus.CANCELLED,
            stopReason = saien.magrathea.core.StopReason.CANCELLED,
        )
        is AgentEvent.Interrupted -> event.state
        is AgentEvent.RecoveryBlocked -> state
    }
}

private fun List<saien.magrathea.core.AgentMessage>.replaceOrAppend(
    message: saien.magrathea.core.AgentMessage,
): List<saien.magrathea.core.AgentMessage> {
    val index = indexOfFirst { item -> item.id == message.id }
    return if (index < 0) this + message else toMutableList().also { it[index] = message }
}

internal fun AgentEvent.isTerminal(): Boolean =
    this is AgentEvent.Completed ||
        this is AgentEvent.Failed ||
        this is AgentEvent.Cancelled ||
        this is AgentEvent.Interrupted ||
        this is AgentEvent.RecoveryBlocked

internal val RECOVERABLE_STORED_STATUSES = setOf(
    AgentStatus.RUNNING,
    AgentStatus.WAITING_FOR_TOOLS,
    AgentStatus.INTERRUPTED,
)
