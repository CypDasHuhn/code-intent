package dev.cint.knowledge

import dev.cint.contracts.knowledge.FunctionAnnotation
import dev.cint.contracts.knowledge.PackOrigin
import dev.cint.contracts.knowledge.PatternHypothesis
import dev.cint.contracts.knowledge.SpanOverride

internal data class KnowledgeStoreState(
    val hasStore: Boolean,
    val timestamp: Long,
    val annotations: LinkedHashMap<String, FunctionAnnotation>,
    val overrides: LinkedHashMap<String, SpanOverride>,
    val hypotheses: LinkedHashMap<String, PatternHypothesis>,
    val packOrigins: LinkedHashMap<String, PackOrigin>,
)
