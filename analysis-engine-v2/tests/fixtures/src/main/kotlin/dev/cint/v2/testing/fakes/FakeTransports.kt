package dev.cint.v2.testing.fakes

import dev.cint.contracts.ipc.IpcMessage
import dev.cint.contracts.mcp.McpNotification
import dev.cint.contracts.mcp.McpResourceReadRequest
import dev.cint.contracts.mcp.McpResourceReadResult
import dev.cint.contracts.mcp.McpToolCall
import dev.cint.contracts.mcp.McpToolResult
import dev.cint.contracts.ports.IpcReceive
import dev.cint.contracts.ports.IpcTransport
import dev.cint.contracts.ports.McpTransport
import dev.cint.contracts.util.CancellationToken
import dev.cint.contracts.util.ContractError
import dev.cint.contracts.util.ContractResult
import dev.cint.contracts.util.ResultFactory

class FakeIpcTransport(
    val inbox: MutableList<IpcReceive> = mutableListOf(),
    val recorder: FakeRecorder = FakeRecorder(),
) : IpcTransport {
    val sent: MutableList<IpcMessage> = mutableListOf()
    var closed: Boolean = false
        private set

    override fun send(message: IpcMessage, cancellation: CancellationToken): ContractResult<Unit> {
        recorder.record("ipc.send", message::class.simpleName ?: "message")
        val checked = cancellation.throwIfCancelled()
        if (checked.isFailure) return checked
        if (closed) {
            return ResultFactory.failure(ContractError.PreconditionFailed("ipc transport is closed"))
        }
        sent.add(message)
        return ResultFactory.success(Unit)
    }

    override fun receive(cancellation: CancellationToken): ContractResult<IpcReceive> {
        recorder.record("ipc.receive", "queued=${inbox.size}")
        val checked = cancellation.throwIfCancelled()
        if (checked.isFailure) return ResultFactory.failure(checked.errorOrNull!!)
        if (closed) return ResultFactory.success(IpcReceive.Closed)
        return ResultFactory.success(inbox.removeFirstOrNull() ?: IpcReceive.Empty)
    }

    override fun close(): ContractResult<Unit> {
        recorder.record("ipc.close")
        closed = true
        return ResultFactory.success(Unit)
    }

    fun enqueue(receive: IpcReceive) {
        inbox.add(receive)
    }
}

class FakeMcpTransport(
    val results: MutableList<McpToolResult> = mutableListOf(),
    val resourceResults: MutableList<McpResourceReadResult> = mutableListOf(),
    private val callFactory: ((McpToolCall) -> ContractResult<McpToolResult>)? = null,
    val recorder: FakeRecorder = FakeRecorder(),
) : McpTransport {
    val calls: MutableList<McpToolCall> = mutableListOf()
    val resourceReads: MutableList<McpResourceReadRequest> = mutableListOf()
    val notifications: MutableList<McpNotification> = mutableListOf()
    var closed: Boolean = false
        private set

    override fun call(request: McpToolCall, cancellation: CancellationToken): ContractResult<McpToolResult> {
        recorder.record("mcp.call", request.name)
        val checked = cancellation.throwIfCancelled()
        if (checked.isFailure) return ResultFactory.failure(checked.errorOrNull!!)
        if (closed) {
            return ResultFactory.failure(ContractError.PreconditionFailed("mcp transport is closed"))
        }
        calls.add(request)
        callFactory?.let { return it(request) }
        val result = results.removeFirstOrNull()
            ?: return ResultFactory.failure(ContractError.NotFound("mcp-result", request.name))
        return ResultFactory.success(result)
    }

    override fun readResource(
        request: McpResourceReadRequest,
        cancellation: CancellationToken,
    ): ContractResult<McpResourceReadResult> {
        recorder.record("mcp.readResource", request.uri)
        val checked = cancellation.throwIfCancelled()
        if (checked.isFailure) return ResultFactory.failure(checked.errorOrNull!!)
        if (closed) {
            return ResultFactory.failure(ContractError.PreconditionFailed("mcp transport is closed"))
        }
        resourceReads.add(request)
        val result = resourceResults.removeFirstOrNull()
            ?: return ResultFactory.failure(ContractError.NotFound("mcp-resource", request.uri))
        return ResultFactory.success(result)
    }

    override fun notify(notification: McpNotification, cancellation: CancellationToken): ContractResult<Unit> {
        recorder.record("mcp.notify", notification::class.simpleName ?: "notification")
        val checked = cancellation.throwIfCancelled()
        if (checked.isFailure) return checked
        if (closed) {
            return ResultFactory.failure(ContractError.PreconditionFailed("mcp transport is closed"))
        }
        notifications.add(notification)
        return ResultFactory.success(Unit)
    }

    override fun close(): ContractResult<Unit> {
        recorder.record("mcp.close")
        closed = true
        return ResultFactory.success(Unit)
    }

    fun enqueue(result: McpToolResult) {
        results.add(result)
    }

    fun enqueueResource(result: McpResourceReadResult) {
        resourceResults.add(result)
    }
}

object FakePortCatalog {
    val implementedPorts: List<String> = listOf(
        "SyntaxEngine",
        "SemanticProvider",
        "LanguageSupport",
        "KnowledgeStore",
        "RulePackSource",
        "IntentPipeline",
        "Runtime",
        "Index",
        "HostSemanticChannel",
        "HostSemanticChannelProvider",
        "IpcTransport",
        "McpTransport",
        "RuleEvaluator",
        "RuleEvaluatorRegistry",
        "FallbackAi",
    )

    val deferredPorts: Map<String, String> = emptyMap()
}
