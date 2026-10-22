package dev.cint.rulepacks.intents.service

import dev.cint.api.ConfigField
import dev.cint.api.ConfigSchema
import dev.cint.api.LanguageConfig
import dev.cint.rulepacks.intents.service.defaults.LANGUAGE_DEFAULTS

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

        fun defaults(): Map<String, ServiceLanguageConfig> = LANGUAGE_DEFAULTS
    }
}
