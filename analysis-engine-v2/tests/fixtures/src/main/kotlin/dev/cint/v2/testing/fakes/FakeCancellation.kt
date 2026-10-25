package dev.cint.v2.testing.fakes

import dev.cint.contracts.util.CancellationToken

object FakeCancellation {
    fun active(): CancellationToken = CancellationToken.NONE

    fun cancelled(): CancellationToken = CancellationToken.fromBoolean(true)

    fun flag(cancelled: Boolean): CancellationToken = CancellationToken.fromBoolean(cancelled)

    fun check(token: CancellationToken): Boolean = token.isCancelled

    fun throwIfCancelled(token: CancellationToken) {
        token.throwIfCancelled().getOrThrow()
    }
}

class CancellationScript(private val cancelAtCheck: Int? = null) {
    var checks: Int = 0
        private set

    fun next(): CancellationToken {
        checks += 1
        val cancelled = cancelAtCheck != null && checks >= cancelAtCheck
        return CancellationToken.fromBoolean(cancelled)
    }

    fun active(): Boolean = cancelAtCheck == null || checks < cancelAtCheck
}
