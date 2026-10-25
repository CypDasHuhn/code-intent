package dev.cint.pipeline

import dev.cint.contracts.data.Annotation
import dev.cint.contracts.data.Completeness
import dev.cint.contracts.data.Confidence
import dev.cint.contracts.data.Contribution
import dev.cint.contracts.data.Intents
import dev.cint.contracts.data.Role
import dev.cint.contracts.facts.CallFact
import dev.cint.contracts.facts.CommentFact
import dev.cint.contracts.facts.CommentKind
import dev.cint.contracts.facts.DeclarationFact
import dev.cint.contracts.facts.DeclarationKind
import dev.cint.contracts.facts.FactBatch
import dev.cint.contracts.facts.FunctionFact
import dev.cint.contracts.facts.GuardFact
import dev.cint.contracts.facts.GuardKind
import dev.cint.contracts.facts.IndexedSource
import dev.cint.contracts.facts.PropertyFact
import dev.cint.contracts.facts.SourceInput
import dev.cint.contracts.facts.SyntaxNode
import dev.cint.contracts.facts.SyntaxTree
import dev.cint.contracts.facts.WriteFact
import dev.cint.contracts.facts.WriteKind
import dev.cint.contracts.knowledge.KnowledgeSnapshot
import dev.cint.contracts.ports.AnalysisRequest
import dev.cint.contracts.ports.CandidateEmission
import dev.cint.contracts.ports.EstablishedEmission
import dev.cint.contracts.ports.RuleEvaluationView
import dev.cint.contracts.ports.RuleEvaluator
import dev.cint.contracts.ports.RuleSpec
import dev.cint.packs.BundledRulePackSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BundledRuleEvaluatorsTest {
    @Test
    fun registryCoversEveryDefaultEnabledBundledRule() {
        val source = BundledRulePackSource()
        val registry = BundledRuleEvaluators.registry()
        val expected = mutableSetOf<String>()
        for (manifest in source.manifests().getOrThrow().filter { it.defaultEnabled }) {
            for (spec in source.playbook(manifest.id).getOrThrow().rules) {
                expected += spec.id
                val evaluator = assertNotNull(registry.resolve(spec), "missing evaluator ${spec.id}")
                assertEquals(spec, evaluator.spec)
            }
        }
        assertTrue(expected.containsAll(EXPECTED_RULE_IDS))
    }

    @Test
    fun bundledEvaluatorsProduceIntentsThroughTheRealPipeline() {
        val tree = tree()
        val source =
            IndexedSource(
                input = SourceInput.InMemory("", "kotlin", 1, "Sample.kt"),
                facts =
                    FactBatch(
                        syntaxTree = tree,
                        calls =
                            listOf(
                                CallFact(
                                    nodeId = "call",
                                    siteId = "call",
                                    callee = "findUser",
                                    receiverId = null,
                                    path = "repository.findUser",
                                ),
                            ),
                        declarations =
                            listOf(
                                dev.cint.contracts.facts.DeclarationFact(
                                    nodeId = "property",
                                    kind = dev.cint.contracts.facts.DeclarationKind.PROPERTY,
                                    name = "count",
                                    nameNodeId = null,
                                    annotations = listOf("Bookkeeping"),
                                ),
                            ),
                        properties =
                            listOf(
                                PropertyFact(
                                    nodeId = "property",
                                    name = "count",
                                    nameNodeId = null,
                                    initializerId = null,
                                ),
                            ),
                    ),
                sourceEpoch = 1,
                completeness = Completeness.COMPLETE,
                contentHash = "hash",
            )
        val pipeline =
            DefaultIntentPipeline(
                BundledRulePackSource(),
                BundledRuleEvaluators.registry(),
            )

        val result =
            pipeline
                .analyze(
                    AnalysisRequest(targetPaths = listOf("Sample.kt"), language = "kotlin"),
                    source,
                    KnowledgeSnapshot(
                        functionAnnotations = emptyList(),
                        spanOverrides = emptyList(),
                        patternHypotheses = emptyList(),
                        timestamp = 1,
                    ),
                ).getOrThrow()

        val intents = result.annotations.flatMap { it.intents }.toSet()
        assertTrue(Intents.BOOKKEEPING in intents, "expected BOOKKEEPING in $intents")
        assertTrue(
            result.candidates.any { Intents.LOOKUP in it.intents },
            "expected a LOOKUP candidate in ${result.candidates}",
        )
        assertTrue(
            result.candidates.any { Intents.SEARCH in it.intents },
            "expected a SEARCH candidate in ${result.candidates}",
        )
        assertTrue(result.annotations.isNotEmpty())
    }

    @Test
    fun bookkeepingMarksAnnotatedDeclarationsAndWritesAtV1Confidence() {
        val declaration =
            node(
                "decl",
                "property_declaration",
                10,
                48,
                "@Bookkeeping\nvar skipped = 0",
                startLine = 1,
                endLine = 2,
            )
        val write = node("write", "augmented_assignment", 49, 61, "skipped++", startLine = 3)
        val batch =
            factBatch(
                listOf(declaration, write),
                writes = listOf(WriteFact(write.id, WriteKind.INCREMENT, targetName = "skipped")),
                declarations =
                    listOf(
                        DeclarationFact(
                            declaration.id,
                            DeclarationKind.PROPERTY,
                            "skipped",
                            null,
                            annotations = listOf("Bookkeeping"),
                            writeIds = listOf(write.id),
                        ),
                    ),
            )

        val established =
            evaluator("bookkeeping:bookkeeping", Intents.BOOKKEEPING)
                .evaluate(TestView(Intents.BOOKKEEPING, batch))
                .filterIsInstance<EstablishedEmission>()
                .sortedBy { it.startByte }

        assertEquals(2, established.size)
        assertEquals(10, established[0].startByte)
        assertEquals(48, established[0].endByte)
        assertEquals("annotation", established[0].source)
        assertEquals(1.0, established[0].confidence.value)
        assertEquals(49, established[1].startByte)
        assertEquals(61, established[1].endByte)
        assertEquals("annotation", established[1].source)
        assertEquals(1.0, established[1].confidence.value)
    }

    @Test
    fun bookkeepingUsesMagicCommentSpanAndSkipsUnmarkedOrFunctionDeclarations() {
        val comment = node("comment", "line_comment", 0, 18, "// cint:bookkeeping", startLine = 0, endLine = 0)
        val declaration =
            node("decl", "property_declaration", 19, 50, "var rejected = 0", startLine = 1, endLine = 1)
        val unmarked =
            node("unmarked", "property_declaration", 51, 80, "var untouched = 0", startLine = 3, endLine = 3)
        val function =
            node(
                "function",
                "function_declaration",
                81,
                120,
                "@Bookkeeping\nfun work() = Unit",
                startLine = 4,
                endLine = 5,
            )
        val batch =
            factBatch(
                listOf(comment, declaration, unmarked, function),
                declarations =
                    listOf(
                        DeclarationFact(
                            declaration.id,
                            DeclarationKind.PROPERTY,
                            "rejected",
                            null,
                            precedingCommentIds = listOf(comment.id),
                        ),
                        DeclarationFact(unmarked.id, DeclarationKind.PROPERTY, "untouched", null),
                        DeclarationFact(
                            function.id,
                            DeclarationKind.FUNCTION,
                            "work",
                            null,
                            annotations = listOf("Bookkeeping"),
                        ),
                    ),
                comments = listOf(CommentFact(comment.id, "bookkeeping", CommentKind.LINE)),
            )

        val established =
            evaluator("bookkeeping:bookkeeping", Intents.BOOKKEEPING)
                .evaluate(TestView(Intents.BOOKKEEPING, batch))
                .filterIsInstance<EstablishedEmission>()

        assertEquals(1, established.size)
        assertEquals(0, established.single().startByte)
        assertEquals(50, established.single().endByte)
        assertEquals("comment", established.single().source)
        assertEquals(1.0, established.single().confidence.value)
    }

    @Test
    fun validationUsesTheCommentSourceForExplicitGuards() {
        val guard = guardCase("explicit", 100, "value < 0", startLine = 3)
        val comment =
            node("validation-comment", "line_comment", 60, 80, "// cint:validation", startLine = 2, endLine = 2)
        val batch =
            factBatch(
                guard.nodes + comment,
                comments = listOf(CommentFact(comment.id, "validation", CommentKind.LINE)),
                guards = listOf(guard.guard),
            )

        val established =
            evaluator("validation:validation", Intents.VALIDATION)
                .evaluate(TestView(Intents.VALIDATION, batch))
                .filterIsInstance<EstablishedEmission>()

        assertEquals(1, established.size)
        assertEquals(guard.condition.startByte, established.single().startByte)
        assertEquals(guard.condition.endByte, established.single().endByte)
        assertEquals("comment", established.single().source)
        assertEquals(1.0, established.single().confidence.value)
        assertTrue(established.single().certifiesReturn)
    }

    @Test
    fun validationGatesBookkeepingPredicatePureAndConstantConditions() {
        val bookkeeping = guardCase("bookkeeping", 200, "rejected == 0", startLine = 4)
        val predicate = guardCase("predicate", 400, "items.isEmpty()", rejection = "return -1", startLine = 8)
        val pure = guardCase("pure", 600, "value < 0", rejection = "return false", startLine = 12)
        val constant = guardCase("constant", 800, "true", startLine = 16)
        val call =
            CallFact(
                nodeId = predicate.condition.id,
                siteId = predicate.condition.id,
                callee = "isEmpty",
                receiverId = null,
                path = "items.isEmpty",
            )
        val batch =
            factBatch(
                bookkeeping.nodes + predicate.nodes + pure.nodes + constant.nodes,
                calls = listOf(call),
                guards = listOf(bookkeeping.guard, predicate.guard, pure.guard, constant.guard),
            )
        val annotations =
            mapOf(
                Intents.BOOKKEEPING to listOf(annotation(Intents.BOOKKEEPING, bookkeeping.body)),
            )

        val established =
            evaluator("validation:validation", Intents.VALIDATION)
                .evaluate(TestView(Intents.VALIDATION, batch, annotations))
                .filterIsInstance<EstablishedEmission>()
                .associateBy { it.startByte }

        assertEquals(
            setOf(bookkeeping.condition.startByte, predicate.condition.startByte, pure.condition.startByte),
            established.keys,
        )
        assertEquals(0.9, established.getValue(bookkeeping.condition.startByte).confidence.value)
        assertEquals("treesitter", established.getValue(bookkeeping.condition.startByte).source)
        assertEquals(0.7, established.getValue(predicate.condition.startByte).confidence.value)
        assertEquals(0.6, established.getValue(pure.condition.startByte).confidence.value)
    }

    @Test
    fun validationRequiresARejectionValueOnlyForReturningBodies() {
        val nonRejecting =
            guardCase("non-rejecting", 100, "items.isNotEmpty()", rejection = "return true", startLine = 3)
        val rejecting =
            guardCase("rejecting", 300, "items.isNotEmpty()", rejection = "return null", startLine = 7)
        val batch =
            factBatch(
                nonRejecting.nodes + rejecting.nodes,
                calls =
                    listOf(
                        CallFact(
                            nonRejecting.condition.id,
                            nonRejecting.condition.id,
                            "isNotEmpty",
                            null,
                            "items.isNotEmpty",
                        ),
                        CallFact(
                            rejecting.condition.id,
                            rejecting.condition.id,
                            "isNotEmpty",
                            null,
                            "items.isNotEmpty",
                        ),
                    ),
                guards = listOf(nonRejecting.guard, rejecting.guard),
            )

        val established =
            evaluator("validation:validation", Intents.VALIDATION)
                .evaluate(TestView(Intents.VALIDATION, batch))
                .filterIsInstance<EstablishedEmission>()

        assertEquals(1, established.size)
        assertEquals(rejecting.condition.startByte, established.single().startByte)
        assertEquals(0.7, established.single().confidence.value)
    }

    @Test
    fun validationEmitsACommentGapCandidateWhenTheGuardIsNotEstablished() {
        val guard = guardCase("candidate", 100, "true", startLine = 4)
        val comment =
            node("gap-comment", "line_comment", 60, 80, "// cint:validation", startLine = 1, endLine = 1)
        val batch =
            factBatch(
                guard.nodes + comment,
                comments = listOf(CommentFact(comment.id, "validation", CommentKind.LINE)),
                guards = listOf(guard.guard),
            )

        val candidates =
            evaluator("validation:validation", Intents.VALIDATION)
                .evaluate(TestView(Intents.VALIDATION, batch))
                .filterIsInstance<CandidateEmission>()

        assertEquals(1, candidates.size)
        assertEquals(guard.condition.startByte, candidates.single().startByte)
        assertEquals(guard.condition.endByte, candidates.single().endByte)
        assertEquals("rule:validation:validation", candidates.single().source)
        assertEquals(0.4, candidates.single().confidence.value)
        assertEquals(dev.cint.contracts.data.CandidateReason.GAP, candidates.single().reason)
    }

    private fun evaluator(
        id: String,
        owns: String,
    ): RuleEvaluator =
        assertNotNull(
            BundledRuleEvaluators.registry().resolve(RuleSpec(id, owns)),
            "missing evaluator $id",
        )

    private fun factBatch(
        nodes: List<SyntaxNode>,
        calls: List<CallFact> = emptyList(),
        writes: List<WriteFact> = emptyList(),
        declarations: List<DeclarationFact> = emptyList(),
        comments: List<CommentFact> = emptyList(),
        guards: List<GuardFact> = emptyList(),
    ): FactBatch =
        FactBatch(
            syntaxTree = treeOf(nodes),
            calls = calls,
            writes = writes,
            declarations = declarations,
            comments = comments,
            guards = guards,
        )

    private fun treeOf(nodes: List<SyntaxNode>): SyntaxTree {
        val end = nodes.maxOfOrNull { it.endByte } ?: 0
        val root =
            node(
                "root",
                "source_file",
                0,
                end + 1,
                "",
                childIds = nodes.filter { it.parentId == null || it.parentId == "root" }.map { it.id },
            )
        return SyntaxTree(
            rootId = root.id,
            nodes = (nodes + root).associateBy { it.id },
            language = "kotlin",
            version = "1",
            contentHash = "hash",
        )
    }

    private fun node(
        id: String,
        kind: String,
        startByte: Int,
        endByte: Int,
        text: String,
        startLine: Int = 0,
        endLine: Int = startLine,
        parentId: String? = null,
        childIds: List<String> = emptyList(),
    ): SyntaxNode =
        SyntaxNode(
            id = id,
            kind = kind,
            startByte = startByte,
            endByte = endByte,
            startLine = startLine,
            startCol = startByte,
            endLine = endLine,
            endCol = endByte,
            text = text,
            parentId = parentId,
            childIds = childIds,
        )

    private fun guardCase(
        prefix: String,
        startByte: Int,
        conditionText: String,
        rejection: String = "return 0",
        startLine: Int = 2,
    ): GuardCase {
        val conditionEnd = startByte + 4 + conditionText.length
        val bodyStart = conditionEnd + 4
        val bodyEnd = bodyStart + rejection.length + 4
        val guard =
            node(
                "$prefix-guard",
                "if_expression",
                startByte,
                bodyEnd,
                "if ($conditionText) { $rejection }",
                startLine = startLine,
                endLine = startLine,
                parentId = "root",
                childIds = listOf("$prefix-condition", "$prefix-body"),
            )
        val condition =
            node(
                "$prefix-condition",
                "binary_expression",
                startByte + 4,
                conditionEnd,
                conditionText,
                startLine = startLine,
                endLine = startLine,
                parentId = guard.id,
            )
        val body =
            node(
                "$prefix-body",
                "block",
                bodyStart,
                bodyEnd,
                "{ $rejection }",
                startLine = startLine,
                endLine = startLine,
                parentId = guard.id,
                childIds = listOf("$prefix-return"),
            )
        val returnStart = bodyStart + 2
        val returnNode =
            node(
                "$prefix-return",
                "return_expression",
                returnStart,
                returnStart + rejection.length,
                rejection,
                startLine = startLine,
                endLine = startLine,
                parentId = body.id,
                childIds = listOf("$prefix-value"),
            )
        val valueText = rejection.substringAfter("return").trim()
        val valueStart = returnStart + rejection.indexOf(valueText)
        val value =
            node(
                "$prefix-value",
                "literal",
                valueStart,
                valueStart + valueText.length,
                valueText,
                startLine = startLine,
                endLine = startLine,
                parentId = returnNode.id,
            )
        return GuardCase(
            nodes = listOf(guard, condition, body, returnNode, value),
            guard = GuardFact(guard.id, GuardKind.CONDITIONAL, condition.id, body.id, null),
            condition = condition,
            body = body,
        )
    }

    private fun annotation(
        intent: String,
        node: SyntaxNode,
    ): Annotation {
        val contribution =
            Contribution(
                rule = "test",
                role = Role.SOURCE_INTENT,
                intents = setOf(intent),
                span = node.span,
                source = "test",
                confidence = Confidence.MAX,
                startByte = node.startByte,
                endByte = node.endByte,
            )
        return Annotation(
            role = Role.SOURCE_INTENT,
            intents = setOf(intent),
            span = node.span,
            source = "test",
            confidence = Confidence.MAX,
            startByte = node.startByte,
            endByte = node.endByte,
            provenance = listOf(contribution),
        )
    }

    private data class GuardCase(
        val nodes: List<SyntaxNode>,
        val guard: GuardFact,
        val condition: SyntaxNode,
        val body: SyntaxNode,
    )

    private class TestView(
        override val owns: String,
        private val batch: FactBatch,
        private val annotationsByIntent: Map<String, List<Annotation>> = emptyMap(),
    ) : RuleEvaluationView {
        override val reads: Set<String> = annotationsByIntent.keys
        override val optionalReads: Set<String> = emptySet()
        override val calls: List<CallFact> get() = batch.calls
        override val writes: List<WriteFact> get() = batch.writes
        override val declarations: List<DeclarationFact> get() = batch.declarations
        override val comments: List<CommentFact> get() = batch.comments
        override val scopes: List<dev.cint.contracts.facts.ScopeFact> get() = batch.scopes
        override val guards: List<GuardFact> get() = batch.guards
        override val returns: List<dev.cint.contracts.facts.ReturnFact> get() = batch.returns
        override val properties: List<PropertyFact> get() = batch.properties
        override val functions: List<FunctionFact> get() = batch.functions
        override val ifs: List<dev.cint.contracts.facts.IfFact> get() = batch.ifs
        override val tries: List<dev.cint.contracts.facts.TryFact> get() = batch.tries
        override val imports: List<dev.cint.contracts.facts.ImportFact> get() = batch.imports
        override val references: List<dev.cint.contracts.facts.ReferenceFact> get() = batch.references

        private val tree = batch.syntaxTree

        override fun node(id: String?): SyntaxNode? = id?.let { tree?.getNode(it) }

        override fun children(node: SyntaxNode): List<SyntaxNode> = node.childIds.mapNotNull(::node)

        override fun descendants(node: SyntaxNode): Sequence<SyntaxNode> =
            sequence {
                yield(node)
                for (childId in node.childIds) {
                    node(childId)?.let { yieldAll(descendants(it)) }
                }
            }

        override fun enclosingFunction(node: SyntaxNode): FunctionFact? = null

        override fun enclosingDeclaration(node: SyntaxNode): DeclarationFact? = null

        override fun annotations(intent: String): List<Annotation> = annotationsByIntent[intent].orEmpty()

        override fun intentsAt(node: SyntaxNode): Set<String> = emptySet()

        override fun coverage(
            node: SyntaxNode,
            effect: Boolean,
        ): Set<String> = emptySet()
    }

    private fun tree(): SyntaxTree {
        val root =
            SyntaxNode(
                id = "root",
                kind = "source_file",
                startByte = 0,
                endByte = 200,
                startLine = 0,
                startCol = 0,
                endLine = 10,
                endCol = 0,
                text = "",
                childIds = listOf("call", "property"),
            )
        val nodes =
            linkedMapOf(
                root.id to root,
                "call" to
                    SyntaxNode(
                        id = "call",
                        kind = "call_expression",
                        startByte = 10,
                        endByte = 40,
                        startLine = 1,
                        startCol = 4,
                        endLine = 1,
                        endCol = 34,
                        text = "repository.findUser()",
                        parentId = "root",
                    ),
                "property" to
                    SyntaxNode(
                        id = "property",
                        kind = "property_declaration",
                        startByte = 50,
                        endByte = 80,
                        startLine = 2,
                        startCol = 4,
                        endLine = 2,
                        endCol = 34,
                        text = "var count = 0",
                        parentId = "root",
                    ),
            )
        return SyntaxTree(
            rootId = root.id,
            nodes = nodes,
            language = "kotlin",
            version = "1",
            contentHash = "hash",
        )
    }

    private companion object {
        val EXPECTED_RULE_IDS =
            setOf(
                "logging:logging",
                "error-handling:error-handling",
                "bookkeeping:bookkeeping",
                "validation:validation",
                "data-access:search",
                "data-access:lookup",
                "normalization:normalization",
                "data-transformation:data-transformation",
                "mutation:data-modify",
                "mutation:local-value-modify",
                "service:delegation",
                "service:init",
                "return-value:return-value",
                "local-copy:local-copy",
            )
    }
}
