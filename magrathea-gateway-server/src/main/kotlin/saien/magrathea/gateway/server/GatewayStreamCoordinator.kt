package saien.magrathea.gateway.server

import java.security.MessageDigest
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import saien.magrathea.core.AgentMessage
import saien.magrathea.core.AgentSessionId
import saien.magrathea.core.AttachmentPart
import saien.magrathea.core.EpochClock
import saien.magrathea.core.IdGenerator
import saien.magrathea.core.SystemEpochClock
import saien.magrathea.core.ToolImageAttachmentReference
import saien.magrathea.core.ToolResultImageContent
import saien.magrathea.core.ToolResultPart
import saien.magrathea.core.requireSupports
import saien.magrathea.gateway.protocol.GATEWAY_ATTACHMENT_URI_PREFIX
import saien.magrathea.gateway.protocol.GatewayEvent
import saien.magrathea.gateway.protocol.GatewayFailureCode
import saien.magrathea.gateway.protocol.GatewayGenerationOptions
import saien.magrathea.gateway.protocol.GatewayProtocolCodec
import saien.magrathea.gateway.protocol.GatewayStreamDescriptor
import saien.magrathea.gateway.protocol.GatewayStreamEnvelope
import saien.magrathea.gateway.protocol.GatewayUsage
import saien.magrathea.gateway.protocol.toGatewayEvent
import saien.magrathea.provider.api.ProviderAdapter
import saien.magrathea.provider.api.ProviderAuthException
import saien.magrathea.provider.api.ProviderClientException
import saien.magrathea.provider.api.ProviderContextLimitException
import saien.magrathea.provider.api.ProviderEvent
import saien.magrathea.provider.api.ProviderException
import saien.magrathea.provider.api.ProviderHttpException
import saien.magrathea.provider.api.ProviderInvocation
import saien.magrathea.provider.api.ProviderInvocationInvalidatedException
import saien.magrathea.provider.api.ProviderNetworkException
import saien.magrathea.provider.api.ProviderProtocolException
import saien.magrathea.provider.api.ProviderRateLimitException
import saien.magrathea.provider.api.ProviderRegistry
import saien.magrathea.provider.api.ProviderRequest
import saien.magrathea.provider.api.ProviderServerException
import saien.magrathea.provider.api.ProviderTimeoutException
import saien.magrathea.provider.api.ProviderUsage
import saien.magrathea.provider.api.sanitizedForModelBoundary
import saien.magrathea.provider.api.validateSemantics

data class GatewayCoordinatorConfig(
    val maxReplayEvents: Int = 4_096,
    val maxReplayBytes: Int = 16 * 1024 * 1024,
    val terminalRetentionMillis: Long = 10 * 60_000,
    val idempotencyRetentionMillis: Long = 24 * 60 * 60_000L,
    val streamLifetimeMillis: Long = 10 * 60_000,
) {
    init {
        require(maxReplayEvents >= 2)
        require(maxReplayBytes > 0)
        require(terminalRetentionMillis > 0)
        require(idempotencyRetentionMillis > 0)
        require(streamLifetimeMillis > 0)
        require(terminalRetentionMillis >= streamLifetimeMillis) {
            "Gateway terminal replay retention must cover the advertised stream lifetime"
        }
    }
}

data class GatewayCreateOutcome(
    val descriptor: GatewayStreamDescriptor,
    val created: Boolean,
)

class SecureGatewayIdGenerator : IdGenerator {
    override fun nextId(): String = UUID.randomUUID().toString()
}

