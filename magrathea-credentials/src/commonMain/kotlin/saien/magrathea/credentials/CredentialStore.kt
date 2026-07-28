package saien.magrathea.credentials

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlin.coroutines.cancellation.CancellationException
import saien.magrathea.core.CredentialProvider
import saien.magrathea.core.CredentialRef
import saien.magrathea.core.ProviderCredential

/** Writable credential boundary whose reads also satisfy Core's [CredentialProvider] contract. */
interface CredentialStore : CredentialProvider {
    @Throws(CredentialStoreException::class, CancellationException::class)
    suspend fun put(ref: CredentialRef, credential: ProviderCredential)

    @Throws(CredentialStoreException::class, CancellationException::class)
    suspend fun remove(ref: CredentialRef)

    @Throws(CredentialStoreException::class, CancellationException::class)
    suspend fun contains(ref: CredentialRef): Boolean

    @Throws(CredentialStoreException::class, CancellationException::class)
    suspend fun read(ref: CredentialRef): ProviderCredential

    override suspend fun resolve(ref: CredentialRef): ProviderCredential = read(ref)
}

enum class CredentialStoreFailure {
    NOT_FOUND,
    CORRUPT,
    UNAVAILABLE,
}

open class CredentialStoreException(
    val failure: CredentialStoreFailure,
) : IllegalStateException("Credential store operation failed (${failure.name.lowercase()})") {
}

class CredentialNotFoundException : CredentialStoreException(CredentialStoreFailure.NOT_FOUND)

/** Volatile storage for tests, previews, and explicitly ephemeral application sessions. */
class EphemeralCredentialStore : CredentialStore {
    private val mutex = Mutex()
    private val credentials = LinkedHashMap<String, ProviderCredential>()

    override suspend fun put(ref: CredentialRef, credential: ProviderCredential) {
        mutex.withLock {
            credentials[ref.storageKey()] = credential.copyForStorage()
        }
    }

    override suspend fun read(ref: CredentialRef): ProviderCredential = mutex.withLock {
        credentials[ref.storageKey()]?.copyForStorage() ?: throw CredentialNotFoundException()
    }

    override suspend fun contains(ref: CredentialRef): Boolean = mutex.withLock {
        credentials.containsKey(ref.storageKey())
    }

    override suspend fun remove(ref: CredentialRef) {
        mutex.withLock { credentials.remove(ref.storageKey()) }
    }
}

@Serializable
internal data class StoredCredentialEnvelope(
    val formatVersion: Int,
    val value: String,
    val endpoint: String? = null,
    val headers: Map<String, String> = emptyMap(),
)

@Serializable
internal data class EncryptedCredentialEnvelope(
    val formatVersion: Int,
    val ivBase64: String,
    val ciphertextBase64: String,
)

@OptIn(ExperimentalSerializationApi::class)
internal object CredentialPayloadCodec {
    private const val FORMAT_VERSION = 1
    private val json = Json {
        ignoreUnknownKeys = false
        encodeDefaults = true
        explicitNulls = true
    }

    fun encode(credential: ProviderCredential): String = json.encodeToString(
        StoredCredentialEnvelope.serializer(),
        StoredCredentialEnvelope(
            formatVersion = FORMAT_VERSION,
            value = credential.value,
            endpoint = credential.endpoint,
            headers = credential.headers.toMap(),
        ),
    )

    fun decode(payload: String): ProviderCredential {
        try {
            val parsed = json.parseToJsonElement(payload)
            val envelope = json.decodeFromJsonElement(StoredCredentialEnvelope.serializer(), parsed)
            if (envelope.formatVersion != FORMAT_VERSION) {
                throw CredentialStoreException(CredentialStoreFailure.CORRUPT)
            }
            requireCanonical(parsed, StoredCredentialEnvelope.serializer(), envelope)
            return ProviderCredential(
                value = envelope.value,
                endpoint = envelope.endpoint,
                headers = envelope.headers.toMap(),
            )
        } catch (known: CredentialStoreException) {
            throw known
        } catch (_: Throwable) {
            throw CredentialStoreException(CredentialStoreFailure.CORRUPT)
        }
    }

    fun encodeEncrypted(ivBase64: String, ciphertextBase64: String): String = json.encodeToString(
        EncryptedCredentialEnvelope.serializer(),
        EncryptedCredentialEnvelope(FORMAT_VERSION, ivBase64, ciphertextBase64),
    )

    fun decodeEncrypted(payload: String): EncryptedCredentialEnvelope {
        try {
            val parsed = json.parseToJsonElement(payload)
            val envelope = json.decodeFromJsonElement(EncryptedCredentialEnvelope.serializer(), parsed)
            if (
                envelope.formatVersion != FORMAT_VERSION ||
                envelope.ivBase64.isBlank() ||
                envelope.ciphertextBase64.isBlank()
            ) {
                throw CredentialStoreException(CredentialStoreFailure.CORRUPT)
            }
            requireCanonical(parsed, EncryptedCredentialEnvelope.serializer(), envelope)
            return envelope
        } catch (known: CredentialStoreException) {
            throw known
        } catch (_: Throwable) {
            throw CredentialStoreException(CredentialStoreFailure.CORRUPT)
        }
    }

    private fun <T> requireCanonical(
        parsed: JsonElement,
        serializer: kotlinx.serialization.KSerializer<T>,
        value: T,
    ) {
        if (parsed != json.encodeToJsonElement(serializer, value)) {
            throw CredentialStoreException(CredentialStoreFailure.CORRUPT)
        }
    }
}

internal fun CredentialRef.storageKey(): String =
    "${provider.length}:$provider:${profile.length}:$profile"

internal fun String.requireSafeCredentialNamespace(): String = apply {
    require(length in 1..64 && first().isAsciiLetterOrDigit() && all { it.isAsciiStorageCharacter() }) {
        "namespace must contain 1-64 ASCII letters, digits, dots, underscores, or dashes and start with a letter or digit"
    }
}

private fun Char.isAsciiLetterOrDigit(): Boolean =
    this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9'

private fun Char.isAsciiStorageCharacter(): Boolean =
    isAsciiLetterOrDigit() || this == '.' || this == '_' || this == '-'

internal fun ProviderCredential.copyForStorage(): ProviderCredential = ProviderCredential(
    value = value,
    endpoint = endpoint,
    headers = headers.toMap(),
)
