package dev.cint.core.user.support

import dev.cint.api.Span
import dev.cint.core.user.UserAnnotation
import dev.cint.core.user.UserAnnotationsFile
import dev.cint.core.user.UserAnnotationsJson
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertTrue

internal fun encodeAnnotation(
    note: String? = null,
    createdAt: String? = null,
): String =
    UserAnnotationsJson.encode(
        listOf(
            UserAnnotation(
                path = "Sample.kt",
                span = Span(0, 0, 0, 1),
                intents = setOf("PURE"),
                note = note,
                createdAt = createdAt,
            ),
        ),
    )

internal fun enter(
    inside: AtomicInteger,
    maxInside: AtomicInteger,
) {
    val current = inside.incrementAndGet()
    maxInside.accumulateAndGet(current) { left, right -> maxOf(left, right) }
}

internal fun waitForStartMarker(pid: Long): String {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
    while (System.nanoTime() < deadline) {
        val marker = UserAnnotationsFile.processStartMarkerForTest(pid)
        if (marker.isNotEmpty()) return marker
        Thread.sleep(5)
    }
    return ""
}

internal fun signal(
    signal: String,
    pid: Long,
) {
    val process = ProcessBuilder("/bin/kill", signal, pid.toString()).start()
    assertTrue(process.waitFor(5, TimeUnit.SECONDS))
    assertEquals(0, process.exitValue())
}

internal fun writeLock(
    lockPath: Path,
    token: String,
    pid: Long,
    start: String,
    old: Boolean,
) {
    Files.createDirectories(lockPath)
    Files.writeString(lockPath.resolve("owner"), "token=$token\npid=$pid\nstart=$start\n")
    if (old) setOld(lockPath)
}

internal fun setOld(path: Path) {
    val old = FileTime.fromMillis(System.currentTimeMillis() - TimeUnit.SECONDS.toMillis(30))
    Files.setLastModifiedTime(path, old)
    val owner = path.resolve("owner")
    if (Files.exists(owner)) Files.setLastModifiedTime(owner, old)
}

internal fun readToken(lockPath: Path): String? {
    val owner = lockPath.resolve("owner")
    if (!Files.exists(owner)) return null
    return Files
        .readAllLines(owner)
        .firstOrNull { it.startsWith("token=") }
        ?.substringAfter("token=")
        ?.trim()
}

internal fun deleteTree(path: Path) {
    if (!Files.exists(path)) return
    Files.walk(path).use { paths ->
        paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
    }
}

internal fun lockPath(root: Path): Path =
    root
        .resolve(UserAnnotationsFile.DIRECTORY)
        .resolve("${UserAnnotationsFile.FILE_NAME}.lock")

internal fun claimPath(root: Path): Path =
    root
        .resolve(UserAnnotationsFile.DIRECTORY)
        .resolve("${UserAnnotationsFile.FILE_NAME}.lock.steal")
