package dev.cint.v2.server

import java.nio.file.Path

data class ServerOptions(
    val socketPath: Path,
    val root: Path,
    val defaultLanguage: String? = null,
    val languages: List<String> = listOf("kotlin", "javascript", "typescript", "vue", "csharp"),
    val version: String = "0.1.0",
    val analysisTimeoutMs: Long = 30_000,
    val closeGraceMs: Long = 5_000,
)
