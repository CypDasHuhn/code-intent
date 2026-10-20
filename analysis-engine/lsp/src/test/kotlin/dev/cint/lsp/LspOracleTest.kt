package dev.cint.lsp

import dev.cint.core.model.SourceFile
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertNotNull

class LspOracleTest {
    @Test
    fun hoverOnSharedServer() {
        val root = Path.of("/home/cyp/repos/mtquery")
        val relative = "src/main/kotlin/importer/SqliteMaintenance.kt"
        val file = root.resolve(relative).toFile()
        if (!file.exists() || !KotlinLspOracle.isRunning(root)) {
            println("skipping: shared kotlin-lsp is not running for $root")
            return
        }
        val text = file.readText()
        val offset = text.indexOf("logger.warn")

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
