package dev.cint.core.rulepack

import dev.cint.api.CINT_API_VERSION
import dev.cint.api.Emit
import dev.cint.api.Intent
import dev.cint.api.Rule
import dev.cint.api.RulePack
import dev.cint.api.RulePackApi
import dev.cint.api.View
import dev.cint.core.scheduler.DependencyCycle
import dev.cint.core.scheduler.DuplicateIntentOwner
import dev.cint.core.scheduler.DuplicateRuleId
import dev.cint.core.scheduler.ReservedIntentOwner
import dev.cint.core.scheduler.RuleScheduler
import dev.cint.core.scheduler.SchedulerError
import dev.cint.core.scheduler.SchedulerException
import dev.cint.core.scheduler.UnknownDependency
import java.util.Collections
import java.util.IdentityHashMap

enum class RulePackLoadFailureKind {
    MISSING_PROVIDER,
    INCOMPATIBLE_API_VERSION,
    CORRUPT_PACK,
    DISCOVERY_LIMIT_EXCEEDED,
    DUPLICATE_PACK_ID,
    DUPLICATE_INTENT_OWNER,
    DUPLICATE_RULE_ID,
    RESERVED_INTENT_OWNER,
    UNKNOWN_DEPENDENCY,
    DEPENDENCY_CYCLE,
}

data class RulePackLoadFailure(
    val kind: RulePackLoadFailureKind,
    val packIds: Set<String>,
    val message: String,
    val cause: Throwable? = null,
    val schedulerError: SchedulerException? = null,
) {
    val packId: String? get() = packIds.singleOrNull()
}

data class RulePackLoadResult(
    val packs: List<RulePack>,
    val failures: List<RulePackLoadFailure>,
) {
    val loadedPackIds: List<String> get() = packs.map { it.id }

    val isDegraded: Boolean get() = failures.isNotEmpty()
}

