package dev.cint.v2.syntax.treesitter

import dev.cint.contracts.facts.SyntaxTree
import dev.cint.contracts.util.ContentHash

internal fun syntaxFingerprint(tree: SyntaxTree): String {
    val canonical = StringBuilder()
    fun walk(nodeId: String, depth: Int) {
        val node = tree.getNode(nodeId) ?: return
        canonical.append(depth).append('|')
            .append(node.kind).append('|')
            .append(node.startByte).append('|')
            .append(node.endByte).append('|')
            .append(node.startLine).append('|')
            .append(node.startCol).append('|')
            .append(node.endLine).append('|')
            .append(node.endCol).append('|')
            .append(if (node.isNamed) '1' else '0').append('\n')
        for (childId in node.childIds) walk(childId, depth + 1)
    }
    walk(tree.rootId, 0)
    return ContentHash.compute(canonical.toString()).value
}
