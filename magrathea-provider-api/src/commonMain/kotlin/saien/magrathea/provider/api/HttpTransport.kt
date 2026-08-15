package saien.magrathea.provider.api

import io.ktor.http.URLProtocol
import io.ktor.http.Url
import kotlinx.coroutines.flow.Flow
import saien.magrathea.core.SystemEpochClock

enum class HttpMethod {
    GET,
    POST,
    PUT,
    PATCH,
    DELETE,
    HEAD,
}

data class HttpHeader(
    val name: String,
    val value: String,
) {
    init {
        require(name.isNotBlank()) { "HTTP header name must not be blank" }
        require('\n' !in name && '\r' !in name) { "HTTP header name must not contain line breaks" }
        require('\n' !in value && '\r' !in value) { "HTTP header value must not contain line breaks" }
    }

    override fun toString(): String = "HttpHeader(name=$name, valueChars=${value.length})"
}

/** Optional per-request overrides for the platform HTTP transport. */
data class HttpTimeoutConfig(
    val requestTimeoutMillis: Long? = null,
    val connectTimeoutMillis: Long? = null,
    val socketTimeoutMillis: Long? = null,
) {
    init {
        require(requestTimeoutMillis == null || requestTimeoutMillis > 0) {
            "HTTP request timeout must be positive"
        }
        require(connectTimeoutMillis == null || connectTimeoutMillis > 0) {
            "HTTP connect timeout must be positive"
        }
        require(socketTimeoutMillis == null || socketTimeoutMillis > 0) {
            "HTTP socket timeout must be positive"
        }
    }
}

data class HttpRequestSpec(
    val method: HttpMethod,
    val url: String,
    val headers: List<HttpHeader> = emptyList(),
    val body: String? = null,
    val timeouts: HttpTimeoutConfig? = null,
) {
    init {
        requireValidHttpEndpoint(url)
    }

    override fun toString(): String {
        val safeUrl = redactHttpUrl(url)
        return "HttpRequestSpec(method=$method, url=$safeUrl, headerNames=${headers.map { it.name }}, bodyChars=${body?.length ?: 0}, timeouts=$timeouts)"
    }
}

/**
 * Enforces the transport policy shared by direct Provider and supporting HTTP requests.
 *
 * Production endpoints must use HTTPS. Plain HTTP is accepted only for an explicit loopback host
 * so local fixtures and development servers remain possible. Userinfo and fragments are forbidden;
 * credentials belong in the separately redacted credential/header boundary.
 */
fun requireValidHttpEndpoint(endpoint: String) {
    require(endpoint.isNotBlank() && endpoint == endpoint.trim()) {
        "HTTP endpoint must be non-blank and trimmed"
    }
    require(endpoint.length <= MAX_HTTP_ENDPOINT_CHARS) {
        "HTTP endpoint exceeded the configured limit"
    }
    require("://" in endpoint) { "HTTP endpoint must include an explicit scheme" }
    val url = runCatching { Url(endpoint) }
        .getOrElse { throw IllegalArgumentException("HTTP endpoint must be a valid URL", it) }
    require(url.protocol == URLProtocol.HTTPS || url.protocol == URLProtocol.HTTP) {
        "HTTP endpoint must use HTTP or HTTPS"
    }
    require(url.host.isNotBlank()) { "HTTP endpoint must include a host" }
    if (url.protocol == URLProtocol.HTTP) {
        require(url.host.isLoopbackHost()) {
            "Plain HTTP endpoints are allowed only on loopback hosts"
        }
    }
    require(url.fragment.isEmpty()) { "HTTP endpoint must not contain a URL fragment" }
    require('@' !in endpoint.substringAfter("://").substringBefore('/')) {
        "HTTP endpoint must not contain userinfo credentials"
    }
}

data class HttpResponseSpec(
    val statusCode: Int,
    val headers: List<HttpHeader> = emptyList(),
    val body: String,
) {
    init {
        require(statusCode in 100..599) { "Invalid HTTP status code $statusCode" }
    }

    override fun toString(): String {
        return "HttpResponseSpec(statusCode=$statusCode, headerNames=${headers.map { it.name }}, bodyChars=${body.length})"
    }
}

data class HttpTransportLimits(
    val maxResponseBodyBytes: Int = 16_777_216,
    val maxStreamLineChars: Int = 1_048_576,
    val maxStreamEventChars: Int = 2_097_152,
) {
    init {
        require(maxResponseBodyBytes > 0) { "maxResponseBodyBytes must be positive" }
        require(maxStreamLineChars > 0) { "maxStreamLineChars must be positive" }
        require(maxStreamEventChars > 0) { "maxStreamEventChars must be positive" }
    }
}

enum class HttpStreamFormat {
    JSON_LINES,
    SERVER_SENT_EVENTS,
}

sealed interface HttpStreamFrame {
    data class ResponseStarted(
        val statusCode: Int,
        val headers: List<HttpHeader>,
    ) : HttpStreamFrame

    data class JsonLine(val value: String) : HttpStreamFrame

    data class ServerSentEvent(
        val event: String?,
        val data: String,
        val id: String?,
    ) : HttpStreamFrame

