package dev.cint.v2.server

import dev.cint.contracts.ports.Runtime
import dev.cint.contracts.util.ContractError
import dev.cint.contracts.util.ContractResult
import dev.cint.contracts.util.ResultFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

internal class RuntimeGate {
    private val lock = ReentrantLock()

    fun <T> call(block: () -> T): T {
        lock.lock()
        try {
            return block()
        } finally {
            lock.unlock()
        }
    }

    fun shutdown(
        runtime: Runtime,
        timeoutMs: Long,
    ): ContractResult<Unit> {
        val acquired =
            try {
                lock.tryLock(timeoutMs, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                false
            }
        if (!acquired) {
            return ResultFactory.failure(
                ContractError.PreconditionFailed("runtime is still processing work during shutdown"),
            )
        }
        return try {
            runtime.shutdown()
        } finally {
            lock.unlock()
        }
    }
}
