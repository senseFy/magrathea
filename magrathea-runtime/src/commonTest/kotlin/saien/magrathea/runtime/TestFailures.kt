package saien.magrathea.runtime

/** Required non-message constructor prevents coroutine stack recovery from cloning the sentinel. */
internal class TestFatalError(
    val token: Any,
) : Error(token.toString())

internal class TestRecoverableException(
    cause: Throwable,
) : Exception("synthetic recoverable wrapper", cause)
