package dev.cint.integration.frontend

import dev.cint.core.facts.frontend.OracleOptions
import dev.cint.core.facts.model.SourceFile
import dev.cint.frontend.SplitAnalyzer
import dev.cint.frontend.VueFrontend
import dev.cint.host.HostConfig
import dev.cint.host.HostRuntime
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SplitCandidateTest {
    @Test
    fun splitRegionsShiftCandidateSpansAndBytes() {
        val text =
            """
            <template><div /></template>
            <script>
            function f() {
                // cint: validation

                if (true) {
                    work()
                }
                return false
            }
            </script>
            """.trimIndent()
        val settings = HostConfig.load(null)
        val pipeline = HostRuntime.pipeline(settings)

        val output =
            SplitAnalyzer.analyze(
                SourceFile("Sample.vue", text),
                VueFrontend,
                Path.of("."),
                OracleOptions(enabled = false, timeoutMs = 1_000L),
                pipeline::plan,
            ) { language -> settings.intentConfig(language) }

        val candidate = output.candidates.single()
        val expectedStart = text.substring(0, text.indexOf("true")).toByteArray(Charsets.UTF_8).size
        val expectedLine = text.lines().indexOfFirst { it.contains("if (true)") }

        assertTrue(candidate.intents.contains("VALIDATION"))
        assertEquals(expectedStart, candidate.startByte)
        assertEquals(expectedStart + "true".length, candidate.endByte)
        assertEquals(expectedLine, candidate.span.startLine)
    }
}
