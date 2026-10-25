package dev.cint.v2.lsp

import dev.cint.contracts.data.Span
import dev.cint.contracts.facts.Diagnostic
import dev.cint.contracts.facts.DiagnosticSeverity
import dev.cint.contracts.facts.OffsetUnit
import dev.cint.contracts.util.ContractError
import dev.cint.contracts.util.ContractResult
import dev.cint.contracts.util.ResultFactory
import java.io.EOFException
import java.nio.charset.StandardCharsets
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

internal data class LspSessionOptions(
    val languageId: String,
    val rootUri: String? = null,
    val requestTimeoutMs: Long = 5000,
    val initTimeoutMs: Long = 30000,
    val clientName: String = "code-intent",
    val clientVersion: String = "0.1.0",
)

internal class LspSession(
    private val transport: LspTransport,
    private val options: LspSessionOptions,
) : AutoCloseable {
    private val nextId = AtomicLong(0)
    private val pending = ConcurrentHashMap<Long, CompletableFuture<ContractResult<Any?>>>()
    private val pendingOperations = ConcurrentHashMap<Long, String>()
    private val documents = ConcurrentHashMap<String, OpenDocument>()
    private val pushedDiagnostics = ConcurrentHashMap<String, DiagnosticState>()
    private val uriLocks = ConcurrentHashMap<String, Any>()
    private val documentLock = Any()
    private val lifecycleLock = Any()
    private val sendLock = Any()
    private val closed = AtomicBoolean(false)
    private val initialized = AtomicBoolean(false)
    private val failure = AtomicReference<ContractError?>(null)
    private var capabilities: Map<String, Any?> = emptyMap()
    private var syncKind: SyncKind = SyncKind.FULL
    private var openClose: Boolean = true

    @Volatile
    internal var notificationWriteGate: ((String) -> Unit)? = null

    @Volatile
    internal var notificationWrittenGate: ((String) -> Unit)? = null
    private val reader =
        Thread({ readLoop() }, "cint-lsp-reader-${transport.description.hashCode()}").apply {
            isDaemon = true
            start()
        }

    fun initialize(): ContractResult<Unit> =
        try {
            if (initialized.get()) {
                ResultFactory.success(Unit)
            } else {
                initializeSession()
            }
        } catch (error: Exception) {
            ResultFactory.failure(
                ContractError.DependencyUnavailable(
                    "lsp",
                    "LSP initialization failed: ${error.message ?: error::class.simpleName}",
                ),
            )
        }

    private fun initializeSession(): ContractResult<Unit> {
        val params =
            linkedMapOf<String, Any?>(
                "processId" to ProcessHandle.current().pid(),
                "clientInfo" to linkedMapOf("name" to options.clientName, "version" to options.clientVersion),
                "rootUri" to options.rootUri,
                "capabilities" to clientCapabilities(),
                "workspaceFolders" to options.rootUri?.let { listOf(linkedMapOf("uri" to it, "name" to "root")) },
            )
        val response = sendRequest("initialize", params, options.initTimeoutMs, "initialize")
        if (response.isFailure) return ResultFactory.failure(response.errorOrNull ?: startupError("initialize failed"))
        val result = response.getValue().asObjectOrNull()
        capabilities = result?.objectOrNull("capabilities") ?: emptyMap<String, Any?>()
        syncKind = parseSyncKind(capabilities)
        openClose = parseOpenClose(capabilities, syncKind)
        sendNotificationInternal("initialized", emptyMap<String, Any?>())
        initialized.set(true)
        return ResultFactory.success(Unit)
    }

    fun openDocument(
        uri: String,
        text: String,
        version: Long = 1,
        languageId: String = options.languageId,
    ): ContractResult<Unit> {
        synchronized(uriLock(uri)) {
            val previous = synchronized(documentLock) { documents[uri] }
            if (previous != null) return changeDocument(uri, text, version, languageId)
            rejectClosed()?.let { return it }
            documentSyncUnsupported()?.let { return ResultFactory.failure(it) }
            synchronized(documentLock) {
                documents[uri] = OpenDocument(text, version, languageId)
                clearDiagnostics(uri)
            }
            val result =
                sendNotification(
                    "textDocument/didOpen",
                    linkedMapOf(
                        "textDocument" to
                            linkedMapOf(
                                "uri" to uri,
                                "languageId" to languageId,
                                "version" to version,
                                "text" to text,
                            ),
                    ),
                )
            if (result.isFailure) {
                synchronized(documentLock) {
                    documents.remove(uri)
                    clearDiagnostics(uri)
                }
            }
            return result
        }
    }

    fun changeDocument(
        uri: String,
        text: String,
        version: Long,
        languageId: String = options.languageId,
    ): ContractResult<Unit> {
        synchronized(uriLock(uri)) {
            val previous = synchronized(documentLock) { documents[uri] }
            if (previous == null) return openDocument(uri, text, version, languageId)
            if (previous.text == text && previous.version == version) return ResultFactory.success(Unit)
            rejectClosed()?.let { return it }
            documentSyncUnsupported()?.let { return ResultFactory.failure(it) }
            val contentChanges =
                when (syncKind) {
                    SyncKind.NONE -> {
                        return ResultFactory.failure(
                            ContractError.PreconditionFailed("LSP server does not support document changes"),
                        )
                    }

                    SyncKind.FULL -> {
                        listOf(linkedMapOf("text" to text))
                    }

                    SyncKind.INCREMENTAL -> {
                        listOf(incrementalChange(previous.text, text))
                    }
                }
            val previousDiagnostics = synchronized(documentLock) { pushedDiagnostics[uri] }
            synchronized(documentLock) {
                documents[uri] = OpenDocument(text, version, previous.languageId)
                clearDiagnostics(uri)
            }
            val result =
                sendNotification(
                    "textDocument/didChange",
                    linkedMapOf(
                        "textDocument" to linkedMapOf("uri" to uri, "version" to version),
                        "contentChanges" to contentChanges,
                    ),
                )
            if (result.isFailure) {
                synchronized(documentLock) {
                    documents[uri] = previous
                    if (previousDiagnostics != null) {
                        pushedDiagnostics[uri] = previousDiagnostics
                    } else {
                        clearDiagnostics(uri)
                    }
                }
            }
            return result
        }
    }

    fun closeDocument(uri: String): ContractResult<Unit> {
        synchronized(uriLock(uri)) {
            rejectClosed()?.let { return it }
            documentSyncUnsupported()?.let { return ResultFactory.failure(it) }
            val previous = synchronized(documentLock) { documents[uri] }
            val previousDiagnostics = synchronized(documentLock) { pushedDiagnostics[uri] }
            synchronized(documentLock) {
                documents.remove(uri)
                clearDiagnostics(uri)
            }
            val result = sendNotification("textDocument/didClose", linkedMapOf("textDocument" to linkedMapOf("uri" to uri)))
            if (result.isFailure && previous != null) {
                synchronized(documentLock) {
                    documents[uri] = previous
                    if (previousDiagnostics != null) pushedDiagnostics[uri] = previousDiagnostics
                }
            }
            return result
        }
    }

    fun documentFor(
        uri: String,
        version: Long,
    ): ContractResult<DocumentSnapshot> {
        synchronized(uriLock(uri)) {
            synchronized(documentLock) {
                val document =
                    documents[uri]
                        ?: return ResultFactory.failure(
                            ContractError.InvalidInput("Document is not open: $uri", uri),
                        )
                if (document.version != version) {
                    return ResultFactory.failure(
                        ContractError.InvalidInput(
                            "Document version mismatch for $uri: requested $version but open document is ${document.version}",
                            uri,
                        ),
                    )
                }
                return ResultFactory.success(DocumentSnapshot(document.text, document.version, document.languageId))
            }
        }
    }

    fun ensureOpen(
        uri: String,
        version: Long,
    ): ContractResult<Unit> = documentFor(uri, version).flatMap { ResultFactory.success(Unit) }

    fun textFor(uri: String): String? =
        synchronized(uriLock(uri)) {
            synchronized(documentLock) { documents[uri]?.text }
        }

    fun diagnosticsFor(
        uri: String,
        version: Long,
    ): List<Diagnostic>? =
        synchronized(documentLock) {
            val state = pushedDiagnostics[uri] ?: return null
            val documentVersion = documents[uri]?.version
            if (state.version != null && documentVersion != null && state.version != documentVersion) {
                return null
            }
            state.diagnostics
        }

    fun supports(capability: String): Boolean {
        val value = capabilities[capability] ?: return false
        return value == true || value is Map<*, *>
    }

    internal fun rpc(
        method: String,
        params: Any?,
        timeoutMs: Long,
        operation: String,
    ): ContractResult<Any?> = sendRequest(method, params, timeoutMs, operation)

    private fun sendRequest(
        method: String,
        params: Any?,
        timeoutMs: Long,
        operation: String,
    ): ContractResult<Any?> {
        val id = nextId.incrementAndGet()
        val future = CompletableFuture<ContractResult<Any?>>()
        synchronized(lifecycleLock) {
            failure.get()?.let { return ResultFactory.failure(it) }
            if (closed.get()) {
                return ResultFactory.failure(ContractError.DependencyUnavailable("lsp", "LSP session is closed"))
            }
            pending[id] = future
            pendingOperations[id] = operation
        }
        try {
            sendMessage(
                linkedMapOf(
                    "jsonrpc" to "2.0",
                    "id" to id,
                    "method" to method,
                    "params" to params,
                ),
            )
        } catch (error: Exception) {
            pending.remove(id)
            pendingOperations.remove(id)
            return ResultFactory.failure(connectionError("could not send $operation", error))
        }
        val effectiveTimeout = timeoutMs.coerceAtLeast(1)
        return try {
            future.get(effectiveTimeout, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            pending.remove(id)
            pendingOperations.remove(id)
            runCatching { sendNotificationInternal("$/cancelRequest", linkedMapOf("id" to id)) }
            ResultFactory.failure(ContractError.Timeout(operation, effectiveTimeout))
        } catch (_: InterruptedException) {
            pending.remove(id)
            pendingOperations.remove(id)
            Thread.currentThread().interrupt()
            ResultFactory.failure(ContractError.Cancelled(operation))
        } catch (error: ExecutionException) {
            pending.remove(id)
            pendingOperations.remove(id)
            ResultFactory.failure(
                ContractError.InternalError("LSP request $operation failed", error.cause?.message ?: error.message),
            )
        }
    }

    private fun sendNotification(
        method: String,
        params: Any?,
    ): ContractResult<Unit> {
        rejectClosed()?.let { return it }
        return try {
            notificationWriteGate?.invoke(method)
            sendNotificationInternal(method, params)
            notificationWrittenGate?.invoke(method)
            ResultFactory.success(Unit)
        } catch (error: Exception) {
            ResultFactory.failure(connectionError("could not send $method", error))
        }
    }

    private fun rejectClosed(): ContractResult<Unit>? {
        failure.get()?.let { return ResultFactory.failure(it) }
        if (closed.get()) {
            return ResultFactory.failure(ContractError.DependencyUnavailable("lsp", "LSP session is closed"))
        }
        return null
    }

    private fun sendNotificationInternal(
        method: String,
        params: Any?,
    ) {
        sendMessage(
            linkedMapOf(
                "jsonrpc" to "2.0",
                "method" to method,
                "params" to params,
            ),
        )
    }

    private fun sendMessage(message: Map<String, Any?>) {
        val body = Json.write(message).toByteArray(StandardCharsets.UTF_8)
        synchronized(sendLock) {
            LspFraming.write(transport.output, body)
        }
    }

    private fun readLoop() {
        try {
            while (!closed.get()) {
                val body = LspFraming.read(transport.input) ?: break
                val text = String(body, StandardCharsets.UTF_8)
                val parsed =
                    try {
                        Json.parse(text)
                    } catch (error: Exception) {
                        throw LspProtocolException("Malformed LSP JSON message: ${error.message}")
                    }
                val message =
                    parsed.asObjectOrNull()
                        ?: throw LspProtocolException("LSP message must be a JSON object")
                dispatch(message)
            }
            if (!closed.get()) failPending(disconnectError(null))
        } catch (error: LspProtocolException) {
            if (!closed.get()) failPending(ContractError.SerializationError(error.message ?: "Malformed LSP message"))
        } catch (error: EOFException) {
            if (!closed.get()) failPending(disconnectError(error.message))
        } catch (error: Exception) {
            if (!closed.get()) failPending(disconnectError(error.message ?: error::class.simpleName))
        }
    }

    private fun dispatch(message: Map<String, Any?>) {
        val method = message.string("method")
        val id = message["id"]
        if (method != null) {
            if (id != null) {
                handleServerRequest(id, method, message["params"])
            } else {
                handleNotification(method, message["params"])
            }
            return
        }
        val requestId = id?.asRequestId()
        if (requestId == null) throw LspProtocolException("LSP message has neither a method nor a response id")
        val future = pending.remove(requestId) ?: return
        val operation = pendingOperations.remove(requestId)
        val error = message.objectOrNull("error")
        if (error != null) {
            val code = error.number("code")?.toInt()
            val text = error.string("message") ?: "unknown JSON-RPC error"
            future.complete(ResultFactory.failure(mapRpcError(code, text, operation)))
        } else {
            future.complete(ResultFactory.success(message["result"]))
        }
    }

    private fun mapRpcError(
        code: Int?,
        message: String,
        operation: String?,
    ): ContractError {
        val context = operation?.let { " in $it" } ?: ""
        return when (code) {
            -32700 -> {
                ContractError.SerializationError("LSP parse error$context: $message")
            }

            -32600, -32602 -> {
                ContractError.InvalidInput("LSP invalid request$context: $message", operation)
            }

            -32601 -> {
                ContractError.NotFound("lsp-method", operation ?: message)
            }

            -32800 -> {
                ContractError.Cancelled(operation ?: message)
            }

            -32801 -> {
                ContractError.PreconditionFailed("LSP content modified$context: $message")
            }

            -32603 -> {
                ContractError.InternalError("LSP internal error$context: $message")
            }

            else -> {
                ContractError.InternalError(
                    "LSP error${code?.let { " $it" } ?: ""}$context: $message",
                )
            }
        }
    }

    private fun handleServerRequest(
        id: Any?,
        method: String,
        params: Any?,
    ) {
        val result: Any? =
            when (method) {
                "workspace/configuration" -> {
                    val items = params.asObjectOrNull()?.listOrNull("items").orEmpty()
                    List(items.size) { null }
                }

                "workspace/workspaceFolders" -> {
                    options.rootUri?.let { listOf(linkedMapOf("uri" to it, "name" to "root")) }
                }

                "workspace/applyEdit" -> {
                    linkedMapOf("applied" to false)
                }

                else -> {
                    null
                }
            }
        runCatching {
            sendMessage(
                linkedMapOf(
                    "jsonrpc" to "2.0",
                    "id" to id,
                    "result" to result,
                ),
            )
        }
    }

    private fun handleNotification(
        method: String,
        params: Any?,
    ) {
        if (method != "textDocument/publishDiagnostics") return
        val objectParams = params.asObjectOrNull() ?: return
        val uri = objectParams.string("uri") ?: return
        val parsed = objectParams.listOrNull("diagnostics").orEmpty().mapNotNull { parseDiagnosticValue(it) }
        val publishedVersion = objectParams.number("version")?.toLong()
        synchronized(documentLock) {
            val documentVersion = documents[uri]?.version
            if (publishedVersion != null && documentVersion != null && publishedVersion != documentVersion) return
            pushedDiagnostics[uri] = DiagnosticState(parsed, publishedVersion ?: documentVersion)
        }
    }

    private fun failPending(error: ContractError) {
        failure.compareAndSet(null, error)
        val operations = pendingOperations.values.toSortedSet()
        val detailed =
            if (error is ContractError.DependencyUnavailable && operations.isNotEmpty()) {
                error.copy(reason = "${error.reason} while waiting for ${operations.joinToString(", ")}")
            } else {
                error
            }
        val futures = pending.values.toList()
        pending.clear()
        pendingOperations.clear()
        for (future in futures) {
            future.complete(ResultFactory.failure(detailed))
        }
    }

    private fun disconnectError(detail: String?): ContractError =
        ContractError.DependencyUnavailable(
            "lsp",
            buildString {
                append("LSP server disconnected or crashed")
                if (!detail.isNullOrBlank()) append(": $detail")
            },
        )

    private fun connectionError(
        operation: String,
        error: Exception,
    ): ContractError {
        val value =
            disconnectError(error.message ?: error::class.simpleName).let {
                if (it is ContractError.DependencyUnavailable) it.copy(reason = "$operation: ${it.reason}") else it
            }
        failure.compareAndSet(null, value)
        return value
    }

    private fun startupError(message: String): ContractError = ContractError.DependencyUnavailable("lsp", message)

    override fun close() {
        var shutdownFuture: CompletableFuture<ContractResult<Any?>>? = null
        var shutdownId: Long? = null
        synchronized(lifecycleLock) {
            if (!closed.compareAndSet(false, true)) return
            if (initialized.getAndSet(false) && failure.get() == null) {
                val id = nextId.incrementAndGet()
                val future = CompletableFuture<ContractResult<Any?>>()
                pending[id] = future
                pendingOperations[id] = "shutdown"
                shutdownFuture = future
                shutdownId = id
            }
        }
        shutdownFuture?.let { future ->
            runCatching {
                sendMessage(linkedMapOf("jsonrpc" to "2.0", "id" to shutdownId, "method" to "shutdown", "params" to null))
            }
            runCatching { future.get(500, TimeUnit.MILLISECONDS) }
            shutdownId?.let {
                pending.remove(it)
                pendingOperations.remove(it)
            }
            runCatching { sendNotificationInternal("exit", null) }
        }
        failPending(ContractError.DependencyUnavailable("lsp", "LSP session was shut down"))
        runCatching { transport.close() }
        runCatching { reader.join(1000) }
    }

    private fun uriLock(uri: String): Any = uriLocks.computeIfAbsent(uri) { Any() }

    private fun documentSyncUnsupported(): ContractError? =
        when {
            syncKind == SyncKind.NONE -> {
                ContractError.PreconditionFailed("LSP server does not support document synchronization")
            }

            !openClose -> {
                ContractError.PreconditionFailed("LSP server does not support didOpen/didClose notifications")
            }

            else -> {
                null
            }
        }

    private fun clearDiagnostics(uri: String) {
        pushedDiagnostics.remove(uri)
    }

    private fun parseOpenClose(
        capabilities: Map<String, Any?>,
        syncKind: SyncKind,
    ): Boolean {
        val raw = capabilities["textDocumentSync"] ?: return syncKind != SyncKind.NONE
        return when (raw) {
            is Number -> syncKind != SyncKind.NONE
            is Map<*, *> -> raw.asObjectOrNull()?.boolean("openClose") ?: (syncKind != SyncKind.NONE)
            else -> syncKind != SyncKind.NONE
        }
    }

    private fun parseSyncKind(capabilities: Map<String, Any?>): SyncKind {
        val raw = capabilities["textDocumentSync"] ?: return SyncKind.FULL
        val change =
            when (raw) {
                is Number -> raw.toInt()
                is Map<*, *> -> raw.asObjectOrNull()?.int("change")
                else -> null
            }
        return when (change) {
            0 -> SyncKind.NONE
            2 -> SyncKind.INCREMENTAL
            else -> SyncKind.FULL
        }
    }

    private fun clientCapabilities(): Map<String, Any?> =
        linkedMapOf(
            "workspace" to
                linkedMapOf(
                    "workspaceFolders" to true,
                    "configuration" to true,
                    "symbol" to linkedMapOf("dynamicRegistration" to false),
                ),
            "textDocument" to
                linkedMapOf(
                    "synchronization" to linkedMapOf("dynamicRegistration" to false, "didSave" to false),
                    "hover" to linkedMapOf("contentFormat" to listOf("markdown", "plaintext")),
                    "definition" to linkedMapOf("linkSupport" to true),
                    "references" to linkedMapOf("dynamicRegistration" to false),
                    "documentSymbol" to
                        linkedMapOf(
                            "dynamicRegistration" to false,
                            "hierarchicalDocumentSymbolSupport" to true,
                        ),
                    "diagnostic" to linkedMapOf("dynamicRegistration" to false, "relatedDocumentSupport" to false),
                ),
            "general" to linkedMapOf("positionEncodings" to listOf("utf-16")),
        )

    private data class OpenDocument(
        val text: String,
        val version: Long,
        val languageId: String,
    )

    private data class DiagnosticState(
        val diagnostics: List<Diagnostic>,
        val version: Long?,
    )

    private enum class SyncKind {
        NONE,
        FULL,
        INCREMENTAL,
    }
}

internal data class DocumentSnapshot(
    val text: String,
    val version: Long,
    val languageId: String,
)

internal data class LspPosition(
    val line: Int,
    val character: Int,
)

internal fun positionAt(
    text: String,
    offset: Int,
    unit: OffsetUnit = OffsetUnit.UTF8_BYTE,
): LspPosition =
    when (unit) {
        OffsetUnit.UTF8_BYTE -> positionAtUtf8ByteOffset(text, offset)
        OffsetUnit.UTF16_CODE_UNIT -> positionAtUtf16Offset(text, offset)
    }

private fun positionAtUtf8ByteOffset(
    text: String,
    byteOffset: Int,
): LspPosition {
    val bytes = text.toByteArray(StandardCharsets.UTF_8)
    val end = byteOffset.coerceIn(0, bytes.size)
    var line = 0
    var lineStart = 0
    for (index in 0 until end) {
        if (bytes[index] == '\n'.code.toByte()) {
            line++
            lineStart = index + 1
        }
    }
    val character = String(bytes, lineStart, end - lineStart, StandardCharsets.UTF_8).length
    return LspPosition(line, character)
}

private fun positionAtUtf16Offset(
    text: String,
    codeUnitOffset: Int,
): LspPosition {
    val end = codeUnitOffset.coerceIn(0, text.length)
    var line = 0
    var lineStart = 0
    for (index in 0 until end) {
        if (text[index] == '\n') {
            line++
            lineStart = index + 1
        }
    }
    return LspPosition(line, end - lineStart)
}

private fun incrementalChange(
    previous: String,
    next: String,
): Map<String, Any?> {
    val maxPrefix = minOf(previous.length, next.length)
    var prefix = 0
    while (prefix < maxPrefix && previous[prefix] == next[prefix]) prefix++
    val maxSuffix = minOf(previous.length, next.length) - prefix
    var suffix = 0
    while (suffix < maxSuffix && previous[previous.length - 1 - suffix] == next[next.length - 1 - suffix]) suffix++
    val start = positionAtUtf16Offset(previous, prefix)
    val end = positionAtUtf16Offset(previous, previous.length - suffix)
    return linkedMapOf(
        "range" to
            linkedMapOf(
                "start" to linkedMapOf("line" to start.line, "character" to start.character),
                "end" to linkedMapOf("line" to end.line, "character" to end.character),
            ),
        "text" to next.substring(prefix, next.length - suffix),
    )
}

internal fun parseSpan(value: Any?): Span? {
    val range = value.asObjectOrNull() ?: return null
    val start = range.objectOrNull("start") ?: return null
    val end = range.objectOrNull("end") ?: return null
    val startLine = start.int("line") ?: return null
    val startCharacter = start.int("character") ?: return null
    val endLine = end.int("line") ?: return null
    val endCharacter = end.int("character") ?: return null
    val safeStartLine = startLine.coerceAtLeast(0)
    val safeStartCharacter = startCharacter.coerceAtLeast(0)
    val safeEndLine = endLine.coerceAtLeast(safeStartLine)
    val safeEndCharacter =
        if (safeEndLine == safeStartLine) {
            endCharacter.coerceAtLeast(safeStartCharacter)
        } else {
            endCharacter.coerceAtLeast(0)
        }
    return Span(safeStartLine, safeStartCharacter, safeEndLine, safeEndCharacter)
}

internal fun parseDiagnosticValue(value: Any?): Diagnostic? {
    val diagnostic = value.asObjectOrNull() ?: return null
    val span = parseSpan(diagnostic["range"]) ?: return null
    val message = diagnostic.string("message") ?: return null
    val severity =
        when (diagnostic.int("severity")) {
            1 -> DiagnosticSeverity.ERROR
            2 -> DiagnosticSeverity.WARNING
            3 -> DiagnosticSeverity.INFO
            4 -> DiagnosticSeverity.HINT
            else -> DiagnosticSeverity.ERROR
        }
    val code =
        when (val raw = diagnostic["code"]) {
            is String -> raw
            is Number -> raw.toString()
            else -> null
        }
    return Diagnostic(
        span = span,
        message = message,
        severity = severity,
        code = code,
        source = diagnostic.string("source") ?: "lsp",
    )
}

private fun Any?.asRequestId(): Long? =
    when (this) {
        is Number -> toLong()
        is String -> toLongOrNull()
        else -> null
    }