class RulePackLoader(
    private val classLoader: ClassLoader = defaultClassLoader(),
    private val expectedApiVersion: Int = CINT_API_VERSION,
    private val onFailure: (RulePackLoadFailure) -> Unit = {
        System.err.println("cint rulepack: ${it.message}")
    },
) {
    fun load(): RulePackLoadResult {
        val failures = mutableListOf<RulePackLoadFailure>()

        fun report(failure: RulePackLoadFailure) {
            failures += failure
            onFailure(failure)
        }

        val loaded = settle(discover(::report), ::report)
        return RulePackLoadResult(loaded, failures.toList())
    }

    private fun discover(report: (RulePackLoadFailure) -> Unit): List<LoadedRulePack> {
        val loaded = mutableListOf<LoadedRulePack>()
        for (name in providerNames(report)) {
            discoverProvider(name, report)?.let { loaded += it }
        }
        return rejectDuplicatePackIds(loaded, report)
            .sortedWith(compareBy({ it.id }, { it.sourceClassName }))
    }

    private fun providerNames(report: (RulePackLoadFailure) -> Unit): List<String> {
        val names = linkedSetOf<String>()
        var resourceCount = 0
        var lineCount = 0
        var stop = false
        val resources =
            try {
                classLoader.getResources(SERVICE_RESOURCE)
            } catch (error: Throwable) {
                if (error is VirtualMachineError) throw error
                report(
                    RulePackLoadFailure(
                        RulePackLoadFailureKind.CORRUPT_PACK,
                        emptySet(),
                        "could not enumerate $SERVICE_RESOURCE: ${error.message ?: error.javaClass.name}",
                        error,
                    ),
                )
                return emptyList()
            }

        while (!stop) {
            val hasMore =
                try {
                    resources.hasMoreElements()
                } catch (error: Throwable) {
                    if (error is VirtualMachineError) throw error
                    report(
                        RulePackLoadFailure(
                            RulePackLoadFailureKind.CORRUPT_PACK,
                            emptySet(),
                            "could not continue enumerating $SERVICE_RESOURCE: ${error.message ?: error.javaClass.name}",
                            error,
                        ),
                    )
                    break
                }
            if (!hasMore) break

            val url =
                try {
                    resources.nextElement()
                } catch (error: Throwable) {
                    if (error is VirtualMachineError) throw error
                    report(
                        RulePackLoadFailure(
                            RulePackLoadFailureKind.CORRUPT_PACK,
                            emptySet(),
                            "could not read the next $SERVICE_RESOURCE location: ${error.message ?: error.javaClass.name}",
                            error,
                        ),
                    )
                    break
                }

            resourceCount += 1
            if (resourceCount > MAX_SERVICE_RESOURCES) {
                report(
                    RulePackLoadFailure(
                        RulePackLoadFailureKind.DISCOVERY_LIMIT_EXCEEDED,
                        emptySet(),
                        "stopped after $MAX_SERVICE_RESOURCES service resources",
                    ),
                )
                break
            }

            try {
                url.openStream().bufferedReader(Charsets.UTF_8).useLines { lines ->
                    for (line in lines) {
                        lineCount += 1
                        if (lineCount > MAX_SERVICE_LINES) {
                            report(
                                RulePackLoadFailure(
                                    RulePackLoadFailureKind.DISCOVERY_LIMIT_EXCEEDED,
                                    emptySet(),
                                    "stopped after $MAX_SERVICE_LINES service lines",
                                ),
                            )
                            stop = true
                            return@useLines
                        }
                        val name = line.substringBefore('#').trim()
                        if (name.isEmpty()) continue
                        if (!isValidProviderName(name)) {
                            report(
                                RulePackLoadFailure(
                                    RulePackLoadFailureKind.CORRUPT_PACK,
                                    emptySet(),
                                    "illegal provider-class name '$name' in $url",
                                ),
                            )
                            continue
                        }
                        names += name
                    }
                }
            } catch (error: Throwable) {
                if (error is VirtualMachineError) throw error
                report(
                    RulePackLoadFailure(
                        RulePackLoadFailureKind.CORRUPT_PACK,
                        emptySet(),
                        "could not read $url: ${error.message ?: error.javaClass.name}",
                        error,
                    ),
                )
            }
        }
        return names.toList()
    }

    private fun isValidProviderName(name: String): Boolean {
        var index = 0
        var expectingStart = true
        while (index < name.length) {
            val codePoint = name.codePointAt(index)
            when {
                codePoint == '.'.code -> {
                    if (expectingStart) return false
                    expectingStart = true
                }
                expectingStart -> {
                    if (!Character.isJavaIdentifierStart(codePoint) && codePoint != '$'.code) return false
                    expectingStart = false
                }
                else -> {
                    if (!Character.isJavaIdentifierPart(codePoint) && codePoint != '$'.code) return false
                }
            }
            index += Character.charCount(codePoint)
        }
        return !expectingStart
    }

    private fun discoverProvider(
        name: String,
        report: (RulePackLoadFailure) -> Unit,
    ): LoadedRulePack? {
        var className: String? = name
        return try {
            val type = Class.forName(name, false, classLoader)
            className = type.name
            if (!RulePack::class.java.isAssignableFrom(type)) {
                report(
                    RulePackLoadFailure(
                        RulePackLoadFailureKind.CORRUPT_PACK,
                        emptySet(),
                        "provider '$className' is not a RulePack",
                    ),
                )
                return null
            }

            val declaredVersion = type.getAnnotation(RulePackApi::class.java)?.version
            if (declaredVersion != null && declaredVersion != expectedApiVersion) {
                report(
                    RulePackLoadFailure(
                        RulePackLoadFailureKind.INCOMPATIBLE_API_VERSION,
                        emptySet(),
                        "provider '$className' targets api version $declaredVersion, expected $expectedApiVersion",
                    ),
                )
                return null
            }

            val versionMethod =
                try {
                    type.getMethod("getApiVersion")
                } catch (error: NoSuchMethodException) {
                    report(
                        RulePackLoadFailure(
                            RulePackLoadFailureKind.INCOMPATIBLE_API_VERSION,
                            emptySet(),
                            "provider '$className' does not declare an api version",
                        ),
                    )
                    return null
                }
            if (versionMethod.declaringClass == RulePack::class.java) {
                report(
                    RulePackLoadFailure(
                        RulePackLoadFailureKind.INCOMPATIBLE_API_VERSION,
                        emptySet(),
                        "provider '$className' does not declare an api version",
                    ),
                )
                return null
            }

            val instance = type.getDeclaredConstructor().newInstance()
            val pack = instance as? RulePack
            if (pack == null) {
                report(
                    RulePackLoadFailure(
                        RulePackLoadFailureKind.CORRUPT_PACK,
                        emptySet(),
                        "provider '$className' is not a RulePack",
                    ),
                )
                return null
            }
            validate(pack, className, report)
        } catch (error: ClassNotFoundException) {
            report(providerFailure(className, error, missingProvider = true))
            null
        } catch (error: Throwable) {
            if (error is VirtualMachineError) throw error
            report(providerFailure(className, error))
            null
        }
    }

    private fun validate(
        pack: RulePack,
        className: String,
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

        if (id.isNullOrBlank()) {
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

        if (languages.any { it.isNullOrBlank() }) {
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
            val rule = element
            val metadata =
                try {
                    val reads: Set<String>? = rule.reads
                    RuleMetadata(rule.id, rule.owns, reads?.toSet())
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

            if (reads.any { it.isNullOrBlank() }) {
                report(
                    RulePackLoadFailure(
                        RulePackLoadFailureKind.CORRUPT_PACK,
                        setOf(packId),
                        "rulepack '$packId' has rule '$ruleId' with a blank read intent",
                    ),
                )
                return null
            }

            loaded += LoadedRule(rule, ruleId, owns, reads)
        }
        return loaded.toList()
    }

    private fun rejectDuplicatePackIds(
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

    private fun settle(
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
            is DuplicateIntentOwner ->
                identitySet<LoadedRulePack>().apply {
                    for (pack in remaining) {
                        if (pack.rules().any { it.owns == error.intent }) add(pack)
                    }
                }

            is DuplicateRuleId ->
                identitySet<LoadedRulePack>().apply {
                    for (pack in remaining) {
                        if (pack.rules().any { it.id == error.ruleId }) add(pack)
                    }
                }

            is ReservedIntentOwner ->
                identitySet<LoadedRulePack>().apply { owners[error.rule]?.let { add(it) } }

            is UnknownDependency ->
                identitySet<LoadedRulePack>().apply { owners[error.reader]?.let { add(it) } }

            is DependencyCycle ->
                identitySet<LoadedRulePack>().apply {
                    for (rule in error.rules) owners[rule]?.let { add(it) }
                }
        }
    }

    private fun providerFailure(
        className: String?,
        error: Throwable,
        missingProvider: Boolean = false,
    ): RulePackLoadFailure {
        val provider = className?.let { " '$it'" }.orEmpty()
        return RulePackLoadFailure(
            if (missingProvider) {
                RulePackLoadFailureKind.MISSING_PROVIDER
            } else {
                RulePackLoadFailureKind.CORRUPT_PACK
            },
            emptySet(),
            "provider$provider could not be loaded: ${error.message ?: error.javaClass.name}",
            error,
        )
    }

    private fun schedulerKind(error: SchedulerError): RulePackLoadFailureKind =
        when (error) {
            SchedulerError.DUPLICATE_INTENT_OWNER -> RulePackLoadFailureKind.DUPLICATE_INTENT_OWNER
            SchedulerError.DUPLICATE_RULE_ID -> RulePackLoadFailureKind.DUPLICATE_RULE_ID
            SchedulerError.RESERVED_INTENT_OWNER -> RulePackLoadFailureKind.RESERVED_INTENT_OWNER
            SchedulerError.UNKNOWN_DEPENDENCY -> RulePackLoadFailureKind.UNKNOWN_DEPENDENCY
            SchedulerError.DEPENDENCY_CYCLE -> RulePackLoadFailureKind.DEPENDENCY_CYCLE
        }

    private data class RuleMetadata(
        val id: String?,
        val owns: String?,
        val reads: Set<String>?,
    )

    private class LoadedRulePack(
        override val id: String,
        override val languages: Set<String>,
        override val apiVersion: Int,
        private val cachedRules: List<Rule>,
        val sourceClassName: String,
    ) : RulePack {
        override fun rules(): List<Rule> = cachedRules
    }

    private class LoadedRule(
        private val source: Rule,
        override val id: String,
        override val owns: Intent,
        override val reads: Set<Intent>,
    ) : Rule {
        override fun run(view: View): List<Emit> = source.run(view)
    }

    private companion object {
        val SERVICE_RESOURCE = "META-INF/services/${RulePack::class.java.name}"
        const val MAX_SERVICE_RESOURCES = 256
        const val MAX_SERVICE_LINES = 4096

        fun defaultClassLoader(): ClassLoader =
            Thread.currentThread().contextClassLoader ?: RulePack::class.java.classLoader

        fun <K, V> identityMap(): MutableMap<K, V> = IdentityHashMap()

        fun <T> identitySet(): MutableSet<T> =
            Collections.newSetFromMap(IdentityHashMap<T, Boolean>())
    }
}
