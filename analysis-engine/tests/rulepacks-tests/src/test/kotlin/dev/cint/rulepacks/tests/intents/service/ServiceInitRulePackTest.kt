package dev.cint.rulepacks.tests.intents.service

import dev.cint.api.CandidateReason
import dev.cint.rulepacks.intents.service.SERVICE_INIT_INTENT
import dev.cint.rulepacks.intents.service.SERVICE_INIT_RULE_ID
import dev.cint.rulepacks.intents.service.ServiceRulePack
import dev.cint.rulepacks.tests.support.analyzePack
import dev.cint.test.fixtures.text
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ServiceInitRulePackTest {
    @Test
    fun constructionFactoryConnectionAndLifecycleEmitInit() {
        val source =
            """
            class ClientHolder {
                val client = HttpClient()
                val connection = DriverManager.getConnection(url)

                fun initialize() {
                    client.connect()
                }
            }
            """.trimIndent()

        val analysis = analyzePack("kotlin", source, listOf(ServiceRulePack()))
        val texts = analysis.source(SERVICE_INIT_INTENT).map { it.text(source) }

        assertTrue(texts.any { it.contains("HttpClient()") })
        assertTrue(texts.any { it.contains("DriverManager.getConnection(url)") })
        assertTrue(texts.any { it.contains("client.connect()") })
        assertTrue(texts.any { it.contains("fun initialize()") })
        assertTrue(analysis.candidates(SERVICE_INIT_INTENT).isEmpty())
    }

    @Test
    fun dependencyInjectionAnnotationEmitsInit() {
        val source =
            """
            class ServiceHolder {
                @Inject
                lateinit var service: UserService
            }
            """.trimIndent()

        val analysis = analyzePack("kotlin", source, listOf(ServiceRulePack()))
        val init = analysis.source(SERVICE_INIT_INTENT).single()

        assertTrue(init.text(source).contains("@Inject"))
        assertEquals("annotation", init.source)
    }

    @Test
    fun lifecycleMethodIsInitWithoutCallEvidence() {
        val source =
            """
            fun initialize() {
                work()
            }
            fun work() {}
            """.trimIndent()

        val analysis = analyzePack("kotlin", source, listOf(ServiceRulePack()))
        val init = analysis.source(SERVICE_INIT_INTENT).single()

        assertTrue(init.text(source).contains("fun initialize()"))
        assertEquals("heuristic", init.source)
    }

    @Test
    fun unconfiguredConstructorsAndPlainCallsAreNotInit() {
        val source =
            """
            class Holder {
                val helper = Helper()

                fun build() {
                    helper.run()
                    data.clear()
                }
            }
            """.trimIndent()

        val analysis = analyzePack("kotlin", source, listOf(ServiceRulePack()))

        assertTrue(analysis.source(SERVICE_INIT_INTENT).isEmpty())
        assertTrue(analysis.candidates(SERVICE_INIT_INTENT).isEmpty())
    }

    @Test
    fun hiddenInitializationHelperBecomesACandidate() {
        val source =
            """
            fun makeTransport(): Transport {
                val socket = Socket(host, port)
                socket.connect()
                return socket
            }
            """.trimIndent()

        val analysis = analyzePack("kotlin", source, listOf(ServiceRulePack()))
        val candidate = analysis.candidates(SERVICE_INIT_INTENT).single()

        assertEquals(2, analysis.source(SERVICE_INIT_INTENT).size)
        assertEquals(CandidateReason.LOW_CONFIDENCE, candidate.reason)
        assertEquals("rule:$SERVICE_INIT_RULE_ID", candidate.source)
        assertEquals("makeTransport", source.substring(candidate.startByte, candidate.endByte))
    }

    @Test
    fun factoryAndConnectionSetupDoNotCreateCandidatesForLifecycleFunctions() {
        val source =
            """
            fun connect() {
                val connection = DriverManager.getConnection(url)
                connection.connect()
            }
            """.trimIndent()

        val analysis = analyzePack("kotlin", source, listOf(ServiceRulePack()))
        val texts = analysis.source(SERVICE_INIT_INTENT).map { it.text(source) }

        assertTrue(texts.any { it.contains("fun connect()") })
        assertTrue(texts.any { it.contains("DriverManager.getConnection(url)") })
        assertTrue(texts.any { it.contains("connection.connect()") })
        assertTrue(analysis.candidates(SERVICE_INIT_INTENT).isEmpty())
    }
}
