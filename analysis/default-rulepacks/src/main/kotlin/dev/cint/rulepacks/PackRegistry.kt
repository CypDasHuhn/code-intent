package dev.cint.rulepacks

import dev.cint.contracts.ports.RuleEvaluator
import dev.cint.contracts.ports.RuleEvaluatorRegistry
import dev.cint.contracts.ports.RuleSpec
import dev.cint.rulepacks.packs.bookkeeping.BookkeepingPack
import dev.cint.rulepacks.packs.dataaccess.DataAccessPack
import dev.cint.rulepacks.packs.errorhandling.ErrorHandlingPack
import dev.cint.rulepacks.packs.localcopy.LocalCopyPack
import dev.cint.rulepacks.packs.logging.LoggingPack
import dev.cint.rulepacks.packs.mutation.MutationPack
import dev.cint.rulepacks.packs.normalization.NormalizationPack
import dev.cint.rulepacks.packs.returnvalue.ReturnValuePack
import dev.cint.rulepacks.packs.service.ServicePack
import dev.cint.rulepacks.packs.transformation.TransformationPack
import dev.cint.rulepacks.packs.validation.ValidationPack
import kotlinx.serialization.json.JsonObject

internal interface PackRules {
    val packId: String

    fun create(
        spec: RuleSpec,
        data: JsonObject,
    ): RuleEvaluator
}

internal object PackRegistry {
    private val packs: List<PackRules> =
        listOf(
            LoggingPack,
            ErrorHandlingPack,
            BookkeepingPack,
            ValidationPack,
            DataAccessPack,
            NormalizationPack,
            TransformationPack,
            MutationPack,
            ServicePack,
            ReturnValuePack,
            LocalCopyPack,
        )

    private val byId = packs.associateBy { it.packId }

    fun evaluators(definition: PackDefinition): List<RuleEvaluator> {
        val pack = byId[definition.manifest.id] ?: return emptyList()
        return definition.playbook.rules.mapNotNull { spec ->
            val data = definition.rules[spec.id] ?: return@mapNotNull null
            pack.create(spec, data)
        }
    }
}

object DefaultRulePacks {
    fun registry(catalog: PackCatalog = PackCatalog.bundled()): RuleEvaluatorRegistry =
        RulePackEvaluatorRegistry(catalog.definitions.flatMap(PackRegistry::evaluators))

    fun evaluators(catalog: PackCatalog = PackCatalog.bundled()): List<RuleEvaluator> =
        (registry(catalog) as RulePackEvaluatorRegistry).evaluators()
}

class RulePackEvaluatorRegistry(
    evaluators: Iterable<RuleEvaluator> = emptyList(),
) : RuleEvaluatorRegistry {
    private val byId = evaluators.sortedBy { it.spec.id }.associateBy { it.spec.id }

    override fun resolve(spec: RuleSpec): RuleEvaluator? = byId[spec.id]

    fun evaluators(): List<RuleEvaluator> = byId.values.sortedBy { it.spec.id }
}
