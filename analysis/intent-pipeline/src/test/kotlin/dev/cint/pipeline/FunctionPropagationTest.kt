package dev.cint.pipeline

import dev.cint.ai.FallbackAiHook
import dev.cint.ai.FallbackAiPolicy
import dev.cint.contracts.data.AnalysisResult
import dev.cint.contracts.data.Completeness
import dev.cint.contracts.data.Confidence
import dev.cint.contracts.data.Intents
import dev.cint.contracts.data.Provenance
import dev.cint.contracts.data.Status
import dev.cint.contracts.facts.CallFact
import dev.cint.contracts.facts.FactBatch
import dev.cint.contracts.facts.FunctionFact
import dev.cint.contracts.facts.ImportFact
import dev.cint.contracts.facts.IndexedSource
import dev.cint.contracts.facts.SemanticFact
import dev.cint.contracts.facts.SemanticRequest
import dev.cint.contracts.facts.SemanticRequestKind
import dev.cint.contracts.facts.SemanticResult
import dev.cint.contracts.facts.SourceInput
import dev.cint.contracts.facts.SyntaxNode
import dev.cint.contracts.facts.SyntaxTree
import dev.cint.contracts.facts.TypeRef
import dev.cint.contracts.knowledge.AnnotationScope
import dev.cint.contracts.knowledge.FunctionAnnotation
import dev.cint.contracts.knowledge.FunctionSelector
import dev.cint.contracts.knowledge.KnowledgeSnapshot
import dev.cint.contracts.knowledge.SpanOverride
import dev.cint.contracts.knowledge.SpanSelector
import dev.cint.contracts.ports.AnalysisRequest
import dev.cint.contracts.ports.KnowledgeStore
import dev.cint.contracts.ports.PackManifest
import dev.cint.contracts.ports.Playbook
import dev.cint.contracts.util.CancellationToken
import dev.cint.contracts.util.Deadline
import dev.cint.v2.testing.fakes.FakeRulePackSource
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FunctionPropagationTest {
    private val now = Instant.fromEpochMilliseconds(1)
    private val resolver = PrecedenceKnowledgeInputResolver()

    @Test
    fun declarationContractPropagatesToItsCallChainWithStructuralModifiers() {
        val source = chainedSource()
        val declarationOnly = annotation(signature = "fun findUser", intent = Intents.SEARCH)

        val result = analyze(source, listOf(declarationOnly))
        val declaration = annotationsAt(result, source, "fn-0")
        val inner = annotationsAt(result, source, "call-0")
        val chain = annotationsAt(result, source, "call-1")

        assertEquals(setOf(Intents.SEARCH), declaration.flatMap { it.intents }.toSet())
        assertEquals(setOf(Intents.SEARCH), inner.flatMap { it.intents }.toSet())
        assertEquals(
            setOf(Intents.SEARCH, Intents.CHAINING, Intents.DATA_TRANSFORMATION),
            chain.flatMap { it.intents }.toSet(),
        )
        assertTrue(chain.flatMap { it.provenance }.any { it.provenance == Provenance.DERIVED })
    }

    @Test
    fun rootOverrideKeepsItsIntentAndStillReceivesChainModifiers() {
        val source = chainedSource()
        val chainNode = source.node("call-1")
        val override =
            SpanOverride(
                selector = SpanSelector("Chain.kt", chainNode.span, HASH),
                intents = setOf(Intents.LOGGING),
                provenance = Provenance.USER,
                confidence = Confidence.ESTABLISHED,
                status = Status.ACCEPTED,
                rationale = "local exception",
                createdAt = now,
                updatedAt = now,
            )

        val result =
            analyze(
                source,
                listOf(annotation(signature = "fun findUser", intent = Intents.SEARCH)),
                spanOverrides = listOf(override),
            )

        val inner = annotationsAt(result, source, "call-0").flatMap { it.intents }.toSet()
        val chain = annotationsAt(result, source, "call-1").flatMap { it.intents }.toSet()
        assertEquals(setOf(Intents.SEARCH), inner)
        assertEquals(setOf(Intents.LOGGING, Intents.CHAINING), chain)
    }

    @Test
    fun propagationOnCleanInputDoesNotCallAiWithAnEnabledPolicy() {
        var aiCalls = 0
        val hook =
            FallbackAiHook { _, _ ->
                aiCalls += 1
                throw AssertionError("AI must not run during deterministic propagation")
            }
        val dirtyInputPolicy =
            FallbackAiPolicy { request ->
                request.source.completeness != Completeness.COMPLETE
            }
        val clean = chainedSource().copy(completeness = Completeness.COMPLETE)

        val result =
            analyze(
                clean,
                listOf(annotation(signature = "fun findUser", intent = Intents.SEARCH)),
                fallbackAi = hook,
                fallbackAiPolicy = dirtyInputPolicy,
            )

        assertEquals(0, aiCalls)
        val chain = annotationsAt(result, clean, "call-1").flatMap { it.intents }.toSet()
        assertTrue(Intents.SEARCH in chain && Intents.CHAINING in chain)
    }

    @Test
    fun dirtyInputStillInvokesFallbackOnlyWhenPolicyAllowsIt() {
        val dirty = chainedSource().copy(completeness = Completeness.STALE)
        val allowedStore =
            dev.cint.knowledge.InMemoryKnowledgeStore
                .existing { now }
        var allowedCalls = 0
        val allowed =
            analyze(
                dirty,
                listOf(annotation(signature = "fun findUser", intent = Intents.SEARCH)),
                fallbackAi =
                    FallbackAiHook { _, _ ->
                        allowedCalls += 1
                        dev.cint.contracts.util.ResultFactory
                            .success(noopChange())
                    },
                fallbackAiPolicy = FallbackAiPolicy.enabled(),
                knowledgeStore = allowedStore,
                materialized = true,
            )
        assertEquals(1, allowedCalls)

        val deniedStore =
            dev.cint.knowledge.InMemoryKnowledgeStore
                .existing { now }
        var deniedCalls = 0
        analyze(
            dirty,
            listOf(annotation(signature = "fun findUser", intent = Intents.SEARCH)),
            fallbackAi =
                FallbackAiHook { _, _ ->
                    deniedCalls += 1
                    dev.cint.contracts.util.ResultFactory
                        .success(noopChange())
                },
            fallbackAiPolicy = FallbackAiPolicy.DISABLED,
            knowledgeStore = deniedStore,
            materialized = true,
        )
        assertEquals(0, deniedCalls)
    }

    @Test
    fun realNavigationCalleeUsesTheFinalMemberName() {
        val source = navigatedSource()
        val result =
            resolve(
                source,
                listOf(annotation(qualifiedName = "map", intent = Intents.DATA_TRANSFORMATION)),
            )

        val outer = contributionsAt(result, source, "call-1")
        assertEquals(setOf(Intents.DATA_TRANSFORMATION), outer.flatMap { it.intents }.toSet())
    }

    @Test
    fun declarationPropagationHonorsDeclaringTypeAndArity() {
        val source = overloadedSource()
        val declaring =
            resolve(
                source,
                listOf(
                    annotation(
                        qualifiedName = "find",
                        declaringType = "RepoB",
                        intent = Intents.SEARCH,
                    ),
                ),
            )
        assertEquals(
            setOf(Intents.SEARCH),
            contributionsAt(declaring, source, "call-b").flatMap { it.intents }.toSet(),
        )
        assertTrue(contributionsAt(declaring, source, "call-a").isEmpty())

        val arity =
            resolve(
                source,
                listOf(
                    annotation(
                        qualifiedName = "",
                        signature = "fun find(value: Int)",
                        intent = Intents.SEARCH,
                    ),
                ),
            )
        assertEquals(
            setOf(Intents.SEARCH),
            contributionsAt(arity, source, "call-a").flatMap { it.intents }.toSet(),
        )
        assertTrue(contributionsAt(arity, source, "call-b").isEmpty())
        assertTrue(contributionsAt(arity, source, "call-c2").isEmpty())
    }

    @Test
    fun declaringTypeMatchRequiresResolvedReceiverType() {
        val source = importedCallSource()
        val result =
            resolve(
                source,
                listOf(
                    annotation(
                        qualifiedName = "println",
                        declaringType = "Logger",
                        intent = Intents.LOGGING,
                    ),
                ),
            )

        assertTrue(result.contributions.isEmpty())
        assertEquals(listOf("KNOWLEDGE_SELECTOR_UNSUPPORTED"), result.errors.map { it.code })
    }

    @Test
    fun nestedArgumentCallsAreNotLinkedAsChains() {
        val source = unrelatedNestedSource()
        val result =
            analyze(
                source,
                listOf(annotation(signature = "fun findUser", intent = Intents.SEARCH)),
            )

        assertTrue(annotationsAt(result, source, "call-outer").isEmpty())
        assertTrue(result.annotations.none { Intents.CHAINING in it.intents })
    }

    @Test
    fun fileScopeAppliesOnlyToItsPathAndRejectsMissingPaths() {
        val source = chainedSource()
        val annotation = annotation("findUser", Intents.SEARCH, scope = AnnotationScope.FILE).copy(path = "Other.kt")
        assertTrue(analyze(source, listOf(annotation)).annotations.isEmpty())

        val matching =
            annotation("findUser", Intents.SEARCH, scope = AnnotationScope.FILE).copy(path = "Chain.kt")
        assertTrue(analyze(source, listOf(matching)).annotations.any { Intents.SEARCH in it.intents })

        val missing = resolve(source, listOf(annotation("findUser", Intents.SEARCH, scope = AnnotationScope.FILE)))
        assertTrue(missing.contributions.isEmpty())
        assertEquals(listOf("KNOWLEDGE_SCOPE_UNSUPPORTED"), missing.errors.map { it.code })
    }

    @Test
    fun exactSelectorBeatsBroadStructuralSelectorAtEqualStatus() {
        val source = simpleSource("findUser")
        val broad = annotation(structuralPattern = "token-prefix:find", intent = Intents.SEARCH)
        val exact = annotation("findUser", Intents.LOOKUP)

        val result = resolve(source, listOf(broad, exact))

        assertEquals(setOf(Intents.LOOKUP), result.contributions.single().intents)
    }

    @Test
    fun declaringTypeSignatureAndImportSelectorsResolve() {
        val declaring =
            resolve(
                simpleSource("Logger.info", declaringType = "Logger"),
                listOf(annotation(qualifiedName = "info", declaringType = "Logger", intent = Intents.LOGGING)),
            )
        assertEquals(setOf(Intents.LOGGING), declaring.contributions.single().intents)

        val signature =
            resolve(
                simpleSource("findUser"),
                listOf(annotation(signature = "fun findUser", intent = Intents.SEARCH)),
            )
        assertEquals(setOf(Intents.SEARCH), signature.contributions.single().intents)

        val imported = importedCallSource()
        val importedResult =
            resolve(
                imported,
                listOf(
                    annotation(
                        qualifiedName = "println",
                        importPath = "kotlin.io.println",
                        intent = Intents.LOGGING,
                    ),
                ),
            )
        assertEquals(setOf(Intents.LOGGING), importedResult.contributions.single().intents)
        assertTrue(importedResult.errors.isEmpty())
    }

    @Test
    fun unmatchedImportSelectorsAreNotReportedAsUnsupported() {
        val result =
            resolve(
                simpleSource("findUser"),
                listOf(
                    annotation(
                        qualifiedName = "findUser",
                        importPath = "kotlin.io.println",
                        intent = Intents.LOGGING,
                    ),
                ),
            )

        assertTrue(result.contributions.isEmpty())
        assertTrue(result.errors.isEmpty())
    }

    @Test
    fun genuinelyUnsupportedSelectorsAreReportedOnce() {
        val declaring =
            resolve(
                simpleSource("findUser"),
                listOf(annotation(qualifiedName = "findUser", declaringType = "MissingType", intent = Intents.LOOKUP)),
            )
        assertEquals(listOf("KNOWLEDGE_SELECTOR_UNSUPPORTED"), declaring.errors.map { it.code })

        val structural =
            resolve(
                simpleSource("findUser"),
                listOf(annotation(structuralPattern = "call-chain-state", intent = Intents.CHAINING)),
            )
        assertEquals(listOf("KNOWLEDGE_SELECTOR_UNSUPPORTED"), structural.errors.map { it.code })
    }

    @Test
    fun resolutionIsDeterministicWhenInputsAreReordered() {
        val source = chainedSource()
        val reversed = source.copy(facts = source.facts.copy(calls = source.facts.calls.reversed()))
        val annotations =
            listOf(
                annotation("findUser", Intents.LOOKUP),
                annotation(structuralPattern = "token-prefix:find", intent = Intents.SEARCH),
            )

        assertEquals(
            resolve(source, annotations),
            resolve(reversed, annotations.reversed()),
        )
    }

    private fun analyze(
        source: IndexedSource,
        annotations: List<FunctionAnnotation>,
        spanOverrides: List<SpanOverride> = emptyList(),
        fallbackAi: FallbackAiHook? = null,
        fallbackAiPolicy: FallbackAiPolicy = FallbackAiPolicy.DISABLED,
        knowledgeStore: KnowledgeStore? = null,
        materialized: Boolean = false,
    ): AnalysisResult {
        val pipeline =
            DefaultIntentPipeline(
                FakeRulePackSource(
                    packManifests = listOf(PackManifest("demo", "Demo", "1", listOf("fake"))),
                    playbooks = mapOf("demo" to Playbook("demo", emptyList())),
                ),
                fallbackAi = fallbackAi,
                fallbackAiPolicy = fallbackAiPolicy,
                knowledgeStore = knowledgeStore,
            )
        return pipeline
            .analyze(
                AnalysisRequest(language = "fake"),
                source,
                snapshot(annotations, spanOverrides, materialized),
            ).getOrThrow()
    }

    private fun resolve(
        source: IndexedSource,
        annotations: List<FunctionAnnotation>,
        spanOverrides: List<SpanOverride> = emptyList(),
    ): KnowledgeInputResolution =
        resolver
            .resolve(
                source,
                snapshot(annotations, spanOverrides),
                emptyList(),
                CancellationToken.NONE,
            ).getOrThrow()

    private fun annotationsAt(
        result: AnalysisResult,
        source: IndexedSource,
        nodeId: String,
    ) = result.annotations.filter {
        val node = source.node(nodeId)
        it.startByte == node.startByte && it.endByte == node.endByte
    }

    private fun contributionsAt(
        resolution: KnowledgeInputResolution,
        source: IndexedSource,
        nodeId: String,
    ): List<dev.cint.contracts.data.Contribution> {
        val node = source.node(nodeId)
        return resolution.contributions.filter {
            it.startByte == node.startByte && it.endByte == node.endByte
        }
    }

    private fun snapshot(
        annotations: List<FunctionAnnotation>,
        spanOverrides: List<SpanOverride> = emptyList(),
        materialized: Boolean = false,
    ) = KnowledgeSnapshot(
        functionAnnotations = annotations,
        spanOverrides = spanOverrides,
        patternHypotheses = emptyList(),
        materialized = materialized,
        timestamp = 1,
    )

    private fun annotation(
        qualifiedName: String = "",
        intent: String,
        importPath: String? = null,
        declaringType: String? = null,
        signature: String? = null,
        structuralPattern: String? = null,
        scope: AnnotationScope = AnnotationScope.GLOBAL,
    ): FunctionAnnotation =
        FunctionAnnotation(
            selector =
                FunctionSelector(
                    qualifiedName = qualifiedName,
                    importPath = importPath,
                    declaringType = declaringType,
                    signature = signature,
                    structuralPattern = structuralPattern,
                ),
            intents = setOf(intent),
            scope = scope,
            provenance = Provenance.USER,
            confidence = Confidence.HIGH,
            status = Status.ACCEPTED,
            createdAt = now,
            updatedAt = now,
        )

    private fun simpleSource(
        name: String,
        declaringType: String? = null,
    ): IndexedSource {
        val root = node("root", "file", 0, 100, 0, 0, 9, 0, "")
        val function = node("fn-0", "function_declaration", 0, 20, 0, 0, 0, 20, "fun $name", root)
        return indexed(
            root = root,
            nodes = listOf(function),
            functions = listOf(FunctionFact("fn-0", name, null, null, declaringType = declaringType)),
            calls = emptyList(),
        )
    }

    private fun chainedSource(): IndexedSource {
        val root = node("root", "file", 0, 200, 0, 0, 9, 0, "")
        val function = node("fn-0", "function_declaration", 0, 20, 0, 0, 0, 20, "fun findUser", root)
        val inner = node("call-0", "call_expression", 30, 40, 2, 4, 2, 14, "findUser()", root)
        val outer = node("call-1", "call_expression", 60, 80, 4, 4, 4, 24, "findUser().map { }", root)
        return indexed(
            root = root,
            nodes = listOf(function, inner, outer),
            functions = listOf(FunctionFact("fn-0", "findUser", null, null)),
            calls =
                listOf(
                    CallFact("call-0", "call-0", "findUser", null, null),
                    CallFact("call-1", "call-1", "findUser().map", "call-0", null, arity = 1),
                ),
        )
    }

    private fun navigatedSource(): IndexedSource {
        val root = node("root", "file", 0, 200, 0, 0, 9, 0, "")
        val inner = node("call-0", "call_expression", 10, 35, 1, 4, 1, 24, "repository.findAll()", root)
        val outer = node("call-1", "call_expression", 40, 80, 2, 4, 2, 30, "repository.findAll().map { it }", root)
        return indexed(
            root = root,
            nodes = listOf(inner, outer),
            functions = emptyList(),
            calls =
                listOf(
                    CallFact("call-0", "call-0", "repository.findAll", null, null),
                    CallFact("call-1", "call-1", "repository.findAll().map", "call-0", null, arity = 1),
                ),
        )
    }

    private fun overloadedSource(): IndexedSource {
        val root = node("root", "file", 0, 300, 0, 0, 9, 0, "")
        val fnA = node("fn-a", "method_declaration", 0, 20, 0, 0, 0, 20, "fun find(value: Int)", root)
        val fnB = node("fn-b", "method_declaration", 0, 20, 0, 0, 0, 20, "fun find(value: String)", root)
        val recvA = node("recv-a", "identifier", 50, 55, 2, 0, 2, 5, "repoA", root)
        val recvB = node("recv-b", "identifier", 90, 95, 3, 0, 3, 5, "repoB", root)
        val callA = node("call-a", "call_expression", 50, 62, 2, 0, 2, 12, "repoA.find(1)", root)
        val callB = node("call-b", "call_expression", 90, 102, 3, 0, 3, 12, "repoB.find(\"x\")", root)
        val callC2 = node("call-c2", "call_expression", 120, 140, 4, 0, 4, 20, "repoB.find(1, 2)", root)
        return indexed(
            root = root,
            nodes = listOf(fnA, fnB, recvA, recvB, callA, callB, callC2),
            semanticFacts =
                listOf(
                    typeAt(recvA, "RepoA"),
                    typeAt(recvB, "RepoB"),
                ),
            functions =
                listOf(
                    FunctionFact("fn-a", "find", null, null, declaringType = "RepoA", signature = "fun find(value: Int)", arity = 1),
                    FunctionFact("fn-b", "find", null, null, declaringType = "RepoB", signature = "fun find(value: String)", arity = 1),
                ),
            calls =
                listOf(
                    CallFact("call-a", "call-a", "find", "recv-a", "repoA.find", arity = 1),
                    CallFact("call-b", "call-b", "find", "recv-b", "repoB.find", arity = 1),
                    CallFact("call-c2", "call-c2", "find", "recv-b", "repoB.find", arity = 2),
                ),
        )
    }

    private fun unrelatedNestedSource(): IndexedSource {
        val root = node("root", "file", 0, 200, 0, 0, 9, 0, "")
        val function = node("fn-0", "function_declaration", 0, 20, 0, 0, 0, 20, "fun findUser", root)
        val arg = node("arg", "argument", 40, 55, 2, 0, 2, 15, "findUser()", root)
        val inner = node("call-inner", "call_expression", 40, 55, 2, 0, 2, 15, "findUser()", arg)
        val outer = node("call-outer", "call_expression", 35, 70, 2, 0, 2, 30, "consume(findUser())", root)
        return indexed(
            root = root,
            nodes = listOf(function, arg, inner, outer),
            functions = listOf(FunctionFact("fn-0", "findUser", null, null)),
            calls =
                listOf(
                    CallFact("call-inner", "call-inner", "findUser", null, null),
                    CallFact("call-outer", "call-outer", "consume", "arg", null),
                ),
        )
    }

    private fun importedCallSource(): IndexedSource {
        val root = node("root", "file", 0, 100, 0, 0, 9, 0, "")
        val call = node("call-0", "call_expression", 20, 30, 2, 4, 2, 14, "println()", root)
        return indexed(
            root = root,
            nodes = listOf(call),
            functions = emptyList(),
            calls = listOf(CallFact("call-0", "call-0", "println", null, null)),
            imports = listOf(ImportFact("import-0", "kotlin.io.println")),
        )
    }

    private fun indexed(
        root: SyntaxNode,
        nodes: List<SyntaxNode>,
        functions: List<FunctionFact>,
        calls: List<CallFact>,
        imports: List<ImportFact> = emptyList(),
        semanticFacts: List<SemanticFact> = emptyList(),
    ): IndexedSource {
        val byId = linkedMapOf<String, SyntaxNode>()
        byId[root.id] = root.copy(childIds = nodes.filter { it.parentId == root.id }.map { it.id })
        nodes.forEach { byId[it.id] = it }
        val tree =
            SyntaxTree(
                rootId = root.id,
                nodes = byId,
                language = "fake",
                version = "1",
                contentHash = HASH,
            )
        return IndexedSource(
            input = SourceInput.InMemory("", "fake", 1, "Chain.kt"),
            facts = FactBatch(syntaxTree = tree, functions = functions, calls = calls, imports = imports),
            sourceEpoch = 1,
            completeness = Completeness.COMPLETE,
            contentHash = HASH,
            semanticFacts = semanticFacts,
        )
    }

    private fun noopChange(): dev.cint.contracts.knowledge.KnowledgeChange =
        dev.cint.contracts.knowledge.KnowledgeChange
            .Batch("noop", emptyList())

    private fun typeAt(
        node: SyntaxNode,
        type: String,
    ): SemanticFact =
        SemanticFact(
            request =
                SemanticRequest(
                    uri = "file://Chain.kt",
                    version = 1,
                    offset = node.startByte,
                    kind = SemanticRequestKind.TYPE_AT,
                    deadline = Deadline.NONE,
                ),
            result =
                SemanticResult.TypeAt(
                    typeRef = TypeRef(type, "test", Confidence.HIGH),
                ),
            providerEpoch = 1,
        )

    private fun node(
        id: String,
        kind: String,
        startByte: Int,
        endByte: Int,
        startLine: Int,
        startCol: Int,
        endLine: Int,
        endCol: Int,
        text: String,
        parent: SyntaxNode? = null,
    ): SyntaxNode =
        SyntaxNode(
            id = id,
            kind = kind,
            startByte = startByte,
            endByte = endByte,
            startLine = startLine,
            startCol = startCol,
            endLine = endLine,
            endCol = endCol,
            text = text,
            parentId = parent?.id,
        )
}
