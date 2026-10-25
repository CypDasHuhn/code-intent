package dev.cint.contracts

import dev.cint.contracts.data.Span
import dev.cint.contracts.knowledge.FunctionSelector
import dev.cint.contracts.knowledge.PatternSelector
import dev.cint.contracts.knowledge.SpanSelector
import dev.cint.contracts.util.ContentHash
import dev.cint.contracts.util.NodeHash
import dev.cint.contracts.util.SelectorIdentity
import dev.cint.contracts.util.SelectorKind
import dev.cint.contracts.util.computeSelectorHash
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class IdentityTest {
    private val emptySha256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"

    @Test
    fun testContentHashMatchesSha256Vectors() {
        assertEquals(emptySha256, ContentHash.compute("").value)
        assertEquals(ContentHash.EMPTY, ContentHash.compute(ByteArray(0)))
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            ContentHash.compute("abc").value,
        )
        assertEquals(
            "d7a8fbb307d7809469ca9abcb0082e4f8d5651e46d3cdb762d02d0bf37c9e592",
            ContentHash.compute("The quick brown fox jumps over the lazy dog").value,
        )
    }

    @Test
    fun testContentHashUsesUtf8() {
        assertEquals(
            "4a99557e4033c3539de2eb65472017cad5f9557f7a0625a09f1c3f6e2ba69c4c",
            ContentHash.compute("\u00e9").value,
        )
        assertEquals(
            "f0443a342c5ef54783a111b51ba56c938e474c32324d90c3a60c9c8e3a37e2d9",
            ContentHash.compute("\uD83D\uDE00").value,
        )
        assertNotEquals(ContentHash.compute("\u0100"), ContentHash.compute("\u0000"))
        assertEquals(ContentHash.compute("text"), ContentHash.compute("text".encodeToByteArray()))
    }

    @Test
    fun testNodeHashMatchesSha256AndEmptyVector() {
        assertEquals(emptySha256, NodeHash.compute("").value)
        assertEquals(NodeHash.EMPTY, NodeHash.compute(ByteArray(0)))
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            NodeHash.compute("abc").value,
        )
    }

    @Test
    fun testNodeHashIsDeterministicAndFramed() {
        val content = ContentHash.compute("content")
        val childA = NodeHash.compute("a")
        val childB = NodeHash.compute("b")
        val childAb = NodeHash.compute("ab")
        val first = NodeHash.compute("call", listOf(childA, childB), content)
        val second = NodeHash.compute("call", listOf(childA, childB), content)
        val framed = NodeHash.compute("call", listOf(childAb), content)

        assertEquals(first, second)
        assertNotEquals(first, framed)
        assertNotEquals(first, NodeHash.compute("other", listOf(childA, childB), content))
        assertEquals(64, first.value.length)
    }

    @Test
    fun testSelectorHashIsStable() {
        val contentHash = ContentHash.compute("content")
        val selector = FunctionSelector(qualifiedName = "a")

        assertEquals(
            computeSelectorHash(selector, contentHash),
            computeSelectorHash(FunctionSelector(qualifiedName = "a"), contentHash),
        )
        assertNotEquals(
            computeSelectorHash(selector, contentHash),
            computeSelectorHash(FunctionSelector(qualifiedName = "b"), contentHash),
        )
    }

    @Test
    fun testSelectorHashCoversEveryFieldWithoutDelimiterCollisions() {
        val contentHash = ContentHash.compute("content")
        val selectors =
            listOf(
                FunctionSelector(qualifiedName = "a:b"),
                FunctionSelector(qualifiedName = "a", receiverType = "b"),
                FunctionSelector(qualifiedName = "a", importPath = "b"),
                FunctionSelector(qualifiedName = "a", declaringType = "b"),
                FunctionSelector(qualifiedName = "a", signature = "b"),
                FunctionSelector(qualifiedName = "a", structuralPattern = "b"),
                FunctionSelector(importPath = "a:b"),
                PatternSelector(pattern = "a:b"),
                PatternSelector(pattern = "a", path = "b"),
            )
        val hashes = selectors.map { computeSelectorHash(it, contentHash) }
        assertEquals(hashes.size, hashes.toSet().size)
    }

    @Test
    fun testSpanSelectorHashUsesLineAndColumn() {
        val contentHash = ContentHash.compute("content")
        val selector = SpanSelector(path = "src/A.kt", span = Span.ZERO, contentHash = contentHash.value)
        val samePosition =
            SpanSelector(
                path = "src/A.kt",
                span = Span(0, 0, 0, 0),
                contentHash = contentHash.value,
            )
        val nextColumn =
            SpanSelector(
                path = "src/A.kt",
                span = Span(0, 1, 0, 1),
                contentHash = contentHash.value,
            )
        val nextLine =
            SpanSelector(
                path = "src/A.kt",
                span = Span(1, 0, 1, 0),
                contentHash = contentHash.value,
            )

        assertEquals(
            computeSelectorHash(selector, contentHash),
            computeSelectorHash(samePosition, contentHash),
        )
        assertNotEquals(
            computeSelectorHash(selector, contentHash),
            computeSelectorHash(nextColumn, contentHash),
        )
        assertNotEquals(
            computeSelectorHash(selector, contentHash),
            computeSelectorHash(nextLine, contentHash),
        )
    }

    @Test
    fun testSelectorIdentityHashMatchesSelectorHash() {
        val contentHash = ContentHash.compute("content")
        val selector = FunctionSelector(qualifiedName = "a")
        val identity = SelectorIdentity(SelectorKind.FUNCTION, selector.identityKey, contentHash)
        assertEquals(
            computeSelectorHash(selector, contentHash),
            computeSelectorHash(identity),
        )
    }
}
