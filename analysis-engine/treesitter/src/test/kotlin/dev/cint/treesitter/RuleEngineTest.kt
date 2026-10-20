package dev.cint.treesitter

import dev.cint.api.Emit
import dev.cint.api.Intent
import dev.cint.api.Rule
import dev.cint.api.RulePack
import dev.cint.api.Span
import dev.cint.api.SyntaxNode
import dev.cint.api.View
import dev.cint.core.config.IntentConfig
import dev.cint.core.model.SourceFile
import dev.cint.core.oracle.NullOracle
import dev.cint.core.scheduler.NonConvergentRule
import dev.cint.core.scheduler.NonMonotoneRule
import dev.cint.core.scheduler.RuleEngine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private class TestRule(
    override val id: String,
    override val owns: Intent,
    override val reads: Set<Intent> = emptySet(),
    private val body: (View) -> List<Emit>,
) : Rule {
    var runs: Int = 0
        private set

    override fun run(view: View): List<Emit> {
        runs++
        return body(view)
    }
}

private class TestPack(
    override val id: String,
    private val entries: List<Rule>,
) : RulePack {
    override val languages: Set<String> = setOf("kotlin")

    override fun rules(): List<Rule> = entries
}

private fun emitAt(
    intent: Intent,
    node: SyntaxNode,
): Emit =
    Emit(
        intents = setOf(intent),
        evidence = "test",
        span = node.span(),
        source = "treesitter",
        confidence = 1.0,
        startByte = node.startByte,
        endByte = node.endByte,
    )

private fun indexedEmit(
    intent: Intent,
    index: Int,
): Emit =
    Emit(
        intents = setOf(intent),
        evidence = "test",
        span = Span(index, 0, index, 1),
        source = "treesitter",
        confidence = 1.0,
        startByte = index * 10,
        endByte = index * 10 + 1,
    )

private fun zeroLengthEmit(
    intent: Intent,
    byte: Int,
    evidence: String = "test",
): Emit =
    Emit(
        intents = setOf(intent),
        evidence = evidence,
        span = Span(0, byte, 0, byte),
        source = "treesitter",
        confidence = 1.0,
        startByte = byte,
        endByte = byte,
    )

private fun emitWithIntents(
    intents: Set<Intent>,
    index: Int,
): Emit =
    Emit(
        intents = intents,
        evidence = "test",
        span = Span(index, 0, index, 1),
        source = "treesitter",
        confidence = 1.0,
        startByte = index * 10,
        endByte = index * 10 + 1,
    )

class RuleEngineTest {
    private val config = IntentConfig("kotlin", emptyMap(), emptyList())

    @Test
    fun selfReadingRuleConvergesUnderInternalFixpoint() {
        val source = SourceFile("Sample.kt", "fun f() {}")
        val tree = KotlinParser().parse(source)
        val chain =
            TestRule("test:chain", "CHAIN", setOf("CHAIN")) { view ->
                val count = (view.annotations("CHAIN").size + 1).coerceAtMost(3)
                (0 until count).map { indexedEmit("CHAIN", it) }
            }

        val annotations =
            RuleEngine().analyze(
                source,
                tree,
                KotlinSyntaxModel,
                NullOracle,
                config,
                listOf(TestPack("test", listOf(chain))),
            )

        assertEquals(4, chain.runs)
        assertEquals(3, annotations.count { "CHAIN" in it.intents })
    }

    @Test
    fun emptyIntentEmitFails() {
        val source = SourceFile("Sample.kt", "fun f() {}")
        val tree = KotlinParser().parse(source)
        val rule =
            TestRule("test:empty", "EMPTY") {
                listOf(emitWithIntents(emptySet(), 0))
            }

        val error =
            assertFailsWith<IllegalArgumentException> {
                RuleEngine().analyze(
                    source,
                    tree,
                    KotlinSyntaxModel,
                    NullOracle,
                    config,
                    listOf(TestPack("test", listOf(rule))),
                )
            }

        assertTrue(error.message.orEmpty().contains("test:empty"))
        assertTrue(error.message.orEmpty().contains("emitted intents"))
    }

