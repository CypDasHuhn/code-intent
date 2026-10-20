package dev.cint.core.runtime.engine

import dev.cint.core.config.IntentConfig
import dev.cint.core.facts.model.SourceFile
import dev.cint.core.facts.oracle.NullOracle
import dev.cint.core.runtime.scheduler.NonConvergentRule
import dev.cint.core.runtime.scheduler.NonMonotoneRule
import dev.cint.core.runtime.scheduler.RuleEngine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RuleEngineSchedulerErrorTest {
    private val config = IntentConfig("kotlin", emptyList())

    @Test
    fun shrinkingSelfReadingRuleFailsWithNamedNonMonotoneError() {
        val source = SourceFile("Sample.kt", "x")
        val tree = fakeTree(source.text)
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
                    fakeSyntax(),
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
    fun configuredIterationLimitFailsWithNamedError() {
        val source = SourceFile("Sample.kt", "fun f() {}")
        val tree = fakeTree(source.text)
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
                    fakeSyntax(),
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
        val tree = fakeTree(source.text)
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
                    fakeSyntax(),
                    NullOracle,
                    config,
                    listOf(TestPack("test", listOf(growing))),
                )
            }

        assertEquals("test:fresh", error.ruleId)
        assertEquals(5L, error.iterations)
        assertTrue(error.message.orEmpty().contains("derived iteration limit 4"))
    }
}
