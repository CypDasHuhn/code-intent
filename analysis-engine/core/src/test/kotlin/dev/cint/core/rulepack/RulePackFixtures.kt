package dev.cint.core.rulepack

import dev.cint.api.CINT_API_VERSION
import dev.cint.api.ConfigurableRulePack
import dev.cint.api.Emit
import dev.cint.api.Intent
import dev.cint.api.Rule
import dev.cint.api.RulePack
import dev.cint.api.RulePackApi
import dev.cint.api.RulePackConfigException
import dev.cint.api.View

private class FixtureRule(
    override val id: String,
    override val owns: Intent,
    override val reads: Set<Intent> = emptySet(),
) : Rule {
    override fun run(view: View): List<Emit> = emptyList()
}

class FixtureGoodPack : RulePack {
    override val id: String = "fixture-good"
    override val languages: Set<String> = setOf("kotlin")
    override val apiVersion: Int = CINT_API_VERSION

    override fun rules(): List<Rule> = listOf(FixtureRule("fixture-good:logging", "LOGGING"))
}

class FixtureDuplicateOwnerPackA : RulePack {
    override val id: String = "aaa-duplicate-owner"
    override val languages: Set<String> = setOf("kotlin")
    override val apiVersion: Int = CINT_API_VERSION

    override fun rules(): List<Rule> = listOf(FixtureRule("aaa-duplicate-owner:conflict", "DUPLICATE_OWNER"))
}

class FixtureDuplicateOwnerPackB : RulePack {
    override val id: String = "zzz-duplicate-owner"
    override val languages: Set<String> = setOf("kotlin")
    override val apiVersion: Int = CINT_API_VERSION

    override fun rules(): List<Rule> = listOf(FixtureRule("zzz-duplicate-owner:conflict", "DUPLICATE_OWNER"))
}

class FixtureDuplicateRuleIdPack : RulePack {
    override val id: String = "zzz-duplicate-rule-id"
    override val languages: Set<String> = setOf("kotlin")
    override val apiVersion: Int = CINT_API_VERSION

    override fun rules(): List<Rule> =
        listOf(
            FixtureRule("zzz-duplicate-rule-id:shared", "ZZZ_DUPLICATE_FIRST"),
            FixtureRule("zzz-duplicate-rule-id:shared", "ZZZ_DUPLICATE_SECOND"),
        )
}

class FixtureReservedIntentPack : RulePack {
    override val id: String = "zzz-reserved"
    override val languages: Set<String> = setOf("kotlin")
    override val apiVersion: Int = CINT_API_VERSION

    override fun rules(): List<Rule> = listOf(FixtureRule("zzz-reserved:untagged", "UNTAGGED"))
}

class FixtureUnknownDependencyPack : RulePack {
    override val id: String = "zzz-unknown"
    override val languages: Set<String> = setOf("kotlin")
    override val apiVersion: Int = CINT_API_VERSION

    override fun rules(): List<Rule> = listOf(FixtureRule("zzz-unknown:reader", "UNKNOWN_OUTPUT", setOf("MISSING")))
}

class FixtureCyclePack : RulePack {
    override val id: String = "zzz-cycle"
    override val languages: Set<String> = setOf("kotlin")
    override val apiVersion: Int = CINT_API_VERSION

    override fun rules(): List<Rule> =
        listOf(
            FixtureRule("zzz-cycle:first", "CYCLE_FIRST", setOf("CYCLE_SECOND")),
            FixtureRule("zzz-cycle:second", "CYCLE_SECOND", setOf("CYCLE_FIRST")),
        )
}

class FixtureThrowingConstructorPack : RulePack {
    init {
        error("constructor fixture pack")
    }

    override val id: String = "zzz-throwing-constructor"
    override val languages: Set<String> = setOf("kotlin")
    override val apiVersion: Int = CINT_API_VERSION

    override fun rules(): List<Rule> = emptyList()
}

class FixtureCorruptPack : RulePack {
    override val id: String = "zzz-corrupt"
    override val languages: Set<String> = setOf("kotlin")
    override val apiVersion: Int = CINT_API_VERSION

    override fun rules(): List<Rule> = error("corrupt fixture pack")
}

class FixtureMismatchedPack : RulePack {
    override val id: String = "zzz-mismatched"
    override val languages: Set<String> = setOf("kotlin")
    override val apiVersion: Int = CINT_API_VERSION + 1

    override fun rules(): List<Rule> = listOf(FixtureRule("zzz-mismatched:logging", "MISMATCHED"))
}

@RulePackApi(CINT_API_VERSION + 1)
class FixtureAnnotatedMismatchPack : RulePack {
    companion object {
        var constructions: Int = 0
            private set

        fun reset() {
            constructions = 0
        }
    }

    init {
        constructions += 1
    }

