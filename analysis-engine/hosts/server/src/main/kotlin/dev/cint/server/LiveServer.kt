package dev.cint.server

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dev.cint.core.config.IntentConfig
import dev.cint.core.facts.oracle.TypeOracle
import dev.cint.frontend.Frontends
import dev.cint.host.HostPipeline
import dev.cint.host.HostSettings
import dev.cint.server.live.CachedAnalysis
import dev.cint.server.live.DocState
import dev.cint.server.live.DocumentKey
import dev.cint.server.live.PublishedCandidate
import dev.cint.server.live.SuggestPass
import dev.cint.server.live.clearDocument
import dev.cint.server.live.configAppend
import dev.cint.server.live.configPayload
import dev.cint.server.live.configReadState
import dev.cint.server.live.configRemove
import dev.cint.server.live.docFrom
import dev.cint.server.live.docsFor
import dev.cint.server.live.publish
import dev.cint.server.live.republishOpenDocs
import dev.cint.server.suggestions.handleCandidateDecision
import dev.cint.server.suggestions.handleSuggest
import dev.cint.server.suggestions.handleSuggestCancel
import dev.cint.server.suggestions.suggestionCapability
import dev.cint.suggest.SuggestionService
import java.io.BufferedReader
import java.io.BufferedWriter
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicLong

internal const val LIVE_LSP_TIMEOUT_MS: Long = 750L
internal const val LIVE_PROTOCOL_VERSION: Int = 3

