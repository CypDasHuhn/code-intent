package dev.cint.contracts.knowledge

import dev.cint.contracts.util.currentTimeMillis
import kotlinx.serialization.Serializable

@Serializable
data class KnowledgeSnapshot(
    val functionAnnotations: List<FunctionAnnotation>,
    val spanOverrides: List<SpanOverride>,
    val packDefaults: List<PackDefault>,
    val patternHypotheses: List<PatternHypothesis>,
    val version: Int = 1,
    val timestamp: Long = currentTimeMillis(),
)