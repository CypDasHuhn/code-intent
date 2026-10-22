package dev.cint.rulepacks.tests.intents.mutation

import dev.cint.api.CINT_API_VERSION
import dev.cint.api.Intent
import dev.cint.api.Rule
import dev.cint.api.RuleEmit
import dev.cint.api.RulePack
import dev.cint.api.View
import dev.cint.api.emit
import dev.cint.core.facts.model.SourceFile
import dev.cint.core.facts.oracle.TypeFact
import dev.cint.core.facts.oracle.TypeOracle
import dev.cint.rulepacks.bookkeeping.BookkeepingConfig
import dev.cint.rulepacks.bookkeeping.BookkeepingRulePack
import dev.cint.rulepacks.intents.mutation.DATA_MODIFY_INTENT
import dev.cint.rulepacks.intents.mutation.LOCAL_VALUE_MODIFY_INTENT
import dev.cint.rulepacks.intents.mutation.MutationRulePack
import dev.cint.rulepacks.intents.structural.localcopy.LocalCopyRulePack
import dev.cint.rulepacks.tests.support.analyzePack
import dev.cint.test.fixtures.text
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private class RepositoryOracle : TypeOracle {
    override fun typeAt(
        file: SourceFile,
        byteOffset: Int,
    ): TypeFact = TypeFact("org.springframework.data.repository.Repository")
}

class MutationOverlapTest {
    @Test
    fun bookkeepingWritesStayBookkeepingAndDoNotBecomeLocalMutation() {
        val text =
            """
            class C {
                @Bookkeeping
                var skipped = 0

                fun f() {
                    val items = mutableListOf<String>()
                    skipped++
                    items.add("x")
                }
            }
            """.trimIndent()

        val analysis = analyzePack("kotlin", text, listOf(BookkeepingRulePack(), MutationRulePack(), LocalCopyRulePack()))

        assertTrue(analysis.texts("BOOKKEEPING").contains("skipped++"))
        assertEquals(setOf("items.add(\"x\")"), analysis.texts(LOCAL_VALUE_MODIFY_INTENT).toSet())
    }

    @Test
    fun configuredBookkeepingMarkersKeepTheirPrecedence() {
        val annotationText =
            """
            class C {
                @Tracked
                var skipped = 0

                fun f() {
                    skipped++
                }
            }
            """.trimIndent()
        val annotationPack =
            BookkeepingRulePack(BookkeepingConfig(annotation = "Tracked", magicComment = "tracked"))
        val annotationAnalysis = analyzePack("kotlin", annotationText, listOf(annotationPack, MutationRulePack(), LocalCopyRulePack()))

        assertTrue(annotationAnalysis.texts("BOOKKEEPING").contains("skipped++"))
        assertTrue(annotationAnalysis.source(LOCAL_VALUE_MODIFY_INTENT).isEmpty())

        val commentText =
            """
            class C {
                // cint:tracked
                var skipped = 0

                fun f() {
                    skipped++
                }
            }
            """.trimIndent()
        val commentAnalysis = analyzePack("kotlin", commentText, listOf(annotationPack, MutationRulePack(), LocalCopyRulePack()))

        assertTrue(commentAnalysis.texts("BOOKKEEPING").contains("skipped++"))
        assertTrue(commentAnalysis.source(LOCAL_VALUE_MODIFY_INTENT).isEmpty())
    }

    @Test
    fun bookkeepingSuppressionCanBeDisabled() {
        val text =
            """
            class C {
                @Bookkeeping
                var skipped = 0

                fun f() {
                    skipped++
                }
            }
            """.trimIndent()
        val pack =
            MutationRulePack().configure(
                mapOf("kotlin" to mapOf("ignoreBookkeeping" to false)),
            )

        val analysis = analyzePack("kotlin", text, listOf(BookkeepingRulePack(), pack, LocalCopyRulePack()))
        val localSpans =
            analysis.texts(LOCAL_VALUE_MODIFY_INTENT) +
                analysis.candidates(LOCAL_VALUE_MODIFY_INTENT).map { text.substring(it.startByte, it.endByte) }

        assertTrue("skipped++" in localSpans)
    }

