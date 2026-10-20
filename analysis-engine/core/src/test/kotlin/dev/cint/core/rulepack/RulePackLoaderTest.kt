package dev.cint.core.rulepack

import dev.cint.api.RulePack
import dev.cint.core.scheduler.RuleScheduler
import dev.cint.core.scheduler.SchedulerError
import java.io.IOException
import java.net.URL
import java.net.URLClassLoader
import java.nio.file.Files
import java.util.Collections
import java.util.Enumeration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

private const val SERVICE_RESOURCE = "META-INF/services/dev.cint.api.RulePack"

private open class IsolatedServiceClassLoader(
    urls: Array<URL>,
    parent: ClassLoader,
) : URLClassLoader(urls, parent) {
    override fun getResources(name: String): Enumeration<URL> =
        if (name == SERVICE_RESOURCE) findResources(name) else super.getResources(name)
}

private class ClassLoadFailureClassLoader(
    urls: Array<URL>,
    parent: ClassLoader,
    private val failedNames: Set<String>,
    private val failure: () -> Throwable,
) : IsolatedServiceClassLoader(urls, parent) {
    override fun loadClass(
        name: String,
        resolve: Boolean,
    ): Class<*> {
        if (name in failedNames) throw failure()
        return super.loadClass(name, resolve)
    }
}

private class ServiceResourcesClassLoader(
    private val resources: List<URL>,
    parent: ClassLoader,
) : URLClassLoader(emptyArray<URL>(), parent) {
    override fun getResources(name: String): Enumeration<URL> =
        if (name == SERVICE_RESOURCE) {
            Collections.enumeration(resources)
        } else {
            super.getResources(name)
        }
}

private class InfiniteResourceClassLoader(
    private val resource: URL,
    parent: ClassLoader,
) : URLClassLoader(emptyArray<URL>(), parent) {
    override fun getResources(name: String): Enumeration<URL> =
        if (name == SERVICE_RESOURCE) {
            object : Enumeration<URL> {
                override fun hasMoreElements(): Boolean = true

                override fun nextElement(): URL = resource
            }
        } else {
            super.getResources(name)
        }
}

private class BrokenResourceClassLoader(
    parent: ClassLoader,
) : URLClassLoader(emptyArray<URL>(), parent) {
    var lookups: Int = 0
        private set

    override fun getResources(name: String): Enumeration<URL> {
        if (name == SERVICE_RESOURCE) {
            lookups += 1
            throw IOException("persistent service resource failure")
        }
        return super.getResources(name)
    }
}

private data class SchedulerScenario(
    val providers: List<String>,
    val skippedPackIds: Set<String>,
    val kind: RulePackLoadFailureKind,
    val error: SchedulerError,
)

private fun loadWithProviders(
    vararg providers: String,
    loaderFactory: (Array<URL>, ClassLoader) -> URLClassLoader = { urls, parent ->
        IsolatedServiceClassLoader(urls, parent)
    },
    block: (RulePackLoadResult) -> Unit,
) {
    val directory = Files.createTempDirectory("cint-rulepack-test")
    try {
        val service = directory.resolve(SERVICE_RESOURCE)
        Files.createDirectories(service.parent)
        Files.writeString(service, providers.joinToString("\n"))
        loaderFactory(
            arrayOf(directory.toUri().toURL()),
            RulePackLoader::class.java.classLoader,
        ).use { classLoader ->
            block(RulePackLoader(classLoader, onFailure = {}).load())
        }
    } finally {
        directory.toFile().deleteRecursively()
    }
}

class RulePackLoaderTest {
    private val good = FixtureGoodPack::class.java.name

    @Test
    fun classpathPackIsDiscoveredWithoutCodeChange() {
        val result = RulePackLoader(onFailure = {}).load()

        assertEquals(listOf("fixture-good"), result.loadedPackIds)
        assertTrue(result.failures.isEmpty())
        val plan = RuleScheduler().plan(result.packs)
        assertEquals(listOf("fixture-good:logging"), plan.strata.flatten().map { it.id })
    }

