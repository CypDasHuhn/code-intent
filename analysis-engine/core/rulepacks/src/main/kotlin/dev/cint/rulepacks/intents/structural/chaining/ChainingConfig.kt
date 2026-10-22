package dev.cint.rulepacks.intents.structural.chaining

import dev.cint.api.ConfigField
import dev.cint.api.ConfigSchema
import dev.cint.api.LanguageConfig

data class ChainingLanguageConfig(
    val minimumLinks: Int = 2,
    val excludeBooleanChains: Boolean = true,
)

data class ChainingConfig(
    val languages: Map<String, ChainingLanguageConfig> = defaults(),
) {
    fun forLanguage(language: String): ChainingLanguageConfig =
        LanguageConfig(languages).forLanguage(language, "kotlin")
            ?: ChainingLanguageConfig()

    fun withOverrides(values: Map<String, Any?>): ChainingConfig = SCHEMA.decode(this, values, CHAINING_PACK_ID)

    companion object {
        private val LANGUAGE_SCHEMA =
            ConfigSchema.of(
                ConfigField.custom<ChainingLanguageConfig, Int>(
                    "minimumLinks",
                    { it.minimumLinks },
                    { config, value -> config.copy(minimumLinks = value) },
                    ::decodeMinimumLinks,
                ),
                ConfigField.custom<ChainingLanguageConfig, Int>(
                    "minLinks",
                    { it.minimumLinks },
                    { config, value -> config.copy(minimumLinks = value) },
                    ::decodeMinimumLinks,
                ),
                ConfigField.boolean<ChainingLanguageConfig>(
                    "excludeBooleanChains",
                    { it.excludeBooleanChains },
                    { config, value -> config.copy(excludeBooleanChains = value) },
                ),
            )

        internal val SCHEMA =
            LanguageConfig.schema<ChainingLanguageConfig, ChainingConfig>(
                languageSchema = LANGUAGE_SCHEMA,
                get = { it.languages },
                set = { config, languages -> config.copy(languages = languages) },
            )

        private val LANGUAGE_DEFAULTS: Map<String, ChainingLanguageConfig> =
            linkedMapOf(
                "kotlin" to ChainingLanguageConfig(),
                "javascript" to ChainingLanguageConfig(),
                "typescript" to ChainingLanguageConfig(),
                "csharp" to ChainingLanguageConfig(),
            )

        fun defaults(): Map<String, ChainingLanguageConfig> = LANGUAGE_DEFAULTS

        fun from(values: Map<String, Any?>): ChainingConfig = ChainingConfig().withOverrides(values)
    }
}

private fun decodeMinimumLinks(
    value: Any?,
    path: String,
    errors: MutableList<String>,
): Int? =
    when (value) {
        is Int -> {
            if (value >= 2) {
                value
            } else {
                errors += "$path: expected an integer of at least 2"
                null
            }
        }

        is Number -> {
            val number = value.toInt()
            if (number.toDouble() == value.toDouble() && number >= 2) {
                number
            } else {
                errors += "$path: expected an integer of at least 2"
                null
            }
        }

        else -> {
            errors += "$path: expected an integer of at least 2"
            null
        }
    }
