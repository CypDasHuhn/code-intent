package dev.cint.v2.syntax.treesitter

import dev.cint.contracts.ports.SyntaxEngine
import dev.cint.contracts.util.ContractError
import dev.cint.contracts.util.ContractResult
import dev.cint.contracts.util.ResultFactory

expect fun createSyntaxEngine(): SyntaxEngine

@Suppress("UNCHECKED_CAST")
internal fun <T> contractFailure(error: ContractError): ContractResult<T> =
    ResultFactory.failure<T>(error) as ContractResult<T>
