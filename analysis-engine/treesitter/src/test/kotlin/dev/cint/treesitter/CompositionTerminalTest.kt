package dev.cint.treesitter

import dev.cint.api.Annotation
import dev.cint.api.CINT_API_VERSION
import dev.cint.api.Emit
import dev.cint.api.Intent
import dev.cint.api.Role
import dev.cint.api.Rule
import dev.cint.api.RulePack
import dev.cint.api.Span
import dev.cint.api.UNTAGGED_INTENT
import dev.cint.api.View
import dev.cint.core.CoreContributors
import dev.cint.core.config.IntentConfig
import dev.cint.core.model.SourceFile
import dev.cint.core.oracle.NullOracle
import dev.cint.core.scheduler.ReservedContributorId
import dev.cint.core.scheduler.RuleEngine
import dev.cint.core.syntax.ProfileSyntaxModel
import dev.cint.core.syntax.SyntaxProfiles
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import java.io.File

private class ReplayRule(
    override val id: String,
    override val owns: Intent,
    override val reads: Set<Intent> = emptySet(),
    private val emissions: List<Emit>,
) : Rule {
    override fun run(view: View): List<Emit> = emissions
}

private class ReplayPack(
    override val id: String,
    private val entries: List<Rule>,
) : RulePack {
    override val languages: Set<String> = setOf("kotlin")
    override val apiVersion: Int = CINT_API_VERSION

    override fun rules(): List<Rule> = entries
}

private data class Snapshot(
    val role: Role,
    val intents: Set<String>,
    val evidence: String,
    val span: Span,
    val source: String,
    val confidence: Double,
    val startByte: Int,
    val endByte: Int,
)

private fun snapshot(annotation: Annotation): Snapshot =
    Snapshot(
        role = annotation.role,
        intents = annotation.intents,
        evidence = annotation.evidence,
        span = annotation.span,
        source = annotation.source,
        confidence = annotation.confidence,
        startByte = annotation.startByte,
        endByte = annotation.endByte,
    )

private fun compositionSnapshots(annotations: List<Annotation>): List<Snapshot> =
    annotations
        .filter { annotation -> annotation.role == Role.SCAFFOLD || annotation.role == Role.DERIVATIVE_USE }
        .map(::snapshot)
        .sortedWith(compareBy({ it.startByte }, { it.endByte }, { it.role.ordinal }, { it.intents.joinToString() }))

private fun replaySource(
    text: String,
    idPrefix: String = "source",
): List<Rule> {
    val source = SourceFile("Sample.kt", text)
    val tree = KotlinParser().parse(source)
    val annotations =
        RuleEngine().analyze(source, tree, KotlinSyntaxModel, NullOracle, testConfig("kotlin"), testPacks("kotlin"))
    val emissions = linkedMapOf<String, MutableList<Emit>>()
    for (annotation in annotations) {
        if (annotation.role != Role.SOURCE_INTENT) continue
        for (intent in annotation.intents) {
            if (intent == UNTAGGED_INTENT) continue
            emissions.getOrPut(intent) { mutableListOf() } +=
                Emit(
                    intents = setOf(intent),
                    evidence = annotation.evidence,
                    span = annotation.span,
                    source = annotation.source,
                    confidence = annotation.confidence,
                    startByte = annotation.startByte,
                    endByte = annotation.endByte,
                )
        }
    }
    return emissions
        .toSortedMap()
        .map { (intent, values) -> ReplayRule("$idPrefix:$intent", intent, emissions = values) }
}

private fun fixture(
    name: String,
    language: String = "kotlin",
): String {
    val candidates =
        listOf(
            File("../../testing-grounds/$language/$name"),
            File("../testing-grounds/$language/$name"),
            File("testing-grounds/$language/$name"),
        )
    return candidates.first { it.exists() }.readText()
}

class CompositionTerminalTest {
    private val config = testConfig("kotlin")

