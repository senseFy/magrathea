@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package saien.magrathea.chatbot

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import saien.magrathea.catalog.CatalogKeyEncoding
import saien.magrathea.catalog.InMemorySessionCatalogStore
import saien.magrathea.catalog.SessionActivityMode
import saien.magrathea.catalog.SessionActivityOrder
import saien.magrathea.catalog.SessionCatalogPolicy
import saien.magrathea.catalog.SessionCatalogProjection
import saien.magrathea.catalog.SessionCatalogProjector
import saien.magrathea.catalog.SessionCatalogQuery
import saien.magrathea.catalog.SessionCatalogSort
import saien.magrathea.catalog.SessionCollection
import saien.magrathea.catalog.SessionOrganization
import saien.magrathea.catalog.SessionPinFilter
import saien.magrathea.core.AgentCheckpoint
import saien.magrathea.core.AgentEvent
import saien.magrathea.core.AgentInterruption
import saien.magrathea.core.AgentInterruptionReason
import saien.magrathea.core.AgentMessage
import saien.magrathea.core.AgentPersistence
import saien.magrathea.core.AgentRecoveryDisposition
import saien.magrathea.core.AgentRecoveryInfo
import saien.magrathea.core.AgentRequest
import saien.magrathea.core.AgentResumeCursor
import saien.magrathea.core.AgentResumePhase
import saien.magrathea.core.AgentRunId
import saien.magrathea.core.AgentSessionId
import saien.magrathea.core.AgentSessionSnapshot
import saien.magrathea.core.AgentStateSnapshot
import saien.magrathea.core.AgentStatus
import saien.magrathea.core.MessageRole
import saien.magrathea.core.StopReason
import saien.magrathea.core.TextPart
import saien.magrathea.core.text
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Exercises the real Chatbot/AgentSessionManager preparation seam with a neutral catalog store.
 * The deterministic runner sends no Provider requests. Physical database atomicity is tested by
 * the storage modules; these tests cover the product's ordering and lifecycle composition.
 */
class ChatbotCatalogPreparationIntegrationTest {
    @Test
    fun firstCommitBindsPreparedInputOrganizationAndActivityWithoutMetadata() = runTest {
        val fixture = fixture(StandardTestDispatcher(testScheduler))
        val collection = collection()
        fixture.store.catalog.createCollection(collection)
        val session = fixture.client.createSession(testChatbotConfiguration())
        val prepared = session.prepareMessage("first input")
        val identity = fixture.store.catalog.prepareNewSession(
            session.sessionId,
            "creation-one",
            SessionOrganization(title = "Initial title", collectionId = collection.id),
        )
        val ticket = fixture.store.catalog.prepareActivity(identity, prepared.messageId)
        val gate = fixture.runner.delayCommit(session.sessionId)

        session.sendPrepared(prepared)

        assertFalse(fixture.store.catalog.exists(session.sessionId))
        assertNull(fixture.store.catalog.sessionHeader(session.sessionId))
        assertNull(fixture.store.persistence.load(session.sessionId))
        assertEquals(prepared.messageId, session.snapshot().messages.single().id)
        gate.complete(Unit)
        advanceUntilIdle()

        val header = assertNotNull(fixture.store.catalog.sessionHeader(session.sessionId))
        assertEquals(identity, header.identity)
        assertEquals("Initial title", header.label)
        assertEquals(collection.id, header.collectionId)
        assertEquals(SessionActivityOrder.Accepted(ticket.sequence), header.activity.order)
        val canonical = assertNotNull(fixture.store.persistence.load(session.sessionId)).snapshot
        assertEquals(prepared.messageId, canonical.state.messages.first().id)
        assertTrue(canonical.request.metadata.isEmpty())
        assertTrue(canonical.state.messages.first().metadata.isEmpty())
        fixture.close()
    }

    @Test
    fun acceptanceOrderSurvivesDifferentSessionsCommittingInReverseOrder() = runTest {
        val fixture = fixture(StandardTestDispatcher(testScheduler))
        val first = fixture.client.createSession(testChatbotConfiguration())
        val firstInput = first.prepareMessage("accepted first")
        val firstIdentity = fixture.store.catalog.prepareNewSession(first.sessionId, "creation-first")
        val firstTicket = fixture.store.catalog.prepareActivity(firstIdentity, firstInput.messageId)
        val firstGate = fixture.runner.delayCommit(first.sessionId)
        first.sendPrepared(firstInput)

        val second = fixture.client.createSession(testChatbotConfiguration())
        val secondInput = second.prepareMessage("accepted second")
        val secondIdentity = fixture.store.catalog.prepareNewSession(second.sessionId, "creation-second")
        val secondTicket = fixture.store.catalog.prepareActivity(secondIdentity, secondInput.messageId)
        second.sendPrepared(secondInput)
        advanceUntilIdle()
        assertTrue(secondTicket.sequence > firstTicket.sequence)
        assertFalse(fixture.store.catalog.exists(first.sessionId))
        assertTrue(fixture.store.catalog.exists(second.sessionId))

        firstGate.complete(Unit)
        advanceUntilIdle()

        val page = fixture.store.catalog.pageSessions(
            SessionCatalogQuery(pin = SessionPinFilter.UNPINNED, sort = SessionCatalogSort.ACTIVITY_DESC),
            limit = 10,
        )
        assertEquals(listOf(second.sessionId, first.sessionId), page.entries.map { it.identity.sessionId })
        fixture.close()
    }

