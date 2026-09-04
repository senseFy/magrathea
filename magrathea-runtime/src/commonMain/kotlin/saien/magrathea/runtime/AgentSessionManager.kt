package saien.magrathea.runtime

import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import saien.magrathea.core.AgentEvent
import saien.magrathea.core.AgentFailureCode
import saien.magrathea.core.AgentPersistence
import saien.magrathea.core.AgentRecoveryDisposition
import saien.magrathea.core.AgentRecoveryInfo
import saien.magrathea.core.AgentRequest
import saien.magrathea.core.AgentRunId
import saien.magrathea.core.AgentRunner
import saien.magrathea.core.AgentSessionId
import saien.magrathea.core.AgentSessionSnapshot
import saien.magrathea.core.AgentStateSnapshot
import saien.magrathea.core.AgentStatus

/** Process-local lifecycle of one canonical live Agent session runtime. */
enum class AgentSessionPhase {
    NEW,
    INACTIVE,
    ACTIVE,
    RESUMABLE,
    RECOVERY_BLOCKED,
    TERMINAL,
    CLOSED,
    DELETED,
}

/**
 * Full live projection for one managed Agent session.
 *
 * [state] may contain provisional streamed output. It is not a durable replacement for
 * [AgentSessionSnapshot]. Consumers that attach late rebuild from this value rather than replaying
 * edge events.
 */
data class AgentSessionRuntimeSnapshot(
    val revision: Long,
    val sessionId: AgentSessionId,
    val request: AgentRequest? = null,
    val runId: AgentRunId? = null,
    val state: AgentStateSnapshot? = null,
    val phase: AgentSessionPhase = AgentSessionPhase.NEW,
    val recovery: AgentRecoveryInfo? = null,
    val failure: AgentFailureCode? = null,
    val lastEvent: AgentEvent? = null,
) {
    val isExecuting: Boolean
        get() = phase == AgentSessionPhase.ACTIVE
}

enum class AgentSessionErrorCode {
    NOT_FOUND,
    ALREADY_EXISTS,
    BUSY,
    CLOSED,
    DELETED,
    DETACHED,
    INVALID_STATE,
    STORAGE,
}

/** Canonical runtime lifetime invalidated before an operation reported its failure. */
enum class AgentSessionInvalidationScope {
    NONE,
    SESSION,
    ALL_SESSIONS,
}

class AgentSessionException(
    val code: AgentSessionErrorCode,
    message: String?,
    cause: Throwable?,
    /** Machine-readable destructive effect; it does not claim that persistence was removed. */
    val invalidationScope: AgentSessionInvalidationScope,
) : Exception(message ?: code.name, cause) {
    constructor(
        code: AgentSessionErrorCode,
        message: String? = null,
        cause: Throwable? = null,
    ) : this(code, message, cause, AgentSessionInvalidationScope.NONE)
}

/**
 * One independently releasable attachment to a canonical live Agent session.
 *
 * Releasing a lease only detaches its caller. It never cancels or interrupts execution. When the
 * last lease leaves an active session, the manager retains the runtime until that execution reaches
 * a stable state.
 */
interface AgentSessionLease {
    val sessionId: AgentSessionId
    val state: StateFlow<AgentSessionRuntimeSnapshot>

    /** Best-effort, non-replay edge events. Slow collectors may miss events; use [state] as truth. */
    val events: SharedFlow<AgentEvent>
    /** True until this specific lease is released; it does not imply that its manager is open. */
    val isAttached: Boolean

    /** Starts a new run after the manager-owned collector has entered upstream collection. */
    @Throws(AgentSessionException::class, CancellationException::class)
    suspend fun start(request: AgentRequest)

    /** Starts recovery and returns after the manager-owned collector is registered. */
    @Throws(AgentSessionException::class, CancellationException::class)
    suspend fun resume()

    /** Interrupts the run and returns after the manager-owned collector has settled. */
    @Throws(AgentSessionException::class, CancellationException::class)
    suspend fun interrupt(): AgentRecoveryInfo

    @Throws(AgentSessionException::class, CancellationException::class)
    suspend fun inspectRecovery(): AgentRecoveryInfo

    /** Cancels pending work and returns after any manager-owned collector has settled. */
    @Throws(AgentSessionException::class, CancellationException::class)
    suspend fun cancel()

    @Throws(AgentSessionException::class, CancellationException::class)
    suspend fun replaceIdleRequest(request: AgentRequest)

    @Throws(AgentSessionException::class, CancellationException::class)
    suspend fun awaitIdle()

    @Throws(AgentSessionException::class, CancellationException::class)
    suspend fun release()
}

/**
 * Process-local owner and canonicalizer for live Agent session runtimes.
 *
 * Catalog reads and destructive mutations are exposed here so they fence live runtimes. The
 * manager is not a platform lifecycle or retention policy: applications explicitly own one
 * manager per runtime root and decide which UI, service, or remote host may acquire leases.
 */
interface AgentSessionManager {
    val liveSessionIds: StateFlow<Set<AgentSessionId>>

    @Throws(AgentSessionException::class, CancellationException::class)
    suspend fun create(sessionId: AgentSessionId = AgentSessionId.create()): AgentSessionLease

    @Throws(AgentSessionException::class, CancellationException::class)
    suspend fun acquire(sessionId: AgentSessionId): AgentSessionLease

    /** Returns a persistence snapshot; concurrent delete/clear may change the next read. */
    @Throws(AgentSessionException::class, CancellationException::class)
    suspend fun listSessions(): List<AgentSessionSnapshot>

    /**
     * Fences the current canonical lifetime before shutdown and persistence deletion.
     *
     * Once that fence is installed, old leases remain deleted and the lifetime is removed even if
     * shutdown or storage deletion fails. Such a non-cancellation failure reports
     * [AgentSessionInvalidationScope.SESSION]; persistence may still contain a restorable record.
     */
    @Throws(AgentSessionException::class, CancellationException::class)
    suspend fun delete(sessionId: AgentSessionId)

    /**
     * Fences every current canonical lifetime before shutdown and persistence clearing.
     *
     * A non-cancellation failure after that fence reports
     * [AgentSessionInvalidationScope.ALL_SESSIONS]; persistence may still contain restorable
     * records, but no old lease becomes valid again.
     */
    @Throws(AgentSessionException::class, CancellationException::class)
    suspend fun clear()

    @Throws(AgentSessionException::class, CancellationException::class)
    suspend fun close()
}

/**
 * Default KMP implementation of [AgentSessionManager].
 *
 * [runner], [persistence], and their synchronous delegates must not await lifecycle or catalog
 * calls back into this manager. Reverse lifecycle actions must be scheduled after the adapter call
 * returns; otherwise the adapter would wait on the operation fence that currently owns its call.
 */
