package saien.magrathea.tooling.consumer

import android.content.Context
import saien.magrathea.chatbot.ChatbotClient
import saien.magrathea.chatbot.DefaultChatbotRequestFactory
import saien.magrathea.chatbot.createChatbotClient
import saien.magrathea.credentials.AndroidKeystoreCredentialStore
import saien.magrathea.provider.api.InMemoryProviderRegistry
import saien.magrathea.provider.gemini.GeminiProviderAdapter
import saien.magrathea.runtime.DefaultAgentRunner
import saien.magrathea.runtime.InMemoryToolRegistry
import saien.magrathea.storage.room.AndroidMagratheaRoom
import saien.magrathea.storage.room.StoredRecordCorruptionReporter

fun openPublishedAndroidChatbot(context: Context): ChatbotClient {
    val appContext = context.applicationContext
    val credentials = AndroidKeystoreCredentialStore(appContext, "published-consumer")
    val stores = AndroidMagratheaRoom.open(
        context = appContext,
        databaseName = "published-consumer.db",
        reporter = StoredRecordCorruptionReporter { },
    )
    val provider = GeminiProviderAdapter()
    val runner = DefaultAgentRunner(
        providerRegistry = InMemoryProviderRegistry(listOf(provider)),
        toolRegistry = InMemoryToolRegistry(),
        sessionStore = stores.sessionStore,
        checkpointStore = stores.checkpointStore,
        credentialProvider = credentials,
    )
    return createChatbotClient(
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
}
