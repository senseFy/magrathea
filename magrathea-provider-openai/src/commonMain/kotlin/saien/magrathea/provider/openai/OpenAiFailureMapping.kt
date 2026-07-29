package saien.magrathea.provider.openai

import saien.magrathea.provider.api.ProviderAuthException
import saien.magrathea.provider.api.ProviderClientException
import saien.magrathea.provider.api.ProviderContextLimitException
import saien.magrathea.provider.api.ProviderRateLimitException
import saien.magrathea.provider.api.ProviderServerException
import saien.magrathea.provider.api.isProviderContextLimitError

internal fun throwOpenAiInBandFailure(
    label: String,
    code: String?,
    errorType: String?,
    providerMessage: String?,
): Nothing {
    val safeCode = code?.takeIf(String::isNotBlank) ?: "unknown"
    val classification = listOfNotNull(code, errorType, providerMessage).joinToString(" ")
    val normalized = classification.lowercase()
    val numericCode = code?.toIntOrNull()
    val canonicalType = errorType.normalizedErrorType()
        ?: code.normalizedErrorType()?.takeUnless { numericCode != null }

    if (isProviderContextLimitError(classification)) {
        throw ProviderContextLimitException()
    }

    val safeMessage = "$label failed with code $safeCode"
    when (canonicalType) {
        "authentication", "authentication_error", "invalid_api_key", "unauthorized" ->
            throw ProviderAuthException(safeMessage, statusCode = 401)
        "permission_denied", "permission_error" ->
            throw ProviderAuthException(safeMessage, statusCode = 403)
        "rate_limit_exceeded", "rate_limit_error", "too_many_requests", "insufficient_quota" ->
            throw ProviderRateLimitException(safeMessage, statusCode = 429)
        "payment_required" ->
            throw ProviderClientException(safeMessage, statusCode = 402)
        "invalid_request",
        "invalid_request_error",
        "invalid_prompt",
        "bad_request",
        "content_policy_violation",
        "refusal",
        "invalid_image",
        "image_too_large",
        "image_too_small",
        "unsupported_image_format",
        "image_download_failed",
        "max_tokens_exceeded",
        "token_limit_exceeded",
        "string_too_long",
        -> throw ProviderClientException(safeMessage, statusCode = 400)
        "not_found", "image_not_found" ->
            throw ProviderClientException(safeMessage, statusCode = 404)
        "precondition_failed" ->
            throw ProviderClientException(safeMessage, statusCode = 412)
        "payload_too_large" ->
            throw ProviderClientException(safeMessage, statusCode = 413)
        "unprocessable" ->
            throw ProviderClientException(safeMessage, statusCode = 422)
        "provider_unavailable" ->
            throw ProviderServerException(safeMessage, statusCode = 502)
        "provider_overloaded", "overloaded_error" ->
            throw ProviderServerException(safeMessage, statusCode = 503)
        "timeout" ->
            throw ProviderServerException(safeMessage, statusCode = 504)
        "server", "server_error", "unmapped" ->
            throw ProviderServerException(safeMessage, statusCode = 500)
    }

    throw when {
        numericCode == 401 || numericCode == 403 ->
            ProviderAuthException(safeMessage, statusCode = numericCode)
        numericCode == 429 ->
            ProviderRateLimitException(safeMessage, statusCode = numericCode)
        numericCode != null && numericCode in 400..499 ->
            ProviderClientException(safeMessage, statusCode = numericCode)
        numericCode != null && numericCode >= 500 ->
            ProviderServerException(safeMessage, statusCode = numericCode)
        normalized.containsAny("authentication", "permission denied", "invalid api key", "unauthorized") ->
            ProviderAuthException(safeMessage, statusCode = 401)
        normalized.containsAny(
            "rate_limit",
            "rate limit",
            "too_many_requests",
            "too many requests",
            "insufficient_quota",
            "insufficient quota",
        ) ->
            ProviderRateLimitException(safeMessage, statusCode = 429)
        normalized.containsAny(
            "invalid_request",
            "invalid request",
            "bad_request",
            "bad request",
            "unprocessable",
            "not_found",
            "not found",
            "unsupported",
        ) ->
            ProviderClientException(safeMessage, statusCode = 400)
        else ->
            ProviderServerException(safeMessage, statusCode = 500)
    }
}

private fun String?.normalizedErrorType(): String? =
    this?.trim()?.lowercase()?.takeIf(String::isNotBlank)

private fun String.containsAny(vararg markers: String): Boolean = markers.any(::contains)
