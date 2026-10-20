package dev.cint.core.runtime.rulepack

import dev.cint.api.*
import dev.cint.core.runtime.scheduler.*

internal fun settleRulePacks(
    packs: List<LoadedRulePack>,
    report: (RulePackLoadFailure) -> Unit,
): List<LoadedRulePack> {
    var remaining = packs
    val scheduler = RuleScheduler()
    while (true) {
        try {
            scheduler.plan(remaining)
            return remaining
        } catch (error: SchedulerException) {
            val skipped = selectSkipped(error, remaining)
            if (skipped.isEmpty()) {
                remaining.forEach {
                    report(
                        RulePackLoadFailure(
                            schedulerKind(error.error),
                            setOf(it.id),
                            "rulepack '${it.id}' failed to load: ${error.message}",
                            error,
                            error,
                        ),
                    )
                }
                return emptyList()
            }
            for (pack in skipped) {
                report(
                    RulePackLoadFailure(
                        schedulerKind(error.error),
                        setOf(pack.id),
                        "skipped rulepack '${pack.id}': ${error.message}",
                        error,
                        error,
                    ),
                )
            }
            remaining = remaining.filterNot { candidate -> skipped.any { it === candidate } }
        } catch (error: Throwable) {
            if (error is VirtualMachineError) throw error
            remaining.forEach {
                report(
                    RulePackLoadFailure(
                        RulePackLoadFailureKind.CORRUPT_PACK,
                        setOf(it.id),
                        "rulepack '${it.id}' failed to load: ${error.message ?: error.javaClass.name}",
                        error,
                    ),
                )
            }
            return emptyList()
        }
    }
}

private fun selectSkipped(
    error: SchedulerException,
    remaining: List<LoadedRulePack>,
): Set<LoadedRulePack> {
    val owners = identityMap<Rule, LoadedRulePack>()
    for (pack in remaining) {
        for (rule in pack.rules()) owners[rule] = pack
    }

    return when (error) {
        is DuplicateIntentOwner -> {
            identitySet<LoadedRulePack>().apply {
                for (pack in remaining) {
                    if (pack.rules().any { it.owns == error.intent }) add(pack)
                }
            }
        }

        is DuplicateRuleId -> {
            identitySet<LoadedRulePack>().apply {
                for (pack in remaining) {
                    if (pack.rules().any { it.id == error.ruleId }) add(pack)
                }
            }
        }

        is ReservedIntentOwner -> {
            identitySet<LoadedRulePack>().apply { owners[error.rule]?.let { add(it) } }
        }

        is ReservedContributorId -> {
            identitySet<LoadedRulePack>().apply { owners[error.rule]?.let { add(it) } }
        }

        is UnknownDependency -> {
            identitySet<LoadedRulePack>().apply { owners[error.reader]?.let { add(it) } }
        }

        is DependencyCycle -> {
            identitySet<LoadedRulePack>().apply {
                for (rule in error.rules) owners[rule]?.let { add(it) }
            }
        }
    }
}

private fun schedulerKind(error: SchedulerError): RulePackLoadFailureKind =
    when (error) {
        SchedulerError.DUPLICATE_INTENT_OWNER -> RulePackLoadFailureKind.DUPLICATE_INTENT_OWNER
        SchedulerError.DUPLICATE_RULE_ID -> RulePackLoadFailureKind.DUPLICATE_RULE_ID
        SchedulerError.RESERVED_INTENT_OWNER -> RulePackLoadFailureKind.RESERVED_INTENT_OWNER
        SchedulerError.RESERVED_CONTRIBUTOR_ID -> RulePackLoadFailureKind.RESERVED_CONTRIBUTOR_ID
        SchedulerError.UNKNOWN_DEPENDENCY -> RulePackLoadFailureKind.UNKNOWN_DEPENDENCY
        SchedulerError.DEPENDENCY_CYCLE -> RulePackLoadFailureKind.DEPENDENCY_CYCLE
    }
