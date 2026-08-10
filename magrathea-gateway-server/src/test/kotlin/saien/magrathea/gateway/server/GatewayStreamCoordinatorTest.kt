@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package saien.magrathea.gateway.server

import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import saien.magrathea.core.AgentMessage
import saien.magrathea.core.AttachmentPart
import saien.magrathea.core.IdGenerator
import saien.magrathea.core.MessageRole
import saien.magrathea.core.ModelDescriptor
import saien.magrathea.core.ProviderCredential
import saien.magrathea.core.ReasoningCapabilities
import saien.magrathea.core.ReasoningEffort
import saien.magrathea.core.ReasoningPreference
import saien.magrathea.core.StopReason
import saien.magrathea.core.TextPart
import saien.magrathea.core.ToolImageAttachmentReference
import saien.magrathea.core.ToolResultAudience
import saien.magrathea.core.ToolResultImageContent
import saien.magrathea.core.ToolResultPart
import saien.magrathea.core.ToolResultTextContent
import saien.magrathea.gateway.protocol.GatewayCreateStreamRequest
import saien.magrathea.gateway.protocol.GatewayAttachmentReference
import saien.magrathea.gateway.protocol.GatewayEvent
import saien.magrathea.gateway.protocol.GatewayFailureCode
import saien.magrathea.gateway.protocol.GatewayGenerationOptions
import saien.magrathea.gateway.protocol.GatewayModelReference
import saien.magrathea.gateway.protocol.GatewayProtocolCodec
import saien.magrathea.gateway.protocol.GatewayStreamEnvelope
import saien.magrathea.provider.api.InMemoryProviderRegistry
import saien.magrathea.provider.api.ProviderAdapter
import saien.magrathea.provider.api.ProviderAuthException
import saien.magrathea.provider.api.ProviderChunk
import saien.magrathea.provider.api.ProviderClientException
import saien.magrathea.provider.api.ProviderContextLimitException
import saien.magrathea.provider.api.ProviderEvent
import saien.magrathea.provider.api.ProviderException
import saien.magrathea.provider.api.ProviderInvocationInvalidatedException
import saien.magrathea.provider.api.ProviderNetworkException
import saien.magrathea.provider.api.ProviderProtocolException
import saien.magrathea.provider.api.ProviderRateLimitException
import saien.magrathea.provider.api.ProviderRequest
import saien.magrathea.provider.api.ProviderServerException
import saien.magrathea.provider.api.ProviderTimeoutException
import saien.magrathea.provider.api.ProviderTimeoutPhase
import saien.magrathea.provider.api.ProviderUsage

class GatewayStreamCoordinatorTest {
    @Test
    fun coordinatorConfigKeepsReplayAvailableForTheAdvertisedLease() {
        assertFailsWith<IllegalArgumentException> {
            GatewayCoordinatorConfig(
                terminalRetentionMillis = 999,
                streamLifetimeMillis = 1_000,
            )
        }
    }

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
    fun resolveReturnsActiveAndRetainedTerminalDescriptorsWithoutStartingProviderWork() = runTest {
        val active = Fixture(this, provider = ScriptedProvider(blockAfterDelta = true))
        val activeDescriptor = active.coordinator.create(USER_A, request()).descriptor
        runCurrent()

        assertEquals(activeDescriptor, active.coordinator.resolveExisting(USER_A, request().requestId))
        assertEquals(1, active.provider.calls)
        assertEquals(1, active.quota.reservations)
        active.coordinator.cancel(USER_A, activeDescriptor.streamId)
        active.close()

        val terminal = Fixture(this)
        val terminalDescriptor = terminal.coordinator.create(USER_A, request()).descriptor
        runCurrent()

        assertEquals(terminalDescriptor, terminal.coordinator.resolveExisting(USER_A, request().requestId))
        assertEquals(1, terminal.provider.calls)
        assertEquals(1, terminal.quota.reservations)
        terminal.close()
    }

    @Test
    fun resolvingATerminalInvocationRenewsItsReplayRetention() = runTest {
        val fixture = Fixture(
            scope = this,
            config = GatewayCoordinatorConfig(
                terminalRetentionMillis = 1_000,
                idempotencyRetentionMillis = 5_000,
                streamLifetimeMillis = 100,
            ),
        )
        val descriptor = fixture.coordinator.create(USER_A, request()).descriptor
        runCurrent()
        advanceTimeBy(900)

        assertEquals(descriptor, fixture.coordinator.resolveExisting(USER_A, request().requestId))
        advanceTimeBy(200)
        runCurrent()
        val replay = fixture.coordinator.events(USER_A, descriptor.streamId, -1).toList()

        assertIs<GatewayEvent.Completed>(replay.last().event)
        assertEquals(1, fixture.provider.calls)
        assertEquals(1, fixture.quota.reservations)
        fixture.close()
    }

