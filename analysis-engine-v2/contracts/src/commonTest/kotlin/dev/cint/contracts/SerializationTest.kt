package dev.cint.contracts

import dev.cint.contracts.data.*
import dev.cint.contracts.facts.*
import dev.cint.contracts.knowledge.*
import dev.cint.contracts.ipc.*
import dev.cint.contracts.ports.*
import dev.cint.contracts.util.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SerializationTest {
    private val json = Json { 
        prettyPrint = true; 
        ignoreUnknownKeys = false 
        classDiscriminator = "type"
    }

    @Test
    fun testIntentSerialization() {
        val intent = Intents.LOGGING
        val serialized = json.encodeToString(intent)
        val deserialized = json.decodeFromString<Intent>(serialized)
        assertEquals(intent, deserialized)
    }

    @Test
    fun testRoleSerialization() {
        val role = Role.SOURCE_INTENT
        val serialized = json.encodeToString(role)
        val deserialized = json.decodeFromString<Role>(serialized)
        assertEquals(role, deserialized)
    }

    @Test
    fun testSpanSerialization() {
        val span = Span(10, 5, 12, 20)
        val serialized = json.encodeToString(span)
        val deserialized = json.decodeFromString<Span>(serialized)
        assertEquals(span, deserialized)
    }

    @Test
    fun testNodeAnchorSerialization() {
        val anchor = NodeAnchor("node-123")
        val serialized = json.encodeToString(anchor)
        val deserialized = json.decodeFromString<NodeAnchor>(serialized)
        assertEquals(anchor, deserialized)
    }

    @Test
    fun testProvenanceSerialization() {
        val provenance = Provenance.USER
        val serialized = json.encodeToString(provenance)
        val deserialized = json.decodeFromString<Provenance>(serialized)
        assertEquals(provenance, deserialized)
    }

    @Test
    fun testConfidenceSerialization() {
        val confidence = Confidence.HIGH
        val serialized = json.encodeToString(confidence)
        val deserialized = json.decodeFromString<Confidence>(serialized)
        assertEquals(confidence, deserialized)
    }

    @Test
    fun testConfidenceValidation() {
        assertEquals(Confidence(0.5).value, 0.5)
        assertEquals(Confidence.fromDouble(1.5).value, 1.0)
        assertEquals(Confidence.fromDouble(-0.5).value, 0.0)
    }

    @Test
    fun testStatusSerialization() {
        val status = Status.ACCEPTED
        val serialized = json.encodeToString(status)
        val deserialized = json.decodeFromString<Status>(serialized)
        assertEquals(status, deserialized)
    }

    @Test
    fun testContributionSerialization() {
        val contribution = Contribution(
            rule = "test-rule",
            role = Role.SOURCE_INTENT,
            intents = setOf(Intents.LOGGING),
            span = Span(1, 0, 1, 10),
            source = "treesitter",
            confidence = Confidence.HIGH,
            startByte = 10,
            endByte = 20,
            provenance = Provenance.RULE,
        )
        val serialized = json.encodeToString(contribution)
        val deserialized = json.decodeFromString<Contribution>(serialized)
        assertEquals(contribution, deserialized)
    }

    @Test
    fun testAnnotationSerialization() {
        val contribution = Contribution(
            rule = "test-rule",
            role = Role.SOURCE_INTENT,
            intents = setOf(Intents.LOGGING),
            span = Span(1, 0, 1, 10),
            source = "treesitter",
            confidence = Confidence.HIGH,
            startByte = 10,
            endByte = 20,
        )
        val annotation = Annotation(
            role = Role.SOURCE_INTENT,
            intents = setOf(Intents.LOGGING),
            span = Span(1, 0, 1, 10),
            source = "treesitter",
            confidence = Confidence.HIGH,
            startByte = 10,
            endByte = 20,
            provenance = listOf<Contribution>(contribution),
        )
        val serialized = json.encodeToString(annotation)
        val deserialized = json.decodeFromString<dev.cint.contracts.data.Annotation>(serialized)
        assertEquals(annotation, deserialized)
    }

    @Test
    fun testCandidateSerialization() {
        val candidate = Candidate(
            intents = setOf(Intents.VALIDATION),
            span = Span(5, 0, 5, 20),
            startByte = 50,
            endByte = 70,
            source = "candidate",
            confidence = Confidence.MEDIUM,
            reason = CandidateReason.GAP,
        )
        val serialized = json.encodeToString(candidate)
        val deserialized = json.decodeFromString<Candidate>(serialized)
        assertEquals(candidate, deserialized)
    }

    @Test
    fun testFunctionSelectorSerialization() {
        val selector = FunctionSelector(
            qualifiedName = "com.example.MyClass.myMethod",
            importPath = "com.example.MyClass",
            receiverType = "MyClass",
            declaringType = "MyClass",
            signature = "(String) -> Int",
        )
        val serialized = json.encodeToString(selector)
        val deserialized = json.decodeFromString<FunctionSelector>(serialized)
        assertEquals(selector, deserialized)
    }

    @Test
    fun testSpanSelectorSerialization() {
        val selector = SpanSelector(
            path = "src/main/kotlin/MyClass.kt",
            span = Span(10, 5, 10, 25),
            contentHash = "abc123",
        )
        val serialized = json.encodeToString(selector)
        val deserialized = json.decodeFromString<SpanSelector>(serialized)
        assertEquals(selector, deserialized)
    }

    @Test
    fun testFunctionAnnotationSerialization() {
        val annotation = FunctionAnnotation.create(
            selector = FunctionSelector(qualifiedName = "com.example.Service.findUser"),
            intents = setOf(Intents.LOOKUP),
            provenance = Provenance.USER,
            confidence = Confidence.HIGH,
            status = Status.ACCEPTED,
        )
        val serialized = json.encodeToString(annotation)
        val deserialized = json.decodeFromString<FunctionAnnotation>(serialized)
        assertEquals(annotation, deserialized)
    }

    @Test
    fun testSpanOverrideSerialization() {
        val override = SpanOverride.create(
            selector = SpanSelector(
                path = "src/main/kotlin/Service.kt",
                span = Span(10, 0, 10, 30),
                contentHash = "hash123",
            ),
            intents = setOf(Intents.VALIDATION),
            rationale = "Explicit validation guard",
        )
        val serialized = json.encodeToString(override)
        val deserialized = json.decodeFromString<SpanOverride>(serialized)
        assertEquals(override, deserialized)
    }

    @Test
    fun testPackDefaultSerialization() {
        val default = PackDefault(
            selector = FunctionSelector(qualifiedName = "org.slf4j.Logger.info"),
            intents = setOf(Intents.LOGGING),
            packId = "logging",
            packVersion = "1.0.0",
        )
        val serialized = json.encodeToString(default)
        val deserialized = json.decodeFromString<PackDefault>(serialized)
        assertEquals(default, deserialized)
    }

    @Test
    fun testPatternHypothesisSerialization() {
        val hypothesis = PatternHypothesis.create(
            id = "hypothesis-1",
            scope = AnnotationScope.GLOBAL,
            evidence = listOf("pattern observed in 10 files"),
            examples = listOf("logger.info(...)"),
            counterexamples = listOf("logger.debug(...)"),
            proposedIntents = setOf(Intents.LOGGING),
        )
        val serialized = json.encodeToString(hypothesis)
        val deserialized = json.decodeFromString<PatternHypothesis>(serialized)
        assertEquals(hypothesis, deserialized)
    }

    @Test
    fun testKnowledgeSnapshotSerialization() {
        val snapshot = KnowledgeSnapshot(
            functionAnnotations = listOf(
                FunctionAnnotation.create(
                    selector = FunctionSelector(qualifiedName = "com.example.A"),
                    intents = setOf(Intents.LOOKUP),
                )
            ),
            spanOverrides = listOf(
                SpanOverride.create(
                    selector = SpanSelector(path = "A.kt", span = Span(1,0,1,10), contentHash = "h1"),
                    intents = setOf(Intents.VALIDATION),
                )
            ),
            packDefaults = listOf(
                PackDefault(
                    selector = FunctionSelector(qualifiedName = "com.example.B"),
                    intents = setOf(Intents.LOGGING),
                    packId = "pack1",
                    packVersion = "1.0",
                )
            ),
            patternHypotheses = listOf(
                PatternHypothesis.create(
                    id = "h1",
                    scope = AnnotationScope.GLOBAL,
                    evidence = listOf("e1"),
                    examples = listOf("ex1"),
                    counterexamples = listOf(),
                    proposedIntents = setOf(Intents.SEARCH),
                )
            ),
        )
        val serialized = json.encodeToString(snapshot)
        val deserialized = json.decodeFromString<KnowledgeSnapshot>(serialized)
        assertEquals(snapshot, deserialized)
    }

    @Test
    fun testSyntaxNodeSerialization() {
        val node = SyntaxNode(
            id = "node-1",
            kind = "function_declaration",
            startByte = 0,
            endByte = 50,
            startLine = 1,
            startCol = 0,
            endLine = 3,
            endCol = 1,
            text = "fun foo() {}",
        )
        val serialized = json.encodeToString(node)
        val deserialized = json.decodeFromString<SyntaxNode>(serialized)
        assertEquals(node, deserialized)
    }

    @Test
    fun testSyntaxTreeSerialization() {
        val node = SyntaxNode(
            id = "root",
            kind = "file",
            startByte = 0,
            endByte = 100,
            startLine = 1,
            startCol = 0,
            endLine = 10,
            endCol = 0,
            text = "fun foo() {}\nfun bar() {}",
        )
        val tree = SyntaxTree(
            rootId = "root",
            nodes = mapOf("root" to node),
            language = "kotlin",
            version = "1.0",
            contentHash = "abc123",
        )
        val serialized = json.encodeToString(tree)
        val deserialized = json.decodeFromString<SyntaxTree>(serialized)
        assertEquals(tree, deserialized)
    }

    @Test
    fun testFactBatchSerialization() {
        val batch = FactBatch(
            syntaxTree = null,
            calls = listOf(
                CallFact(
                    nodeId = "call-1",
                    siteId = "site-1",
                    callee = "foo",
                    receiverId = null,
                    path = "foo",
                )
            ),
            declarations = listOf(
                DeclarationFact(
                    nodeId = "decl-1",
                    kind = DeclarationKind.FUNCTION,
                    name = "foo",
                    nameNodeId = "name-1",
                )
            ),
        )
        val serialized = json.encodeToString(batch)
        val deserialized = json.decodeFromString<FactBatch>(serialized)
        assertEquals(batch, deserialized)
    }

    @Test
    fun testSourceInputSerialization() {
        val fileInput = SourceInput.FilePath("/path/to/file.kt", "kotlin", 1)
        val serialized = json.encodeToString(fileInput)
        val deserialized = json.decodeFromString<SourceInput>(serialized)
        assertEquals(fileInput, deserialized)

        val memoryInput = SourceInput.InMemory("fun foo() {}", "kotlin", 1, "test.kt")
        val serialized2 = json.encodeToString(memoryInput)
        val deserialized2 = json.decodeFromString<SourceInput>(serialized2)
        assertEquals(memoryInput, deserialized2)
    }

    @Test
    fun testIndexedSourceSerialization() {
        val indexed = IndexedSource(
            input = SourceInput.FilePath("test.kt", "kotlin"),
            facts = FactBatch(syntaxTree = null),
            sourceEpoch = 12345,
            completeness = Completeness.COMPLETE,
            contentHash = "hash123",
        )
        val serialized = json.encodeToString(indexed)
        val deserialized = json.decodeFromString<IndexedSource>(serialized)
        assertEquals(indexed, deserialized)
    }

    @Test
    fun testLanguageDescriptorSerialization() {
        val descriptor = LanguageDescriptor(
            id = "kotlin",
            name = "Kotlin",
            extensions = listOf("kt", "kts"),
            grammarName = "kotlin",
            mimeTypes = listOf("text/x-kotlin"),
        )
        val serialized = json.encodeToString(descriptor)
        val deserialized = json.decodeFromString<LanguageDescriptor>(serialized)
        assertEquals(descriptor, deserialized)
    }

    @Test
    fun testSemanticRequestSerialization() {
        val request = SemanticRequest(
            uri = "file:///test.kt",
            version = 1,
            offset = 100,
            kind = SemanticRequestKind.TYPE_AT,
            deadline = Deadline.fromMillis(currentTimeMillis() + 5000),
        )
        val serialized = json.encodeToString(request)
        val deserialized = json.decodeFromString<SemanticRequest>(serialized)
        assertEquals(request, deserialized)
    }

    @Test
    fun testSemanticResultSerialization() {
        val result: SemanticResult = SemanticResult.TypeAt(
            typeRef = TypeRef("String", "lsp", Confidence.HIGH),
            unavailable = null,
        )
        val serialized = json.encodeToString<SemanticResult>(result)
        val deserialized = json.decodeFromString<SemanticResult>(serialized)
        assertEquals(result, deserialized)
    }

    @Test
    fun testUnavailableSerialization() {
        val unavailable = Unavailable.providerUnavailable("LSP not running")
        val serialized = json.encodeToString(unavailable)
        val deserialized = json.decodeFromString<Unavailable>(serialized)
        assertEquals(unavailable, deserialized)
    }

    @Test
    fun testDeadlineSerialization() {
        val deadline = Deadline.fromMillis(currentTimeMillis() + 5000)
        val serialized = json.encodeToString(deadline)
        val deserialized = json.decodeFromString<Deadline>(serialized)
        assertEquals(deadline, deserialized)
    }

    @Test
    fun testCancellationTokenSerialization() {
        val token = CancellationToken.NONE
        val serialized = json.encodeToString(token)
        val deserialized = json.decodeFromString<CancellationToken>(serialized)
        assertEquals(token.isCancelled, deserialized.isCancelled)

        val cancelled = CancelledToken
        val serialized2 = json.encodeToString(cancelled)
        val deserialized2 = json.decodeFromString<CancellationToken>(serialized2)
        assertEquals(cancelled.isCancelled, deserialized2.isCancelled)
    }

    @Test
    fun testContractErrorSerialization() {
        val error = ContractError.SerializationError("Failed to serialize")
        val serialized = json.encodeToString(error)
        val deserialized = json.decodeFromString<ContractError>(serialized)
        assertEquals(error, deserialized)
    }

    @Test
    fun testResultSerialization() {
        val success: Result<String> = ResultFactory.success("value")
        val serialized = json.encodeToString(success)
        val deserialized = json.decodeFromString<Result<String>>(serialized)
        assertEquals(success, deserialized)

        val failure: Result<String> = ResultFactory.failure(ContractError.ValidationError("Invalid")) as Result<String>
        val serialized2 = json.encodeToString(failure)
        val deserialized2 = json.decodeFromString<Result<String>>(serialized2)
        assertEquals(failure, deserialized2)
    }

    @Test
    fun testContentHashSerialization() {
        val hash = ContentHash.compute("test data")
        val serialized = json.encodeToString(hash)
        val deserialized = json.decodeFromString<ContentHash>(serialized)
        assertEquals(hash, deserialized)
    }

    @Test
    fun testNodeHashSerialization() {
        val hash = NodeHash.compute("test node")
        val serialized = json.encodeToString(hash)
        val deserialized = json.decodeFromString<NodeHash>(serialized)
        assertEquals(hash, deserialized)
    }

    @Test
    fun testSelectorIdentitySerialization() {
        val identity = SelectorIdentity(
            kind = SelectorKind.FUNCTION,
            key = "fn:com.example.Foo",
            contentHash = ContentHash.compute("content"),
        )
        val serialized = json.encodeToString(identity)
        val deserialized = json.decodeFromString<SelectorIdentity>(serialized)
        assertEquals(identity, deserialized)
    }

    @Test
    fun testPackManifestSerialization() {
        val manifest = PackManifest(
            id = "logging",
            name = "Logging Pack",
            version = "1.0.0",
            languages = listOf("kotlin", "java"),
            providesIntents = listOf(Intents.LOGGING),
        )
        val serialized = json.encodeToString(manifest)
        val deserialized = json.decodeFromString<PackManifest>(serialized)
        assertEquals(manifest, deserialized)
    }

    @Test
    fun testPlaybookSerialization() {
        val playbook = Playbook(
            packId = "logging",
            rules = listOf(
                RuleSpec(
                    id = "logging:logger-call",
                    owns = Intents.LOGGING,
                    reads = emptyList(),
                )
            ),
        )
        val serialized = json.encodeToString(playbook)
        val deserialized = json.decodeFromString<Playbook>(serialized)
        assertEquals(playbook, deserialized)
    }

    @Test
    fun testAnalysisRequestSerialization() {
        val request = AnalysisRequest(
            targetPaths = listOf("src/main"),
            language = "kotlin",
            options = mapOf("strict" to "true"),
        )
        val serialized = json.encodeToString(request)
        val deserialized = json.decodeFromString<AnalysisRequest>(serialized)
        assertEquals(request, deserialized)
    }

    @Test
    fun testAnalysisResultSerialization() {
        val result = AnalysisResult(
            annotations = emptyList(),
            candidates = emptyList(),
            facts = FactBatch(syntaxTree = null),
            sourceEpoch = 123,
            completeness = Completeness.COMPLETE,
            errors = emptyList(),
        )
        val serialized = json.encodeToString(result)
        val deserialized = json.decodeFromString<AnalysisResult>(serialized)
        assertEquals(result, deserialized)
    }

    @Test
    fun testIpcMessagesSerialization() {
        val handshake = IpcMessage.Handshake(
            fingerprint = "fp123",
            clientInfo = ClientInfo("test-client", "1.0"),
        )
        val serialized = json.encodeToString(handshake)
        val deserialized = json.decodeFromString<IpcMessage>(serialized)
        assertEquals(handshake, deserialized)
    }

    @Test
    fun testMcpMessagesSerialization() {
        val request = McpRequest.KnowledgeSnapshot(requestId = "req-1")
        val serialized = json.encodeToString(request)
        val deserialized = json.decodeFromString<McpRequest>(serialized)
        assertEquals(request, deserialized)

        val ks = dev.cint.contracts.knowledge.KnowledgeSnapshot(
            functionAnnotations = emptyList(),
            spanOverrides = emptyList(),
            packDefaults = emptyList(),
            patternHypotheses = emptyList(),
        )
        val response = McpResponse.KnowledgeSnapshotResponse(
            requestId = "req-1",
            snapshotData = ks,
            error = null,
        )
        val serialized2 = json.encodeToString(response)
        val deserialized2 = json.decodeFromString<McpResponse>(serialized2)
        assertEquals(response, deserialized2)
    }

    @Test
    fun testChangedRangesSerialization() {
        val ranges = ChangedRanges(listOf(Span(1, 0, 1, 10), Span(5, 0, 5, 20)))
        val serialized = json.encodeToString(ranges)
        val deserialized = json.decodeFromString<ChangedRanges>(serialized)
        assertEquals(ranges, deserialized)
    }

    @Test
    fun testRuntimeStatusSerialization() {
        val status = RuntimeStatus(
            isInitialized = true,
            indexedFiles = 100,
            knowledgeEntries = 50,
            lastAnalysis = currentTimeMillis(),
        )
        val serialized = json.encodeToString(status)
        val deserialized = json.decodeFromString<RuntimeStatus>(serialized)
        assertEquals(status, deserialized)
    }

    @Test
    fun testInvalidInputRejected() {
        val invalidJson = """{"value": 1.5}"""
        try {
            json.decodeFromString<Confidence>(invalidJson)
            throw AssertionError("Should have thrown")
        } catch (e: Exception) {
        }
    }
}