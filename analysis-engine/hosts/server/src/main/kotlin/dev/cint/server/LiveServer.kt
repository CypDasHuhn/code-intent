package dev.cint.server

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.util.RawValue
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dev.cint.api.Annotation
import dev.cint.api.CandidateEmit
import dev.cint.api.ConfigFieldDescriptor
import dev.cint.api.ConfigFieldKind
import dev.cint.api.ConfigFieldScope
import dev.cint.api.RulePackConfigDescriptor
import dev.cint.core.artifact.AnnotationJson
import dev.cint.core.candidate.CandidateIdentity
import dev.cint.core.config.IntentConfig
import dev.cint.core.facts.frontend.LanguageFrontend
import dev.cint.core.facts.frontend.OracleOptions
import dev.cint.core.facts.frontend.SplitHandler
import dev.cint.core.facts.model.SourceFile
import dev.cint.core.facts.oracle.NullOracle
import dev.cint.core.facts.oracle.TypeOracle
import dev.cint.core.runtime.scheduler.AnalysisOutput
import dev.cint.core.support.hashing.contentHash
import dev.cint.core.user.UserAnnotation
import dev.cint.core.user.UserAnnotationsFile
import dev.cint.frontend.Frontends
import dev.cint.frontend.SplitAnalyzer
import dev.cint.host.HostPipeline
import dev.cint.host.HostSettings
import dev.cint.host.PackConfigOverlay
import dev.cint.host.PackConfigOverlayState
import dev.cint.suggest.CandidateDocument
import dev.cint.suggest.SuggestionOptions
import dev.cint.suggest.SuggestionProviderRegistry
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
import java.time.Instant
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

internal const val LIVE_LSP_TIMEOUT_MS: Long = 750L
internal const val LIVE_PROTOCOL_VERSION: Int = 3

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

private data class PublishedCandidate(
    val candidateId: String,
    val anchorId: String,
    val path: String,
    val contentHash: String,
    val version: Int,
    val analysisRevision: Long,
    val candidate: CandidateEmit,
)

private data class DocumentKey(
    val owner: BufferedWriter,
    val uri: String,
)

private data class CachedAnalysis(
    val output: AnalysisOutput,
    val version: Int,
    val textHash: String,
    val configRevision: Long,
    val analysisRevision: Long,
)

private data class SuggestPass(
    val requestId: String?,
    val version: Int,
    val baseStateRevision: Long,
    val cancelled: AtomicBoolean = AtomicBoolean(false),
)

