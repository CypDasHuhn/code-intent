package dev.cint.rulepacks.intents.service

import dev.cint.api.CINT_API_VERSION
import dev.cint.api.ConfigSchema
import dev.cint.api.ConfigurableRulePack
import dev.cint.api.Intent
import dev.cint.api.Rule
import dev.cint.rulepacks.intents.service.rules.ServiceDelegationRule
import dev.cint.rulepacks.intents.service.rules.ServiceInitRule

const val SERVICE_DELEGATION_INTENT: Intent = "SERVICE_DELEGATION"
const val SERVICE_INIT_INTENT: Intent = "SERVICE_INIT"
const val SERVICE_DELEGATION_RULE_ID: String = "$SERVICE_PACK_ID:delegation"
const val SERVICE_INIT_RULE_ID: String = "$SERVICE_PACK_ID:init"

class ServiceRulePack(
    private val config: ServiceConfig = ServiceConfig(),
) : ConfigurableRulePack {
    override val id: String = SERVICE_PACK_ID
    override val languages: Set<String> = config.languages.keys
    override val apiVersion: Int = CINT_API_VERSION

    override fun rules(): List<Rule> = listOf(ServiceDelegationRule(config), ServiceInitRule(config))

    override fun configure(values: Map<String, Any?>): ConfigurableRulePack =
        if (values.isEmpty()) this else ServiceRulePack(config.withOverrides(values))

    override fun configureAdditive(values: Map<String, Any?>): ConfigurableRulePack =
        if (values.isEmpty()) this else ServiceRulePack(config.withAdditiveOverrides(values))

    override fun configSchema(): ConfigSchema<*> = ServiceConfig.SCHEMA
}
