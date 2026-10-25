package dev.cint.v2.cli

import dev.cint.contracts.data.AnalysisResult
import dev.cint.contracts.data.Annotation
import dev.cint.contracts.data.Candidate
import dev.cint.contracts.data.Contribution
import dev.cint.contracts.data.Role
import dev.cint.contracts.data.Span
import dev.cint.contracts.data.Completeness
import dev.cint.contracts.data.AnalysisError
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Serializable
data class CliDocument(
    val language: String,
    val files: List<CliFile>,
    val meta: CliMeta,
)

@Serializable
data class CliMeta(
    val formatVersion: Int = FORMAT_VERSION,
    val version: String = CliOptionsParser.VERSION,
    val sourceEpoch: Long,
    val completeness: String,
    val fileCount: Int,
) {
    companion object {
        const val FORMAT_VERSION = 1
    }
}

@Serializable
data class CliFile(
    val path: String,
    val hash: String,
    val annotations: List<CliAnnotation>,
    val candidates: List<CliCandidate>,
    val diagnostics: List<CliDiagnostic>,
)

@Serializable
data class CliNdjsonFile(
    val type: String = "file",
    val language: String,
    val file: CliFile,
)

@Serializable
data class CliNdjsonMeta(
    val type: String = "meta",
    val meta: CliMeta,
)

@Serializable
data class CliAnnotation(
    val role: String,
    val intents: List<String>,
    val source: String,
    val confidence: Double,
    val startLine: Int,
    val startCol: Int,
    val endLine: Int,
    val endCol: Int,
    val startByte: Int,
    val endByte: Int,
    val nodeAnchor: JsonElement = JsonNull,
    val provenance: List<CliProvenance>,
)

@Serializable
data class CliProvenance(
    val rule: String,
    val role: String,
    val intents: List<String>,
    val source: String,
    val confidence: Double,
    val startLine: Int,
    val startCol: Int,
    val endLine: Int,
    val endCol: Int,
    val startByte: Int,
    val endByte: Int,
)

@Serializable
data class CliCandidate(
    val intents: List<String>,
    val source: String,
    val confidence: Double,
    val startLine: Int,
    val startCol: Int,
    val endLine: Int,
    val endCol: Int,
    val startByte: Int,
    val endByte: Int,
    val reason: String,
    val aiEligible: Boolean,
    val rationale: String? = null,
    val provider: String? = null,
    val model: String? = null,
    val path: String? = null,
)

@Serializable
data class CliDiagnostic(
    val code: String,
    val message: String,
    val severity: String,
    val span: CliSpan? = null,
)

@Serializable
data class CliSpan(
    val startLine: Int,
    val startCol: Int,
    val endLine: Int,
    val endCol: Int,
)


private fun Annotation.v1Source(): String {
    val mapped = provenance.map { it.v1Source() }.distinct()
    return mapped.singleOrNull() ?: "mixed"
}

private fun Contribution.v1Source(): String =
    when {
        rule == "core:return-category" -> "cfg"
        source == "composition" -> "treesitter"
        source == "provisional" -> "heuristic"
        source == "knowledge" -> "annotation"
        source == "rule" || source == "candidate" -> "heuristic"
        else -> source
    }

private fun Role.roleName(): String = name.lowercase().replace('_', '-')

object CliOutputMapper {
    fun file(
        path: String,
        hash: String,
        result: AnalysisResult,
    ): CliFile =
        CliFile(
            path = path,
            hash = hash,
            annotations = result.annotations.map(::annotation).sortedWith(ANNOTATION_ORDER),
            candidates = result.candidates.map(::candidate).sortedWith(CANDIDATE_ORDER),
            diagnostics = result.errors.map(::diagnostic).sortedWith(DIAGNOSTIC_ORDER),
        )

    fun meta(results: List<AnalysisResult>): CliMeta =
        CliMeta(
            sourceEpoch = results.maxOfOrNull { it.sourceEpoch } ?: 0,
            completeness = worstCompleteness(results).name,
            fileCount = results.size,
        )

    fun annotation(annotation: Annotation): CliAnnotation =
        CliAnnotation(
            role = annotation.role.roleName(),
            intents = annotation.intents.sorted(),
            source = annotation.v1Source(),
            confidence = annotation.confidence.value,
            startLine = annotation.span.startLine,
            startCol = annotation.span.startCol,
            endLine = annotation.span.endLine,
            endCol = annotation.span.endCol,
            startByte = annotation.startByte,
            endByte = annotation.endByte,
            nodeAnchor = annotation.nodeAnchor?.let { buildJsonObject { put("id", it.id) } } ?: JsonNull,
            provenance = annotation.provenance.map(::provenance),
        )

    fun provenance(contribution: Contribution): CliProvenance =
        CliProvenance(
            rule = contribution.rule,
            role = contribution.role.roleName(),
            intents = contribution.intents.sorted(),
            source = contribution.v1Source(),
            confidence = contribution.confidence.value,
            startLine = contribution.span.startLine,
            startCol = contribution.span.startCol,
            endLine = contribution.span.endLine,
            endCol = contribution.span.endCol,
            startByte = contribution.startByte,
            endByte = contribution.endByte,
        )

    fun candidate(candidate: Candidate): CliCandidate =
        CliCandidate(
            intents = candidate.intents.sorted(),
            source = candidate.source,
            confidence = candidate.confidence.value,
            startLine = candidate.span.startLine,
            startCol = candidate.span.startCol,
            endLine = candidate.span.endLine,
            endCol = candidate.span.endCol,
            startByte = candidate.startByte,
            endByte = candidate.endByte,
            reason = candidate.reason.name,
            aiEligible = candidate.aiEligible,
            rationale = candidate.rationale,
            provider = candidate.provider,
            model = candidate.model,
            path = candidate.path,
        )

    fun diagnostic(error: AnalysisError): CliDiagnostic =
        CliDiagnostic(
            code = error.code,
            message = error.message,
            severity = error.severity.name,
            span = error.span?.let(::span),
        )

    fun span(span: Span): CliSpan =
        CliSpan(
            startLine = span.startLine,
            startCol = span.startCol,
            endLine = span.endLine,
            endCol = span.endCol,
        )

    fun worstCompleteness(results: List<AnalysisResult>): Completeness =
        results.map { it.completeness }.fold(Completeness.COMPLETE) { left, right ->
            when {
                left == Completeness.PARTIAL || right == Completeness.PARTIAL -> Completeness.PARTIAL
                left == Completeness.STALE || right == Completeness.STALE -> Completeness.STALE
                else -> Completeness.COMPLETE
            }
        }

    val ANNOTATION_ORDER: Comparator<CliAnnotation> =
        compareBy(
            { it.startLine },
            { it.startByte },
            { it.role },
            { it.source },
            { it.intents.joinToString(",") },
            { it.endLine },
            { it.endCol },
        )

    val CANDIDATE_ORDER: Comparator<CliCandidate> =
        compareBy(
            { it.startLine },
            { it.startByte },
            { it.source },
            { it.intents.joinToString(",") },
            { it.endLine },
            { it.endCol },
        )

    val DIAGNOSTIC_ORDER: Comparator<CliDiagnostic> =
        compareBy(
            { it.span?.startLine ?: Int.MAX_VALUE },
            { it.span?.startCol ?: Int.MAX_VALUE },
            { it.code },
            { it.message },
            { it.severity },
        )
}
