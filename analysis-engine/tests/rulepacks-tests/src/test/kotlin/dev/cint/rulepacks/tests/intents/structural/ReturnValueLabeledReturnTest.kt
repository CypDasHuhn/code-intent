package dev.cint.rulepacks.tests.intents.structural

import dev.cint.rulepacks.intents.structural.RETURN_VALUE_INTENT
import dev.cint.rulepacks.intents.structural.ReturnValueRulePack
import dev.cint.rulepacks.tests.support.analyzePack
import kotlin.test.Test
import kotlin.test.assertEquals

class ReturnValueLabeledReturnTest {
    @Test
    fun labeledReturnValuesEmitAndBareLabeledReturnsDoNot() {
        val text =
            """
            fun f(values: List<Int>) {
                values.map { return@map it + 1 }
                values.forEach { return@forEach }
            }
            """.trimIndent()

        val analysis = analyzePack("kotlin", text, listOf(ReturnValueRulePack()))

        assertEquals(listOf("it + 1"), analysis.texts(RETURN_VALUE_INTENT))
        assertEquals(1, analysis.source(RETURN_VALUE_INTENT).size)
    }
}
