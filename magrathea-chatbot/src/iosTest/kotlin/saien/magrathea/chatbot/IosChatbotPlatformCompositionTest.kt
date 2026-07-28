@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package saien.magrathea.chatbot

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import platform.Foundation.NSFileManager
import platform.Foundation.NSUUID
import saien.magrathea.core.CredentialRef
import saien.magrathea.core.ModelDescriptor
import saien.magrathea.credentials.IosKeychainCredentialStore
import saien.magrathea.provider.api.InMemoryProviderRegistry
import saien.magrathea.provider.gemini.GeminiProviderAdapter
import saien.magrathea.runtime.DefaultAgentRunner
import saien.magrathea.runtime.InMemoryToolRegistry
import saien.magrathea.storage.room.IosMagratheaRoom
import saien.magrathea.storage.room.StoredRecordCorruptionReporter

class IosChatbotPlatformCompositionTest {
    @Test
    fun publicChatbotComposesWithRoomKeychainAndGeminiAdapter() = runTest {
        assertFailsWith<IllegalArgumentException> {
            IosMagratheaRoom.applicationSupportDatabasePath("../escape", "chatbot.db")
        }

        val suffix = NSUUID().UUIDString
        val directoryName = "MagratheaChatbotTests-$suffix"
        val databasePath = IosMagratheaRoom.applicationSupportDatabasePath(directoryName, "chatbot.db")
        val directoryPath = databasePath.removeSuffix("/chatbot.db")
        val credentialRef = CredentialRef("gemini")
        val credentials = IosKeychainCredentialStore("chatbot-$suffix")
        val stores = IosMagratheaRoom.open(databasePath, StoredRecordCorruptionReporter { })
        val provider = GeminiProviderAdapter()
        val runner = DefaultAgentRunner(
            providerRegistry = InMemoryProviderRegistry(listOf(provider)),
            toolRegistry = InMemoryToolRegistry(),
            sessionStore = stores.sessionStore,
            checkpointStore = stores.checkpointStore,
            credentialProvider = credentials,
        )
        val client = createChatbotClient(
            runner = runner,
            requestFactory = DefaultChatbotRequestFactory(),
            sessionStore = stores.sessionStore,
            checkpointStore = stores.checkpointStore,
            closeResources = {
                try {
                    provider.close()
                } finally {
                    stores.close()
                }
            },
        )

        try {
            val session = client.createSession(
                ChatbotSessionConfiguration(
                    model = ModelDescriptor("gemini", "gemini-test", supportsStreaming = true),
                    credentialRef = credentialRef,
                ),
            )
            assertEquals(ChatbotStatus.IDLE, session.snapshot().status)
            assertEquals(emptyList(), client.history())
        } finally {
            client.close()
            client.close()
            NSFileManager.defaultManager.removeItemAtPath(directoryPath, error = null)
        }
    }
}
