package dev.cint.core.runtime.rulepack

import dev.cint.core.runtime.scheduler.RuleScheduler
import dev.cint.core.runtime.scheduler.SchedulerError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RulePackLoaderSchedulerTest {
    private val good = FixtureGoodPack::class.java.name

    @Test
    fun missingCorruptAndMismatchedPacksAreSkippedWhileTheRestAnalyze() {
        loadWithProviders(
            good,
            FixtureCorruptPack::class.java.name,
            FixtureThrowingConstructorPack::class.java.name,
            FixtureMismatchedPack::class.java.name,
            "dev.cint.core.runtime.rulepack.DoesNotExist",
        ) { result ->
            assertEquals(listOf("fixture-good"), result.loadedPackIds)
            assertTrue(result.isDegraded)
            assertEquals(
                setOf(
                    RulePackLoadFailureKind.CORRUPT_PACK,
                    RulePackLoadFailureKind.INCOMPATIBLE_API_VERSION,
                    RulePackLoadFailureKind.MISSING_PROVIDER,
                ),
                result.failures.map { it.kind }.toSet(),
            )
            assertEquals(2, result.failures.count { it.kind == RulePackLoadFailureKind.CORRUPT_PACK })
            val mismatch = result.failures.single { it.kind == RulePackLoadFailureKind.INCOMPATIBLE_API_VERSION }
            assertEquals(setOf("zzz-mismatched"), mismatch.packIds)
            assertTrue(mismatch.message.contains("expected"))
            assertEquals(
                1,
                RuleScheduler()
                    .plan(result.packs)
                    .strata
                    .flatten()
                    .size,
            )
        }
    }

    @Test
    fun schedulerLoadErrorsAreNamedAndOnlyParticipatingPacksAreSkipped() {
        val scenarios =
            listOf(
                SchedulerScenario(
                    listOf(
                        FixtureDuplicateOwnerPackA::class.java.name,
                        FixtureDuplicateOwnerPackB::class.java.name,
                    ),
                    setOf("aaa-duplicate-owner", "zzz-duplicate-owner"),
                    RulePackLoadFailureKind.DUPLICATE_INTENT_OWNER,
                    SchedulerError.DUPLICATE_INTENT_OWNER,
                ),
                SchedulerScenario(
                    listOf(
                        FixtureDuplicateOwnerPackB::class.java.name,
                        FixtureDuplicateOwnerPackA::class.java.name,
                    ),
                    setOf("aaa-duplicate-owner", "zzz-duplicate-owner"),
                    RulePackLoadFailureKind.DUPLICATE_INTENT_OWNER,
                    SchedulerError.DUPLICATE_INTENT_OWNER,
                ),
                SchedulerScenario(
                    listOf(FixtureDuplicateRuleIdPack::class.java.name),
                    setOf("zzz-duplicate-rule-id"),
                    RulePackLoadFailureKind.DUPLICATE_RULE_ID,
                    SchedulerError.DUPLICATE_RULE_ID,
                ),
                SchedulerScenario(
                    listOf(FixtureReservedIntentPack::class.java.name),
                    setOf("zzz-reserved"),
                    RulePackLoadFailureKind.RESERVED_INTENT_OWNER,
                    SchedulerError.RESERVED_INTENT_OWNER,
                ),
                SchedulerScenario(
                    listOf(FixtureUnknownDependencyPack::class.java.name),
                    setOf("zzz-unknown"),
                    RulePackLoadFailureKind.UNKNOWN_DEPENDENCY,
                    SchedulerError.UNKNOWN_DEPENDENCY,
                ),
                SchedulerScenario(
                    listOf(FixtureCyclePack::class.java.name),
                    setOf("zzz-cycle"),
                    RulePackLoadFailureKind.DEPENDENCY_CYCLE,
                    SchedulerError.DEPENDENCY_CYCLE,
                ),
            )

        for ((providers, skippedPackIds, kind, error) in scenarios) {
            loadWithProviders(good, *providers.toTypedArray()) { result ->
                assertEquals(listOf("fixture-good"), result.loadedPackIds, providers.toString())
                val failures =
                    result.failures.filter {
                        it.schedulerError != null && it.schedulerError.error == error
                    }
                assertTrue(failures.isNotEmpty())
                assertTrue(failures.all { it.kind == kind })
                assertTrue(failures.all { it.schedulerError != null })
                assertEquals(skippedPackIds, failures.flatMap { it.packIds }.toSet())
                assertTrue(failures.all { skippedPackIds.all { id -> id in it.message } })
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
    }

    @Test
    fun loadedSnapshotIsStableWhenSourceRulesChange() {
        loadWithProviders(good, FixtureStatefulRulesPack::class.java.name) { result ->
            assertEquals(listOf("fixture-good", "fixture-stateful"), result.loadedPackIds)

            val first = result.packs.flatMap { it.rules() }.map { it.id }
            val second = result.packs.flatMap { it.rules() }.map { it.id }
            assertEquals(first, second)
            assertEquals(
                setOf("fixture-good:logging", "fixture-stateful:logging"),
                first.toSet(),
            )

            val plan = RuleScheduler().plan(result.packs)
            assertEquals(
                first.toSet(),
                plan.strata
                    .flatten()
                    .map { it.id }
                    .toSet(),
            )
            assertEquals(
                first.toSet(),
                RuleScheduler()
                    .plan(result.packs)
                    .strata
                    .flatten()
                    .map { it.id }
                    .toSet(),
            )
        }
    }

    @Test
    fun linkageFailureIsCorruptNotMissingProvider() {
        loadWithProviders(good, FixtureLinkageFailurePack::class.java.name) { result ->
            assertEquals(listOf("fixture-good"), result.loadedPackIds)
            assertEquals(
                RulePackLoadFailureKind.CORRUPT_PACK,
                result.failures.single().kind,
            )
        }
    }
}