    @Test
    fun resolvePreservesInvalidationAndReplayExpiryWithoutStartingProviderWork() = runTest {
        val invalidated = Fixture(this, provider = ScriptedProvider(blockAfterDelta = true))
        val invalidatedDescriptor = invalidated.coordinator.create(USER_A, request()).descriptor
        runCurrent()
        invalidated.coordinator.cancel(USER_A, invalidatedDescriptor.streamId)

        assertFailsWith<GatewayInvocationInvalidatedException> {
            invalidated.coordinator.resolveExisting(USER_A, request().requestId)
        }
        assertEquals(1, invalidated.provider.calls)
        assertEquals(1, invalidated.quota.reservations)
        invalidated.close()

        val expired = Fixture(
            scope = this,
            config = GatewayCoordinatorConfig(
                terminalRetentionMillis = 100,
                idempotencyRetentionMillis = 1_000,
                streamLifetimeMillis = 100,
            ),
        )
        expired.coordinator.create(USER_A, request())
        runCurrent()
        advanceTimeBy(100)
        runCurrent()

        assertFailsWith<GatewayInvocationReplayUnavailableException> {
            expired.coordinator.resolveExisting(USER_A, request().requestId)
        }
        assertEquals(1, expired.provider.calls)
        assertEquals(1, expired.quota.reservations)
        expired.close()
    }

    @Test
    fun resolveAfterCoordinatorStateLossFailsClosedWithoutStartingOrOrphaningProviderWork() = runTest {
        val provider = ScriptedProvider(blockAfterDelta = true)
        val original = Fixture(this, provider = provider)
        val descriptor = original.coordinator.create(USER_A, request()).descriptor
        runCurrent()
        assertEquals(1, provider.calls)

        val restarted = Fixture(this, provider = provider)
        assertFailsWith<GatewayInvocationUnknownException> {
            restarted.coordinator.resolveExisting(USER_A, request().requestId)
        }
        assertFailsWith<GatewayInvocationUnknownException> {
            restarted.coordinator.resolveExisting(USER_B, request().requestId)
        }
        assertEquals(1, provider.calls)
        assertEquals(0, restarted.quota.reservations)

        original.coordinator.cancel(USER_A, descriptor.streamId)
        restarted.close()
        original.close()
    }

    @Test
    fun completedInvocationRemainsIdempotentlyReplayableDuringTerminalRetention() = runTest {
        val fixture = Fixture(
            scope = this,
            config = GatewayCoordinatorConfig(
                terminalRetentionMillis = 1_000,
                streamLifetimeMillis = 100,
            ),
        )
        val first = fixture.coordinator.create(USER_A, request())
        runCurrent()
        advanceTimeBy(100)

        val reused = fixture.coordinator.create(USER_A, request())

        assertFalse(reused.created)
        assertEquals(first.descriptor, reused.descriptor)
        assertEquals(1, fixture.provider.calls)
        fixture.close()
    }

    @Test
    fun terminalReuseRenewsReplayRetention() = runTest {
        val fixture = Fixture(
            scope = this,
            config = GatewayCoordinatorConfig(
                terminalRetentionMillis = 1_000,
                idempotencyRetentionMillis = 5_000,
                streamLifetimeMillis = 100,
            ),
        )
        val first = fixture.coordinator.create(USER_A, request())
        runCurrent()
        advanceTimeBy(900)

        val reused = fixture.coordinator.create(USER_A, request())
        advanceTimeBy(200)
        runCurrent()
        val replay = fixture.coordinator.events(USER_A, reused.descriptor.streamId, -1).toList()

        assertFalse(reused.created)
        assertEquals(first.descriptor, reused.descriptor)
        assertIs<GatewayEvent.Completed>(replay.last().event)
        assertEquals(1, fixture.provider.calls)
        fixture.close()
    }

