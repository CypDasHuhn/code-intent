package dev.cint.rulepacks.tests.intents.dataaccess

import dev.cint.rulepacks.intents.dataaccess.DataAccessRulePack
import dev.cint.rulepacks.intents.dataaccess.LOOKUP_INTENT
import dev.cint.rulepacks.intents.dataaccess.SEARCH_INTENT
import kotlin.test.Test
import kotlin.test.assertEquals
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
                val direct = statusById.lookup(id)
                val tableValue = lookupTable.resolve(id)
            }
            """.trimIndent()

        val texts = dataAccess("kotlin", text).texts(LOOKUP_INTENT)

        assertTrue(texts.any { it.startsWith("statusById.getValue") })
        assertTrue(texts.any { it.startsWith("statusByCode.getOrDefault") })
        assertTrue(texts.any { it.startsWith("statusById.lookup") })
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
                    var direct = StatusById.Lookup(id);
                }
            }
            """.trimIndent()

        val texts = dataAccess("csharp", text).texts(LOOKUP_INTENT)

        assertTrue(texts.any { it == "StatusById[id]" })
        assertTrue(texts.any { it == "dictionary[id]" })
        assertTrue(texts.any { it.startsWith("StatusById.Lookup") })
    }

    @Test
    fun lookupDoesNotBecomeSearchByAccident() {
        val text =
            """
            fun f(id: String) {
                val status = statusById[id]
                val direct = statusById.lookup(id)
            }
            """.trimIndent()

        val analysis = dataAccess("kotlin", text)

        assertTrue(analysis.source(LOOKUP_INTENT).isNotEmpty())
        analysis.assertNoSource(SEARCH_INTENT)
    }

    @Test
    fun enumValueAndConstantCallsEmitLookup() {
        val text =
            """
            fun f(code: String) {
                val status = Status.valueOf("ACTIVE")
                val mode = Mode.fromCode(code)
            }
            """.trimIndent()

        val analysis = dataAccess("kotlin", text)

        assertTrue(analysis.texts(LOOKUP_INTENT).any { it.startsWith("Status.valueOf") })
        assertTrue(analysis.texts(LOOKUP_INTENT).any { it.startsWith("Mode.fromCode") })
    }

    @Test
    fun stringValueOfStaysOutOfLookup() {
        val text =
            """
            fun f(value: Int) {
                val rendered = String.valueOf(value)
            }
            """.trimIndent()

        val analysis = dataAccess("kotlin", text)

        analysis.assertNoSource(LOOKUP_INTENT)
        assertTrue(analysis.candidateTexts(LOOKUP_INTENT).isEmpty())
    }

    @Test
    fun javascriptAndTypescriptIndexLookupEmitLookup() {
        val javascript =
            """
            function f(id) {
                const status = statusById[id];
                const value = map.get(id);
                const bitmapValue = bitmap[id];
            }
            """.trimIndent()
        val javascriptTexts = dataAccess("javascript", javascript).texts(LOOKUP_INTENT)
        assertTrue(javascriptTexts.any { it == "statusById[id]" })
        assertTrue(javascriptTexts.any { it.startsWith("map.get") })
        assertTrue(javascriptTexts.none { it == "bitmap[id]" })

        val typescript =
            """
            function f(id: string) {
                const status = statusById[id];
                const value = map.get(id);
            }
            """.trimIndent()
        val typescriptTexts = dataAccess("typescript", typescript).texts(LOOKUP_INTENT)
        assertTrue(typescriptTexts.any { it == "statusById[id]" })
        assertTrue(typescriptTexts.any { it.startsWith("map.get") })
    }

    @Test
    fun additiveKnownMapOverlayKeepsDefaults() {
        val pack =
            DataAccessRulePack().configureAdditive(
                mapOf("kotlin" to mapOf("knownMaps" to listOf("myTable"))),
            )
        val text =
            """
            fun f(repo: UserRepository, id: String) {
                val mine = myTable[id]
                val status = statusById[id]
                repo.findAll()
            }
            """.trimIndent()

        val analysis = dataAccess("kotlin", text, pack)

        val lookupTexts = analysis.texts(LOOKUP_INTENT)
        assertTrue(lookupTexts.any { it == "myTable[id]" })
        assertTrue(lookupTexts.any { it == "statusById[id]" })
        assertTrue(analysis.texts(SEARCH_INTENT).any { it.startsWith("repo.findAll") })
    }

    @Test
    fun knownMapWritesAreNotLookup() {
        val text =
            """
            fun f(id: String, status: Int) {
                statusById[id] = status
                statusById[id] += 1
                statusById[id]++
            }
            """.trimIndent()

        val analysis = dataAccess("kotlin", text)

        analysis.assertNoSource(LOOKUP_INTENT)
    }

    @Test
    fun enumStyleReceiversWithoutKnownSuffixBecomeCandidates() {
        val text =
            """
            fun f(code: String) {
                Color.valueOf("RED")
                Role.fromCode(code)
                Currency.valueOf(code)
                Priority.fromCode(code)
            }
            """.trimIndent()

        val analysis = dataAccess("kotlin", text)

        analysis.assertNoSource(LOOKUP_INTENT)
        val candidates = analysis.candidateTexts(LOOKUP_INTENT)
        assertTrue(candidates.any { it.startsWith("Color.valueOf") })
        assertTrue(candidates.any { it.startsWith("Role.fromCode") })
        assertTrue(candidates.any { it.startsWith("Currency.valueOf") })
        assertTrue(candidates.any { it.startsWith("Priority.fromCode") })
    }

    @Test
    fun configuredEnumTypesEstablishNonSuffixEnums() {
        val pack =
            DataAccessRulePack().configure(
                mapOf("kotlin" to mapOf("enumTypes" to listOf("Color"))),
            )
        val text =
            """
            fun f(code: String) {
                Color.valueOf("RED")
                Role.fromCode(code)
            }
            """.trimIndent()

        val analysis = dataAccess("kotlin", text, pack)

        assertTrue(analysis.texts(LOOKUP_INTENT).any { it.startsWith("Color.valueOf") })
        assertTrue(analysis.candidateTexts(LOOKUP_INTENT).any { it.startsWith("Role.fromCode") })
    }

    @Test
    fun knownMapReadsInsideWriteExpressionsStayLookup() {
        val text =
            """
            fun f(id: String, value: Int, copy: Int, active: String) {
                copy = statusById[id]
                copy += statusByCode[active]
                codeMap[id].field = value
                translationTable[statusById[id]] = value
            }
            """.trimIndent()

        val texts = dataAccess("kotlin", text).texts(LOOKUP_INTENT)

        assertTrue(texts.any { it == "statusById[id]" })
        assertTrue(texts.any { it == "statusByCode[active]" })
        assertTrue(texts.any { it == "codeMap[id]" })
        assertTrue(texts.none { it.startsWith("translationTable[") })
        assertEquals(2, texts.count { it == "statusById[id]" })
    }

    @Test
    fun structuralIncrementAndDecrementTargetsAreNotLookup() {
        val cases =
            mapOf(
                "kotlin" to
                    """
                    fun f(counts: IntArray, i: Int, statusById: MutableMap<String, Int>) {
                        counts[i - 1]++
                        statusById["x-y"]++
                        --statusById["x-y"]
                    }
                    """.trimIndent(),
                "javascript" to
                    """
                    function f(counts, i, statusById) {
                        counts[i - 1]++;
                        statusById["x-y"]++;
                        --statusById["x-y"];
                    }
                    """.trimIndent(),
                "typescript" to
                    """
                    function f(counts: number[], i: number, statusById: Map<string, number>) {
                        counts[i - 1]++;
                        statusById["x-y"]++;
                        --statusById["x-y"];
                    }
                    """.trimIndent(),
                "csharp" to
                    """
                    class C {
                        void F(int[] counts, int i, Dictionary<string, int> statusById) {
                            counts[i - 1]++;
                            statusById["x-y"]++;
                            --statusById["x-y"];
                        }
                    }
                    """.trimIndent(),
            )

        for ((language, text) in cases) {
            dataAccess(language, text).assertNoSource(LOOKUP_INTENT)
        }
    }

    @Test
    fun readsInsideStructuralIncrementAndAssignmentTargetsStayLookup() {
        val text =
            """
            fun f(id: String, value: Int) {
                statusById[otherById[id] - 1]++
                translationTable[statusById[id]] = value
            }
            """.trimIndent()

        val texts = dataAccess("kotlin", text).texts(LOOKUP_INTENT)

        assertTrue(texts.any { it == "otherById[id]" })
        assertTrue(texts.any { it == "statusById[id]" })
        assertTrue(texts.none { it.startsWith("statusById[otherById") })
        assertTrue(texts.none { it.startsWith("translationTable[") })
    }

    @Test
    fun mixedWriteTargetExcludesOnlyAssignedIndex() {
        val text =
            """
            fun f(id: String) {
                statusById[id] = otherMap[id]
            }
            """.trimIndent()

        val texts = dataAccess("kotlin", text).texts(LOOKUP_INTENT)

        assertTrue(texts.none { it == "statusById[id]" })
        assertTrue(texts.any { it == "otherMap[id]" })
    }
}
