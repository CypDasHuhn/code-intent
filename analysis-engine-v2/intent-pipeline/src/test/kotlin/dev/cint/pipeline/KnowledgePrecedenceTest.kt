package dev.cint.pipeline

import dev.cint.contracts.data.Confidence
import dev.cint.contracts.data.Intents
import dev.cint.contracts.data.Provenance
import dev.cint.contracts.data.Status
import dev.cint.contracts.facts.DeclarationFact
import dev.cint.contracts.facts.DeclarationKind
import dev.cint.contracts.facts.ImportFact
import dev.cint.contracts.knowledge.AnnotationScope
import dev.cint.contracts.knowledge.FunctionAnnotation
import dev.cint.contracts.knowledge.FunctionSelector
import dev.cint.contracts.knowledge.KnowledgeSnapshot
import dev.cint.contracts.knowledge.PackDefault
import dev.cint.contracts.knowledge.SpanOverride
import dev.cint.contracts.knowledge.SpanSelector
import dev.cint.contracts.util.CancellationToken
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class KnowledgePrecedenceTest {
    private val now = Instant.fromEpochMilliseconds(1)
    private val source = sourceWithFunctions("findUser")
    private val node = source.node("fn-0")

    @Test
    fun spanOverrideWinsThenFunctionAnnotationThenPackDefault() {
        val selector = FunctionSelector(qualifiedName = "findUser")
        val defaults = listOf(
            PackDefault(
                selector = selector,
                intents = setOf(Intents.SEARCH),
                packId = "demo",
                packVersion = "1",
                confidence = Confidence.MEDIUM,
                createdAt = now,
            ),
        )
        val annotation = FunctionAnnotation(
            selector = selector,
            intents = setOf(Intents.LOOKUP),
            scope = AnnotationScope.GLOBAL,
            provenance = Provenance.USER,
            confidence = Confidence.HIGH,
            status = Status.ACCEPTED,
            createdAt = now,
            updatedAt = now,
        )
        val override = SpanOverride(
            selector = SpanSelector(
                path = "Sample.fake",
                span = node.span,
                contentHash = HASH,
            ),
            intents = setOf(Intents.LOGGING),
            provenance = Provenance.USER,
            confidence = Confidence.ESTABLISHED,
            status = Status.ACCEPTED,
            rationale = "local",
            createdAt = now,
            updatedAt = now,
        )
        val resolver = PrecedenceKnowledgeInputResolver()

        val overridden = resolver.resolve(
            source,
            snapshot(functionAnnotations = listOf(annotation), spanOverrides = listOf(override)),
            defaults,
            CancellationToken.NONE,
        ).getOrThrow()
        assertEquals(setOf(Intents.LOGGING), overridden.contributions.single().intents)

        val annotated = resolver.resolve(
            source,
            snapshot(functionAnnotations = listOf(annotation)),
            defaults,
            CancellationToken.NONE,
        ).getOrThrow()
        assertEquals(setOf(Intents.LOOKUP), annotated.contributions.single().intents)

        val defaulted = resolver.resolve(
            source,
            snapshot(functionAnnotations = emptyList()),
            defaults,
            CancellationToken.NONE,
        ).getOrThrow()
        assertEquals(setOf(Intents.SEARCH), defaulted.contributions.single().intents)
    }

    @Test
    fun staleOverrideContentHashIsIgnored() {
        val override = SpanOverride(
            selector = SpanSelector(
                path = "Sample.fake",
                span = node.span,
                contentHash = "stale",
            ),
            intents = setOf(Intents.LOGGING),
            provenance = Provenance.USER,
            confidence = Confidence.HIGH,
            status = Status.ACCEPTED,
            rationale = "",
            createdAt = now,
            updatedAt = now,
        )
        val defaults = listOf(
            PackDefault(
                selector = FunctionSelector(qualifiedName = "findUser"),
                intents = setOf(Intents.SEARCH),
                packId = "demo",
                packVersion = "1",
                createdAt = now,
            ),
        )

        val contributions = PrecedenceKnowledgeInputResolver().resolve(
            source,
            snapshot(spanOverrides = listOf(override)),
            defaults,
            CancellationToken.NONE,
        ).getOrThrow()

        assertEquals(setOf(Intents.SEARCH), contributions.contributions.single().intents)
    }

    @Test
    fun rejectedAndSuppressedRecordsAreNotEligible() {
        val selector = FunctionSelector(qualifiedName = "findUser")
        val rejectedHighConfidence =
            FunctionAnnotation(
                selector = selector,
                intents = setOf(Intents.SEARCH),
                scope = AnnotationScope.GLOBAL,
                provenance = Provenance.USER,
                confidence = Confidence.MAX,
                status = Status.REJECTED,
                createdAt = now,
                updatedAt = now,
            )
        val accepted =
            rejectedHighConfidence.copy(
                intents = setOf(Intents.LOOKUP),
                confidence = Confidence.LOW,
                status = Status.ACCEPTED,
            )
        val suppressedDefault =
            PackDefault(
                selector = selector,
                intents = setOf(Intents.VALIDATION),
                packId = "demo",
                packVersion = "1",
                status = Status.SUPPRESSED,
                confidence = Confidence.MAX,
                createdAt = now,
            )
        val rejectedOverride =
            SpanOverride(
                selector = SpanSelector("Sample.fake", node.span, HASH),
                intents = setOf(Intents.LOGGING),
                provenance = Provenance.USER,
                confidence = Confidence.MAX,
                status = Status.REJECTED,
                rationale = "",
                createdAt = now,
                updatedAt = now,
            )

        val result = PrecedenceKnowledgeInputResolver().resolve(
            source,
            snapshot(
                functionAnnotations = listOf(rejectedHighConfidence, accepted),
                spanOverrides = listOf(rejectedOverride),
            ),
            listOf(suppressedDefault),
            CancellationToken.NONE,
        ).getOrThrow()

        assertEquals(setOf(Intents.LOOKUP), result.contributions.single().intents)
    }

    @Test
    fun materializedSnapshotDoesNotResurrectRawPackDefaults() {
        val defaults = listOf(
            PackDefault(
                selector = FunctionSelector(qualifiedName = "findUser"),
                intents = setOf(Intents.SEARCH),
                packId = "demo",
                packVersion = "1",
                createdAt = now,
            ),
        )
        val resolver = PrecedenceKnowledgeInputResolver()

        val uninitialized = resolver.resolve(
            source,
            snapshot(),
            defaults,
            CancellationToken.NONE,
        ).getOrThrow()
        assertEquals(setOf(Intents.SEARCH), uninitialized.contributions.single().intents)

        val initialized = resolver.resolve(
            source,
            snapshot(materialized = true),
            defaults,
            CancellationToken.NONE,
        ).getOrThrow()
        assertEquals(emptyList(), initialized.contributions)
    }

    @Test
    fun structuralAndReceiverSelectorsMatchFunctions() {
        val prefixSource = sourceWithFunctions("findByUser")
        val prefixDefault =
            PackDefault(
                selector = FunctionSelector(structuralPattern = "token-prefix:findBy"),
                intents = setOf(Intents.SEARCH),
                packId = "data-access",
                packVersion = "1",
                createdAt = now,
            )
        val prefix = PrecedenceKnowledgeInputResolver().resolve(
            prefixSource,
            snapshot(),
            listOf(prefixDefault),
            CancellationToken.NONE,
        ).getOrThrow()
        assertEquals(setOf(Intents.SEARCH), prefix.contributions.single().intents)

        val receiverSource = sourceWithFunctions("org.slf4j.Logger.info")
        val receiverDefault =
            PackDefault(
                selector = FunctionSelector(
                    qualifiedName = "info",
                    receiverType = "org.slf4j.Logger",
                ),
                intents = setOf(Intents.LOGGING),
                packId = "logging",
                packVersion = "1",
                createdAt = now,
            )
        val receiver = PrecedenceKnowledgeInputResolver().resolve(
            receiverSource,
            snapshot(),
            listOf(receiverDefault),
            CancellationToken.NONE,
        ).getOrThrow()
        assertEquals(setOf(Intents.LOGGING), receiver.contributions.single().intents)
    }

    @Test
    fun importNamesDoNotAnnotateLocalDeclarationsAndAnnotationSelectorsMatch() {
        val imported = sourceWithFunctions("println")
        val importedSource = imported.copy(
            facts = imported.facts.copy(
                imports = listOf(ImportFact("import-1", "kotlin.io.println")),
            ),
        )
        val importDefault =
            PackDefault(
                selector = FunctionSelector(qualifiedName = "kotlin.io.println"),
                intents = setOf(Intents.LOGGING),
                packId = "logging",
                packVersion = "1",
                createdAt = now,
            )
        val importedResult = PrecedenceKnowledgeInputResolver().resolve(
            importedSource,
            snapshot(),
            listOf(importDefault),
            CancellationToken.NONE,
        ).getOrThrow()
        assertEquals(emptyList(), importedResult.contributions)

        val importPathDefault =
            PackDefault(
                selector = FunctionSelector(
                    qualifiedName = "println",
                    importPath = "kotlin.io.println",
                ),
                intents = setOf(Intents.LOGGING),
                packId = "logging",
                packVersion = "1",
                createdAt = now,
            )
        val importPathResult = PrecedenceKnowledgeInputResolver().resolve(
            importedSource,
            snapshot(),
            listOf(importPathDefault),
            CancellationToken.NONE,
        ).getOrThrow()
        assertEquals(emptyList(), importPathResult.contributions)
        assertEquals(listOf("KNOWLEDGE_SELECTOR_UNSUPPORTED"), importPathResult.errors.map { it.code })

        val annotated = sourceWithFunctions("record")
        val annotatedSource = annotated.copy(
            facts = annotated.facts.copy(
                declarations = listOf(
                    DeclarationFact(
                        nodeId = "fn-0",
                        kind = DeclarationKind.FUNCTION,
                        name = "record",
                        nameNodeId = null,
                        annotations = listOf("@Bookkeeping"),
                    ),
                ),
            ),
        )
        val annotationDefault =
            PackDefault(
                selector = FunctionSelector(structuralPattern = "@Bookkeeping"),
                intents = setOf(Intents.BOOKKEEPING),
                packId = "bookkeeping",
                packVersion = "1",
                createdAt = now,
            )
        val annotatedResult = PrecedenceKnowledgeInputResolver().resolve(
            annotatedSource,
            snapshot(),
            listOf(annotationDefault),
            CancellationToken.NONE,
        ).getOrThrow()
        assertEquals(setOf(Intents.BOOKKEEPING), annotatedResult.contributions.single().intents)
    }

    @Test
    fun unresolvedReceiverTypeIsReportedInsteadOfSilentlyNoMatching() {
        val unqualified = sourceWithFunctions("info")
        val receiverDefault =
            PackDefault(
                selector = FunctionSelector(
                    qualifiedName = "info",
                    receiverType = "org.slf4j.Logger",
                ),
                intents = setOf(Intents.LOGGING),
                packId = "logging",
                packVersion = "1",
                createdAt = now,
            )

        val result = PrecedenceKnowledgeInputResolver().resolve(
            unqualified,
            snapshot(),
            listOf(receiverDefault),
            CancellationToken.NONE,
        ).getOrThrow()

        assertEquals(emptyList(), result.contributions)
        assertEquals(listOf("KNOWLEDGE_SELECTOR_UNSUPPORTED"), result.errors.map { it.code })
    }

    @Test
    fun unsupportedStructuralSelectorIsReportedNotSilentlyDropped() {
        val unsupported =
            PackDefault(
                selector = FunctionSelector(structuralPattern = "throw"),
                intents = setOf(Intents.ERROR_HANDLING),
                packId = "error-handling",
                packVersion = "1",
                createdAt = now,
            )

        val result = PrecedenceKnowledgeInputResolver().resolve(
            source,
            snapshot(),
            listOf(unsupported),
            CancellationToken.NONE,
        ).getOrThrow()

        assertEquals(emptyList(), result.contributions)
        assertEquals(listOf("KNOWLEDGE_SELECTOR_UNSUPPORTED"), result.errors.map { it.code })
    }

    private fun snapshot(
        functionAnnotations: List<FunctionAnnotation> = emptyList(),
        spanOverrides: List<SpanOverride> = emptyList(),
        materialized: Boolean = false,
    ) = KnowledgeSnapshot(
        functionAnnotations = functionAnnotations,
        spanOverrides = spanOverrides,
        patternHypotheses = emptyList(),
        materialized = materialized,
        timestamp = 1,
    )
}