    @Test
    fun completedReplayExpiryFailsClosedWithoutRepeatingProviderWork() = runTest {
        val fixture = Fixture(
            scope = this,
            config = GatewayCoordinatorConfig(
                terminalRetentionMillis = 100,
                idempotencyRetentionMillis = 1_000,
                streamLifetimeMillis = 100,
            ),
        )
        val created = fixture.coordinator.create(USER_A, request()).descriptor
        runCurrent()
        advanceTimeBy(100)
        runCurrent()

        assertFailsWith<GatewayStreamNotFoundException> {
            fixture.coordinator.events(USER_A, created.streamId, -1)
        }
        assertFailsWith<GatewayInvocationReplayUnavailableException> {
            fixture.coordinator.create(USER_A, request())
        }
        assertFailsWith<GatewayIdempotencyConflictException> {
            fixture.coordinator.create(
                USER_A,
                request().copy(messages = listOf(message("different"))),
            )
        }
        assertEquals(1, fixture.provider.calls)
        assertEquals(1, fixture.quota.reservations)

        advanceTimeBy(1_000)
        runCurrent()
        assertTrue(fixture.coordinator.create(USER_A, request()).created)
        runCurrent()
        assertEquals(2, fixture.provider.calls)
        assertEquals(2, fixture.quota.reservations)
        fixture.close()
    }

