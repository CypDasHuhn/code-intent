package dev.cint.suggest.cache

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dev.cint.api.Span
import dev.cint.suggest.session.AgentRequest
import dev.cint.suggest.session.AgentTurn
import dev.cint.suggest.session.AnnotationSession
import dev.cint.suggest.session.AnnotationSessionProvider
import dev.cint.suggest.session.SessionDocument
import dev.cint.suggest.session.SessionLimits
import dev.cint.suggest.session.SessionText
import dev.cint.suggest.session.SessionUnit
import dev.cint.suggest.session.ToolCall
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VerdictCacheTest {
    private val mapper = jacksonObjectMapper()
    private val text = "val match = Regex(\"cint\").find(text)"
    private val findStart = text.indexOf("find(text)")
    private val findEnd = findStart + "find(text)".length

    @Test
    fun unitKeyIncludesEveryIdentityComponent() {
        val unit = unit("u0", 0, text.length)
        val base = UnitCacheKeys.of(unit, "env")

        assertTrue(base.hash != UnitCacheKeys.of(unit.copy(language = "typescript"), "env").hash)
        assertTrue(base.hash != UnitCacheKeys.of(unit.copy(imports = listOf("a.B")), "env").hash)
        assertTrue(base.hash != UnitCacheKeys.of(unit.copy(nodeKind = "if_expression"), "env").hash)
        assertTrue(base.hash != UnitCacheKeys.of(unit.copy(normalizedText = "other"), "env").hash)
        assertTrue(base.hash != UnitCacheKeys.of(unit.copy(enclosingSymbol = "other"), "env").hash)
        assertTrue(base.hash != UnitCacheKeys.of(unit.copy(enclosingSignature = "fun other()"), "env").hash)
        assertTrue(base.hash != UnitCacheKeys.of(unit.copy(referencedNames = listOf("other")), "env").hash)
        assertTrue(base.hash != UnitCacheKeys.of(unit.copy(existingAnnotations = listOf(annotation())), "env").hash)
        assertTrue(base.hash != UnitCacheKeys.of(unit, "other-env").hash)
    }

    @Test
    fun negativeVerdictsAreCachedAndReused() {
        val cache = VerdictCache()
        val unit = unit("u0", 0, text.length)
        val key = UnitCacheKeys.of(unit, "env")
        cache.store(key, unit, emptyList(), emptyList(), none = true, anchorFingerprint = "anchor")

        val cached = assertNotNull(cache.lookup(key, unit))
        assertTrue(cached.none)
        assertTrue(cached.adds.isEmpty())
        assertEquals(1, cache.size())
    }

    @Test
    fun lruEvictionIsBounded() {
        val cache = VerdictCache(maxEntries = 2)
        val first = unit("u0", 0, text.length).copy(normalizedText = "first")
        val second = first.copy(normalizedText = "second")
        val third = first.copy(normalizedText = "third")
        for (candidate in listOf(first, second, third)) {
            cache.store(UnitCacheKeys.of(candidate, "env"), candidate, emptyList(), emptyList(), true, "anchor")
        }

        assertEquals(2, cache.size())
        assertNull(cache.lookup(UnitCacheKeys.of(first, "env"), first))
        assertNotNull(cache.lookup(UnitCacheKeys.of(second, "env"), second))
        assertNotNull(cache.lookup(UnitCacheKeys.of(third, "env"), third))
    }

    @Test
    fun ttlEvictionDropsStaleEntries() {
        var now = 1_000L
        val cache = VerdictCache(ttlMillis = 100L, clock = { now })
        val unit = unit("u0", 0, text.length)
        val key = UnitCacheKeys.of(unit, "env")
        cache.store(key, unit, emptyList(), emptyList(), true, "anchor")

        now += 101L
        assertNull(cache.lookup(key, unit))
        assertEquals(0, cache.size())
    }

    @Test
    fun textChangeIgnoresFormattingAndReportsEditedIdentifiers() {
        val oldText = "val match = Regex(\"cint\").find(text)"
        val formatted = "val  match  =  Regex(\"cint\").find( text )"
        assertTrue(TextChange.between(oldText, formatted).empty)

        val renamed = "val found = Regex(\"cint\").find(text)"
        val change = TextChange.between(oldText, renamed)
        assertTrue(change.ranges.isNotEmpty())
        assertTrue("match" in change.names)
        assertTrue("found" in change.names)

        val deleted = TextChange.between("x + y", "x  y")
        assertTrue(!deleted.empty)
        assertTrue(deleted.ranges.isNotEmpty())
    }

    @Test
    fun unchangedUnitsReuseVerdictsWithoutCallingTheProvider() {
        val cache = VerdictCache()
        val provider = AddProvider()
        val document = document(listOf(unit("u0", 0, text.length)))
        val first =
            AnnotationSession(provider, verdictCache = cache, aiEnv = "env")
                .run(document, SessionLimits())
        assertEquals(1, first.graded)
        assertEquals(0, first.reused)
        assertEquals(1, provider.calls.get())
        assertEquals(1, first.candidates.size)

        val second =
            AnnotationSession(provider, verdictCache = cache, aiEnv = "env")
                .run(document, SessionLimits())
        assertEquals(0, second.graded)
        assertEquals(1, second.reused)
        assertEquals(1, provider.calls.get())
        assertEquals(1, second.candidates.size)
        assertEquals(first.candidates.single().startByte, second.candidates.single().startByte)
        assertEquals(first.candidates.single().endByte, second.candidates.single().endByte)
        assertTrue(second.events.any { it.type == "reuse" })
    }

    @Test
    fun changedUnitIsGradedAndSeededWithAPriorHint() {
        val cache = VerdictCache()
        val provider = AddProvider()
        val original = document(listOf(unit("u0", 0, text.length)))
        AnnotationSession(provider, verdictCache = cache, aiEnv = "env").run(original, SessionLimits())

        val changedText = text.replace("text)", "other)")
        val changedUnit = unit("u0", 0, changedText.length, changedText)
        val changed = document(listOf(changedUnit), changedText)
        val result =
            AnnotationSession(provider, verdictCache = cache, aiEnv = "env")
                .run(changed, SessionLimits())

        assertEquals(1, result.graded)
        assertEquals(0, result.reused)
        assertEquals(2, provider.calls.get())
        assertTrue(provider.lastPrompt.contains("prior:"))
        assertTrue(result.events.any { it.type == "hint" })
    }

    @Test
    fun environmentChangeInvalidatesTheCacheLayer() {
        val cache = VerdictCache()
        val provider = AddProvider()
        val document = document(listOf(unit("u0", 0, text.length)))
        AnnotationSession(provider, verdictCache = cache, aiEnv = "env-1").run(document, SessionLimits())
        val result =
            AnnotationSession(provider, verdictCache = cache, aiEnv = "env-2")
                .run(document, SessionLimits())

        assertEquals(1, result.graded)
        assertEquals(0, result.reused)
        assertEquals(2, provider.calls.get())
    }

    @Test
    fun changedRangeAndReferencedNamesBlockReuse() {
        val cache = VerdictCache()
        val provider = AddProvider()
        val document = document(listOf(unit("u0", 0, text.length).copy(referencedNames = listOf("text"))))
        AnnotationSession(provider, verdictCache = cache, aiEnv = "env").run(document, SessionLimits())

        val invalidated =
            AnnotationSession(
                provider,
                verdictCache = cache,
                aiEnv = "env",
                cacheInvalidation = CacheInvalidation(listOf(0..5), emptySet()),
            ).run(document, SessionLimits())
        assertEquals(1, invalidated.graded)
        assertEquals(0, invalidated.reused)
        assertTrue(provider.lastPrompt.contains("prior:"), provider.lastPrompt)

        val byName =
            AnnotationSession(
                provider,
                verdictCache = cache,
                aiEnv = "env",
                cacheInvalidation = CacheInvalidation(emptyList(), setOf("text")),
            ).run(document, SessionLimits())
        assertEquals(1, byName.graded)
        assertEquals(0, byName.reused)
        assertTrue(provider.lastPrompt.contains("prior:"), provider.lastPrompt)
    }

    @Test
    fun intraUnitFormattingKeepsTheCachedTokenSpan() {
        val cache = VerdictCache()
        val provider = TokenProvider()
        val originalText = "f(a, b)"
        val originalUnit = unit("u0", 0, originalText.length, originalText)
        val original = document(listOf(originalUnit), originalText)
        val first = AnnotationSession(provider, verdictCache = cache, aiEnv = "env").run(original, SessionLimits())
        assertEquals(1, provider.calls.get())
        assertEquals("a", originalText.substring(first.candidates.single().startByte, first.candidates.single().endByte))

        val formattedText = "f( a , b )"
        val formattedUnit =
            unit("u0", 0, formattedText.length, formattedText).copy(
                normalizedText = unit("u0", 0, originalText.length, originalText).normalizedText,
            )
        val second =
            AnnotationSession(provider, verdictCache = cache, aiEnv = "env")
                .run(document(listOf(formattedUnit), formattedText), SessionLimits())

        assertEquals(1, second.reused)
        assertEquals(0, second.graded)
        val candidate = second.candidates.single()
        assertEquals(
            "a",
            formattedText.substring(candidate.startByte, candidate.endByte),
        )
    }

    @Test
    fun reformattedAnnotatedUnitStillReusesAnEmptyVerdict() {
        val cache = VerdictCache()
        val provider = AddProvider()
        val originalText = "f(a, b)"
        val originalAnnotation =
            dev.cint.suggest.session.SessionAnnotation(
                intents = setOf("LOGGING"),
                startByte = 2,
                endByte = 3,
                span = SessionText.spanFor(originalText, 2, 3) ?: Span(0, 0, 0, 0),
                source = "treesitter",
                confidence = 1.0,
                role = "SOURCE_INTENT",
            )
        val originalUnit =
            unit("u0", 0, originalText.length, originalText).copy(existingAnnotations = listOf(originalAnnotation))
        AnnotationSession(provider, verdictCache = cache, aiEnv = "env")
            .run(document(listOf(originalUnit), originalText), SessionLimits())

        val formattedText = "f( a , b )"
        val formattedStart = formattedText.indexOf('a')
        val formattedAnnotation =
            originalAnnotation.copy(
                startByte = formattedStart,
                endByte = formattedStart + 1,
                span = SessionText.spanFor(formattedText, formattedStart, formattedStart + 1) ?: Span(0, 0, 0, 0),
            )
        val formattedUnit =
            unit("u0", 0, formattedText.length, formattedText).copy(
                existingAnnotations = listOf(formattedAnnotation),
                normalizedText = originalUnit.normalizedText,
            )
        val second =
            AnnotationSession(provider, verdictCache = cache, aiEnv = "env")
                .run(document(listOf(formattedUnit), formattedText), SessionLimits())

        assertEquals(1, second.reused)
        assertEquals(0, second.graded)
    }

    @Test
    fun restrictionHintsDoNotDependOnTheVerdictCache() {
        val provider = AddProvider()
        val hint = "prior restriction: REMOVE LOGGING 0..10 - confirm, adjust, or discard"
        val document = document(listOf(unit("u0", 0, text.length).copy(priorHints = listOf(hint))))
        AnnotationSession(provider).run(document, SessionLimits())

        assertTrue(provider.lastPrompt.contains("prior restriction:"), provider.lastPrompt)
    }

    @Test
    fun cacheDisabledLeavesTheSessionUnchanged() {
        val provider = AddProvider()
        val document = document(listOf(unit("u0", 0, text.length)))
        val result = AnnotationSession(provider).run(document, SessionLimits())

        assertEquals(1, result.graded)
        assertEquals(0, result.reused)
        assertEquals(0, result.overhead)
    }

    private fun document(
        units: List<SessionUnit>,
        sourceText: String = text,
    ): SessionDocument =
        SessionDocument(
            path = "Facts.kt",
            version = 1,
            text = sourceText,
            language = "kotlin",
            units = units,
            annotations = units.flatMap { it.existingAnnotations },
            knownIntents = setOf("SEARCH", "LOGGING"),
        )

    private fun unit(
        id: String,
        start: Int,
        end: Int,
        sourceText: String = text,
    ): SessionUnit =
        SessionUnit(
            unitId = id,
            path = "Facts.kt",
            language = "kotlin",
            startByte = start,
            endByte = end,
            span = SessionText.spanFor(sourceText, start, end) ?: Span(0, 0, 0, 0),
            text = sourceText.substring(start, end),
            enclosingStatement = Span(0, 0, 0, sourceText.length),
            imports = listOf("kotlin.text.Regex"),
            nodeKind = "call_expression",
            enclosingSymbol = "f",
            enclosingSignature = "fun f(text: String)",
            referencedNames = listOf("Regex", "find", "text"),
        )

    private fun annotation(): dev.cint.suggest.session.SessionAnnotation =
        dev.cint.suggest.session.SessionAnnotation(
            intents = setOf("LOGGING"),
            startByte = findStart,
            endByte = findEnd,
            span = SessionText.spanFor(text, findStart, findEnd) ?: Span(0, 0, 0, 0),
            source = "treesitter",
            confidence = 1.0,
            role = "SOURCE_INTENT",
        )

    private inner class TokenProvider : AnnotationSessionProvider {
        override val id: String = "scripted"
        override val model: String = "scripted-v1"
        override val defaultPromptVersion: String = "scripted-prompt-v1"
        val calls: AtomicInteger = AtomicInteger(0)

        override fun complete(request: AgentRequest): AgentTurn {
            calls.incrementAndGet()
            val user =
                request.messages
                    .lastOrNull { it.role == "user" }
                    ?.content
                    .orEmpty()
            val units = mapper.readTree(user).path("units")
            val verdicts =
                units.map { unit ->
                    val unitText = unit.path("text").asText()
                    val start = unit.path("startByte").asInt()
                    val index = unitText.indexOf('a')
                    if (index >= 0) {
                        linkedMapOf(
                            "unitId" to unit.path("unitId").asText(),
                            "add" to
                                listOf(
                                    linkedMapOf(
                                        "intent" to "SEARCH",
                                        "startByte" to start + index,
                                        "endByte" to start + index + 1,
                                        "confidence" to 0.6,
                                    ),
                                ),
                        )
                    } else {
                        linkedMapOf("unitId" to unit.path("unitId").asText(), "verdict" to "none")
                    }
                }
            return AgentTurn.Calls(
                listOf(
                    ToolCall(
                        id = "call",
                        name = "submit_verdicts",
                        arguments = mapper.writeValueAsString(linkedMapOf("verdicts" to verdicts)),
                    ),
                ),
            )
        }
    }

    private inner class AddProvider : AnnotationSessionProvider {
        override val id: String = "scripted"
        override val model: String = "scripted-v1"
        override val defaultPromptVersion: String = "scripted-prompt-v1"
        val calls: AtomicInteger = AtomicInteger(0)
        var lastPrompt: String = ""

        override fun complete(request: AgentRequest): AgentTurn {
            calls.incrementAndGet()
            val user =
                request.messages
                    .lastOrNull { it.role == "user" }
                    ?.content
                    .orEmpty()
            lastPrompt = user
            val units = mapper.readTree(user).path("units")
            val verdicts =
                units.map { unit ->
                    val unitText = unit.path("text").asText()
                    val start = unit.path("startByte").asInt()
                    when {
                        unitText.contains("find(") -> {
                            val index = unitText.indexOf("find(")
                            linkedMapOf(
                                "unitId" to unit.path("unitId").asText(),
                                "add" to
                                    listOf(
                                        linkedMapOf(
                                            "intent" to "SEARCH",
                                            "startByte" to start + index,
                                            "endByte" to start + index + "find(text)".length,
                                            "confidence" to 0.6,
                                        ),
                                    ),
                            )
                        }

                        else -> {
                            linkedMapOf("unitId" to unit.path("unitId").asText(), "verdict" to "none")
                        }
                    }
                }
            return AgentTurn.Calls(
                listOf(
                    ToolCall(
                        id = "call",
                        name = "submit_verdicts",
                        arguments = mapper.writeValueAsString(linkedMapOf("verdicts" to verdicts)),
                    ),
                ),
            )
        }
    }
}