    @Test
    fun foreignIntentEmitFails() {
        val source = SourceFile("Sample.kt", "fun f() {}")
        val tree = KotlinParser().parse(source)
        val rule =
            TestRule("test:foreign", "FOREIGN") {
                listOf(emitWithIntents(setOf("OTHER"), 0))
            }

        val error =
            assertFailsWith<IllegalArgumentException> {
                RuleEngine().analyze(
                    source,
                    tree,
                    KotlinSyntaxModel,
                    NullOracle,
                    config,
                    listOf(TestPack("test", listOf(rule))),
                )
            }

        assertTrue(error.message.orEmpty().contains("test:foreign"))
        assertTrue(error.message.orEmpty().contains("OTHER"))
    }

    @Test
    fun shrinkingSelfReadingRuleFailsWithNamedNonMonotoneError() {
        val source = SourceFile("Sample.kt", "x")
        val tree = KotlinParser().parse(source)
        val rule =
            TestRule("test:shrink", "SHRINK", setOf("SHRINK")) { view ->
                if (view.annotations("SHRINK").isEmpty()) {
                    listOf(zeroLengthEmit("SHRINK", 0), zeroLengthEmit("SHRINK", 1))
                } else {
                    listOf(zeroLengthEmit("SHRINK", 0))
                }
            }

        val error =
            assertFailsWith<NonMonotoneRule> {
                RuleEngine().analyze(
                    source,
                    tree,
                    KotlinSyntaxModel,
                    NullOracle,
                    config,
                    listOf(TestPack("test", listOf(rule))),
                )
            }

        assertEquals("test:shrink", error.ruleId)
        assertEquals(2L, error.iterations)
        assertTrue(error.message.orEmpty().contains("annotation identity set shrank"))
    }

    @Test
    fun evidenceStabilizesAtStableIdentity() {
        val source = SourceFile("Sample.kt", "x")
        val tree = KotlinParser().parse(source)
        val rule =
            TestRule("test:evidence", "EVIDENCE", setOf("EVIDENCE")) { view ->
                val prior = view.annotations("EVIDENCE").singleOrNull()?.evidence.orEmpty()
                val evidence = if (prior.length < 3) "$prior|" else prior
                listOf(
                    Emit(
                        intents = setOf("EVIDENCE"),
                        evidence = evidence,
                        span = Span(0, 0, 0, 0),
                        source = "treesitter",
                        confidence = 1.0,
                        startByte = 0,
                        endByte = 0,
                    ),
                )
            }

        val annotations =
            RuleEngine().analyze(
                source,
                tree,
                KotlinSyntaxModel,
                NullOracle,
                config,
                listOf(TestPack("test", listOf(rule))),
            )

        assertEquals(1, annotations.count { "EVIDENCE" in it.intents })
        assertEquals("|||", annotations.single { "EVIDENCE" in it.intents }.evidence)
        assertEquals(4, rule.runs)
    }

    @Test
    fun evidenceChangeTriggeringNewIdentityIsReached() {
        val source = SourceFile("Sample.kt", "fun f() {}")
        val tree = KotlinParser().parse(source)
        val rule =
            TestRule("test:growth", "GROW", setOf("GROW")) { view ->
                val zero = view.annotations("GROW").firstOrNull { it.startByte == 0 && it.endByte == 0 }
                val one = view.annotations("GROW").firstOrNull { it.startByte == 1 && it.endByte == 1 }
                when {
                    zero == null -> listOf(zeroLengthEmit("GROW", 0, "a"))
                    one != null ->
                        listOf(
                            zeroLengthEmit("GROW", 0, zero.evidence),
                            zeroLengthEmit("GROW", 1, one.evidence),
                        )
                    zero.evidence == "a" -> listOf(zeroLengthEmit("GROW", 0, "b"))
                    zero.evidence == "b" -> listOf(zeroLengthEmit("GROW", 0, "c"))
                    else ->
                        listOf(
                            zeroLengthEmit("GROW", 0, "c"),
                            zeroLengthEmit("GROW", 1, "new"),
                        )
                }
            }

        val annotations =
            RuleEngine().analyze(
                source,
                tree,
                KotlinSyntaxModel,
                NullOracle,
                config,
                listOf(TestPack("test", listOf(rule))),
            )

        assertEquals(2, annotations.count { "GROW" in it.intents })
        assertEquals("c", annotations.single { it.startByte == 0 && it.endByte == 0 }.evidence)
        assertEquals("new", annotations.single { it.startByte == 1 && it.endByte == 1 }.evidence)
    }

