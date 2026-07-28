package saien.magrathea.provider.api

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ProviderInputCapabilitiesTest {
    @Test
    fun exactAndPrefixRulesNormalizeCallerMimeTypes() {
        val capabilities = ProviderInputCapabilities(
            attachmentMimeTypes = setOf("application/pdf"),
            attachmentMimeTypePrefixes = setOf("text/"),
        )

        assertTrue(capabilities.supportsAttachment(" application/pdf; charset=binary "))
        assertTrue(capabilities.supportsAttachment("TEXT/PLAIN; charset=utf-8"))
        assertFalse(capabilities.supportsAttachment("image/png"))
        assertFalse(capabilities.supportsAttachment("text/"))
        assertFalse(capabilities.supportsAttachment("text/plain extra"))
        assertFalse(capabilities.supportsAttachment("not-a-mime-type"))
    }

    @Test
    fun declarationsMustUseCanonicalMimeRules() {
        assertFailsWith<IllegalArgumentException> {
            ProviderInputCapabilities(attachmentMimeTypes = setOf("Application/PDF"))
        }
        assertFailsWith<IllegalArgumentException> {
            ProviderInputCapabilities(attachmentMimeTypePrefixes = setOf("text"))
        }
    }

    @Test
    fun referenceProvidersDeclareOnlyTheirWireEnvelope() {
        assertTrue(
            ReferenceProviderInputCapabilities.geminiInteractions
                .supportsAttachment("application/csv"),
        )
        assertTrue(
            ReferenceProviderInputCapabilities.openAiResponses
                .supportsAttachment("text/markdown"),
        )
        assertTrue(
            ReferenceProviderInputCapabilities.openAiChatCompletions
                .supportsAttachment("image/png"),
        )
        assertFalse(
            ReferenceProviderInputCapabilities.openAiChatCompletions
                .supportsAttachment("application/pdf"),
        )
        assertTrue(
            ReferenceProviderInputCapabilities.anthropicMessages
                .supportsAttachment("application/pdf"),
        )
        assertFalse(
            ReferenceProviderInputCapabilities.anthropicMessages
                .supportsAttachment("text/csv"),
        )
    }
}