class GatewayStreamCoordinator(
    private val providerRegistry: ProviderRegistry,
    private val modelResolver: GatewayModelResolver,
    private val credentialResolver: GatewayProviderCredentialResolver,
    private val attachmentResolver: GatewayAttachmentResolver,
    private val quotaManager: GatewayQuotaManager,
    private val auditSink: GatewayAuditSink,
    parentScope: CoroutineScope,
    private val config: GatewayCoordinatorConfig = GatewayCoordinatorConfig(),
    private val idGenerator: IdGenerator = SecureGatewayIdGenerator(),
    private val clock: EpochClock = SystemEpochClock,
    private val codec: GatewayProtocolCodec = GatewayProtocolCodec(),
) : AutoCloseable {
    private val coordinatorJob = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + coordinatorJob)
    private val mapMutex = Mutex()
    private val streams = LinkedHashMap<String, StreamRecord>()
    private val idempotency = LinkedHashMap<IdempotencyScope, String>()
    private val tombstones = LinkedHashMap<IdempotencyScope, IdempotencyTombstone>()
    private val createLocks = LinkedHashMap<IdempotencyScope, ScopedCreateLock>()

    suspend fun create(
        principal: GatewayPrincipal,
        request: saien.magrathea.gateway.protocol.GatewayCreateStreamRequest,
    ): GatewayCreateOutcome {
        request.validate()
        val fingerprint = requestFingerprint(codec.encodeCreateRequest(request))
        val idempotencyScope = IdempotencyScope(principal.subject, principal.tenantId, request.requestId)

        return withScopedCreateLock(idempotencyScope) {
            check(coordinatorJob.isActive) { "Gateway coordinator is closed" }
            val existing = mapMutex.withLock {
                tombstones[idempotencyScope]?.let { tombstone ->
                    if (tombstone.expiresAtEpochMs <= clock.nowEpochMs()) {
                        tombstones.remove(idempotencyScope)
                    } else if (tombstone.fingerprint != null && tombstone.fingerprint != fingerprint) {
                        throw GatewayIdempotencyConflictException()
                    } else {
                        when (tombstone.disposition) {
                            TombstoneDisposition.RETRYABLE_INVALIDATION ->
                                throw GatewayInvocationInvalidatedException()
                            TombstoneDisposition.REPLAY_UNAVAILABLE ->
                                throw GatewayInvocationReplayUnavailableException()
                        }
                    }
                }
                idempotency[idempotencyScope]?.let { existingStreamId ->
                    val record = streams[existingStreamId]
                    if (record == null) {
                        idempotency.remove(idempotencyScope)
                        return@let
                    }
                    if (record.fingerprint != fingerprint) throw GatewayIdempotencyConflictException()
                    return@withLock record
                }
                null
            }
            if (existing != null) {
                val existingState = existing.mutex.withLock {
                    ExistingStreamState(
                        terminal = existing.terminal,
                        leaseExpired = !existing.terminal &&
                            existing.expiresAtEpochMs <= clock.nowEpochMs(),
                        invalidated = when (val terminal = existing.events.lastOrNull()?.event) {
                            is GatewayEvent.Cancelled -> true
                            is GatewayEvent.Failed -> terminal.retryable
                            else -> false
                        },
                    )
                }
                if (existingState.leaseExpired) {
                    existing.job?.cancelAndJoin()
                    val reusableTerminal = existing.mutex.withLock {
                        when (val terminal = existing.events.lastOrNull()?.event) {
                            is GatewayEvent.Completed -> true
                            is GatewayEvent.Failed -> !terminal.retryable
                            else -> false
                        }
                    }
                    if (!reusableTerminal) throw GatewayInvocationInvalidatedException()
                }
                if (existingState.invalidated) throw GatewayInvocationInvalidatedException()
                if (existingState.terminal) scheduleRetention(existing)
                auditSink.record(existing.auditEvent(GatewayAuditAction.STREAM_REUSED))
                return@withScopedCreateLock GatewayCreateOutcome(existing.descriptor(), created = false)
            }

            val resolvedModel = modelResolver.resolve(principal, request.model)
            if (
                resolvedModel.provider != request.model.provider ||
                resolvedModel.model != request.model.model
            ) {
                throw GatewayAuthorizationException()
            }
            resolvedModel.requireSupports(request.reasoningPreference)
            val provider = providerRegistry.get(resolvedModel.provider)
                ?: throw GatewayAuthorizationException()
            val credential = credentialResolver.resolve(principal, resolvedModel)
            val modelMessages = request.messages.map(AgentMessage::projectForProviderBoundary)
            val modelAttachmentIds = modelMessages.modelAttachmentIds()
            val modelAttachments = request.attachments.filter { it.id in modelAttachmentIds }
            val messages = attachmentResolver.resolve(principal, modelAttachments, modelMessages)
            val quotaReservation = when (val quota = quotaManager.reserve(principal, request)) {
                is GatewayQuotaDecision.Granted -> quota.reservation
                is GatewayQuotaDecision.Denied -> throw GatewayQuotaException(quota.retryAfterMillis)
            }
            var createdRecord: StreamRecord? = null
            try {
                val streamId = allocateStreamId()
                val record = StreamRecord(
                    owner = principal,
                    request = request,
                    fingerprint = fingerprint,
                    streamId = streamId,
                    expiresAtEpochMs = clock.nowEpochMs() + config.streamLifetimeMillis,
                    quota = quotaReservation,
                    provider = provider,
                    providerRequest = ProviderRequest(
                        invocation = ProviderInvocation(
                            requestId = request.requestId,
                            sessionId = AgentSessionId(request.sessionId),
                            turn = request.turn,
                        ),
                        model = resolvedModel,
                        reasoningPreference = request.reasoningPreference,
                        messages = messages,
                        tools = request.tools,
                        credential = credential,
                        temperature = request.options.temperature,
                        maxTokens = request.options.maxTokens,
                    ),
                    live = MutableSharedFlow(replay = config.maxReplayEvents),
                )
                createdRecord = record
                append(record, GatewayEvent.StreamOpened())
                auditSink.record(record.auditEvent(GatewayAuditAction.STREAM_CREATED))
                val providerJob = scope.launch(start = CoroutineStart.LAZY) { runProvider(record) }
                record.job = providerJob
                mapMutex.withLock {
                    streams[streamId] = record
                    idempotency[idempotencyScope] = streamId
                }
                scheduleLifetimeCancellation(record)
                check(providerJob.start()) { "Gateway coordinator cannot start Provider work" }
                GatewayCreateOutcome(record.descriptor(), created = true)
            } catch (failure: Throwable) {
                createdRecord?.let { record ->
                    record.job?.cancelAndJoin()
                    cancelRecordTimers(record)
                    mapMutex.withLock {
                        if (streams[record.streamId] === record) streams.remove(record.streamId)
                        if (idempotency[idempotencyScope] == record.streamId) idempotency.remove(idempotencyScope)
                    }
                }
                runCatching { quotaReservation.fail() }
                throw failure
            }
        }
    }

    suspend fun resolveExisting(
        principal: GatewayPrincipal,
        requestId: String,
    ): GatewayStreamDescriptor {
        require(requestId.isNotBlank()) { "Gateway requestId must not be blank" }
        val scopeKey = IdempotencyScope(principal.subject, principal.tenantId, requestId)
        return withScopedCreateLock(scopeKey) {
            check(coordinatorJob.isActive) { "Gateway coordinator is closed" }
            val record = mapMutex.withLock {
                val tombstone = tombstones[scopeKey]
                if (tombstone != null) {
                    if (tombstone.expiresAtEpochMs <= clock.nowEpochMs()) {
                        throw GatewayInvocationUnknownException()
                    }
                    when (tombstone.disposition) {
                        TombstoneDisposition.RETRYABLE_INVALIDATION ->
                            throw GatewayInvocationInvalidatedException()
                        TombstoneDisposition.REPLAY_UNAVAILABLE ->
                            throw GatewayInvocationReplayUnavailableException()
                    }
                }
                val streamId = idempotency[scopeKey] ?: throw GatewayInvocationUnknownException()
                streams[streamId] ?: throw GatewayInvocationUnknownException()
            }
            val existingState = record.mutex.withLock {
                ExistingStreamState(
                    terminal = record.terminal,
                    leaseExpired = !record.terminal &&
                        record.expiresAtEpochMs <= clock.nowEpochMs(),
                    invalidated = when (val terminal = record.events.lastOrNull()?.event) {
                        is GatewayEvent.Cancelled -> true
                        is GatewayEvent.Failed -> terminal.retryable
                        else -> false
                    },
                )
            }
            if (existingState.leaseExpired) {
                record.job?.cancelAndJoin()
                val reusableTerminal = record.mutex.withLock {
                    when (val terminal = record.events.lastOrNull()?.event) {
                        is GatewayEvent.Completed -> true
                        is GatewayEvent.Failed -> !terminal.retryable
                        else -> false
                    }
                }
                if (!reusableTerminal) throw GatewayInvocationInvalidatedException()
            }
            if (existingState.invalidated) throw GatewayInvocationInvalidatedException()
            if (existingState.terminal) scheduleRetention(record)
            auditSink.record(record.auditEvent(GatewayAuditAction.STREAM_REUSED))
            record.descriptor()
        }
    }

    suspend fun events(
        principal: GatewayPrincipal,
        streamId: String,
        afterSequence: Long,
    ): Flow<GatewayStreamEnvelope> {
        if (afterSequence < -1) throw GatewayCursorException()
        val record = ownedRecord(principal, streamId)
        val snapshot = record.mutex.withLock {
            if (!record.terminal && record.expiresAtEpochMs <= clock.nowEpochMs()) {
                throw GatewayStreamNotFoundException()
            }
            val first = record.events.firstOrNull()?.sequence ?: 0
            val last = record.events.lastOrNull()?.sequence ?: -1
            if (afterSequence < first - 1) throw GatewayReplayWindowException()
            if (afterSequence > last) throw GatewayCursorException()
            record.events.filter { it.sequence > afterSequence } to record.terminal
        }
        return flow {
            var cursor = afterSequence
            snapshot.first.forEach { envelope ->
                emit(envelope)
                cursor = envelope.sequence
            }
            if (snapshot.first.lastOrNull()?.event.isTerminal() || (snapshot.second && cursor >= record.lastSequence())) {
                return@flow
            }
            try {
                record.live.filter { it.sequence > cursor }.collect { envelope ->
                    emit(envelope)
                    cursor = envelope.sequence
                    if (envelope.event.isTerminal()) throw TerminalCollected
                }
            } catch (_: TerminalCollected) { }
        }
    }

    suspend fun abandon(principal: GatewayPrincipal, requestId: String) {
        require(requestId.isNotBlank()) { "Gateway requestId must not be blank" }
        val scopeKey = IdempotencyScope(principal.subject, principal.tenantId, requestId)
        var tombstoneToSchedule: IdempotencyTombstone? = null
        withScopedCreateLock(scopeKey) {
            val record = mapMutex.withLock {
                val streamId = idempotency[scopeKey]
                val existing = streamId?.let(streams::get)
                if (streamId != null && existing == null) idempotency.remove(scopeKey)
                if (existing == null) {
                    val retained = tombstones[scopeKey]
                    if (retained != null && retained.expiresAtEpochMs <= clock.nowEpochMs()) {
                        tombstones.remove(scopeKey)
                    }
                    if (tombstones[scopeKey] == null) {
                        tombstoneToSchedule = IdempotencyTombstone(
                            fingerprint = null,
                            disposition = TombstoneDisposition.RETRYABLE_INVALIDATION,
                            expiresAtEpochMs = clock.nowEpochMs() + config.idempotencyRetentionMillis,
                        ).also { tombstones[scopeKey] = it }
                    }
                }
                existing
            } ?: return@withScopedCreateLock
            val job = record.mutex.withLock {
                if (record.terminal) null else record.job
            }
            job?.cancelAndJoin()
        }
        tombstoneToSchedule?.let { scheduleTombstoneCleanup(scopeKey, it) }
    }

    suspend fun cancel(principal: GatewayPrincipal, streamId: String) {
        val record = ownedRecord(principal, streamId)
        val job = record.mutex.withLock {
            if (record.terminal) null else record.job
        }
        job?.cancelAndJoin()
    }

    override fun close() {
        coordinatorJob.cancel()
    }

    private suspend fun runProvider(record: StreamRecord) {
        try {
            var observedEvent = false
            record.provider.generate(record.providerRequest).collect { chunk ->
                chunk.validateSemantics()
                if (chunk.events.isEmpty()) {
                    throw IllegalStateException("Gateway Provider must emit canonical events")
                }
                chunk.events.forEach { providerEvent ->
                    observedEvent = true
                    if (providerEvent is ProviderEvent.Completed) {
                        val completed = providerEvent.toGatewayEvent()
                        check(completed is GatewayEvent.Completed)
                        settleCompleted(
                            record = record,
                            completed = completed,
                            usage = providerEvent.usage?.toGatewayUsage(),
                        )
                        throw ProviderTerminalCollected
                    } else {
                        val proposed = providerEvent.toGatewayEvent()
                        val selected = append(record, proposed)
                        if (selected != proposed) {
                            settlePublishedFailure(record, selected as GatewayEvent.Failed)
                            throw ProviderTerminalCollected
                        }
                    }
                }
            }
            if (!observedEvent || !record.isTerminal()) {
                throw IllegalStateException("Gateway Provider completed without a terminal event")
            }
        } catch (_: ProviderTerminalCollected) {
            // A semantic Completed is authoritative; collection stops immediately.
        } catch (cancelled: CancellationException) {
            settleCancelled(record)
        } catch (failure: ProviderException) {
            settleFailed(record, failure.toGatewayFailure())
        } catch (_: Throwable) {
            settleFailed(
                record,
                GatewayEvent.Failed(
                    code = GatewayFailureCode.INTERNAL_FAILURE,
                    retryable = false,
                ),
            )
        } finally {
            cancelStreamTimers(record)
            scheduleRetention(record)
        }
    }

    private suspend fun settleCompleted(
        record: StreamRecord,
        completed: GatewayEvent.Completed,
        usage: GatewayUsage?,
    ) = withContext(NonCancellable) {
        if (record.isTerminal()) return@withContext
        if (!canAppendExactly(record, completed)) {
            settleFailed(record, replayLimitFailure())
            return@withContext
        }
        check(append(record, completed) == completed) {
            "Gateway terminal capacity changed after reservation"
        }
        runCatching { record.quota.complete(usage) }
        safeAudit(record.auditEvent(GatewayAuditAction.STREAM_COMPLETED, usage = usage))
    }

    private suspend fun settleCancelled(record: StreamRecord) = withContext(NonCancellable) {
        if (record.isTerminal()) return@withContext
        val cancelled = GatewayEvent.Cancelled("cancelled")
        if (runCatching { append(record, cancelled) }.getOrNull() != cancelled) return@withContext
        runCatching { record.quota.cancel() }
        safeAudit(record.auditEvent(GatewayAuditAction.STREAM_CANCELLED))
    }

    private suspend fun settleFailed(
        record: StreamRecord,
        failure: GatewayEvent.Failed,
    ) = withContext(NonCancellable) {
        if (record.isTerminal()) return@withContext
        val selected = runCatching { append(record, failure) }.getOrNull()
        if (selected !is GatewayEvent.Failed) return@withContext
        runCatching { record.quota.fail() }
        safeAudit(
            record.auditEvent(
                GatewayAuditAction.STREAM_FAILED,
                failureCode = selected.code.name,
            ),
        )
    }

    private suspend fun settlePublishedFailure(
        record: StreamRecord,
        failure: GatewayEvent.Failed,
    ) = withContext(NonCancellable) {
        runCatching { record.quota.fail() }
        safeAudit(
            record.auditEvent(
                GatewayAuditAction.STREAM_FAILED,
                failureCode = failure.code.name,
            ),
        )
    }

    private suspend fun append(record: StreamRecord, proposedEvent: GatewayEvent): GatewayEvent {
        return record.mutex.withLock {
            if (record.terminal) throw IllegalStateException("Gateway stream is already terminal")
            val sequence = record.events.size.toLong()
            val fallback = replayLimitFailure()
            val proposedEnvelope = record.envelope(sequence, proposedEvent)
            val proposedBytes = encodedSize(proposedEnvelope)
            val selectedEvent = when {
                proposedEvent is GatewayEvent.StreamOpened -> {
                    val fallbackBytes = encodedSize(record.envelope(sequence + 1, fallback))
                    check(
                        record.events.size + 2 <= config.maxReplayEvents &&
                            record.encodedBytes + proposedBytes + fallbackBytes <= config.maxReplayBytes,
                    ) { "Gateway replay capacity cannot retain stream_opened and one terminal event" }
                    proposedEvent
                }
                proposedEvent.isTerminal() -> {
                    if (
                        record.events.size < config.maxReplayEvents &&
                        record.encodedBytes + proposedBytes <= config.maxReplayBytes
                    ) {
                        proposedEvent
                    } else {
                        fallback
                    }
                }
                else -> {
                    val fallbackBytes = encodedSize(record.envelope(sequence + 1, fallback))
                    if (
                        record.events.size < config.maxReplayEvents - 1 &&
                        record.encodedBytes + proposedBytes + fallbackBytes <= config.maxReplayBytes
                    ) {
                        proposedEvent
                    } else {
                        fallback
                    }
                }
            }
            val envelope = if (selectedEvent === proposedEvent) {
                proposedEnvelope
            } else {
                record.envelope(sequence, selectedEvent)
            }
            val encodedBytes = if (selectedEvent === proposedEvent) proposedBytes else encodedSize(envelope)
            check(record.events.size < config.maxReplayEvents) { "Gateway replay event capacity was exhausted" }
            check(record.encodedBytes + encodedBytes <= config.maxReplayBytes) {
                "Gateway replay byte capacity was exhausted"
            }
            record.events += envelope
            record.encodedBytes += encodedBytes
            record.terminal = envelope.event.isTerminal()
            record.live.emit(envelope)
            selectedEvent
        }
    }

    private suspend fun canAppendExactly(record: StreamRecord, event: GatewayEvent): Boolean = record.mutex.withLock {
        if (record.terminal || record.events.size >= config.maxReplayEvents) return@withLock false
        val envelope = record.envelope(record.events.size.toLong(), event)
        record.encodedBytes + encodedSize(envelope) <= config.maxReplayBytes
    }

    private fun encodedSize(envelope: GatewayStreamEnvelope): Int =
        codec.encodeEnvelope(envelope).encodeToByteArray().size

    private fun replayLimitFailure() = GatewayEvent.Failed(
        code = GatewayFailureCode.PROTOCOL_FAILURE,
    )

    private suspend fun scheduleLifetimeCancellation(record: StreamRecord) {
        record.mutex.withLock {
            if (record.terminal) return@withLock
            check(record.lifetimeJob == null) { "Gateway stream lifetime was already scheduled" }
            record.lifetimeJob = scope.launch {
                delay(config.streamLifetimeMillis)
                val job = record.mutex.withLock {
                    if (!record.terminal) record.job else null
                }
                job?.cancelAndJoin()
            }
        }
    }

    private suspend fun cancelStreamTimers(record: StreamRecord) {
        val timer = record.mutex.withLock {
            record.lifetimeJob.also {
                record.lifetimeJob = null
            }
        }
        timer?.cancel()
    }

    private suspend fun cancelRecordTimers(record: StreamRecord) {
        val timers = record.mutex.withLock {
            listOfNotNull(record.lifetimeJob, record.retentionJob).also {
                record.lifetimeJob = null
                record.retentionJob = null
                record.retentionGeneration += 1
            }
        }
        timers.forEach(Job::cancel)
    }

    private suspend fun scheduleRetention(record: StreamRecord) {
        val remainingLease = (record.expiresAtEpochMs - clock.nowEpochMs()).coerceAtLeast(0L)
        val delayMillis = maxOf(config.terminalRetentionMillis, remainingLease)
        val generation = record.mutex.withLock {
            record.retentionGeneration += 1
            record.retentionJob?.cancel()
            record.retentionGeneration
        }
        val retentionJob = scope.launch {
            delay(delayMillis)
            val scopeKey = record.idempotencyScope()
            var tombstone: IdempotencyTombstone? = null
            withScopedCreateLock(scopeKey) {
                val current = record.mutex.withLock {
                    record.retentionGeneration == generation
                }
                if (!current) return@withScopedCreateLock
                val disposition = record.tombstoneDisposition()
                mapMutex.withLock {
                    if (streams[record.streamId] === record) {
                        streams.remove(record.streamId)
                        if (idempotency[scopeKey] == record.streamId) idempotency.remove(scopeKey)
                        tombstone = IdempotencyTombstone(
                            fingerprint = record.fingerprint,
                            disposition = disposition,
                            expiresAtEpochMs = clock.nowEpochMs() + config.idempotencyRetentionMillis,
                        ).also { tombstones[scopeKey] = it }
                    }
                }
            }
            tombstone?.let { scheduleTombstoneCleanup(scopeKey, it) }
        }
        record.mutex.withLock {
            if (record.retentionGeneration == generation) {
                record.retentionJob = retentionJob
            } else {
                retentionJob.cancel()
            }
        }
    }

    private fun scheduleTombstoneCleanup(
        scopeKey: IdempotencyScope,
        tombstone: IdempotencyTombstone,
    ) {
        scope.launch {
            delay(config.idempotencyRetentionMillis)
            mapMutex.withLock {
                if (
                    tombstones[scopeKey] === tombstone
                ) {
                    tombstones.remove(scopeKey)
                }
            }
        }
    }

    private suspend fun ownedRecord(principal: GatewayPrincipal, streamId: String): StreamRecord {
        return mapMutex.withLock {
            streams[streamId]?.takeIf { it.owner == principal }
        } ?: throw GatewayStreamNotFoundException()
    }

    private suspend fun allocateStreamId(): String = mapMutex.withLock {
        repeat(8) {
            val candidate = "stream-${idGenerator.nextId()}"
            if (candidate !in streams) return@withLock candidate
        }
        error("Unable to allocate a unique Gateway stream ID")
    }

    private suspend fun <T> withScopedCreateLock(
        scopeKey: IdempotencyScope,
        block: suspend () -> T,
    ): T {
        val entry = mapMutex.withLock {
            createLocks.getOrPut(scopeKey, ::ScopedCreateLock).also { it.references += 1 }
        }
        try {
            return entry.mutex.withLock { block() }
        } finally {
            mapMutex.withLock {
                entry.references -= 1
                if (entry.references == 0 && createLocks[scopeKey] === entry) createLocks.remove(scopeKey)
            }
        }
    }

    private suspend fun safeAudit(event: GatewayAuditEvent) {
        try {
            auditSink.record(event)
        } catch (_: Throwable) {
            // The stream already has a terminal result. Deployments should surface sink failures
            // through their own operational monitoring rather than leaking them to the Web client.
        }
    }

    private data class IdempotencyScope(
        val subject: String,
        val tenantId: String,
        val requestId: String,
    )

    private data class ExistingStreamState(
        val terminal: Boolean,
        val leaseExpired: Boolean,
        val invalidated: Boolean,
    )

    private data class IdempotencyTombstone(
        /** Null only when request-scoped abandonment wins the race before create supplies a body. */
        val fingerprint: String?,
        val disposition: TombstoneDisposition,
        val expiresAtEpochMs: Long,
    )

    private enum class TombstoneDisposition {
        RETRYABLE_INVALIDATION,
        REPLAY_UNAVAILABLE,
    }

    private class ScopedCreateLock {
        val mutex = Mutex()
        var references: Int = 0
    }

    private class StreamRecord(
        val owner: GatewayPrincipal,
        val request: saien.magrathea.gateway.protocol.GatewayCreateStreamRequest,
        val fingerprint: String,
        val streamId: String,
        val expiresAtEpochMs: Long,
        val quota: GatewayQuotaReservation,
        val provider: ProviderAdapter,
        val providerRequest: ProviderRequest,
        val live: MutableSharedFlow<GatewayStreamEnvelope>,
    ) {
        val mutex = Mutex()
        val events = mutableListOf<GatewayStreamEnvelope>()
        var encodedBytes = 0
        var terminal = false
        var job: Job? = null
        var lifetimeJob: Job? = null
        var retentionJob: Job? = null
        var retentionGeneration: Long = 0

        fun descriptor() = GatewayStreamDescriptor(
            streamId = streamId,
            requestId = request.requestId,
            sessionId = request.sessionId,
            expiresAtEpochMs = expiresAtEpochMs,
        )

        fun envelope(sequence: Long, event: GatewayEvent) = GatewayStreamEnvelope(
            streamId = streamId,
            requestId = request.requestId,
            sessionId = request.sessionId,
            sequence = sequence,
            event = event,
        )

        fun auditEvent(
            action: GatewayAuditAction,
            usage: GatewayUsage? = null,
            failureCode: String? = null,
        ) = GatewayAuditEvent(
            action = action,
            subject = owner.subject,
            tenantId = owner.tenantId,
            requestId = request.requestId,
            streamId = streamId,
            sessionId = request.sessionId,
            provider = request.model.provider,
            model = request.model.model,
            usage = usage,
            failureCode = failureCode,
        )

        suspend fun isTerminal(): Boolean = mutex.withLock { terminal }
        suspend fun lastSequence(): Long = mutex.withLock { events.lastOrNull()?.sequence ?: -1 }

        suspend fun tombstoneDisposition(): TombstoneDisposition = mutex.withLock {
            when (val terminalEvent = events.lastOrNull()?.event) {
                is GatewayEvent.Completed -> TombstoneDisposition.REPLAY_UNAVAILABLE
                is GatewayEvent.Failed -> if (terminalEvent.retryable) {
                    TombstoneDisposition.RETRYABLE_INVALIDATION
                } else {
                    TombstoneDisposition.REPLAY_UNAVAILABLE
                }
                is GatewayEvent.Cancelled -> TombstoneDisposition.RETRYABLE_INVALIDATION
                else -> error("Gateway retention requires a terminal event")
            }
        }

        fun idempotencyScope() = IdempotencyScope(owner.subject, owner.tenantId, request.requestId)
    }

    private object TerminalCollected : CancellationException()
    private object ProviderTerminalCollected : RuntimeException()
}

