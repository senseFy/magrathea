package saien.magrathea.provider.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Assert.assertThrows
import saien.magrathea.core.ProviderConfig
import saien.magrathea.core.ProviderOptions
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class ProviderTransportConfigContractTest {
    @Test
    fun wrongTypedConfig_isRejectedBeforeProviderCall() {
        try {
            compileProviderTransportConfig(
                config = ProviderConfig(options = AnthropicTransportConfig().toProviderOptions()),
                expectedFamily = "openai",
            )
            throw AssertionError("Expected provider family validation")
        } catch (error: IllegalArgumentException) {
            assertFalse(error.message.orEmpty().isBlank())
        }

        val valid = compileProviderTransportConfig(
            config = ProviderConfig(options = OpenAiTransportConfig(reasoningEffort = "high").toProviderOptions()),
            expectedFamily = "openai",
        )
        assertEquals(OpenAiTransportConfig(reasoningEffort = "high"), valid)
    }

    @Test
    fun adapterWithoutAnOptionsFamilyRejectsTypedOptions() {
        assertThrows(IllegalArgumentException::class.java) {
            compileProviderTransportConfig(
                config = ProviderConfig(options = OpenAiTransportConfig().toProviderOptions()),
                expectedFamily = null,
            )
        }
    }

    @Test
    fun compatibleEndpointAuthenticationModesRoundTripThroughProviderOptions() {
        val openAi = OpenAiTransportConfig(
            protocol = OpenAiWireProtocol.CHAT_COMPLETIONS,
            authentication = OpenAiAuthentication.API_KEY,
        )
        val anthropic = AnthropicTransportConfig(authentication = AnthropicAuthentication.BEARER)

        assertEquals(
            openAi,
            compileProviderTransportConfig(
                ProviderConfig(options = openAi.toProviderOptions()),
                expectedFamily = "openai",
            ),
        )
        assertEquals(
            anthropic,
            compileProviderTransportConfig(
                ProviderConfig(options = anthropic.toProviderOptions()),
                expectedFamily = "anthropic",
            ),
        )
    }

    @Test
    fun xSearchHostedToolRoundTripsWithStrictValidation() {
        val config = OpenAiTransportConfig(
            hostedTools = listOf(
                OpenAiXSearchToolConfig(
                    excludedHandles = listOf("spam"),
                    fromDate = "2026-07-01",
                    toDate = "2026-07-16",
                    enableVideoUnderstanding = true,
                ),
            ),
            maxToolTurns = 3,
        )

        assertEquals(
            config,
            compileProviderTransportConfig(
                ProviderConfig(options = config.toProviderOptions()),
                expectedFamily = "openai",
            ),
        )

        assertThrows(IllegalArgumentException::class.java) {
            OpenAiXSearchToolConfig(
                allowedHandles = listOf("xai"),
                excludedHandles = listOf("spam"),
            )
        }

        assertThrows(IllegalArgumentException::class.java) {
            OpenAiXSearchToolConfig(fromDate = "2026-02-30")
        }

        assertThrows(IllegalArgumentException::class.java) {
            OpenAiTransportConfig(
                protocol = OpenAiWireProtocol.CHAT_COMPLETIONS,
                hostedTools = listOf(OpenAiXSearchToolConfig()),
            )
        }
    }

    @Test
    fun unknownProviderOptions_areRejectedBeforeProviderCall() {
        try {
            compileProviderTransportConfig(
                config = ProviderConfig(options = ProviderOptions(family = "future-provider")),
                expectedFamily = "future-provider",
            )
            throw AssertionError("Expected unknown provider options rejection")
        } catch (error: IllegalArgumentException) {
            assertTrue(error.message.orEmpty().contains("future-provider"))
        }
    }

    @Test
    fun unknownTypedOptionField_isRejected() {
        val config = ProviderConfig(
            options = ProviderOptions(
                family = "openai",
                values = buildJsonObject { put("notARealOpenAiOption", true) },
            ),
        )

        try {
            compileProviderTransportConfig(config, expectedFamily = "openai")
            throw AssertionError("Expected strict provider option decoding")
        } catch (error: IllegalArgumentException) {
            assertFalse(error.message.orEmpty().isBlank())
        }
    }
}
