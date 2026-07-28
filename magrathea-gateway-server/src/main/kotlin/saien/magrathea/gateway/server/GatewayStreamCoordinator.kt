package saien.magrathea.gateway.server

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
import saien.magrathea.core.AgentSessionId
import saien.magrathea.core.EpochClock
import saien.magrathea.core.IdGenerator
import saien.magrathea.core.SystemEpochClock
import saien.magrathea.gateway.protocol.GatewayEvent
import saien.magrathea.gateway.protocol.GatewayFailureCode
import saien.magrathea.gateway.protocol.GatewayGenerationOptions
import saien.magrathea.gateway.protocol.GatewayProtocolCodec
import saien.magrathea.gateway.protocol.GatewayStreamDescriptor
import saien.magrathea.gateway.protocol.GatewayStreamEnvelope
import saien.magrathea.gateway.protocol.GatewayUsage
import saien.magrathea.gateway.protocol.toGatewayEvent
import saien.magrathea.provider.api.ProviderAdapter
import saien.magrathea.provider.api.ProviderContextLimitException
import saien.magrathea.provider.api.ProviderEvent
import saien.magrathea.provider.api.ProviderInvocation
import saien.magrathea.provider.api.ProviderRegistry
import saien.magrathea.provider.api.ProviderRequest
import saien.magrathea.provider.api.ProviderUsage
import saien.magrathea.provider.api.validateSemantics

