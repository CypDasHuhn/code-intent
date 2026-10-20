package dev.cint.rulepacks.validation

import dev.cint.api.CINT_API_VERSION
import dev.cint.api.ConfigurableRulePack
import dev.cint.api.Emit
import dev.cint.api.Intent
import dev.cint.api.Rule
import dev.cint.api.RulePack
import dev.cint.api.RulePackConfigException
import dev.cint.api.SyntaxNode
import dev.cint.api.View
import dev.cint.rulepacks.bookkeeping.BOOKKEEPING_INTENT
import dev.cint.rulepacks.support.configString
import dev.cint.rulepacks.support.configStringList
import dev.cint.rulepacks.support.configStrings

const val VALIDATION_INTENT: Intent = "VALIDATION"
const val VALIDATION_PACK_ID: String = "validation"
const val VALIDATION_RULE_ID: String = "$VALIDATION_PACK_ID:validation"

data class ValidationConfig(
    val predicates: Set<String> = DEFAULT_PREDICATES,
    val predicatePrefixes: List<String> = DEFAULT_PREDICATE_PREFIXES,
    val magicComment: String = "validation",
    val commentKinds: Set<String> = setOf("comment", "line_comment", "multiline_comment"),
) {
    fun withOverrides(values: Map<String, Any?>): ValidationConfig {
        val errors = mutableListOf<String>()
        var updated = this
        for ((rawKey, rawValue) in values) {
            val path = "$VALIDATION_PACK_ID.$rawKey"
            updated =
                when (rawKey) {
                    "predicates" -> {
                        updated.copy(predicates = configStrings(rawValue, path, errors) ?: updated.predicates)
                    }

                    "predicatePrefixes" -> {
                        updated.copy(
                            predicatePrefixes =
                                configStringList(rawValue, path, errors) ?: updated.predicatePrefixes,
                        )
                    }

                    "magicComment" -> {
                        updated.copy(magicComment = configString(rawValue, path, errors) ?: updated.magicComment)
                    }

                    "commentKinds" -> {
                        updated.copy(commentKinds = configStrings(rawValue, path, errors) ?: updated.commentKinds)
                    }

                    else -> {
                        errors += "$path: unknown key"
                        updated
                    }
                }
        }
        if (errors.isNotEmpty()) throw RulePackConfigException(errors.joinToString("; "))
        return updated
    }

    companion object {
        val DEFAULT_PREDICATES: Set<String> =
            setOf(
                "none",
                "any",
                "all",
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
            )
        val DEFAULT_PREDICATE_PREFIXES: List<String> = listOf("is", "has", "Is", "Has")

        fun from(values: Map<String, Any?>): ValidationConfig = ValidationConfig().withOverrides(values)
    }
}

class ValidationRulePack(
    private val config: ValidationConfig = ValidationConfig(),
) : ConfigurableRulePack {
    override val id: String = VALIDATION_PACK_ID
    override val apiVersion: Int = CINT_API_VERSION
    override val languages: Set<String> = setOf("csharp", "javascript", "kotlin", "typescript")

    override fun configure(values: Map<String, Any?>): RulePack = ValidationRulePack(config.withOverrides(values))

    override fun rules(): List<Rule> = listOf(ValidationRule(config))
}

private class ValidationRule(
    private val config: ValidationConfig,
) : Rule {
    override val id: String = VALIDATION_RULE_ID
    override val owns: Intent = VALIDATION_INTENT
    override val reads: Set<Intent> = setOf(BOOKKEEPING_INTENT)

    private val commentPattern =
        Regex("""cint\s*:\s*${Regex.escape(config.magicComment)}""", RegexOption.IGNORE_CASE)

    override fun run(view: View): List<Emit> {
        val result = mutableListOf<Emit>()
        for ((node, condition1, thenBody) in view.ifs()) {
            val condition = condition1 ?: continue
            val body = thenBody ?: continue

            val explicit = hasValidationComment(view, node)
            val bookkeeping = hasBookkeeping(view, body)
            val predicate = isValidationPredicate(view, condition)
            val pure = view.isSideEffectFree(condition)
            val constant = view.isConstant(condition)
            val validationGuard =
                (explicit || bookkeeping || predicate || pure) &&
                    !(constant && !explicit && !bookkeeping)
            if (!validationGuard) continue

            val confidence =
                when {
                    explicit -> 1.0
                    bookkeeping -> 0.9
                    predicate -> 0.7
                    else -> 0.5
                }
            val source = if (explicit) "comment" else "treesitter"
            result += emit(condition, source, confidence)
        }
        return result
    }

    private fun hasBookkeeping(
        view: View,
        body: SyntaxNode,
    ): Boolean =
        view.annotations(BOOKKEEPING_INTENT).any { annotation ->
            annotation.startByte >= body.startByte && annotation.endByte <= body.endByte
        }

    private fun isValidationPredicate(
        view: View,
        condition: SyntaxNode,
    ): Boolean =
        view
            .calls()
            .filter {
                it.node.startByte >= condition.startByte &&
                    it.node.endByte <= condition.endByte
            }.any { call ->
                val name = call.callee ?: return@any false
                name in config.predicates || config.predicatePrefixes.any { name.startsWith(it) }
            }

    private fun hasValidationComment(
        view: View,
        guard: SyntaxNode,
    ): Boolean {
        val comment =
            commentScope(view, guard)
                .descendants()
                .filter { it.endByte <= guard.startByte && it.kind in config.commentKinds }
                .maxByOrNull { it.startByte } ?: return false
        if (guard.startLine - comment.endLine > 1) return false
        return commentPattern.containsMatchIn(comment.text.lowercase())
    }

    private fun commentScope(
        view: View,
        guard: SyntaxNode,
    ): SyntaxNode {
        val function = view.enclosingFunction(guard)
        if (function != null) {
            val body =
                view
                    .functions()
                    .firstOrNull {
                        it.node.startByte == function.startByte &&
                            it.node.endByte == function.endByte
                    }?.body
            return body ?: function
        }
        var root = guard
        while (true) {
            root = root.parent ?: return root
        }
    }

    private fun emit(
        node: SyntaxNode,
        source: String,
        confidence: Double,
    ): Emit =
        Emit(
            intents = setOf(owns),
            evidence = node.text.replace("\n", " ").take(160),
            span = node.span(),
            source = source,
            confidence = confidence,
            startByte = node.startByte,
            endByte = node.endByte,
            certifiesReturn = true,
        )
}
