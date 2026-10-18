package com.semanticpainter.server

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.semanticpainter.core.config.IntentConfig
import com.semanticpainter.core.config.loadIntentConfig
import com.semanticpainter.core.engine.AnalysisEngine
import com.semanticpainter.core.frontend.LanguageFrontend
import com.semanticpainter.core.frontend.OracleOptions
import com.semanticpainter.core.frontend.SplitHandler
import com.semanticpainter.core.hashing.contentHash
import com.semanticpainter.core.model.Annotation
import com.semanticpainter.core.model.Role
import com.semanticpainter.core.model.SourceFile
import com.semanticpainter.core.oracle.NullOracle
import com.semanticpainter.core.oracle.TypeOracle
import com.semanticpainter.core.rules.IntentBlocks
import com.semanticpainter.frontend.Frontends
import com.semanticpainter.frontend.SplitAnalyzer
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

private data class DocState(
    val uri: String,
    val text: String,
    val version: Int,
    val language: String,
)

private const val LIVE_LSP_TIMEOUT_MS = 750L

class LiveServer(
    private val root: Path,
    private val socketPath: Path,
    private val configPath: String?,
    private val useLsp: Boolean,
    private val defaultLanguage: String,
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

        System.err.println("semantic-code-painter live server on $socketPath (root: $root)")
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

            "semanticPainter/analyze" -> {
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
                    System.err.println("semantic-code-painter: analysis failed, using heuristics")
                    error.printStackTrace()
                    analyzeHeuristic(frontend, source, config)
                }
            }

        val params =
            mapOf(
                "uri" to doc.uri,
                "version" to doc.version,
                "hash" to contentHash(doc.text),
                "annotations" to analysis.map(::annotationJson),
            )
        write(
            writer,
            mapOf(
                "jsonrpc" to "2.0",
                "method" to "semanticPainter/publishTags",
                "params" to params,
            ),
        )
    }

    private fun configFor(language: String): IntentConfig =
        configs.computeIfAbsent(language) {
            loadIntentConfig(configPath, it)
        }

    private fun oracleFor(frontend: LanguageFrontend): TypeOracle {
        if (!useLsp) return NullOracle
        return oracles.computeIfAbsent(frontend.language) {
            runCatching { frontend.oracle(root, OracleOptions(true, LIVE_LSP_TIMEOUT_MS)) }
                .onFailure { error ->
                    System.err.println("semantic-code-painter: LSP connect failed, using heuristics")
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
                configPath,
                OracleOptions(useLsp, LIVE_LSP_TIMEOUT_MS),
            )
        }
        val tree = frontend.parse(source)
        val syntax = frontend.syntax(tree)
        return AnalysisEngine(IntentBlocks.build(config)).analyze(source, tree, syntax, oracleFor(frontend), config)
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
                configPath,
                OracleOptions(false, LIVE_LSP_TIMEOUT_MS),
            )
        }
        val tree = frontend.parse(source)
        val syntax = frontend.syntax(tree)
        return AnalysisEngine(IntentBlocks.build(config)).analyze(source, tree, syntax, NullOracle, config)
    }

    private fun annotationJson(annotation: Annotation): Map<String, Any?> =
        mapOf(
            "role" to roleName(annotation.role),
            "intents" to annotation.intents,
            "rule" to annotation.rule,
            "evidence" to annotation.evidence,
            "source" to annotation.source,
            "confidence" to annotation.confidence,
            "startLine" to annotation.span.startLine,
            "startCol" to annotation.span.startCol,
            "endLine" to annotation.span.endLine,
            "endCol" to annotation.span.endCol,
            "startByte" to annotation.startByte,
            "endByte" to annotation.endByte,
        )

    private fun roleName(role: Role): String =
        when (role) {
            Role.SOURCE_INTENT -> "source-intent"
            Role.DERIVATIVE_USE -> "derivative-use"
            Role.SCAFFOLD -> "scaffold"
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
