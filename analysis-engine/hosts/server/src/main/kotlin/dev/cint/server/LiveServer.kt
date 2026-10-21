package dev.cint.server

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.util.RawValue
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dev.cint.api.Annotation
import dev.cint.api.ConfigFieldDescriptor
import dev.cint.api.ConfigFieldKind
import dev.cint.api.ConfigFieldScope
import dev.cint.api.RulePackConfigDescriptor
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
import dev.cint.host.PackConfigOverlay
import dev.cint.host.PackConfigOverlayState
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
internal const val LIVE_PROTOCOL_VERSION: Int = 2

private data class ResolvedConfigField(
    val packId: String,
    val language: String,
    val field: ConfigFieldDescriptor,
)

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
    private val docs = ConcurrentHashMap<BufferedWriter, ConcurrentHashMap<String, DocState>>()
    private val clients = Executors.newCachedThreadPool()

    @Volatile
    private var configRevision: Long = 0

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
                docs.remove(writer)
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
            }

            "cint/analyze" -> {
                val uri = params?.get("uri")?.asText() ?: return
                val doc = docsFor(writer)[uri] ?: return
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

    internal fun configDescriptors(): List<RulePackConfigDescriptor> = packs.configDescriptors()

    internal fun configReadState(): PackConfigOverlayState = PackConfigOverlay.read(root)

    internal fun configRead(): Map<String, Map<String, Any?>> {
        val overlay = configReadState()
        if (overlay.error != null) throw IllegalStateException(overlay.error)
        return overlay.packs
    }

    internal fun configAppend(
        packId: String,
        language: String,
        field: String,
        value: String,
    ): Map<String, Map<String, Any?>> =
        synchronized(this) {
            val resolved = resolveConfigField(packId, language, field)
            applyConfigMutation(resolved.packId) {
                PackConfigOverlay.append(root, resolved.packId, resolved.language, resolved.field, value)
            }
        }

    internal fun configRemove(
        packId: String,
        language: String,
        field: String,
        value: String,
    ): Map<String, Map<String, Any?>> =
        synchronized(this) {
            val resolved = resolveConfigField(packId, language, field)
            applyConfigMutation(resolved.packId) {
                PackConfigOverlay.remove(root, resolved.packId, resolved.language, resolved.field, value)
            }
        }

    private fun applyConfigMutation(
        packId: String,
        mutate: () -> Map<String, Map<String, Any?>>,
    ): Map<String, Map<String, Any?>> {
        val previous = PackConfigOverlay.read(root)
        val previousError = previous.error
        if (previousError != null) {
            throw IllegalStateException("refusing to modify invalid config overlay: $previousError")
        }
        val overlay = mutate()
        packs.reload()
        // Any target failure means the candidate overlay was not applied, even
        // when the same failure was already present before this mutation.
        val failure = packs.failures.firstOrNull { packId in it.packIds }
        val targetMissing = packs.effectivePacks.none { it.id == packId }
        if (failure != null || targetMissing) {
            PackConfigOverlay.restore(root, previous)
            packs.reload()
            throw IllegalStateException(
                failure?.message ?: "rulepack '$packId' failed to reload after config mutation",
            )
        }
        configRevision += 1
        return overlay
    }

    internal fun analyzeForTest(
        uri: String,
        text: String,
        language: String,
    ): List<Annotation> {
        val frontend = Frontends.forLanguage(language) ?: error("unsupported language: $language")
        return analyze(frontend, SourceFile(uri, text), settings.intentConfig(frontend.language))
    }

    private fun resolveConfigField(
        packId: String,
        language: String,
        field: String,
    ): ResolvedConfigField {
        val descriptor =
            packs.configDescriptors().firstOrNull { it.packId == packId }
                ?: throw IllegalArgumentException("unknown rulepack: $packId")
        val canonicalLanguage =
            descriptor.languages.firstOrNull { it.equals(language, ignoreCase = true) }
                ?: throw IllegalArgumentException("rulepack '$packId' does not support language '$language'")
        val configField =
            descriptor.fields.firstOrNull { it.key == field }
                ?: throw IllegalArgumentException("rulepack '$packId' has no config field '$field'")
        if (configField.kind != ConfigFieldKind.STRINGS && configField.kind != ConfigFieldKind.STRING_LIST) {
            throw IllegalArgumentException("rulepack '$packId' field '$field' is not a string list field")
        }
        if (
            configField.scope == ConfigFieldScope.LANGUAGE &&
            configField.languages.none { it.equals(canonicalLanguage, ignoreCase = true) }
        ) {
            throw IllegalArgumentException("rulepack '$packId' field '$field' does not apply to '$canonicalLanguage'")
        }
        return ResolvedConfigField(packId, canonicalLanguage, configField)
    }

    private fun configPayload(
        overlay: Map<String, Map<String, Any?>> = configRead(),
        overlayError: String? = null,
    ): Map<String, Any?> {
        val result =
            linkedMapOf<String, Any?>(
                "packs" to configDescriptors(),
                "overlay" to overlay,
            )
        if (overlayError != null) result["overlayError"] = overlayError
        return result
    }

    private fun republishOpenDocs() {
        for ((writer, clientDocs) in docs.entries.toList()) {
            for (doc in clientDocs.values.toList()) {
                runCatching { publish(writer, doc) }
                    .onFailure { error ->
                        System.err.println("cint config republish: ${error.message ?: error.javaClass.name}")
                    }
            }
        }
    }

    private fun docsFor(writer: BufferedWriter): ConcurrentHashMap<String, DocState> = docs.computeIfAbsent(writer) { ConcurrentHashMap() }

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
        // Pin the analysis to the config revision it actually observed, then
        // release the global lock before any socket write. A later mutation may
        // increment the revision first; the client ignores publishes whose
        // revision is lower than the last accepted revision.
        val (analysis, revision) =
            synchronized(this) {
                val result =
                    runCatching {
                        analyze(frontend, source, config)
                    }.getOrElse { error ->
                        System.err.println("cint: analysis failed, using heuristics")
                        error.printStackTrace()
                        analyzeHeuristic(frontend, source, config)
                    }
                result to configRevision
            }
        val params =
            mapOf(
                "uri" to doc.uri,
                "version" to doc.version,
                "hash" to contentHash(doc.text),
                "revision" to revision,
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
                packs::userContributionsFor,
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
                packs::userContributionsFor,
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
        synchronized(writer) {
            writer.write(mapper.writeValueAsString(message))
            writer.newLine()
            writer.flush()
        }
    }
}
