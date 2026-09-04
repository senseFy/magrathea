package saien.magrathea.core

import org.junit.Assert.assertEquals
import org.junit.Test

class StorageSchemaV6DescriptorContractTest {
    @Test
    fun frozenV6DescriptorFingerprintRemainsAvailableToTheMigrationBaseline() {
        val fingerprint = requireNotNull(
            javaClass.getResource("/v6/core/storage-schema-descriptor.sha256"),
        ).readText().trim()

        assertEquals(6, STORAGE_SCHEMA_V6_VERSION)
        assertEquals(
            "aa8f096e3b8ff49a379b8e4424d1d1cde53af00ace673465e23139332b695ab4",
            fingerprint,
        )
    }
}
