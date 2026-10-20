package dev.cint.rulepacks.errorhandling

import dev.cint.api.CallFact
import dev.cint.api.ConfigField
import dev.cint.api.ConfigSchema
import dev.cint.api.Emit
import dev.cint.api.Intent
import dev.cint.api.LanguageConfig
import dev.cint.api.Matcher
import dev.cint.api.SingleIntentPack
import dev.cint.api.View
import dev.cint.api.emit

const val ERROR_HANDLING_INTENT: Intent = "ERROR_HANDLING"
const val ERROR_HANDLING_PACK_ID: String = "error-handling"
const val ERROR_HANDLING_RULE_ID: String = "$ERROR_HANDLING_PACK_ID:error-handling"

data class ErrorHandlingLanguageConfig(
    val calls: Set<String> = KOTLIN_CALLS,
    val qualifiers: Set<String> = emptySet(),
    val detectThrows: Boolean = true,
) {
    internal fun accepts(call: CallFact): Boolean {
        val path = call.path ?: return false
        val callee = path.substringAfterLast('.')
        if (callee !in calls) return false
        if (call.receiver == null) return true
        return qualifiers.any { qualifier ->
            path == "$qualifier.$callee" || path.endsWith(".$qualifier.$callee")
        }
    }

    fun withOverrides(
        values: Map<String, Any?>,
        language: String,
    ): ErrorHandlingLanguageConfig = SCHEMA.decode(this, values, "$ERROR_HANDLING_PACK_ID.${language.lowercase()}")

    companion object {
        val KOTLIN_CALLS: Set<String> =
            setOf("require", "requireNotNull", "check", "checkNotNull", "error", "TODO", "assert")
        val JAVASCRIPT_CALLS: Set<String> = setOf("assert", "invariant")
        val CSHARP_CALLS: Set<String> = setOf("Assert")
        val CSHARP_QUALIFIERS: Set<String> = setOf("Debug", "Trace", "Contract")

        private val LANGUAGE_DEFAULTS: Map<String, ErrorHandlingLanguageConfig> =
            linkedMapOf(
                "kotlin" to ErrorHandlingLanguageConfig(),
                "javascript" to ErrorHandlingLanguageConfig(calls = JAVASCRIPT_CALLS),
                "typescript" to ErrorHandlingLanguageConfig(calls = JAVASCRIPT_CALLS),
                "csharp" to ErrorHandlingLanguageConfig(calls = CSHARP_CALLS, qualifiers = CSHARP_QUALIFIERS),
            )

        internal val SCHEMA =
            ConfigSchema.of(
                ConfigField.strings<ErrorHandlingLanguageConfig>(
                    "calls",
                    { it.calls },
                    { config, value -> config.copy(calls = value) },
                ),
                ConfigField.strings<ErrorHandlingLanguageConfig>(
                    "qualifiers",
                    { it.qualifiers },
                    { config, value -> config.copy(qualifiers = value) },
                ),
                ConfigField.boolean<ErrorHandlingLanguageConfig>(
                    "throws",
                    { it.detectThrows },
                    { config, value -> config.copy(detectThrows = value) },
                ),
                ConfigField.boolean<ErrorHandlingLanguageConfig>(
                    "detectThrows",
                    { it.detectThrows },
                    { config, value -> config.copy(detectThrows = value) },
                ),
            )

        fun defaults(): Map<String, ErrorHandlingLanguageConfig> = LANGUAGE_DEFAULTS
    }
}

data class ErrorHandlingConfig(
    val languages: Map<String, ErrorHandlingLanguageConfig> = ErrorHandlingLanguageConfig.defaults(),
) {
    fun forLanguage(language: String): ErrorHandlingLanguageConfig =
        LanguageConfig(languages).forLanguage(language, "kotlin") ?: ErrorHandlingLanguageConfig()

    fun withOverrides(values: Map<String, Any?>): ErrorHandlingConfig = SCHEMA.decode(this, values, ERROR_HANDLING_PACK_ID)

    companion object {
        internal val SCHEMA =
            LanguageConfig.schema<ErrorHandlingLanguageConfig, ErrorHandlingConfig>(
                languageSchema = ErrorHandlingLanguageConfig.SCHEMA,
                get = { it.languages },
                set = { config, languages -> config.copy(languages = languages) },
            )

        val languages: Set<String> = ErrorHandlingLanguageConfig.defaults().keys

        fun from(values: Map<String, Any?>): ErrorHandlingConfig = ErrorHandlingConfig().withOverrides(values)
    }
}

class ErrorHandlingRulePack(
    config: ErrorHandlingConfig = ErrorHandlingConfig(),
) : SingleIntentPack<ErrorHandlingConfig>(
        id = ERROR_HANDLING_PACK_ID,
        intent = ERROR_HANDLING_INTENT,
        languages = config.languages.keys,
        config = config,
        schema = ErrorHandlingConfig.SCHEMA,
        ruleId = ERROR_HANDLING_RULE_ID,
    ) {
    override val matchers: List<Matcher> = listOf(Matcher { view -> ErrorHandlingRule(config.forLanguage(view.language())).match(view) })

    override fun withConfig(config: ErrorHandlingConfig): SingleIntentPack<ErrorHandlingConfig> = ErrorHandlingRulePack(config)
}

private class ErrorHandlingRule(
    private val config: ErrorHandlingLanguageConfig,
) : Matcher {
    override fun match(view: View): List<Emit> {
        val calls = view.calls().filter(config::accepts).map { it.emit(ERROR_HANDLING_INTENT) }
        val throws =
            if (config.detectThrows) {
                view.throws().map { Emit.of(ERROR_HANDLING_INTENT, it) }
            } else {
                emptyList()
            }
        return calls + throws
    }
}
