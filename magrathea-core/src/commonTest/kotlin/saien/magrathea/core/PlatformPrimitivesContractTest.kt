package saien.magrathea.core

import kotlin.test.Test
import kotlin.test.assertEquals

class PlatformPrimitivesContractTest {
    @Test
    fun agentModelFactory_usesInjectedIdGeneratorAndClock() {
        val ids = ArrayDeque(listOf("session-id", "message-id"))
        val factory = AgentModelFactory(
            idGenerator = IdGenerator { ids.removeFirst() },
            clock = EpochClock { 1_700_000_000_123 },
        )

        val sessionId = factory.createSessionId()
        val message = factory.createMessage(
            role = MessageRole.USER,
            parts = listOf(TextPart("hello")),
        )
        val request = AgentRequest(
            sessionId = sessionId,
            messages = listOf(message),
            model = ModelDescriptor(provider = "test", model = "test"),
        )
        val snapshot = factory.createSessionSnapshot(
            sessionId = sessionId,
            request = request,
            state = AgentStateSnapshot(messages = listOf(message)),
        )

        assertEquals("session-id", sessionId.value)
        assertEquals("message-id", message.id)
        assertEquals(1_700_000_000_123, message.createdAtEpochMs)
        assertEquals(1_700_000_000_123, snapshot.updatedAtEpochMs)
    }

    @Test
    fun agentSessionIdCreate_acceptsInjectedGeneratorWithoutChangingNoArgApi() {
        assertEquals("fixed-session", AgentSessionId.create(IdGenerator { "fixed-session" }).value)
    }
}
