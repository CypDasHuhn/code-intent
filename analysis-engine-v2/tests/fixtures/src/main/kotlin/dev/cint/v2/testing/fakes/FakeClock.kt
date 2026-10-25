package dev.cint.v2.testing.fakes

import dev.cint.contracts.util.Deadline
import kotlinx.datetime.Instant

class FakeClock(start: Instant = DEFAULT_INSTANT) {
    var now: Instant = start
        private set

    fun advanceByMillis(millis: Long): Instant {
        require(millis >= 0) { "Cannot move a fake clock backwards by $millis ms" }
        now = Instant.fromEpochMilliseconds(now.toEpochMilliseconds() + millis)
        return now
    }

    fun deadlineIn(millis: Long): Deadline {
        require(millis >= 0) { "Deadline offset must not be negative: $millis" }
        return Deadline.fromMillis(now.toEpochMilliseconds() + millis)
    }

    fun isExpired(deadline: Deadline): Boolean = now >= deadline.instant

    fun remainingMillis(deadline: Deadline): Long =
        (deadline.instant.toEpochMilliseconds() - now.toEpochMilliseconds()).coerceAtLeast(0L)

    fun epochMillis(): Long = now.toEpochMilliseconds()

    companion object {
        val DEFAULT_INSTANT: Instant = Instant.parse("2024-01-01T00:00:00Z")
    }
}
