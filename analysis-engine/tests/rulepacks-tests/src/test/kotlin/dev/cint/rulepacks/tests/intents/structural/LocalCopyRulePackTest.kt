package dev.cint.rulepacks.tests.intents.structural

import dev.cint.api.CandidateReason
import dev.cint.rulepacks.bookkeeping.BookkeepingRulePack
import dev.cint.rulepacks.intents.dataaccess.DataAccessRulePack
import dev.cint.rulepacks.intents.dataaccess.SEARCH_INTENT
import dev.cint.rulepacks.intents.mutation.DATA_MODIFY_INTENT
import dev.cint.rulepacks.intents.mutation.LOCAL_VALUE_MODIFY_INTENT
import dev.cint.rulepacks.intents.mutation.MutationRulePack
import dev.cint.rulepacks.intents.structural.LOCAL_COPY_INTENT
import dev.cint.rulepacks.intents.structural.LOCAL_COPY_RULE_ID
import dev.cint.rulepacks.intents.structural.LocalCopyRulePack
import dev.cint.rulepacks.intents.structural.RETURN_VALUE_INTENT
import dev.cint.rulepacks.intents.structural.ReturnValueRulePack
import dev.cint.rulepacks.tests.support.PackAnalysis
import dev.cint.rulepacks.tests.support.analyzePack
import dev.cint.test.fixtures.text
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private fun analyze(
    language: String,
    text: String,
    packs: List<dev.cint.api.RulePack> = listOf(LocalCopyRulePack()),
): PackAnalysis = analyzePack(language, text, packs)

class LocalCopyRulePackTest {
    @Test
    fun packDeclaresOneOwnedRuleAcrossBundledLanguages() {
        val pack = LocalCopyRulePack()

        assertEquals("local-copy", pack.id)
        assertEquals(setOf("kotlin", "javascript", "typescript", "csharp"), pack.languages)
        val rule = pack.rules().single()
        assertEquals(LOCAL_COPY_RULE_ID, rule.id)
        assertEquals(LOCAL_COPY_INTENT, rule.owns)
        assertTrue(rule.reads.isEmpty())
    }

    @Test
    fun normalizedParameterCopiesEstablish() {
        val text =
            """
            class C {
                private var _db: Database? = null
                private var x: Int = 0

                fun configure(db: Database, x: Int) {
                    _db = db
                    this.x = x
                }
            }
            """.trimIndent()

        val analysis = analyze("kotlin", text)

        assertEquals(setOf("_db = db", "this.x = x"), analysis.texts(LOCAL_COPY_INTENT).toSet())
        assertTrue(analysis.source(LOCAL_COPY_INTENT).all { it.rule == LOCAL_COPY_RULE_ID })
        assertTrue(analysis.candidates(LOCAL_COPY_INTENT).isEmpty())
    }

    @Test
    fun copiesEstablishAcrossLanguages() {
        val cases =
            listOf(
                "javascript" to "class C { configure(db) { this.db = db; } }",
                "typescript" to "class C { configure(db: Database): void { this.db = db; } }",
                "csharp" to "class C { Database _db; void Configure(Database db) { _db = db; } }",
            )

        for ((language, text) in cases) {
            val texts = analyze(language, text).texts(LOCAL_COPY_INTENT)
            assertTrue(texts.any { it.contains("db") }, "$language: $texts")
        }
    }

    @Test
    fun fieldToFieldAndSelfCopiesDoNotEstablish() {
        val text =
            """
            class C {
                private var _db: Database? = null
                private var _x: Int = 0

                fun configure() {
                    this._db = _db
                    _x = _x
                }
            }
            """.trimIndent()

        val analysis = analyze("kotlin", text)

        assertTrue(analysis.source(LOCAL_COPY_INTENT).isEmpty())
        assertTrue(analysis.texts(LOCAL_COPY_INTENT).none { "_db = _db" in it || "_x = _x" in it })
        assertTrue(analysis.candidates(LOCAL_COPY_INTENT).none { "_x = _x" in text.substring(it.startByte, it.endByte) })
    }

    @Test
    fun declaredPropertySourcesDoNotEstablish() {
        val text =
            """
            class C {
                private var db: Database? = null
                private var _db: Database? = null

                fun configure() {
                    this._db = db
                }
            }
            """.trimIndent()

        val analysis = analyze("kotlin", text)

        assertTrue(analysis.source(LOCAL_COPY_INTENT).isEmpty())
        assertTrue(analysis.texts(LOCAL_COPY_INTENT).none { "this._db = db" in it })
    }

    @Test
    fun caseVariantsDoNotEstablish() {
        val text =
            """
            class C {
                private var _db: Database? = null

                fun configure(DB: Database) {
                    _db = DB
                }
            }
            """.trimIndent()

        val analysis = analyze("kotlin", text)

        assertTrue(analysis.source(LOCAL_COPY_INTENT).isEmpty())
    }

