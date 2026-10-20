package dev.cint.rulepacks.validation

import dev.cint.api.CommentKind
import dev.cint.api.ConfigField
import dev.cint.api.ConfigSchema
import dev.cint.api.Emit
import dev.cint.api.GuardFact
import dev.cint.api.GuardKind
import dev.cint.api.Intent
import dev.cint.api.Matcher
import dev.cint.api.SingleIntentPack
import dev.cint.api.SyntaxNode
import dev.cint.api.View
import dev.cint.rulepacks.bookkeeping.BOOKKEEPING_INTENT

const val VALIDATION_INTENT: Intent = "VALIDATION"
const val VALIDATION_PACK_ID: String = "validation"
const val VALIDATION_RULE_ID: String = "$VALIDATION_PACK_ID:validation"

data class ValidationConfig(
    val predicates: Set<String> = DEFAULT_PREDICATES,
    val predicatePrefixes: List<String> = DEFAULT_PREDICATE_PREFIXES,
    val magicComment: String = "validation",
    val commentKinds: Set<String> = DEFAULT_COMMENT_KINDS,
) {
    fun withOverrides(values: Map<String, Any?>): ValidationConfig = SCHEMA.decode(this, values, VALIDATION_PACK_ID)

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
        val DEFAULT_COMMENT_KINDS: Set<String> = setOf("comment", "line_comment", "multiline_comment")

        internal val SCHEMA =
            ConfigSchema.of(
                ConfigField.strings<ValidationConfig>(
                    "predicates",
                    { it.predicates },
                    { config, value -> config.copy(predicates = value) },
                ),
                ConfigField.stringList<ValidationConfig>(
                    "predicatePrefixes",
                    { it.predicatePrefixes },
                    { config, value -> config.copy(predicatePrefixes = value) },
                ),
                ConfigField.string<ValidationConfig>(
                    "magicComment",
                    { it.magicComment },
                    { config, value -> config.copy(magicComment = value) },
                ),
                ConfigField.strings<ValidationConfig>(
                    "commentKinds",
                    { it.commentKinds },
                    { config, value -> config.copy(commentKinds = value) },
                ),
            )

        fun from(values: Map<String, Any?>): ValidationConfig = ValidationConfig().withOverrides(values)
    }
}

class ValidationRulePack(
    config: ValidationConfig = ValidationConfig(),
) : SingleIntentPack<ValidationConfig>(
        id = VALIDATION_PACK_ID,
        intent = VALIDATION_INTENT,
        languages = setOf("csharp", "javascript", "kotlin", "typescript"),
        config = config,
        schema = ValidationConfig.SCHEMA,
        ruleId = VALIDATION_RULE_ID,
    ) {
    override val reads: Set<Intent> = setOf(BOOKKEEPING_INTENT)

    override val matchers: List<Matcher> = listOf(Matcher { view -> ValidationRule(config).match(view) })

    override fun withConfig(config: ValidationConfig): SingleIntentPack<ValidationConfig> = ValidationRulePack(config)
}

private class ValidationRule(
    private val config: ValidationConfig,
) : Matcher {
    private val commentPattern =
        Regex("""cint\s*:\s*${Regex.escape(config.magicComment)}""", RegexOption.IGNORE_CASE)
    private val commentKinds = CommentKind.fromConfig(config.commentKinds)

    override fun match(view: View): List<Emit> =
        view
            .guards()
            .asSequence()
            .filter { it.kind == GuardKind.CONDITIONAL }
            .mapNotNull { guard -> emitFor(view, guard) }
            .toList()

    private fun emitFor(
        view: View,
        guard: GuardFact,
    ): Emit? {
        val condition = guard.condition ?: return null
        val body = guard.thenBody ?: return null

        val explicit = hasValidationComment(view, guard)
        val bookkeeping = hasBookkeeping(view, body)
        val predicate = isValidationPredicate(view, condition)
        val pure = view.isSideEffectFree(condition)
        val constant = view.isConstant(condition)
        val validationGuard =
            (explicit || bookkeeping || predicate || pure) &&
                !(constant && !explicit && !bookkeeping)
        if (!validationGuard) return null

        val confidence =
            when {
                explicit -> 1.0
                bookkeeping -> 0.9
                predicate -> 0.7
                else -> 0.5
            }
        val source = if (explicit) "comment" else Emit.DEFAULT_SOURCE
        return Emit.of(VALIDATION_INTENT, condition, source, confidence, certifiesReturn = true)
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
        guard: GuardFact,
    ): Boolean {
        val function = view.enclosingFunction(guard.node)
        val scope =
            function?.let { node ->
                view
                    .functions()
                    .firstOrNull {
                        it.node.startByte == node.startByte &&
                            it.node.endByte == node.endByte
                    }?.body ?: node
            }
        val comment =
            view
                .comments()
                .asSequence()
                .filter { it.kind in commentKinds }
                .filter { it.node.endByte <= guard.node.startByte }
                .filter { scope == null || it.node.startByte >= scope.startByte }
                .maxByOrNull { it.node.startByte } ?: return false
        if (guard.node.startLine - comment.node.endLine > 1) return false
        return commentPattern.containsMatchIn(comment.text)
    }
}