class LiveServer(
    private val root: Path,
    private val socketPath: Path,
    private val useLsp: Boolean,
    private val defaultLanguage: String,
    private val packs: HostPipeline,
    private val settings: HostSettings,
    private val suggestionSettings: SuggestionSettings = SuggestionSettingsLoader.load(root),
    private val suggestionService: SuggestionService = suggestionServiceFrom(suggestionSettings),
) {
    private val mapper: ObjectMapper = jacksonObjectMapper()
    private val configs = ConcurrentHashMap<String, IntentConfig>()
    private val oracles = ConcurrentHashMap<String, TypeOracle>()
    private val docs = ConcurrentHashMap<BufferedWriter, ConcurrentHashMap<String, DocState>>()
    private val clients = Executors.newCachedThreadPool()
    private val suggestionExecutor =
        Executors.newFixedThreadPool(suggestionSettings.workerThreads) { runnable ->
            Thread(runnable, "cint-suggestions").apply { isDaemon = true }
        }
    private val providerExecutor =
        Executors.newFixedThreadPool(suggestionSettings.workerThreads) { runnable ->
            Thread(runnable, "cint-suggestions-provider").apply { isDaemon = true }
        }
    private val analyses = ConcurrentHashMap<DocumentKey, CachedAnalysis>()
    private val publishedCandidates = ConcurrentHashMap<DocumentKey, List<PublishedCandidate>>()
    private val analysisRevisions = ConcurrentHashMap<DocumentKey, AtomicLong>()
    private val candidateLocks = ConcurrentHashMap<DocumentKey, Any>()
    private val anchorLocks = ConcurrentHashMap<String, Any>()
    private val candidateStateRevisions = ConcurrentHashMap<DocumentKey, Long>()
    private val inFlightSuggestions = ConcurrentHashMap<DocumentKey, SuggestPass>()
    private val inFlightFutures = ConcurrentHashMap<DocumentKey, Future<*>>()
    private val providerFutures = ConcurrentHashMap<DocumentKey, Future<*>>()
    private val cancelledSuggestions = ConcurrentHashMap<String, Long>()
    private val sessionSkipped = ConcurrentHashMap<DocumentKey, MutableSet<String>>()
    private val suggestionStore = SuggestionStore(root)
    private val suggestionRevision = AtomicLong(0)

    @Volatile
    private var configRevision: Long = 0

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

    internal fun rewriteDocumentTextForTest(
        writer: BufferedWriter,
        uri: String,
        text: String,
    ) {
        val clientDocs = docs[writer] ?: return
        val doc = clientDocs[uri] ?: return
        clientDocs[uri] = doc.copy(text = text)
    }

    internal fun analyzeForTest(
        uri: String,
        text: String,
        language: String,
    ): List<Annotation> = analyzeOutputForTest(uri, text, language).annotations

    internal fun analyzeOutputForTest(
        uri: String,
        text: String,
        language: String,
    ): AnalysisOutput {
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

    private fun republishUri(uri: String) {
        for ((writer, clientDocs) in docs.entries.toList()) {
            val doc = clientDocs[uri] ?: continue
            runCatching { publish(writer, doc) }
                .onFailure { error ->
                    System.err.println("cint decision republish: ${error.message ?: error.javaClass.name}")
                }
        }
    }

    private fun docsFor(writer: BufferedWriter): ConcurrentHashMap<String, DocState> = docs.computeIfAbsent(writer) { ConcurrentHashMap() }

    private fun clearDocument(
        writer: BufferedWriter,
        uri: String,
    ) {
        val key = DocumentKey(writer, uri)
        inFlightSuggestions.remove(key)?.cancelled?.set(true)
        providerFutures.remove(key)?.cancel(true)
        inFlightFutures.remove(key)?.cancel(true)
        analyses.remove(key)
        publishedCandidates.remove(key)
        analysisRevisions.remove(key)
        candidateLocks.remove(key)
        candidateStateRevisions.remove(key)
        sessionSkipped.remove(key)
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
        val key = DocumentKey(writer, doc.uri)
        // Pin the analysis, its config snapshot and its per-document revision to
        // one lock so revision order always matches analysis order.
        val (output, configRevision, analysisRevision) =
            synchronized(this) {
                val result =
                    runCatching {
                        analyze(frontend, source, config)
                    }.getOrElse { error ->
                        System.err.println("cint: analysis failed, using heuristics")
                        error.printStackTrace()
                        analyzeHeuristic(frontend, source, config)
                    }
                val snapshot = this.configRevision
                analysisRevisionBarrierForTest?.invoke()
                val revision = nextAnalysisRevision(key)
                analyses[key] =
                    CachedAnalysis(
                        output = result,
                        version = doc.version,
                        textHash = contentHash(doc.text),
                        configRevision = snapshot,
                        analysisRevision = revision,
                    )
                Triple(result, snapshot, revision)
            }
        val params =
            mapOf(
                "uri" to doc.uri,
                "version" to doc.version,
                "hash" to contentHash(doc.text),
                "revision" to configRevision,
                "analysisRevision" to analysisRevision,
                "annotations" to output.annotations.map { RawValue(AnnotationJson.render(it)) },
            )
        write(
            writer,
            mapOf(
                "jsonrpc" to "2.0",
                "method" to "cint/publishTags",
                "params" to params,
            ),
        )
        publishCandidates(writer, doc, output.candidates, analysisRevision)
    }

    private fun nextAnalysisRevision(key: DocumentKey): Long = analysisRevisions.computeIfAbsent(key) { AtomicLong(0) }.incrementAndGet()

    private fun suggestionCapability(): Map<String, Any?> {
        val selectedProvider = suggestionSettings.effectiveProvider
        val provider = suggestionService.findProvider(selectedProvider)
        return linkedMapOf(
            "enabled" to suggestionSettings.enabled,
            "trigger" to suggestionSettings.trigger,
            "provider" to (selectedProvider ?: provider?.id),
            "model" to (suggestionSettings.model ?: provider?.model),
            "knownIntents" to knownIntents(),
        )
    }

    private fun knownIntents(): List<String> =
        runCatching {
            packs.intents(Frontends.languages())
        }.getOrDefault(emptyList())

    private fun publishCandidates(
        writer: BufferedWriter,
        doc: DocState,
        candidates: List<CandidateEmit>,
        analysisRevision: Long,
        expectedStateRevision: Long? = null,
        commitSkip: String? = null,
    ): Int? {
        val key = DocumentKey(writer, doc.uri)
        val lock = candidateLocks.computeIfAbsent(key) { Any() }
        synchronized(lock) {
            val currentAnalysis = analyses[key]?.analysisRevision ?: 0L
            val currentPublished = publishedCandidates[key]?.maxOfOrNull { it.analysisRevision } ?: 0L
            val currentState = candidateStateRevisions[key] ?: 0L
            if (
                analysisRevision < currentAnalysis ||
                analysisRevision < currentPublished ||
                (expectedStateRevision != null && expectedStateRevision != currentState)
            ) {
                return null
            }
            if (commitSkip != null) {
                sessionSkipped.computeIfAbsent(key) { ConcurrentHashMap.newKeySet() } += commitSkip
                candidatePublishBarrierForTest?.invoke()
            }
            val path = relativePath(doc.uri)
            val currentSessionSkips = sessionSkipped[key].orEmpty()
            val decisions = suggestionStore.load()
            val rejected = decisions.filter { it.decision == "reject" }.mapTo(linkedSetOf()) { it.candidateId }
            val confirmed = decisions.filter { it.decision == "confirm" }.mapTo(linkedSetOf()) { it.candidateId }
            val userAnnotations =
                if (confirmed.isEmpty()) {
                    emptyList()
                } else {
                    UserAnnotationsFile.loadTolerant(root) { error ->
                        System.err.println("cint user annotations: ${error.message ?: error.javaClass.name}")
                    }
                }
            val resolvedAnchors =
                decisions
                    .filter { it.decision == "confirm" && it.anchorId != null }
                    .filter { decision ->
                        userAnnotations.any { annotation ->
                            annotation.path == decision.path &&
                                annotation.span ==
                                dev.cint.api.Span(
                                    decision.startLine,
                                    decision.startCol,
                                    decision.endLine,
                                    decision.endCol,
                                ) &&
                                annotation.intents.any { it in decision.intents }
                        }
                    }.mapNotNullTo(linkedSetOf()) { it.anchorId }
            val document = CandidateDocument(path, doc.version, doc.text, emptySet())
            val groups = suggestionService.group(document, candidates)
            val publishedById = linkedMapOf<String, PublishedCandidate>()
            val publishedGroups = mutableListOf<Pair<dev.cint.suggest.CandidateGroup, List<PublishedCandidate>>>()
            for (group in groups) {
                val groupResolved =
                    group.anchorId in resolvedAnchors ||
                        group.candidates.any { candidate ->
                            val candidateId = candidateId(path, group.contentHash, candidate)
                            candidateId in confirmed &&
                                userAnnotations.any { annotation ->
                                    annotation.path == path &&
                                        annotation.span == candidate.span &&
                                        annotation.intents.any { it in candidate.intents }
                                }
                        }
                if (groupResolved) continue
                val kept =
                    group.candidates.mapNotNull { candidate ->
                        val candidateId = candidateId(path, group.contentHash, candidate)
                        if (candidateId in rejected || candidateId in currentSessionSkips) {
                            null
                        } else {
                            PublishedCandidate(
                                candidateId = candidateId,
                                anchorId = group.anchorId,
                                path = path,
                                contentHash = group.contentHash,
                                version = doc.version,
                                analysisRevision = analysisRevision,
                                candidate = candidate,
                            ).also { publishedById[candidateId] = it }
                        }
                    }
                if (kept.isNotEmpty()) publishedGroups += group to kept
            }
            val published = publishedById.values.toList()
            publishedCandidates[key] = published
            val revision = suggestionRevision.incrementAndGet()
            candidateStateRevisions[key] = revision
            val params =
                linkedMapOf<String, Any?>(
                    "uri" to doc.uri,
                    "version" to doc.version,
                    "analysisRevision" to analysisRevision,
                    "suggestionRevision" to revision,
                    "groups" to publishedGroups.map { (group, kept) -> candidateGroupPayload(group, kept) },
                    "candidates" to published.map(::candidatePayload),
                )
            write(
                writer,
                mapOf(
                    "jsonrpc" to "2.0",
                    "method" to "cint/publishCandidates",
                    "params" to params,
                ),
            )
            return published.size
        }
    }

    private fun candidateId(
        path: String,
        contentHash: String,
        candidate: CandidateEmit,
    ): String =
        CandidateIdentity.of(
            path,
            contentHash,
            candidate.startByte,
            candidate.endByte,
            candidate.intents,
            candidate.reason.name,
        )

    private fun candidateGroupPayload(
        group: dev.cint.suggest.CandidateGroup,
        kept: List<PublishedCandidate>,
    ): Map<String, Any?> =
        linkedMapOf(
            "anchorId" to group.anchorId,
            "path" to group.path,
            "contentHash" to group.contentHash,
            "startLine" to group.span.startLine,
            "startCol" to group.span.startCol,
            "endLine" to group.span.endLine,
            "endCol" to group.span.endCol,
            "startByte" to group.startByte,
            "endByte" to group.endByte,
            "candidates" to kept.map(::candidatePayload),
        )

    private fun candidatePayload(candidate: PublishedCandidate): Map<String, Any?> {
        val emit = candidate.candidate
        return linkedMapOf<String, Any?>(
            "candidateId" to candidate.candidateId,
            "path" to candidate.path,
            "contentHash" to candidate.contentHash,
            "intents" to emit.intents.toList(),
            "source" to emit.source,
            "confidence" to emit.confidence,
            "startLine" to emit.span.startLine,
            "startCol" to emit.span.startCol,
            "endLine" to emit.span.endLine,
            "endCol" to emit.span.endCol,
            "startByte" to emit.startByte,
            "endByte" to emit.endByte,
            "reason" to emit.reason.name,
            "aiEligible" to emit.aiEligible,
        ).apply {
            emit.rationale?.let { put("rationale", it) }
            emit.promptVersion?.let { put("promptVersion", it) }
            emit.provider?.let { put("provider", it) }
            emit.model?.let { put("model", it) }
        }
    }

    private fun handleSuggest(
        writer: BufferedWriter,
        params: JsonNode?,
        id: JsonNode?,
    ) {
        if (!suggestionSettings.enabled) {
            return respond(
                writer,
                id,
                mapOf("accepted" to false, "disabled" to true, "count" to 0),
            )
        }
        val uri = params?.get("uri")?.asText() ?: return sendError(writer, id, "uri is required")
        val doc = docsFor(writer)[uri] ?: return sendError(writer, id, "document is not open: $uri")
        val version = params.get("version")?.asInt() ?: doc.version
        if (version != doc.version) {
            return respond(
                writer,
                id,
                mapOf("accepted" to false, "stale" to true, "count" to 0),
            )
        }
        val requestId = params.get("requestId")?.asText()
        pruneCancelledSuggestions()
        if (requestId != null && cancelledSuggestions.remove(requestId) != null) {
            return respond(
                writer,
                id,
                mapOf("accepted" to false, "cancelled" to true, "count" to 0),
            )
        }
        val key = DocumentKey(writer, uri)
        val pass =
            SuggestPass(
                requestId = requestId,
                version = version,
                baseStateRevision = candidateStateRevisions[key] ?: 0L,
            )
        val previous = inFlightSuggestions.putIfAbsent(key, pass)
        if (previous != null) {
            return respond(
                writer,
                id,
                mapOf("accepted" to false, "busy" to true, "count" to 0),
            )
        }
        val future =
            suggestionExecutor.submit {
                try {
                    val current = docs[writer]?.get(uri)
                    if (current == null || current.version != doc.version || current.text != doc.text) {
                        respond(writer, id, mapOf("accepted" to false, "stale" to true, "count" to 0))
                        return@submit
                    }
                    val cached = analyses[key]
                    if (cached == null) {
                        respond(writer, id, mapOf("accepted" to false, "stale" to true, "count" to 0))
                        return@submit
                    }
                    val known = knownIntents().toSet()
                    val document =
                        CandidateDocument(
                            path = relativePath(uri),
                            version = current.version,
                            text = current.text,
                            knownIntents = known,
                            neighboringIntents =
                                cached.output.annotations
                                    .flatMap { it.intents }
                                    .toSet(),
                            language = current.language,
                        )
                    val providerFuture =
                        providerExecutor.submit(
                            Callable {
                                suggestionService.enrich(
                                    candidates = cached.output.candidates,
                                    document = document,
                                    options =
                                        SuggestionOptions(
                                            providerId = suggestionSettings.effectiveProvider,
                                            model = suggestionSettings.effectiveModel,
                                            maxCandidates = suggestionSettings.maxCandidates,
                                            promptVersion = suggestionSettings.promptVersion,
                                            excerptRadiusBytes = suggestionSettings.excerptRadiusBytes,
                                            allowGlobs = suggestionSettings.allowGlobs,
                                            redactPatterns = suggestionSettings.redactPatterns,
                                        ),
                                )
                            },
                        )
                    providerFutures[key] = providerFuture
                    val enriched =
                        try {
                            providerFuture.get(suggestionSettings.timeoutMs, TimeUnit.MILLISECONDS)
                        } catch (_: TimeoutException) {
                            providerFuture.cancel(true)
                            respond(
                                writer,
                                id,
                                mapOf("accepted" to false, "cancelled" to true, "timeout" to true, "count" to 0),
                            )
                            return@submit
                        } catch (error: ExecutionException) {
                            throw error.cause ?: error
                        } finally {
                            providerFutures.remove(key, providerFuture)
                        }
                    val latest = analyses[key]
                    val cancelled =
                        pass.cancelled.get() ||
                            docs[writer]?.get(uri) != current ||
                            latest?.analysisRevision != cached.analysisRevision
                    if (cancelled) {
                        respond(writer, id, mapOf("accepted" to false, "cancelled" to true, "count" to 0))
                        return@submit
                    }
                    val count =
                        publishCandidates(
                            writer,
                            current,
                            enriched,
                            cached.analysisRevision,
                            expectedStateRevision = pass.baseStateRevision,
                        )
                    if (count == null) {
                        respond(writer, id, mapOf("accepted" to false, "cancelled" to true, "count" to 0))
                    } else {
                        respond(writer, id, mapOf("accepted" to true, "cancelled" to false, "count" to count))
                    }
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    respond(writer, id, mapOf("accepted" to false, "cancelled" to true, "count" to 0))
                } catch (error: Exception) {
                    if (pass.cancelled.get()) {
                        respond(writer, id, mapOf("accepted" to false, "cancelled" to true, "count" to 0))
                    } else {
                        sendError(writer, id, error.message ?: error.toString())
                    }
                } finally {
                    inFlightSuggestions.remove(key, pass)
                    inFlightFutures.remove(key)
                    if (requestId != null) cancelledSuggestions.remove(requestId)
                }
            }
        inFlightFutures[key] = future
    }

    private fun handleSuggestCancel(
        writer: BufferedWriter,
        params: JsonNode?,
        id: JsonNode?,
    ) {
        val uri = params?.get("uri")?.asText()
        val requestId = params?.get("requestId")?.asText()
        if (requestId != null) {
            cancelledSuggestions[requestId] = System.nanoTime()
            pruneCancelledSuggestions()
        }
        val keys =
            if (uri != null) {
                listOf(DocumentKey(writer, uri))
            } else {
                inFlightSuggestions.keys.filter { it.owner === writer }
            }
        for (key in keys) {
            val pass = inFlightSuggestions[key]
            if (pass != null && (requestId == null || pass.requestId == requestId)) {
                pass.cancelled.set(true)
                providerFutures[key]?.cancel(true)
            }
        }
        respond(writer, id, true)
    }

    private fun pruneCancelledSuggestions() {
        val cutoff = System.nanoTime() - TimeUnit.SECONDS.toNanos(60)
        cancelledSuggestions.entries.removeIf { it.value < cutoff }
    }

    private fun handleCandidateDecision(
        writer: BufferedWriter,
        params: JsonNode?,
        id: JsonNode?,
    ) {
        val uri = params?.get("uri")?.asText() ?: return sendError(writer, id, "uri is required")
        val candidateId = params.get("candidateId")?.asText() ?: return sendError(writer, id, "candidateId is required")
        val decision = params.get("decision")?.asText()?.lowercase() ?: return sendError(writer, id, "decision is required")
        if (decision !in setOf("confirm", "reject", "skip")) {
            return sendError(writer, id, "unknown decision: $decision")
        }
        val doc = docsFor(writer)[uri] ?: return sendError(writer, id, "document is not open: $uri")
        val version = params.get("version")?.asInt() ?: doc.version
        if (version != doc.version) {
            return respond(writer, id, mapOf("accepted" to false, "stale" to true))
        }
        val key = DocumentKey(writer, uri)
        val baseStateRevision = candidateStateRevisions[key] ?: 0L
        val candidate =
            publishedCandidates[key]
                .orEmpty()
                .firstOrNull { it.candidateId == candidateId }
                ?: return respond(writer, id, mapOf("accepted" to false, "missing" to true))
        val currentHash = suggestionService.anchorHash(doc.text, candidate.candidate)
        if (currentHash != candidate.contentHash) {
            return respond(writer, id, mapOf("accepted" to false, "stale" to true))
        }
        when (decision) {
            "skip" -> {
                val current = publishedCandidates[key].orEmpty()
                val analysisRevision = current.maxOfOrNull { it.analysisRevision } ?: nextAnalysisRevision(key)
                candidateDecisionBarrierForTest?.invoke()
                val published =
                    publishCandidates(
                        writer,
                        doc,
                        current.map { it.candidate },
                        analysisRevision,
                        expectedStateRevision = baseStateRevision,
                        commitSkip = candidateId,
                    )
                if (published == null) {
                    return respond(writer, id, mapOf("accepted" to false, "stale" to true))
                }
            }

            "reject" -> {
                suggestionStore.record(
                    SuggestionDecision(
                        candidateId = candidateId,
                        path = candidate.path,
                        contentHash = candidate.contentHash,
                        intents = candidate.candidate.intents,
                        reason = candidate.candidate.reason.name,
                        decision = "reject",
                        createdAt = Instant.now().toString(),
                        anchorId = candidate.anchorId,
                        startLine = candidate.candidate.span.startLine,
                        startCol = candidate.candidate.span.startCol,
                        endLine = candidate.candidate.span.endLine,
                        endCol = candidate.candidate.span.endCol,
                    ),
                )
                republishUri(uri)
            }

            "confirm" -> {
                val anchorLock = anchorLocks.computeIfAbsent(candidate.anchorId) { Any() }
                val confirmed =
                    synchronized(anchorLock) {
                        if (anchorResolvedGlobally(candidate.anchorId)) {
                            false
                        } else {
                            UserAnnotationsFile.add(
                                root,
                                UserAnnotation(
                                    path = relativePath(uri),
                                    span = candidate.candidate.span,
                                    intents = candidate.candidate.intents,
                                    contentHash = candidate.contentHash,
                                    createdAt = Instant.now().toString(),
                                ),
                            )
                            suggestionStore.record(
                                SuggestionDecision(
                                    candidateId = candidateId,
                                    path = candidate.path,
                                    contentHash = candidate.contentHash,
                                    intents = candidate.candidate.intents,
                                    reason = candidate.candidate.reason.name,
                                    decision = "confirm",
                                    createdAt = Instant.now().toString(),
                                    anchorId = candidate.anchorId,
                                    startLine = candidate.candidate.span.startLine,
                                    startCol = candidate.candidate.span.startCol,
                                    endLine = candidate.candidate.span.endLine,
                                    endCol = candidate.candidate.span.endCol,
                                ),
                            )
                            true
                        }
                    }
                if (!confirmed) {
                    return respond(writer, id, mapOf("accepted" to false, "missing" to true))
                }
                republishUri(uri)
            }
        }
        respond(writer, id, mapOf("accepted" to true, "stale" to false, "decision" to decision))
    }

    private fun anchorResolvedGlobally(anchorId: String): Boolean {
        val decisions = suggestionStore.load()
        val confirmations = decisions.filter { it.decision == "confirm" && it.anchorId == anchorId }
        if (confirmations.isEmpty()) return false
        val userAnnotations = UserAnnotationsFile.loadTolerant(root)
        return confirmations.any { decision ->
            userAnnotations.any { annotation ->
                annotation.path == decision.path &&
                    annotation.span ==
                    dev.cint.api.Span(
                        decision.startLine,
                        decision.startCol,
                        decision.endLine,
                        decision.endCol,
                    ) &&
                    annotation.intents.any { it in decision.intents }
            }
        }
    }

    private fun relativePath(uri: String): String {
        val path = runCatching { Path.of(uri) }.getOrNull() ?: return uri
        if (!path.isAbsolute) return uri
        return runCatching { root.relativize(path).toString() }.getOrDefault(uri)
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
    ): AnalysisOutput {
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
    ): AnalysisOutput {
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
