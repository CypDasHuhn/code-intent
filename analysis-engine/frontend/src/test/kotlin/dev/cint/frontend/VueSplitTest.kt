package dev.cint.frontend

import dev.cint.api.Role
import dev.cint.core.frontend.OracleOptions
import dev.cint.core.host.HostPipeline
import dev.cint.core.model.SourceFile
import dev.cint.rulepacks.BundledRulePackSelection
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
        val expectedStartByte = text.substring(0, text.indexOf("console.log")).toByteArray(Charsets.UTF_8).size

        assertEquals(expectedLine, print.span.startLine)
        assertEquals(expectedCol, print.span.startCol)
        assertEquals(expectedStartByte, print.startByte)
        assertTrue(text.substring(print.startByte, print.endByte).startsWith("console.log"))

        val contributor = print.provenance.single()
        assertEquals("print", contributor.rule)
        assertEquals(Role.SOURCE_INTENT, contributor.role)
        assertEquals(print.span, contributor.span)
        assertEquals(print.startByte, contributor.startByte)
        assertEquals(print.endByte, contributor.endByte)
    }

    @Test
    fun scriptAnnotationsUseTheRulePipelineInHostCoordinates() {
        val text =
            """
            <template>
              <div>{{ message }}</div>
            </template>

            <script setup lang="ts">
            function work() {
              console.log("done")
              throw new Error("boom")
            }
            </script>
            """.trimIndent()
        val source = SourceFile("Sample.vue", text)
        val pipeline = HostPipeline(constructedPacks = BundledRulePackSelection::constructed)

        val annotations = SplitAnalyzer.analyze(source, VueFrontend, Path.of("."), null, options, pipeline::plan)
        val logging = annotations.first { it.role == Role.SOURCE_INTENT && "LOGGING" in it.intents }
        val errorHandling = annotations.first { it.role == Role.SOURCE_INTENT && "ERROR_HANDLING" in it.intents }

        val expectedStart = text.substring(0, text.indexOf("console.log")).toByteArray(Charsets.UTF_8).size
        val expectedLine = text.lines().indexOfFirst { it.contains("console.log") }
        assertEquals(expectedStart, logging.startByte)
        assertEquals(expectedLine, logging.span.startLine)
        assertTrue(logging.provenance.all { it.rule == "logging:logging" })

        val throwStart = text.substring(0, text.indexOf("throw new Error")).toByteArray(Charsets.UTF_8).size
        assertEquals(throwStart, errorHandling.startByte)
        assertTrue(errorHandling.provenance.all { it.rule == "error-handling-typescript:error-handling" })
    }

    @Test
    fun mergedContributorsShiftInHostCoordinates() {
        val text =
            """
            <template>
              <div>{{ message }}</div>
            </template>

            <script setup lang="ts">
            function guard(loggingEnabled: boolean) {
              if (loggingEnabled) {
                return
              }
            }
            </script>
            """.trimIndent()
        val source = SourceFile("Sample.vue", text)
        val annotations = SplitAnalyzer.analyze(source, VueFrontend, Path.of("."), null, options)
        val merged = annotations.first { it.rule == "logging-state" && it.intents == setOf("LOGGING", "VALIDATION") }

        assertEquals(2, merged.provenance.size)
        assertEquals(setOf("logging-state", "validation-guard"), merged.provenance.map { it.rule }.toSet())
        assertTrue(merged.provenance.all { it.span == merged.span })
        assertTrue(merged.provenance.all { it.startByte == merged.startByte && it.endByte == merged.endByte })

        val ifIndex = text.indexOf("if (loggingEnabled)")
        val expectedStart = text.substring(0, ifIndex + "if (".length).toByteArray(Charsets.UTF_8).size
        val expectedLine = text.lines().indexOfFirst { it.contains("if (loggingEnabled)") }
        val expectedCol = text.lines()[expectedLine].indexOf("loggingEnabled")

        assertEquals(expectedStart, merged.startByte)
        assertEquals(expectedStart + "loggingEnabled".toByteArray(Charsets.UTF_8).size, merged.endByte)
        assertEquals(expectedLine, merged.span.startLine)
        assertEquals(expectedCol, merged.span.startCol)
    }
}
