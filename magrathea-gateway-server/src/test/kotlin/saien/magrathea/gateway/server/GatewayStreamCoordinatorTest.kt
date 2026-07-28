@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package saien.magrathea.gateway.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import saien.magrathea.core.AgentMessage
import saien.magrathea.core.AttachmentPart
import saien.magrathea.core.IdGenerator
import saien.magrathea.core.MessageRole
import saien.magrathea.core.ModelDescriptor
import saien.magrathea.core.ProviderCredential
import saien.magrathea.core.StopReason
import saien.magrathea.core.TextPart
import saien.magrathea.gateway.protocol.GatewayCreateStreamRequest
import saien.magrathea.gateway.protocol.GatewayAttachmentReference
import saien.magrathea.gateway.protocol.GatewayEvent
import saien.magrathea.gateway.protocol.GatewayGenerationOptions
import saien.magrathea.gateway.protocol.GatewayModelReference
import saien.magrathea.gateway.protocol.GatewayProtocolCodec
import saien.magrathea.gateway.protocol.GatewayStreamEnvelope
import saien.magrathea.provider.api.InMemoryProviderRegistry
import saien.magrathea.provider.api.ProviderAdapter
import saien.magrathea.provider.api.ProviderChunk
import saien.magrathea.provider.api.ProviderContextLimitException
import saien.magrathea.provider.api.ProviderEvent
import saien.magrathea.provider.api.ProviderRequest
import saien.magrathea.provider.api.ProviderUsage

class GatewayStreamCoordinatorTest {
    @Test
    fun sameScopedRequestIsExactlyOnceAndDifferentBodyConflicts() = runTest {
        val fixture = Fixture(this)
        val request = request()

        val first = fixture.coordinator.create(USER_A, request)
        val second = fixture.coordinator.create(USER_A, request)
        runCurrent()

        assertTrue(first.created)
        assertFalse(second.created)
        assertEquals(first.descriptor, second.descriptor)
        assertEquals(1, fixture.provider.calls)
        assertFailsWith<GatewayIdempotencyConflictException> {
            fixture.coordinator.create(
                USER_A,
                request.copy(messages = listOf(message("different"))),
            )
        }
        fixture.close()
    }

    @Test
    fun slowModelResolutionDoesNotBlockUnrelatedCreateScopes() = runTest {
        val slowEntered = CompletableDeferred<Unit>()
        val releaseSlow = CompletableDeferred<Unit>()
        val fixture = Fixture(
            scope = this,
            modelResolver = GatewayModelResolver { _, reference ->
                if (reference.model == "slow-model") {
                    slowEntered.complete(Unit)
                    releaseSlow.await()
                }
                ModelDescriptor(reference.provider, reference.model, supportsStreaming = true)
            },
        )
        val slowRequest = request().copy(
            requestId = "slow-session:0",
            sessionId = "slow-session",
            model = GatewayModelReference("gemini", "slow-model"),
        )
        val fastRequest = request().copy(
            requestId = "fast-session:0",
            sessionId = "fast-session",
            model = GatewayModelReference("gemini", "fast-model"),
        )

        val slow = async { fixture.coordinator.create(USER_A, slowRequest) }
        slowEntered.await()
        val fast = async { fixture.coordinator.create(USER_A, fastRequest) }
        runCurrent()

        assertTrue(fast.isCompleted)
        assertTrue(fast.await().created)
        assertFalse(slow.isCompleted)
        releaseSlow.complete(Unit)
        assertTrue(slow.await().created)
        fixture.close()
    }

    @Test
    fun serverModelResolverCannotSubstituteAClientAuthorizedIdentity() = runTest {
        val fixture = Fixture(
            scope = this,
            modelResolver = GatewayModelResolver { _, _ ->
                ModelDescriptor("other-provider", "other-model", supportsStreaming = true)
            },
        )

        assertFailsWith<GatewayAuthorizationException> {
            fixture.coordinator.create(USER_A, request())
        }
        assertEquals(0, fixture.provider.calls)
        assertEquals(0, fixture.quota.reservations)
        fixture.close()
    }

