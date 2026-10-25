package dev.cint.v2.mcp

import dev.cint.contracts.ports.HostChannelConfig
import dev.cint.contracts.ports.ProviderMode
import dev.cint.contracts.ports.SemanticProviderConfig
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path

internal data class McpProviderConfiguration(
    val providerCommands: Map<String, String> = emptyMap(),
    val providerConfigs: Map<String, SemanticProviderConfig> = emptyMap(),
)

internal object McpProviderConfigLoader {
    private val json =
        Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }

    fun load(path: String): McpProviderConfiguration {
        val file =
            try {
                Path.of(path)
            } catch (_: InvalidPathException) {
                throw IllegalArgumentException("invalid provider config path '$path'")
            }
        val text =
            try {
                Files.readString(file)
            } catch (error: Exception) {
                throw IllegalArgumentException(
                    "unable to read provider config '$path': ${error.message ?: error::class.simpleName}",
                )
            }
        val document =
            try {
                json.decodeFromString<ProviderConfigDocument>(text)
            } catch (error: Exception) {
                throw IllegalArgumentException(
                    "invalid provider config '$path': ${error.message ?: error::class.simpleName}",
                )
            }
        val configs = linkedMapOf<String, SemanticProviderConfig>()
        document.providers.forEach { (rawLanguage, entry) ->
            val language = normalizeLanguage(rawLanguage, "provider config language")
            configs[language] = entry.toConfig(language)
        }
        return McpProviderConfiguration(providerConfigs = configs)
    }

    fun resolve(
        file: McpProviderConfiguration,
        commands: Map<String, String>,
        modes: Map<String, ProviderMode>,
        endpoints: Map<String, String>,
    ): McpProviderConfiguration {
        val configs = linkedMapOf<String, SemanticProviderConfig>()
        configs.putAll(file.providerConfigs)
        val languages = (commands.keys + modes.keys + endpoints.keys).distinct()
        for (language in languages) {
            val existing = configs[language]
            val command = commands[language] ?: existing?.command
            val endpoint = endpoints[language] ?: existing?.endpoint
            val mode =
                modes[language]
                    ?: existing?.mode
                    ?: when {
                        endpoint != null -> ProviderMode.ATTACHED
                        command != null -> ProviderMode.MANAGED
                        else -> ProviderMode.MANAGED
                    }
            configs[language] =
                (existing ?: SemanticProviderConfig(mode = mode)).copy(
                    mode = mode,
                    command = command,
                    endpoint = endpoint,
                )
        }
        return McpProviderConfiguration(providerCommands = commands, providerConfigs = configs)
    }

    fun parseAssignment(
        raw: String,
        flag: String,
    ): Pair<String, String> {
        val separator = raw.indexOf('=')
        if (separator <= 0) throw IllegalArgumentException("$flag requires LANGUAGE=VALUE")
        val language = normalizeLanguage(raw.substring(0, separator), "$flag language")
        val value = raw.substring(separator + 1).trim()
        if (value.isEmpty()) throw IllegalArgumentException("$flag value must not be blank")
        return language to value
    }

    fun parseMode(
        raw: String,
        language: String,
    ): ProviderMode =
        when (raw.trim().lowercase()) {
            "managed" -> {
                ProviderMode.MANAGED
            }

            "attached" -> {
                ProviderMode.ATTACHED
            }

            "editor" -> {
                ProviderMode.EDITOR
            }

            else -> {
                throw IllegalArgumentException(
                    "unsupported provider mode '$raw' for language '$language'; expected managed, attached, or editor",
                )
            }
        }

    private fun normalizeLanguage(
        raw: String,
        label: String,
    ): String {
        val language = raw.trim().lowercase().removePrefix(".")
        if (language.isEmpty()) throw IllegalArgumentException("$label must not be blank")
        return language
    }

    private fun ProviderConfigEntry.toConfig(language: String): SemanticProviderConfig {
        val explicitMode = mode?.takeIf { it.isNotBlank() }?.let { parseMode(it, language) }
        val command = command?.trim()
        val endpoint = endpoint?.trim()
        val rootPath = rootPath?.trim()
        if (command != null && command.isEmpty()) {
            throw IllegalArgumentException("provider command for language '$language' must not be blank")
        }
        if (endpoint != null && endpoint.isEmpty()) {
            throw IllegalArgumentException("provider endpoint for language '$language' must not be blank")
        }
        if (rootPath != null && rootPath.isEmpty()) {
            throw IllegalArgumentException("provider rootPath for language '$language' must not be blank")
        }
        val resolvedMode =
            explicitMode
                ?: when {
                    command != null -> {
                        ProviderMode.MANAGED
                    }

                    endpoint != null -> {
                        ProviderMode.ATTACHED
                    }

                    hostChannelConfig != null -> {
                        ProviderMode.EDITOR
                    }

                    else -> {
                        throw IllegalArgumentException(
                            "provider config for language '$language' requires mode, command, endpoint, or hostChannelConfig",
                        )
                    }
                }
        timeoutMs?.let {
            if (it <= 0) throw IllegalArgumentException("provider timeoutMs for language '$language' must be positive")
        }
        initTimeoutMs?.let {
            if (it <= 0) {
                throw IllegalArgumentException("provider initTimeoutMs for language '$language' must be positive")
            }
        }
        if (languageOverrides.keys.any { it.isBlank() } || languageOverrides.values.any { it.isBlank() }) {
            throw IllegalArgumentException("provider languageOverrides for language '$language' must not contain blanks")
        }
        hostChannelConfig?.let {
            if (it.type.isBlank()) {
                throw IllegalArgumentException("provider hostChannelConfig.type for language '$language' must not be blank")
            }
        }
        val defaults = SemanticProviderConfig(mode = resolvedMode)
        return SemanticProviderConfig(
            mode = resolvedMode,
            command = command,
            endpoint = endpoint,
            rootPath = rootPath,
            hostChannelConfig = hostChannelConfig,
            languageOverrides = languageOverrides,
            timeoutMs = timeoutMs ?: defaults.timeoutMs,
            initTimeoutMs = initTimeoutMs ?: defaults.initTimeoutMs,
        )
    }
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
