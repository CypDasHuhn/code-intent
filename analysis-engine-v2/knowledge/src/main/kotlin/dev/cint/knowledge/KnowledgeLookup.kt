package dev.cint.knowledge

import dev.cint.contracts.data.Intent
import dev.cint.contracts.knowledge.FunctionSelector
import dev.cint.contracts.knowledge.KnowledgeSnapshot
import dev.cint.contracts.knowledge.SpanSelector

object KnowledgeLookup {
    fun effectiveIntents(
        snapshot: KnowledgeSnapshot,
        functionSelector: FunctionSelector,
        overrideSelector: SpanSelector? = null,
    ): Set<Intent> {
        if (overrideSelector != null) {
            val override = snapshot.spanOverrides
                .firstOrNull {
                    it.selector.identityKey == overrideSelector.identityKey &&
                        KnowledgeStatusPolicy.eligible(it.status)
                }
            if (override != null) {
                return override.intents.toSet()
            }
        }
        return snapshot.functionAnnotations
            .firstOrNull {
                it.selector.identityKey == functionSelector.identityKey &&
                    KnowledgeStatusPolicy.eligible(it.status)
            }
            ?.intents
            ?.toSet()
            ?: emptySet()
    }
}
