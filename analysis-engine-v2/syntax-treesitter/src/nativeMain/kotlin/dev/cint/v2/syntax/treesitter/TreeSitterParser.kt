package dev.cint.v2.syntax.treesitter

import dev.cint.v2.syntax.treesitter.cinterop.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.cinterop.ExperimentalForeignApi

@OptIn(ExperimentalForeignApi::class)
actual fun createTreeSitterParser(): TreeSitterParser = NativeTreeSitterParser()

@OptIn(ExperimentalForeignApi::class)
private class NativeTreeSitterParser : TreeSitterParser {
    private val json = Json { ignoreUnknownKeys = true }

    override fun parse(languageName: String, sourceCode: String): ParseResult {
        // Prove tree-sitter C interop works by creating a parser
        val parser = ts_parser_new()
        ts_parser_delete(parser)
        
        return ParseResult(
            language = languageName,
            sourceCode = sourceCode,
            treeJson = json.encodeToString(JsonNode(
                type = "SOURCE_FILE",
                startByte = 0,
                endByte = sourceCode.length.toLong(),
                startPoint = Point(row = 0, column = 0),
                endPoint = Point(row = sourceCode.lines().count().toLong(), column = 0),
                text = null,
                children = null
            )),
            nodeCount = 1,
            parseTimeMs = 0
        )
    }

    override fun parseFile(languageName: String, filePath: String): ParseResult {
        // For spike, just read file using JVM-style path (will be replaced with actual file I/O later)
        val sourceCode = readFileFallback(filePath)
        return parse(languageName, sourceCode)
    }

    private fun readFileFallback(path: String): String {
        // Fallback: just return a dummy Kotlin code for the spike
        return """
            package dev.cint.test

            fun main() {
                println("Hello, World!")
            }

            class DataClass(val name: String, val value: Int)
        """.trimIndent()
    }
}

@kotlinx.serialization.Serializable
private data class JsonNode(
    val type: String,
    val startByte: Long,
    val endByte: Long,
    val startPoint: Point,
    val endPoint: Point,
    val text: String?,
    val children: List<JsonNode>?
)

@kotlinx.serialization.Serializable
private data class Point(
    val row: Long,
    val column: Long
)