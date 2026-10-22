package dev.cint.host.support

import dev.cint.api.CINT_API_VERSION
import dev.cint.api.ConfigurableRulePack
import dev.cint.api.Emit
import dev.cint.api.Intent
import dev.cint.api.Rule
import dev.cint.api.RuleEmit
import dev.cint.api.RulePack
import dev.cint.api.RulePackConfigException
import dev.cint.api.View

private class FixtureRule(
    override val id: String,
    override val owns: Intent,
) : Rule {
    override val reads: Set<Intent> = emptySet()

    override fun run(view: View): List<RuleEmit> = emptyList()
}

class FixtureConfigurablePack(
    private val calls: Set<String> = setOf("default"),
) : ConfigurableRulePack {
    companion object {
        var lastCalls: Set<String>? = null
            private set

        fun reset() {
            lastCalls = null
        }
    }

    override val id: String = "fixture-configurable"
    override val languages: Set<String> = setOf("kotlin")
    override val apiVersion: Int = CINT_API_VERSION

    override fun configure(values: Map<String, Any?>): RulePack {
        val unknown = values.keys - "calls"
        if (unknown.isNotEmpty()) {
            throw RulePackConfigException("fixture-configurable.${unknown.minOf { it }}: unknown key")
        }
        val configured =
            when (val raw = values["calls"]) {
                null -> {
                    calls
                }

                is String -> {
                    setOf(raw)
                }

                is List<*> -> {
                    if (raw.all { it is String }) {
                        raw.map { it as String }.toSet()
                    } else {
                        throw RulePackConfigException("fixture-configurable.calls: expected strings")
                    }
                }

                else -> {
                    throw RulePackConfigException("fixture-configurable.calls: expected a string or list")
                }
            }
        lastCalls = configured
        return FixtureConfigurablePack(configured)
    }

    override fun rules(): List<Rule> = listOf(FixtureRule("fixture-configurable:logging", "CONFIGURABLE_LOGGING"))
}
