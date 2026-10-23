package dev.cint.suggest.cache

import dev.cint.core.support.hashing.SourceNormalizer
import dev.cint.core.support.hashing.contentHash
import dev.cint.suggest.session.SessionUnit

data class UnitCacheKey(
    val language: String,
    val importsDigest: String,
    val nodeKind: String,
    val textDigest: String,
    val symbolDigest: String,
    val signatureDigest: String,
    val referencesDigest: String,
    val existingIntentDigest: String,
    val aiEnv: String,
) {
    val hash: String =
        contentHash(
            listOf(
                language,
                importsDigest,
                nodeKind,
                textDigest,
                symbolDigest,
                signatureDigest,
                referencesDigest,
                existingIntentDigest,
                aiEnv,
            ).joinToString("\n"),
        )
}

object UnitCacheKeys {
    fun of(
        unit: SessionUnit,
        aiEnv: String,
    ): UnitCacheKey {
        val tokens = SourceNormalizer.tokenSpans(unit.text, stripComments = true)
        val existing =
            unit.existingAnnotations
                .flatMap { annotation ->
                    val range =
                        relativeTokenRange(
                            tokens,
                            annotation.startByte - unit.startByte,
                            annotation.endByte - unit.startByte,
                            requireExact = true,
                        )
                    annotation.intents.sorted().map { intent ->
                        listOf(
                            intent,
                            range?.first?.toString() ?: (annotation.startByte - unit.startByte).toString(),
                            range?.second?.toString() ?: (annotation.endByte - unit.startByte).toString(),
                            annotation.source,
                        ).joinToString(":")
                    }
                }.sorted()
        return UnitCacheKey(
            language = unit.language,
            importsDigest = contentHash(unit.imports.sorted().joinToString("\n")),
            nodeKind = unit.nodeKind,
            textDigest = contentHash(unit.normalizedText),
            symbolDigest = contentHash(unit.enclosingSymbol.orEmpty()),
            signatureDigest = contentHash(unit.enclosingSignature.orEmpty()),
            referencesDigest = contentHash(unit.referencedNames.sorted().joinToString("\n")),
            existingIntentDigest = contentHash(existing.joinToString("\n")),
            aiEnv = aiEnv,
        )
    }

    fun relativeTokenRange(
        tokens: List<dev.cint.core.support.hashing.SourceToken>,
        startByte: Int,
        endByte: Int,
        requireExact: Boolean = false,
    ): Pair<Int, Int>? {
        if (tokens.isEmpty() || endByte <= startByte) return null
        val start = tokens.indexOfFirst { it.endByte > startByte }
        val end = tokens.indexOfLast { it.startByte < endByte } + 1
        if (start < 0 || end <= start) return null
        if (requireExact && (tokens[start].startByte != startByte || tokens[end - 1].endByte != endByte)) {
            return null
        }
        return start to end
    }

    fun tokenAnchorRange(
        tokens: List<dev.cint.core.support.hashing.SourceToken>,
        startByte: Int,
        endByte: Int,
    ): TokenAnchor? {
        if (tokens.isEmpty() || endByte <= startByte) return null
        val startIndex = tokens.indexOfFirst { it.endByte > startByte }
        val endIndex = tokens.indexOfLast { it.startByte < endByte }
        if (startIndex < 0 || endIndex < 0 || endIndex < startIndex) return null
        val startToken = tokens[startIndex]
        val endToken = tokens[endIndex]
        val startOffset = (startByte - startToken.startByte).coerceIn(0, startToken.endByte - startToken.startByte)
        val endOffset = (endByte - endToken.startByte).coerceIn(0, endToken.endByte - endToken.startByte)
        if (startIndex == endIndex && endOffset <= startOffset) return null
        return TokenAnchor(startIndex, startOffset, endIndex, endOffset)
    }
}

data class TokenAnchor(
    val startToken: Int,
    val startOffset: Int,
    val endToken: Int,
    val endOffset: Int,
)

data class CacheInvalidation(
    val changedRanges: List<IntRange> = emptyList(),
    val changedNames: Set<String> = emptySet(),
) {
    fun allows(unit: SessionUnit): Boolean {
        val overlaps =
            changedRanges.any { range ->
                range.first < unit.endByte && range.last + 1 > unit.startByte
            }
        if (overlaps) return false
        return changedNames.isEmpty() || unit.referencedNames.none { it in changedNames }
    }

    companion object {
        val NONE: CacheInvalidation = CacheInvalidation()
    }
}
