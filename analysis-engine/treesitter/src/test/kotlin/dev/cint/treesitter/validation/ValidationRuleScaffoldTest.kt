package dev.cint.treesitter.validation

import dev.cint.api.Role
import dev.cint.api.UNTAGGED_INTENT
import dev.cint.core.CoreContributors
import dev.cint.core.facts.oracle.NullOracle
import dev.cint.core.runtime.scheduler.RuleEngine
import dev.cint.rulepacks.BookkeepingRulePack
import dev.cint.rulepacks.LoggingRulePack
import dev.cint.rulepacks.ValidationRulePack
import dev.cint.treesitter.support.parseValidationInput
import dev.cint.treesitter.support.validationPipeline
import kotlin.test.Test
import kotlin.test.assertTrue

class ValidationRuleScaffoldTest {
    @Test
    fun onlyFalseAndBareReturnsAreNeutralForScaffolds() {
        val text =
            """
            private val log = LoggerFactory.getLogger(Foo::class.java)
            fun loggingEnabled(): Boolean = true
            fun nullCase(): String? {
                if (loggingEnabled()) {
                    log.info("x")
                } else {
                    return null
                }
            }
            fun zeroCase(): Int {
                if (loggingEnabled()) {
                    log.info("x")
                } else {
                    return 0
                }
            }
            fun falseCase(): Boolean {
                if (loggingEnabled()) {
                    log.info("x")
                } else {
                    return false
                }
            }
            fun bareCase() {
                if (loggingEnabled()) {
                    log.info("x")
                } else {
                    return
                }
            }
            """.trimIndent()
        val input = parseValidationInput("kotlin", "Sample.kt", text)
        val annotations =
            RuleEngine().analyze(
                input.source,
                input.tree,
                input.syntax,
                NullOracle,
                input.config,
                listOf(LoggingRulePack(), BookkeepingRulePack(), ValidationRulePack()),
            )

        fun scaffoldCovers(
            text: String,
            intent: String,
        ): Boolean {
            val node =
                input.tree.root
                    .descendants()
                    .first { it.text.trim() == text }
            return annotations.any {
                it.role == Role.SCAFFOLD &&
                    it.rule == CoreContributors.SCAFFOLD &&
                    it.startByte <= node.startByte &&
                    it.endByte >= node.endByte &&
                    intent in it.intents
            }
        }

        assertTrue(scaffoldCovers("return null", UNTAGGED_INTENT))
        assertTrue(scaffoldCovers("return 0", UNTAGGED_INTENT))
        assertTrue(!scaffoldCovers("return false", UNTAGGED_INTENT))
        assertTrue(
            annotations.none {
                it.role == Role.SCAFFOLD &&
                    it.rule == CoreContributors.SCAFFOLD &&
                    it.intents.isEmpty()
            },
        )
    }
}
