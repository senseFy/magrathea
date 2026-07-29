package saien.magrathea.samples.android

import android.content.Context
import android.util.Log
import saien.magrathea.chatbot.ChatbotClient
import saien.magrathea.chatbot.ChatbotObservation
import saien.magrathea.chatbot.ChatbotSession
import saien.magrathea.chatbot.ChatbotSessionConfiguration
import saien.magrathea.chatbot.ChatbotSnapshot
import saien.magrathea.chatbot.ChatbotStateObserver
import saien.magrathea.chatbot.DefaultChatbotRequestFactory
import saien.magrathea.chatbot.createChatbotClient
import saien.magrathea.core.CredentialRef
import saien.magrathea.core.ModelDescriptor
import saien.magrathea.core.ProviderCredential
import saien.magrathea.credentials.AndroidKeystoreCredentialStore
import saien.magrathea.provider.api.InMemoryProviderRegistry
import saien.magrathea.provider.gemini.GeminiProviderAdapter
import saien.magrathea.runtime.DefaultAgentRunner
import saien.magrathea.runtime.InMemoryToolRegistry
import saien.magrathea.storage.room.AndroidMagratheaRoom
import saien.magrathea.storage.room.StoredRecordCorruptionReporter

class AndroidChatSample(
    context: Context,
    private val render: (ChatbotSnapshot) -> Unit,
) {
    private val appContext = context.applicationContext
    private val credentialRef = CredentialRef("gemini")
    private val defaultConfiguration = ChatbotSessionConfiguration(
        model = ModelDescriptor("gemini", "gemini-2.5-flash", supportsStreaming = true),
        credentialRef = credentialRef,
    )
    private val credentials = AndroidKeystoreCredentialStore(appContext, "android-sample")
    private val stores = AndroidMagratheaRoom.open(
        context = appContext,
        databaseName = "magrathea-sample.db",
        reporter = StoredRecordCorruptionReporter {
            Log.w("MagratheaStorage", "Corrupt persisted chatbot record")
        },
    )
    private val provider = GeminiProviderAdapter()

    private val runner = DefaultAgentRunner(
        providerRegistry = InMemoryProviderRegistry(listOf(provider)),
        toolRegistry = InMemoryToolRegistry(),
        persistence = stores.persistence,
        credentialProvider = credentials,
    )
    private val client: ChatbotClient = createChatbotClient(
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
    private var session: ChatbotSession? = null
    private var observation: ChatbotObservation? = null

    suspend fun setApiKey(apiKey: String) {
        credentials.put(credentialRef, ProviderCredential(apiKey))
    }

    suspend fun start() {
        attach(client.createSession(defaultConfiguration))
    }

    suspend fun send(text: String) {
        requireNotNull(session) { "Call start() before send()" }.send(text)
    }

    suspend fun cancel() {
        session?.cancel()
    }

    suspend fun resume(sessionId: String) {
        attach(client.resumeSession(sessionId))
    }

    suspend fun historySessionIds(): List<String> = client.history().map { it.sessionId }

    suspend fun close() {
        observation?.cancel()
        observation = null
        session = null
        client.close()
    }

    private fun attach(next: ChatbotSession) {
        observation?.cancel()
        session = next
        observation = next.observe(ChatbotStateObserver(render))
        render(next.snapshot())
    }
}