class DefaultAgentSessionManager(
    private val runner: AgentRunner,
    private val persistence: AgentPersistence,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : AgentSessionManager {
    private val managerJob = SupervisorJob()
    private val scope = CoroutineScope(managerJob + dispatcher)
    private val mutex = Mutex()
    private val slots = LinkedHashMap<String, SessionSlot>()
    private val mutableLiveSessionIds = MutableStateFlow<Set<AgentSessionId>>(emptySet())
    private val closeCompletion = CompletableDeferred<Result<Unit>>()
    private var clearing: CompletableDeferred<Unit>? = null
    private var nextGeneration = 0L
    private var catalogReadCount = 0
    private var catalogReadsSettled = CompletableDeferred(Unit)
    private var closed = false

    override val liveSessionIds: StateFlow<Set<AgentSessionId>> =
        mutableLiveSessionIds.asStateFlow()

    override suspend fun create(sessionId: AgentSessionId): AgentSessionLease =
        obtain(sessionId, OpenMode.CREATE)

    override suspend fun acquire(sessionId: AgentSessionId): AgentSessionLease =
        obtain(sessionId, OpenMode.RESTORE)

    override suspend fun listSessions(): List<AgentSessionSnapshot> {
        beginCatalogRead()
        return try {
            storageOperation { persistence.listSessions() }
        } finally {
            withContext(NonCancellable) { endCatalogRead() }
        }
    }

    override suspend fun delete(sessionId: AgentSessionId) {
        requireSessionId(sessionId)
        while (true) {
            var waitFor: CompletableDeferred<Unit>? = null
            var deletion: DeletingSlot? = null
            var runtime: ManagedAgentSession? = null
            mutex.withLock {
                ensureOpenLocked()
                clearing?.let {
                    waitFor = it
                    return@withLock
                }
                when (val slot = slots[sessionId.value]) {
                    is OpeningSlot -> waitFor = slot.settled
                    is ClosingSlot -> waitFor = slot.settled
                    is DeletingSlot -> waitFor = slot.settled
                    is LiveSlot -> {
                        val claimed = DeletingSlot(
                            generation = slot.generation,
                            operationOwner = slot,
                        )
                        slots[sessionId.value] = claimed
                        deletion = claimed
                        runtime = slot.runtime
                        publishLiveSessionIdsLocked()
                    }
                    null -> {
                        val claimed = DeletingSlot(allocateGenerationLocked())
                        slots[sessionId.value] = claimed
                        deletion = claimed
                    }
                }
            }
            waitFor?.let {
                it.await()
                continue
            }
            val claimed = checkNotNull(deletion)
            val outcome = withContext(NonCancellable) {
                runCatching {
                    claimed.operationsSettled.await()
                    runtime?.shutdown(SessionShutdown.DELETE)
                    storageOperation { persistence.deleteSession(sessionId) }
                }.also {
                    mutex.withLock {
                        if (slots[sessionId.value] === claimed) slots.remove(sessionId.value)
                        publishLiveSessionIdsLocked()
                    }
                    claimed.settled.complete(Unit)
                }
            }
            outcome.exceptionOrNull()?.let { failure ->
                throw failure.withInvalidation(AgentSessionInvalidationScope.SESSION)
            }
            return
        }
    }

    override suspend fun clear() {
        while (true) {
            var waitFor: CompletableDeferred<Unit>? = null
            var clearGate: CompletableDeferred<Unit>? = null
            var runtimes: List<ManagedAgentSession> = emptyList()
            var operationGates: List<CompletableDeferred<Unit>> = emptyList()
            mutex.withLock {
                ensureOpenLocked()
                clearing?.let {
                    waitFor = it
                    return@withLock
                }
                slots.values.firstOrNull { it !is LiveSlot }?.let { transient ->
                    waitFor = transient.settlement
                    return@withLock
                }
                val gate = CompletableDeferred<Unit>()
                clearing = gate
                clearGate = gate
                val liveSlots = slots.values.filterIsInstance<LiveSlot>()
                runtimes = liveSlots.map(LiveSlot::runtime)
                operationGates = liveSlots.map(LiveSlot::operationsSettled)
                liveSlots.forEach { live ->
                    slots[live.runtime.sessionId.value] = DeletingSlot(
                        generation = live.generation,
                        operationOwner = live,
                    )
                }
                publishLiveSessionIdsLocked()
            }
            waitFor?.let {
                it.await()
                continue
            }
            val gate = checkNotNull(clearGate)
            val outcome = withContext(NonCancellable) {
                runCatching {
                    var failure: Throwable? = null
                    operationGates.forEach { gate ->
                        try {
                            gate.await()
                        } catch (error: Throwable) {
                            if (failure == null) failure = error
                        }
                    }
                    runtimes.forEach { runtime ->
                        try {
                            runtime.shutdown(SessionShutdown.DELETE)
                        } catch (error: Throwable) {
                            if (failure == null) failure = error
                        }
                    }
                    try {
                        storageOperation { persistence.clear() }
                    } catch (error: Throwable) {
                        if (failure == null) failure = error
                    }
                    failure?.let { throw it }
                }.also {
                    mutex.withLock {
                        if (clearing === gate) {
                            clearing = null
                            slots.values.filterIsInstance<DeletingSlot>()
                                .forEach { it.settled.complete(Unit) }
                            slots.clear()
                            publishLiveSessionIdsLocked()
                        }
                    }
                    gate.complete(Unit)
                }
            }
            outcome.exceptionOrNull()?.let { failure ->
                throw failure.withInvalidation(AgentSessionInvalidationScope.ALL_SESSIONS)
            }
            return
        }
    }

    override suspend fun close() {
        var ownsClose = false
        var runtimes: List<ManagedAgentSession> = emptyList()
        var pending: List<CompletableDeferred<Unit>> = emptyList()
        mutex.withLock {
            if (!closed) {
                closed = true
                ownsClose = true
                runtimes = slots.values.filterIsInstance<LiveSlot>().map(LiveSlot::runtime)
                pending = slots.values.mapNotNull { slot ->
                    slot.settlement.takeUnless { it.isCompleted }
                } + slots.values.filterIsInstance<LiveSlot>().mapNotNull { slot ->
                    slot.operationsSettled.takeUnless { it.isCompleted }
                } + listOfNotNull(
                    clearing?.takeUnless { it.isCompleted },
                    catalogReadsSettled.takeUnless { it.isCompleted },
                )
                mutableLiveSessionIds.value = emptySet()
            }
        }
        if (!ownsClose) {
            closeCompletion.await().getOrThrow()
            return
        }

        withContext(NonCancellable) {
            val outcome = runCatching {
                var failure: Throwable? = null
                pending.forEach { gate ->
                    try {
                        gate.await()
                    } catch (error: Throwable) {
                        if (failure == null) failure = error
                    }
                }
                runtimes.forEach { runtime ->
                    try {
                        runtime.shutdown(SessionShutdown.INTERRUPT)
                    } catch (error: Throwable) {
                        if (failure == null) failure = error
                    }
                }
                mutex.withLock {
                    slots.clear()
                    clearing = null
                    mutableLiveSessionIds.value = emptySet()
                }
                managerJob.cancelAndJoin()
                failure?.let { throw it }
                Unit
            }
            closeCompletion.complete(outcome)
            outcome.getOrThrow()
        }
    }

    private suspend fun obtain(
        sessionId: AgentSessionId,
        mode: OpenMode,
    ): AgentSessionLease {
        requireSessionId(sessionId)
        while (true) {
            var waitFor: CompletableDeferred<Unit>? = null
            var opening: OpeningSlot? = null
            var acquired: LiveSlot? = null
            mutex.withLock {
                ensureOpenLocked()
                clearing?.let {
                    waitFor = it
                    return@withLock
                }
                when (val slot = slots[sessionId.value]) {
                    is LiveSlot -> {
                        if (mode == OpenMode.CREATE) {
                            throw AgentSessionException(
                                AgentSessionErrorCode.ALREADY_EXISTS,
                                "Agent session ${sessionId.value} already exists",
                            )
                        }
                        slot.leaseCount += 1
                        beginLiveOperationLocked(slot)
                        acquired = slot
                    }
                    is OpeningSlot -> waitFor = slot.settled
                    is ClosingSlot -> waitFor = slot.settled
                    is DeletingSlot -> waitFor = slot.settled
                    null -> {
                        val claimed = OpeningSlot(
                            generation = allocateGenerationLocked(),
                            mode = mode,
                        )
                        slots[sessionId.value] = claimed
                        opening = claimed
                    }
                }
            }
            acquired?.let { live ->
                return handoffLease(live.runtime, live.generation)
            }
            waitFor?.let {
                it.await()
                continue
            }

            val claimed = checkNotNull(opening)
            val runtime = try {
                val seed = when (claimed.mode) {
                    OpenMode.CREATE -> validateCreate(sessionId)
                    OpenMode.RESTORE -> loadRestoreSeed(sessionId)
                }
                ManagedAgentSession(
                    runner = runner,
                    persistence = persistence,
                    sessionId = sessionId,
                    parentJob = managerJob,
                    dispatcher = dispatcher,
                    seed = seed,
                    onDisposable = { settled -> scope.launchDispose(settled) },
                )
            } catch (failure: Throwable) {
                withContext(NonCancellable) {
                    mutex.withLock {
                        if (slots[sessionId.value] === claimed) slots.remove(sessionId.value)
                    }
                    claimed.settled.complete(Unit)
                }
                throw failure
            }

            val installed = withContext(NonCancellable) {
                var didInstall = false
                mutex.withLock {
                    if (!closed && clearing == null && slots[sessionId.value] === claimed) {
                        val live = LiveSlot(
                            generation = claimed.generation,
                            runtime = runtime,
                            leaseCount = 1,
                        )
                        beginLiveOperationLocked(live)
                        slots[sessionId.value] = live
                        didInstall = true
                        publishLiveSessionIdsLocked()
                    } else if (slots[sessionId.value] === claimed) {
                        slots.remove(sessionId.value)
                    }
                }
                if (!didInstall) runtime.shutdown(SessionShutdown.NONE)
                claimed.settled.complete(Unit)
                didInstall
            }
            if (installed) {
                return handoffLease(runtime, claimed.generation)
            }
            mutex.withLock { ensureOpenLocked() }
        }
    }

    private suspend fun handoffLease(
        runtime: ManagedAgentSession,
        generation: Long,
    ): AgentSessionLease = try {
        suspendCancellableCoroutine { continuation ->
            val lease = DefaultAgentSessionLease(
                manager = this,
                runtime = runtime,
                generation = generation,
            )
            continuation.resume(lease) { _, rejectedLease, _ ->
                scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    rejectedLease.release()
                }
            }
        }
    } finally {
        withContext(NonCancellable) { endLeaseOperation(runtime, generation) }
    }

    private suspend fun beginCatalogRead() {
        mutex.withLock {
            ensureOpenLocked()
            if (catalogReadCount == 0) catalogReadsSettled = CompletableDeferred()
            catalogReadCount += 1
        }
    }

    private suspend fun endCatalogRead() {
        var settled: CompletableDeferred<Unit>? = null
        mutex.withLock {
            check(catalogReadCount > 0) { "Agent session catalog read count underflow" }
            catalogReadCount -= 1
            if (catalogReadCount == 0) settled = catalogReadsSettled
        }
        settled?.complete(Unit)
    }

    private suspend fun validateCreate(sessionId: AgentSessionId): SessionSeed? {
        val existing = storageOperation { persistence.load(sessionId) }
        if (existing != null) {
            throw AgentSessionException(
                AgentSessionErrorCode.ALREADY_EXISTS,
                "Agent session ${sessionId.value} already exists",
            )
        }
        val recovery = controlOperation { runner.inspectRecovery(sessionId) }
        if (recovery.disposition == AgentRecoveryDisposition.ACTIVE) {
            throw AgentSessionException(
                AgentSessionErrorCode.BUSY,
                "Agent session ${sessionId.value} is active outside this manager",
            )
        }
        if (recovery.disposition != AgentRecoveryDisposition.NOT_FOUND) {
            throw AgentSessionException(
                AgentSessionErrorCode.ALREADY_EXISTS,
                "Agent session ${sessionId.value} already has recoverable state",
            )
        }
        return null
    }

    private suspend fun loadRestoreSeed(sessionId: AgentSessionId): SessionSeed {
        repeat(RESTORE_READ_ATTEMPTS) { attempt ->
            val record = storageOperation { persistence.load(sessionId) }
                ?: throw AgentSessionException(
                    AgentSessionErrorCode.NOT_FOUND,
                    "Agent session ${sessionId.value} was not found",
                )
            if (record.snapshot.sessionId != sessionId) {
                throw AgentSessionException(
                    AgentSessionErrorCode.STORAGE,
                    "Persistence returned a different Agent session for ${sessionId.value}",
                )
            }
            val recovery = controlOperation { runner.inspectRecovery(sessionId) }
            if (recovery.sessionId != sessionId) {
                throw AgentSessionException(
                    AgentSessionErrorCode.INVALID_STATE,
                    "Runner inspected a different Agent session",
                )
            }
            when (recovery.disposition) {
                AgentRecoveryDisposition.ACTIVE -> throw AgentSessionException(
                    AgentSessionErrorCode.BUSY,
                    "Agent session ${sessionId.value} is active outside this manager",
                )
                AgentRecoveryDisposition.NOT_FOUND -> if (
                    record.snapshot.state.status in RECOVERABLE_STORED_STATUSES
                ) {
                    throw AgentSessionException(
                        AgentSessionErrorCode.NOT_FOUND,
                        "Agent session ${sessionId.value} has no recoverable execution",
                    )
                }
                AgentRecoveryDisposition.RESUMABLE,
                AgentRecoveryDisposition.BLOCKED,
                AgentRecoveryDisposition.TERMINAL,
                -> Unit
            }
            val refreshed = storageOperation { persistence.load(sessionId) }
                ?: throw AgentSessionException(
                    AgentSessionErrorCode.NOT_FOUND,
                    "Agent session ${sessionId.value} was not found",
                )
            if (
                refreshed == record &&
                (
                    recovery.disposition == AgentRecoveryDisposition.NOT_FOUND ||
                        recovery.runId == record.snapshot.runId
                )
            ) {
                return SessionSeed(
                    snapshot = record.snapshot,
                    recovery = recovery.takeUnless {
                        it.disposition == AgentRecoveryDisposition.NOT_FOUND
                    },
                )
            }
            if (attempt == RESTORE_READ_ATTEMPTS - 1) {
                throw AgentSessionException(
                    AgentSessionErrorCode.BUSY,
                    "Agent session ${sessionId.value} changed while it was being restored",
                )
            }
        }
        error("Restore attempts must return or throw")
    }

    internal suspend fun <T> withLeaseOperation(
        runtimeToken: Any,
        generation: Long,
        operation: suspend () -> T,
    ): T {
        val runtime = runtimeToken as? ManagedAgentSession
            ?: throw AgentSessionException(AgentSessionErrorCode.CLOSED)
        mutex.withLock {
            ensureOpenLocked()
            val live = slots[runtime.sessionId.value] as? LiveSlot
            if (live?.generation != generation || live.runtime !== runtime) {
                val deleting = slots[runtime.sessionId.value] is DeletingSlot
                throw AgentSessionException(
                    if (deleting || runtime.state.value.phase == AgentSessionPhase.DELETED) {
                        AgentSessionErrorCode.DELETED
                    } else {
                        AgentSessionErrorCode.CLOSED
                    },
                )
            }
            beginLiveOperationLocked(live)
        }
        return try {
            operation()
        } finally {
            withContext(NonCancellable) { endLeaseOperation(runtime, generation) }
        }
    }

    internal suspend fun validateLease(
        runtimeToken: Any,
        generation: Long,
    ) {
        val runtime = runtimeToken as? ManagedAgentSession
            ?: throw AgentSessionException(AgentSessionErrorCode.CLOSED)
        mutex.withLock {
            ensureOpenLocked()
            val live = slots[runtime.sessionId.value] as? LiveSlot
            if (live?.generation != generation || live.runtime !== runtime) {
                throw AgentSessionException(
                    if (
                        slots[runtime.sessionId.value] is DeletingSlot ||
                        runtime.state.value.phase == AgentSessionPhase.DELETED
                    ) {
                        AgentSessionErrorCode.DELETED
                    } else {
                        AgentSessionErrorCode.CLOSED
                    },
                )
            }
        }
    }

    private suspend fun endLeaseOperation(
        runtime: ManagedAgentSession,
        generation: Long,
    ) {
        var settled: CompletableDeferred<Unit>? = null
        mutex.withLock {
            val slot = slots[runtime.sessionId.value]
            val live = slot as? LiveSlot
            if (live?.generation == generation && live.runtime === runtime) {
                check(live.operationCount > 0) { "Agent session operation count underflow" }
                live.operationCount -= 1
                if (live.operationCount == 0) settled = live.operationsSettled
            } else if (slot is DeletingSlot && slot.generation == generation) {
                val owner = slot.operationOwner
                if (owner != null) {
                    check(owner.operationCount > 0) { "Agent session operation count underflow" }
                    owner.operationCount -= 1
                    if (owner.operationCount == 0) settled = owner.operationsSettled
                }
            }
        }
        settled?.complete(Unit)
    }

    private fun beginLiveOperationLocked(live: LiveSlot) {
        if (live.operationCount == 0) live.operationsSettled = CompletableDeferred()
        live.operationCount += 1
    }

    internal suspend fun releaseLease(
        runtimeToken: Any,
        generation: Long,
    ) {
        val runtime = runtimeToken as? ManagedAgentSession ?: return
        var becameUnleased = false
        mutex.withLock {
            val live = slots[runtime.sessionId.value] as? LiveSlot ?: return@withLock
            if (live.generation != generation || live.runtime !== runtime) return@withLock
            check(live.leaseCount > 0) { "Agent session lease count underflow" }
            live.leaseCount -= 1
            becameUnleased = live.leaseCount == 0
        }
        if (becameUnleased) disposeIfUnleased(runtime)
    }

    private fun CoroutineScope.launchDispose(runtime: ManagedAgentSession) {
        launch { disposeIfUnleased(runtime) }
    }

    private suspend fun disposeIfUnleased(runtime: ManagedAgentSession) {
        if (!runtime.isDisposable()) return
        var closing: ClosingSlot? = null
        mutex.withLock {
            if (closed || clearing != null) return
            val live = slots[runtime.sessionId.value] as? LiveSlot ?: return
            if (live.runtime !== runtime || live.leaseCount != 0) return
            val claimed = ClosingSlot(live.generation)
            slots[runtime.sessionId.value] = claimed
            closing = claimed
            publishLiveSessionIdsLocked()
        }
        val claimed = checkNotNull(closing)
        try {
            runtime.shutdown(SessionShutdown.NONE)
        } finally {
            mutex.withLock {
                if (slots[runtime.sessionId.value] === claimed) slots.remove(runtime.sessionId.value)
                publishLiveSessionIdsLocked()
            }
            claimed.settled.complete(Unit)
        }
    }

    private fun allocateGenerationLocked(): Long {
        check(nextGeneration != Long.MAX_VALUE) { "Agent session generations are exhausted" }
        return ++nextGeneration
    }

    private fun publishLiveSessionIdsLocked() {
        mutableLiveSessionIds.value = slots.values
            .filterIsInstance<LiveSlot>()
            .mapTo(linkedSetOf()) { slot -> slot.runtime.sessionId }
    }

    private fun ensureOpenLocked() {
        if (closed) throw AgentSessionException(AgentSessionErrorCode.CLOSED)
    }
}