data class GatewayCoordinatorConfig(
    val maxReplayEvents: Int = 4_096,
    val maxReplayBytes: Int = 16 * 1024 * 1024,
    val reconnectGraceMillis: Long = 30_000,
    val terminalRetentionMillis: Long = 5 * 60_000,
    val streamLifetimeMillis: Long = 10 * 60_000,
) {
    init {
        require(maxReplayEvents >= 2)
        require(maxReplayBytes > 0)
        require(reconnectGraceMillis > 0)
        require(terminalRetentionMillis > 0)
        require(streamLifetimeMillis > 0)
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
    private val createLocks = LinkedHashMap<IdempotencyScope, ScopedCreateLock>()

    suspend fun create(
        principal: GatewayPrincipal,
        request: saien.magrathea.gateway.protocol.GatewayCreateStreamRequest,
    ): GatewayCreateOutcome {
        request.validate()
        val fingerprint = codec.encodeCreateRequest(request)
        val idempotencyScope = IdempotencyScope(principal.subject, principal.tenantId, request.requestId)

        return withScopedCreateLock(idempotencyScope) {
            check(coordinatorJob.isActive) { "Gateway coordinator is closed" }
            var expired: StreamRecord? = null
            val existing = mapMutex.withLock {
                idempotency[idempotencyScope]?.let { existingStreamId ->
                    val record = streams[existingStreamId]
                    if (record != null && record.expiresAtEpochMs > clock.nowEpochMs()) {
                        if (record.fingerprint != fingerprint) throw GatewayIdempotencyConflictException()
                        return@withLock record
                    }
                    idempotency.remove(idempotencyScope)
                    streams.remove(existingStreamId)
                    expired = record
                }
                null
            }
            expired?.job?.cancelAndJoin()
            if (existing != null) {
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
            val provider = providerRegistry.get(resolvedModel.provider)
                ?: throw GatewayAuthorizationException()
            val credential = credentialResolver.resolve(principal, resolvedModel)
            val messages = attachmentResolver.resolve(principal, request.attachments, request.messages)
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
                check(providerJob.start()) { "Gateway coordinator cannot start Provider work" }
                scheduleDisconnectCancellation(record)
                scheduleLifetimeCancellation(record)
                GatewayCreateOutcome(record.descriptor(), created = true)
            } catch (failure: Throwable) {
                createdRecord?.let { record ->
                    record.job?.cancelAndJoin()
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

    suspend fun events(
        principal: GatewayPrincipal,
        streamId: String,
        afterSequence: Long,
    ): Flow<GatewayStreamEnvelope> {
        if (afterSequence < -1) throw GatewayCursorException()
        val record = ownedRecord(principal, streamId)
        record.mutex.withLock {
            val first = record.events.firstOrNull()?.sequence ?: 0
            val last = record.events.lastOrNull()?.sequence ?: -1
            if (afterSequence < first - 1) throw GatewayReplayWindowException()
            if (afterSequence > last) throw GatewayCursorException()
        }
        return flow {
            attach(record)
            try {
                val snapshot = record.mutex.withLock {
                    val pending = record.events.filter { it.sequence > afterSequence }
                    pending to record.terminal
                }
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
            } finally {
                detach(record)
            }
        }
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
        var completedUsage: GatewayUsage? = null
        try {
            var observedEvent = false
            var pendingCompleted: ProviderEvent.Completed? = null
            record.provider.generate(record.providerRequest).collect { chunk ->
                chunk.validateSemantics()
                if (chunk.events.isEmpty()) {
                    throw IllegalStateException("Gateway Provider must emit canonical events")
                }
                chunk.events.forEach { providerEvent ->
                    if (pendingCompleted != null) {
                        throw IllegalStateException("Gateway Provider emitted an event after completion")
                    }
                    observedEvent = true
                    if (providerEvent is ProviderEvent.Completed) {
                        completedUsage = providerEvent.usage?.toGatewayUsage()
                        pendingCompleted = providerEvent
                    } else {
                        val proposed = providerEvent.toGatewayEvent()
                        if (append(record, proposed) != proposed) throw GatewayReplayCapacityException()
                    }
                }
            }
            if (!observedEvent || pendingCompleted == null) {
                throw IllegalStateException("Gateway Provider completed without a terminal event")
            }
            val completedEvent = requireNotNull(pendingCompleted).toGatewayEvent()
            if (!canAppendExactly(record, completedEvent)) throw GatewayReplayCapacityException()
            withContext(NonCancellable) {
                record.quota.complete(completedUsage)
                check(append(record, completedEvent) == completedEvent) {
                    "Gateway terminal capacity changed after reservation"
                }
                safeAudit(record.auditEvent(GatewayAuditAction.STREAM_COMPLETED, usage = completedUsage))
            }
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                if (!record.isTerminal()) runCatching { append(record, GatewayEvent.Cancelled("cancelled")) }
                runCatching { record.quota.cancel() }
                safeAudit(record.auditEvent(GatewayAuditAction.STREAM_CANCELLED))
            }
        } catch (_: GatewayReplayCapacityException) {
            withContext(NonCancellable) {
                if (!record.isTerminal()) runCatching { append(record, replayLimitFailure()) }
                runCatching { record.quota.fail() }
                safeAudit(
                    record.auditEvent(
                        GatewayAuditAction.STREAM_FAILED,
                        failureCode = GatewayFailureCode.PROTOCOL_FAILURE.name,
                    ),
                )
            }
        } catch (_: ProviderContextLimitException) {
            withContext(NonCancellable) {
                if (!record.isTerminal()) {
                    runCatching {
                        append(
                            record,
                            GatewayEvent.Failed(
                                code = GatewayFailureCode.CONTEXT_LIMIT,
                                retryable = false,
                            ),
                        )
                    }
                }
                runCatching { record.quota.fail() }
                safeAudit(
                    record.auditEvent(
                        GatewayAuditAction.STREAM_FAILED,
                        failureCode = GatewayFailureCode.CONTEXT_LIMIT.name,
                    ),
                )
            }
        } catch (_: Throwable) {
            withContext(NonCancellable) {
                if (!record.isTerminal()) {
                    runCatching {
                        append(
                            record,
                            GatewayEvent.Failed(
                                code = GatewayFailureCode.UPSTREAM_FAILURE,
                                retryable = false,
                            ),
                        )
                    }
                }
                runCatching { record.quota.fail() }
                safeAudit(
                    record.auditEvent(
                        GatewayAuditAction.STREAM_FAILED,
                        failureCode = GatewayFailureCode.UPSTREAM_FAILURE.name,
                    ),
                )
            }
        } finally {
            cancelStreamTimers(record)
            scheduleRetention(record)
        }
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

    private suspend fun attach(record: StreamRecord) {
        record.mutex.withLock {
            if (!record.terminal && record.expiresAtEpochMs <= clock.nowEpochMs()) {
                throw GatewayStreamNotFoundException()
            }
            record.disconnectJob?.cancel()
            record.disconnectJob = null
            record.subscribers += 1
        }
    }

    private suspend fun detach(record: StreamRecord) {
        record.mutex.withLock {
            record.subscribers = (record.subscribers - 1).coerceAtLeast(0)
        }
        scheduleDisconnectCancellation(record)
    }

    private suspend fun scheduleDisconnectCancellation(record: StreamRecord) {
        record.mutex.withLock {
            if (record.terminal || record.subscribers > 0 || record.disconnectJob != null) return
            record.disconnectJob = scope.launch {
                delay(config.reconnectGraceMillis)
                val job = record.mutex.withLock {
                    record.disconnectJob = null
                    if (!record.terminal && record.subscribers == 0) record.job else null
                }
                job?.cancelAndJoin()
            }
        }
    }

    private suspend fun scheduleLifetimeCancellation(record: StreamRecord) {
        record.mutex.withLock {
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
        val timers = record.mutex.withLock {
            listOf(record.disconnectJob, record.lifetimeJob).also {
                record.disconnectJob = null
                record.lifetimeJob = null
            }
        }
        timers.forEach { it?.cancel() }
    }

    private fun scheduleRetention(record: StreamRecord) {
        scope.launch {
            delay(config.terminalRetentionMillis)
            mapMutex.withLock {
                if (streams[record.streamId] === record) {
                    streams.remove(record.streamId)
                    idempotency.entries.removeAll { it.value == record.streamId }
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
            // through their own mandatory telemetry rather than leaking them to the Web client.
        }
    }

    private data class IdempotencyScope(
        val subject: String,
        val tenantId: String,
        val requestId: String,
    )

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
        var subscribers = 0
        var job: Job? = null
        var disconnectJob: Job? = null
        var lifetimeJob: Job? = null

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
    }

    private object TerminalCollected : CancellationException()
    private class GatewayReplayCapacityException : RuntimeException()
}

private fun GatewayEvent?.isTerminal(): Boolean =
    this is GatewayEvent.Completed || this is GatewayEvent.Failed || this is GatewayEvent.Cancelled

private fun ProviderUsage.toGatewayUsage() = GatewayUsage(inputTokens, outputTokens, reasoningTokens)
