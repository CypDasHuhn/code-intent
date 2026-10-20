package dev.cint.core.user

import dev.cint.api.Contribution
import dev.cint.api.Role
import dev.cint.api.Span
import dev.cint.api.SyntaxNode
import dev.cint.api.UNTAGGED_INTENT
import dev.cint.core.facts.model.SourceFile
import dev.cint.core.support.hashing.contentHash
import dev.cint.core.syntax.SyntaxModel
import dev.cint.core.syntax.SyntaxTree
import java.nio.file.Path

object UserAnnotationResolver {
    fun resolve(
        annotations: List<UserAnnotation>,
        file: SourceFile,
        tree: SyntaxTree,
        syntax: SyntaxModel,
        root: Path?,
    ): List<Contribution> =
        annotations.mapNotNull { annotation ->
            if (!pathMatches(annotation.path, file.path, root)) return@mapNotNull null
            resolve(annotation, file, tree, syntax)
        }

    fun isUserPure(contribution: Contribution): Boolean =
        contribution.rule == USER_CONTRIBUTOR_ID && USER_PURE_INTENT in contribution.intents

    fun selectionText(
        file: SourceFile,
        span: Span,
    ): String? = selectionText(file.text, span)

    fun selectionText(
        text: String,
        span: Span,
    ): String? {
        val bytes = text.toByteArray(Charsets.UTF_8)
        val lineStarts = lineStarts(bytes)
        val start = offset(bytes, lineStarts, span.startLine, span.startCol) ?: return null
        val end = offset(bytes, lineStarts, span.endLine, span.endCol) ?: return null
        if (start > end) return null
        return slice(bytes, start, end)
    }

    private fun resolve(
        annotation: UserAnnotation,
        file: SourceFile,
        tree: SyntaxTree,
        syntax: SyntaxModel,
    ): Contribution? {
        val intents = annotation.intents.filter { it != UNTAGGED_INTENT }.toSet()
        if (intents.isEmpty()) return null

        val bytes = file.text.toByteArray(Charsets.UTF_8)
        val lineStarts = lineStarts(bytes)
        val rangeStart = offset(bytes, lineStarts, annotation.span.startLine, annotation.span.startCol)
        val rangeEnd = offset(bytes, lineStarts, annotation.span.endLine, annotation.span.endCol)
        val rangeText =
            if (rangeStart != null && rangeEnd != null && rangeStart <= rangeEnd) {
                slice(bytes, rangeStart, rangeEnd)
            } else {
                null
            }

        val selected =
            if (annotation.symbol != null) {
                val named = candidates(tree, syntax).filter { it.name == annotation.symbol }
                val verified =
                    named.filter { candidate ->
                        verified(candidate.node, annotation.contentHash, rangeStart, rangeEnd, rangeText)
                    }
                closest(verified, annotation.span)?.node ?: return null
            } else {
                if (rangeStart == null || rangeEnd == null || rangeText == null) return null
                if (annotation.contentHash != null && contentHash(rangeText) != annotation.contentHash) return null
                RangeSelection(annotation.span, rangeStart, rangeEnd)
            }

        val span: Span
        val startByte: Int
        val endByte: Int
        when (selected) {
            is SyntaxNode -> {
                span = selected.span()
                startByte = selected.startByte
                endByte = selected.endByte
            }

            is RangeSelection -> {
                span = selected.span
                startByte = selected.startByte
                endByte = selected.endByte
            }

            else -> {
                return null
            }
        }
        if (endByte < startByte) return null

        return Contribution(
            rule = USER_CONTRIBUTOR_ID,
            role = Role.SOURCE_INTENT,
            intents = intents,
            span = span,
            source = USER_SOURCE,
            confidence = 1.0,
            startByte = startByte,
            endByte = endByte,
        )
    }

    private fun verified(
        node: SyntaxNode,
        hash: String?,
        rangeStart: Int?,
        rangeEnd: Int?,
        rangeText: String?,
    ): Boolean {
        val rangeInside = rangeStart != null && rangeEnd != null && rangeStart >= node.startByte && rangeEnd <= node.endByte
        if (hash == null) return rangeInside
        val nodeMatches = node.text.isNotEmpty() && contentHash(node.text) == hash
        if (nodeMatches) return true
        return rangeInside && rangeText != null && contentHash(rangeText) == hash
    }

    private fun closest(
        candidates: List<Candidate>,
        span: Span,
    ): Candidate? =
        candidates.minWithOrNull(
            compareBy<Candidate>(
                { distance(it.node, span) },
                { it.node.endByte - it.node.startByte },
            ),
        )

    private fun distance(
        node: SyntaxNode,
        span: Span,
    ): Int =
        when {
            span.startLine < node.startLine -> node.startLine - span.startLine
            span.startLine > node.endLine -> span.startLine - node.endLine
            else -> 0
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

    private fun pathMatches(
        recordPath: String,
        sourcePath: String,
        root: Path?,
    ): Boolean =
        runCatching {
            val source = Path.of(sourcePath)
            val absoluteSource = if (source.isAbsolute) source else root?.resolve(source) ?: source
            val sourceNames = setOf(normalize(source), normalize(absoluteSource))
            val record = Path.of(recordPath)
            val recordNames =
                if (record.isAbsolute) {
                    setOf(normalize(record))
                } else {
                    setOf(normalize(record), root?.let { normalize(it.resolve(record)) } ?: normalize(record))
                }
            sourceNames.any { it in recordNames }
        }.getOrDefault(false)

    private fun normalize(path: Path): String = path.toAbsolutePath().normalize().toString()

    private fun lineStarts(bytes: ByteArray): IntArray =
        buildList {
            add(0)
            bytes.forEachIndexed { index, byte ->
                if (byte == '\n'.code.toByte()) add(index + 1)
            }
        }.toIntArray()

    private fun offset(
        bytes: ByteArray,
        lineStarts: IntArray,
        line: Int,
        col: Int,
    ): Int? {
        if (line < 0 || line >= lineStarts.size || col < 0) return null
        val start = lineStarts[line]
        val end = if (line + 1 < lineStarts.size) lineStarts[line + 1] - 1 else bytes.size
        val value = start + col
        if (value < start || value > end || value > bytes.size) return null
        return value
    }

    private fun slice(
        bytes: ByteArray,
        start: Int,
        end: Int,
    ): String? {
        if (start < 0 || end < start || end > bytes.size) return null
        return String(bytes, start, end - start, Charsets.UTF_8)
    }

    private data class Candidate(
        val node: SyntaxNode,
        val name: String?,
    )

    private data class RangeSelection(
        val span: Span,
        val startByte: Int,
        val endByte: Int,
    )
}
