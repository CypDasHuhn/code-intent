package dev.cint.rulepacks.tests.intents.service

import dev.cint.api.CandidateReason
import dev.cint.rulepacks.intents.service.SERVICE_DELEGATION_INTENT
import dev.cint.rulepacks.intents.service.SERVICE_DELEGATION_RULE_ID
import dev.cint.rulepacks.intents.service.SERVICE_INIT_INTENT
import dev.cint.rulepacks.intents.service.SERVICE_INIT_RULE_ID
import dev.cint.rulepacks.intents.service.ServiceConfig
import dev.cint.rulepacks.intents.service.ServiceRulePack
import dev.cint.rulepacks.tests.support.analyzePack
import dev.cint.test.fixtures.text
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ServiceDelegationRulePackTest {
    @Test
    fun packDeclaresBothServiceRules() {
        val pack = ServiceRulePack()

        assertEquals("service", pack.id)
        assertEquals(setOf("kotlin", "javascript", "typescript", "csharp"), pack.languages)
        assertEquals(setOf("service:delegation", "service:init"), pack.rules().map { it.id }.toSet())
        assertEquals(SERVICE_DELEGATION_INTENT, pack.rules().single { it.id == SERVICE_DELEGATION_RULE_ID }.owns)
        assertEquals(SERVICE_INIT_INTENT, pack.rules().single { it.id == SERVICE_INIT_RULE_ID }.owns)
        assertTrue(pack.rules().all { it.reads.isEmpty() })
    }

    @Test
    fun thinForwardingMethodEmitsDelegationAtTheCall() {
        val source =
            """
            class UserController(private val service: UserService) {
                fun find(id: String): User {
                    return service.findById(id)
                }
            }
            """.trimIndent()

        val analysis = analyzePack("kotlin", source, listOf(ServiceRulePack()))
        val delegation = analysis.source(SERVICE_DELEGATION_INTENT).single()

        assertEquals("service.findById(id)", delegation.text(source))
        assertEquals("heuristic", delegation.source)
    }

    @Test
    fun expressionBodyForwardingAlsoEmitsDelegation() {
        val source =
            """
            class UserController(private val service: UserService) {
                fun find(id: String) = service.findById(id)
            }
            """.trimIndent()

        val analysis = analyzePack("kotlin", source, listOf(ServiceRulePack()))
        val delegation = analysis.source(SERVICE_DELEGATION_INTENT).single()

        assertEquals("service.findById(id)", delegation.text(source))
    }

    @Test
    fun controllerPatternEmitsDelegationWithoutAServiceReceiverName() {
        val source =
            """
            class UserController {
                fun show(id: String) {
                    worker.run(id)
                }
            }
            """.trimIndent()

        val analysis = analyzePack("kotlin", source, listOf(ServiceRulePack()))
        val delegation = analysis.source(SERVICE_DELEGATION_INTENT).single()

        assertEquals("worker.run(id)", delegation.text(source))
        assertEquals("heuristic", delegation.source)
    }

    @Test
    fun mixedDelegatingBodyBecomesACandidateNotEstablishedDelegation() {
        val source =
            """
            class UserController(private val repository: UserRepository) {
                fun update(id: String, value: String) {
                    val current = repository.find(id)
                    current.apply(value)
                    repository.save(current)
                    audit(id)
                }
            }
            """.trimIndent()

        val analysis = analyzePack("kotlin", source, listOf(ServiceRulePack()))
        val candidate = analysis.candidates(SERVICE_DELEGATION_INTENT).single()

        assertTrue(analysis.source(SERVICE_DELEGATION_INTENT).isEmpty())
        assertEquals(CandidateReason.LOW_CONFIDENCE, candidate.reason)
        assertEquals("rule:$SERVICE_DELEGATION_RULE_ID", candidate.source)
        assertEquals("repository.find(id)", source.substring(candidate.startByte, candidate.endByte))
    }

    @Test
    fun localLogicInsideAGuardIsCandidateOnly() {
        val source =
            """
            class UserController(private val service: UserService) {
                fun find(id: String) {
                    if (enabled) {
                        service.find(id)
                    }
                }
            }
            """.trimIndent()

        val analysis = analyzePack("kotlin", source, listOf(ServiceRulePack()))

        assertTrue(analysis.source(SERVICE_DELEGATION_INTENT).isEmpty())
        assertEquals(1, analysis.candidates(SERVICE_DELEGATION_INTENT).size)
    }

    @Test
    fun nonServiceThinMethodDoesNotEmitDelegation() {
        val source =
            """
            fun f() {
                helper()
            }
            fun helper() {}
            """.trimIndent()

        val analysis = analyzePack("kotlin", source, listOf(ServiceRulePack()))

        assertTrue(analysis.source(SERVICE_DELEGATION_INTENT).isEmpty())
        assertTrue(analysis.candidates(SERVICE_DELEGATION_INTENT).isEmpty())
    }

    @Test
    fun configuredAndAdditiveServiceNamesBothEmitDelegation() {
        val base = ServiceConfig.from(mapOf("kotlin" to mapOf("serviceNames" to listOf("primary"))))
        val pack =
            ServiceRulePack(base)
                .configureAdditive(mapOf("kotlin" to mapOf("serviceNames" to listOf("secondary"))))
        val source =
            """
            fun first() {
                primary.find(1)
            }
            fun second() {
                secondary.find(2)
            }
            """.trimIndent()

        val analysis = analyzePack("kotlin", source, listOf(pack))

        assertEquals(
            setOf("primary.find(1)", "secondary.find(2)"),
            analysis.source(SERVICE_DELEGATION_INTENT).map { it.text(source) }.toSet(),
        )
    }
}