    @Test
    fun configuredIterationLimitFailsWithNamedError() {
        val source = SourceFile("Sample.kt", "fun f() {}")
        val tree = KotlinParser().parse(source)
        val chain =
            TestRule("test:chain", "CHAIN", setOf("CHAIN")) { view ->
                val count = (view.annotations("CHAIN").size + 1).coerceAtMost(4)
                (0 until count).map { indexedEmit("CHAIN", it) }
            }

        val error =
            assertFailsWith<NonConvergentRule> {
                RuleEngine(maxIterationsPerRule = 2).analyze(
                    source,
                    tree,
                    KotlinSyntaxModel,
                    NullOracle,
                    config,
                    listOf(TestPack("test", listOf(chain))),
                )
            }

        assertEquals("test:chain", error.ruleId)
        assertTrue(error.message.orEmpty().contains("configured iteration limit 2"))
    }

    @Test
    fun nonRepeatingSelfReadingRuleFailsWithDerivedLimit() {
        val source = SourceFile("Sample.kt", "x")
        val tree = KotlinParser().parse(source)
        val growing =
            TestRule("test:fresh", "FRESH", setOf("FRESH")) { view ->
                val count = view.annotations("FRESH").size + 1
                (0 until count).map { indexedEmit("FRESH", it) }
            }

        val error =
            assertFailsWith<NonConvergentRule> {
                RuleEngine().analyze(
                    source,
                    tree,
                    KotlinSyntaxModel,
                    NullOracle,
                    config,
                    listOf(TestPack("test", listOf(growing))),
                )
            }

        assertEquals("test:fresh", error.ruleId)
        assertEquals(5L, error.iterations)
        assertTrue(error.message.orEmpty().contains("derived iteration limit 4"))
    }

    @Test
    fun convergentChainIncludingZeroLengthSpansFitsDerivedLimit() {
        val source = SourceFile("Sample.kt", "x")
        val tree = KotlinParser().parse(source)
        val intervals =
            listOf(
                zeroLengthEmit("CHAIN", 0),
                zeroLengthEmit("CHAIN", 1),
                indexedEmit("CHAIN", 0),
            )
        val chain =
            TestRule("test:chain", "CHAIN", setOf("CHAIN")) { view ->
                intervals.take(view.annotations("CHAIN").size + 1)
            }

        val annotations =
            RuleEngine().analyze(
                source,
                tree,
                KotlinSyntaxModel,
                NullOracle,
                config,
                listOf(TestPack("test", listOf(chain))),
            )

        assertEquals(3, annotations.count { "CHAIN" in it.intents })
        assertEquals(4, chain.runs)
    }

    @Test
    fun convergentChainBeyondTreeDescendantCountConverges() {
        val source = SourceFile("Sample.kt", "fun f() {}")
        val tree = KotlinParser().parse(source)
        val target = tree.root.descendants().count() + 3
        val chain =
            TestRule("test:chain", "CHAIN", setOf("CHAIN")) { view ->
                val count = (view.annotations("CHAIN").size + 1).coerceAtMost(target)
                (0 until count).map { indexedEmit("CHAIN", it) }
            }

        val annotations =
            RuleEngine().analyze(
                source,
                tree,
                KotlinSyntaxModel,
                NullOracle,
                config,
                listOf(TestPack("test", listOf(chain))),
            )

        assertEquals(target, annotations.count { "CHAIN" in it.intents })
        assertEquals(target + 1, chain.runs)
    }

