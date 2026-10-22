package dev.cint.core.facts.oracle

import dev.cint.core.facts.model.SourceFile
import java.util.concurrent.TimeoutException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class RecordingOracle(
    private val result: (SourceFile, Int) -> TypeFact?,
) : TypeOracle {
    var calls = 0
        private set

    override fun typeAt(
        file: SourceFile,
        byteOffset: Int,
    ): TypeFact? {
        calls++
        return result(file, byteOffset)
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

private class CachingDelegate :
    TypeOracle,
    OracleMetrics {
    var hoverCalls = 0
        private set
    var delegateHits = 0
        private set
    private var lookups = 0

    override fun typeAt(
        file: SourceFile,
        byteOffset: Int,
    ): TypeFact? {
        lookups++
        return if (lookups == 1) {
            hoverCalls++
            TypeFact("Acme.Type")
        } else {
            delegateHits++
            TypeFact("Acme.Type")
        }
    }

    override fun oracleStats(): OracleStats =
        OracleStats(
            hoverCalls = hoverCalls.toLong(),
            delegateHits = delegateHits.toLong(),
        )
}

class OracleSessionTest {
    private val file = SourceFile("Sample.kt", "fun f() {}")

    @Test
    fun memoizesPositiveAndNegativeResultsPerOffset() {
        val delegate = RecordingOracle { _, offset -> if (offset == 1) TypeFact("Acme.Type") else null }
        val session = OracleSession(delegate)

        assertEquals("Acme.Type", session.typeAt(file, 1)?.fqName)
        assertEquals("Acme.Type", session.typeAt(file, 1)?.fqName)
        assertNull(session.typeAt(file, 2))
        assertNull(session.typeAt(file, 2))

        assertEquals(2, delegate.calls)
        val stats = session.oracleStats()
        assertEquals(4L, stats.requests)
        assertEquals(2L, stats.queries)
        assertEquals(2L, stats.cacheHits)
        assertEquals(2L, stats.sessionHits)
        assertEquals(0L, stats.timeouts)
    }

    @Test
    fun reusingASessionWithNewTextResetsTheAnalysisAndRefreshesTheType() {
        val delegate = RecordingOracle { file, _ -> TypeFact(file.text) }
        val session = OracleSession(delegate)

        assertEquals("first", session.typeAt(SourceFile("Sample.kt", "first"), 1)?.fqName)
        assertEquals("second", session.typeAt(SourceFile("Sample.kt", "second"), 1)?.fqName)

        assertEquals(2, delegate.calls)
        val stats = session.oracleStats()
        assertEquals(1L, stats.requests)
        assertEquals(1L, stats.queries)
        assertEquals(0L, stats.sessionHits)
    }

    @Test
    fun duplicateTimeoutsAreMemoizedPerOffset() {
        val delegate = TimeoutOracle()
        val session = OracleSession(delegate, OracleBudget(maxTimeouts = 5, maxFailures = 10))

        assertNull(session.typeAt(file, 1))
        assertNull(session.typeAt(file, 1))

        assertEquals(1, delegate.calls)
        val stats = session.oracleStats()
        assertEquals(2L, stats.requests)
        assertEquals(1L, stats.queries)
        assertEquals(1L, stats.sessionHits)
        assertEquals(1L, stats.timeouts)
        assertEquals(1L, stats.failures)
    }

    @Test
    fun timeoutsOpenTheCircuitAndDegradeToNull() {
        val delegate = TimeoutOracle()
        val session = OracleSession(delegate, OracleBudget(maxTimeouts = 2, maxFailures = 10))

        repeat(5) { offset -> assertNull(session.typeAt(file, offset)) }

        assertEquals(2, delegate.calls)
        val stats = session.oracleStats()
        assertEquals(2L, stats.queries)
        assertEquals(2L, stats.timeouts)
        assertEquals(2L, stats.failures)
        assertEquals(3L, stats.blocked)
    }

    @Test
    fun failuresOpenTheCircuitAndDegradeToNull() {
        val delegate =
            RecordingOracle { _, _ ->
                throw IllegalStateException("oracle unavailable")
            }
        val session = OracleSession(delegate, OracleBudget(maxTimeouts = 10, maxFailures = 2))

        repeat(4) { offset -> assertNull(session.typeAt(file, offset)) }

        assertEquals(2, delegate.calls)
        val stats = session.oracleStats()
        assertEquals(0L, stats.timeouts)
        assertEquals(2L, stats.failures)
        assertEquals(2L, stats.blocked)
    }

    @Test
    fun delegateCacheHitsAreFoldedWithoutDoubleCounting() {
        val delegate = CachingDelegate()
        val session = OracleSession(delegate)

        assertEquals("Acme.Type", session.typeAt(file, 1)?.fqName)
        assertEquals("Acme.Type", session.typeAt(file, 2)?.fqName)
        assertEquals("Acme.Type", session.typeAt(file, 1)?.fqName)

        val stats = session.oracleStats()
        assertEquals(3L, stats.requests)
        assertEquals(1L, stats.queries)
        assertEquals(1L, stats.sessionHits)
        assertEquals(1L, stats.delegateHits)
        assertEquals(2L, stats.cacheHits)
        assertEquals(stats.requests, stats.queries + stats.sessionHits + stats.delegateHits)
    }

    @Test
    fun elapsedBudgetBoundsSlowQueries() {
        var now = 0L
        val delegate =
            RecordingOracle { _, offset ->
                now += 4_000_000L
                TypeFact("Type$offset")
            }
        val session =
            OracleSession(
                delegate,
                OracleBudget(maxTimeouts = 100, maxFailures = 100, maxElapsedMs = 10),
                nanoTime = { now },
            )

        repeat(50) { offset -> session.typeAt(file, offset) }

        assertEquals(3, delegate.calls)
        val stats = session.oracleStats()
        assertEquals(12L, stats.elapsedMs)
        assertEquals(47L, stats.blocked)
        assertTrue(stats.cacheHits == 0L)
    }
}
