package dev.cint.v2.languages

import dev.cint.v2.syntax.treesitter.TreeSitterGrammars
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class TreeSitterLanguageRegistryTest {
    @Test
    fun resolvesIdsAliasesAndExtensions() {
        assertEquals("kotlin", TreeSitterLanguageRegistry.resolve("KT")?.id)
        assertEquals("typescript", TreeSitterLanguageRegistry.resolve("tsx")?.id)
        assertEquals("csharp", TreeSitterLanguageRegistry.resolve("c#")?.id)
        assertEquals("javascript", TreeSitterLanguageRegistry.forExtension("mjs")?.id)
        assertEquals("kotlin", TreeSitterLanguageRegistry.forPath("src/Main.kts")?.id)
        assertNull(TreeSitterLanguageRegistry.resolve("brainfuck"))
    }

    @Test
    fun descriptorsStayAlignedWithAdapterGrammarRegistration() {
        for (descriptor in TreeSitterLanguageRegistry.descriptors) {
            val grammar = TreeSitterGrammars.find(descriptor.id)
            assertNotNull(grammar, "missing adapter grammar for ${descriptor.id}")
            assertEquals(descriptor.grammarName, grammar.grammarName)
        }
    }
}
