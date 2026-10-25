package dev.cint.runtime

import dev.cint.v2.languages.LanguageRootLocator
import java.nio.file.Files
import java.nio.file.Path

object MarkerRootLocator : LanguageRootLocator {
    private const val GIT_MARKER = ".git"

    override fun locate(
        path: String?,
        markers: List<String>,
    ): String? {
        val file = path?.let { runCatching { Path.of(it).toAbsolutePath().normalize() }.getOrNull() } ?: return null
        val start = if (Files.isDirectory(file)) file else file.parent ?: return null
        val languageMarkers = markers.filterNot { it == GIT_MARKER }
        for (marker in languageMarkers) {
            var directory: Path? = start
            while (directory != null) {
                if (hasMarker(directory, marker)) return directory.toString()
                directory = directory.parent
            }
        }
        if (GIT_MARKER in markers) {
            var directory: Path? = start
            while (directory != null) {
                if (hasMarker(directory, GIT_MARKER)) return directory.toString()
                directory = directory.parent
            }
        }
        return null
    }

    private fun hasMarker(
        directory: Path,
        marker: String,
    ): Boolean =
        if (marker.contains('*') || marker.contains('?')) {
            runCatching {
                Files.newDirectoryStream(directory, marker).use { it.iterator().hasNext() }
            }.getOrDefault(false)
        } else {
            Files.exists(directory.resolve(marker))
        }
}
