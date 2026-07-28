package saien.magrathea.core

import kotlin.test.Test
import kotlin.test.assertEquals

class TokenUsageContractTest {
    @Test
    fun tokenUsage_accumulatesKnownDimensionsWithoutInventingUnknownValues() {
        assertEquals(
            TokenUsage(inputTokens = 13, outputTokens = 5, reasoningTokens = 2),
            TokenUsage(inputTokens = 10, reasoningTokens = 2) + TokenUsage(inputTokens = 3, outputTokens = 5),
        )
        assertEquals(TokenUsage(), TokenUsage() + TokenUsage())
    }

}
