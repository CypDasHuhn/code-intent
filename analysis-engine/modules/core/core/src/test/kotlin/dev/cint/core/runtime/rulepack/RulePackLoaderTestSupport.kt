package dev.cint.core.runtime.rulepack

import dev.cint.core.runtime.scheduler.SchedulerError
import java.io.IOException
import java.net.URL
import java.net.URLClassLoader
import java.nio.file.Files
import java.util.Collections
import java.util.Enumeration

internal const val SERVICE_RESOURCE = "META-INF/services/dev.cint.api.RulePack"

internal open class IsolatedServiceClassLoader(
    urls: Array<URL>,
    parent: ClassLoader,
) : URLClassLoader(urls, parent) {
    override fun getResources(name: String): Enumeration<URL> =
        if (name == SERVICE_RESOURCE) findResources(name) else super.getResources(name)
}

internal class ClassLoadFailureClassLoader(
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

internal class ServiceResourcesClassLoader(
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

internal class InfiniteResourceClassLoader(
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

internal class BrokenResourceClassLoader(
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

internal data class SchedulerScenario(
    val providers: List<String>,
    val skippedPackIds: Set<String>,
    val kind: RulePackLoadFailureKind,
    val error: SchedulerError,
)

internal fun loadWithProviders(
    vararg providers: String,
    config: Map<String, Map<String, Any?>> = emptyMap(),
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
            block(RulePackLoader(classLoader, onFailure = {}).load(config))
        }
    } finally {
        directory.toFile().deleteRecursively()
    }
}
