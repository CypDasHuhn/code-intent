package dev.cint.rulepacks.intents.structural

import dev.cint.api.CandidateEmit
import dev.cint.api.CandidateReason
import dev.cint.api.Emit
import dev.cint.api.Intent
import dev.cint.api.Matcher
import dev.cint.api.RuleEmit
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

internal fun isEstablishedLocalCopy(write: WriteFact): Boolean =
    LocalCopyMatch(LocalCopyLanguageConfig()).match(write) == CopyMatch.ESTABLISHED

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
    private val config: LocalCopyLanguageConfig,
) : Matcher {
    private val match = LocalCopyMatch(config)

    override fun match(view: View): List<RuleEmit> {
        val selected = linkedMapOf<Pair<Int, Int>, Pair<WriteFact, CopyMatch>>()
        for (write in view.writes()) {
            val result = match.match(write) ?: continue
            val key = write.node.startByte to write.node.endByte
            val previous = selected[key]
            if (previous == null || (previous.second == CopyMatch.CANDIDATE && result == CopyMatch.ESTABLISHED)) {
                selected[key] = write to result
            }
        }

        return selected.values.map { (write, result) ->
            when (result) {
                CopyMatch.ESTABLISHED -> {
                    Emit.of(
                        LOCAL_COPY_INTENT,
                        write.node,
                        source = HEURISTIC_SOURCE,
                        confidence = ESTABLISHED_CONFIDENCE,
                    )
                }

                CopyMatch.CANDIDATE -> {
                    CandidateEmit.of(
                        LOCAL_COPY_INTENT,
                        write.node,
                        CandidateReason.LOW_CONFIDENCE,
                        source = "rule:$LOCAL_COPY_RULE_ID",
                        confidence = CANDIDATE_CONFIDENCE,
                        aiEligible = true,
                    )
                }
            }
        }
    }
}

private class LocalCopyMatch(
    private val config: LocalCopyLanguageConfig,
) {
    fun match(write: WriteFact): CopyMatch? {
        val assignment = simpleAssignment(write) ?: return null
        val target = normalize(assignment.left)
        val value = normalize(assignment.right)
        if (target.isEmpty() || value.isEmpty()) return null

        val established = target == value && assignment.left != assignment.right
        return when {
            established -> CopyMatch.ESTABLISHED
            config.candidates && isFieldLike(assignment.left) -> CopyMatch.CANDIDATE
            else -> null
        }
    }

    private fun simpleAssignment(write: WriteFact): Assignment? {
        if (write.kind != WriteKind.ASSIGNMENT) return null
        val node = write.node
        if (assignmentOperator(node) != "=") return null
        val left = leftNode(node)?.text?.trim() ?: return null
        val right = rightNode(node)?.text?.trim() ?: return null
        if (!isSimpleTarget(left) || !SIMPLE_IDENTIFIER.matches(right)) return null
        return Assignment(left, right)
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
        return value.trimStart('_').lowercase()
    }
}
