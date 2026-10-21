package dev.cint.core.runtime.engine

import dev.cint.core.config.IntentConfig
import dev.cint.core.facts.model.SourceFile
import dev.cint.core.facts.oracle.NullOracle
import dev.cint.core.runtime.engine.support.*
import dev.cint.core.runtime.scheduler.RuleEngine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RuleEngineConvergenceTest {
    private val config = IntentConfig("kotlin", emptyList())

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
            RuleEngine()
                .analyze(
                    source,
                    tree,
                    fakeSyntax(),
                    NullOracle,
                    config,
                    listOf(TestPack("test", listOf(chain))),
                ).annotations

        assertEquals(4, chain.runs)
        assertEquals(3, annotations.count { "CHAIN" in it.intents })
    }

    @Test
    fun engineNeverPopulatesReservedNodeAnchor() {
        val source = SourceFile("Sample.kt", "fun f() {}")
        val tree = fakeTree(source.text)
        val rule = TestRule("test:anchor", "ANCHOR") { listOf(emitAt("ANCHOR", tree.root)) }

        val annotations =
            RuleEngine()
                .analyze(
                    source,
                    tree,
                    fakeSyntax(),
                    NullOracle,
                    config,
                    listOf(TestPack("test", listOf(rule))),
                ).annotations

        assertTrue(annotations.isNotEmpty())
        assertTrue(annotations.all { it.nodeAnchor == null })
    }

    @Test
    fun sourceMetadataStabilizesAtStableIdentity() {
        val source = SourceFile("Sample.kt", "x")
        val tree = fakeTree(source.text)
        val rule =
            TestRule("test:metadata", "METADATA", setOf("METADATA")) { view ->
                val prior =
                    view
                        .annotations("METADATA")
                        .singleOrNull()
                        ?.source
                        .orEmpty()
                val reported = if (prior.length < 3) "$prior|" else prior
                listOf(zeroLengthEmit("METADATA", 0, reported))
            }

        val annotations =
            RuleEngine()
                .analyze(
                    source,
                    tree,
                    fakeSyntax(),
                    NullOracle,
                    config,
                    listOf(TestPack("test", listOf(rule))),
                ).annotations

        assertEquals(1, annotations.count { "METADATA" in it.intents })
        assertEquals("|||", annotations.single { "METADATA" in it.intents }.source)
        assertEquals(4, rule.runs)
    }

    @Test
    fun sourceChangeTriggeringNewIdentityIsReached() {
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
                            zeroLengthEmit("GROW", 0, zero.source),
                            zeroLengthEmit("GROW", 1, one.source),
                        )
                    }

                    zero.source == "a" -> {
                        listOf(zeroLengthEmit("GROW", 0, "b"))
                    }

                    zero.source == "b" -> {
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
            RuleEngine()
                .analyze(
                    source,
                    tree,
                    fakeSyntax(),
                    NullOracle,
                    config,
                    listOf(TestPack("test", listOf(rule))),
                ).annotations

        assertEquals(2, annotations.count { "GROW" in it.intents })
        assertEquals("c", annotations.single { it.startByte == 0 && it.endByte == 0 }.source)
        assertEquals("new", annotations.single { it.startByte == 1 && it.endByte == 1 }.source)
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
            RuleEngine()
                .analyze(
                    source,
                    tree,
                    fakeSyntax(),
                    NullOracle,
                    config,
                    listOf(TestPack("test", listOf(chain))),
                ).annotations

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
            RuleEngine()
                .analyze(
                    source,
                    tree,
                    fakeSyntax(),
                    NullOracle,
                    config,
                    listOf(TestPack("test", listOf(chain))),
                ).annotations

        assertEquals(target, annotations.count { "CHAIN" in it.intents })
        assertEquals(target + 1, chain.runs)
    }
}
