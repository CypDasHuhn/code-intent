package dev.cint.pipeline

import dev.cint.ai.FallbackAiHook
import dev.cint.ai.FallbackAiPolicy
import dev.cint.ai.FallbackAiRequest
import dev.cint.contracts.data.AnalysisError
import dev.cint.contracts.data.AnalysisResult
import dev.cint.contracts.data.Completeness
import dev.cint.contracts.data.ErrorSeverity
import dev.cint.contracts.facts.IndexedSource
import dev.cint.contracts.facts.SourceInput
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
    private val ruleEvaluators: RuleEvaluatorRegistry = BundledRuleEvaluators.registry(),
    private val knowledgeInputs: KnowledgeInputResolver = PrecedenceKnowledgeInputResolver(),
    private val applyUnmaterializedDefaults: Boolean = true,
    private val enableCallChains: Boolean = true,
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
                ?: source.facts.syntaxTree
                    ?.language
                    ?.takeIf { it.isNotBlank() }

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

        val fallbackResult =
            applyFallbackAi(
                language = language,
                source = source,
                knowledge = knowledge,
                packDefaults = selected.defaults,
                errors = errors,
                cancellation = cancellation,
            )
        if (fallbackResult.isFailure) {
            return ResultFactory.failure(fallbackResult.errorOrNull!!)
        }
        val effectiveKnowledge = fallbackResult.getOrThrow()

        val defaults = if (applyUnmaterializedDefaults) selected.defaults else emptyList()
        val knowledgeResolution =
            knowledgeInputs.resolve(source, effectiveKnowledge, defaults, cancellation)
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
                    CompositionPlan.config(bound.composition).copy(callChainsEnabled = enableCallChains),
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
    ): ContractResult<KnowledgeSnapshot> {
        val cancelled = cancellation.throwIfCancelled()
        if (cancelled.isFailure) return ResultFactory.failure(cancelled.errorOrNull!!)
        val path =
            when (val input = source.input) {
                is SourceInput.FilePath -> input.path
                is SourceInput.InMemory -> input.path ?: input.documentId
            }
        val dirty = source.completeness != Completeness.COMPLETE || source.input.version > 0
        val hook = fallbackAi
        val store = knowledgeStore
        val callerRequest = request(language, source, knowledge, packDefaults, dirty, path)
        if (!fallbackAiPolicy.shouldRun(callerRequest)) return ResultFactory.success(knowledge)
        if (hook == null || store == null) {
            errors +=
                aiUnavailable(
                    if (hook == null) {
                        "fallback AI policy is enabled but no hook is configured"
                    } else {
                        "fallback AI policy is enabled but no knowledge store is configured"
                    },
                )
            return ResultFactory.success(knowledge)
        }
        val current = store.snapshot(cancellation)
        if (current.isFailure) {
            val error = current.errorOrNull!!
            if (error is ContractError.Cancelled) return ResultFactory.failure(error)
            errors += aiUnavailable("fallback AI could not read the current knowledge snapshot: $error")
            return ResultFactory.success(knowledge)
        }
        val currentKnowledge = current.getOrThrow()
        val request = request(language, source, currentKnowledge, packDefaults, dirty, path)
        if (!fallbackAiPolicy.shouldRun(request)) return ResultFactory.success(knowledge)

        val proposal = hook.propose(request, cancellation)
        if (proposal.isFailure) {
            val error = proposal.errorOrNull!!
            if (error is ContractError.Cancelled) return ResultFactory.failure(error)
            errors += aiFailure(error.toString())
            return ResultFactory.success(knowledge)
        }
        val applied = store.proposeChange(proposal.getOrThrow(), cancellation)
        if (applied.isFailure) {
            val error = applied.errorOrNull!!
            if (error is ContractError.Cancelled) return ResultFactory.failure(error)
            errors += aiFailure(error.toString())
        }
        return ResultFactory.success(knowledge)
    }

    private fun request(
        language: String?,
        source: IndexedSource,
        knowledge: KnowledgeSnapshot,
        packDefaults: List<dev.cint.contracts.knowledge.PackDefault>,
        dirty: Boolean,
        path: String?,
    ): FallbackAiRequest =
        FallbackAiRequest(
            language = language,
            source = source,
            knowledge = knowledge,
            packDefaults = packDefaults,
            dirty = dirty,
            changedPaths = if (dirty && path != null) setOf(path) else emptySet(),
        )

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
