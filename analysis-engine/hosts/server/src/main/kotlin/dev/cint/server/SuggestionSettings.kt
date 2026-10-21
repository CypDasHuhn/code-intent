package dev.cint.server

import org.yaml.snakeyaml.Yaml
import java.nio.file.Files
import java.nio.file.Path

data class SuggestionSettings(
    val enabled: Boolean = false,
    val trigger: String = "manual",
    val provider: String? = null,
    val model: String? = null,
    val maxCandidates: Int = 5,
    val promptVersion: String? = null,
    val timeoutMs: Long = 4_000L,
    val workerThreads: Int = 2,
)

object SuggestionSettingsLoader {
    fun load(root: Path): SuggestionSettings {
        val path = root.resolve(".cint").resolve("suggestions.yml")
        if (!Files.isRegularFile(path)) return SuggestionSettings()
        val parsed = runCatching { Yaml().load<Any?>(Files.readString(path)) }.getOrNull()
        val suggestions = (parsed as? Map<*, *>)?.get("suggestions") as? Map<*, *> ?: return SuggestionSettings()
        return SuggestionSettings(
            enabled = suggestions["enabled"] as? Boolean ?: false,
            trigger = (suggestions["trigger"] as? String)?.takeIf { it.isNotBlank() } ?: "manual",
            provider = (suggestions["provider"] as? String)?.takeIf { it.isNotBlank() },
            model = (suggestions["model"] as? String)?.takeIf { it.isNotBlank() },
            maxCandidates = (suggestions["maxCandidates"] as? Number)?.toInt()?.coerceAtLeast(1) ?: 5,
            promptVersion = (suggestions["promptVersion"] as? String)?.takeIf { it.isNotBlank() },
            timeoutMs = (suggestions["timeoutMs"] as? Number)?.toLong()?.coerceAtLeast(1L) ?: 4_000L,
            workerThreads = (suggestions["workerThreads"] as? Number)?.toInt()?.coerceIn(1, 8) ?: 2,
        )
    }
}
