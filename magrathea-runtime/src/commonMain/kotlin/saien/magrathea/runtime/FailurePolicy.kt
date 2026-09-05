package saien.magrathea.runtime

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Finds a fatal failure even when an integration wrapped it in a recoverable exception. */
internal fun Throwable.fatalErrorOrNull(): Error? {
    val visited = mutableListOf<Throwable>()
    var current: Throwable? = this
    while (true) {
        val candidate = current ?: return null
        if (visited.any { seen -> seen === candidate }) return null
        if (candidate is Error) return candidate
        visited += candidate
        current = candidate.cause
    }
}

internal fun Throwable.rethrowFatalError() {
    fatalErrorOrNull()?.let { fatal -> throw fatal }
}

/** Completes mandatory cleanup, then preserves the primary failure unless cleanup was fatal. */
internal suspend fun rethrowWithCleanup(
    primaryFailure: Throwable,
    cleanup: suspend () -> Unit,
): Nothing {
    val failures = CleanupFailureAccumulator().apply { record(primaryFailure) }
    withContext(NonCancellable) { failures.capture(cleanup) }
    throw checkNotNull(failures.failureOrNull())
}

/** Attempts every independent cleanup and gives the first fatal failure final priority. */
internal class CleanupFailureAccumulator {
    private val failures = mutableListOf<Throwable>()

    fun record(failure: Throwable) {
        failures += failure
    }

    suspend fun capture(block: suspend () -> Unit) {
        try {
            block()
        } catch (failure: Throwable) {
            record(failure)
        }
    }

    fun failureOrNull(): Throwable? {
        if (failures.isEmpty()) return null
        val fatal = failures.firstNotNullOfOrNull(Throwable::fatalErrorOrNull)
        val primary = fatal ?: failures.first()
        failures.forEach { failure ->
            if (
                failure !== primary &&
                (fatal == null || failure.fatalErrorOrNull() !== fatal)
            ) {
                primary.addSuppressed(failure)
            }
        }
        return primary
    }
}
