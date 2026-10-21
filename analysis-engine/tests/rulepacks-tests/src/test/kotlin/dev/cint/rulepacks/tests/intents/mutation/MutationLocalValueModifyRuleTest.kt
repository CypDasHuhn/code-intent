package dev.cint.rulepacks.tests.intents.mutation

import dev.cint.api.CandidateReason
import dev.cint.api.RulePack
import dev.cint.rulepacks.bookkeeping.BookkeepingRulePack
import dev.cint.rulepacks.intents.mutation.LOCAL_VALUE_MODIFY_INTENT
import dev.cint.rulepacks.intents.mutation.LOCAL_VALUE_MODIFY_RULE_ID
import dev.cint.rulepacks.intents.mutation.MutationRulePack
import dev.cint.rulepacks.intents.structural.LocalCopyRulePack
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
    fun externalAndMemberAssignmentsStayCandidates() {
        val text =
            """
            fun f(user: User) {
                var count = 0
                count = 1
                user.name = "x"
            }
            """.trimIndent()

        val analysis = analyze("kotlin", text)

        assertEquals(setOf("count = 1"), analysis.texts(LOCAL_VALUE_MODIFY_INTENT).toSet())
        assertEquals(
            setOf("user.name = \"x\""),
            analysis
                .candidates(LOCAL_VALUE_MODIFY_INTENT)
                .map {
                    text.substring(it.startByte, it.endByte)
                }.toSet(),
        )
    }

    @Test
    fun untypedDomainMutationNamesStayCandidates() {
        val text =
            """
            fun f() {
                handler.add("x")
            }
            """.trimIndent()

        val analysis = analyze("kotlin", text)

        assertTrue(analysis.source(LOCAL_VALUE_MODIFY_INTENT).isEmpty())
        assertEquals(1, analysis.candidates(LOCAL_VALUE_MODIFY_INTENT).size)
    }

    @Test
    fun ambiguousMethodMutationsYieldCandidates() {
        val text =
            """
            fun f() {
                handler.mutate()
                handler.updateProfile()
            }
            """.trimIndent()

        val analysis = analyze("kotlin", text)
        val candidates = analysis.candidates(LOCAL_VALUE_MODIFY_INTENT)

        assertEquals(2, candidates.size)
        assertTrue(candidates.all { it.reason == CandidateReason.PACK_DOMAIN })
        assertTrue(candidates.all { it.source == "rule:$LOCAL_VALUE_MODIFY_RULE_ID" })
        assertTrue(candidates.all { it.aiEligible })
        assertTrue(analysis.source(LOCAL_VALUE_MODIFY_INTENT).isEmpty())
    }

    @Test
    fun lowSignalPrefixesDoNotBecomeLocalCandidates() {
        val text =
            """
            fun f() {
                setup()
                setVisible(true)
                addEventListener("click", handler)
                applyPlugin(plugin)
            }
            """.trimIndent()

        val analysis = analyze("kotlin", text)

        assertTrue(analysis.candidates(LOCAL_VALUE_MODIFY_INTENT).isEmpty())
    }

    @Test
    fun customMutableOperationsAreEstablishedWithAReceiverProof() {
        val pack =
            MutationRulePack().configure(
                mapOf(
                    "kotlin" to
                        mapOf(
                            "mutableOperations" to listOf("frobnicate"),
                            "mutableReceiverNames" to listOf("widget"),
                        ),
                ),
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
    fun incrementPatternAcceptsOnlyIncrementWrites() {
        val pack =
            MutationRulePack().configure(
                mapOf("kotlin" to mapOf("assignmentPatterns" to listOf("increment"))),
            )
        val text =
            """
            fun f() {
                var count = 0
                count++
                count--
            }
            """.trimIndent()

        assertEquals(setOf("count++"), analyze("kotlin", text, pack).texts(LOCAL_VALUE_MODIFY_INTENT).toSet())
    }

    @Test
    fun decrementPatternAcceptsOnlyDecrementWrites() {
        val pack =
            MutationRulePack().configure(
                mapOf("kotlin" to mapOf("assignmentPatterns" to listOf("decrement"))),
            )
        val text =
            """
            fun f() {
                var count = 0
                count++
                count--
            }
            """.trimIndent()

        assertEquals(setOf("count--"), analyze("kotlin", text, pack).texts(LOCAL_VALUE_MODIFY_INTENT).toSet())
    }

    @Test
    fun persistenceWinsOverMutableCollectionOperations() {
        val text =
            """
            fun f(item: Item, items: MutableList<Item>) {
                repository.delete(item)
                items.delete(item)
            }
            """.trimIndent()

        val analysis = analyze("kotlin", text)

        assertEquals(setOf("repository.delete(item)"), analysis.texts("DATA_MODIFY").toSet())
        assertEquals(setOf("items.delete(item)"), analysis.texts(LOCAL_VALUE_MODIFY_INTENT).toSet())
    }

    @Test
    fun prefixLoopCountersAreNotPainted() {
        val kotlinText =
            """
            fun f() {
                var i = 3
                var total = 0
                while (i > 0) {
                    --i
                    ++i
                    total += i
                }
            }
            """.trimIndent()
        val kotlin = analyze("kotlin", kotlinText)

        assertEquals(setOf("total += i"), kotlin.texts(LOCAL_VALUE_MODIFY_INTENT).toSet())

        val csharpText =
            """
            class C {
                void F() {
                    int i = 0;
                    int total = 0;
                    while (i < 3) {
                        ++i;
                        total += i;
                    }
                }
            }
            """.trimIndent()
        val csharp = analyze("csharp", csharpText)

        assertTrue(csharp.texts(LOCAL_VALUE_MODIFY_INTENT).none { "++i" in it || "--i" in it })
    }

    @Test
    fun csharpLocalWrappersYieldCandidates() {
        val text =
            """
            class C {
                void F() {
                    handler.UpdateProfile();
                    handler.Mutate();
                    handler.MergeState();
                }
            }
            """.trimIndent()

        val analysis = analyze("csharp", text)
        val candidates = analysis.candidates(LOCAL_VALUE_MODIFY_INTENT)

        assertEquals(3, candidates.size)
        assertTrue(analysis.source(LOCAL_VALUE_MODIFY_INTENT).isEmpty())
    }

    private fun analyze(
        language: String,
        text: String,
        pack: RulePack = MutationRulePack(),
    ): PackAnalysis = analyzePack(language, text, listOf(BookkeepingRulePack(), pack, LocalCopyRulePack()))
}
