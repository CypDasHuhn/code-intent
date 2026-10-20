package dev.cint.treesitter.language

import dev.cint.core.facts.model.SourceFile
import dev.cint.treesitter.KotlinParser
import kotlin.test.Test
import kotlin.test.assertTrue

class KotlinParserTest {
    @Test
    fun dumpsAst() {
        val text =
            """
            private val log = LoggerFactory.getLogger(Foo::class.java)
            fun f() {
                log.info("x")
                if (1 == 1) {
                    println("debug")
                }
                try {
                    work()
                } catch (failure: Exception) {
                    log.warn("failed", failure)
                }
            }
            """.trimIndent()

        val tree = KotlinParser().parse(SourceFile("Sample.kt", text))
        assertTrue(tree.root.descendants("call_expression").any())
    }
}
