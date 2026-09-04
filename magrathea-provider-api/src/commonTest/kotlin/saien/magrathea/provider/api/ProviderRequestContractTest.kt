package saien.magrathea.provider.api

import saien.magrathea.core.ModelDescriptor
import kotlin.test.Test
import kotlin.test.assertFailsWith

class ProviderRequestContractTest {
    @Test
    fun outputTokenBoundMustBePositiveWhenPresent() {
        assertFailsWith<IllegalArgumentException> {
            ProviderRequest(
                model = ModelDescriptor("test", "test-model"),
                messages = emptyList(),
                maxTokens = 0,
            )
        }
    }
}