private fun ProviderException.toGatewayFailure(): GatewayEvent.Failed {
    val canonical = canonicalFailure()
    val code = when (canonical) {
        is ProviderContextLimitException -> GatewayFailureCode.CONTEXT_LIMIT
        is ProviderAuthException -> GatewayFailureCode.AUTHENTICATION_FAILURE
        is ProviderRateLimitException -> GatewayFailureCode.RATE_LIMIT
        is ProviderTimeoutException -> GatewayFailureCode.TIMEOUT
        is ProviderNetworkException -> GatewayFailureCode.NETWORK_FAILURE
        is ProviderProtocolException -> GatewayFailureCode.PROTOCOL_FAILURE
        is ProviderClientException -> GatewayFailureCode.CLIENT_FAILURE
        is ProviderServerException -> GatewayFailureCode.SERVER_FAILURE
        else -> GatewayFailureCode.INTERNAL_FAILURE
    }
    val canRetryPhysicalInvocation = code != GatewayFailureCode.CONTEXT_LIMIT && retryable
    val retryAfterMillis = if (canRetryPhysicalInvocation) {
        (canonical as? ProviderHttpException)?.retryAfterMillis
    } else {
        null
    }
    return GatewayEvent.Failed(
        code = code,
        retryable = canRetryPhysicalInvocation,
        retryAfterMillis = retryAfterMillis,
    )
}

