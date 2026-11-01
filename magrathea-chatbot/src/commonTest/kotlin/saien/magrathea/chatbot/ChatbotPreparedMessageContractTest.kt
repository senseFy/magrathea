@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package saien.magrathea.chatbot

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import saien.magrathea.core.AgentEvent
import saien.magrathea.core.AgentMessage
import saien.magrathea.core.AgentRequest
import saien.magrathea.core.AgentRunId
import saien.magrathea.core.AgentSessionId
import saien.magrathea.core.AgentSessionSnapshot
import saien.magrathea.core.AgentStateSnapshot
import saien.magrathea.core.AgentStatus
import saien.magrathea.core.AttachmentPart
import saien.magrathea.core.MessageRole
import saien.magrathea.core.StopReason
import saien.magrathea.core.TextPart
import saien.magrathea.core.text
import saien.magrathea.runtime.InMemoryAgentPersistence
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChatbotPreparedMessageContractTest {
    @Test
    fun awaitingIdleDoesNotBlockCancellationOfAnActiveRun() = runTest {
        val fixture = fixture(StandardTestDispatcher(testScheduler))
        val response = CompletableDeferred<Unit>()
        fixture.runner.beforeCommit = response
        val session = fixture.client.createSession(testChatbotConfiguration())
        try {
            session.send("waiting for a provider response")
            runCurrent()
            val joined = async { session.awaitIdle() }
            runCurrent()
            assertFalse(joined.isCompleted)

            val cancelled = async { session.cancel() }
            runCurrent()

            assertTrue(cancelled.isCompleted, "Cancellation must not wait behind awaitIdle")
            cancelled.await()
            joined.await()
            assertEquals(listOf(session.sessionId), fixture.runner.cancelledSessions)
            assertFalse(response.isCompleted)
            assertEquals(ChatbotStatus.CANCELLED, session.snapshot().status)
        } finally {
            response.complete(Unit)
            fixture.client.close()
        }
    }

    @Test
    fun awaitingIdleDoesNotPreventTheFacadeFromDetaching() = runTest {
        val fixture = fixture(StandardTestDispatcher(testScheduler))
        val response = CompletableDeferred<Unit>()
        fixture.runner.beforeCommit = response
        val session = fixture.client.createSession(testChatbotConfiguration())
        try {
            session.send("manager-owned work")
            runCurrent()
            val joined = async { session.awaitIdle() }
            runCurrent()
            val closed = async { session.close() }
            runCurrent()

            assertTrue(closed.isCompleted, "Detaching the facade must not await its active run")
            closed.await()
            val detached = session.snapshot()
            assertFalse(joined.isCompleted)
            response.complete(Unit)
            advanceUntilIdle()
            joined.await()
            assertEquals(detached, session.snapshot(), "An admitted wait must not update a closed facade")
            assertNotNull(fixture.store.load(session.sessionId))
        } finally {
            response.complete(Unit)
            fixture.client.close()
        }
    }

    @Test
    fun awaitIdleJoinsCollectorCleanupAfterTerminalProjectionWithoutChangingPersistence() = runTest {
        val fixture = fixture(StandardTestDispatcher(testScheduler))
        val cleanup = CompletableDeferred<Unit>()
        fixture.runner.afterCompleted = cleanup
        val session = fixture.client.createSession(testChatbotConfiguration())
        try {
            session.send("join terminal cleanup")
            runCurrent()
            assertEquals(ChatbotStatus.COMPLETED, session.snapshot().status)
            val original = fixture.store.load(session.sessionId)
            val joined = async { session.awaitIdle() }
            runCurrent()
            assertFalse(joined.isCompleted)
            cleanup.complete(Unit)
            advanceUntilIdle()
            joined.await()
            assertEquals(original, fixture.store.load(session.sessionId))
            assertEquals(1, fixture.runner.requests.size)
            session.close()
            assertEquals(ChatbotFailure.CLOSED, assertFailsWith<ChatbotException> { session.awaitIdle() }.failure)
        } finally {
            cleanup.complete(Unit)
            fixture.client.close()
        }
    }

    @Test
    fun preparationExposesStableIdentityWithoutChangingHistoryOrPersistence() = runTest {
        val fixture = fixture(StandardTestDispatcher(testScheduler))
        val session = fixture.client.createSession(testChatbotConfiguration())
        val before = session.snapshot()

        val prepared = session.prepareMessage("prepared input")

        assertEquals(session.sessionId, prepared.sessionId)
        assertTrue(prepared.messageId.isNotBlank())
        assertEquals(before, session.snapshot())
        assertNull(before.sessionId)
        assertTrue(fixture.store.listSessions().isEmpty())
        assertTrue(fixture.runner.requests.isEmpty())

        session.sendPrepared(prepared)
        advanceUntilIdle()

        val persisted = assertNotNull(fixture.store.load(session.sessionId)).snapshot
        assertEquals(prepared.messageId, persisted.state.messages.first().id)
        assertEquals(session.sessionId.value, session.snapshot().sessionId)
        assertEquals(prepared.sessionId, fixture.runner.requests.single().sessionId)
        fixture.client.close()
    }

    @Test
    fun preparedInputDetachesAttachmentsAndNestedMetadataFromCallerCollections() = runTest {
        val fixture = fixture(StandardTestDispatcher(testScheduler))
        val session = fixture.client.createSession(testChatbotConfiguration())
        val attachments = mutableListOf(ChatbotAttachment("asset:original", "image/png", "before.png"))
        val nested = mutableMapOf<String, JsonElement>("value" to JsonPrimitive("before"))
        val array = mutableListOf<JsonElement>(JsonObject(nested))
        val metadata = mutableMapOf<String, JsonElement>("entries" to JsonArray(array))
        val prepared = session.prepareMessage("frozen", attachments, ChatbotSendOptions(JsonObject(metadata)))

        attachments.clear()
        nested["value"] = JsonPrimitive("after")
        array.clear()
        metadata.clear()
        session.sendPrepared(prepared)
        advanceUntilIdle()

        val user = fixture.runner.requests.single().messages.single()
        assertEquals("before.png", user.parts.filterIsInstance<AttachmentPart>().single().fileName)
        assertEquals("before", user.metadata.getValue("entries").jsonArray.single()
            .jsonObject.getValue("value").jsonPrimitive.content)
        fixture.client.close()
    }

    @Test
    fun sendingPreparedInputUsesCurrentHistoryAndConfiguration() = runTest {
        val fixture = fixture(StandardTestDispatcher(testScheduler))
        val session = fixture.client.createSession(testChatbotConfiguration())
        val prepared = session.prepareMessage("prepared before the first send")

        session.send("first through the existing API")
        advanceUntilIdle()
        session.updateConfiguration(testChatbotConfiguration(model = "later-model"))
        session.sendPrepared(prepared)
        advanceUntilIdle()

        val request = fixture.runner.requests.last()
        assertEquals("later-model", request.model.model)
        assertEquals(
            listOf("first through the existing API", "prepared before the first send"),
            request.messages.filter { it.role == MessageRole.USER }.map { it.text() },
        )
        assertEquals(prepared.messageId, request.messages.last().id)
        fixture.client.close()
    }

    @Test
    fun foreignFacadeCannotConsumePreparedInputAndConcurrentReuseSendsOnce() = runTest {
        val fixture = fixture(StandardTestDispatcher(testScheduler))
        val owner = fixture.client.createSession(testChatbotConfiguration())
        val foreign = fixture.client.createSession(testChatbotConfiguration())
        val prepared = owner.prepareMessage("single attempt")

        assertEquals(ChatbotFailure.INVALID_ARGUMENT,
            assertFailsWith<ChatbotException> { foreign.sendPrepared(prepared) }.failure)
        assertTrue(fixture.runner.requests.isEmpty())

        val first = async { runCatching { owner.sendPrepared(prepared) } }
        val second = async { runCatching { owner.sendPrepared(prepared) } }
        val outcomes = listOf(first.await(), second.await())
        advanceUntilIdle()

        assertEquals(1, outcomes.count { it.isSuccess })
        assertEquals(ChatbotFailure.INVALID_ARGUMENT,
            (outcomes.single { it.isFailure }.exceptionOrNull() as ChatbotException).failure)
        assertEquals(1, fixture.runner.requests.size)
        fixture.client.close()
    }

    @Test
    fun preparedInputCannotMoveToAnotherFacadeOfTheSameCanonicalSession() = runTest {
        val fixture = fixture(StandardTestDispatcher(testScheduler))
        val original = fixture.client.createSession(testChatbotConfiguration())
        original.send("persist the session")
        advanceUntilIdle()
        val prepared = original.prepareMessage("belongs to the original facade")
        val sessionId = original.sessionId
        original.close()
        val restored = fixture.client.restoreSession(sessionId.value)

        assertEquals(sessionId, restored.sessionId)
        assertEquals(ChatbotFailure.CLOSED,
            assertFailsWith<ChatbotException> { original.sendPrepared(prepared) }.failure)
        assertEquals(ChatbotFailure.INVALID_ARGUMENT,
            assertFailsWith<ChatbotException> { restored.sendPrepared(prepared) }.failure)
        assertEquals(1, fixture.runner.requests.size)
        fixture.client.close()
    }

    @Test
    fun cancellationBeforeTheAttemptDoesNotConsumePreparedInput() = runTest {
        val fixture = fixture(StandardTestDispatcher(testScheduler))
        val session = fixture.client.createSession(testChatbotConfiguration())
        val prepared = session.prepareMessage("still usable")
        var rejected = false
        val cancelled = launch(start = CoroutineStart.UNDISPATCHED) {
            currentCoroutineContext().cancel()
            try {
                session.sendPrepared(prepared)
            } catch (_: CancellationException) {
                rejected = true
            }
        }
        cancelled.join()

        assertTrue(rejected)
        assertTrue(fixture.runner.requests.isEmpty())
        session.sendPrepared(prepared)
        advanceUntilIdle()
        assertEquals(prepared.messageId, fixture.runner.requests.single().messages.single().id)
        fixture.client.close()
    }

    @Test
    fun cancellationWhileThePreviousCollectorSettlesLeavesPreparedInputUnused() = runTest {
        val fixture = fixture(StandardTestDispatcher(testScheduler))
        val cleanup = CompletableDeferred<Unit>()
        fixture.runner.afterCompleted = cleanup
        val session = fixture.client.createSession(testChatbotConfiguration())
        try {
            session.send("previous execution")
            runCurrent()
            assertEquals(ChatbotStatus.COMPLETED, session.snapshot().status)
            val previous = fixture.store.load(session.sessionId)
            val prepared = session.prepareMessage("next execution")

            val waiting = async { session.sendPrepared(prepared) }
            runCurrent()
            assertFalse(waiting.isCompleted)
            waiting.cancel()
            waiting.join()

            assertFalse(cleanup.isCompleted)
            assertEquals(1, fixture.runner.requests.size)
            assertEquals(previous, fixture.store.load(session.sessionId))
            cleanup.complete(Unit)
            advanceUntilIdle()
            session.sendPrepared(prepared)
            advanceUntilIdle()

            assertEquals(2, fixture.runner.requests.size)
            assertEquals(prepared.messageId, fixture.runner.requests.last().messages.last().id)
        } finally {
            cleanup.complete(Unit)
            fixture.client.close()
        }
    }

    @Test
    fun unusablePreparedInputsDoNotStopAnActiveExecution() = runTest {
        val fixture = fixture(StandardTestDispatcher(testScheduler))
        val response = CompletableDeferred<Unit>()
        val session = fixture.client.createSession(testChatbotConfiguration())
        val foreign = fixture.client.createSession(testChatbotConfiguration())
        try {
            val consumed = session.prepareMessage("already sent")
            session.sendPrepared(consumed)
            advanceUntilIdle()
            val foreignInput = foreign.prepareMessage("another facade")
            fixture.runner.beforeCommit = response
            session.send("active execution")
            runCurrent()

            for (input in listOf(consumed, foreignInput)) {
                assertEquals(
                    ChatbotFailure.INVALID_ARGUMENT,
                    assertFailsWith<ChatbotException> { session.sendPrepared(input) }.failure,
                )
            }

            assertTrue(fixture.runner.cancelledSessions.isEmpty())
            assertEquals(2, fixture.runner.requests.size)
            assertEquals(ChatbotStatus.RUNNING, session.snapshot().status)
            assertFalse(response.isCompleted)
            response.complete(Unit)
            advanceUntilIdle()
            foreign.sendPrepared(foreignInput)
            advanceUntilIdle()
            assertEquals(foreignInput.messageId, fixture.runner.requests.last().messages.last().id)
        } finally {
            response.complete(Unit)
            fixture.client.close()
        }
    }

    @Test
    fun failedAttemptIsNotReusableAndItsFailureDoesNotExposeInput() = runTest {
        var failFactory = true
        val delegate = DefaultChatbotRequestFactory()
        val factory = object : ChatbotRequestFactory {
            override fun create(context: ChatbotRequestContext): AgentRequest {
                if (failFactory) error("sensitive prepared contents")
                return delegate.create(context)
            }
        }
        val fixture = fixture(StandardTestDispatcher(testScheduler), factory)
        val session = fixture.client.createSession(testChatbotConfiguration())
        val prepared = session.prepareMessage("sensitive prepared contents")

        val failed = assertFailsWith<ChatbotException> { session.sendPrepared(prepared) }
        assertEquals(ChatbotFailure.OPERATION_FAILED, failed.failure)
        assertNull(failed.cause)
        assertFalse(failed.toString().contains("sensitive"))
        failFactory = false
        assertEquals(ChatbotFailure.INVALID_ARGUMENT,
            assertFailsWith<ChatbotException> { session.sendPrepared(prepared) }.failure)
        assertTrue(fixture.runner.requests.isEmpty())
        session.send("a deliberate new send")
        advanceUntilIdle()
        assertEquals(1, fixture.runner.requests.size)
        fixture.client.close()
    }

    @Test
    fun acceptedPreparedInputKeepsItsIdentityWhileTheFirstCommitIsDelayed() = runTest {
        val fixture = fixture(StandardTestDispatcher(testScheduler))
        val commitGate = CompletableDeferred<Unit>()
        fixture.runner.beforeCommit = commitGate
        val session = fixture.client.createSession(testChatbotConfiguration())
        val prepared = session.prepareMessage("accepted before persistence")

        session.sendPrepared(prepared)

        assertNull(fixture.store.load(session.sessionId))
        assertEquals(prepared.messageId, session.snapshot().messages.single().id)
        assertEquals(ChatbotFailure.INVALID_ARGUMENT,
            assertFailsWith<ChatbotException> { session.sendPrepared(prepared) }.failure)
        commitGate.complete(Unit)
        advanceUntilIdle()
        assertEquals(prepared.messageId,
            assertNotNull(fixture.store.load(session.sessionId)).snapshot.state.messages.first().id)
        assertEquals(1, fixture.runner.requests.size)
        fixture.client.close()
    }

    private fun fixture(
        dispatcher: CoroutineDispatcher,
        requestFactory: ChatbotRequestFactory = DefaultChatbotRequestFactory(),
    ): Fixture {
        val store = InMemoryAgentPersistence()
        val runner = RecordingRunner(store)
        val client = createChatbotClient(runner, requestFactory, store, {}, dispatcher)
        return Fixture(store, runner, client)
    }

    private class Fixture(
        val store: InMemoryAgentPersistence,
        val runner: RecordingRunner,
        val client: ChatbotClient,
    )

    private class RecordingRunner(private val store: InMemoryAgentPersistence) : TestAgentRunner() {
        val requests = mutableListOf<AgentRequest>()
        val cancelledSessions = mutableListOf<AgentSessionId>()
        private val collectors = mutableMapOf<AgentSessionId, Job>()
        var beforeCommit: CompletableDeferred<Unit>? = null
        var afterCompleted: CompletableDeferred<Unit>? = null

        override fun run(request: AgentRequest): Flow<AgentEvent> = flow {
            requests += request
            collectors[request.sessionId] = checkNotNull(currentCoroutineContext()[Job])
            try {
                val runId = AgentRunId("prepared-run-${requests.size}")
                emit(AgentEvent.Started(request.sessionId, runId))
                beforeCommit?.await()
                val assistant = AgentMessage(
                    id = "prepared-answer-${requests.size}",
                    role = MessageRole.ASSISTANT,
                    parts = listOf(TextPart("answer")),
                    stopReason = StopReason.COMPLETED,
                )
                val state = AgentStateSnapshot(
                    messages = request.messages + assistant,
                    status = AgentStatus.COMPLETED,
                    stopReason = StopReason.COMPLETED,
                )
                store.commit(AgentSessionSnapshot(request.sessionId, runId, request, state), null)
                try {
                    emit(AgentEvent.Completed(request.sessionId, state))
                } finally {
                    // The manager deliberately stops upstream collection by throwing at the terminal
                    // event. Cleanup belongs in finally, not unreachable code after that emit.
                    afterCompleted?.let { gate -> withContext(NonCancellable) { gate.await() } }
                }
            } finally {
                collectors.remove(request.sessionId)
            }
        }

        override suspend fun resume(sessionId: AgentSessionId): Flow<AgentEvent> =
            error("No resume is expected")

        override suspend fun cancel(sessionId: AgentSessionId) {
            cancelledSessions += sessionId
            collectors[sessionId]?.cancel()
        }
    }
}
