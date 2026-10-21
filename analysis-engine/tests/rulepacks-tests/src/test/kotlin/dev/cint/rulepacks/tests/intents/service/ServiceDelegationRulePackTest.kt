package dev.cint.rulepacks.tests.intents.service

import dev.cint.api.CandidateReason
import dev.cint.core.facts.model.SourceFile
import dev.cint.core.facts.oracle.TypeFact
import dev.cint.core.facts.oracle.TypeOracle
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

private class FixedOracle(
    private val fact: TypeFact?,
) : TypeOracle {
    override fun typeAt(
        file: SourceFile,
        byteOffset: Int,
    ): TypeFact? = fact
}

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
    fun thinForwardingMethodEmitsDelegationAtTheCallWithExactSpan() {
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
        assertEquals(source.indexOf("service.findById(id)"), delegation.startByte)
        assertEquals(source.indexOf("service.findById(id)") + "service.findById(id)".length, delegation.endByte)
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

        assertEquals("service.findById(id)", analysis.source(SERVICE_DELEGATION_INTENT).single().text(source))
    }

    @Test
    fun javascriptAndCsharpThinForwardingEmitDelegation() {
        val cases =
            listOf(
                "javascript" to
                    """
                    function find(id) {
                        return service.findById(id);
                    }
                    """.trimIndent(),
                "csharp" to
                    """
                    class UserController {
                        private readonly IUserService _service;

                        void Find(string id) {
                            _service.Find(id);
                        }
                    }
                    """.trimIndent(),
            )

        for ((language, source) in cases) {
            val analysis = analyzePack(language, source, listOf(ServiceRulePack()))
            assertEquals(1, analysis.source(SERVICE_DELEGATION_INTENT).size, language)
        }
    }

    @Test
    fun controllerPatternThinCallBecomesCandidateNotEstablished() {
        val source =
            """
            class UserController {
                fun show(id: String) {
                    worker.run(id)
                }
            }
            """.trimIndent()

        val analysis = analyzePack("kotlin", source, listOf(ServiceRulePack()))
        val candidate = analysis.candidates(SERVICE_DELEGATION_INTENT).single()

        assertTrue(analysis.source(SERVICE_DELEGATION_INTENT).isEmpty())
        assertEquals("worker.run(id)", source.substring(candidate.startByte, candidate.endByte))
        assertEquals("rule:$SERVICE_DELEGATION_RULE_ID", candidate.source)
    }

    @Test
    fun controllerLocalHelpersAreNotEstablishedDelegation() {
        val source =
            """
            class UserController {
                fun format(value: String) {
                    render(value)
                }

                fun validate() {
                    require(ok)
                }

                fun log() {
                    logger.info("x")
                }
            }
            """.trimIndent()

        val analysis = analyzePack("kotlin", source, listOf(ServiceRulePack()))

        assertTrue(analysis.source(SERVICE_DELEGATION_INTENT).isEmpty())
        assertEquals(3, analysis.candidates(SERVICE_DELEGATION_INTENT).size)
    }

    @Test
    fun singleStatementWrappersBecomeCandidates() {
        val cases =
            listOf(
                "fun find(id: String) = audit(service.find(id))",
                "fun find(id: String) = mapper.map(service.find(id))",
                "fun find(id: String) = service.find(id).also { log(it) }",
                "fun find(id: String) = service.find(id) + 1",
            )

        for (source in cases) {
            val analysis = analyzePack("kotlin", source, listOf(ServiceRulePack()))
            assertTrue(analysis.source(SERVICE_DELEGATION_INTENT).isEmpty(), source)
            assertTrue(analysis.candidates(SERVICE_DELEGATION_INTENT).isNotEmpty(), source)
        }
    }

    @Test
    fun multiBranchDispatchBecomesCandidatesNotEstablished() {
        val source =
            """
            fun find(kind: String) =
                when (kind) {
                    "a" -> service.findA()
                    else -> service.findB()
                }
            """.trimIndent()

        val analysis = analyzePack("kotlin", source, listOf(ServiceRulePack()))

        assertTrue(analysis.source(SERVICE_DELEGATION_INTENT).isEmpty())
        assertEquals(
            setOf("service.findA()", "service.findB()"),
            analysis.candidates(SERVICE_DELEGATION_INTENT).map { source.substring(it.startByte, it.endByte) }.toSet(),
        )
    }

    @Test
    fun mixedDelegatingBodyEmitsOneCandidatePerForwardingSite() {
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
        val candidates = analysis.candidates(SERVICE_DELEGATION_INTENT)

        assertTrue(analysis.source(SERVICE_DELEGATION_INTENT).isEmpty())
        assertEquals(
            setOf("repository.find(id)", "repository.save(current)"),
            candidates.map { source.substring(it.startByte, it.endByte) }.toSet(),
        )
        assertTrue(candidates.all { it.reason == CandidateReason.LOW_CONFIDENCE })
        assertTrue(candidates.all { it.source == "rule:$SERVICE_DELEGATION_RULE_ID" })
    }

    @Test
    fun nestedCallsCountAsOneForwardingUnit() {
        val source =
            """
            class UserController(private val service: UserService) {
                fun mixed(id: String) {
                    service.save(service.find(id))
                    val local = 1
                    audit(local)
                }
            }
            """.trimIndent()

        val analysis = analyzePack("kotlin", source, listOf(ServiceRulePack()))

        assertTrue(analysis.source(SERVICE_DELEGATION_INTENT).isEmpty())
        assertTrue(analysis.candidates(SERVICE_DELEGATION_INTENT).isEmpty())
    }

    @Test
    fun halfForwardingUnitsBecomeCandidates() {
        val source =
            """
            class UserController(private val service: UserService) {
                fun mixed(id: String) {
                    service.find(id)
                    audit(id)
                }
            }
            """.trimIndent()

        val analysis = analyzePack("kotlin", source, listOf(ServiceRulePack()))

        assertTrue(analysis.source(SERVICE_DELEGATION_INTENT).isEmpty())
        assertEquals(1, analysis.candidates(SERVICE_DELEGATION_INTENT).size)
    }

    @Test
    fun pureLocalArgumentBindingRemainsCandidateOnly() {
        val source =
            """
            class UserController(private val service: UserService) {
                fun find(id: String) {
                    val key = id.trim()
                    return service.find(key)
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
    fun receiverTypeOracleCanEstablishDelegation() {
        val source =
            """
            fun find(id: String) {
                thing.find(id)
            }
            """.trimIndent()
        val oracle = FixedOracle(TypeFact("com.acme.UserService", "lsp", 0.9))

        val analysis = analyzePack("kotlin", source, listOf(ServiceRulePack()), oracle)

        assertEquals("thing.find(id)", analysis.source(SERVICE_DELEGATION_INTENT).single().text(source))
    }

    @Test
    fun configurePreservesExistingPackConfigAndAppliesNewConfig() {
        val base = ServiceConfig.from(mapOf("kotlin" to mapOf("serviceNames" to listOf("primary"))))
        val pack =
            ServiceRulePack(base)
                .configure(mapOf("kotlin" to mapOf("delegationCalls" to listOf("forwardTo"))))
        val source =
            """
            fun first() {
                primary.find(1)
            }
            fun second() {
                forwardTo(2)
            }
            """.trimIndent()

        val analysis = analyzePack("kotlin", source, listOf(pack))

        assertEquals(
            setOf("primary.find(1)", "forwardTo(2)"),
            analysis.source(SERVICE_DELEGATION_INTENT).map { it.text(source) }.toSet(),
        )
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
