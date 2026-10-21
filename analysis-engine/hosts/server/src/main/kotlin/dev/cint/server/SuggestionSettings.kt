package dev.cint.server

import dev.cint.suggest.DEEPSEEK_API_KEY_ENV
import dev.cint.suggest.DEEPSEEK_BASE_URL
import dev.cint.suggest.DEEPSEEK_MAX_CONFIDENCE
import dev.cint.suggest.DEEPSEEK_MAX_TOKENS
import dev.cint.suggest.DEEPSEEK_MODEL_ID
import dev.cint.suggest.DEEPSEEK_PROVIDER_ID
import dev.cint.suggest.FAKE_MODEL_ID
import dev.cint.suggest.FAKE_PROVIDER_ID
import dev.cint.suggest.SourceExcerpt
import org.yaml.snakeyaml.Yaml
import java.nio.file.Files
import java.nio.file.Path

data class SuggestionSettings(
    val enabled: Boolean = false,
    val trigger: String = "manual",
    val provider: String? = null,
    val model: String? = null,
    val maxCandidates: Int = 5,
    val maxEnrichmentCandidates: Int = 5,
    val promptVersion: String? = null,
    val timeoutMs: Long = 10_000L,
    val workerThreads: Int = 2,
    val apiKeyEnv: String = DEEPSEEK_API_KEY_ENV,
    val baseUrl: String = DEEPSEEK_BASE_URL,
    val maxTokens: Int = DEEPSEEK_MAX_TOKENS,
    val maxConfidence: Double = DEEPSEEK_MAX_CONFIDENCE,
    val excerptRadiusBytes: Int = SourceExcerpt.DEFAULT_RADIUS_BYTES,
    val allowGlobs: List<String> = emptyList(),
    val redactPatterns: List<String> = emptyList(),
) {
    val effectiveProvider: String? get() = provider ?: if (enabled) DEEPSEEK_PROVIDER_ID else null

    val effectiveModel: String?
        get() =
            model ?: when (effectiveProvider) {
                DEEPSEEK_PROVIDER_ID -> DEEPSEEK_MODEL_ID
                FAKE_PROVIDER_ID -> FAKE_MODEL_ID
                else -> null
            }
}

object SuggestionSettingsLoader {
    fun load(root: Path): SuggestionSettings {
        val path = root.resolve(".cint").resolve("suggestions.yml")
        if (!Files.isRegularFile(path)) return SuggestionSettings()
        val parsed = runCatching { Yaml().load<Any?>(Files.readString(path)) }.getOrNull()
        val suggestions = (parsed as? Map<*, *>)?.get("suggestions") as? Map<*, *> ?: return SuggestionSettings()
        val enabled = suggestions["enabled"] as? Boolean ?: false
        return SuggestionSettings(
            enabled = enabled,
            trigger = (suggestions["trigger"] as? String)?.takeIf { it.isNotBlank() } ?: "manual",
            provider =
                (suggestions["provider"] as? String)?.takeIf { it.isNotBlank() }
                    ?: if (enabled) DEEPSEEK_PROVIDER_ID else null,
            model = (suggestions["model"] as? String)?.takeIf { it.isNotBlank() },
            maxCandidates = (suggestions["maxCandidates"] as? Number)?.toInt()?.coerceAtLeast(1) ?: 5,
            maxEnrichmentCandidates =
                (suggestions["maxEnrichmentCandidates"] as? Number)?.toInt()?.coerceAtLeast(0) ?: 5,
            promptVersion = (suggestions["promptVersion"] as? String)?.takeIf { it.isNotBlank() },
            timeoutMs = (suggestions["timeoutMs"] as? Number)?.toLong()?.coerceAtLeast(1L) ?: 10_000L,
            workerThreads = (suggestions["workerThreads"] as? Number)?.toInt()?.coerceIn(1, 8) ?: 2,
            apiKeyEnv = (suggestions["apiKeyEnv"] as? String)?.takeIf { it.isNotBlank() } ?: DEEPSEEK_API_KEY_ENV,
            baseUrl = (suggestions["baseUrl"] as? String)?.takeIf { it.isNotBlank() } ?: DEEPSEEK_BASE_URL,
            maxTokens = (suggestions["maxTokens"] as? Number)?.toInt()?.coerceAtLeast(1) ?: DEEPSEEK_MAX_TOKENS,
            maxConfidence =
                (suggestions["maxConfidence"] as? Number)?.toDouble()?.coerceIn(0.0, DEEPSEEK_MAX_CONFIDENCE)
                    ?: DEEPSEEK_MAX_CONFIDENCE,
            excerptRadiusBytes =
                (suggestions["excerptRadiusBytes"] as? Number)?.toInt()?.coerceAtLeast(0)
                    ?: SourceExcerpt.DEFAULT_RADIUS_BYTES,
            allowGlobs = stringList(suggestions["allowGlobs"]),
            redactPatterns = stringList(suggestions["redactPatterns"]),
        )
    }

    private fun stringList(value: Any?): List<String> =
        when (value) {
            is List<*> -> value.mapNotNull { (it as? String)?.takeIf(String::isNotBlank) }
            is String -> listOf(value).filter(String::isNotBlank)
            else -> emptyList()
        }
}
