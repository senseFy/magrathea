package saien.magrathea.provider.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.fail
import org.junit.Test

class ProviderRegistryTest {
    @Test
    fun registryShouldReturnRegisteredProvider() {
        val provider = FakeEchoProvider()
        val registry = InMemoryProviderRegistry(listOf(provider))
        assertNotNull(registry.get("fake"))
        assertEquals(1, registry.all().size)
    }

    @Test
    fun duplicateProviderKeys_areRejectedInsteadOfSilentlyOverwritten() {
        try {
            InMemoryProviderRegistry(listOf(FakeEchoProvider(), FakeEchoProvider()))
            fail("Expected duplicate provider keys to be rejected")
        } catch (_: IllegalArgumentException) {
        }
    }
}
