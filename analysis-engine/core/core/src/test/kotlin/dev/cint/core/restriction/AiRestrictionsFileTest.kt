package dev.cint.core.restriction

import dev.cint.api.Span
import dev.cint.core.user.USER_PURE_INTENT
import dev.cint.core.user.UserAnnotationsFile
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AiRestrictionsFileTest {
    @Test
    fun restrictionsPersistSeparatelyFromUserAnnotations() {
        val root = Files.createTempDirectory("cint-ai-restrictions")
        try {
            val record =
                AiRestrictionRecord(
                    id = "proposal-1",
                    path = "Facts.kt",
                    span = Span(4, 8, 4, 12),
                    contentHash = "sha256:abc",
                    remove =
                        listOf(
                            RestrictionOperation("LOGGING", 0, 4, 0.62, "not a log call"),
                        ),
                    add =
                        listOf(
                            RestrictionOperation("SEARCH", 0, 4, 0.71),
                        ),
                    symbol = "magicCommentIn",
                    unitFingerprint = "abc",
                    provider = "deepseek",
                    model = "deepseek-chat",
                    promptVersion = "deepseek-intent-v3",
                    createdAt = "2026-10-22T12:00:00Z",
                )

            AiRestrictionsFile.add(root, record)

            assertTrue(Files.isRegularFile(AiRestrictionsFile.path(root)))
            assertFalse(Files.exists(UserAnnotationsFile.path(root)))
            val loaded = AiRestrictionsFile.load(root).single()
            assertEquals(record, loaded)
            assertEquals(setOf("LOGGING"), loaded.remove.map { it.intent }.toSet())
            assertEquals(setOf("SEARCH"), loaded.add.map { it.intent }.toSet())
            assertEquals("deepseek-intent-v3", loaded.promptVersion)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun addingTheSameIdReplacesTheRecordAndRemovalDropsOnlyIt() {
        val root = Files.createTempDirectory("cint-ai-restrictions-replace")
        try {
            val first = record("one")
            val second = record("two")
            AiRestrictionsFile.add(root, first)
            AiRestrictionsFile.add(root, second)
            AiRestrictionsFile.add(root, second.copy(contentHash = "sha256:updated"))

            val loaded = AiRestrictionsFile.load(root)
            assertEquals(listOf("one", "two"), loaded.map { it.id })
            assertEquals("sha256:updated", loaded.last().contentHash)

            assertTrue(AiRestrictionsFile.remove(root, "one"))
            assertFalse(AiRestrictionsFile.remove(root, "one"))
            assertEquals(listOf("two"), AiRestrictionsFile.load(root).map { it.id })
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun negativeRemoveOffsetsRoundTripAndAddOnlyRecordsAreRejected() {
        val root = Files.createTempDirectory("cint-ai-restrictions-negative")
        try {
            val record =
                AiRestrictionRecord(
                    id = "child",
                    path = "Facts.kt",
                    span = Span(1, 8, 1, 15),
                    contentHash = "sha256:inner",
                    remove = listOf(RestrictionOperation("LOGGING", -12, 20)),
                    add = emptyList(),
                    symbol = "f",
                    unitFingerprint = "fingerprint",
                    provider = "deepseek",
                    model = "deepseek-chat",
                    promptVersion = "v3",
                )
            AiRestrictionsFile.add(root, record)
            val loaded = AiRestrictionsFile.load(root).single()
            assertEquals(-12, loaded.remove.single().startOffset)
            assertEquals(20, loaded.remove.single().endOffset)
        } finally {
            root.toFile().deleteRecursively()
        }

        val addOnly =
            AiRestrictionsJson.parse(
                """
                {"version":1,"restrictions":[{"id":"add","path":"Facts.kt","contentHash":"abc","anchor":{"startLine":0,"startCol":0,"endLine":0,"endCol":1},"remove":[],"add":[{"intent":"SEARCH","startOffset":0,"endOffset":1}]}]}
                """.trimIndent(),
            )
        assertTrue(addOnly.isEmpty())
    }

    @Test
    fun addOnlyRecordsAndUnknownFileVersionsAreRejected() {
        val addOnly =
            AiRestrictionsJson.parse(
                """
                {"version":1,"restrictions":[{"id":"add","path":"Facts.kt","contentHash":"abc","anchor":{"startLine":0,"startCol":0,"endLine":0,"endCol":1},"remove":[],"add":[{"intent":"SEARCH","startOffset":0,"endOffset":1}]}]}
                """.trimIndent(),
            )
        assertTrue(addOnly.isEmpty())

        val future =
            AiRestrictionsJson.parse(
                """
                {"version":2,"restrictions":[{"id":"future","path":"Facts.kt","contentHash":"abc","anchor":{"startLine":0,"startCol":0,"endLine":0,"endCol":1},"remove":[{"intent":"LOGGING","startOffset":0,"endOffset":1}],"add":[]}]}
                """.trimIndent(),
            )
        assertTrue(future.isEmpty())
    }

    @Test
    fun addRejectsAddOnlyRecordsBeforeWriting() {
        val root = Files.createTempDirectory("cint-ai-restrictions-add-only")
        try {
            assertFailsWith<IllegalArgumentException> {
                AiRestrictionsFile.add(root, record("add-only").copy(remove = emptyList()))
            }
            assertFalse(Files.exists(AiRestrictionsFile.path(root)))
            assertTrue(AiRestrictionsFile.load(root).isEmpty())
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun addRefusesAnUnsupportedFutureVersionFileWithoutRewritingIt() {
        val root = Files.createTempDirectory("cint-ai-restrictions-future")
        try {
            val path = AiRestrictionsFile.path(root)
            Files.createDirectories(path.parent)
            val original =
                """
                {"version":2,"restrictions":[{"id":"future","path":"Facts.kt","contentHash":"abc","anchor":{"startLine":0,"startCol":0,"endLine":0,"endCol":1},"remove":[{"intent":"LOGGING","startOffset":0,"endOffset":1}],"add":[]}]}
                """.trimIndent()
            Files.writeString(path, original)

            assertFailsWith<IllegalStateException> {
                AiRestrictionsFile.add(root, record("new"))
            }

            assertEquals(original, Files.readString(path))
            assertTrue(AiRestrictionsFile.load(root).isEmpty())
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun applicationRemovesPackIntentKeepsUserAndAddsAiConfirmed() {
        val existing =
            contribution("logging:logging", setOf("LOGGING"), 10, 20)
        val user =
            contribution(
                "core:user-annotations",
                setOf(USER_PURE_INTENT),
                10,
                20,
                source = "user",
            )
        val restriction =
            ResolvedRestriction(
                id = "proposal",
                path = "Facts.kt",
                anchorStartByte = 10,
                anchorEndByte = 20,
                anchorSpan = Span(0, 10, 0, 20),
                remove = listOf(operation("LOGGING", 10, 20)),
                add = listOf(operation("SEARCH", 10, 20)),
                provider = "deepseek",
                model = "deepseek-chat",
                promptVersion = "deepseek-intent-v3",
            )

        val applied =
            AiRestrictionApplication.apply(
                mapOf(
                    "logging:logging" to listOf(existing),
                    "core:user-annotations" to listOf(user),
                ),
                listOf(restriction),
            )

        val contributions = applied.values.flatten()
        assertTrue(contributions.none { "LOGGING" in it.intents })
        assertTrue(contributions.any { it.rule == "core:user-annotations" && USER_PURE_INTENT in it.intents })
        val replacement = contributions.single { "SEARCH" in it.intents }
        assertEquals("ai-confirmed", replacement.source)
        assertEquals("core:ai-confirmed", replacement.rule)
        assertEquals("deepseek", replacement.provider)
        assertEquals("deepseek-chat", replacement.model)
        assertEquals("deepseek-intent-v3", replacement.promptVersion)
    }

    @Test
    fun dormantRestrictionAppliesNeitherRemovalNorReplacement() {
        val existing = contribution("logging:logging", setOf("LOGGING"), 10, 20)
        val restriction =
            ResolvedRestriction(
                id = "dormant",
                path = "Facts.kt",
                anchorStartByte = 10,
                anchorEndByte = 20,
                anchorSpan = Span(0, 10, 0, 20),
                remove = listOf(operation("LOGGING", 30, 40)),
                add = listOf(operation("SEARCH", 30, 40)),
                provider = "deepseek",
                model = "deepseek-chat",
                promptVersion = "v3",
            )

        val applied =
            AiRestrictionApplication.apply(
                mapOf("logging:logging" to listOf(existing)),
                listOf(restriction),
            )

        val contributions = applied.values.flatten()
        assertEquals(setOf("LOGGING"), contributions.single().intents)
    }

    @Test
    fun userAnnotationAtTheSameSpanWinsOverARestriction() {
        val user = contribution("core:user-annotations", setOf("LOGGING"), 10, 20, source = "user")
        val restriction =
            ResolvedRestriction(
                id = "user-wins",
                path = "Facts.kt",
                anchorStartByte = 10,
                anchorEndByte = 20,
                anchorSpan = Span(0, 10, 0, 20),
                remove = listOf(operation("LOGGING", 10, 20)),
                add = emptyList(),
                provider = "deepseek",
                model = "deepseek-chat",
                promptVersion = "v3",
            )

        val applied =
            AiRestrictionApplication.apply(
                mapOf("core:user-annotations" to listOf(user)),
                listOf(restriction),
            )

        assertEquals(setOf("LOGGING"), applied.getValue("core:user-annotations").single().intents)
    }

    private fun record(id: String): AiRestrictionRecord =
        AiRestrictionRecord(
            id = id,
            path = "Facts.kt",
            span = Span(0, 0, 0, 1),
            contentHash = "sha256:abc",
            remove = listOf(RestrictionOperation("LOGGING", 0, 1)),
            add = emptyList(),
            provider = "deepseek",
            model = "deepseek-chat",
            promptVersion = "v3",
            createdAt = "2026-10-22T12:00:00Z",
        )

    private fun operation(
        intent: String,
        start: Int,
        end: Int,
    ): ResolvedRestrictionOperation =
        ResolvedRestrictionOperation(
            intent = intent,
            startByte = start,
            endByte = end,
            span = Span(0, start, 0, end),
            confidence = 0.7,
            rationale = null,
        )

    private fun contribution(
        rule: String,
        intents: Set<String>,
        start: Int,
        end: Int,
        source: String = "treesitter",
    ) = dev.cint.api.Contribution(
        rule = rule,
        role = dev.cint.api.Role.SOURCE_INTENT,
        intents = intents,
        span = Span(0, start, 0, end),
        source = source,
        confidence = 1.0,
        startByte = start,
        endByte = end,
    )
}
