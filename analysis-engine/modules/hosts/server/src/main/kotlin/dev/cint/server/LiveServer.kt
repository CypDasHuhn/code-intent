package dev.cint.server

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.util.RawValue
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dev.cint.api.Annotation
import dev.cint.core.artifact.AnnotationJson
import dev.cint.core.config.IntentConfig
import dev.cint.core.facts.frontend.LanguageFrontend
import dev.cint.core.facts.frontend.OracleOptions
import dev.cint.core.facts.frontend.SplitHandler
import dev.cint.core.facts.model.SourceFile
import dev.cint.core.facts.oracle.NullOracle
import dev.cint.core.facts.oracle.TypeOracle
import dev.cint.core.support.hashing.contentHash
import dev.cint.frontend.Frontends
import dev.cint.frontend.SplitAnalyzer
import dev.cint.host.HostPipeline
import dev.cint.host.HostSettings
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

internal const val LIVE_LSP_TIMEOUT_MS: Long = 750L

private data class DocState(
    val uri: String,
    val text: String,
    val version: Int,
    val language: String,
)

class LiveServer(
    private val root: Path,
    private val socketPath: Path,
    private val useLsp: Boolean,
    private val defaultLanguage: String,
    private val packs: HostPipeline,
    private val settings: HostSettings,
) {
    private val mapper: ObjectMapper = jacksonObjectMapper()
    private val configs = ConcurrentHashMap<String, IntentConfig>()
    private val oracles = ConcurrentHashMap<String, TypeOracle>()
    private val docs = ConcurrentHashMap<String, DocState>()
    private val clients = Executors.newCachedThreadPool()

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
                runCatching { oracles.values.forEach { (it as? AutoCloseable)?.close() } }
                runCatching { Files.deleteIfExists(socketPath) }
            },
        )

        System.err.println("cint live server on $socketPath (root: $root)")
        while (server.isOpen) {
            val channel = runCatching { server.accept() }.getOrNull() ?: break
            clients.execute { handleClient(channel) }
        }
    }

    private fun handleClient(channel: SocketChannel) {
        channel.use {
            val reader = BufferedReader(Channels.newReader(channel, StandardCharsets.UTF_8))
            val writer = BufferedWriter(Channels.newWriter(channel, StandardCharsets.UTF_8))
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isBlank()) continue
                runCatching { handleMessage(line, writer) }
                    .onFailure { error ->
                        error.printStackTrace()
                        sendError(writer, null, error.toString())
                    }
            }
        }
    }

    private fun handleMessage(
        line: String,
        writer: BufferedWriter,
    ) {
        val message = mapper.readTree(line)
        val method = message["method"]?.asText() ?: return
        val params = message["params"]
        val id = message["id"]

        when (method) {
            "initialize" -> {
                respond(
                    writer,
                    id,
                    mapOf(
                        "protocol" to 1,
                        "root" to root.toString(),
                        "languages" to Frontends.languages(),
                    ),
                )
            }

            "textDocument/didOpen" -> {
                val doc = docFrom(params) ?: return
                docs[doc.uri] = doc
                publish(writer, doc)
            }

            "textDocument/didChange" -> {
                val doc = docFrom(params) ?: return
                docs[doc.uri] = doc
                publish(writer, doc)
            }

            "textDocument/didClose" -> {
                val uri = params?.get("uri")?.asText() ?: return
                docs.remove(uri)
            }

            "cint/analyze" -> {
                val uri = params?.get("uri")?.asText() ?: return
                val doc = docs[uri] ?: return
                publish(writer, doc)
                if (id != null) respond(writer, id, true)
            }

            "shutdown" -> {
                respond(writer, id, true)
            }

            else -> {
                sendError(writer, id, "unknown method: $method")
            }
        }
    }

    private fun docFrom(params: JsonNode?): DocState? {
        val uri = params?.get("uri")?.asText() ?: return null
        val text = params.get("text")?.asText() ?: return null
        val version = params.get("version")?.asInt() ?: 0
        val language = params.get("languageId")?.asText() ?: defaultLanguage
        return DocState(uri, text, version, language)
    }

    private fun publish(
        writer: BufferedWriter,
        doc: DocState,
    ) {
        val frontend =
            Frontends.forLanguage(doc.language)
                ?: run {
                    sendError(writer, null, "unsupported language: ${doc.language}")
                    return
                }
        val config = configFor(frontend.language)
        val source = SourceFile(doc.uri, doc.text)
        val analysis =
            synchronized(this) {
                runCatching {
                    analyze(frontend, source, config)
                }.getOrElse { error ->
                    System.err.println("cint: analysis failed, using heuristics")
                    error.printStackTrace()
                    analyzeHeuristic(frontend, source, config)
                }
            }

        val params =
            mapOf(
                "uri" to doc.uri,
                "version" to doc.version,
                "hash" to contentHash(doc.text),
                "annotations" to analysis.map { RawValue(AnnotationJson.render(it)) },
            )
        write(
            writer,
            mapOf(
                "jsonrpc" to "2.0",
                "method" to "cint/publishTags",
                "params" to params,
            ),
        )
    }

    private fun configFor(language: String): IntentConfig =
        configs.computeIfAbsent(language) {
            settings.intentConfig(it)
        }

    internal fun oracleOptions(useLsp: Boolean = this.useLsp): OracleOptions = settings.oracleOptions(useLsp, LIVE_LSP_TIMEOUT_MS)

    private fun oracleFor(frontend: LanguageFrontend): TypeOracle {
        val options = oracleOptions()
        if (!options.enabled) return NullOracle
        return oracles.computeIfAbsent(frontend.language) {
            runCatching { frontend.oracle(root, options) }
                .onFailure { error ->
                    System.err.println("cint: LSP connect failed, using heuristics")
                    error.printStackTrace()
                }.getOrNull()
                ?: NullOracle
        }
    }

    private fun analyze(
        frontend: LanguageFrontend,
        source: SourceFile,
        config: IntentConfig,
    ): List<Annotation> {
        if (frontend is SplitHandler) {
            return SplitAnalyzer.analyze(
                source,
                frontend,
                root,
                oracleOptions(),
                packs::plan,
            ) { language -> settings.intentConfig(language) }
        }
        val tree = frontend.parse(source)
        val syntax = frontend.syntax(tree)
        return packs.analyze(source, tree, syntax, oracleFor(frontend), config)
    }

    private fun analyzeHeuristic(
        frontend: LanguageFrontend,
        source: SourceFile,
        config: IntentConfig,
    ): List<Annotation> {
        if (frontend is SplitHandler) {
            return SplitAnalyzer.analyze(
                source,
                frontend,
                root,
                oracleOptions(false),
                packs::plan,
            ) { language -> settings.intentConfig(language) }
        }
        val tree = frontend.parse(source)
        val syntax = frontend.syntax(tree)
        return packs.analyze(source, tree, syntax, NullOracle, config)
    }

    private fun respond(
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

    private fun sendError(
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

    private fun write(
        writer: BufferedWriter,
        message: Any,
    ) {
        writer.write(mapper.writeValueAsString(message))
        writer.newLine()
        writer.flush()
    }
}
