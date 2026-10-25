package dev.cint.contracts.util

import kotlinx.serialization.Serializable

@Serializable
data class Unavailable(
    val reason: UnavailableReason,
    val message: String,
    val retryAfter: Long? = null,
) {
    companion object {
        fun providerUnavailable(message: String = "Semantic provider unavailable"): Unavailable =
            Unavailable(UnavailableReason.PROVIDER_UNAVAILABLE, message)

        fun timeout(message: String = "Request timed out"): Unavailable = Unavailable(UnavailableReason.TIMEOUT, message)

        fun notSupported(message: String = "Operation not supported"): Unavailable = Unavailable(UnavailableReason.NOT_SUPPORTED, message)

        fun invalidRequest(message: String): Unavailable = Unavailable(UnavailableReason.INVALID_REQUEST, message)

        fun internalError(message: String): Unavailable = Unavailable(UnavailableReason.INTERNAL_ERROR, message)

        fun cancelled(message: String = "Request cancelled"): Unavailable = Unavailable(UnavailableReason.CANCELLED, message)
    }
}

@Serializable
enum class UnavailableReason {
    PROVIDER_UNAVAILABLE,
    TIMEOUT,
    NOT_SUPPORTED,
    INVALID_REQUEST,
    INTERNAL_ERROR,
    CANCELLED,
    LSP_NOT_RUNNING,
    LSP_NOT_INITIALIZED,
    CAPABILITY_NOT_SUPPORTED,
}
