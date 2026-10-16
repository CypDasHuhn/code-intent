package com.semanticpainter

import org.yaml.snakeyaml.Yaml
import java.io.File

data class FeatureConfig(
    val tag: String,
    val params: Map<String, List<String>>,
) {
    fun list(key: String): List<String> = params[key].orEmpty()

    fun has(key: String, value: String): Boolean =
        list(key).any { value == it || value.endsWith(".$it") }
}

fun loadFeatureConfig(path: String, feature: String, language: String): FeatureConfig {
    @Suppress("UNCHECKED_CAST")
    val root = Yaml().load<Map<String, Any?>>(File(path).readText()) ?: error("empty config: $path")
    val features = root["features"] as? Map<String, Any?> ?: error("config missing 'features'")
    val feat = features[feature] as? Map<String, Any?> ?: error("config missing feature '$feature'")
    val tag = feat["tag"] as? String ?: feature.replaceFirstChar { it.uppercaseChar() }
    val langs = feat["languages"] as? Map<String, Any?> ?: error("feature '$feature' missing 'languages'")
    val lang = langs[language] as? Map<String, Any?>
        ?: error("config missing language '$language' for feature '$feature'")
    return FeatureConfig(tag, lang.mapValues { (_, v) -> toStringList(v) })
}

private fun toStringList(value: Any?): List<String> = when (value) {
    null -> emptyList()
    is List<*> -> value.map { it.toString() }
    else -> listOf(value.toString())
}
