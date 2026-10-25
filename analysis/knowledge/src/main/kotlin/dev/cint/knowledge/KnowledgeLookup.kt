package dev.cint.knowledge

import dev.cint.contracts.data.Intent
import dev.cint.contracts.knowledge.AnnotationScope
import dev.cint.contracts.knowledge.FunctionSelector
import dev.cint.contracts.knowledge.KnowledgeSnapshot
import dev.cint.contracts.knowledge.SpanSelector

object KnowledgeLookup {
    fun effectiveIntents(
        snapshot: KnowledgeSnapshot,
        functionSelector: FunctionSelector,
        overrideSelector: SpanSelector? = null,
        path: String? = null,
    ): Set<Intent> {
        if (overrideSelector != null) {
            val override =
                snapshot.spanOverrides
                    .filter {
                        it.selector.identityKey == overrideSelector.identityKey &&
                            KnowledgeStatusPolicy.eligible(it.status)
                    }.minWithOrNull(KnowledgePrecedence.spanOverrides())
            if (override != null) {
                return override.intents.toSet()
            }
        }
        return snapshot.functionAnnotations
            .filter {
                it.selector.identityKey == functionSelector.identityKey &&
                    KnowledgeStatusPolicy.eligible(it.status) &&
                    when (it.scope) {
                        AnnotationScope.GLOBAL, AnnotationScope.PROJECT -> true
                        AnnotationScope.FILE -> path != null && it.path == path
                    }
            }.minWithOrNull(KnowledgePrecedence.functionAnnotations())
            ?.intents
            ?.toSet()
            ?: emptySet()
    }
}