    @Test
    fun permanentFailureReplayExpiryFailsClosedWhileRetryableFailureInvalidates() = runTest {
        suspend fun tombstoneFailure(
            failure: Throwable,
            replayUnavailable: Boolean,
        ) {
            val fixture = Fixture(
                scope = this,
                provider = ScriptedProvider(failure = failure),
                config = GatewayCoordinatorConfig(
                    terminalRetentionMillis = 100,
                    idempotencyRetentionMillis = 1_000,
                    streamLifetimeMillis = 100,
                ),
            )
            fixture.coordinator.create(USER_A, request())
            runCurrent()
            advanceTimeBy(100)
            runCurrent()

            val actual = runCatching { fixture.coordinator.create(USER_A, request()) }.exceptionOrNull()
            if (replayUnavailable) {
                assertIs<GatewayInvocationReplayUnavailableException>(actual)
            } else {
                assertIs<GatewayInvocationInvalidatedException>(actual)
            }
            assertEquals(1, fixture.provider.calls)
            assertEquals(1, fixture.quota.reservations)
            fixture.close()
        }

        tombstoneFailure(
            failure = ProviderProtocolException("permanent"),
            replayUnavailable = true,
        )
        tombstoneFailure(
            failure = ProviderNetworkException("retryable"),
            replayUnavailable = false,
        )
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
    fun directGatewayRequestCannotExposeProductOnlyToolResultDataToProvider() = runTest {
        val secret = "gateway-direct-request-secret"
        val fixture = Fixture(scope = this)
        val maliciousToolResult = ToolResultPart(
            toolCallId = "tool-call-1",
            toolName = "search",
            result = JsonPrimitive(secret),
            displayText = secret,
            metadata = buildJsonObject { put("private", secret) },
            content = listOf(
                ToolResultTextContent("model-visible", setOf(ToolResultAudience.MODEL)),
                ToolResultTextContent(secret, setOf(ToolResultAudience.USER)),
                ToolResultImageContent(
                    source = ToolImageAttachmentReference("magrathea-attachment:user-only-image"),
                    mimeType = "image/jpeg",
                    audiences = setOf(ToolResultAudience.USER),
                ),
            ),
            providerMetadata = buildJsonObject { put("private", secret) },
            modelResultVisible = false,
        )
        val directRequest = request().copy(
            messages = listOf(
                message("ignored").copy(
                    role = MessageRole.TOOL,
                    parts = listOf(maliciousToolResult),
                ),
            ),
            attachments = listOf(
                GatewayAttachmentReference("user-only-image", "image/jpeg", 42),
            ),
        )

        fixture.coordinator.create(USER_A, directRequest)
        runCurrent()

        val providerRequest = fixture.provider.requests.single()
        assertFalse(providerRequest.toString().contains(secret))
        val projected = providerRequest.messages.single().parts.single() as ToolResultPart
        assertEquals(null, projected.displayText)
        assertEquals(emptySet(), projected.metadata.keys)
        assertEquals(null, projected.providerMetadata)
        assertEquals(JsonPrimitive("Tool completed without model-visible output."), projected.result)
        assertEquals(1, projected.content.size)
        assertEquals(setOf(ToolResultAudience.MODEL), projected.content.single().audiences)
        fixture.close()
    }

    @Test
    fun trustedModelResolutionCarriesReasoningPreferenceToProvider() = runTest {
        val fixture = Fixture(
            scope = this,
            modelResolver = GatewayModelResolver { _, reference ->
                ModelDescriptor(
                    provider = reference.provider,
                    model = reference.model,
                    reasoningCapabilities = ReasoningCapabilities(
                        supportedEfforts = setOf(ReasoningEffort.HIGH),
                    ),
                    supportsStreaming = true,
                )
            },
        )

        fixture.coordinator.create(
            USER_A,
            request().copy(
                reasoningPreference = ReasoningPreference.Effort(ReasoningEffort.HIGH),
            ),
        )
        runCurrent()

        assertEquals(
            ReasoningPreference.Effort(ReasoningEffort.HIGH),
            fixture.provider.requests.single().reasoningPreference,
        )
        fixture.close()
    }

    @Test
    fun unsupportedReasoningFailsBeforeCredentialAttachmentQuotaAndProviderWork() = runTest {
        var credentialResolutions = 0
        var attachmentResolutions = 0
        val fixture = Fixture(
            scope = this,
            modelResolver = GatewayModelResolver { _, reference ->
                ModelDescriptor(
                    provider = reference.provider,
                    model = reference.model,
                    reasoningCapabilities = ReasoningCapabilities(
                        supportedEfforts = setOf(ReasoningEffort.HIGH),
                    ),
                    supportsStreaming = true,
                )
            },
            credentialResolver = GatewayProviderCredentialResolver { _, _ ->
                credentialResolutions += 1
                ProviderCredential("server-only-secret")
            },
            attachmentResolver = GatewayAttachmentResolver { _, _, messages ->
                attachmentResolutions += 1
                messages
            },
        )

        assertFailsWith<IllegalArgumentException> {
            fixture.coordinator.create(
                USER_A,
                request().copy(
                    reasoningPreference = ReasoningPreference.Effort(ReasoningEffort.MAX),
                ),
            )
        }
        runCurrent()

        assertEquals(0, credentialResolutions)
        assertEquals(0, attachmentResolutions)
        assertEquals(0, fixture.quota.reservations)
        assertEquals(0, fixture.provider.calls)
        assertTrue(fixture.audit.isEmpty())
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
        assertFailsWith<GatewayInvocationInvalidatedException> {
            fixture.coordinator.create(USER_A, request())
        }
        assertEquals(1, fixture.provider.calls)
        fixture.close()
    }

    @Test
    fun abandonByScopedRequestIdIsIdempotentAndDoesNotRevealAbsence() = runTest {
        val fixture = Fixture(this, provider = ScriptedProvider(blockAfterDelta = true))
        fixture.coordinator.create(USER_A, request())
        runCurrent()

        fixture.coordinator.abandon(USER_B, request().requestId)
        fixture.coordinator.abandon(USER_A, "missing-request")
        assertEquals(0, fixture.provider.cancellations)

        fixture.coordinator.abandon(USER_A, request().requestId)
        fixture.coordinator.abandon(USER_A, request().requestId)

        assertEquals(1, fixture.provider.cancellations)
        assertEquals(1, fixture.quota.cancelled)
        assertFailsWith<GatewayInvocationInvalidatedException> {
            fixture.coordinator.create(USER_A, request())
        }
        assertEquals(1, fixture.provider.calls)
        fixture.close()
    }

    @Test
    fun abandonWinningTheCreateRaceInvalidatesTheRequestIdempotently() = runTest {
        val fixture = Fixture(this)
        val request = request()

        fixture.coordinator.abandon(USER_A, request.requestId)
        fixture.coordinator.abandon(USER_A, request.requestId)

        assertFailsWith<GatewayInvocationInvalidatedException> {
            fixture.coordinator.create(USER_A, request)
        }
        assertFailsWith<GatewayInvocationInvalidatedException> {
            fixture.coordinator.create(
                USER_A,
                request.copy(messages = listOf(message("different"))),
            )
        }
        assertEquals(0, fixture.provider.calls)
        assertEquals(0, fixture.quota.reservations)
        fixture.close()
    }

    @Test
    fun detachedStreamRemainsReattachableUntilItsLeaseExpires() = runTest {
        val fixture = Fixture(
            scope = this,
            provider = ScriptedProvider(blockAfterDelta = true),
            config = GatewayCoordinatorConfig(
                terminalRetentionMillis = 60_000,
                streamLifetimeMillis = 60_000,
            ),
        )
        val created = fixture.coordinator.create(USER_A, request()).descriptor
        val collection = backgroundScope.async {
            fixture.coordinator.events(USER_A, created.streamId, -1).toList()
        }
        runCurrent()

        collection.cancelAndJoin()
        advanceTimeBy(31_000)
        runCurrent()
        assertEquals(0, fixture.provider.cancellations)

        val reattached = backgroundScope.async {
            fixture.coordinator.events(USER_A, created.streamId, afterSequence = 1).toList()
        }
        runCurrent()
        fixture.coordinator.cancel(USER_A, created.streamId)
        val resumedEvents = reattached.await()

        assertEquals(1, fixture.provider.cancellations)
        assertIs<GatewayEvent.Cancelled>(resumedEvents.last().event)
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
    fun completedRemainsAuthoritativeWhenQuotaCompletionFails() = runTest {
        val fixture = Fixture(this, quota = RecordingQuotaManager(failComplete = true))
        val created = fixture.coordinator.create(USER_A, request()).descriptor
        runCurrent()

        val events = fixture.coordinator.events(USER_A, created.streamId, -1).toList()

        assertTrue(events.last().event is GatewayEvent.Completed)
        assertEquals(1, fixture.quota.completed)
        assertEquals(0, fixture.quota.failed)
        assertEquals(0, fixture.quota.cancelled)
        assertEquals(1, fixture.audit.count { it.action == GatewayAuditAction.STREAM_COMPLETED })
        assertTrue(fixture.audit.none { it.action == GatewayAuditAction.STREAM_FAILED })
        fixture.close()
    }

    @Test
    fun completedRemainsAuthoritativeAfterLaterTransportFailureOrCancellation() = runTest {
        listOf(
            ProviderNetworkException("late disconnect"),
            CancellationException("late cancellation"),
        ).forEachIndexed { index, lateFailure ->
            val fixture = Fixture(
                scope = this,
                provider = ScriptedProvider(failureAfterCompleted = lateFailure),
            )
            val created = fixture.coordinator.create(
                USER_A,
                request().copy(
                    requestId = "terminal-$index:0",
                    sessionId = "terminal-$index",
                ),
            ).descriptor
            runCurrent()

            val events = fixture.coordinator.events(USER_A, created.streamId, -1).toList()

            assertIs<GatewayEvent.Completed>(events.last().event)
            assertEquals(1, fixture.quota.completed)
            assertEquals(0, fixture.quota.failed)
            assertEquals(0, fixture.quota.cancelled)
            assertEquals(1, fixture.audit.count { it.action == GatewayAuditAction.STREAM_COMPLETED })
            assertTrue(fixture.audit.none { it.action == GatewayAuditAction.STREAM_FAILED })
            assertTrue(fixture.audit.none { it.action == GatewayAuditAction.STREAM_CANCELLED })
            fixture.close()
        }
    }

    @Test
    fun fastCompletionCancelsTheInstalledLifetimeTimer() = runTest {
        val fixture = Fixture(
            scope = this,
            config = GatewayCoordinatorConfig(
                terminalRetentionMillis = 1_000,
                streamLifetimeMillis = 100,
            ),
        )
        val created = fixture.coordinator.create(USER_A, request()).descriptor
        runCurrent()
        advanceTimeBy(100)
        runCurrent()

        val events = fixture.coordinator.events(USER_A, created.streamId, -1).toList()

        assertIs<GatewayEvent.Completed>(events.last().event)
        assertEquals(0, fixture.provider.cancellations)
        assertEquals(1, fixture.quota.completed)
        assertEquals(0, fixture.quota.cancelled)
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
    fun providerFailureTaxonomyIsStableSafeAndPreservesRetryPolicy() = runTest {
        val providerMessageCanary = "provider-message-must-not-cross-the-gateway"

        suspend fun failedEvent(failure: Throwable): GatewayEvent.Failed {
            val fixture = Fixture(
                scope = this,
                provider = ScriptedProvider(failure = failure),
            )
            val created = fixture.coordinator.create(USER_A, request()).descriptor
            runCurrent()
            val terminal = fixture.coordinator.events(USER_A, created.streamId, -1).toList().last()
            val failed = assertIs<GatewayEvent.Failed>(terminal.event)
            assertFalse(GatewayProtocolCodec().encodeEnvelope(terminal).contains(providerMessageCanary))
            fixture.close()
            return failed
        }

        data class Expected(
            val failure: Throwable,
            val code: GatewayFailureCode,
            val retryable: Boolean,
            val retryAfterMillis: Long? = null,
        )

        listOf(
            Expected(
                ProviderAuthException(providerMessageCanary),
                GatewayFailureCode.AUTHENTICATION_FAILURE,
                false,
            ),
            Expected(
                ProviderClientException(providerMessageCanary, statusCode = 400),
                GatewayFailureCode.CLIENT_FAILURE,
                false,
            ),
            Expected(
                ProviderProtocolException(providerMessageCanary),
                GatewayFailureCode.PROTOCOL_FAILURE,
                false,
            ),
            Expected(
                ProviderRateLimitException(providerMessageCanary, retryAfterMillis = 1_100),
                GatewayFailureCode.RATE_LIMIT,
                true,
                1_100,
            ),
            Expected(
                ProviderNetworkException(providerMessageCanary),
                GatewayFailureCode.NETWORK_FAILURE,
                true,
            ),
            Expected(
                ProviderTimeoutException(ProviderTimeoutPhase.STREAM_IDLE),
                GatewayFailureCode.TIMEOUT,
                true,
            ),
            Expected(
                ProviderContextLimitException(providerMessageCanary),
                GatewayFailureCode.CONTEXT_LIMIT,
                false,
            ),
            Expected(
                ProviderServerException(providerMessageCanary, statusCode = 503, retryAfterMillis = 2_200),
                GatewayFailureCode.SERVER_FAILURE,
                true,
                2_200,
            ),
            Expected(
                ProviderException(providerMessageCanary),
                GatewayFailureCode.INTERNAL_FAILURE,
                false,
            ),
            Expected(
                ProviderInvocationInvalidatedException(
                    failure = ProviderClientException(
                        providerMessageCanary,
                        statusCode = 409,
                        retryAfterMillis = 3_300,
                    ),
                    retryable = true,
                ),
                GatewayFailureCode.CLIENT_FAILURE,
                true,
                3_300,
            ),
        ).forEach { expected ->
            val actual = failedEvent(expected.failure)
            assertEquals(expected.code, actual.code)
            assertEquals(expected.retryable, actual.retryable)
            assertEquals(expected.retryAfterMillis, actual.retryAfterMillis)
        }
    }

    @Test
    fun hardStreamLifetimeCancelsAHungProviderEvenWhileSubscribed() = runTest {
        val fixture = Fixture(
            scope = this,
            provider = ScriptedProvider(blockAfterDelta = true),
            config = GatewayCoordinatorConfig(
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
        assertFailsWith<GatewayInvocationInvalidatedException> {
            fixture.coordinator.create(USER_A, request())
        }
        assertEquals(1, fixture.provider.calls)
        fixture.close()
    }

    @Test
    fun replayEventLimitStopsUpstreamWithOneBoundedFailureTerminal() = runTest {
        val fixture = Fixture(
            scope = this,
            config = GatewayCoordinatorConfig(
                maxReplayEvents = 2,
                terminalRetentionMillis = 20_000,
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
                terminalRetentionMillis = 20_000,
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
                terminalRetentionMillis = 20_000,
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
        credentialResolver: GatewayProviderCredentialResolver =
            GatewayProviderCredentialResolver { _, _ -> ProviderCredential("server-only-secret") },
        attachmentResolver: GatewayAttachmentResolver = RejectingGatewayAttachmentResolver,
        config: GatewayCoordinatorConfig = GatewayCoordinatorConfig(
            terminalRetentionMillis = 20_000,
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
            credentialResolver = credentialResolver,
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
        private val failureAfterCompleted: Throwable? = null,
    ) : ProviderAdapter {
        override val key: String = "gemini"
        var calls = 0
        var cancellations = 0
        val requests = mutableListOf<ProviderRequest>()

        override suspend fun generate(request: ProviderRequest): Flow<ProviderChunk> = flow {
            calls += 1
            requests += request
            failure?.let { throw it }
            try {
                emit(ProviderChunk(events = listOf(ProviderEvent.TextDelta("hello"))))
                if (blockAfterDelta) awaitCancellation()
                try {
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
                    failureAfterCompleted?.let { throw it }
                }
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
        var cancelled = 0

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

                    override suspend fun cancel() {
                        cancelled += 1
                    }
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
