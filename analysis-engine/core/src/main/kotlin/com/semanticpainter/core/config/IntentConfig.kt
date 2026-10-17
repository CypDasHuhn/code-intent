package com.semanticpainter.core.config

import com.semanticpainter.core.model.UNTAGGED_INTENT
import org.yaml.snakeyaml.Yaml
import java.io.File

data class IntentDefinition(
    val name: String,
    val meaning: String,
    val blocks: List<String>,
    val params: Map<String, List<String>>,
) {
    fun list(key: String): List<String> = params[key].orEmpty()

    fun has(key: String, value: String): Boolean =
        list(key).any { value == it || value.endsWith(".$it") }
}

data class ResolutionConfig(
    val precedence: List<String>,
    val default: String,
) {
    fun resolve(meanings: Collection<String>): String {
        if (meanings.isEmpty()) return default
        val present = meanings.toSet()
        return precedence.firstOrNull { it in present } ?: default
    }
}

data class IntentConfig(
    val language: String,
    val intents: Map<String, IntentDefinition>,
    val untaggedMeaning: String,
    val resolution: ResolutionConfig,
    val pureAnnotations: List<String>,
) {
    fun meaningOf(intent: String): String =
        if (intent == UNTAGGED_INTENT) {
            untaggedMeaning
        } else {
            intents[intent]?.meaning ?: resolution.default
        }

    fun resolve(intents: Set<String>): String =
        resolution.resolve(intents.map(::meaningOf))
}

fun loadIntentConfig(path: String?, language: String): IntentConfig {
    @Suppress("UNCHECKED_CAST")
    val root = Yaml().load<Map<String, Any?>>(readConfigText(path))
        ?: error("empty config")

    val configuredLanguage = root["language"] as? String ?: language
    val intentNodes = root["intents"] as? Map<String, Any?> ?: error("config missing 'intents'")

    val intents = intentNodes.mapValues { (name, value) ->
        val node = value as? Map<String, Any?> ?: error("intent '$name' must be a map")
        val meaning = node["meaning"] as? String ?: error("intent '$name' missing 'meaning'")
        val blocks = (node["blocks"] as? List<*>)?.map { it.toString() }.orEmpty()
        val lang = node[configuredLanguage] as? Map<String, Any?> ?: emptyMap()
        IntentDefinition(
            name = name,
            meaning = meaning,
            blocks = blocks,
            params = lang.mapValues { (_, v) -> toStringList(v) },
        )
    }

    val untagged = root["untagged"] as? Map<String, Any?> ?: emptyMap()
    val untaggedMeaning = untagged["meaning"] as? String ?: "SHOW"

    val resolveNode = root["resolve"] as? Map<String, Any?> ?: emptyMap()
    val precedence = (resolveNode["precedence"] as? List<*>)?.map { it.toString() }
        ?: listOf("SHOW", "CONCEAL")
    val default = resolveNode["default"] as? String ?: "SHOW"

    val purity = root["purity"] as? Map<String, Any?> ?: emptyMap()
    val pureAnnotations = (purity["annotations"] as? List<*>)?.map { it.toString() }.orEmpty()

    return IntentConfig(
        language = configuredLanguage,
        intents = intents,
        untaggedMeaning = untaggedMeaning,
        resolution = ResolutionConfig(precedence, default),
        pureAnnotations = pureAnnotations,
    )
}

private fun readConfigText(path: String?): String {
    if (path != null) {
        val file = File(path)
        if (file.exists()) return file.readText()
    }
    val resource = "/intents.yaml"
    val stream = IntentConfig::class.java.getResourceAsStream(resource)
        ?: error("config not found: '${path ?: resource}'")
    return stream.bufferedReader().use { it.readText() }
}

private fun toStringList(value: Any?): List<String> = when (value) {
    null -> emptyList()
    is List<*> -> value.map { it.toString() }
    else -> listOf(value.toString())
}
