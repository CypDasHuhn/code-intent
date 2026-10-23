package dev.cint.frontend

import dev.cint.api.Span
import dev.cint.core.facts.frontend.LanguageFrontend
import dev.cint.core.facts.frontend.SplitHandler
import dev.cint.core.facts.frontend.SplitRegion
import dev.cint.core.facts.model.SourceFile
import dev.cint.core.support.hashing.SourceNormalizer
import dev.cint.core.syntax.UnitIdentity
import dev.cint.core.syntax.expressionUnits
import dev.cint.core.user.UserAnnotationResolver

data class SplitAnchor(
    val text: String,
    val anchorStart: Int,
    val anchorEnd: Int,
    val regionStartByte: Int,
    val regionStartLine: Int,
    val regionStartCol: Int,
    val language: String,
    val regionOrdinal: Int?,
    val regionKey: String?,
    val regionAmbiguous: Boolean,
    val symbol: String?,
    val unitFingerprint: String?,
    val fingerprintVersion: Int,
    val startToken: Int?,
    val endToken: Int?,
    val startTokenOffset: Int?,
    val endTokenOffset: Int?,
    val startNormalized: Int?,
    val endNormalized: Int?,
)

object SplitAnchorResolver {
    fun leafRegions(
        host: SourceFile,
        frontend: LanguageFrontend,
    ): List<SplitRegion> {
        val handler = frontend as? SplitHandler ?: return emptyList()
        return leafRegions(host, handler)
    }

    fun leafRegions(
        host: SourceFile,
        handler: SplitHandler,
    ): List<SplitRegion> {
        val result = mutableListOf<SplitRegion>()
        collectLeafRegions(handler, host, 0, 0, 0, result)
        return result
    }

    fun isHostLevelSpan(
        host: SourceFile,
        frontend: LanguageFrontend,
        span: Span,
    ): Boolean {
        val range = UserAnnotationResolver.byteRange(host.text, span) ?: return false
        return leafRegions(host, frontend).none { region ->
            val end = region.startByte + region.text.toByteArray(Charsets.UTF_8).size
            range.first < end && range.second > region.startByte
        }
    }

    fun anchor(
        host: SourceFile,
        frontend: LanguageFrontend,
        startByte: Int,
        endByte: Int,
    ): SplitAnchor {
        val regions = leafRegions(host, frontend)
        val regionIndex =
            regions.indexOfFirst { candidate ->
                val end = candidate.startByte + candidate.text.toByteArray(Charsets.UTF_8).size
                startByte >= candidate.startByte && endByte <= end
            }
        val region = regions.getOrNull(regionIndex)
        val contextText = region?.text ?: host.text
        val contextStart = startByte - (region?.startByte ?: 0)
        val contextEnd = endByte - (region?.startByte ?: 0)
        val contextFrontend =
            region?.let { Frontends.forLanguage(it.language) } ?: frontend
        val contextSource = SourceFile(host.path, contextText)
        val tree = contextFrontend.parse(contextSource)
        val syntax = contextFrontend.syntax(tree)
        val units = syntax.expressionUnits(tree)
        val unit =
            units
                .filter { it.startByte <= contextStart && it.endByte >= contextEnd }
                .minByOrNull { it.endByte - it.startByte }
        val unitFingerprint =
            unit?.let {
                UnitIdentity.fingerprint(
                    contextSource,
                    tree,
                    syntax,
                    units,
                    it,
                    UnitIdentity.CURRENT_FINGERPRINT_VERSION,
                )
            }
        val symbol = UnitIdentity.symbolAt(contextSource, tree, syntax, contextStart)
        val tokens = unit?.let { SourceNormalizer.tokenSpans(it.text, stripComments = true) }.orEmpty()
        val tokenAnchor =
            unit?.let {
                tokenRange(tokens, contextStart - it.startByte, contextEnd - it.startByte)
            }
        return SplitAnchor(
            text = contextText,
            anchorStart = contextStart,
            anchorEnd = contextEnd,
            regionStartByte = region?.startByte ?: 0,
            regionStartLine = region?.startLine ?: 0,
            regionStartCol = region?.startCol ?: 0,
            language = region?.language ?: frontend.language,
            regionOrdinal = region?.let { regionIndex.takeIf { index -> index >= 0 } },
            regionKey = region?.identityKey,
            regionAmbiguous = region?.let { regions.count { candidate -> candidate.identityKey == it.identityKey } > 1 } ?: false,
            symbol = symbol,
            unitFingerprint = unitFingerprint,
            fingerprintVersion = UnitIdentity.CURRENT_FINGERPRINT_VERSION,
            startToken = tokenAnchor?.startToken,
            endToken = tokenAnchor?.endToken,
            startTokenOffset = tokenAnchor?.startOffset,
            endTokenOffset = tokenAnchor?.endOffset,
            startNormalized =
                unit?.let {
                    SourceNormalizer.normalizedLengthAtByte(it.text, contextStart - it.startByte)
                },
            endNormalized =
                unit?.let {
                    SourceNormalizer.normalizedLengthAtByte(it.text, contextEnd - it.startByte)
                },
        )
    }

    private fun collectLeafRegions(
        handler: SplitHandler,
        source: SourceFile,
        baseStartByte: Int,
        baseStartLine: Int,
        baseStartCol: Int,
        result: MutableList<SplitRegion>,
    ) {
        for (region in handler.regions(source)) {
            val regionFrontend = Frontends.forLanguage(region.language) ?: continue
            val absolute =
                region.copy(
                    startByte = baseStartByte + region.startByte,
                    startLine = baseStartLine + region.startLine,
                    startCol = if (region.startLine == 0) baseStartCol + region.startCol else region.startCol,
                )
            val nested = regionFrontend as? SplitHandler
            if (nested != null) {
                collectLeafRegions(
                    nested,
                    SourceFile(source.path, region.text),
                    absolute.startByte,
                    absolute.startLine,
                    absolute.startCol,
                    result,
                )
            } else {
                result += absolute
            }
        }
    }

    private fun tokenRange(
        tokens: List<dev.cint.core.support.hashing.SourceToken>,
        startByte: Int,
        endByte: Int,
    ): TokenRange? {
        if (tokens.isEmpty() || endByte <= startByte) return null
        val startIndex = tokens.indexOfFirst { it.endByte > startByte }
        val endIndex = tokens.indexOfLast { it.startByte < endByte }
        if (startIndex < 0 || endIndex < 0 || endIndex < startIndex) return null
        val startToken = tokens[startIndex]
        val endToken = tokens[endIndex]
        val startOffset = (startByte - startToken.startByte).coerceIn(0, startToken.endByte - startToken.startByte)
        val endOffset = (endByte - endToken.startByte).coerceIn(0, endToken.endByte - endToken.startByte)
        if (startIndex == endIndex && endOffset <= startOffset) return null
        return TokenRange(startIndex, endIndex, startOffset, endOffset)
    }

    private data class TokenRange(
        val startToken: Int,
        val endToken: Int,
        val startOffset: Int,
        val endOffset: Int,
    )
}
