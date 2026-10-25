package dev.cint.v2.testing

import dev.cint.contracts.data.Confidence
import dev.cint.contracts.data.Intents
import dev.cint.contracts.data.Provenance
import dev.cint.contracts.data.Span
import dev.cint.contracts.data.Status
import dev.cint.contracts.facts.SemanticRequest
import dev.cint.contracts.facts.SemanticRequestKind
import dev.cint.contracts.facts.SemanticResult
import dev.cint.contracts.facts.SyntaxNode
import dev.cint.contracts.facts.SyntaxTree
import dev.cint.contracts.facts.TypeRef
import dev.cint.contracts.facts.SourceInput
import dev.cint.contracts.knowledge.AnnotationScope
import dev.cint.contracts.knowledge.FunctionAnnotation
import dev.cint.contracts.knowledge.FunctionSelector
import dev.cint.contracts.knowledge.PackDefault
import dev.cint.contracts.knowledge.PackOrigin
import dev.cint.contracts.ports.AnalysisRequest
import dev.cint.contracts.ports.HostChannelConfig
import dev.cint.contracts.ports.IndexInput
import dev.cint.contracts.ports.IpcReceive
import dev.cint.contracts.ports.PackDiagnostic
import dev.cint.contracts.ports.PackManifest
import dev.cint.contracts.ports.Playbook
import dev.cint.contracts.ports.RuleSpec
import dev.cint.contracts.ports.bindRulePlan
import dev.cint.contracts.util.CancellationToken
import dev.cint.contracts.util.ContentHash
import dev.cint.contracts.util.ContractError
import dev.cint.contracts.util.UnavailableReason
import dev.cint.contracts.ipc.ClientInfo
import dev.cint.contracts.ipc.IpcMessage
import dev.cint.contracts.mcp.McpNotification
import dev.cint.contracts.mcp.McpRequest
import dev.cint.contracts.mcp.McpResponse
import dev.cint.v2.testing.fakes.CancellationScript
import dev.cint.v2.testing.fakes.FakeCancellation
import dev.cint.v2.testing.fakes.FakeClock
import dev.cint.v2.testing.fakes.FakeHostSemanticChannel
import dev.cint.v2.testing.fakes.FakeHostSemanticChannelProvider
import dev.cint.v2.testing.fakes.FakeIndex
import dev.cint.v2.testing.fakes.FakeIpcTransport
import dev.cint.v2.testing.fakes.FakeIntentPipeline
import dev.cint.v2.testing.fakes.FakeKnowledgeStore
import dev.cint.v2.testing.fakes.FakeLanguageSupport
import dev.cint.v2.testing.fakes.FakeMcpTransport
import dev.cint.v2.testing.fakes.FakePortCatalog
import dev.cint.v2.testing.fakes.FakeRecorder
import dev.cint.v2.testing.fakes.FakeRuleEvaluator
import dev.cint.v2.testing.fakes.FakeRuleEvaluatorRegistry
import dev.cint.v2.testing.fakes.FakeRulePackSource
import dev.cint.v2.testing.fakes.FakeRuntime
import dev.cint.v2.testing.fakes.FakeSemanticProvider
import dev.cint.v2.testing.fakes.FakeSyntaxEngine
import dev.cint.v2.testing.fakes.fakeDescriptor
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

class FakePortsTest {
    private val clock = FakeClock()
    private val content = "fun findUser() {\n    call repository.findUser\n}\n".encodeToByteArray()

    @Test
    fun syntaxEngineIsDeterministic() {
        val first = FakeSyntaxEngine("fake")
        val second = FakeSyntaxEngine("fake")

        val firstTree = first.parse(content, null, CancellationToken.NONE).getOrThrow()
        val secondTree = second.parse(content, null, CancellationToken.NONE).getOrThrow()

        assertEquals(firstTree, secondTree)
        assertEquals(
            first.facts(firstTree, listOf("q"), CancellationToken.NONE).getOrThrow(),
            second.facts(secondTree, listOf("q"), CancellationToken.NONE).getOrThrow(),
        )
        assertEquals(
            first.nodeHash(firstTree, firstTree.rootId).getOrThrow(),
            second.nodeHash(secondTree, secondTree.rootId).getOrThrow(),
        )
        assertEquals(0, first.diff(firstTree, secondTree).ranges.size)
        assertFalse(first.diff(firstTree, secondTree).isWholeFile)
    }