    private fun newPipeline(
        text: String,
        packs: List<RulePack>,
        path: String = "Sample.kt",
    ): List<Annotation> {
        val source = SourceFile(path, text)
        val tree = KotlinParser().parse(source)
        return RuleEngine().analyze(source, tree, KotlinSyntaxModel, NullOracle, config, packs)
    }

    @Test
    fun compositionIgnoresProducerRuleAndPackIdentity() {
        val text = fixture("Scaffolds.kt")
        val first =
            newPipeline(
                text,
                listOf(ReplayPack("first", replaySource(text, "first"))),
                "Scaffolds.kt",
            )
        val second =
            newPipeline(
                text,
                listOf(ReplayPack("second", replaySource(text, "another"))),
                "Scaffolds.kt",
            )

        assertEquals(compositionSnapshots(first), compositionSnapshots(second))
    }

    @Test
    fun addingAPackOnlyAddsItsSourceIntentsToComposition() {
        val text =
            """
            private val log = LoggerFactory.getLogger(Foo::class.java)
            fun a() {
                log.warn("a")
            }
            fun b() {
                throw IllegalStateException("b")
            }
            """.trimIndent()
        val source = SourceFile("Sample.kt", text)
        val tree = KotlinParser().parse(source)
        val call =
            KotlinSyntaxModel.calls(tree).first { it.callee == "warn" }.site
        val thrown = tree.root.descendants().first { KotlinSyntaxModel.isThrow(it) }
        val logging =
            ReplayRule(
                id = "pack-a:logging",
                owns = "LOGGING",
                emissions =
                    listOf(
                        Emit(
                            intents = setOf("LOGGING"),
                            evidence = call.text,
                            span = call.span(),
                            source = "treesitter",
                            confidence = 1.0,
                            startByte = call.startByte,
                            endByte = call.endByte,
                        ),
                    ),
            )
        val error =
            ReplayRule(
                id = "pack-b:error",
                owns = "ERROR_HANDLING",
                emissions =
                    listOf(
                        Emit(
                            intents = setOf("ERROR_HANDLING"),
                            evidence = thrown.text,
                            span = thrown.span(),
                            source = "treesitter",
                            confidence = 1.0,
                            startByte = thrown.startByte,
                            endByte = thrown.endByte,
                        ),
                    ),
            )

        fun run(packs: List<RulePack>): List<Snapshot> =
            compositionSnapshots(
                RuleEngine().analyze(source, tree, KotlinSyntaxModel, NullOracle, config, packs),
            )

        val loggingOnly = run(listOf(ReplayPack("a", listOf(logging))))
        val errorOnly = run(listOf(ReplayPack("b", listOf(error))))
        val both =
            run(
                listOf(
                    ReplayPack("b", listOf(error)),
                    ReplayPack("a", listOf(logging)),
                ),
            )
        val expected =
            (loggingOnly + errorOnly)
                .distinct()
                .sortedWith(
                    compareBy({ it.startByte }, { it.endByte }, { it.role.ordinal }, { it.intents.joinToString() }),
                )
        val withoutB = run(listOf(ReplayPack("a", listOf(logging))))
        val noOp = ReplayRule("pack-c:noop", "NOOP", emissions = emptyList())
        val withNoOp = run(listOf(ReplayPack("a", listOf(logging)), ReplayPack("c", listOf(noOp))))

        assertEquals(expected, both)
        assertEquals(loggingOnly, withoutB)
        assertEquals(loggingOnly, withNoOp)
        assertTrue(errorOnly.any { it.intents == setOf("ERROR_HANDLING") })
    }

