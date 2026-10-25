package dev.cint.contracts.util

import kotlinx.serialization.Serializable
import kotlinx.datetime.Instant
import kotlinx.datetime.Clock

@Serializable
data class Deadline(
    val instant: Instant,
) {
    companion object {
        val NONE: Deadline = Deadline(Instant.parse("9999-12-31T23:59:59.999999999Z"))

        fun fromMillis(millis: Long): Deadline =
            Deadline(Instant.fromEpochMilliseconds(millis))

        fun fromNowMs(millis: Long): Deadline =
            Deadline(Instant.fromEpochMilliseconds(Clock.System.now().toEpochMilliseconds() + millis))

        @Suppress("UNUSED_PARAMETER")
        fun infinite(): Deadline = NONE
    }

    val isExpired: Boolean
        get() = Clock.System.now() >= instant

    val timeRemainingMs: Long
        get() = (instant.toEpochMilliseconds() - Clock.System.now().toEpochMilliseconds()).coerceAtLeast(0L)

    fun check(): ContractResult<out Unit> =
        if (isExpired) ResultFactory.failure(ContractError.Timeout("deadline", timeRemainingMs))
        else ResultFactory.success<Unit>(Unit)

    override fun toString(): String =
        if (this == NONE) "Deadline.NONE"
        else "Deadline($instant, remaining=${timeRemainingMs}ms)"
}