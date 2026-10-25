package dev.cint.contracts.util

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName

@Serializable
sealed interface CancellationToken {
    val isCancelled: Boolean

    fun throwIfCancelled(): ContractResult<out Unit> =
        if (isCancelled) ResultFactory.failure(ContractError.Cancelled("operation"))
        else ResultFactory.success<Unit>(Unit)

    companion object {
        val NONE: CancellationToken = NotCancelled()

        fun fromBoolean(cancelled: Boolean): CancellationToken =
            if (cancelled) CancelledToken else NONE
    }
}

@Serializable
@SerialName("NotCancelled")
class NotCancelled() : CancellationToken {
    override val isCancelled: Boolean = false
}

@Serializable
@SerialName("CancelledToken")
object CancelledToken : CancellationToken {
    override val isCancelled: Boolean = true
}

@Serializable
data class CancellationException(val message: String)