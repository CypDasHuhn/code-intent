package dev.cint.rulepacks.tests.intents.dataaccess.overlap

import dev.cint.api.CandidateReason
import dev.cint.core.facts.oracle.MapOracle
import dev.cint.core.facts.oracle.TypeFact
import dev.cint.rulepacks.bookkeeping.BookkeepingRulePack
import dev.cint.rulepacks.intents.dataaccess.DataAccessRulePack
import dev.cint.rulepacks.intents.dataaccess.LOOKUP_INTENT
import dev.cint.rulepacks.intents.dataaccess.SEARCH_INTENT
import dev.cint.rulepacks.intents.dataaccess.config.DataAccessConfig
import dev.cint.rulepacks.intents.mutation.DATA_MODIFY_INTENT
import dev.cint.rulepacks.intents.mutation.LOCAL_VALUE_MODIFY_INTENT
import dev.cint.rulepacks.intents.mutation.MutationRulePack
import dev.cint.rulepacks.intents.structural.localcopy.LocalCopyRulePack
import dev.cint.rulepacks.tests.intents.dataaccess.support.dataAccess
import dev.cint.rulepacks.tests.support.PackAnalysis
import dev.cint.rulepacks.tests.support.analyzePack
import dev.cint.rulepacks.tests.support.parsePackInput
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private val DURABLE_COMPOUND_DEFAULTS =
    setOf(
        "findOneAndUpdate",
        "findOneAndDelete",
        "findOneAndReplace",
        "findOneAndIncrement",
        "findOneAndDecrement",
        "findOneAndModify",
        "findAndModify",
    )

