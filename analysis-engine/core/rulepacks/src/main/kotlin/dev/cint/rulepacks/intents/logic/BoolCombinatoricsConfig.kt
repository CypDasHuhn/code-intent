package dev.cint.rulepacks.intents.logic

import dev.cint.api.ConfigField
import dev.cint.api.ConfigSchema
import dev.cint.api.LanguageConfig

data class BoolCombinatoricsLanguageConfig(
    val quantifierCalls: Set<String> = emptySet(),
    val predicateCalls: Set<String> = emptySet(),
    val predicatePrefixes: List<String> = emptyList(),
    val minimumOperators: Int = 2,
    val minimumPredicates: Int = 2,
)

data class BoolCombinatoricsConfig(
    val languages: Map<String, BoolCombinatoricsLanguageConfig> = defaults(),
) {
    fun forLanguage(language: String): BoolCombinatoricsLanguageConfig =
        LanguageConfig(languages).forLanguage(language, "kotlin")
            ?: BoolCombinatoricsLanguageConfig()

    fun withOverrides(values: Map<String, Any?>): BoolCombinatoricsConfig = SCHEMA.decode(this, values, BOOL_COMBINATORICS_PACK_ID)

    companion object {
        private fun decodeInt(
            value: Any?,
            path: String,
            errors: MutableList<String>,
        ): Int? =
            when (value) {
                is Int -> {
                    value
                }

                is Number -> {
                    value.toInt()
                }

                else -> {
                    errors += "$path: expected an integer"
                    null
                }
            }

        private val LANGUAGE_SCHEMA =
            ConfigSchema.of(
                ConfigField.strings<BoolCombinatoricsLanguageConfig>(
                    "quantifierCalls",
                    { it.quantifierCalls },
                    { config, value -> config.copy(quantifierCalls = value) },
                ),
                ConfigField.strings<BoolCombinatoricsLanguageConfig>(
                    "predicateCalls",
                    { it.predicateCalls },
                    { config, value -> config.copy(predicateCalls = value) },
                ),
                ConfigField.stringList<BoolCombinatoricsLanguageConfig>(
                    "predicatePrefixes",
                    { it.predicatePrefixes },
                    { config, value -> config.copy(predicatePrefixes = value) },
                ),
                ConfigField.custom<BoolCombinatoricsLanguageConfig, Int>(
                    "minimumOperators",
                    { it.minimumOperators },
                    { config, value -> config.copy(minimumOperators = value) },
                    ::decodeInt,
                ),
                ConfigField.custom<BoolCombinatoricsLanguageConfig, Int>(
                    "minimumPredicates",
                    { it.minimumPredicates },
                    { config, value -> config.copy(minimumPredicates = value) },
                    ::decodeInt,
                ),
            )

        internal val SCHEMA =
            LanguageConfig.schema<BoolCombinatoricsLanguageConfig, BoolCombinatoricsConfig>(
                languageSchema = LANGUAGE_SCHEMA,
                get = { it.languages },
                set = { config, languages -> config.copy(languages = languages) },
            )

        fun from(values: Map<String, Any?>): BoolCombinatoricsConfig = BoolCombinatoricsConfig().withOverrides(values)

        private val COMMON_PREDICATES =
            setOf(
                "isEmpty",
                "isNotEmpty",
                "isBlank",
                "isNotBlank",
                "isNullOrBlank",
                "isNullOrEmpty",
                "contains",
                "containsKey",
                "containsValue",
                "startsWith",
                "endsWith",
                "matches",
                "isValid",
                "isPresent",
                "isAbsent",
                "hasValue",
                "exists",
                "isDefined",
                "isUndefined",
                "isNull",
                "isNotNull",
            )

        private val KOTLIN =
            BoolCombinatoricsLanguageConfig(
                quantifierCalls = setOf("all", "any", "none"),
                predicateCalls = COMMON_PREDICATES,
                predicatePrefixes = listOf("is", "has", "can", "should"),
            )

        private val JAVASCRIPT =
            BoolCombinatoricsLanguageConfig(
                quantifierCalls = setOf("every", "some"),
                predicateCalls =
                    COMMON_PREDICATES +
                        setOf(
                            "includes",
                            "hasOwnProperty",
                            "isNaN",
                            "isFinite",
                        ),
                predicatePrefixes = listOf("is", "has", "can", "should"),
            )

        private val CSHARP =
            BoolCombinatoricsLanguageConfig(
                quantifierCalls = setOf("All", "Any", "TrueForAll"),
                predicateCalls =
                    setOf(
                        "Contains",
                        "ContainsKey",
                        "ContainsValue",
                        "StartsWith",
                        "EndsWith",
                        "IsNullOrEmpty",
                        "IsNullOrWhiteSpace",
                        "IsNaN",
                        "IsInfinity",
                        "Exists",
                    ),
                predicatePrefixes = listOf("Is", "Has", "Can", "Should"),
            )

        private val LANGUAGE_DEFAULTS: Map<String, BoolCombinatoricsLanguageConfig> =
            linkedMapOf(
                "kotlin" to KOTLIN,
                "javascript" to JAVASCRIPT,
                "typescript" to JAVASCRIPT,
                "csharp" to CSHARP,
            )

        fun defaults(): Map<String, BoolCombinatoricsLanguageConfig> = LANGUAGE_DEFAULTS
    }
}