    @Test
    fun compositionOutputIsCoreOwnedScaffoldOrDerivative() {
        val text =
            """
            private val log = LoggerFactory.getLogger(Foo::class.java)
            fun f() {
                if (1 == 1) {
                    log.warn("x")
                } else {
                    work()
                }
                try {
                    work()
                } catch (e: Exception) {
                    log.warn("failed", e)
                }
            }
            fun work() {}
            """.trimIndent()
        val annotations = newPipeline(text, listOf(ReplayPack("source", replaySource(text))))
        val composed = annotations.filter { it.role == Role.SCAFFOLD || it.role == Role.DERIVATIVE_USE }

        val coreOnly =
            setOf(
                CoreContributors.RETURN_CATEGORY,
                CoreContributors.DERIVATIVE_USE,
                CoreContributors.SCAFFOLD,
            )

        assertTrue(composed.isNotEmpty())
        assertTrue(composed.all { it.provenance.all { contribution -> contribution.rule in coreOnly } })
        assertTrue(composed.all { it.role == Role.SCAFFOLD || it.role == Role.DERIVATIVE_USE })
        assertTrue(annotations.none { it.role == Role.SOURCE_INTENT && it.rule in coreOnly })
    }

    @Test
    fun pluginRuleIdCannotUseTheCompositionNamespace() {
        val text = "fun f() { println(\"x\") }"
        val rule =
            ReplayRule(
                id = CoreContributors.SCAFFOLD,
                owns = "LOGGING",
                emissions = emptyList(),
            )

        val error =
            assertFailsWith<ReservedContributorId> {
                newPipeline(text, listOf(ReplayPack("plugin", listOf(rule))))
            }

        assertEquals(CoreContributors.SCAFFOLD, error.rule.id)
    }

    @Test
    fun packOrderAndSplitDoNotChangeComposition() {
        val text = fixture("Catches.kt")
        val rules = replaySource(text)
        val onePack = newPipeline(text, listOf(ReplayPack("all", rules)), "Catches.kt")
        val split = rules.chunked(2)
        val reversed =
            newPipeline(
                text,
                split.mapIndexed { index, chunk -> ReplayPack("p$index", chunk) }.reversed(),
                "Catches.kt",
            )

        assertEquals(compositionSnapshots(onePack), compositionSnapshots(reversed))
    }

    @Test
    fun returnCategoryIsDerivedFromTheCfgAndValidationSourceIntents() {
        val text =
            """
            fun choose(value: Int): Int {
                if (value < 0) {
                    return 0
                } else {
                    return 1
                }
            }
            """.trimIndent()
        val source = SourceFile("Sample.kt", text)
        val tree = KotlinParser().parse(source)
        val validation =
            ReplayRule(
                id = "test:validation",
                owns = "VALIDATION",
                emissions =
                    listOf(
                        Emit(
                            intents = setOf("VALIDATION"),
                            evidence = "value < 0",
                            span = Span(1, 7, 1, 16),
                            source = "treesitter",
                            confidence = 1.0,
                            startByte = text.indexOf("value < 0"),
                            endByte = text.indexOf("value < 0") + "value < 0".length,
                        ),
                    ),
            )

        val annotations =
            RuleEngine().analyze(
                source,
                tree,
                KotlinSyntaxModel,
                NullOracle,
                config,
                listOf(ReplayPack("test", listOf(validation))),
            )

        val returns = annotations.filter { it.rule == CoreContributors.RETURN_CATEGORY }
        val validationReturn = returns.first { "VALIDATION" in it.intents }
        val untaggedReturn = returns.first { UNTAGGED_INTENT in it.intents }
        assertTrue(returns.all { it.rule == CoreContributors.RETURN_CATEGORY })
        assertEquals(Role.SCAFFOLD, validationReturn.role)
        assertEquals(setOf("VALIDATION"), validationReturn.intents)
        assertEquals(setOf(UNTAGGED_INTENT), untaggedReturn.intents)
        assertTrue(validationReturn.evidence.contains("return 0"))
        assertTrue(untaggedReturn.evidence.contains("return 1"))
    }

