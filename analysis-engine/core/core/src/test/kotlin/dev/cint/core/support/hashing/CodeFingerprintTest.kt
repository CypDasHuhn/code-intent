package dev.cint.core.support.hashing

import kotlin.test.Test
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class CodeFingerprintTest {
    @Test
    fun codeIdentityDependsOnTheClassBody() {
        val first = CodeFingerprint.of("engine", listOf(String::class.java))
        val second = CodeFingerprint.of("engine", listOf(StringBuilder::class.java))
        val same = CodeFingerprint.of("engine", listOf(String::class.java))

        assertNotEquals(first, second)
        assertTrue(first == same)
        assertTrue(CodeFingerprint.classDigest(String::class.java).isNotBlank())
    }

    @Test
    fun packageIdentityCoversWholePackagesNotOnlyAnchorClasses() {
        val support =
            CodeFingerprint.ofPackages(
                label = "core-packages",
                prefixes = listOf("dev/cint/core/support/"),
                anchors = listOf(SourceNormalizer::class.java),
            )
        val user =
            CodeFingerprint.ofPackages(
                label = "core-packages",
                prefixes = listOf("dev/cint/core/user/"),
                anchors = listOf(SourceNormalizer::class.java),
            )
        assertNotEquals(support, user)
        assertTrue(support.isNotBlank())
    }

    @Test
    fun missingPackageFailsLoudly() {
        val error =
            runCatching {
                CodeFingerprint.ofPackages(
                    label = "missing",
                    prefixes = listOf("dev/cint/does-not-exist/"),
                    anchors = listOf(SourceNormalizer::class.java),
                )
            }.exceptionOrNull()
        assertTrue(error is IllegalStateException)
    }
}