    override val id: String = "zzz-annotated-mismatch"
    override val languages: Set<String> = setOf("kotlin")
    override val apiVersion: Int = CINT_API_VERSION

    override fun rules(): List<Rule> = listOf(FixtureRule("zzz-annotated-mismatch:logging", "ANNOTATED_MISMATCH"))
}

class FixtureThrowingLanguagesPack : RulePack {
    override val id: String = "zzz-throwing-languages"
    override val languages: Set<String>
        get() = error("throwing languages fixture pack")
    override val apiVersion: Int = CINT_API_VERSION

    override fun rules(): List<Rule> = emptyList()
}

class FixtureThrowingRuleMetadataPack : RulePack {
    override val id: String = "zzz-throwing-rule"
    override val languages: Set<String> = setOf("kotlin")
    override val apiVersion: Int = CINT_API_VERSION

    override fun rules(): List<Rule> =
        listOf(
            object : Rule {
                override val id: String
                    get() = error("throwing rule id")

                override val owns: Intent = "THROWING_RULE"
                override val reads: Set<Intent> = emptySet()

                override fun run(view: View): List<Emit> = emptyList()
            },
        )
}

class FixtureUnnamespacedRulePack : RulePack {
    override val id: String = "zzz-unnamespaced"
    override val languages: Set<String> = setOf("kotlin")
    override val apiVersion: Int = CINT_API_VERSION

    override fun rules(): List<Rule> = listOf(FixtureRule("logging", "UNNAMESPACED"))
}

class FixtureForeignNamespacePack : RulePack {
    override val id: String = "zzz-foreign"
    override val languages: Set<String> = setOf("kotlin")
    override val apiVersion: Int = CINT_API_VERSION

    override fun rules(): List<Rule> = listOf(FixtureRule("fixture-good:logging", "FOREIGN"))
}

class FixtureDuplicatePackIdOne : RulePack {
    override val id: String = "duplicate-pack"
    override val languages: Set<String> = setOf("kotlin")
    override val apiVersion: Int = CINT_API_VERSION

    override fun rules(): List<Rule> = listOf(FixtureRule("duplicate-pack:one", "DUPLICATE_PACK_ONE"))
}

class FixtureDuplicatePackIdTwo : RulePack {
    override val id: String = "duplicate-pack"
    override val languages: Set<String> = setOf("kotlin")
    override val apiVersion: Int = CINT_API_VERSION

    override fun rules(): List<Rule> = listOf(FixtureRule("duplicate-pack:two", "DUPLICATE_PACK_TWO"))
}

class FixtureStatefulRulesPack : RulePack {
    override val id: String = "fixture-stateful"
    override val languages: Set<String> = setOf("kotlin")
    override val apiVersion: Int = CINT_API_VERSION

    private var calls: Int = 0

    override fun rules(): List<Rule> {
        calls += 1
        check(calls == 1) { "rules() called more than once" }
        return listOf(FixtureRule("fixture-stateful:logging", "STATEFUL_LOGGING"))
    }
}

class FixtureCountingConstructorPack : RulePack {
    companion object {
        var constructions: Int = 0
            private set

        fun reset() {
            constructions = 0
        }
    }

    init {
        constructions += 1
    }

    override val id: String = "fixture-counting"
    override val languages: Set<String> = setOf("kotlin")
    override val apiVersion: Int = CINT_API_VERSION

    override fun rules(): List<Rule> = listOf(FixtureRule("fixture-counting:logging", "COUNTING_LOGGING"))
}

class FixtureLinkageFailurePack : RulePack {
    init {
        throw NoClassDefFoundError("missing/dependency")
    }

    override val id: String = "zzz-linkage"
    override val languages: Set<String> = setOf("kotlin")
    override val apiVersion: Int = CINT_API_VERSION

    override fun rules(): List<Rule> = emptyList()
}

class FixtureÜnicodePack : RulePack {
    override val id: String = "fixture-unicode"
    override val languages: Set<String> = setOf("kotlin")
    override val apiVersion: Int = CINT_API_VERSION

    override fun rules(): List<Rule> = listOf(FixtureRule("fixture-unicode:logging", "UNICODE_LOGGING"))
}

class FixtureConfigurableMismatchPack : ConfigurableRulePack {
    companion object {
        var configurations: Int = 0
            private set

        fun reset() {
            configurations = 0
        }
    }

    override val id: String = "zzz-configurable-mismatch"
    override val languages: Set<String> = setOf("kotlin")
    override val apiVersion: Int = CINT_API_VERSION + 1

    override fun configure(values: Map<String, Any?>): RulePack {
        configurations += 1
        return this
    }

    override fun rules(): List<Rule> = listOf(FixtureRule("zzz-configurable-mismatch:logging", "CONFIGURABLE_MISMATCH"))
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
            throw RulePackConfigException("fixture-configurable.${unknown.sorted().first()}: unknown key")
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
