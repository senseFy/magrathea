package saien.magrathea.mcp

import io.modelcontextprotocol.kotlin.sdk.types.AudioContent
import io.modelcontextprotocol.kotlin.sdk.types.Annotations
import io.modelcontextprotocol.kotlin.sdk.types.BlobResourceContents
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ContentBlock
import io.modelcontextprotocol.kotlin.sdk.types.EmbeddedResource
import io.modelcontextprotocol.kotlin.sdk.types.ImageContent
import io.modelcontextprotocol.kotlin.sdk.types.McpJson
import io.modelcontextprotocol.kotlin.sdk.types.ResourceLink
import io.modelcontextprotocol.kotlin.sdk.types.Role
import io.modelcontextprotocol.kotlin.sdk.types.TaskSupport
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.TextResourceContents
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import io.modelcontextprotocol.kotlin.sdk.types.UnknownResourceContents
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import saien.magrathea.core.ToolExecutionResult
import saien.magrathea.core.InlineToolImageSource
import saien.magrathea.core.ToolOrigin
import saien.magrathea.core.ToolResultAudience
import saien.magrathea.core.ToolResultImageContent
import saien.magrathea.core.ToolResultTextContent

internal fun Tool.toDescriptor(server: McpServer): McpToolDescriptor {
    val taskSupport = when (execution?.taskSupport ?: TaskSupport.Forbidden) {
        TaskSupport.Forbidden -> McpTaskSupport.FORBIDDEN
        TaskSupport.Optional -> McpTaskSupport.OPTIONAL
        TaskSupport.Required -> McpTaskSupport.REQUIRED
    }
    val displayTitle = annotations?.title?.takeIf(String::isNotBlank)
        ?: title?.takeIf(String::isNotBlank)
        ?: name
    return McpToolDescriptor(
        server = server,
        remoteName = name,
        runtimeName = McpToolNames.runtimeName(server.id, name),
        title = displayTitle,
        description = description?.takeIf(String::isNotBlank) ?: displayTitle,
        inputSchema = inputSchema.toJsonSchema(),
        outputSchema = outputSchema?.toJsonSchema(),
        hints = McpToolHints(
            readOnly = annotations?.readOnlyHint,
            destructive = annotations?.destructiveHint,
            idempotent = annotations?.idempotentHint,
            openWorld = annotations?.openWorldHint,
        ),
        icons = icons.orEmpty().map { icon ->
            McpToolIcon(
                source = icon.src,
                mimeType = icon.mimeType,
                sizes = icon.sizes.orEmpty(),
                theme = icon.theme?.name?.lowercase(),
            )
        },
        taskSupport = taskSupport,
        compatibility = if (taskSupport == McpTaskSupport.REQUIRED) {
            McpToolCompatibility.REQUIRES_TASKS
        } else {
            McpToolCompatibility.SUPPORTED
        },
        metadata = meta ?: JsonObject(emptyMap()),
    )
}

private fun ToolSchema.toJsonSchema(): JsonObject = buildJsonObject {
    put("type", "object")
    schema?.let { put("\$schema", it) }
    properties?.let { put("properties", it) }
    required?.let { requiredNames ->
        put("required", buildJsonArray { requiredNames.forEach { add(JsonPrimitive(it)) } })
    }
    defs?.let { put("\$defs", it) }
}

