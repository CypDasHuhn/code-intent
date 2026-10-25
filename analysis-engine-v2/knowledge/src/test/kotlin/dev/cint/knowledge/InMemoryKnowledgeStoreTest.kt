package dev.cint.knowledge

import dev.cint.contracts.data.Confidence
import dev.cint.contracts.data.Intents
import dev.cint.contracts.data.Provenance
import dev.cint.contracts.data.Span
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
import dev.cint.contracts.util.CancellationToken
import dev.cint.contracts.util.ContractError
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InMemoryKnowledgeStoreTest {
    private val fixedNow = Instant.parse("2025-01-01T00:00:00Z")
    private val later = Instant.parse("2025-01-02T00:00:00Z")
    private val clock = { fixedNow }

    private fun annotation(
        selector: FunctionSelector = FunctionSelector(qualifiedName = "com.example.Service.findUser"),
        intents: Set<String> = setOf(Intents.LOOKUP),
        status: Status = Status.ACCEPTED,
        createdAt: Instant = fixedNow,
        scope: AnnotationScope = AnnotationScope.GLOBAL,
        path: String? = null,
    ): FunctionAnnotation = FunctionAnnotation.create(
        selector = selector,
        intents = intents,
        scope = scope,
        provenance = Provenance.USER,
        confidence = Confidence.HIGH,
        status = status,
        createdAt = createdAt,
        path = path,
    )

    private fun spanOverride(
        selector: SpanSelector = SpanSelector("Service.kt", Span(1, 0, 1, 10), "hash"),
        intents: Set<String> = setOf(Intents.VALIDATION),
    ): SpanOverride = SpanOverride.create(
        selector = selector,
        intents = intents,
        provenance = Provenance.USER,
        rationale = "explicit",
        createdAt = fixedNow,
    )

    private fun hypothesis(
        selector: PatternSelector = PatternSelector(pattern = "logger"),
        evidence: List<String> = listOf("evidence"),
        status: Status = Status.SUGGESTED,
    ): PatternHypothesis = PatternHypothesis.create(
        selector = selector,
        scope = AnnotationScope.GLOBAL,
        evidence = evidence,
        examples = listOf("logger.info(...)"),
        counterexamples = emptyList(),
        proposedIntents = setOf(Intents.LOGGING),
        status = status,
        createdAt = fixedNow,
    )

    private fun packDefault(
        selector: FunctionSelector = FunctionSelector(qualifiedName = "packed"),
        intents: Set<String> = setOf(Intents.LOGGING),
        packId: String = "logging",
        confidence: Confidence = Confidence.MEDIUM,
    ): PackDefault = PackDefault(
        selector = selector,
        intents = intents,
        packId = packId,
        packVersion = "1.0.0",
        confidence = confidence,
        createdAt = fixedNow,
    )

    private fun snapshot(
        annotations: List<FunctionAnnotation> = emptyList(),
        overrides: List<SpanOverride> = emptyList(),
        hypotheses: List<PatternHypothesis> = emptyList(),
        packOrigins: Map<String, PackOrigin> = emptyMap(),
    ): KnowledgeSnapshot = KnowledgeSnapshot(
        functionAnnotations = annotations,
        spanOverrides = overrides,
        patternHypotheses = hypotheses,
        packOrigins = packOrigins,
        timestamp = 1,
    )

    @Test
    fun selectorIdentityCoversEveryFieldAndKeepsEntriesDistinct() {
        val selectors = listOf(
            FunctionSelector(qualifiedName = "a"),
            FunctionSelector(qualifiedName = "a", importPath = "b"),
            FunctionSelector(qualifiedName = "a", receiverType = "b"),
            FunctionSelector(qualifiedName = "a", declaringType = "b"),
            FunctionSelector(qualifiedName = "a", signature = "b"),
            FunctionSelector(qualifiedName = "a", structuralPattern = "b"),
        )
        assertEquals(selectors.size, selectors.map { it.identityKey }.toSet().size)

        val store = InMemoryKnowledgeStore.empty(clock)
        selectors.forEach { selector ->
            assertTrue(store.setFunctionAnnotation(annotation(selector)).isSuccess)
        }
        assertEquals(selectors.size, store.functionAnnotations().getOrThrow().size)
    }

    @Test
    fun duplicateSetReplacesTheExistingEntry() {
        val store = InMemoryKnowledgeStore.empty(clock)
        val selector = FunctionSelector(qualifiedName = "a")
        store.setFunctionAnnotation(annotation(selector, setOf(Intents.LOOKUP), Status.SUGGESTED))
        store.setFunctionAnnotation(annotation(selector, setOf(Intents.SEARCH), Status.ESTABLISHED))

        val entries = store.functionAnnotations().getOrThrow()
        assertEquals(1, entries.size)
        assertEquals(setOf(Intents.SEARCH), entries.single().intents)
        assertEquals(Status.ESTABLISHED, entries.single().status)
    }

    @Test
    fun staleUpdatesAreRejectedAndCreatedAtIsPreserved() {
        val selector = FunctionSelector(qualifiedName = "a")
        val store = InMemoryKnowledgeStore.empty(clock)
        val first = annotation(selector, createdAt = fixedNow)
        store.setFunctionAnnotation(first)
        val updated = annotation(selector, intents = setOf(Intents.SEARCH), createdAt = later)
        assertTrue(store.setFunctionAnnotation(updated).isSuccess)

        val stored = store.functionAnnotations().getOrThrow().single()
        assertEquals(fixedNow, stored.createdAt)
        assertEquals(later, stored.updatedAt)

        val stale = annotation(selector, intents = setOf(Intents.LOGGING), createdAt = fixedNow)
        val rejected = store.setFunctionAnnotation(stale)
        assertTrue(rejected.isFailure)
        val error = assertIs<ContractError.ValidationError>(rejected.errorOrNull)
        assertEquals("updatedAt", error.field)
        assertEquals(setOf(Intents.SEARCH), store.functionAnnotations().getOrThrow().single().intents)
    }

    @Test
    fun callerCollectionsCannotMutateStoreOrSnapshots() {
        val mutableIntents = mutableSetOf(Intents.LOOKUP, Intents.SEARCH)
        val store = InMemoryKnowledgeStore.empty(clock)
        val returned = store.setFunctionAnnotation(
            annotation(FunctionSelector(qualifiedName = "a"), mutableIntents),
        ).getOrThrow()
        mutableIntents.add(Intents.LOGGING)
        (returned.intents as MutableSet).add(Intents.LOGGING)

        val first = store.snapshot().getOrThrow().functionAnnotations.single()
        assertEquals(setOf(Intents.LOOKUP, Intents.SEARCH), first.intents)
        (first.intents as MutableSet).add(Intents.LOGGING)
        assertEquals(
            setOf(Intents.LOOKUP, Intents.SEARCH),
            store.snapshot().getOrThrow().functionAnnotations.single().intents,
        )

        val evidence = mutableListOf("first", "second")
        store.setPatternHypothesis(hypothesis(evidence = evidence))
        evidence.add("third")
        assertEquals(listOf("first", "second"), store.patternHypotheses().getOrThrow().single().evidence)
    }

    @Test
    fun overridePrecedenceIsResolvedFromSnapshotData() {
        val functionSelector = FunctionSelector(qualifiedName = "a")
        val spanSelector = SpanSelector("A.kt", Span(2, 0, 2, 5), "hash")
        val snapshot = snapshot(
            annotations = listOf(annotation(functionSelector, setOf(Intents.LOOKUP))),
            overrides = listOf(spanOverride(spanSelector, setOf(Intents.VALIDATION))),
        )

        assertEquals(
            setOf(Intents.VALIDATION),
            KnowledgeLookup.effectiveIntents(snapshot, functionSelector, spanSelector),
        )
        assertEquals(
            setOf(Intents.LOOKUP),
            KnowledgeLookup.effectiveIntents(snapshot, functionSelector),
        )
    }

    @Test
    fun duplicatePatternSelectorsReplaceAndRemovalIsIdempotent() {
        val store = InMemoryKnowledgeStore.empty(clock)
        val selector = PatternSelector(pattern = "logger")
        store.setPatternHypothesis(hypothesis(selector, listOf("first")))
        store.setPatternHypothesis(hypothesis(selector, listOf("second"), Status.ACCEPTED))
        assertEquals(listOf("second"), store.patternHypotheses().getOrThrow().single().evidence)

        assertTrue(store.removePatternHypothesis(selector).isSuccess)
        assertTrue(store.removePatternHypothesis(selector).isSuccess)
        assertTrue(store.patternHypotheses().getOrThrow().isEmpty())
        assertTrue(
            store.removeFunctionAnnotation(FunctionSelector(qualifiedName = "missing")).isSuccess,
        )
    }

    @Test
    fun staleEntriesArePrunedByLiveSelectorIdentity() {
        val keep = FunctionSelector(qualifiedName = "keep")
        val stale = FunctionSelector(qualifiedName = "stale")
        val keepSpan = SpanSelector("A.kt", Span(1, 0, 1, 1), "keep")
        val staleSpan = SpanSelector("A.kt", Span(2, 0, 2, 1), "stale")
        val store = InMemoryKnowledgeStore.empty(clock)
        store.setFunctionAnnotation(annotation(keep))
        store.setFunctionAnnotation(annotation(stale))
        store.setSpanOverride(spanOverride(keepSpan))
        store.setSpanOverride(spanOverride(staleSpan))

        val removed = store.pruneStale(setOf(keep), setOf(keepSpan)).getOrThrow()

        assertEquals(2, removed)
        assertEquals(listOf(keep), store.functionAnnotations().getOrThrow().map { it.selector })
        assertEquals(listOf(keepSpan), store.spanOverrides().getOrThrow().map { it.selector })
    }

    @Test
    fun fallbackAiCannotReplaceAnotherWritersKnowledge() {
        val selector = FunctionSelector(qualifiedName = "user")
        val userSuggested = storeWith(annotation(selector, setOf(Intents.SEARCH), Status.SUGGESTED))
        val aiSuggestion = FunctionAnnotation.create(
            selector = selector,
            intents = setOf(Intents.LOOKUP),
            provenance = Provenance.AI_FALLBACK,
            status = Status.SUGGESTED,
            createdAt = later,
        )

        val suggested = userSuggested.setFunctionAnnotation(aiSuggestion)
        assertTrue(suggested.isFailure)
        assertIs<ContractError.PreconditionFailed>(suggested.errorOrNull)
        assertEquals(setOf(Intents.SEARCH), userSuggested.functionAnnotations().getOrThrow().single().intents)

        val userAccepted = storeWith(annotation(selector, setOf(Intents.SEARCH), Status.ACCEPTED))
        val accepted = userAccepted.setFunctionAnnotation(aiSuggestion)
        assertTrue(accepted.isFailure)
        assertIs<ContractError.PreconditionFailed>(accepted.errorOrNull)
        assertEquals(Status.ACCEPTED, userAccepted.functionAnnotations().getOrThrow().single().status)

        val aiOnly = storeWith(aiSuggestion.copy(createdAt = fixedNow, updatedAt = fixedNow))
        assertTrue(
            aiOnly.setFunctionAnnotation(aiSuggestion.copy(intents = setOf(Intents.SEARCH), createdAt = later))
                .isSuccess,
        )
        assertEquals(setOf(Intents.SEARCH), aiOnly.functionAnnotations().getOrThrow().single().intents)
    }

    @Test
    fun fallbackAiCannotReplaceAnotherWritersSpanOverride() {
        val selector = SpanSelector("A.kt", Span(1, 0, 1, 1), "h")
        val store = InMemoryKnowledgeStore.empty(clock)
        assertTrue(store.setSpanOverride(spanOverride(selector)).isSuccess)
        val aiOverride = SpanOverride.create(
            selector = selector,
            intents = setOf(Intents.LOOKUP),
            provenance = Provenance.AI_FALLBACK,
            status = Status.SUGGESTED,
            createdAt = later,
        )

        val result = store.setSpanOverride(aiOverride)

        assertTrue(result.isFailure)
        assertIs<ContractError.PreconditionFailed>(result.errorOrNull)
        assertEquals(setOf(Intents.VALIDATION), store.spanOverrides().getOrThrow().single().intents)
    }

    private fun storeWith(annotation: FunctionAnnotation): InMemoryKnowledgeStore =
        InMemoryKnowledgeStore.fromSnapshot(
            snapshot(annotations = listOf(annotation)),
            exists = true,
            now = clock,
        ).getOrThrow()

    @Test
    fun invalidWritesAreRejectedWithoutChangingTheStore() {
        val store = InMemoryKnowledgeStore.empty(clock)
        val unknownIntent = store.setFunctionAnnotation(annotation(intents = setOf("NOT_AN_INTENT")))
        assertTrue(unknownIntent.isFailure)
        assertIs<ContractError.ValidationError>(unknownIntent.errorOrNull)
        assertTrue(store.functionAnnotations().getOrThrow().isEmpty())

        assertTrue(store.setSpanOverride(spanOverride(intents = emptySet())).isFailure)
        assertTrue(store.spanOverrides().getOrThrow().isEmpty())

        val backwards = annotation().copy(
            createdAt = Instant.parse("2025-01-02T00:00:00Z"),
            updatedAt = Instant.parse("2025-01-01T00:00:00Z"),
        )
        assertTrue(store.setFunctionAnnotation(backwards).isFailure)
    }

    @Test
    fun cancellationStopsEveryMutator() {
        val store = InMemoryKnowledgeStore.empty(clock)
        val cancelled = CancellationToken.fromBoolean(true)
        val selector = FunctionSelector(qualifiedName = "a")
        val spanSelector = SpanSelector("A.kt", Span(1, 0, 1, 1), "h")
        val patternSelector = PatternSelector(pattern = "p")

        assertTrue(store.snapshot(cancelled).isFailure)
        assertTrue(store.functionAnnotations(cancelled).isFailure)
        assertTrue(store.setFunctionAnnotation(annotation(selector), cancelled).isFailure)
        assertTrue(store.removeFunctionAnnotation(selector, cancelled).isFailure)
        assertTrue(store.spanOverrides(cancelled).isFailure)
        assertTrue(store.setSpanOverride(spanOverride(spanSelector), cancelled).isFailure)
        assertTrue(store.removeSpanOverride(spanSelector, cancelled).isFailure)
        assertTrue(store.patternHypotheses(cancelled).isFailure)
        assertTrue(store.setPatternHypothesis(hypothesis(patternSelector), cancelled).isFailure)
        assertTrue(store.removePatternHypothesis(patternSelector, cancelled).isFailure)
        assertTrue(store.packOrigin(selector, cancelled).isFailure)
        assertTrue(store.materializeDefaults(listOf(packDefault()), cancelled).isFailure)
        assertTrue(store.pruneStale(setOf(selector), setOf(spanSelector), cancelled).isFailure)
        assertTrue(
            store.proposeChange(
                KnowledgeChange.SetFunctionAnnotation(annotation(selector)),
                cancelled,
            ).isFailure,
        )
        assertTrue(store.functionAnnotations().getOrThrow().isEmpty())
        assertTrue(!store.isMaterialized)
    }

    @Test
    fun proposeChangeAppliesNestedChangesAtomically() {
        val selector = FunctionSelector(qualifiedName = "a")
        val store = InMemoryKnowledgeStore.empty(clock)
        val valid = KnowledgeChange.Batch(
            description = "all valid",
            changes = listOf(
                KnowledgeChange.SetFunctionAnnotation(annotation(selector)),
                KnowledgeChange.SetSpanOverride(spanOverride()),
                KnowledgeChange.SetPatternHypothesis(hypothesis()),
            ),
        )
        assertTrue(store.proposeChange(valid).isSuccess)
        assertEquals(1, store.functionAnnotations().getOrThrow().size)
        assertEquals(1, store.spanOverrides().getOrThrow().size)
        assertEquals(1, store.patternHypotheses().getOrThrow().size)

        val invalid = KnowledgeChange.Batch(
            description = "one invalid",
            changes = listOf(
                KnowledgeChange.SetFunctionAnnotation(annotation(FunctionSelector(qualifiedName = "b"))),
                KnowledgeChange.SetFunctionAnnotation(annotation(intents = setOf("BAD"))),
            ),
        )
        assertTrue(store.proposeChange(invalid).isFailure)
        assertEquals(1, store.functionAnnotations().getOrThrow().size)
        assertNull(
            store.functionAnnotations().getOrThrow().find { it.selector.qualifiedName == "b" },
        )
    }

    @Test
    fun materializationIsObservableAndOnlyHappensWhenNoStoreExists() {
        val selector = FunctionSelector(qualifiedName = "existing")
        val seeded = InMemoryKnowledgeStore.fromSnapshot(
            snapshot(annotations = listOf(annotation(selector, setOf(Intents.LOOKUP)))),
            exists = true,
            now = clock,
        ).getOrThrow()
        assertTrue(seeded.isMaterialized)

        val default = packDefault(selector, setOf(Intents.SEARCH))
        assertTrue(seeded.materializeDefaults(listOf(default)).isSuccess)
        val unchanged = seeded.functionAnnotations().getOrThrow().single()
        assertEquals(setOf(Intents.LOOKUP), unchanged.intents)
        assertEquals(Provenance.USER, unchanged.provenance)
        assertNull(seeded.packOrigin(selector).getOrThrow())

        val fresh = InMemoryKnowledgeStore.empty(clock)
        assertTrue(!fresh.isMaterialized)
        assertTrue(fresh.materializeDefaults(listOf(packDefault())).isSuccess)
        assertTrue(fresh.isMaterialized)
    }

    @Test
    fun invalidDefaultsFailEvenWhenAStoreExists() {
        val store = InMemoryKnowledgeStore.existing(clock)
        val invalid = packDefault(intents = setOf("NOT_AN_INTENT"))
        val result = store.materializeDefaults(listOf(invalid))
        assertTrue(result.isFailure)
        assertIs<ContractError.ValidationError>(result.errorOrNull)
    }

    @Test
    fun materializationPreservesEditsAndRecordsPackOriginInSnapshots() {
        val existingSelector = FunctionSelector(qualifiedName = "existing")
        val addedSelector = FunctionSelector(qualifiedName = "added")
        val seeded = InMemoryKnowledgeStore.fromSnapshot(
            snapshot(annotations = listOf(annotation(existingSelector, setOf(Intents.LOOKUP)))),
            exists = false,
            now = clock,
        ).getOrThrow()
        val defaults = listOf(
            packDefault(existingSelector, setOf(Intents.SEARCH), "pack-b"),
            packDefault(addedSelector, setOf(Intents.LOGGING), "pack-a"),
        )

        assertTrue(seeded.materializeDefaults(defaults).isSuccess)

        val entries = seeded.functionAnnotations().getOrThrow().associateBy { it.selector.identityKey }
        assertEquals(setOf(Intents.LOOKUP), entries.getValue(existingSelector.identityKey).intents)
        assertEquals(
            Provenance.PACK_DEFAULT,
            entries.getValue(addedSelector.identityKey).provenance,
        )
        assertEquals(PackOrigin("pack-a", "1.0.0"), seeded.packOrigin(addedSelector).getOrThrow())
        assertNull(seeded.packOrigin(existingSelector).getOrThrow())
        assertEquals(
            PackOrigin("pack-a", "1.0.0"),
            seeded.snapshot().getOrThrow().packOrigins.getValue(addedSelector.identityKey),
        )

        val cloned = InMemoryKnowledgeStore.fromSnapshot(
            seeded.snapshot().getOrThrow(),
            now = clock,
        ).getOrThrow()
        assertEquals(
            PackOrigin("pack-a", "1.0.0"),
            cloned.packOrigin(addedSelector).getOrThrow(),
        )
    }

    @Test
    fun explicitOverrideClearsPackOrigin() {
        val selector = FunctionSelector(qualifiedName = "packed")
        val store = InMemoryKnowledgeStore.empty(clock)
        store.materializeDefaults(listOf(packDefault(selector)))
        assertEquals(PackOrigin("logging", "1.0.0"), store.packOrigin(selector).getOrThrow())

        val override = annotation(
            selector,
            setOf(Intents.LOOKUP),
            createdAt = Instant.parse("2025-01-03T00:00:00Z"),
        )
        assertTrue(store.setFunctionAnnotation(override).isSuccess)
        assertNull(store.packOrigin(selector).getOrThrow())
    }

    @Test
    fun duplicateDefaultsUseDeterministicPrecedence() {
        val selector = FunctionSelector(qualifiedName = "a")
        val low = packDefault(selector, setOf(Intents.LOOKUP), "pack-a", Confidence.LOW)
        val high = packDefault(selector, setOf(Intents.SEARCH), "pack-b", Confidence.HIGH)

        val first = InMemoryKnowledgeStore.empty(clock)
        val second = InMemoryKnowledgeStore.empty(clock)
        first.materializeDefaults(listOf(low, high))
        second.materializeDefaults(listOf(high, low))

        val firstEntry = first.functionAnnotations().getOrThrow().single()
        val secondEntry = second.functionAnnotations().getOrThrow().single()
        assertEquals(setOf(Intents.SEARCH), firstEntry.intents)
        assertEquals(firstEntry, secondEntry)
        assertEquals(PackOrigin("pack-b", "1.0.0"), first.packOrigin(selector).getOrThrow())
    }

    @Test
    fun rejectedAndSuppressedKnowledgeCannotWinLookupOrMaterialization() {
        val functionSelector = FunctionSelector(qualifiedName = "a")
        val spanSelector = SpanSelector("A.kt", Span(2, 0, 2, 5), "hash")
        val rejectedOverride = spanOverride(spanSelector).copy(status = Status.REJECTED)
        val suppressedOverride = spanOverride(spanSelector).copy(status = Status.SUPPRESSED)
        val accepted = annotation(functionSelector, setOf(Intents.LOOKUP))

        assertEquals(
            setOf(Intents.LOOKUP),
            KnowledgeLookup.effectiveIntents(
                snapshot(annotations = listOf(accepted), overrides = listOf(rejectedOverride, suppressedOverride)),
                functionSelector,
                spanSelector,
            ),
        )
        assertEquals(
            emptySet(),
            KnowledgeLookup.effectiveIntents(
                snapshot(annotations = listOf(accepted.copy(status = Status.REJECTED))),
                functionSelector,
            ),
        )

        val store = InMemoryKnowledgeStore.empty(clock)
        store.materializeDefaults(
            listOf(
                packDefault(functionSelector, setOf(Intents.SEARCH)).copy(status = Status.REJECTED),
                packDefault(FunctionSelector(qualifiedName = "b"), setOf(Intents.VALIDATION))
                    .copy(status = Status.SUPPRESSED),
            ),
        )
        assertTrue(store.functionAnnotations().getOrThrow().isEmpty())
    }

    @Test
    fun fileScopedAnnotationsWithOneSelectorCoexistAcrossPaths() {
        val selector = FunctionSelector(qualifiedName = "findUser")
        val first = annotation(selector, scope = AnnotationScope.FILE, path = "A.kt")
        val second = annotation(selector, scope = AnnotationScope.FILE, path = "B.kt")
        assertTrue(first.identityKey != second.identityKey)

        val store = InMemoryKnowledgeStore.empty(clock)
        assertTrue(store.setFunctionAnnotation(first).isSuccess)
        assertTrue(store.setFunctionAnnotation(second).isSuccess)

        val annotations = store.functionAnnotations().getOrThrow()
        assertEquals(setOf("A.kt", "B.kt"), annotations.mapNotNull { it.path }.toSet())
        assertEquals(setOf(first.identityKey, second.identityKey), annotations.map { it.identityKey }.toSet())
        assertTrue(InMemoryKnowledgeStore.fromSnapshot(snapshot(annotations = listOf(first, second))).isSuccess)

        assertTrue(store.removeFunctionAnnotation(selector, path = "A.kt").isSuccess)
        assertEquals(listOf("B.kt"), store.functionAnnotations().getOrThrow().mapNotNull { it.path })

        assertTrue(store.removeFunctionAnnotation(selector).isSuccess)
        assertTrue(store.functionAnnotations().getOrThrow().isEmpty())
    }

    @Test
    fun pruneAndLookupArePathAware() {
        val selector = FunctionSelector(qualifiedName = "findUser")
        val first = annotation(selector, setOf(Intents.SEARCH), scope = AnnotationScope.FILE, path = "A.kt")
        val second = annotation(selector, setOf(Intents.LOOKUP), scope = AnnotationScope.FILE, path = "B.kt")
        val knowledge = snapshot(annotations = listOf(first, second))

        assertEquals(
            setOf(Intents.SEARCH),
            KnowledgeLookup.effectiveIntents(knowledge, selector, path = "A.kt"),
        )
        assertEquals(
            setOf(Intents.LOOKUP),
            KnowledgeLookup.effectiveIntents(knowledge, selector, path = "B.kt"),
        )
        assertEquals(emptySet(), KnowledgeLookup.effectiveIntents(knowledge, selector))

        val store = InMemoryKnowledgeStore.fromSnapshot(knowledge).getOrThrow()
        assertEquals(
            1,
            store.pruneStale(
                setOf(selector),
                emptySet(),
                validFilePaths = mapOf(selector to setOf("A.kt")),
            ).getOrThrow(),
        )
        assertEquals(listOf("A.kt"), store.functionAnnotations().getOrThrow().mapNotNull { it.path })
    }

    @Test
    fun snapshotsAreStableAndVersioned() {
        val store = InMemoryKnowledgeStore.empty(clock)
        store.setFunctionAnnotation(annotation(FunctionSelector(qualifiedName = "b")))
        store.setFunctionAnnotation(annotation(FunctionSelector(qualifiedName = "a")))
        val result = store.snapshot().getOrThrow()

        assertEquals(KnowledgeSnapshot.SUPPORTED_VERSION, result.version)
        assertEquals(listOf("a", "b"), result.functionAnnotations.map { it.selector.qualifiedName })
        assertTrue(result.timestamp > 0)
    }
}
