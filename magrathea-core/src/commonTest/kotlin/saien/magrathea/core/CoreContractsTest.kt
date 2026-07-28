package saien.magrathea.core

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
        val result = ToolExecutionResult(
            toolCallId = "call-1",
            toolName = "search",
            result = JsonPrimitive("raw output"),
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

        assertEquals("raw output", result.outputText())
        assertEquals("Doc", result.citations().single().title)
        assertEquals("raw output", part.displayText)
        assertEquals("https://example.com", part.citations().single().url)
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
