package dev.cint.contracts.knowledge

import dev.cint.contracts.data.Provenance
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

@Serializable
sealed interface KnowledgeChange {
    data class SetFunctionAnnotation(
        val annotation: FunctionAnnotation,
        val provenance: Provenance = Provenance.USER,
    ) : KnowledgeChange

    data class RemoveFunctionAnnotation(
        val selector: FunctionSelector,
    ) : KnowledgeChange

    data class SetSpanOverride(
        val override: SpanOverride,
        val provenance: Provenance = Provenance.USER,
    ) : KnowledgeChange

    data class RemoveSpanOverride(
        val selector: SpanSelector,
    ) : KnowledgeChange

    data class MaterializeDefaults(
        val defaults: List<PackDefault>,
    ) : KnowledgeChange

    data class ProposeChange(
        val description: String,
        val changes: List<KnowledgeChange>,
    ) : KnowledgeChange
}