package dev.cint.rulepacks.tests.intents.mutation

import dev.cint.api.CandidateReason
import dev.cint.api.RulePack
import dev.cint.rulepacks.bookkeeping.BookkeepingRulePack
import dev.cint.rulepacks.intents.mutation.DATA_MODIFY_INTENT
import dev.cint.rulepacks.intents.mutation.LOCAL_VALUE_MODIFY_INTENT
import dev.cint.rulepacks.intents.mutation.LOCAL_VALUE_MODIFY_RULE_ID
import dev.cint.rulepacks.intents.mutation.MutationRulePack
import dev.cint.rulepacks.intents.service.SERVICE_DELEGATION_INTENT
import dev.cint.rulepacks.intents.service.ServiceRulePack
import dev.cint.rulepacks.intents.structural.LocalCopyRulePack
import dev.cint.rulepacks.tests.support.PackAnalysis
import dev.cint.rulepacks.tests.support.analyzePack
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MutationLocalValueModifyRuleTest {
    @Test
    fun assignmentAndIncrementWritesEmitLocalValueModify() {
        val text =
            """
            fun f() {
                var count = 0
                var total = 0
                count = 1
                count++
                total += count
            }
            """.trimIndent()

        val analysis = analyze("kotlin", text)

        assertEquals(
            setOf("count = 1", "count++", "total += count"),
            analysis.texts(LOCAL_VALUE_MODIFY_INTENT).toSet(),
        )
        assertTrue(analysis.source(LOCAL_VALUE_MODIFY_INTENT).all { it.rule == LOCAL_VALUE_MODIFY_RULE_ID })
    }

    @Test
    fun mutableLocalDeclarationsAreOffByDefault() {
        val text =
            """
            fun f() {
                var count = 0
                count = 1
            }
            """.trimIndent()

        val analysis = analyze("kotlin", text)

        assertEquals(setOf("count = 1"), analysis.texts(LOCAL_VALUE_MODIFY_INTENT).toSet())
    }

    @Test
    fun configuredMutableLocalDeclarationsCoverWrittenVarsOnly() {
        val pack =
            MutationRulePack().configure(
                mapOf("kotlin" to mapOf("mutableLocalDeclarations" to true)),
            )
        val text =
            """
            fun f() {
                var count = 0
                var untouched = 1
                val items = mutableListOf<String>()
                var total = 0
                items.add("x")
                count = 1
                total += count
            }
            """.trimIndent()

        val analysis = analyze("kotlin", text, pack)

        assertEquals(
            setOf("var count = 0", "items.add(\"x\")", "count = 1", "var total = 0", "total += count"),
            analysis.texts(LOCAL_VALUE_MODIFY_INTENT).toSet(),
        )
    }

    @Test
    fun configuredMutableLocalDeclarationsSkipPureCopyAndBookkeepingLocals() {
        val mutation =
            MutationRulePack().configure(
                mapOf("kotlin" to mapOf("mutableLocalDeclarations" to true)),
            )
        val localCopy = LocalCopyRulePack().configure(mapOf("kotlin" to mapOf("prefixes" to listOf("store_"))))
        val text =
            """
            fun f(db: Database) {
                @Bookkeeping
                var skipped = 0
                var store_db = db
                store_db = db
                skipped++
            }
            """.trimIndent()

        val analysis = analyzePack("kotlin", text, listOf(BookkeepingRulePack(), mutation, localCopy))

        assertTrue(analysis.source(LOCAL_VALUE_MODIFY_INTENT).isEmpty())
    }

    @Test
    fun explicitForCounterDeclarationsStaySuppressed() {
        val mutation =
            MutationRulePack().configure(
                mapOf("javascript" to mapOf("mutableLocalDeclarations" to true)),
            )
        val text =
            """
            function f() {
                for (var i = 0; i < 3; i++) {
                    consume(i);
                }
            }
            """.trimIndent()

        val analysis = analyze("javascript", text, mutation)

        assertTrue(analysis.source(LOCAL_VALUE_MODIFY_INTENT).isEmpty())
    }

    @Test
    fun configuredMutableLocalDeclarationsWorkAcrossJavascriptTypescriptAndCsharp() {
        val mutation =
            MutationRulePack().configure(
                mapOf(
                    "javascript" to mapOf("mutableLocalDeclarations" to true),
                    "typescript" to mapOf("mutableLocalDeclarations" to true),
                    "csharp" to mapOf("mutableLocalDeclarations" to true),
                ),
            )
        val cases =
            mapOf(
                "javascript" to
                    """
                    function f() {
                        let count = 0;
                        var total = 0;
                        const stable = 0;
                        count = 1;
                        total += count;
                        stable = 1;
                    }
                    """.trimIndent(),
                "typescript" to
                    """
                    function f() {
                        let count: number = 0;
                        var total = 0;
                        const stable = 0;
                        count = 1;
                        total += count;
                        stable = 1;
                    }
                    """.trimIndent(),
                "csharp" to
                    """
                    class C {
                        void F() {
                            var count = 0;
                            int total = 0;
                            const int stable = 0;
                            count = 1;
                            total += count;
                            stable = 1;
                        }
                    }
                    """.trimIndent(),
            )

        for ((language, text) in cases) {
            val texts = analyze(language, text, mutation).texts(LOCAL_VALUE_MODIFY_INTENT)
            assertTrue(texts.any { it.trimStart().startsWith("count") && "= 0" in it }, "$language: $texts")
            assertTrue(texts.any { it.trimStart().startsWith("total") && "= 0" in it }, "$language: $texts")
            assertTrue(
                texts.none { it.contains("const") || it.trimStart().startsWith("stable = 0") },
                "$language: $texts",
            )
        }
    }

    @Test
    fun multiDeclaratorMutationsEmitOnlyTheWrittenDeclarator() {
        val mutation =
            MutationRulePack().configure(
                mapOf(
                    "javascript" to mapOf("mutableLocalDeclarations" to true),
                    "typescript" to mapOf("mutableLocalDeclarations" to true),
                    "csharp" to mapOf("mutableLocalDeclarations" to true),
                ),
            )
        val cases =
            mapOf(
                "javascript" to
                    """
                    function f() {
                        let written = 0, untouched = 0;
                        written = 1;
                    }
                    """.trimIndent(),
                "typescript" to
                    """
                    function f() {
                        let written: number = 0, untouched = 0;
                        written = 1;
                    }
                    """.trimIndent(),
                "csharp" to
                    """
                    class C {
                        void F() {
                            int written = 0, untouched = 0;
                            written = 1;
                        }
                    }
                    """.trimIndent(),
            )

        for ((language, text) in cases) {
            val texts = analyze(language, text, mutation).texts(LOCAL_VALUE_MODIFY_INTENT)
            assertTrue(texts.any { it.contains("written") && "= 0" in it }, "$language: $texts")
            assertTrue(texts.none { it.contains("untouched") }, "$language: $texts")
        }
    }

    @Test
    fun callbackNamesCollidingWithLocalVocabularyStayOutOfLocalMutation() {
        val text =
            """
            fun run(mutate: (String) -> Unit) {
                mutate("x")
            }
            """.trimIndent()

        val analysis =
            analyzePack("kotlin", text, listOf(BookkeepingRulePack(), MutationRulePack(), LocalCopyRulePack(), ServiceRulePack()))

        assertTrue(analysis.source(LOCAL_VALUE_MODIFY_INTENT).isEmpty())
        assertTrue(analysis.candidates(LOCAL_VALUE_MODIFY_INTENT).isEmpty())
        assertEquals(
            setOf("mutate(\"x\")"),
            analysis.candidates(SERVICE_DELEGATION_INTENT).map { text.substring(it.startByte, it.endByte) }.toSet(),
        )
    }

    @Test
    fun durableAndGenericCallbacksCoexistAsDataModifyAndDelegationCandidate() {
        val text =
            """
            fun publish(move: (Path, Path) -> Unit = Files::move, notify: (Path) -> Unit) {
                move(Paths.get("a"), Paths.get("b"))
                notify(Paths.get("a"))
            }
            """.trimIndent()

        val analysis =
            analyzePack("kotlin", text, listOf(BookkeepingRulePack(), MutationRulePack(), LocalCopyRulePack(), ServiceRulePack()))

        assertEquals(
            setOf("move(Paths.get(\"a\"), Paths.get(\"b\"))"),
            analysis.texts(DATA_MODIFY_INTENT).toSet(),
        )
        assertEquals(
            setOf("notify(Paths.get(\"a\"))"),
            analysis.candidates(SERVICE_DELEGATION_INTENT).map { text.substring(it.startByte, it.endByte) }.toSet(),
        )
    }

    @Test
    fun annotatedFunctionCallbackParametersAreParsed() {
        val text =
            """
            @Deprecated("publish")
            fun publish(move: (Path, Path) -> Unit = Files::move) {
                move(Paths.get("a"), Paths.get("b"))
            }
            """.trimIndent()

        val analysis =
            analyzePack("kotlin", text, listOf(BookkeepingRulePack(), MutationRulePack(), LocalCopyRulePack(), ServiceRulePack()))

        assertEquals(
            setOf("move(Paths.get(\"a\"), Paths.get(\"b\"))"),
            analysis.texts(DATA_MODIFY_INTENT).toSet(),
        )
        assertTrue(analysis.candidates(SERVICE_DELEGATION_INTENT).isEmpty())
    }

    @Test
    fun bareReferenceCallbackDefaultsWorkForJavascriptAndInferredTypescript() {
        val cases =
            listOf(
                "javascript" to "function publish(move = Files.move) { move(\"a\", \"b\"); }",
                "typescript" to "function publish(move = fs.rename) { move(\"a\", \"b\"); }",
                "javascript" to "const publish = (move = Files.move) => { move(\"a\", \"b\"); };",
                "typescript" to "const publish = (move = fs.rename) => { move(\"a\", \"b\"); };",
            )

        for ((language, text) in cases) {
            val analysis =
                analyzePack(language, text, listOf(BookkeepingRulePack(), MutationRulePack(), LocalCopyRulePack(), ServiceRulePack()))
            assertEquals(
                setOf("move(\"a\", \"b\")"),
                analysis.texts(DATA_MODIFY_INTENT).toSet(),
                language,
            )
            assertTrue(analysis.candidates(SERVICE_DELEGATION_INTENT).isEmpty(), language)
        }
    }

    @Test
    fun samInterfaceCallbackInvocationsUseTheirDurableDefaults() {
        val cases =
            listOf(
                "fun publish(consumer: Consumer<Path> = Consumer(Files::delete)) { consumer.accept(Paths.get(\"a\")) }",
                "fun publish(runnable: Runnable = Runnable(Files::delete)) { runnable.run() }",
            )

        for (text in cases) {
            val analysis =
                analyzePack("kotlin", text, listOf(BookkeepingRulePack(), MutationRulePack(), LocalCopyRulePack(), ServiceRulePack()))
            assertTrue(analysis.source(DATA_MODIFY_INTENT).isNotEmpty(), text)
            assertTrue(analysis.candidates(SERVICE_DELEGATION_INTENT).isEmpty(), text)
        }
    }

    @Test
    fun samTypesWithFunctionTypeArgumentsUseTheirInterfaceMethod() {
        val cases =
            listOf(
                "fun publish(consumer: Consumer<(Path) -> Unit>) { consumer.accept(noop) }" to
                    "consumer.accept(noop)",
                "fun publish(supplier: Supplier<(Path) -> Unit>) { supplier.get() }" to
                    "supplier.get()",
                "fun publish(predicate: Predicate<(Path) -> Unit>) { predicate.test(noop) }" to
                    "predicate.test(noop)",
                "fun publish(function: Function<Path, (Int) -> String>) { function.apply(path) }" to
                    "function.apply(path)",
            )

        for ((text, expected) in cases) {
            val analysis = analyzePack("kotlin", text, listOf(ServiceRulePack()))
            assertTrue(
                analysis.candidates(SERVICE_DELEGATION_INTENT).any {
                    text.substring(it.startByte, it.endByte) == expected
                },
                "$expected: ${analysis.candidates(SERVICE_DELEGATION_INTENT)}",
            )
        }
    }

    @Test
    fun durableDefaultThroughSamTypeWithFunctionTypeArgumentEmitsDataModify() {
        val text =
            """
            fun publish(consumer: Consumer<(Path) -> Unit> = Consumer { Files.delete(it) }) {
                consumer.accept { }
            }
            """.trimIndent()

        val analysis =
            analyzePack(
                "kotlin",
                text,
                listOf(BookkeepingRulePack(), MutationRulePack(), LocalCopyRulePack(), ServiceRulePack()),
            )

        assertTrue(analysis.source(DATA_MODIFY_INTENT).isNotEmpty())
        assertTrue(analysis.candidates(SERVICE_DELEGATION_INTENT).isEmpty())
    }

    @Test
    fun aliasedFunctionTypesKeepDurableCallbackDefaults() {
        val cases =
            listOf(
                Triple(
                    "kotlin",
                    """
                    typealias Move = (Path, Path) -> Unit
                    fun publish(move: Move = Files::move) {
                        move(Paths.get("a"), Paths.get("b"))
                    }
                    """.trimIndent(),
                    "move(Paths.get(\"a\"), Paths.get(\"b\"))",
                ),
                Triple(
                    "typescript",
                    """
                    type Move = (a: string, b: string) => void;
                    function publish(move: Move = fs.rename) {
                        move("a", "b");
                    }
                    """.trimIndent(),
                    "move(\"a\", \"b\")",
                ),
                Triple(
                    "csharp",
                    """
                    class C {
                        delegate void MoveHandler(string a, string b);
                        void Publish(MoveHandler move = File.Move) {
                            move("a", "b");
                        }
                    }
                    """.trimIndent(),
                    "move(\"a\", \"b\")",
                ),
            )

        for ((language, text, expected) in cases) {
            val analysis =
                analyzePack(
                    language,
                    text,
                    listOf(BookkeepingRulePack(), MutationRulePack(), LocalCopyRulePack(), ServiceRulePack()),
                )
            assertEquals(setOf(expected), analysis.texts(DATA_MODIFY_INTENT).toSet(), language)
            assertTrue(analysis.candidates(SERVICE_DELEGATION_INTENT).isEmpty(), language)
        }
    }

    @Test
    fun samReceiverMappingRequiresSamInterfaceEvidence() {
        val text =
            """
            fun publish(callback: (Path) -> Unit = Files::delete) {
                callback.apply { }
                callback.run { }
            }

            fun configure(config: Config = Config.DEFAULT) {
                config.apply { }
            }
            """.trimIndent()

        val analysis =
            analyzePack(
                "kotlin",
                text,
                listOf(BookkeepingRulePack(), MutationRulePack(), LocalCopyRulePack(), ServiceRulePack()),
            )

        assertTrue(analysis.source(DATA_MODIFY_INTENT).isEmpty())
        assertTrue(analysis.candidates(SERVICE_DELEGATION_INTENT).isEmpty())
    }

    @Test
    fun kotlinFunctionTypeInvokeUsesItsDurableDefault() {
        val text =
            """
            fun publish(callback: (Path) -> Unit = Files::delete) {
                callback.invoke(Paths.get("a"))
            }
            """.trimIndent()

        val analysis =
            analyzePack(
                "kotlin",
                text,
                listOf(BookkeepingRulePack(), MutationRulePack(), LocalCopyRulePack(), ServiceRulePack()),
            )

        assertEquals(setOf("callback.invoke(Paths.get(\"a\"))"), analysis.texts(DATA_MODIFY_INTENT).toSet())
        assertTrue(analysis.candidates(SERVICE_DELEGATION_INTENT).isEmpty())
    }

    @Test
    fun configuredDurableCallbackTargetsAreSharedWithServiceClassification() {
        val mutation =
            MutationRulePack().configure(
                mapOf("kotlin" to mapOf("persistencePaths" to listOf("Archive.move"))),
            )
        val text =
            """
            fun publish(move: (Path, Path) -> Unit = Archive::move) {
                move(Paths.get("a"), Paths.get("b"))
            }
            """.trimIndent()

        val analysis = analyzePack("kotlin", text, listOf(BookkeepingRulePack(), mutation, LocalCopyRulePack(), ServiceRulePack()))

        assertEquals(setOf("move(Paths.get(\"a\"), Paths.get(\"b\"))"), analysis.texts(DATA_MODIFY_INTENT).toSet())
        assertTrue(analysis.candidates(SERVICE_DELEGATION_INTENT).isEmpty())
    }

    @Test
    fun removingADurableCallbackTargetFallsBackToDelegationCandidate() {
        val mutation =
            MutationRulePack().configure(
                mapOf(
                    "kotlin" to
                        mapOf(
                            "persistencePaths" to emptyList<String>(),
                            "persistenceCalls" to emptyList<String>(),
                        ),
                ),
            )
        val text =
            """
            fun publish(move: (Path, Path) -> Unit = Files::move) {
                move(Paths.get("a"), Paths.get("b"))
            }
            """.trimIndent()

        val analysis = analyzePack("kotlin", text, listOf(BookkeepingRulePack(), mutation, LocalCopyRulePack(), ServiceRulePack()))

        assertTrue(analysis.source(DATA_MODIFY_INTENT).isEmpty())
        assertEquals(
            setOf("move(Paths.get(\"a\"), Paths.get(\"b\"))"),
            analysis.candidates(SERVICE_DELEGATION_INTENT).map { text.substring(it.startByte, it.endByte) }.toSet(),
        )
    }

    @Test
    fun durableCallbackDefaultEmitsDataModifyOnly() {
        val text =
            """
            fun publish(stage: (String) -> String, move: (Path, Path) -> Unit = Files::move) {
                var jsonTemp = Paths.get("out.json")
                jsonTemp = Paths.get(stage("json"))
                move(jsonTemp, Paths.get("neighbours.json"))
            }
            """.trimIndent()

        val analysis = analyze("kotlin", text)

        assertEquals(
            setOf("move(jsonTemp, Paths.get(\"neighbours.json\"))"),
            analysis.texts(DATA_MODIFY_INTENT).toSet(),
        )
        assertEquals(
            setOf("jsonTemp = Paths.get(stage(\"json\"))"),
            analysis.texts(LOCAL_VALUE_MODIFY_INTENT).toSet(),
        )
        assertTrue(analysis.candidates(LOCAL_VALUE_MODIFY_INTENT).isEmpty())
    }

    @Test
    fun neighboursPublishFixtureTagsWritesAndDurableCallback() {
        val text =
            """
            class NeighboursCli {
                fun publish(
                    stage: (String) -> String,
                    move: (Path, Path) -> Unit = Files::move,
                    notify: (Path) -> Unit,
                ) {
                    var jsonTemp = Paths.get("neighbours.json.tmp")
                    var htmlTemp = Paths.get("neighbours.html.tmp")
                    jsonTemp = Paths.get(stage("json"))
                    htmlTemp = Paths.get(stage("html"))
                    move(jsonTemp, Paths.get("neighbours.json"))
                    move(htmlTemp, Paths.get("neighbours.html"))
                    notify(Paths.get("neighbours.json"))
                }
            }
            """.trimIndent()

        val analysis = analyze("kotlin", text)

        assertEquals(
            setOf(
                "jsonTemp = Paths.get(stage(\"json\"))",
                "htmlTemp = Paths.get(stage(\"html\"))",
            ),
            analysis.texts(LOCAL_VALUE_MODIFY_INTENT).toSet(),
        )
        assertEquals(
            setOf(
                "move(jsonTemp, Paths.get(\"neighbours.json\"))",
                "move(htmlTemp, Paths.get(\"neighbours.html\"))",
            ),
            analysis.texts(DATA_MODIFY_INTENT).toSet(),
        )
    }

    @Test
    fun configuredMutableCollectionOperationsEmitLocalValueModify() {
        val text =
            """
            fun f() {
                val items = mutableListOf<String>()
                items.add("x")
                val values = mutableMapOf<String, Int>()
                values.put("x", 1)
            }
            """.trimIndent()

        val analysis = analyze("kotlin", text)

        assertEquals(
            setOf("items.add(\"x\")", "values.put(\"x\", 1)"),
            analysis.texts(LOCAL_VALUE_MODIFY_INTENT).toSet(),
        )
    }

    @Test
    fun whileLoopCountersAreLocalValueModify() {
        val text =
            """
            fun f() {
                var i = 0
                var total = 0
                while (i < 3) {
                    i++
                    total += i
                }
            }
            """.trimIndent()

        val analysis = analyze("kotlin", text)

        assertEquals(setOf("i++", "total += i"), analysis.texts(LOCAL_VALUE_MODIFY_INTENT).toSet())
    }

    @Test
    fun externalAndMemberAssignmentsStayCandidates() {
        val text =
            """
            fun f(user: User) {
                var count = 0
                count = 1
                user.name = "x"
            }
            """.trimIndent()

        val analysis = analyze("kotlin", text)

        assertEquals(setOf("count = 1"), analysis.texts(LOCAL_VALUE_MODIFY_INTENT).toSet())
        assertEquals(
            setOf("user.name = \"x\""),
            analysis
                .candidates(LOCAL_VALUE_MODIFY_INTENT)
                .map {
                    text.substring(it.startByte, it.endByte)
                }.toSet(),
        )
    }

    @Test
    fun untypedDomainMutationNamesStayCandidates() {
        val text =
            """
            fun f() {
                handler.add("x")
            }
            """.trimIndent()

        val analysis = analyze("kotlin", text)

        assertTrue(analysis.source(LOCAL_VALUE_MODIFY_INTENT).isEmpty())
        assertEquals(1, analysis.candidates(LOCAL_VALUE_MODIFY_INTENT).size)
    }

    @Test
    fun ambiguousMethodMutationsYieldCandidates() {
        val text =
            """
            fun f() {
                handler.mutate()
                handler.updateProfile()
            }
            """.trimIndent()

        val analysis = analyze("kotlin", text)
        val candidates = analysis.candidates(LOCAL_VALUE_MODIFY_INTENT)

        assertEquals(2, candidates.size)
        assertTrue(candidates.all { it.reason == CandidateReason.PACK_DOMAIN })
        assertTrue(candidates.all { it.source == "rule:$LOCAL_VALUE_MODIFY_RULE_ID" })
        assertTrue(candidates.all { it.aiEligible })
        assertTrue(analysis.source(LOCAL_VALUE_MODIFY_INTENT).isEmpty())
    }

    @Test
    fun lowSignalPrefixesDoNotBecomeLocalCandidates() {
        val text =
            """
            fun f() {
                setup()
                setVisible(true)
                addEventListener("click", handler)
                applyPlugin(plugin)
            }
            """.trimIndent()

        val analysis = analyze("kotlin", text)

        assertTrue(analysis.candidates(LOCAL_VALUE_MODIFY_INTENT).isEmpty())
    }

    @Test
    fun customMutableOperationsAreEstablishedWithAReceiverProof() {
        val pack =
            MutationRulePack().configure(
                mapOf(
                    "kotlin" to
                        mapOf(
                            "mutableOperations" to listOf("frobnicate"),
                            "mutableReceiverNames" to listOf("widget"),
                        ),
                ),
            )
        val text =
            """
            fun f() {
                widget.frobnicate()
            }
            """.trimIndent()

        val analysis = analyze("kotlin", text, pack)

        assertEquals(setOf("widget.frobnicate()"), analysis.texts(LOCAL_VALUE_MODIFY_INTENT).toSet())
    }

    @Test
    fun incrementPatternAcceptsOnlyIncrementWrites() {
        val pack =
            MutationRulePack().configure(
                mapOf("kotlin" to mapOf("assignmentPatterns" to listOf("increment"))),
            )
        val text =
            """
            fun f() {
                var count = 0
                count++
                count--
            }
            """.trimIndent()

        assertEquals(setOf("count++"), analyze("kotlin", text, pack).texts(LOCAL_VALUE_MODIFY_INTENT).toSet())
    }

    @Test
    fun decrementPatternAcceptsOnlyDecrementWrites() {
        val pack =
            MutationRulePack().configure(
                mapOf("kotlin" to mapOf("assignmentPatterns" to listOf("decrement"))),
            )
        val text =
            """
            fun f() {
                var count = 0
                count++
                count--
            }
            """.trimIndent()

        assertEquals(setOf("count--"), analyze("kotlin", text, pack).texts(LOCAL_VALUE_MODIFY_INTENT).toSet())
    }

    @Test
    fun persistenceWinsOverMutableCollectionOperations() {
        val text =
            """
            fun f(item: Item, items: MutableList<Item>) {
                repository.delete(item)
                items.delete(item)
            }
            """.trimIndent()

        val analysis = analyze("kotlin", text)

        assertEquals(setOf("repository.delete(item)"), analysis.texts("DATA_MODIFY").toSet())
        assertEquals(setOf("items.delete(item)"), analysis.texts(LOCAL_VALUE_MODIFY_INTENT).toSet())
    }

    @Test
    fun whileLoopPrefixCountersAreLocalValueModify() {
        val kotlinText =
            """
            fun f() {
                var i = 3
                var total = 0
                while (i > 0) {
                    --i
                    ++i
                    total += i
                }
            }
            """.trimIndent()
        val kotlin = analyze("kotlin", kotlinText)

        assertEquals(setOf("--i", "++i", "total += i"), kotlin.texts(LOCAL_VALUE_MODIFY_INTENT).toSet())

        val csharpText =
            """
            class C {
                void F() {
                    int i = 0;
                    int total = 0;
                    while (i < 3) {
                        ++i;
                        total += i;
                    }
                }
            }
            """.trimIndent()
        val csharp = analyze("csharp", csharpText)

        assertEquals(setOf("++i", "total += i"), csharp.texts(LOCAL_VALUE_MODIFY_INTENT).toSet())
    }

    @Test
    fun csharpLocalWrappersYieldCandidates() {
        val text =
            """
            class C {
                void F() {
                    handler.UpdateProfile();
                    handler.Mutate();
                    handler.MergeState();
                }
            }
            """.trimIndent()

        val analysis = analyze("csharp", text)
        val candidates = analysis.candidates(LOCAL_VALUE_MODIFY_INTENT)

        assertEquals(3, candidates.size)
        assertTrue(analysis.source(LOCAL_VALUE_MODIFY_INTENT).isEmpty())
    }

    private fun analyze(
        language: String,
        text: String,
        pack: RulePack = MutationRulePack(),
    ): PackAnalysis = analyzePack(language, text, listOf(BookkeepingRulePack(), pack, LocalCopyRulePack()))
}
