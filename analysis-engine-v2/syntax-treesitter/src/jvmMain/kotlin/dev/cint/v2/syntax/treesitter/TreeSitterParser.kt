package dev.cint.v2.syntax.treesitter

import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString

actual fun createTreeSitterParser(): TreeSitterParser = JvmTreeSitterParser()

private class JvmTreeSitterParser : TreeSitterParser {
    private val json = Json { ignoreUnknownKeys = true }

    override fun parse(languageName: String, sourceCode: String): ParseResult {
        return ParseResult(
            language = languageName,
            sourceCode = sourceCode,
            treeJson = json.encodeToString(JsonNode.serializer(), JsonNode(
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
        val sourceCode = java.io.File(filePath).readText()
        return parse(languageName, sourceCode)
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