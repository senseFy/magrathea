@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package saien.magrathea.credentials

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import platform.CoreFoundation.CFDataCreate
import platform.CoreFoundation.CFDataGetBytePtr
import platform.CoreFoundation.CFDataGetLength
import platform.CoreFoundation.CFDataRef
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFDictionarySetValue
import platform.CoreFoundation.CFMutableDictionaryRef
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFStringCreateWithCString
import platform.CoreFoundation.CFTypeRef
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFStringEncodingUTF8
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.SecItemUpdate
import platform.Security.errSecDuplicateItem
import platform.Security.errSecItemNotFound
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrAccessible
import platform.Security.kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecReturnData
import platform.Security.kSecValueData
import platform.posix.memcpy
import saien.magrathea.core.CredentialRef
import saien.magrathea.core.ProviderCredential

class IosKeychainCredentialStore(
    namespace: String,
) : CredentialStore {
    private val service = "saien.magrathea.credentials.${namespace.requireSafeCredentialNamespace()}"
    private val mutex = Mutex()

    override suspend fun put(ref: CredentialRef, credential: ProviderCredential) = mutex.withLock {
        withContext(Dispatchers.Default) {
            val payload = CredentialPayloadCodec.encode(credential).encodeToByteArray()
            try {
                putBytes(ref.storageKey(), payload)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (known: CredentialStoreException) {
                throw known
            } catch (_: Throwable) {
                throw CredentialStoreException(CredentialStoreFailure.UNAVAILABLE)
            } finally {
                payload.fill(0)
            }
        }
    }

    override suspend fun read(ref: CredentialRef): ProviderCredential = mutex.withLock {
        withContext(Dispatchers.Default) {
            val payload = readBytes(ref.storageKey()) ?: throw CredentialNotFoundException()
            try {
                CredentialPayloadCodec.decode(payload.decodeToString())
            } catch (known: CredentialStoreException) {
                throw known
            } catch (_: Throwable) {
                throw CredentialStoreException(CredentialStoreFailure.CORRUPT)
            } finally {
                payload.fill(0)
            }
        }
    }

    override suspend fun contains(ref: CredentialRef): Boolean = mutex.withLock {
        withContext(Dispatchers.Default) {
            try {
                when (withBaseQuery(ref.storageKey()) { SecItemCopyMatching(it, null) }) {
                    errSecSuccess -> true
                    errSecItemNotFound -> false
                    else -> throw CredentialStoreException(CredentialStoreFailure.UNAVAILABLE)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (known: CredentialStoreException) {
                throw known
            } catch (_: Throwable) {
                throw CredentialStoreException(CredentialStoreFailure.UNAVAILABLE)
            }
        }
    }

    override suspend fun remove(ref: CredentialRef) = mutex.withLock {
        withContext(Dispatchers.Default) {
            val status = withBaseQuery(ref.storageKey()) { SecItemDelete(it) }
            if (status != errSecSuccess && status != errSecItemNotFound) {
                throw CredentialStoreException(CredentialStoreFailure.UNAVAILABLE)
            }
        }
    }

    private fun putBytes(account: String, payload: ByteArray) {
        val data = payload.toCFData()
        try {
            val updateStatus = withMutableDictionary { attributes ->
                CFDictionarySetValue(attributes, kSecValueData, data)
                CFDictionarySetValue(attributes, kSecAttrAccessible, kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly)
                withBaseQuery(account) { query -> SecItemUpdate(query, attributes) }
            }
            if (updateStatus == errSecSuccess) return
            if (updateStatus != errSecItemNotFound) {
                throw CredentialStoreException(CredentialStoreFailure.UNAVAILABLE)
            }

            val addStatus = withBaseQuery(account) { query ->
                CFDictionarySetValue(query, kSecValueData, data)
                CFDictionarySetValue(query, kSecAttrAccessible, kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly)
                SecItemAdd(query, null)
            }
            if (addStatus == errSecDuplicateItem) {
                val retryStatus = withMutableDictionary { attributes ->
                    CFDictionarySetValue(attributes, kSecValueData, data)
                    withBaseQuery(account) { query -> SecItemUpdate(query, attributes) }
                }
                if (retryStatus != errSecSuccess) {
                    throw CredentialStoreException(CredentialStoreFailure.UNAVAILABLE)
                }
                return
            }
            if (addStatus != errSecSuccess) {
                throw CredentialStoreException(CredentialStoreFailure.UNAVAILABLE)
            }
        } finally {
            CFRelease(data)
        }
    }

    private fun readBytes(account: String): ByteArray? = withBaseQuery(account) { query ->
        CFDictionarySetValue(query, kSecReturnData, kCFBooleanTrue)
        CFDictionarySetValue(query, kSecMatchLimit, kSecMatchLimitOne)
        memScoped {
            val result = alloc<CFTypeRefVar>()
            val status = SecItemCopyMatching(query, result.ptr)
            if (status == errSecItemNotFound) return@memScoped null
            if (status != errSecSuccess) {
                throw CredentialStoreException(CredentialStoreFailure.UNAVAILABLE)
            }
            val value = result.value ?: throw CredentialStoreException(CredentialStoreFailure.CORRUPT)
            try {
                value.reinterpretData().toByteArray()
            } finally {
                CFRelease(value)
            }
        }
    }

    private fun <T> withBaseQuery(account: String, block: (CFMutableDictionaryRef) -> T): T {
        val serviceValue = service.toCFString()
        val accountValue = account.toCFString()
        return withMutableDictionary { query ->
            try {
                CFDictionarySetValue(query, kSecClass, kSecClassGenericPassword)
                CFDictionarySetValue(query, kSecAttrService, serviceValue)
                CFDictionarySetValue(query, kSecAttrAccount, accountValue)
                block(query)
            } finally {
                CFRelease(accountValue)
                CFRelease(serviceValue)
            }
        }
    }

    private fun <T> withMutableDictionary(block: (CFMutableDictionaryRef) -> T): T {
        val dictionary = CFDictionaryCreateMutable(null, 0, null, null)
            ?: throw CredentialStoreException(CredentialStoreFailure.UNAVAILABLE)
        try {
            return block(dictionary)
        } finally {
            CFRelease(dictionary)
        }
    }

    private fun String.toCFString(): CFTypeRef =
        CFStringCreateWithCString(null, this, kCFStringEncodingUTF8)
            ?: throw CredentialStoreException(CredentialStoreFailure.UNAVAILABLE)

    private fun ByteArray.toCFData(): CFTypeRef = usePinned { pinned ->
        CFDataCreate(null, pinned.addressOf(0).reinterpret(), size.convert())
            ?: throw CredentialStoreException(CredentialStoreFailure.UNAVAILABLE)
    }

    private fun CFTypeRef.reinterpretData(): CFDataRef = reinterpret()

    private fun CFDataRef.toByteArray(): ByteArray {
        val size = CFDataGetLength(this).toInt()
        val output = ByteArray(size)
        output.usePinned { pinned ->
            memcpy(pinned.addressOf(0), CFDataGetBytePtr(this), size.convert())
        }
        return output
    }
}
