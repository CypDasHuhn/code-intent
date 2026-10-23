package dev.cint.core.support.hashing

import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarFile
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile

object CodeFingerprint {
    fun of(
        label: String,
        classes: Collection<Class<*>>,
    ): String {
        val parts =
            classes
                .map { clazz -> "${clazz.name}:${classDigest(clazz)}" }
                .sorted()
        return contentHash((listOf(label) + parts).joinToString("\n"))
    }

    fun ofPackages(
        label: String,
        prefixes: Collection<String>,
        resourcePrefixes: Collection<String> = emptyList(),
        anchors: Collection<Class<*>>,
        allowEmpty: Boolean = false,
    ): String {
        val roots =
            anchors
                .map { clazz ->
                    runCatching {
                        Path.of(
                            clazz.protectionDomain.codeSource.location
                                .toURI(),
                        )
                    }.getOrElse { error ->
                        throw IllegalStateException(
                            "code fingerprint '$label' cannot read code source for ${clazz.name}",
                            error,
                        )
                    }
                }.distinct()
        check(roots.isNotEmpty()) { "code fingerprint '$label' has no code sources" }
        val entries = linkedMapOf<String, String>()
        for (root in roots) {
            if (root.isDirectory()) {
                Files.walk(root).use { paths ->
                    paths
                        .filter { it.isRegularFile() }
                        .forEach { path ->
                            val relative = root.relativize(path).toString().replace('\\', '/')
                            if (matches(relative, prefixes, resourcePrefixes)) {
                                entries[relative] = contentHash(Files.readAllBytes(path).toString(Charsets.ISO_8859_1))
                            }
                        }
                }
            } else if (root.isRegularFile()) {
                JarFile(root.toFile()).use { jar ->
                    jar.entries().asSequence().forEach { entry ->
                        if (!entry.isDirectory && matches(entry.name, prefixes, resourcePrefixes)) {
                            entries[entry.name] =
                                jar.getInputStream(entry).use(::digest)
                        }
                    }
                }
            } else {
                throw IllegalStateException("code fingerprint '$label' cannot read code source $root")
            }
        }
        if (!allowEmpty) {
            check(entries.isNotEmpty()) {
                "code fingerprint '$label' found no files for prefixes $prefixes"
            }
        }
        return contentHash(
            (listOf(label) + entries.toSortedMap().map { (path, digest) -> "$path:$digest" })
                .joinToString("\n"),
        )
    }

    fun classDigest(clazz: Class<*>): String {
        val resource = "/" + clazz.name.replace('.', '/') + ".class"
        return clazz.getResourceAsStream(resource)?.use(::digest)
            ?: error("class resource missing for ${clazz.name}")
    }

    fun resourceDigest(
        clazz: Class<*>,
        resource: String,
    ): String =
        clazz.getResourceAsStream(resource)?.use(::digest)
            ?: error("resource missing: $resource")

    private fun matches(
        path: String,
        prefixes: Collection<String>,
        resourcePrefixes: Collection<String>,
    ): Boolean =
        prefixes.any { path.endsWith(".class") && path.startsWith(it) } ||
            resourcePrefixes.any { path.startsWith(it) }

    private fun digest(stream: InputStream): String = contentHash(stream.readBytes().toString(Charsets.ISO_8859_1))
}
