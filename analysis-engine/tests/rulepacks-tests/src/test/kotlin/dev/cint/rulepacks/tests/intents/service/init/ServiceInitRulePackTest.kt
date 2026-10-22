package dev.cint.rulepacks.tests.intents.service.init

import dev.cint.api.CandidateReason
import dev.cint.core.facts.model.SourceFile
import dev.cint.core.facts.oracle.TypeFact
import dev.cint.core.facts.oracle.TypeOracle
import dev.cint.rulepacks.intents.service.SERVICE_DELEGATION_INTENT
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
    fun constructionFactoryConnectionAndLifecycleWithEvidenceEmitInit() {
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
        assertTrue(texts.any { it == "initialize" })
        assertTrue(analysis.candidates(SERVICE_INIT_INTENT).isEmpty())
    }

    @Test
    fun dependencyInjectionAnnotationsWithArgumentsEmitInit() {
        val source =
            """
            class ServiceHolder {
                @Bean(name = "client")
                fun client(): HttpClient = HttpClient()

                @Autowired(required = false)
                lateinit var service: UserService
            }
            """.trimIndent()

        val analysis = analyzePack("kotlin", source, listOf(ServiceRulePack()))
        val texts = analysis.source(SERVICE_INIT_INTENT).map { it.text(source) }

        assertTrue(texts.any { it.contains("@Bean(name = \"client\")") })
        assertTrue(texts.any { it.contains("@Autowired(required = false)") })
        assertTrue(analysis.candidates(SERVICE_INIT_INTENT).isEmpty())
    }

    @Test
    fun lifecycleNameAloneIsOnlyACandidate() {
        val source =
            """
            fun initialize() {
                work()
            }
            fun work() {}
            """.trimIndent()

        val analysis = analyzePack("kotlin", source, listOf(ServiceRulePack()))
        val candidate = analysis.candidates(SERVICE_INIT_INTENT).single()

        assertTrue(analysis.source(SERVICE_INIT_INTENT).isEmpty())
        assertEquals("initialize", source.substring(candidate.startByte, candidate.endByte))
        assertEquals(CandidateReason.LOW_CONFIDENCE, candidate.reason)
    }

    @Test
    fun hiddenInitializationHelperBecomesACandidate() {
        val source =
            """
            fun makeTransport(): Transport {
                val connection = Socket(host, port)
                connection.connect()
                return connection
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
    fun unqualifiedGenericInitCallsAreCandidates() {
        val source =
            """
            fun connect() {
                window.connect()
            }

            fun configure() {
                resolve(path)
            }
            """.trimIndent()

        val analysis = analyzePack("kotlin", source, listOf(ServiceRulePack()))
        val candidateTexts =
            analysis.candidates(SERVICE_INIT_INTENT).map { source.substring(it.startByte, it.endByte) }.toSet()

        assertTrue(analysis.source(SERVICE_INIT_INTENT).isEmpty())
        assertTrue("window.connect()" in candidateTexts)
        assertTrue("resolve(path)" in candidateTexts)
    }

    @Test
    fun qualifiedGenericInitCallsAreEstablished() {
        val source =
            """
            fun wire() {
                container.resolve(path)
                ctx.getBean("client")
            }
            """.trimIndent()

        val analysis = analyzePack("kotlin", source, listOf(ServiceRulePack()))
        val texts = analysis.source(SERVICE_INIT_INTENT).map { it.text(source) }

        assertTrue(texts.any { it.contains("container.resolve(path)") })
        assertTrue(texts.any { it.contains("ctx.getBean(\"client\")") })
    }

    @Test
    fun useAndTeardownCallsAreNotInit() {
        val source =
            """
            fun cleanup() {
                connection.close()
                server.stop()
            }
            """.trimIndent()

        val analysis = analyzePack("kotlin", source, listOf(ServiceRulePack()))

        assertTrue(analysis.source(SERVICE_INIT_INTENT).isEmpty())
        assertTrue(analysis.candidates(SERVICE_INIT_INTENT).isEmpty())
    }

    @Test
    fun connectionSetupIsInitOnlyAndNotDelegation() {
        val source =
            """
            fun connect() {
                client.connect()
            }
            """.trimIndent()

        val analysis = analyzePack("kotlin", source, listOf(ServiceRulePack()))

        assertTrue(analysis.source(SERVICE_INIT_INTENT).any { it.text(source) == "client.connect()" })
        assertTrue(analysis.source(SERVICE_DELEGATION_INTENT).isEmpty())
    }

    @Test
    fun callOnConfiguredInitTypeIsNotInitUnlessItIsConstructionOrSetup() {
        val source =
            """
            fun load(url: String) {
                httpClient.get(url)
            }
            """.trimIndent()
        val oracle = InitOracle(TypeFact("HttpClient", "lsp", 0.9))

        val analysis = analyzePack("kotlin", source, listOf(ServiceRulePack()), oracle)

        assertTrue(analysis.source(SERVICE_INIT_INTENT).isEmpty())
    }

    @Test
    fun factoryConstructionIsInitAndNotDelegation() {
        val cases =
            mapOf(
                "kotlin" to "fun build() { val factory = UserFactory() }",
                "javascript" to "function build() { return new UserFactory(); }",
                "typescript" to "function build(): UserFactory { return new UserFactory(); }",
                "csharp" to "class C { UserFactory Build() { return new UserFactory(); } }",
            )

        for ((language, source) in cases) {
            val analysis = analyzePack(language, source, listOf(ServiceRulePack()))

            assertTrue(analysis.source(SERVICE_INIT_INTENT).isNotEmpty(), language)
            assertTrue(analysis.source(SERVICE_DELEGATION_INTENT).isEmpty(), language)
        }
    }

    @Test
    fun factoryTypedDiReceiversAreEstablishedInit() {
        val source =
            """
            fun wire() {
                serviceFactory.getBean("client")
                serviceFactory.resolve(path)
                serviceFactory.getInstance()
            }
            """.trimIndent()
        val oracle = InitOracle(TypeFact("com.acme.ServiceFactory", "lsp", 0.9))

        val analysis = analyzePack("kotlin", source, listOf(ServiceRulePack()), oracle)
        val texts = analysis.source(SERVICE_INIT_INTENT).map { it.text(source) }

        assertTrue(texts.any { it.contains("serviceFactory.getBean") })
        assertTrue(texts.any { it.contains("serviceFactory.resolve") })
        assertTrue(texts.any { it.contains("serviceFactory.getInstance") })
        assertTrue(analysis.candidates(SERVICE_INIT_INTENT).isEmpty())

        val delegationSource = "class WidgetController { fun build() = serviceFactory.create() }"
        val delegation =
            analyzePack(
                "kotlin",
                delegationSource,
                listOf(ServiceRulePack()),
                InitOracle(TypeFact("com.acme.ServiceFactory", "lsp", 0.9)),
            )

        assertTrue(delegation.source(SERVICE_DELEGATION_INTENT).isEmpty())
        assertEquals(1, delegation.candidates(SERVICE_DELEGATION_INTENT).size)
    }

    @Test
    fun thinFactoryCallsRequireExplicitDelegationEvidence() {
        val source =
            """
            class WidgetController {
                fun build() = widgetFactory.create()
            }

            fun log() {
                loggerFactory.info("x")
            }
            """.trimIndent()

        val unconfigured = analyzePack("kotlin", source, listOf(ServiceRulePack()))

        assertTrue(unconfigured.source(SERVICE_DELEGATION_INTENT).isEmpty())
        assertEquals(1, unconfigured.candidates(SERVICE_DELEGATION_INTENT).size)

        val configured =
            ServiceRulePack().configure(
                mapOf("kotlin" to mapOf("delegationCalls" to listOf("create"))),
            )
        val analysis = analyzePack("kotlin", source, listOf(configured))

        assertEquals(
            setOf("widgetFactory.create()"),
            analysis.source(SERVICE_DELEGATION_INTENT).map { it.text(source) }.toSet(),
        )
    }
}

private class InitOracle(
    private val fact: TypeFact?,
) : TypeOracle {
    override fun typeAt(
        file: SourceFile,
        byteOffset: Int,
    ): TypeFact? = fact
}
