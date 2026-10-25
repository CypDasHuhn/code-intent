package dev.cint.pipeline

import dev.cint.contracts.data.Confidence
import dev.cint.contracts.data.Intents
import dev.cint.contracts.facts.CallFact
import dev.cint.contracts.facts.DeclarationKind
import dev.cint.contracts.facts.GuardKind
import dev.cint.contracts.ports.EstablishedEmission
import dev.cint.contracts.ports.RuleEvaluator
import dev.cint.contracts.ports.RuleEvaluatorRegistry
import dev.cint.contracts.ports.RuleEvaluationView
import dev.cint.contracts.ports.RuleSpec

/**
 * Provisional rule evaluators for the default bundled packs.
 *
 * These are deliberately simple, fact-based heuristics. They keep the walking
 * skeleton executable until the full V1 detection semantics land. Full corpus
 * parity is owned by ticket 084 (`084.fact-semantics`, `084.pack-defaults`,
 * `084.composition-parity`, `084.non-kotlin-syntax-parity`).
 */
object BundledRuleEvaluators {
    const val PROVISIONAL_SOURCE: String = "provisional"

    fun registry(): RuleEvaluatorRegistry = RuleEvaluatorCatalog(evaluators())

    fun evaluators(): List<RuleEvaluator> = buildList {
        add(LoggingEvaluator())
        add(ErrorHandlingEvaluator())
        add(BookkeepingEvaluator())
        add(ValidationEvaluator())
        add(SearchEvaluator())
        add(LookupEvaluator())
        add(NormalizationEvaluator())
        add(DataTransformationEvaluator())
        add(DataModifyEvaluator())
        add(LocalValueModifyEvaluator())
        add(ServiceDelegationEvaluator())
        add(ServiceInitEvaluator())
        add(ReturnValueEvaluator())
        add(LocalCopyEvaluator())
    }.sortedBy { it.spec.id }
}

private abstract class ProvisionalEvaluator(
    override val spec: RuleSpec,
) : RuleEvaluator {
    protected fun RuleEvaluationView.emit(
        nodeId: String?,
        confidence: Double,
    ): EstablishedEmission? {
        val node = node(nodeId) ?: return null
        return EstablishedEmission(
            intents = setOf(spec.owns),
            span = node.span,
            startByte = node.startByte,
            endByte = node.endByte,
            source = BundledRuleEvaluators.PROVISIONAL_SOURCE,
            confidence = Confidence.fromDouble(confidence),
        )
    }

    protected fun RuleEvaluationView.emits(
        nodeIds: Iterable<String?>,
        confidence: Double,
    ): List<EstablishedEmission> =
        nodeIds.filterNotNull().distinct().mapNotNull { emit(it, confidence) }
}

private class LoggingEvaluator :
    ProvisionalEvaluator(RuleSpec("logging:logging", Intents.LOGGING)) {
    override fun evaluate(view: RuleEvaluationView): List<EstablishedEmission> =
        view.emits(
            view.calls.filter { it.matchesAny(LOGGING_TOKENS) }.map { it.nodeId },
            CONFIDENCE_MEDIUM,
        )
}

private class ErrorHandlingEvaluator :
    ProvisionalEvaluator(RuleSpec("error-handling:error-handling", Intents.ERROR_HANDLING)) {
    override fun evaluate(view: RuleEvaluationView): List<EstablishedEmission> {
        val nodes = mutableListOf<String?>()
        nodes += view.tries.map { it.nodeId }
        nodes += view.calls.filter { it.matchesAny(ERROR_TOKENS) }.map { it.nodeId }
        return view.emits(nodes, CONFIDENCE_MEDIUM)
    }
}

private class BookkeepingEvaluator :
    ProvisionalEvaluator(RuleSpec("bookkeeping:bookkeeping", Intents.BOOKKEEPING)) {
    override fun evaluate(view: RuleEvaluationView): List<EstablishedEmission> {
        val propertyNames = view.properties.mapNotNull { it.name }.toSet()
        val nodes = mutableListOf<String?>()
        nodes += view.properties.map { it.nodeId }
        nodes += view.declarations.filter { it.kind == DeclarationKind.PROPERTY }.map { it.nodeId }
        nodes += view.writes.filter { it.targetName in propertyNames }.map { it.nodeId }
        return view.emits(nodes, CONFIDENCE_ESTABLISHED)
    }
}

