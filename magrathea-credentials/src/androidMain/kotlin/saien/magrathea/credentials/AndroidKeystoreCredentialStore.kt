package saien.magrathea.credentials

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import android.util.Base64
import java.io.File
import java.io.FileNotFoundException
import java.security.MessageDigest
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import saien.magrathea.core.CredentialRef
import saien.magrathea.core.ProviderCredential

class AndroidKeystoreCredentialStore(
    context: Context,
    namespace: String,
) : CredentialStore {
    private val namespace = namespace.requireSafeCredentialNamespace()
    private val encryptedBlobs = NoBackupCredentialBlobStore(
        File(
            context.applicationContext.noBackupFilesDir,
            "saien.magrathea.credentials.$namespace",
        ),
    )
    private val keyAlias = "saien.magrathea.credentials.$namespace.aes-gcm"
    private val mutex = Mutex()

    override suspend fun put(ref: CredentialRef, credential: ProviderCredential) = mutex.withLock {
        withContext(Dispatchers.IO) {
            val account = ref.storageKey()
            val plaintext = CredentialPayloadCodec.encode(credential).encodeToByteArray()
            try {
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(Cipher.ENCRYPT_MODE, loadOrCreateSecretKey())
                cipher.updateAAD(account.encodeToByteArray())
                val ciphertext = cipher.doFinal(plaintext)
                val persisted = CredentialPayloadCodec.encodeEncrypted(
                    ivBase64 = Base64.encodeToString(cipher.iv, Base64.NO_WRAP),
                    ciphertextBase64 = Base64.encodeToString(ciphertext, Base64.NO_WRAP),
                )
                encryptedBlobs.write(account, persisted)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (known: CredentialStoreException) {
                throw known
            } catch (_: Throwable) {
                throw CredentialStoreException(CredentialStoreFailure.UNAVAILABLE)
            } finally {
                plaintext.fill(0)
            }
        }
    }

    override suspend fun read(ref: CredentialRef): ProviderCredential = mutex.withLock {
        withContext(Dispatchers.IO) {
            val account = ref.storageKey()
            val persisted = try {
                encryptedBlobs.read(account) ?: throw CredentialNotFoundException()
            } catch (missing: CredentialNotFoundException) {
                throw missing
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                throw CredentialStoreException(CredentialStoreFailure.UNAVAILABLE)
            }
            try {
                val encrypted = CredentialPayloadCodec.decodeEncrypted(persisted)
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(
                    Cipher.DECRYPT_MODE,
                    loadExistingSecretKey(),
                    GCMParameterSpec(GCM_TAG_LENGTH_BITS, Base64.decode(encrypted.ivBase64, Base64.NO_WRAP)),
                )
                cipher.updateAAD(account.encodeToByteArray())
                val plaintext = cipher.doFinal(Base64.decode(encrypted.ciphertextBase64, Base64.NO_WRAP))
                try {
                    CredentialPayloadCodec.decode(plaintext.decodeToString())
                } finally {
                    plaintext.fill(0)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (missing: CredentialNotFoundException) {
                throw missing
            } catch (_: Throwable) {
                throw CredentialStoreException(CredentialStoreFailure.CORRUPT)
            }
        }
    }

    override suspend fun contains(ref: CredentialRef): Boolean = mutex.withLock {
        withContext(Dispatchers.IO) {
            try {
                encryptedBlobs.contains(ref.storageKey())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                throw CredentialStoreException(CredentialStoreFailure.UNAVAILABLE)
            }
        }
    }

    override suspend fun remove(ref: CredentialRef) = mutex.withLock {
        withContext(Dispatchers.IO) {
            try {
                encryptedBlobs.remove(ref.storageKey())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                throw CredentialStoreException(CredentialStoreFailure.UNAVAILABLE)
            }
        }
    }

    private fun loadExistingSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        return keyStore.getKey(keyAlias, null) as? SecretKey
            ?: throw CredentialStoreException(CredentialStoreFailure.CORRUPT)
    }

    private fun loadOrCreateSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(keyAlias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).run {
            init(
                KeyGenParameterSpec.Builder(
                    keyAlias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
            generateKey()
        }
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_LENGTH_BITS = 128
    }
}

internal class NoBackupCredentialBlobStore(
    private val directory: File,
) {
    fun write(account: String, payload: String) {
        ensureDirectory()
        val atomicFile = AtomicFile(file(account))
        val output = atomicFile.startWrite()
        try {
            output.write(payload.encodeToByteArray())
            atomicFile.finishWrite(output)
        } catch (failure: Throwable) {
            atomicFile.failWrite(output)
            throw failure
        }
    }

    fun read(account: String): String? {
        val atomicFile = AtomicFile(file(account))
        return try {
            atomicFile.readFully().decodeToString()
        } catch (_: FileNotFoundException) {
            null
        }
    }

    fun contains(account: String): Boolean = try {
        AtomicFile(file(account)).openRead().use { }
        true
    } catch (_: FileNotFoundException) {
        false
    }

    fun remove(account: String) {
        AtomicFile(file(account)).delete()
    }

    internal fun file(account: String): File = File(
        directory,
        "credential-${credentialBlobFileName(account)}.json",
    )

    private fun ensureDirectory() {
        if ((!directory.exists() && !directory.mkdirs()) || !directory.isDirectory) {
            throw IllegalStateException("Credential no-backup directory is unavailable")
        }
    }
}

internal fun credentialBlobFileName(account: String): String = MessageDigest
    .getInstance("SHA-256")
    .digest(account.encodeToByteArray())
    .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
