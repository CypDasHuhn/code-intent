package dev.cint.v2.server

import dev.cint.contracts.data.AnalysisError
import dev.cint.contracts.data.AnalysisResult
import dev.cint.contracts.data.Annotation
import dev.cint.contracts.data.Candidate
import dev.cint.contracts.data.CandidateReason
import dev.cint.contracts.data.Contribution
import dev.cint.contracts.util.ContentHash
import dev.cint.ipc.AnalyzedFile
import dev.cint.ipc.AnalyzeResult
import dev.cint.ipc.CandidateOperation
import dev.cint.ipc.CandidateOperationKind
import dev.cint.ipc.CandidateOrigin
import dev.cint.ipc.IpcAnnotation
import dev.cint.ipc.IpcCandidate
import dev.cint.ipc.IpcDiagnostic
import dev.cint.ipc.IpcMethods
import dev.cint.ipc.IpcProvenance
import dev.cint.ipc.IpcSeverity
import dev.cint.ipc.IpcSpan
import dev.cint.ipc.PublishCandidatesParams
import dev.cint.ipc.PublishTagsParams
import dev.cint.ipc.JsonRpcMessage
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal fun analyzeResultOf(
    uri: String,
    hash: String?,
    analysis: AnalysisResult,
): AnalyzeResult {
    val diagnostics = analysis.errors.map(::diagnosticOf)
    return AnalyzeResult(
        files =
            listOf(
                AnalyzedFile(
                    path = uri,
                    hash = hash,
                    annotations = analysis.annotations.map(::annotationOf),
                    diagnostics = diagnostics,
                ),
            ),
        candidates = analysis.candidates.map { candidateOf(it, uri, hash.orEmpty()) },
        diagnostics = diagnostics,
    )
}

internal fun analyzeResultForTargets(results: List<Pair<String, AnalysisResult>>): AnalyzeResult {
    val files =
        results.map { (uri, analysis) ->
            AnalyzedFile(
                path = uri,
                hash = null,
                annotations = analysis.annotations.map(::annotationOf),
                diagnostics = analysis.errors.map(::diagnosticOf),
            )
        }
    val candidates = results.flatMap { (uri, analysis) -> analysis.candidates.map { candidateOf(it, uri, "") } }
    val diagnostics = results.flatMap { (_, analysis) -> analysis.errors.map(::diagnosticOf) }
    return AnalyzeResult(files = files, candidates = candidates, diagnostics = diagnostics)
}

internal fun tagsParamsOf(
    uri: String,
    version: Int,
    hash: String,
    analysisVersion: Long,
    analysis: AnalysisResult,
): PublishTagsParams =
    PublishTagsParams(
        uri = uri,
        version = version,
        hash = hash,
        configRevision = 0,
        analysisVersion = analysisVersion,
        annotations = analysis.annotations.map(::annotationOf),
        diagnostics = analysis.errors.map(::diagnosticOf),
    )

internal fun candidatesParamsOf(
    uri: String,
    version: Int,
    hash: String,
    analysisVersion: Long,
    analysis: AnalysisResult,
): PublishCandidatesParams =
    PublishCandidatesParams(
        uri = uri,
        version = version,
        analysisRevision = analysisVersion,
        proposalRevision = analysisVersion,
        proposals = analysis.candidates.map { candidateOf(it, uri, hash) },
    )

internal fun legacyContentHash(text: String): String =
    ContentHash.compute(text.replace("\r\n", "\n").removeSuffix("\n")).value

private fun candidateContentHash(
    candidate: Candidate,
    text: String?,
): String? {
    text ?: return null
    val bytes = text.toByteArray(Charsets.UTF_8)
    if (candidate.startByte !in 0..bytes.size || candidate.endByte !in candidate.startByte..bytes.size) return null
    return legacyContentHash(String(bytes, candidate.startByte, candidate.endByte - candidate.startByte, Charsets.UTF_8))
}