private class ValidationEvaluator :
    ProvisionalEvaluator(
        RuleSpec(
            "validation:validation",
            Intents.VALIDATION,
            reads = listOf(Intents.BOOKKEEPING, Intents.DATA_MODIFY, Intents.LOCAL_VALUE_MODIFY),
        ),
    ) {
    override fun evaluate(view: RuleEvaluationView): List<EstablishedEmission> {
        val nodes = mutableListOf<String?>()
        nodes += view.guards.filter { it.kind == GuardKind.CONDITIONAL }.map { it.nodeId }
        nodes += view.calls.filter { it.matchesAny(VALIDATION_TOKENS) }.map { it.nodeId }
        return view.emits(nodes, CONFIDENCE_MEDIUM)
    }
}

private class SearchEvaluator :
    ProvisionalEvaluator(RuleSpec("data-access:search", Intents.SEARCH)) {
    override fun evaluate(view: RuleEvaluationView): List<EstablishedEmission> =
        view.emits(
            view.calls.filter { it.matchesAny(SEARCH_TOKENS) }.map { it.nodeId },
            CONFIDENCE_MEDIUM,
        )
}

private class LookupEvaluator :
    ProvisionalEvaluator(RuleSpec("data-access:lookup", Intents.LOOKUP)) {
    override fun evaluate(view: RuleEvaluationView): List<EstablishedEmission> =
        view.emits(
            view.calls
                .filter { it.matchesAny(LOOKUP_TOKENS) && !it.matchesAny(SEARCH_TOKENS) }
                .map { it.nodeId },
            CONFIDENCE_MEDIUM,
        )
}

private class NormalizationEvaluator :
    ProvisionalEvaluator(RuleSpec("normalization:normalization", Intents.NORMALIZATION)) {
    override fun evaluate(view: RuleEvaluationView): List<EstablishedEmission> =
        view.emits(
            view.calls.filter { it.matchesAny(NORMALIZATION_TOKENS) }.map { it.nodeId },
            CONFIDENCE_MEDIUM,
        )
}

private class DataTransformationEvaluator :
    ProvisionalEvaluator(
        RuleSpec(
            "data-transformation:data-transformation",
            Intents.DATA_TRANSFORMATION,
            reads = listOf(Intents.SEARCH),
        ),
    ) {
    override fun evaluate(view: RuleEvaluationView): List<EstablishedEmission> =
        view.emits(
            view.calls.filter { it.matchesAny(TRANSFORMATION_TOKENS) }.map { it.nodeId },
            CONFIDENCE_MEDIUM,
        )
}

private class DataModifyEvaluator :
    ProvisionalEvaluator(RuleSpec("mutation:data-modify", Intents.DATA_MODIFY)) {
    override fun evaluate(view: RuleEvaluationView): List<EstablishedEmission> {
        val localNames =
            view.declarations.filter { it.kind == DeclarationKind.VARIABLE }.mapNotNull { it.name }.toSet()
        val nodes = mutableListOf<String?>()
        nodes += view.writes.filter { it.targetName != null && it.targetName !in localNames }.map { it.nodeId }
        nodes += view.calls.filter { it.matchesAny(MUTATION_TOKENS) }.map { it.nodeId }
        return view.emits(nodes, CONFIDENCE_MEDIUM)
    }
}

private class LocalValueModifyEvaluator :
    ProvisionalEvaluator(
        RuleSpec(
            "mutation:local-value-modify",
            Intents.LOCAL_VALUE_MODIFY,
            reads = listOf(Intents.BOOKKEEPING, Intents.LOCAL_COPY),
        ),
    ) {
    override fun evaluate(view: RuleEvaluationView): List<EstablishedEmission> {
        val localNames =
            view.declarations.filter { it.kind == DeclarationKind.VARIABLE }.mapNotNull { it.name }.toSet()
        val nodes =
            view.writes
                .filter { it.targetName == null || it.targetName in localNames }
                .map { it.nodeId }
        return view.emits(nodes, CONFIDENCE_MEDIUM)
    }
}

