package dev.cint.rulepacks.intents.service

import dev.cint.api.ConfigField
import dev.cint.api.ConfigSchema
import dev.cint.api.LanguageConfig

const val SERVICE_PACK_ID: String = "service"

data class ServiceLanguageConfig(
    val delegationCalls: Set<String> = emptySet(),
    val delegationQualifiers: Set<String> = emptySet(),
    val serviceNames: Set<String> = emptySet(),
    val serviceTypes: Set<String> = emptySet(),
    val serviceSuffixes: Set<String> = emptySet(),
    val controllerSuffixes: Set<String> = emptySet(),
    val facadeSuffixes: Set<String> = emptySet(),
    val initCalls: Set<String> = emptySet(),
    val initQualifiers: Set<String> = emptySet(),
    val initTypes: Set<String> = emptySet(),
    val initSuffixes: Set<String> = emptySet(),
    val initMethods: Set<String> = emptySet(),
    val connectionCalls: Set<String> = emptySet(),
    val connectionQualifiers: Set<String> = emptySet(),
    val diAnnotations: Set<String> = emptySet(),
    val diCalls: Set<String> = emptySet(),
    val diQualifiers: Set<String> = emptySet(),
)

data class ServiceConfig(
    val languages: Map<String, ServiceLanguageConfig> = defaults(),
) {
    fun forLanguage(language: String): ServiceLanguageConfig =
        LanguageConfig(languages).forLanguage(language, "kotlin")
            ?: ServiceLanguageConfig()

    fun withOverrides(values: Map<String, Any?>): ServiceConfig = SCHEMA.decode(this, values, SERVICE_PACK_ID)

    fun withAdditiveOverrides(values: Map<String, Any?>): ServiceConfig = SCHEMA.decodeAdditive(this, values, SERVICE_PACK_ID)

    companion object {
        private val LANGUAGE_SCHEMA =
            ConfigSchema.of(
                ConfigField.strings<ServiceLanguageConfig>(
                    "delegationCalls",
                    { it.delegationCalls },
                    { config, value -> config.copy(delegationCalls = value) },
                ),
                ConfigField.strings<ServiceLanguageConfig>(
                    "delegationQualifiers",
                    { it.delegationQualifiers },
                    { config, value -> config.copy(delegationQualifiers = value) },
                ),
                ConfigField.strings<ServiceLanguageConfig>(
                    "serviceNames",
                    { it.serviceNames },
                    { config, value -> config.copy(serviceNames = value) },
                ),
                ConfigField.strings<ServiceLanguageConfig>(
                    "serviceTypes",
                    { it.serviceTypes },
                    { config, value -> config.copy(serviceTypes = value) },
                ),
                ConfigField.strings<ServiceLanguageConfig>(
                    "serviceSuffixes",
                    { it.serviceSuffixes },
                    { config, value -> config.copy(serviceSuffixes = value) },
                ),
                ConfigField.strings<ServiceLanguageConfig>(
                    "controllerSuffixes",
                    { it.controllerSuffixes },
                    { config, value -> config.copy(controllerSuffixes = value) },
                ),
                ConfigField.strings<ServiceLanguageConfig>(
                    "facadeSuffixes",
                    { it.facadeSuffixes },
                    { config, value -> config.copy(facadeSuffixes = value) },
                ),
                ConfigField.strings<ServiceLanguageConfig>(
                    "initCalls",
                    { it.initCalls },
                    { config, value -> config.copy(initCalls = value) },
                ),
                ConfigField.strings<ServiceLanguageConfig>(
                    "initQualifiers",
                    { it.initQualifiers },
                    { config, value -> config.copy(initQualifiers = value) },
                ),
                ConfigField.strings<ServiceLanguageConfig>(
                    "initTypes",
                    { it.initTypes },
                    { config, value -> config.copy(initTypes = value) },
                ),
                ConfigField.strings<ServiceLanguageConfig>(
                    "initSuffixes",
                    { it.initSuffixes },
                    { config, value -> config.copy(initSuffixes = value) },
                ),
                ConfigField.strings<ServiceLanguageConfig>(
                    "initMethods",
                    { it.initMethods },
                    { config, value -> config.copy(initMethods = value) },
                ),
                ConfigField.strings<ServiceLanguageConfig>(
                    "connectionCalls",
                    { it.connectionCalls },
                    { config, value -> config.copy(connectionCalls = value) },
                ),
                ConfigField.strings<ServiceLanguageConfig>(
                    "connectionQualifiers",
                    { it.connectionQualifiers },
                    { config, value -> config.copy(connectionQualifiers = value) },
                ),
                ConfigField.strings<ServiceLanguageConfig>(
                    "diAnnotations",
                    { it.diAnnotations },
                    { config, value -> config.copy(diAnnotations = value) },
                ),
                ConfigField.strings<ServiceLanguageConfig>(
                    "diCalls",
                    { it.diCalls },
                    { config, value -> config.copy(diCalls = value) },
                ),
                ConfigField.strings<ServiceLanguageConfig>(
                    "diQualifiers",
                    { it.diQualifiers },
                    { config, value -> config.copy(diQualifiers = value) },
                ),
            )

        internal val SCHEMA =
            LanguageConfig.schema<ServiceLanguageConfig, ServiceConfig>(
                languageSchema = LANGUAGE_SCHEMA,
                get = { it.languages },
                set = { config, languages -> config.copy(languages = languages) },
            )

        fun from(values: Map<String, Any?>): ServiceConfig = ServiceConfig().withOverrides(values)

        private val COMMON_SERVICE_NAMES =
            setOf(
                "service",
                "svc",
                "repository",
                "repo",
                "facade",
                "adapter",
                "gateway",
                "client",
                "delegate",
                "proxy",
                "controller",
                "services",
                "apiClient",
                "httpClient",
                "remote",
            )

        private val COMMON_SERVICE_TYPES =
            setOf(
                "ApiClient",
                "ServiceClient",
                "ServiceProxy",
                "ServiceFacade",
                "ServiceAdapter",
                "ServiceGateway",
            )

        private val COMMON_SERVICE_SUFFIXES =
            setOf(
                "Service",
                "Repository",
                "Repo",
                "Facade",
                "Adapter",
                "Gateway",
                "Client",
                "Provider",
                "Controller",
            )

        private val COMMON_INIT_SUFFIXES = setOf("Factory")

        private val COMMON_INIT_TYPES =
            setOf(
                "HttpClient",
                "OkHttpClient",
                "WebClient",
                "RestClient",
                "RestTemplate",
                "Retrofit",
                "FeignClient",
                "KafkaProducer",
                "KafkaConsumer",
                "RedisClient",
                "Jedis",
                "MongoClient",
                "DataSource",
                "Connection",
                "Socket",
                "Server",
            )

        private val COMMON_INIT_QUALIFIERS =
            setOf(
                "ClientBuilder",
                "ServiceFactory",
                "DriverManager",
                "DataSource",
                "HttpClient",
                "OkHttpClient",
                "WebClient",
                "RestClient",
                "RestTemplate",
                "Retrofit",
                "Feign",
                "ServiceLoader",
                "ServiceProvider",
                "ConnectionFactory",
                "Pool",
            )

        private val COMMON_CONNECTION_QUALIFIERS =
            setOf(
                "DriverManager",
                "DataSource",
                "ConnectionFactory",
                "Connection",
                "connection",
                "conn",
                "_connection",
                "Pool",
                "HttpClient",
                "Client",
                "client",
            )

        private val COMMON_DI_QUALIFIERS =
            setOf(
                "context",
                "ctx",
                "container",
                "provider",
                "serviceProvider",
                "services",
                "injector",
                "locator",
                "beanFactory",
                "applicationContext",
                "resolver",
                "registry",
            )

        private val COMMON_DI_ANNOTATIONS =
            setOf(
                "Inject",
                "Autowired",
                "Provides",
                "Bean",
                "Injectable",
                "FromServices",
                "Injected",
            )

        private val COMMON_LANGUAGE =
            ServiceLanguageConfig(
                delegationCalls = setOf("delegate", "forward", "dispatch", "callThrough"),
                delegationQualifiers = setOf("services"),
                serviceNames = COMMON_SERVICE_NAMES,
                serviceTypes = COMMON_SERVICE_TYPES,
                serviceSuffixes = COMMON_SERVICE_SUFFIXES,
                controllerSuffixes = setOf("Controller", "Resource", "Endpoint"),
                facadeSuffixes = setOf("Facade", "Adapter", "Gateway", "Delegate", "Proxy"),
                initCalls =
                    setOf(
                        "createClient",
                        "newClient",
                        "buildClient",
                        "createService",
                        "newService",
                        "createConnection",
                    ),
                initQualifiers = COMMON_INIT_QUALIFIERS,
                initTypes = COMMON_INIT_TYPES,
                initSuffixes = COMMON_INIT_SUFFIXES,
                initMethods =
                    setOf(
                        "init",
                        "initialize",
                        "setup",
                        "bootstrap",
                        "wire",
                        "configure",
                        "createClient",
                        "createConnection",
                        "connect",
                    ),
                connectionCalls =
                    setOf(
                        "connect",
                        "openConnection",
                        "getConnection",
                        "createConnection",
                        "newConnection",
                        "acquireConnection",
                        "openSession",
                    ),
                connectionQualifiers = COMMON_CONNECTION_QUALIFIERS,
                diAnnotations = COMMON_DI_ANNOTATIONS,
                diCalls = setOf("inject", "resolve", "getInstance", "getService"),
                diQualifiers = COMMON_DI_QUALIFIERS,
            )

        private val LANGUAGE_DEFAULTS: Map<String, ServiceLanguageConfig> =
            run {
                val javascript = COMMON_LANGUAGE.copy(initCalls = COMMON_LANGUAGE.initCalls + "createPool")
                linkedMapOf(
                    "kotlin" to
                        COMMON_LANGUAGE.copy(
                            initCalls =
                                setOf(
                                    "createClient",
                                    "newClient",
                                    "buildClient",
                                    "createService",
                                    "newService",
                                    "createConnection",
                                ),
                            initMethods =
                                setOf(
                                    "init",
                                    "initialize",
                                    "initialise",
                                    "setup",
                                    "setUp",
                                    "bootstrap",
                                    "wire",
                                    "configure",
                                    "createClient",
                                    "createConnection",
                                    "openConnection",
                                    "buildClient",
                                    "connect",
                                ),
                            diCalls = setOf("getBean", "getService", "getInstance", "inject", "resolve"),
                            diQualifiers = COMMON_DI_QUALIFIERS,
                        ),
                    "javascript" to javascript,
                    "typescript" to javascript,
                    "csharp" to
                        COMMON_LANGUAGE.copy(
                            delegationCalls = setOf("Delegate", "Forward", "Dispatch", "CallThrough"),
                            delegationQualifiers = setOf("Services"),
                            serviceNames =
                                COMMON_SERVICE_NAMES +
                                    setOf("_service", "_repository", "_facade", "_adapter", "_gateway", "_client"),
                            initCalls =
                                setOf(
                                    "CreateClient",
                                    "BuildClient",
                                    "CreateService",
                                    "CreateConnection",
                                    "Initialize",
                                    "Configure",
                                    "AddHttpClient",
                                    "AddDbContext",
                                    "AddSingleton",
                                    "AddScoped",
                                    "AddTransient",
                                    "BuildServiceProvider",
                                ),
                            initMethods =
                                setOf(
                                    "Initialize",
                                    "Initialise",
                                    "Setup",
                                    "Bootstrap",
                                    "Configure",
                                    "Wire",
                                    "Register",
                                    "CreateClient",
                                    "CreateConnection",
                                    "Connect",
                                ),
                            connectionCalls =
                                setOf(
                                    "Connect",
                                    "OpenConnection",
                                    "GetConnection",
                                    "CreateConnection",
                                    "OpenSession",
                                ),
                            diCalls = setOf("GetService", "GetRequiredService", "Resolve", "Inject", "GetInstance"),
                            diQualifiers = COMMON_DI_QUALIFIERS + setOf("_services"),
                        ),
                )
            }

        fun defaults(): Map<String, ServiceLanguageConfig> = LANGUAGE_DEFAULTS
    }
}
