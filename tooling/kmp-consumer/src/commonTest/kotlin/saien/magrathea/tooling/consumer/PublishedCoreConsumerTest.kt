package saien.magrathea.tooling.consumer

import kotlin.test.Test
import kotlin.test.assertEquals

class PublishedCoreConsumerTest {
    @Test
    fun resolvesPublishedCommonApi() {
        assertEquals("published-consumer", publishedCoreSessionId("published-consumer").value)
        assertEquals("gemini", publishedGeminiProviderKey())
        assertEquals("openai,anthropic", publishedProviderKeys())
        assertEquals("SESSION:NOT_FOUND:IDLE", publishedPlatformAdapterFingerprint())
        assertEquals(
            "gateway:consumer-model:https://gateway.example/consumer:IDLE",
            publishedGatewayFingerprint(),
        )
        assertEquals("web_search:2:7:5", publishedWebSearchFingerprint())
        assertEquals("consumer:search:LOW:ALLOW", publishedExtensionFingerprint())
        assertEquals(
            "apple-link|gemini|openai,anthropic|SESSION:NOT_FOUND:IDLE|" +
                "gateway:consumer-model:https://gateway.example/consumer:IDLE|" +
                "web_search:2:7:5|consumer:search:LOW:ALLOW",
            publishedAppleLinkFingerprint(),
        )
    }
}
