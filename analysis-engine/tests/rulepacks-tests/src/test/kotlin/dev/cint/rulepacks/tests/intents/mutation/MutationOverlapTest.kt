package dev.cint.rulepacks.tests.intents.mutation

import dev.cint.api.CINT_API_VERSION
import dev.cint.api.Intent
import dev.cint.api.Rule
import dev.cint.api.RuleEmit
import dev.cint.api.RulePack
import dev.cint.api.View
import dev.cint.api.emit
import dev.cint.rulepacks.bookkeeping.BookkeepingRulePack
import dev.cint.rulepacks.intents.mutation.DATA_MODIFY_INTENT
import dev.cint.rulepacks.intents.mutation.LOCAL_VALUE_MODIFY_INTENT
import dev.cint.rulepacks.intents.mutation.MutationRulePack
import dev.cint.rulepacks.tests.support.analyzePack
import dev.cint.test.fixtures.text
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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

        val analysis = analyzePack("kotlin", text, listOf(BookkeepingRulePack(), MutationRulePack()))

        assertTrue(analysis.texts("BOOKKEEPING").contains("skipped++"))
        assertEquals(setOf("items.add(\"x\")"), analysis.texts(LOCAL_VALUE_MODIFY_INTENT).toSet())
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

        val analysis = analyzePack("kotlin", text, listOf(MutationRulePack(), search))

        assertEquals(setOf("repository.findByStatus(status)"), analysis.texts("SEARCH").toSet())
        assertEquals(setOf("repository.save(found)"), analysis.texts(DATA_MODIFY_INTENT).toSet())
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

        val analysis = analyzePack("kotlin", text, listOf(MutationRulePack(), delegation))

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
