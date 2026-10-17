package com.semanticpainter.server

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.semanticpainter.core.artifact.Artifact
import com.semanticpainter.core.artifact.FileEntry
import com.semanticpainter.core.config.loadFeatureConfig
import com.semanticpainter.core.engine.AnalysisEngine
import com.semanticpainter.core.hashing.contentHash
import com.semanticpainter.core.model.SourceFile
import com.semanticpainter.core.oracle.MapOracle
import com.semanticpainter.core.oracle.NullOracle
import com.semanticpainter.core.oracle.TypeFact
import com.semanticpainter.core.oracle.TypeOracle
import com.semanticpainter.lsp.KotlinLspOracle
import com.semanticpainter.treesitter.KotlinParser
import com.semanticpainter.treesitter.LoggingBlocks
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.file.Path

data class AnalyzeRequest(
    val path: String = "<buffer>",
    val root: String? = null,
    val text: String,
    val language: String = "kotlin",
    val feature: String = "logging",
    val config: String? = null,
    val types: Map<String, String> = emptyMap(),
)

private val mapper = jacksonObjectMapper()

fun main(argv: Array<String>) {
    var host = "127.0.0.1"
    var port = 8765
    var useLsp = true
    var i = 0
    while (i < argv.size) {
        when (argv[i]) {
            "--host" -> host = argv[++i]
            "--port" -> port = argv[++i].toInt()
            "--no-lsp" -> useLsp = false
            else -> error("unknown flag: ${argv[i]}")
        }
        i++
    }

    val server = HttpServer.create(InetSocketAddress(host, port), 0)
    server.createContext("/health") { exchange ->
        respond(exchange, 200, """{"status":"ok"}""")
    }
    server.createContext("/analyze") { exchange ->
        if (exchange.requestMethod != "POST") {
            respond(exchange, 405, """{"error":"POST required"}""")
            return@createContext
        }
        try {
            val request = exchange.requestBody.use { mapper.readValue<AnalyzeRequest>(it) }
            respond(exchange, 200, analyze(request, useLsp))
        } catch (error: Exception) {
            respond(exchange, 400, """{"error":"${error.message?.replace("\"", "'")}"}""")
        }
    }
    server.start()
    System.err.println("semantic-code-painter listening on http://$host:$port")
}

private fun analyze(request: AnalyzeRequest, useLsp: Boolean): String {
    require(request.language == "kotlin") { "only kotlin is supported by the tree-sitter frontend" }
    val root = Path.of(request.root ?: ".").toAbsolutePath().normalize()
    val config = loadFeatureConfig(request.config, request.feature, request.language)
    val source = SourceFile(request.path, request.text)
    val tree = KotlinParser().parse(source)
    val oracle: TypeOracle = when {
        request.types.isNotEmpty() -> MapOracle(
            types = request.types.mapKeys { it.key.toInt() }.mapValues { TypeFact(it.value) },
        )
        useLsp -> runCatching { KotlinLspOracle.connect(root) }.getOrElse { NullOracle }
        else -> NullOracle
    }
    try {
        val spans = AnalysisEngine(LoggingBlocks.select(config.blocks)).analyze(source, tree, oracle, config)
        return Artifact.render(
            config.feature,
            config.tag,
            config.language,
            listOf(FileEntry(request.path, contentHash(request.text), spans)),
        )
    } finally {
        (oracle as? AutoCloseable)?.close()
    }
}

private fun respond(exchange: HttpExchange, status: Int, body: String) {
    val bytes = body.toByteArray(Charsets.UTF_8)
    exchange.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
    exchange.sendResponseHeaders(status, bytes.size.toLong())
    exchange.responseBody.use { it.write(bytes) }
}
