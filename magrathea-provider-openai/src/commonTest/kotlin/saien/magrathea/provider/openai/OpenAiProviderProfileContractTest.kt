package saien.magrathea.provider.openai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import saien.magrathea.provider.api.OpenAiWireProtocol

class OpenAiProviderProfileContractTest {
    @Test
    fun builtInProfilesKeepProviderIdentityProtocolAndDialectIndependent() {
        val openAi = OpenAiProviderProfile.openAi()
        val openRouter = OpenAiProviderProfile.openRouter()
        val xAi = OpenAiProviderProfile.xAi()

        assertEquals("openai", openAi.providerId)
        assertEquals(OpenAiWireProtocol.RESPONSES, openAi.defaultProtocol)
        assertEquals(OpenAiProtocolDialect.OPENAI, openAi.dialect)

        assertEquals("openrouter", openRouter.providerId)
        assertEquals(OpenAiWireProtocol.CHAT_COMPLETIONS, openRouter.defaultProtocol)
        assertEquals(OpenAiProtocolDialect.OPENROUTER, openRouter.dialect)

        assertEquals("xai", xAi.providerId)
        assertEquals(OpenAiWireProtocol.RESPONSES, xAi.defaultProtocol)
        assertEquals(OpenAiProtocolDialect.XAI, xAi.dialect)
    }

    @Test
    fun compatibleProfileRequiresTheHostToOwnEndpointSelection() {
        val profile = OpenAiProviderProfile.compatible(providerId = "private-gateway")

        assertEquals(OpenAiProtocolDialect.COMPATIBLE, profile.dialect)
        assertEquals(OpenAiWireProtocol.CHAT_COMPLETIONS, profile.defaultProtocol)
        assertNull(profile.defaultEndpoint(OpenAiWireProtocol.RESPONSES))
        assertNull(profile.defaultEndpoint(OpenAiWireProtocol.CHAT_COMPLETIONS))
    }

    @Test
    fun profileRejectsAmbiguousIdentityAndInsecureEndpoints() {
        assertFailsWith<IllegalArgumentException> {
            OpenAiProviderProfile.compatible(providerId = "OpenRouter")
        }
        assertFailsWith<IllegalArgumentException> {
            OpenAiProviderProfile.compatible(
                providerId = "private-gateway",
                chatCompletionsEndpoint = "http://provider.example/v1/chat/completions",
            )
        }
    }

    @Test
    fun diagnosticsExposeConfigurationShapeWithoutEndpointValues() {
        val profile = OpenAiProviderProfile.openRouter()

        assertTrue(profile.toString().contains("providerId=openrouter"))
        assertTrue(profile.toString().contains("chatCompletionsEndpoint=<configured>"))
        assertFalse(profile.toString().contains("openrouter.ai"))
    }
}