    @Test
    fun attachmentOwnershipFailureStopsBeforeQuotaAndProviderWork() = runTest {
        val fixture = Fixture(
            scope = this,
            attachmentResolver = GatewayAttachmentResolver { _, _, _ ->
                throw GatewayAuthorizationException()
            },
        )
        val attachmentRequest = request().copy(
            messages = listOf(
                message("ignored").copy(
                    parts = listOf(AttachmentPart("magrathea-attachment:file-1", "image/png")),
                ),
            ),
            attachments = listOf(GatewayAttachmentReference("file-1", "image/png", 42)),
        )

        assertFailsWith<GatewayAuthorizationException> {
            fixture.coordinator.create(USER_A, attachmentRequest)
        }
        assertEquals(0, fixture.quota.reservations)
        assertEquals(0, fixture.provider.calls)
        fixture.close()
    }

    @Test
    fun replayCursorHasNoDuplicateAndOwnershipIsFailClosed() = runTest {
        val fixture = Fixture(this)
        val created = fixture.coordinator.create(USER_A, request()).descriptor
        runCurrent()

        val all = fixture.coordinator.events(USER_A, created.streamId, -1).toList()
        val resumed = fixture.coordinator.events(USER_A, created.streamId, 1).toList()

        assertEquals(listOf(0L, 1L, 2L), all.map { it.sequence })
        assertEquals(listOf(2L), resumed.map { it.sequence })
        assertIs<GatewayEvent.StreamOpened>(all[0].event)
        assertIs<GatewayEvent.TextDelta>(all[1].event)
        assertIs<GatewayEvent.Completed>(all[2].event)
        assertFailsWith<GatewayStreamNotFoundException> {
            fixture.coordinator.events(USER_B, created.streamId, -1)
        }
        assertFailsWith<GatewayCursorException> {
            fixture.coordinator.events(USER_A, created.streamId, 99)
        }
        fixture.close()
    }

    @Test
    fun explicitCancelStopsProviderAndEmitsExactlyOneTerminal() = runTest {
        val fixture = Fixture(this, provider = ScriptedProvider(blockAfterDelta = true))
        val created = fixture.coordinator.create(USER_A, request()).descriptor
        val collection = backgroundScope.async {
            fixture.coordinator.events(USER_A, created.streamId, -1).toList()
        }
        runCurrent()

        fixture.coordinator.cancel(USER_A, created.streamId)
        val events = collection.await()

        assertEquals(1, fixture.provider.cancellations)
        assertEquals(1, events.count { it.event is GatewayEvent.Cancelled })
        assertTrue(events.last().event is GatewayEvent.Cancelled)
        fixture.coordinator.cancel(USER_A, created.streamId)
        assertEquals(1, fixture.provider.cancellations)
        fixture.close()
    }

    @Test
    fun auditAndQuotaNeverReceivePromptOrCredentialPayloads() = runTest {
        val fixture = Fixture(this)
        val created = fixture.coordinator.create(USER_A, request()).descriptor
        runCurrent()
        fixture.coordinator.events(USER_A, created.streamId, -1).toList()

        assertEquals(1, fixture.quota.reservations)
        assertEquals(1, fixture.quota.completed)
        assertTrue(fixture.audit.any { it.action == GatewayAuditAction.STREAM_CREATED })
        val terminal = fixture.audit.single { it.action == GatewayAuditAction.STREAM_COMPLETED }
        assertEquals("tenant-a", terminal.tenantId)
        assertEquals("gemini-test", terminal.model)
        assertFalse(terminal.toString().contains("server-only-secret"))
        assertFalse(terminal.toString().contains("hello from browser"))
        fixture.close()
    }

    @Test
    fun quotaCompletionFailureCannotPublishContradictoryCompletedTerminal() = runTest {
        val fixture = Fixture(this, quota = RecordingQuotaManager(failComplete = true))
        val created = fixture.coordinator.create(USER_A, request()).descriptor
        runCurrent()

        val events = fixture.coordinator.events(USER_A, created.streamId, -1).toList()

        assertTrue(events.last().event is GatewayEvent.Failed)
        assertTrue(events.none { it.event is GatewayEvent.Completed })
        assertEquals(1, fixture.quota.completed)
        assertEquals(1, fixture.quota.failed)
        assertTrue(fixture.audit.any { it.action == GatewayAuditAction.STREAM_FAILED })
        assertTrue(fixture.audit.none { it.action == GatewayAuditAction.STREAM_COMPLETED })
        fixture.close()
    }

