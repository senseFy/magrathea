package saien.magrathea.mcp

import io.modelcontextprotocol.kotlin.sdk.types.Annotations
import io.modelcontextprotocol.kotlin.sdk.types.AudioContent
import io.modelcontextprotocol.kotlin.sdk.types.BlobResourceContents
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.EmbeddedResource
import io.modelcontextprotocol.kotlin.sdk.types.ImageContent
import io.modelcontextprotocol.kotlin.sdk.types.ResourceLink
import io.modelcontextprotocol.kotlin.sdk.types.Role
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.TextResourceContents
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import saien.magrathea.core.InlineToolImageSource
import saien.magrathea.core.ToolResultAudience
import saien.magrathea.core.ToolResultImageContent
import saien.magrathea.core.ToolResultTextContent
import saien.magrathea.core.ToolOrigin

class McpMultimodalToolResultContractTest {
    @Test
    fun officialMcpImageAndAudienceAnnotationsMapToCanonicalToolContent() {
        val result = CallToolResult(
            content = listOf(
                TextContent(text = "model context"),
                ImageContent(
                    data = "IMAGE_DATA",
                    mimeType = "IMAGE/PNG",
                    annotations = Annotations(audience = listOf(Role.User)),
                ),
            ),
        ).toMagratheaResult(
            server = McpServer("images", "Images"),
            remoteToolName = "find_images",
            runtimeToolName = "mcp__images__find_images",
            toolTitle = "Find images",
            toolCallId = "call-1",
        )

        val text = assertIs<ToolResultTextContent>(result.content[0])
        assertEquals(
            setOf(ToolResultAudience.MODEL, ToolResultAudience.USER),
            text.audiences,
        )
        val image = assertIs<ToolResultImageContent>(result.content[1])
        assertEquals(setOf(ToolResultAudience.USER), image.audiences)
        assertEquals("image/png", image.mimeType)
        assertEquals("IMAGE_DATA", assertIs<InlineToolImageSource>(image.source).data)
        assertEquals(
            ToolOrigin("images", "Images", "find_images", "Find images"),
            result.origin,
        )
    }

    @Test
    fun structuredContentRemainsCanonicalWhileUnstructuredContentRemainsTyped() {
        val structured = buildJsonObject {
            put("temperature", 22)
            put("unit", "celsius")
        }
        val result = CallToolResult(
            content = listOf(TextContent("London is 22 C")),
            structuredContent = structured,
        ).mapped()

        assertEquals(structured, result.result)
        assertEquals(
            "London is 22 C",
            assertIs<ToolResultTextContent>(result.content.single()).text,
        )
        assertEquals(false, "mcpContent" in result.metadata)
    }

    @Test
    fun contentOnlyResultRetainsTheOfficialMcpContentEnvelope() {
        val result = CallToolResult(
            content = listOf(TextContent("plain result")),
        ).mapped()

        assertEquals(1, result.result.jsonObject.getValue("content").jsonArray.size)
        assertEquals(
            "plain result",
            assertIs<ToolResultTextContent>(result.content.single()).text,
        )
        assertFalse(result.modelResultVisible)
    }

    @Test
    fun contentOnlyImageEnvelopeIsDurableWhileModelProjectionUsesOneTypedImage() {
        val result = CallToolResult(
            content = listOf(
                ImageContent(
                    data = "MCP_IMAGE_DATA",
                    mimeType = "image/png",
                    annotations = Annotations(audience = listOf(Role.Assistant)),
                ),
            ),
        ).mapped()

        assertFalse(result.modelResultVisible)
        assertTrue(result.result.toString().contains("MCP_IMAGE_DATA"))
        val image = assertIs<ToolResultImageContent>(result.content.single())
        assertEquals(setOf(ToolResultAudience.MODEL), image.audiences)
        assertEquals("image/png", image.mimeType)
        assertEquals("MCP_IMAGE_DATA", assertIs<InlineToolImageSource>(image.source).data)
    }

    @Test
    fun structuredOnlyAndUserOnlyErrorsPreserveCanonicalState() {
        val structured = buildJsonObject { put("errorCode", "LOOKUP_FAILED") }
        val structuredOnly = CallToolResult(
            content = emptyList(),
            structuredContent = structured,
        ).mapped()
        val userOnlyError = CallToolResult(
            content = listOf(
                TextContent(
                    text = "Visible to the user",
                    annotations = Annotations(audience = listOf(Role.User)),
                ),
            ),
            structuredContent = structured,
            isError = true,
        ).mapped()

        assertEquals(structured, structuredOnly.result)
        assertEquals(emptyList(), structuredOnly.content)
        assertEquals(structured, userOnlyError.result)
        assertEquals(true, userOnlyError.isError)
        assertEquals(
            setOf(ToolResultAudience.USER),
            assertIs<ToolResultTextContent>(userOnlyError.content.single()).audiences,
        )
    }

