package dev.cint.rulepacks.errorhandling

import dev.cint.api.CINT_API_VERSION
import dev.cint.api.CallFact
import dev.cint.api.ConfigurableRulePack
import dev.cint.api.Emit
import dev.cint.api.Intent
import dev.cint.api.Rule
import dev.cint.api.RulePack
import dev.cint.api.RulePackConfigException
import dev.cint.api.SyntaxNode
import dev.cint.api.View
import dev.cint.rulepacks.configBoolean
import dev.cint.rulepacks.configStrings

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
    ): ErrorHandlingConfig {
        val errors = mutableListOf<String>()
        val path = "${ErrorHandlingRulePack.PACK_ID}-${language.lowercase()}"
        var updated = this
        for ((rawKey, rawValue) in values) {
            val key = "$path.$rawKey"
            updated =
                when (rawKey) {
                    "calls" -> {
                        updated.copy(calls = configStrings(rawValue, key, errors) ?: updated.calls)
                    }

                    "qualifiers" -> {
                        updated.copy(qualifiers = configStrings(rawValue, key, errors) ?: updated.qualifiers)
                    }

                    "throws", "detectThrows" -> {
                        updated.copy(detectThrows = configBoolean(rawValue, key, errors) ?: updated.detectThrows)
                    }

                    else -> {
                        errors += "$key: unknown key"
                        updated
                    }
                }
        }
        if (errors.isNotEmpty()) throw RulePackConfigException(errors.joinToString("; "))
        return updated
    }

    companion object {
        val KOTLIN_CALLS: Set<String> =
            setOf("require", "requireNotNull", "check", "checkNotNull", "error", "TODO", "assert")
        val JAVASCRIPT_CALLS: Set<String> = setOf("assert", "invariant")
        val CSHARP_CALLS: Set<String> = setOf("Assert")
        val CSHARP_QUALIFIERS: Set<String> = setOf("Debug", "Trace", "Contract")

        val languages: List<String> = listOf("kotlin", "javascript", "typescript", "csharp")

        fun forLanguage(language: String): ErrorHandlingConfig =
            when (language.lowercase()) {
                "javascript", "typescript" -> ErrorHandlingConfig(calls = JAVASCRIPT_CALLS)
                "csharp" -> ErrorHandlingConfig(calls = CSHARP_CALLS, qualifiers = CSHARP_QUALIFIERS)
                else -> ErrorHandlingConfig()
            }

        fun from(
            values: Map<String, Any?>,
            language: String,
        ): ErrorHandlingConfig = forLanguage(language).withOverrides(values, language)
    }
}

class ErrorHandlingRulePack(
    language: String = "kotlin",
    private val config: ErrorHandlingConfig = ErrorHandlingConfig.forLanguage(language),
) : ConfigurableRulePack {
    val language: String = language.lowercase()

    override val id: String = "$PACK_ID-${this.language}"

    override val apiVersion: Int = CINT_API_VERSION

    override val languages: Set<String> = setOf(this.language)

    override fun configure(values: Map<String, Any?>): RulePack = ErrorHandlingRulePack(language, config.withOverrides(values, language))

    override fun rules(): List<Rule> = listOf(ErrorHandlingRule(config, "$id:error-handling"))

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
    override val id: String,
) : Rule {
    override val owns: Intent = ERROR_HANDLING_INTENT

    override val reads: Set<Intent> = emptySet()

    override fun run(view: View): List<Emit> {
        val calls = view.calls().filter(config::accepts).map { emit(it.site) }
        val throws = if (config.detectThrows) view.throws().map(::emit) else emptyList()
        return calls + throws
    }

    private fun emit(node: SyntaxNode): Emit =
        Emit(
            intents = setOf(owns),
            evidence = node.text.replace("\n", " ").take(160),
            span = node.span(),
            source = "treesitter",
            confidence = 1.0,
            startByte = node.startByte,
            endByte = node.endByte,
        )
}
