package dev.cint.rulepacks

import dev.cint.contracts.data.Confidence
import dev.cint.contracts.knowledge.FunctionSelector
import dev.cint.contracts.ports.CompositionSpec
import dev.cint.contracts.ports.PackManifest
import dev.cint.contracts.ports.Playbook
import dev.cint.contracts.ports.RuleSpec
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

object BundledPacks {
    val all: List<PackDefinition> by lazy { BundledPackLoader.load() }
}

internal object BundledPackLoader {
    private const val ROOT = "/default-rulepacks"
    private const val INDEX = "$ROOT/index.json"
    private const val SUPPORTED_FORMAT_VERSION = 1

    private val json = Json { ignoreUnknownKeys = false }

    fun load(): List<PackDefinition> {
        val index = json.decodeFromString<PackIndex>(resource(INDEX))
        require(index.formatVersion == SUPPORTED_FORMAT_VERSION) {
            "Unsupported bundled pack format version: ${index.formatVersion}"
        }
        return index.packs.map(::definition)
    }

    private fun definition(pack: String): PackDefinition {
        val base = "$ROOT/$pack"
        val manifest = json.decodeFromString<BundledPackManifest>(resource("$base/manifest.json"))
        val playbook = json.decodeFromString<BundledPackPlaybook>(resource("$base/playbook.json"))
        val defaults = json.decodeFromString<BundledPackDefaults>(resource("$base/defaults.json"))
        val rules = json.decodeFromString<RulePackRules>(resource("$base/rules.json"))
        require(manifest.id == pack) {
            "Pack directory '$pack' declares manifest id '${manifest.id}'"
        }
        require(playbook.packId == pack) {
            "Pack directory '$pack' declares playbook id '${playbook.packId}'"
        }
        val languages =
            defaults.defaults
                .flatMap { it.languages }
                .distinct()
                .sorted()
        val ruleSpecs = playbook.rules
        val missingRules = ruleSpecs.map { it.id }.filterNot { it in rules.rules }.sorted()
        require(missingRules.isEmpty()) {
            "Pack '$pack' is missing rule data for: ${missingRules.joinToString(",")}"
        }
        val unknownRules =
            rules.rules.keys
                .filterNot { it in ruleSpecs.map { spec -> spec.id } }
                .sorted()
        require(unknownRules.isEmpty()) {
            "Pack '$pack' declares rule data for playbook rules that do not exist: ${unknownRules.joinToString(",")}"
        }
        return PackDefinition(
            manifest =
                PackManifest(
                    id = manifest.id,
                    name = manifest.name,
                    version = manifest.version,
                    languages = languages,
                    description = manifest.description,
                    dependencies = manifest.dependencies.distinct().sorted(),
                    providesIntents = ruleSpecs.map { it.owns }.distinct().sorted(),
                    defaultEnabled = manifest.defaultEnabled,
                ),
            playbook = Playbook(packId = pack, rules = ruleSpecs, composition = playbook.composition),
            seeds =
                defaults.defaults.map { seed ->
                    PackSeedDefault(
                        selector = seed.selector,
                        intents = seed.intents.toSet(),
                        languages = seed.languages.toSet(),
                        confidence = Confidence.fromDouble(seed.confidence),
                    )
                },
            rules = rules.rules,
        )
    }

    private fun resource(path: String): String =
        BundledPackLoader::class.java
            .getResourceAsStream(path)
            ?.bufferedReader(Charsets.UTF_8)
            ?.use { it.readText() }
            ?: error("Missing bundled pack resource: $path")
}

@Serializable
private data class PackIndex(
    val formatVersion: Int = 1,
    val packs: List<String>,
)

@Serializable
private data class BundledPackManifest(
    val id: String,
    val name: String,
    val version: String,
    val description: String = "",
    val defaultEnabled: Boolean = true,
    val dependencies: List<String> = emptyList(),
)

@Serializable
private data class BundledPackPlaybook(
    val packId: String,
    val rules: List<RuleSpec> = emptyList(),
    val composition: CompositionSpec? = null,
)

@Serializable
private data class RulePackRules(
    val rules: Map<String, JsonObject> = emptyMap(),
)

@Serializable
private data class BundledPackDefaults(
    val defaults: List<BundledPackDefaultResource> = emptyList(),
)

@Serializable
private data class BundledPackDefaultResource(
    val selector: FunctionSelector,
    val intents: List<String>,
    val languages: List<String>,
    val confidence: Double = 0.5,
)
