package dev.cint.contracts.util

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@Serializable
sealed interface CancellationToken {
    val isCancelled: Boolean

    fun throwIfCancelled(): ContractResult<out Unit> =
        if (isCancelled) {
            ResultFactory.failure(ContractError.Cancelled("operation"))
        } else {
            ResultFactory.success<Unit>(Unit)
        }

    companion object {
        val NONE: CancellationToken = NotCancelled()

        fun fromBoolean(cancelled: Boolean): CancellationToken = if (cancelled) CancelledToken else NONE

        fun from(provider: () -> Boolean): CancellationToken = DynamicCancellationToken(provider)
    }
}

@Serializable
@SerialName("NotCancelled")
class NotCancelled : CancellationToken {
    override val isCancelled: Boolean = false
}

@Serializable
@SerialName("CancelledToken")
object CancelledToken : CancellationToken {
    override val isCancelled: Boolean = true
}

@Serializable
@SerialName("DynamicCancellationToken")
class DynamicCancellationToken(
    @Transient private val provider: () -> Boolean = { false },
) : CancellationToken {
    override val isCancelled: Boolean
        get() = provider()
}

@Serializable
data class CancellationException(
    val message: String,
)
