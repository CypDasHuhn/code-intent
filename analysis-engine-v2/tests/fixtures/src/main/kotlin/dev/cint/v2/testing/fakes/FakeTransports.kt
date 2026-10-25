package dev.cint.v2.testing.fakes

import dev.cint.contracts.ipc.IpcMessage
import dev.cint.contracts.mcp.McpNotification
import dev.cint.contracts.mcp.McpRequest
import dev.cint.contracts.mcp.McpResponse
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
    val responses: MutableList<McpResponse> = mutableListOf(),
    private val responseFactory: ((McpRequest) -> ContractResult<McpResponse>)? = null,
    val recorder: FakeRecorder = FakeRecorder(),
) : McpTransport {
    val requests: MutableList<McpRequest> = mutableListOf()
    val notifications: MutableList<McpNotification> = mutableListOf()
    var closed: Boolean = false
        private set

    override fun request(request: McpRequest, cancellation: CancellationToken): ContractResult<McpResponse> {
        recorder.record("mcp.request", request.toString().substringBefore("("))
        val checked = cancellation.throwIfCancelled()
        if (checked.isFailure) return ResultFactory.failure(checked.errorOrNull!!)
        if (closed) {
            return ResultFactory.failure(ContractError.PreconditionFailed("mcp transport is closed"))
        }
        requests.add(request)
        responseFactory?.let { return it(request) }
        val response = responses.removeFirstOrNull()
            ?: return ResultFactory.failure(ContractError.NotFound("mcp-response", request.toString()))
        return ResultFactory.success(response)
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

    fun enqueue(response: McpResponse) {
        responses.add(response)
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
