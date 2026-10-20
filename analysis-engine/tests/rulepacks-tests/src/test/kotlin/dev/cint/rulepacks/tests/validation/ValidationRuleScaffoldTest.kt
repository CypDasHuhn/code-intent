package dev.cint.rulepacks.tests.validation

import dev.cint.api.Role
import dev.cint.core.CoreContributors
import dev.cint.core.facts.oracle.NullOracle
import dev.cint.core.runtime.scheduler.RuleEngine
import dev.cint.rulepacks.bookkeeping.BookkeepingRulePack
import dev.cint.rulepacks.logging.LoggingRulePack
import dev.cint.rulepacks.tests.support.parseValidationInput
import dev.cint.rulepacks.validation.VALIDATION_INTENT
import dev.cint.rulepacks.validation.ValidationRulePack
import kotlin.test.Test
import kotlin.test.assertTrue

class ValidationRuleScaffoldTest {
    @Test
    fun nonExitingValidationGuardsTagReturnsAndScaffolds() {
        val text =
            """
            private val log = LoggerFactory.getLogger(Foo::class.java)
            fun nullCase(value: Int): String? {
                if (value > 0) {
                    log.info("x")
                } else {
                    return null
                }
            }
            fun zeroCase(value: Int): Int {
                if (value > 0) {
                    log.info("x")
                } else {
                    return 0
                }
            }
            fun falseCase(value: Int): Boolean {
                if (value > 0) {
                    log.info("x")
                } else {
                    return false
                }
            }
            fun bareCase(value: Int) {
                if (value > 0) {
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

        assertTrue(scaffoldCovers("return null", VALIDATION_INTENT))
        assertTrue(scaffoldCovers("return 0", VALIDATION_INTENT))
        assertTrue(scaffoldCovers("return false", VALIDATION_INTENT))
        assertTrue(
            annotations.none {
                it.role == Role.SCAFFOLD &&
                    it.rule == CoreContributors.SCAFFOLD &&
                    it.intents.isEmpty()
            },
        )
    }
}
