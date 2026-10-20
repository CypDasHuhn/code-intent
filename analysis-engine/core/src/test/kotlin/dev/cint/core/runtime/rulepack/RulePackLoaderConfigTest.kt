package dev.cint.core.runtime.rulepack

import dev.cint.core.rulepack.FixtureRawRuleListPack
import dev.cint.core.runtime.scheduler.RuleScheduler
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RulePackLoaderConfigTest {
    private val good = FixtureGoodPack::class.java.name

    @Test
    fun packDefaultsApplyWithoutConfig() {
        FixtureConfigurablePack.reset()

        loadWithProviders(good, FixtureConfigurablePack::class.java.name) { result ->
            assertEquals(listOf("fixture-configurable", "fixture-good"), result.loadedPackIds)
            assertTrue(result.failures.isEmpty())
            assertNull(FixtureConfigurablePack.lastCalls)
        }
    }

    @Test
    fun packConfigOverridesValuesByPackId() {
        FixtureConfigurablePack.reset()

        loadWithProviders(
            good,
            FixtureConfigurablePack::class.java.name,
            config = mapOf("fixture-configurable" to mapOf("calls" to listOf("audit", "trace"))),
        ) { result ->
            assertEquals(listOf("fixture-configurable", "fixture-good"), result.loadedPackIds)
            assertTrue(result.failures.isEmpty())
            assertEquals(setOf("audit", "trace"), FixtureConfigurablePack.lastCalls)
        }
    }

    @Test
    fun unknownPackConfigIsReported() {
        loadWithProviders(
            good,
            config = mapOf("missing-pack" to mapOf("calls" to listOf("audit"))),
        ) { result ->
            assertEquals(listOf("fixture-good"), result.loadedPackIds)
            val failure = result.failures.single()
            assertEquals(RulePackLoadFailureKind.UNKNOWN_PACK_CONFIG, failure.kind)
            assertEquals(setOf("missing-pack"), failure.packIds)
            assertTrue(failure.message.contains("missing-pack"))
        }
    }

    @Test
    fun unknownPackConfigKeyIsReportedAndThePackIsSkipped() {
        loadWithProviders(
            good,
            FixtureConfigurablePack::class.java.name,
            config = mapOf("fixture-configurable" to mapOf("unknown" to true)),
        ) { result ->
            assertEquals(listOf("fixture-good"), result.loadedPackIds)
            val failure = result.failures.single()
            assertEquals(RulePackLoadFailureKind.INVALID_PACK_CONFIG, failure.kind)
            assertEquals(setOf("fixture-configurable"), failure.packIds)
            assertTrue(failure.message.contains("fixture-configurable.unknown"))
        }
    }

    @Test
    fun emptyConfigSectionDoesNotDisableNonConfigurablePack() {
        loadWithProviders(
            good,
            config = mapOf("fixture-good" to emptyMap()),
        ) { result ->
            assertEquals(listOf("fixture-good"), result.loadedPackIds)
            assertTrue(result.failures.isEmpty())
        }
    }

    @Test
    fun nonEmptyConfigForNonConfigurablePackIsReported() {
        loadWithProviders(
            good,
            config = mapOf("fixture-good" to mapOf("unknown" to true)),
        ) { result ->
            assertEquals(emptyList(), result.loadedPackIds)
            val failure = result.failures.single()
            assertEquals(RulePackLoadFailureKind.INVALID_PACK_CONFIG, failure.kind)
            assertEquals(setOf("fixture-good"), failure.packIds)
        }
    }

    @Test
    fun olderApiVersionsAreRejected() {
        loadWithProviders(good, FixtureOlderVersionPack::class.java.name) { result ->
            assertEquals(listOf("fixture-good"), result.loadedPackIds)
            val failure = result.failures.single()
            assertEquals(RulePackLoadFailureKind.INCOMPATIBLE_API_VERSION, failure.kind)
            assertTrue(failure.message.contains("api version"))
        }
    }

    @Test
    fun apiVersionGateRunsBeforePackConfiguration() {
        FixtureConfigurableMismatchPack.reset()

        loadWithProviders(
            good,
            FixtureConfigurableMismatchPack::class.java.name,
            config = mapOf("zzz-configurable-mismatch" to mapOf("calls" to listOf("audit"))),
        ) { result ->
            assertEquals(listOf("fixture-good"), result.loadedPackIds)
            assertEquals(0, FixtureConfigurableMismatchPack.configurations)
            val failure = result.failures.single()
            assertEquals(RulePackLoadFailureKind.INCOMPATIBLE_API_VERSION, failure.kind)
            assertEquals(setOf("zzz-configurable-mismatch"), failure.packIds)
        }
    }

    @Test
    fun configuredConstructorFailureIsNotReportedAsUnknownPack() {
        loadWithProviders(
            good,
            FixtureThrowingConstructorPack::class.java.name,
            config = mapOf("zzz-throwing-constructor" to mapOf("calls" to listOf("audit"))),
        ) { result ->
            assertEquals(listOf("fixture-good"), result.loadedPackIds)
            assertTrue(result.failures.any { it.kind == RulePackLoadFailureKind.CORRUPT_PACK })
            assertTrue(result.failures.none { it.kind == RulePackLoadFailureKind.UNKNOWN_PACK_CONFIG })
            val unverified = result.failures.single { it.kind == RulePackLoadFailureKind.UNVERIFIED_PACK_CONFIG }
            assertEquals(setOf("zzz-throwing-constructor"), unverified.packIds)
        }
    }

    @Test
    fun trulyUnknownConfigIsReportedWhenAProviderIdCannotBeRead() {
        loadWithProviders(
            good,
            FixtureThrowingConstructorPack::class.java.name,
            config = mapOf("missing-pack" to mapOf("calls" to listOf("audit"))),
        ) { result ->
            assertEquals(listOf("fixture-good"), result.loadedPackIds)
            assertTrue(result.failures.any { it.kind == RulePackLoadFailureKind.CORRUPT_PACK })
            val missing = result.failures.single { "missing-pack" in it.packIds }
            assertEquals(RulePackLoadFailureKind.UNVERIFIED_PACK_CONFIG, missing.kind)
            assertTrue(missing.message.contains("missing-pack"))
        }
    }

    @Test
    fun trulyUnknownConfigIsReportedAlongsideAKnownIdCorruptPack() {
        loadWithProviders(
            good,
            FixtureCorruptPack::class.java.name,
            config = mapOf("missing-pack" to mapOf("calls" to listOf("audit"))),
        ) { result ->
            assertEquals(listOf("fixture-good"), result.loadedPackIds)
            assertTrue(result.failures.any { it.kind == RulePackLoadFailureKind.CORRUPT_PACK })
            val missing = result.failures.single { "missing-pack" in it.packIds }
            assertEquals(RulePackLoadFailureKind.UNKNOWN_PACK_CONFIG, missing.kind)
        }
    }

    @Test
    fun configuredAnnotatedMismatchIsNotReportedAsUnknownPack() {
        FixtureAnnotatedMismatchPack.reset()

        loadWithProviders(
            good,
            FixtureAnnotatedMismatchPack::class.java.name,
            config = mapOf("zzz-annotated-mismatch" to mapOf("calls" to listOf("audit"))),
        ) { result ->
            assertEquals(listOf("fixture-good"), result.loadedPackIds)
            assertEquals(0, FixtureAnnotatedMismatchPack.constructions)
            assertTrue(result.failures.any { it.kind == RulePackLoadFailureKind.INCOMPATIBLE_API_VERSION })
            assertTrue(result.failures.none { it.kind == RulePackLoadFailureKind.UNKNOWN_PACK_CONFIG })
        }
    }
}

