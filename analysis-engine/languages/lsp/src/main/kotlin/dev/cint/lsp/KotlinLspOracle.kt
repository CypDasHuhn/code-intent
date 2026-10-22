package dev.cint.lsp

import dev.cint.core.facts.model.SourceFile
import dev.cint.core.facts.oracle.OracleMetrics
import dev.cint.core.facts.oracle.OracleStats
import dev.cint.core.facts.oracle.TypeFact
import dev.cint.core.facts.oracle.TypeOracle
import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.jsonrpc.Launcher
import org.eclipse.lsp4j.services.LanguageClient
import org.eclipse.lsp4j.services.LanguageServer
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicLong

data class LspServerSpec(
    val languageId: String,
    val executable: String,
    val arguments: List<String>,
    val cacheName: String,
    val hoverFence: String,
    val portBase: Int = 13000,
    val portRange: Int = 20000,
    val typePattern: Regex = DEFAULT_TYPE_PATTERN,
    val declarationPattern: Regex = DEFAULT_DECLARATION_PATTERN,
) {
    companion object {
        val DEFAULT_TYPE_PATTERN = Regex(""":\s*([A-Za-z_][A-Za-z0-9_.$]*)""")
        val DEFAULT_DECLARATION_PATTERN = Regex("""(?:class|interface|object|typealias)\s+([A-Za-z_][A-Za-z0-9_.$]*)""")
    }
}

val KotlinLspSpec =
    LspServerSpec(
        languageId = "kotlin",
        executable = "kotlin-lsp",
        arguments =
            listOf(
                "--socket",
                "127.0.0.1:{port}",
                "--multi-client",
                "--data-sharing",
                "none",
                "--log-level",
                "ERROR",
            ),
        cacheName = "kotlin-lsp",
        hoverFence = "```kotlin",
    )

