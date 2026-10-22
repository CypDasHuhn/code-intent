package dev.cint.core.facts.oracle

import dev.cint.core.facts.model.SourceFile
import dev.cint.core.support.hashing.contentHash
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

data class TypeFact(
    val fqName: String,
    val source: String = "lsp",
    val confidence: Double = 1.0,
)

data class OracleStats(
    val requests: Long = 0,
    val hoverCalls: Long = 0,
    val sessionHits: Long = 0,
    val delegateHits: Long = 0,
    val timeouts: Long = 0,
    val failures: Long = 0,
    val blocked: Long = 0,
    val elapsedNanos: Long = 0,
) {
    val queries: Long get() = hoverCalls

    val cacheHits: Long get() = sessionHits + delegateHits

    val elapsedMs: Long get() = elapsedNanos / 1_000_000L

    operator fun plus(other: OracleStats): OracleStats =
        OracleStats(
            requests = requests + other.requests,
            hoverCalls = hoverCalls + other.hoverCalls,
            sessionHits = sessionHits + other.sessionHits,
            delegateHits = delegateHits + other.delegateHits,
            timeouts = timeouts + other.timeouts,
            failures = failures + other.failures,
            blocked = blocked + other.blocked,
            elapsedNanos = elapsedNanos + other.elapsedNanos,
        )

    companion object {
        val EMPTY = OracleStats()
    }
}

interface OracleMetrics {
    fun oracleStats(): OracleStats
}

data class OracleBudget(
    val maxTimeouts: Int = 2,
    val maxFailures: Int = 3,
    val maxElapsedMs: Long = 1_500L,
) {
    init {
        require(maxTimeouts > 0) { "maxTimeouts must be positive" }
        require(maxFailures > 0) { "maxFailures must be positive" }
        require(maxElapsedMs > 0) { "maxElapsedMs must be positive" }
    }

    val maxElapsedNanos: Long get() = TimeUnit.MILLISECONDS.toNanos(maxElapsedMs)
}

interface TypeOracle {
    fun typeAt(
        file: SourceFile,
        byteOffset: Int,
    ): TypeFact?
}

class OracleSession(
    private val delegate: TypeOracle,
    private val budget: OracleBudget = OracleBudget(),
    private val nanoTime: () -> Long = System::nanoTime,
) : TypeOracle,
    OracleMetrics {
    private data class Identity(
        val path: String,
        val text: String,
        val content: String,
    )

    private data class Key(
        val path: String,
        val content: String,
        val offset: Int,
    )

    private sealed interface Outcome {
        data class Resolved(
            val fact: TypeFact?,
        ) : Outcome

        data object Failed : Outcome
    }

    private val cache = HashMap<Key, Outcome>()
    private var identity: Identity? = null
    private var delegateBaseline = delegateStats()
    private var requests = 0L
    private var delegateCalls = 0L
    private var sessionHits = 0L
    private var timeouts = 0L
    private var failures = 0L
    private var blocked = 0L
    private var elapsedNanos = 0L

    fun beginAnalysis(file: SourceFile) {
        synchronized(this) {
            reset(identityFor(file))
        }
    }

    override fun typeAt(
        file: SourceFile,
        byteOffset: Int,
    ): TypeFact? =
        synchronized(this) {
            val current = identityFor(file)
            requests++
            val key = Key(current.path, current.content, byteOffset)
            val cached = cache[key]
            if (cached != null) {
                sessionHits++
                return@synchronized when (cached) {
                    is Outcome.Resolved -> cached.fact
                    Outcome.Failed -> null
                }
            }
            if (tripped()) {
                blocked++
                return@synchronized null
            }
            delegateCalls++
            val started = nanoTime()
            try {
                val fact = delegate.typeAt(file, byteOffset)
                cache[key] = Outcome.Resolved(fact)
                fact
            } catch (timeout: TimeoutException) {
                timeouts++
                failures++
                cache[key] = Outcome.Failed
                null
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                failures++
                cache[key] = Outcome.Failed
                null
            } catch (error: Exception) {
                if (isTimeout(error)) timeouts++ else failures++
                cache[key] = Outcome.Failed
                null
            } finally {
                elapsedNanos += (nanoTime() - started).coerceAtLeast(0L)
            }
        }

    override fun oracleStats(): OracleStats =
        synchronized(this) {
            val delta = delegateDelta()
            OracleStats(
                requests = requests,
                hoverCalls = delta?.hoverCalls ?: delegateCalls,
                sessionHits = sessionHits,
                delegateHits = delta?.delegateHits ?: 0L,
                timeouts = delta?.timeouts ?: timeouts,
                failures = delta?.failures ?: failures,
                blocked = blocked,
                elapsedNanos = elapsedNanos,
            )
        }

    internal fun source(): TypeOracle = delegate

    private fun identityFor(file: SourceFile): Identity {
        val current = identity
        if (current != null && current.path == file.path && current.text == file.text) return current
        return Identity(file.path, file.text, contentHash(file.text)).also(::reset)
    }

    private fun reset(next: Identity) {
        identity = next
        cache.clear()
        delegateBaseline = delegateStats()
        requests = 0L
        delegateCalls = 0L
        sessionHits = 0L
        timeouts = 0L
        failures = 0L
        blocked = 0L
        elapsedNanos = 0L
    }

    private fun delegateStats(): OracleStats = (delegate as? OracleMetrics)?.oracleStats() ?: OracleStats.EMPTY

    private fun delegateDelta(): OracleStats? {
        val metrics = delegate as? OracleMetrics ?: return null
        val current = metrics.oracleStats()
        return OracleStats(
            requests = (current.requests - delegateBaseline.requests).coerceAtLeast(0L),
            hoverCalls = (current.hoverCalls - delegateBaseline.hoverCalls).coerceAtLeast(0L),
            sessionHits = (current.sessionHits - delegateBaseline.sessionHits).coerceAtLeast(0L),
            delegateHits = (current.delegateHits - delegateBaseline.delegateHits).coerceAtLeast(0L),
            timeouts = (current.timeouts - delegateBaseline.timeouts).coerceAtLeast(0L),
            failures = (current.failures - delegateBaseline.failures).coerceAtLeast(0L),
            blocked = (current.blocked - delegateBaseline.blocked).coerceAtLeast(0L),
            elapsedNanos = (current.elapsedNanos - delegateBaseline.elapsedNanos).coerceAtLeast(0L),
        )
    }

    private fun tripped(): Boolean =
        timeouts >= budget.maxTimeouts ||
            failures >= budget.maxFailures ||
            elapsedNanos >= budget.maxElapsedNanos

    private fun isTimeout(error: Throwable): Boolean {
        var current: Throwable? = error
        while (current != null) {
            if (current is TimeoutException) return true
            current = current.cause
        }
        return false
    }
}

object NullOracle : TypeOracle {
    override fun typeAt(
        file: SourceFile,
        byteOffset: Int,
    ): TypeFact? = null
}

class MapOracle(
    private val types: Map<Int, TypeFact> = emptyMap(),
) : TypeOracle {
    override fun typeAt(
        file: SourceFile,
        byteOffset: Int,
    ): TypeFact? = types[byteOffset]
}
