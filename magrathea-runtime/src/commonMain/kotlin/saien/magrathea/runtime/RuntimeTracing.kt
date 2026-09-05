package saien.magrathea.runtime

import kotlinx.coroutines.CancellationException
import saien.magrathea.core.AgentFailureCode
import saien.magrathea.core.MagratheaTraceSpan
import saien.magrathea.core.MagratheaTracer
import saien.magrathea.core.NoopMagratheaTraceSpan
import saien.magrathea.core.TokenUsage
import saien.magrathea.core.TraceContext
import saien.magrathea.core.TraceStatus
import saien.magrathea.core.TraceValue
import saien.magrathea.core.currentMagratheaTraceContext
import saien.magrathea.provider.api.ProviderHttpException
import saien.magrathea.provider.api.ProviderInvocationIntent
import saien.magrathea.provider.api.ProviderInvocationInvalidatedException
import saien.magrathea.provider.api.ProviderNetworkException
import saien.magrathea.provider.api.ProviderProtocolException
import saien.magrathea.provider.api.ProviderTimeoutException

internal object RuntimeTraceNames {
    const val AGENT_EXECUTION = "magrathea.agent.execution"
    const val AGENT_CONTROL = "magrathea.agent.control"
    const val AGENT_TURN = "magrathea.agent.turn"
    const val CONTEXT_PREPARE = "magrathea.context.prepare"
    const val PROVIDER_REQUEST = "magrathea.provider.request"
    const val TOOL_CALL = "magrathea.tool.call"
    const val STORE_OPERATION = "magrathea.store.operation"
}

internal object RuntimeTraceEvents {
    const val PROVIDER_FIRST_EVENT = "magrathea.provider.first_event"
    const val PROVIDER_TERMINAL_EVENT = "magrathea.provider.terminal_event"
    const val PROVIDER_RETRY_SCHEDULED = "magrathea.provider.retry_scheduled"
    const val TOOL_RESULT_REUSED = "magrathea.tool.result_reused"
}

internal class RuntimeTracing(
    private val tracer: MagratheaTracer,
) {
    suspend fun startSpan(
        name: String,
        attributes: Map<String, TraceValue> = emptyMap(),
    ): RuntimeTraceSpan = startSpan(
        name = name,
        parent = currentMagratheaTraceContext(),
        attributes = attributes,
    )

    fun startSpan(
        name: String,
        parent: TraceContext?,
        attributes: Map<String, TraceValue> = emptyMap(),
    ): RuntimeTraceSpan {
        val span = try {
            tracer.startSpan(name, parent, attributes)
        } catch (cancelled: CancellationException) {
            cancelled.rethrowFatalError()
            throw cancelled
        } catch (failure: Exception) {
            failure.rethrowFatalError()
            NoopMagratheaTraceSpan
        }
        return RuntimeTraceSpan(span)
    }
}

internal class RuntimeTraceSpan(
    private val span: MagratheaTraceSpan,
) {
    val context: TraceContext?
        get() = try {
            span.context
        } catch (cancelled: CancellationException) {
            cancelled.rethrowFatalError()
            throw cancelled
        } catch (failure: Exception) {
            failure.rethrowFatalError()
            null
        }

    fun addEvent(name: String, attributes: Map<String, TraceValue> = emptyMap()) {
        try {
            span.addEvent(name, attributes)
        } catch (cancelled: CancellationException) {
            cancelled.rethrowFatalError()
            throw cancelled
        } catch (failure: Exception) {
            failure.rethrowFatalError()
            // Tracing cannot affect Runtime behavior.
        }
    }

    fun end(status: TraceStatus, attributes: Map<String, TraceValue>) {
        try {
            span.end(status, attributes)
        } catch (cancelled: CancellationException) {
            cancelled.rethrowFatalError()
            throw cancelled
        } catch (failure: Exception) {
            failure.rethrowFatalError()
            // Tracing cannot affect Runtime behavior.
        }
    }

    fun endSuccess(vararg attributes: Pair<String, Any?>) {
        end(
            TraceStatus.OK,
            traceAttributes("magrathea.outcome" to "success", *attributes),
        )
    }

    fun endFailure(
        failureCode: AgentFailureCode?,
        phase: String,
        vararg attributes: Pair<String, Any?>,
    ) {
        end(
            TraceStatus.ERROR,
            traceAttributes(
                "magrathea.outcome" to "failure",
                "magrathea.error.code" to failureCode?.name,
                "magrathea.error.phase" to phase,
                *attributes,
            ),
        )
    }

    fun endCancelled(vararg attributes: Pair<String, Any?>) {
        end(
            TraceStatus.UNSET,
            traceAttributes("magrathea.outcome" to "cancelled", *attributes),
        )
    }

    fun endInterrupted(
        failureCode: AgentFailureCode?,
        phase: String,
        vararg attributes: Pair<String, Any?>,
    ) {
        end(
            TraceStatus.UNSET,
            traceAttributes(
                "magrathea.outcome" to "interrupted",
                "magrathea.error.code" to failureCode?.name,
                "magrathea.error.phase" to phase,
                *attributes,
            ),
        )
    }
}

internal fun traceAttributes(vararg values: Pair<String, Any?>): Map<String, TraceValue> =
    buildMap {
        values.forEach { (key, value) ->
            val traceValue = when (value) {
                null -> null
                is String -> TraceValue.StringValue(value)
                is Boolean -> TraceValue.BooleanValue(value)
                is Int -> TraceValue.LongValue(value.toLong())
                is Long -> TraceValue.LongValue(value)
                is Double -> TraceValue.DoubleValue(value)
                else -> error("Unsupported Runtime trace value: ${value::class}")
            }
            if (traceValue != null) put(key, traceValue)
        }
    }

internal fun TokenUsage.traceAttributes(): Map<String, TraceValue> = traceAttributes(
    "magrathea.usage.input_tokens" to inputTokens,
    "magrathea.usage.output_tokens" to outputTokens,
    "magrathea.usage.reasoning_tokens" to reasoningTokens,
)

internal fun ProviderInvocationIntent.traceValue(): String = when (this) {
    ProviderInvocationIntent.CREATE -> "new_attempt"
    ProviderInvocationIntent.REATTACH -> "reattach"
}

internal fun Throwable.providerTracePhase(): String = when (this) {
    is ProviderProtocolException -> "provider.decode"
    is ProviderInvocationInvalidatedException -> "provider.resolve"
    is ProviderNetworkException,
    is ProviderHttpException,
    is ProviderTimeoutException,
    -> "provider.transport"
    else -> "provider"
}
