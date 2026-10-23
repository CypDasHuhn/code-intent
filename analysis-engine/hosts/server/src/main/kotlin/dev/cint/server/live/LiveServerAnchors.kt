package dev.cint.server.live

import dev.cint.core.facts.model.SourceFile
import dev.cint.core.syntax.UnitIdentity
import dev.cint.core.syntax.expressionUnits
import dev.cint.frontend.Frontends
import dev.cint.server.LiveServer
import dev.cint.suggest.cache.UnitCacheKeys

internal fun LiveServer.anchorContexts(
    doc: DocState,
    fingerprintVersion: Int = UnitIdentity.CURRENT_FINGERPRINT_VERSION,
): (Int, Int) -> String {
    val frontend = Frontends.forLanguage(doc.language) ?: return { _, _ -> "" }
    parseRuns.incrementAndGet()
    return runCatching {
        val source = SourceFile(doc.uri, doc.text)
        val tree = frontend.parse(source)
        val syntax = frontend.syntax(tree)
        val units = syntax.expressionUnits(tree)
        val lookup: (Int, Int) -> String = { startByte, endByte ->
            val exact =
                UnitIdentity.unitFingerprint(
                    source,
                    tree,
                    syntax,
                    startByte,
                    endByte,
                    fingerprintVersion,
                )
            if (exact != null) {
                exact
            } else {
                val unit =
                    units
                        .filter { it.startByte <= startByte && it.endByte >= endByte }
                        .minByOrNull { it.endByte - it.startByte }
                val unitFingerprint =
                    unit?.let {
                        UnitIdentity.fingerprint(
                            source,
                            tree,
                            syntax,
                            units,
                            it,
                            fingerprintVersion,
                        )
                    }
                val range =
                    unit?.let {
                        UnitCacheKeys.relativeTokenRange(
                            dev.cint.core.support.hashing.SourceNormalizer
                                .tokenSpans(it.text, stripComments = true),
                            startByte - it.startByte,
                            endByte - it.startByte,
                        )
                    }
                listOf(unitFingerprint.orEmpty(), range?.toString().orEmpty()).joinToString(":")
            }
        }
        lookup
    }.getOrElse { { _, _ -> "" } }
}

internal fun LiveServer.anchorContext(
    doc: DocState,
    startByte: Int,
    endByte: Int,
    fingerprintVersion: Int = UnitIdentity.CURRENT_FINGERPRINT_VERSION,
): String = anchorContexts(doc, fingerprintVersion)(startByte, endByte)
