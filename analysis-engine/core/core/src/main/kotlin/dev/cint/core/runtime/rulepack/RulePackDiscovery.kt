package dev.cint.core.runtime.rulepack

import dev.cint.api.*

internal fun discoverRulePacks(
    classLoader: ClassLoader,
    expectedApiVersion: Int,
    config: Map<String, Map<String, Any?>>,
    configResolver: RulePackConfigResolver,
    overlay: Map<String, Map<String, Any?>>,
    state: DiscoveryState,
    report: (RulePackLoadFailure) -> Unit,
): Pair<List<LoadedRulePack>, Map<String, Map<String, Any?>>> {
    val discovered = mutableListOf<DiscoveredRulePack>()
    for (name in providerNames(classLoader, state, report)) {
        discoverProvider(classLoader, expectedApiVersion, name, state, report)?.let { discovered += it }
    }
    val configIssues = mutableListOf<RulePackConfigIssue>()
    val resolvedConfig =
        try {
            configResolver.resolve(config, state.seenPackIds, configIssues)
        } catch (error: Throwable) {
            if (error is VirtualMachineError) throw error
            report(
                RulePackLoadFailure(
                    RulePackLoadFailureKind.INVALID_PACK_CONFIG,
                    emptySet(),
                    "rulepack config resolver failed: ${error.message ?: error.javaClass.name}",
                    error,
                ),
            )
            config
        }
    for ((packId, message) in configIssues) {
        report(
            RulePackLoadFailure(
                RulePackLoadFailureKind.INVALID_PACK_CONFIG,
                setOf(packId),
                message,
            ),
        )
    }
    val loaded = mutableListOf<LoadedRulePack>()
    for ((pack, id, sourceClassName) in discovered) {
        val schema = configSchemaOf(pack)
        val configured = configureRulePack(pack, id, resolvedConfig, report) ?: continue
        val additive = configureRulePackAdditive(configured, id, overlay, report) ?: continue
        validateRulePack(additive, sourceClassName, expectedApiVersion, report, schema)?.let { loaded += it }
    }
    val packs =
        rejectDuplicatePackIds(loaded, report)
            .sortedWith(compareBy({ it.id }, { it.sourceClassName }))
    return packs to resolvedConfig
}

private data class DiscoveredRulePack(
    val pack: RulePack,
    val id: String,
    val sourceClassName: String,
)

private fun providerNames(
    classLoader: ClassLoader,
    state: DiscoveryState,
    report: (RulePackLoadFailure) -> Unit,
): List<String> {
    val names = linkedSetOf<String>()
    var resourceCount = 0
    var lineCount = 0
    var stop = false
    val resources =
        try {
            classLoader.getResources(SERVICE_RESOURCE)
        } catch (error: Throwable) {
            if (error is VirtualMachineError) throw error
            state.unidentifiedProviderFailure = true
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
                state.unidentifiedProviderFailure = true
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
                state.unidentifiedProviderFailure = true
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
            state.unidentifiedProviderFailure = true
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
                        state.unidentifiedProviderFailure = true
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
                        state.unidentifiedProviderFailure = true
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
            state.unidentifiedProviderFailure = true
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
    classLoader: ClassLoader,
    expectedApiVersion: Int,
    name: String,
    state: DiscoveryState,
    report: (RulePackLoadFailure) -> Unit,
): DiscoveredRulePack? {
    var className: String? = name
    return try {
        val type = Class.forName(name, false, classLoader)
        className = type.name
        if (!RulePack::class.java.isAssignableFrom(type)) {
            state.unidentifiedProviderFailure = true
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
            state.unidentifiedProviderFailure = true
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
                state.unidentifiedProviderFailure = true
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
            state.unidentifiedProviderFailure = true
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
            state.unidentifiedProviderFailure = true
            report(
                RulePackLoadFailure(
                    RulePackLoadFailureKind.CORRUPT_PACK,
                    emptySet(),
                    "provider '$className' is not a RulePack",
                ),
            )
            return null
        }
        val id = preflightRulePack(pack, expectedApiVersion, state, report) ?: return null
        DiscoveredRulePack(pack, id, className)
    } catch (error: ClassNotFoundException) {
        state.unidentifiedProviderFailure = true
        report(providerFailure(className, error, missingProvider = true))
        null
    } catch (error: Throwable) {
        if (error is VirtualMachineError) throw error
        state.unidentifiedProviderFailure = true
        report(providerFailure(className, error))
        null
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
