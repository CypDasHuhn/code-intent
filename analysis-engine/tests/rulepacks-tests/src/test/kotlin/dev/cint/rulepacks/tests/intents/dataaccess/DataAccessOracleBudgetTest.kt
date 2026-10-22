package dev.cint.rulepacks.tests.intents.dataaccess

import dev.cint.core.facts.model.SourceFile
import dev.cint.core.facts.oracle.OracleBudget
import dev.cint.core.facts.oracle.OracleSession
import dev.cint.core.facts.oracle.TypeFact
import dev.cint.core.facts.oracle.TypeOracle
import dev.cint.rulepacks.intents.dataaccess.DataAccessRulePack
import dev.cint.rulepacks.intents.dataaccess.LOOKUP_INTENT
import dev.cint.rulepacks.intents.dataaccess.SEARCH_INTENT
import dev.cint.rulepacks.tests.support.analyzePack
import dev.cint.rulepacks.tests.support.parsePackInput
import java.util.concurrent.TimeoutException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private class CountingOracle : TypeOracle {
    var calls = 0
        private set

    override fun typeAt(
        file: SourceFile,
        byteOffset: Int,
    ): TypeFact? {
        calls++
        return null
    }
}

private class TimeoutOracle : TypeOracle {
    var calls = 0
        private set

    override fun typeAt(
        file: SourceFile,
        byteOffset: Int,
    ): TypeFact? {
        calls++
        throw TimeoutException("hover timed out")
    }
}

private fun fixture(name: String): String = requireNotNull(DataAccessOracleBudgetTest::class.java.getResource("/fixtures/$name")).readText()

class DataAccessOracleBudgetTest {
    @Test
    fun nonLookupCallsDoNotQueryTheOracle() {
        val oracle = CountingOracle()
        val analysis =
            analyzePack(
                "kotlin",
                """
                fun f() {
                    thing.process()
                    value.compute()
                    thing.render()
                }
                """.trimIndent(),
                listOf(DataAccessRulePack()),
                oracle,
            )

        assertEquals(0, oracle.calls)
        assertEquals(0L, analysis.candidates.count { LOOKUP_INTENT in it.intents }.toLong())
    }

    @Test
    fun knownMapsAndConfiguredEvidenceShortCircuitTheOracle() {
        val oracle = CountingOracle()
        val analysis =
            analyzePack(
                "kotlin",
                """
                fun f(id: String) {
                    val status = statusById.getValue(id)
                    val mode = Status.valueOf(id)
                }
                """.trimIndent(),
                listOf(DataAccessRulePack()),
                oracle,
            )

        assertEquals(0, oracle.calls)
        assertTrue(analysis.texts(LOOKUP_INTENT).any { it.startsWith("statusById.getValue") })
        assertTrue(analysis.texts(LOOKUP_INTENT).any { it.startsWith("Status.valueOf") })
    }

    @Test
    fun nonTypeableReceiversAreNotQueried() {
        val oracle = CountingOracle()
        val analysis =
            analyzePack(
                "kotlin",
                """
                fun f(id: String) {
                    val value = "text".getValue(id)
                }
                """.trimIndent(),
                listOf(DataAccessRulePack()),
                oracle,
            )

        assertEquals(0, oracle.calls)
        assertTrue(analysis.candidateTexts(LOOKUP_INTENT).any { it.startsWith("\"text\".getValue") })
    }

    @Test
    fun timeoutBudgetTripsOnManyUnresolvedReceiversAndPreservesEstablishedEvidence() {
        val oracle = TimeoutOracle()
        val session = OracleSession(oracle, OracleBudget(maxTimeouts = 1, maxFailures = 10, maxElapsedMs = 60_000))
        val analysis = analyzePack(parsePackInput("kotlin", fixture("many-unresolved.kt.txt")), listOf(DataAccessRulePack()), session)

        assertEquals(1, oracle.calls)
        val stats = session.oracleStats()
        assertEquals(1L, stats.timeouts)
        assertTrue(stats.blocked > 0)
        assertTrue(analysis.texts(LOOKUP_INTENT).any { it.startsWith("statusById.getValue") })
        assertTrue(analysis.texts(LOOKUP_INTENT).any { it.startsWith("Status.valueOf") })
        assertTrue(analysis.texts(SEARCH_INTENT).any { it.startsWith("repository.findAll") })
    }

    @Test
    fun elapsedBudgetBoundsManyUnresolvedReceiversAndPreservesEstablishedEvidence() {
        var now = 0L
        val oracle =
            object : TypeOracle {
                var calls = 0

                override fun typeAt(
                    file: SourceFile,
                    byteOffset: Int,
                ): TypeFact? {
                    calls++
                    now += 2_000_000L
                    return null
                }
            }
        val session = OracleSession(oracle, OracleBudget(maxTimeouts = 100, maxFailures = 100, maxElapsedMs = 10), nanoTime = { now })
        val analysis = analyzePack(parsePackInput("kotlin", fixture("many-unresolved.kt.txt")), listOf(DataAccessRulePack()), session)

        assertTrue(oracle.calls <= 6)
        assertTrue(session.oracleStats().blocked > 0)
        assertEquals(0L, session.oracleStats().timeouts)
        assertTrue(analysis.texts(LOOKUP_INTENT).any { it.startsWith("statusById.getValue") })
        assertTrue(analysis.texts(LOOKUP_INTENT).any { it.startsWith("Status.valueOf") })
        assertTrue(analysis.texts(SEARCH_INTENT).any { it.startsWith("repository.findAll") })
    }
}