    @Test
    fun semanticProviderCoversEveryRequestKind() {
        val requests = SemanticRequestKind.entries.map { kind ->
            SemanticRequest("file:///Sample.fake", 1, 0, kind, clock.deadlineIn(1000))
        }
        val first = FakeSemanticProvider("fake")
        val second = FakeSemanticProvider("fake")
        requests.forEach { request ->
            assertEquals(first.request(request).getOrThrow(), second.request(request).getOrThrow())
        }
        assertEquals(requests.size, first.recorder.count("semantic.request"))
        assertTrue(first.shutdown().isSuccess)
        assertTrue(first.request(requests.first()).isFailure)
    }

    @Test
    fun hostChannelDelegatesToProviderAndRecords() {
        val providerRecorder = FakeRecorder()
        val provider = FakeSemanticProvider("fake", providerRecorder)
        val channel = FakeHostSemanticChannel(provider)
        val request = SemanticRequest(
            "file:///Sample.fake",
            1,
            0,
            SemanticRequestKind.HOVER_AT,
            clock.deadlineIn(1000),
        )

        assertTrue(channel.send(request).isSuccess)
        assertEquals(1, providerRecorder.count("semantic.request"))
        assertEquals(1, channel.recorder.count("host.send"))
        assertTrue(channel.close().isSuccess)
        assertTrue(channel.send(request).isFailure)
    }

    @Test
    fun languageSupportWiresSyntaxAndProvider() {
        val engine = FakeSyntaxEngine("fake")
        val provider = FakeSemanticProvider("fake")
        val support = FakeLanguageSupport(fakeDescriptor(), engine, provider)

        assertEquals("fake", support.descriptor().getOrThrow().id)
        assertEquals(engine, support.syntax().getOrThrow())
        assertEquals(provider, support.semanticProvider().getOrThrow())
        assertEquals(3, support.recorder.calls.size)
    }

    @Test
    fun failingLanguageSupportReturnsDeclaredError() {
        val support = FakeLanguageSupport(
            fakeDescriptor(),
            forcedFailure = ContractError.DependencyUnavailable("lsp", "not running"),
        )
        assertEquals(
            ContractError.DependencyUnavailable("lsp", "not running"),
            support.descriptor().errorOrNull,
        )
    }

    @Test
    fun knowledgeStoreIsDeterministicAndDefaultsDoNotOverwrite() {
        val store = FakeKnowledgeStore(clock = clock)
        val selector = FunctionSelector(qualifiedName = "findUser")
        val annotation = FunctionAnnotation(
            selector = selector,
            intents = setOf(Intents.LOOKUP),
            scope = AnnotationScope.GLOBAL,
            provenance = Provenance.USER,
            confidence = Confidence.HIGH,
            status = Status.ACCEPTED,
            createdAt = clock.now,
            updatedAt = clock.now,
        )

        assertTrue(store.setFunctionAnnotation(annotation).isSuccess)
        val first = store.snapshot().getOrThrow()
        val second = store.snapshot().getOrThrow()
        assertEquals(first, second)

        val default = PackDefault(
            selector = selector,
            intents = setOf(Intents.SEARCH),
            packId = "lookup",
            packVersion = "1.0.0",
            createdAt = clock.now,
        )
        assertTrue(store.materializeDefaults(listOf(default)).isSuccess)
        assertEquals(
            setOf(Intents.LOOKUP),
            store.functionAnnotations().getOrThrow().single().intents,
        )

        val fresh = FunctionSelector(qualifiedName = "saveUser")
        assertTrue(
            store.materializeDefaults(
                listOf(default.copy(selector = fresh, intents = setOf(Intents.DATA_MODIFY))),
            ).isSuccess,
        )
        assertEquals(2, store.functionAnnotations().getOrThrow().size)
    }

    @Test
    fun knowledgeStoreRemovalIsExplicit() {
        val store = FakeKnowledgeStore(clock = clock)
        val selector = FunctionSelector(qualifiedName = "missing")
        assertTrue(store.removeFunctionAnnotation(selector).isFailure)
    }

