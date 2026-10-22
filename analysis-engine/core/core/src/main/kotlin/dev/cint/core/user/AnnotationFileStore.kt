package dev.cint.core.user

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

internal object AnnotationFileStore {
    fun load(path: Path): List<UserAnnotation> {
        if (!Files.isRegularFile(path)) return emptyList()
        return UserAnnotationsJson.parse(Files.readString(path))
    }

    fun write(
        path: Path,
        annotations: List<UserAnnotation>,
        verify: () -> Unit,
    ) {
        Files.createDirectories(path.parent)
        val temporary = path.resolveSibling("${path.fileName}.tmp")
        Files.writeString(temporary, UserAnnotationsJson.encode(annotations))
        try {
            verify()
        } catch (error: Throwable) {
            runCatching { Files.deleteIfExists(temporary) }
            throw error
        }
        runCatching {
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }.getOrElse {
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