private class ManagedAgentSession(
    private val runner: AgentRunner,
    private val persistence: AgentPersistence,
    val sessionId: AgentSessionId,
    parentJob: Job,
    dispatcher: CoroutineDispatcher,
    seed: SessionSeed?,
    private val onDisposable: (ManagedAgentSession) -> Unit,
) {
    private val sessionJob = SupervisorJob(parentJob)
    private val scope = CoroutineScope(sessionJob + dispatcher)
    private val commandMutex = Mutex()
    private val stateMutex = Mutex()
    private val mutableState = MutableStateFlow(seed.toRuntimeSnapshot(sessionId))
    private val mutableEvents = MutableSharedFlow<AgentEvent>(
        extraBufferCapacity = EVENT_BUFFER_CAPACITY,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    private var activeExecution: ActiveExecution? = null
    private var nextExecutionToken = 0L
    private var closed = false
    private var closeCode = AgentSessionErrorCode.CLOSED

    val state: StateFlow<AgentSessionRuntimeSnapshot> = mutableState.asStateFlow()
    val events: SharedFlow<AgentEvent> = mutableEvents.asSharedFlow()

    suspend fun isDisposable(): Boolean = stateMutex.withLock {
        activeExecution == null && mutableState.value.phase != AgentSessionPhase.ACTIVE
    }

    suspend fun start(request: AgentRequest) {
        if (request.sessionId != sessionId) {
            throw AgentSessionException(
                AgentSessionErrorCode.INVALID_STATE,
                "Agent request session ID does not match its acquired runtime",
            )
        }
        startOperation(request, resume = false) { runner.run(request) }
    }

    suspend fun resume() {
        startOperation(request = null, resume = true) { runner.resume(sessionId) }
    }

    suspend fun interrupt(): AgentRecoveryInfo = commandMutex.withLock {
        ensureOpen()
        val observed = stateMutex.withLock { activeExecution to mutableState.value }
        if (observed.first == null || observed.second.phase != AgentSessionPhase.ACTIVE) {
            throw AgentSessionException(
                AgentSessionErrorCode.INVALID_STATE,
                "Agent session ${sessionId.value} is not active",
            )
        }
        var controlSucceeded = false
        try {
            val recovery = controlOperation { runner.interrupt(sessionId) }
            controlSucceeded = true
            requireOwnRecovery(recovery)
            val authoritative = stateMutex.withLock {
                val current = mutableState.value
                if (
                    current.phase == AgentSessionPhase.TERMINAL &&
                    current.state?.status?.let(NON_CANCELLED_TERMINAL_STATUSES::contains) == true
                ) {
                    current.toRecoveryInfo()
                } else {
                    if (recovery.disposition == AgentRecoveryDisposition.NOT_FOUND) {
                        throw AgentSessionException(
                            AgentSessionErrorCode.INVALID_STATE,
                            "Runner lost active Agent session ${sessionId.value} while interrupting",
                        )
                    }
                    publishRecoveryLocked(recovery)
                    recovery
                }
            }
            onDisposable(this)
            authoritative
        } finally {
            if (controlSucceeded) awaitExecutionSettled(observed.first)
        }
    }

    suspend fun inspectRecovery(): AgentRecoveryInfo = commandMutex.withLock {
        ensureOpen()
        val observed = stateMutex.withLock { mutableState.value }
        observed.localRecoveryInfo()?.let { return@withLock it }
        val recovery = controlOperation { runner.inspectRecovery(sessionId) }
        requireOwnRecovery(recovery)
        stateMutex.withLock {
            if (mutableState.value.revision == observed.revision) {
                publishRecoveryLocked(recovery)
                recovery
            } else {
                mutableState.value.toRecoveryInfo()
            }
        }
    }

    suspend fun cancel() = commandMutex.withLock {
        ensureOpen()
        val observed = stateMutex.withLock { activeExecution to mutableState.value }
        val before = observed.second
        if (!before.phase.hasPendingExecution) {
            throw AgentSessionException(
                AgentSessionErrorCode.INVALID_STATE,
                "Agent session ${sessionId.value} has no pending execution",
            )
        }
        var controlSucceeded = false
        try {
            controlOperation { runner.cancel(sessionId) }
            controlSucceeded = true
            // Runner control may settle before a terminal event already handed to the managed
            // collector is reduced. Drain that collector before choosing the canonical winner.
            awaitExecutionSettled(observed.first)
            val persisted = storageOperation { persistence.load(sessionId) }
            if (persisted != null && persisted.snapshot.sessionId != sessionId) {
                throw AgentSessionException(
                    AgentSessionErrorCode.STORAGE,
                    "Persistence returned a different Agent session for ${sessionId.value}",
                )
            }
            val after = stateMutex.withLock { mutableState.value }
            val terminalWinner = after.takeIf { snapshot ->
                snapshot.phase == AgentSessionPhase.TERMINAL &&
                    snapshot.state?.status?.let(NON_CANCELLED_TERMINAL_STATUSES::contains) == true
            }
            if (terminalWinner != null) {
                onDisposable(this)
                return@withLock
            }

            val knownRunId = after.runId ?: before.runId
            val matchingRecord = persisted?.takeIf { record ->
                knownRunId?.let { record.snapshot.runId == it }
                    ?: (before.request != null && record.snapshot.request == before.request)
            }
            val persistedTerminalWinner = matchingRecord?.takeIf { record ->
                knownRunId != null &&
                    record.snapshot.runId == knownRunId &&
                    record.snapshot.state.status in NON_CANCELLED_TERMINAL_STATUSES
            }
            if (persistedTerminalWinner != null) {
                stateMutex.withLock {
                    val current = mutableState.value
                    if (
                        current.phase != AgentSessionPhase.TERMINAL ||
                        current.state?.status !in NON_CANCELLED_TERMINAL_STATUSES
                    ) {
                        val snapshot = persistedTerminalWinner.snapshot
                        mutableState.value = current.copy(
                            revision = nextRevision(current),
                            request = snapshot.request,
                            runId = snapshot.runId,
                            state = snapshot.state,
                            phase = AgentSessionPhase.TERMINAL,
                            recovery = null,
                            failure = null,
                            lastEvent = if (snapshot.state.status == AgentStatus.COMPLETED) {
                                AgentEvent.Completed(sessionId, snapshot.state)
                            } else {
                                null
                            },
                        )
                    }
                }
                onDisposable(this)
                return@withLock
            }
            val baseState = when (before.phase) {
                AgentSessionPhase.RESUMABLE,
                AgentSessionPhase.RECOVERY_BLOCKED,
                -> before.recovery?.state
                    ?: before.state
                    ?: matchingRecord?.checkpoint?.state
                    ?: matchingRecord?.snapshot?.state
                AgentSessionPhase.ACTIVE -> matchingRecord?.snapshot?.state
                    ?.takeIf { it.status == AgentStatus.CANCELLED }
                    ?: after.state
                    ?: before.state
                    ?: matchingRecord?.snapshot?.state
                else -> before.state ?: after.state ?: matchingRecord?.snapshot?.state
            }
            val cancelledState = (baseState ?: AgentStateSnapshot(
                messages = before.request?.messages.orEmpty(),
            )).copy(
                status = AgentStatus.CANCELLED,
                stopReason = saien.magrathea.core.StopReason.CANCELLED,
            )
            val canonicalRequest = (before.request
                ?: after.request
                ?: matchingRecord?.snapshot?.request)
                ?.copy(messages = cancelledState.messages)
            stateMutex.withLock {
                val current = mutableState.value
                mutableState.value = current.copy(
                    revision = nextRevision(current),
                    request = canonicalRequest ?: current.request,
                    runId = matchingRecord?.snapshot?.runId ?: current.runId,
                    state = cancelledState,
                    phase = AgentSessionPhase.TERMINAL,
                    recovery = null,
                    failure = null,
                    lastEvent = AgentEvent.Cancelled(sessionId),
                )
            }
            matchingRecord?.let { record ->
                val normalizedRequest = canonicalRequest ?: record.snapshot.request
                if (
                    record.checkpoint != null ||
                    record.snapshot.request != normalizedRequest ||
                    record.snapshot.state != cancelledState ||
                    record.snapshot.interruption != null
                ) {
                    val normalized = record.snapshot.copy(
                        request = normalizedRequest,
                        state = cancelledState,
                        interruption = null,
                        updatedAtEpochMs = saien.magrathea.core.SystemEpochClock.nowEpochMs(),
                    )
                    storageOperation { persistence.commit(normalized, checkpoint = null) }
                }
            }
            onDisposable(this)
        } finally {
            if (controlSucceeded) awaitExecutionSettled(observed.first)
        }
    }

    suspend fun replaceIdleRequest(request: AgentRequest) = commandMutex.withLock {
        ensureOpen()
        if (request.sessionId != sessionId) {
            throw AgentSessionException(AgentSessionErrorCode.INVALID_STATE)
        }
        val current = stateMutex.withLock { mutableState.value }
        val execution = stateMutex.withLock { activeExecution }
        if (
            execution != null ||
            current.phase == AgentSessionPhase.ACTIVE ||
            current.phase == AgentSessionPhase.RESUMABLE ||
            current.phase == AgentSessionPhase.RECOVERY_BLOCKED
        ) {
            throw AgentSessionException(AgentSessionErrorCode.BUSY)
        }
        if (current.state != null && current.state.messages != request.messages) {
            throw AgentSessionException(
                AgentSessionErrorCode.INVALID_STATE,
                "An idle request replacement must preserve authoritative messages",
            )
        }
        val record = storageOperation { persistence.load(sessionId) }
        if (record != null) {
            if (record.checkpoint != null || record.snapshot.state.status in RECOVERABLE_STORED_STATUSES) {
                throw AgentSessionException(
                    AgentSessionErrorCode.BUSY,
                    "Agent session ${sessionId.value} has recoverable execution state",
                )
            }
            if (record.snapshot.state.messages != request.messages) {
                throw AgentSessionException(
                    AgentSessionErrorCode.INVALID_STATE,
                    "An idle request replacement must preserve persisted messages",
                )
            }
            storageOperation {
                persistence.commit(
                    record.snapshot.copy(
                        request = request,
                        updatedAtEpochMs = saien.magrathea.core.SystemEpochClock.nowEpochMs(),
                    ),
                    checkpoint = null,
                )
            }
        }
        stateMutex.withLock {
            val latest = mutableState.value
            mutableState.value = latest.copy(
                revision = nextRevision(latest),
                request = request,
            )
        }
    }

    suspend fun awaitIdle() {
        while (true) {
            val completion = stateMutex.withLock { activeExecution?.completion } ?: return
            completion.await()
        }
    }

    private suspend fun awaitExecutionSettled(execution: ActiveExecution?) {
        if (execution != null) withContext(NonCancellable) { execution.completion.await() }
    }

    suspend fun shutdown(mode: SessionShutdown) {
        var ownsShutdown = false
        var controlFailure: Throwable? = null
        commandMutex.withLock {
            if (!closed) {
                ownsShutdown = true
                closed = true
                closeCode = if (mode == SessionShutdown.DELETE) {
                    AgentSessionErrorCode.DELETED
                } else {
                    AgentSessionErrorCode.CLOSED
                }
                val controlPendingExecution = stateMutex.withLock {
                    val current = mutableState.value
                    val shouldControl = when (mode) {
                        SessionShutdown.NONE -> false
                        SessionShutdown.INTERRUPT -> current.phase == AgentSessionPhase.ACTIVE
                        SessionShutdown.DELETE -> current.phase.hasPendingExecution
                    }
                    mutableState.value = current.copy(
                        revision = nextRevision(current),
                        phase = if (mode == SessionShutdown.DELETE) {
                            AgentSessionPhase.DELETED
                        } else {
                            AgentSessionPhase.CLOSED
                        },
                    )
                    shouldControl
                }
                try {
                    if (controlPendingExecution) {
                        when (mode) {
                            SessionShutdown.NONE -> Unit
                            SessionShutdown.INTERRUPT ->
                                controlOperation { runner.interrupt(sessionId) }
                            SessionShutdown.DELETE ->
                                controlOperation { runner.cancel(sessionId) }
                        }
                    }
                } catch (error: Throwable) {
                    controlFailure = error
                }
            }
        }
        if (ownsShutdown) {
            withContext(NonCancellable) { sessionJob.cancelAndJoin() }
            controlFailure?.let { throw it }
        }
    }

    private suspend fun startOperation(
        request: AgentRequest?,
        resume: Boolean,
        source: suspend () -> Flow<AgentEvent>,
    ) {
        commandMutex.withLock {
            ensureOpen()
            val execution = stateMutex.withLock {
                val current = mutableState.value
                if (activeExecution != null) {
                    throw AgentSessionException(AgentSessionErrorCode.BUSY)
                }
                if (resume && current.phase != AgentSessionPhase.RESUMABLE) {
                    throw AgentSessionException(
                        if (current.phase == AgentSessionPhase.ACTIVE) {
                            AgentSessionErrorCode.BUSY
                        } else {
                            AgentSessionErrorCode.INVALID_STATE
                        },
                        "Agent session ${sessionId.value} is not resumable",
                    )
                }
                if (!resume && current.phase.hasPendingExecution) {
                    throw AgentSessionException(
                        AgentSessionErrorCode.BUSY,
                        "Agent session ${sessionId.value} must be resumed or cancelled first",
                    )
                }
                val effectiveRequest = request ?: current.request ?: throw AgentSessionException(
                    AgentSessionErrorCode.NOT_FOUND,
                    "Agent session ${sessionId.value} has no persisted request",
                )
                check(nextExecutionToken != Long.MAX_VALUE) {
                    "Agent session execution tokens are exhausted"
                }
                val created = ActiveExecution(
                    token = ++nextExecutionToken,
                    completion = CompletableDeferred(),
                )
                val previousState = current.state
                activeExecution = created
                val provisionalState = if (resume && previousState != null) {
                    previousState.copy(
                        messages = effectiveRequest.messages,
                        status = AgentStatus.RUNNING,
                        stopReason = null,
                    )
                } else {
                    AgentStateSnapshot(
                        messages = effectiveRequest.messages,
                        status = AgentStatus.RUNNING,
                        usage = previousState?.usage ?: saien.magrathea.core.TokenUsage(),
                        latestRequestUsage = previousState?.latestRequestUsage
                            ?: saien.magrathea.core.TokenUsage(),
                        contextManagement = previousState?.contextManagement
                            ?: saien.magrathea.core.ContextManagementState(),
                    )
                }
                mutableState.value = current.copy(
                    revision = nextRevision(current),
                    request = effectiveRequest,
                    runId = current.runId.takeIf { resume },
                    state = provisionalState,
                    phase = AgentSessionPhase.ACTIVE,
                    recovery = null,
                    failure = null,
                    lastEvent = null,
                )
                created
            }
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                execute(execution, source)
            }
        }
    }

    private suspend fun execute(
        execution: ActiveExecution,
        source: suspend () -> Flow<AgentEvent>,
    ) {
        var terminalSeen = false
        var cancelled = false
        try {
            source().collect { event ->
                if (event.sessionId() != sessionId) {
                    throw AgentSessionException(
                        AgentSessionErrorCode.INVALID_STATE,
                        "Runner emitted an event for a different Agent session",
                    )
                }
                if (publishEvent(event)) mutableEvents.tryEmit(event)
                if (event.isTerminal()) {
                    terminalSeen = true
                    throw TerminalEventCollected()
                }
            }
        } catch (_: TerminalEventCollected) {
            // A canonical terminal event ends the managed collection immediately.
        } catch (cancellation: CancellationException) {
            cancelled = true
            throw cancellation
        } catch (_: Throwable) {
            val failure = AgentEvent.Failed(sessionId, AgentFailureCode.INTERNAL)
            if (publishEvent(failure)) mutableEvents.tryEmit(failure)
            terminalSeen = true
        } finally {
            withContext(NonCancellable) {
                if (!terminalSeen && !cancelled && sessionJob.isActive) {
                    val failure = AgentEvent.Failed(sessionId, AgentFailureCode.INTERNAL)
                    if (publishEvent(failure)) mutableEvents.tryEmit(failure)
                    terminalSeen = true
                }
                if (terminalSeen) refreshAfterExecution(execution.token)
                stateMutex.withLock {
                    if (activeExecution?.token == execution.token) activeExecution = null
                }
                execution.completion.complete(Unit)
                onDisposable(this@ManagedAgentSession)
            }
        }
    }

    private suspend fun publishEvent(event: AgentEvent): Boolean = stateMutex.withLock {
        val current = mutableState.value
        if (
            current.phase == AgentSessionPhase.CLOSED ||
            current.phase == AgentSessionPhase.DELETED
        ) {
            return@withLock false
        }
        val nextState = reduceState(current.state, current.request, event)
        mutableState.value = current.copy(
            revision = nextRevision(current),
            runId = when (event) {
                is AgentEvent.Started -> event.runId
                is AgentEvent.CheckpointSaved -> event.checkpoint.runId
                is AgentEvent.Interrupted -> event.runId
                is AgentEvent.RecoveryBlocked -> event.runId
                else -> current.runId
            },
            state = nextState,
            phase = event.toSessionPhase(),
            recovery = when (event) {
                is AgentEvent.Interrupted -> AgentRecoveryInfo(
                    sessionId = sessionId,
                    runId = event.runId,
                    disposition = AgentRecoveryDisposition.RESUMABLE,
                    status = event.state.status,
                    state = event.state,
                    interruption = event.interruption,
                )
                is AgentEvent.RecoveryBlocked -> AgentRecoveryInfo(
                    sessionId = sessionId,
                    runId = event.runId,
                    disposition = AgentRecoveryDisposition.BLOCKED,
                    status = nextState?.status,
                    state = nextState,
                    blockedReason = event.reason,
                )
                else -> if (event.isTerminal()) null else current.recovery
            },
            failure = (event as? AgentEvent.Failed)?.code,
            lastEvent = event,
        )
        true
    }

    private fun publishRecoveryLocked(recovery: AgentRecoveryInfo) {
        val current = mutableState.value
        mutableState.value = current.copy(
            revision = nextRevision(current),
            runId = recovery.runId ?: current.runId,
            state = recovery.state ?: current.state,
            phase = recovery.toSessionPhase(current.phase),
            recovery = recovery,
            failure = null,
        )
    }

    private suspend fun refreshAfterExecution(token: Long) {
        val observed = stateMutex.withLock {
            mutableState.value.takeIf {
                activeExecution?.token == token &&
                    it.phase != AgentSessionPhase.CLOSED &&
                    it.phase != AgentSessionPhase.DELETED
            }
        } ?: return
        val record = runCatching { persistence.load(sessionId) }.getOrNull() ?: return
        if (record.snapshot.sessionId != sessionId) return
        if (observed.runId == null || record.snapshot.runId != observed.runId) return

        val recovery = if (record.snapshot.state.status in RECOVERABLE_STORED_STATUSES) {
            runCatching { runner.inspectRecovery(sessionId) }.getOrNull()
                ?.takeIf { info ->
                    info.sessionId == sessionId &&
                        info.runId == record.snapshot.runId &&
                        info.disposition != AgentRecoveryDisposition.ACTIVE
                }
        } else {
            null
        }
        stateMutex.withLock {
            val current = mutableState.value
            if (
                activeExecution?.token != token ||
                current.phase == AgentSessionPhase.CLOSED ||
                current.phase == AgentSessionPhase.DELETED ||
                current.revision != observed.revision
            ) {
                return@withLock
            }
            mutableState.value = current.copy(
                revision = nextRevision(current),
                request = record.snapshot.request,
                runId = record.snapshot.runId,
                state = recovery?.state ?: record.snapshot.state,
                phase = recovery?.toSessionPhase(record.snapshot.state.toSessionPhase())
                    ?: record.snapshot.state.toSessionPhase(),
                recovery = recovery,
            )
        }
    }

    private fun ensureOpen() {
        if (closed) throw AgentSessionException(closeCode)
    }

    private fun requireOwnRecovery(recovery: AgentRecoveryInfo) {
        if (recovery.sessionId != sessionId) {
            throw AgentSessionException(
                AgentSessionErrorCode.INVALID_STATE,
                "Runner inspected a different Agent session",
            )
        }
    }
}

