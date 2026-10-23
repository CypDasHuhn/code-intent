package dev.cint.frontend

import dev.cint.core.config.IntentConfig
import dev.cint.core.facts.frontend.LanguageFrontend
import dev.cint.core.facts.frontend.OracleOptions
import dev.cint.core.facts.frontend.SplitHandler
import dev.cint.core.facts.frontend.SplitRegion
import dev.cint.core.facts.model.SourceFile
import dev.cint.core.facts.oracle.TypeOracle
import dev.cint.core.runtime.scheduler.ExecutionPlan
import dev.cint.core.runtime.scheduler.TerminalNode
import dev.cint.core.syntax.SyntaxModel
import dev.cint.core.syntax.SyntaxTree
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

class SplitAmbiguityTest {
    @Test
    fun crossLevelDuplicateLeafIdentitiesAreAmbiguous() {
        val script = "function f() { return g(a, b) }"
        val outer =
            object : LanguageFrontend, SplitHandler {
                override val language: String = "outer"
                override val extensions: List<String> = emptyList()

                override fun parse(file: SourceFile): SyntaxTree = KotlinFrontend.parse(file)

                override fun syntax(tree: SyntaxTree): SyntaxModel = KotlinFrontend.syntax(tree)

                override fun oracle(
                    root: Path,
                    options: OracleOptions,
                ): TypeOracle = KotlinFrontend.oracle(root, options)

                override fun regions(file: SourceFile): List<SplitRegion> =
                    listOf(
                        SplitRegion("javascript", script, 0, 0, 0),
                        SplitRegion("vue", "<script>\n$script</script>", 0, 0, 0),
                    )
            }
        val leaves = mutableListOf<SplitRegion>()
        SplitAnalyzer.analyze(
            SourceFile("Sample.outer", "host"),
            outer,
            Path.of("."),
            OracleOptions(enabled = false, timeoutMs = 1_000L),
            planFor = { ExecutionPlan(emptyList(), emptyList(), TerminalNode(emptySet())) },
            userContributionsFor = { _, _, _, _, region ->
                leaves += region
                emptyList()
            },
            configFor = { language -> IntentConfig(language, emptyList()) },
        )

        assertTrue(leaves.size >= 2)
        assertTrue(leaves.all { it.identityAmbiguous })
        assertTrue(leaves.map { it.identityKey }.distinct().size == 1)
    }
}