    @Test
    fun fakeKnowledgeStoreKeepsFileVariantsPathAware() {
        val store = FakeKnowledgeStore(clock = clock)
        val selector = FunctionSelector(qualifiedName = "findUser")
        fun file(path: String) =
            FunctionAnnotation.create(
                selector = selector,
                intents = setOf(Intents.SEARCH),
                scope = AnnotationScope.FILE,
                provenance = Provenance.USER,
                confidence = Confidence.HIGH,
                status = Status.ACCEPTED,
                createdAt = clock.now,
                path = path,
            )

        assertTrue(store.setFunctionAnnotation(file("A.kt")).isSuccess)
        assertTrue(store.setFunctionAnnotation(file("B.kt")).isSuccess)
        assertTrue(store.removeFunctionAnnotation(selector, path = "A.kt").isSuccess)
        assertEquals(listOf("B.kt"), store.functionAnnotations().getOrThrow().mapNotNull { it.path })

        assertEquals(
            1,
            store.pruneStale(
                setOf(selector),
                emptySet(),
                validFilePaths = mapOf(selector to setOf("Other.kt")),
            ).getOrThrow(),
        )
        assertTrue(store.functionAnnotations().getOrThrow().isEmpty())
    }

    @Test
    fun rulePackSourceReadsScriptedData() {
        val manifest = PackManifest("lookup", "Lookup", "1.0.0", listOf("fake"))
        val playbook = Playbook("lookup", listOf(RuleSpec("lookup-rule", Intents.LOOKUP)))
        val diagnostic = PackDiagnostic("lookup", "bad-seed", "seed rejected")
        val source = FakeRulePackSource(
            packManifests = listOf(manifest),
            playbooks = mapOf("lookup" to playbook),
            packDiagnostics = listOf(diagnostic),
        )

        assertEquals(listOf(manifest), source.manifests().getOrThrow())
        assertEquals(playbook, source.playbook("lookup").getOrThrow())
        assertEquals(listOf(diagnostic), source.diagnostics().getOrThrow())
        assertEquals(ContractError.NotFound("playbook", "missing"), source.playbook("missing").errorOrNull)
    }

    @Test
    fun indexIsDeterministicCachesAndRejectsMissingContent() {
        val first = FakeIndex(contents = mutableMapOf("Sample.fake" to content.decodeToString()))
        val second = FakeIndex(contents = mutableMapOf("Sample.fake" to content.decodeToString()))
        val input = SourceInput.FilePath("Sample.fake", "fake", 1)
        val supportOne = FakeLanguageSupport(fakeDescriptor())
        val supportTwo = FakeLanguageSupport(fakeDescriptor())

        val firstResult = first.ensure(input, supportOne)
        val secondResult = second.ensure(input, supportTwo)
        assertEquals(firstResult.getOrThrow(), secondResult.getOrThrow())
        assertEquals(1L, firstResult.getOrThrow().sourceEpoch)
        assertTrue(firstResult.getOrThrow().facts.functions.isNotEmpty())

        val cached = first.ensure(input, supportOne).getOrThrow()
        assertEquals(firstResult.getOrThrow(), cached)
        assertEquals(1, first.recorder.count("index.cacheHit"))
        assertTrue(firstResult.getOrThrow().semanticFacts.single().result is SemanticResult.TypeAt)

        val versionTwo = first.ensure(input.copy(version = 2), supportOne).getOrThrow()
        assertEquals(2L, (versionTwo.input as SourceInput.FilePath).version)
        assertNotEquals(firstResult.getOrThrow(), versionTwo)
        assertTrue(versionTwo.sourceEpoch > firstResult.getOrThrow().sourceEpoch)
        assertEquals(firstResult.getOrThrow(), first.ensure(input, supportOne).getOrThrow())

        val otherSupport = FakeLanguageSupport(
            fakeDescriptor(id = "other"),
            FakeSyntaxEngine("other"),
            FakeSemanticProvider("other"),
        )
        val otherLanguage = first.ensure(input, otherSupport).getOrThrow()
        assertNotEquals(firstResult.getOrThrow(), otherLanguage)
        assertEquals("other", otherLanguage.facts.syntaxTree?.language)

        val missing = first.ensure(SourceInput.FilePath("Missing.fake", "fake", 1), supportOne)
        assertTrue(missing.isFailure)

        assertTrue(first.invalidate(listOf("Sample.fake")).isSuccess)
        assertTrue(first.snapshot("Sample.fake").getOrThrow() == null)
    }

