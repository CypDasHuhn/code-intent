package dev.cint.core.host

import dev.cint.core.config.IntentConfig
import dev.cint.core.config.loadIntentConfig
import java.io.File

object HostConfig {
    fun load(
        path: String?,
        language: String,
    ): IntentConfig {
        if (path == null) return loadIntentConfig(null, language)
        val file = File(path)
        require(file.isFile) { "config not found: '$path'" }
        loadIntentConfig(path, language)
        System.err.println(
            "cint: custom detection config '$path' is not wired into rulepacks yet " +
                "(tickets 012/013); continuing with pack defaults",
        )
        return loadIntentConfig(null, language)
    }
}
