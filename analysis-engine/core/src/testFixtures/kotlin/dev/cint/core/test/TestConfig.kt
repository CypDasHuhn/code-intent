package dev.cint.core.test

import dev.cint.core.config.DEFAULT_PURITY_ANNOTATIONS
import dev.cint.core.config.IntentConfig

fun testConfig(language: String): IntentConfig =
    IntentConfig(
        language = language,
        intents = emptyMap(),
        pureAnnotations = DEFAULT_PURITY_ANNOTATIONS,
    )
