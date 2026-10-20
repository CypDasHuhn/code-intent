package dev.cint.server

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import dev.cint.core.artifact.Artifact
import dev.cint.core.artifact.FileEntry
import dev.cint.core.frontend.OracleOptions
import dev.cint.core.frontend.SplitHandler
import dev.cint.core.hashing.contentHash
import dev.cint.core.host.HostConfig
import dev.cint.core.host.HostPipeline
import dev.cint.core.model.SourceFile
import dev.cint.core.oracle.MapOracle
import dev.cint.core.oracle.NullOracle
import dev.cint.core.oracle.TypeFact
import dev.cint.core.oracle.TypeOracle
import dev.cint.frontend.Frontends
import dev.cint.frontend.SplitAnalyzer
import dev.cint.rulepacks.BundledRulePackSelection
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

    if (configPath != null) HostConfig.load(configPath, language)

    val packs = HostPipeline(constructedPacks = BundledRulePackSelection::constructed)

    if (socket != null) {
        LiveServer(root, Path.of(socket), useLsp, language, packs).run()
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
            respond(exchange, 200, analyze(request, useLsp, language, packs))
        } catch (error: Exception) {
            respond(exchange, 400, """{"error":"${error.message?.replace("\"", "'")}"}""")
        }
    }
    server.start()
    System.err.println("cint listening on http://$host:$port")
}

private fun analyze(
    request: AnalyzeRequest,
    useLsp: Boolean,
    defaultLanguage: String,
    packs: HostPipeline,
): String {
    val root = Path.of(request.root ?: ".").toAbsolutePath().normalize()
    val config = HostConfig.load(request.config, request.language ?: defaultLanguage)
    val frontend = Frontends.require(config.language)

    val source = SourceFile(request.path, request.text)
    val annotations =
        if (frontend is SplitHandler) {
            SplitAnalyzer.analyze(
                source,
                frontend,
                root,
                null,
                OracleOptions(useLsp, DEFAULT_LSP_TIMEOUT_MS),
                packs::plan,
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
                packs.analyze(source, tree, syntax, oracle, config)
            } finally {
                (oracle as? AutoCloseable)?.close()
            }
        }
    val languages =
        if (frontend is SplitHandler) {
            frontend.regions(source).map { it.language }.toSet().ifEmpty { setOf(config.language) }
        } else {
            setOf(config.language)
        }
    return Artifact.render(
        config.language,
        packs.intents(languages),
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
