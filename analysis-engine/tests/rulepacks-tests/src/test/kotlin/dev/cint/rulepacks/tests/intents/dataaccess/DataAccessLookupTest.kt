package dev.cint.rulepacks.tests.intents.dataaccess

import dev.cint.rulepacks.intents.dataaccess.DataAccessRulePack
import dev.cint.rulepacks.intents.dataaccess.LOOKUP_INTENT
import dev.cint.rulepacks.intents.dataaccess.SEARCH_INTENT
import kotlin.test.Test
import kotlin.test.assertTrue

class DataAccessLookupTest {
    @Test
    fun knownMapIndexesAndEnumLikeMapsEmitLookup() {
        val text =
            """
            fun f(id: String) {
                val status = statusById[id]
                val code = statusByCode["active"]
                val label = translationTable[key]
                val setting = config["mode"]
            }
            """.trimIndent()

        val texts = dataAccess("kotlin", text).texts(LOOKUP_INTENT)

        assertTrue(texts.any { it == "statusById[id]" })
        assertTrue(texts.any { it == "statusByCode[\"active\"]" })
        assertTrue(texts.any { it == "translationTable[key]" })
        assertTrue(texts.any { it == "config[\"mode\"]" })
    }

    @Test
    fun knownMapAndConfiguredLookupCallsEmitLookup() {
        val text =
            """
            fun f(id: String) {
                val status = statusById.getValue(id)
                val fallback = statusByCode.getOrDefault("active", 0)
                val direct = lookup(id)
                val tableValue = lookupTable.resolve(id)
            }
            """.trimIndent()

        val texts = dataAccess("kotlin", text).texts(LOOKUP_INTENT)

        assertTrue(texts.any { it.startsWith("statusById.getValue") })
        assertTrue(texts.any { it.startsWith("statusByCode.getOrDefault") })
        assertTrue(texts.any { it == "lookup(id)" })
        assertTrue(texts.any { it.startsWith("lookupTable.resolve") })
    }

    @Test
    fun ambiguousGetsAndWrappersBecomeLookupCandidatesOnly() {
        val text =
            """
            fun f(repo: UserRepository, id: String) {
                repo.get(id)
                repo.find(id)
                fetchValue(id)
                loadValue(id)
            }
            """.trimIndent()

        val analysis = dataAccess("kotlin", text)

        analysis.assertNoSource(LOOKUP_INTENT)
        val candidates = analysis.candidateTexts(LOOKUP_INTENT)
        assertTrue(candidates.any { it.startsWith("repo.get") })
        assertTrue(candidates.any { it.startsWith("repo.find") })
        assertTrue(candidates.any { it.startsWith("fetchValue") })
        assertTrue(candidates.any { it.startsWith("loadValue") })
    }

    @Test
    fun unknownIndexesAreNotLookup() {
        val text =
            """
            fun f(values: Array<String>) {
                val first = values[0]
            }
            """.trimIndent()

        val analysis = dataAccess("kotlin", text)

        analysis.assertNoSource(LOOKUP_INTENT)
        assertTrue(analysis.candidateTexts(LOOKUP_INTENT).isEmpty())
    }

    @Test
    fun configuredKnownMapOverrideIsApplied() {
        val pack =
            DataAccessRulePack().configure(
                mapOf(
                    "kotlin" to
                        mapOf(
                            "knownMaps" to listOf("myTable"),
                            "knownMapSuffixes" to listOf("CustomTable"),
                        ),
                ),
            )
        val text =
            """
            fun f(id: String) {
                val value = myTable[id]
                val status = statusById[id]
            }
            """.trimIndent()

        val texts = dataAccess("kotlin", text, pack).texts(LOOKUP_INTENT)

        assertTrue(texts.any { it == "myTable[id]" })
        assertTrue(texts.none { it == "statusById[id]" })
    }

    @Test
    fun csharpElementAccessAndDatabaseLookupEmitLookup() {
        val text =
            """
            class C {
                void F(string id, Dictionary<string, int> dictionary) {
                    var byId = StatusById[id];
                    var value = dictionary[id];
                    var direct = Lookup(id);
                }
            }
            """.trimIndent()

        val texts = dataAccess("csharp", text).texts(LOOKUP_INTENT)

        assertTrue(texts.any { it == "StatusById[id]" })
        assertTrue(texts.any { it == "dictionary[id]" })
        assertTrue(texts.any { it == "Lookup(id)" })
    }

    @Test
    fun lookupDoesNotBecomeSearchByAccident() {
        val text =
            """
            fun f(id: String) {
                val status = statusById[id]
                val direct = lookup(id)
            }
            """.trimIndent()

        val analysis = dataAccess("kotlin", text)

        assertTrue(analysis.source(LOOKUP_INTENT).isNotEmpty())
        analysis.assertNoSource(SEARCH_INTENT)
    }
}
