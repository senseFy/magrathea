package saien.magrathea.provider.openai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import saien.magrathea.provider.api.ProviderAuthException
import saien.magrathea.provider.api.ProviderClientException
import saien.magrathea.provider.api.ProviderPermissionException
import saien.magrathea.provider.api.ProviderRateLimitException
import saien.magrathea.provider.api.ProviderServerException

class OpenAiFailureMappingContractTest {
    @Test
    fun canonicalErrorTypeDeterminesTheTypedFailureAndStatus() {
        assertEquals(
            403,
            failure<ProviderPermissionException>("server_error", "permission_denied").statusCode,
        )
        assertEquals(
            429,
            failure<ProviderRateLimitException>("server_error", "rate_limit_exceeded").statusCode,
        )
        assertEquals(
            400,
            failure<ProviderClientException>("server_error", "invalid_prompt").statusCode,
        )
        assertEquals(
            402,
            failure<ProviderClientException>("server_error", "payment_required").statusCode,
        )
        assertEquals(
            502,
            failure<ProviderServerException>("server_error", "provider_unavailable").statusCode,
        )
        assertEquals(
            504,
            failure<ProviderServerException>("server_error", "timeout").statusCode,
        )
    }

    @Test
    fun mappedFailureDoesNotExposeTheProviderMessage() {
        val exception = failure<ProviderClientException>(
            code = "invalid_prompt",
            errorType = null,
            providerMessage = "sensitive upstream request fragment",
        )

        assertFalse(exception.message.orEmpty().contains("sensitive upstream request fragment"))
    }

    private inline fun <reified T : Throwable> failure(
        code: String?,
        errorType: String?,
        providerMessage: String = "upstream detail",
    ): T = assertFailsWith<T> {
        throwOpenAiInBandFailure(
            label = "OpenAI contract",
            code = code,
            errorType = errorType,
            providerMessage = providerMessage,
        )
    }
}
