package saien.magrathea.credentials

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class AndroidCredentialBlobContractTest {
    @Test
    fun noBackupBlobFileNameIsFixedSafeAndDoesNotExposeCredentialIdentity() {
        val directory = Files.createTempDirectory("magrathea-credential-files-").toFile()
        val account = "6:gemini:7:default"
        val otherAccount = "6:gemini:4:work"
        val store = NoBackupCredentialBlobStore(directory)

        val file = store.file(account)
        val digest = credentialBlobFileName(account)

        assertEquals(directory, file.parentFile)
        assertEquals("credential-$digest.json", file.name)
        assertEquals(64, digest.length)
        assertTrue(digest.all { it in '0'..'9' || it in 'a'..'f' })
        assertFalse(file.name.contains("gemini"))
        assertFalse(file.name.contains("default"))
        assertNotEquals(digest, credentialBlobFileName(otherAccount))

        check(directory.deleteRecursively()) { "Failed to remove credential host-test directory" }
    }
}
