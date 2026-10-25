package dev.cint.pipeline

import dev.cint.ai.FallbackAiHook
import dev.cint.ai.FallbackAiPolicy
import dev.cint.ai.FallbackAiRequest
import dev.cint.contracts.data.AnalysisError
import dev.cint.contracts.data.AnalysisResult
import dev.cint.contracts.data.ErrorSeverity
import dev.cint.contracts.facts.IndexedSource
import dev.cint.contracts.knowledge.KnowledgeSnapshot
import dev.cint.contracts.ports.AnalysisRequest
import dev.cint.contracts.ports.IntentPipeline
import dev.cint.contracts.ports.KnowledgeStore
import dev.cint.contracts.ports.RuleEvaluatorRegistry
import dev.cint.contracts.ports.RulePackSource
import dev.cint.contracts.util.CancellationToken
import dev.cint.contracts.util.ContractError
import dev.cint.contracts.util.ContractResult
import dev.cint.contracts.util.ResultFactory
import dev.cint.core.composition.CompositionPlan
import dev.cint.core.engine.RuleEngine

class DefaultIntentPipeline(
    packSource: RulePackSource,
    private val ruleEvaluators: RuleEvaluatorRegistry = RuleEvaluatorCatalog.bundled(),
    private val knowledgeInputs: KnowledgeInputResolver = PrecedenceKnowledgeInputResolver(),
    private val fallbackAi: FallbackAiHook? = null,
    private val fallbackAiPolicy: FallbackAiPolicy = FallbackAiPolicy.DISABLED,
    private val knowledgeStore: KnowledgeStore? = null,
    private val engine: RuleEngine = RuleEngine(),
) : IntentPipeline {
    private val packSelector = PackSelector(packSource)

    override fun analyze(
        request: AnalysisRequest,
        source: IndexedSource,
        knowledge: KnowledgeSnapshot,
        cancellation: CancellationToken,
    ): ContractResult<AnalysisResult> {
        cancellation.throwIfCancelled().let {
            if (it.isFailure) return ResultFactory.failure(it.errorOrNull!!)
        }

        val language =
            request.language?.takeIf { it.isNotBlank() }
                ?: source.facts.syntaxTree?.language?.takeIf { it.isNotBlank() }

        val selection = packSelector.select(language, cancellation)
        if (selection.isFailure) return ResultFactory.failure(selection.errorOrNull!!)
        val selected = selection.getOrThrow()

        val plan = bindPackRules(selected, ruleEvaluators)
        if (plan.isFailure) return ResultFactory.failure(plan.errorOrNull!!)
        val bound = plan.getOrThrow()

        val executionPlan =
            try {
                engine.plan(bound.rules)
            } catch (error: RuntimeException) {
                return ResultFactory.failure(internal(error))
            }

        val errors = mutableListOf<AnalysisError>()
        errors +=
            selected.diagnostics.map {
                AnalysisError(
                    code = it.code,
                    message = it.message,
                    severity = ErrorSeverity.WARNING,
                )
            }

        val effectiveKnowledge =
            applyFallbackAi(
                language = language,
                source = source,
                knowledge = knowledge,
                packDefaults = selected.defaults,
                errors = errors,
                cancellation = cancellation,
            )

        val knowledgeResolution =
            knowledgeInputs.resolve(source, effectiveKnowledge, selected.defaults, cancellation)
        if (knowledgeResolution.isFailure) {
            return ResultFactory.failure(knowledgeResolution.errorOrNull!!)
        }
        val resolved = knowledgeResolution.getOrThrow()
        errors += resolved.errors

        val output =
            try {
                engine.analyze(
                    executionPlan,
                    source.facts,
                    resolved.contributions.sortedWith(
                        compareBy({ it.startByte }, { it.endByte }, { it.rule }),
                    ),
                    CompositionPlan.config(bound.composition),
                )
            } catch (error: RuntimeException) {
                return ResultFactory.failure(internal(error))
            }

        return ResultFactory.success(
            AnalysisResult(
                annotations = output.annotations,
                candidates = output.candidates,
                facts = source.facts,
                sourceEpoch = source.sourceEpoch,
                completeness = source.completeness,
                errors = errors.distinctBy { it.code to it.message }.sortedWith(compareBy({ it.code }, { it.message })),
            ),
        )
    }

    private fun applyFallbackAi(
        language: String?,
        source: IndexedSource,
        knowledge: KnowledgeSnapshot,
        packDefaults: List<dev.cint.contracts.knowledge.PackDefault>,
        errors: MutableList<AnalysisError>,
        cancellation: CancellationToken,
    ): KnowledgeSnapshot {
        val request = FallbackAiRequest(language, source, knowledge, packDefaults)
        if (!fallbackAiPolicy.shouldRun(request)) return knowledge
        val hook = fallbackAi
        if (hook == null) {
            errors += aiUnavailable("fallback AI policy is enabled but no hook is configured")
            return knowledge
        }
        val store = knowledgeStore
        if (store == null) {
            errors += aiUnavailable("fallback AI policy is enabled but no knowledge store is configured")
            return knowledge
        }

        val proposal = hook.propose(request)
        if (proposal.isFailure) {
            errors += aiFailure(proposal.errorOrNull.toString())
            return knowledge
        }
        val applied = store.proposeChange(proposal.getOrThrow(), cancellation)
        if (applied.isFailure) {
            errors += aiFailure(applied.errorOrNull.toString())
            return knowledge
        }
        val refreshed = store.snapshot(cancellation)
        if (refreshed.isFailure) {
            errors +=
                AnalysisError(
                    code = "FALLBACK_AI_REFRESH_FAILED",
                    message = refreshed.errorOrNull.toString(),
                    severity = ErrorSeverity.WARNING,
                )
            return knowledge
        }
        return refreshed.getOrThrow()
    }

    private fun aiFailure(message: String): AnalysisError =
        AnalysisError(
            code = "FALLBACK_AI_FAILED",
            message = message,
            severity = ErrorSeverity.WARNING,
        )

    private fun aiUnavailable(message: String): AnalysisError =
        AnalysisError(
            code = "FALLBACK_AI_UNAVAILABLE",
            message = message,
            severity = ErrorSeverity.WARNING,
        )

    private fun internal(error: RuntimeException) =
        ContractError.InternalError(
            error.message ?: error::class.simpleName.orEmpty(),
            error::class.simpleName,
        )
}
