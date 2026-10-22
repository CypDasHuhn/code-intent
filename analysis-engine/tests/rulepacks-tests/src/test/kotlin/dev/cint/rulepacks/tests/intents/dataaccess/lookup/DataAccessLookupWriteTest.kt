package dev.cint.rulepacks.tests.intents.dataaccess.lookup

import dev.cint.rulepacks.intents.dataaccess.LOOKUP_INTENT
import dev.cint.rulepacks.tests.intents.dataaccess.support.dataAccess
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DataAccessLookupWriteTest {
    @Test
    fun knownMapWritesAreNotLookup() {
        val text =
            """
            fun f(id: String, status: Int) {
                statusById[id] = status
                statusById[id] += 1
                statusById[id]++
            }
            """.trimIndent()

        val analysis = dataAccess("kotlin", text)

        analysis.assertNoSource(LOOKUP_INTENT)
    }

    @Test
    fun knownMapReadsInsideWriteExpressionsStayLookup() {
        val text =
            """
            fun f(id: String, value: Int, copy: Int, active: String) {
                copy = statusById[id]
                copy += statusByCode[active]
                codeMap[id].field = value
                translationTable[statusById[id]] = value
            }
            """.trimIndent()

        val texts = dataAccess("kotlin", text).texts(LOOKUP_INTENT)

        assertTrue(texts.any { it == "statusById[id]" })
        assertTrue(texts.any { it == "statusByCode[active]" })
        assertTrue(texts.any { it == "codeMap[id]" })
        assertTrue(texts.none { it.startsWith("translationTable[") })
        assertEquals(2, texts.count { it == "statusById[id]" })
    }

    @Test
    fun structuralIncrementAndDecrementTargetsAreNotLookup() {
        val cases =
            mapOf(
                "kotlin" to
                    """
                    fun f(counts: IntArray, i: Int, statusById: MutableMap<String, Int>) {
                        counts[i - 1]++
                        statusById["x-y"]++
                        --statusById["x-y"]
                    }
                    """.trimIndent(),
                "javascript" to
                    """
                    function f(counts, i, statusById) {
                        counts[i - 1]++;
                        statusById["x-y"]++;
                        --statusById["x-y"];
                    }
                    """.trimIndent(),
                "typescript" to
                    """
                    function f(counts: number[], i: number, statusById: Map<string, number>) {
                        counts[i - 1]++;
                        statusById["x-y"]++;
                        --statusById["x-y"];
                    }
                    """.trimIndent(),
                "csharp" to
                    """
                    class C {
                        void F(int[] counts, int i, Dictionary<string, int> statusById) {
                            counts[i - 1]++;
                            statusById["x-y"]++;
                            --statusById["x-y"];
                        }
                    }
                    """.trimIndent(),
            )

        for ((language, text) in cases) {
            dataAccess(language, text).assertNoSource(LOOKUP_INTENT)
        }
    }

    @Test
    fun readsInsideStructuralIncrementAndAssignmentTargetsStayLookup() {
        val text =
            """
            fun f(id: String, value: Int) {
                statusById[otherById[id] - 1]++
                translationTable[statusById[id]] = value
            }
            """.trimIndent()

        val texts = dataAccess("kotlin", text).texts(LOOKUP_INTENT)

        assertTrue(texts.any { it == "otherById[id]" })
        assertTrue(texts.any { it == "statusById[id]" })
        assertTrue(texts.none { it.startsWith("statusById[otherById") })
        assertTrue(texts.none { it.startsWith("translationTable[") })
    }

    @Test
    fun mixedWriteTargetExcludesOnlyAssignedIndex() {
        val text =
            """
            fun f(id: String) {
                statusById[id] = otherMap[id]
            }
            """.trimIndent()

        val texts = dataAccess("kotlin", text).texts(LOOKUP_INTENT)

        assertTrue(texts.none { it == "statusById[id]" })
        assertTrue(texts.any { it == "otherMap[id]" })
    }
}
