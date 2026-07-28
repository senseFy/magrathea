package saien.magrathea.mcp

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondOk
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlinx.coroutines.test.runTest

class McpTransportSecurityTest {
    private val client = HttpClient(MockEngine { respondOk() })

    @Test
    fun permitsHttpsAndLoopbackHttp() {
        streamableHttpMcpTransportFactory(client, "https://mcp.example.com/v1")
        streamableHttpMcpTransportFactory(client, "http://127.0.0.1:3000/mcp")
        streamableHttpMcpTransportFactory(client, "http://localhost:3000/mcp")
    }

    @Test
    fun rejectsPlaintextRemoteAndCredentialedEndpoints() {
        assertFailsWith<IllegalArgumentException> {
            streamableHttpMcpTransportFactory(client, "http://mcp.example.com/v1")
        }
        assertFailsWith<IllegalArgumentException> {
            streamableHttpMcpTransportFactory(client, "https://token@mcp.example.com/v1")
        }
        assertFailsWith<IllegalArgumentException> {
            streamableHttpMcpTransportFactory(client, "https://mcp.example.com/v1#ignored")
        }
    }

    @Test
    fun rejectsReservedInvalidAndCaseDuplicateHeaders() = runTest {
        assertFailsWith<IllegalArgumentException> {
            streamableHttpMcpTransportFactory(
                client,
                "https://mcp.example.com/v1",
                McpRequestHeadersProvider { mapOf("Mcp-Session-Id" to "host-owned") },
            ).create()
        }
        assertFailsWith<IllegalArgumentException> {
            streamableHttpMcpTransportFactory(
                client,
                "https://mcp.example.com/v1",
                McpRequestHeadersProvider { mapOf("Bad Header" to "value") },
            ).create()
        }
        assertFailsWith<IllegalArgumentException> {
            streamableHttpMcpTransportFactory(
                client,
                "https://mcp.example.com/v1",
                McpRequestHeadersProvider {
                    mapOf("X-Token" to "first", "x-token" to "second")
                },
            ).create()
        }
    }
}
