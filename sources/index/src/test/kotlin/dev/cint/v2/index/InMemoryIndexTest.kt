package dev.cint.v2.index

import dev.cint.contracts.data.Completeness
import dev.cint.contracts.data.Span
import dev.cint.contracts.facts.Diagnostic
import dev.cint.contracts.facts.DiagnosticSeverity
import dev.cint.contracts.facts.FactBatch
import dev.cint.contracts.facts.ImportFact
import dev.cint.contracts.facts.SemanticRequestKind
import dev.cint.contracts.facts.SemanticResult
import dev.cint.contracts.facts.SourceInput
import dev.cint.contracts.ports.IndexInput
import dev.cint.contracts.ports.SemanticProvider
import dev.cint.contracts.util.CancellationToken
import dev.cint.contracts.util.CancelledToken
import dev.cint.contracts.util.ContentHash
import dev.cint.contracts.util.ContractError
import dev.cint.contracts.util.ContractResult
import dev.cint.contracts.util.Unavailable
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InMemoryIndexTest {
    @Test
    fun repeatedInMemoryAnalysisReusesUnchangedFacts() {
        val engine = FakeSyntaxEngine()
        val provider = FakeSemanticProvider()
        val support = FakeLanguageSupport(engine, provider)
        val index = InMemoryIndex(testPlan())
        val input = SourceInput.InMemory("fun main() {}", "test", version = 1, path = "Memory.kt")

        val first = index.ensure(input, support).requireSuccess()
        val second = index.ensure(input, support).requireSuccess()
        val versioned = index.ensure(input.copy(version = 2), support).requireSuccess()

        assertEquals(1, engine.parseCalls)
        assertEquals(1, engine.factsCalls)
        assertEquals(2, provider.requestCalls)
        assertEquals(first.facts, second.facts)
        assertEquals(first.sourceEpoch, second.sourceEpoch)
        assertEquals(first.contentHash, second.contentHash)
        assertEquals(first.sourceEpoch, versioned.sourceEpoch - 1)
        assertEquals(2, (versioned.input as SourceInput.InMemory).version)
        assertEquals(
            2,
            versioned.semanticFacts
                .single()
                .request.version,
        )
        assertTrue(versioned.semanticFacts.isNotEmpty())
    }

    @Test
    fun semanticRequestOpensDocumentWithVersionedText() {
        withTempDir { directory ->
            val file = directory.resolve("A.kt")
            Files.writeString(file, "fun main() {}")
            val provider = FakeSemanticProvider().apply { documentSyncSupported = true }
            val support = FakeLanguageSupport(FakeSyntaxEngine(), provider)
            val index = InMemoryIndex(testPlan())
            val input = SourceInput.FilePath(file.toString(), "test", version = 7)

            index.ensure(input, support).requireSuccess()
            index.ensure(input, support).requireSuccess()

            assertEquals(1, provider.openDocumentCalls)
            val opened = provider.openedDocuments.single()
            assertTrue(opened.first.endsWith("/A.kt"))
            assertEquals("fun main() {}", opened.second)
            assertEquals(7, opened.third)
        }
    }

    @Test
    fun fileMetadataPrefilterConfirmsWithContentHash() {
        withTempDir { directory ->
            val file = directory.resolve("A.kt")
            Files.writeString(file, "hello")
            val engine = FakeSyntaxEngine()
            val provider = FakeSemanticProvider()
            val support = FakeLanguageSupport(engine, provider)
            val index = InMemoryIndex(testPlan())
            val input = SourceInput.FilePath(file.toString(), "test", 1)

            val first = index.ensure(input, support).requireSuccess()
            assertEquals(1, engine.parseCalls)

            Files.setLastModifiedTime(
                file,
                FileTime.fromMillis(Files.getLastModifiedTime(file).toMillis() + 5_000),
            )
            val touched = index.ensure(input, support).requireSuccess()

            assertEquals(1, engine.parseCalls)
            assertEquals(1, engine.factsCalls)
            assertEquals(1, provider.requestCalls)
            assertEquals(first.facts, touched.facts)
            assertEquals(first.sourceEpoch, touched.sourceEpoch)

            Files.writeString(file, "world")
            val changed = index.ensure(input.copy(version = 2), support).requireSuccess()

            assertEquals(2, engine.parseCalls)
            assertEquals(2, engine.factsCalls)
            assertEquals(2, provider.requestCalls)
            assertTrue(first.contentHash != changed.contentHash)
            assertTrue(first.sourceEpoch < changed.sourceEpoch)
        }
    }

    @Test
    fun changedFileInvalidatesOnlyAffectedNodesAndDependents() {
        withTempDir { directory ->
            val aFile = directory.resolve("A.kt")
            val bFile = directory.resolve("B.kt")
            val cFile = directory.resolve("C.kt")
            Files.writeString(aFile, "a+b")
            Files.writeString(bFile, "b")
            Files.writeString(cFile, "c")

            val engineA =
                FakeSyntaxEngine(
                    treeFactory = { text ->
                        when (text) {
                            "a+b" -> twoChildTree(text, "a", "b")
                            "A+b" -> twoChildTree(text, "A", "b")
                            else -> singleNodeTree(text)
                        }
                    },
                )
            val engineB =
                FakeSyntaxEngine(
                    factsFactory = {
                        FactBatch(
                            syntaxTree = it,
                            imports = listOf(ImportFact(nodeId = "import", path = aFile.toString())),
                        )
                    },
                )
            val engineC = FakeSyntaxEngine()
            val providerA = FakeSemanticProvider()
            var semanticGeneration = 0
            val providerB =
                FakeSemanticProvider().apply {
                    executor = {
                        semanticGeneration += 1
                        success(
                            SemanticResult.DiagnosticsFor(
                                listOf(
                                    Diagnostic(
                                        span = Span.ZERO,
                                        message = "generation-$semanticGeneration",
                                        severity = DiagnosticSeverity.INFO,
                                    ),
                                ),
                            ),
                        )
                    }
                }
            val providerC = FakeSemanticProvider()

            val index = InMemoryIndex(testPlan())
            val inputA = SourceInput.FilePath(aFile.toString(), "test", 1)
            val inputB = SourceInput.FilePath(bFile.toString(), "test", 1)
            val inputC = SourceInput.FilePath(cFile.toString(), "test", 1)

            index.ensure(inputA, FakeLanguageSupport(engineA, providerA)).requireSuccess()
            index.ensure(inputB, FakeLanguageSupport(engineB, providerB)).requireSuccess()
            index.ensure(inputC, FakeLanguageSupport(engineC, providerC)).requireSuccess()

            Files.writeString(aFile, "A+b")
            Files.setLastModifiedTime(
                aFile,
                FileTime.fromMillis(Files.getLastModifiedTime(aFile).toMillis() + 5_000),
            )
            index.ensure(inputA, FakeLanguageSupport(engineA, providerA)).requireSuccess()

            val change = requireNotNull(index.lastChange(aFile.toString()).requireSuccess())
            assertEquals(setOf("root", "left"), change.changed)
            assertTrue(change.added.isEmpty())
            assertTrue(change.removed.isEmpty())
            assertTrue(change.changed.contains("right") == false)

            assertEquals(listOf(bFile.toString()), index.dependentsOf(aFile.toString()).requireSuccess())
            assertEquals(
                Completeness.STALE,
                index.snapshot(bFile.toString()).requireSuccess()?.completeness,
            )
            assertEquals(
                Completeness.COMPLETE,
                index.snapshot(cFile.toString()).requireSuccess()?.completeness,
            )

            val staleSnapshot = index.snapshot(bFile.toString()).requireSuccess()
            val revalidated =
                index
                    .ensure(
                        inputB,
                        FakeLanguageSupport(engineB, providerB),
                    ).requireSuccess()

            assertEquals(1, engineB.parseCalls)
            assertEquals(1, engineB.factsCalls)
            assertEquals(2, providerB.requestCalls)
            assertTrue(staleSnapshot?.semanticFacts != revalidated.semanticFacts)
            assertTrue(requireNotNull(staleSnapshot).sourceEpoch < revalidated.sourceEpoch)
            assertEquals(Completeness.COMPLETE, revalidated.completeness)
            assertEquals(
                Completeness.COMPLETE,
                index.snapshot(bFile.toString()).requireSuccess()?.completeness,
            )
            assertEquals(1, engineC.parseCalls)
        }
    }

    @Test
    fun invalidateRemovesSourceAndMarksDependentsStale() {
        withTempDir { directory ->
            val aFile = directory.resolve("A.kt")
            val bFile = directory.resolve("B.kt")
            Files.writeString(aFile, "a")
            Files.writeString(bFile, "b")
            val engineA = FakeSyntaxEngine()
            val engineB =
                FakeSyntaxEngine(
                    factsFactory = {
                        FactBatch(
                            syntaxTree = it,
                            imports = listOf(ImportFact(nodeId = "import", path = aFile.toString())),
                        )
                    },
                )
            val index = InMemoryIndex(testPlan())
            val inputA = SourceInput.FilePath(aFile.toString(), "test", 1)
            val inputB = SourceInput.FilePath(bFile.toString(), "test", 1)
            val supportA = FakeLanguageSupport(engineA, FakeSemanticProvider())
            val supportB = FakeLanguageSupport(engineB, FakeSemanticProvider())
            index.ensure(inputA, supportA).requireSuccess()
            index.ensure(inputB, supportB).requireSuccess()

            index.invalidate(listOf(aFile.toString())).requireSuccess()

            assertNull(index.snapshot(aFile.toString()).requireSuccess())
            assertEquals(
                Completeness.STALE,
                index.snapshot(bFile.toString()).requireSuccess()?.completeness,
            )
            assertEquals(listOf(bFile.toString()), index.dependentsOf(aFile.toString()).requireSuccess())

            index.ensure(inputA, supportA).requireSuccess()
            assertEquals(2, engineA.parseCalls)
        }
    }

    @Test
    fun fullRescanIsExplicitAndReparsesEveryInput() {
        withTempDir { directory ->
            val file = directory.resolve("A.kt")
            Files.writeString(file, "hello")
            val engine = FakeSyntaxEngine()
            val provider = FakeSemanticProvider()
            val support = FakeLanguageSupport(engine, provider)
            val index = InMemoryIndex(testPlan())
            val input = SourceInput.FilePath(file.toString(), "test", 1)

            index.ensure(input, support).requireSuccess()
            assertEquals(1, engine.parseCalls)

            val result = index.fullRescan(listOf(IndexInput(input, support))).requireSuccess()

            assertEquals(1, result.sources.size)
            assertEquals(2, engine.parseCalls)
            assertEquals(2, engine.factsCalls)
            assertEquals(2, provider.requestCalls)
            assertEquals(
                result.sources.single().contentHash,
                index.snapshot(file.toString()).requireSuccess()?.contentHash,
            )
        }
    }

    @Test
    fun missingSemanticProviderIsAnExplicitError() {
        val engine = FakeSyntaxEngine()
        val missing: ContractResult<SemanticProvider> =
            failure(
                ContractError.DependencyUnavailable("lsp", "no provider"),
            )
        val support = FakeLanguageSupport(success(engine), missing)
        val index = InMemoryIndex(testPlan())
        val input = SourceInput.InMemory("fun main() {}", "test", documentId = "doc")

        val error = index.ensure(input, support).requireFailure()

        assertIs<ContractError.DependencyUnavailable>(error)
        assertEquals("lsp", error.dependency)
        assertEquals(0, engine.parseCalls)
    }

    @Test
    fun failedSemanticProviderRequestIsAnExplicitError() {
        val engine = FakeSyntaxEngine()
        val provider =
            FakeSemanticProvider().apply {
                executor = { failure(ContractError.InternalError("provider failed")) }
            }
        val index = InMemoryIndex(testPlan())
        val input = SourceInput.InMemory("fun main() {}", "test", documentId = "doc")

        val error = index.ensure(input, FakeLanguageSupport(engine, provider)).requireFailure()

        assertIs<ContractError.InternalError>(error)
        assertEquals("provider failed", error.message)
    }

    @Test
    fun unavailableSemanticResultIsAnExplicitError() {
        val engine = FakeSyntaxEngine()
        val provider =
            FakeSemanticProvider().apply {
                executor = {
                    success(
                        SemanticResult.DiagnosticsFor(
                            diagnostics = emptyList(),
                            unavailable = Unavailable.providerUnavailable("down"),
                        ),
                    )
                }
            }
        val index = InMemoryIndex(testPlan())
        val input = SourceInput.InMemory("fun main() {}", "test", documentId = "doc")

        val error = index.ensure(input, FakeLanguageSupport(engine, provider)).requireFailure()

        assertIs<ContractError.DependencyUnavailable>(error)
        assertEquals("semantic-provider", error.dependency)
    }

    @Test
    fun throwingSemanticProviderIsAnExplicitError() {
        val engine = FakeSyntaxEngine()
        val provider =
            FakeSemanticProvider().apply {
                throwOnRequest = IllegalStateException("crash")
            }
        val index = InMemoryIndex(testPlan())
        val input = SourceInput.InMemory("fun main() {}", "test", documentId = "doc")

        val error = index.ensure(input, FakeLanguageSupport(engine, provider)).requireFailure()

        assertIs<ContractError.InternalError>(error)
        assertEquals("Semantic provider request failed", error.message)
    }

    @Test
    fun cachedSourceStillRequiresSemanticProvider() {
        val engine = FakeSyntaxEngine()
        val provider = FakeSemanticProvider()
        val index = InMemoryIndex(testPlan())
        val input = SourceInput.InMemory("fun main() {}", "test", path = "A.kt")
        index.ensure(input, FakeLanguageSupport(engine, provider)).requireSuccess()

        val missing: ContractResult<SemanticProvider> =
            failure(
                ContractError.DependencyUnavailable("lsp", "gone"),
            )
        val error =
            index
                .ensure(
                    input,
                    FakeLanguageSupport(success(engine), missing),
                ).requireFailure()

        assertIs<ContractError.DependencyUnavailable>(error)
        assertEquals(1, engine.parseCalls)
    }

    @Test
    fun malformedContentHashFailsExplicitly() {
        val engine =
            FakeSyntaxEngine().apply {
                treeOverride = singleNodeTree("text").copy(contentHash = "not-a-hash")
            }
        val index = InMemoryIndex(testPlan())

        val error =
            index
                .ensure(
                    SourceInput.InMemory("text", "test", documentId = "doc"),
                    FakeLanguageSupport(engine, FakeSemanticProvider()),
                ).requireFailure()

        assertValidation(error, "contentHash")
    }

    @Test
    fun mismatchedContentHashFailsExplicitly() {
        val engine =
            FakeSyntaxEngine().apply {
                treeOverride =
                    singleNodeTree("text").copy(
                        contentHash = ContentHash.compute("other").value,
                    )
            }
        val index = InMemoryIndex(testPlan())

        val error =
            index
                .ensure(
                    SourceInput.InMemory("text", "test", documentId = "doc"),
                    FakeLanguageSupport(engine, FakeSemanticProvider()),
                ).requireFailure()

        assertValidation(error, "contentHash")
    }

    @Test
    fun danglingSyntaxNodeFailsExplicitly() {
        val base = singleNodeTree("text")
        val root = base.nodes.getValue("root").copy(childIds = listOf("missing"))
        val engine =
            FakeSyntaxEngine().apply {
                treeOverride = base.copy(nodes = mapOf("root" to root))
            }
        val index = InMemoryIndex(testPlan())

        val error =
            index
                .ensure(
                    SourceInput.InMemory("text", "test", documentId = "doc"),
                    FakeLanguageSupport(engine, FakeSemanticProvider()),
                ).requireFailure()

        assertValidation(error, "nodes.root.childIds")
    }

    @Test
    fun syntaxFactsForDifferentTreeFailExplicitly() {
        val engine =
            FakeSyntaxEngine().apply {
                factsOverride = FactBatch(syntaxTree = singleNodeTree("other"))
            }
        val index = InMemoryIndex(testPlan())

        val error =
            index
                .ensure(
                    SourceInput.InMemory("text", "test", documentId = "doc"),
                    FakeLanguageSupport(engine, FakeSemanticProvider()),
                ).requireFailure()

        assertValidation(error, "facts.syntaxTree")
    }

    @Test
    fun cancellationStopsBeforeParsingOrSemanticRequests() {
        val engine = FakeSyntaxEngine()
        val provider = FakeSemanticProvider()
        val index = InMemoryIndex(testPlan())
        val input = SourceInput.InMemory("fun main() {}", "test", documentId = "doc")

        val error =
            index
                .ensure(
                    input,
                    FakeLanguageSupport(engine, provider),
                    CancelledToken,
                ).requireFailure()

        assertIs<ContractError.Cancelled>(error)
        assertEquals(0, engine.parseCalls)
        assertEquals(0, provider.requestCalls)
    }

    @Test
    fun engineCancellationPropagates() {
        val engine =
            FakeSyntaxEngine().apply {
                parseError = ContractError.Cancelled("SyntaxEngine.parse")
            }
        val index = InMemoryIndex(testPlan())

        val error =
            index
                .ensure(
                    SourceInput.InMemory("fun main() {}", "test", documentId = "doc"),
                    FakeLanguageSupport(engine, FakeSemanticProvider()),
                ).requireFailure()

        assertIs<ContractError.Cancelled>(error)
    }

    @Test
    fun cancelledInvalidateDoesNoWork() {
        val index = InMemoryIndex(testPlan())

        val error =
            index
                .invalidate(
                    listOf("A.kt"),
                    CancellationToken.fromBoolean(cancelled = true),
                ).requireFailure()

        assertIs<ContractError.Cancelled>(error)
    }

    @Test
    fun indexDoesNotResolveLanguagesOrCallDescriptor() {
        withTempDir { directory ->
            val file = directory.resolve("A.kt")
            Files.writeString(file, "hello")
            val engine = FakeSyntaxEngine()
            val support = FakeLanguageSupport(engine, FakeSemanticProvider())
            val index = InMemoryIndex(testPlan())

            index
                .ensure(
                    SourceInput.FilePath(file.toString(), languageHint = "ignored", version = 1),
                    support,
                ).requireSuccess()

            assertEquals(0, support.descriptorCalls)
            assertEquals(1, engine.parseCalls)
        }
    }

    @Test
    fun sameSizeSameTimestampContentChangeIsDetectedByHash() {
        withTempDir { directory ->
            val file = directory.resolve("A.kt")
            Files.writeString(file, "hello")
            val originalTime = Files.getLastModifiedTime(file)
            val engine = FakeSyntaxEngine()
            val index = InMemoryIndex(testPlan())
            val support = FakeLanguageSupport(engine, FakeSemanticProvider())
            val input = SourceInput.FilePath(file.toString(), "test", 1)

            val first = index.ensure(input, support).requireSuccess()

            Files.writeString(file, "world")
            Files.setLastModifiedTime(file, originalTime)
            val changed = index.ensure(input, support).requireSuccess()

            assertEquals(2, engine.parseCalls)
            assertTrue(first.contentHash != changed.contentHash)
            assertTrue(first.sourceEpoch < changed.sourceEpoch)
        }
    }

    @Test
    fun pathlessInMemoryUsesStableDocumentIdentity() {
        val engine =
            FakeSyntaxEngine(
                treeFactory = { text ->
                    when (text) {
                        "a+b" -> twoChildTree(text, "a", "b")
                        "A+b" -> twoChildTree(text, "A", "b")
                        else -> singleNodeTree(text)
                    }
                },
            )
        val index = InMemoryIndex(testPlan())
        val support = FakeLanguageSupport(engine, FakeSemanticProvider())
        val firstInput = SourceInput.InMemory("a+b", "test", 1, documentId = "doc-1")
        val editedInput = firstInput.copy(text = "A+b", version = 2)

        val first = index.ensure(firstInput, support).requireSuccess()
        val edited = index.ensure(editedInput, support).requireSuccess()

        assertEquals(index.keyOf(firstInput).requireSuccess(), index.keyOf(editedInput).requireSuccess())
        assertEquals(2, engine.parseCalls)
        assertEquals(first.facts.syntaxTree, engine.lastPrevious)
        assertTrue(first.sourceEpoch < edited.sourceEpoch)
        assertEquals(edited.contentHash, index.snapshotSource(editedInput).requireSuccess()?.contentHash)
        val change = requireNotNull(index.lastChangeOf(editedInput).requireSuccess())
        assertEquals(setOf("root", "left"), change.changed)
        assertEquals(3, index.nodeHashesOf(editedInput).requireSuccess()?.size)
    }

    @Test
    fun differentSyntaxOrProviderIdentityRebuildsFacts() {
        val input = SourceInput.InMemory("fun main() {}", "test", documentId = "doc")
        val index = InMemoryIndex(testPlan())
        val engineA = FakeSyntaxEngine()
        val providerA = FakeSemanticProvider()

        val first = index.ensure(input, FakeLanguageSupport(engineA, providerA)).requireSuccess()

        val providerB = FakeSemanticProvider()
        val providerChanged =
            index
                .ensure(
                    input,
                    FakeLanguageSupport(engineA, providerB),
                ).requireSuccess()

        assertEquals(2, engineA.parseCalls)
        assertEquals(1, providerB.requestCalls)
        assertTrue(
            first.semanticFacts.single().providerEpoch !=
                providerChanged.semanticFacts.single().providerEpoch,
        )

        val engineB = FakeSyntaxEngine()
        val providerC = FakeSemanticProvider()
        val supportChanged =
            index
                .ensure(
                    input,
                    FakeLanguageSupport(engineB, providerC),
                ).requireSuccess()

        assertEquals(1, engineB.parseCalls)
        assertEquals(null, engineB.lastPrevious)
        assertEquals(1, providerC.requestCalls)
        assertTrue(providerChanged.sourceEpoch < supportChanged.sourceEpoch)
    }

    @Test
    fun planControlsSyntaxQueriesAndSemanticRequests() {
        val engine = FakeSyntaxEngine()
        val provider =
            FakeSemanticProvider().apply {
                executor = { success(SemanticResult.TypeAt(typeRef = null)) }
            }
        val plan =
            IndexPlan(
                syntaxQueryIds = listOf("custom.query"),
                semanticRequests = listOf(SemanticRequestSpec(SemanticRequestKind.TYPE_AT, offset = 3)),
            )
        val index = InMemoryIndex(plan)
        val input = SourceInput.InMemory("fun main() {}", "test", version = 7, documentId = "doc")

        val source = index.ensure(input, FakeLanguageSupport(engine, provider)).requireSuccess()

        assertEquals(listOf("custom.query"), engine.lastQueryIds)
        assertEquals(SemanticRequestKind.TYPE_AT, provider.requests.single().kind)
        assertEquals(3, provider.requests.single().offset)
        assertEquals(7, provider.requests.single().version)
        assertEquals(
            3,
            source.semanticFacts
                .single()
                .request.offset,
        )
    }

    @Test
    fun emptySemanticPlanIsAnExplicitError() {
        val engine = FakeSyntaxEngine()
        val index = InMemoryIndex(IndexPlan(emptyList(), emptyList()))

        val error =
            index
                .ensure(
                    SourceInput.InMemory("fun main() {}", "test", documentId = "doc"),
                    FakeLanguageSupport(engine, FakeSemanticProvider()),
                ).requireFailure()

        assertValidation(error, "plan.semanticRequests")
        assertEquals(0, engine.parseCalls)
    }

    @Test
    fun semanticResultKindMustMatchTheRequest() {
        val provider =
            FakeSemanticProvider().apply {
                executor = { success(SemanticResult.HoverAt(content = null)) }
            }
        val index = InMemoryIndex(testPlan())

        val error =
            index
                .ensure(
                    SourceInput.InMemory("fun main() {}", "test", documentId = "doc"),
                    FakeLanguageSupport(FakeSyntaxEngine(), provider),
                ).requireFailure()

        assertValidation(error, "semanticFacts")
    }

    @Test
    fun malformedEngineNodeHashFailsExplicitly() {
        val engine =
            FakeSyntaxEngine().apply {
                nodeHashOverride = success("not-a-hash")
            }
        val index = InMemoryIndex(testPlan())

        val error =
            index
                .ensure(
                    SourceInput.InMemory("text", "test", documentId = "doc"),
                    FakeLanguageSupport(engine, FakeSemanticProvider()),
                ).requireFailure()

        assertValidation(error, "nodeHash.root")
    }

    @Test
    fun treeLanguageMustMatchResolvedSyntaxEngine() {
        val engine =
            FakeSyntaxEngine().apply {
                engineLanguage = "kotlin"
            }
        val index = InMemoryIndex(testPlan())

        val error =
            index
                .ensure(
                    SourceInput.InMemory("text", "test", documentId = "doc"),
                    FakeLanguageSupport(engine, FakeSemanticProvider()),
                ).requireFailure()

        assertValidation(error, "language")
    }

    @Test
    fun failedFullRescanPreservesPreviousCache() {
        withTempDir { directory ->
            val file = directory.resolve("A.kt")
            Files.writeString(file, "hello")
            val engine = FakeSyntaxEngine()
            val index = InMemoryIndex(testPlan())
            val support = FakeLanguageSupport(engine, FakeSemanticProvider())
            val input = SourceInput.FilePath(file.toString(), "test", 1)
            val first = index.ensure(input, support).requireSuccess()
            val parseCalls = engine.parseCalls

            val error =
                index
                    .fullRescan(
                        listOf(
                            IndexInput(input, support),
                            IndexInput(
                                SourceInput.FilePath(directory.resolve("missing.kt").toString(), "test", 1),
                                support,
                            ),
                        ),
                    ).requireFailure()

            assertIs<ContractError.NotFound>(error)
            val preserved = index.snapshot(file.toString()).requireSuccess()
            assertEquals(first.sourceEpoch, preserved?.sourceEpoch)
            assertEquals(first.contentHash, preserved?.contentHash)
            val afterFailedRescan = engine.parseCalls
            index.ensure(input, support).requireSuccess()
            assertEquals(afterFailedRescan, engine.parseCalls)
        }
    }

    @Test
    fun cancelledFullRescanPreservesPreviousCache() {
        withTempDir { directory ->
            val file = directory.resolve("A.kt")
            Files.writeString(file, "hello")
            val engine = FakeSyntaxEngine()
            val index = InMemoryIndex(testPlan())
            val support = FakeLanguageSupport(engine, FakeSemanticProvider())
            val input = SourceInput.FilePath(file.toString(), "test", 1)
            val first = index.ensure(input, support).requireSuccess()

            val error =
                index
                    .fullRescan(
                        listOf(IndexInput(input, support)),
                        CancelledToken,
                    ).requireFailure()

            assertIs<ContractError.Cancelled>(error)
            assertEquals(first.sourceEpoch, index.snapshot(file.toString()).requireSuccess()?.sourceEpoch)
        }
    }

    @Test
    fun relativeImportsProduceTransitiveDependents() {
        withTempDir { directory ->
            val aFile = directory.resolve("A.kt")
            val bFile = directory.resolve("B.kt")
            val cFile = directory.resolve("C.kt")
            Files.writeString(aFile, "a")
            Files.writeString(bFile, "b")
            Files.writeString(cFile, "c")
            val engineA = FakeSyntaxEngine()
            val engineB =
                FakeSyntaxEngine(
                    factsFactory = {
                        FactBatch(
                            syntaxTree = it,
                            imports = listOf(ImportFact(nodeId = "import", path = "A.kt")),
                        )
                    },
                )
            val engineC =
                FakeSyntaxEngine(
                    factsFactory = {
                        FactBatch(
                            syntaxTree = it,
                            imports = listOf(ImportFact(nodeId = "import", path = "B.kt")),
                        )
                    },
                )
            val index = InMemoryIndex(testPlan())
            val inputA = SourceInput.FilePath(aFile.toString(), "test", 1)
            val inputB = SourceInput.FilePath(bFile.toString(), "test", 1)
            val inputC = SourceInput.FilePath(cFile.toString(), "test", 1)
            val supportA = FakeLanguageSupport(engineA, FakeSemanticProvider())
            val supportB = FakeLanguageSupport(engineB, FakeSemanticProvider())
            val supportC = FakeLanguageSupport(engineC, FakeSemanticProvider())
            index.ensure(inputA, supportA).requireSuccess()
            index.ensure(inputB, supportB).requireSuccess()
            index.ensure(inputC, supportC).requireSuccess()

            Files.writeString(aFile, "changed")
            Files.setLastModifiedTime(
                aFile,
                FileTime.fromMillis(Files.getLastModifiedTime(aFile).toMillis() + 5_000),
            )
            index.ensure(inputA, supportA).requireSuccess()

            assertEquals(
                listOf(bFile.toString(), cFile.toString()),
                index.dependentsOf(aFile.toString()).requireSuccess(),
            )
            assertEquals(
                Completeness.STALE,
                index.snapshot(cFile.toString()).requireSuccess()?.completeness,
            )
        }
    }

    @Test
    fun pathlessSameLanguageDocumentsDoNotOverwriteEachOther() {
        val engine = FakeSyntaxEngine()
        val provider = FakeSemanticProvider()
        val index = InMemoryIndex(testPlan())
        val support = FakeLanguageSupport(engine, provider)
        val first = SourceInput.InMemory("first", "test", documentId = "doc-1")
        val second = SourceInput.InMemory("second", "test", documentId = "doc-2")

        val firstSource = index.ensure(first, support).requireSuccess()
        val secondSource = index.ensure(second, support).requireSuccess()

        assertEquals(2, engine.parseCalls)
        assertEquals("memory:test:doc-1", index.keyOf(first).requireSuccess())
        assertEquals("memory:test:doc-2", index.keyOf(second).requireSuccess())
        assertTrue(firstSource.contentHash != secondSource.contentHash)
        assertEquals(firstSource.contentHash, index.snapshotSource(first).requireSuccess()?.contentHash)
        assertEquals(secondSource.contentHash, index.snapshotSource(second).requireSuccess()?.contentHash)
    }

    @Test
    fun pathlessInMemoryWithoutDocumentIdIsRejected() {
        val engine = FakeSyntaxEngine()
        val index = InMemoryIndex(testPlan())
        val support = FakeLanguageSupport(engine, FakeSemanticProvider())

        val missing =
            index
                .ensure(
                    SourceInput.InMemory("text", "test"),
                    support,
                ).requireFailure()
        assertIs<ContractError.InvalidInput>(missing)
        assertEquals("documentId", missing.input)

        val blank =
            index
                .ensure(
                    SourceInput.InMemory("text", "test", documentId = "  "),
                    support,
                ).requireFailure()
        assertIs<ContractError.InvalidInput>(blank)
        assertEquals(0, engine.parseCalls)
    }

    @Test
    fun sourceQueriesRejectMissingPathlessDocumentId() {
        val index = InMemoryIndex(testPlan())
        val invalid = SourceInput.InMemory("text", "test")

        listOf(
            index.snapshotSource(invalid).requireFailure(),
            index.nodeHashesOf(invalid).requireFailure(),
            index.lastChangeOf(invalid).requireFailure(),
            index.keyOf(invalid).requireFailure(),
        ).forEach { error ->
            assertIs<ContractError.InvalidInput>(error)
            assertEquals("documentId", error.input)
        }
    }

    private fun assertValidation(
        error: ContractError,
        field: String,
    ) {
        assertIs<ContractError.ValidationError>(error)
        assertEquals(field, error.field)
    }

    private fun withTempDir(block: (Path) -> Unit) {
        val directory = Files.createTempDirectory("cint-index")
        try {
            block(directory)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}
