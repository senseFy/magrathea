package saien.magrathea.core

enum class MagratheaDebugLevel {
    DEBUG,
    INFO,
    WARN,
    ERROR,
}

sealed interface MagratheaDebugValue {
    data class StringValue(val value: String) : MagratheaDebugValue

    data class LongValue(val value: Long) : MagratheaDebugValue

    data class DoubleValue(val value: Double) : MagratheaDebugValue

    data class BooleanValue(val value: Boolean) : MagratheaDebugValue
}

data class MagratheaDebugRecord(
    val level: MagratheaDebugLevel,
    val component: String,
    val event: String,
    val sessionId: String? = null,
    val traceContext: TraceContext? = null,
    val attributes: Map<String, MagratheaDebugValue> = emptyMap(),
) {
    init {
        require(component.isNotBlank()) { "Debug component must not be blank" }
        require(event.isNotBlank()) { "Debug event must not be blank" }
        require(sessionId == null || sessionId.isNotBlank()) {
            "Debug session ID must be null or non-blank"
        }
        attributes.forEach { (key, value) ->
            require(key.isNotBlank()) { "Debug attribute key must not be blank" }
            if (value is MagratheaDebugValue.StringValue) {
                require(value.value.length <= MAX_DEBUG_STRING_LENGTH) {
                    "Debug string attribute exceeds the maximum length"
                }
            }
        }
    }
}

/**
 * Host-owned destination for bounded diagnostic records.
 *
 * Implementations may be called from Runtime's execution path. [enabled] and [record] must be
 * thread-safe and return promptly without file, network, or other blocking I/O. Queueing, storage,
 * retention, and export belong to the host.
 */
interface MagratheaDebugRecorder {
    val enabled: Boolean

    fun record(record: MagratheaDebugRecord)
}

object NoopMagratheaDebugRecorder : MagratheaDebugRecorder {
    override val enabled: Boolean = false

    override fun record(record: MagratheaDebugRecord) = Unit
}

private const val MAX_DEBUG_STRING_LENGTH = 1_024
