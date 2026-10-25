package dev.cint.packs

import dev.cint.contracts.data.Intents
import dev.cint.contracts.ports.PackDiagnostic
import dev.cint.contracts.ports.PackManifest
import dev.cint.contracts.util.ContractError

class PackCatalog private constructor(
    packDefinitions: List<PackDefinition>,
) {
    private val orderedDefinitions: List<PackDefinition> =
        packDefinitions.sortedWith(compareBy({ it.manifest.id }, { it.manifest.name }))

    private val definitionsById: Map<String, PackDefinition>
    private val degraded: Map<String, ContractError>

    val definitions: List<PackDefinition>
    val availableDefinitions: List<PackDefinition>
    val manifests: List<PackManifest>
    val diagnostics: List<PackDiagnostic>

    init {
        val byId = linkedMapOf<String, PackDefinition>()
        val issues = linkedMapOf<String, ContractError>()
        val findings = mutableListOf<PackDiagnostic>()

        fun report(
            packId: String,
            code: String,
            message: String,
            error: ContractError,
        ) {
            findings += PackDiagnostic(packId = packId, code = code, message = message, error = error)
            issues.putIfAbsent(packId, error)
        }

        for ((id, group) in orderedDefinitions.groupBy { it.manifest.id }) {
            if (group.size > 1) {
                report(
                    packId = id,
                    code = "DUPLICATE_PACK_ID",
                    message = "Pack id '$id' is declared ${group.size} times",
                    error = ContractError.ValidationError("Duplicate pack id '$id'", field = "id"),
                )
            } else {
                byId[id] = group.single()
            }
        }

        val ids = byId.keys.sorted()

        for (id in ids) {
            val definition = byId.getValue(id)
            val manifest = definition.manifest
            val playbook = definition.playbook
            if (id.isBlank()) {
                report(id, "BLANK_PACK_ID", "Pack id must not be blank", ContractError.ValidationError("Pack id must not be blank", "id"))
            }
            if (manifest.version.isBlank()) {
                report(
                    id,
                    "BLANK_PACK_VERSION",
                    "Pack '$id' has a blank version",
                    ContractError.ValidationError("Pack version must not be blank", "version"),
                )
            }
            if (manifest.languages.isEmpty() || manifest.languages.any { it.isBlank() }) {
                report(
                    id,
                    "INVALID_PACK_LANGUAGES",
                    "Pack '$id' has invalid languages",
                    ContractError.ValidationError("Pack languages must be non-empty and non-blank", "languages"),
                )
            }
            if (playbook.packId != id) {
                report(
                    id,
                    "PLAYBOOK_PACK_MISMATCH",
                    "Playbook packId '${playbook.packId}' does not match manifest id '$id'",
                    ContractError.ValidationError("Playbook packId does not match manifest id", "packId"),
                )
            }
            val duplicateRuleIds =
                playbook.rules
                    .groupBy { it.id }
                    .filterValues { it.size > 1 }
                    .keys
                    .sorted()
            for (ruleId in duplicateRuleIds) {
                report(
                    id,
                    "DUPLICATE_RULE_ID",
                    "Pack '$id' declares rule id '$ruleId' more than once",
                    ContractError.ValidationError("Duplicate rule id '$ruleId' in pack '$id'", "rules"),
                )
            }
            val duplicateOwned =
                playbook.rules
                    .groupBy { it.owns }
                    .filterValues { it.size > 1 }
                    .keys
                    .sorted()
            for (intent in duplicateOwned) {
                report(
                    id,
                    "DUPLICATE_OWNED_INTENT",
                    "Pack '$id' owns intent '$intent' more than once",
                    ContractError.ValidationError("Intent '$intent' is owned more than once in pack '$id'", "rules"),
                )
            }
            val owned = playbook.rules.map { it.owns }.toSet()
            if (manifest.providesIntents.toSet() != owned) {
                report(
                    id,
                    "MANIFEST_INTENT_MISMATCH",
                    "Pack '$id' advertises intents ${manifest.providesIntents.sorted()} but its playbook owns ${owned.sorted()}",
                    ContractError.ValidationError("Manifest providesIntents does not match playbook ownership", "providesIntents"),
                )
            }
            val languages = manifest.languages.toSet()
            for (seed in definition.seeds) {
                val undeclaredLanguages = seed.languages.filterNot { it in languages }.sorted()
                if (undeclaredLanguages.isNotEmpty()) {
                    report(
                        id,
                        "SEED_LANGUAGE_NOT_DECLARED",
                        "Pack '$id' has defaults for undeclared languages: ${undeclaredLanguages.joinToString(",")}",
                        ContractError.ValidationError("Seed languages are not declared by the manifest", "languages"),
                    )
                }
                val unsupportedIntents = seed.intents.filterNot { it in owned }.sorted()
                if (unsupportedIntents.isNotEmpty()) {
                    report(
                        id,
                        "SEED_INTENT_NOT_OWNED",
                        "Pack '$id' has defaults for unowned intents: ${unsupportedIntents.joinToString(",")}",
                        ContractError.ValidationError("Seed intents are not owned by the playbook", "intents"),
                    )
                }
                val unknownIntents = seed.intents.filterNot { Intents.isKnown(it) }.sorted()
                if (unknownIntents.isNotEmpty()) {
                    report(
                        id,
                        "UNKNOWN_SEED_INTENT",
                        "Pack '$id' has unknown intents: ${unknownIntents.joinToString(",")}",
                        ContractError.ValidationError("Seed intents are unknown", "intents"),
                    )
                }
            }
        }

        val ruleOwners = linkedMapOf<String, MutableList<String>>()
        val intentOwners = linkedMapOf<String, MutableList<String>>()
        for (id in ids) {
            for (rule in byId.getValue(id).playbook.rules) {
                if (rule.id.isBlank()) {
                    report(
                        id,
                        "BLANK_RULE_ID",
                        "Pack '$id' has a rule with a blank id",
                        ContractError.ValidationError("Rule id must not be blank", "rules"),
                    )
                } else {
                    ruleOwners.getOrPut(rule.id) { mutableListOf() } += id
                }
                if (rule.owns.isBlank()) {
                    report(
                        id,
                        "BLANK_RULE_INTENT",
                        "Pack '$id' has a rule with a blank intents owner",
                        ContractError.ValidationError("Rule owning intent must not be blank", "rules"),
                    )
                } else {
                    intentOwners.getOrPut(rule.owns) { mutableListOf() } += id
                }
            }
        }
        for ((ruleId, owners) in ruleOwners) {
            val distinct = owners.distinct()
            if (distinct.size > 1) {
                for (owner in distinct) {
                    report(
                        owner,
                        "GLOBAL_DUPLICATE_RULE_ID",
                        "Rule id '$ruleId' is declared by multiple packs: ${distinct.joinToString(",")}",
                        ContractError.ValidationError("Rule id '$ruleId' is declared by multiple packs", "rules"),
                    )
                }
            }
        }
        for ((intent, owners) in intentOwners) {
            val distinct = owners.distinct()
            if (distinct.size > 1) {
                for (owner in distinct) {
                    report(
                        owner,
                        "GLOBAL_DUPLICATE_OWNED_INTENT",
                        "Intent '$intent' is owned by multiple packs: ${distinct.joinToString(",")}",
                        ContractError.ValidationError("Intent '$intent' is owned by multiple packs", "providesIntents"),
                    )
                }
            }
        }

        fun reportDependency(
            packId: String,
            code: String,
            message: String,
            dependency: String,
        ) {
            report(
                packId,
                code,
                message,
                ContractError.DependencyUnavailable(dependency = "pack:$dependency", reason = message),
            )
        }

        val state = mutableMapOf<String, Int>()
        val stack = mutableListOf<String>()

        fun visit(id: String) {
            state[id] = VISITING
            stack.add(id)
            val dependencies =
                byId
                    .getValue(id)
                    .manifest.dependencies
                    .distinct()
                    .sorted()
            for (dependency in dependencies) {
                if (dependency !in byId) continue
                when (state[dependency]) {
                    null -> {
                        visit(dependency)
                    }

                    VISITING -> {
                        val cycle = stack.dropWhile { it != dependency } + dependency
                        val message = "Dependency cycle: ${cycle.joinToString(" -> ")}"
                        cycle.dropLast(1).forEach { member ->
                            report(
                                member,
                                "DEPENDENCY_CYCLE",
                                message,
                                ContractError.ValidationError(message, field = "dependencies"),
                            )
                        }
                    }
                }
            }
            stack.removeAt(stack.lastIndex)
            state[id] = VISITED
        }

        for (id in ids) {
            if (state[id] == null) visit(id)
        }

        for (id in ids) {
            val missing =
                byId
                    .getValue(id)
                    .manifest.dependencies
                    .distinct()
                    .sorted()
                    .firstOrNull { it !in byId }
            if (missing != null) {
                reportDependency(id, "MISSING_DEPENDENCY", "Missing dependency '$missing' of pack '$id'", missing)
            }
        }

        var changed = true
        while (changed) {
            changed = false
            for (id in ids) {
                val unavailable =
                    byId
                        .getValue(id)
                        .manifest.dependencies
                        .distinct()
                        .sorted()
                        .firstOrNull { it !in byId || it in issues }
                if (unavailable != null && id !in issues) {
                    reportDependency(id, "DEPENDENCY_UNAVAILABLE", "Dependency '$unavailable' of pack '$id' is unavailable", unavailable)
                    changed = true
                }
            }
        }

        definitionsById = byId.toMap()
        degraded = issues.toMap()
        definitions = byId.values.sortedWith(compareBy({ it.manifest.id }, { it.manifest.name }))
        availableDefinitions = definitions.filter { it.manifest.id !in degraded }
        manifests = availableDefinitions.map { it.manifest }
        diagnostics = findings.sortedWith(compareBy({ it.packId }, { it.code }, { it.message }))
    }

    val degradedPacks: Map<String, ContractError>
        get() = degraded.entries.sortedBy { it.key }.associate { it.toPair() }

    fun definition(packId: String): PackDefinition? = definitionsById[packId]

    fun issue(packId: String): ContractError? = degraded[packId]

    companion object {
        private const val VISITING = 1
        private const val VISITED = 2

        fun of(definitions: List<PackDefinition>): PackCatalog = PackCatalog(definitions)

        fun bundled(): PackCatalog = PackCatalog(BundledPacks.all)
    }
}