    @Test
    fun nonSelfReadingRulesRunOnceAndSeeSettledUpstream() {
        val source = SourceFile("Sample.kt", "fun f() {}")
        val tree = KotlinParser().parse(source)
        val upstream =
            TestRule("test:upstream", "UPSTREAM") { view ->
                listOf(emitAt("UPSTREAM", view.functions().single().node))
            }
        val downstream =
            TestRule("test:downstream", "DOWNSTREAM", setOf("UPSTREAM")) { view ->
                assertEquals(1, view.annotations("UPSTREAM").size)
                listOf(emitAt("DOWNSTREAM", view.functions().single().node))
            }

        val annotations =
            RuleEngine().analyze(
                source,
                tree,
                KotlinSyntaxModel,
                NullOracle,
                config,
                listOf(TestPack("test", listOf(upstream, downstream))),
            )

        assertEquals(1, upstream.runs)
        assertEquals(1, downstream.runs)
        val merged = annotations.single { "UPSTREAM" in it.intents }
        assertEquals(setOf("UPSTREAM", "DOWNSTREAM"), merged.intents)
        assertEquals(setOf("test:upstream", "test:downstream"), merged.provenance.map { it.rule }.toSet())
    }

    @Test
    fun nonSelfReadingRuleRunsOnceAfterSelfReadingUpstreamSettles() {
        val source = SourceFile("Sample.kt", "fun f() {}")
        val tree = KotlinParser().parse(source)
        val chain =
            TestRule("test:chain", "CHAIN", setOf("CHAIN")) { view ->
                val count = (view.annotations("CHAIN").size + 1).coerceAtMost(3)
                (0 until count).map { indexedEmit("CHAIN", it) }
            }
        val reader =
            TestRule("test:reader", "READ", setOf("CHAIN")) { view ->
                assertEquals(3, view.annotations("CHAIN").size)
                emptyList()
            }

        RuleEngine().analyze(
            source,
            tree,
            KotlinSyntaxModel,
            NullOracle,
            config,
            listOf(TestPack("test", listOf(chain, reader))),
        )

        assertEquals(4, chain.runs)
        assertEquals(1, reader.runs)
    }

    @Test
    fun rulesInTheSameStratumDoNotSeeLiveInProgressOutput() {
        val source = SourceFile("Sample.kt", "fun f() {}")
        val tree = KotlinParser().parse(source)
        val seen = mutableSetOf<Intent>()
        val first =
            TestRule("test:a", "A") { view ->
                listOf(emitAt("A", view.functions().single().node))
            }
        val second =
            TestRule("test:b", "B") { view ->
                seen += view.coverage(view.functions().single().node).intents
                emptyList()
            }

        RuleEngine().analyze(
            source,
            tree,
            KotlinSyntaxModel,
            NullOracle,
            config,
            listOf(TestPack("test", listOf(first, second))),
        )

        assertTrue("A" !in seen)
    }

    @Test
    fun ruleViewIsScopedToItsOwnsAndReads() {
        val source = SourceFile("Sample.kt", "fun f() {}")
        val tree = KotlinParser().parse(source)
        val sourceRule =
            TestRule("test:source", "SOURCE") { view ->
                listOf(emitAt("SOURCE", view.functions().single().node))
            }
        val anchor =
            TestRule("test:anchor", "ANCHOR") { view ->
                listOf(emitAt("ANCHOR", view.functions().single().node))
            }
        val probe =
            TestRule("test:probe", "PROBE", setOf("ANCHOR")) { view ->
                assertEquals(1, view.annotations("ANCHOR").size)
                assertTrue(view.annotations("SOURCE").isEmpty())
                emptyList()
            }

        RuleEngine().analyze(
            source,
            tree,
            KotlinSyntaxModel,
            NullOracle,
            config,
            listOf(TestPack("test", listOf(sourceRule, anchor, probe))),
        )

        assertEquals(1, probe.runs)
    }
}
