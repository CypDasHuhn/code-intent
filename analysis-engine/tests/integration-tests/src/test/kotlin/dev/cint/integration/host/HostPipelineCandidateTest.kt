package dev.cint.integration.host

import dev.cint.core.facts.model.SourceFile
import dev.cint.core.facts.oracle.NullOracle
import dev.cint.host.HostConfig
import dev.cint.host.HostRuntime
import dev.cint.rulepacks.BundledRulePackConfigResolver
import dev.cint.treesitter.KotlinParser
import dev.cint.treesitter.KotlinSyntaxModel
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HostPipelineCandidateTest {
    @Test
    fun hostPipelineReturnsCandidatesAlongsideEstablishedAnnotations() {
        val settings = HostConfig.load(null)
        val pipeline = HostRuntime.pipeline(settings, BundledRulePackConfigResolver, Path.of("."))
        val text =
            """
            fun f(): Boolean {
                // cint: validation

                if (true) {
                    work()
                }
                return false
            }
            """.trimIndent()
        val source = SourceFile("Sample.kt", text)
        val tree = KotlinParser().parse(source)

        val output = pipeline.analyze(source, tree, KotlinSyntaxModel, NullOracle, settings.intentConfig("kotlin"))

        assertTrue(output.annotations.none { "VALIDATION" in it.intents })
        val candidate = output.candidates.single { "VALIDATION" in it.intents }
        assertEquals("rule:validation:validation", candidate.source)
        assertTrue(candidate.aiEligible)
    }
}
