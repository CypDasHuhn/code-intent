package dev.cint.v2.syntax.treesitter

import dev.cint.contracts.facts.SyntaxNode
import dev.cint.contracts.facts.SyntaxTree
import dev.cint.contracts.util.ContentHash
import dev.cint.contracts.util.ContractError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SyntaxEngineNodeHashTest {
    @Test
    fun nodeHashCoversTheWholeSubtree() {
        val engine = createSyntaxEngine()
        val first = tree("a", "b")
        val changed = tree("A", "b")

        val firstRoot = engine.nodeHash(first, "root").getOrThrow()
        val firstLeft = engine.nodeHash(first, "left").getOrThrow()
        val firstRight = engine.nodeHash(first, "right").getOrThrow()

        assertNotEquals(firstRoot, engine.nodeHash(changed, "root").getOrThrow())
        assertNotEquals(firstLeft, engine.nodeHash(changed, "left").getOrThrow())
        assertEquals(firstRight, engine.nodeHash(changed, "right").getOrThrow())
    }

    @Test
    fun missingNodeIsReported() {
        val result = createSyntaxEngine().nodeHash(tree("a", "b"), "missing")

        assertTrue(result.isFailure)
        assertIs<ContractError.NotFound>(result.errorOrNull)
    }

    private fun tree(leftText: String, rightText: String): SyntaxTree {
        val root = SyntaxNode(
            id = "root",
            kind = "file",
            startByte = 0,
            endByte = 2,
            startLine = 0,
            startCol = 0,
            endLine = 0,
            endCol = 2,
            text = "$leftText$rightText",
            parentId = null,
            childIds = listOf("left", "right"),
        )
        val left = SyntaxNode(
            id = "left",
            kind = "expression",
            startByte = 0,
            endByte = 1,
            startLine = 0,
            startCol = 0,
            endLine = 0,
            endCol = 1,
            text = leftText,
            parentId = "root",
        )
        val right = SyntaxNode(
            id = "right",
            kind = "expression",
            startByte = 1,
            endByte = 2,
            startLine = 0,
            startCol = 1,
            endLine = 0,
            endCol = 2,
            text = rightText,
            parentId = "root",
        )
        return SyntaxTree(
            rootId = "root",
            nodes = mapOf("root" to root, "left" to left, "right" to right),
            language = "c",
            version = "test",
            contentHash = ContentHash.compute("$leftText$rightText").value,
        )
    }
}
