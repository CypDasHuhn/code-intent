package dev.cint.rulepacks.intents.structural

import dev.cint.api.ConfigField
import dev.cint.api.ConfigSchema
import dev.cint.api.LanguageConfig

data class ReturnValueLanguageConfig(
    val includeLiterals: Boolean = false,
)

data class ReturnValueConfig(
    val languages: Map<String, ReturnValueLanguageConfig> = defaults(),
) {
    fun forLanguage(language: String): ReturnValueLanguageConfig =
        LanguageConfig(languages).forLanguage(language, "kotlin")
            ?: ReturnValueLanguageConfig()

    fun withOverrides(values: Map<String, Any?>): ReturnValueConfig = SCHEMA.decode(this, values, RETURN_VALUE_PACK_ID)

    companion object {
        private val LANGUAGE_SCHEMA =
            ConfigSchema.of(
                ConfigField.boolean<ReturnValueLanguageConfig>(
                    "includeLiterals",
                    { it.includeLiterals },
                    { config, value -> config.copy(includeLiterals = value) },
                ),
                ConfigField.boolean<ReturnValueLanguageConfig>(
                    "allowLiterals",
                    { it.includeLiterals },
                    { config, value -> config.copy(includeLiterals = value) },
                ),
            )

        internal val SCHEMA =
            LanguageConfig.schema<ReturnValueLanguageConfig, ReturnValueConfig>(
                languageSchema = LANGUAGE_SCHEMA,
                get = { it.languages },
                set = { config, languages -> config.copy(languages = languages) },
            )

        private val LANGUAGE_DEFAULTS: Map<String, ReturnValueLanguageConfig> =
            linkedMapOf(
                "kotlin" to ReturnValueLanguageConfig(),
                "javascript" to ReturnValueLanguageConfig(),
                "typescript" to ReturnValueLanguageConfig(),
                "csharp" to ReturnValueLanguageConfig(),
            )

        fun defaults(): Map<String, ReturnValueLanguageConfig> = LANGUAGE_DEFAULTS

        fun from(values: Map<String, Any?>): ReturnValueConfig = ReturnValueConfig().withOverrides(values)
    }
}
