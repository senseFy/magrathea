package saien.magrathea.core

import org.junit.Assert.assertEquals
import org.junit.Test

class StorageSchemaV8DescriptorContractTest {
    @Test
    fun frozenV8DescriptorFingerprintRemainsAvailableToTheMigrationChain() {
        val fingerprint = requireNotNull(
            javaClass.getResource("/v8/core/storage-schema-descriptor.sha256"),
        ).readText().trim()

        assertEquals(8, STORAGE_SCHEMA_V8_VERSION)
        assertEquals(
            "b8e3b396bdecd2d6f0d6a3aba504e3ddddaf958ff65ed44f192015f81612d6ee",
            fingerprint,
        )
    }
}