private class DefaultAgentSessionLease(
    private val manager: DefaultAgentSessionManager,
    private val runtime: ManagedAgentSession,
    private val generation: Long,
) : AgentSessionLease {
    private val attached = MutableStateFlow(true)
    private val lifecycleMutex = Mutex()

    override val sessionId: AgentSessionId = runtime.sessionId
    override val state: StateFlow<AgentSessionRuntimeSnapshot> = runtime.state
    override val events: SharedFlow<AgentEvent> = runtime.events
    override val isAttached: Boolean
        get() = attached.value

    override suspend fun start(request: AgentRequest) = attachedOperation { runtime.start(request) }
    override suspend fun resume() = attachedOperation { runtime.resume() }
    override suspend fun interrupt(): AgentRecoveryInfo = attachedOperation { runtime.interrupt() }
    override suspend fun inspectRecovery(): AgentRecoveryInfo =
        attachedOperation { runtime.inspectRecovery() }
    override suspend fun cancel() = attachedOperation { runtime.cancel() }
    override suspend fun replaceIdleRequest(request: AgentRequest) =
        attachedOperation { runtime.replaceIdleRequest(request) }
    override suspend fun awaitIdle() {
        lifecycleMutex.withLock {
            if (!attached.value) throw AgentSessionException(AgentSessionErrorCode.DETACHED)
            manager.validateLease(runtime, generation)
        }
        runtime.awaitIdle()
    }

    override suspend fun release() {
        withContext(NonCancellable) {
            lifecycleMutex.withLock {
                if (attached.compareAndSet(expect = true, update = false)) {
                    manager.releaseLease(runtime, generation)
                }
            }
        }
    }

    private suspend fun <T> attachedOperation(operation: suspend () -> T): T {
        return lifecycleMutex.withLock {
            if (!attached.value) throw AgentSessionException(AgentSessionErrorCode.DETACHED)
            manager.withLeaseOperation(runtime, generation, operation)
        }
    }
}

