package dev.cint.treesitter.language

import dev.cint.core.facts.model.SourceFile
import dev.cint.treesitter.KotlinParser
import dev.cint.treesitter.KotlinSyntaxModel
import dev.cint.treesitter.support.testConfig
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KotlinPurityTest {
    private val config = testConfig("kotlin")

    @Test
    fun detectsConfiguredPureAnnotations() {
        val text =
            """
            @Pure
            fun literal(): String = "test"

            @Contract(pure = true)
            fun contracted(): String = "test"

            fun inferred(): String = "test"
            """.trimIndent()

        val tree = KotlinParser().parse(SourceFile("Purity.kt", text))
        val patterns = config.pureAnnotations

        assertTrue(KotlinSyntaxModel.isAnnotatedPure(KotlinSyntaxModel.findFunction(tree, "literal")!!, patterns))
        assertTrue(KotlinSyntaxModel.isAnnotatedPure(KotlinSyntaxModel.findFunction(tree, "contracted")!!, patterns))
        assertFalse(KotlinSyntaxModel.isAnnotatedPure(KotlinSyntaxModel.findFunction(tree, "inferred")!!, patterns))
    }

    @Test
    fun doesNotTreatOtherContractFlagsAsPure() {
        val text =
            """
            @Contract(pure = false)
            fun effectful(): String = "test"
            """.trimIndent()

        val tree = KotlinParser().parse(SourceFile("Purity.kt", text))
        val patterns = config.pureAnnotations

        assertFalse(KotlinSyntaxModel.isAnnotatedPure(KotlinSyntaxModel.findFunction(tree, "effectful")!!, patterns))
    }
}
