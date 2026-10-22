package dev.cint.rulepacks.validation

import dev.cint.api.CandidateEmit
import dev.cint.api.CandidateReason
import dev.cint.api.CommentKind
import dev.cint.api.Emit
import dev.cint.api.GuardFact
import dev.cint.api.GuardKind
import dev.cint.api.Matcher
import dev.cint.api.RuleEmit
import dev.cint.api.SyntaxNode
import dev.cint.api.View
import dev.cint.rulepacks.bookkeeping.BOOKKEEPING_INTENT
import dev.cint.rulepacks.intents.mutation.DATA_MODIFY_INTENT
import dev.cint.rulepacks.intents.mutation.LOCAL_VALUE_MODIFY_INTENT

internal val VALIDATION_READS = setOf(DATA_MODIFY_INTENT, LOCAL_VALUE_MODIFY_INTENT, BOOKKEEPING_INTENT)

private val NESTED_SCOPE_KINDS =
    setOf(
        "function_declaration",
        "local_function_statement",
        "method_declaration",
        "constructor_declaration",
        "lambda_literal",
        "annotated_lambda",
        "lambda_expression",
        "arrow_function",
        "function_expression",
    )

private val LOOP_KINDS =
    setOf(
        "while_statement",
        "for_statement",
        "for_in_statement",
        "foreach_statement",
        "do_statement",
    )

private val SWITCH_KINDS =
    setOf(
        "switch_statement",
        "switch_expression",
        "when_expression",
    )

