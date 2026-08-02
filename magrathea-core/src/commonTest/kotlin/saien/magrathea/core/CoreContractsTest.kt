package saien.magrathea.core

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CoreContractsTest {
    @Test
    fun createSessionIdShouldNotBeBlank() {
        assertTrue(AgentSessionId.create().value.isNotBlank())
    }

    @Test
    fun textShouldJoinTextParts() {
        val message = AgentMessage(role = MessageRole.USER, parts = listOf(TextPart("a"), TextPart("b"), JsonPart(JsonObject(emptyMap()))))
        assertTrue(message.text() == "ab")
    }

    @Test
    fun toolExecutionResultShouldExposeDisplayTextAndCitations() {
        val origin = ToolOrigin(
            sourceId = "docs",
            sourceLabel = "Documentation",
            toolId = "search",
            toolLabel = "Search",
        )
        val result = ToolExecutionResult(
            toolCallId = "call-1",
            toolName = "search",
            result = JsonPrimitive("raw output"),
            modelResultVisible = false,
            origin = origin,
            metadata = buildJsonObject {
                put("citations", buildJsonArray {
                    add(buildJsonObject {
                        put("title", "Doc")
                        put("url", "https://example.com")
                        put("snippet", "Excerpt")
                    })
                })
            },
        )

        val part = result.toMessagePart()

        assertEquals("Doc", result.citations().single().title)
        assertNull(part.displayText)
        assertFalse(part.modelResultVisible)
        assertEquals(origin, part.origin)
        assertEquals("https://example.com", part.citations().single().url)
    }

    @Test
    fun toolOriginRejectsUnsafePresentationValues() {
        assertFailsWith<IllegalArgumentException> {
            ToolOrigin(" source", "Source", "lookup", "Lookup")
        }
        assertFailsWith<IllegalArgumentException> {
            ToolOrigin("source", "Source\nInjected", "lookup", "Lookup")
        }
        assertFailsWith<IllegalArgumentException> {
            ToolOrigin("source", "Source", "lookup", "x".repeat(257))
        }
    }

    @Test
    fun malformedCitationMetadataIsIgnoredWithoutBreakingProjection() {
        val nonArray = ToolExecutionResult(
            toolCallId = "call-1",
            toolName = "search",
            result = JsonPrimitive("result"),
            metadata = buildJsonObject {
                put("citations", buildJsonObject { put("private", "secret") })
            },
        )
        val malformedFields = ToolExecutionResult(
            toolCallId = "call-2",
            toolName = "search",
            result = JsonPrimitive("result"),
            metadata = buildJsonObject {
                put("citations", buildJsonArray {
                    add(buildJsonObject {
                        put("title", buildJsonObject { put("private", "secret") })
                        put("url", "https://example.com")
                    })
                })
            },
        )

        assertEquals(emptyList(), nonArray.citations())
        assertEquals("", malformedFields.citations().single().title)
        assertEquals("https://example.com", malformedFields.citations().single().url)
    }

    @Test
    fun userErrorCodeIsExplicitValidatedAndPreserved() {
        val result = ToolExecutionResult(
            toolCallId = "call-error",
            toolName = "search",
            result = buildJsonObject { put("code", "private-provider-code") },
            isError = true,
            userErrorCode = "search-unavailable",
            modelResultVisible = false,
        )

        assertEquals("search-unavailable", result.toMessagePart().userErrorCode)
        assertFailsWith<IllegalArgumentException> {
            result.copy(isError = false)
        }
        assertFailsWith<IllegalArgumentException> {
            result.copy(userErrorCode = "not a stable code")
        }
    }

    @Test
    fun typedToolResultContentRoundTripsWithExplicitAudiencesAndAttribution() {
        val expected = AgentMessage(
            role = MessageRole.TOOL,
            parts = listOf(
                ToolResultPart(
                    toolCallId = "call-1",
                    toolName = "image_search",
                    result = buildJsonObject { put("type", "image_search_results") },
                    content = listOf(
                        ToolResultTextContent(
                            text = "model context",
                            audiences = setOf(ToolResultAudience.MODEL),
                        ),
                        ToolResultImageContent(
                            source = RemoteToolImageSource("https://cdn.example.com/image.png"),
                            previewSource = RemoteToolImageSource("https://cdn.example.com/preview.png"),
                            mimeType = "image/png",
                            title = "Architecture",
                            altText = "A system diagram",
                            width = 1_200,
                            height = 800,
                            attribution = ToolMediaAttribution(
                                title = "Example",
                                url = "https://example.com/article",
                                license = "CC BY 4.0",
                                licenseUrl = "https://creativecommons.org/licenses/by/4.0/",
                            ),
                            audiences = setOf(ToolResultAudience.USER),
                        ),
                    ),
                ),
            ),
        )
        val json = Json {
            classDiscriminator = "type"
            encodeDefaults = false
        }

        val encoded = json.encodeToString(AgentMessage.serializer(), expected)

        assertEquals(expected, json.decodeFromString(AgentMessage.serializer(), encoded))
        assertTrue(encoded.contains("\"MODEL\""))
        assertTrue(encoded.contains("\"USER\""))
    }

    @Test
    fun multimodalContractsRejectUnsafeOrAmbiguousValues() {
        assertFailsWith<IllegalArgumentException> {
            RemoteToolImageSource("http://example.com/image.png")
        }
        assertFailsWith<IllegalArgumentException> {
            RemoteToolImageSource("https://secret@example.com/image.png")
        }
        assertFailsWith<IllegalArgumentException> {
            ToolMediaAttribution(url = "https://example.com/source image")
        }
        assertFailsWith<IllegalArgumentException> {
            ToolResultTextContent("text", emptySet())
        }
        assertFailsWith<IllegalArgumentException> {
            ToolResultImageContent(
                source = InlineToolImageSource("bytes"),
                mimeType = "Image/PNG",
                audiences = setOf(ToolResultAudience.MODEL),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            ToolResultImageContent(
                source = RemoteToolImageSource("https://example.com/image.png"),
                previewMimeType = "image/png",
                audiences = setOf(ToolResultAudience.USER),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            ModelDescriptor("test", "model", inputModalities = emptySet())
        }
        assertFailsWith<IllegalArgumentException> {
            RuntimeConfig(maxToolResultContentItems = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            RuntimeConfig(maxInlineToolResultBytes = 0)
        }
    }

    @Test
    fun attachmentHelpersShouldRedactUnsupportedDataUrlsWhenRenderedAsText() {
        val attachment = AttachmentPart(
            uri = "data:application/pdf;base64,VERY_LARGE_PAYLOAD",
            mimeType = "application/pdf",
        )

        assertNull(attachment.imageMimeTypeOrNull())
        assertFalse(attachment.textReference().contains("VERY_LARGE_PAYLOAD"))
    }

    @Test
    fun attachmentHelpersShouldOnlyTreatHttpsAsRemoteImageInput() {
        assertTrue(AttachmentPart("https://example.com/image.png", "").isHttpsUrl())
        assertFalse(AttachmentPart("http://example.com/image.png", "").isHttpsUrl())
    }

    @Test
    fun timeoutDefaultsAreLongEnoughForStreamingAgentRuns() {
        assertEquals(
            ProviderTimeoutConfig(
                connectTimeoutMillis = 15_000,
                firstEventTimeoutMillis = 120_000,
                streamIdleTimeoutMillis = 90_000,
                callTimeoutMillis = 600_000,
            ),
            ProviderConfig().timeouts,
        )
        assertEquals(120_000, RuntimeConfig().defaultToolTimeoutMillis)
        assertEquals(1_800_000, RuntimeConfig().runTimeoutMillis)
    }

    @Test
    fun timeoutConfigurationRejectsImpossibleDeadlines() {
        assertFailsWith<IllegalArgumentException> {
            ProviderTimeoutConfig(connectTimeoutMillis = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            ProviderTimeoutConfig(firstEventTimeoutMillis = 700_000)
        }
        assertFailsWith<IllegalArgumentException> {
            RuntimeConfig(runTimeoutMillis = 0)
        }
    }

    @Test
    fun providerInterruptionRejectsARetryTimeBeforeTheInterruption() {
        assertFailsWith<IllegalArgumentException> {
            AgentInterruption(
                reason = AgentInterruptionReason.PROVIDER_FAILURE,
                provider = ProviderInterruption(
                    code = AgentFailureCode.PROVIDER_RATE_LIMIT,
                    phase = ProviderInterruptionPhase.BEFORE_FIRST_EVENT,
                    retryAtEpochMs = 999L,
                ),
                occurredAtEpochMs = 1_000L,
            )
        }
    }

    @Test
    fun resumeCursorModelsTheNextOrdinalAndPendingProviderInvocation() {
        val pending = AgentPendingProviderInvocation(
            requestId = "run-1:turn-2:attempt-0",
            purpose = ProviderRequestPurpose.CONTEXT_SUMMARY,
            inputIdentity = "context-summary-input-1",
        )

        assertEquals(
            AgentProviderInvocationCursor(),
            AgentResumeCursor(
                turn = 2,
                phase = AgentResumePhase.MODEL_PENDING,
            ).provider,
        )
        assertEquals(
            pending,
            AgentResumeCursor(
                turn = 2,
                phase = AgentResumePhase.MODEL_PENDING,
                provider = AgentProviderInvocationCursor(
                    nextPhysicalAttempt = 1,
                    pending = pending,
                ),
            ).provider.pending,
        )
    }

    @Test
    fun resumeCursorRejectsInvalidProviderInvocationState() {
        assertFailsWith<IllegalArgumentException> {
            AgentProviderInvocationCursor(nextPhysicalAttempt = -1)
        }
        assertFailsWith<IllegalArgumentException> {
            AgentProviderInvocationCursor(
                pending = AgentPendingProviderInvocation(
                    requestId = "request-1",
                    purpose = ProviderRequestPurpose.MODEL,
                    inputIdentity = "input-1",
                ),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            AgentPendingProviderInvocation(
                requestId = " ",
                purpose = ProviderRequestPurpose.MODEL,
                inputIdentity = "input-1",
            )
        }
        assertFailsWith<IllegalArgumentException> {
            AgentPendingProviderInvocation(
                requestId = "request-1",
                purpose = ProviderRequestPurpose.MODEL,
                inputIdentity = "\t",
            )
        }
        assertFailsWith<IllegalArgumentException> {
            AgentResumeCursor(
                turn = 1,
                phase = AgentResumePhase.TURN_COMMITTED,
                provider = AgentProviderInvocationCursor(
                    nextPhysicalAttempt = 1,
                    pending = AgentPendingProviderInvocation(
                        requestId = "request-1",
                        purpose = ProviderRequestPurpose.MODEL,
                        inputIdentity = "input-1",
                    ),
                ),
            )
        }
    }

    @Test
    fun contextManagementDefaultsUseTokenBudgetsAndOneOverflowRecovery() {
        assertEquals(
            ContextManagementConfig(
                enabled = true,
                reserveTokens = 16_384,
                keepRecentTokens = 20_000,
                summaryMaxTokens = 4_096,
                charsPerTokenEstimate = 4,
                toolResultSummaryMaxChars = 2_000,
                contextWindowTokensOverride = null,
                overflowRetryLimit = 1,
            ),
            RuntimeConfig().contextManagement,
        )
    }

    @Test
    fun contextManagementRejectsUnsafeBudgetsAndInvalidPersistentState() {
        assertFailsWith<IllegalArgumentException> {
            ContextManagementConfig(reserveTokens = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            ContextManagementConfig(
                reserveTokens = 100,
                contextWindowTokensOverride = 100,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            ContextManagementConfig(overflowRetryLimit = -1)
        }
        assertFailsWith<IllegalArgumentException> {
            ContextCompaction(
                summary = "",
                firstKeptMessageId = "kept",
                summarizedThroughMessageId = "old",
                sourcePrefixDigest = "digest",
                tokensBefore = 1,
                generation = 1,
                summaryModel = ModelDescriptor("test", "test"),
            )
        }
    }
}