class LiveServer(
    internal val root: Path,
    private val socketPath: Path,
    internal val useLsp: Boolean,
    internal val defaultLanguage: String,
    internal val packs: HostPipeline,
    internal val settings: HostSettings,
    internal val suggestionSettings: SuggestionSettings = SuggestionSettingsLoader.load(root),
    internal val suggestionService: SuggestionService = suggestionServiceFrom(suggestionSettings),
) {
    private val mapper: ObjectMapper = jacksonObjectMapper()
    internal val configs = ConcurrentHashMap<String, IntentConfig>()
    internal val oracles = ConcurrentHashMap<String, TypeOracle>()
    internal val docs = ConcurrentHashMap<BufferedWriter, ConcurrentHashMap<String, DocState>>()
    private val clients = Executors.newCachedThreadPool()
    internal val suggestionExecutor =
        Executors.newFixedThreadPool(suggestionSettings.workerThreads) { runnable ->
            Thread(runnable, "cint-suggestions").apply { isDaemon = true }
        }
    internal val providerExecutor =
        Executors.newFixedThreadPool(suggestionSettings.workerThreads) { runnable ->
            Thread(runnable, "cint-suggestions-provider").apply { isDaemon = true }
        }
    internal val analyses = ConcurrentHashMap<DocumentKey, CachedAnalysis>()
    internal val publishedCandidates = ConcurrentHashMap<DocumentKey, List<PublishedCandidate>>()
    internal val analysisRevisions = ConcurrentHashMap<DocumentKey, AtomicLong>()
    internal val candidateLocks = ConcurrentHashMap<DocumentKey, Any>()
    internal val anchorLocks = ConcurrentHashMap<String, Any>()
    internal val candidateStateRevisions = ConcurrentHashMap<DocumentKey, Long>()
    internal val inFlightSuggestions = ConcurrentHashMap<DocumentKey, SuggestPass>()
    internal val inFlightFutures = ConcurrentHashMap<DocumentKey, Future<*>>()
    internal val providerFutures = ConcurrentHashMap<DocumentKey, Future<*>>()
    internal val cancelledSuggestions = ConcurrentHashMap<String, Long>()
    internal val sessionSkipped = ConcurrentHashMap<DocumentKey, MutableSet<String>>()
    internal val suggestionStore = SuggestionStore(root)
    internal val suggestionRevision = AtomicLong(0)

    @Volatile
    internal var configRevision: Long = 0

    @Volatile
    internal var analysisRevisionBarrierForTest: (() -> Unit)? = null

    @Volatile
    internal var candidateDecisionBarrierForTest: (() -> Unit)? = null

    @Volatile
    internal var candidatePublishBarrierForTest: (() -> Unit)? = null

    fun run() {
        val parent = socketPath.parent
        if (parent != null) {
            Files.createDirectories(parent)
            runCatching {
                Files.setPosixFilePermissions(parent, PosixFilePermissions.fromString("rwx------"))
            }
        }
        Files.deleteIfExists(socketPath)

        val server = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
        server.bind(UnixDomainSocketAddress.of(socketPath))
        runCatching {
            Files.setPosixFilePermissions(socketPath, PosixFilePermissions.fromString("rw-------"))
        }

        Runtime.getRuntime().addShutdownHook(
            Thread {
                runCatching { server.close() }
                runCatching { shutdownSuggestions() }
                runCatching { oracles.values.forEach { (it as? AutoCloseable)?.close() } }
                runCatching { Files.deleteIfExists(socketPath) }
            },
        )

        System.err.println("cint live server on $socketPath (root: $root)")
        while (server.isOpen) {
            val channel = runCatching { server.accept() }.getOrNull() ?: break
            clients.execute { handleClient(channel) }
        }
        shutdownSuggestions()
    }

    private fun shutdownSuggestions() {
        inFlightSuggestions.values.forEach { it.cancelled.set(true) }
        providerFutures.values.forEach { it.cancel(true) }
        inFlightFutures.values.forEach { it.cancel(true) }
        suggestionExecutor.shutdownNow()
        providerExecutor.shutdownNow()
    }

    private fun handleClient(channel: SocketChannel) {
        channel.use {
            val reader = BufferedReader(Channels.newReader(channel, StandardCharsets.UTF_8))
            val writer = BufferedWriter(Channels.newWriter(channel, StandardCharsets.UTF_8))
            try {
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isBlank()) continue
                    runCatching { handleMessage(line, writer) }
                        .onFailure { error ->
                            error.printStackTrace()
                            sendError(writer, null, error.toString())
                        }
                }
            } finally {
                docs.remove(writer)?.keys?.forEach { uri -> clearDocument(writer, uri) }
            }
        }
    }

    internal fun handleMessage(
        line: String,
        writer: BufferedWriter,
    ) {
        val message = mapper.readTree(line)
        val method = message["method"]?.asText() ?: return
        val params = message["params"]
        val id = message["id"]

        when (method) {
            "initialize" -> {
                val overlay = configReadState()
                respond(
                    writer,
                    id,
                    mapOf(
                        "protocol" to LIVE_PROTOCOL_VERSION,
                        "root" to root.toString(),
                        "languages" to Frontends.languages(),
                        "config" to configPayload(overlay.packs, overlay.error),
                        "suggestions" to suggestionCapability(),
                    ),
                )
            }

            "cint/config/read" -> {
                val overlay = configReadState()
                val error = overlay.error
                if (error != null) {
                    sendError(writer, id, error)
                } else {
                    respond(writer, id, configPayload(overlay.packs))
                }
            }

            "cint/config/append" -> {
                val packId = params?.get("packId")?.asText() ?: return sendError(writer, id, "packId is required")
                val language = params.get("language")?.asText() ?: return sendError(writer, id, "language is required")
                val field = params.get("field")?.asText() ?: return sendError(writer, id, "field is required")
                val value = params.get("value")?.asText() ?: return sendError(writer, id, "value is required")
                if (value.isBlank()) return sendError(writer, id, "value must not be blank")
                runCatching {
                    val overlay = configAppend(packId, language, field, value)
                    republishOpenDocs()
                    respond(writer, id, configPayload(overlay))
                }.onFailure { error ->
                    sendError(writer, id, error.message ?: error.toString())
                }
            }

            "cint/config/remove" -> {
                val packId = params?.get("packId")?.asText() ?: return sendError(writer, id, "packId is required")
                val language = params.get("language")?.asText() ?: return sendError(writer, id, "language is required")
                val field = params.get("field")?.asText() ?: return sendError(writer, id, "field is required")
                val value = params.get("value")?.asText() ?: return sendError(writer, id, "value is required")
                runCatching {
                    val overlay = configRemove(packId, language, field, value)
                    republishOpenDocs()
                    respond(writer, id, configPayload(overlay))
                }.onFailure { error ->
                    sendError(writer, id, error.message ?: error.toString())
                }
            }

            "textDocument/didOpen" -> {
                val doc = docFrom(params) ?: return
                docsFor(writer)[doc.uri] = doc
                publish(writer, doc)
            }

            "textDocument/didChange" -> {
                val doc = docFrom(params) ?: return
                docsFor(writer)[doc.uri] = doc
                publish(writer, doc)
            }

            "textDocument/didClose" -> {
                val uri = params?.get("uri")?.asText() ?: return
                docsFor(writer).remove(uri)
                clearDocument(writer, uri)
            }

            "cint/analyze" -> {
                val uri = params?.get("uri")?.asText() ?: return
                val doc = docsFor(writer)[uri] ?: return
                publish(writer, doc)
                if (id != null) respond(writer, id, true)
            }

            "cint/suggest" -> {
                handleSuggest(writer, params, id)
            }

            "cint/suggest/cancel" -> {
                handleSuggestCancel(writer, params, id)
            }

            "cint/candidates/decide" -> {
                handleCandidateDecision(writer, params, id)
            }

            "shutdown" -> {
                respond(writer, id, true)
            }

            else -> {
                sendError(writer, id, "unknown method: $method")
            }
        }
    }

    internal fun respond(
        writer: BufferedWriter,
        id: JsonNode?,
        result: Any?,
    ) {
        write(
            writer,
            mapOf(
                "jsonrpc" to "2.0",
                "id" to id,
                "result" to result,
            ),
        )
    }

    internal fun sendError(
        writer: BufferedWriter,
        id: JsonNode?,
        message: String,
    ) {
        write(
            writer,
            mapOf(
                "jsonrpc" to "2.0",
                "id" to id,
                "error" to mapOf("code" to -32000, "message" to message),
            ),
        )
    }

    internal fun write(
        writer: BufferedWriter,
        message: Any,
    ) {
        synchronized(writer) {
            writer.write(mapper.writeValueAsString(message))
            writer.newLine()
            writer.flush()
        }
    }
}
