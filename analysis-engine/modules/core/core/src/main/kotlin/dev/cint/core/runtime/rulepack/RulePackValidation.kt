package dev.cint.core.runtime.rulepack

import dev.cint.api.*

internal fun validateRulePack(
    pack: RulePack,
    className: String,
    expectedApiVersion: Int,
    report: (RulePackLoadFailure) -> Unit,
): LoadedRulePack? {
    val id =
        try {
            pack.id
        } catch (error: Throwable) {
            if (error is VirtualMachineError) throw error
            report(
                RulePackLoadFailure(
                    RulePackLoadFailureKind.CORRUPT_PACK,
                    emptySet(),
                    "rulepack could not report its id: ${error.message ?: error.javaClass.name}",
                    error,
                ),
            )
            return null
        }

    if (id.isBlank()) {
        report(
            RulePackLoadFailure(
                RulePackLoadFailureKind.CORRUPT_PACK,
                emptySet(),
                "rulepack has a blank id",
            ),
        )
        return null
    }

    val languages =
        try {
            pack.languages.toSet()
        } catch (error: Throwable) {
            if (error is VirtualMachineError) throw error
            report(
                RulePackLoadFailure(
                    RulePackLoadFailureKind.CORRUPT_PACK,
                    setOf(id),
                    "rulepack '$id' could not report its languages: ${error.message ?: error.javaClass.name}",
                    error,
                ),
            )
            return null
        }

    if (languages.any { it.isBlank() }) {
        report(
            RulePackLoadFailure(
                RulePackLoadFailureKind.CORRUPT_PACK,
                setOf(id),
                "rulepack '$id' has a blank language",
            ),
        )
        return null
    }

    val version =
        try {
            pack.apiVersion
        } catch (error: Throwable) {
            if (error is VirtualMachineError) throw error
            val incompatible = error is LinkageError
            report(
                RulePackLoadFailure(
                    if (incompatible) {
                        RulePackLoadFailureKind.INCOMPATIBLE_API_VERSION
                    } else {
                        RulePackLoadFailureKind.CORRUPT_PACK
                    },
                    setOf(id),
                    "rulepack '$id' could not report its api version: ${error.message ?: error.javaClass.name}",
                    error,
                ),
            )
            return null
        }

    if (version != expectedApiVersion) {
        report(
            RulePackLoadFailure(
                RulePackLoadFailureKind.INCOMPATIBLE_API_VERSION,
                setOf(id),
                "rulepack '$id' targets api version $version, expected $expectedApiVersion",
            ),
        )
        return null
    }

    val rules =
        try {
            pack.rules()
        } catch (error: Throwable) {
            if (error is VirtualMachineError) throw error
            report(
                RulePackLoadFailure(
                    RulePackLoadFailureKind.CORRUPT_PACK,
                    setOf(id),
                    "rulepack '$id' could not provide its rules: ${error.message ?: error.javaClass.name}",
                    error,
                ),
            )
            return null
        }

    val cachedRules = validateRules(id, rules, report) ?: return null
    return LoadedRulePack(id, languages, version, cachedRules, className)
}

private fun validateRules(
    packId: String,
    rules: List<*>?,
    report: (RulePackLoadFailure) -> Unit,
): List<LoadedRule>? {
    if (rules == null) {
        report(
            RulePackLoadFailure(
                RulePackLoadFailureKind.CORRUPT_PACK,
                setOf(packId),
                "rulepack '$packId' provided no rule list",
            ),
        )
        return null
    }
    val prefix = "$packId:"
    val loaded = mutableListOf<LoadedRule>()
    for (element in rules) {
        if (element !is Rule) {
            report(
                RulePackLoadFailure(
                    RulePackLoadFailureKind.CORRUPT_PACK,
                    setOf(packId),
                    "rulepack '$packId' contains a rule list entry that is not a Rule",
                ),
            )
            return null
        }
        val metadata =
            try {
                val reads: Set<String> = element.reads
                RuleMetadata(element.id, element.owns, reads.toSet())
            } catch (error: Throwable) {
                if (error is VirtualMachineError) throw error
                report(
                    RulePackLoadFailure(
                        RulePackLoadFailureKind.CORRUPT_PACK,
                        setOf(packId),
                        "rulepack '$packId' has a rule with unreadable metadata: ${error.message ?: error.javaClass.name}",
                        error,
                    ),
                )
                return null
            }

        val ruleId = metadata.id
        if (ruleId.isNullOrBlank()) {
            report(
                RulePackLoadFailure(
                    RulePackLoadFailureKind.CORRUPT_PACK,
                    setOf(packId),
                    "rulepack '$packId' has a rule with a blank id",
                ),
            )
            return null
        }

        if (!ruleId.startsWith(prefix) || ruleId.length == prefix.length) {
            report(
                RulePackLoadFailure(
                    RulePackLoadFailureKind.CORRUPT_PACK,
                    setOf(packId),
                    "rulepack '$packId' has rule '$ruleId' outside its namespace",
                ),
            )
            return null
        }

        val owns = metadata.owns
        if (owns.isNullOrBlank()) {
            report(
                RulePackLoadFailure(
                    RulePackLoadFailureKind.CORRUPT_PACK,
                    setOf(packId),
                    "rulepack '$packId' has rule '$ruleId' with a blank owned intent",
                ),
            )
            return null
        }

        val reads = metadata.reads
        if (reads == null) {
            report(
                RulePackLoadFailure(
                    RulePackLoadFailureKind.CORRUPT_PACK,
                    setOf(packId),
                    "rulepack '$packId' has rule '$ruleId' with no read set",
                ),
            )
            return null
        }

        if (reads.any { it.isBlank() }) {
            report(
                RulePackLoadFailure(
                    RulePackLoadFailureKind.CORRUPT_PACK,
                    setOf(packId),
                    "rulepack '$packId' has rule '$ruleId' with a blank read intent",
                ),
            )
            return null
        }

        loaded += LoadedRule(element, ruleId, owns, reads)
    }
    return loaded.toList()
}

internal fun rejectDuplicatePackIds(
    packs: List<LoadedRulePack>,
    report: (RulePackLoadFailure) -> Unit,
): List<LoadedRulePack> {
    val accepted = mutableListOf<LoadedRulePack>()
    for ((id, group) in packs.groupBy { it.id }) {
        if (group.size == 1) {
            accepted += group.single()
        } else {
            report(
                RulePackLoadFailure(
                    RulePackLoadFailureKind.DUPLICATE_PACK_ID,
                    setOf(id),
                    "duplicate rulepack id '$id' from ${group.joinToString { it.sourceClassName }}",
                ),
            )
        }
    }
    return accepted
}
