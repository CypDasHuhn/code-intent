package dev.cint.packs

import dev.cint.contracts.knowledge.PackDefault
import dev.cint.contracts.ports.PackDiagnostic
import dev.cint.contracts.ports.PackManifest
import dev.cint.contracts.ports.Playbook
import dev.cint.contracts.ports.RulePackSource
import dev.cint.contracts.util.CancellationToken
import dev.cint.contracts.util.ContractError
import dev.cint.contracts.util.ContractResult
import dev.cint.contracts.util.ResultFactory
import kotlinx.datetime.Instant

class BundledRulePackSource(
    val catalog: PackCatalog = PackCatalog.bundled(),
    knownLanguageIds: Set<String> = catalog.manifests.flatMap { it.languages }.toSet(),
) : RulePackSource {
    private val knownLanguages: Set<String> = knownLanguageIds.toSet()
    private val defaultsByLanguage: Map<String, List<PackDefault>> = buildDefaults(catalog)

    override fun manifests(cancellation: CancellationToken): ContractResult<List<PackManifest>> =
        cancellation.check() ?: ResultFactory.success(catalog.manifests)

    override fun diagnostics(cancellation: CancellationToken): ContractResult<List<PackDiagnostic>> =
        cancellation.check() ?: ResultFactory.success(catalog.diagnostics)

    override fun playbook(
        packId: String,
        cancellation: CancellationToken,
    ): ContractResult<Playbook> {
        cancellation.check()?.let { return it }
        if (packId.isBlank()) {
            return ResultFactory.failure(ContractError.InvalidInput("Pack id must not be blank", input = packId))
        }
        catalog.issue(packId)?.let { return ResultFactory.failure(it) }
        val definition =
            catalog.definition(packId)
                ?: return ResultFactory.failure(ContractError.NotFound("pack", packId))
        return ResultFactory.success(definition.playbook)
    }

    override fun defaults(
        language: String,
        cancellation: CancellationToken,
    ): ContractResult<List<PackDefault>> {
        cancellation.check()?.let { return it }
        if (language.isBlank()) {
            return ResultFactory.failure(ContractError.InvalidInput("Language must not be blank", input = language))
        }
        if (language !in knownLanguages) {
            return ResultFactory.failure(ContractError.InvalidInput("Unknown language '$language'", input = language))
        }
        return ResultFactory.success(defaultsByLanguage[language].orEmpty())
    }

    private fun buildDefaults(catalog: PackCatalog): Map<String, List<PackDefault>> {
        val byLanguage = linkedMapOf<String, MutableList<PackDefault>>()
        for (definition in catalog.availableDefinitions) {
            for (seed in definition.seeds) {
                for (language in seed.languages) {
                    byLanguage.getOrPut(language) { mutableListOf() } +=
                        PackDefault(
                            selector = seed.selector,
                            intents = seed.intents,
                            packId = definition.manifest.id,
                            packVersion = definition.manifest.version,
                            confidence = seed.confidence,
                            createdAt = PACK_SEED_CREATED_AT,
                        )
                }
            }
        }
        return byLanguage.mapValues { (_, defaults) ->
            defaults
                .distinctBy { Triple(it.packId, it.selector.identityKey, it.intents.sorted()) }
                .sortedWith(
                    compareBy(
                        { it.packId },
                        { it.selector.identityKey },
                        { it.intents.sorted().joinToString(",") },
                    ),
                )
        }
    }

    private companion object {
        val PACK_SEED_CREATED_AT: Instant = Instant.fromEpochMilliseconds(0)
    }
}

private fun CancellationToken.check(): ContractResult<Nothing>? =
    if (isCancelled) {
        ResultFactory.failure(ContractError.Cancelled("rule-pack-source"))
    } else {
        null
    }
