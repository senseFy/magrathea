package saien.magrathea.credentials

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import saien.magrathea.core.CredentialRef
import saien.magrathea.core.ProviderCredential

class CredentialStoreContractTest {
    @Test
    fun ephemeralStore_putResolveOverwriteRemoveAndProfileIsolation() = runTest {
        val store = EphemeralCredentialStore()
        val defaultRef = CredentialRef("gemini")
        val workRef = CredentialRef("gemini", "work")
        assertFalse(store.contains(defaultRef))
        store.put(defaultRef, credential("default-secret"))
        store.put(workRef, credential("work-secret"))

        assertTrue(store.contains(defaultRef))
        assertTrue(store.contains(workRef))
        assertCredential("default-secret", store.resolve(defaultRef))
        assertCredential("work-secret", store.resolve(workRef))

        store.put(defaultRef, credential("replacement-secret"))
        assertCredential("replacement-secret", store.resolve(defaultRef))
        assertCredential("work-secret", store.resolve(workRef))

        store.remove(defaultRef)
        assertFalse(store.contains(defaultRef))
        assertFailsWith<CredentialNotFoundException> { store.resolve(defaultRef) }
        store.remove(defaultRef)
    }

    @Test
    fun credentialNamespace_rejectsPathComponentsAndNonAsciiInput() {
        listOf("", ".hidden", "../escape", "contains/slash", "秘密").forEach { namespace ->
            assertFailsWith<IllegalArgumentException> { namespace.requireSafeCredentialNamespace() }
        }
        assertEquals("safe.namespace-1", "safe.namespace-1".requireSafeCredentialNamespace())
    }

    @Test
    fun ephemeralStore_concurrentProfilesRemainIsolated() = runTest {
        val store = EphemeralCredentialStore()
        val refs = (0 until 64).map { CredentialRef("provider", "profile-$it") }

        refs.mapIndexed { index, ref -> async { store.put(ref, credential("secret-$index")) } }.awaitAll()

        val values = refs.map { ref -> async { store.resolve(ref).value } }.awaitAll()
        assertEquals((0 until 64).map { "secret-$it" }, values)
    }

    @Test
    fun persistedPayloadIsStrictAndExceptionsNeverRenderSecret() {
        val secret = "credential-canary-secret"
        val encoded = CredentialPayloadCodec.encode(credential(secret))
        assertCredential(secret, CredentialPayloadCodec.decode(encoded))

        val unknown = encoded.replaceFirst("{", "{\"future\":true,")
        val error = assertFailsWith<Throwable> { CredentialPayloadCodec.decode(unknown) }
        assertFalse(error.toString().contains(secret))

        val stableError = CredentialStoreException(CredentialStoreFailure.CORRUPT)
        assertFalse(stableError.toString().contains(secret))
        assertEquals(null, stableError.cause)
    }

    @Test
    fun persistedPayloadRejectsMissingCurrentSchemaFields() {
        val complete = CredentialPayloadCodec.encode(credential("strict-secret"))

        listOf(
            complete.replace(",\"endpoint\":\"https://provider.invalid\"", ""),
            complete.replace(Regex(",\"headers\":\\{.*}$"), "}"),
        ).forEach { incomplete ->
            val error = assertFailsWith<CredentialStoreException> {
                CredentialPayloadCodec.decode(incomplete)
            }
            assertEquals(CredentialStoreFailure.CORRUPT, error.failure)
        }
    }

    @Test
    fun encryptedEnvelopeContainsOnlyCipherMaterial() {
        val secret = "must-never-enter-encrypted-envelope"
        val encoded = CredentialPayloadCodec.encodeEncrypted("aXY=", "Y2lwaGVydGV4dA==")
        val envelope = CredentialPayloadCodec.decodeEncrypted(encoded)

        assertEquals("aXY=", envelope.ivBase64)
        assertFalse(encoded.contains(secret))
        assertFalse(encoded.contains("value"))
        assertFalse(encoded.contains("endpoint"))
        assertFalse(encoded.contains("headers"))
    }

    @Test
    fun encryptedEnvelopeRejectsNonCanonicalOrIncompletePayload() {
        listOf(
            "{\"formatVersion\":1,\"ivBase64\":\"aXY=\"}",
            "{\"formatVersion\":1,\"ivBase64\":\"aXY=\",\"ciphertextBase64\":\"Y2lwaGVydGV4dA==\",\"future\":true}",
        ).forEach { invalid ->
            val error = assertFailsWith<CredentialStoreException> {
                CredentialPayloadCodec.decodeEncrypted(invalid)
            }
            assertEquals(CredentialStoreFailure.CORRUPT, error.failure)
        }
    }

    private fun credential(secret: String): ProviderCredential = ProviderCredential(
        value = secret,
        endpoint = "https://provider.invalid",
        headers = mapOf("X-Test" to "header-$secret"),
    )

    private fun assertCredential(expectedSecret: String, actual: ProviderCredential) {
        assertEquals(expectedSecret, actual.value)
        assertEquals("https://provider.invalid", actual.endpoint)
        assertEquals(mapOf("X-Test" to "header-$expectedSecret"), actual.headers)
    }
}