    @Test
    fun contentOnlyEnvelopeHidesCanonicalResultWhenAnyBlockIsNotModelVisible() {
        val userOnly = CallToolResult(
            content = listOf(
                TextContent(
                    text = "Never send this to the model",
                    annotations = Annotations(audience = listOf(Role.User)),
                ),
            ),
        ).mapped()
        val mixed = CallToolResult(
            content = listOf(
                TextContent(
                    text = "model",
                    annotations = Annotations(audience = listOf(Role.Assistant)),
                ),
                TextContent(
                    text = "user",
                    annotations = Annotations(audience = listOf(Role.User)),
                ),
            ),
        ).mapped()

        assertEquals(false, userOnly.modelResultVisible)
        assertEquals(false, mixed.modelResultVisible)
    }

    @Test
    fun heterogeneousModelContentIsPreservedAsTypedModelVisibleText() {
        val assistant = Annotations(audience = listOf(Role.Assistant))
        val privateMeta = buildJsonObject { put("private", "PRIVATE_MCP_META") }
        val result = CallToolResult(
            content = listOf(
                ResourceLink(
                    name = "guide",
                    uri = "https://example.test/guide",
                    title = "Guide",
                    mimeType = "text/html",
                    annotations = assistant,
                    meta = privateMeta,
                ),
                EmbeddedResource(
                    resource = TextResourceContents(
                        text = "embedded context",
                        uri = "file:///workspace/context.txt",
                        mimeType = "text/plain",
                        meta = privateMeta,
                    ),
                    annotations = assistant,
                    meta = privateMeta,
                ),
                EmbeddedResource(
                    resource = BlobResourceContents(
                        blob = "BLOB_DATA",
                        uri = "file:///workspace/context.bin",
                        mimeType = "application/octet-stream",
                        meta = privateMeta,
                    ),
                    annotations = assistant,
                    meta = privateMeta,
                ),
                AudioContent(
                    data = "AUDIO_DATA",
                    mimeType = "audio/wav",
                    annotations = assistant,
                    meta = privateMeta,
                ),
            ),
        ).mapped()

        val projected = result.content.map { assertIs<ToolResultTextContent>(it) }
        assertEquals(4, projected.size)
        assertTrue(projected.all { it.audiences == setOf(ToolResultAudience.MODEL) })
        assertTrue(projected[0].text.contains("https://example.test/guide"))
        assertTrue(projected[1].text.contains("embedded context"))
        assertTrue(projected[2].text.contains("binary resource content omitted"))
        assertTrue(projected[3].text.contains("audio content omitted"))
        assertFalse(projected.toString().contains("PRIVATE_MCP_META"))
        assertFalse(projected.toString().contains("BLOB_DATA"))
        assertFalse(projected.toString().contains("AUDIO_DATA"))
        assertFalse(result.modelResultVisible)
    }

    @Test
    fun modelOnlyContentDoesNotBecomeUserDisplayText() {
        val result = CallToolResult(
            content = listOf(
                TextContent(
                    text = "MODEL_ONLY_SECRET",
                    annotations = Annotations(audience = listOf(Role.Assistant)),
                ),
            ),
        ).mapped()

        assertEquals("Tool completed.", result.displayText)
        assertEquals(false, result.modelResultVisible)
    }

    @Test
    fun unsafeRemoteTitleFallsBackWithoutBreakingTheToolResult() {
        val result = CallToolResult(
            content = listOf(TextContent("result")),
        ).toMagratheaResult(
            server = McpServer("contract", "Contract"),
            remoteToolName = "lookup",
            runtimeToolName = "mcp__contract__lookup",
            toolTitle = "Unsafe\nTitle",
            toolCallId = "call-contract",
        )

        assertEquals("lookup", result.origin?.toolLabel)
    }

    @Test
    fun unsafeRemoteIdentityOmitsPresentationOriginWithoutBreakingTheToolResult() {
        val result = CallToolResult(
            content = listOf(TextContent("result")),
        ).toMagratheaResult(
            server = McpServer("contract", "Contract"),
            remoteToolName = "unsafe\ntool",
            runtimeToolName = "mcp__contract__lookup",
            toolTitle = "Unsafe\nTitle",
            toolCallId = "call-contract",
        )

        assertEquals(null, result.origin)
        assertEquals("result", result.displayText)
    }

    private fun CallToolResult.mapped() = toMagratheaResult(
        server = McpServer("contract", "Contract"),
        remoteToolName = "lookup",
        runtimeToolName = "mcp__contract__lookup",
        toolTitle = "Lookup",
        toolCallId = "call-contract",
    )
}
