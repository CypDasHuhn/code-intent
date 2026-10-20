package dev.cint.integration

import dev.cint.test.fixtures.TestFixtures
import org.junit.Assume.assumeTrue
import java.nio.file.Path

internal fun assumeFixtureRoot(): Path {
    val root = TestFixtures.rootOrNull()
    assumeTrue(
        "testing-grounds not found; set -Dcint.testingGrounds=<path> or CINT_TESTING_GROUNDS=<path>",
        root != null,
    )
    return requireNotNull(root)
}

internal fun fixture(relative: String): String = TestFixtures.read(assumeFixtureRoot(), relative)
