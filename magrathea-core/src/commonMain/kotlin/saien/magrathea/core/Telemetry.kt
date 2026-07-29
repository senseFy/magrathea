package saien.magrathea.core

import kotlin.time.TimeSource

fun interface MagratheaTelemetry {
    fun record(event: TelemetryEvent)
}

object NoopMagratheaTelemetry : MagratheaTelemetry {
    override fun record(event: TelemetryEvent) = Unit
}

fun interface MonotonicClock {
    fun nowMillis(): Long
}

object SystemMonotonicClock : MonotonicClock {
    private val origin = TimeSource.Monotonic.markNow()

    override fun nowMillis(): Long = origin.elapsedNow().inWholeMilliseconds
}

enum class TelemetryOutcome {
    SUCCESS,
    FAILURE,
    CANCELLED,
}

enum class TelemetryStoreOperation {
    LOAD_STATE,
    COMMIT_STATE,
}

sealed interface TelemetryEvent {
    data class SessionStarted(
        val sessionId: AgentSessionId,
        val resumed: Boolean,
    ) : TelemetryEvent

    data class TurnStarted(
        val sessionId: AgentSessionId,
        val turn: Int,
    ) : TelemetryEvent

    data class ProviderRequestStarted(
        val sessionId: AgentSessionId,
        val turn: Int,
        val attempt: Int,
    ) : TelemetryEvent

    data class ProviderFirstChunk(
        val sessionId: AgentSessionId,
        val turn: Int,
        val attempt: Int,
        val latencyMillis: Long,
    ) : TelemetryEvent

    data class ProviderRequestFinished(
        val sessionId: AgentSessionId,
        val turn: Int,
        val attempt: Int,
        val durationMillis: Long,
        val outcome: TelemetryOutcome,
        val failureCode: AgentFailureCode?,
        val usage: TokenUsage,
    ) : TelemetryEvent

    data class RetryScheduled(
        val sessionId: AgentSessionId,
        val turn: Int,
        val attempt: Int,
        val failureCode: AgentFailureCode,
    ) : TelemetryEvent

    data class ToolExecutionFinished(
        val sessionId: AgentSessionId,
        val turn: Int,
        val durationMillis: Long,
        val outcome: TelemetryOutcome,
        val isError: Boolean,
    ) : TelemetryEvent

    data class StoreOperationFinished(
        val sessionId: AgentSessionId,
        val operation: TelemetryStoreOperation,
        val durationMillis: Long,
        val outcome: TelemetryOutcome,
    ) : TelemetryEvent

    data class SessionFinished(
        val sessionId: AgentSessionId,
        val turn: Int,
        val outcome: TelemetryOutcome,
        val failureCode: AgentFailureCode?,
        val usage: TokenUsage,
    ) : TelemetryEvent
}
