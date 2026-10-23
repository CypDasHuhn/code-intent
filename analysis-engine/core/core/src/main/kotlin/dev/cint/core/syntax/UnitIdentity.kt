package dev.cint.core.syntax

import dev.cint.api.SyntaxNode
import dev.cint.core.facts.model.SourceFile
import dev.cint.core.support.hashing.SourceNormalizer
import dev.cint.core.support.hashing.contentHash

object UnitIdentity {
    const val CURRENT_FINGERPRINT_VERSION: Int = 2
    const val UNVERSIONED_FINGERPRINT_VERSION: Int = 2

    fun versionOf(version: Int?): Int = version ?: UNVERSIONED_FINGERPRINT_VERSION

    fun symbolAt(
        file: SourceFile,
        tree: SyntaxTree,
        syntax: SyntaxModel,
        startByte: Int,
    ): String? =
        candidates(tree, syntax)
            .filter { candidate -> candidate.node.startByte <= startByte && candidate.node.endByte >= startByte }
            .minWithOrNull(
                compareBy<Candidate>(
                    { it.node.endByte - it.node.startByte },
                    { kotlin.math.abs(it.node.startByte - startByte) },
                ),
            )?.name

    fun unitFingerprint(
        file: SourceFile,
        tree: SyntaxTree,
        syntax: SyntaxModel,
        startByte: Int,
        endByte: Int,
        fingerprintVersion: Int = CURRENT_FINGERPRINT_VERSION,
    ): String? {
        val units = syntax.expressionUnits(tree)
        val target = units.firstOrNull { it.startByte == startByte && it.endByte == endByte } ?: return null
        return fingerprint(file, tree, syntax, units, target, fingerprintVersion)
    }

    fun fingerprint(
        file: SourceFile,
        tree: SyntaxTree,
        syntax: SyntaxModel,
        units: List<ExpressionUnit>,
        target: ExpressionUnit,
        fingerprintVersion: Int = CURRENT_FINGERPRINT_VERSION,
    ): String {
        val normalized = normalize(target.text, fingerprintVersion, comments = false)
        val symbol = symbolAt(file, tree, syntax, target.startByte).orEmpty()
        val previous = previousSiblingText(target.node, fingerprintVersion)
        val sibling = nextSiblingText(target.node, fingerprintVersion)
        val ordinal =
            units
                .filter {
                    it.node.kind == target.node.kind &&
                        normalize(it.text, fingerprintVersion, comments = false) == normalized &&
                        symbolAt(file, tree, syntax, it.startByte).orEmpty() == symbol &&
                        previousSiblingText(it.node, fingerprintVersion) == previous &&
                        nextSiblingText(it.node, fingerprintVersion) == sibling
                }.count { it.startByte <= target.startByte }
        return contentHash(
            listOf(
                target.node.kind,
                normalized,
                symbol,
                previous,
                sibling,
                ordinal.toString(),
            ).joinToString("\n"),
        )
    }

    fun normalize(
        text: String,
        fingerprintVersion: Int,
        comments: Boolean,
    ): String =
        if (fingerprintVersion <= 1) {
            text.trim().replace(Regex("\\s+"), " ")
        } else {
            SourceNormalizer.normalize(text, stripComments = !comments)
        }

    fun relativeTokenRange(
        text: String,
        startByte: Int,
        endByte: Int,
    ): Pair<Int, Int>? {
        val tokens = SourceNormalizer.tokenSpans(text, stripComments = true)
        if (tokens.isEmpty() || endByte <= startByte) return null
        val start = tokens.indexOfFirst { it.endByte > startByte }
        val end = tokens.indexOfLast { it.startByte < endByte } + 1
        if (start < 0 || end <= start) return null
        return start to end
    }

    private fun previousSiblingText(
        node: SyntaxNode,
        fingerprintVersion: Int,
    ): String {
        val parent = node.parent ?: return ""
        val index = parent.children.indexOfFirst { it.startByte == node.startByte && it.endByte == node.endByte }
        val sibling =
            if (index <= 0) {
                null
            } else {
                parent.children.take(index).lastOrNull { it.isNamed }
            }
        return normalize(sibling?.text ?: "", fingerprintVersion, comments = true)
    }

    private fun nextSiblingText(
        node: SyntaxNode,
        fingerprintVersion: Int,
    ): String {
        val parent = node.parent ?: return ""
        val index = parent.children.indexOfFirst { it.startByte == node.startByte && it.endByte == node.endByte }
        val sibling =
            if (index < 0) {
                null
            } else {
                parent.children.drop(index + 1).firstOrNull { it.isNamed }
            }
        return normalize(sibling?.text ?: "", fingerprintVersion, comments = true)
    }

    private fun candidates(
        tree: SyntaxTree,
        syntax: SyntaxModel,
    ): List<Candidate> {
        val result = linkedMapOf<Long, Candidate>()

        fun add(
            node: SyntaxNode,
            name: String?,
        ) {
            if (name == null) return
            result.putIfAbsent(key(node), Candidate(node, name))
        }
        syntax.functions(tree).forEach { function -> add(function.node, function.name) }
        syntax.declarations(tree).forEach { declaration -> add(declaration.node, declaration.name) }
        syntax.properties(tree).forEach { property -> add(property.node, property.name) }
        return result.values.toList()
    }

    private fun key(node: SyntaxNode): Long = (node.startByte.toLong() shl 32) or (node.endByte.toLong() and 0xffffffffL)

    private data class Candidate(
        val node: SyntaxNode,
        val name: String?,
    )
}
