package dev.cint.rulepacks.intents.structural

import dev.cint.api.CandidateEmit
import dev.cint.api.CandidateReason
import dev.cint.api.Emit
import dev.cint.api.Intent
import dev.cint.api.Matcher
import dev.cint.api.RuleEmit
import dev.cint.api.ScopeKind
import dev.cint.api.SingleIntentPack
import dev.cint.api.SyntaxNode
import dev.cint.api.View
import dev.cint.api.WriteFact
import dev.cint.api.WriteKind

const val LOCAL_COPY_INTENT: Intent = "LOCAL_COPY"
const val LOCAL_COPY_PACK_ID: String = "local-copy"
const val LOCAL_COPY_RULE_ID: String = "$LOCAL_COPY_PACK_ID:local-copy"

private const val ESTABLISHED_CONFIDENCE: Double = 0.8
private const val CANDIDATE_CONFIDENCE: Double = 0.4
private const val HEURISTIC_SOURCE: String = "heuristic"

private val SIMPLE_IDENTIFIER = Regex("""^[A-Za-z_$][A-Za-z0-9_$]*$""")
private val OWNER_QUALIFIED =
    Regex("""^(?:this(?:@[A-Za-z_][A-Za-z0-9_]*)?|self|Me|base|super)\.[A-Za-z_$][A-Za-z0-9_$]*$""")
private val ASSIGNMENT_OPERATORS = setOf("=", "+=", "-=", "*=", "/=", "%=", "?:=", "||=", "&&=")
private val IDENTIFIER = Regex("""[A-Za-z_$][A-Za-z0-9_$]*""")
private val LITERAL_IDENTIFIERS = setOf("null", "true", "false", "undefined", "nil", "None")

class LocalCopyRulePack(
    config: LocalCopyConfig = LocalCopyConfig(),
) : SingleIntentPack<LocalCopyConfig>(
        id = LOCAL_COPY_PACK_ID,
        intent = LOCAL_COPY_INTENT,
        languages = config.languages.keys.toSet(),
        config = config,
        schema = LocalCopyConfig.SCHEMA,
        ruleId = LOCAL_COPY_RULE_ID,
    ) {
    override val matchers: List<Matcher> =
        listOf(Matcher { view -> LocalCopyRule(config.forLanguage(view.language())).match(view) })

    override fun withConfig(config: LocalCopyConfig): SingleIntentPack<LocalCopyConfig> = LocalCopyRulePack(config)
}

private enum class CopyMatch {
    ESTABLISHED,
    CANDIDATE,
}

private data class Assignment(
    val left: String,
    val right: String,
)

private class LocalCopyRule(
    config: LocalCopyLanguageConfig,
) : Matcher {
    private val copy = LocalCopyMatch(config)

    override fun match(view: View): List<RuleEmit> {
        val selected = linkedMapOf<Pair<Int, Int>, Pair<SyntaxNode, CopyMatch>>()
        val declared = view.properties().mapNotNull { it.name }.toSet()
        val parameters = mutableMapOf<Pair<Int, Int>, Set<String>>()
        for (write in view.writes()) {
            val result = copy.assignment(write, declared, parameterNames(view, write.node, parameters)) ?: continue
            select(selected, write.node, result)
        }
        for ((node, name, _, initializer) in view.properties()) {
            val result =
                copy.declaration(
                    name,
                    initializer,
                    view.enclosingFunction(node) != null ||
                        view.enclosingScope(node)?.kind == ScopeKind.CLASS,
                    declared,
                    parameterNames(view, node, parameters),
                ) ?: continue
            select(selected, node, result)
        }
        return selected.values.map { (node, result) -> emit(node, result) }
    }

    private fun select(
        selected: MutableMap<Pair<Int, Int>, Pair<SyntaxNode, CopyMatch>>,
        node: SyntaxNode,
        result: CopyMatch,
    ) {
        val key = node.startByte to node.endByte
        val previous = selected[key]
        if (previous == null || (previous.second == CopyMatch.CANDIDATE && result == CopyMatch.ESTABLISHED)) {
            selected[key] = node to result
        }
    }

    private fun emit(
        node: SyntaxNode,
        result: CopyMatch,
    ): RuleEmit =
        when (result) {
            CopyMatch.ESTABLISHED -> {
                Emit.of(
                    LOCAL_COPY_INTENT,
                    node,
                    source = HEURISTIC_SOURCE,
                    confidence = ESTABLISHED_CONFIDENCE,
                )
            }

            CopyMatch.CANDIDATE -> {
                CandidateEmit.of(
                    LOCAL_COPY_INTENT,
                    node,
                    CandidateReason.LOW_CONFIDENCE,
                    source = "rule:$LOCAL_COPY_RULE_ID",
                    confidence = CANDIDATE_CONFIDENCE,
                    aiEligible = true,
                )
            }
        }
}

