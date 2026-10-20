package dev.cint.core.facts.frontend

import dev.cint.core.facts.model.SourceFile

data class SplitRegion(
    val language: String,
    val text: String,
    val startByte: Int,
    val startLine: Int,
    val startCol: Int,
)

interface SplitHandler {
    fun regions(file: SourceFile): List<SplitRegion>
}
