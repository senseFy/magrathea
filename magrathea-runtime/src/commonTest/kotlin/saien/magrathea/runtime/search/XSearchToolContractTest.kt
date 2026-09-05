package saien.magrathea.runtime.search

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import saien.magrathea.core.AgentMessage
import saien.magrathea.core.AgentSessionId
import saien.magrathea.core.AgentRunId
import saien.magrathea.core.Citation
import saien.magrathea.core.MessageRole
import saien.magrathea.core.ToolCallPart
import saien.magrathea.core.ToolExecutionRequest
import saien.magrathea.core.ToolRecoveryPolicy
import saien.magrathea.core.citations
import saien.magrathea.runtime.TestFatalError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class XSearchToolContractTest {
    @Test
    fun policyRejectsAmbiguousOrUnboundedConfiguration() {
        assertFailsWith<IllegalArgumentException> { XSearchPolicy(maxSearchCallsPerRun = 0) }
        assertFailsWith<IllegalArgumentException> { XSearchPolicy(maxHandlesPerRequest = 21) }
        assertFailsWith<IllegalArgumentException> {
            XSearchPolicy(allowedHandles = listOf("xai"), excludedHandles = listOf("spam"))
        }
        assertFailsWith<IllegalArgumentException> {
            XSearchPolicy(allowedHandles = listOf("@xai"))
        }
        assertFailsWith<IllegalArgumentException> {
            XSearchPolicy(allowedHandles = listOf("xai", "xai"))
        }
    }

    @Test
    fun definitionContainsStableSchemaAndHardRuntimeLimits() {
        val backend = XSearchBackend { XSearchEvidence("No evidence.") }
        val tool = XSearchTool(
            backend = backend,
            policy = XSearchPolicy(
                maxSearchCallsPerRun = 2,
                maxQueryChars = 240,
                maxHandlesPerRequest = 10,
                timeoutMs = 4_000,
            ),
            requiresPermission = "network",
            requiresApproval = true,
        )
        val failClosed = XSearchTool(
            backend = backend,
            recoveryPolicy = ToolRecoveryPolicy.FAIL_CLOSED,
        )

        assertEquals(XSearchTool.NAME, tool.definition.name)
        assertEquals(ToolRecoveryPolicy.REPLAY_SAFE, tool.recoveryPolicy)
        assertEquals(ToolRecoveryPolicy.FAIL_CLOSED, failClosed.recoveryPolicy)
        assertEquals(2, tool.definition.maxCallsPerTurn)
        assertEquals(2, tool.definition.maxCallsPerRun)
        assertEquals(4_000, tool.definition.timeoutMs)
        assertEquals("network", tool.definition.requiresPermission)
        assertTrue(tool.definition.requiresApproval)
        val properties = tool.definition.schema.getValue("properties").jsonObject
        assertEquals(240, properties.getValue("query").jsonObject.getValue("maxLength").jsonPrimitive.content.toInt())
        assertEquals(
            10,
            properties.getValue("allowed_handles").jsonObject.getValue("maxItems").jsonPrimitive.content.toInt(),
        )
        assertEquals(false, tool.definition.schema.getValue("additionalProperties").jsonPrimitive.content.toBoolean())
    }

    @Test
    fun backendReceivesValidatedFiltersAndEvidenceBecomesCanonicalCitations() = runTest {
        var observed: XSearchBackendRequest? = null
        val tool = XSearchTool(
            backend = XSearchBackend { request ->
                observed = request
                XSearchEvidence(
                    text = "  Current discussion.\nSecond line.  ",
                    citations = listOf(
                        Citation("Post", "https://x.com/xai/status/1", "  first\npost "),
                        Citation("Duplicate", "https://x.com/xai/status/1#reply", "duplicate"),
                        Citation("Insecure", "http://x.com/xai/status/2", "insecure"),
                        Citation("", "https://x.com/kotlin/status/3", "second"),
                    ),
                )
            },
            policy = XSearchPolicy(
                maxCitationsInContext = 2,
                enableImageUnderstanding = true,
                timeoutMs = 9_000,
            ),
        )

        val result = tool.execute(
            executionRequest(
                buildJsonObject {
                    put("query", "  Kotlin KMP reactions  ")
                    put("from_date", "2026-07-01")
                    put("to_date", "2026-07-16")
                    put("allowed_handles", buildJsonArray {
                        add(JsonPrimitive("xai"))
                        add(JsonPrimitive("kotlin"))
                    })
                },
            ),
        )

        assertFalse(result.isError)
        assertEquals("Kotlin KMP reactions", observed?.query)
        assertEquals("2026-07-01", observed?.fromDate)
        assertEquals("2026-07-16", observed?.toDate)
        assertEquals(listOf("xai", "kotlin"), observed?.allowedHandles)
        assertEquals(emptyList(), observed?.excludedHandles)
        assertEquals(true, observed?.enableImageUnderstanding)
        assertEquals(false, observed?.enableVideoUnderstanding)
        assertEquals(9_000, observed?.timeoutMs)
        assertFalse(observed.toString().contains("Kotlin KMP reactions"))

        val payload = result.result.jsonObject
        assertEquals("untrusted_external_content", payload.getValue("contentSafety").jsonPrimitive.content)
        assertEquals("Current discussion.\nSecond line.", payload.getValue("text").jsonPrimitive.content)
        assertEquals(2, payload.getValue("sourceCount").jsonPrimitive.content.toInt())
        assertTrue(payload.getValue("truncated").jsonPrimitive.content.toBoolean())
        assertEquals(
            listOf("https://x.com/xai/status/1", "https://x.com/kotlin/status/3"),
            payload.getValue("sources").jsonArray.map { source ->
                source.jsonObject.getValue("url").jsonPrimitive.content
            },
        )
        assertEquals("https://x.com/kotlin/status/3", result.citations().last().title)
        assertEquals("Searched X · 2 sources.", result.displayText)
    }

    @Test
    fun hostPolicyCanOnlyBeNarrowedByTheModel() = runTest {
        var observed: XSearchBackendRequest? = null
        val tool = XSearchTool(
            backend = XSearchBackend { request ->
                observed = request
                XSearchEvidence("Evidence")
            },
            policy = XSearchPolicy(allowedHandles = listOf("xai", "kotlin")),
        )

        val narrowed = tool.execute(
            executionRequest(
                buildJsonObject {
                    put("query", "release")
                    put("allowed_handles", buildJsonArray { add(JsonPrimitive("kotlin")) })
                },
            ),
        )
        assertFalse(narrowed.isError)
        assertEquals(listOf("kotlin"), observed?.allowedHandles)

        val expanded = tool.execute(
            executionRequest(
                buildJsonObject {
                    put("query", "release")
                    put("allowed_handles", buildJsonArray { add(JsonPrimitive("other")) })
                },
            ),
        )
        assertTrue(expanded.isError)
        assertEquals(
            "unsupported-policy",
            expanded.result.jsonObject.getValue("code").jsonPrimitive.content,
        )
    }

    @Test
    fun invalidArgumentsFailBeforeBackend() = runTest {
        var calls = 0
        val tool = XSearchTool(
            backend = XSearchBackend {
                calls += 1
                XSearchEvidence("Evidence")
            },
            policy = XSearchPolicy(maxQueryChars = 4),
        )

        val invalidQuery = tool.execute(executionRequest(buildJsonObject { put("query", "12345") }))
        val invalidDate = tool.execute(
            executionRequest(
                buildJsonObject {
                    put("query", "okay")
                    put("from_date", "2026-02-30")
                },
            ),
        )
        val reversedDate = tool.execute(
            executionRequest(
                buildJsonObject {
                    put("query", "okay")
                    put("from_date", "2026-07-16")
                    put("to_date", "2026-07-01")
                },
            ),
        )
        val conflictingHandles = tool.execute(
            executionRequest(
                buildJsonObject {
                    put("query", "okay")
                    put("allowed_handles", buildJsonArray { add(JsonPrimitive("xai")) })
                    put("excluded_handles", buildJsonArray { add(JsonPrimitive("spam")) })
                },
            ),
        )
        val unexpected = tool.execute(
            executionRequest(
                buildJsonObject {
                    put("query", "okay")
                    put("max_results", 50)
                },
            ),
        )

        assertTrue(invalidQuery.isError)
        assertTrue(invalidDate.isError)
        assertTrue(reversedDate.isError)
        assertTrue(conflictingHandles.isError)
        assertTrue(unexpected.isError)
        assertEquals(0, calls)
    }

    @Test
    fun backendFailuresAreContentFreeAndCancellationPropagates() = runTest {
        val privateDetail = "XAI_PRIVATE_FAILURE"
        val rateLimited = XSearchTool(
            backend = XSearchBackend {
                throw XSearchBackendException(XSearchFailureCode.RATE_LIMITED, privateDetail)
            },
        ).execute(executionRequest(buildJsonObject { put("query", "valid") }))

        assertEquals("rate-limited", rateLimited.result.jsonObject.getValue("code").jsonPrimitive.content)
        assertFalse(rateLimited.toString().contains(privateDetail))

        val malformed = XSearchTool(
            backend = XSearchBackend { XSearchEvidence("   ") },
        ).execute(executionRequest(buildJsonObject { put("query", "valid") }))
        assertEquals("malformed-response", malformed.result.jsonObject.getValue("code").jsonPrimitive.content)

        val outputLimited = XSearchTool(
            backend = XSearchBackend {
                throw XSearchBackendException(XSearchFailureCode.OUTPUT_LIMIT)
            },
        ).execute(executionRequest(buildJsonObject { put("query", "valid") }))
        assertEquals("output-limit", outputLimited.result.jsonObject.getValue("code").jsonPrimitive.content)

        val cancelled = XSearchTool(
            backend = XSearchBackend { throw CancellationException("cancel") },
        )
        assertFailsWith<CancellationException> {
            cancelled.execute(executionRequest(buildJsonObject { put("query", "valid") }))
        }
    }

    @Test
    fun wrappedFatalBackendFailureEscapesExactly() = runTest {
        val fatal = TestFatalError(Any())
        val tool = XSearchTool(
            backend = XSearchBackend {
                throw XSearchBackendException(
                    XSearchFailureCode.UNAVAILABLE,
                    cause = fatal,
                )
            },
        )

        val escaped = runCatching {
            tool.execute(executionRequest(buildJsonObject { put("query", "valid") }))
        }.exceptionOrNull()

        assertSame(fatal, escaped)
    }

    private fun executionRequest(arguments: JsonObject): ToolExecutionRequest {
        val toolCall = ToolCallPart(
            toolCallId = "x-search-call",
            toolName = XSearchTool.NAME,
            arguments = arguments,
        )
        return ToolExecutionRequest(
            sessionId = AgentSessionId("x-search-session"),
            runId = AgentRunId("x-search-run"),
            executionId = "x-search-run:x-search-call:0",
            assistantMessage = AgentMessage(role = MessageRole.ASSISTANT, parts = listOf(toolCall)),
            toolCall = toolCall,
        )
    }
}