internal fun legacyPublishMessages(
    uri: String,
    version: Int,
    hash: String,
    analysisVersion: Long,
    analysis: AnalysisResult,
    text: String? = null,
    revision: Long = 0,
): List<JsonRpcMessage> {
    val annotations =
        analysis.annotations.map { annotation ->
            val provenance = annotation.provenance.map(::legacyContribution)
            buildJsonObject {
                put("role", roleOf(annotation.role.name))
                put("intents", JsonArray(annotation.intents.sorted().map(::JsonPrimitive)))
                put("source", legacySource(annotation.source))
                put("confidence", annotation.confidence.value)
                put("startLine", annotation.span.startLine)
                put("startCol", annotation.span.startCol)
                put("endLine", annotation.span.endLine)
                put("endCol", annotation.span.endCol)
                put("startByte", annotation.startByte)
                put("endByte", annotation.endByte)
                put("nodeAnchor", annotation.nodeAnchor?.id?.let(::JsonPrimitive) ?: JsonNull)
                put("provenance", JsonArray(provenance))
            }
        }
    val legacyCandidates =
        analysis.candidates.map { candidate ->
            val path = candidate.path ?: uri
            val contentHash = candidateContentHash(candidate, text) ?: hash
            LegacyCandidate(
                candidate = candidate,
                path = path,
                contentHash = contentHash,
                json =
                    buildJsonObject {
                        put("candidateId", legacyCandidateId(path, contentHash, candidate))
                        put("path", path)
                        put("contentHash", contentHash)
                        put("intents", JsonArray(candidate.intents.sorted().map(::JsonPrimitive)))
                        put("source", legacyCandidateSource(candidate.source))
                        put("confidence", candidate.confidence.value)
                        put("startLine", candidate.span.startLine)
                        put("startCol", candidate.span.startCol)
                        put("endLine", candidate.span.endLine)
                        put("endCol", candidate.span.endCol)
                        put("startByte", candidate.startByte)
                        put("endByte", candidate.endByte)
                        put("reason", candidate.reason.name)
                        put("aiEligible", candidate.aiEligible)
                        candidate.rationale?.let { put("rationale", it) }
                        candidate.provider?.let { put("provider", it) }
                        candidate.model?.let { put("model", it) }
                        candidate.promptVersion?.let { put("promptVersion", it) }
                    },
            )
        }
    val candidates = legacyCandidates.map { it.json }
    val groups =
        legacyCandidates
            .groupBy { Triple(it.contentHash, it.candidate.startByte, it.candidate.endByte) }
            .map { (key, grouped) -> key to grouped }
            .sortedWith(compareBy({ it.first.second }, { it.first.third }, { it.first.first }))
            .map { (key, grouped) ->
                val (contentHash, startByte, endByte) = key
                val first = grouped.first()
                buildJsonObject {
                    put("anchorId", legacyAnchorId(first.path, contentHash, startByte, endByte))
                    put("path", first.path)
                    put("contentHash", contentHash)
                    put("startLine", first.candidate.span.startLine)
                    put("startCol", first.candidate.span.startCol)
                    put("endLine", first.candidate.span.endLine)
                    put("endCol", first.candidate.span.endCol)
                    put("startByte", startByte)
                    put("endByte", endByte)
                    put("candidates", JsonArray(grouped.map { it.json }))
                    put(
                        "operations",
                        JsonArray(
                            grouped.flatMap { entry ->
                                entry.candidate.intents.sorted().map { intent ->
                                    buildJsonObject {
                                        put("kind", "ADD")
                                        put("intent", intent)
                                        put("startLine", entry.candidate.span.startLine)
                                        put("startCol", entry.candidate.span.startCol)
                                        put("endLine", entry.candidate.span.endLine)
                                        put("endCol", entry.candidate.span.endCol)
                                        put("startByte", entry.candidate.startByte)
                                        put("endByte", entry.candidate.endByte)
                                        put("confidence", entry.candidate.confidence.value)
                                        entry.candidate.rationale?.let { put("rationale", it) }
                                        put("source", legacyCandidateSource(entry.candidate.source))
                                    }
                                }
                            },
                        ),
                    )
                }
            }
    val tags =
        buildJsonObject {
            put("uri", uri)
            put("version", version)
            put("hash", hash)
            put("revision", revision)
            put("analysisRevision", analysisVersion)
            put("annotations", JsonArray(annotations))
        }
    val proposals =
        buildJsonObject {
            put("uri", uri)
            put("version", version)
            put("analysisRevision", analysisVersion)
            put("suggestionRevision", analysisVersion)
            put("groups", JsonArray(groups))
            put("candidates", JsonArray(candidates))
        }
    return listOf(
        JsonRpcMessage.Notification(IpcMethods.PUBLISH_TAGS, tags),
        JsonRpcMessage.Notification(IpcMethods.PUBLISH_CANDIDATES, proposals),
    )
}

