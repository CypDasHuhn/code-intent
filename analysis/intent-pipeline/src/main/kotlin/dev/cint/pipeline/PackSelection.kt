package dev.cint.pipeline

import dev.cint.contracts.knowledge.PackDefault
import dev.cint.contracts.ports.PackDiagnostic
import dev.cint.contracts.ports.PackManifest
import dev.cint.contracts.ports.Playbook
import dev.cint.contracts.ports.RulePackSource
import dev.cint.contracts.util.CancellationToken
import dev.cint.contracts.util.ContractError
import dev.cint.contracts.util.ContractResult
import dev.cint.contracts.util.ResultFactory

data class SelectedPack(
    val manifest: PackManifest,
    val playbook: Playbook,
)

data class PackSelection(
    val language: String?,
    val packs: List<SelectedPack>,
    val defaults: List<PackDefault>,
    val diagnostics: List<PackDiagnostic>,
)

class PackSelector(
    private val source: RulePackSource,
) {
    fun select(
        language: String?,
        cancellation: CancellationToken = CancellationToken.NONE,
    ): ContractResult<PackSelection> =
        try {
            selectChecked(language, cancellation)
        } catch (failure: SelectionFailure) {
            ResultFactory.failure(failure.error)
        }

    private fun selectChecked(
        language: String?,
        cancellation: CancellationToken,
    ): ContractResult<PackSelection> {
        val manifests = source.manifests(cancellation).orReturn()
        val diagnostics = source.diagnostics(cancellation).orReturn()
        val duplicateIds =
            manifests
                .groupingBy { it.id }
                .eachCount()
                .filterValues { it > 1 }
                .keys
                .sorted()
        if (duplicateIds.isNotEmpty()) {
            return ResultFactory.failure(
                ContractError.ValidationError(
                    "Duplicate pack ids: ${duplicateIds.joinToString(", ")}",
                    "id",
                ),
            )
        }
        val degraded = diagnostics.filter { it.error != null }.map { it.packId }.toSet()
        val byId = manifests.sortedBy { it.id }.associateBy { it.id }

        val roots =
            manifests
                .filter { it.defaultEnabled && it.id !in degraded }
                .filter { language.isNullOrBlank() || language in it.languages }
                .map { it.id }

        val selected = closure(roots, byId, degraded).orReturn()
        val ordered = order(selected, byId).orReturn()

        val packs =
            ordered.map { id ->
                val playbook = source.playbook(id, cancellation).orReturn()
                val manifest = byId.getValue(id)
                if (playbook.packId != manifest.id) {
                    return ResultFactory.failure(
                        ContractError.ValidationError(
                            "Playbook packId '${playbook.packId}' does not match manifest id '${manifest.id}'",
                            "packId",
                        ),
                    )
                }
                SelectedPack(manifest, playbook)
            }

        val defaults =
            if (language.isNullOrBlank()) {
                emptyList()
            } else {
                source
                    .defaults(language, cancellation)
                    .orReturn()
                    .filter { it.packId in selected }
                    .sortedWith(
                        compareBy(
                            { it.packId },
                            { it.selector.identityKey },
                            { it.intents.sorted().joinToString("\u0000") },
                            { it.packVersion },
                        ),
                    )
            }

        return ResultFactory.success(
            PackSelection(
                language = language?.takeIf { it.isNotBlank() },
                packs = packs,
                defaults = defaults,
                diagnostics = diagnostics.sortedWith(compareBy({ it.packId }, { it.code }, { it.message })),
            ),
        )
    }

    private fun closure(
        roots: List<String>,
        manifests: Map<String, PackManifest>,
        degraded: Set<String>,
    ): ContractResult<Set<String>> {
        val selected = linkedSetOf<String>()
        val pending = roots.sorted().toMutableList()
        while (pending.isNotEmpty()) {
            val id = pending.removeAt(0)
            if (!selected.add(id)) continue
            val manifest =
                manifests[id]
                    ?: return ResultFactory.failure(
                        ContractError.NotFound("pack", id),
                    )
            for (dependency in manifest.dependencies.distinct().sorted()) {
                if (dependency in degraded) {
                    return ResultFactory.failure(
                        ContractError.DependencyUnavailable(
                            "pack:$dependency",
                            "Dependency '$dependency' of pack '$id' is degraded",
                        ),
                    )
                }
                if (dependency !in manifests) {
                    return ResultFactory.failure(
                        ContractError.DependencyUnavailable(
                            "pack:$dependency",
                            "Missing dependency '$dependency' of pack '$id'",
                        ),
                    )
                }
                if (dependency !in selected) pending += dependency
            }
        }
        return ResultFactory.success(selected)
    }

    private fun order(
        selected: Set<String>,
        manifests: Map<String, PackManifest>,
    ): ContractResult<List<String>> {
        val state = mutableMapOf<String, Int>()
        val ordered = mutableListOf<String>()
        val stack = mutableListOf<String>()

        fun visit(id: String): ContractResult<Unit> {
            when (state[id]) {
                VISITED -> {
                    return ResultFactory.success(Unit)
                }

                VISITING -> {
                    val start = stack.indexOf(id)
                    val cycle = stack.subList(start, stack.size) + id
                    return ResultFactory.failure(
                        ContractError.ValidationError(
                            "Dependency cycle: ${cycle.joinToString(" -> ")}",
                            "dependencies",
                        ),
                    )
                }
            }
            state[id] = VISITING
            stack += id
            val manifest = manifests.getValue(id)
            for (dependency in manifest.dependencies.distinct().sorted()) {
                if (dependency !in selected) continue
                val result = visit(dependency)
                if (result.isFailure) return result
            }
            stack.removeAt(stack.lastIndex)
            state[id] = VISITED
            ordered += id
            return ResultFactory.success(Unit)
        }

        for (id in selected.sorted()) {
            val result = visit(id)
            if (result.isFailure) return ResultFactory.failure(result.errorOrNull!!)
        }
        return ResultFactory.success(ordered)
    }

    private companion object {
        const val VISITING = 1
        const val VISITED = 2
    }
}

private fun <T> ContractResult<T>.orReturn(): T = if (isSuccess) getOrThrow() else throw SelectionFailure(errorOrNull!!)

private class SelectionFailure(
    val error: ContractError,
) : RuntimeException()
