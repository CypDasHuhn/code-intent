package com.semanticpainter.server

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.semanticpainter.core.artifact.Artifact
import com.semanticpainter.core.artifact.FileEntry
import com.semanticpainter.core.config.loadIntentConfig
import com.semanticpainter.core.engine.AnalysisEngine
import com.semanticpainter.core.frontend.OracleOptions
import com.semanticpainter.core.frontend.SplitHandler
import com.semanticpainter.core.hashing.contentHash
import com.semanticpainter.core.model.SourceFile
import com.semanticpainter.core.model.UNTAGGED_INTENT
import com.semanticpainter.core.oracle.MapOracle
import com.semanticpainter.core.oracle.NullOracle
import com.semanticpainter.core.oracle.TypeFact
import com.semanticpainter.core.oracle.TypeOracle
import com.semanticpainter.core.rules.IntentBlocks
import com.semanticpainter.frontend.Frontends
import com.semanticpainter.frontend.SplitAnalyzer
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.file.Path

private const val DEFAULT_LSP_TIMEOUT_MS = 5_000L

data class AnalyzeRequest(
    val path: String = "<buffer>",
    val root: String? = null,
    val text: String,
    val language: String? = null,
    val config: String? = null,
    val types: Map<String, String> = emptyMap(),
)

private val mapper =
    jacksonObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

fun main(argv: Array<String>) {
    var host = "127.0.0.1"
    var port = 8765
    var useLsp = true
    var socket: String? = null
    var root = Path.of(".").toAbsolutePath().normalize()
    var configPath: String? = null
    var language = "kotlin"
    var i = 0
    while (i < argv.size) {
        when (argv[i]) {
            "--host" -> host = argv[++i]
            "--port" -> port = argv[++i].toInt()
            "--no-lsp" -> useLsp = false
            "--socket" -> socket = argv[++i]
            "--root" -> root = Path.of(argv[++i]).toAbsolutePath().normalize()
            "--config" -> configPath = argv[++i]
            "--lang" -> language = argv[++i]
            else -> error("unknown flag: ${argv[i]}")
        }
        i++
    }

    if (socket != null) {
        LiveServer(root, Path.of(socket), configPath, useLsp, language).run()
        return
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
            respond(exchange, 200, analyze(request, useLsp, configPath, language))
        } catch (error: Exception) {
            respond(exchange, 400, """{"error":"${error.message?.replace("\"", "'")}"}""")
        }
    }
    server.start()
    System.err.println("semantic-code-painter listening on http://$host:$port")
}

private fun analyze(
    request: AnalyzeRequest,
    useLsp: Boolean,
    defaultConfig: String?,
    defaultLanguage: String,
): String {
    val root = Path.of(request.root ?: ".").toAbsolutePath().normalize()
    val config = loadIntentConfig(request.config ?: defaultConfig, request.language ?: defaultLanguage)
    val frontend = Frontends.require(config.language)

    val source = SourceFile(request.path, request.text)
    val annotations =
        if (frontend is SplitHandler) {
            SplitAnalyzer.analyze(
                source,
                frontend,
                root,
                request.config ?: defaultConfig,
                OracleOptions(useLsp, DEFAULT_LSP_TIMEOUT_MS),
            )
        } else {
            val tree = frontend.parse(source)
            val syntax = frontend.syntax(tree)
            val oracle: TypeOracle =
                when {
                    request.types.isNotEmpty() -> {
                        MapOracle(
                            types = request.types.mapKeys { it.key.toInt() }.mapValues { TypeFact(it.value) },
                        )
                    }

                    useLsp -> {
                        runCatching {
                            frontend.oracle(root, OracleOptions(true, DEFAULT_LSP_TIMEOUT_MS))
                        }.getOrElse { NullOracle }
                    }

                    else -> {
                        NullOracle
                    }
                }
            try {
                AnalysisEngine(IntentBlocks.build(config)).analyze(source, tree, syntax, oracle, config)
            } finally {
                (oracle as? AutoCloseable)?.close()
            }
        }
    return Artifact.render(
        config.language,
        config.intents.keys.toList() + UNTAGGED_INTENT,
        listOf(FileEntry(request.path, contentHash(request.text), annotations)),
    )
}

private fun respond(
    exchange: HttpExchange,
    status: Int,
    body: String,
) {
    val bytes = body.toByteArray(Charsets.UTF_8)
    exchange.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
    exchange.sendResponseHeaders(status, bytes.size.toLong())
    exchange.responseBody.use { it.write(bytes) }
}
