package dev.cint.v2.testing.fakes

import dev.cint.contracts.knowledge.PackDefault
import dev.cint.contracts.ports.PackDiagnostic
import dev.cint.contracts.ports.PackManifest
import dev.cint.contracts.ports.Playbook
import dev.cint.contracts.ports.RulePackSource
import dev.cint.contracts.util.CancellationToken
import dev.cint.contracts.util.ContractError
import dev.cint.contracts.util.ContractResult
import dev.cint.contracts.util.ResultFactory

class FakeRulePackSource(
    var packManifests: List<PackManifest> = emptyList(),
    var playbooks: Map<String, Playbook> = emptyMap(),
    var defaultsByLanguage: Map<String, List<PackDefault>> = emptyMap(),
    var packDiagnostics: List<PackDiagnostic> = emptyList(),
    val recorder: FakeRecorder = FakeRecorder(),
) : RulePackSource {
    override fun manifests(cancellation: CancellationToken): ContractResult<List<PackManifest>> {
        recorder.record("packs.manifests")
        val checked = cancellation.throwIfCancelled()
        if (checked.isFailure) return checked.map { emptyList() }
        return ResultFactory.success(packManifests.sortedBy { it.id })
    }

    override fun playbook(packId: String, cancellation: CancellationToken): ContractResult<Playbook> {
        recorder.record("packs.playbook", packId)
        val checked = cancellation.throwIfCancelled()
        if (checked.isFailure) return checked.map { Playbook(packId, emptyList()) }
        val playbook = playbooks[packId]
            ?: return ResultFactory.failure(ContractError.NotFound("playbook", packId))
        return ResultFactory.success(playbook)
    }

    override fun defaults(language: String, cancellation: CancellationToken): ContractResult<List<PackDefault>> {
        recorder.record("packs.defaults", language)
        val checked = cancellation.throwIfCancelled()
        if (checked.isFailure) return ResultFactory.failure(checked.errorOrNull!!)
        return ResultFactory.success(defaultsByLanguage[language].orEmpty())
    }

    override fun diagnostics(cancellation: CancellationToken): ContractResult<List<PackDiagnostic>> {
        recorder.record("packs.diagnostics")
        val checked = cancellation.throwIfCancelled()
        if (checked.isFailure) return ResultFactory.failure(checked.errorOrNull!!)
        return ResultFactory.success(packDiagnostics.sortedWith(compareBy({ it.packId }, { it.code })))
    }
}