private val LOCAL_ATOMIC_DEFAULTS = setOf("getAndUpdate", "getAndIncrement", "getAndDecrement")

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
    fun compoundMutationsDoNotEmitReadAmbiguityInTheFullPack() {
        val text =
            """
            fun f(query: String, value: Int, id: String) {
                repository.findOneAndUpdate(query, value)
                repository.findOneAndDelete(query)
                repository.getPersist(value)
                repository.findByStatus("active")
                repository.count()
                repository.get(id)
            }
            """.trimIndent()
        val dataAccess =
            DataAccessRulePack().configureAdditive(
                mapOf("kotlin" to mapOf("compoundMutationCalls" to listOf("getPersist"))),
            )
        val mutation =
            MutationRulePack().configureAdditive(
                mapOf(
                    "kotlin" to
                        mapOf(
                            "persistenceCalls" to
                                listOf("findOneAndUpdate", "findOneAndDelete", "getPersist"),
                        ),
                ),
            )

        val analysis = analyzePack("kotlin", text, listOf(BookkeepingRulePack(), dataAccess, mutation, LocalCopyRulePack()))

        assertEquals(
            setOf(
                "repository.findOneAndUpdate(query, value)",
                "repository.findOneAndDelete(query)",
                "repository.getPersist(value)",
            ),
            analysis.texts(DATA_MODIFY_INTENT).toSet(),
        )

        val readTexts =
            buildList {
                analysis.source(SEARCH_INTENT).mapTo(this) { text.substring(it.startByte, it.endByte) }
                analysis.source(LOOKUP_INTENT).mapTo(this) { text.substring(it.startByte, it.endByte) }
                analysis.candidates(SEARCH_INTENT).mapTo(this) { text.substring(it.startByte, it.endByte) }
                analysis.candidates(LOOKUP_INTENT).mapTo(this) { text.substring(it.startByte, it.endByte) }
            }
        assertTrue(
            readTexts.none {
                it.contains("findOneAndUpdate") ||
                    it.contains("findOneAndDelete") ||
                    it.contains("getPersist")
            },
        )
        assertTrue(analysis.texts(SEARCH_INTENT).any { it.startsWith("repository.findByStatus") })
        assertTrue(analysis.texts(DATA_MODIFY_INTENT).none { it.contains("findByStatus") })
        assertTrue(analysis.texts(DATA_MODIFY_INTENT).none { it == "repository.count()" })
        assertTrue(analysis.texts(DATA_MODIFY_INTENT).none { it == "repository.get(id)" })
    }

    @Test
    fun durableDefaultCompoundMutationCallsAreOwnedByMutationPack() {
        for (callee in DURABLE_COMPOUND_DEFAULTS) {
            val text =
                """
                fun f(value: Int) {
                    model.$callee(value)
                }
                """.trimIndent()
            val analysis = fullPackAnalysis(text)

            assertTrue(
                dataModifyTexts(analysis, text).any { it == "model.$callee(value)" },
                callee,
            )
            assertTrue(
                readTexts(analysis, text).none { it.contains("model.$callee") },
                callee,
            )
        }
    }

    @Test
    fun localAtomicDefaultCompoundMutationCallsAreLocalOnly() {
        for (callee in LOCAL_ATOMIC_DEFAULTS) {
            val text =
                """
                fun f(value: Int) {
                    model.$callee(value)
                }
                """.trimIndent()
            val analysis = fullPackAnalysis(text)

            assertTrue(
                localModifyTexts(analysis, text).any { it == "model.$callee(value)" },
                callee,
            )
            assertTrue(dataModifyTexts(analysis, text).isEmpty(), callee)
            assertTrue(
                readTexts(analysis, text).none { it.contains("model.$callee") },
                callee,
            )
        }
    }

    @Test
    fun atomicIntegerGetAndIncrementIsLocalValueModify() {
        val text =
            """
            fun f() {
                counter.getAndIncrement()
            }
            """.trimIndent()
        val analysis =
            fullPackAnalysis(
                text,
                mapOf("counter" to "java.util.concurrent.atomic.AtomicInteger"),
            )

        assertTrue(localModifyTexts(analysis, text).any { it == "counter.getAndIncrement()" })
        assertTrue(dataModifyTexts(analysis, text).isEmpty())
        assertTrue(readTexts(analysis, text).none { it.contains("counter.getAndIncrement") })
    }

    @Test
    fun mutableNamedAtomicAndDurableCompoundCallsAreClassified() {
        val atomicText =
            """
            fun f() {
                cache.getAndIncrement()
            }
            """.trimIndent()
        val atomic = fullPackAnalysis(atomicText)

        assertTrue(localModifyTexts(atomic, atomicText).any { it == "cache.getAndIncrement()" })
        assertTrue(dataModifyTexts(atomic, atomicText).isEmpty())
        assertTrue(readTexts(atomic, atomicText).none { it.contains("cache.getAndIncrement") })

        val durableText =
            """
            fun f(value: Int) {
                items.findAndModify(value)
            }
            """.trimIndent()
        val durable = fullPackAnalysis(durableText)

        assertTrue(
            dataModifyTexts(durable, durableText).any { it == "items.findAndModify(value)" },
        )
        assertTrue(readTexts(durable, durableText).none { it.contains("items.findAndModify") })
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

private fun fullPackAnalysis(
    text: String,
    types: Map<String, String> = emptyMap(),
): PackAnalysis {
    val input = parsePackInput("kotlin", text)
    val facts =
        input.syntax
            .calls(input.tree)
            .mapNotNull { call ->
                val receiver = call.receiver ?: return@mapNotNull null
                val fqName = types[receiver.text] ?: return@mapNotNull null
                input.syntax.receiverOffset(receiver) to TypeFact(fqName)
            }.toMap()
    return analyzePack(
        input,
        listOf(BookkeepingRulePack(), DataAccessRulePack(), MutationRulePack(), LocalCopyRulePack()),
        MapOracle(facts),
    )
}

private fun dataModifyTexts(
    analysis: PackAnalysis,
    text: String,
): List<String> =
    analysis.texts(DATA_MODIFY_INTENT) +
        analysis.candidates(DATA_MODIFY_INTENT).map { text.substring(it.startByte, it.endByte) }

private fun localModifyTexts(
    analysis: PackAnalysis,
    text: String,
): List<String> =
    analysis.texts(LOCAL_VALUE_MODIFY_INTENT) +
        analysis.candidates(LOCAL_VALUE_MODIFY_INTENT).map { text.substring(it.startByte, it.endByte) }

private fun readTexts(
    analysis: PackAnalysis,
    text: String,
): List<String> =
    buildList {
        analysis.source(SEARCH_INTENT).mapTo(this) { text.substring(it.startByte, it.endByte) }
        analysis.source(LOOKUP_INTENT).mapTo(this) { text.substring(it.startByte, it.endByte) }
        analysis.candidates(SEARCH_INTENT).mapTo(this) { text.substring(it.startByte, it.endByte) }
        analysis.candidates(LOOKUP_INTENT).mapTo(this) { text.substring(it.startByte, it.endByte) }
    }
