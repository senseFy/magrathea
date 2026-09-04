package saien.magrathea.chatbot

import kotlin.coroutines.ContinuationInterceptor
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import saien.magrathea.core.AgentPersistence
import saien.magrathea.core.AgentRunner
import saien.magrathea.core.AgentSessionId
import saien.magrathea.core.ModelDescriptor
import saien.magrathea.runtime.AgentSessionLease
import saien.magrathea.runtime.DefaultAgentSessionManager
import saien.magrathea.runtime.InMemoryAgentPersistence

internal fun testChatbotConfiguration(
    provider: String = "test",
    model: String = "test-model",
): ChatbotSessionConfiguration = ChatbotSessionConfiguration(
    ModelDescriptor(provider = provider, model = model),
)

internal class ManagedChatbotControllerFixture private constructor(
    val controller: ChatbotController,
    val manager: DefaultAgentSessionManager,
    val lease: AgentSessionLease,
    val persistence: AgentPersistence,
) {
    suspend fun close() = withContext(NonCancellable) {
        controller.close()
        manager.close()
    }

    companion object {
        suspend fun create(
            runner: AgentRunner,
            scope: CoroutineScope,
            configuration: ChatbotSessionConfiguration = testChatbotConfiguration(),
            requestFactory: ChatbotRequestFactory = DefaultChatbotRequestFactory(),
            persistence: AgentPersistence = InMemoryAgentPersistence(),
            sessionId: AgentSessionId = AgentSessionId.create(),
            restore: Boolean = false,
        ): ManagedChatbotControllerFixture {
            val dispatcher = scope.coroutineContext[ContinuationInterceptor] as CoroutineDispatcher
            val manager = DefaultAgentSessionManager(runner, persistence, dispatcher)
            val lease = if (restore) manager.acquire(sessionId) else manager.create(sessionId)
            return ManagedChatbotControllerFixture(
                controller = ChatbotController(
                    lease = lease,
                    requestFactory = requestFactory,
                    initialConfiguration = configuration,
                    scope = scope,
                ),
                manager = manager,
                lease = lease,
                persistence = persistence,
            )
        }
    }
}
