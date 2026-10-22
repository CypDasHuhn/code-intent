package dev.cint.core.user.layout

import java.nio.file.Path

internal object AnnotationLayout {
    const val DIRECTORY: String = ".cint"
    const val FILE_NAME: String = "user-annotations.json"

    fun path(root: Path): Path = root.resolve(DIRECTORY).resolve(FILE_NAME)
}
