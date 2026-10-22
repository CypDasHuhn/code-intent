package dev.cint.server.live

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.util.RawValue
import dev.cint.api.Annotation
import dev.cint.core.artifact.AnnotationJson
import dev.cint.core.config.IntentConfig
import dev.cint.core.facts.frontend.LanguageFrontend
import dev.cint.core.facts.frontend.OracleOptions
import dev.cint.core.facts.frontend.SplitHandler
import dev.cint.core.facts.model.SourceFile
import dev.cint.core.facts.oracle.NullOracle
import dev.cint.core.facts.oracle.TypeOracle
import dev.cint.core.runtime.scheduler.AnalysisOutput
import dev.cint.core.support.hashing.contentHash
import dev.cint.frontend.Frontends
import dev.cint.frontend.SplitAnalyzer
import dev.cint.server.LIVE_LSP_TIMEOUT_MS
import dev.cint.server.LiveServer
import java.io.BufferedWriter
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

internal fun LiveServer.rewriteDocumentTextForTest(
    writer: BufferedWriter,
    uri: String,
    text: String,
) {
    val clientDocs = docs[writer] ?: return
    val doc = clientDocs[uri] ?: return
    clientDocs[uri] = doc.copy(text = text)
}

internal fun LiveServer.analyzeForTest(
    uri: String,
    text: String,
    language: String,
): List<Annotation> = analyzeOutputForTest(uri, text, language).annotations

internal fun LiveServer.analyzeOutputForTest(
    uri: String,
    text: String,
    language: String,
): AnalysisOutput {
    val frontend = Frontends.forLanguage(language) ?: error("unsupported language: $language")
    return analyze(frontend, SourceFile(uri, text), settings.intentConfig(frontend.language))
}

internal fun LiveServer.docsFor(writer: BufferedWriter): ConcurrentHashMap<String, DocState> =
    docs.computeIfAbsent(writer) { ConcurrentHashMap() }

internal fun LiveServer.clearDocument(
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
    sessionLogs.remove(key)
}

internal fun LiveServer.docFrom(params: JsonNode?): DocState? {
    val uri = params?.get("uri")?.asText() ?: return null
    val text = params.get("text")?.asText() ?: return null
    val version = params.get("version")?.asInt() ?: 0
    val language = params.get("languageId")?.asText() ?: defaultLanguage
    return DocState(uri, text, version, language)
}

internal fun LiveServer.publish(
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

internal fun LiveServer.nextAnalysisRevision(key: DocumentKey): Long =
    analysisRevisions.computeIfAbsent(key) { AtomicLong(0) }.incrementAndGet()

internal fun LiveServer.oracleOptions(useLsp: Boolean = this.useLsp): OracleOptions = settings.oracleOptions(useLsp, LIVE_LSP_TIMEOUT_MS)

internal fun LiveServer.oracleFor(frontend: LanguageFrontend): TypeOracle {
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

private fun LiveServer.analyze(
    frontend: LanguageFrontend,
    source: SourceFile,
    config: IntentConfig,
): AnalysisOutput {
    val output =
        if (frontend is SplitHandler) {
            SplitAnalyzer.analyze(
                source,
                frontend,
                root,
                oracleOptions(),
                packs::plan,
                packs::userContributionsFor,
            ) { language -> settings.intentConfig(language) }
        } else {
            val tree = frontend.parse(source)
            val syntax = frontend.syntax(tree)
            packs.analyze(source, tree, syntax, oracleFor(frontend), config)
        }
    logOracleDiagnostics(output)
    return output
}

private fun LiveServer.logOracleDiagnostics(output: AnalysisOutput) {
    val stats = output.oracle
    if (stats.queries == 0L && stats.cacheHits == 0L && stats.timeouts == 0L && stats.failures == 0L) return
    System.err.println(
        "cint oracle: requests=${stats.requests} queries=${stats.hoverCalls} " +
            "cacheHits=${stats.cacheHits} sessionHits=${stats.sessionHits} delegateHits=${stats.delegateHits} " +
            "timeouts=${stats.timeouts} failures=${stats.failures} blocked=${stats.blocked} " +
            "elapsedMs=${stats.elapsedMs}",
    )
}

private fun LiveServer.analyzeHeuristic(
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

internal fun LiveServer.relativePath(uri: String): String {
    val path = runCatching { Path.of(uri) }.getOrNull() ?: return uri
    if (!path.isAbsolute) return uri
    return runCatching { root.relativize(path).toString() }.getOrDefault(uri)
}
