@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package saien.magrathea.core

import kotlin.concurrent.atomics.AtomicReference
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext

data class TraceContext(
    val traceId: String,
    val spanId: String,
) {
    init {
        require(traceId.isNotBlank()) { "Trace ID must not be blank" }
        require(spanId.isNotBlank()) { "Span ID must not be blank" }
    }
}

enum class TraceStatus {
    UNSET,
    OK,
    ERROR,
}

sealed interface TraceValue {
    data class StringValue(val value: String) : TraceValue

    data class LongValue(val value: Long) : TraceValue

    data class DoubleValue(val value: Double) : TraceValue

    data class BooleanValue(val value: Boolean) : TraceValue
}

data class TraceEvent(
    val name: String,
    val offsetMillis: Long,
    val attributes: Map<String, TraceValue> = emptyMap(),
) {
    init {
        require(name.isNotBlank()) { "Trace event name must not be blank" }
        require(offsetMillis >= 0) { "Trace event offset must not be negative" }
        requireTraceAttributes(attributes)
    }
}

data class TraceSpanData(
    val name: String,
    val context: TraceContext,
    val parentSpanId: String?,
    val startedAtEpochMillis: Long,
    val durationMillis: Long,
    val status: TraceStatus,
    val attributes: Map<String, TraceValue> = emptyMap(),
    val events: List<TraceEvent> = emptyList(),
) {
    init {
        require(name.isNotBlank()) { "Trace span name must not be blank" }
        require(parentSpanId == null || parentSpanId.isNotBlank()) {
            "Parent span ID must be null or non-blank"
        }
        require(durationMillis >= 0) { "Trace span duration must not be negative" }
        requireTraceAttributes(attributes)
    }
}

/**
 * Host-owned destination for completed spans.
 *
 * [export] may be called concurrently and must return promptly without file, network, or other
 * blocking I/O. Buffering, storage, retry, and lifecycle belong to the host.
 */
fun interface MagratheaTraceSink {
    fun export(span: TraceSpanData)
}

interface MagratheaTracer {
    fun startSpan(
        name: String,
        parent: TraceContext? = null,
        attributes: Map<String, TraceValue> = emptyMap(),
    ): MagratheaTraceSpan
}

interface MagratheaTraceSpan {
    /** Null only for a no-op span. */
    val context: TraceContext?

    fun addEvent(
        name: String,
        attributes: Map<String, TraceValue> = emptyMap(),
    )

    fun end(
        status: TraceStatus = TraceStatus.UNSET,
        attributes: Map<String, TraceValue> = emptyMap(),
    )
}

object NoopMagratheaTracer : MagratheaTracer {
    override fun startSpan(
        name: String,
        parent: TraceContext?,
        attributes: Map<String, TraceValue>,
    ): MagratheaTraceSpan = NoopMagratheaTraceSpan
}

object NoopMagratheaTraceSpan : MagratheaTraceSpan {
    override val context: TraceContext? = null

    override fun addEvent(name: String, attributes: Map<String, TraceValue>) = Unit

    override fun end(status: TraceStatus, attributes: Map<String, TraceValue>) = Unit
}

class DefaultMagratheaTracer(
    private val sink: MagratheaTraceSink,
    private val epochClock: EpochClock = SystemEpochClock,
    private val monotonicClock: MonotonicClock = SystemMonotonicClock,
    private val idGenerator: IdGenerator = SystemIdGenerator,
) : MagratheaTracer {
    override fun startSpan(
        name: String,
        parent: TraceContext?,
        attributes: Map<String, TraceValue>,
    ): MagratheaTraceSpan {
        require(name.isNotBlank()) { "Trace span name must not be blank" }
        requireTraceAttributes(attributes)
        val context = TraceContext(
            traceId = parent?.traceId ?: idGenerator.nextId(),
            spanId = idGenerator.nextId(),
        )
        return DefaultMagratheaTraceSpan(
            name = name,
            context = context,
            parentSpanId = parent?.spanId,
            startAttributes = attributes.toMap(),
            startedAtEpochMillis = epochClock.nowEpochMs(),
            startedAtMonotonicMillis = monotonicClock.nowMillis(),
            monotonicClock = monotonicClock,
            sink = sink,
        )
    }
}

private class DefaultMagratheaTraceSpan(
    private val name: String,
    override val context: TraceContext,
    private val parentSpanId: String?,
    private val startAttributes: Map<String, TraceValue>,
    private val startedAtEpochMillis: Long,
    private val startedAtMonotonicMillis: Long,
    private val monotonicClock: MonotonicClock,
    private val sink: MagratheaTraceSink,
) : MagratheaTraceSpan {
    private val state = AtomicReference(SpanState())

    override fun addEvent(name: String, attributes: Map<String, TraceValue>) {
        if (state.load().ended) return
        require(name.isNotBlank()) { "Trace event name must not be blank" }
        requireTraceAttributes(attributes)
        val event = TraceEvent(
            name = name,
            offsetMillis = elapsedMillis(),
            attributes = attributes.toMap(),
        )
        while (true) {
            val current = state.load()
            if (current.ended) return
            val updated = current.copy(events = current.events + event)
            if (state.compareAndSet(current, updated)) return
        }
    }

    override fun end(status: TraceStatus, attributes: Map<String, TraceValue>) {
        if (state.load().ended) return
        requireTraceAttributes(attributes)
        while (true) {
            val current = state.load()
            if (current.ended) return
            if (!state.compareAndSet(current, current.copy(ended = true))) continue
            val completed = TraceSpanData(
                name = name,
                context = context,
                parentSpanId = parentSpanId,
                startedAtEpochMillis = startedAtEpochMillis,
                durationMillis = elapsedMillis(),
                status = status,
                attributes = startAttributes + attributes,
                events = current.events,
            )
            try {
                sink.export(completed)
            } catch (_: Throwable) {
                // Observability must not affect the instrumented operation.
            }
            return
        }
    }

    private fun elapsedMillis(): Long =
        (monotonicClock.nowMillis() - startedAtMonotonicMillis).coerceAtLeast(0L)

    private data class SpanState(
        val ended: Boolean = false,
        val events: List<TraceEvent> = emptyList(),
    )
}

private class MagratheaTraceContextElement(
    val traceContext: TraceContext?,
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<MagratheaTraceContextElement>
}

suspend fun currentMagratheaTraceContext(): TraceContext? =
    currentCoroutineContext()[MagratheaTraceContextElement]?.traceContext

suspend fun <T> withMagratheaTraceContext(
    context: TraceContext?,
    block: suspend () -> T,
): T = withContext(MagratheaTraceContextElement(context)) {
    block()
}

object MagratheaSdk {
    val version: String
        get() = MAGRATHEA_CORE_SDK_VERSION
}

private fun requireTraceAttributes(attributes: Map<String, TraceValue>) {
    require(attributes.keys.none(String::isBlank)) { "Trace attribute keys must not be blank" }
}
