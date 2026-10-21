package dev.cint.core.runtime.rulepack

import dev.cint.api.*

class RulePackLoader(
    private val classLoader: ClassLoader = defaultClassLoader(),
    private val expectedApiVersion: Int = CINT_API_VERSION,
    private val onFailure: (RulePackLoadFailure) -> Unit,
) {
    fun load(
        config: Map<String, Map<String, Any?>> = emptyMap(),
        configResolver: RulePackConfigResolver = RulePackConfigResolver.NONE,
        overlay: Map<String, Map<String, Any?>> = emptyMap(),
    ): RulePackLoadResult {
        val failures = mutableListOf<RulePackLoadFailure>()

        fun report(failure: RulePackLoadFailure) {
            failures += failure
            onFailure(failure)
        }

        val state = DiscoveryState()
        val (discovered, resolvedConfig) =
            discoverRulePacks(classLoader, expectedApiVersion, config, configResolver, overlay, state, ::report)
        val loaded = settleRulePacks(discovered, ::report)
        for (packId in resolvedConfig.keys + overlay.keys) {
            if (packId in state.seenPackIds) continue
            if (state.unidentifiedProviderFailure) {
                report(
                    RulePackLoadFailure(
                        RulePackLoadFailureKind.UNVERIFIED_PACK_CONFIG,
                        setOf(packId),
                        "config for '$packId' could not be verified because discovery was incomplete",
                    ),
                )
            } else {
                report(
                    RulePackLoadFailure(
                        RulePackLoadFailureKind.UNKNOWN_PACK_CONFIG,
                        setOf(packId),
                        "config for unknown rulepack '$packId'",
                    ),
                )
            }
        }
        return RulePackLoadResult(loaded, failures.toList())
    }

    fun preflight(
        pack: RulePack,
        observe: (RulePackLoadFailure) -> Unit = {},
    ): RulePack? {
        val state = DiscoveryState()

        fun report(failure: RulePackLoadFailure) {
            onFailure(failure)
            observe(failure)
        }

        return if (preflightRulePack(pack, expectedApiVersion, state, ::report) != null) pack else null
    }

    fun configure(
        pack: RulePack,
        values: Map<String, Any?>,
        observe: (RulePackLoadFailure) -> Unit = {},
    ): RulePack? {
        val failures = mutableListOf<RulePackLoadFailure>()

        fun report(failure: RulePackLoadFailure) {
            failures += failure
            onFailure(failure)
            observe(failure)
        }

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
        return configureRulePack(pack, id, mapOf(id to values), ::report)
    }

    fun validate(packs: List<RulePack>): RulePackLoadResult {
        val failures = mutableListOf<RulePackLoadFailure>()

        fun report(failure: RulePackLoadFailure) {
            failures += failure
            onFailure(failure)
        }

        val loaded =
            packs.mapNotNull { pack ->
                validateRulePack(pack, pack.javaClass.name, expectedApiVersion, ::report)
            }
        val accepted =
            rejectDuplicatePackIds(loaded, ::report)
                .sortedWith(compareBy({ it.id }, { it.sourceClassName }))
        return RulePackLoadResult(settleRulePacks(accepted, ::report), failures.toList())
    }
}
