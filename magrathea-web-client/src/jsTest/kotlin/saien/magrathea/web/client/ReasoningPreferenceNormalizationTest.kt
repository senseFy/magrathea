package saien.magrathea.web.client

import kotlin.test.Test
import kotlin.test.assertEquals
import saien.magrathea.core.ModelDescriptor
import saien.magrathea.core.ReasoningCapabilities
import saien.magrathea.core.ReasoningEffort
import saien.magrathea.core.ReasoningPreference

class ReasoningPreferenceNormalizationTest {
    @Test
    fun explicitEffortIsPreservedWhenTargetSupportsIt() {
        val preference = ReasoningPreference.Effort(ReasoningEffort.HIGH)
        val model = ModelDescriptor(
            provider = "test",
            model = "compatible-target",
            reasoningCapabilities = ReasoningCapabilities(
                supportedEfforts = setOf(ReasoningEffort.HIGH),
            ),
        )

        assertEquals(
            preference,
            normalizeReasoningPreferenceForModel(preference, model),
        )
    }

    @Test
    fun explicitEffortResetsToAutoWhenTargetDoesNotSupportIt() {
        val preference = ReasoningPreference.Effort(ReasoningEffort.HIGH)
        val model = ModelDescriptor(
            provider = "test",
            model = "incompatible-target",
            reasoningCapabilities = ReasoningCapabilities(
                supportedEfforts = setOf(ReasoningEffort.LOW),
            ),
        )

        assertEquals(
            ReasoningPreference.Auto,
            normalizeReasoningPreferenceForModel(preference, model),
        )
    }
}
