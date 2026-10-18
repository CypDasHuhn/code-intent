package dev.cint.frontend

import dev.cint.core.frontend.OracleOptions
import dev.cint.core.model.SourceFile
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VueSplitTest {
    private val options = OracleOptions(enabled = false, timeoutMs = 1_000)

    @Test
    fun regionsUseScriptLang() {
        val source =
            SourceFile(
                "Sample.vue",
                """
                <template><div /></template>
                <script setup lang="ts">const x = 1</script>
                <script>const y = 2</script>
                """.trimIndent(),
            )
        val regions = VueFrontend.regions(source)
        assertEquals(2, regions.size)
        assertEquals("typescript", regions[0].language)
        assertEquals("javascript", regions[1].language)
    }

    @Test
    fun skipsExternalScripts() {
        val source =
            SourceFile(
                "Sample.vue",
                """<script src="./main.ts"></script>""",
            )
        assertTrue(VueFrontend.regions(source).isEmpty())
    }

    @Test
    fun scriptAnnotationsUseHostCoordinates() {
        val text =
            """
            <template>
              <div>{{ message }}</div>
            </template>

            <script setup lang="ts">
            function work() {
              console.log("done")
            }
            </script>
            """.trimIndent()
        val source = SourceFile("Sample.vue", text)
        val annotations = SplitAnalyzer.analyze(source, VueFrontend, Path.of("."), null, options)
        val print = annotations.first { it.rule == "print" }
        val expectedLine = text.lines().indexOfFirst { it.contains("console.log") }
        val expectedCol = text.lines()[expectedLine].indexOf("console.log")
        assertEquals(expectedLine, print.span.startLine)
        assertEquals(expectedCol, print.span.startCol)
    }
}
