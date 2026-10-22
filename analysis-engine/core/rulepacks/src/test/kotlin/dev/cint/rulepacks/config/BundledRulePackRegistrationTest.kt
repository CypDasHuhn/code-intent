package dev.cint.rulepacks.config

import dev.cint.core.runtime.rulepack.RulePackLoader
import dev.cint.core.runtime.scheduler.RuleScheduler
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private val bundledIds =
    setOf(
        "logging",
        "error-handling",
        "bookkeeping",
        "mutation",
        "validation",
        "normalization",
        "data-access",
        "service",
        "data-transformation",
        "return-value",
        "local-copy",
    )
private val bundledLanguages = setOf("kotlin", "javascript", "typescript", "csharp")
private val bundledIntents =
    setOf(
        "LOGGING",
        "ERROR_HANDLING",
        "BOOKKEEPING",
        "DATA_MODIFY",
        "LOCAL_VALUE_MODIFY",
        "VALIDATION",
        "NORMALIZATION",
        "SEARCH",
        "LOOKUP",
        "SERVICE_DELEGATION",
        "SERVICE_INIT",
        "DATA_TRANSFORMATION",
        "RETURN_VALUE",
        "LOCAL_COPY",
    )

class BundledRulePackRegistrationTest {
    @Test
    fun everyBundledPackIsAServiceProviderWithUniqueIdentity() {
        val result = RulePackLoader(onFailure = {}).load()
        val bundled = result.packs.filter { it.id in bundledIds }

        assertTrue(result.failures.isEmpty(), result.failures.joinToString { it.message })
        assertEquals(bundledIds, bundled.map { it.id }.toSet())
        assertEquals(
            result.packs.size,
            result.packs
                .map { it.id }
                .distinct()
                .size,
        )

        val rules = bundled.flatMap { it.rules() }
        assertEquals(rules.size, rules.map { it.id }.distinct().size)
        assertEquals(bundledIntents, rules.map { it.owns }.toSet())
        assertEquals(rules.size, rules.map { it.owns }.distinct().size)
    }

    @Test
    fun everyBundledPackUsesTheSameLanguagesAndLeavesVueToTheSplitter() {
        val result = RulePackLoader(onFailure = {}).load()
        val bundled = result.packs.filter { it.id in bundledIds }

        assertEquals(bundledIds, bundled.map { it.id }.toSet())
        for (pack in bundled) {
            assertEquals(bundledLanguages, pack.languages, "${pack.id} languages")
        }
        assertTrue(result.packs.none { "vue" in it.languages })
    }

    @Test
    fun eachLanguageSelectsOneOwnerPerIntentWithoutDuplicates() {
        val result = RulePackLoader(onFailure = {}).load()

        for (language in bundledLanguages) {
            val selected =
                result.packs.filter { pack ->
                    pack.languages.isEmpty() || pack.languages.any { it.equals(language, ignoreCase = true) }
                }
            val plan = RuleScheduler().plan(selected)
            val owners = plan.strata.flatten().map { it.owns }

            assertEquals(owners.size, owners.distinct().size, "$language duplicate owner")
            assertTrue(bundledIntents.all { it in plan.terminal.sourceIntents }, "$language intents")
            assertTrue(
                plan.strata.flatten().none { it.id.startsWith("error-handling-") },
                "$language selected a per-language error-handling rule",
            )
        }
    }
}
