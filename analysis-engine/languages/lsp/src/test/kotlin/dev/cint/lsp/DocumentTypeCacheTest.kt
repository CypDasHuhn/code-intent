package dev.cint.lsp

import dev.cint.core.facts.oracle.TypeFact
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DocumentTypeCacheTest {
    @Test
    fun cachesPositiveAndNegativeResultsPerDocumentVersion() {
        val cache = DocumentTypeCache(8)
        val positive = DocumentTypeKey("file:///A.kt", 1, 10)
        val negative = DocumentTypeKey("file:///A.kt", 1, 20)

        cache.put(positive, DocumentTypeEntry(TypeFact("Acme.Type"), 1))
        cache.put(negative, DocumentTypeEntry(null, 2))

        val positiveLookup = cache.lookup(positive)
        assertTrue(positiveLookup.present)
        assertEquals("Acme.Type", positiveLookup.entry?.fact?.fqName)
        val negativeLookup = cache.lookup(negative)
        assertTrue(negativeLookup.present)
        assertNull(negativeLookup.entry?.fact)
        assertEquals(2L, negativeLookup.entry?.cachedAtNanos)
    }

    @Test
    fun invalidationDropsOnlyTheAffectedDocument() {
        val cache = DocumentTypeCache(8)
        val first = DocumentTypeKey("file:///A.kt", 1, 10)
        val second = DocumentTypeKey("file:///B.kt", 1, 10)
        cache.put(first, DocumentTypeEntry(TypeFact("A"), 1))
        cache.put(second, DocumentTypeEntry(TypeFact("B"), 1))

        cache.invalidate("file:///A.kt")

        assertFalse(cache.lookup(first).present)
        assertTrue(cache.lookup(second).present)
    }

    @Test
    fun aNewVersionDoesNotReuseTheOldVersionEntry() {
        val cache = DocumentTypeCache(8)
        val old = DocumentTypeKey("file:///A.kt", 1, 10)
        val next = DocumentTypeKey("file:///A.kt", 2, 10)
        cache.put(old, DocumentTypeEntry(TypeFact("A"), 1))

        assertFalse(cache.lookup(next).present)
        cache.invalidate("file:///A.kt")
        assertFalse(cache.lookup(old).present)
    }

    @Test
    fun theCacheIsBoundedAndEvictsTheLeastRecentlyUsedEntry() {
        val cache = DocumentTypeCache(2)
        val first = DocumentTypeKey("file:///A.kt", 1, 1)
        val second = DocumentTypeKey("file:///A.kt", 1, 2)
        val third = DocumentTypeKey("file:///A.kt", 1, 3)
        cache.put(first, DocumentTypeEntry(TypeFact("One"), 1))
        cache.put(second, DocumentTypeEntry(TypeFact("Two"), 1))
        cache.lookup(first)
        cache.put(third, DocumentTypeEntry(TypeFact("Three"), 1))

        assertEquals(2, cache.size())
        assertTrue(cache.lookup(first).present)
        assertFalse(cache.lookup(second).present)
        assertTrue(cache.lookup(third).present)
    }
}
