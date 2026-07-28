package saien.magrathea.core

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SessionCredentialContractTest {
    private val json = Json { encodeDefaults = true }
    private val codec = AgentSessionSnapshotCodec(json)

    @Test
    fun sessionSnapshot_persistsOnlyCredentialReference() {
        val ref = CredentialRef("test-provider", "work")
        val decoded = codec.decode(codec.encode(snapshot(ProviderConfig(credentialRef = ref))))

        assertEquals(ref, decoded.request.engine.provider.credentialRef)
        assertTrue(codec.encode(decoded).contains("credentialRef"))
    }

    @Test
    fun sessionSnapshotPersistsNonSecretCompatibleTransportSelection() {
        val config = ProviderConfig(
            options = ProviderOptions(
                family = "anthropic",
                values = buildJsonObject { put("authentication", "bearer") },
            ),
            credentialRef = CredentialRef("anthropic", "compatible"),
        )

        val decoded = codec.decode(codec.encode(snapshot(config)))

        assertEquals(config.options, decoded.request.engine.provider.options)
        assertEquals(config.credentialRef, decoded.request.engine.provider.credentialRef)
    }

    @Test
    fun snapshotDecoder_rejectsUnknownCredentialField() {
        val clean = codec.encode(snapshot(ProviderConfig()))
        val unknownFieldPayload = clean.replace(
            oldValue = "\"provider\":{",
            newValue = "\"provider\":{\"apiKey\":\"unexpected-secret\",",
        )
        assertTrue(unknownFieldPayload.contains("unexpected-secret"))
        assertFailsWith<SerializationException> {
            codec.decode(unknownFieldPayload)
        }
    }

    @Test
    fun providerCredential_toStringIsRedacted() {
        val canary = "MAG_TOSTRING_CREDENTIAL_CANARY"
        assertFalse(ProviderCredential(canary).toString().contains(canary))
    }

    @Test
    fun providerConfig_toStringOmitsEndpointHeaderAndOptionValues() {
        val canary = "MAG_PROVIDER_CONFIG_TOSTRING_CANARY"
        val config = ProviderConfig(
            endpoint = "https://user:$canary@example.invalid/v1?key=$canary",
            headers = mapOf("X-Canary" to canary),
            options = ProviderOptions(
                family = "openai",
                values = buildJsonObject { put("deployment", canary) },
            ),
        )

        val rendered = config.toString()

        assertFalse(rendered.contains(canary))
        assertTrue(rendered.contains("endpoint=<custom>"))
        assertTrue(rendered.contains("headerNames=[X-Canary]"))
        assertTrue(rendered.contains("optionsFamily=openai"))
    }

    @Test
    fun providerOptions_rejectCredentialLikeKeysWithoutLeakingValue() {
        val canary = "MAG_PROVIDER_OPTIONS_CREDENTIAL_CANARY"

        val error = assertFailsWith<IllegalArgumentException> {
            ProviderOptions(
                family = "test-provider",
                values = buildJsonObject {
                    put("nested", buildJsonObject { put("access_token", canary) })
                },
            )
        }

        assertFalse(error.message.orEmpty().contains(canary))
    }

    private fun snapshot(providerConfig: ProviderConfig): AgentSessionSnapshot {
        val sessionId = AgentSessionId("credential-contract-session")
        val request = AgentRequest(
            sessionId = sessionId,
            messages = listOf(AgentMessage(role = MessageRole.USER, parts = listOf(TextPart("hello")))),
            model = ModelDescriptor(provider = "test-provider", model = "test-model"),
            engine = AgentEngineConfig(provider = providerConfig),
        )
        return AgentSessionSnapshot(sessionId, request, AgentStateSnapshot(messages = request.messages))
    }
}
