package com.semanticpainter.treesitter

import com.semanticpainter.core.config.loadIntentConfig
import com.semanticpainter.core.model.SourceFile
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KotlinPurityTest {
    private val config = loadIntentConfig(null, "kotlin")

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

        assertTrue(KotlinPurity.isAnnotatedPure(KotlinPurity.findFunction(tree, "literal")!!, patterns))
        assertTrue(KotlinPurity.isAnnotatedPure(KotlinPurity.findFunction(tree, "contracted")!!, patterns))
        assertFalse(KotlinPurity.isAnnotatedPure(KotlinPurity.findFunction(tree, "inferred")!!, patterns))
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

        assertFalse(KotlinPurity.isAnnotatedPure(KotlinPurity.findFunction(tree, "effectful")!!, patterns))
    }
}
