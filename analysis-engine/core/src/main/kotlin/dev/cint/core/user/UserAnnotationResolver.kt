package dev.cint.core.user

import dev.cint.api.Contribution
import dev.cint.api.Role
import dev.cint.api.Span
import dev.cint.api.SyntaxNode
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

    private fun resolve(
        annotation: UserAnnotation,
        file: SourceFile,
        tree: SyntaxTree,
        syntax: SyntaxModel,
    ): Contribution? {
        val candidates = candidates(tree, syntax)
        val text = file.text
        val lineStarts = lineStarts(text)
        val rangeStart = offset(text, lineStarts, annotation.span.startLine, annotation.span.startCol)
        val rangeEnd = offset(text, lineStarts, annotation.span.endLine, annotation.span.endCol)
        val rangeText =
            if (rangeStart != null && rangeEnd != null && rangeStart <= rangeEnd && rangeEnd <= text.length) {
                text.substring(rangeStart, rangeEnd)
            } else {
                null
            }

        val selected =
            if (annotation.symbol != null) {
                val named = candidates.filter { it.name == annotation.symbol }
                val matches = named.ifEmpty { candidates.filter { it.node.text == annotation.symbol } }
                val verified =
                    matches.filter { candidate ->
                        verified(candidate.node, annotation.contentHash, rangeStart, rangeEnd, rangeText)
                    }
                closest(verified, annotation.span)?.node ?: return null
            } else {
                if (rangeStart == null || rangeEnd == null || rangeText == null) return null
                if (annotation.contentHash != null && contentHash(rangeText) != annotation.contentHash) return null
                RangeSelection(annotation.span, rangeText)
            }

        val resolvedSpan: Span
        val evidence: String
        if (selected is SyntaxNode) {
            resolvedSpan = selected.span()
            evidence = selected.text.replace("\n", " ").take(160)
        } else if (selected is RangeSelection) {
            resolvedSpan = selected.span
            evidence = selected.text.replace("\n", " ").take(160)
        } else {
            return null
        }

        val startByte = offset(text, lineStarts, resolvedSpan.startLine, resolvedSpan.startCol) ?: return null
        val endByte = offset(text, lineStarts, resolvedSpan.endLine, resolvedSpan.endCol) ?: return null
        if (endByte < startByte) return null
        return Contribution(
            rule = USER_CONTRIBUTOR_ID,
            role = Role.SOURCE_INTENT,
            intents = annotation.intents,
            evidence = annotation.note?.takeIf { it.isNotBlank() } ?: evidence,
            span = resolvedSpan,
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
        if (node.text.isNotEmpty() && contentHash(node.text) == hash) return true
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
        val named = linkedMapOf<Long, String>()
        syntax.functions(tree).forEach { function ->
            function.name?.let { named[key(function.node)] = it }
        }
        syntax.declarations(tree).forEach { declaration ->
            declaration.name?.let { named[key(declaration.node)] = it }
        }
        syntax.properties(tree).forEach { property ->
            property.name?.let { named[key(property.node)] = it }
        }
        return tree.root
            .descendants()
            .map { node -> Candidate(node, named[key(node)]) }
            .distinctBy { key(it.node) }
            .toList()
    }

    private fun key(node: SyntaxNode): Long = (node.startByte.toLong() shl 32) or (node.endByte.toLong() and 0xffffffffL)

    private fun pathMatches(
        recordPath: String,
        sourcePath: String,
        root: Path?,
    ): Boolean {
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
        return sourceNames.any { it in recordNames }
    }

    private fun normalize(path: Path): String = path.toAbsolutePath().normalize().toString()

    private fun lineStarts(text: String): IntArray =
        buildList {
            add(0)
            text.forEachIndexed { index, character ->
                if (character == '\n') add(index + 1)
            }
        }.toIntArray()

    private fun offset(
        text: String,
        lineStarts: IntArray,
        line: Int,
        col: Int,
    ): Int? {
        if (line < 0 || line >= lineStarts.size || col < 0) return null
        val start = lineStarts[line]
        val end = if (line + 1 < lineStarts.size) lineStarts[line + 1] - 1 else text.length
        val value = start + col
        if (value < start || value > end || value > text.length) return null
        return value
    }

    private data class Candidate(
        val node: SyntaxNode,
        val name: String?,
    )

    private data class RangeSelection(
        val span: Span,
        val text: String,
    )
}