class RulePackLoaderValidationTest {
    private val good = FixtureGoodPack::class.java.name

    @Test
    fun corruptRuleMetadataAndLanguagesAreSkipped() {
        loadWithProviders(
            good,
            FixtureThrowingLanguagesPack::class.java.name,
            FixtureThrowingRuleMetadataPack::class.java.name,
            FixtureUnnamespacedRulePack::class.java.name,
            FixtureForeignNamespacePack::class.java.name,
        ) { result ->
            assertEquals(listOf("fixture-good"), result.loadedPackIds)
            val corrupt = result.failures.filter { it.kind == RulePackLoadFailureKind.CORRUPT_PACK }
            assertEquals(
                setOf(
                    "zzz-throwing-languages",
                    "zzz-throwing-rule",
                    "zzz-unnamespaced",
                    "zzz-foreign",
                ),
                corrupt.flatMap { it.packIds }.toSet(),
            )
            assertEquals(
                listOf("fixture-good:logging"),
                RuleScheduler()
                    .plan(result.packs)
                    .strata
                    .flatten()
                    .map { it.id },
            )
        }
    }

    @Test
    fun duplicatePackIdsAreRejectedTogether() {
        loadWithProviders(
            good,
            FixtureDuplicatePackIdOne::class.java.name,
            FixtureDuplicatePackIdTwo::class.java.name,
        ) { result ->
            assertEquals(listOf("fixture-good"), result.loadedPackIds)
            val failure = result.failures.single { it.kind == RulePackLoadFailureKind.DUPLICATE_PACK_ID }
            assertEquals(setOf("duplicate-pack"), failure.packIds)
            assertTrue(failure.message.contains("duplicate-pack"))
            assertTrue(failure.message.contains(FixtureDuplicatePackIdOne::class.java.name))
            assertTrue(failure.message.contains(FixtureDuplicatePackIdTwo::class.java.name))
        }
    }

    @Test
    fun annotatedApiMismatchIsRejectedBeforeConstruction() {
        FixtureAnnotatedMismatchPack.reset()

        loadWithProviders(good, FixtureAnnotatedMismatchPack::class.java.name) { result ->
            assertEquals(listOf("fixture-good"), result.loadedPackIds)
            val failure = result.failures.single { it.kind == RulePackLoadFailureKind.INCOMPATIBLE_API_VERSION }
            assertTrue(failure.message.contains(FixtureAnnotatedMismatchPack::class.java.name))
            assertEquals(0, FixtureAnnotatedMismatchPack.constructions)
        }
    }

    @Test
    fun rawRuleListEntryIsCorrupt() {
        loadWithProviders(good, FixtureRawRuleListPack::class.java.name) { result ->
            assertEquals(listOf("fixture-good"), result.loadedPackIds)
            val failure = result.failures.single()
            assertEquals(RulePackLoadFailureKind.CORRUPT_PACK, failure.kind)
            assertEquals(setOf("fixture-raw-rule-list"), failure.packIds)
        }
    }
}