    @Test
    fun declarationAndLocalCopiesAreHandledSafely() {
        val text =
            """
            fun configure(db: Database) {
                val _db = db
                val copy = db
                var local = db
                local = db
            }
            """.trimIndent()

        val analysis = analyze("kotlin", text)

        assertTrue(analysis.texts(LOCAL_COPY_INTENT).any { "_db = db" in it })
        val candidates = analysis.candidates(LOCAL_COPY_INTENT).map { text.substring(it.startByte, it.endByte) }
        assertTrue(candidates.any { "copy = db" in it }, candidates.toString())
        assertTrue(candidates.any { "local = db" in it }, candidates.toString())
    }

    @Test
    fun generalMutationAndPersistenceDoNotEstablish() {
        val text =
            """
            fun f(user: User, db: Database) {
                user.name = "x"
                count = count + 1
                repository.save(db)
                x = x
            }
            """.trimIndent()

        val analysis = analyze("kotlin", text)

        assertTrue(analysis.source(LOCAL_COPY_INTENT).isEmpty())
        assertTrue(analysis.candidates(LOCAL_COPY_INTENT).isEmpty())
    }

    @Test
    fun configuredPrefixesExtendNormalization() {
        val pack = LocalCopyRulePack().configure(mapOf("kotlin" to mapOf("prefixes" to listOf("store_"))))
        val text = "class C { var store_db: Database? = null; fun f(db: Database) { store_db = db } }"

        val analysis = analyze("kotlin", text, listOf(pack))

        assertEquals(listOf("store_db = db"), analysis.texts(LOCAL_COPY_INTENT))
    }

    @Test
    fun ambiguousFieldAssignmentsStayCandidates() {
        val text = "class C { var database: Database? = null; fun f(db: Database) { this.database = db } }"

        val analysis = analyze("kotlin", text)
        val candidate = analysis.candidates(LOCAL_COPY_INTENT).single()

        assertTrue(analysis.source(LOCAL_COPY_INTENT).isEmpty())
        assertEquals(CandidateReason.LOW_CONFIDENCE, candidate.reason)
        assertEquals("rule:$LOCAL_COPY_RULE_ID", candidate.source)
        assertEquals("this.database = db", text.substring(candidate.startByte, candidate.endByte))
    }

    @Test
    fun structuralPacksKeepSearchReturnAndMutationBoundaries() {
        val text =
            """
            fun find(repository: Repository, db: Database): List<String> {
                var _db: Database? = null
                _db = db
                return repository.findAll()
            }
            """.trimIndent()

        val analysis =
            analyze(
                "kotlin",
                text,
                listOf(
                    BookkeepingRulePack(),
                    MutationRulePack(),
                    DataAccessRulePack(),
                    ReturnValueRulePack(),
                    LocalCopyRulePack(),
                ),
            )

        assertEquals(setOf("_db = db"), analysis.texts(LOCAL_COPY_INTENT).toSet())
        assertTrue(analysis.source(LOCAL_VALUE_MODIFY_INTENT).isEmpty())
        assertEquals(setOf("repository.findAll()"), analysis.texts(SEARCH_INTENT).toSet())
        assertEquals(setOf("repository.findAll()"), analysis.texts(RETURN_VALUE_INTENT).toSet())
        assertTrue(analysis.source(DATA_MODIFY_INTENT).isEmpty())
    }

    @Test
    fun mutationSuppressesConfiguredLocalCopiesByDefault() {
        val localCopy = LocalCopyRulePack().configure(mapOf("kotlin" to mapOf("prefixes" to listOf("store_"))))
        val text =
            """
            fun configure(db: Database) {
                var store_db: Database? = null
                store_db = db
            }
            """.trimIndent()

        val analysis = analyze("kotlin", text, listOf(BookkeepingRulePack(), MutationRulePack(), localCopy))

        assertEquals(setOf("store_db = db"), analysis.texts(LOCAL_COPY_INTENT).toSet())
        assertTrue(analysis.source(LOCAL_VALUE_MODIFY_INTENT).isEmpty())
    }

    @Test
    fun mutationPureCopySuppressionCanBeDisabled() {
        val mutation =
            MutationRulePack().configure(
                mapOf("kotlin" to mapOf("ignorePureCopies" to false)),
            )
        val localCopy = LocalCopyRulePack().configure(mapOf("kotlin" to mapOf("prefixes" to listOf("store_"))))
        val text =
            """
            fun configure(db: Database) {
                var store_db: Database? = null
                store_db = db
            }
            """.trimIndent()

        val analysis = analyze("kotlin", text, listOf(BookkeepingRulePack(), mutation, localCopy))

        assertEquals(setOf("store_db = db"), analysis.texts(LOCAL_COPY_INTENT).toSet())
        assertEquals(setOf("store_db = db"), analysis.texts(LOCAL_VALUE_MODIFY_INTENT).toSet())
    }
}
