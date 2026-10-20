package dev.cint.core.runtime.engine

import dev.cint.core.config.IntentConfig
import dev.cint.core.facts.model.SourceFile
import dev.cint.core.facts.oracle.NullOracle
import dev.cint.core.runtime.scheduler.RuleEngine
import kotlin.test.Test
import kotlin.test.assertEquals

class RuleEngineConvergenceTest {
    private val config = IntentConfig("kotlin", emptyMap(), emptyList())

    @Test
    fun selfReadingRuleConvergesUnderInternalFixpoint() {
        val source = SourceFile("Sample.kt", "fun f() {}")
        val tree = fakeTree(source.text)
        val chain =
            TestRule("test:chain", "CHAIN", setOf("CHAIN")) { view ->
                val count = (view.annotations("CHAIN").size + 1).coerceAtMost(3)
                (0 until count).map { indexedEmit("CHAIN", it) }
            }

        val annotations =
            RuleEngine().analyze(
                source,
                tree,
                fakeSyntax(),
                NullOracle,
                config,
                listOf(TestPack("test", listOf(chain))),
            )

        assertEquals(4, chain.runs)
        assertEquals(3, annotations.count { "CHAIN" in it.intents })
    }

    @Test
    fun evidenceStabilizesAtStableIdentity() {
        val source = SourceFile("Sample.kt", "x")
        val tree = fakeTree(source.text)
        val rule =
            TestRule("test:evidence", "EVIDENCE", setOf("EVIDENCE")) { view ->
                val prior =
                    view
                        .annotations("EVIDENCE")
                        .singleOrNull()
                        ?.evidence
                        .orEmpty()
                val evidence = if (prior.length < 3) "$prior|" else prior
                listOf(zeroLengthEmit("EVIDENCE", 0, evidence))
            }

        val annotations =
            RuleEngine().analyze(
                source,
                tree,
                fakeSyntax(),
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
        val tree = fakeTree(source.text)
        val rule =
            TestRule("test:growth", "GROW", setOf("GROW")) { view ->
                val zero = view.annotations("GROW").firstOrNull { it.startByte == 0 && it.endByte == 0 }
                val one = view.annotations("GROW").firstOrNull { it.startByte == 1 && it.endByte == 1 }
                when {
                    zero == null -> {
                        listOf(zeroLengthEmit("GROW", 0, "a"))
                    }

                    one != null -> {
                        listOf(
                            zeroLengthEmit("GROW", 0, zero.evidence),
                            zeroLengthEmit("GROW", 1, one.evidence),
                        )
                    }

                    zero.evidence == "a" -> {
                        listOf(zeroLengthEmit("GROW", 0, "b"))
                    }

                    zero.evidence == "b" -> {
                        listOf(zeroLengthEmit("GROW", 0, "c"))
                    }

                    else -> {
                        listOf(
                            zeroLengthEmit("GROW", 0, "c"),
                            zeroLengthEmit("GROW", 1, "new"),
                        )
                    }
                }
            }

        val annotations =
            RuleEngine().analyze(
                source,
                tree,
                fakeSyntax(),
                NullOracle,
                config,
                listOf(TestPack("test", listOf(rule))),
            )

        assertEquals(2, annotations.count { "GROW" in it.intents })
        assertEquals("c", annotations.single { it.startByte == 0 && it.endByte == 0 }.evidence)
        assertEquals("new", annotations.single { it.startByte == 1 && it.endByte == 1 }.evidence)
    }

    @Test
    fun convergentChainIncludingZeroLengthSpansFitsDerivedLimit() {
        val source = SourceFile("Sample.kt", "x")
        val tree = fakeTree(source.text)
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
                fakeSyntax(),
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
        val tree = fakeTree(source.text)
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
                fakeSyntax(),
                NullOracle,
                config,
                listOf(TestPack("test", listOf(chain))),
            )

        assertEquals(target, annotations.count { "CHAIN" in it.intents })
        assertEquals(target + 1, chain.runs)
    }
}
