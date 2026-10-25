package dev.cint.runtime

import dev.cint.contracts.data.AnalysisResult
import dev.cint.contracts.ports.FallbackAi
import dev.cint.contracts.ports.FallbackAiPolicy
import dev.cint.contracts.ports.Index
import dev.cint.contracts.ports.IntentPipeline
import dev.cint.contracts.ports.KnowledgeRepositoryFactory
import dev.cint.contracts.ports.KnowledgeStore
import dev.cint.contracts.ports.KnowledgeStoreFactory
import dev.cint.contracts.ports.RuleEvaluatorRegistry
import dev.cint.contracts.ports.RulePackSource
import dev.cint.contracts.util.CancellationToken
import dev.cint.contracts.util.ContractError
import dev.cint.contracts.util.ContractResult
import dev.cint.contracts.util.ResultFactory
import dev.cint.knowledge.persistence.FileKnowledgeRepositoryFactory
import dev.cint.knowledge.persistence.PersistentKnowledgeStores
import dev.cint.packs.BundledRulePackSource
import dev.cint.pipeline.DefaultIntentPipeline
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

fun interface AnalysisPublisher {
    fun publish(
        result: AnalysisResult,
        cancellation: CancellationToken,
    ): ContractResult<Unit>

    companion object {
        val NONE: AnalysisPublisher = AnalysisPublisher { _, _ -> ResultFactory.success(Unit) }
    }
}

data class RuntimeComponents(
    val languageComposition: RuntimeLanguageComposition? = null,
    val index: Index? = null,
    val packSourceFactory: () -> RulePackSource = { BundledRulePackSource() },
    val pipeline: IntentPipeline? = null,
    val pipelineFactory: ((RulePackSource, KnowledgeStore) -> IntentPipeline)? = null,
    val knowledgeStore: KnowledgeStore? = null,
    val knowledgeRepositoryFactory: KnowledgeRepositoryFactory = FileKnowledgeRepositoryFactory,
    val knowledgeStoreFactory: KnowledgeStoreFactory = PersistentKnowledgeStores,
    val ruleEvaluators: RuleEvaluatorRegistry? = null,
    val fallbackAi: FallbackAi? = null,
    val fallbackAiPolicy: FallbackAiPolicy = FallbackAiPolicy.DISABLED,
    val publisher: AnalysisPublisher = AnalysisPublisher.NONE,
    val clock: () -> Instant = { Clock.System.now() },
) {
    internal fun createPipeline(
        packSource: RulePackSource,
        knowledgeStore: KnowledgeStore,
    ): ContractResult<IntentPipeline> {
        pipelineFactory?.let { factory ->
            return ResultFactory.success(factory(packSource, knowledgeStore))
        }
        pipeline?.let { return ResultFactory.success(it) }
        val evaluators =
            ruleEvaluators
                ?: return ResultFactory.failure(
                    ContractError.PreconditionFailed(
                        "A rule evaluator registry is required for the default intent pipeline",
                    ),
                )
        return ResultFactory.success(
            DefaultIntentPipeline(
                packSource = packSource,
                ruleEvaluators = evaluators,
                fallbackAi = fallbackAi,
                fallbackAiPolicy = fallbackAiPolicy,
                knowledgeStore = knowledgeStore,
            ),
        )
    }
}
