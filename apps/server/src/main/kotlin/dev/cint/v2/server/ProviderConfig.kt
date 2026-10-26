package dev.cint.v2.server

import dev.cint.contracts.ports.HostChannelConfig
import dev.cint.contracts.ports.ProviderMode
import dev.cint.contracts.ports.SemanticProviderConfig
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path

private val providerConfigJson =
    Json {
        explicitNulls = false
        ignoreUnknownKeys = true
    }

internal fun loadProviderConfigs(root: Path): Map<String, SemanticProviderConfig> {
    val path = root.resolve(".cint/providers.json")
    if (!Files.isRegularFile(path)) return emptyMap()
    val document = providerConfigJson.decodeFromString<ProviderConfigDocument>(Files.readString(path))
    return document.providers.map { (rawLanguage, entry) ->
        val language = rawLanguage.trim().lowercase()
        require(language.isNotEmpty()) { "provider config language must not be blank" }
        language to entry.toConfig(language, root)
    }.toMap()
}

private fun ProviderConfigEntry.toConfig(
    language: String,
    root: Path,
): SemanticProviderConfig {
    val normalizedCommand = command?.trim()?.takeIf { it.isNotEmpty() }
    val normalizedEndpoint = endpoint?.trim()?.takeIf { it.isNotEmpty() }
    val normalizedRoot =
        rootPath
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { raw ->
                val path = Path.of(raw)
                if (path.isAbsolute) path.normalize().toString() else root.resolve(path).normalize().toString()
            }
    val resolvedMode =
        mode
            ?.trim()
            ?.lowercase()
            ?.let { value ->
                when (value) {
                    "managed" -> ProviderMode.MANAGED
                    "attached" -> ProviderMode.ATTACHED
                    "editor" -> ProviderMode.EDITOR
                    else -> error("unsupported provider mode '$value' for language '$language'")
                }
            }
            ?: when {
                normalizedCommand != null -> ProviderMode.MANAGED
                normalizedEndpoint != null -> ProviderMode.ATTACHED
                hostChannelConfig != null -> ProviderMode.EDITOR
                else -> error("provider config for language '$language' has no provider mode")
            }
    return SemanticProviderConfig(
        mode = resolvedMode,
        command = normalizedCommand,
        endpoint = normalizedEndpoint,
        rootPath = normalizedRoot,
        hostChannelConfig = hostChannelConfig,
        languageOverrides = languageOverrides,
        timeoutMs = timeoutMs ?: 5_000,
        initTimeoutMs = initTimeoutMs ?: 30_000,
    )
}

@Serializable
private data class ProviderConfigDocument(
    val providers: Map<String, ProviderConfigEntry> = emptyMap(),
)

@Serializable
private data class ProviderConfigEntry(
    val mode: String? = null,
    val command: String? = null,
    val endpoint: String? = null,
    val rootPath: String? = null,
    val timeoutMs: Long? = null,
    val initTimeoutMs: Long? = null,
    val languageOverrides: Map<String, String> = emptyMap(),
    val hostChannelConfig: HostChannelConfig? = null,
)