private tailrec fun ProviderException.canonicalFailure(): ProviderException =
    if (this is ProviderInvocationInvalidatedException) failure.canonicalFailure() else this

private fun GatewayEvent?.isTerminal(): Boolean =
    this is GatewayEvent.Completed || this is GatewayEvent.Failed || this is GatewayEvent.Cancelled

private fun ProviderUsage.toGatewayUsage() = GatewayUsage(inputTokens, outputTokens, reasoningTokens)

private fun AgentMessage.projectForProviderBoundary(): AgentMessage = copy(
    parts = parts.map { part ->
        if (part is ToolResultPart) part.sanitizedForModelBoundary() else part
    },
)

private fun List<AgentMessage>.modelAttachmentIds(): Set<String> = flatMap { message ->
    message.parts.flatMap { part ->
        when (part) {
            is AttachmentPart -> listOfNotNull(
                part.uri.takeIf { it.startsWith(GATEWAY_ATTACHMENT_URI_PREFIX) }
                    ?.removePrefix(GATEWAY_ATTACHMENT_URI_PREFIX),
            )
            is ToolResultPart -> part.content.mapNotNull { content ->
                val image = content as? ToolResultImageContent ?: return@mapNotNull null
                val source = image.source as? ToolImageAttachmentReference ?: return@mapNotNull null
                source.uri.takeIf { it.startsWith(GATEWAY_ATTACHMENT_URI_PREFIX) }
                    ?.removePrefix(GATEWAY_ATTACHMENT_URI_PREFIX)
            }
            else -> emptyList()
        }
    }
}.toSet()

private fun requestFingerprint(encodedRequest: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(encodedRequest.encodeToByteArray())
        .joinToString(separator = "") { byte -> "%02x".format(byte) }
