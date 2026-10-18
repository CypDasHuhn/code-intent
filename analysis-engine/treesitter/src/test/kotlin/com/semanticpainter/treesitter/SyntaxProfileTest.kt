package com.semanticpainter.treesitter

import com.semanticpainter.core.syntax.SyntaxGroups
import com.semanticpainter.core.syntax.SyntaxProfiles
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
        for (language in listOf("kotlin", "javascript", "typescript")) {
            val profile = SyntaxProfiles.load(language)
            assertEquals(language, profile.language)
            for (group in required) {
                assertTrue(profile.kinds(group).isNotEmpty(), "$language missing syntax group: $group")
            }
        }
    }
}