class LspOracle internal constructor(
    private val root: Path,
    private val spec: LspServerSpec,
    private val socket: Socket?,
    private val executor: java.util.concurrent.ExecutorService?,
    private val server: LanguageServer,
    private val typeTimeoutMs: Long,
    negativeTtlMs: Long = NEGATIVE_TTL_MS,
    private val nanoTime: () -> Long = System::nanoTime,
) : TypeOracle,
    OracleMetrics,
    AutoCloseable {
    init {
        require(negativeTtlMs > 0) { "negativeTtlMs must be positive" }
    }

    private val opened = mutableMapOf<String, String>()
    private val versions = mutableMapOf<String, Long>()
    private val cache = DocumentTypeCache(CACHE_ENTRIES)
    private val negativeTtlNanos = TimeUnit.MILLISECONDS.toNanos(negativeTtlMs)
    private val hoverCalls = AtomicLong()
    private val cacheHits = AtomicLong()
    private val timeouts = AtomicLong()
    private val failures = AtomicLong()

    @Synchronized
    override fun typeAt(
        file: SourceFile,
        byteOffset: Int,
    ): TypeFact? {
        val uri = uriFor(file)
        val version = open(uri, file.text)
        val key = DocumentTypeKey(uri, version, byteOffset)
        val cached = cache.lookup(key)
        if (cached.present) {
            val entry = cached.entry
            if (entry != null) {
                val fresh = entry.fact != null || nanoTime() - entry.cachedAtNanos <= negativeTtlNanos
                if (fresh) {
                    cacheHits.incrementAndGet()
                    return entry.fact
                }
                cache.remove(key)
            }
        }
        val position = positionAt(file.text, byteOffset)
        hoverCalls.incrementAndGet()
        return try {
            val hover =
                server.textDocumentService
                    .hover(HoverParams(TextDocumentIdentifier(uri), position))
                    .get(typeTimeoutMs, TimeUnit.MILLISECONDS)
            val type = hoverText(hover)?.let { extractType(it, spec) }?.let { TypeFact(it, "lsp", 0.95) }
            cache.put(key, DocumentTypeEntry(type, nanoTime()))
            type
        } catch (error: Exception) {
            failures.incrementAndGet()
            if (isTimeout(error)) timeouts.incrementAndGet()
            throw error
        }
    }

    override fun oracleStats(): OracleStats =
        OracleStats(
            hoverCalls = hoverCalls.get(),
            delegateHits = cacheHits.get(),
            timeouts = timeouts.get(),
            failures = failures.get(),
        )

    private fun isTimeout(error: Throwable): Boolean {
        var current: Throwable? = error
        while (current != null) {
            if (current is TimeoutException) return true
            current = current.cause
        }
        return false
    }

    private fun uriFor(file: SourceFile): String {
        val path = Path.of(file.path)
        return (if (path.isAbsolute) path else root.resolve(path)).toUri().toString()
    }

    @Synchronized
    private fun open(
        uri: String,
        text: String,
    ): Long {
        if (opened[uri] == text) return versions.getValue(uri)
        if (opened.containsKey(uri)) {
            server.textDocumentService.didClose(
                org.eclipse.lsp4j.DidCloseTextDocumentParams(TextDocumentIdentifier(uri)),
            )
        }
        val version = (versions[uri] ?: 0L) + 1L
        versions[uri] = version
        cache.invalidate(uri)
        server.textDocumentService.didOpen(
            org.eclipse.lsp4j.DidOpenTextDocumentParams(
                TextDocumentItem(uri, spec.languageId, version.toInt(), text),
            ),
        )
        opened[uri] = text
        return version
    }

    @Synchronized
    override fun close() {
        for (uri in opened.keys) {
            runCatching {
                server.textDocumentService.didClose(
                    org.eclipse.lsp4j.DidCloseTextDocumentParams(TextDocumentIdentifier(uri)),
                )
            }
        }
        opened.clear()
        versions.clear()
        socket?.close()
        executor?.shutdownNow()
    }

    companion object {
        private const val CACHE_ENTRIES = 1_024
        private const val NEGATIVE_TTL_MS = 500L

        fun connect(
            root: Path,
            spec: LspServerSpec,
            typeTimeoutMs: Long,
        ): LspOracle {
            java.util.logging.LogManager
                .getLogManager()
                .reset()
            val absoluteRoot = root.toAbsolutePath().normalize()
            val port = portFor(absoluteRoot, spec)
            if (!portOpen(port)) {
                val command =
                    listOf(spec.executable) +
                        spec.arguments.map { it.replace("{port}", port.toString()) }
                ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .also {
                        it.environment()["XDG_CACHE_HOME"] = cacheRoot(spec).toString()
                    }.start()
                waitForPort(port, spec)
            }

            val socket = Socket()
            socket.connect(InetSocketAddress("127.0.0.1", port), 5_000)
            socket.soTimeout = 30_000

            val client = Client()
            val executor =
                java.util.concurrent.Executors.newCachedThreadPool { runnable ->
                    Thread(runnable, "lsp4j").apply { isDaemon = true }
                }
            val launcher: Launcher<LanguageServer> =
                Launcher
                    .Builder<LanguageServer>()
                    .setLocalService(client)
                    .setRemoteInterface(LanguageServer::class.java)
                    .setInput(socket.getInputStream())
                    .setOutput(socket.getOutputStream())
                    .setExecutorService(executor)
                    .create()
            val server = launcher.remoteProxy
            launcher.startListening()

            val params =
                InitializeParams().apply {
                    processId = ProcessHandle.current().pid().toInt()
                    rootUri = absoluteRoot.toUri().toString()
                    capabilities = ClientCapabilities()
                    workspaceFolders =
                        listOf(
                            WorkspaceFolder(absoluteRoot.toUri().toString(), absoluteRoot.fileName.toString()),
                        )
                }
            server.initialize(params).get(30, TimeUnit.SECONDS)
            server.initialized(org.eclipse.lsp4j.InitializedParams())

            return LspOracle(absoluteRoot, spec, socket, executor, server, typeTimeoutMs)
        }

        fun isRunning(
            root: Path,
            spec: LspServerSpec,
        ): Boolean = portOpen(portFor(root.toAbsolutePath().normalize(), spec))

        private fun cacheRoot(spec: LspServerSpec): Path =
            Path
                .of(System.getProperty("user.cache.home") ?: "${System.getProperty("user.home")}/.cache")
                .resolve("cint")
                .resolve(spec.cacheName)

        private fun portFor(
            root: Path,
            spec: LspServerSpec,
        ): Int {
            val digest =
                MessageDigest
                    .getInstance("SHA-256")
                    .digest(root.toString().toByteArray(Charsets.UTF_8))
            val hex = digest.joinToString("") { "%02x".format(it) }.substring(0, 8)
            return spec.portBase + (hex.toLong(16) % spec.portRange).toInt()
        }

        private fun portOpen(port: Int): Boolean =
            runCatching {
                Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 200) }
            }.isSuccess

        private fun waitForPort(
            port: Int,
            spec: LspServerSpec,
        ) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120)
            while (System.nanoTime() < deadline) {
                if (portOpen(port)) return
                Thread.sleep(250)
            }
            error("${spec.executable} did not start on port $port")
        }
    }
}

