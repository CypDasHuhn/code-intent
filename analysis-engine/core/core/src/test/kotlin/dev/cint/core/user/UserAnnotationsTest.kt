package dev.cint.core.user

import dev.cint.api.FunctionFact
import dev.cint.api.Span
import dev.cint.api.SyntaxNode
import dev.cint.core.facts.model.SourceFile
import dev.cint.core.support.hashing.contentHash
import dev.cint.core.syntax.ProfileSyntaxModel
import dev.cint.core.syntax.SyntaxModel
import dev.cint.core.syntax.SyntaxProfile
import dev.cint.core.syntax.SyntaxTree
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UserAnnotationsTest {
    private val text = "fun make(): String = \"x\""

    @Test
    fun persistenceRoundTripKeepsUnknownIntentsAndMetadata() {
        val annotation =
            UserAnnotation(
                path = "src/Sample.kt",
                span = Span(0, 0, 0, text.length),
                intents = setOf("PURE", "TEAM_CUSTOM_INTENT"),
                symbol = "make",
                contentHash = "abc123",
                note = "generated helper",
                createdAt = "2026-10-20T10:00:00Z",
            )

        val parsed = UserAnnotationsJson.parse(UserAnnotationsJson.encode(listOf(annotation)))

        assertEquals(listOf(annotation), parsed)
    }

    @Test
    fun staleAnchorDoesNotProduceAContribution() {
        val annotation =
            UserAnnotation(
                path = "Sample.kt",
                span = Span(10, 0, 10, 4),
                intents = setOf("PURE"),
                symbol = "removedFunction",
                contentHash = "not-the-current-content",
            )

        val result = resolve(listOf(annotation))

        assertTrue(result.isEmpty())
    }

    @Test
    fun staleSymbolDoesNotRebindWithoutMatchingContent() {
        val annotation =
            UserAnnotation(
                path = "Sample.kt",
                span = Span(0, 0, 0, 4),
                intents = setOf("PURE"),
                symbol = "make",
                contentHash = contentHash("an older make declaration"),
            )

        val result = resolve(listOf(annotation))

        assertTrue(result.isEmpty())
    }

    @Test
    fun clientSymbolAnchorIsVerifiedByItsRangeText() {
        val annotation =
            UserAnnotation(
                path = "Sample.kt",
                span = Span(0, 4, 0, 8),
                intents = setOf("PURE"),
                symbol = "make",
                contentHash = contentHash("make"),
            )

        val contribution = resolve(listOf(annotation)).single()

        assertEquals(0, contribution.startByte)
        assertEquals(text.length, contribution.endByte)
    }

    @Test
    fun clientRangeAnchorUsesTheVerifiedRangeInsteadOfTheContainingNode() {
        val annotation =
            UserAnnotation(
                path = "Sample.kt",
                span = Span(0, 4, 0, 8),
                intents = setOf("TEAM_CUSTOM_INTENT"),
                contentHash = contentHash("make"),
            )

        val contribution = resolve(listOf(annotation)).single()

        assertEquals(4, contribution.startByte)
        assertEquals(8, contribution.endByte)
    }

    @Test
    fun clientRangeAnchorWithAHashMissIsStale() {
        val annotation =
            UserAnnotation(
                path = "Sample.kt",
                span = Span(0, 4, 0, 8),
                intents = setOf("TEAM_CUSTOM_INTENT"),
                contentHash = contentHash("an older selection"),
            )

        assertTrue(resolve(listOf(annotation)).isEmpty())
    }

    @Test
    fun staleSymbolDoesNotRebindToANonDeclaration() {
        val annotation =
            UserAnnotation(
                path = "Sample.kt",
                span = Span(1, 4, 1, 8),
                intents = setOf("PURE"),
                symbol = "make",
                contentHash = contentHash("make"),
            )
        val sourceText = "fun keep() = 1\nval ref = make\n"
        val tree = nonDeclarationTree(sourceText)

        val result = UserAnnotationResolver.resolve(listOf(annotation), tree.source, tree, emptyModel, null)

        assertTrue(result.isEmpty())
    }

    @Test
    fun untaggedIsNotAPublishableUserIntent() {
        val annotation =
            UserAnnotation(
                path = "Sample.kt",
                span = Span(0, 4, 0, 8),
                intents = setOf("UNTAGGED", "TEAM_CUSTOM_INTENT"),
                symbol = "make",
                contentHash = contentHash("make"),
            )

        val contribution = resolve(listOf(annotation)).single()

        assertEquals(setOf("TEAM_CUSTOM_INTENT"), contribution.intents)
    }

    @Test
    fun resolverUsesUtf8ByteColumns() {
        val sourceText = "πfun make(): String = \"x\""
        val annotation =
            UserAnnotation(
                path = "Sample.kt",
                span = Span(0, 6, 0, 10),
                intents = setOf("TEAM_CUSTOM_INTENT"),
                symbol = "make",
                contentHash = contentHash("make"),
            )
        val tree = utf8Tree(sourceText)

        val contribution = UserAnnotationResolver.resolve(listOf(annotation), tree.source, tree, fakeSyntax(tree), null).single()

        assertEquals(2, contribution.startByte)
        assertEquals(sourceText.toByteArray(Charsets.UTF_8).size, contribution.endByte)
    }

    @Test
    fun rangeFallbackKeepsTheStoredSelection() {
        val annotation =
            UserAnnotation(
                path = "Sample.kt",
                span = Span(0, 4, 0, 8),
                intents = setOf("TEAM_CUSTOM_INTENT"),
            )

        val contribution = resolve(listOf(annotation)).single()

        assertEquals("TEAM_CUSTOM_INTENT", contribution.intents.single())
        assertEquals(4, contribution.startByte)
        assertEquals(8, contribution.endByte)
        assertEquals(USER_CONTRIBUTOR_ID, contribution.rule)
        assertEquals(USER_SOURCE, contribution.source)
    }

    private fun resolve(annotations: List<UserAnnotation>): List<dev.cint.api.Contribution> {
        val tree = fakeTree(text)
        return UserAnnotationResolver.resolve(annotations, tree.source, tree, fakeSyntax(tree), null)
    }

    private fun fakeSyntax(tree: SyntaxTree): SyntaxModel {
        val function = fakeFunction(tree)
        return object : SyntaxModel by emptyModel {
            override fun functions(tree: SyntaxTree): Sequence<FunctionFact> = sequenceOf(function)
        }
    }

    private fun fakeFunction(tree: SyntaxTree): FunctionFact {
        val node = tree.root.children.single()
        return FunctionFact(node, "make", node, null)
    }

    private fun nonDeclarationTree(text: String): SyntaxTree {
        val rootNode = Node("source_file", 0, text.toByteArray(Charsets.UTF_8).size, text)
        val start = text.substring(0, text.indexOf("make")).toByteArray(Charsets.UTF_8).size
        val reference = Node("identifier", start, start + "make".length, "make", rootNode)
        rootNode.children = listOf(reference)
        return object : SyntaxTree {
            override val source: SourceFile = SourceFile("Sample.kt", text)
            override val root: SyntaxNode = rootNode
        }
    }

    private fun utf8Tree(text: String): SyntaxTree {
        val rootNode = Node("source_file", 0, text.toByteArray(Charsets.UTF_8).size, text)
        val function = Node("function_declaration", 2, text.toByteArray(Charsets.UTF_8).size, text.substring(1), rootNode)
        rootNode.children = listOf(function)
        return object : SyntaxTree {
            override val source: SourceFile = SourceFile("Sample.kt", text)
            override val root: SyntaxNode = rootNode
        }
    }

    private fun fakeTree(text: String): SyntaxTree {
        val rootNode = Node("source_file", 0, text.length, text)
        val function = Node("function_declaration", 0, text.length, text, rootNode)
        rootNode.children = listOf(function)
        return object : SyntaxTree {
            override val source: SourceFile = SourceFile("Sample.kt", text)
            override val root: SyntaxNode = rootNode
        }
    }

    private class Node(
        override val kind: String,
        override val startByte: Int,
        override val endByte: Int,
        override val text: String,
        override val parent: SyntaxNode? = null,
    ) : SyntaxNode {
        override var children: List<SyntaxNode> = emptyList()
        override val startLine: Int get() = 0
        override val startCol: Int get() = startByte
        override val endLine: Int get() = 0
        override val endCol: Int get() = endByte
    }

    private companion object {
        val emptyProfile =
            SyntaxProfile(
                language = "kotlin",
                groups = emptyMap(),
                tokens = emptyMap(),
                keywords = emptyMap(),
                operators = emptyMap(),
                fields = emptyMap(),
            )
        val emptyModel = ProfileSyntaxModel(emptyProfile)
    }
}