private class ServiceDelegationEvaluator :
    ProvisionalEvaluator(
        RuleSpec(
            "service:delegation",
            Intents.SERVICE_DELEGATION,
            optionalReads = listOf(Intents.DATA_MODIFY),
        ),
    ) {
    override fun evaluate(view: RuleEvaluationView): List<EstablishedEmission> =
        view.emits(
            view.calls.filter { it.matchesAny(DELEGATION_TOKENS) }.map { it.nodeId },
            CONFIDENCE_MEDIUM,
        )
}

private class ServiceInitEvaluator :
    ProvisionalEvaluator(RuleSpec("service:init", Intents.SERVICE_INIT)) {
    override fun evaluate(view: RuleEvaluationView): List<EstablishedEmission> =
        view.emits(
            view.calls.filter { it.matchesAny(SERVICE_INIT_TOKENS) }.map { it.nodeId },
            CONFIDENCE_MEDIUM,
        )
}

private class ReturnValueEvaluator :
    ProvisionalEvaluator(RuleSpec("return-value:return-value", Intents.RETURN_VALUE)) {
    override fun evaluate(view: RuleEvaluationView): List<EstablishedEmission> =
        view.emits(
            view.returns.filter { it.expressionId != null }.map { it.nodeId },
            CONFIDENCE_MEDIUM,
        )
}

private class LocalCopyEvaluator :
    ProvisionalEvaluator(RuleSpec("local-copy:local-copy", Intents.LOCAL_COPY)) {
    override fun evaluate(view: RuleEvaluationView): List<EstablishedEmission> {
        val nodes = mutableListOf<String?>()
        nodes += view.writes.filter { it.valueId != null }.map { it.nodeId }
        nodes += view.calls.filter { it.matchesAny(LOCAL_COPY_TOKENS) }.map { it.nodeId }
        return view.emits(nodes, CONFIDENCE_LOW)
    }
}

private fun CallFact.matchesAny(tokens: Set<String>): Boolean {
    val segments =
        listOfNotNull(callee, path)
            .flatMap { it.lowercase().split(TOKEN_SEPARATOR) }
            .filter { it.isNotBlank() }
    return tokens.any { token ->
        segments.any { segment -> segment == token || (token.length >= 4 && segment.startsWith(token)) }
    }
}

private const val CONFIDENCE_LOW = 0.55
private const val CONFIDENCE_MEDIUM = 0.65
private const val CONFIDENCE_ESTABLISHED = 0.85

private val TOKEN_SEPARATOR = Regex("[^a-z0-9]+")

private val LOGGING_TOKENS =
    setOf("log", "logger", "println", "print", "debug", "info", "warn", "warning", "error", "trace", "audit", "telemetry", "metric")

private val ERROR_TOKENS =
    setOf("throw", "raise", "panic", "fail", "error", "recover", "catch", "except", "handler")

private val VALIDATION_TOKENS =
    setOf("require", "check", "validate", "validation", "assert", "verify", "ensure", "guard", "nonnull", "notnull", "isvalid", "isempty", "isblank")

private val SEARCH_TOKENS =
    setOf("search", "query", "filter", "scan", "select", "match", "list")

private val LOOKUP_TOKENS =
    setOf("get", "load", "fetch", "read", "lookup", "find", "exists", "contains", "byid", "resolve", "retrieve")

private val NORMALIZATION_TOKENS =
    setOf("normalize", "trim", "lowercase", "uppercase", "tolower", "toupper", "strip", "canonical", "sanitize", "replace", "escape", "encode", "decode")

private val TRANSFORMATION_TOKENS =
    setOf("map", "flatmap", "transform", "convert", "parse", "format", "sort", "distinct", "groupby", "join", "split", "reduce", "fold", "serialize", "deserialize")

private val MUTATION_TOKENS =
    setOf("save", "insert", "update", "delete", "remove", "write", "put", "post", "patch", "persist", "store", "commit", "flush", "upsert")

private val DELEGATION_TOKENS =
    setOf("delegate", "forward", "invoke", "execute", "call", "send", "publish", "emit", "dispatch", "handle", "service", "repository", "repo", "client", "api", "gateway", "adapter", "remote", "proxy", "controller")

private val SERVICE_INIT_TOKENS =
    setOf("new", "create", "factory", "build", "init", "initialize", "connect", "open", "start", "configure", "instantiate", "construct", "launch", "setup", "spawn")

private val LOCAL_COPY_TOKENS =
    setOf("copy", "clone", "tolist", "toset", "tomutable", "toarray", "toqueue", "tostack")
