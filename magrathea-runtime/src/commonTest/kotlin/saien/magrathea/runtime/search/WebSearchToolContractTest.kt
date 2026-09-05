package saien.magrathea.runtime.search

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import saien.magrathea.core.AgentMessage
import saien.magrathea.core.AgentSessionId
import saien.magrathea.core.AgentRunId
import saien.magrathea.core.MessageRole
import saien.magrathea.core.SharedToolExecutionPermit
import saien.magrathea.core.ToolCallPart
import saien.magrathea.core.ToolExecutionRequest
import saien.magrathea.core.ToolRecoveryPolicy
import saien.magrathea.core.UnlimitedToolExecutionPermit
import saien.magrathea.core.citations
import saien.magrathea.runtime.TestFatalError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class WebSearchToolContractTest {
    @Test
    fun toolDelegatesRequestAdmissionToItsBackend() {
        val permit = SharedToolExecutionPermit(maxConcurrentExecutions = 1)
        val backend = object : WebSearchBackend {
            override val executionPermit = permit

            override suspend fun search(request: WebSearchBackendRequest) =
                WebSearchBackendResponse(emptyList())
        }
        val defaultBackend = WebSearchBackend { WebSearchBackendResponse(emptyList()) }

        assertSame(permit, WebSearchTool(backend).executionPermit(executionRequest("query")))
        assertSame(UnlimitedToolExecutionPermit, defaultBackend.executionPermit)
    }

    @Test
    fun policyRejectsAmbiguousOrUnboundedConfiguration() {
        assertFailsWith<IllegalArgumentException> { WebSearchPolicy(maxSearchCallsPerRun = 0) }
        assertFailsWith<IllegalArgumentException> { WebSearchPolicy(maxResultsPerQuery = 51) }
        assertFailsWith<IllegalArgumentException> {
            WebSearchPolicy(maxResultsPerQuery = 2, maxSourcesInContext = 3)
        }
        assertFailsWith<IllegalArgumentException> {
            WebSearchPolicy(allowedDomains = listOf("example.com"), blockedDomains = listOf("other.example"))
        }
        assertFailsWith<IllegalArgumentException> { WebSearchPolicy(allowedDomains = listOf("https://example.com")) }
        assertFailsWith<IllegalArgumentException> { SearchLocale(languageTag = "en_us") }
        assertFailsWith<IllegalArgumentException> { SearchLocation(city = " ") }
    }

    @Test
    fun definitionContainsStableSchemaAndHardRuntimeLimits() {
        val backend = WebSearchBackend { WebSearchBackendResponse(emptyList()) }
        val tool = WebSearchTool(
            backend = backend,
            policy = WebSearchPolicy(maxSearchCallsPerRun = 2, maxQueryChars = 240, timeoutMs = 4_000),
            requiresPermission = "network",
            requiresApproval = true,
        )
        val failClosed = WebSearchTool(
            backend = backend,
            recoveryPolicy = ToolRecoveryPolicy.FAIL_CLOSED,
        )

        assertEquals(WebSearchTool.NAME, tool.definition.name)
        assertEquals(ToolRecoveryPolicy.REPLAY_SAFE, tool.recoveryPolicy)
        assertEquals(ToolRecoveryPolicy.FAIL_CLOSED, failClosed.recoveryPolicy)
        assertEquals(2, tool.definition.maxCallsPerTurn)
        assertEquals(2, tool.definition.maxCallsPerRun)
        assertEquals(4_000, tool.definition.timeoutMs)
        assertEquals("network", tool.definition.requiresPermission)
        assertTrue(tool.definition.requiresApproval)
        assertEquals(240, tool.definition.schema["properties"]?.jsonObject
            ?.get("query")?.jsonObject?.get("maxLength")?.jsonPrimitive?.content?.toInt())
        assertEquals(false, tool.definition.schema["additionalProperties"]?.jsonPrimitive?.content?.toBoolean())
    }

    @Test
    fun backendReceivesStructuredPolicyAndResultsBecomeBoundedCitations() = runTest {
        var observed: WebSearchBackendRequest? = null
        val backend = WebSearchBackend { request ->
            observed = request
            WebSearchBackendResponse(
                listOf(
                    source("First", "https://docs.example.com/first", "  first\n\u0000 snippet  "),
                    source("Duplicate", "https://docs.example.com/first#section", "duplicate"),
                    source("Blocked by allow list", "https://other.example/result", "other"),
                    source("Insecure", "http://docs.example.com/insecure", "http"),
                    source("Second", "https://example.com/second", "x".repeat(100)),
                    source("Third", "https://example.com/third", "third"),
                ),
            )
        }
        val policy = WebSearchPolicy(
            maxResultsPerQuery = 5,
            maxSourcesInContext = 2,
            maxSnippetChars = 64,
            depth = WebSearchDepth.DEEP,
            freshness = SearchFreshness.PAST_WEEK,
            allowedDomains = listOf("example.com"),
            locale = SearchLocale(languageTag = "en-US", countryCode = "US"),
            location = SearchLocation(city = "San Francisco", countryCode = "US"),
            safeSearch = SearchSafeSearch.STRICT,
            timeoutMs = 9_000,
        )
        val result = WebSearchTool(backend, policy).execute(executionRequest("  current release  "))

        assertFalse(result.isError)
        assertEquals("current release", observed?.query)
        assertEquals(5, observed?.maxResults)
        assertEquals(WebSearchDepth.DEEP, observed?.depth)
        assertEquals(SearchFreshness.PAST_WEEK, observed?.freshness)
        assertEquals(listOf("example.com"), observed?.allowedDomains)
        assertEquals(SearchSafeSearch.STRICT, observed?.safeSearch)
        assertEquals(9_000, observed?.timeoutMs)
        assertFalse(observed.toString().contains("current release"))
        assertFalse(observed.toString().contains("San Francisco"))

        val payload = result.result.jsonObject
        assertEquals("untrusted_external_content", payload["contentSafety"]?.jsonPrimitive?.content)
        assertEquals(2, payload["sourceCount"]?.jsonPrimitive?.content?.toInt())
        assertTrue(payload["truncated"]?.jsonPrimitive?.content?.toBoolean() == true)
        val sources = payload.getValue("sources").jsonArray
        assertEquals(listOf("First", "Second"), sources.map { it.jsonObject.getValue("title").jsonPrimitive.content })
        assertEquals("first snippet", sources.first().jsonObject.getValue("snippet").jsonPrimitive.content)
        assertEquals(64, sources.last().jsonObject.getValue("snippet").jsonPrimitive.content.length)
        assertEquals(listOf("First", "Second"), result.citations().map { it.title })
        assertEquals("Found 2 web sources.", result.displayText)
    }

    @Test
    fun blockedDomainsAreEnforcedOnReturnedSources() = runTest {
        val tool = WebSearchTool(
            backend = WebSearchBackend {
                WebSearchBackendResponse(
                    listOf(
                        source("Allowed", "https://allowed.example/page"),
                        source("Blocked", "https://private.blocked.example/page"),
                    ),
                )
            },
            policy = WebSearchPolicy(blockedDomains = listOf("blocked.example")),
        )

        val result = tool.execute(executionRequest("query"))

        assertEquals(listOf("Allowed"), result.citations().map { it.title })
        assertTrue(result.result.jsonObject.getValue("truncated").jsonPrimitive.content.toBoolean())
    }

    @Test
    fun backendOverproductionCannotBypassTheRawCandidateLimit() = runTest {
        val tool = WebSearchTool(
            backend = WebSearchBackend {
                WebSearchBackendResponse(
                    listOf(
                        source("Insecure", "http://example.com/insecure"),
                        source("Within budget", "https://example.com/within-budget"),
                        source("Beyond budget", "https://example.com/beyond-budget"),
                    ),
                )
            },
            policy = WebSearchPolicy(maxResultsPerQuery = 2, maxSourcesInContext = 2),
        )

        val result = tool.execute(executionRequest("query"))

        assertEquals(listOf("Within budget"), result.citations().map { it.title })
        assertTrue(result.result.jsonObject.getValue("truncated").jsonPrimitive.content.toBoolean())
    }

    @Test
    fun toolSnapshotsMutableDomainPolicyAtConstruction() = runTest {
        val allowedDomains = mutableListOf("example.com")
        var observedDomains: List<String>? = null
        val tool = WebSearchTool(
            backend = WebSearchBackend { request ->
                observedDomains = request.allowedDomains
                WebSearchBackendResponse(emptyList())
            },
            policy = WebSearchPolicy(allowedDomains = allowedDomains),
        )
        allowedDomains += "mutated.example"

        tool.execute(executionRequest("query"))

        assertEquals(listOf("example.com"), tool.policy.allowedDomains)
        assertEquals(listOf("example.com"), observedDomains)
    }

    @Test
    fun invalidQueryFailsBeforeBackendAndBackendFailuresAreContentFree() = runTest {
        var calls = 0
        val rejectingTool = WebSearchTool(
            backend = WebSearchBackend {
                calls += 1
                WebSearchBackendResponse(emptyList())
            },
            policy = WebSearchPolicy(maxQueryChars = 4),
        )
        val invalid = rejectingTool.execute(executionRequest("12345"))

        assertTrue(invalid.isError)
        assertEquals(0, calls)
        assertEquals("invalid-query", invalid.result.jsonObject.getValue("code").jsonPrimitive.content)

        val unexpectedArgument = rejectingTool.execute(
            executionRequest("ok", buildJsonObject {
                put("query", "ok")
                put("maxResults", 50)
            }),
        )
        assertTrue(unexpectedArgument.isError)
        assertEquals(0, calls)

        val secret = "SEARCH_BACKEND_SECRET"
        val failed = WebSearchTool(
            backend = WebSearchBackend { throw IllegalStateException(secret) },
        ).execute(executionRequest("valid"))

        assertTrue(failed.isError)
        assertFalse(failed.toString().contains(secret))
        assertEquals("unavailable", failed.result.jsonObject.getValue("code").jsonPrimitive.content)
    }

    @Test
    fun typedBackendFailureIsPreservedAndCancellationPropagates() = runTest {
        val rateLimited = WebSearchTool(
            backend = WebSearchBackend {
                throw WebSearchBackendException(WebSearchFailureCode.RATE_LIMITED, "private backend detail")
            },
        ).execute(executionRequest("valid"))

        assertEquals("rate-limited", rateLimited.result.jsonObject.getValue("code").jsonPrimitive.content)
        assertFalse(rateLimited.toString().contains("private backend detail"))

        val cancelled = WebSearchTool(
            backend = WebSearchBackend { throw CancellationException("cancel") },
        )
        assertFailsWith<CancellationException> { cancelled.execute(executionRequest("valid")) }
    }

    @Test
    fun wrappedFatalBackendFailureEscapesExactly() = runTest {
        val fatal = TestFatalError(Any())
        val tool = WebSearchTool(
            backend = WebSearchBackend {
                throw WebSearchBackendException(
                    WebSearchFailureCode.UNAVAILABLE,
                    cause = fatal,
                )
            },
        )

        val escaped = runCatching { tool.execute(executionRequest("valid")) }.exceptionOrNull()

        assertSame(fatal, escaped)
    }

    private fun executionRequest(
        query: String,
        arguments: JsonObject = buildJsonObject { put("query", query) },
    ): ToolExecutionRequest {
        val toolCall = ToolCallPart(
            toolCallId = "search-call",
            toolName = WebSearchTool.NAME,
            arguments = arguments,
        )
        return ToolExecutionRequest(
            sessionId = AgentSessionId("search-session"),
            runId = AgentRunId("search-run"),
            executionId = "search-run:search-call:0",
            assistantMessage = AgentMessage(role = MessageRole.ASSISTANT, parts = listOf(toolCall)),
            toolCall = toolCall,
        )
    }

    private fun source(
        title: String,
        url: String,
        snippet: String = "snippet",
    ) = WebSearchSource(title = title, url = url, snippet = snippet)
}
