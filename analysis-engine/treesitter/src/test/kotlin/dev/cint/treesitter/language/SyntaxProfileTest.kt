package dev.cint.treesitter.language

import dev.cint.core.syntax.SyntaxGroups
import dev.cint.core.syntax.SyntaxProfiles
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SyntaxProfileTest {
    @Test
    fun profilesDefineCoreGroups() {
        val required =
            listOf(
                SyntaxGroups.CALL,
                SyntaxGroups.NAVIGATION,
                SyntaxGroups.CALLEE_SUFFIX,
                SyntaxGroups.IDENTIFIER,
                SyntaxGroups.IMPORT,
                SyntaxGroups.PROPERTY,
                SyntaxGroups.FUNCTION,
                SyntaxGroups.IF,
                SyntaxGroups.LOOP,
                SyntaxGroups.STATEMENT_LIST,
                SyntaxGroups.JUMP,
                SyntaxGroups.TRY,
                SyntaxGroups.CATCH,
                SyntaxGroups.COMMENT,
                SyntaxGroups.REFERENCE,
                SyntaxGroups.EFFECT,
            )
        for (language in listOf("kotlin", "javascript", "typescript", "csharp")) {
            val profile = SyntaxProfiles.load(language)
            assertEquals(language, profile.language)
            for (group in required) {
                assertTrue(profile.kinds(group).isNotEmpty(), "$language missing syntax group: $group")
            }
        }
    }

    @Test
    fun kotlinLoopAndGuardRolesUseActualGrammarKinds() {
        val profile = SyntaxProfiles.load("kotlin")
        val loop = profile.kinds(SyntaxGroups.LOOP)
        val guard = profile.kinds(SyntaxGroups.GUARD)

        assertTrue("for_statement" in loop)
        assertTrue("for_statement" in guard)
        assertTrue("while_statement" in loop)
        assertTrue("while_expression" !in loop)
        assertTrue("while_expression" !in guard)
    }

    @Test
    fun profilesDefineSemanticRoles() {
        val required =
            listOf(
                SyntaxGroups.DECLARATION_WRAPPER,
                SyntaxGroups.ANNOTATION,
                SyntaxGroups.COMMENT,
                SyntaxGroups.PROPERTY,
                SyntaxGroups.WRITE,
                SyntaxGroups.CALL,
                SyntaxGroups.GUARD,
                SyntaxGroups.CLASS,
                SyntaxGroups.CLASS_BODY,
            )
        for (language in listOf("kotlin", "javascript", "typescript", "csharp")) {
            val profile = SyntaxProfiles.load(language)
            for (group in required) {
                assertTrue(profile.kinds(group).isNotEmpty(), "$language missing semantic role: $group")
            }
            assertTrue(profile.groups.containsKey(SyntaxGroups.MODIFIER), "$language missing semantic role: modifier")
        }
    }
}
