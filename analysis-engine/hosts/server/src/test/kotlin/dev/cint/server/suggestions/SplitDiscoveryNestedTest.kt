package dev.cint.server.suggestions

import dev.cint.core.facts.frontend.LanguageFrontend
import dev.cint.core.facts.frontend.OracleOptions
import dev.cint.core.facts.frontend.SplitHandler
import dev.cint.core.facts.frontend.SplitRegion
import dev.cint.core.facts.model.SourceFile
import dev.cint.core.facts.oracle.TypeOracle
import dev.cint.core.syntax.SyntaxModel
import dev.cint.core.syntax.SyntaxTree
import dev.cint.frontend.KotlinFrontend
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SplitDiscoveryNestedTest {
    @Test
    fun nestedSplitRegionsAreEnumeratedRecursively() {
        val innerScript = "<script>\nfunction f() {\n  return g(a, b)\n}\n</script>"
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
                        SplitRegion(
                            language = "vue",
                            text = innerScript,
                            startByte = 7,
                            startLine = 3,
                            startCol = 2,
                        ),
                    )
            }
        val source = SourceFile("Sample.outer", "prefix\n$innerScript")
        val result = mutableListOf<Pair<SplitRegion, String>>()

        collectLeafRegions(outer, source, 0, 0, 0, "r", result)

        val (region, prefix) = result.single()
        assertEquals("javascript", region.language)
        assertEquals("r0-0-", prefix)
        assertTrue(region.startByte >= 7)
        assertEquals(4, region.startLine)

        val leaves =
            dev.cint.frontend.SplitAnchorResolver
                .leafRegions(source, outer as SplitHandler)
        assertEquals(1, leaves.size)
        val startByte = region.startByte + region.text.indexOf("g(a, b)")
        val endByte = startByte + "g(a, b)".length
        val anchor =
            dev.cint.frontend.SplitAnchorResolver
                .anchor(source, outer, startByte, endByte)
        assertEquals("javascript", anchor.language)
        assertEquals(region.identityKey, anchor.regionKey)
        assertTrue(anchor.unitFingerprint != null)
    }
}
