package dev.cint.knowledge

import dev.cint.contracts.data.Intent
import dev.cint.contracts.data.Provenance
import dev.cint.contracts.data.Status
import dev.cint.contracts.knowledge.AnnotationScope
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
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

class InMemoryKnowledgeStore private constructor(
    initiallyExists: Boolean,
    initialTimestamp: Long,
    initialFunctionAnnotations: Collection<FunctionAnnotation>,
    initialSpanOverrides: Collection<SpanOverride>,
    initialPatternHypotheses: Collection<PatternHypothesis>,
    initialPackOrigins: Map<String, PackOrigin>,
    private val now: () -> Instant,
) : KnowledgeStore {
    private val lock = Any()
    private var hasStore = initiallyExists
    private var timestamp = initialTimestamp
    private val annotations = LinkedHashMap<String, FunctionAnnotation>()
    private val overrides = LinkedHashMap<String, SpanOverride>()
    private val hypotheses = LinkedHashMap<String, PatternHypothesis>()
    private val packOrigins = LinkedHashMap<String, PackOrigin>()

    init {
        initialFunctionAnnotations.forEach {
            annotations[it.identityKey] = it.defensiveCopy()
        }
        initialSpanOverrides.forEach {
            overrides[it.selector.identityKey] = it.defensiveCopy()
        }
        initialPatternHypotheses.forEach {
            hypotheses[it.selector.identityKey] = it.defensiveCopy()
        }
        packOrigins.putAll(initialPackOrigins)
    }

    override val isMaterialized: Boolean
        get() = synchronized(lock) { hasStore }

    override fun snapshot(cancellation: CancellationToken): ContractResult<KnowledgeSnapshot> {
        cancellationError(cancellation)?.let { return ResultFactory.failure(it) }
        return synchronized(lock) {
            cancellationError(cancellation)?.let { return@synchronized ResultFactory.failure(it) }
            ResultFactory.success(
                KnowledgeSnapshot(
                    functionAnnotations =
                        annotations.values
                            .sortedBy { it.identityKey }
                            .map { it.defensiveCopy() },
                    spanOverrides =
                        overrides.values
                            .sortedBy { it.selector.identityKey }
                            .map { it.defensiveCopy() },
                    patternHypotheses =
                        hypotheses.values
                            .sortedBy { it.selector.identityKey }
                            .map { it.defensiveCopy() },
                    packOrigins =
                        packOrigins.entries
                            .sortedBy { it.key }
                            .associateTo(LinkedHashMap()) { it.key to it.value },
                    materialized = hasStore,
                    timestamp = timestamp,
                ),
            )
        }
    }

    override fun functionAnnotations(cancellation: CancellationToken): ContractResult<List<FunctionAnnotation>> {
        cancellationError(cancellation)?.let { return ResultFactory.failure(it) }
        return synchronized(lock) {
            ResultFactory.success(
                annotations.values
                    .sortedBy { it.identityKey }
                    .map { it.defensiveCopy() },
            )
        }
    }

    override fun setFunctionAnnotation(
        annotation: FunctionAnnotation,
        cancellation: CancellationToken,
    ): ContractResult<FunctionAnnotation> {
        cancellationError(cancellation)?.let { return ResultFactory.failure(it) }
        validateFunctionAnnotation(annotation)?.let { return ResultFactory.failure(it) }
        return synchronized(lock) {
            cancellationError(cancellation)?.let { return@synchronized ResultFactory.failure(it) }
            val key = annotation.identityKey
            val existing = annotations[key]
            if (existing != null && annotation.provenance == Provenance.AI_FALLBACK &&
                !replaceableByAi(existing.provenance, existing.status)
            ) {
                return@synchronized ResultFactory.failure(
                    ContractError.PreconditionFailed("Fallback AI cannot replace existing function annotation $key"),
                )
            }
            if (existing != null && annotation.updatedAt < existing.updatedAt) {
                return@synchronized ResultFactory.failure(staleWrite("function annotation"))
            }
            val stored =
                annotation.defensiveCopy().copy(
                    createdAt = existing?.createdAt ?: annotation.createdAt,
                    updatedAt = latestInstant(existing?.updatedAt, annotation.updatedAt, now()),
                )
            annotations[key] = stored
            if (stored.provenance != Provenance.PACK_DEFAULT) {
                packOrigins.remove(key)
            }
            hasStore = true
            touch()
            ResultFactory.success(stored.defensiveCopy())
        }
    }

    override fun removeFunctionAnnotation(
        selector: FunctionSelector,
        cancellation: CancellationToken,
        path: String?,
    ): ContractResult<Unit> {
        cancellationError(cancellation)?.let { return ResultFactory.failure(it) }
        return synchronized(lock) {
            val removed =
                if (path == null) {
                    annotations.entries.removeAll { it.value.selector == selector }
                } else {
                    annotations.entries.removeAll {
                        it.value.selector == selector &&
                            it.value.scope == AnnotationScope.FILE &&
                            it.value.path == path
                    }
                }
            if (path == null) {
                packOrigins.remove(selector.identityKey)
            }
            if (removed) {
                touch()
            }
            ResultFactory.success(Unit)
        }
    }

    override fun spanOverrides(cancellation: CancellationToken): ContractResult<List<SpanOverride>> {
        cancellationError(cancellation)?.let { return ResultFactory.failure(it) }
        return synchronized(lock) {
            ResultFactory.success(
                overrides.values
                    .sortedBy { it.selector.identityKey }
                    .map { it.defensiveCopy() },
            )
        }
    }

    override fun setSpanOverride(
        override: SpanOverride,
        cancellation: CancellationToken,
    ): ContractResult<SpanOverride> {
        cancellationError(cancellation)?.let { return ResultFactory.failure(it) }
        validateSpanOverride(override)?.let { return ResultFactory.failure(it) }
        return synchronized(lock) {
            cancellationError(cancellation)?.let { return@synchronized ResultFactory.failure(it) }
            val key = override.selector.identityKey
            val existing = overrides[key]
            if (existing != null && override.provenance == Provenance.AI_FALLBACK &&
                !replaceableByAi(existing.provenance, existing.status)
            ) {
                return@synchronized ResultFactory.failure(
                    ContractError.PreconditionFailed("Fallback AI cannot replace existing span override $key"),
                )
            }
            if (existing != null && override.updatedAt < existing.updatedAt) {
                return@synchronized ResultFactory.failure(staleWrite("span override"))
            }
            val stored =
                override.defensiveCopy().copy(
                    createdAt = existing?.createdAt ?: override.createdAt,
                    updatedAt = latestInstant(existing?.updatedAt, override.updatedAt, now()),
                )
            overrides[key] = stored
            hasStore = true
            touch()
            ResultFactory.success(stored.defensiveCopy())
        }
    }

    override fun removeSpanOverride(
        selector: SpanSelector,
        cancellation: CancellationToken,
    ): ContractResult<Unit> {
        cancellationError(cancellation)?.let { return ResultFactory.failure(it) }
        return synchronized(lock) {
            if (overrides.remove(selector.identityKey) != null) {
                touch()
            }
            ResultFactory.success(Unit)
        }
    }

    override fun patternHypotheses(cancellation: CancellationToken): ContractResult<List<PatternHypothesis>> {
        cancellationError(cancellation)?.let { return ResultFactory.failure(it) }
        return synchronized(lock) {
            ResultFactory.success(
                hypotheses.values
                    .sortedBy { it.selector.identityKey }
                    .map { it.defensiveCopy() },
            )
        }
    }

    override fun setPatternHypothesis(
        hypothesis: PatternHypothesis,
        cancellation: CancellationToken,
    ): ContractResult<PatternHypothesis> {
        cancellationError(cancellation)?.let { return ResultFactory.failure(it) }
        validatePatternHypothesis(hypothesis)?.let { return ResultFactory.failure(it) }
        return synchronized(lock) {
            cancellationError(cancellation)?.let { return@synchronized ResultFactory.failure(it) }
            val key = hypothesis.selector.identityKey
            val existing = hypotheses[key]
            if (existing != null && hypothesis.updatedAt < existing.updatedAt) {
                return@synchronized ResultFactory.failure(staleWrite("pattern hypothesis"))
            }
            val stored =
                hypothesis.defensiveCopy().copy(
                    createdAt = existing?.createdAt ?: hypothesis.createdAt,
                    updatedAt = latestInstant(existing?.updatedAt, hypothesis.updatedAt, now()),
                )
            hypotheses[key] = stored
            hasStore = true
            touch()
            ResultFactory.success(stored.defensiveCopy())
        }
    }

    override fun removePatternHypothesis(
        selector: PatternSelector,
        cancellation: CancellationToken,
    ): ContractResult<Unit> {
        cancellationError(cancellation)?.let { return ResultFactory.failure(it) }
        return synchronized(lock) {
            if (hypotheses.remove(selector.identityKey) != null) {
                touch()
            }
            ResultFactory.success(Unit)
        }
    }

    override fun packOrigin(
        selector: FunctionSelector,
        cancellation: CancellationToken,
    ): ContractResult<PackOrigin?> {
        cancellationError(cancellation)?.let { return ResultFactory.failure(it) }
        return synchronized(lock) {
            ResultFactory.success(packOrigins[selector.identityKey])
        }
    }

    override fun materializeDefaults(
        defaults: List<PackDefault>,
        cancellation: CancellationToken,
    ): ContractResult<Unit> {
        cancellationError(cancellation)?.let { return ResultFactory.failure(it) }
        for (default in defaults) {
            validatePackDefault(default)?.let { return ResultFactory.failure(it) }
        }
        return synchronized(lock) {
            cancellationError(cancellation)?.let { return@synchronized ResultFactory.failure(it) }
            if (hasStore) {
                return@synchronized ResultFactory.success(Unit)
            }

            val winners =
                defaults
                    .filter { KnowledgeStatusPolicy.eligible(it.status) }
                    .sortedWith(packDefaultPrecedence)
                    .distinctBy { it.selector.identityKey }
            if (winners.isEmpty()) {
                return@synchronized ResultFactory.success(Unit)
            }

            val materializedAt = now()
            for (default in winners) {
                val key = default.selector.identityKey
                if (annotations.containsKey(key)) {
                    continue
                }
                val annotation =
                    FunctionAnnotation(
                        selector = default.selector,
                        intents = default.intents.toSet(),
                        scope = AnnotationScope.GLOBAL,
                        provenance = Provenance.PACK_DEFAULT,
                        confidence = default.confidence,
                        status = default.status,
                        createdAt = materializedAt,
                        updatedAt = materializedAt,
                    )
                annotations[annotation.identityKey] = annotation
                packOrigins[key] = PackOrigin(default.packId, default.packVersion)
            }
            hasStore = true
            touch()
            ResultFactory.success(Unit)
        }
    }

    override fun pruneStale(
        validFunctionSelectors: Set<FunctionSelector>,
        validSpanSelectors: Set<SpanSelector>,
        cancellation: CancellationToken,
        validFilePaths: Map<FunctionSelector, Set<String>>,
    ): ContractResult<Int> {
        cancellationError(cancellation)?.let { return ResultFactory.failure(it) }
        return synchronized(lock) {
            val functionKeys = validFunctionSelectors.mapTo(HashSet()) { it.identityKey }
            val filePaths =
                validFilePaths.mapKeys { it.key.identityKey }.mapValues { (_, paths) -> paths.toSet() }
            val spanKeys = validSpanSelectors.mapTo(HashSet()) { it.identityKey }
            val staleFunctions =
                annotations.entries
                    .filter { (_, annotation) ->
                        if (annotation.selector.identityKey !in functionKeys) {
                            true
                        } else if (annotation.scope == AnnotationScope.FILE) {
                            annotation.path !in filePaths[annotation.selector.identityKey].orEmpty()
                        } else {
                            false
                        }
                    }.map { it.key }
            val staleSpans = overrides.keys.filterNot { it in spanKeys }
            staleFunctions.forEach {
                annotations.remove(it)
                packOrigins.remove(it)
            }
            staleSpans.forEach { overrides.remove(it) }
            val removed = staleFunctions.size + staleSpans.size
            if (removed > 0) {
                touch()
            }
            ResultFactory.success(removed)
        }
    }

    override fun proposeChange(
        change: KnowledgeChange,
        cancellation: CancellationToken,
    ): ContractResult<Unit> {
        cancellationError(cancellation)?.let { return ResultFactory.failure(it) }
        return synchronized(lock) {
            cancellationError(cancellation)?.let { return@synchronized ResultFactory.failure(it) }
            val backup = captureState()
            val result = applyKnowledgeChange(this, change)
            if (result.isFailure) {
                restoreState(backup)
            }
            result
        }
    }

    private fun touch() {
        timestamp = now().toEpochMilliseconds()
    }

    private fun replaceableByAi(
        provenance: Provenance,
        status: Status,
    ): Boolean = provenance == Provenance.AI_FALLBACK && status == Status.SUGGESTED

    private fun captureState(): KnowledgeStoreState =
        KnowledgeStoreState(
            hasStore = hasStore,
            timestamp = timestamp,
            annotations = LinkedHashMap(annotations),
            overrides = LinkedHashMap(overrides),
            hypotheses = LinkedHashMap(hypotheses),
            packOrigins = LinkedHashMap(packOrigins),
        )

    private fun restoreState(state: KnowledgeStoreState) {
        hasStore = state.hasStore
        timestamp = state.timestamp
        annotations.clear()
        annotations.putAll(state.annotations)
        overrides.clear()
        overrides.putAll(state.overrides)
        hypotheses.clear()
        hypotheses.putAll(state.hypotheses)
        packOrigins.clear()
        packOrigins.putAll(state.packOrigins)
    }

    companion object {
        fun empty(now: () -> Instant = { Clock.System.now() }): InMemoryKnowledgeStore =
            InMemoryKnowledgeStore(
                initiallyExists = false,
                initialTimestamp = now().toEpochMilliseconds(),
                initialFunctionAnnotations = emptyList(),
                initialSpanOverrides = emptyList(),
                initialPatternHypotheses = emptyList(),
                initialPackOrigins = emptyMap(),
                now = now,
            )

        fun existing(now: () -> Instant = { Clock.System.now() }): InMemoryKnowledgeStore =
            InMemoryKnowledgeStore(
                initiallyExists = true,
                initialTimestamp = now().toEpochMilliseconds(),
                initialFunctionAnnotations = emptyList(),
                initialSpanOverrides = emptyList(),
                initialPatternHypotheses = emptyList(),
                initialPackOrigins = emptyMap(),
                now = now,
            )

        fun fromSnapshot(
            snapshot: KnowledgeSnapshot,
            exists: Boolean = true,
            now: () -> Instant = { Clock.System.now() },
        ): ContractResult<InMemoryKnowledgeStore> {
            validateSnapshot(snapshot)?.let { return ResultFactory.failure(it) }
            return ResultFactory.success(
                InMemoryKnowledgeStore(
                    initiallyExists = exists,
                    initialTimestamp = snapshot.timestamp,
                    initialFunctionAnnotations = snapshot.functionAnnotations,
                    initialSpanOverrides = snapshot.spanOverrides,
                    initialPatternHypotheses = snapshot.patternHypotheses,
                    initialPackOrigins = snapshot.packOrigins,
                    now = now,
                ),
            )
        }
    }
}
