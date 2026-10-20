package dev.cint.treesitter.engine

import dev.cint.api.Intent
import dev.cint.core.config.IntentConfig
import dev.cint.core.facts.model.SourceFile
import dev.cint.core.facts.oracle.NullOracle
import dev.cint.core.runtime.scheduler.RuleEngine
import dev.cint.treesitter.KotlinParser
import dev.cint.treesitter.KotlinSyntaxModel
import dev.cint.treesitter.support.TestPack
import dev.cint.treesitter.support.TestRule
import dev.cint.treesitter.support.emitAt
import dev.cint.treesitter.support.indexedEmit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RuleEngineStratificationTest {
    private val config = IntentConfig("kotlin", emptyMap(), emptyList())

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
