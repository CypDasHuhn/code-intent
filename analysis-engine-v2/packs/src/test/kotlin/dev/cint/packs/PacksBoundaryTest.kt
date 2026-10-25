package dev.cint.packs

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PacksBoundaryTest {
    @Test
    fun mainSourcesStayInsideTheContractsBoundary() {
        val root = File("src/main/kotlin")
        assertTrue(root.isDirectory, "missing main source directory")

        val forbidden =
            listOf(
                "import dev.cint.core",
                "import dev.cint.knowledge",
                "import dev.cint.languages",
                "ServiceLoader",
                "ClassLoader",
                "META-INF/services",
            )

        Files.walk(root.toPath()).use { paths ->
            paths
                .filter { it.toString().endsWith(".kt") }
                .forEach { path ->
                    val text = Files.readString(path)
                    for (marker in forbidden) {
                        assertFalse(text.contains(marker), "$path contains forbidden marker '$marker'")
                    }
                }
        }
    }

    @Test
    fun bundledPackContentIsAResourceNotKotlinSeedData() {
        assertTrue(File("src/main/resources/packs/bundled-packs.json").isFile)
        val mainSources =
            File("src/main/kotlin").walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .joinToString("\n") { it.readText() }
        assertFalse(mainSources.contains("fun functionDefault("))
        assertFalse(mainSources.contains("internal val loggingDefaults"))
    }
}
