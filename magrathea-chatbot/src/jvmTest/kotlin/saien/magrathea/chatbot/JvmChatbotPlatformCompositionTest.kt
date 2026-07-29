package saien.magrathea.chatbot

import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import saien.magrathea.core.CredentialProvider
import saien.magrathea.core.CredentialRef
import saien.magrathea.core.ModelDescriptor
import saien.magrathea.core.ProviderCredential
import saien.magrathea.provider.api.InMemoryProviderRegistry
import saien.magrathea.provider.gemini.GeminiProviderAdapter
import saien.magrathea.runtime.DefaultAgentRunner
import saien.magrathea.runtime.InMemoryToolRegistry
import saien.magrathea.storage.room.JvmMagratheaRoom
import saien.magrathea.storage.room.StoredRecordCorruptionReporter

class JvmChatbotPlatformCompositionTest {
    @Test
    fun publicChatbotComposesWithRoomAndGeminiAdapter() = runTest {
        assertFailsWith<IllegalArgumentException> {
            JvmMagratheaRoom.open(" ", StoredRecordCorruptionReporter { })
        }

        val directory = Files.createTempDirectory("magrathea-chatbot-")
        val stores = JvmMagratheaRoom.open(
            databasePath = directory.resolve("chatbot.db").toString(),
            reporter = StoredRecordCorruptionReporter { },
        )
        val credentialRef = CredentialRef("gemini")
        val credentials = CredentialProvider { ProviderCredential("jvm-test-key") }
        val provider = GeminiProviderAdapter()
        val runner = DefaultAgentRunner(
            providerRegistry = InMemoryProviderRegistry(listOf(provider)),
            toolRegistry = InMemoryToolRegistry(),
            persistence = stores.persistence,
            credentialProvider = credentials,
        )
        val client = createChatbotClient(
            runner = runner,
            requestFactory = DefaultChatbotRequestFactory(),
            persistence = stores.persistence,
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
            check(directory.toFile().deleteRecursively()) {
                "Failed to delete chatbot integration directory"
            }
        }
    }
}
