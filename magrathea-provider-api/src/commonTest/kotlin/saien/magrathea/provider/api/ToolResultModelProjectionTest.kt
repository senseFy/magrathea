package saien.magrathea.provider.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import saien.magrathea.core.InlineToolImageSource
import saien.magrathea.core.ModelInputModality
import saien.magrathea.core.ToolResultAudience
import saien.magrathea.core.ToolResultContent
import saien.magrathea.core.ToolResultImageContent
import saien.magrathea.core.ToolOrigin
import saien.magrathea.core.ToolResultPart
import saien.magrathea.core.ToolResultTextContent

class ToolResultModelProjectionTest {
    private val canonicalResult = buildJsonObject { put("answer", 42) }
    private val textCapabilities = ProviderInputCapabilities()
    private val imageCapabilities = ProviderInputCapabilities(
        attachmentMimeTypes = setOf("image/png"),
    )

    @Test
    fun modelContentComposesWithCanonicalResult() {
        val part = toolResult(
            content = listOf(
                ToolResultTextContent("model summary", setOf(ToolResultAudience.MODEL)),
                ToolResultTextContent("user detail", setOf(ToolResultAudience.USER)),
            ),
        )

        val projection = part.modelProjection(
            setOf(ModelInputModality.TEXT),
            textCapabilities,
        )

        assertEquals(listOf("model summary"), projection.content.map { (it as ToolResultTextContent).text })
        assertEquals(canonicalResult, projection.canonicalResult)
        assertEquals(canonicalResult, part.result)
    }

    @Test
    fun structurallyEquivalentJsonTextIsTheOnlyTypedContentDeduplicated() {
        val projection = toolResult(
            content = listOf(
                ToolResultTextContent(
                    "{ \"answer\" : 42 }",
                    setOf(ToolResultAudience.MODEL),
                ),
                ToolResultTextContent(
                    "human-readable summary",
                    setOf(ToolResultAudience.MODEL),
                ),
            ),
        ).modelProjection(setOf(ModelInputModality.TEXT), textCapabilities)

        assertEquals(canonicalResult, projection.canonicalResult)
        assertEquals(
            listOf("human-readable summary"),
            projection.content.map { (it as ToolResultTextContent).text },
        )
    }

    @Test
    fun primitiveCanonicalTextUsesItsWireRenderingForDeduplication() {
        val projection = ToolResultPart(
            toolCallId = "call-1",
            toolName = "lookup",
            result = JsonPrimitive("same result"),
            content = listOf(
                ToolResultTextContent(
                    "same result",
                    setOf(ToolResultAudience.MODEL),
                ),
            ),
        ).modelProjection(setOf(ModelInputModality.TEXT), textCapabilities)

        assertEquals(JsonPrimitive("same result"), projection.canonicalResult)
        assertEquals(emptyList(), projection.content)
    }

    @Test
    fun canonicalResultIsTheFallbackForMissingOrUserOnlyModelContent() {
        val noContent = toolResult().modelProjection(
            setOf(ModelInputModality.TEXT),
            textCapabilities,
        )
        val userOnly = toolResult(
            content = listOf(
                ToolResultTextContent("user detail", setOf(ToolResultAudience.USER)),
            ),
        ).modelProjection(setOf(ModelInputModality.TEXT), textCapabilities)

        assertEquals(canonicalResult, noContent.canonicalResult)
        assertEquals(emptyList(), noContent.content)
        assertEquals(canonicalResult, userOnly.canonicalResult)
        assertEquals(emptyList(), userOnly.content)
    }

    @Test
    fun unsupportedImagesFallBackWhileSupportedImagesRemainTyped() {
        val part = toolResult(
            content = listOf(
                ToolResultImageContent(
                    source = InlineToolImageSource("IMAGE_DATA"),
                    mimeType = "image/png",
                    audiences = setOf(ToolResultAudience.MODEL),
                ),
            ),
        )

        val textOnly = part.modelProjection(
            setOf(ModelInputModality.TEXT),
            imageCapabilities,
        )
        val multimodal = part.modelProjection(
            setOf(ModelInputModality.TEXT, ModelInputModality.IMAGE),
            imageCapabilities,
        )

        assertEquals(canonicalResult, textOnly.canonicalResult)
        assertEquals(1, multimodal.content.size)
        assertEquals(canonicalResult, multimodal.canonicalResult)
    }

    @Test
    fun userOnlyCanonicalResultFallsBackToANeutralMarkerWithoutLeakingData() {
        val secret = "USER_ONLY_SECRET"
        val projection = ToolResultPart(
            toolCallId = "call-1",
            toolName = "lookup",
            result = buildJsonObject { put("secret", secret) },
            isError = true,
            content = listOf(
                ToolResultTextContent(secret, setOf(ToolResultAudience.USER)),
            ),
            modelResultVisible = false,
        ).modelProjection(setOf(ModelInputModality.TEXT), textCapabilities)

        assertEquals(emptyList(), projection.content)
        assertEquals(
            "Tool failed without model-visible error details.",
            projection.canonicalResult?.jsonPrimitive?.content,
        )
        assertFalse(projection.canonicalResult.toString().contains(secret))
    }

    @Test
    fun imageProjectionRequiresAnExplicitSupportedMimeType() {
        fun image(mimeType: String?) = ToolResultImageContent(
            source = InlineToolImageSource("IMAGE_DATA"),
            mimeType = mimeType,
            audiences = setOf(ToolResultAudience.MODEL),
        )
        val modalities = setOf(ModelInputModality.TEXT, ModelInputModality.IMAGE)

        val supported = toolResult(content = listOf(image("image/png")))
            .modelProjection(modalities, imageCapabilities)
        val unsupported = toolResult(content = listOf(image("image/svg+xml")))
            .modelProjection(modalities, imageCapabilities)
        val unspecified = toolResult(content = listOf(image(null)))
            .modelProjection(modalities, imageCapabilities)

        assertEquals(1, supported.content.size)
        assertEquals(emptyList(), unsupported.content)
        assertEquals(emptyList(), unspecified.content)
        assertEquals(canonicalResult, unsupported.canonicalResult)
        assertEquals(canonicalResult, unspecified.canonicalResult)
    }

    @Test
    fun modelBoundaryRemovesProductPresentationAndPrivateMetadata() {
        val projected = ToolResultPart(
            toolCallId = "call-1",
            toolName = "lookup",
            result = canonicalResult,
            metadata = buildJsonObject { put("private", "value") },
            origin = ToolOrigin("catalog", "Catalog", "lookup", "Lookup"),
            providerMetadata = buildJsonObject { put("provider", "value") },
        ).sanitizedForModelBoundary()

        assertEquals(emptySet(), projected.metadata.keys)
        assertNull(projected.origin)
        assertNull(projected.providerMetadata)
    }

    private fun toolResult(
        content: List<ToolResultContent> = emptyList(),
    ): ToolResultPart = ToolResultPart(
        toolCallId = "call-1",
        toolName = "lookup",
        result = canonicalResult,
        content = content,
    )
}
