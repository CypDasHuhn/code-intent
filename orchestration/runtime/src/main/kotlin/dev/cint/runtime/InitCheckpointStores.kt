package dev.cint.runtime

import dev.cint.contracts.ports.InitCheckpointStore
import dev.cint.contracts.util.ContractResult

/**
 * Runtime facade for init checkpoint persistence.
 *
 * Apps (including MCP) configure checkpoint storage through this object so they
 * do not import the `knowledge-persistence` adapter. The adapter owns the
 * checkpoint document layout, atomic writes, and cross-process locking.
 */
object InitCheckpointStores {
    fun open(
        rootPath: String?,
        checkpointPath: String?,
    ): ContractResult<InitCheckpointStore> =
        dev.cint.knowledge.persistence.InitCheckpointStores
            .open(rootPath, checkpointPath)

    fun inMemory(): InitCheckpointStore =
        dev.cint.knowledge.persistence.InitCheckpointStores
            .inMemory()
}
