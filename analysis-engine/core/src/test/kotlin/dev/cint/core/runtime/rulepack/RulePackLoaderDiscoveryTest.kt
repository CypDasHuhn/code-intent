package dev.cint.core.runtime.rulepack

import dev.cint.core.runtime.scheduler.RuleScheduler
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class RulePackLoaderDiscoveryTest {
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
    fun validProvidersBeforeABadEntryAreConstructedOnce() {
        FixtureCountingConstructorPack.reset()

        loadWithProviders(
            FixtureCountingConstructorPack::class.java.name,
            "dev.cint.core.runtime.rulepack.DoesNotExist",
            good,
        ) { result ->
            assertEquals(listOf("fixture-counting", "fixture-good"), result.loadedPackIds)
            assertEquals(1, FixtureCountingConstructorPack.constructions)
            assertTrue(result.failures.any { it.kind == RulePackLoadFailureKind.MISSING_PROVIDER })
        }
    }

    @Test
    fun providerClassLoadingLinkageErrorsAreReportedAndSkipped() {
        val failedName = "dev.cint.core.runtime.rulepack.ClassThatFailsToLoad"
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
        val failedNames = (1..80).map { "dev.cint.core.runtime.rulepack.ClassThatFailsToLoad$it" }.toSet()

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

    @Test
    fun malformedServiceEntriesAreSkippedAndLaterPacksLoad() {
        loadWithProviders(
            "dev.cint.core.runtime.rulepack.Bad..Name",
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
                    java.nio.file.Path
                        .of("/cint-missing-service-${System.nanoTime()}")
                        .toUri()
                        .toURL()
                val valid =
                    urls
                        .single()
                        .toURI()
                        .resolve(SERVICE_RESOURCE)
                        .toURL()
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
                val valid =
                    urls
                        .single()
                        .toURI()
                        .resolve(SERVICE_RESOURCE)
                        .toURL()
                ServiceResourcesClassLoader(missing + valid, parent)
            },
        ) { result ->
            assertEquals(listOf("fixture-good"), result.loadedPackIds)
            assertEquals(80, result.failures.count { it.kind == RulePackLoadFailureKind.CORRUPT_PACK })
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

class RulePackLoaderLimitsTest {
    private val good = FixtureGoodPack::class.java.name

    @org.junit.Test(timeout = 5_000)
    fun infiniteResourceEnumerationTerminates() {
        loadWithProviders(
            good,
            loaderFactory = { urls, parent ->
                val resource =
                    urls
                        .single()
                        .toURI()
                        .resolve(SERVICE_RESOURCE)
                        .toURL()
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
        val lines =
            buildList {
                add(good)
                addAll(List(4_094) { "" })
                add("# boundary")
            }

        loadWithProviders(*lines.toTypedArray()) { result ->
            assertEquals(listOf("fixture-good"), result.loadedPackIds)
            assertTrue(result.failures.isEmpty())
        }
    }

    @Test
    fun serviceLineLimitRejects4097Lines() {
        val lines =
            buildList {
                add(good)
                addAll(List(4_095) { "" })
                add("# boundary")
            }

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
        val firstResourceLines =
            buildList {
                add(good)
                addAll(List(4_095) { "" })
                add("# boundary")
            }

        loadWithProviders(
            *firstResourceLines.toTypedArray(),
            loaderFactory = { urls, parent ->
                val directory =
                    java.nio.file.Path
                        .of(urls.single().toURI())
                val second = directory.resolve("second-service")
                Files.writeString(second, FixtureCountingConstructorPack::class.java.name)
                val first =
                    urls
                        .single()
                        .toURI()
                        .resolve(SERVICE_RESOURCE)
                        .toURL()
                ServiceResourcesClassLoader(listOf(first, second.toUri().toURL()), parent)
            },
        ) { result ->
            assertEquals(listOf("fixture-good"), result.loadedPackIds)
            val failure = result.failures.single()
            assertEquals(RulePackLoadFailureKind.DISCOVERY_LIMIT_EXCEEDED, failure.kind)
            assertEquals(0, FixtureCountingConstructorPack.constructions)
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
}