private data class SessionSeed(
    val snapshot: AgentSessionSnapshot,
    val recovery: AgentRecoveryInfo?,
)

private sealed interface SessionSlot {
    val generation: Long
    val settlement: CompletableDeferred<Unit>
}

private class OpeningSlot(
    override val generation: Long,
    val mode: OpenMode,
) : SessionSlot {
    override val settlement = CompletableDeferred<Unit>()
    val settled: CompletableDeferred<Unit>
        get() = settlement
}

private class LiveSlot(
    override val generation: Long,
    val runtime: ManagedAgentSession,
    var leaseCount: Int,
) : SessionSlot {
    override val settlement = CompletableDeferred(Unit)
    var operationCount: Int = 0
    var operationsSettled: CompletableDeferred<Unit> = CompletableDeferred(Unit)
}

private class ClosingSlot(
    override val generation: Long,
) : SessionSlot {
    override val settlement = CompletableDeferred<Unit>()
    val settled: CompletableDeferred<Unit>
        get() = settlement
}

private class DeletingSlot(
    override val generation: Long,
    val operationOwner: LiveSlot? = null,
) : SessionSlot {
    val operationsSettled: CompletableDeferred<Unit> =
        operationOwner?.operationsSettled ?: CompletableDeferred(Unit)
    override val settlement = CompletableDeferred<Unit>()
    val settled: CompletableDeferred<Unit>
        get() = settlement
}

