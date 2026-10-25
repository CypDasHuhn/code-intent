package dev.cint.knowledge

import dev.cint.contracts.data.Provenance
import dev.cint.contracts.knowledge.AnnotationScope
import dev.cint.contracts.knowledge.FunctionAnnotation
import dev.cint.contracts.knowledge.FunctionSelector
import dev.cint.contracts.knowledge.PackDefault
import dev.cint.contracts.knowledge.SpanOverride

object KnowledgePrecedence {
    fun functionAnnotations(specificity: (FunctionSelector) -> Int = { 0 }): Comparator<FunctionAnnotation> =
        compareByDescending<FunctionAnnotation> { KnowledgeStatusPolicy.rank(it.status) }
            .thenByDescending { specificity(it.selector) }
            .thenByDescending { scopeRank(it.scope) }
            .thenByDescending { provenanceRank(it.provenance) }
            .thenByDescending { it.confidence.value }
            .thenBy { it.selector.identityKey }

    fun packDefaults(specificity: (FunctionSelector) -> Int = { 0 }): Comparator<PackDefault> =
        compareByDescending<PackDefault> { KnowledgeStatusPolicy.rank(it.status) }
            .thenByDescending { specificity(it.selector) }
            .thenByDescending { provenanceRank(it.provenance) }
            .thenByDescending { it.confidence.value }
            .thenBy { it.packId }
            .thenBy { it.packVersion }
            .thenBy { it.intents.sorted().joinToString("\u0000") }

    fun spanOverrides(): Comparator<SpanOverride> =
        compareByDescending<SpanOverride> { KnowledgeStatusPolicy.rank(it.status) }
            .thenByDescending { provenanceRank(it.provenance) }
            .thenByDescending { it.confidence.value }
            .thenBy { it.selector.identityKey }

    fun scopeRank(scope: AnnotationScope): Int =
        when (scope) {
            AnnotationScope.GLOBAL -> 0
            AnnotationScope.FILE -> 1
            AnnotationScope.PROJECT -> 2
        }

    fun provenanceRank(provenance: Provenance): Int =
        when (provenance) {
            Provenance.USER -> 5
            Provenance.MCP_AGENT -> 4
            Provenance.PACK_DEFAULT -> 3
            Provenance.AI_FALLBACK -> 2
            Provenance.RULE -> 1
            Provenance.DERIVED -> 0
        }
}

internal val packDefaultPrecedence: Comparator<PackDefault> = KnowledgePrecedence.packDefaults()