    @Test
    fun missingCorruptAndMismatchedPacksAreSkippedWhileTheRestAnalyze() {
        loadWithProviders(
            good,
            FixtureCorruptPack::class.java.name,
            FixtureThrowingConstructorPack::class.java.name,
            FixtureMismatchedPack::class.java.name,
            "dev.cint.core.rulepack.DoesNotExist",
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
            assertEquals(1, RuleScheduler().plan(result.packs).strata.flatten().size)
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

        for (scenario in scenarios) {
            loadWithProviders(good, *scenario.providers.toTypedArray()) { result ->
                assertEquals(listOf("fixture-good"), result.loadedPackIds, scenario.providers.toString())
                val failures =
                    result.failures.filter {
                        it.schedulerError != null && it.schedulerError.error == scenario.error
                    }
                assertTrue(failures.isNotEmpty())
                assertTrue(failures.all { it.kind == scenario.kind })
                assertTrue(failures.all { it.schedulerError != null })
                assertEquals(scenario.skippedPackIds, failures.flatMap { it.packIds }.toSet())
                assertTrue(failures.all { scenario.skippedPackIds.all { id -> id in it.message } })
                assertEquals(
                    listOf("fixture-good:logging"),
                    RuleScheduler().plan(result.packs).strata.flatten().map { it.id },
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
            assertEquals(first.toSet(), plan.strata.flatten().map { it.id }.toSet())
            assertEquals(
                first.toSet(),
                RuleScheduler().plan(result.packs).strata.flatten().map { it.id }.toSet(),
            )
        }
    }

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
            assertEquals(listOf("fixture-good:logging"), RuleScheduler().plan(result.packs).strata.flatten().map { it.id })
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
    fun validProvidersBeforeABadEntryAreConstructedOnce() {
        FixtureCountingConstructorPack.reset()

        loadWithProviders(
            FixtureCountingConstructorPack::class.java.name,
            "dev.cint.core.rulepack.DoesNotExist",
            good,
        ) { result ->
            assertEquals(listOf("fixture-counting", "fixture-good"), result.loadedPackIds)
            assertEquals(1, FixtureCountingConstructorPack.constructions)
            assertTrue(result.failures.any { it.kind == RulePackLoadFailureKind.MISSING_PROVIDER })
        }
    }

    @Test
    fun providerClassLoadingLinkageErrorsAreReportedAndSkipped() {
        val failedName = "dev.cint.core.rulepack.ClassThatFailsToLoad"
        val failures =
            listOf<() -> Throwable>(
                { NoClassDefFoundError("missing/supertype") },
                { UnsupportedClassVersionError("class file version 99.0") },
            )

        for (failure in failures) {
            loadWithProviders(
                failedName,
                good,
                loaderFactory = { urls, parent ->
                    ClassLoadFailureClassLoader(urls, parent, setOf(failedName), failure)
                },
            ) { result ->
                assertEquals(listOf("fixture-good"), result.loadedPackIds)
                assertEquals(
                    RulePackLoadFailureKind.CORRUPT_PACK,
                    result.failures.single().kind,
                )
            }
        }
    }

    @Test
    fun manyIdenticalClassLoadingFailuresDoNotDropLaterPacks() {
        val failedNames = (1..80).map { "dev.cint.core.rulepack.ClassThatFailsToLoad$it" }.toSet()

        loadWithProviders(
            *failedNames.toTypedArray(),
            good,
            loaderFactory = { urls, parent ->
                ClassLoadFailureClassLoader(urls, parent, failedNames) {
                    NoClassDefFoundError("missing/shared-type")
                }
            },
        ) { result ->
            assertEquals(listOf("fixture-good"), result.loadedPackIds)
            assertEquals(80, result.failures.count { it.kind == RulePackLoadFailureKind.CORRUPT_PACK })
        }
    }

    @Test
    fun unicodeProviderClassNameIsDiscovered() {
        val name = FixtureÜnicodePack::class.java.name
        assertTrue(name.any { it.code > 127 })

        loadWithProviders(good, name) { result ->
            assertEquals(listOf("fixture-good", "fixture-unicode"), result.loadedPackIds)
            assertTrue(result.failures.isEmpty())
        }
    }

    @org.junit.Test(timeout = 5_000)
    fun infiniteResourceEnumerationTerminates() {
        loadWithProviders(
            good,
            loaderFactory = { urls, parent ->
                val resource = urls.single().toURI().resolve(SERVICE_RESOURCE).toURL()
                InfiniteResourceClassLoader(resource, parent)
            },
        ) { result ->
            assertEquals(listOf("fixture-good"), result.loadedPackIds)
            assertTrue(
                result.failures.any {
                    it.kind == RulePackLoadFailureKind.DISCOVERY_LIMIT_EXCEEDED
                },
            )
        }
    }

    @Test
    fun serviceLineLimitAllowsExactly4096Lines() {
        val lines = listOf(good) + List(4_094) { "" } + "# boundary"

        loadWithProviders(*lines.toTypedArray()) { result ->
            assertEquals(listOf("fixture-good"), result.loadedPackIds)
            assertTrue(result.failures.isEmpty())
        }
    }

    @Test
    fun serviceLineLimitRejects4097Lines() {
        val lines = listOf(good) + List(4_095) { "" } + "# boundary"

        loadWithProviders(*lines.toTypedArray()) { result ->
            assertEquals(listOf("fixture-good"), result.loadedPackIds)
            val failure = result.failures.single()
            assertEquals(RulePackLoadFailureKind.DISCOVERY_LIMIT_EXCEEDED, failure.kind)
            assertTrue(failure.message.contains("4096 service lines"))
        }
    }

    @Test
    fun serviceLineLimitStopsBeforeLaterResources() {
        FixtureCountingConstructorPack.reset()
        val firstResourceLines = listOf(good) + List(4_095) { "" } + "# boundary"

        loadWithProviders(
            *firstResourceLines.toTypedArray(),
            loaderFactory = { urls, parent ->
                val directory = java.nio.file.Path.of(urls.single().toURI())
                val second = directory.resolve("second-service")
                Files.writeString(second, FixtureCountingConstructorPack::class.java.name)
                val first = urls.single().toURI().resolve(SERVICE_RESOURCE).toURL()
                ServiceResourcesClassLoader(listOf(first, second.toUri().toURL()), parent)
            },
        ) { result ->
            assertEquals(listOf("fixture-good"), result.loadedPackIds)
            val failure = result.failures.single()
            assertEquals(RulePackLoadFailureKind.DISCOVERY_LIMIT_EXCEEDED, failure.kind)
            assertEquals(0, FixtureCountingConstructorPack.constructions)
        }
    }

    @Test
    fun malformedServiceEntriesAreSkippedAndLaterPacksLoad() {
        loadWithProviders(
            "dev.cint.core.rulepack.Bad..Name",
            "Illegal configuration-file syntax",
            good,
        ) { result ->
            assertEquals(listOf("fixture-good"), result.loadedPackIds)
            val illegal = result.failures.filter { it.kind == RulePackLoadFailureKind.CORRUPT_PACK }
            assertEquals(2, illegal.size)
            assertTrue(illegal.all { it.message.contains("illegal provider-class name") })
        }
    }

    @Test
    fun manyMalformedServiceLinesDoNotDropLaterPacks() {
        val malformed = (1..80).map { "not a provider class name $it" }

        loadWithProviders(*malformed.toTypedArray(), good) { result ->
            assertEquals(listOf("fixture-good"), result.loadedPackIds)
            assertEquals(80, result.failures.count { it.kind == RulePackLoadFailureKind.CORRUPT_PACK })
        }
    }

    @Test
    fun unreadableServiceResourceDoesNotAbortLaterResources() {
        loadWithProviders(
            good,
            loaderFactory = { urls, parent ->
                val missing =
                    java.nio.file.Path.of("/cint-missing-service-${System.nanoTime()}").toUri().toURL()
                val valid = urls.single().toURI().resolve(SERVICE_RESOURCE).toURL()
                ServiceResourcesClassLoader(listOf(missing, valid), parent)
            },
        ) { result ->
            assertEquals(listOf("fixture-good"), result.loadedPackIds)
            assertTrue(result.failures.any { it.kind == RulePackLoadFailureKind.CORRUPT_PACK })
        }
    }

    @Test
    fun manyUnreadableServiceResourcesDoNotDropLaterResources() {
        loadWithProviders(
            good,
            loaderFactory = { urls, parent ->
                val missing =
                    (1..80).map { index ->
                        java.nio.file.Path
                            .of("/cint-missing-service-$index-${System.nanoTime()}")
                            .toUri()
                            .toURL()
                    }
                val valid = urls.single().toURI().resolve(SERVICE_RESOURCE).toURL()
                ServiceResourcesClassLoader(missing + valid, parent)
            },
        ) { result ->
            assertEquals(listOf("fixture-good"), result.loadedPackIds)
            assertEquals(80, result.failures.count { it.kind == RulePackLoadFailureKind.CORRUPT_PACK })
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

    @org.junit.Test(timeout = 5_000)
    fun persistentServiceResourceFailureTerminatesWithDegradedResult() {
        BrokenResourceClassLoader(RulePackLoader::class.java.classLoader).use { classLoader ->
            val result = RulePackLoader(classLoader, onFailure = {}).load()

            assertTrue(result.isDegraded)
            assertTrue(result.packs.isEmpty())
            assertTrue(result.failures.isNotEmpty())
            assertTrue(result.failures.all { it.kind == RulePackLoadFailureKind.CORRUPT_PACK })
            assertTrue(classLoader.lookups in 1..16)
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

    @Test
    fun loadingUsesNoRuntimeCompilerOrClassLoaderReplacement() {
        val contextClassLoader = Thread.currentThread().contextClassLoader

        val result = RulePackLoader(onFailure = {}).load()

        assertTrue(result.packs.isNotEmpty())
        assertSame(contextClassLoader, Thread.currentThread().contextClassLoader)
        assertFailsWith<ClassNotFoundException> {
            Class.forName(
                "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
                false,
                RulePackLoader::class.java.classLoader,
            )
        }
    }
}