    @Test
    fun mutableOperationOnAPersistenceTypeEstablishesOnlyDataModify() {
        val text =
            """
            fun f(item: Item, items: MutableList<Item>) {
                items.delete(item)
            }
            """.trimIndent()

        val analysis =
            analyzePack(
                "kotlin",
                text,
                listOf(BookkeepingRulePack(), MutationRulePack(), LocalCopyRulePack()),
                RepositoryOracle(),
            )

        assertEquals(setOf("items.delete(item)"), analysis.texts(DATA_MODIFY_INTENT).toSet())
        assertTrue(analysis.source(LOCAL_VALUE_MODIFY_INTENT).isEmpty())
    }

    @Test
    fun configuredDerivedDeleteCanCoOccurWithSearch() {
        val text =
            """
            fun f(status: Status) {
                repository.deleteByStatus(status)
            }
            """.trimIndent()
        val mutation =
            MutationRulePack().configure(
                mapOf("kotlin" to mapOf("persistenceCalls" to listOf("deleteByStatus"))),
            )
        val search = CallIntentPack("search", "SEARCH", setOf("repository.deleteByStatus(status)"))

        val analysis = analyzePack("kotlin", text, listOf(BookkeepingRulePack(), mutation, LocalCopyRulePack(), search))

        val annotation = analysis.source(DATA_MODIFY_INTENT).single()
        assertEquals(setOf(DATA_MODIFY_INTENT, "SEARCH"), annotation.intents)
        assertEquals(setOf("repository.deleteByStatus(status)"), analysis.texts("SEARCH").toSet())
    }

    @Test
    fun searchAndDataModifyCoexistIndependently() {
        val text =
            """
            fun f(status: Status) {
                val found = repository.findByStatus(status)
                repository.save(found)
            }
            """.trimIndent()
        val search = CallIntentPack("search", "SEARCH", setOf("repository.findByStatus(status)"))

        val analysis = analyzePack("kotlin", text, listOf(BookkeepingRulePack(), MutationRulePack(), LocalCopyRulePack(), search))

        assertEquals(setOf("repository.findByStatus(status)"), analysis.texts("SEARCH").toSet())
        assertEquals(setOf("repository.save(found)"), analysis.texts(DATA_MODIFY_INTENT).toSet())
        assertTrue(analysis.candidates(DATA_MODIFY_INTENT).isEmpty())
    }

    @Test
    fun serviceDelegationCanCoOccurOnTheSamePersistenceCall() {
        val text =
            """
            fun f(user: User) {
                repository.save(user)
            }
            """.trimIndent()
        val delegation =
            CallIntentPack("service", "SERVICE_DELEGATION", setOf("repository.save(user)"))

        val analysis = analyzePack("kotlin", text, listOf(BookkeepingRulePack(), MutationRulePack(), LocalCopyRulePack(), delegation))

        val annotation = analysis.source(DATA_MODIFY_INTENT).single()
        assertEquals(setOf(DATA_MODIFY_INTENT, "SERVICE_DELEGATION"), annotation.intents)
        assertEquals("repository.save(user)", annotation.text(text))
        assertEquals(setOf("repository.save(user)"), analysis.texts("SERVICE_DELEGATION").toSet())
    }
}

private class CallIntentPack(
    private val packId: String,
    private val intent: Intent,
    private val callTexts: Set<String>,
) : RulePack {
    override val id: String = packId
    override val languages: Set<String> = setOf("kotlin", "javascript", "typescript", "csharp")
    override val apiVersion: Int = CINT_API_VERSION

    override fun rules(): List<Rule> =
        listOf(
            object : Rule {
                override val id: String = "$packId:rule"
                override val owns: Intent = intent
                override val reads: Set<Intent> = emptySet()

                override fun run(view: View): List<RuleEmit> =
                    view
                        .calls()
                        .filter { it.site.text in callTexts }
                        .map { it.emit(intent) }
            },
        )
}
