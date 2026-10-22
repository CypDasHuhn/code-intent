package dev.cint.rulepacks.intents.mutation.rules

import dev.cint.api.DeclarationFact
import dev.cint.api.DeclarationKind
import dev.cint.api.GuardKind
import dev.cint.api.SyntaxNode
import dev.cint.api.View
import dev.cint.api.WriteFact
import dev.cint.rulepacks.bookkeeping.BOOKKEEPING_INTENT

private val LOOP_COUNTER_NAMES =
    setOf("i", "j", "k", "n", "idx", "index", "counter", "pos", "position")

internal fun declarationWrites(view: View): Map<Pair<Int, Int>, DeclarationFact> =
    view
        .declarations()
        .flatMap { declaration ->
            declaration.writes().map { write -> (write.node.startByte to write.node.endByte) to declaration }
        }.toMap()

internal fun bookkeepingSpans(view: View): Set<Pair<Int, Int>> =
    view
        .annotations(BOOKKEEPING_INTENT)
        .mapTo(mutableSetOf()) { it.startByte to it.endByte }

internal fun isBookkeeping(
    node: SyntaxNode,
    spans: Set<Pair<Int, Int>>,
): Boolean = (node.startByte to node.endByte) in spans

private val KOTLIN_MUTABLE_LOCAL = Regex("""(?:^|\s)var(?:\s|<|$)""")
private val JS_MUTABLE_DECLARATION = Regex("""^(?:export\s+)?(?:let|var)\b""")
private val JS_IMMUTABLE_DECLARATION = Regex("""^(?:export\s+)?const\b""")
private val CSHARP_IMMUTABLE_DECLARATION = Regex("""(?:^|\s)(?:const|readonly)\b""")

private val JS_DECLARATION_WRAPPERS =
    setOf("lexical_declaration", "variable_declaration", "export_statement")

private val CSHARP_DECLARATION_WRAPPERS =
    setOf("variable_declaration", "local_declaration_statement", "field_declaration")

internal fun mutableLocalDeclarationNode(
    declaration: DeclarationFact,
    language: String,
): SyntaxNode? {
    if (declaration.kind != DeclarationKind.VARIABLE) return null
    return when (language.lowercase()) {
        "kotlin" -> {
            if (KOTLIN_MUTABLE_LOCAL.containsMatchIn(declaration.node.text.trimStart())) declaration.node else null
        }

        "javascript", "typescript" -> {
            val wrapper = declarationWrapper(declaration.node, JS_DECLARATION_WRAPPERS) ?: return null
            val text = wrapper.text.trimStart()
            when {
                JS_IMMUTABLE_DECLARATION.containsMatchIn(text) -> null
                JS_MUTABLE_DECLARATION.containsMatchIn(text) -> declaration.node
                else -> null
            }
        }

        "csharp" -> {
            val wrapper = declarationWrapper(declaration.node, CSHARP_DECLARATION_WRAPPERS) ?: return null
            if (CSHARP_IMMUTABLE_DECLARATION.containsMatchIn(wrapper.text)) null else declaration.node
        }

        else -> {
            val text = declaration.node.text.trimStart()
            if (text.startsWith("val ") || text.startsWith("const ") || text.startsWith("readonly ")) null else declaration.node
        }
    }
}

private fun declarationWrapper(
    node: SyntaxNode,
    wrappers: Set<String>,
): SyntaxNode? {
    var current: SyntaxNode? = node
    while (current != null && current.kind !in wrappers) {
        current = current.parent
    }
    var wrapper = current ?: return null
    var parent = wrapper.parent
    while (parent != null && parent.kind in wrappers) {
        wrapper = parent
        parent = wrapper.parent
    }
    return wrapper
}

internal fun isLoopCounter(
    view: View,
    write: WriteFact,
): Boolean {
    val name = writeTarget(write) ?: return false
    if (name.lowercase() !in LOOP_COUNTER_NAMES) return false
    return view.guards().any { guard ->
        if (guard.kind != GuardKind.LOOP) return@any false
        val header = guard.node.text.trimStart()
        val forLoop = header.startsWith("for") || header.startsWith("foreach")
        forLoop &&
            write.node.startByte >= guard.node.startByte &&
            write.node.endByte <= guard.node.endByte
    }
}

private fun writeTarget(write: WriteFact): String? {
    write.reference?.text?.let { return it.substringAfterLast('.').trim() }
    val text =
        write.node.text
            .trim()
            .removePrefix("++")
            .removePrefix("--")
            .trim()
    return WRITE_TARGET.find(text)?.value?.substringAfterLast('.')
}

private val WRITE_TARGET = Regex("""^[A-Za-z_$][A-Za-z0-9_$]*(?:\.[A-Za-z_$][A-Za-z0-9_$]*)*""")
