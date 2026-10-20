package dev.cint.test.fixtures

import java.nio.file.Files
import java.nio.file.Path

object TestFixtures {
    private const val ROOT_PROPERTY = "cint.testingGrounds"
    private const val ROOT_ENV = "CINT_TESTING_GROUNDS"

    fun rootOrNull(): Path? {
        val configured =
            System.getProperty(ROOT_PROPERTY)?.takeIf { it.isNotBlank() }
                ?: System.getenv(ROOT_ENV)?.takeIf { it.isNotBlank() }
        if (configured != null) {
            val root = Path.of(configured).toAbsolutePath().normalize()
            check(Files.isDirectory(root)) { "configured testing-grounds root does not exist: $root" }
            return root
        }
        val cwd = Path.of("").toAbsolutePath().normalize()
        return generateSequence(cwd) { it.parent }
            .map { it.resolve("testing-grounds") }
            .firstOrNull { Files.isDirectory(it) }
    }

    fun root(): Path =
        rootOrNull()
            ?: error("testing-grounds not found; set -D$ROOT_PROPERTY=<path> or $ROOT_ENV=<path>")

    fun fixture(relative: String): String = read(root(), relative)

    fun read(
        root: Path,
        relative: String,
    ): String {
        val file = root.resolve(relative)
        check(Files.isRegularFile(file)) { "missing fixture $file" }
        return Files.readString(file)
    }
}
