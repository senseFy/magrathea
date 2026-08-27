@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package saien.magrathea.runtime

import kotlin.concurrent.atomics.AtomicReference
import saien.magrathea.core.MagratheaDebugRecord
import saien.magrathea.core.MagratheaDebugRecorder
import saien.magrathea.core.MagratheaDebugValue

internal class RecordingDebugRecorder : MagratheaDebugRecorder {
    private val recorded = AtomicReference<List<MagratheaDebugRecord>>(emptyList())

    override val enabled: Boolean = true

    val records: List<MagratheaDebugRecord>
        get() = recorded.load()

    override fun record(record: MagratheaDebugRecord) {
        while (true) {
            val current = recorded.load()
            if (recorded.compareAndSet(current, current + record)) return
        }
    }
}

internal fun MagratheaDebugRecord.stringAttribute(key: String): String? =
    (attributes[key] as? MagratheaDebugValue.StringValue)?.value

internal fun MagratheaDebugRecord.longAttribute(key: String): Long? =
    (attributes[key] as? MagratheaDebugValue.LongValue)?.value

internal fun MagratheaDebugRecord.booleanAttribute(key: String): Boolean? =
    (attributes[key] as? MagratheaDebugValue.BooleanValue)?.value