private data class ActiveExecution(
    val token: Long,
    val completion: CompletableDeferred<Unit>,
)

private enum class OpenMode {
    CREATE,
    RESTORE,
}

private enum class SessionShutdown {
    NONE,
    INTERRUPT,
    DELETE,
}

private fun SessionSeed?.toRuntimeSnapshot(sessionId: AgentSessionId): AgentSessionRuntimeSnapshot {
    val seed = this ?: return AgentSessionRuntimeSnapshot(
        revision = 0L,
        sessionId = sessionId,
    )
    val state = seed.recovery?.state ?: seed.snapshot.state
    return AgentSessionRuntimeSnapshot(
        revision = 0L,
        sessionId = sessionId,
        request = seed.snapshot.request,
        runId = seed.recovery?.runId ?: seed.snapshot.runId,
        state = state,
        phase = seed.recovery?.toSessionPhase(seed.snapshot.state.toSessionPhase())
            ?: seed.snapshot.state.toSessionPhase(),
        recovery = seed.recovery,
    )
}

private fun AgentStateSnapshot.toSessionPhase(): AgentSessionPhase = when (status) {
    AgentStatus.IDLE -> AgentSessionPhase.INACTIVE
    AgentStatus.RUNNING,
    AgentStatus.WAITING_FOR_TOOLS,
    -> AgentSessionPhase.ACTIVE
    AgentStatus.INTERRUPTED -> AgentSessionPhase.RESUMABLE
    AgentStatus.COMPLETED,
    AgentStatus.FAILED,
    AgentStatus.CANCELLED,
    -> AgentSessionPhase.TERMINAL
}

