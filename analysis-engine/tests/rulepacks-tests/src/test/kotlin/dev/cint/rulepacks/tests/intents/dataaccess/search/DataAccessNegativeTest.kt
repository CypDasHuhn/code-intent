package dev.cint.rulepacks.tests.intents.dataaccess.search

import dev.cint.rulepacks.intents.dataaccess.LOOKUP_INTENT
import dev.cint.rulepacks.intents.dataaccess.SEARCH_INTENT
import dev.cint.rulepacks.tests.intents.dataaccess.support.candidateTexts
import dev.cint.rulepacks.tests.intents.dataaccess.support.dataAccess
import kotlin.test.Test
import kotlin.test.assertTrue

class DataAccessNegativeTest {
    @Test
    fun tokenSuffixCollisionsDoNotEstablishLookupOrSearch() {
        val text =
            """
            fun f(key: String, i: Int) {
                val first = mutable[key]
                val second = bitmap[i]
                val third = predict.getValue(key)
                restore.filter { true }
            }
            """.trimIndent()

        val analysis = dataAccess("kotlin", text)

        analysis.assertNoSource(LOOKUP_INTENT)
        analysis.assertNoSource(SEARCH_INTENT)
    }

    @Test
    fun bareConfiguredCallsAreCandidatesOnly() {
        val text =
            """
            fun f(id: String) {
                lookup(id)
                getValue()
                valueOf("x")
                from("table")
                select("value")
            }
            """.trimIndent()

        val analysis = dataAccess("kotlin", text)

        analysis.assertNoSource(LOOKUP_INTENT)
        analysis.assertNoSource(SEARCH_INTENT)
        assertTrue(analysis.candidateTexts(LOOKUP_INTENT).any { it.startsWith("lookup(") })
        assertTrue(analysis.candidateTexts(LOOKUP_INTENT).any { it.startsWith("getValue(") })
        assertTrue(analysis.candidateTexts(LOOKUP_INTENT).any { it.startsWith("valueOf(") })
        assertTrue(analysis.candidateTexts(SEARCH_INTENT).any { it.startsWith("from(") })
        assertTrue(analysis.candidateTexts(SEARCH_INTENT).any { it.startsWith("select(") })
    }
}