private fun annotationOf(annotation: Annotation): IpcAnnotation =
    IpcAnnotation(
        role = roleOf(annotation.role.name),
        intents = annotation.intents.sorted(),
        source = wireSource(annotation.source),
        confidence = annotation.confidence.value,
        startLine = annotation.span.startLine,
        startCol = annotation.span.startCol,
        endLine = annotation.span.endLine,
        endCol = annotation.span.endCol,
        startByte = annotation.startByte,
        endByte = annotation.endByte,
        nodeAnchor = annotation.nodeAnchor?.id,
        provenance = annotation.provenance.map(::provenanceOf),
    )

private fun provenanceOf(contribution: Contribution): IpcProvenance =
    IpcProvenance(
        ruleId = contribution.rule,
        packId = packIdOf(contribution.rule),
        role = roleOf(contribution.role.name),
        intents = contribution.intents.sorted(),
        source = wireSource(contribution.source),
        confidence = contribution.confidence.value,
        startLine = contribution.span.startLine,
        startCol = contribution.span.startCol,
        endLine = contribution.span.endLine,
        endCol = contribution.span.endCol,
        startByte = contribution.startByte,
        endByte = contribution.endByte,
    )

private fun legacyContribution(contribution: Contribution): JsonObject =
    buildJsonObject {
        put("rule", contribution.rule)
        put("role", roleOf(contribution.role.name))
        put("intents", JsonArray(contribution.intents.sorted().map(::JsonPrimitive)))
        put("source", legacySource(contribution.source))
        put("confidence", contribution.confidence.value)
        put("startLine", contribution.span.startLine)
        put("startCol", contribution.span.startCol)
        put("endLine", contribution.span.endLine)
        put("endCol", contribution.span.endCol)
        put("startByte", contribution.startByte)
        put("endByte", contribution.endByte)
    }

private fun candidateOf(
    candidate: Candidate,
    defaultUri: String,
    hash: String,
): IpcCandidate {
    val origin = originOf(candidate)
    return IpcCandidate(
        candidateId = candidateId(candidate, defaultUri),
        path = candidate.path ?: defaultUri,
        contentHash = hash,
        intents = candidate.intents.sorted(),
        startLine = candidate.span.startLine,
        startCol = candidate.span.startCol,
        endLine = candidate.span.endLine,
        endCol = candidate.span.endCol,
        startByte = candidate.startByte,
        endByte = candidate.endByte,
        confidence = candidate.confidence.value,
        origin = origin,
        category = categoryOf(candidate.reason),
        fingerprints = mapOf("anchor" to candidateId(candidate, defaultUri)),
        operations = listOf(operationOf(candidate, origin)),
    )
}

private fun operationOf(
    candidate: Candidate,
    origin: CandidateOrigin,
): CandidateOperation =
    CandidateOperation(
        kind = CandidateOperationKind.ADD,
        intents = candidate.intents.sorted(),
        startLine = candidate.span.startLine,
        startCol = candidate.span.startCol,
        endLine = candidate.span.endLine,
        endCol = candidate.span.endCol,
        startByte = candidate.startByte,
        endByte = candidate.endByte,
        confidence = candidate.confidence.value,
        origin = origin,
        rationale = candidate.rationale,
    )

private fun originOf(candidate: Candidate): CandidateOrigin {
    val provider = candidate.provider
    val model = candidate.model
    if (provider != null || model != null || candidate.source.startsWith("ai:")) {
        return CandidateOrigin(
            type = "ai",
            provider = provider,
            model = model,
            promptVersion = candidate.promptVersion,
        )
    }
    if (candidate.source.startsWith("rule:")) {
        return CandidateOrigin(type = "rule", ruleId = candidate.source.removePrefix("rule:"))
    }
    return CandidateOrigin(type = "heuristic", ruleId = candidate.source.takeIf { it.isNotBlank() })
}

