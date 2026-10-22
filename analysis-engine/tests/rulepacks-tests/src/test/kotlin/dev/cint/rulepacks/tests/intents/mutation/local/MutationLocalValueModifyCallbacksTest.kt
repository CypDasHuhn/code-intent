package dev.cint.rulepacks.tests.intents.mutation.local

import dev.cint.api.RulePack
import dev.cint.rulepacks.bookkeeping.BookkeepingRulePack
import dev.cint.rulepacks.intents.mutation.DATA_MODIFY_INTENT
import dev.cint.rulepacks.intents.mutation.LOCAL_VALUE_MODIFY_INTENT
import dev.cint.rulepacks.intents.mutation.MutationRulePack
import dev.cint.rulepacks.intents.service.SERVICE_DELEGATION_INTENT
import dev.cint.rulepacks.intents.service.ServiceRulePack
import dev.cint.rulepacks.intents.structural.LocalCopyRulePack
import dev.cint.rulepacks.tests.support.PackAnalysis
import dev.cint.rulepacks.tests.support.analyzePack
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MutationLocalValueModifyCallbacksTest {
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
}

private fun analyze(
    language: String,
    text: String,
    pack: RulePack = MutationRulePack(),
): PackAnalysis = analyzePack(language, text, listOf(BookkeepingRulePack(), pack, LocalCopyRulePack()))
