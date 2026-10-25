package dev.cint.ai

import dev.cint.contracts.util.CancellationToken
import dev.cint.contracts.util.ContractResult

data class AiHttpResponse(
    val status: Int,
    val body: String,
)

fun interface AiTransport {
    fun post(
        body: String,
        cancellation: CancellationToken,
    ): ContractResult<AiHttpResponse>
}