private class LocalCopyMatch(
    private val config: LocalCopyLanguageConfig,
) {
    fun assignment(
        write: WriteFact,
        declared: Set<String>,
        parameters: Set<String>,
    ): CopyMatch? {
        if (write.kind != WriteKind.ASSIGNMENT) return null
        val node = write.node
        if (assignmentOperator(node) != "=") return null
        val left = leftNode(node)?.text?.trim() ?: return null
        val right = rightNode(node)?.text?.trim() ?: return null
        if (!isSimpleTarget(left) || !isReferenceIdentifier(right)) return null
        return classify(left, right, declared, parameters)
    }

    fun declaration(
        name: String?,
        initializer: SyntaxNode?,
        insideFunction: Boolean,
        declared: Set<String>,
        parameters: Set<String>,
    ): CopyMatch? {
        if (!insideFunction || name == null || initializer == null) return null
        val right = initializer.text.trim()
        if (!isReferenceIdentifier(right) || name == right) return null
        return classify(name, right, declared, parameters)
    }

    private fun classify(
        left: String,
        right: String,
        declared: Set<String>,
        parameters: Set<String>,
    ): CopyMatch? {
        if (left == right) return null
        val target = normalize(left)
        val value = normalize(right)
        if (target.isEmpty() || value.isEmpty()) return null

        val declaredSource = right in declared && right !in parameters
        val established =
            isFieldLike(left) &&
                !isFieldLike(right) &&
                !declaredSource &&
                target == value
        return when {
            established -> CopyMatch.ESTABLISHED
            config.candidates -> CopyMatch.CANDIDATE
            else -> null
        }
    }

    private fun assignmentOperator(node: SyntaxNode): String? =
        node.children.firstOrNull { it.text in ASSIGNMENT_OPERATORS }?.text
            ?: ASSIGNMENT_OPERATORS.firstOrNull { it != "=" && it in node.text }
            ?: if ("=" in node.text) "=" else null

    private fun leftNode(node: SyntaxNode): SyntaxNode? = node.childByField("left") ?: node.children.firstOrNull()

    private fun rightNode(node: SyntaxNode): SyntaxNode? {
        node.childByField("right")?.let { return it }
        val operatorIndex = node.children.indexOfFirst { it.text in ASSIGNMENT_OPERATORS }
        if (operatorIndex < 0) return null
        return node.children
            .drop(operatorIndex + 1)
            .firstOrNull { child -> child.text != ";" && !child.kind.contains("comment") }
    }

    private fun isReferenceIdentifier(value: String): Boolean = SIMPLE_IDENTIFIER.matches(value) && value !in LITERAL_IDENTIFIERS

    private fun isSimpleTarget(target: String): Boolean = SIMPLE_IDENTIFIER.matches(target) || OWNER_QUALIFIED.matches(target)

    private fun isFieldLike(target: String): Boolean {
        if (OWNER_QUALIFIED.matches(target)) return true
        val name = target.substringAfterLast('.')
        return name.startsWith("_") || config.prefixes.any { prefix -> prefix.isNotEmpty() && name.startsWith(prefix) }
    }

    private fun normalize(name: String): String {
        var value = name.substringAfterLast('.').trim()
        value =
            config.prefixes
                .filter { it.isNotEmpty() }
                .firstOrNull { value.startsWith(it) }
                ?.let { value.removePrefix(it) }
                ?: value
        return value.trimStart('_')
    }
}

private fun parameterNames(
    view: View,
    node: SyntaxNode,
    cache: MutableMap<Pair<Int, Int>, Set<String>>,
): Set<String> {
    val function = view.enclosingFunction(node) ?: return emptySet()
    val key = function.startByte to function.endByte
    return cache.getOrPut(key) { parseParameterNames(function.text) }
}

private fun parseParameterNames(functionText: String): Set<String> {
    val open = functionText.indexOf('(')
    if (open < 0) return emptySet()
    var depth = 0
    var close = -1
    for (index in open until functionText.length) {
        if (functionText[index] == '(') {
            depth++
        } else if (functionText[index] == ')') {
            depth--
            if (depth == 0) {
                close = index
                break
            }
        }
    }
    if (close <= open + 1) return emptySet()
    return splitTopLevel(functionText.substring(open + 1, close))
        .mapNotNull(::parameterName)
        .toSet()
}

private fun splitTopLevel(parameters: String): List<String> {
    val result = mutableListOf<String>()
    var angle = 0
    var parens = 0
    var brackets = 0
    var braces = 0
    var start = 0
    for (index in parameters.indices) {
        val character = parameters[index]
        when (character) {
            '<' -> {
                angle++
            }

            '>' if angle > 0 -> {
                angle--
            }

            '(' -> {
                parens++
            }

            ')' if parens > 0 -> {
                parens--
            }

            '[' -> {
                brackets++
            }

            ']' if brackets > 0 -> {
                brackets--
            }

            '{' -> {
                braces++
            }

            '}' if braces > 0 -> {
                braces--
            }

            ',' if angle == 0 && parens == 0 && brackets == 0 && braces == 0 -> {
                result += parameters.substring(start, index).trim()
                start = index + 1
            }
        }
    }
    result += parameters.substring(start).trim()
    return result.filter { it.isNotEmpty() }
}

private fun parameterName(parameter: String): String? {
    var declaration = parameter.replace(Regex("""^\s*\[[^\]]*]\s*"""), "").trim()
    var braces = 0
    var parens = 0
    for (index in declaration.indices) {
        when (declaration[index]) {
            '(' -> {
                parens++
            }

            ')' -> {
                if (parens > 0) parens--
            }

            '{' -> {
                braces++
            }

            '}' -> {
                if (braces > 0) braces--
            }

            '=' -> {
                if (parens == 0 && braces == 0) {
                    declaration = declaration.substring(0, index).trim()
                    break
                }
            }
        }
    }
    val beforeType = declaration.substringBefore(':').trim()
    val source = beforeType.ifEmpty { declaration }
    return IDENTIFIER.findAll(source).lastOrNull()?.value
}
