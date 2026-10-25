package dev.cint.packs

import dev.cint.contracts.data.Confidence
import dev.cint.contracts.knowledge.FunctionSelector
import dev.cint.contracts.ports.CompositionSpec
import dev.cint.contracts.ports.PackManifest
import dev.cint.contracts.ports.Playbook
import dev.cint.contracts.ports.RuleSpec
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

object BundledPacks {
    val all: List<PackDefinition> by lazy { BundledPackLoader.load() }
}

internal object BundledPackLoader {
    private const val RESOURCE = "/packs/bundled-packs.json"
    private const val SUPPORTED_FORMAT_VERSION = 1

    private val json = Json { ignoreUnknownKeys = false }

    fun load(): List<PackDefinition> {
        val text =
            BundledPackLoader::class.java
                .getResourceAsStream(RESOURCE)
                ?.bufferedReader(Charsets.UTF_8)
                ?.use { it.readText() }
                ?: error("Missing bundled pack resource: $RESOURCE")
        val resource = json.decodeFromString<BundledPackResourceFile>(text)
        require(resource.formatVersion == SUPPORTED_FORMAT_VERSION) {
            "Unsupported bundled pack format version: ${resource.formatVersion}"
        }
        return resource.packs.map(::definition)
    }

    private fun definition(resource: BundledPackResource): PackDefinition {
        val languages =
            resource.defaults
                .flatMap { it.languages }
                .distinct()
                .sorted()
        val rules = resource.rules
        val manifest =
            PackManifest(
                id = resource.id,
                name = resource.name,
                version = resource.version,
                languages = languages,
                description = resource.description,
                dependencies = resource.dependencies.distinct().sorted(),
                providesIntents = rules.map { it.owns }.distinct().sorted(),
                defaultEnabled = resource.defaultEnabled,
            )
        return PackDefinition(
            manifest = manifest,
            playbook = Playbook(packId = resource.id, rules = rules, composition = resource.composition),
            seeds =
                resource.defaults.map { seed ->
                    PackSeedDefault(
                        selector = seed.selector,
                        intents = seed.intents.toSet(),
                        languages = seed.languages.toSet(),
                        confidence = Confidence.fromDouble(seed.confidence),
                    )
                },
        )
    }
}

@Serializable
private data class BundledPackResourceFile(
    val formatVersion: Int = 1,
    val packs: List<BundledPackResource>,
)

@Serializable
private data class BundledPackResource(
    val id: String,
    val name: String,
    val version: String,
    val description: String = "",
    val defaultEnabled: Boolean = true,
    val dependencies: List<String> = emptyList(),
    val rules: List<RuleSpec> = emptyList(),
    val composition: CompositionSpec? = null,
    val defaults: List<BundledPackDefaultResource> = emptyList(),
)

@Serializable
private data class BundledPackDefaultResource(
    val selector: FunctionSelector,
    val intents: List<String>,
    val languages: List<String>,
    val confidence: Double = 0.5,
)
