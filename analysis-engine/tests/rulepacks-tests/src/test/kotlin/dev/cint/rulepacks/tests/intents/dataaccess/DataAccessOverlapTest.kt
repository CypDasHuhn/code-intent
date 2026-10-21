package dev.cint.rulepacks.tests.intents.dataaccess

import dev.cint.api.CandidateReason
import dev.cint.rulepacks.intents.dataaccess.LOOKUP_INTENT
import dev.cint.rulepacks.intents.dataaccess.SEARCH_INTENT
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DataAccessOverlapTest {
    @Test
    fun findByIdIsExplicitSearchWithLookupBoundaryCandidate() {
        val text =
            """
            fun f(repo: UserRepository, id: String) {
                repo.findById(id)
            }
            """.trimIndent()

        val analysis = dataAccess("kotlin", text)

        assertTrue(analysis.texts(SEARCH_INTENT).any { it.startsWith("repo.findById") })
        val lookupCandidate = analysis.candidates(LOOKUP_INTENT).first { it.startByte == analysis.source(SEARCH_INTENT).first().startByte }
        assertEquals(CandidateReason.PACK_DOMAIN, lookupCandidate.reason)
        assertTrue(lookupCandidate.aiEligible)
        assertEquals("rule:data-access:lookup", lookupCandidate.source)
    }

    @Test
    fun knownMapGetIsLookupWithSearchAlternative() {
        val text =
            """
            fun f(id: String) {
                val status = statusById.get(id)
            }
            """.trimIndent()

        val analysis = dataAccess("kotlin", text)

        val lookup = analysis.source(LOOKUP_INTENT).single()
        assertTrue(analysis.texts(LOOKUP_INTENT).single().startsWith("statusById.get"))
        val searchCandidate = analysis.candidates(SEARCH_INTENT).single()
        assertEquals(lookup.startByte, searchCandidate.startByte)
        assertEquals(lookup.endByte, searchCandidate.endByte)
        assertEquals(CandidateReason.PACK_DOMAIN, searchCandidate.reason)
        assertEquals("rule:data-access:search", searchCandidate.source)
    }

    @Test
    fun ambiguousGetYieldsBothCandidatesAndNoEstablishedOutput() {
        val text =
            """
            fun f(repo: UserRepository, id: String) {
                repo.get(id)
            }
            """.trimIndent()

        val analysis = dataAccess("kotlin", text)

        analysis.assertNoSource(SEARCH_INTENT)
        analysis.assertNoSource(LOOKUP_INTENT)
        assertEquals(1, analysis.candidates(SEARCH_INTENT).size)
        assertEquals(1, analysis.candidates(LOOKUP_INTENT).size)
    }

    @Test
    fun searchAndLookupCoexistInOneFile() {
        val text =
            """
            fun f(repo: UserRepository, id: String) {
                val status = statusById[id]
                repo.search("active")
            }
            """.trimIndent()

        val analysis = dataAccess("kotlin", text)

        assertTrue(analysis.texts(SEARCH_INTENT).any { it.startsWith("repo.search") })
        assertTrue(analysis.texts(LOOKUP_INTENT).any { it == "statusById[id]" })
    }
}
