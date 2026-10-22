package dev.cint.host.support

import dev.cint.api.CINT_API_VERSION
import dev.cint.api.Emit
import dev.cint.api.Intent
import dev.cint.api.Rule
import dev.cint.api.RuleEmit
import dev.cint.api.RulePack
import dev.cint.api.View

private class HostFixtureRule(
    override val id: String,
    override val owns: Intent,
) : Rule {
    override val reads: Set<Intent> = emptySet()

    override fun run(view: View): List<RuleEmit> = emptyList()
}

class HostFixtureGoodPack : RulePack {
    override val id: String = "fixture-good"
    override val languages: Set<String> = setOf("kotlin")
    override val apiVersion: Int = CINT_API_VERSION

    override fun rules(): List<Rule> = listOf(HostFixtureRule("fixture-good:logging", "LOGGING"))
}
