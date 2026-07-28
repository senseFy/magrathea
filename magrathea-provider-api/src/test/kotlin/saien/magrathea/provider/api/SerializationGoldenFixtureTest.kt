package saien.magrathea.provider.api

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test
import saien.magrathea.core.StopReason
import saien.magrathea.core.ToolCallPart

class SerializationGoldenFixtureTest {
    private val json = Json {
        classDiscriminator = "type"
        encodeDefaults = false
        ignoreUnknownKeys = false
    }

    @Test
    fun providerEvents_matchV1GoldenFixture() {
        val serializer = ListSerializer(ProviderEvent.serializer())
        val expected = providerEventsFixture()
        val fixture = resource("/v1/provider/provider-events.json")

        assertGolden(fixture, json.encodeToString(serializer, expected))
        assertEquals(expected, json.decodeFromString(serializer, fixture))
    }

    @Test
    fun providerChunk_matchesV1GoldenFixture() {
        val expected = ProviderChunk(
            events = listOf(
                ProviderEvent.TextStart(),
                ProviderEvent.TextDelta("hello"),
                ProviderEvent.TextEnd(),
                ProviderEvent.Completed(
                    finishReason = "STOP",
                    stopReason = StopReason.COMPLETED,
                    usage = ProviderUsage(inputTokens = 2, outputTokens = 1),
                ),
            ),
        )
        val fixture = resource("/v1/provider/provider-chunk.json")

        assertGolden(fixture, json.encodeToString(ProviderChunk.serializer(), expected))
        assertEquals(expected, json.decodeFromString(ProviderChunk.serializer(), fixture))
    }

    private fun providerEventsFixture(): List<ProviderEvent> {
        val finalCall = ToolCallPart(
            toolCallId = "call-1",
            toolName = "search",
            arguments = buildJsonObject { put("query", JsonPrimitive("kmp")) },
            thoughtSignature = "thought-sig",
            providerCallId = "provider-call-1",
        )
        val usage = ProviderUsage(inputTokens = 5, outputTokens = 3, reasoningTokens = 1)
        return listOf(
            ProviderEvent.TextStart("text-sig"),
            ProviderEvent.TextDelta("hello", "text-sig"),
            ProviderEvent.TextEnd("hello", "text-sig"),
            ProviderEvent.ReasoningStart("reason-sig", redacted = true),
            ProviderEvent.ReasoningDelta("inspect", "reason-sig"),
            ProviderEvent.ReasoningEnd("inspect", "reason-sig", redacted = true),
            ProviderEvent.ToolCallStart(finalCall.copy(partial = true)),
            ProviderEvent.ToolCallDelta("call-1", "{\"query\":\"kmp\"}"),
            ProviderEvent.ToolCallEnd(finalCall),
            ProviderEvent.UsageDelta(usage),
            ProviderEvent.Completed("STOP", StopReason.TOOL_CALLS, usage),
        )
    }

    private fun resource(path: String): String {
        return requireNotNull(javaClass.getResource(path)) { "Missing serialization fixture $path" }.readText()
    }

    private fun assertGolden(expectedPayload: String, actualPayload: String) {
        val expected = json.parseToJsonElement(expectedPayload)
        val actual = json.parseToJsonElement(actualPayload)
        assertEquals("Serialization fixture changed. Actual payload: $actualPayload", expected, actual)
    }
}