    @Test
    fun expiredDeadlineReturnsUnavailableInsteadOfAValue() {
        val clock = FakeClock()
        val provider = FakeSemanticProvider("fake", clock = clock)
        val deadline = clock.deadlineIn(100)
        clock.advanceByMillis(101)

        val result = provider.request(
            SemanticRequest("file:///Sample.fake", 1, 0, SemanticRequestKind.TYPE_AT, deadline),
        ).getOrThrow() as SemanticResult.TypeAt

        assertEquals(null, result.typeRef)
        assertEquals(UnavailableReason.TIMEOUT, result.unavailable?.reason)
        assertEquals(1, provider.recorder.count("semantic.deadlineExpired"))
    }

    @Test
    fun syntaxByteOffsetsHandleUtf8AndCrlf() {
        val text = "// café\r\nfun findUser() {}\r\n"
        val tree = FakeSyntaxEngine("fake")
            .parse(text.encodeToByteArray(), null, CancellationToken.NONE)
            .getOrThrow()
        val function = tree.nodes.values.single { it.kind == "function_declaration" }
        val expectedStart = text.substring(0, text.indexOf("fun")).encodeToByteArray().size

        assertEquals(expectedStart, function.startByte)
        assertEquals(expectedStart + function.text.encodeToByteArray().size, function.endByte)
        assertEquals(1, function.startLine)
        assertEquals("fun findUser", function.text)
    }

    @Test
    fun transportsAreScriptableAndDeterministic() {
        val ipc = FakeIpcTransport()
        val handshake = IpcMessage.Handshake(
            fingerprint = "fp",
            clientInfo = ClientInfo("fake-client", "1.0"),
        )
        assertTrue(ipc.send(handshake).isSuccess)
        ipc.enqueue(IpcReceive.Message(handshake))
        assertEquals(IpcReceive.Message(handshake), ipc.receive().getOrThrow())
        assertEquals(IpcReceive.Empty, ipc.receive().getOrThrow())
        assertTrue(ipc.close().isSuccess)
        assertEquals(IpcReceive.Closed, ipc.receive().getOrThrow())

        val mcp = FakeMcpTransport()
        val request = McpRequest.KnowledgeSnapshot("req-1")
        val response = McpResponse.KnowledgeSnapshotResponse("req-1", null)
        mcp.enqueue(response)
        assertEquals(response, mcp.request(request).getOrThrow())
        assertTrue(mcp.notify(McpNotification.KnowledgeChanged("set", "findUser")).isSuccess)
        assertEquals(1, mcp.notifications.size)
        assertTrue(mcp.close().isSuccess)
        assertTrue(mcp.request(request).isFailure)
    }

    @Test
    fun runtimeChainsLanguageIndexAndPipeline() {
        val recorder = FakeRecorder()
        val clock = FakeClock()
        val syntax = FakeSyntaxEngine("fake", recorder)
        val provider = FakeSemanticProvider("fake", recorder)
        val support = FakeLanguageSupport(fakeDescriptor(), syntax, provider, recorder)
        val contents = mutableMapOf("Sample.fake" to content.decodeToString())
        val index = FakeIndex(contents, clock, recorder = recorder)
        val knowledge = FakeKnowledgeStore(clock = clock, recorder = recorder)
        val annotation = FunctionAnnotation(
            selector = FunctionSelector(qualifiedName = "findUser"),
            intents = setOf(Intents.LOOKUP),
            scope = AnnotationScope.GLOBAL,
            provenance = Provenance.USER,
            confidence = Confidence.HIGH,
            status = Status.ACCEPTED,
            createdAt = clock.now,
            updatedAt = clock.now,
        )
        knowledge.setFunctionAnnotation(annotation)
        val pipeline = FakeIntentPipeline(recorder)
        val runtime = FakeRuntime(
            supports = mapOf("fake" to support),
            sources = mutableMapOf("Sample.fake" to SourceInput.FilePath("Sample.fake", "fake", 1)),
            index = index,
            pipeline = pipeline,
            knowledgeStore = knowledge,
            clock = clock,
            recorder = recorder,
        )

        val result = runtime.analyze(AnalysisRequest(targetPaths = listOf("Sample.fake"), language = "fake"))
            .getOrThrow()

        assertEquals(listOf(Intents.LOOKUP), result.annotations.map { it.intents.single() })
        assertEquals(1, recorder.count("syntax.parse"))
        assertEquals(1, recorder.count("syntax.facts"))
        assertEquals(1, recorder.count("semantic.request"))
        assertEquals(1, recorder.count("pipeline.analyze"))
        assertEquals(1, recorder.count("index.indexed"))
        assertEquals(
            listOf(
                "index.ensure",
                "language.descriptor",
                "language.syntax",
                "language.semanticProvider",
                "syntax.parse",
                "syntax.facts",
                "semantic.request",
                "index.indexed",
                "pipeline.analyze",
                "runtime.analyzed",
            ),
            recorder.names().filterNot { it.startsWith("knowledge.") || it == "runtime.analyze" },
        )
        assertEquals(1, runtime.status().getOrThrow().indexedFiles)
        assertEquals(1, runtime.status().getOrThrow().knowledgeEntries)
    }