private fun diagnosticOf(error: AnalysisError): IpcDiagnostic =
    IpcDiagnostic(
        code = error.code,
        message = error.message,
        severity =
            when (error.severity) {
                dev.cint.contracts.data.ErrorSeverity.ERROR -> IpcSeverity.ERROR
                dev.cint.contracts.data.ErrorSeverity.WARNING -> IpcSeverity.WARNING
                dev.cint.contracts.data.ErrorSeverity.INFO -> IpcSeverity.INFO
            },
        span = error.span?.let { IpcSpan(it.startLine, it.startCol, it.endLine, it.endCol, 0, 0) },
    )

private fun wireSource(source: String): String =
    when (source.lowercase()) {
        "knowledge", "annotation", "user", "pack-default" -> "annotation"
        "comment" -> "comment"
        "rule", "candidate", "treesitter", "syntax" -> "treesitter"
        "composition", "cfg" -> "cfg"
        "ai", "ai-fallback", "mcp-agent" -> "ai"
        "heuristic" -> "heuristic"
        else -> "heuristic"
    }

private fun legacySource(source: String): String =
    when (source.lowercase()) {
        "knowledge", "annotation", "user", "pack-default" -> "annotation"
        "comment" -> "comment"
        "rule", "candidate", "treesitter", "syntax" -> "treesitter"
        "heuristic" -> "heuristic"
        "composition", "cfg" -> "cfg"
        "ai", "ai-fallback", "mcp-agent" -> "ai"
        else -> "treesitter"
    }

private fun legacyCandidateSource(source: String): String =
    when {
        source.startsWith("ai:") || source.startsWith("rule:") -> source
        source.equals("ai", ignoreCase = true) -> "ai"
        else -> "rule:$source"
    }

private data class LegacyCandidate(
    val candidate: Candidate,
    val path: String,
    val contentHash: String,
    val json: JsonObject,
)

private fun legacyCandidateId(
    path: String,
    contentHash: String,
    candidate: Candidate,
): String =
    ContentHash.compute(
        listOf(
            path,
            contentHash,
            candidate.startByte.toString(),
            candidate.endByte.toString(),
            candidate.intents.sorted().joinToString(","),
            candidate.reason.name,
        ).joinToString("\n"),
    ).value

private fun legacyAnchorId(
    path: String,
    contentHash: String,
    startByte: Int,
    endByte: Int,
): String =
    ContentHash.compute(
        listOf(path, contentHash, startByte.toString(), endByte.toString(), "", "ANCHOR").joinToString("\n"),
    ).value

private fun candidateId(
    candidate: Candidate,
    defaultUri: String,
): String =
    ContentHash.compute(
        listOf(
            candidate.path ?: defaultUri,
            candidate.startByte,
            candidate.endByte,
            candidate.intents.sorted().joinToString(","),
            candidate.source,
        ).joinToString("|"),
    ).value

private fun categoryOf(reason: CandidateReason): String =
    when (reason) {
        CandidateReason.PACK_DOMAIN -> "pack-domain"
        CandidateReason.LOW_CONFIDENCE -> "low-confidence"
        CandidateReason.GAP -> "low-confidence"
        CandidateReason.AMBIGUOUS_RECEIVER -> "low-confidence"
        CandidateReason.AMBIGUOUS_INTENT -> "low-confidence"
        CandidateReason.MISSING_SEMANTICS -> "low-confidence"
    }

private fun packIdOf(rule: String): String? =
    rule.substringBefore(':').takeIf { it.isNotBlank() && it != rule }

private fun roleOf(role: String): String =
    when (role) {
        "SOURCE_INTENT", "source-intent" -> "source-intent"
        "DERIVATIVE_USE", "derivative-use" -> "derivative-use"
        "SCAFFOLD", "scaffold" -> "scaffold"
        else -> role.lowercase().replace('_', '-')
    }
