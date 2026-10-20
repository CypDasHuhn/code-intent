package dev.cint.frontend

import dev.cint.core.facts.model.SourceFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VueSplitTest {
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
}
