package saien.magrathea.mcp

import io.modelcontextprotocol.kotlin.sdk.types.AudioContent
import io.modelcontextprotocol.kotlin.sdk.types.BlobResourceContents
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.EmbeddedResource
import io.modelcontextprotocol.kotlin.sdk.types.ImageContent
import io.modelcontextprotocol.kotlin.sdk.types.McpJson
import io.modelcontextprotocol.kotlin.sdk.types.ResourceLink
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
    val displayText = content.mapNotNull { block ->
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

    return ToolExecutionResult(
        toolCallId = toolCallId,
        toolName = runtimeToolName,
        result = result,
        isError = isError == true,
        displayText = displayText,
        metadata = buildJsonObject {
            put(MCP_SERVER_ID_KEY, server.id)
            put(MCP_SERVER_NAME_KEY, server.displayName)
            put(MCP_TOOL_NAME_KEY, remoteToolName)
            put(MCP_TOOL_TITLE_KEY, toolTitle)
            if (structuredContent != null) {
                put("mcpContent", encodedContent)
            }
            meta?.let { put("mcpMeta", it) }
        },
    )
}
