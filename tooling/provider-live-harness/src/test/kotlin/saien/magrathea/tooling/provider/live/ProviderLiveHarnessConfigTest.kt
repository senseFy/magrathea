package saien.magrathea.tooling.provider.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import saien.magrathea.core.AgentEvent
import saien.magrathea.core.AgentFailureCode
import saien.magrathea.core.AgentMessage
import saien.magrathea.core.AgentRunId
import saien.magrathea.core.AgentSessionId
import saien.magrathea.core.AgentStateSnapshot
import saien.magrathea.core.MessageRole
import saien.magrathea.core.TextPart
import saien.magrathea.core.TokenUsage
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class ProviderLiveHarnessConfigTest {
    @Test
    fun defaultsToGeminiChatScenario() {
        val config = ProviderLiveHarnessConfig.from(emptyArray(), emptyMap())
        assertEquals("chat", config.scenario)
        assertEquals("gemini", config.provider)
        assertEquals("gemini-2.5-flash", config.model)
        assertEquals(128, config.maxTokens)
        assertEquals(0, config.maxProviderRetries)
        assertNull(config.endpoint)
        assertNull(config.authentication)
        assertNull(config.openAiProtocol)
        assertNull(config.filePath)
    }

    @Test
    fun readsApiKeysFromEnvironment() {
        val config = ProviderLiveHarnessConfig.from(emptyArray(), mapOf("MAGRATHEA_GEMINI_API_KEY" to "abc"))
        assertEquals("abc", config.apiKeyFor("gemini"))
        assertNull(config.apiKeyFor("openai"))
    }

    @Test
    fun rejectsUnknownProviderKey() {
        assertThrows(IllegalArgumentException::class.java) {
            ProviderLiveHarnessConfig.from(arrayOf("provider=unknown"), emptyMap())
        }
    }

    @Test
    fun fileScenarioRequiresAPathWithoutPrintingIt() {
        assertThrows(IllegalArgumentException::class.java) {
            ProviderLiveHarnessConfig.from(arrayOf("scenario=file"), emptyMap())
        }
        val path = "/private/tmp/sensitive-document.pdf"
        val config = ProviderLiveHarnessConfig.from(
            arrayOf("scenario=file", "file=$path"),
            emptyMap(),
        )

        assertEquals(path, config.filePath)
        assertFalse(config.toString().contains(path))
        assertTrue(config.toString().contains("fileConfigured=true"))
    }

    @Test
    fun readsCompatibleEndpointAndAuthenticationSettings() {
        val config = ProviderLiveHarnessConfig.from(
            arrayOf(
                "provider=anthropic",
                "model=anthropic/claude-contract",
                "endpoint=https://compatible.example.test/api/v1/messages",
                "authentication=bearer",
                "maxTokens=64",
                "maxProviderRetries=1",
            ),
            mapOf("MAGRATHEA_ANTHROPIC_API_KEY" to "compatible-secret"),
        )

        assertEquals("https://compatible.example.test/api/v1/messages", config.endpoint)
        assertEquals(ProviderLiveAuthentication.BEARER, config.authentication)
        assertEquals(64, config.maxTokens)
        assertEquals(1, config.maxProviderRetries)
        assertEquals("compatible-secret", config.apiKeyFor("anthropic"))
        assertFalse(config.toString().contains("compatible-secret"))
    }

    @Test
    fun selectsOpenAiChatCompletionsExplicitly() {
        val config = ProviderLiveHarnessConfig.from(
            arrayOf(
                "provider=openai",
                "protocol=chat-completions",
                "endpoint=https://compatible.example.test/v1/chat/completions",
            ),
            emptyMap(),
        )

        assertEquals(ProviderLiveOpenAiProtocol.CHAT_COMPLETIONS, config.openAiProtocol)
        assertTrue(config.toString().contains("openAiProtocol=CHAT_COMPLETIONS"))
    }

    @Test
    fun xSearchUsesOpenAiResponsesWithAReasoningSizedOutputBudget() {
        val config = ProviderLiveHarnessConfig.from(
            arrayOf(
                "provider=xai",
                "scenario=x-search",
                "model=grok-contract",
            ),
            emptyMap(),
        )

        assertEquals("x-search", config.scenario)
        assertEquals(ProviderLiveOpenAiProtocol.RESPONSES, config.openAiProtocol)
        assertEquals(8_192, config.maxTokens)
    }

    @Test
    fun xSearchRejectsAnUnrelatedProviderOrChatCompletions() {
        assertThrows(IllegalArgumentException::class.java) {
            ProviderLiveHarnessConfig.from(
                arrayOf("provider=gemini", "scenario=x-search"),
                emptyMap(),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ProviderLiveHarnessConfig.from(
                arrayOf(
                    "provider=xai",
                    "scenario=x-search",
                    "protocol=chat-completions",
                ),
                emptyMap(),
            )
        }
    }

    @Test
    fun rejectsInsecureEndpointAndInvalidAuthenticationCombination() {
        assertThrows(IllegalArgumentException::class.java) {
            ProviderLiveHarnessConfig.from(
                arrayOf("provider=openai", "endpoint=http://compatible.example.test/responses"),
                emptyMap(),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ProviderLiveHarnessConfig.from(
                arrayOf("provider=anthropic", "authentication=bearer"),
                emptyMap(),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ProviderLiveHarnessConfig.from(
                arrayOf(
                    "provider=openai",
                    "endpoint=https://compatible.example.test/responses",
                    "authentication=x-api-key",
                ),
                emptyMap(),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ProviderLiveHarnessConfig.from(arrayOf("maxTokens=0"), emptyMap())
        }
        assertThrows(IllegalArgumentException::class.java) {
            ProviderLiveHarnessConfig.from(arrayOf("maxProviderRetries=-1"), emptyMap())
        }
        assertThrows(IllegalArgumentException::class.java) {
            ProviderLiveHarnessConfig.from(arrayOf("provider=gemini", "protocol=chat-completions"), emptyMap())
        }
        assertThrows(IllegalArgumentException::class.java) {
            ProviderLiveHarnessConfig.from(arrayOf("scenario=unknown"), emptyMap())
        }
    }

    @Test
    fun providerProfilesHaveIndependentIdentityAndProtocolDefaults() {
        val openRouter = ProviderLiveHarnessConfig.from(
            arrayOf("provider=openrouter"),
            mapOf("MAGRATHEA_OPENROUTER_API_KEY" to "secret"),
        )
        val xAi = ProviderLiveHarnessConfig.from(
            arrayOf("provider=xai"),
            mapOf("MAGRATHEA_XAI_API_KEY" to "secret"),
        )

        assertEquals(ProviderLiveOpenAiProtocol.CHAT_COMPLETIONS, openRouter.openAiProtocol)
        assertEquals("openai/gpt-4o-mini", openRouter.model)
        assertEquals("secret", openRouter.apiKeyFor("openrouter"))
        assertEquals(ProviderLiveOpenAiProtocol.RESPONSES, xAi.openAiProtocol)
        assertEquals("grok-4.5", xAi.model)
    }

    @Test
    fun failedOrCancelledAgentEventFailsTheHarnessGate() {
        val sessionId = AgentSessionId("harness-test")

        assertThrows(IllegalStateException::class.java) {
            requireSuccessfulProviderLiveEvent(AgentEvent.Failed(sessionId, AgentFailureCode.PROVIDER_SERVER))
        }
        assertThrows(IllegalStateException::class.java) {
            requireSuccessfulProviderLiveEvent(AgentEvent.Cancelled(sessionId))
        }
        requireSuccessfulProviderLiveEvent(AgentEvent.Started(sessionId, AgentRunId("harness-run")))
    }

    @Test
    fun eventFormattingDoesNotExposeDebugMessageMetadataOrFailurePayloads() {
        val canary = "harness-sensitive-canary"
        val sessionId = AgentSessionId("harness-test")
        val message = AgentMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(TextPart(text = canary, signature = canary)),
            metadata = buildJsonObject { put("provider-payload", canary) },
        )
        val output = listOf(
            AgentEvent.Debug(sessionId, "probe", canary),
            AgentEvent.MessageEmitted(sessionId, message),
            AgentEvent.Failed(sessionId, AgentFailureCode.INTERNAL),
        ).flatMap(::formatProviderLiveEvent).joinToString("\n")

        assertFalse(output.contains(canary))
        assertTrue(output.contains("payloadChars="))
        assertTrue(output.contains("textChars="))
        assertTrue(output.contains("metadata-keys="))
        assertTrue(output.contains("failed code=INTERNAL"))
    }

    @Test
    fun completedEventFormattingReportsOnlyAggregateUsage() {
        val output = formatProviderLiveEvent(
            AgentEvent.Completed(
                AgentSessionId("usage-test"),
                AgentStateSnapshot(
                    messages = emptyList(),
                    usage = TokenUsage(
                        inputTokens = 123,
                        outputTokens = 45,
                        reasoningTokens = 6,
                    ),
                ),
            ),
        ).joinToString("\n")

        assertTrue(output.contains("inputTokens=123"))
        assertTrue(output.contains("outputTokens=45"))
        assertTrue(output.contains("reasoningTokens=6"))
    }
}
