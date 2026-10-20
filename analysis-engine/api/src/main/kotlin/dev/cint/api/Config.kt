package dev.cint.api

interface ConfigurableRulePack : RulePack {
    fun configure(values: Map<String, Any?>): RulePack
}

class RulePackConfigException(
    message: String,
) : IllegalArgumentException(message)
