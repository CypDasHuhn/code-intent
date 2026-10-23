package dev.cint.core.support.hashing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class SourceNormalizerTest {
    @Test
    fun whitespaceAndCommentsDoNotChangeTheNormalizedText() {
        assertEquals(
            normalizedSourceText("val x=a+b"),
            normalizedSourceText("val  x = a + b // formatting\n"),
        )
        assertEquals(
            normalizedSourceText("log.warn(message)"),
            normalizedSourceText("log.warn( message )"),
        )
        assertEquals(
            normalizedSourceText("val x = a /* note */ + b"),
            normalizedSourceText("val x = a + b"),
        )
    }

    @Test
    fun commentMarkersInsideStringsArePreserved() {
        assertNotEquals(
            normalizedSourceText("val url = \"https://example.com\""),
            normalizedSourceText("val url = \"https://other.example\""),
        )
        assertEquals(
            normalizedSourceText("val url = \"https://example.com\""),
            normalizedSourceText("val  url=\"https://example.com\""),
        )
    }

    @Test
    fun rawAndNormalizedOffsetsAreInverseAtTokenBoundaries() {
        val text = "f(a, name)"
        val name = text.indexOf("name")
        val normalized = normalizedSourceText(text)
        val normalizedName = normalized.indexOf("name")
        assertEquals(normalizedName, SourceNormalizer.normalizedLengthAtByte(text, name))
        assertEquals(name, SourceNormalizer.rawByteAtNormalizedOffset(text, normalizedName))
        assertEquals(name + 2, SourceNormalizer.rawByteAtNormalizedOffset(text, normalizedName + 2))

        val leading = "   f(a)"
        val firstToken = leading.indexOf('f')
        assertEquals(0, SourceNormalizer.normalizedLengthAtByte(leading, firstToken))
        assertEquals(firstToken, SourceNormalizer.rawByteAtNormalizedOffset(leading, 0))

        val trailing = "f(a)   "
        val end = trailing.indexOf(')') + 1
        val normalizedEnd = normalizedSourceText(trailing).length
        assertEquals(normalizedEnd, SourceNormalizer.normalizedLengthAtByte(trailing, end + 2))
        assertEquals(end, SourceNormalizer.rawByteAtNormalizedOffset(trailing, normalizedEnd))
    }

    @Test
    fun rawAndNormalizedOffsetsHandleCommentsAndMultibyteTokens() {
        val commented = "f(a, /* note */ name)"
        val name = commented.indexOf("name")
        val normalizedCommented = normalizedSourceText(commented)
        val normalizedName = normalizedCommented.indexOf("name")
        assertEquals(normalizedName, SourceNormalizer.normalizedLengthAtByte(commented, name))
        assertEquals(name, SourceNormalizer.rawByteAtNormalizedOffset(commented, normalizedName))

        val multibyte = "f(α, β)"
        val betaByte = multibyte.substring(0, multibyte.indexOf('β')).toByteArray(Charsets.UTF_8).size
        val normalizedMultibyte = normalizedSourceText(multibyte)
        val normalizedBeta = normalizedMultibyte.indexOf('β')
        assertEquals(normalizedBeta, SourceNormalizer.normalizedLengthAtByte(multibyte, betaByte))
        assertEquals(betaByte, SourceNormalizer.rawByteAtNormalizedOffset(multibyte, normalizedBeta))
        assertEquals(
            betaByte + 'β'.toString().toByteArray(Charsets.UTF_8).size,
            SourceNormalizer.rawByteAtNormalizedOffset(multibyte, normalizedBeta + 1),
        )
    }
}
