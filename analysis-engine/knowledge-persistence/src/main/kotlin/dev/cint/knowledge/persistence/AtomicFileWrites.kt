package dev.cint.knowledge.persistence

import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

internal object AtomicFileWrites {
    fun writeText(
        target: Path,
        text: String,
        prefix: String,
    ) {
        val absolute = target.toAbsolutePath()
        val parent = absolute.parent ?: Path.of(".").toAbsolutePath()
        Files.createDirectories(parent)
        val temporary = Files.createTempFile(parent, prefix, ".tmp")
        try {
            Files.writeString(temporary, text, StandardCharsets.UTF_8)
            moveIntoPlace(temporary, absolute)
        } catch (error: Exception) {
            Files.deleteIfExists(temporary)
            throw error
        }
    }

    private fun moveIntoPlace(
        temporary: Path,
        target: Path,
    ) {
        try {
            Files.move(
                temporary,
                target,
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
