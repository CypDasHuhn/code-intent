package dev.cint.core.composition

import dev.cint.contracts.ports.CompositionSpec

object CompositionPlan {
    fun config(specs: List<CompositionSpec>): CompositionConfig {
        val present = specs.filter { it.derivativeUse != null || it.scaffolds != null || it.pureAnnotations.isNotEmpty() }
        if (present.isEmpty()) return CompositionConfig()
        return CompositionConfig(
            derivativeUseEnabled = present.all { it.derivativeUse?.enabled ?: true },
            derivativeMinConfidence =
                present.mapNotNull { it.derivativeUse?.minConfidence }.maxOrNull()
                    ?: CompositionConfig().derivativeMinConfidence,
            scaffoldsEnabled = present.all { it.scaffolds?.enabled ?: true },
            pureAnnotations = present.flatMap { it.pureAnnotations }.distinct().sorted(),
        )
    }
}
