package dev.cint.treesitter

import dev.cint.core.syntax.SyntaxProfile
import org.yaml.snakeyaml.Yaml

object SyntaxProfiles {
    fun load(language: String): SyntaxProfile {
        val resource = "/syntax/$language.yaml"
        val stream =
            SyntaxProfiles::class.java.getResourceAsStream(resource)
                ?: error("syntax profile not found: $resource")
        val root =
            stream.bufferedReader().use { Yaml().load<Map<String, Any?>>(it) }
                ?: error("empty syntax profile: $resource")
        return SyntaxProfile(
            language = root["language"] as? String ?: language,
            groups = stringSetMap(root["groups"]),
            tokens = stringMap(root["tokens"]),
            keywords = stringSetMap(root["keywords"]),
            operators = stringSetMap(root["operators"]),
            fields = stringListMap(root["fields"]),
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun stringSetMap(value: Any?): Map<String, Set<String>> {
        val map = value as? Map<String, Any?> ?: return emptyMap()
        return map.mapValues { (_, item) -> stringSet(item) }
    }

    @Suppress("UNCHECKED_CAST")
    private fun stringMap(value: Any?): Map<String, String> {
        val map = value as? Map<String, Any?> ?: return emptyMap()
        return map.mapValues { (_, item) -> item.toString() }
    }

    @Suppress("UNCHECKED_CAST")
    private fun stringListMap(value: Any?): Map<String, List<String>> {
        val map = value as? Map<String, Any?> ?: return emptyMap()
        return map.mapValues { (_, item) -> stringList(item) }
    }

    private fun stringList(value: Any?): List<String> =
        when (value) {
            null -> emptyList()
            is List<*> -> value.map { it.toString() }
            else -> listOf(value.toString())
        }

    private fun stringSet(value: Any?): Set<String> =
        when (value) {
            null -> emptySet()
            is List<*> -> value.map { it.toString() }.toSet()
            else -> setOf(value.toString())
        }
}
