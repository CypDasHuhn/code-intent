package dev.cint.contracts.data

import dev.cint.contracts.facts.FactBatch
import dev.cint.contracts.data.Span
import kotlinx.serialization.Serializable

@Serializable
data class AnalysisResult(
    val annotations: List<Annotation>,
    val candidates: List<Candidate>,
    val facts: FactBatch,
    val sourceEpoch: Long,
    val completeness: Completeness,
    val errors: List<AnalysisError>,
)

@Serializable
enum class Completeness {
    COMPLETE,
    STALE,
    PARTIAL,
}

@Serializable
data class AnalysisError(
    val code: String,
    val message: String,
    val span: Span? = null,
    val severity: ErrorSeverity = ErrorSeverity.ERROR,
)

@Serializable
enum class ErrorSeverity {
    ERROR,
    WARNING,
    INFO,
}