package dev.cint.contracts.mcp

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

@Serializable
data class McpToolDefinition(
    val name: String,
    val description: String,
    val inputSchema: JsonObject,
)

@Serializable
data class McpToolCall(
    val name: String,
    val arguments: JsonObject = JsonObject(emptyMap()),
)

@Serializable
data class McpToolContent(
    val type: String = "text",
    val text: String,
)

@Serializable
data class McpToolResult(
    val content: List<McpToolContent>,
    val structuredContent: JsonElement? = null,
    val isError: Boolean = false,
)

@Serializable
data class McpResourceDefinition(
    val uri: String,
    val name: String,
    val description: String? = null,
    val mimeType: String = "application/json",
)

@Serializable
data class McpResourceReadRequest(
    val uri: String,
)

@Serializable
data class McpResourceContent(
    val uri: String,
    val mimeType: String? = "application/json",
    val text: String? = null,
)

@Serializable
data class McpResourceReadResult(
    val contents: List<McpResourceContent>,
)

@Serializable
data class McpErrorDetail(
    val code: String,
    val message: String,
    val details: JsonObject? = null,
)

@Serializable
data class McpErrorPayload(
    val error: McpErrorDetail,
)
