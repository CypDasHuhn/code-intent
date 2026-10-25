package dev.cint.v2.testing.fakes

import dev.cint.contracts.knowledge.FunctionAnnotation
import dev.cint.contracts.knowledge.FunctionSelector
import dev.cint.contracts.knowledge.KnowledgeChange
import dev.cint.contracts.knowledge.KnowledgeSnapshot
import dev.cint.contracts.knowledge.PackDefault
import dev.cint.contracts.knowledge.PackOrigin
import dev.cint.contracts.knowledge.PatternHypothesis
import dev.cint.contracts.knowledge.PatternSelector
import dev.cint.contracts.knowledge.SpanOverride
import dev.cint.contracts.knowledge.SpanSelector
import dev.cint.contracts.ports.KnowledgeStore
import dev.cint.contracts.util.CancellationToken
import dev.cint.contracts.util.ContractError
import dev.cint.contracts.util.ContractResult
import dev.cint.contracts.util.ResultFactory

class FakeKnowledgeStore(
    functionAnnotations: List<FunctionAnnotation> = emptyList(),
    spanOverrides: List<SpanOverride> = emptyList(),
    patternHypotheses: List<PatternHypothesis> = emptyList(),
    packOrigins: Map<String, PackOrigin> = emptyMap(),
    materialized: Boolean = false,
    private val clock: FakeClock = FakeClock(),
    val recorder: FakeRecorder = FakeRecorder(),
) : KnowledgeStore {
    private val annotations = linkedMapOf<String, FunctionAnnotation>()
    private val overrides = linkedMapOf<String, SpanOverride>()
    private val hypotheses = linkedMapOf<String, PatternHypothesis>()
    private val origins = linkedMapOf<String, PackOrigin>()
    val proposedChanges = mutableListOf<KnowledgeChange>()
    override var isMaterialized: Boolean = materialized
        private set

    init {
        functionAnnotations.sortedBy { it.selector.identityKey }.forEach {
            annotations[it.selector.identityKey] = it
        }
        spanOverrides.sortedBy { it.selector.identityKey }.forEach {
            overrides[it.selector.identityKey] = it
        }
        patternHypotheses.sortedBy { it.selector.identityKey }.forEach {
            hypotheses[it.selector.identityKey] = it
        }
        packOrigins.toSortedMap().forEach { (key, origin) -> origins[key] = origin }
    }

    override fun snapshot(cancellation: CancellationToken): ContractResult<KnowledgeSnapshot> {
        recorder.record("knowledge.snapshot")
        val checked = cancellation.throwIfCancelled()
        if (checked.isFailure) return ResultFactory.failure(checked.errorOrNull!!)
        return ResultFactory.success(
            KnowledgeSnapshot(
                functionAnnotations = annotations.values.sortedBy { it.selector.identityKey },
                spanOverrides = overrides.values.sortedBy { it.selector.identityKey },
                patternHypotheses = hypotheses.values.sortedBy { it.selector.identityKey },
                packOrigins = origins.toSortedMap(),
                materialized = isMaterialized,
                timestamp = clock.epochMillis(),
            ),
        )
    }

    override fun functionAnnotations(cancellation: CancellationToken): ContractResult<List<FunctionAnnotation>> {
        recorder.record("knowledge.functionAnnotations")
        val checked = cancellation.throwIfCancelled()
        if (checked.isFailure) return ResultFactory.failure(checked.errorOrNull!!)
        return ResultFactory.success(annotations.values.sortedBy { it.selector.identityKey })
    }

    override fun setFunctionAnnotation(
        annotation: FunctionAnnotation,
        cancellation: CancellationToken,
    ): ContractResult<FunctionAnnotation> {
        recorder.record("knowledge.setFunctionAnnotation", annotation.selector.identityKey)
        val checked = cancellation.throwIfCancelled()
        if (checked.isFailure) return ResultFactory.failure(checked.errorOrNull!!)
        annotations[annotation.selector.identityKey] = annotation
        return ResultFactory.success(annotation)
    }

    override fun removeFunctionAnnotation(
        selector: FunctionSelector,
        cancellation: CancellationToken,
    ): ContractResult<Unit> {
        recorder.record("knowledge.removeFunctionAnnotation", selector.identityKey)
        val checked = cancellation.throwIfCancelled()
        if (checked.isFailure) return checked
        if (annotations.remove(selector.identityKey) == null) {
            return ResultFactory.failure(ContractError.NotFound("function-annotation", selector.identityKey))
        }
        origins.remove(selector.identityKey)
        return ResultFactory.success(Unit)
    }

    override fun spanOverrides(cancellation: CancellationToken): ContractResult<List<SpanOverride>> {
        recorder.record("knowledge.spanOverrides")
        val checked = cancellation.throwIfCancelled()
        if (checked.isFailure) return ResultFactory.failure(checked.errorOrNull!!)
        return ResultFactory.success(overrides.values.sortedBy { it.selector.identityKey })
    }

    override fun setSpanOverride(
        override: SpanOverride,
        cancellation: CancellationToken,
    ): ContractResult<SpanOverride> {
        recorder.record("knowledge.setSpanOverride", override.selector.identityKey)
        val checked = cancellation.throwIfCancelled()
        if (checked.isFailure) return ResultFactory.failure(checked.errorOrNull!!)
        overrides[override.selector.identityKey] = override
        return ResultFactory.success(override)
    }

    override fun removeSpanOverride(
        selector: SpanSelector,
        cancellation: CancellationToken,
    ): ContractResult<Unit> {
        recorder.record("knowledge.removeSpanOverride", selector.identityKey)
        val checked = cancellation.throwIfCancelled()
        if (checked.isFailure) return checked
        if (overrides.remove(selector.identityKey) == null) {
            return ResultFactory.failure(ContractError.NotFound("span-override", selector.identityKey))
        }
        return ResultFactory.success(Unit)
    }

    override fun patternHypotheses(cancellation: CancellationToken): ContractResult<List<PatternHypothesis>> {
        recorder.record("knowledge.patternHypotheses")
        val checked = cancellation.throwIfCancelled()
        if (checked.isFailure) return ResultFactory.failure(checked.errorOrNull!!)
        return ResultFactory.success(hypotheses.values.sortedBy { it.selector.identityKey })
    }

    override fun setPatternHypothesis(
        hypothesis: PatternHypothesis,
        cancellation: CancellationToken,
    ): ContractResult<PatternHypothesis> {
        recorder.record("knowledge.setPatternHypothesis", hypothesis.selector.identityKey)
        val checked = cancellation.throwIfCancelled()
        if (checked.isFailure) return ResultFactory.failure(checked.errorOrNull!!)
        hypotheses[hypothesis.selector.identityKey] = hypothesis
        return ResultFactory.success(hypothesis)
    }

    override fun removePatternHypothesis(
        selector: PatternSelector,
        cancellation: CancellationToken,
    ): ContractResult<Unit> {
        recorder.record("knowledge.removePatternHypothesis", selector.identityKey)
        val checked = cancellation.throwIfCancelled()
        if (checked.isFailure) return checked
        if (hypotheses.remove(selector.identityKey) == null) {
            return ResultFactory.failure(ContractError.NotFound("pattern-hypothesis", selector.identityKey))
        }
        return ResultFactory.success(Unit)
    }

    override fun packOrigin(
        selector: FunctionSelector,
        cancellation: CancellationToken,
    ): ContractResult<PackOrigin?> {
        recorder.record("knowledge.packOrigin", selector.identityKey)
        val checked = cancellation.throwIfCancelled()
        if (checked.isFailure) return ResultFactory.failure(checked.errorOrNull!!)
        return ResultFactory.success(origins[selector.identityKey])
    }

    override fun materializeDefaults(
        defaults: List<PackDefault>,
        cancellation: CancellationToken,
    ): ContractResult<Unit> {
        recorder.record("knowledge.materializeDefaults", "count=${defaults.size}")
        val checked = cancellation.throwIfCancelled()
        if (checked.isFailure) return checked
        defaults.filter { it.status != dev.cint.contracts.data.Status.REJECTED && it.status != dev.cint.contracts.data.Status.SUPPRESSED }
            .sortedBy { it.selector.identityKey }.forEach { default ->
            val key = default.selector.identityKey
            if (key !in annotations) {
                annotations[key] = default.toFunctionAnnotation()
                origins[key] = PackOrigin(default.packId, default.packVersion)
                isMaterialized = true
            }
        }
        return ResultFactory.success(Unit)
    }

    override fun pruneStale(
        validFunctionSelectors: Set<FunctionSelector>,
        validSpanSelectors: Set<SpanSelector>,
        cancellation: CancellationToken,
    ): ContractResult<Int> {
        recorder.record("knowledge.pruneStale")
        val checked = cancellation.throwIfCancelled()
        if (checked.isFailure) return ResultFactory.failure(checked.errorOrNull!!)
        val validFunctions = validFunctionSelectors.map { it.identityKey }.toSet()
        val validSpans = validSpanSelectors.map { it.identityKey }.toSet()
        val staleFunctions = annotations.keys.filter { it !in validFunctions }
        val staleSpans = overrides.keys.filter { it !in validSpans }
        staleFunctions.forEach {
            annotations.remove(it)
            origins.remove(it)
        }
        staleSpans.forEach(overrides::remove)
        return ResultFactory.success(staleFunctions.size + staleSpans.size)
    }

    override fun proposeChange(
        change: KnowledgeChange,
        cancellation: CancellationToken,
    ): ContractResult<Unit> {
        recorder.record("knowledge.proposeChange", change::class.simpleName ?: "unknown")
        val checked = cancellation.throwIfCancelled()
        if (checked.isFailure) return checked
        proposedChanges.add(change)
        return ResultFactory.success(Unit)
    }
}
