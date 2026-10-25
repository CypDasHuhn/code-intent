package dev.cint.v2.mcp

import dev.cint.contracts.mcp.McpToolContent
import dev.cint.contracts.mcp.McpToolResult
import dev.cint.contracts.util.ContractError
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal class McpInputException(
    val error: ContractError,
) : Exception(error.toString())

internal class McpProtocolException(
    val code: Int,
    message: String,
    val data: JsonElement? = null,
) : Exception(message)

internal fun toolSuccess(payload: JsonElement): McpToolResult =
    McpToolResult(
        content = listOf(McpToolContent(text = payload.toString())),
        structuredContent = payload,
    )

internal fun toolSuccess(value: String): McpToolResult = toolSuccess(JsonPrimitive(value))

internal fun toolFailure(
    error: ContractError,
    extra: JsonObject? = null,
): McpToolResult {
    val payload = buildJsonObject {
        put("error", contractErrorJson(error))
        extra?.forEach { (key, value) -> put(key, value) }
    }
    return McpToolResult(
        content = listOf(McpToolContent(text = payload.toString())),
        structuredContent = payload,
        isError = true,
    )
}
