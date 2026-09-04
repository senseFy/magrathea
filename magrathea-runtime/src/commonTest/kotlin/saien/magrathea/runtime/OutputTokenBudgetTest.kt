package saien.magrathea.runtime

import saien.magrathea.core.ModelDescriptor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class OutputTokenBudgetTest {
    @Test
    fun explicitRequestBoundTakesPrecedenceOverModelCapability() {
        assertEquals(
            4_096,
            resolveMaxOutputTokens(
                model = model(contextWindow = 128_000, maxOutput = 16_384),
                explicitMaxTokens = 4_096,
                estimatedInputTokens = 1_000,
            ),
        )
    }

    @Test
    fun explicitRequestBoundMayReplaceAStaleLowerCatalogCapability() {
        assertEquals(
            32_768,
            resolveMaxOutputTokens(
                model = model(contextWindow = 128_000, maxOutput = 16_384),
                explicitMaxTokens = 32_768,
                estimatedInputTokens = 1_000,
            ),
        )
    }

    @Test
    fun modelCapabilityProvidesDefaultWhenRequestHasNoOverride() {
        assertEquals(
            16_384,
            resolveMaxOutputTokens(
                model = model(contextWindow = 128_000, maxOutput = 16_384),
                explicitMaxTokens = null,
                estimatedInputTokens = 1_000,
            ),
        )
    }

    @Test
    fun knownBudgetIsClampedToRemainingContext() {
        assertEquals(
            2_000,
            resolveMaxOutputTokens(
                model = model(contextWindow = 10_000, maxOutput = 8_000),
                explicitMaxTokens = null,
                estimatedInputTokens = 8_000,
            ),
        )
    }

    @Test
    fun exhaustedEstimatedContextKeepsTheSmallestRepresentableProviderBound() {
        assertEquals(
            1,
            resolveMaxOutputTokens(
                model = model(contextWindow = 10_000, maxOutput = 8_000),
                explicitMaxTokens = null,
                estimatedInputTokens = 10_001,
            ),
        )
    }

    @Test
    fun unknownCapabilityRemainsUnboundedEvenWhenContextWindowIsKnown() {
        assertNull(
            resolveMaxOutputTokens(
                model = model(contextWindow = 10_000, maxOutput = null),
                explicitMaxTokens = null,
                estimatedInputTokens = 1_000,
            ),
        )
    }

    @Test
    fun contextWindowOverrideParticipatesInTheClamp() {
        assertEquals(
            1_500,
            resolveMaxOutputTokens(
                model = model(contextWindow = null, maxOutput = 8_000),
                explicitMaxTokens = null,
                estimatedInputTokens = 6_500,
                contextWindowTokensOverride = 8_000,
            ),
        )
    }

    private fun model(contextWindow: Long?, maxOutput: Int?) = ModelDescriptor(
        provider = "test",
        model = "test-model",
        contextWindowTokens = contextWindow,
        maxOutputTokens = maxOutput,
    )
}
