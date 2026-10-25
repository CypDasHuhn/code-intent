package dev.cint.packs

import dev.cint.contracts.data.Confidence
import dev.cint.contracts.data.Intent
import dev.cint.contracts.knowledge.FunctionSelector
import dev.cint.contracts.ports.PackManifest
import dev.cint.contracts.ports.Playbook

data class PackSeedDefault(
    val selector: FunctionSelector,
    val intents: Set<Intent>,
    val languages: Set<String>,
    val confidence: Confidence = Confidence.MEDIUM,
)

data class PackDefinition(
    val manifest: PackManifest,
    val playbook: Playbook,
    val seeds: List<PackSeedDefault> = emptyList(),
)
