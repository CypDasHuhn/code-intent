package dev.cint.rulepacks.tests.intents.service

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

private class InitOracle(
    private val fact: TypeFact?,
) : TypeOracle {
    override fun typeAt(
        file: SourceFile,
        byteOffset: Int,
    ): TypeFact? = fact
}

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
    fun kotlinConstructorInjectionEmitsInitWiring() {
        val source =
            """
            class UserController(
                private val userService: UserService,
                private val name: String
            ) {}
            """.trimIndent()

        val analysis = analyzePack("kotlin", source, listOf(ServiceRulePack()))
        val texts = analysis.source(SERVICE_INIT_INTENT).map { it.text(source) }

        assertTrue(texts.any { it.contains("userService: UserService") })
        assertTrue(texts.none { it.contains("name: String") })
    }

    @Test
    fun kotlinAnnotatedConstructorInjectionEmitsInitWiring() {
        val source =
            """
            class UserController @Named("users") @Inject constructor(
                private val userService: UserService,
                private val name: String
            ) {}
            """.trimIndent()

        val analysis = analyzePack("kotlin", source, listOf(ServiceRulePack()))
        val texts = analysis.source(SERVICE_INIT_INTENT).map { it.text(source) }

        assertTrue(texts.any { it.contains("private val userService: UserService") })
        assertTrue(texts.none { it.contains("private val name: String") })
    }

    @Test
    fun typescriptParameterPropertiesEmitInitWiring() {
        val source =
            """
            class UserController {
                constructor(private readonly userService: UserService, public repository: UserRepository, name: string) {}
            }
            """.trimIndent()

        val analysis = analyzePack("typescript", source, listOf(ServiceRulePack()))
        val texts = analysis.source(SERVICE_INIT_INTENT).map { it.text(source) }

        assertTrue(texts.any { it.contains("private readonly userService: UserService") })
        assertTrue(texts.any { it.contains("public repository: UserRepository") })
        assertTrue(texts.none { it.contains("name: string") })
    }

    @Test
    fun typescriptPlainConstructorParametersAreNotInjection() {
        val source =
            """
            class UserController {
                constructor(userService: UserService, name: string) {}
            }
            """.trimIndent()

        val analysis = analyzePack("typescript", source, listOf(ServiceRulePack()))

        assertTrue(analysis.source(SERVICE_INIT_INTENT).isEmpty())
        assertTrue(analysis.candidates(SERVICE_INIT_INTENT).isEmpty())
    }

    @Test
    fun csharpConstructorInjectionEmitsInitWiring() {
        val source =
            """
            public class UserController
            {
                private readonly IUserService _service;

                public UserController(IUserService service)
                {
                    _service = service;
                }
            }
            """.trimIndent()

        val analysis = analyzePack("csharp", source, listOf(ServiceRulePack()))
        val texts = analysis.source(SERVICE_INIT_INTENT).map { it.text(source) }

        assertTrue(texts.any { it.contains("IUserService service") })
    }

    @Test
    fun newConstructionWorksOutsideDeclarationInitializersInAllLanguages() {
        val cases =
            listOf(
                "javascript" to
                    """
                    function make() {
                        return new HttpClient();
                    }
                    function send() {
                        use(new HttpClient());
                    }
                    function reset(client) {
                        client = new HttpClient();
                    }
                    """.trimIndent(),
                "typescript" to
                    """
                    class ClientHolder {
                        make(): HttpClient {
                            return new HttpClient();
                        }
                    }
                    """.trimIndent(),
                "csharp" to
                    """
                    class ClientHolder
                    {
                        HttpClient Make()
                        {
                            return new HttpClient();
                        }

                        void Send()
                        {
                            Use(new HttpClient());
                        }

                        void Reset()
                        {
                            this.client = new HttpClient();
                        }
                    }
                    """.trimIndent(),
            )

        for ((language, source) in cases) {
            val analysis = analyzePack(language, source, listOf(ServiceRulePack()))
            val init = analysis.source(SERVICE_INIT_INTENT)

            assertTrue(init.any { it.text(source) == "new HttpClient()" }, language)
            assertTrue(init.any { it.text(source) == "new HttpClient()" }, language)
        }
    }

    @Test
    fun csharpTargetTypedNewUsesTheCreationNode() {
        val source =
            """
            class ClientHolder
            {
                void Make()
                {
                    HttpClient client = new();
                }
            }
            """.trimIndent()

        val analysis = analyzePack("csharp", source, listOf(ServiceRulePack()))
        val init = analysis.source(SERVICE_INIT_INTENT).single()

        assertEquals("new()", init.text(source))
        assertEquals(source.indexOf("new()"), init.startByte)
        assertEquals(source.indexOf("new()") + "new()".length, init.endByte)
    }

    @Test
    fun creationSpanIsTheCreationNodeNotTheWholeInitializer() {
        val source =
            """
            const client = new HttpClient();
            """.trimIndent()

        val analysis = analyzePack("javascript", source, listOf(ServiceRulePack()))
        val init = analysis.source(SERVICE_INIT_INTENT).single()

        assertEquals("new HttpClient()", init.text(source))
        assertEquals(source.indexOf("new HttpClient()"), init.startByte)
        assertEquals(source.indexOf("new HttpClient()") + "new HttpClient()".length, init.endByte)
    }

    @Test
    fun strayNewTextInStringsAndCommentsIsNotInit() {
        val source =
            """
            fun label(): String = "new HttpClient()"
            // new HttpClient()
            """.trimIndent()

        val analysis = analyzePack("kotlin", source, listOf(ServiceRulePack()))

        assertTrue(analysis.source(SERVICE_INIT_INTENT).isEmpty())
        assertTrue(analysis.candidates(SERVICE_INIT_INTENT).isEmpty())
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
    fun leadingFileCommentsDoNotDisableCreationDetection() {
        val cases =
            listOf(
                "javascript" to
                    """
                    // license header
                    function make() {
                        return new HttpClient();
                    }
                    """.trimIndent(),
                "javascript" to
                    """
                    /* license header */
                    function make() {
                        return new HttpClient();
                    }
                    """.trimIndent(),
                "csharp" to
                    """
                    // license header
                    class ClientHolder
                    {
                        HttpClient Make()
                        {
                            return new HttpClient();
                        }
                    }
                    """.trimIndent(),
            )

        for ((language, source) in cases) {
            val analysis = analyzePack(language, source, listOf(ServiceRulePack()))
            assertTrue(
                analysis.source(SERVICE_INIT_INTENT).any { it.text(source) == "new HttpClient()" },
                language,
            )
        }
    }

    @Test
    fun nestedCreationKeepsAnOuterConfiguredConstruction() {
        val source =
            """
            class ClientHolder
            {
                HttpClient Make()
                {
                    return new HttpClient(new HttpClientHandler());
                }
            }
            """.trimIndent()

        val analysis = analyzePack("csharp", source, listOf(ServiceRulePack()))

        assertTrue(
            analysis.source(SERVICE_INIT_INTENT).any { annotation ->
                annotation.text(source).startsWith("new HttpClient(")
            },
        )
    }

    @Test
    fun nestedHelperCreationDoesNotCertifyAnOuterLifecycleFunction() {
        val source =
            """
            function initialize() {
                function make() {
                    return new HttpClient();
                }
            }
            """.trimIndent()

        val analysis = analyzePack("javascript", source, listOf(ServiceRulePack()))
        val established = analysis.source(SERVICE_INIT_INTENT).map { it.text(source) }
        val candidates = analysis.candidates(SERVICE_INIT_INTENT).map { source.substring(it.startByte, it.endByte) }

        assertTrue(established.none { it == "initialize" })
        assertTrue("initialize" in candidates)
    }

    @Test
    fun csharpPrimaryAndRecordParametersEmitConstructorInjection() {
        val source =
            """
            class ApiClient(IHttpClientFactory factory) {}

            record UserClient(IHttpClientFactory Factory) {}
            """.trimIndent()

        val analysis = analyzePack("csharp", source, listOf(ServiceRulePack()))
        val texts = analysis.source(SERVICE_INIT_INTENT).map { it.text(source) }

        assertTrue(texts.any { it.contains("IHttpClientFactory factory") })
        assertTrue(texts.any { it.contains("IHttpClientFactory Factory") })
    }

    @Test
    fun genericParameterListsWithCommasKeepAllConstructorInjectionParameters() {
        val cases =
            listOf(
                "kotlin" to
                    """
                    class RegistryHolder(
                        private val registry: Map<String, UserService>,
                        private val repository: UserRepository
                    ) {}
                    """.trimIndent(),
                "csharp" to
                    """
                    class RegistryHolder
                    {
                        public RegistryHolder(Dictionary<string, IService> services, IUserRepository repository)
                        {
                        }
                    }
                    """.trimIndent(),
            )

        for ((language, source) in cases) {
            val analysis = analyzePack(language, source, listOf(ServiceRulePack()))
            val texts = analysis.source(SERVICE_INIT_INTENT).map { it.text(source) }
            assertTrue(texts.any { it.contains("UserService") || it.contains("IService") }, language)
            assertTrue(texts.any { it.contains("UserRepository") || it.contains("IUserRepository") }, language)
        }
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
