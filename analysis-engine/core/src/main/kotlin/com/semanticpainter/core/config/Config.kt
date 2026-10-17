package com.semanticpainter.core.config

import org.yaml.snakeyaml.Yaml
import java.io.File

data class FeatureConfig(
    val feature: String,
    val tag: String,
    val language: String,
    val params: Map<String, List<String>>,
    val blocks: List<String>,
) {
    fun list(key: String): List<String> = params[key].orEmpty()

    fun has(key: String, value: String): Boolean =
        list(key).any { value == it || value.endsWith(".$it") }
}

fun loadFeatureConfig(path: String?, feature: String, language: String): FeatureConfig {
    @Suppress("UNCHECKED_CAST")
    val root = Yaml().load<Map<String, Any?>>(readConfigText(path, feature))
        ?: error("empty config")

    val features = root["features"] as? Map<String, Any?> ?: error("config missing 'features'")
    val feat = features[feature] as? Map<String, Any?> ?: error("config missing feature '$feature'")
    val tag = feat["tag"] as? String ?: feature.replaceFirstChar { it.uppercaseChar() }
    val langs = feat["languages"] as? Map<String, Any?> ?: error("feature '$feature' missing 'languages'")
    val lang = langs[language] as? Map<String, Any?>
        ?: error("config missing language '$language' for feature '$feature'")

    val blocks = (feat["blocks"] as? List<*>)?.map { it.toString() }.orEmpty()

    return FeatureConfig(
        feature = feature,
        tag = tag,
        language = language,
        params = lang.mapValues { (_, value) -> toStringList(value) },
        blocks = blocks,
    )
}

private fun readConfigText(path: String?, feature: String): String {
    if (path != null) {
        val file = File(path)
        if (file.exists()) return file.readText()
    }
    val resource = "/$feature.yaml"
    val stream = FeatureConfig::class.java.getResourceAsStream(resource)
        ?: error("config not found: '${path ?: resource}'")
    return stream.bufferedReader().use { it.readText() }
}

private fun toStringList(value: Any?): List<String> = when (value) {
    null -> emptyList()
    is List<*> -> value.map { it.toString() }
    else -> listOf(value.toString())
}
