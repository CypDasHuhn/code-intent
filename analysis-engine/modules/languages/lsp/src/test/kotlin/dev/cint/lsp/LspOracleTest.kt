package dev.cint.lsp

import dev.cint.core.facts.model.SourceFile
import org.junit.Assume.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LspOracleTest {
    @Test
    fun hoverOnSharedServer() {
        val configuredRoot = System.getProperty("cint.lsp.root") ?: System.getenv("CINT_LSP_ROOT")
        assumeTrue(
            "set -Dcint.lsp.root=<project> or CINT_LSP_ROOT=<project> to run the LSP oracle test",
            !configuredRoot.isNullOrBlank(),
        )
        val root = Path.of(requireNotNull(configuredRoot)).toAbsolutePath().normalize()
        val relative =
            System.getProperty("cint.lsp.file")
                ?: System.getenv("CINT_LSP_FILE")
                ?: "src/main/kotlin/importer/SqliteMaintenance.kt"
        val symbol = System.getProperty("cint.lsp.symbol") ?: System.getenv("CINT_LSP_SYMBOL") ?: "logger.warn"
        val file = root.resolve(relative)
        assertTrue(Files.isRegularFile(file), "LSP fixture does not exist: $file")
        assertTrue(KotlinLspOracle.isRunning(root), "kotlin-lsp is not running for $root; start it or unset cint.lsp.root")

        val text = Files.readString(file)
        val offset = text.indexOf(symbol)
        assertTrue(offset >= 0, "LSP symbol '$symbol' not found in $file")

        val totalStart = System.nanoTime()
        val connectStart = System.nanoTime()
        val oracle = KotlinLspOracle.connect(root)
        println("connectMs=${(System.nanoTime() - connectStart) / 1_000_000}")
        oracle.use { oracle ->
            val queryStart = System.nanoTime()
            val type = oracle.typeAt(SourceFile(relative, text), offset)
            println("queryMs=${(System.nanoTime() - queryStart) / 1_000_000} type=$type")
            assertNotNull(type)
        }
        println("totalMs=${(System.nanoTime() - totalStart) / 1_000_000}")
    }
}
