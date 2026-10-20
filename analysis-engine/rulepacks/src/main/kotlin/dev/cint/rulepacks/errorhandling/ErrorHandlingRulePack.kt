package dev.cint.rulepacks.errorhandling

import dev.cint.api.CallFact
import dev.cint.api.ConfigField
import dev.cint.api.ConfigSchema
import dev.cint.api.Emit
import dev.cint.api.Intent
import dev.cint.api.LanguageConfig
import dev.cint.api.Matcher
import dev.cint.api.RulePack
import dev.cint.api.SingleIntentPack
import dev.cint.api.SyntaxNode
import dev.cint.api.View

const val ERROR_HANDLING_INTENT: Intent = "ERROR_HANDLING"

data class ErrorHandlingConfig(
    val calls: Set<String> = KOTLIN_CALLS,
    val qualifiers: Set<String> = emptySet(),
    val detectThrows: Boolean = true,
) {
    internal fun accepts(call: CallFact): Boolean {
        val callee = call.callee ?: return false
        if (callee !in calls) return false
        val receiver = call.receiver ?: return true
        return qualifiers.any { qualifier ->
            receiver.text == qualifier || receiver.text.endsWith(".$qualifier")
        }
    }

    fun withOverrides(
        values: Map<String, Any?>,
        language: String,
    ): ErrorHandlingConfig = SCHEMA.decode(this, values, "${ErrorHandlingRulePack.PACK_ID}-${language.lowercase()}")

    companion object {
        val KOTLIN_CALLS: Set<String> =
            setOf("require", "requireNotNull", "check", "checkNotNull", "error", "TODO", "assert")
        val JAVASCRIPT_CALLS: Set<String> = setOf("assert", "invariant")
        val CSHARP_CALLS: Set<String> = setOf("Assert")
        val CSHARP_QUALIFIERS: Set<String> = setOf("Debug", "Trace", "Contract")

        private val LANGUAGE_DEFAULTS: Map<String, ErrorHandlingConfig> =
            linkedMapOf(
                "kotlin" to ErrorHandlingConfig(),
                "javascript" to ErrorHandlingConfig(calls = JAVASCRIPT_CALLS),
                "typescript" to ErrorHandlingConfig(calls = JAVASCRIPT_CALLS),
                "csharp" to ErrorHandlingConfig(calls = CSHARP_CALLS, qualifiers = CSHARP_QUALIFIERS),
            )

        internal val SCHEMA =
            ConfigSchema.of(
                ConfigField.strings<ErrorHandlingConfig>("calls", { it.calls }, { config, value -> config.copy(calls = value) }),
                ConfigField.strings<ErrorHandlingConfig>(
                    "qualifiers",
                    { it.qualifiers },
                    { config, value -> config.copy(qualifiers = value) },
                ),
                ConfigField.boolean<ErrorHandlingConfig>(
                    "throws",
                    { it.detectThrows },
                    { config, value -> config.copy(detectThrows = value) },
                ),
                ConfigField.boolean<ErrorHandlingConfig>(
                    "detectThrows",
                    { it.detectThrows },
                    { config, value -> config.copy(detectThrows = value) },
                ),
            )

        val languages: List<String> = LANGUAGE_DEFAULTS.keys.toList()

        fun forLanguage(language: String): ErrorHandlingConfig =
            LanguageConfig(LANGUAGE_DEFAULTS).forLanguage(language) ?: ErrorHandlingConfig()

        fun from(
            values: Map<String, Any?>,
            language: String,
        ): ErrorHandlingConfig = forLanguage(language).withOverrides(values, language)
    }
}

class ErrorHandlingRulePack(
    language: String = "kotlin",
    config: ErrorHandlingConfig = ErrorHandlingConfig.forLanguage(language),
) : SingleIntentPack<ErrorHandlingConfig>(
        id = "$PACK_ID-${language.lowercase()}",
        intent = ERROR_HANDLING_INTENT,
        languages = setOf(language.lowercase()),
        config = config,
        schema = ErrorHandlingConfig.SCHEMA,
        ruleId = "$PACK_ID-${language.lowercase()}:error-handling",
    ) {
    val language: String = language.lowercase()

    override val matchers: List<Matcher> = listOf(Matcher { view -> ErrorHandlingRule(config).match(view) })

    override fun withConfig(config: ErrorHandlingConfig): SingleIntentPack<ErrorHandlingConfig> = ErrorHandlingRulePack(language, config)

    companion object {
        const val PACK_ID: String = "error-handling"

        val languages: List<String> = ErrorHandlingConfig.languages

        fun forLanguage(language: String): ErrorHandlingRulePack = ErrorHandlingRulePack(language)

        fun selection(language: String): List<RulePack> = listOf(forLanguage(language))

        fun all(): List<ErrorHandlingRulePack> = languages.map(::forLanguage)
    }
}

private class ErrorHandlingRule(
    private val config: ErrorHandlingConfig,
) : Matcher {
    override fun match(view: View): List<Emit> {
        val calls = view.calls().filter(config::accepts).map { emit(it.site) }
        val throws = if (config.detectThrows) view.throws().map(::emit) else emptyList()
        return calls + throws
    }

    private fun emit(node: SyntaxNode): Emit = Emit.of(ERROR_HANDLING_INTENT, node)
}
