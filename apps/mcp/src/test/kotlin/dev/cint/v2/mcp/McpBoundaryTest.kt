package dev.cint.v2.mcp

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class McpBoundaryTest {
    @Test
    fun mcpMainSourcesUseOnlyContractsRuntimeAndItsOwnPackage() {
        val sourceDir = File(System.getProperty("v2.mcp.sourceDir") ?: "src/main/kotlin")
        assertTrue(sourceDir.isDirectory, "missing MCP source directory: $sourceDir")
        val files = sourceDir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue(files.isNotEmpty(), "no MCP Kotlin sources found")
        val imports =
            files.flatMap { file ->
                file
                    .readLines()
                    .map { it.trim() }
                    .filter { it.startsWith("import ") }
                    .map { it.removePrefix("import ").substringBefore(" as ") }
                    .map { file.name to it }
            }
        val forbidden =
            imports.filter { (_, symbol) ->
                symbol.startsWith("dev.cint.") &&
                    !symbol.startsWith("dev.cint.contracts.") &&
                    !symbol.startsWith("dev.cint.runtime.") &&
                    !symbol.startsWith("dev.cint.v2.mcp.")
            }
        assertTrue(
            forbidden.isEmpty(),
            "MCP must not import core internals or adapters: $forbidden",
        )
    }
}
