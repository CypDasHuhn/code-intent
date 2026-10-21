package dev.cint.rulepacks.tests.intents.mutation

import dev.cint.api.CandidateReason
import dev.cint.api.RulePack
import dev.cint.rulepacks.intents.mutation.LOCAL_VALUE_MODIFY_INTENT
import dev.cint.rulepacks.intents.mutation.LOCAL_VALUE_MODIFY_RULE_ID
import dev.cint.rulepacks.intents.mutation.MutationRulePack
import dev.cint.rulepacks.tests.support.PackAnalysis
import dev.cint.rulepacks.tests.support.analyzePack
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MutationLocalValueModifyRuleTest {
    @Test
    fun assignmentAndIncrementWritesEmitLocalValueModify() {
        val text =
            """
            fun f() {
                var count = 0
                var total = 0
                count = 1
                count++
                total += count
            }
            """.trimIndent()

        val analysis = analyze("kotlin", text)

        assertEquals(
            setOf("count = 1", "count++", "total += count"),
            analysis.texts(LOCAL_VALUE_MODIFY_INTENT).toSet(),
        )
        assertTrue(analysis.source(LOCAL_VALUE_MODIFY_INTENT).all { it.rule == LOCAL_VALUE_MODIFY_RULE_ID })
    }

    @Test
    fun configuredMutableCollectionOperationsEmitLocalValueModify() {
        val text =
            """
            fun f() {
                val items = mutableListOf<String>()
                items.add("x")
                val values = mutableMapOf<String, Int>()
                values.put("x", 1)
            }
            """.trimIndent()

        val analysis = analyze("kotlin", text)

        assertEquals(
            setOf("items.add(\"x\")", "values.put(\"x\", 1)"),
            analysis.texts(LOCAL_VALUE_MODIFY_INTENT).toSet(),
        )
    }

    @Test
    fun loopCountersAreNotPaintedButOtherWritesAre() {
        val text =
            """
            fun f() {
                var i = 0
                var total = 0
                while (i < 3) {
                    i++
                    total += i
                }
            }
            """.trimIndent()

        val analysis = analyze("kotlin", text)

        assertEquals(setOf("total += i"), analysis.texts(LOCAL_VALUE_MODIFY_INTENT).toSet())
    }

    @Test
    fun ambiguousMethodMutationsYieldCandidates() {
        val text =
            """
            fun f() {
                handler.mutate()
            }
            """.trimIndent()

        val analysis = analyze("kotlin", text)
        val candidate = analysis.candidates(LOCAL_VALUE_MODIFY_INTENT).single()

        assertEquals(CandidateReason.PACK_DOMAIN, candidate.reason)
        assertEquals("rule:$LOCAL_VALUE_MODIFY_RULE_ID", candidate.source)
        assertTrue(candidate.aiEligible)
        assertTrue(analysis.source(LOCAL_VALUE_MODIFY_INTENT).isEmpty())
    }

    @Test
    fun customMutableOperationsAreEstablished() {
        val pack =
            MutationRulePack().configure(
                mapOf("kotlin" to mapOf("mutableOperations" to listOf("frobnicate"))),
            )
        val text =
            """
            fun f() {
                widget.frobnicate()
            }
            """.trimIndent()

        val analysis = analyze("kotlin", text, pack)

        assertEquals(setOf("widget.frobnicate()"), analysis.texts(LOCAL_VALUE_MODIFY_INTENT).toSet())
    }

    @Test
    fun assignmentPatternsCanExcludeIncrements() {
        val pack =
            MutationRulePack().configure(
                mapOf("kotlin" to mapOf("assignmentPatterns" to listOf("assignment"))),
            )
        val text =
            """
            fun f() {
                var count = 0
                count = 1
                count++
            }
            """.trimIndent()

        val analysis = analyze("kotlin", text, pack)

        assertEquals(setOf("count = 1"), analysis.texts(LOCAL_VALUE_MODIFY_INTENT).toSet())
    }

    @Test
    fun persistenceWinsOverMutableCollectionOperations() {
        val text =
            """
            fun f(item: Item, local: MutableList<Item>) {
                repository.delete(item)
                local.delete(item)
            }
            """.trimIndent()

        val analysis = analyze("kotlin", text)

        assertEquals(setOf("repository.delete(item)"), analysis.texts("DATA_MODIFY").toSet())
        assertEquals(setOf("local.delete(item)"), analysis.texts(LOCAL_VALUE_MODIFY_INTENT).toSet())
    }

    private fun analyze(
        language: String,
        text: String,
        pack: RulePack = MutationRulePack(),
    ): PackAnalysis = analyzePack(language, text, listOf(pack))
}
