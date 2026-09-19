package saien.magrathea.mcp

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class McpHttpFailureContractTest {
    @Test
    fun officialHttpTransportPreservesNetworkFailureClassification() = runBlocking {
        val client = HttpClient(MockEngine { throw IOException("network-canary") })
        val connection = McpServerConnection(
            McpServer("http", "HTTP"),
            streamableHttpMcpTransportFactory(client, "https://mcp.example.com/mcp"),
        )
        try {
            val failure = assertFailsWith<McpOperationException> { connection.connect() }
            assertEquals(McpConnectionFailure.TRANSPORT, failure.reason)
            assertNull(failure.cause)
            assertTrue(failure.suppressedExceptions.isEmpty())
            assertTrue("canary" !in failure.toString())
        } finally {
            connection.close()
            client.close()
        }
    }

    @Test
    fun officialHttpTransportPreservesStatusThroughItsExceptionWrapping() = runBlocking {
        for ((status, expected) in listOf(
            429 to McpConnectionFailure.RATE_LIMITED,
            401 to McpConnectionFailure.AUTHENTICATION,
            403 to McpConnectionFailure.AUTHENTICATION,
            503 to McpConnectionFailure.TRANSPORT,
        )) {
            val client = HttpClient(MockEngine { respond("raw-body-canary", HttpStatusCode.fromValue(status)) })
            val connection = McpServerConnection(
                McpServer("http", "HTTP"),
                streamableHttpMcpTransportFactory(client, "https://mcp.example.com/mcp"),
            )
            try {
                val failure = assertFailsWith<McpOperationException> { connection.connect() }
                assertEquals(expected, failure.reason)
                assertNull(failure.cause)
                assertTrue(failure.suppressedExceptions.isEmpty())
                assertTrue("canary" !in failure.toString())
            } finally {
                connection.close()
                client.close()
            }
        }
    }
}
