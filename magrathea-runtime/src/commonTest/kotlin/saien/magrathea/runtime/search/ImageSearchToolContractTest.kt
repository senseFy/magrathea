package saien.magrathea.runtime.search

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import saien.magrathea.core.AgentMessage
import saien.magrathea.core.AgentRunId
import saien.magrathea.core.AgentSessionId
import saien.magrathea.core.MessageRole
import saien.magrathea.core.RemoteToolImageSource
import saien.magrathea.core.ToolCallPart
import saien.magrathea.core.ToolExecutionRequest
import saien.magrathea.core.ToolRecoveryPolicy
import saien.magrathea.core.ToolResultAudience
import saien.magrathea.core.ToolResultImageContent
import saien.magrathea.core.citations
import saien.magrathea.runtime.TestFatalError

class ImageSearchToolContractTest {
    @Test
    fun policyRejectsAmbiguousOrUnboundedConfiguration() {
        assertFailsWith<IllegalArgumentException> { ImageSearchPolicy(maxSearchCallsPerRun = 0) }
        assertFailsWith<IllegalArgumentException> { ImageSearchPolicy(maxResultsPerQuery = 51) }
        assertFailsWith<IllegalArgumentException> {
            ImageSearchPolicy(
                allowedDomains = listOf("example.com"),
                blockedDomains = listOf("other.example"),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            ImageSearchPolicy(allowedDomains = listOf("https://example.com"))
        }
    }

    @Test
    fun definitionContainsStableSchemaAndHardRuntimeLimits() {
        val backend = ImageSearchBackend { ImageSearchBackendResponse(emptyList()) }
        val tool = ImageSearchTool(
            backend = backend,
            policy = ImageSearchPolicy(maxSearchCallsPerRun = 2, maxQueryChars = 240, timeoutMs = 4_000),
            requiresPermission = "network",
            requiresApproval = true,
        )
        val failClosed = ImageSearchTool(
            backend = backend,
            recoveryPolicy = ToolRecoveryPolicy.FAIL_CLOSED,
        )

        assertEquals(ImageSearchTool.NAME, tool.definition.name)
        assertEquals(ToolRecoveryPolicy.REPLAY_SAFE, tool.recoveryPolicy)
        assertEquals(ToolRecoveryPolicy.FAIL_CLOSED, failClosed.recoveryPolicy)
        assertTrue(tool.definition.description.contains("mediaReference"))
        assertFalse(tool.definition.description.contains("Markdown"))
        assertEquals(2, tool.definition.maxCallsPerTurn)
        assertEquals(2, tool.definition.maxCallsPerRun)
        assertEquals(4_000, tool.definition.timeoutMs)
        assertEquals("network", tool.definition.requiresPermission)
        assertTrue(tool.definition.requiresApproval)
        assertEquals(
            240,
            tool.definition.schema["properties"]?.jsonObject
                ?.get("query")?.jsonObject?.get("maxLength")?.jsonPrimitive?.content?.toInt(),
        )
    }

    @Test
    fun backendPolicyAndExternalResultsBecomeBoundedAttributedUserImages() = runTest {
        var observed: ImageSearchBackendRequest? = null
        val backend = ImageSearchBackend { request ->
            observed = request
            ImageSearchBackendResponse(
                listOf(
                    source(
                        imageUrl = "https://cdn.example.com/first.jpg",
                        sourcePageUrl = "https://docs.example.com/first",
                        title = "  First\nimage ",
                        description = "  first\u0000 description ",
                        thumbnailUrl = "https://cdn.example.com/first-thumb.jpg",
                    ),
                    source(
                        imageUrl = "https://cdn.example.com/first.jpg#duplicate",
                        sourcePageUrl = "https://docs.example.com/duplicate",
                    ),
                    source(
                        imageUrl = "https://cdn.example.com/blocked.jpg",
                        sourcePageUrl = "https://other.example/blocked",
                    ),
                    source(
                        imageUrl = "http://cdn.example.com/insecure.jpg",
                        sourcePageUrl = "https://docs.example.com/insecure",
                    ),
                    source(
                        imageUrl = "https://cdn.example.com/second.png",
                        sourcePageUrl = "https://example.com/second",
                        title = "Second",
                        description = "x".repeat(100),
                        mimeType = "IMAGE/PNG",
                    ),
                ),
            )
        }
        val policy = ImageSearchPolicy(
            maxResultsPerQuery = 5,
            maxDescriptionChars = 32,
            freshness = SearchFreshness.PAST_WEEK,
            allowedDomains = listOf("example.com"),
            locale = SearchLocale(languageTag = "en-US", countryCode = "US"),
            location = SearchLocation(city = "San Francisco", countryCode = "US"),
            safeSearch = SearchSafeSearch.STRICT,
            timeoutMs = 9_000,
        )

        val result = ImageSearchTool(backend, policy).execute(executionRequest("  current design  "))

        assertFalse(result.isError)
        assertEquals("current design", observed?.query)
        assertEquals(5, observed?.maxResults)
        assertEquals(SearchFreshness.PAST_WEEK, observed?.freshness)
        assertEquals(SearchSafeSearch.STRICT, observed?.safeSearch)
        assertFalse(observed.toString().contains("current design"))
        assertFalse(observed.toString().contains("San Francisco"))

        val payload = result.result.jsonObject
        assertEquals("untrusted_external_content", payload["contentSafety"]?.jsonPrimitive?.content)
        assertEquals(2, payload["sourceCount"]?.jsonPrimitive?.content?.toInt())
        assertTrue(payload["truncated"]?.jsonPrimitive?.content?.toBoolean() == true)
        assertEquals(
            listOf("First image", "Second"),
            payload.getValue("sources").jsonArray.map {
                it.jsonObject.getValue("title").jsonPrimitive.content
            },
        )
        assertEquals(32, payload.getValue("sources").jsonArray.last()
            .jsonObject.getValue("description").jsonPrimitive.content.length)
        assertEquals(listOf("First image", "Second"), result.citations().map { it.title })
        assertEquals("Found 2 images.", result.displayText)

        assertEquals(2, result.content.size)
        val first = assertIs<ToolResultImageContent>(result.content.first())
        val reference = assertNotNull(first.reference)
        assertEquals(setOf(ToolResultAudience.USER), first.audiences)
        assertEquals("https://cdn.example.com/first.jpg", assertIs<RemoteToolImageSource>(first.source).uri)
        assertEquals(
            "https://cdn.example.com/first-thumb.jpg",
            assertIs<RemoteToolImageSource>(first.previewSource).uri,
        )
        assertEquals("https://docs.example.com/first", first.attribution?.url)
        assertEquals(
            reference.toUri(),
            payload.getValue("sources").jsonArray.first()
                .jsonObject.getValue("mediaReference").jsonPrimitive.content,
        )
    }

    @Test
    fun normalizationFillsResultBudgetAfterRejectedCandidates() = runTest {
        val result = ImageSearchTool(
            backend = ImageSearchBackend {
                ImageSearchBackendResponse(
                    listOf(
                        source(
                            imageUrl = "https://cdn.example.com/first.jpg",
                            sourcePageUrl = "https://example.com/invalid-mime",
                            mimeType = "application/octet-stream",
                        ),
                        source(
                            imageUrl = "https://cdn.example.com/first.jpg",
                            sourcePageUrl = "https://example.com/first",
                            title = "First",
                        ),
                        source(
                            imageUrl = "http://cdn.example.com/insecure.jpg",
                            sourcePageUrl = "https://example.com/insecure",
                        ),
                        source(
                            imageUrl = "https://cdn.example.com/second.jpg",
                            sourcePageUrl = "https://example.com/second",
                            title = "Second",
                        ),
                    ),
                )
            },
            policy = ImageSearchPolicy(maxResultsPerQuery = 2),
        ).execute(executionRequest("design references"))

        val payload = result.result.jsonObject
        assertEquals(2, payload.getValue("sourceCount").jsonPrimitive.content.toInt())
        assertEquals(
            listOf("First", "Second"),
            payload.getValue("sources").jsonArray.map {
                it.jsonObject.getValue("title").jsonPrimitive.content
            },
        )
        assertTrue(payload.getValue("truncated").jsonPrimitive.content.toBoolean())
    }

    @Test
    fun invalidQueryAndBackendFailuresAreBoundedAndContentFree() = runTest {
        var calls = 0
        val rejecting = ImageSearchTool(
            backend = ImageSearchBackend {
                calls += 1
                ImageSearchBackendResponse(emptyList())
            },
            policy = ImageSearchPolicy(maxQueryChars = 4),
        )
        val invalid = rejecting.execute(executionRequest("12345"))
        assertTrue(invalid.isError)
        assertEquals(0, calls)
        assertEquals("invalid-query", invalid.result.jsonObject.getValue("code").jsonPrimitive.content)

        val secret = "IMAGE_BACKEND_SECRET"
        val failed = ImageSearchTool(
            backend = ImageSearchBackend { throw IllegalStateException(secret) },
        ).execute(executionRequest("valid"))
        assertTrue(failed.isError)
        assertFalse(failed.toString().contains(secret))
        assertEquals("unavailable", failed.result.jsonObject.getValue("code").jsonPrimitive.content)

        val rateLimited = ImageSearchTool(
            backend = ImageSearchBackend {
                throw ImageSearchBackendException(
                    ImageSearchFailureCode.RATE_LIMITED,
                    "private backend detail",
                )
            },
        ).execute(executionRequest("valid"))
        assertEquals("rate-limited", rateLimited.result.jsonObject.getValue("code").jsonPrimitive.content)
        assertFalse(rateLimited.toString().contains("private backend detail"))

        val cancelled = ImageSearchTool(
            backend = ImageSearchBackend { throw CancellationException("cancel") },
        )
        assertFailsWith<CancellationException> { cancelled.execute(executionRequest("valid")) }
    }

    @Test
    fun wrappedFatalBackendFailureEscapesExactly() = runTest {
        val fatal = TestFatalError(Any())
        val tool = ImageSearchTool(
            backend = ImageSearchBackend {
                throw ImageSearchBackendException(
                    ImageSearchFailureCode.UNAVAILABLE,
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
        val call = ToolCallPart(
            toolCallId = "image-search-call",
            toolName = ImageSearchTool.NAME,
            arguments = arguments,
        )
        return ToolExecutionRequest(
            sessionId = AgentSessionId("image-search-session"),
            runId = AgentRunId("image-search-run"),
            executionId = "image-search-run:image-search-call:0",
            assistantMessage = AgentMessage(role = MessageRole.ASSISTANT, parts = listOf(call)),
            toolCall = call,
        )
    }

    private fun source(
        imageUrl: String,
        sourcePageUrl: String,
        title: String? = "Image",
        description: String? = "description",
        thumbnailUrl: String? = null,
        mimeType: String? = "image/jpeg",
    ): ImageSearchSource = ImageSearchSource(
        imageUrl = imageUrl,
        sourcePageUrl = sourcePageUrl,
        title = title,
        description = description,
        thumbnailUrl = thumbnailUrl,
        mimeType = mimeType,
        publisher = "Example",
    )
}
