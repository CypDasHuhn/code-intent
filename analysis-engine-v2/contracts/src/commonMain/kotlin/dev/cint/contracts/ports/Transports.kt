package dev.cint.contracts.ports

import dev.cint.contracts.ipc.IpcMessage
import dev.cint.contracts.mcp.McpNotification
import dev.cint.contracts.mcp.McpResourceReadRequest
import dev.cint.contracts.mcp.McpResourceReadResult
import dev.cint.contracts.mcp.McpToolCall
import dev.cint.contracts.mcp.McpToolResult
import dev.cint.contracts.util.CancellationToken
import dev.cint.contracts.util.ContractResult

sealed interface IpcReceive {
    data class Message(val message: IpcMessage) : IpcReceive
    data object Empty : IpcReceive
    data object Closed : IpcReceive
}

interface IpcTransport {
    fun send(
        message: IpcMessage,
        cancellation: CancellationToken = CancellationToken.NONE,
    ): ContractResult<Unit>

    fun receive(cancellation: CancellationToken = CancellationToken.NONE): ContractResult<IpcReceive>

    fun close(): ContractResult<Unit>
}

interface McpTransport {
    fun call(
        request: McpToolCall,
        cancellation: CancellationToken = CancellationToken.NONE,
    ): ContractResult<McpToolResult>

    fun readResource(
        request: McpResourceReadRequest,
        cancellation: CancellationToken = CancellationToken.NONE,
    ): ContractResult<McpResourceReadResult>

    fun notify(
        notification: McpNotification,
        cancellation: CancellationToken = CancellationToken.NONE,
    ): ContractResult<Unit>

    fun close(): ContractResult<Unit>
}