internal fun CallToolResult.toMagratheaResult(
    server: McpServer,
    remoteToolName: String,
    runtimeToolName: String,
    toolTitle: String,
    toolCallId: String,
): ToolExecutionResult {
    val encoded = McpJson.encodeToJsonElement(CallToolResult.serializer(), this).jsonObject
    val encodedContent = encoded["content"] ?: JsonArray(emptyList())
    val result: JsonElement = structuredContent ?: buildJsonObject {
        put("content", encodedContent)
    }
    // The encoded MCP content envelope is the durable canonical representation. Model projection
    // uses the typed blocks so modality filtering cannot leak inline media as JSON/base64 text.
    val modelResultVisible = structuredContent != null || content.isEmpty()
    val userVisibleText = content
        .filter { block -> ToolResultAudience.USER in block.toMagratheaAudiences() }
        .mapNotNull { block ->
            when (block) {
                is TextContent -> block.text
                is ResourceLink -> block.title ?: block.name.ifBlank { block.uri }
                is EmbeddedResource -> when (val resource = block.resource) {
                    is TextResourceContents -> resource.text
                    is BlobResourceContents -> "[resource: ${resource.mimeType ?: "application/octet-stream"}]"
                    is UnknownResourceContents -> "[resource: ${resource.mimeType ?: "application/octet-stream"}]"
                }
                is ImageContent -> "[image: ${block.mimeType}]"
                is AudioContent -> "[audio: ${block.mimeType}]"
            }
        }.joinToString("\n").ifBlank { null }
    val displayText = userVisibleText ?: if (content.isNotEmpty()) {
        if (isError == true) "Tool failed." else "Tool completed."
    } else {
        null
    }
    val resultContent = content.mapNotNull { block ->
        when (block) {
            is TextContent -> block.text
                .takeIf(String::isNotBlank)
                ?.let { text ->
                    ToolResultTextContent(
                        text = text,
                        audiences = block.toMagratheaAudiences(),
                    )
                }
            is ImageContent -> ToolResultImageContent(
                source = InlineToolImageSource(block.data),
                mimeType = block.mimeType.trim().lowercase(),
                audiences = block.toMagratheaAudiences(),
            )
            is ResourceLink -> block.toModelSafeTextContent()
            is EmbeddedResource -> block.toModelSafeTextContent()
            is AudioContent -> ToolResultTextContent(
                text = "[audio content omitted: ${block.mimeType}]",
                audiences = block.toMagratheaAudiences(),
            )
        }
    }

    return ToolExecutionResult(
        toolCallId = toolCallId,
        toolName = runtimeToolName,
        result = result,
        isError = isError == true,
        displayText = displayText,
        content = resultContent,
        modelResultVisible = modelResultVisible,
        metadata = buildJsonObject {
            meta?.let { put("mcpMeta", it) }
        },
        origin = mcpToolOriginOrNull(server, remoteToolName, toolTitle),
    )
}

private fun mcpToolOriginOrNull(
    server: McpServer,
    remoteToolName: String,
    toolTitle: String,
): ToolOrigin? {
    val label = toolTitle.takeIf(String::isSafeToolOriginValue) ?: remoteToolName
    return runCatching {
        ToolOrigin(
            sourceId = server.id,
            sourceLabel = server.displayName,
            toolId = remoteToolName,
            toolLabel = label,
        )
    }.getOrNull()
}

private fun String.isSafeToolOriginValue(): Boolean =
    length in 1..MAX_TOOL_ORIGIN_VALUE_CHARS &&
        this == trim() &&
        none(Char::isMcpControlCharacter)

private const val MAX_TOOL_ORIGIN_VALUE_CHARS = 256

private fun ResourceLink.toModelSafeTextContent(): ToolResultTextContent = ToolResultTextContent(
    text = buildString {
        append(title ?: name.ifBlank { uri })
        if (uri.isNotBlank()) append("\n").append(uri)
        description?.takeIf(String::isNotBlank)?.let { append("\n").append(it) }
    },
    audiences = toMagratheaAudiences(),
)

private fun EmbeddedResource.toModelSafeTextContent(): ToolResultTextContent = ToolResultTextContent(
    text = when (val resource = resource) {
        is TextResourceContents -> resource.text.takeIf(String::isNotBlank)
            ?: "[empty text resource: ${resource.mimeType ?: "text/plain"}]"
        is BlobResourceContents ->
            "[binary resource content omitted: ${resource.mimeType ?: "application/octet-stream"}]"
        is UnknownResourceContents ->
            "[resource content omitted: ${resource.mimeType ?: "application/octet-stream"}]"
    },
    audiences = toMagratheaAudiences(),
)

private val DEFAULT_TOOL_RESULT_AUDIENCES = setOf(
    ToolResultAudience.MODEL,
    ToolResultAudience.USER,
)

private fun ContentBlock.toMagratheaAudiences(): Set<ToolResultAudience> = when (this) {
    is TextContent -> annotations.toMagratheaAudiences()
    is ImageContent -> annotations.toMagratheaAudiences()
    is AudioContent -> annotations.toMagratheaAudiences()
    is ResourceLink -> annotations.toMagratheaAudiences()
    is EmbeddedResource -> annotations.toMagratheaAudiences()
}

private fun Annotations?.toMagratheaAudiences(): Set<ToolResultAudience> {
    val declared = this?.audience.orEmpty()
    if (declared.isEmpty()) {
        return DEFAULT_TOOL_RESULT_AUDIENCES
    }
    return declared.mapTo(linkedSetOf()) { role ->
        when (role) {
            Role.Assistant -> ToolResultAudience.MODEL
            Role.User -> ToolResultAudience.USER
        }
    }
}
