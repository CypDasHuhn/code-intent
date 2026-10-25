package dev.cint.treesitter.tmp

import dev.cint.api.SyntaxNode
import dev.cint.core.facts.model.SourceFile
import dev.cint.treesitter.KotlinParser
import java.io.File
import java.security.MessageDigest
import kotlin.test.Test

class V1SyntaxCaptureTest {
    @Test
    fun capture() {
        val corpus = File(System.getenv("CINT_V1_CORPUS"))
        val output = File(System.getenv("CINT_V1_SNAPSHOT_OUT"))
        val parser = KotlinParser()
        val files = corpus.listFiles { file -> file.extension == "kt" }!!.sortedBy { it.name }
        val json = StringBuilder()
        json.append("{\n  \"formatVersion\": 1,\n")
        json.append("  \"generator\": \"analysis-engine/languages/treesitter KotlinParser\",\n")
        json.append("  \"v1Commit\": ").append(quote(System.getenv("CINT_V1_COMMIT"))).append(",\n")
        json.append("  \"files\": {\n")
        files.forEachIndexed { fileIndex, file ->
            val source = file.readText()
            val tree = parser.parse(SourceFile(file.name, source))
            val nodes = mutableListOf<SyntaxNode>()
            val depths = mutableListOf<Int>()
            val canonical = StringBuilder()
            fun walk(node: SyntaxNode, depth: Int) {
                nodes += node
                depths += depth
                canonical.append(depth).append('|')
                    .append(node.kind).append('|')
                    .append(node.startByte).append('|')
                    .append(node.endByte).append('|')
                    .append(node.startLine).append('|')
                    .append(node.startCol).append('|')
                    .append(node.endLine).append('|')
                    .append(node.endCol).append('|')
                    .append(if (node.isNamed) '1' else '0').append('\n')
                for (child in node.children) walk(child, depth + 1)
            }
            walk(tree.root, 0)
            val fingerprint = MessageDigest.getInstance("SHA-256")
                .digest(canonical.toString().toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            json.append("    ").append(quote(file.name)).append(": {\n")
            json.append("      \"source\": ").append(quote(source)).append(",\n")
            json.append("      \"fingerprint\": ").append(quote(fingerprint)).append(",\n")
            json.append("      \"nodes\": [\n")
            nodes.forEachIndexed { index, node ->
                json.append("        [")
                    .append(quote(node.kind)).append(',')
                    .append(node.startByte).append(',')
                    .append(node.endByte).append(',')
                    .append(node.startLine).append(',')
                    .append(node.startCol).append(',')
                    .append(node.endLine).append(',')
                    .append(node.endCol).append(',')
                    .append(if (node.isNamed) "true" else "false").append(',')
                    .append(depths[index])
                    .append("]")
                if (index != nodes.lastIndex) json.append(',')
                json.append('\n')
            }
            json.append("      ]\n    }")
            if (fileIndex != files.lastIndex) json.append(',')
            json.append('\n')
        }
        json.append("  }\n}\n")
        output.parentFile.mkdirs()
        output.writeText(json.toString())
    }

    private fun quote(value: String): String = buildString {
        append('"')
        for (char in value) {
            when (char) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> {
                    if (char.code < 0x20) {
                        append("\\u").append(char.code.toString(16).padStart(4, '0'))
                    } else {
                        append(char)
                    }
                }
            }
        }
        append('"')
    }
}
