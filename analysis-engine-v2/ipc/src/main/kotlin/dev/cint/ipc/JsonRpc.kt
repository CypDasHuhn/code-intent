package dev.cint.ipc

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

sealed interface JsonRpcId {
    data class NumberId(val value: Long) : JsonRpcId
    data class StringId(val value: String) : JsonRpcId
    data object NullId : JsonRpcId
}

val JsonRpcId.key: String
    get() = when (this) {
        is JsonRpcId.NumberId -> "n:$value"
        is JsonRpcId.StringId -> "s:$value"
        JsonRpcId.NullId -> "null"
    }

sealed interface JsonRpcMessage {
    data class Request(
        val id: JsonRpcId,
        val method: String,
        val params: JsonObject = JsonObject(emptyMap()),
    ) : JsonRpcMessage

    data class Notification(
        val method: String,
        val params: JsonObject = JsonObject(emptyMap()),
    ) : JsonRpcMessage

    data class Response(
        val id: JsonRpcId,
        val result: JsonElement,
    ) : JsonRpcMessage

    data class ErrorResponse(
        val id: JsonRpcId,
        val error: JsonRpcError,
    ) : JsonRpcMessage
}

data class JsonRpcError(
    val code: Int,
    val message: String,
    val data: JsonElement? = null,
)

object JsonRpcErrorCodes {
    const val PARSE_ERROR = -32700
    const val INVALID_REQUEST = -32600
    const val METHOD_NOT_FOUND = -32601
    const val INVALID_PARAMS = -32602
    const val INTERNAL_ERROR = -32603
    const val PROTOCOL_ERROR = -32000
    const val HANDSHAKE_REQUIRED = -32002
    const val DUPLICATE_ID = -32003
    const val CONNECTION_CLOSED = -32004
}

class IpcProtocolException(
    val rpcError: JsonRpcError,
    cause: Throwable? = null,
) : Exception(rpcError.message, cause)

object IpcErrors {
    fun parse(message: String): IpcProtocolException =
        IpcProtocolException(JsonRpcError(JsonRpcErrorCodes.PARSE_ERROR, message, errorData("parse-error")))

    fun invalidRequest(message: String): IpcProtocolException =
        IpcProtocolException(JsonRpcError(JsonRpcErrorCodes.INVALID_REQUEST, message, errorData("invalid-request")))

    fun invalidParams(message: String): IpcProtocolException =
        IpcProtocolException(JsonRpcError(JsonRpcErrorCodes.INVALID_PARAMS, message, errorData("invalid-params")))

    fun methodNotFound(method: String): JsonRpcError =
        JsonRpcError(
            JsonRpcErrorCodes.METHOD_NOT_FOUND,
            "unknown method: $method",
            errorData("unknown-method", JsonObject(mapOf("method" to JsonPrimitive(method)))),
        )

    fun handshakeRequired(method: String): JsonRpcError =
        JsonRpcError(
            JsonRpcErrorCodes.HANDSHAKE_REQUIRED,
            "initialize required before $method",
            errorData("handshake-required"),
        )

    fun duplicateId(id: JsonRpcId): JsonRpcError =
        JsonRpcError(
            JsonRpcErrorCodes.DUPLICATE_ID,
            "duplicate request id: ${id.display()}",
            errorData("duplicate-id", JsonObject(mapOf("id" to id.toJson()))),
        )

    fun connectionClosed(message: String = "connection closed"): JsonRpcError =
        JsonRpcError(JsonRpcErrorCodes.CONNECTION_CLOSED, message, errorData("connection-closed"))

    fun protocol(message: String): JsonRpcError =
        JsonRpcError(JsonRpcErrorCodes.PROTOCOL_ERROR, message, errorData("protocol-error"))

    private fun errorData(kind: String, details: JsonObject? = null): JsonObject =
        buildJsonObject {
            put("kind", kind)
            if (details != null) put("details", details)
        }
}

fun JsonRpcId.display(): String =
    when (this) {
        is JsonRpcId.NumberId -> value.toString()
        is JsonRpcId.StringId -> value
        JsonRpcId.NullId -> "null"
    }

fun JsonRpcId.toJson(): JsonElement =
    when (this) {
        is JsonRpcId.NumberId -> JsonPrimitive(value)
        is JsonRpcId.StringId -> JsonPrimitive(value)
        JsonRpcId.NullId -> JsonNull
    }

