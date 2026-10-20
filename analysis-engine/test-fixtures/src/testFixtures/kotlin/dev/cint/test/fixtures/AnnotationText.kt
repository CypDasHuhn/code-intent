package dev.cint.test.fixtures

import dev.cint.api.Annotation
import dev.cint.api.Contribution

fun sourceText(
    source: String,
    startByte: Int,
    endByte: Int,
): String =
    source
        .toByteArray(Charsets.UTF_8)
        .copyOfRange(startByte, endByte)
        .toString(Charsets.UTF_8)

fun Annotation.text(source: String): String = sourceText(source, startByte, endByte)

fun Contribution.text(source: String): String = sourceText(source, startByte, endByte)
