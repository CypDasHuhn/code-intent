package dev.cint.rulepacks.intents.structural

import dev.cint.api.ConfigField
import dev.cint.api.ConfigSchema
import dev.cint.api.LanguageConfig

private val DEFAULT_PREFIXES = listOf("m_", "s_", "f_")

data class LocalCopyLanguageConfig(
    val prefixes: List<String> = DEFAULT_PREFIXES,
    val candidates: Boolean = true,
)

data class LocalCopyConfig(
    val languages: Map<String, LocalCopyLanguageConfig> = defaults(),
) {
    fun forLanguage(language: String): LocalCopyLanguageConfig =
        LanguageConfig(languages).forLanguage(language, "kotlin")
            ?: LocalCopyLanguageConfig()

    fun withOverrides(values: Map<String, Any?>): LocalCopyConfig = SCHEMA.decode(this, values, LOCAL_COPY_PACK_ID)

    companion object {
        private val LANGUAGE_SCHEMA =
            ConfigSchema.of(
                ConfigField.stringList<LocalCopyLanguageConfig>(
                    "prefixes",
                    { it.prefixes },
                    { config, value -> config.copy(prefixes = value) },
                ),
                ConfigField.stringList<LocalCopyLanguageConfig>(
                    "namePrefixes",
                    { it.prefixes },
                    { config, value -> config.copy(prefixes = value) },
                ),
                ConfigField.boolean<LocalCopyLanguageConfig>(
                    "candidates",
                    { it.candidates },
                    { config, value -> config.copy(candidates = value) },
                ),
                ConfigField.boolean<LocalCopyLanguageConfig>(
                    "includeCandidates",
                    { it.candidates },
                    { config, value -> config.copy(candidates = value) },
                ),
            )

        internal val SCHEMA =
            LanguageConfig.schema<LocalCopyLanguageConfig, LocalCopyConfig>(
                languageSchema = LANGUAGE_SCHEMA,
                get = { it.languages },
                set = { config, languages -> config.copy(languages = languages) },
            )

        private val LANGUAGE_DEFAULTS: Map<String, LocalCopyLanguageConfig> =
            linkedMapOf(
                "kotlin" to LocalCopyLanguageConfig(),
                "javascript" to LocalCopyLanguageConfig(),
                "typescript" to LocalCopyLanguageConfig(),
                "csharp" to LocalCopyLanguageConfig(),
            )

        fun defaults(): Map<String, LocalCopyLanguageConfig> = LANGUAGE_DEFAULTS

        fun from(values: Map<String, Any?>): LocalCopyConfig = LocalCopyConfig().withOverrides(values)
    }
}
