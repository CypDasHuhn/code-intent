package dev.cint.contracts.ports

import dev.cint.contracts.init.InitCheckpoint
import dev.cint.contracts.util.CancellationToken
import dev.cint.contracts.util.ContractResult

interface InitCheckpointStore {
    fun load(
        workflowId: String,
        cancellation: CancellationToken = CancellationToken.NONE,
    ): ContractResult<InitCheckpoint?>

    fun save(
        checkpoint: InitCheckpoint,
        cancellation: CancellationToken = CancellationToken.NONE,
    ): ContractResult<Unit>

    fun update(
        workflowId: String,
        cancellation: CancellationToken = CancellationToken.NONE,
        transform: (InitCheckpoint?) -> ContractResult<InitCheckpoint?>,
    ): ContractResult<InitCheckpoint?>
}
