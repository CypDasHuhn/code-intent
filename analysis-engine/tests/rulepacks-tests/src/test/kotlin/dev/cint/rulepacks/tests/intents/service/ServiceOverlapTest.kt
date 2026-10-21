package dev.cint.rulepacks.tests.intents.service

import dev.cint.api.CINT_API_VERSION
import dev.cint.api.Intent
import dev.cint.api.Rule
import dev.cint.api.RuleEmit
import dev.cint.api.RulePack
import dev.cint.api.View
import dev.cint.api.emit
import dev.cint.rulepacks.errorhandling.ErrorHandlingRulePack
import dev.cint.rulepacks.intents.service.SERVICE_DELEGATION_INTENT
import dev.cint.rulepacks.intents.service.ServiceRulePack
import dev.cint.rulepacks.tests.support.analyzePack
import dev.cint.test.fixtures.text
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ServiceOverlapTest {
    @Test
    fun delegationCoexistsWithSearchOnTheForwardedCall() {
        val source =
            """
            class UserController(private val repository: UserRepository) {
                fun find(id: String): User {
                    return repository.findById(id)
                }
            }
            """.trimIndent()
        val packs =
            listOf(
                ServiceRulePack(),
                overlapPack(callRule("SEARCH", "findById")),
            )

        val analysis = analyzePack("kotlin", source, packs)
        val delegation = analysis.source(SERVICE_DELEGATION_INTENT).single()

        assertEquals("repository.findById(id)", delegation.text(source))
        assertTrue("SEARCH" in delegation.intents)
        assertEquals(setOf("SEARCH", SERVICE_DELEGATION_INTENT), delegation.intents)
    }

    @Test
    fun delegationDoesNotSuppressDataModifyOnTheDelegatedCall() {
        val source =
            """
            class UserController(private val repository: UserRepository) {
                fun save(value: User) {
                    repository.save(value)
                }
            }
            """.trimIndent()
        val packs =
            listOf(
                ServiceRulePack(),
                overlapPack(callRule("DATA_MODIFY", "save")),
            )

        val analysis = analyzePack("kotlin", source, packs)
        val delegation = analysis.source(SERVICE_DELEGATION_INTENT).single()

        assertEquals("repository.save(value)", delegation.text(source))
        assertEquals(setOf("DATA_MODIFY", SERVICE_DELEGATION_INTENT), delegation.intents)
    }

    @Test
    fun mixedBodyStaysCandidateWhileInnerIntentsRemainEstablished() {
        val source =
            """
            class UserController(private val repository: UserRepository) {
                fun update(id: String, value: String) {
                    val current = repository.find(id)
                    if (current == null) throw IllegalArgumentException("missing")
                    repository.save(current.copy(value = value))
                    audit(id)
                }
            }
            """.trimIndent()
        val packs =
            listOf(
                ServiceRulePack(),
                ErrorHandlingRulePack(),
                overlapPack(callRule("SEARCH", "find"), callRule("DATA_MODIFY", "save")),
            )

        val analysis = analyzePack("kotlin", source, packs)

        assertTrue(analysis.source(SERVICE_DELEGATION_INTENT).isEmpty())
        assertEquals(2, analysis.candidates(SERVICE_DELEGATION_INTENT).size)
        assertTrue(analysis.source("SEARCH").isNotEmpty())
        assertTrue(analysis.source("DATA_MODIFY").isNotEmpty())
        assertTrue(analysis.source("ERROR_HANDLING").isNotEmpty())
    }

    private fun callRule(
        intent: Intent,
        vararg callees: String,
    ): Rule = CalleeRule("overlap:${intent.lowercase()}", intent, callees.toSet())

    private fun overlapPack(vararg rules: Rule): RulePack = OverlapPack("overlap", rules.toList())

    private class CalleeRule(
        override val id: String,
        override val owns: Intent,
        private val callees: Set<String>,
    ) : Rule {
        override val reads: Set<Intent> = emptySet()

        override fun run(view: View): List<RuleEmit> =
            view.calls().mapNotNull { call ->
                val callee = call.callee ?: return@mapNotNull null
                if (callee in callees) call.emit(owns) else null
            }
    }

    private class OverlapPack(
        override val id: String,
        private val entries: List<Rule>,
    ) : RulePack {
        override val languages: Set<String> = setOf("kotlin", "javascript", "typescript", "csharp")
        override val apiVersion: Int = CINT_API_VERSION

        override fun rules(): List<Rule> = entries
    }
}
