package dev.cint.core.runtime.rulepack

import dev.cint.api.*
import dev.cint.core.runtime.scheduler.SchedulerException
import java.util.*

enum class RulePackLoadFailureKind {
    MISSING_PROVIDER,
    INCOMPATIBLE_API_VERSION,
    CORRUPT_PACK,
    DISCOVERY_LIMIT_EXCEEDED,
    DUPLICATE_PACK_ID,
    DUPLICATE_INTENT_OWNER,
    DUPLICATE_RULE_ID,
    RESERVED_INTENT_OWNER,
    RESERVED_CONTRIBUTOR_ID,
    UNKNOWN_DEPENDENCY,
    DEPENDENCY_CYCLE,
    UNKNOWN_PACK_CONFIG,
    UNVERIFIED_PACK_CONFIG,
    INVALID_PACK_CONFIG,
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
}

typealias RulePackConfigResolver = dev.cint.api.RulePackConfigResolver

internal val SERVICE_RESOURCE: String = "META-INF/services/${RulePack::class.java.name}"
internal const val MAX_SERVICE_RESOURCES = 256
internal const val MAX_SERVICE_LINES = 4096

internal fun defaultClassLoader(): ClassLoader = Thread.currentThread().contextClassLoader ?: RulePack::class.java.classLoader

internal fun <K, V> identityMap(): MutableMap<K, V> = IdentityHashMap()

internal fun <T> identitySet(): MutableSet<T> = Collections.newSetFromMap(IdentityHashMap<T, Boolean>())

internal class DiscoveryState {
    val seenPackIds: MutableSet<String> = linkedSetOf()
    var unidentifiedProviderFailure: Boolean = false
}

internal data class RuleMetadata(
    val id: String?,
    val owns: String?,
    val reads: Set<String>?,
)

internal class LoadedRulePack(
    override val id: String,
    override val languages: Set<String>,
    override val apiVersion: Int,
    private val cachedRules: List<Rule>,
    val sourceClassName: String,
) : RulePack {
    override fun rules(): List<Rule> = cachedRules
}

internal class LoadedRule(
    private val source: Rule,
    override val id: String,
    override val owns: Intent,
    override val reads: Set<Intent>,
) : Rule {
    override fun run(view: View): List<Emit> = source.run(view)
}

internal fun preflightRulePack(
    pack: RulePack,
    expectedApiVersion: Int,
    state: DiscoveryState,
    report: (RulePackLoadFailure) -> Unit,
): String? {
    val id =
        try {
            pack.id
        } catch (error: Throwable) {
            if (error is VirtualMachineError) throw error
            state.unidentifiedProviderFailure = true
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
        state.unidentifiedProviderFailure = true
        report(
            RulePackLoadFailure(
                RulePackLoadFailureKind.CORRUPT_PACK,
                emptySet(),
                "rulepack has a blank id",
            ),
        )
        return null
    }

    state.seenPackIds += id

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

    return id
}

internal fun configureRulePack(
    pack: RulePack,
    id: String,
    config: Map<String, Map<String, Any?>>,
    report: (RulePackLoadFailure) -> Unit,
): RulePack? {
    val values = config[id] ?: return pack
    if (values.isEmpty()) return pack

    if (pack !is ConfigurableRulePack) {
        report(
            RulePackLoadFailure(
                RulePackLoadFailureKind.INVALID_PACK_CONFIG,
                setOf(id),
                "rulepack '$id' does not accept config keys: ${values.keys.sorted().joinToString(", ")}",
            ),
        )
        return null
    }

    return try {
        val configured = pack.configure(values)
        if (configured.id != id) {
            report(
                RulePackLoadFailure(
                    RulePackLoadFailureKind.INVALID_PACK_CONFIG,
                    setOf(id),
                    "rulepack '$id' changed its id while applying config",
                ),
            )
            null
        } else {
            configured
        }
    } catch (error: RulePackConfigException) {
        report(
            RulePackLoadFailure(
                RulePackLoadFailureKind.INVALID_PACK_CONFIG,
                setOf(id),
                "rulepack '$id' has invalid config: ${error.message ?: error.javaClass.name}",
                error,
            ),
        )
        null
    } catch (error: Throwable) {
        if (error is VirtualMachineError) throw error
        report(
            RulePackLoadFailure(
                RulePackLoadFailureKind.INVALID_PACK_CONFIG,
                setOf(id),
                "rulepack '$id' could not apply config: ${error.message ?: error.javaClass.name}",
                error,
            ),
        )
        null
    }
}
