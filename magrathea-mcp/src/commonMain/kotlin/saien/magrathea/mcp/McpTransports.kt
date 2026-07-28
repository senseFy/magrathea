package saien.magrathea.mcp

import io.ktor.client.HttpClient
import io.ktor.client.request.headers
import io.ktor.http.HttpHeaders
import io.ktor.http.URLProtocol
import io.ktor.http.Url
import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpClientTransport
import io.modelcontextprotocol.kotlin.sdk.shared.Transport

class McpTransportHandle(
    val transport: Transport,
    private val terminateSession: suspend () -> Unit = {},
    private val releaseResources: suspend () -> Unit = {},
) {
    internal suspend fun terminate() = terminateSession()

    internal suspend fun release() = releaseResources()
}

fun interface McpTransportFactory {
    suspend fun create(): McpTransportHandle
}

fun interface McpRequestHeadersProvider {
    suspend fun headers(): Map<String, String>
}

/**
 * Builds the current MCP Streamable HTTP transport.
 *
 * Plain HTTP is accepted only for loopback hosts. Header values are resolved for each new
 * connection and are not retained in [McpServer] or any Agent/session payload.
 */
fun streamableHttpMcpTransportFactory(
    client: HttpClient,
    endpoint: String,
    headersProvider: McpRequestHeadersProvider = McpRequestHeadersProvider { emptyMap() },
): McpTransportFactory {
    requireValidMcpStreamableHttpEndpoint(endpoint)
    return McpTransportFactory {
        val requestHeaders = headersProvider.headers().also(::validateRequestHeaders)
        val transport = StreamableHttpClientTransport(
            client = client,
            url = endpoint,
            requestBuilder = {
                headers {
                    requestHeaders.forEach { (name, value) -> append(name, value) }
                }
            },
        )
        McpTransportHandle(
            transport = transport,
            terminateSession = { transport.terminateSession() },
        )
    }
}

/**
 * Validates the endpoint policy used by [streamableHttpMcpTransportFactory].
 *
 * Hosts can call this before persisting a server profile so an invalid or insecure endpoint is
 * rejected at the configuration boundary instead of during the first connection attempt.
 */
fun requireValidMcpStreamableHttpEndpoint(endpoint: String) {
    require(endpoint == endpoint.trim()) { "MCP endpoint must be trimmed" }
    val url = runCatching { Url(endpoint) }
        .getOrElse { throw IllegalArgumentException("MCP endpoint must be a valid URL", it) }
    require(url.protocol == URLProtocol.HTTPS || url.protocol == URLProtocol.HTTP) {
        "MCP Streamable HTTP endpoint must use HTTP or HTTPS"
    }
    if (url.protocol == URLProtocol.HTTP) {
        require(url.host.isLoopbackHost()) {
            "Plain HTTP MCP endpoints are allowed only on loopback hosts"
        }
    }
    require(url.fragment.isEmpty()) { "MCP endpoint must not contain a URL fragment" }
    require('@' !in endpoint.substringAfter("://").substringBefore('/')) {
        "MCP endpoint must not contain userinfo credentials"
    }
}

private fun String.isLoopbackHost(): Boolean {
    val normalized = lowercase().trim('[', ']')
    return normalized == "localhost" ||
        normalized == "127.0.0.1" ||
        normalized == "::1"
}

private fun validateRequestHeaders(headers: Map<String, String>) {
    require(headers.size <= 32) { "MCP request header count exceeded the configured limit" }
    val reserved = setOf(
        HttpHeaders.Accept.lowercase(),
        HttpHeaders.ContentType.lowercase(),
        HttpHeaders.Host.lowercase(),
        "mcp-session-id",
        "mcp-protocol-version",
        "last-event-id",
    )
    val normalizedNames = mutableSetOf<String>()
    headers.forEach { (name, value) ->
        require(name.isHttpToken() && name.length <= 128) {
            "MCP request header name is invalid"
        }
        require(
            value.isNotBlank() &&
                value.length <= 16_384 &&
                value.all { character ->
                    character == '\t' || character.code in 0x20..0x7e
                },
        ) {
            "MCP request header value is invalid"
        }
        val normalizedName = name.lowercase()
        require(normalizedNames.add(normalizedName)) {
            "MCP request header names must be unique ignoring case"
        }
        require(normalizedName !in reserved) { "MCP transport owns the $name header" }
    }
}

private fun String.isHttpToken(): Boolean =
    isNotEmpty() &&
        all { character ->
            character in 'a'..'z' ||
                character in 'A'..'Z' ||
                character in '0'..'9' ||
                character in setOf(
                    '!', '#', '$', '%', '&', '\'', '*', '+', '-', '.', '^', '_', '`', '|', '~',
                )
        }
