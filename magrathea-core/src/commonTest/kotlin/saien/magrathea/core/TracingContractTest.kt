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
import kotlin.test.assertTrue
import kotlin.test.assertSame
import kotlin.test.assertFailsWith
import kotlinx.coroutines.CancellationException

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
        assertTrue(spans.single().events.size in 1..8)
    }

    @Test
    fun activeAndRateBudgetsDropBeforeGeneratingIdsAndRecoverAfterEnd() {
        var ids = 0
        val clock = MutableMonotonicClock()
        val tracer = DefaultMagratheaTracer(MagratheaTraceSink {}, monotonicClock = clock,
            idGenerator = IdGenerator { "id-${ids++}" },
            limits = TraceRecordingLimits(activeSpans = 1, startsPerSecond = 2))
        val first = tracer.startSpan("first")
        assertNull(tracer.startSpan("over-active").context)
        assertEquals(2, ids)
        first.end()
        tracer.startSpan("second").end()
        assertNull(tracer.startSpan("over-rate").context)
        assertEquals(2, tracer.droppedSpanCount)
        clock.now = 1000
        assertTrue(tracer.startSpan("next-window").context != null)
    }

    @Test
    fun largePayloadsAndRepeatedEventsHaveABoundedRetainedRepresentation() {
        var completed: TraceSpanData? = null
        val tracer = DefaultMagratheaTracer(MagratheaTraceSink { completed = it })
        val attributes = (0 until 1000).associate { "key-$it" to TraceValue.StringValue("x".repeat(10000)) }
        val span = tracer.startSpan("n".repeat(10000), attributes = attributes)
        repeat(10000) { span.addEvent("event", attributes) }
        span.end(attributes = attributes)
        val record = requireNotNull(completed)
        assertEquals(96, record.name.length)
        assertTrue(record.attributes.size <= 24 && record.events.size <= 8)
        fun Map<String, TraceValue>.textSize() = entries.sumOf { (key, value) ->
            assertTrue(key.length <= 64)
            val text = (value as? TraceValue.StringValue)?.value.orEmpty()
            assertTrue(text.length <= 128)
            key.length + text.length
        }
        val text = record.name.length + record.context.traceId.length + record.context.spanId.length +
            (record.parentSpanId?.length ?: 0) + record.attributes.textSize() +
            record.events.sumOf { it.name.length + it.attributes.textSize() }
        assertTrue(text <= 2048)
        span.addEvent("after-end", attributes)
        span.end()
        assertSame(record, completed)
    }

    @Test
    fun cancellationAndWrappedFatalSinkFailuresEscapeAndReleaseTheActivePermit() {
        val cancellation = CancellationException("cancel")
        var failure: Throwable = cancellation
        val tracer = DefaultMagratheaTracer(MagratheaTraceSink { throw failure },
            limits = TraceRecordingLimits(activeSpans = 1))
        assertSame(cancellation, assertFailsWith<CancellationException> { tracer.startSpan("cancel").end() })
        val fatal = object : Error("fatal") {}
        failure = IllegalStateException("wrapped", fatal)
        assertSame(fatal, assertFailsWith<Error> { tracer.startSpan("fatal").end() })
        assertTrue(tracer.startSpan("permit-released").context != null)
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
