package dev.cint.contracts.ports

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
import dev.cint.contracts.util.CancellationToken
import dev.cint.contracts.util.ContractResult

interface KnowledgeStore {
    val isMaterialized: Boolean

    fun snapshot(cancellation: CancellationToken = CancellationToken.NONE): ContractResult<KnowledgeSnapshot>

    fun functionAnnotations(cancellation: CancellationToken = CancellationToken.NONE): ContractResult<List<FunctionAnnotation>>

    fun setFunctionAnnotation(
        annotation: FunctionAnnotation,
        cancellation: CancellationToken = CancellationToken.NONE,
    ): ContractResult<FunctionAnnotation>

    fun removeFunctionAnnotation(
        selector: FunctionSelector,
        cancellation: CancellationToken = CancellationToken.NONE,
        path: String? = null,
    ): ContractResult<Unit>

    fun spanOverrides(cancellation: CancellationToken = CancellationToken.NONE): ContractResult<List<SpanOverride>>

    fun setSpanOverride(
        override: SpanOverride,
        cancellation: CancellationToken = CancellationToken.NONE,
    ): ContractResult<SpanOverride>

    fun removeSpanOverride(
        selector: SpanSelector,
        cancellation: CancellationToken = CancellationToken.NONE,
    ): ContractResult<Unit>

    fun patternHypotheses(cancellation: CancellationToken = CancellationToken.NONE): ContractResult<List<PatternHypothesis>>

    fun setPatternHypothesis(
        hypothesis: PatternHypothesis,
        cancellation: CancellationToken = CancellationToken.NONE,
    ): ContractResult<PatternHypothesis>

    fun removePatternHypothesis(
        selector: PatternSelector,
        cancellation: CancellationToken = CancellationToken.NONE,
    ): ContractResult<Unit>

    fun packOrigin(
        selector: FunctionSelector,
        cancellation: CancellationToken = CancellationToken.NONE,
    ): ContractResult<PackOrigin?>

    fun materializeDefaults(
        defaults: List<PackDefault>,
        cancellation: CancellationToken = CancellationToken.NONE,
    ): ContractResult<Unit>

    fun pruneStale(
        validFunctionSelectors: Set<FunctionSelector>,
        validSpanSelectors: Set<SpanSelector>,
        cancellation: CancellationToken = CancellationToken.NONE,
        validFilePaths: Map<FunctionSelector, Set<String>> = emptyMap(),
    ): ContractResult<Int>

    fun proposeChange(
        change: KnowledgeChange,
        cancellation: CancellationToken = CancellationToken.NONE,
    ): ContractResult<Unit>
}