    @Test
    fun providerContextLimitRemainsRecoverableAcrossTheGatewayBoundary() = runTest {
        val fixture = Fixture(
            scope = this,
            provider = ScriptedProvider(failure = ProviderContextLimitException()),
        )
        val created = fixture.coordinator.create(USER_A, request()).descriptor
        runCurrent()

        val events = fixture.coordinator.events(USER_A, created.streamId, -1).toList()

        val failed = assertIs<GatewayEvent.Failed>(events.last().event)
        assertEquals(
            saien.magrathea.gateway.protocol.GatewayFailureCode.CONTEXT_LIMIT,
            failed.code,
        )
        assertEquals(1, fixture.quota.failed)
        fixture.close()
    }

    @Test
    fun hardStreamLifetimeCancelsAHungProviderEvenWhileSubscribed() = runTest {
        val fixture = Fixture(
            scope = this,
            provider = ScriptedProvider(blockAfterDelta = true),
            config = GatewayCoordinatorConfig(
                reconnectGraceMillis = 60_000,
                terminalRetentionMillis = 60_000,
                streamLifetimeMillis = 1_000,
            ),
        )
        val created = fixture.coordinator.create(USER_A, request()).descriptor
        val collection = backgroundScope.async {
            fixture.coordinator.events(USER_A, created.streamId, -1).toList()
        }
        runCurrent()

        advanceTimeBy(1_000)
        runCurrent()
        val events = collection.await()

        assertEquals(1, fixture.provider.cancellations)
        assertIs<GatewayEvent.Cancelled>(events.last().event)
        fixture.close()
    }

    @Test
    fun replayEventLimitStopsUpstreamWithOneBoundedFailureTerminal() = runTest {
        val fixture = Fixture(
            scope = this,
            config = GatewayCoordinatorConfig(
                maxReplayEvents = 2,
                reconnectGraceMillis = 10_000,
                terminalRetentionMillis = 10_000,
                streamLifetimeMillis = 20_000,
            ),
        )
        val created = fixture.coordinator.create(USER_A, request()).descriptor
        runCurrent()

        val events = fixture.coordinator.events(USER_A, created.streamId, -1).toList()

        assertEquals(listOf(0L, 1L), events.map { it.sequence })
        assertIs<GatewayEvent.StreamOpened>(events.first().event)
        val failed = assertIs<GatewayEvent.Failed>(events.last().event)
        assertEquals(saien.magrathea.gateway.protocol.GatewayFailureCode.PROTOCOL_FAILURE, failed.code)
        assertEquals(0, fixture.quota.completed)
        assertEquals(1, fixture.quota.failed)
        fixture.close()
    }

    @Test
    fun oversizedCompletedEventFailsBeforeQuotaCompletionAndStaysWithinByteBudget() = runTest {
        val maxReplayBytes = 800
        val fixture = Fixture(
            scope = this,
            provider = ScriptedProvider(
                completedMetadata = buildJsonObject { put("response", "x".repeat(2_000)) },
            ),
            config = GatewayCoordinatorConfig(
                maxReplayBytes = maxReplayBytes,
                reconnectGraceMillis = 10_000,
                terminalRetentionMillis = 10_000,
                streamLifetimeMillis = 20_000,
            ),
        )
        val created = fixture.coordinator.create(USER_A, request()).descriptor
        runCurrent()

        val events = fixture.coordinator.events(USER_A, created.streamId, -1).toList()
        val encodedBytes = events.sumOf { GatewayProtocolCodec().encodeEnvelope(it).encodeToByteArray().size }

        val failed = assertIs<GatewayEvent.Failed>(events.last().event)
        assertEquals(saien.magrathea.gateway.protocol.GatewayFailureCode.PROTOCOL_FAILURE, failed.code)
        assertTrue(events.none { it.event is GatewayEvent.Completed })
        assertTrue(encodedBytes <= maxReplayBytes)
        assertEquals(0, fixture.quota.completed)
        assertEquals(1, fixture.quota.failed)
        fixture.close()
    }

