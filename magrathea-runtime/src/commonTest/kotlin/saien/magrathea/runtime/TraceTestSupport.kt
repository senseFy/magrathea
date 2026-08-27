@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package saien.magrathea.runtime

import kotlin.concurrent.atomics.AtomicReference
import saien.magrathea.core.DefaultMagratheaTracer
import saien.magrathea.core.EpochClock
import saien.magrathea.core.IdGenerator
import saien.magrathea.core.MagratheaTraceSink
import saien.magrathea.core.MonotonicClock
import saien.magrathea.core.SystemIdGenerator
import saien.magrathea.core.TraceSpanData
import saien.magrathea.core.TraceValue

internal class RecordingTraceSink : MagratheaTraceSink {
    private val recorded = AtomicReference<List<TraceSpanData>>(emptyList())

    val spans: List<TraceSpanData>
        get() = recorded.load()

    override fun export(span: TraceSpanData) {
        while (true) {
            val current = recorded.load()
            if (recorded.compareAndSet(current, current + span)) return
        }
    }

    fun tracer(
        epochClock: EpochClock = EpochClock { 0L },
        monotonicClock: MonotonicClock = MonotonicClock { 0L },
        idGenerator: IdGenerator = SystemIdGenerator,
    ): DefaultMagratheaTracer = DefaultMagratheaTracer(
        sink = this,
        epochClock = epochClock,
        monotonicClock = monotonicClock,
        idGenerator = idGenerator,
    )
}

internal fun TraceSpanData.stringAttribute(key: String): String? =
    (attributes[key] as? TraceValue.StringValue)?.value

internal fun TraceSpanData.longAttribute(key: String): Long? =
    (attributes[key] as? TraceValue.LongValue)?.value

internal fun TraceSpanData.booleanAttribute(key: String): Boolean? =
    (attributes[key] as? TraceValue.BooleanValue)?.value
