package dev.cint.v2.languages

import dev.cint.v2.syntax.treesitter.TreeSitterGrammars
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class TreeSitterLanguageRegistryTest {
    @Test
    fun resolvesIdsAliasesAndExtensions() {
        assertEquals("kotlin", TreeSitterLanguageRegistry.forLanguage("KT")?.id)
        assertEquals("typescript", TreeSitterLanguageRegistry.forLanguage("tsx")?.id)
        assertEquals("csharp", TreeSitterLanguageRegistry.forLanguage("c#")?.id)
        assertEquals("javascript", TreeSitterLanguageRegistry.forExtension("mjs")?.id)
        assertEquals("kotlin", TreeSitterLanguageRegistry.forPath("src/Main.kts")?.id)
        assertNull(TreeSitterLanguageRegistry.forLanguage("brainfuck"))
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
