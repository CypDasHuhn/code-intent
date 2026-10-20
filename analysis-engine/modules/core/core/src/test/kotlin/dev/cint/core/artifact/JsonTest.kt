package dev.cint.core.artifact

import kotlin.test.Test
import kotlin.test.assertEquals

class JsonTest {
    @Test
    fun nonFiniteNumbersRenderAsQuotedStrings() {
        val json =
            Json.render(
                linkedMapOf(
                    "nan" to Double.NaN,
                    "positive" to Double.POSITIVE_INFINITY,
                    "negative" to Double.NEGATIVE_INFINITY,
                ),
            )

        assertEquals("""{"nan":"NaN","positive":"Infinity","negative":"-Infinity"}""", json)
    }
}
