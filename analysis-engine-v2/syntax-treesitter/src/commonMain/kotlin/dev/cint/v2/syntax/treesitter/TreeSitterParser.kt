package dev.cint.v2.syntax.treesitter

import kotlinx.serialization.Serializable

@Serializable
data class ParseResult(
    val language: String,
    val sourceCode: String,
    val treeJson: String,
    val nodeCount: Int,
    val parseTimeMs: Long
)

interface TreeSitterParser {
    fun parse(languageName: String, sourceCode: String): ParseResult
    fun parseFile(languageName: String, filePath: String): ParseResult
}

expect fun createTreeSitterParser(): TreeSitterParser