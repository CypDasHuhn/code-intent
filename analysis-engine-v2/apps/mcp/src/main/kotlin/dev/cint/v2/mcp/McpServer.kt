package dev.cint.v2.mcp

import dev.cint.contracts.mcp.McpResourceContent
import dev.cint.contracts.mcp.McpResourceReadRequest
import dev.cint.contracts.mcp.McpResourceReadResult
import dev.cint.contracts.mcp.McpToolCall
import dev.cint.contracts.ports.InitCheckpointStore
import dev.cint.runtime.InitCheckpointStores
import dev.cint.contracts.ports.Runtime
import dev.cint.contracts.util.CancellationToken
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class McpServer(
    private val runtime: Runtime,
    input: InputStream = System.`in`,
    output: OutputStream = System.out,
    now: () -> Instant = { Clock.System.now() },
    checkpointStore: InitCheckpointStore = InitCheckpointStores.inMemory(),
    workflowId: String = "default",
) {
    private val reader = input.bufferedReader(Charsets.UTF_8)
    private val writer = output.bufferedWriter(Charsets.UTF_8)
    private val tools = McpRuntimeTools(runtime, now, checkpointStore, workflowId)
    private val executor =
        Executors.newSingleThreadExecutor { task ->
            Thread(task, "cint-mcp-tool").apply { isDaemon = true }
        }
    private val active = ConcurrentHashMap<String, AtomicBoolean>()
    private val runtimeLock = ReentrantLock()

    @Volatile
    var closed: Boolean = false
        private set

    @Volatile
    private var stopping = false

    fun run() {
        try {
            while (!stopping) {
                val line = reader.readLine() ?: break
                if (line.isBlank()) continue
                when (val parsed = parseMessage(line)) {
                    is Parsed.Invalid -> writeResponse(parsed.response)
                    is Parsed.Ignore -> Unit
                    is Parsed.Notification -> handleNotification(parsed.method, parsed.params)
                    is Parsed.Request -> handleRequest(parsed)
                }
            }
        } finally {
            stopping = true
            active.values.forEach { it.set(true) }
            executor.shutdownNow()
            runCatching { executor.awaitTermination(5, TimeUnit.SECONDS) }
            closed = true
            runCatching { runtime.shutdown() }
            runCatching { synchronized(writer) { writer.flush() } }
        }
    }

    fun handleLine(line: String): JsonObject? =
        when (val parsed = parseMessage(line)) {
            is Parsed.Invalid -> parsed.response
            is Parsed.Ignore -> null
            is Parsed.Notification -> {
                handleNotification(parsed.method, parsed.params)
                null
            }
            is Parsed.Request ->
                processRequest(
                    parsed.id,
                    parsed.method,
                    parsed.params,
                    CancellationToken.NONE,
                )
        }

    private fun handleRequest(request: Parsed.Request) {
        if (request.method !in LONG_RUNNING_METHODS) {
            writeResponse(
                processRequest(
                    request.id,
                    request.method,
                    request.params,
                    CancellationToken.NONE,
                ),
            )
            return
        }
        val key = request.id.key()
        val cancelled = AtomicBoolean(false)
        active[key] = cancelled
        executor.execute {
            try {
                val response =
                    processRequest(
                        request.id,
                        request.method,
                        request.params,
                        CancellationToken.from(cancelled::get),
                    )
                writeResponse(response)
            } finally {
                active.remove(key, cancelled)
            }
        }
    }

    private fun processRequest(
        id: JsonElement,
        method: String,
        params: JsonObject,
        cancellation: CancellationToken,
    ): JsonObject =
        try {
            successResponse(id, dispatch(method, params, cancellation))
        } catch (error: McpProtocolException) {
            errorResponse(id, error.code, error.message ?: "protocol error", error.data)
        } catch (error: Exception) {
            errorResponse(
                id,
                -32603,
                error.message ?: "internal error",
                buildJsonObject { put("code", "INTERNAL_ERROR") },
            )
        }

    private fun dispatch(
        method: String,
        params: JsonObject,
        cancellation: CancellationToken,
    ): JsonElement =
        when (method) {
            "initialize" -> initialize(params)
            "ping" -> JsonObject(emptyMap())
            "tools/list" ->
                buildJsonObject {
                    put("tools", encodeMcp(McpCatalog.tools()))
                }
            "tools/call" -> withRuntimeLock { callTool(params, cancellation) }
            "resources/list" ->
                buildJsonObject {
                    put("resources", encodeMcp(McpCatalog.resources()))
                }
            "resources/templates/list" ->
                buildJsonObject {
                    put("resourceTemplates", buildJsonArray {})
                }
            "resources/read" -> withRuntimeLock { readResource(params, cancellation) }
            "prompts/list" ->
                buildJsonObject {
                    put("prompts", buildJsonArray {})
                }
            "shutdown" -> {
                stopping = true
                JsonNull
            }
            else -> throw McpProtocolException(
                -32601,
                "Unknown method: $method",
                buildJsonObject {
                    put("code", "METHOD_NOT_FOUND")
                    put("method", method)
                },
            )
        }

    private fun <T> withRuntimeLock(block: () -> T): T {
        runtimeLock.lock()
        return try {
            block()
        } finally {
            runtimeLock.unlock()
        }
    }

    private fun initialize(params: JsonObject): JsonObject {
        val requested = params.string("protocolVersion")
        val version = requested?.takeIf { it in SUPPORTED_PROTOCOL_VERSIONS }
            ?: SUPPORTED_PROTOCOL_VERSIONS.last()
        return buildJsonObject {
            put("protocolVersion", version)
            put(
                "capabilities",
                buildJsonObject {
                    put(
                        "tools",
                        buildJsonObject {
                            put("listChanged", false)
                        },
                    )
                    put(
                        "resources",
                        buildJsonObject {
                            put("subscribe", false)
                            put("listChanged", false)
                        },
                    )
                },
            )
            put(
                "serverInfo",
                buildJsonObject {
                    put("name", "code-intent-mcp")
                    put("version", "0.1.0")
                },
            )
            put(
                "instructions",
                "Read V2 analysis and knowledge, then write knowledge through validated tools.",
            )
        }
    }

    private fun callTool(
        params: JsonObject,
        cancellation: CancellationToken,
    ): JsonElement {
        val call =
            try {
                decodeMcp<McpToolCall>(params)
            } catch (error: Exception) {
                throw McpProtocolException(
                    -32602,
                    "Invalid tools/call parameters: ${error.message ?: "invalid input"}",
                    buildJsonObject { put("code", "INVALID_PARAMS") },
                )
            }
        if (call.name.isBlank()) {
            throw McpProtocolException(
                -32602,
                "tools/call requires a tool name",
                buildJsonObject { put("code", "INVALID_PARAMS") },
            )
        }
        return encodeMcp(tools.call(call.name, call.arguments, cancellation))
    }

    private fun readResource(
        params: JsonObject,
        cancellation: CancellationToken,
    ): JsonElement {
        val request =
            try {
                decodeMcp<McpResourceReadRequest>(params)
            } catch (error: Exception) {
                throw McpProtocolException(
                    -32602,
                    "Invalid resources/read parameters: ${error.message ?: "invalid input"}",
                    buildJsonObject { put("code", "INVALID_PARAMS") },
                )
            }
        if (request.uri.isBlank()) {
            throw McpProtocolException(
                -32602,
                "resources/read requires a uri",
                buildJsonObject { put("code", "INVALID_PARAMS") },
            )
        }
        val text = tools.readResource(request.uri, cancellation)
        return encodeMcp(
            McpResourceReadResult(
                contents = listOf(McpResourceContent(uri = request.uri, text = text)),
            ),
        )
    }

    private fun handleNotification(
        method: String,
        params: JsonObject,
    ) {
        when (method) {
            "exit" -> stopping = true
            "notifications/cancelled" -> {
                val requestId = params["requestId"] ?: return
                active[requestId.key()]?.set(true)
            }
            else -> Unit
        }
    }

    private fun parseMessage(line: String): Parsed {
        val element =
            try {
                Json.parseToJsonElement(line)
            } catch (error: Exception) {
                return Parsed.Invalid(
                    errorResponse(
                        JsonNull,
                        -32700,
                        "Invalid JSON: ${error.message ?: "parse failure"}",
                    ),
                )
            }
        val message = element as? JsonObject
            ?: return Parsed.Invalid(errorResponse(JsonNull, -32600, "JSON-RPC message must be an object"))
        if (message.string("jsonrpc") != "2.0") {
            return Parsed.Invalid(errorResponse(JsonNull, -32600, "jsonrpc must be exactly \"2.0\""))
        }

        val method = message.string("method")
        val hasId = message.containsKey("id")
        val id = message["id"] ?: JsonNull
        if (method == null) {
            if (message.containsKey("result") || message.containsKey("error")) return Parsed.Ignore
            return if (hasId) {
                Parsed.Invalid(errorResponse(id, -32600, "JSON-RPC request must contain a method"))
            } else {
                Parsed.Ignore
            }
        }

        val params =
            when (val raw = message["params"]) {
                null, is JsonNull -> JsonObject(emptyMap())
                is JsonObject -> raw
                else -> {
                    return if (hasId) {
                        Parsed.Invalid(errorResponse(id, -32602, "params must be an object"))
                    } else {
                        Parsed.Ignore
                    }
                }
            }

        return if (hasId) {
            Parsed.Request(id, method, params)
        } else {
            Parsed.Notification(method, params)
        }
    }

    private fun writeResponse(response: JsonObject) {
        synchronized(writer) {
            writer.write(response.toString())
            writer.newLine()
            writer.flush()
        }
    }

    private fun successResponse(
        id: JsonElement,
        result: JsonElement,
    ): JsonObject = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        put("result", result)
    }

    private fun errorResponse(
        id: JsonElement,
        code: Int,
        message: String,
        data: JsonElement? = null,
    ): JsonObject = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        put(
            "error",
            buildJsonObject {
                put("code", code)
                put("message", message)
                if (data != null) put("data", data)
            },
        )
    }

    private fun JsonElement.key(): String =
        when (this) {
            is JsonPrimitive -> if (isString) "s:$content" else "n:$content"
            else -> "null"
        }

    private sealed interface Parsed {
        data class Request(
            val id: JsonElement,
            val method: String,
            val params: JsonObject,
        ) : Parsed

        data class Notification(
            val method: String,
            val params: JsonObject,
        ) : Parsed

        data class Invalid(val response: JsonObject) : Parsed

        data object Ignore : Parsed
    }

    private companion object {
        val SUPPORTED_PROTOCOL_VERSIONS =
            listOf("2024-11-05", "2025-03-26", "2025-06-18")
        val LONG_RUNNING_METHODS = setOf("tools/call", "resources/read")
    }
}
