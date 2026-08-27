package saien.magrathea.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TracingContractTest {
    @Test
    fun defaultTracerExportsParentedImmutableSpan() {
        val spans = mutableListOf<TraceSpanData>()
        val monotonicClock = MutableMonotonicClock()
        val tracer = DefaultMagratheaTracer(
            sink = MagratheaTraceSink(spans::add),
            epochClock = EpochClock { 1_000L },
            monotonicClock = monotonicClock,
            idGenerator = SequenceIdGenerator("span"),
        )
        val parent = TraceContext("parent-trace", "parent-span")
        val startAttributes = mutableMapOf<String, TraceValue>(
            "start" to TraceValue.StringValue("safe"),
        )

        val span = tracer.startSpan("operation", parent, startAttributes)
        startAttributes.clear()
        monotonicClock.now = 5L
        span.addEvent("milestone", mapOf("count" to TraceValue.LongValue(1)))
        monotonicClock.now = 9L
        span.end(
            TraceStatus.OK,
            mapOf("start" to TraceValue.StringValue("final")),
        )
        span.end(TraceStatus.ERROR)

        val completed = spans.single()
        assertEquals("parent-trace", completed.context.traceId)
        assertEquals("span", completed.context.spanId)
        assertEquals("parent-span", completed.parentSpanId)
        assertEquals(1_000L, completed.startedAtEpochMillis)
        assertEquals(9L, completed.durationMillis)
        assertEquals(TraceStatus.OK, completed.status)
        assertEquals(TraceValue.StringValue("final"), completed.attributes["start"])
        assertEquals(5L, completed.events.single().offsetMillis)
    }

    @Test
    fun traceContextPropagatesAndCanBeCleared() = runTest {
        val context = TraceContext("trace", "span")

        assertNull(currentMagratheaTraceContext())
        withMagratheaTraceContext(context) {
            assertEquals(context, currentMagratheaTraceContext())
            coroutineScope {
                assertEquals(
                    listOf(context, context),
                    listOf(
                        async { currentMagratheaTraceContext() },
                        async { currentMagratheaTraceContext() },
                    ).awaitAll(),
                )
            }
            withMagratheaTraceContext(null) {
                assertNull(currentMagratheaTraceContext())
            }
            assertEquals(context, currentMagratheaTraceContext())
        }
        assertNull(currentMagratheaTraceContext())
    }

    @Test
    fun throwingSinkAndNoopSpanAreHarmless() {
        val tracer = DefaultMagratheaTracer(
            sink = MagratheaTraceSink { error("sink failure") },
            epochClock = EpochClock { 0L },
            monotonicClock = MonotonicClock { 0L },
            idGenerator = SequenceIdGenerator("trace", "span"),
        )

        tracer.startSpan("operation").end(TraceStatus.OK)
        assertNull(NoopMagratheaTracer.startSpan("operation").context)
    }

    @Test
    fun spanEventsAndEndAreThreadSafe() = runTest {
        val spans = mutableListOf<TraceSpanData>()
        val tracer = DefaultMagratheaTracer(
            sink = MagratheaTraceSink(spans::add),
            epochClock = EpochClock { 0L },
            monotonicClock = MonotonicClock { 0L },
            idGenerator = SequenceIdGenerator("trace", "span"),
        )
        val span = tracer.startSpan("parallel")

        withContext(Dispatchers.Default) {
            coroutineScope {
                (0 until 100).map { index ->
                    async {
                        span.addEvent(
                            "event",
                            mapOf("index" to TraceValue.LongValue(index.toLong())),
                        )
                    }
                }.awaitAll()
                (0 until 20).map {
                    async { span.end(TraceStatus.OK) }
                }.awaitAll()
            }
        }

        assertEquals(1, spans.size)
        assertEquals(100, spans.single().events.size)
    }

    private class MutableMonotonicClock : MonotonicClock {
        var now: Long = 0L

        override fun nowMillis(): Long = now
    }

    private class SequenceIdGenerator(vararg values: String) : IdGenerator {
        private val values = values.iterator()

        override fun nextId(): String = values.next()
    }
}
