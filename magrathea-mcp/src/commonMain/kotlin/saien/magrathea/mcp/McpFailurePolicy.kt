package saien.magrathea.mcp

import io.modelcontextprotocol.kotlin.sdk.shared.Transport
import io.modelcontextprotocol.kotlin.sdk.shared.TransportSendOptions
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCMessage
import kotlinx.coroutines.flow.MutableStateFlow

/** Transport implementations may wrap both HTTP failures and fatal errors. Cause cycles are legal. */
internal fun Throwable.mcpCauseChain(): List<Throwable> {
    val causes = mutableListOf<Throwable>()
    var current: Throwable? = this
    while (true) {
        val candidate = current ?: return causes
        if (causes.any { it === candidate }) return causes
        causes += candidate
        current = candidate.cause
    }
}

internal fun Throwable.rethrowMcpFatalError() {
    mcpFatalErrorOrNull()?.let { throw it }
}

private fun Throwable.mcpFatalErrorOrNull(): Error? = mcpCauseChain().filterIsInstance<Error>().firstOrNull()

/**
 * The client closes its transport before rethrowing initialization failures. During that cleanup,
 * ordinary close failures must not mask the original failure. Fatal errors take precedence in
 * every phase, without retaining ordinary payload-bearing exceptions.
 */
internal class McpFailureGuardTransport(private val delegate: Transport) : Transport by delegate {
    private val fatal = MutableStateFlow<Error?>(null)
    private val initializing = MutableStateFlow(true)

    fun finishInitialization() {
        initializing.value = false
    }

    override suspend fun start() = guard { delegate.start() }

    override suspend fun send(message: JSONRPCMessage, options: TransportSendOptions?) =
        guard { delegate.send(message, options) }

    override suspend fun close() {
        try {
            guard { delegate.close() }
        } catch (failure: Throwable) {
            failure.rethrowMcpFatalError()
            if (!initializing.value) throw failure
        }
    }

    private suspend fun guard(block: suspend () -> Unit) {
        try {
            block()
        } catch (failure: Throwable) {
            failure.mcpFatalErrorOrNull()?.let { fatal.compareAndSet(null, it) }
            throw (fatal.value ?: failure)
        }
        fatal.value?.let { throw it }
    }
}

/** Attempt every independent release; a cleanup failure must never hide a fatal primary failure. */
internal class McpCleanupFailures(primaryFailure: Throwable? = null) {
    private val failures = mutableListOf<Throwable>().apply { primaryFailure?.let(::add) }

    suspend fun capture(block: suspend () -> Unit) {
        try {
            block()
        } catch (failure: Throwable) {
            failures += failure
        }
    }

    fun failureOrNull(): Throwable? {
        val fatal = failures.firstNotNullOfOrNull { failure ->
            failure.mcpFatalErrorOrNull()
        }
        val primary = fatal ?: failures.firstOrNull() ?: return null
        failures.forEach { failure ->
            if (failure !== primary && failure.mcpCauseChain().none { it === primary }) {
                primary.addSuppressed(failure)
            }
        }
        return primary
    }
}
