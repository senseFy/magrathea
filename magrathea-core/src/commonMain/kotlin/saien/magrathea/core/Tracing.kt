@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package saien.magrathea.core

import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicLong
import kotlinx.coroutines.CancellationException
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

/** Limits apply before retaining caller data. Contention and exhausted budgets drop diagnostics. */
data class TraceRecordingLimits(
    val activeSpans: Int = 64,
    val startsPerSecond: Int = 128,
    val attributes: Int = 24,
    val events: Int = 8,
    val eventAttributes: Int = 4,
    val nameChars: Int = 96,
    val keyChars: Int = 64,
    val valueChars: Int = 128,
    val idChars: Int = 128,
    val totalTextChars: Int = 2048,
) {
    init {
        require(listOf(activeSpans, startsPerSecond, attributes, events, eventAttributes,
            nameChars, keyChars, valueChars, idChars, totalTextChars).all { it in 1..65536 })
        require(totalTextChars >= nameChars + 3 * idChars)
    }
}

class DefaultMagratheaTracer(
    private val sink: MagratheaTraceSink,
    private val epochClock: EpochClock = SystemEpochClock,
    private val monotonicClock: MonotonicClock = SystemMonotonicClock,
    private val idGenerator: IdGenerator = SystemIdGenerator,
    private val limits: TraceRecordingLimits = TraceRecordingLimits(),
) : MagratheaTracer {
    private data class Admission(val second: Long = -1, val starts: Int = 0)
    private val admission = AtomicReference(Admission())
    private val active = AtomicInt(0)
    private val dropped = AtomicLong(0)
    val droppedSpanCount: Long get() = dropped.load()

    override fun startSpan(
        name: String,
        parent: TraceContext?,
        attributes: Map<String, TraceValue>,
    ): MagratheaTraceSpan {
        val now = monotonicClock.nowMillis()
        val current = admission.load()
        val second = now / 1000
        val starts = if (second == current.second) current.starts else 0
        if (active.fetchAndAdd(1) >= limits.activeSpans) {
            release()
            dropped.fetchAndAdd(1)
            return NoopMagratheaTraceSpan
        }
        if (starts >= limits.startsPerSecond ||
            !admission.compareAndSet(current, Admission(second, starts + 1))) {
            release()
            dropped.fetchAndAdd(1)
            return NoopMagratheaTraceSpan
        }
        try {
            val boundedName = name.take(limits.nameChars)
            val traceId = parent?.traceId ?: idGenerator.nextId()
            val spanId = idGenerator.nextId()
            if (boundedName.isBlank() || traceId.length !in 1..limits.idChars ||
                spanId.length !in 1..limits.idChars ||
                (parent != null && parent.spanId.length > limits.idChars)) {
                release()
                dropped.fetchAndAdd(1)
                return NoopMagratheaTraceSpan
            }
            val context = TraceContext(traceId, spanId)
            val budget = TraceTextBudget(limits.totalTextChars - boundedName.length -
                traceId.length - spanId.length - (parent?.spanId?.length ?: 0), limits)
            return DefaultMagratheaTraceSpan(
                boundedName, context, parent?.spanId, budget.attributes(attributes, limits.attributes),
                budget.remaining, epochClock.nowEpochMs(), now, monotonicClock, sink, limits, ::release,
            )
        } catch (failure: Throwable) {
            release()
            throw failure
        }
    }

    private fun release() { active.fetchAndAdd(-1) }

}

private class TraceTextBudget(var remaining: Int, private val limits: TraceRecordingLimits) {
    fun attributes(source: Map<String, TraceValue>, count: Int): Map<String, TraceValue> {
        val result = linkedMapOf<String, TraceValue>()
        val iterator = source.entries.iterator()
        repeat(count) {
            if (!iterator.hasNext()) return result
            val (rawKey, rawValue) = iterator.next()
            val key = rawKey.take(limits.keyChars)
            if (key.isBlank() || key.length > remaining) return@repeat
            val value = when (rawValue) {
                is TraceValue.StringValue -> TraceValue.StringValue(
                    rawValue.value.take(minOf(limits.valueChars, remaining - key.length)),
                )
                is TraceValue.DoubleValue -> if (rawValue.value.isFinite()) rawValue else return@repeat
                else -> rawValue
            }
            if (key in result) return@repeat
            remaining -= key.length + (value as? TraceValue.StringValue)?.value.orEmpty().length
            result[key] = value
        }
        return result
    }
}

