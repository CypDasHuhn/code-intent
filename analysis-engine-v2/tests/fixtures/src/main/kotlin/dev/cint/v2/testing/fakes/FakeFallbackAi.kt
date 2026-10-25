package dev.cint.v2.testing.fakes

import dev.cint.contracts.knowledge.KnowledgeChange
import dev.cint.contracts.ports.FallbackAi
import dev.cint.contracts.ports.FallbackAiRequest
import dev.cint.contracts.util.CancellationToken
import dev.cint.contracts.util.ContractError
import dev.cint.contracts.util.ContractResult
import dev.cint.contracts.util.ResultFactory

class FakeFallbackAi(
    private val proposal: KnowledgeChange = KnowledgeChange.Batch("fake-fallback-ai", emptyList()),
    private val failure: ContractError? = null,
    val recorder: FakeRecorder = FakeRecorder(),
) : FallbackAi {
    val requests: MutableList<FallbackAiRequest> = mutableListOf()
    val calls: Int
        get() = requests.size

    override fun propose(
        request: FallbackAiRequest,
        cancellation: CancellationToken,
    ): ContractResult<KnowledgeChange> {
        recorder.record("fallbackAi.propose", request.language ?: "")
        val checked = cancellation.throwIfCancelled()
        if (checked.isFailure) return ResultFactory.failure(checked.errorOrNull!!)
        requests.add(request)
        failure?.let { return ResultFactory.failure(it) }
        return ResultFactory.success(proposal)
    }
}
