package dev.cint.integration.host

import dev.cint.host.HostRuntime
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

class BundledRulePackRegistrationHostTest {
    @Test
    fun hostSelectsServiceRegisteredPacksPerLanguage() {
        val pipeline = HostRuntime.pipeline()

        for (language in bundledLanguages) {
            val packs = pipeline.packs(language)
            assertEquals(bundledIds, packs.map { it.id }.filter { it in bundledIds }.toSet(), language)
            assertEquals(1, packs.count { it.id == "error-handling" }, language)
            assertTrue(packs.none { it.id.startsWith("error-handling-") }, language)

            val owners =
                pipeline
                    .plan(language)
                    .strata
                    .flatten()
                    .map { it.owns }
            assertEquals(owners.size, owners.distinct().size, language)
            assertTrue("ERROR_HANDLING" in pipeline.intents(listOf(language)), language)
            assertTrue("DATA_MODIFY" in pipeline.intents(listOf(language)), language)
            assertTrue("LOCAL_VALUE_MODIFY" in pipeline.intents(listOf(language)), language)
            assertTrue("NORMALIZATION" in pipeline.intents(listOf(language)), language)
            assertTrue("SEARCH" in pipeline.intents(listOf(language)), language)
            assertTrue("LOOKUP" in pipeline.intents(listOf(language)), language)
            assertTrue("SERVICE_DELEGATION" in pipeline.intents(listOf(language)), language)
            assertTrue("SERVICE_INIT" in pipeline.intents(listOf(language)), language)
            assertTrue("DATA_TRANSFORMATION" in pipeline.intents(listOf(language)), language)
            assertTrue("RETURN_VALUE" in pipeline.intents(listOf(language)), language)
            assertTrue("LOCAL_COPY" in pipeline.intents(listOf(language)), language)
        }
    }

    @Test
    fun vueSelectionStaysEmptyBecauseRegionsAreSplitBeforePlanning() {
        val pipeline = HostRuntime.pipeline()

        assertTrue(pipeline.packs("vue").isEmpty())
        assertEquals(listOf("UNTAGGED"), pipeline.intents(listOf("vue")))
    }
}
