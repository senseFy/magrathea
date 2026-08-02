package saien.magrathea.provider.api

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProviderFailureContractTest {
    @Test
    fun retryabilityIsPartOfTheProviderFailureTaxonomy() {
        listOf(
            ProviderNetworkException("offline"),
            ProviderTimeoutException(ProviderTimeoutPhase.FIRST_EVENT),
            ProviderRateLimitException("rate limited"),
            ProviderServerException("unavailable", statusCode = 503),
        ).forEach { failure ->
            assertTrue(failure.retryable, failure::class.simpleName)
        }

        listOf(
            ProviderException("unspecified"),
            ProviderAuthException("unauthorized"),
            ProviderClientException("invalid", statusCode = 400),
            ProviderContextLimitException(),
            ProviderProtocolException("malformed"),
        ).forEach { failure ->
            assertFalse(failure.retryable, failure::class.simpleName)
        }
    }

    @Test
    fun invalidatedInvocationCanOverrideTheUnderlyingFailureRetryability() {
        val transient = ProviderInvocationInvalidatedException(
            failure = ProviderServerException("unavailable", statusCode = 503),
            retryable = true,
        )
        val permanent = ProviderInvocationInvalidatedException(
            failure = ProviderServerException("rejected", statusCode = 502),
            retryable = false,
        )

        assertTrue(transient.retryable)
        assertFalse(permanent.retryable)
        assertTrue(transient.failure is ProviderServerException)
    }
}