private class DefaultMagratheaTraceSpan(
    private val name: String,
    override val context: TraceContext,
    private val parentSpanId: String?,
    startAttributes: Map<String, TraceValue>,
    remainingText: Int,
    private val startedAtEpochMillis: Long,
    private val startedAtMonotonicMillis: Long,
    private val monotonicClock: MonotonicClock,
    private val sink: MagratheaTraceSink,
    private val limits: TraceRecordingLimits,
    private val release: () -> Unit,
) : MagratheaTraceSpan {
    // A completed handle retains no attribute/event graph.
    private val state = AtomicReference<SpanState?>(SpanState(startAttributes, emptyList(), remainingText))

    override fun addEvent(name: String, attributes: Map<String, TraceValue>) {
        val current = state.load() ?: return
        if (current.events.size >= limits.events) return
        val boundedName = name.take(minOf(limits.nameChars, current.remainingText))
        if (boundedName.isBlank()) return
        val budget = TraceTextBudget(current.remainingText - boundedName.length, limits)
        val event = TraceEvent(boundedName, elapsedMillis(), budget.attributes(attributes, limits.eventAttributes))
        state.compareAndSet(current, current.copy(events = current.events + event, remainingText = budget.remaining))
    }

    override fun end(status: TraceStatus, attributes: Map<String, TraceValue>) {
        val current = state.exchange(null) ?: return
        try {
            val baseText = name.length + context.traceId.length + context.spanId.length +
                (parentSpanId?.length ?: 0) + current.events.sumOf { event ->
                    event.name.length + event.attributes.textSize()
                }
            val budget = TraceTextBudget(limits.totalTextChars - baseText, limits)
            val merged = linkedMapOf<String, TraceValue>()
            // Correlation survives even when completion attributes consume the remaining budget.
            current.attributes["magrathea.agent.session_id"]?.let {
                merged.putAll(budget.attributes(mapOf("magrathea.agent.session_id" to it), 1))
            }
            merged.putAll(budget.attributes(attributes, limits.attributes - merged.size))
            val start = budget.attributes(current.attributes, limits.attributes - merged.size)
            start.forEach { (key, value) -> if (key !in merged) merged[key] = value }
            val completed = TraceSpanData(name, context, parentSpanId, startedAtEpochMillis,
                elapsedMillis(), status, merged, current.events)
            try {
                sink.export(completed)
            } catch (failure: Exception) {
                failure.rethrowTraceFatal()
                if (failure is CancellationException) throw failure
            }
        } finally {
            release()
        }
    }

    private fun elapsedMillis(): Long =
        (monotonicClock.nowMillis() - startedAtMonotonicMillis).coerceAtLeast(0L)

    private data class SpanState(
        val attributes: Map<String, TraceValue>,
        val events: List<TraceEvent>,
        val remainingText: Int,
    )
}

private fun Map<String, TraceValue>.textSize(): Int = entries.sumOf { (key, value) ->
    key.length + (value as? TraceValue.StringValue)?.value.orEmpty().length
}

private fun Throwable.rethrowTraceFatal() {
    var slow: Throwable? = this
    var fast: Throwable? = this
    while (slow != null) {
        if (slow is Error) throw slow
        slow = slow.cause
        fast = fast?.cause?.cause
        if (slow === fast && slow != null) {
            // Check the complete cycle once, including nodes skipped by the fast pointer.
            val start = slow
            do {
                if (slow is Error) throw slow
                slow = slow?.cause
            } while (slow !== start)
            return
        }
    }
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