    @Test
    fun regeneratingAnEarlierInputPreservesOrderThenTheNextSendAdvancesIt() = runTest {
        val fixture = fixture(StandardTestDispatcher(testScheduler))
        val session = fixture.client.createSession(testChatbotConfiguration())
        val identity = fixture.store.catalog.prepareNewSession(session.sessionId, "creation-regenerate")
        val first = session.prepareMessage("U1")
        fixture.store.catalog.prepareActivity(identity, first.messageId)
        session.sendPrepared(first)
        advanceUntilIdle()

        val second = session.prepareMessage("U2")
        val secondTicket = fixture.store.catalog.prepareActivity(identity, second.messageId)
        session.sendPrepared(second)
        advanceUntilIdle()

        fixture.store.catalog.preparePreservedActivity(identity, first.messageId)
        session.regenerate(first.messageId)
        advanceUntilIdle()

        val regenerated = assertNotNull(fixture.store.catalog.sessionHeader(session.sessionId))
        assertEquals(SessionActivityOrder.Accepted(secondTicket.sequence), regenerated.activity.order)
        assertEquals(first.messageId, regenerated.activity.observedIdentity)
        assertEquals(second.messageId, regenerated.activity.lastAcceptedIdentity)
        assertEquals(
            listOf(first.messageId),
            assertNotNull(fixture.store.persistence.load(session.sessionId)).snapshot.state.messages
                .filter { it.role == MessageRole.USER }.map { it.id },
        )

        val third = session.prepareMessage("U3 after regeneration")
        val thirdTicket = fixture.store.catalog.prepareActivity(identity, third.messageId)
        session.sendPrepared(third)
        advanceUntilIdle()
        val advanced = assertNotNull(fixture.store.catalog.sessionHeader(session.sessionId))
        assertTrue(thirdTicket.sequence > secondTicket.sequence)
        assertEquals(SessionActivityOrder.Accepted(thirdTicket.sequence), advanced.activity.order)
        assertEquals(third.messageId, advanced.activity.lastAcceptedIdentity)
        fixture.close()
    }

    @Test
    fun deletingCollectionBeforeFirstCommitUnassignsWithoutLosingTheInputOrTitle() = runTest {
        val fixture = fixture(StandardTestDispatcher(testScheduler))
        val collection = collection()
        fixture.store.catalog.createCollection(collection)
        val session = fixture.client.createSession(testChatbotConfiguration())
        val input = session.prepareMessage("accepted while collection existed")
        val identity = fixture.store.catalog.prepareNewSession(
            session.sessionId,
            "creation-delete-group",
            SessionOrganization(title = "Keep title", collectionId = collection.id),
        )
        fixture.store.catalog.prepareActivity(identity, input.messageId)
        val gate = fixture.runner.delayCommit(session.sessionId)
        session.sendPrepared(input)

        fixture.store.catalog.deleteCollection(collection.id)
        gate.complete(Unit)
        advanceUntilIdle()

        val header = assertNotNull(fixture.store.catalog.sessionHeader(session.sessionId))
        assertNull(header.collectionId)
        assertEquals("Keep title", header.label)
        assertEquals(ChatbotStatus.COMPLETED, session.snapshot().status)
        assertEquals(1, fixture.runner.requests.size)
        fixture.close()
    }

    @Test
    fun shutdownCanMakeTheFirstCommitBeforePreparationIsReleased() = runTest {
        val fixture = fixture(StandardTestDispatcher(testScheduler))
        val session = fixture.client.createSession(testChatbotConfiguration())
        val input = session.prepareMessage("flush on shutdown")
        val identity = fixture.store.catalog.prepareNewSession(
            session.sessionId,
            "creation-close",
            SessionOrganization(title = "Shutdown title"),
        )
        val ticket = fixture.store.catalog.prepareActivity(identity, input.messageId)
        fixture.runner.delayCommit(session.sessionId)
        session.sendPrepared(input)
        assertFalse(fixture.store.catalog.exists(session.sessionId))

        // Closing the canonical owner fences and drains the execution, including its final write.
        // The catalog handle and transient preparation deliberately remain alive until it returns.
        fixture.client.close()
        fixture.store.catalog.releasePreparation(identity)

        val header = assertNotNull(fixture.store.catalog.sessionHeader(session.sessionId))
        assertEquals("Shutdown title", header.label)
        assertEquals(SessionActivityOrder.Accepted(ticket.sequence), header.activity.order)
        assertEquals(AgentStatus.INTERRUPTED, header.status)
        val record = assertNotNull(fixture.store.persistence.load(session.sessionId))
        assertEquals(input.messageId, record.snapshot.state.messages.single().id)
        assertNotNull(record.checkpoint)
        assertEquals(1, fixture.runner.interruptCount)
        fixture.store.close()
    }