class JsonRpcCodec(
    private val json: Json = Json {
        ignoreUnknownKeys = false
        isLenient = false
        allowSpecialFloatingPointValues = false
    },
) {
    fun decode(line: String): JsonRpcMessage {
        val element =
            try {
                json.parseToJsonElement(line)
            } catch (error: Exception) {
                throw IpcErrors.parse("invalid JSON: ${error.message ?: "parse failure"}")
            }
        val message = element as? JsonObject ?: throw IpcErrors.invalidRequest("JSON-RPC message must be an object")
        val allowedKeys = setOf("jsonrpc", "id", "method", "params", "result", "error")
        val unknownKeys = message.keys - allowedKeys
        if (unknownKeys.isNotEmpty()) {
            throw IpcErrors.invalidRequest("unknown JSON-RPC field(s): ${unknownKeys.sorted().joinToString(", ")}")
        }
        if (message["jsonrpc"]?.jsonString() != "2.0") {
            throw IpcErrors.invalidRequest("jsonrpc must be exactly \"2.0\"")
        }

        val hasMethod = message.containsKey("method")
        val hasResult = message.containsKey("result")
        val hasError = message.containsKey("error")
        val id = message["id"]?.let(::decodeId)

        return when {
            hasMethod -> {
                if (hasResult || hasError) {
                    throw IpcErrors.invalidRequest("request must not contain result or error")
                }
                val method = message["method"]?.jsonString()?.takeIf { it.isNotBlank() }
                    ?: throw IpcErrors.invalidRequest("method must be a non-blank string")
                val params = decodeParams(message["params"])
                if (id == null) {
                    JsonRpcMessage.Notification(method, params)
                } else {
                    JsonRpcMessage.Request(id, method, params)
                }
            }

            hasResult || hasError -> {
                if (hasResult && hasError) {
                    throw IpcErrors.invalidRequest("response must not contain both result and error")
                }
                if (!message.containsKey("id")) {
                    throw IpcErrors.invalidRequest("response must contain an id")
                }
                val responseId = id ?: JsonRpcId.NullId
                if (hasError) {
                    JsonRpcMessage.ErrorResponse(responseId, decodeError(message["error"]))
                } else {
                    JsonRpcMessage.Response(responseId, message.getValue("result"))
                }
            }

            else -> throw IpcErrors.invalidRequest("message must contain method, result, or error")
        }
    }

    fun encode(message: JsonRpcMessage): String =
        when (message) {
            is JsonRpcMessage.Request ->
                buildJsonObject {
                    put("jsonrpc", "2.0")
                    put("id", message.id.toJson())
                    put("method", message.method)
                    put("params", message.params)
                }.toString()

            is JsonRpcMessage.Notification ->
                buildJsonObject {
                    put("jsonrpc", "2.0")
                    put("method", message.method)
                    put("params", message.params)
                }.toString()

            is JsonRpcMessage.Response ->
                buildJsonObject {
                    put("jsonrpc", "2.0")
                    put("id", message.id.toJson())
                    put("result", message.result)
                }.toString()

            is JsonRpcMessage.ErrorResponse ->
                buildJsonObject {
                    put("jsonrpc", "2.0")
                    put("id", message.id.toJson())
                    put(
                        "error",
                        buildJsonObject {
                            put("code", message.error.code)
                            put("message", message.error.message)
                            if (message.error.data != null) put("data", message.error.data)
                        },
                    )
                }.toString()
        }

    private fun decodeParams(element: JsonElement?): JsonObject {
        if (element == null) return JsonObject(emptyMap())
        return element as? JsonObject
            ?: throw IpcErrors.invalidParams("params must be an object")
    }

    private fun decodeId(element: JsonElement): JsonRpcId =
        when (element) {
            is JsonNull -> JsonRpcId.NullId
            is JsonPrimitive -> {
                if (element.isString) {
                    JsonRpcId.StringId(element.content)
                } else {
                    val value = element.longOrNull
                        ?: throw IpcErrors.invalidRequest("numeric id must be an integer")
                    JsonRpcId.NumberId(value)
                }
            }
            else -> throw IpcErrors.invalidRequest("id must be a string, integer, or null")
        }

    private fun decodeError(element: JsonElement?): JsonRpcError {
        val error = element as? JsonObject ?: throw IpcErrors.invalidRequest("error must be an object")
        val unknown = error.keys - setOf("code", "message", "data")
        if (unknown.isNotEmpty()) {
            throw IpcErrors.invalidRequest("unknown JSON-RPC error field(s): ${unknown.sorted().joinToString(", ")}")
        }
        val code = (error["code"] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toIntOrNull()
            ?: throw IpcErrors.invalidRequest("error.code must be an integer")
        val message = error["message"]?.jsonString()
            ?: throw IpcErrors.invalidRequest("error.message must be a string")
        return JsonRpcError(code, message, error["data"])
    }
}

private fun JsonElement.jsonString(): String? =
    (this as? JsonPrimitive)?.takeIf { it.isString }?.content
