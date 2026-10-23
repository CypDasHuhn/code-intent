package dev.cint.core.facts.frontend

import dev.cint.core.facts.model.SourceFile
import dev.cint.core.support.hashing.SourceNormalizer
import dev.cint.core.support.hashing.contentHash

data class SplitRegion(
    val language: String,
    val text: String,
    val startByte: Int,
    val startLine: Int,
    val startCol: Int,
    val ordinal: Int = 0,
    val identityAmbiguous: Boolean = false,
) {
    val identityKey: String
        get() = contentHash(listOf(language, SourceNormalizer.normalize(text)).joinToString("\n"))
}

interface SplitHandler {
    fun regions(file: SourceFile): List<SplitRegion>
}
