package dev.cint.rulepacks.tests.intents.dataaccess

import dev.cint.rulepacks.intents.dataaccess.DATA_ACCESS_PACK_ID
import dev.cint.rulepacks.intents.dataaccess.DataAccessRulePack
import dev.cint.rulepacks.intents.dataaccess.LOOKUP_INTENT
import dev.cint.rulepacks.intents.dataaccess.LOOKUP_RULE_ID
import dev.cint.rulepacks.intents.dataaccess.SEARCH_INTENT
import dev.cint.rulepacks.intents.dataaccess.SEARCH_RULE_ID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DataAccessSearchTest {
    @Test
    fun packDeclaresSearchAndLookupRules() {
        val pack = DataAccessRulePack()

        assertEquals(DATA_ACCESS_PACK_ID, pack.id)
        assertEquals(setOf("kotlin", "javascript", "typescript", "csharp"), pack.languages)
        assertEquals(setOf(SEARCH_RULE_ID, LOOKUP_RULE_ID), pack.rules().map { it.id }.toSet())
        assertEquals(SEARCH_INTENT, pack.rules().first { it.id == SEARCH_RULE_ID }.owns)
        assertEquals(LOOKUP_INTENT, pack.rules().first { it.id == LOOKUP_RULE_ID }.owns)
        assertTrue(pack.rules().all { it.reads.isEmpty() })
    }

    @Test
    fun configuredRepositoryAndQueryCallsEmitSearch() {
        val text =
            """
            fun f(repo: UserRepository) {
                repo.findAll()
                repo.findById("1")
                repo.search("name")
                repo.query().where("id = 1").filter { true }
            }
            """.trimIndent()

        val texts = dataAccess("kotlin", text).texts(SEARCH_INTENT)

        assertTrue(texts.any { it.startsWith("repo.findAll()") })
        assertTrue(texts.any { it.startsWith("repo.findById") })
        assertTrue(texts.any { it.startsWith("repo.search") })
        assertTrue(texts.any { it.startsWith("repo.query().where") })
        assertTrue(texts.any { it.startsWith("repo.query().where(\"id = 1\").filter") })
    }

    @Test
    fun searchIsLanguageSpecific() {
        val javascript =
            """
            function f(repo) {
                repo.findAll();
                repo.search("name");
                repo.save(user);
            }
            """.trimIndent()
        val javascriptTexts = dataAccess("javascript", javascript).texts(SEARCH_INTENT)
        assertTrue(javascriptTexts.any { it.startsWith("repo.findAll()") })
        assertTrue(javascriptTexts.any { it.startsWith("repo.search") })
        assertTrue(javascriptTexts.none { it.startsWith("repo.save") })

        val csharp =
            """
            class C {
                void F(UserRepository repo) {
                    repo.FindAll();
                    repo.FindById("1");
                    repo.Save(user);
                }
            }
            """.trimIndent()
        val csharpTexts = dataAccess("csharp", csharp).texts(SEARCH_INTENT)
        assertTrue(csharpTexts.any { it.startsWith("repo.FindAll()") })
        assertTrue(csharpTexts.any { it.startsWith("repo.FindById") })
        assertTrue(csharpTexts.none { it.startsWith("repo.Save") })
    }

    @Test
    fun configuredSearchCallOverrideIsApplied() {
        val pack =
            DataAccessRulePack().configure(
                mapOf("kotlin" to mapOf("searchCalls" to listOf("scanAll"))),
            )
        val text =
            """
            fun f(repo: UserRepository) {
                repo.scanAll("x")
                repo.findAll()
            }
            """.trimIndent()

        val texts = dataAccess("kotlin", text, pack).texts(SEARCH_INTENT)

        assertTrue(texts.any { it.startsWith("repo.scanAll") })
        assertTrue(texts.none { it.startsWith("repo.findAll") })
    }

    @Test
    fun ambiguousGetsAndWrappersBecomeSearchCandidatesOnly() {
        val text =
            """
            fun f(repo: UserRepository, id: String) {
                repo.get(id)
                repo.find(id)
                fetchUser(id)
                loadUser(id)
            }
            """.trimIndent()

        val analysis = dataAccess("kotlin", text)

        analysis.assertNoSource(SEARCH_INTENT)
        val candidates = analysis.candidateTexts(SEARCH_INTENT)
        assertTrue(candidates.any { it.startsWith("repo.get") })
        assertTrue(candidates.any { it.startsWith("repo.find") })
        assertTrue(candidates.any { it.startsWith("fetchUser") })
        assertTrue(candidates.any { it.startsWith("loadUser") })
    }

    @Test
    fun configuredSearchCallWithUnknownReceiverStaysCandidate() {
        val text =
            """
            fun f(things: List<String>) {
                things.filter { it.isNotEmpty() }
            }
            """.trimIndent()

        val analysis = dataAccess("kotlin", text)

        analysis.assertNoSource(SEARCH_INTENT)
        assertTrue(analysis.candidateTexts(SEARCH_INTENT).any { it.startsWith("things.filter") })
    }

    @Test
    fun commonPerLanguageRetrievalIdiomsEmitSearch() {
        val kotlin =
            """
            fun f(query: Query, entityManager: EntityManager) {
                query.getResultList()
                entityManager.createQuery("select u from User u").getSingleResult()
            }
            """.trimIndent()
        val kotlinTexts = dataAccess("kotlin", kotlin).texts(SEARCH_INTENT)
        assertTrue(kotlinTexts.any { it.startsWith("query.getResultList") })
        assertTrue(kotlinTexts.any { it.startsWith("entityManager.createQuery") })
        assertTrue(
            kotlinTexts.any {
                it.startsWith("entityManager.createQuery") && it.contains("getSingleResult")
            },
        )

        val javascript =
            """
            function f(prisma) {
                prisma.user.findMany({});
                prisma.user.findUnique({ where: { id: 1 } });
            }
            """.trimIndent()
        val javascriptTexts = dataAccess("javascript", javascript).texts(SEARCH_INTENT)
        assertTrue(javascriptTexts.any { it.startsWith("prisma.user.findMany") })
        assertTrue(javascriptTexts.any { it.startsWith("prisma.user.findUnique") })

        val typescript =
            """
            function f(prisma: PrismaClient) {
                prisma.user.findMany({});
            }
            """.trimIndent()
        assertTrue(dataAccess("typescript", typescript).texts(SEARCH_INTENT).any { it.startsWith("prisma.user.findMany") })

        val csharp =
            """
            class C {
                void F(Context context) {
                    context.Users.FirstOrDefault();
                    context.Users.SingleOrDefault();
                    context.Users.ToListAsync();
                }
            }
            """.trimIndent()
        val csharpTexts = dataAccess("csharp", csharp).texts(SEARCH_INTENT)
        assertTrue(csharpTexts.any { it.startsWith("context.Users.FirstOrDefault") })
        assertTrue(csharpTexts.any { it.startsWith("context.Users.SingleOrDefault") })
        assertTrue(csharpTexts.any { it.startsWith("context.Users.ToListAsync") })
    }
}
