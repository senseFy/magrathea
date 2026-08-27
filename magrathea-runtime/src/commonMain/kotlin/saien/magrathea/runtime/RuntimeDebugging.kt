package saien.magrathea.runtime

import saien.magrathea.core.AgentRunId
import saien.magrathea.core.AgentSessionId
import saien.magrathea.core.MagratheaDebugLevel
import saien.magrathea.core.MagratheaDebugRecord
import saien.magrathea.core.MagratheaDebugRecorder
import saien.magrathea.core.MagratheaDebugValue
import saien.magrathea.core.TraceContext
import saien.magrathea.core.currentMagratheaTraceContext

internal data class RuntimeDebugCorrelation(
    val runId: AgentRunId? = null,
    val turn: Int? = null,
    val providerRequestId: String? = null,
    val providerAttempt: Int? = null,
    val providerPurpose: String? = null,
    val traceContext: TraceContext? = null,
)

internal class RuntimeDebugging(
    private val recorder: MagratheaDebugRecorder,
) {
    suspend fun record(
        sessionId: AgentSessionId,
        event: String,
        level: MagratheaDebugLevel = MagratheaDebugLevel.DEBUG,
        correlation: RuntimeDebugCorrelation = RuntimeDebugCorrelation(),
        attributes: () -> Map<String, MagratheaDebugValue> = { emptyMap() },
    ) {
        val enabled = try {
            recorder.enabled
        } catch (_: Throwable) {
            false
        }
        if (!enabled) return
        val record = try {
            MagratheaDebugRecord(
                level = level,
                component = "magrathea.runtime",
                event = event,
                sessionId = sessionId.value,
                traceContext = correlation.traceContext ?: currentMagratheaTraceContext(),
                attributes = correlation.debugAttributes() + attributes(),
            )
        } catch (_: Throwable) {
            return
        }
        try {
            recorder.record(record)
        } catch (_: Throwable) {
            // Debug recording cannot affect Runtime behavior.
        }
    }
}

private fun RuntimeDebugCorrelation.debugAttributes(): Map<String, MagratheaDebugValue> = debugAttributes(
    "run_id" to runId?.value,
    "turn" to turn,
    "provider_request_id" to providerRequestId,
    "provider_attempt" to providerAttempt,
    "provider_purpose" to providerPurpose,
)

internal fun debugAttributes(vararg values: Pair<String, Any?>): Map<String, MagratheaDebugValue> =
    buildMap {
        values.forEach { (key, value) ->
            val debugValue = when (value) {
                null -> null
                is String -> MagratheaDebugValue.StringValue(value)
                is Boolean -> MagratheaDebugValue.BooleanValue(value)
                is Int -> MagratheaDebugValue.LongValue(value.toLong())
                is Long -> MagratheaDebugValue.LongValue(value)
                is Double -> MagratheaDebugValue.DoubleValue(value)
                else -> error("Unsupported Runtime debug value: ${value::class}")
            }
            if (debugValue != null) put(key, debugValue)
        }
    }