    @Test
    fun insufficientOpeningCapacityRejectsCreationAndReleasesQuota() = runTest {
        val fixture = Fixture(
            scope = this,
            config = GatewayCoordinatorConfig(
                maxReplayBytes = 1,
                reconnectGraceMillis = 10_000,
                terminalRetentionMillis = 10_000,
                streamLifetimeMillis = 20_000,
            ),
        )

        assertFailsWith<IllegalStateException> { fixture.coordinator.create(USER_A, request()) }
        assertEquals(0, fixture.provider.calls)
        assertEquals(1, fixture.quota.failed)
        fixture.close()
    }

    private class Fixture(
        scope: kotlinx.coroutines.CoroutineScope,
        provider: ScriptedProvider = ScriptedProvider(),
        quota: RecordingQuotaManager = RecordingQuotaManager(),
        modelResolver: GatewayModelResolver = GatewayModelResolver { _, reference ->
            ModelDescriptor(
                provider = reference.provider,
                model = reference.model,
                supportsStreaming = true,
            )
        },
        attachmentResolver: GatewayAttachmentResolver = RejectingGatewayAttachmentResolver,
        config: GatewayCoordinatorConfig = GatewayCoordinatorConfig(
            reconnectGraceMillis = 10_000,
            terminalRetentionMillis = 10_000,
            streamLifetimeMillis = 20_000,
        ),
    ) : AutoCloseable {
        val provider = provider
        val quota = quota
        val audit = mutableListOf<GatewayAuditEvent>()
        private var nextId = 0
        val coordinator = GatewayStreamCoordinator(
            providerRegistry = InMemoryProviderRegistry(listOf(provider)),
            modelResolver = modelResolver,
            credentialResolver = GatewayProviderCredentialResolver { _, _ -> ProviderCredential("server-only-secret") },
            attachmentResolver = attachmentResolver,
            quotaManager = quota,
            auditSink = GatewayAuditSink(audit::add),
            parentScope = scope,
            config = config,
            idGenerator = IdGenerator { "test-${nextId++}" },
        )

        override fun close() = coordinator.close()
    }

    private class ScriptedProvider(
        private val blockAfterDelta: Boolean = false,
        private val completedMetadata: JsonObject? = null,
        private val failure: Throwable? = null,
    ) : ProviderAdapter {
        override val key: String = "gemini"
        var calls = 0
        var cancellations = 0

        override suspend fun generate(request: ProviderRequest): Flow<ProviderChunk> = flow {
            calls += 1
            failure?.let { throw it }
            try {
                emit(ProviderChunk(events = listOf(ProviderEvent.TextDelta("hello"))))
                if (blockAfterDelta) awaitCancellation()
                emit(
                    ProviderChunk(
                        events = listOf(
                            ProviderEvent.Completed(
                                stopReason = StopReason.COMPLETED,
                                usage = ProviderUsage(inputTokens = 3, outputTokens = 1),
                                providerMetadata = completedMetadata,
                            ),
                        ),
                    ),
                )
            } finally {
                if (blockAfterDelta) cancellations += 1
            }
        }
    }

    private class RecordingQuotaManager(
        private val failComplete: Boolean = false,
    ) : GatewayQuotaManager {
        var reservations = 0
        var completed = 0
        var failed = 0

        override suspend fun reserve(
            principal: GatewayPrincipal,
            request: GatewayCreateStreamRequest,
        ): GatewayQuotaDecision {
            reservations += 1
            return GatewayQuotaDecision.Granted(
                object : GatewayQuotaReservation {
                    override suspend fun complete(usage: saien.magrathea.gateway.protocol.GatewayUsage?) {
                        completed += 1
                        if (failComplete) error("quota completion failed")
                    }

                    override suspend fun cancel() = Unit
                    override suspend fun fail() {
                        failed += 1
                    }
                },
            )
        }
    }

    companion object {
        private val USER_A = GatewayPrincipal("user-a", "tenant-a")
        private val USER_B = GatewayPrincipal("user-b", "tenant-b")

        fun request() = GatewayCreateStreamRequest(
            requestId = "session-1:0",
            sessionId = "session-1",
            turn = 0,
            model = GatewayModelReference(provider = "gemini", model = "gemini-test"),
            messages = listOf(message("hello from browser")),
            options = GatewayGenerationOptions(maxTokens = 64),
        )

        fun message(text: String) = AgentMessage(
            id = "message-1",
            role = MessageRole.USER,
            parts = listOf(TextPart(text)),
            createdAtEpochMs = 1,
            metadata = JsonObject(emptyMap()),
        )
    }
}
