package dev.cint.rulepacks.errorhandling

import dev.cint.api.CallFact
import dev.cint.api.Emit
import dev.cint.api.Intent
import dev.cint.api.Rule
import dev.cint.api.RulePack
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
    }
}

class ErrorHandlingRulePack(
    language: String = "kotlin",
    private val config: ErrorHandlingConfig = ErrorHandlingConfig.forLanguage(language),
) : RulePack {
    val language: String = language.lowercase()

    override val id: String = "$PACK_ID-${this.language}"

    override val languages: Set<String> = setOf(this.language)

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