internal class ValidationRule(
    private val config: ValidationConfig,
) : Matcher {
    private val commentPattern =
        Regex("""cint\s*:\s*${Regex.escape(config.magicComment)}""", RegexOption.IGNORE_CASE)
    private val commentKinds = CommentKind.fromConfig(config.commentKinds)

    override fun match(view: View): List<RuleEmit> {
        val result = mutableListOf<RuleEmit>()
        for (guard in view.guards()) {
            if (guard.kind != GuardKind.CONDITIONAL) continue
            val established = emitFor(view, guard)
            if (established != null) {
                result += established
            } else {
                gapCandidate(view, guard)?.let { result += it }
            }
        }
        result += elvisEmits(view)
        return result.distinctBy { Triple(it.startByte, it.endByte, it.intents) }
    }

    private fun elvisEmits(view: View): List<Emit> {
        val root = rootNode(view) ?: return emptyList()
        return root
            .descendants()
            .filter { isElvis(it) }
            .mapNotNull { node ->
                val right = node.children.lastOrNull { child -> child.text !in coalesceTokens } ?: return@mapNotNull null
                if (!isRejectingExit(view, right)) return@mapNotNull null
                if (hasKnownWrites(view, node)) return@mapNotNull null
                Emit.of(VALIDATION_INTENT, node, confidence = 0.6)
            }.toList()
    }

    private fun isElvis(node: SyntaxNode): Boolean =
        node.kind in config.elvisKinds ||
            (node.kind == "binary_expression" && node.children.any { it.text in coalesceTokens })

    private val coalesceTokens: Set<String> get() = config.coalesceOperators + "?:"

    private fun hasKnownWrites(
        view: View,
        node: SyntaxNode,
    ): Boolean =
        view.writes().any { write ->
            write.node.startByte >= node.startByte && write.node.endByte <= node.endByte
        } ||
            VALIDATION_READS.any { intent ->
                view.annotations(intent).any { annotation ->
                    annotation.startByte >= node.startByte && annotation.endByte <= node.endByte
                }
            }

    private fun hasRejectingExit(
        view: View,
        body: SyntaxNode,
    ): Boolean =
        body.descendants().any { node ->
            if (insideNestedScope(node, body)) return@any false
            when {
                leadingKeyword(node) == "continue" -> !insideNestedJumpScope(node, body, includeSwitch = false)
                leadingKeyword(node) == "break" -> !insideNestedJumpScope(node, body, includeSwitch = true)
                else -> view.isExit(node) && isRejectingExit(view, node)
            }
        }

    private fun insideNestedScope(
        node: SyntaxNode,
        body: SyntaxNode,
    ): Boolean {
        if (sameSpan(node, body)) return false
        var current = node.parent
        while (current != null) {
            if (sameSpan(current, body)) return false
            if (current.kind in NESTED_SCOPE_KINDS) return true
            current = current.parent
        }
        return false
    }

    private fun insideNestedJumpScope(
        node: SyntaxNode,
        body: SyntaxNode,
        includeSwitch: Boolean,
    ): Boolean {
        if (sameSpan(node, body)) return false
        var current = node.parent
        while (current != null) {
            if (current.kind in LOOP_KINDS) return true
            if (includeSwitch && current.kind in SWITCH_KINDS) return true
            if (sameSpan(current, body)) return false
            current = current.parent
        }
        return false
    }

    private fun sameSpan(
        first: SyntaxNode,
        second: SyntaxNode,
    ): Boolean = first.startByte == second.startByte && first.endByte == second.endByte

    private fun leadingKeyword(node: SyntaxNode): String = node.text.trimStart().takeWhile { it.isLetter() }

    private fun isRejectingExit(
        view: View,
        node: SyntaxNode,
    ): Boolean {
        if (!view.isExit(node)) return false
        val text =
            node.text
                .trim()
                .removeSuffix(";")
                .trim()
        if (text.startsWith("continue") || text.startsWith("break")) return true
        if (!text.startsWith("return")) return false
        val expression = returnExpression(node) ?: return true
        return expression.text.trim() in config.rejectionValues
    }

    private fun returnExpression(node: SyntaxNode): SyntaxNode? {
        val children = node.children.filter { it.kind != ";" && it.kind !in config.commentKinds }
        val keyword = children.indexOfFirst { it.text.trimStart().startsWith("return") }
        if (keyword < 0) return children.lastOrNull()
        return children.drop(keyword + 1).firstOrNull()
    }

    private fun rootNode(view: View): SyntaxNode? {
        val seed =
            view.calls().firstOrNull()?.node
                ?: view.declarations().firstOrNull()?.node
                ?: view.functions().firstOrNull()?.node
                ?: view.properties().firstOrNull()?.node
                ?: view
                    .references()
                    .values
                    .firstOrNull { it.isNotEmpty() }
                    ?.first()
                ?: return null
        return generateSequence(seed) { it.parent }.last()
    }

    private fun gapCandidate(
        view: View,
        guard: GuardFact,
    ): CandidateEmit? {
        val condition = guard.condition ?: return null
        if (!validationComment(view, guard, maxDistanceLines = 3)) return null
        return CandidateEmit.of(
            VALIDATION_INTENT,
            condition,
            CandidateReason.GAP,
            source = "rule:$VALIDATION_RULE_ID",
            confidence = 0.4,
        )
    }

    private fun emitFor(
        view: View,
        guard: GuardFact,
    ): Emit? {
        val condition = guard.condition ?: return null
        val body = guard.thenBody ?: return null

        val explicit = hasValidationComment(view, guard)
        val bookkeeping = hasBookkeeping(view, body)
        val predicate = isValidationPredicate(view, condition)
        val rejection =
            hasRejectingExit(view, body) ||
                guard.elseBody?.let { hasRejectingExit(view, it) } == true
        val pure = view.isSideEffectFree(condition) && rejection
        val readOnly = !hasKnownWrites(view, condition) && rejection
        val constant = view.isConstant(condition)
        val validationGuard =
            (explicit || bookkeeping || (predicate && rejection) || pure || readOnly) &&
                !(constant && !explicit && !bookkeeping)
        if (!validationGuard) return null

        val confidence =
            when {
                explicit -> 1.0
                bookkeeping -> 0.9
                predicate -> 0.7
                pure -> 0.6
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
    ): Boolean = validationComment(view, guard, maxDistanceLines = 1)

    private fun validationComment(
        view: View,
        guard: GuardFact,
        maxDistanceLines: Int,
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
        if (guard.node.startLine - comment.node.endLine > maxDistanceLines) return false
        return commentPattern.containsMatchIn(comment.text)
    }
}