    @Test
    fun fakeClockAndCancellationAreDeterministic() {
        val clock = FakeClock()
        val deadline = clock.deadlineIn(1000)
        assertEquals(1000L, clock.remainingMillis(deadline))
        assertFalse(clock.isExpired(deadline))
        clock.advanceByMillis(1000)
        assertTrue(clock.isExpired(deadline))
        assertEquals(0L, clock.remainingMillis(deadline))

        val script = CancellationScript(cancelAtCheck = 2)
        assertFalse(script.next().isCancelled)
        assertTrue(script.next().isCancelled)
        assertTrue(FakeCancellation.active().isCancelled.not())
        assertTrue(FakeCancellation.cancelled().isCancelled)
    }

    @Test
    fun cancelledCallsFailWithoutInvokingWork() {
        val engine = FakeSyntaxEngine("fake")
        assertTrue(engine.parse(content, null, CancellationToken.fromBoolean(true)).isFailure)
        assertTrue(engine.facts(defaultTree(engine), listOf("q"), CancellationToken.fromBoolean(true)).isFailure)
    }

    @Test
    fun fakesHaveNoIoOrNetworkImports() {
        val sourceDir = System.getProperty("v2.fixtures.sourceDir")
            ?: fail("v2.fixtures.sourceDir system property is not set")
        val fakesDir = Paths.get(sourceDir, "dev", "cint", "v2", "testing", "fakes")
        val forbidden = listOf(
            Regex("""\bjava\.io\b"""),
            Regex("""\bjava\.net\b"""),
            Regex("""\bjava\.nio\b"""),
            Regex("""\bProcessBuilder\b"""),
            Regex("""\bSocket\b"""),
            Regex("""\bURL\("""),
            Regex("""\bkotlinx\.coroutines\b"""),
            Regex("""(?<![A-Za-z0-9_])Files\."""),
            Regex("""(?<![A-Za-z0-9_])Paths\."""),
        )
        val offenders = mutableListOf<String>()
        Files.walk(fakesDir).use { stream ->
            stream.filter { it.toString().endsWith(".kt") }.forEach { file ->
                val text = Files.readString(file)
                forbidden.filter { it.containsMatchIn(text) }.forEach { token ->
                    offenders.add("${file.fileName}: ${token.pattern}")
                }
            }
        }
        assertTrue(offenders.isEmpty(), "Fakes must not reference IO or network: $offenders")
    }

    private fun defaultTree(engine: FakeSyntaxEngine) =
        engine.parse(content, null, CancellationToken.NONE).getOrThrow()

