package dev.cint.contracts.facts

import dev.cint.contracts.ports.ProviderMode
import kotlinx.serialization.Serializable

@Serializable
data class LanguageDescriptor(
    val id: String,
    val name: String,
    val extensions: List<String>,
    val grammarName: String,
    val mimeTypes: List<String> = emptyList(),
    val config: Map<String, String> = emptyMap(),
    val providerMode: ProviderMode = ProviderMode.EDITOR,
    val rootMarkers: List<String> = emptyList(),
    val profile: String = id,
    val aliases: List<String> = emptyList(),
) {
    fun matchesPath(path: String): Boolean {
        val normalized = path.trim().lowercase()
        val extension = normalized.substringAfterLast('.', "")
        if (extension.isNotEmpty() && extensions.any { it.trim().lowercase().removePrefix(".") == extension }) {
            return true
        }
        return mimeTypes.any { it.equals(normalized, ignoreCase = true) }
    }
}
