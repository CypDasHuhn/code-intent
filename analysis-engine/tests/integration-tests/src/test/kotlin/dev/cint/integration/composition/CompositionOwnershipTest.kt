package dev.cint.integration.composition

import dev.cint.api.Role
import dev.cint.core.CoreContributors
import dev.cint.core.runtime.scheduler.ReservedContributorId
import dev.cint.integration.composition.support.ReplayPack
import dev.cint.integration.composition.support.ReplayRule
import dev.cint.integration.composition.support.analyzePipeline
import dev.cint.integration.composition.support.replaySource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CompositionOwnershipTest {
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
        val annotations = analyzePipeline(text, listOf(ReplayPack("source", replaySource(text))))
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
                analyzePipeline(text, listOf(ReplayPack("plugin", listOf(rule))))
            }

        assertEquals(CoreContributors.SCAFFOLD, error.rule.id)
    }
}
