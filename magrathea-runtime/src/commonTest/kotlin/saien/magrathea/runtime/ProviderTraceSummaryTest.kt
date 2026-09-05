package saien.magrathea.runtime

import kotlin.test.*
import saien.magrathea.core.*
import saien.magrathea.provider.api.*

class ProviderTraceSummaryTest {
    @Test
    fun aLargeStreamProducesOnlyFixedCountersAndOneFirstTextEvent() {
        val sink = RecordingTraceSink()
        var now = 0L
        val span = RuntimeTraceSpan(sink.tracer(monotonicClock = MonotonicClock { now }).startSpan("attempt"))
        val request = ProviderRequest(model = ModelDescriptor("provider", "model"), messages = emptyList())
        val summary = ProviderTraceSummary(span, request)
        now = 5
        span.addEvent(RuntimeTraceEvents.PROVIDER_FIRST_EVENT)
        summary.observe(ProviderChunk(events = listOf(ProviderEvent.ReasoningStart(), ProviderEvent.ReasoningDelta("private"))))
        now = 20
        val text = ProviderChunk(events = listOf(ProviderEvent.TextDelta("private-text")))
        repeat(100000) { summary.observe(text) }
        summary.observe(ProviderChunk(events = listOf(ProviderEvent.Completed())))
        summary.finish()
        span.end(TraceStatus.OK, emptyMap())
        val recorded = sink.spans.single()
        assertTrue(recorded.events.size <= 8)
        assertFalse(recorded.toString().contains("private"))
        assertEquals(5, recorded.events.single { it.name == RuntimeTraceEvents.PROVIDER_FIRST_EVENT }.offsetMillis)
        assertEquals(20, recorded.events.single { it.name == "magrathea.provider.first_text" }.offsetMillis)
        val counts = recorded.events.single { it.name == "magrathea.provider.content_counts" }.attributes
        assertEquals(TraceValue.LongValue(1200000), counts["text_chars"])
        assertEquals(TraceValue.LongValue(7), counts["reasoning_chars"])
        val stream = recorded.events.single { it.name == "magrathea.provider.stream_summary" }.attributes
        assertEquals(TraceValue.LongValue(100002), stream["chunks"])
        assertEquals(TraceValue.BooleanValue(true), stream["terminal_observed"])
    }

    @Test
    fun finalPartTextReplacesItsDeltasAndAlsoSupportsNonStreamingText() {
        val sink = RecordingTraceSink()
        val span = RuntimeTraceSpan(sink.tracer().startSpan("attempt"))
        val summary = ProviderTraceSummary(span, ProviderRequest(model = ModelDescriptor("provider", "model"), messages = emptyList()))
        summary.observe(ProviderChunk(events = listOf(ProviderEvent.TextStart(), ProviderEvent.TextDelta("old"), ProviderEvent.TextEnd("final"))))
        summary.observe(ProviderChunk(events = listOf(ProviderEvent.TextStart(), ProviderEvent.TextEnd("next"))))
        summary.finish()
        span.end(TraceStatus.OK, emptyMap())
        val counts = sink.spans.single().events.single { it.name == "magrathea.provider.content_counts" }.attributes
        assertEquals(TraceValue.LongValue(9), counts["text_chars"])
        assertEquals(1, sink.spans.single().events.count { it.name == "magrathea.provider.first_text" })
    }
}
