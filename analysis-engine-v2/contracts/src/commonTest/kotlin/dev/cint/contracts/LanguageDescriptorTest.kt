package dev.cint.contracts

import dev.cint.contracts.facts.LanguageDescriptor
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LanguageDescriptorTest {
    private val javascript =
        LanguageDescriptor(
            id = "javascript",
            name = "JavaScript",
            extensions = listOf("js", "jsx"),
            grammarName = "tree-sitter-javascript",
            mimeTypes = listOf("text/javascript"),
        )

    @Test
    fun mimeMatchingDoesNotMatchEveryPath() {
        assertTrue(javascript.matchesPath("src/Main.JS"))
        assertTrue(javascript.matchesPath("text/javascript"))
        assertFalse(javascript.matchesPath("Main.kt"))
        assertFalse(javascript.matchesPath("src/Main.ts"))
    }
}
