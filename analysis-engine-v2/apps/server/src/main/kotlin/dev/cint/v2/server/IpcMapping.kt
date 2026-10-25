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

internal fun legacyPublishMessages(
    uri: String,
    version: Int,
    hash: String,
    analysisVersion: Long,
    analysis: AnalysisResult,
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
    val candidates =
        analysis.candidates.map { candidate ->
            buildJsonObject {
                put("candidateId", candidateId(candidate, uri))
                put("path", candidate.path ?: uri)
                put("contentHash", hash)
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
            }
        }
    val tags =
        buildJsonObject {
            put("uri", uri)
            put("version", version)
            put("hash", hash)
            put("revision", 0)
            put("analysisRevision", analysisVersion)
            put("annotations", JsonArray(annotations))
        }
    val proposals =
        buildJsonObject {
            put("uri", uri)
            put("version", version)
            put("analysisRevision", analysisVersion)
            put("suggestionRevision", analysisVersion)
            put("groups", JsonArray(emptyList()))
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
