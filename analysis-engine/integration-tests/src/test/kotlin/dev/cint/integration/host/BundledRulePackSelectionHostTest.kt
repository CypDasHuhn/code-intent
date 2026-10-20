package dev.cint.integration.host

import dev.cint.core.runtime.rulepack.RulePackLoader
import dev.cint.host.HostPipeline
import dev.cint.rulepacks.support.BundledRulePackSelection
import kotlin.test.Test
import kotlin.test.assertTrue

class BundledRulePackSelectionHostTest {
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