    data class RetryHint(val retryMillis: Long) : HttpStreamFrame

    data object Completed : HttpStreamFrame
}

/** Platform-neutral, bounded HTTP and streaming transport used by Provider adapters. */
interface HttpTransport {
    suspend fun execute(request: HttpRequestSpec): HttpResponseSpec

    fun stream(
        request: HttpRequestSpec,
        format: HttpStreamFormat,
    ): Flow<HttpStreamFrame>

    fun close()
}

class HttpStreamFramer(
    private val format: HttpStreamFormat,
    private val limits: HttpTransportLimits = HttpTransportLimits(),
) {
    private var firstLine = true
    private var finished = false
    private var eventType: String? = null
    private var lastEventId: String? = null
    private val dataLines = mutableListOf<String>()
    private var eventChars = 0

    fun accept(rawLine: String): List<HttpStreamFrame> {
        check(!finished) { "HTTP stream framer is already completed" }
        val line = if (firstLine) rawLine.removePrefix(UTF8_BOM) else rawLine
        firstLine = false
        if (line.length > limits.maxStreamLineChars) {
            throw ProviderProtocolException("HTTP stream line exceeds configured limit")
        }
        return when (format) {
            HttpStreamFormat.JSON_LINES -> acceptJsonLine(line)
            HttpStreamFormat.SERVER_SENT_EVENTS -> acceptServerSentEventLine(line)
        }
    }

    fun finish(): List<HttpStreamFrame> {
        check(!finished) { "HTTP stream framer is already completed" }
        finished = true
        return buildList {
            if (format == HttpStreamFormat.SERVER_SENT_EVENTS) {
                dispatchServerSentEvent()?.let(::add)
            }
            add(HttpStreamFrame.Completed)
        }
    }

    private fun acceptJsonLine(line: String): List<HttpStreamFrame> {
        return if (line.isBlank()) emptyList() else listOf(HttpStreamFrame.JsonLine(line))
    }

    private fun acceptServerSentEventLine(line: String): List<HttpStreamFrame> {
        if (line.isEmpty()) return listOfNotNull(dispatchServerSentEvent())
        if (line.startsWith(':')) return emptyList()

        val separator = line.indexOf(':')
        val field = if (separator < 0) line else line.substring(0, separator)
        val rawValue = if (separator < 0) "" else line.substring(separator + 1)
        val value = rawValue.removePrefix(" ")
        return when (field) {
            "data" -> {
                val addedChars = value.length + if (dataLines.isEmpty()) 0 else 1
                if (eventChars + addedChars > limits.maxStreamEventChars) {
                    throw ProviderProtocolException("Server-sent event exceeds configured limit")
                }
                dataLines += value
                eventChars += addedChars
                emptyList()
            }
            "event" -> {
                eventType = value
                emptyList()
            }
            "id" -> {
                if ('\u0000' !in value) lastEventId = value.ifEmpty { null }
                emptyList()
            }
            "retry" -> parseNonNegativeLong(value)?.let { listOf(HttpStreamFrame.RetryHint(it)) }.orEmpty()
            else -> emptyList()
        }
    }

    private fun dispatchServerSentEvent(): HttpStreamFrame.ServerSentEvent? {
        if (dataLines.isEmpty()) {
            eventType = null
            eventChars = 0
            return null
        }
        val frame = HttpStreamFrame.ServerSentEvent(
            event = eventType?.ifEmpty { null },
            data = dataLines.joinToString("\n"),
            id = lastEventId,
        )
        dataLines.clear()
        eventType = null
        eventChars = 0
        return frame
    }
}

fun HttpResponseSpec.requireSuccessful(
    nowEpochMillis: Long = SystemEpochClock.nowEpochMs(),
): HttpResponseSpec {
    if (statusCode in 200..299) return this

    val retryAfterMillis = firstHeaderValue("Retry-After")
        ?.let { parseRetryAfterMillis(it, nowEpochMillis) }
    val message = "HTTP $statusCode"
    throw when {
        statusCode in setOf(400, 413, 422) && isProviderContextLimitError(body) ->
            ProviderContextLimitException(statusCode = statusCode)
        statusCode == 401 -> ProviderAuthException(
            message = message,
            statusCode = statusCode,
        )
        statusCode == 403 -> ProviderPermissionException(
            message = message,
            statusCode = statusCode,
        )
        statusCode == 429 -> ProviderRateLimitException(
            message = message,
            statusCode = statusCode,
            retryAfterMillis = retryAfterMillis,
        )
        statusCode >= 500 -> ProviderServerException(
            message = message,
            statusCode = statusCode,
            retryAfterMillis = retryAfterMillis,
        )
        else -> ProviderClientException(
            message = message,
            statusCode = statusCode,
            retryAfterMillis = retryAfterMillis,
        )
    }
}

/**
 * Conservative cross-Provider classification for context-window failures.
 *
 * Error bodies are used only for classification and are never copied into public exceptions,
 * diagnostics, or telemetry because they may contain request fragments.
 */