    @Test
    fun multiFileRuntimeQualifiesFactsAndKeepsTreesCoherent() {
        val recorder = FakeRecorder()
        val clock = FakeClock()
        val syntax = FakeSyntaxEngine("fake", recorder)
        val provider = FakeSemanticProvider("fake", recorder, clock = clock)
        val support = FakeLanguageSupport(fakeDescriptor(), syntax, provider, recorder)
        val index = FakeIndex(
            mutableMapOf("one.fake" to content.decodeToString(), "two.fake" to content.decodeToString()),
            clock,
            recorder = recorder,
        )
        val knowledge = FakeKnowledgeStore(clock = clock, recorder = recorder)
        knowledge.setFunctionAnnotation(
            FunctionAnnotation(
                selector = FunctionSelector(qualifiedName = "findUser"),
                intents = setOf(Intents.LOOKUP),
                scope = AnnotationScope.GLOBAL,
                provenance = Provenance.USER,
                confidence = Confidence.HIGH,
                status = Status.ACCEPTED,
                createdAt = clock.now,
                updatedAt = clock.now,
            ),
        )
        val runtime = FakeRuntime(
            supports = mapOf("fake" to support),
            sources = mutableMapOf(
                "one.fake" to SourceInput.InMemory(content.decodeToString(), "fake", 1, "one.fake"),
                "two.fake" to SourceInput.InMemory(content.decodeToString(), "fake", 1, "two.fake"),
            ),
            index = index,
            pipeline = FakeIntentPipeline(recorder),
            knowledgeStore = knowledge,
            clock = clock,
            recorder = recorder,
        )

        val result = runtime.analyze(
            AnalysisRequest(targetPaths = listOf("one.fake", "two.fake"), language = "fake"),
        ).getOrThrow()

        val tree = assertNotNull(result.facts.syntaxTree)
        assertEquals(2, result.annotations.size)
        assertEquals(2, result.facts.functions.size)
        val functionIds = result.facts.functions.mapNotNull { it.nodeId }
        assertEquals(functionIds.size, functionIds.toSet().size)
        assertTrue(functionIds.all { it in tree.nodes })
        assertTrue(functionIds.all { nodeId -> nodeId.substringBefore('|') == "one.fake" || nodeId.substringBefore('|') == "two.fake" })
        assertTrue(result.facts.calls.all { it.nodeId in tree.nodes })
        assertEquals(2, tree.nodes.values.count { it.kind == "function_declaration" })
    }


    @Test
    fun knowledgeStoreTracksOriginsAndPrunesStaleEntries() {
        val store = FakeKnowledgeStore(clock = clock)
        val selector = FunctionSelector(qualifiedName = "findUser")
        val default = PackDefault(
            selector = selector,
            intents = setOf(Intents.LOOKUP),
            packId = "lookup",
            packVersion = "1.0.0",
            createdAt = clock.now,
        )

        assertFalse(store.isMaterialized)
        assertTrue(store.materializeDefaults(listOf(default)).isSuccess)
        assertTrue(store.isMaterialized)
        assertEquals(PackOrigin("lookup", "1.0.0"), store.packOrigin(selector).getOrThrow())
        assertEquals(
            PackOrigin("lookup", "1.0.0"),
            store.snapshot().getOrThrow().packOrigins[selector.identityKey],
        )

        assertEquals(1, store.pruneStale(emptySet(), emptySet()).getOrThrow())
        assertTrue(store.functionAnnotations().getOrThrow().isEmpty())
        assertNull(store.packOrigin(selector).getOrThrow())
    }

    @Test
    fun indexSupportsPathlessDocumentIdsAndFullRescan() {
        val index = FakeIndex(contents = mutableMapOf("a.fake" to "fun a() {}"))
        val support = FakeLanguageSupport(fakeDescriptor())
        val missingDocumentId = SourceInput.InMemory("fun a() {}", "fake", 1, null, null)

        assertTrue(index.ensure(missingDocumentId, support).isFailure)
        val first = index.ensure(
            SourceInput.InMemory("fun a() {}", "fake", 1, null, "doc:a"),
            support,
        ).getOrThrow()
        val second = index.ensure(
            SourceInput.InMemory("fun b() {}", "fake", 1, null, "doc:b"),
            support,
        ).getOrThrow()
        val rescan = index.fullRescan(listOf(IndexInput(first.input, support), IndexInput(second.input, support)))
            .getOrThrow()

        assertEquals(listOf(first, second), rescan.sources)
        assertEquals(1, index.recorder.count("index.fullRescan"))
        assertEquals("memory://doc:a", first.semanticFacts.single().request.uri)
    }

    @Test
    fun semanticDocumentSyncAndHostProviderAreDeterministic() {
        val provider = FakeSemanticProvider("fake")
        assertTrue(provider.openDocument("file:///A.fake", "one", 1).isSuccess)
        assertTrue(provider.changeDocument("file:///A.fake", "two", 2).isSuccess)
        assertEquals("two", provider.documents.getValue("file:///A.fake").text)
        assertTrue(provider.closeDocument("file:///A.fake").isSuccess)
        assertTrue(provider.documents.isEmpty())

        val hostProvider = FakeHostSemanticChannelProvider()
        val channel = hostProvider.open(HostChannelConfig("fake-host")).getOrThrow()
        assertTrue(channel.openDocument("file:///A.fake", "one", 1).isSuccess)
        assertEquals("fake-host", hostProvider.openedConfigs.single().type)
    }

