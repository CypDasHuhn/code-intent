package dev.cint.contracts.ports

import dev.cint.contracts.knowledge.PackDefault
import dev.cint.contracts.util.CancellationToken
import dev.cint.contracts.util.ContractResult
import kotlinx.serialization.Serializable

interface RulePackSource {
    fun manifests(cancellation: CancellationToken = CancellationToken.NONE): ContractResult<List<PackManifest>>

    fun playbook(
        packId: String,
        cancellation: CancellationToken = CancellationToken.NONE,
    ): ContractResult<Playbook>

    fun defaults(
        language: String,
        cancellation: CancellationToken = CancellationToken.NONE,
    ): ContractResult<List<PackDefault>>
}

@Serializable
data class PackManifest(
    val id: String,
    val name: String,
    val version: String,
    val languages: List<String>,
    val description: String = "",
    val dependencies: List<String> = emptyList(),
    val providesIntents: List<String> = emptyList(),
)

@Serializable
data class Playbook(
    val packId: String,
    val rules: List<RuleSpec>,
    val composition: CompositionSpec? = null,
)

@Serializable
data class RuleSpec(
    val id: String,
    val owns: String,
    val reads: List<String> = emptyList(),
    val optionalReads: List<String> = emptyList(),
    val config: Map<String, String> = emptyMap(),
)

@Serializable
data class CompositionSpec(
    val derivativeUse: DerivativeUseSpec? = null,
    val scaffolds: ScaffoldSpec? = null,
)

@Serializable
data class DerivativeUseSpec(
    val enabled: Boolean = true,
    val minConfidence: Double = 0.5,
)

@Serializable
data class ScaffoldSpec(
    val enabled: Boolean = true,
    val minCoverage: Double = 0.8,
)