package dev.cint.rulepacks.tests.intents.service.init

import dev.cint.rulepacks.intents.service.SERVICE_INIT_INTENT
import dev.cint.rulepacks.intents.service.ServiceRulePack
import dev.cint.rulepacks.tests.support.analyzePack
import dev.cint.test.fixtures.text
import kotlin.test.Test
import kotlin.test.assertTrue

class ServiceInitWiringRulePackTest {
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
}
