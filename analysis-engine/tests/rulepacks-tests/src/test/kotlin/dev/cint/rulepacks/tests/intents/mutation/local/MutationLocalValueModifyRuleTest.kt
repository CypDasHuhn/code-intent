package dev.cint.rulepacks.tests.intents.mutation.local

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
    fun mutableLocalDeclarationsAreOffByDefault() {
        val text =
            """
            fun f() {
                var count = 0
                count = 1
            }
            """.trimIndent()

        val analysis = analyze("kotlin", text)

        assertEquals(setOf("count = 1"), analysis.texts(LOCAL_VALUE_MODIFY_INTENT).toSet())
    }

    @Test
    fun configuredMutableLocalDeclarationsCoverWrittenVarsOnly() {
        val pack =
            MutationRulePack().configure(
                mapOf("kotlin" to mapOf("mutableLocalDeclarations" to true)),
            )
        val text =
            """
            fun f() {
                var count = 0
                var untouched = 1
                val items = mutableListOf<String>()
                var total = 0
                items.add("x")
                count = 1
                total += count
            }
            """.trimIndent()

        val analysis = analyze("kotlin", text, pack)

        assertEquals(
            setOf("var count = 0", "items.add(\"x\")", "count = 1", "var total = 0", "total += count"),
            analysis.texts(LOCAL_VALUE_MODIFY_INTENT).toSet(),
        )
    }

    @Test
    fun configuredMutableLocalDeclarationsSkipPureCopyAndBookkeepingLocals() {
        val mutation =
            MutationRulePack().configure(
                mapOf("kotlin" to mapOf("mutableLocalDeclarations" to true)),
            )
        val localCopy = LocalCopyRulePack().configure(mapOf("kotlin" to mapOf("prefixes" to listOf("store_"))))
        val text =
            """
            fun f(db: Database) {
                @Bookkeeping
                var skipped = 0
                var store_db = db
                store_db = db
                skipped++
            }
            """.trimIndent()

        val analysis = analyzePack("kotlin", text, listOf(BookkeepingRulePack(), mutation, localCopy))

        assertTrue(analysis.source(LOCAL_VALUE_MODIFY_INTENT).isEmpty())
    }

    @Test
    fun explicitForCounterDeclarationsStaySuppressed() {
        val mutation =
            MutationRulePack().configure(
                mapOf("javascript" to mapOf("mutableLocalDeclarations" to true)),
            )
        val text =
            """
            function f() {
                for (var i = 0; i < 3; i++) {
                    consume(i);
                }
            }
            """.trimIndent()

        val analysis = analyze("javascript", text, mutation)

        assertTrue(analysis.source(LOCAL_VALUE_MODIFY_INTENT).isEmpty())
    }

    @Test
    fun configuredMutableLocalDeclarationsWorkAcrossJavascriptTypescriptAndCsharp() {
        val mutation =
            MutationRulePack().configure(
                mapOf(
                    "javascript" to mapOf("mutableLocalDeclarations" to true),
                    "typescript" to mapOf("mutableLocalDeclarations" to true),
                    "csharp" to mapOf("mutableLocalDeclarations" to true),
                ),
            )
        val cases =
            mapOf(
                "javascript" to
                    """
                    function f() {
                        let count = 0;
                        var total = 0;
                        const stable = 0;
                        count = 1;
                        total += count;
                        stable = 1;
                    }
                    """.trimIndent(),
                "typescript" to
                    """
                    function f() {
                        let count: number = 0;
                        var total = 0;
                        const stable = 0;
                        count = 1;
                        total += count;
                        stable = 1;
                    }
                    """.trimIndent(),
                "csharp" to
                    """
                    class C {
                        void F() {
                            var count = 0;
                            int total = 0;
                            const int stable = 0;
                            count = 1;
                            total += count;
                            stable = 1;
                        }
                    }
                    """.trimIndent(),
            )

        for ((language, text) in cases) {
            val texts = analyze(language, text, mutation).texts(LOCAL_VALUE_MODIFY_INTENT)
            assertTrue(texts.any { it.trimStart().startsWith("count") && "= 0" in it }, "$language: $texts")
            assertTrue(texts.any { it.trimStart().startsWith("total") && "= 0" in it }, "$language: $texts")
            assertTrue(
                texts.none { it.contains("const") || it.trimStart().startsWith("stable = 0") },
                "$language: $texts",
            )
        }
    }

    @Test
    fun multiDeclaratorMutationsEmitOnlyTheWrittenDeclarator() {
        val mutation =
            MutationRulePack().configure(
                mapOf(
                    "javascript" to mapOf("mutableLocalDeclarations" to true),
                    "typescript" to mapOf("mutableLocalDeclarations" to true),
                    "csharp" to mapOf("mutableLocalDeclarations" to true),
                ),
            )
        val cases =
            mapOf(
                "javascript" to
                    """
                    function f() {
                        let written = 0, untouched = 0;
                        written = 1;
                    }
                    """.trimIndent(),
                "typescript" to
                    """
                    function f() {
                        let written: number = 0, untouched = 0;
                        written = 1;
                    }
                    """.trimIndent(),
                "csharp" to
                    """
                    class C {
                        void F() {
                            int written = 0, untouched = 0;
                            written = 1;
                        }
                    }
                    """.trimIndent(),
            )

        for ((language, text) in cases) {
            val texts = analyze(language, text, mutation).texts(LOCAL_VALUE_MODIFY_INTENT)
            assertTrue(texts.any { it.contains("written") && "= 0" in it }, "$language: $texts")
            assertTrue(texts.none { it.contains("untouched") }, "$language: $texts")
        }
    }
}

private fun analyze(
    language: String,
    text: String,
    pack: RulePack = MutationRulePack(),
): PackAnalysis = analyzePack(language, text, listOf(BookkeepingRulePack(), pack, LocalCopyRulePack()))