    private fun fixture(dispatcher: CoroutineDispatcher): Fixture {
        val store = InMemorySessionCatalogStore(UserInputProjector())
        val runner = ControlledRunner(store.persistence)
        val client = createChatbotClient(runner, DefaultChatbotRequestFactory(), store.persistence, {}, dispatcher)
        return Fixture(store, runner, client)
    }

    private fun collection() = SessionCollection(
        "collection-one",
        "Projects",
        CatalogKeyEncoding.stringKey("projects"),
        "projects",
    )

    private class Fixture(
        val store: InMemorySessionCatalogStore,
        val runner: ControlledRunner,
        val client: ChatbotClient,
    ) {
        suspend fun close() {
            client.close()
            store.close()
        }
    }

    /** A test host policy, not a Chatbot requirement in the catalog implementation. */
    private class UserInputProjector : SessionCatalogProjector {
        override val policy = SessionCatalogPolicy("chatbot-preparation-test", 1)
        override val activityMode = SessionActivityMode.PREPARED

        override fun project(snapshot: AgentSessionSnapshot): SessionCatalogProjection {
            val lastUser = snapshot.state.messages.lastOrNull { it.role == MessageRole.USER }
            val label = snapshot.state.messages.firstOrNull()?.text().orEmpty()
            return SessionCatalogProjection(
                fallbackLabel = label,
                activityIdentity = lastUser?.id,
                activityAtEpochMs = lastUser?.createdAtEpochMs ?: snapshot.updatedAtEpochMs,
                normalizedFallbackLabel = normalizeSearch(label),
            )
        }

        override fun normalizeSearch(text: String): String = text.trim().lowercase()
    }

    private class ControlledRunner(private val persistence: AgentPersistence) : TestAgentRunner() {
        val requests = mutableListOf<AgentRequest>()
        var interruptCount = 0
        private val gates = mutableMapOf<AgentSessionId, CompletableDeferred<Unit>>()
        private val active = mutableMapOf<AgentSessionId, Pair<AgentRequest, AgentRunId>>()

        fun delayCommit(sessionId: AgentSessionId): CompletableDeferred<Unit> =
            CompletableDeferred<Unit>().also { gates[sessionId] = it }

        override fun run(request: AgentRequest): Flow<AgentEvent> = flow {
            requests += request
            val runId = AgentRunId("catalog-run-${requests.size}")
            active[request.sessionId] = request to runId
            try {
                emit(AgentEvent.Started(request.sessionId, runId))
                gates.remove(request.sessionId)?.await()
                val state = AgentStateSnapshot(
                    messages = request.messages + AgentMessage(
                        role = MessageRole.ASSISTANT,
                        parts = listOf(TextPart("answer")),
                        stopReason = StopReason.COMPLETED,
                    ),
                    status = AgentStatus.COMPLETED,
                    stopReason = StopReason.COMPLETED,
                )
                persistence.commit(AgentSessionSnapshot(request.sessionId, runId, request, state), null)
                emit(AgentEvent.Completed(request.sessionId, state))
            } finally {
                active.remove(request.sessionId)
            }
        }

        override suspend fun interrupt(sessionId: AgentSessionId): AgentRecoveryInfo {
            val (request, runId) = checkNotNull(active[sessionId])
            interruptCount += 1
            val state = AgentStateSnapshot(
                messages = request.messages,
                status = AgentStatus.INTERRUPTED,
                stopReason = StopReason.INTERRUPTED,
            )
            val interruption = AgentInterruption(AgentInterruptionReason.HOST_REQUESTED)
            val checkpoint = AgentCheckpoint(
                sessionId,
                runId,
                AgentResumeCursor(0, AgentResumePhase.TURN_PREPARING),
                state,
            )
            persistence.commit(
                AgentSessionSnapshot(sessionId, runId, request, state, interruption = interruption),
                checkpoint,
            )
            return AgentRecoveryInfo(
                sessionId = sessionId,
                disposition = AgentRecoveryDisposition.RESUMABLE,
                runId = runId,
                state = state,
                status = state.status,
                interruption = interruption,
            )
        }

        override suspend fun resume(sessionId: AgentSessionId): Flow<AgentEvent> =
            error("No resume is expected")

        override suspend fun cancel(sessionId: AgentSessionId) = Unit
    }
}
