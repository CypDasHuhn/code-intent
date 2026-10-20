package dev.cint.core.runtime.engine

import dev.cint.core.config.IntentConfig
import dev.cint.core.facts.model.SourceFile
import dev.cint.core.facts.oracle.NullOracle
import dev.cint.core.runtime.scheduler.RuleEngine
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RuleEngineEmitValidationTest {
    private val config = IntentConfig("kotlin", emptyMap(), emptyList())

    @Test
    fun emptyIntentEmitFails() {
        val source = SourceFile("Sample.kt", "fun f() {}")
        val tree = fakeTree(source.text)
        val rule =
            TestRule("test:empty", "EMPTY") {
                listOf(emitWithIntents(emptySet(), 0))
            }

        val error =
            assertFailsWith<IllegalArgumentException> {
                RuleEngine().analyze(
                    source,
                    tree,
                    fakeSyntax(),
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
        val tree = fakeTree(source.text)
        val rule =
            TestRule("test:foreign", "FOREIGN") {
                listOf(emitWithIntents(setOf("OTHER"), 0))
            }

        val error =
            assertFailsWith<IllegalArgumentException> {
                RuleEngine().analyze(
                    source,
                    tree,
                    fakeSyntax(),
                    NullOracle,
                    config,
                    listOf(TestPack("test", listOf(rule))),
                )
            }

        assertTrue(error.message.orEmpty().contains("test:foreign"))
        assertTrue(error.message.orEmpty().contains("OTHER"))
    }
}