object KotlinLspOracle {
    private const val DEFAULT_TYPE_TIMEOUT_MS = 5_000L

    fun connect(
        root: Path,
        typeTimeoutMs: Long = DEFAULT_TYPE_TIMEOUT_MS,
    ): LspOracle = LspOracle.connect(root, KotlinLspSpec, typeTimeoutMs)

    fun isRunning(root: Path): Boolean = LspOracle.isRunning(root, KotlinLspSpec)
}

private class Client : LanguageClient {
    override fun telemetryEvent(obj: Any?) = Unit

    override fun publishDiagnostics(diagnostics: PublishDiagnosticsParams) = Unit

    override fun showMessage(messageParams: MessageParams) = Unit

    override fun showMessageRequest(requestParams: ShowMessageRequestParams): CompletableFuture<MessageActionItem> =
        CompletableFuture.completedFuture(null)

    override fun logMessage(message: MessageParams) = Unit

    override fun workspaceFolders(): CompletableFuture<List<WorkspaceFolder>> = CompletableFuture.completedFuture(emptyList())

    override fun configuration(params: ConfigurationParams): CompletableFuture<List<Any>> = CompletableFuture.completedFuture(emptyList())
}

private fun hoverText(hover: Hover?): String? {
    val contents = hover?.contents ?: return null
    return when {
        contents.isLeft -> {
            contents.left.joinToString("\n") { item ->
                if (item.isLeft) item.left else item.right.value
            }
        }

        contents.isRight -> {
            contents.right.value
        }

        else -> {
            null
        }
    }
}

private fun extractType(
    hover: String,
    spec: LspServerSpec,
): String? {
    val text = hover.replace(spec.hoverFence, "").replace("```", "").trim()
    spec.typePattern.find(text)?.let { return it.groupValues[1] }
    return spec.declarationPattern
        .find(text)
        ?.groupValues
        ?.get(1)
}

private fun positionAt(
    text: String,
    byteOffset: Int,
): org.eclipse.lsp4j.Position {
    val bytes = text.toByteArray(Charsets.UTF_8)
    val end = byteOffset.coerceIn(0, bytes.size)
    var line = 0
    var lineStart = 0
    for (i in 0 until end) {
        if (bytes[i] == '\n'.code.toByte()) {
            line++
            lineStart = i + 1
        }
    }
    val character = String(bytes, lineStart, end - lineStart, Charsets.UTF_8).length
    return org.eclipse.lsp4j.Position(line, character)
}