    @Test
    fun returnCategoryCoversRejectionSentinelsOnFalseEdgesAndFinalReturns() {
        val text =
            """
            fun chooseNull(ok: Boolean): String? {
                if (!ok) {
                    work()
                } else {
                    return null
                }
            }
            fun chooseBare(ok: Boolean) {
                if (!ok) {
                    work()
                } else {
                    return
                }
            }
            fun finalValue(value: Int): Int {
                return value
            }
            fun work() {}
            """.trimIndent()
        val source = SourceFile("Sample.kt", text)
        val tree = KotlinParser().parse(source)
        val conditions = KotlinSyntaxModel.ifs(tree).mapNotNull { it.condition }.toList()
        val validation =
            ReplayRule(
                id = "test:validation",
                owns = "VALIDATION",
                emissions =
                    conditions.map { condition ->
                        Emit(
                            intents = setOf("VALIDATION"),
                            evidence = condition.text,
                            span = condition.span(),
                            source = "treesitter",
                            confidence = 1.0,
                            startByte = condition.startByte,
                            endByte = condition.endByte,
                        )
                    },
            )

        val annotations =
            RuleEngine().analyze(
                source,
                tree,
                KotlinSyntaxModel,
                NullOracle,
                config,
                listOf(ReplayPack("test", listOf(validation))),
            )
        val returns = annotations.filter { it.rule == CoreContributors.RETURN_CATEGORY }

        val nullReturn = returns.first { it.evidence.contains("return null") }
        val bareReturn = returns.first { it.evidence.trim() == "return" }
        val finalReturn = returns.first { it.evidence.contains("return value") }

        assertEquals(setOf("VALIDATION"), nullReturn.intents)
        assertEquals(setOf("VALIDATION"), bareReturn.intents)
        assertEquals(setOf(UNTAGGED_INTENT), finalReturn.intents)
        assertTrue(
            annotations.none {
                it.rule == CoreContributors.SCAFFOLD && it.evidence.contains("fun finalValue")
            },
        )
    }

    @Test
    fun returnScaffoldsTreatBareReturnsAsNeutralAndCarryExpressionIntents() {
        val text =
            """
            fun bare(value: String?) {
                requireNotNull(value)
                return
            }
            fun expression(value: String?) {
                return requireNotNull(value)
            }
            """.trimIndent()
        val source = SourceFile("Sample.kt", text)
        val tree = KotlinParser().parse(source)
        val calls = KotlinSyntaxModel.calls(tree).filter { it.callee == "requireNotNull" }.toList()
        val errorHandling =
            ReplayRule(
                id = "test:error",
                owns = "ERROR_HANDLING",
                emissions =
                    calls.map { call ->
                        Emit(
                            intents = setOf("ERROR_HANDLING"),
                            evidence = call.site.text,
                            span = call.site.span(),
                            source = "treesitter",
                            confidence = 1.0,
                            startByte = call.site.startByte,
                            endByte = call.site.endByte,
                        )
                    },
            )

        val annotations =
            RuleEngine().analyze(
                source,
                tree,
                KotlinSyntaxModel,
                NullOracle,
                config,
                listOf(ReplayPack("test", listOf(errorHandling))),
            )
        val functions = annotations.filter { it.rule == CoreContributors.SCAFFOLD }

        val bare = functions.first { it.evidence.contains("fun bare") }
        val expression = functions.first { it.evidence.contains("fun expression") }
        assertEquals(setOf("ERROR_HANDLING"), bare.intents)
        assertEquals(setOf("ERROR_HANDLING"), expression.intents)
    }

    @Test
    fun newPipelinePromotesAnnotatedPureFunctionsAndTransitiveDeclarations() {
        val text =
            """
            private val log = LoggerFactory.getLogger(Foo::class.java)
            @Pure
            fun make(): String = "x"
            fun f() {
                val source = make()
                val message = source
                log.warn(message)
            }
            """.trimIndent()
        val annotations =
            newPipeline(text, listOf(ReplayPack("source", replaySource(text))))

        val derivatives = annotations.filter { it.role == Role.DERIVATIVE_USE }
        assertEquals(3, derivatives.size)
        assertTrue(derivatives.all { it.intents == setOf("LOGGING") })
        assertTrue(derivatives.any { it.evidence.contains("fun make") })
        assertTrue(derivatives.any { it.evidence.contains("val source") })
        assertTrue(derivatives.any { it.evidence.contains("val message") })
    }

