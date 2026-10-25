package dev.cint.contracts.knowledge

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed interface KnowledgeChange {
    @Serializable
    @SerialName("SetFunctionAnnotation")
    data class SetFunctionAnnotation(
        val annotation: FunctionAnnotation,
    ) : KnowledgeChange

    @Serializable
    @SerialName("RemoveFunctionAnnotation")
    data class RemoveFunctionAnnotation(
        val selector: FunctionSelector,
    ) : KnowledgeChange

    @Serializable
    @SerialName("SetSpanOverride")
    data class SetSpanOverride(
        val override: SpanOverride,
    ) : KnowledgeChange

    @Serializable
    @SerialName("RemoveSpanOverride")
    data class RemoveSpanOverride(
        val selector: SpanSelector,
    ) : KnowledgeChange

    @Serializable
    @SerialName("MaterializeDefaults")
    data class MaterializeDefaults(
        val defaults: List<PackDefault>,
    ) : KnowledgeChange

    @Serializable
    @SerialName("ProposeChange")
    data class ProposeChange(
        val description: String,
        val changes: List<KnowledgeChange>,
    ) : KnowledgeChange
}
