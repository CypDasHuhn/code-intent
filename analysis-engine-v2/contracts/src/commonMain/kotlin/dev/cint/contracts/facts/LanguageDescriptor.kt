package dev.cint.contracts.facts

import kotlinx.serialization.Serializable

@Serializable
data class LanguageDescriptor(
    val id: String,
    val name: String,
    val extensions: List<String>,
    val grammarName: String,
    val mimeTypes: List<String> = emptyList(),
    val config: Map<String, String> = emptyMap(),
) {
    fun matchesPath(path: String): Boolean =
        extensions.any { path.endsWith(".$it") } ||
        mimeTypes.any { it == "text/$id" || it == "application/$id" }
}