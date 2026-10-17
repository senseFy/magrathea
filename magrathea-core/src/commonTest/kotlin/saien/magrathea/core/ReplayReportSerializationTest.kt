package saien.magrathea.core

import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class ReplayReportSerializationTest {
    @Test
    fun contextReportRoundTripsAndLegacyEventsRemainUnchanged() {
        val json = Json { encodeDefaults = true }
        val legacy = AgentEvent.ContextTransformed(AgentSessionId("session"), 0, 1)
        val legacyJson = json.encodeToString<AgentEvent>(legacy)
        assertFalse(legacyJson.contains("replay"))
        assertEquals(legacy, json.decodeFromString<AgentEvent>(legacyJson))

        val reported = legacy.copy(replay = ReplayReport(
            runId = AgentRunId("run"),
            turn = 0,
            provider = "openai",
            model = "model",
            exclusions = listOf(ReplayExclusion("partial", ReplayExclusionReason.EMPTY_AFTER_TRANSFORMATION)),
        ))
        assertEquals(reported, json.decodeFromString<AgentEvent>(json.encodeToString<AgentEvent>(reported)))
    }
}