    @Test
    fun nodeHashCoversDescendantsNotJustTheNodeText() {
        val engine = FakeSyntaxEngine("fake")
        val first = treeWithChild("a")
        val changed = treeWithChild("b")

        assertNotEquals(
            engine.nodeHash(first, "root").getOrThrow(),
            engine.nodeHash(changed, "root").getOrThrow(),
        )
        assertNotEquals(
            engine.nodeHash(first, "left").getOrThrow(),
            engine.nodeHash(changed, "left").getOrThrow(),
        )
        assertEquals(
            engine.nodeHash(first, "right").getOrThrow(),
            engine.nodeHash(changed, "right").getOrThrow(),
        )
    }

    @Test
    fun ruleEvaluatorRegistryBindsPlaybookPlans() {
        val spec = RuleSpec("lookup-rule", Intents.LOOKUP)
        val evaluator = FakeRuleEvaluator(spec)
        val registry = FakeRuleEvaluatorRegistry(mapOf(spec.id to evaluator))

        val plan = bindRulePlan(Playbook("lookup", listOf(spec)), registry).getOrThrow()

        assertEquals(listOf(evaluator), plan.rules)
        assertEquals(listOf(spec.id), registry.requested)
    }

    @Test
    fun everyDeclaredPortHasAFake() {
        assertEquals(
            listOf(
                "SyntaxEngine",
                "SemanticProvider",
                "LanguageSupport",
                "KnowledgeStore",
                "RulePackSource",
                "IntentPipeline",
                "Runtime",
                "Index",
                "HostSemanticChannel",
                "HostSemanticChannelProvider",
                "IpcTransport",
                "McpTransport",
                "RuleEvaluator",
                "RuleEvaluatorRegistry",
            ),
            FakePortCatalog.implementedPorts,
        )
        assertEquals("ticket 077: no fallback-AI port exists in contracts yet", FakePortCatalog.deferredPorts["FallbackAi"])
        assertNotNull(FakeSyntaxEngine("fake"))
        assertNotNull(FakeSemanticProvider("fake"))
        assertNotNull(FakeLanguageSupport(fakeDescriptor()))
        assertNotNull(FakeKnowledgeStore())
        assertNotNull(FakeRulePackSource())
        assertNotNull(FakeIntentPipeline())
        assertNotNull(
            FakeRuntime(
                supports = mapOf("fake" to FakeLanguageSupport(fakeDescriptor())),
                sources = mutableMapOf("Sample.fake" to SourceInput.InMemory(content.decodeToString(), "fake")),
            ),
        )
        assertNotNull(FakeIndex())
        assertNotNull(FakeHostSemanticChannel())
        assertNotNull(FakeHostSemanticChannelProvider())
        assertNotNull(FakeIpcTransport())
        assertNotNull(FakeMcpTransport())
        assertNotNull(FakeRuleEvaluator(RuleSpec("fake", Intents.LOOKUP)))
        assertNotNull(FakeRuleEvaluatorRegistry())
    }

    private fun treeWithChild(leftText: String): SyntaxTree {
        val root = SyntaxNode(
            id = "root",
            kind = "file",
            startByte = 0,
            endByte = 2,
            startLine = 0,
            startCol = 0,
            endLine = 0,
            endCol = 2,
            text = "xx",
            childIds = listOf("left", "right"),
        )
        val left = SyntaxNode(
            id = "left",
            kind = "expression",
            startByte = 0,
            endByte = 1,
            startLine = 0,
            startCol = 0,
            endLine = 0,
            endCol = 1,
            text = leftText,
            parentId = "root",
        )
        val right = SyntaxNode(
            id = "right",
            kind = "expression",
            startByte = 1,
            endByte = 2,
            startLine = 0,
            startCol = 1,
            endLine = 0,
            endCol = 2,
            text = "x",
            parentId = "root",
        )
        return SyntaxTree(
            rootId = "root",
            nodes = mapOf("root" to root, "left" to left, "right" to right),
            language = "fake",
            version = "1",
            contentHash = ContentHash.compute("xx").value,
        )
    }
}
