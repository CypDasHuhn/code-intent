package com.semanticpainter.core.config

import org.yaml.snakeyaml.Yaml
import java.io.File

data class IntentDefinition(
    val name: String,
    val blocks: List<String>,
    val params: Map<String, List<String>>,
) {
    fun list(key: String): List<String> = params[key].orEmpty()

    fun has(key: String, value: String): Boolean =
        list(key).any { value == it || value.endsWith(".$it") }
}

data class IntentConfig(
    val language: String,
    val intents: Map<String, IntentDefinition>,
    val pureAnnotations: List<String>,
)

fun loadIntentConfig(path: String?, language: String): IntentConfig {
    @Suppress("UNCHECKED_CAST")
    val root = Yaml().load<Map<String, Any?>>(readConfigText(path))
        ?: error("empty config")

    val configuredLanguage = root["language"] as? String ?: language
    val intentNodes = root["intents"] as? Map<String, Any?> ?: error("config missing 'intents'")

    val intents = intentNodes.mapValues { (name, value) ->
        val node = value as? Map<String, Any?> ?: error("intent '$name' must be a map")
        val blocks = (node["blocks"] as? List<*>)?.map { it.toString() }.orEmpty()
        val lang = node[configuredLanguage] as? Map<String, Any?> ?: emptyMap()
        IntentDefinition(
            name = name,
            blocks = blocks,
            params = lang.mapValues { (_, v) -> toStringList(v) },
        )
    }

    val purity = root["purity"] as? Map<String, Any?> ?: emptyMap()
    val pureAnnotations = (purity["annotations"] as? List<*>)?.map { it.toString() }.orEmpty()

    return IntentConfig(
        language = configuredLanguage,
        intents = intents,
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
