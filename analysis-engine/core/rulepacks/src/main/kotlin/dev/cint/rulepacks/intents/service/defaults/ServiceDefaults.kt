package dev.cint.rulepacks.intents.service.defaults

import dev.cint.rulepacks.intents.service.ServiceLanguageConfig

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

internal val LANGUAGE_DEFAULTS: Map<String, ServiceLanguageConfig> =
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