    @Test
    fun newPipelineDoesNotPromoteImpureLocalsOrFunctions() {
        val text =
            """
            private val log = LoggerFactory.getLogger(Foo::class.java)
            fun make(): String = "x"
            fun f() {
                val source = make()
                val message = source
                log.warn(message)
                log.warn(source)
            }
            """.trimIndent()
        val annotations =
            newPipeline(text, listOf(ReplayPack("source", replaySource(text))))

        val derivatives = annotations.filter { it.role == Role.DERIVATIVE_USE }
        assertTrue(derivatives.any { it.evidence.contains("val message") })
        assertTrue(derivatives.none { it.evidence.contains("val source") })
        assertTrue(derivatives.none { it.evidence.contains("fun make") })
    }

    @Test
    fun newPipelineRequiresFunctionReferencesToStayInsideDerivativeUse() {
        val text =
            """
            private val log = LoggerFactory.getLogger(Foo::class.java)
            @Pure
            fun text(): String = "x"
            fun f() {
                val message = text()
                log.warn(message)
                log.warn(text())
            }
            """.trimIndent()
        val annotations =
            newPipeline(text, listOf(ReplayPack("source", replaySource(text))))

        val derivatives = annotations.filter { it.role == Role.DERIVATIVE_USE }
        assertTrue(derivatives.any { it.evidence.contains("val message") })
        assertTrue(derivatives.none { it.evidence.contains("fun text") })
    }

    @Test
    fun derivativeUseResolvesTheSmallestContainingAnnotation() {
        val text =
            """
            fun f() {
                val message = "x"
                log.warn(message)
            }
            """.trimIndent()
        val source = SourceFile("Sample.kt", text)
        val tree = KotlinParser().parse(source)
        val function = KotlinSyntaxModel.functions(tree).first().node
        val call = KotlinSyntaxModel.calls(tree).first { it.callee == "warn" }.site
        val logging =
            ReplayRule(
                id = "test:logging",
                owns = "LOGGING",
                emissions =
                    listOf(
                        Emit(
                            intents = setOf("LOGGING"),
                            evidence = function.text,
                            span = function.span(),
                            source = "treesitter",
                            confidence = 1.0,
                            startByte = function.startByte,
                            endByte = function.endByte,
                        ),
                    ),
            )
        val errorHandling =
            ReplayRule(
                id = "test:error",
                owns = "ERROR_HANDLING",
                emissions =
                    listOf(
                        Emit(
                            intents = setOf("ERROR_HANDLING"),
                            evidence = call.text,
                            span = call.span(),
                            source = "treesitter",
                            confidence = 1.0,
                            startByte = call.startByte,
                            endByte = call.endByte,
                        ),
                    ),
            )

        val annotations =
            RuleEngine().analyze(
                source,
                tree,
                KotlinSyntaxModel,
                NullOracle,
                config,
                listOf(ReplayPack("test", listOf(logging, errorHandling))),
            )
        val derivative =
            annotations.first {
                it.role == Role.DERIVATIVE_USE && it.evidence.contains("val message")
            }

        assertEquals(setOf("ERROR_HANDLING"), derivative.intents)
    }

    @Test
    fun noSourceIntentsStillClassifiesReturnsWithoutScaffoldsOrDerivatives() {
        val text = "fun f(): Int { return 1 }"
        val source = SourceFile("Sample.kt", text)
        val tree = KotlinParser().parse(source)
        val annotations =
            RuleEngine().analyze(
                source,
                tree,
                KotlinSyntaxModel,
                NullOracle,
                IntentConfig("kotlin", emptyMap(), emptyList()),
                emptyList(),
            )

        assertEquals(1, annotations.size)
        assertEquals(Role.SCAFFOLD, annotations.single().role)
        assertEquals(setOf(UNTAGGED_INTENT), annotations.single().intents)
    }
}
