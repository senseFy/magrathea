package saien.magrathea.core

import org.junit.Assert.assertEquals
import org.junit.Test

class StorageSchemaV7DescriptorContractTest {
    @Test
    fun frozenV7DescriptorFingerprintRemainsAvailableToTheMigrationChain() {
        val fingerprint = requireNotNull(
            javaClass.getResource("/v7/core/storage-schema-descriptor.sha256"),
        ).readText().trim()

        assertEquals(7, STORAGE_SCHEMA_V7_VERSION)
        assertEquals(
            "31189c43e7acb99e32e79b52c29852554469372ec7cb71dd61cdc10e0c690730",
            fingerprint,
        )
    }
}