fun isProviderContextLimitError(codeOrMessage: String?): Boolean {
    val value = codeOrMessage?.lowercase()?.takeIf(String::isNotBlank) ?: return false
    return CONTEXT_LIMIT_MARKERS.any(value::contains) ||
        (
            value.contains("token") &&
                value.contains("limit") &&
                value.containsAny("exceed", "maximum", "too many", "too long")
        ) ||
        (
            value.contains("input") &&
                value.containsAny("too long", "too large", "exceeds") &&
                value.containsAny("context", "token", "length")
        )
}

fun parseRetryAfterMillis(value: String, nowEpochMillis: Long): Long? {
    val trimmed = value.trim()
    parseRetryMillis(trimmed)?.let { return it }
    val targetEpochMillis = parseImfFixDateEpochMillis(trimmed) ?: return null
    return (targetEpochMillis - nowEpochMillis).coerceAtLeast(0)
}

private fun String.containsAny(vararg values: String): Boolean = values.any(::contains)

private val CONTEXT_LIMIT_MARKERS = listOf(
    "context_length_exceeded",
    "context window exceeded",
    "context window is exceeded",
    "context window limit",
    "maximum context length",
    "max context length",
    "prompt is too long",
    "request too large for model",
)

private fun HttpResponseSpec.firstHeaderValue(name: String): String? {
    return headers.firstOrNull { it.name.equals(name, ignoreCase = true) }?.value
}

private fun parseRetryMillis(value: String): Long? {
    val seconds = parseNonNegativeLong(value) ?: return null
    return if (seconds > Long.MAX_VALUE / 1_000) Long.MAX_VALUE else seconds * 1_000
}

private fun parseNonNegativeLong(value: String): Long? {
    if (value.isEmpty() || value.any { !it.isDigit() }) return null
    return value.toLongOrNull()
}

private fun parseImfFixDateEpochMillis(value: String): Long? {
    val match = IMF_FIXDATE.matchEntire(value) ?: return null
    val day = match.groupValues[1].toIntOrNull() ?: return null
    val month = MONTHS[match.groupValues[2].lowercase()] ?: return null
    val year = match.groupValues[3].toIntOrNull() ?: return null
    val hour = match.groupValues[4].toIntOrNull() ?: return null
    val minute = match.groupValues[5].toIntOrNull() ?: return null
    val second = match.groupValues[6].toIntOrNull() ?: return null
    if (year !in 1601..9999 || hour !in 0..23 || minute !in 0..59 || second !in 0..59) return null
    if (day !in 1..daysInMonth(year, month)) return null

    val days = daysFromCivil(year, month, day)
    return ((days * 24 + hour) * 60 + minute) * 60_000 + second * 1_000L
}

private fun daysInMonth(year: Int, month: Int): Int = when (month) {
    2 -> if (year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)) 29 else 28
    4, 6, 9, 11 -> 30
    else -> 31
}

private fun daysFromCivil(year: Int, month: Int, day: Int): Long {
    val adjustedYear = year - if (month <= 2) 1 else 0
    val era = adjustedYear / 400
    val yearOfEra = adjustedYear - era * 400
    val adjustedMonth = month + if (month > 2) -3 else 9
    val dayOfYear = (153 * adjustedMonth + 2) / 5 + day - 1
    val dayOfEra = yearOfEra * 365 + yearOfEra / 4 - yearOfEra / 100 + dayOfYear
    return era * 146_097L + dayOfEra - 719_468L
}

internal fun redactHttpUrl(value: String?): String? {
    if (value == null) return null
    val withoutQueryOrFragment = value.substringBefore('?').substringBefore('#')
    val schemeSeparator = withoutQueryOrFragment.indexOf("://")
    val authorityStart = if (schemeSeparator >= 0) schemeSeparator + 3 else 0
    val authorityEnd = withoutQueryOrFragment.indexOf('/', startIndex = authorityStart)
        .takeIf { it >= 0 }
        ?: withoutQueryOrFragment.length
    val origin = withoutQueryOrFragment.substring(0, authorityEnd)
    val userInfoEnd = origin.lastIndexOf('@')
    if (userInfoEnd < authorityStart) return origin
    return origin.substring(0, authorityStart) + "<redacted>@" + origin.substring(userInfoEnd + 1)
}

private fun String.isLoopbackHost(): Boolean {
    val normalized = lowercase().trim('[', ']')
    return normalized == "localhost" ||
        normalized == "127.0.0.1" ||
        normalized == "::1"
}

private const val MAX_HTTP_ENDPOINT_CHARS = 16_384
private const val UTF8_BOM = "\uFEFF"

private val IMF_FIXDATE = Regex(
    "^[A-Za-z]{3}, ([0-9]{2}) ([A-Za-z]{3}) ([0-9]{4}) ([0-9]{2}):([0-9]{2}):([0-9]{2}) GMT$",
)

private val MONTHS = mapOf(
    "jan" to 1,
    "feb" to 2,
    "mar" to 3,
    "apr" to 4,
    "may" to 5,
    "jun" to 6,
    "jul" to 7,
    "aug" to 8,
    "sep" to 9,
    "oct" to 10,
    "nov" to 11,
    "dec" to 12,
)
