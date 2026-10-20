package dev.cint.rulepacks

import dev.cint.core.runtime.rulepack.RulePackLoader
import dev.cint.host.HostPipeline
import dev.cint.rulepacks.errorhandling.ErrorHandlingRulePack
import dev.cint.rulepacks.support.BundledRulePackSelection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BundledRulePackSelectionTest {
    @Test
    fun constructsOneErrorHandlingPackForEachLanguage() {
        for (language in ErrorHandlingRulePack.languages) {
            assertEquals(
                listOf("error-handling-$language"),
                BundledRulePackSelection.constructed(language).map { it.id },
            )
        }
    }

    @Test
    fun constructsNothingForUnsupportedLanguages() {
        assertTrue(BundledRulePackSelection.constructed("vue").isEmpty())
    }

    @Test
    fun hostPipelineValidatesConstructedErrorHandlingPacks() {
        val pipeline =
            HostPipeline(
                loader = RulePackLoader(onFailure = {}),
                constructedPacks = BundledRulePackSelection::constructed,
            )

        val ruleIds =
            pipeline
                .plan("kotlin")
                .strata
                .flatten()
                .map { it.id }
        assertTrue("logging:logging" in ruleIds)
        assertTrue("bookkeeping:bookkeeping" in ruleIds)
        assertTrue("error-handling-kotlin:error-handling" in ruleIds)
        assertTrue("ERROR_HANDLING" in pipeline.intents(listOf("kotlin")))
        assertTrue(
            "error-handling-typescript:error-handling" !in
                pipeline
                    .plan("kotlin")
                    .strata
                    .flatten()
                    .map { it.id },
        )
    }
}
