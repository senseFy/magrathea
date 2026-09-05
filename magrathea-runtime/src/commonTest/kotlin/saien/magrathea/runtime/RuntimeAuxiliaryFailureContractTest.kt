package saien.magrathea.runtime

import kotlin.test.Test
import kotlin.test.assertSame
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import saien.magrathea.core.AgentSessionId
import saien.magrathea.core.MagratheaDebugRecord
import saien.magrathea.core.MagratheaDebugRecorder
import saien.magrathea.core.MagratheaTraceSpan
import saien.magrathea.core.MagratheaTracer
import saien.magrathea.core.TraceContext
import saien.magrathea.core.TraceStatus
import saien.magrathea.core.TraceValue

class RuntimeAuxiliaryFailureContractTest {
    @Test
    fun wrappedFatalFromTracerStartEscapesExactly() {
        val fatal = TestFatalError(Any())
        val tracing = RuntimeTracing(
            object : MagratheaTracer {
                override fun startSpan(
                    name: String,
                    parent: TraceContext?,
                    attributes: Map<String, TraceValue>,
                ): MagratheaTraceSpan = throw TestRecoverableException(fatal)
            },
        )

        val escaped = runCatching {
            tracing.startSpan("fatal-trace", parent = null)
        }.exceptionOrNull()

        assertSame(fatal, escaped)
    }

    @Test
    fun wrappedFatalFromTraceSpanEndEscapesExactly() {
        val fatal = TestFatalError(Any())
        val span = RuntimeTraceSpan(
            object : MagratheaTraceSpan {
                override val context: TraceContext? = null

                override fun addEvent(name: String, attributes: Map<String, TraceValue>) = Unit

                override fun end(status: TraceStatus, attributes: Map<String, TraceValue>) {
                    throw TestRecoverableException(fatal)
                }
            },
        )

        val escaped = runCatching { span.endSuccess() }.exceptionOrNull()

        assertSame(fatal, escaped)
    }

    @Test
    fun wrappedFatalFromDebugRecorderEscapesExactly() = runTest {
        val fatal = TestFatalError(Any())
        val debugging = RuntimeDebugging(
            object : MagratheaDebugRecorder {
                override val enabled: Boolean = true

                override fun record(record: MagratheaDebugRecord) {
                    throw TestRecoverableException(fatal)
                }
            },
        )

        val escaped = runCatching {
            debugging.record(
                sessionId = AgentSessionId("fatal-debug-session"),
                event = "fatal-debug-event",
            )
        }.exceptionOrNull()

        assertSame(fatal, escaped)
    }

    @Test
    fun tracingCancellationIsNeverTreatedAsFailOpenObservability() {
        val cancellation = CancellationException("tracing cancelled")
        val tracing = RuntimeTracing(
            object : MagratheaTracer {
                override fun startSpan(
                    name: String,
                    parent: TraceContext?,
                    attributes: Map<String, TraceValue>,
                ): MagratheaTraceSpan = throw cancellation
            },
        )

        val escaped = runCatching {
            tracing.startSpan("cancelled-trace", parent = null)
        }.exceptionOrNull()

        assertSame(cancellation, escaped)
    }

    @Test
    fun debugCancellationIsNeverTreatedAsFailOpenObservability() = runTest {
        val cancellation = CancellationException("debug cancelled")
        val debugging = RuntimeDebugging(
            object : MagratheaDebugRecorder {
                override val enabled: Boolean = true

                override fun record(record: MagratheaDebugRecord) {
                    throw cancellation
                }
            },
        )

        val escaped = runCatching {
            debugging.record(
                sessionId = AgentSessionId("cancelled-debug-session"),
                event = "cancelled-debug-event",
            )
        }.exceptionOrNull()

        assertSame(cancellation, escaped)
    }
}
