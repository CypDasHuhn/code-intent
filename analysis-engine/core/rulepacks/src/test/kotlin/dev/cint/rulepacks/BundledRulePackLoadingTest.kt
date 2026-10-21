package dev.cint.rulepacks

import dev.cint.api.CINT_API_VERSION
import dev.cint.api.Emit
import dev.cint.api.Intent
import dev.cint.api.Rule
import dev.cint.api.RuleEmit
import dev.cint.api.RulePack
import dev.cint.api.RulePackApi
import dev.cint.api.View
import dev.cint.core.runtime.rulepack.RulePackLoader
import dev.cint.core.runtime.scheduler.RuleScheduler
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

    override fun run(view: View): List<RuleEmit> = emptyList()
}

class BundledRulePackLoadingTest {
    @Test
    fun bundledPackUsesTheSharedLoader() {
        val result = RulePackLoader(TestBundledPack::class.java.classLoader, onFailure = {}).load()

        assertEquals(
            listOf(
                "bookkeeping",
                "bool-combinatorics",
                "bundled-test",
                "chaining",
                "data-access",
                "data-transformation",
                "error-handling",
                "local-copy",
                "logging",
                "mutation",
                "normalization",
                "return-value",
                "service",
                "validation",
            ),
            result.loadedPackIds,
        )
        assertTrue(result.failures.isEmpty())
        val plan = RuleScheduler().plan(result.packs)
        assertEquals(
            setOf(
                "bookkeeping:bookkeeping",
                "bool-combinatorics:bool-combinatorics",
                "bundled-test:logging",
                "chaining:chaining",
                "data-access:lookup",
                "data-access:search",
                "data-transformation:data-transformation",
                "error-handling:error-handling",
                "local-copy:local-copy",
                "logging:logging",
                "mutation:data-modify",
                "mutation:local-value-modify",
                "normalization:normalization",
                "return-value:return-value",
                "service:delegation",
                "service:init",
                "validation:validation",
            ),
            plan.strata
                .flatten()
                .map { it.id }
                .toSet(),
        )
    }
}
