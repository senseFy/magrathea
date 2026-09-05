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
import saien.magrathea.core.AgentPersistenceRecord
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
    /** Recovery is blocked or not yet established; a null recovery payload means inspect again. */
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

    /** Joins local execution only; unresolved recovery can still prohibit starting another run. */
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
                    val failures = CleanupFailureAccumulator()
                    failures.capture { claimed.operationsSettled.await() }
                    failures.capture { runtime?.shutdown(SessionShutdown.DELETE) }
                    val shutdownFailure = failures.failureOrNull()
                    if (shutdownFailure == null) {
                        failures.capture { storageOperation { persistence.deleteSession(sessionId) } }
                    }
                    (shutdownFailure ?: failures.failureOrNull())?.let { failure -> throw failure }
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
                    val failures = CleanupFailureAccumulator()
                    operationGates.forEach { gate ->
                        failures.capture { gate.await() }
                    }
                    runtimes.forEach { runtime ->
                        failures.capture { runtime.shutdown(SessionShutdown.DELETE) }
                    }
                    val shutdownFailure = failures.failureOrNull()
                    if (shutdownFailure == null) {
                        failures.capture { storageOperation { persistence.clear() } }
                    }
                    (shutdownFailure ?: failures.failureOrNull())?.let { failure -> throw failure }
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
                val failures = CleanupFailureAccumulator()
                pending.forEach { gate ->
                    failures.capture { gate.await() }
                }
                runtimes.forEach { runtime ->
                    failures.capture { runtime.shutdown(SessionShutdown.INTERRUPT) }
                }
                failures.capture {
                    mutex.withLock {
                        slots.clear()
                        clearing = null
                        mutableLiveSessionIds.value = emptySet()
                    }
                }
                failures.capture { managerJob.cancelAndJoin() }
                failures.failureOrNull()?.let { failure -> throw failure }
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
                rethrowWithCleanup(failure) {
                    mutex.withLock {
                        if (slots[sessionId.value] === claimed) slots.remove(sessionId.value)
                    }
                    claimed.settled.complete(Unit)
                }
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
                val failures = CleanupFailureAccumulator()
                if (!didInstall) failures.capture { runtime.shutdown(SessionShutdown.NONE) }
                claimed.settled.complete(Unit)
                failures.failureOrNull()?.let { failure -> throw failure }
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
    private var machine = SessionMachine(sessionId, seed.toSessionResult())
    private val mutableState = MutableStateFlow(machine.snapshot(revision = 0L))
    private val mutableEvents = MutableSharedFlow<AgentEvent>(
        extraBufferCapacity = EVENT_BUFFER_CAPACITY,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    val state: StateFlow<AgentSessionRuntimeSnapshot> = mutableState.asStateFlow()
    val events: SharedFlow<AgentEvent> = mutableEvents.asSharedFlow()

    suspend fun isDisposable(): Boolean = stateMutex.withLock { machine.execution == null }

    /** Publish a pure transition; public revisions do not participate in domain decisions. */
    private fun transitionLocked(next: SessionMachine) {
        val current = mutableState.value
        val projection = next.snapshot(current.revision)
        val published = if (projection != current) projection.copy(revision = nextRevision(current)) else current
        machine = next
        mutableState.value = published
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
        val attempt = stateMutex.withLock {
            machine.requireOpen()
            if (!machine.isExecuting) {
                throw AgentSessionException(AgentSessionErrorCode.INVALID_STATE, "Agent session is not active")
            }
            checkNotNull(machine.execution)
        }
        var controlSucceeded = false
        try {
            val recovery = controlOperation { runner.interrupt(sessionId) }
            controlSucceeded = true
            requireOwnRecovery(recovery)
            val authoritative = stateMutex.withLock {
                transitionLocked(machine.interrupted(attempt, recovery))
                machine.recoveryInfo()
            }
            onDisposable(this)
            authoritative
        } finally {
            if (controlSucceeded) awaitExecutionSettled(attempt)
        }
    }

    suspend fun inspectRecovery(): AgentRecoveryInfo = commandMutex.withLock {
        val observed = stateMutex.withLock {
            machine.requireOpen()
            machine
        }
        if (observed.result.isConfirmed) return@withLock observed.recoveryInfo()
        if (observed.execution == null) {
            val observation = observePersistence()
            stateMutex.withLock {
                transitionLocked(machine.observePersistence(observed, observation))
                machine.recoveryInfo()
            }
        } else {
            val recovery = controlOperation { runner.inspectRecovery(sessionId) }
            requireOwnRecovery(recovery)
            stateMutex.withLock {
                transitionLocked(machine.observeRunner(observed, recovery))
                machine.recoveryInfo()
            }
        }
    }

    suspend fun cancel() = commandMutex.withLock {
        val before = stateMutex.withLock {
            machine.requireOpen()
            if (!machine.hasPendingWork) {
                throw AgentSessionException(AgentSessionErrorCode.INVALID_STATE, "Agent session has no pending execution")
            }
            machine
        }
        var controlSucceeded = false
        try {
            controlOperation { runner.cancel(sessionId) }
            controlSucceeded = true
            // Drain terminal events already handed to the collector before choosing a winner.
            awaitExecutionSettled(before.execution)
            val persisted = storageOperation { persistence.load(sessionId) }
            if (persisted != null && persisted.snapshot.sessionId != sessionId) {
                throw AgentSessionException(AgentSessionErrorCode.STORAGE, "Persistence returned a different Agent session")
            }
            val plan = stateMutex.withLock {
                machine.planCancellation(before, persisted, saien.magrathea.core.SystemEpochClock.nowEpochMs())
            }
            stateMutex.withLock { transitionLocked(machine.prepareCancellation(plan)) }
            plan.commit?.let { snapshot ->
                storageOperation { persistence.commit(snapshot, checkpoint = null) }
            }
            stateMutex.withLock { transitionLocked(machine.completeCancellation(plan)) }
            onDisposable(this)
        } finally {
            if (controlSucceeded) awaitExecutionSettled(before.execution)
        }
    }

    suspend fun replaceIdleRequest(request: AgentRequest) = commandMutex.withLock {
        ensureOpen()
        if (request.sessionId != sessionId) {
            throw AgentSessionException(AgentSessionErrorCode.INVALID_STATE)
        }
        val current = stateMutex.withLock {
            machine.requireAdmission(resume = false)
            machine.result
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
        stateMutex.withLock { transitionLocked(machine.replaceRequest(request)) }
    }

    suspend fun awaitIdle() {
        while (true) {
            val completion = stateMutex.withLock { machine.execution?.completion } ?: return
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
            val controlPending = stateMutex.withLock {
                if (machine.lifecycle != SessionLifecycle.OPEN) return@withLock false
                ownsShutdown = true
                val pending = when (mode) {
                    SessionShutdown.NONE -> false
                    SessionShutdown.INTERRUPT -> machine.isExecuting
                    SessionShutdown.DELETE -> machine.hasPendingWork
                }
                transitionLocked(machine.close(deleted = mode == SessionShutdown.DELETE))
                pending
            }
            try {
                if (controlPending) {
                    when (mode) {
                        SessionShutdown.NONE -> Unit
                        SessionShutdown.INTERRUPT -> controlOperation { runner.interrupt(sessionId) }
                        SessionShutdown.DELETE -> controlOperation { runner.cancel(sessionId) }
                    }
                }
            } catch (error: Throwable) {
                controlFailure = error
            }
        }
        if (ownsShutdown) {
            withContext(NonCancellable) {
                val failures = CleanupFailureAccumulator()
                controlFailure?.let(failures::record)
                failures.capture { sessionJob.cancelAndJoin() }
                failures.failureOrNull()?.let { failure -> throw failure }
            }
        }
    }

    private suspend fun startOperation(
        request: AgentRequest?,
        resume: Boolean,
        source: suspend () -> Flow<AgentEvent>,
    ) {
        commandMutex.withLock {
            val execution = stateMutex.withLock {
                transitionLocked(machine.begin(request, resume))
                checkNotNull(machine.execution)
            }
            scope.launch(start = CoroutineStart.UNDISPATCHED) { execute(execution, source) }
        }
    }

    private suspend fun execute(
        execution: ActiveExecution,
        source: suspend () -> Flow<AgentEvent>,
    ) {
        var terminalSeen = false
        var primaryFailure: Throwable? = null
        try {
            source().collect { event ->
                if (event.sessionId() != sessionId) {
                    throw AgentSessionException(
                        AgentSessionErrorCode.INVALID_STATE,
                        "Runner emitted an event for a different Agent session",
                    )
                }
                if (publishEvent(execution, event)) mutableEvents.tryEmit(event)
                if (event.isTerminal()) {
                    terminalSeen = true
                    throw TerminalEventCollected()
                }
            }
        } catch (_: TerminalEventCollected) {
            // A canonical terminal event ends the managed collection immediately.
        } catch (cancellation: CancellationException) {
            val fatal = cancellation.fatalErrorOrNull()
            primaryFailure = fatal ?: cancellation
            if (fatal != null) throw fatal
            throw cancellation
        } catch (failure: Throwable) {
            failure.fatalErrorOrNull()?.let { fatal ->
                primaryFailure = fatal
                throw fatal
            }
            publishUnexpectedFailure(execution)
            terminalSeen = true
        } finally {
            withContext(NonCancellable) {
                val failures = CleanupFailureAccumulator()
                primaryFailure?.let(failures::record)
                if (!terminalSeen && primaryFailure == null && sessionJob.isActive) {
                    failures.capture {
                        publishUnexpectedFailure(execution)
                        terminalSeen = true
                    }
                }
                failures.capture { settleExecution(execution, refreshTerminal = terminalSeen) }
                execution.completion.complete(Unit)
                failures.capture { onDisposable(this@ManagedAgentSession) }
                failures.failureOrNull()?.let { failure -> throw failure }
            }
        }
    }

    private suspend fun publishUnexpectedFailure(execution: ActiveExecution) {
        val event = stateMutex.withLock {
            val next = machine.recordUnexpectedFailure(execution) ?: return@withLock null
            transitionLocked(next)
            next.result.lastEvent
        }
        event?.let { mutableEvents.tryEmit(it) }
    }

    private suspend fun publishEvent(execution: ActiveExecution, event: AgentEvent): Boolean = stateMutex.withLock {
        val next = machine.recordEvent(execution, event) ?: return@withLock false
        transitionLocked(next)
        true
    }

    private suspend fun settleExecution(execution: ActiveExecution, refreshTerminal: Boolean) {
        val observed = stateMutex.withLock { machine }
        var observation: RecoveryObservation = RecoveryObservation.Unavailable
        try {
            if (observed.lifecycle == SessionLifecycle.OPEN && (refreshTerminal || !observed.result.isConfirmed)) {
                observation = observePersistence()
            }
        } finally {
            stateMutex.withLock { transitionLocked(machine.settle(execution, observed, observation)) }
        }
    }

    private suspend fun observePersistence(): RecoveryObservation = try {
        val record = persistence.load(sessionId)
        when {
            record == null -> RecoveryObservation.Absent
            record.snapshot.sessionId != sessionId -> RecoveryObservation.Unavailable
            record.snapshot.state.status !in RECOVERABLE_STORED_STATUSES && record.checkpoint == null ->
                RecoveryObservation.Present(record, recovery = null)
            else -> {
                val recovery = runner.inspectRecovery(sessionId)
                if (
                    recovery.sessionId == sessionId &&
                    recovery.runId == record.snapshot.runId &&
                    recovery.disposition in PENDING_RECOVERY_DISPOSITIONS
                ) {
                    RecoveryObservation.Present(record, recovery)
                } else {
                    RecoveryObservation.Unavailable
                }
            }
        }
    } catch (failure: Exception) {
        failure.rethrowFatalError()
        if (failure is CancellationException) throw failure
        RecoveryObservation.Unavailable
    }

    private fun ensureOpen() = machine.requireOpen()

    private fun requireOwnRecovery(recovery: AgentRecoveryInfo) {
        if (recovery.sessionId != sessionId) {
            throw AgentSessionException(AgentSessionErrorCode.INVALID_STATE, "Runner inspected a different Agent session")
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

private enum class OpenMode {
    CREATE,
    RESTORE,
}

private enum class SessionShutdown {
    NONE,
    INTERRUPT,
    DELETE,
}

private fun SessionSeed?.toSessionResult(): SessionResult =
    this?.let { SessionResult.fromStored(it.snapshot, it.recovery) } ?: SessionResult()

private val PENDING_RECOVERY_DISPOSITIONS = setOf(
    AgentRecoveryDisposition.RESUMABLE, AgentRecoveryDisposition.BLOCKED,
)


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
    cancelled.rethrowFatalError()
    throw cancelled
} catch (known: AgentSessionException) {
    known.rethrowFatalError()
    throw known
} catch (failure: Exception) {
    failure.rethrowFatalError()
    throw AgentSessionException(AgentSessionErrorCode.STORAGE, cause = failure)
}

private suspend inline fun <T> controlOperation(crossinline operation: suspend () -> T): T = try {
    operation()
} catch (cancelled: CancellationException) {
    cancelled.rethrowFatalError()
    throw cancelled
} catch (known: AgentSessionException) {
    known.rethrowFatalError()
    throw known
} catch (failure: Exception) {
    failure.rethrowFatalError()
    throw AgentSessionException(AgentSessionErrorCode.INVALID_STATE, cause = failure)
}

private fun Throwable.withInvalidation(
    scope: AgentSessionInvalidationScope,
): Throwable = fatalErrorOrNull() ?: when (this) {
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


private const val RESTORE_READ_ATTEMPTS = 2
private const val EVENT_BUFFER_CAPACITY = 64

private class TerminalEventCollected : Throwable()