private fun AgentRecoveryInfo.toSessionPhase(fallback: AgentSessionPhase): AgentSessionPhase =
    when (disposition) {
        AgentRecoveryDisposition.ACTIVE -> AgentSessionPhase.ACTIVE
        AgentRecoveryDisposition.RESUMABLE -> AgentSessionPhase.RESUMABLE
        AgentRecoveryDisposition.BLOCKED -> AgentSessionPhase.RECOVERY_BLOCKED
        AgentRecoveryDisposition.TERMINAL -> AgentSessionPhase.TERMINAL
        AgentRecoveryDisposition.NOT_FOUND -> fallback
    }

private val AgentSessionPhase.hasPendingExecution: Boolean
    get() = this == AgentSessionPhase.ACTIVE ||
        this == AgentSessionPhase.RESUMABLE ||
        this == AgentSessionPhase.RECOVERY_BLOCKED

private fun AgentSessionRuntimeSnapshot.localRecoveryInfo(): AgentRecoveryInfo? = when (phase) {
    AgentSessionPhase.ACTIVE -> null
    AgentSessionPhase.RESUMABLE,
    AgentSessionPhase.RECOVERY_BLOCKED,
    -> recovery ?: toRecoveryInfo()
    AgentSessionPhase.NEW,
    AgentSessionPhase.INACTIVE,
    AgentSessionPhase.TERMINAL,
    AgentSessionPhase.CLOSED,
    AgentSessionPhase.DELETED,
    -> toRecoveryInfo()
}

