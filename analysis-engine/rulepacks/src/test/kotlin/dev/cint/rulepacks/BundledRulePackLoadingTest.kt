package dev.cint.rulepacks

import dev.cint.api.CINT_API_VERSION
import dev.cint.api.Emit
import dev.cint.api.Intent
import dev.cint.api.Rule
import dev.cint.api.RulePack
import dev.cint.api.RulePackApi
import dev.cint.api.View
import dev.cint.core.rulepack.RulePackLoader
import dev.cint.core.scheduler.RuleScheduler
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RulePackApi(CINT_API_VERSION)
class TestBundledPack : RulePack {
    override val id: String = "bundled-test"
    override val languages: Set<String> = setOf("kotlin")
    override val apiVersion: Int = CINT_API_VERSION

    override fun rules(): List<Rule> = listOf(TestBundledRule())
}

private class TestBundledRule : Rule {
    override val id: String = "bundled-test:logging"
    override val owns: Intent = "BUNDLED_LOGGING"
    override val reads: Set<Intent> = emptySet()

    override fun run(view: View): List<Emit> = emptyList()
}

class BundledRulePackLoadingTest {
    @Test
    fun bundledPackUsesTheSharedLoader() {
        val result = RulePackLoader(TestBundledPack::class.java.classLoader, onFailure = {}).load()

        assertEquals(listOf("bookkeeping", "bundled-test", "logging", "validation"), result.loadedPackIds)
        assertTrue(result.failures.isEmpty())
        val plan = RuleScheduler().plan(result.packs)
        assertEquals(
            setOf("bookkeeping:bookkeeping", "bundled-test:logging", "logging:logging", "validation:validation"),
            plan.strata
                .flatten()
                .map { it.id }
                .toSet(),
        )
    }
}
