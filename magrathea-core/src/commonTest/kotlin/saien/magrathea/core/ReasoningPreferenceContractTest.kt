package saien.magrathea.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ReasoningPreferenceContractTest {
    @Test
    fun autoDoesNotRequireReasoningCapabilities() {
        val request = request(
            model = ModelDescriptor("test", "plain-model"),
            preference = ReasoningPreference.Auto,
        )

        assertEquals(ReasoningPreference.Auto, request.reasoningPreference)
        assertTrue(!request.model.supportsReasoning)
    }

    @Test
    fun explicitPreferencesRequireExactModelCapabilities() {
        val model = ModelDescriptor(
            provider = "test",
            model = "reasoning-model",
            reasoningCapabilities = ReasoningCapabilities(
                supportedEfforts = setOf(
                    ReasoningEffort.LOW,
                    ReasoningEffort.HIGH,
                ),
                supportsDisabled = true,
            ),
        )

        assertEquals(
            ReasoningPreference.Effort(ReasoningEffort.HIGH),
            request(model, ReasoningPreference.Effort(ReasoningEffort.HIGH)).reasoningPreference,
        )
        assertEquals(
            ReasoningPreference.Disabled,
            request(model, ReasoningPreference.Disabled).reasoningPreference,
        )
        assertFailsWith<IllegalArgumentException> {
            request(model, ReasoningPreference.Effort(ReasoningEffort.MAX))
        }
    }

    @Test
    fun reasoningWithoutPortableControlsSupportsOnlyAuto() {
        val model = ModelDescriptor(
            provider = "test",
            model = "provider-default-only",
            reasoningCapabilities = ReasoningCapabilities(),
        )

        assertTrue(model.supportsReasoning)
        assertFailsWith<IllegalArgumentException> {
            request(model, ReasoningPreference.Disabled)
        }
        assertFailsWith<IllegalArgumentException> {
            request(model, ReasoningPreference.Effort(ReasoningEffort.LOW))
        }
    }

    private fun request(
        model: ModelDescriptor,
        preference: ReasoningPreference,
    ): AgentRequest = AgentRequest(
        messages = listOf(AgentMessage(role = MessageRole.USER, parts = listOf(TextPart("hello")))),
        model = model,
        reasoningPreference = preference,
    )
}