private fun AgentSessionRuntimeSnapshot.toRecoveryInfo(): AgentRecoveryInfo = AgentRecoveryInfo(
    sessionId = sessionId,
    runId = runId,
    disposition = when (phase) {
        AgentSessionPhase.ACTIVE -> AgentRecoveryDisposition.ACTIVE
        AgentSessionPhase.RESUMABLE -> AgentRecoveryDisposition.RESUMABLE
        AgentSessionPhase.RECOVERY_BLOCKED -> AgentRecoveryDisposition.BLOCKED
        AgentSessionPhase.INACTIVE,
        AgentSessionPhase.TERMINAL,
        AgentSessionPhase.CLOSED,
        -> AgentRecoveryDisposition.TERMINAL
        AgentSessionPhase.NEW,
        AgentSessionPhase.DELETED,
        -> AgentRecoveryDisposition.NOT_FOUND
    },
    status = state?.status,
    state = state,
    cursor = recovery?.cursor,
    interruption = recovery?.interruption,
    blockedReason = recovery?.blockedReason,
)

private fun AgentEvent.toSessionPhase(): AgentSessionPhase = when (this) {
    is AgentEvent.Started,
    is AgentEvent.TurnStarted,
    is AgentEvent.ContextTransformed,
    is AgentEvent.MessageEmitted,
    is AgentEvent.ToolRequested,
    is AgentEvent.ToolCompleted,
    is AgentEvent.RetryScheduled,
    is AgentEvent.CheckpointSaved,
    -> AgentSessionPhase.ACTIVE
    is AgentEvent.Interrupted -> AgentSessionPhase.RESUMABLE
    is AgentEvent.RecoveryBlocked -> AgentSessionPhase.RECOVERY_BLOCKED
    is AgentEvent.Completed,
    is AgentEvent.Failed,
    is AgentEvent.Cancelled,
    -> AgentSessionPhase.TERMINAL
}

private fun reduceState(
    current: AgentStateSnapshot?,
    request: AgentRequest?,
    event: AgentEvent,
): AgentStateSnapshot? {
    val state = current ?: request?.let { AgentStateSnapshot(messages = it.messages) }
    return when (event) {
        is AgentEvent.Started -> state?.copy(status = AgentStatus.RUNNING)
        is AgentEvent.TurnStarted -> state?.copy(turn = event.turn, status = AgentStatus.RUNNING)
        is AgentEvent.ContextTransformed -> state
        is AgentEvent.MessageEmitted -> state?.copy(
            messages = state.messages.replaceOrAppend(event.message),
            status = AgentStatus.RUNNING,
        )
        is AgentEvent.ToolRequested -> state?.copy(
            pendingToolCalls = state.pendingToolCalls
                .filterNot { call -> call.toolCallId == event.toolCall.toolCallId } + event.toolCall,
            status = AgentStatus.WAITING_FOR_TOOLS,
        )
        is AgentEvent.ToolCompleted -> state?.copy(
            pendingToolCalls = state.pendingToolCalls
                .filterNot { call -> call.toolCallId == event.result.toolCallId },
            status = if (
                state.pendingToolCalls.any { call -> call.toolCallId != event.result.toolCallId }
            ) {
                AgentStatus.WAITING_FOR_TOOLS
            } else {
                AgentStatus.RUNNING
            },
        )
        is AgentEvent.RetryScheduled -> state?.copy(
            retryCount = if (state.retryCount == Int.MAX_VALUE) Int.MAX_VALUE else state.retryCount + 1,
        )
        is AgentEvent.CheckpointSaved -> event.checkpoint.state
        is AgentEvent.Completed -> event.state
        is AgentEvent.Failed -> state?.copy(
            status = AgentStatus.FAILED,
            stopReason = saien.magrathea.core.StopReason.ERROR,
        )
        is AgentEvent.Cancelled -> state?.copy(
            status = AgentStatus.CANCELLED,
            stopReason = saien.magrathea.core.StopReason.CANCELLED,
        )
        is AgentEvent.Interrupted -> event.state
        is AgentEvent.RecoveryBlocked -> state
    }
}

private fun List<saien.magrathea.core.AgentMessage>.replaceOrAppend(
    message: saien.magrathea.core.AgentMessage,
): List<saien.magrathea.core.AgentMessage> {
    val index = indexOfFirst { item -> item.id == message.id }
    return if (index < 0) this + message else toMutableList().also { it[index] = message }
}

private fun AgentEvent.sessionId(): AgentSessionId = when (this) {
    is AgentEvent.Started -> sessionId
    is AgentEvent.TurnStarted -> sessionId
    is AgentEvent.ContextTransformed -> sessionId
    is AgentEvent.MessageEmitted -> sessionId
    is AgentEvent.ToolRequested -> sessionId
    is AgentEvent.ToolCompleted -> sessionId
    is AgentEvent.RetryScheduled -> sessionId
    is AgentEvent.CheckpointSaved -> checkpoint.sessionId
    is AgentEvent.Completed -> sessionId
    is AgentEvent.Failed -> sessionId
    is AgentEvent.Cancelled -> sessionId
    is AgentEvent.Interrupted -> sessionId
    is AgentEvent.RecoveryBlocked -> sessionId
}

private fun AgentEvent.isTerminal(): Boolean =
    this is AgentEvent.Completed ||
        this is AgentEvent.Failed ||
        this is AgentEvent.Cancelled ||
        this is AgentEvent.Interrupted ||
        this is AgentEvent.RecoveryBlocked

private fun nextRevision(snapshot: AgentSessionRuntimeSnapshot): Long {
    check(snapshot.revision != Long.MAX_VALUE) { "Agent session revisions are exhausted" }
    return snapshot.revision + 1L
}

private fun requireSessionId(sessionId: AgentSessionId) {
    if (sessionId.value.isBlank()) {
        throw AgentSessionException(
            AgentSessionErrorCode.INVALID_STATE,
            "Agent session ID must not be blank",
        )
    }
}

private suspend inline fun <T> storageOperation(crossinline operation: suspend () -> T): T = try {
    operation()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (known: AgentSessionException) {
    throw known
} catch (failure: Throwable) {
    throw AgentSessionException(AgentSessionErrorCode.STORAGE, cause = failure)
}

private suspend inline fun <T> controlOperation(crossinline operation: suspend () -> T): T = try {
    operation()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (known: AgentSessionException) {
    throw known
} catch (failure: Throwable) {
    throw AgentSessionException(AgentSessionErrorCode.INVALID_STATE, cause = failure)
}

private fun Throwable.withInvalidation(
    scope: AgentSessionInvalidationScope,
): Throwable = when (this) {
    is CancellationException -> this
    is AgentSessionException -> if (invalidationScope == scope) {
        this
    } else {
        AgentSessionException(
            code = code,
            message = message,
            cause = this,
            invalidationScope = scope,
        )
    }
    else -> AgentSessionException(
        code = AgentSessionErrorCode.INVALID_STATE,
        message = message,
        cause = this,
        invalidationScope = scope,
    )
}

private val RECOVERABLE_STORED_STATUSES = setOf(
    AgentStatus.RUNNING,
    AgentStatus.WAITING_FOR_TOOLS,
    AgentStatus.INTERRUPTED,
)

private val NON_CANCELLED_TERMINAL_STATUSES = setOf(
    AgentStatus.COMPLETED,
    AgentStatus.FAILED,
)

private const val RESTORE_READ_ATTEMPTS = 2
private const val EVENT_BUFFER_CAPACITY = 64

private class TerminalEventCollected : Throwable()
