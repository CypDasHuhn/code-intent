package dev.cint.knowledge.persistence

import dev.cint.contracts.knowledge.FunctionAnnotation
import dev.cint.contracts.knowledge.FunctionSelector
import dev.cint.contracts.knowledge.KnowledgeChange
import dev.cint.contracts.knowledge.KnowledgeSnapshot
import dev.cint.contracts.knowledge.PackDefault
import dev.cint.contracts.knowledge.PatternHypothesis
import dev.cint.contracts.knowledge.PatternSelector
import dev.cint.contracts.knowledge.SpanOverride
import dev.cint.contracts.knowledge.SpanSelector
import dev.cint.contracts.ports.KnowledgeRepository
import dev.cint.contracts.ports.KnowledgeStore
import dev.cint.contracts.ports.KnowledgeStoreFactory
import dev.cint.contracts.util.CancellationToken
import dev.cint.contracts.util.ContractResult
import dev.cint.contracts.util.ResultFactory

class PersistentKnowledgeStore(
    private val delegate: KnowledgeStore,
    private val repository: KnowledgeRepository,
) : KnowledgeStore by delegate {
    override val isMaterialized: Boolean
        get() = delegate.isMaterialized

    override fun snapshot(cancellation: CancellationToken): ContractResult<KnowledgeSnapshot> =
        delegate.snapshot(cancellation)

    override fun setFunctionAnnotation(
        annotation: FunctionAnnotation,
        cancellation: CancellationToken,
    ): ContractResult<FunctionAnnotation> =
        delegate.setFunctionAnnotation(annotation, cancellation).persist(cancellation)

    override fun removeFunctionAnnotation(
        selector: FunctionSelector,
        cancellation: CancellationToken,
        path: String?,
    ): ContractResult<Unit> =
        persistResult(delegate.removeFunctionAnnotation(selector, cancellation, path), cancellation)

    override fun setSpanOverride(
        override: SpanOverride,
        cancellation: CancellationToken,
    ): ContractResult<SpanOverride> =
        delegate.setSpanOverride(override, cancellation).persist(cancellation)

    override fun removeSpanOverride(
        selector: SpanSelector,
        cancellation: CancellationToken,
    ): ContractResult<Unit> =
        persistResult(delegate.removeSpanOverride(selector, cancellation), cancellation)

    override fun setPatternHypothesis(
        hypothesis: PatternHypothesis,
        cancellation: CancellationToken,
    ): ContractResult<PatternHypothesis> =
        delegate.setPatternHypothesis(hypothesis, cancellation).persist(cancellation)

    override fun removePatternHypothesis(
        selector: PatternSelector,
        cancellation: CancellationToken,
    ): ContractResult<Unit> =
        persistResult(delegate.removePatternHypothesis(selector, cancellation), cancellation)

    override fun materializeDefaults(
        defaults: List<PackDefault>,
        cancellation: CancellationToken,
    ): ContractResult<Unit> {
        val result = delegate.materializeDefaults(defaults, cancellation)
        if (result.isFailure || !delegate.isMaterialized) return result
        return persistResult(result, cancellation)
    }

    override fun pruneStale(
        validFunctionSelectors: Set<FunctionSelector>,
        validSpanSelectors: Set<SpanSelector>,
        cancellation: CancellationToken,
        validFilePaths: Map<FunctionSelector, Set<String>>,
    ): ContractResult<Int> =
        persistResult(
            delegate.pruneStale(validFunctionSelectors, validSpanSelectors, cancellation, validFilePaths),
            cancellation,
        )

    override fun proposeChange(
        change: KnowledgeChange,
        cancellation: CancellationToken,
    ): ContractResult<Unit> = persistResult(delegate.proposeChange(change, cancellation), cancellation)

    fun save(cancellation: CancellationToken = CancellationToken.NONE): ContractResult<Unit> {
        val snapshot = delegate.snapshot(cancellation)
        if (snapshot.isFailure) return ResultFactory.failure(snapshot.errorOrNull!!)
        return repository.save(snapshot.getOrThrow(), cancellation)
    }

    private fun <T> ContractResult<T>.persist(cancellation: CancellationToken): ContractResult<T> {
        if (isFailure) return this
        val saved = save(cancellation)
        if (saved.isFailure) return ResultFactory.failure(saved.errorOrNull!!)
        return this
    }

    private fun <T> persistResult(
        result: ContractResult<T>,
        cancellation: CancellationToken,
    ): ContractResult<T> =
        if (result.isFailure) {
            result
        } else {
            result.persist(cancellation)
        }
}

object PersistentKnowledgeStores : KnowledgeStoreFactory {
    override fun persistent(
        repository: KnowledgeRepository,
        delegate: KnowledgeStore,
    ): KnowledgeStore = PersistentKnowledgeStore(delegate, repository)
}
