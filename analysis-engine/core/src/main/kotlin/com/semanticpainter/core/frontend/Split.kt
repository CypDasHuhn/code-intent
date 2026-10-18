package com.semanticpainter.core.frontend

import com.semanticpainter.core.model.SourceFile

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
