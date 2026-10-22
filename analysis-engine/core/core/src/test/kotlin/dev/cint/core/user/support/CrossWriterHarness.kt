package dev.cint.core.user

import dev.cint.api.Span
import org.junit.Assume.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

internal fun newHarness(name: String): Harness {
    val repo = repoRoot()
    val nvim = nvimAvailable()
    if (System.getenv("CINT_REQUIRE_NVIM") == "1") {
        assertTrue(nvim, "nvim is required by CINT_REQUIRE_NVIM=1 but is not available")
    } else {
        assumeTrue("nvim is not available; install Neovim to run the cross-writer test", nvim)
    }
    assumeTrue("could not find nvim-renderer from ${Path.of("").toAbsolutePath()}", repo != null)
    val root = Files.createTempDirectory("cint-cross-writer-$name")
    val script = Files.createTempFile("cint-cross-writer-$name", ".lua")
    Files.writeString(script, CROSS_WRITER_LUA_SCRIPT)
    return Harness(root, script, requireNotNull(repo))
}

private fun repoRoot(): Path? {
    val starts =
        listOfNotNull(
            System.getProperty("cint.repoRoot")?.let(Path::of),
            System.getenv("CINT_REPO_ROOT")?.let(Path::of),
            Path.of("").toAbsolutePath(),
        )
    for (start in starts) {
        var current: Path? = start.toAbsolutePath().normalize()
        while (current != null) {
            if (Files.isRegularFile(current.resolve("nvim-renderer/lua/cint/init.lua"))) {
                return current
            }
            current = current.parent
        }
    }
    return null
}

private fun nvimAvailable(): Boolean =
    runCatching {
        val process = ProcessBuilder("nvim", "--version").redirectErrorStream(true).start()
        process.waitFor(10, TimeUnit.SECONDS) && process.exitValue() == 0
    }.getOrDefault(false)

private fun spawnNvim(
    harness: Harness,
    file: Path,
    mode: String,
    line: Int,
    col: Int = 5,
    intent: String? = null,
    note: String? = null,
    started: Path? = null,
    ready: Path? = null,
    gate: Path? = null,
    blocked: Path? = null,
    intervalDir: Path? = null,
    conflictDir: Path? = null,
    writerId: String? = null,
): Spawned {
    val log = harness.root.resolve("nvim-${writerId ?: mode}-${System.nanoTime()}.log")
    val builder =
        ProcessBuilder(
            "nvim",
            "--headless",
            "-u",
            "NONE",
            "--noplugin",
            "-i",
            "NONE",
            "-l",
            harness.script.toString(),
        )
    builder.environment().apply {
        put("CINT_CROSS_REPO_ROOT", harness.repoRoot.toString())
        put("CINT_CROSS_ROOT", harness.root.toString())
        put("CINT_CROSS_FILE", file.toString())
        put("CINT_CROSS_MODE", mode)
        put("CINT_CROSS_LINE", line.toString())
        put("CINT_CROSS_COL", col.toString())
        intent?.let { put("CINT_CROSS_INTENT", it) }
        note?.let { put("CINT_CROSS_NOTE", it) }
        started?.let { put("CINT_CROSS_STARTED", it.toString()) }
        ready?.let { put("CINT_CROSS_READY", it.toString()) }
        gate?.let { put("CINT_CROSS_GATE", it.toString()) }
        blocked?.let { put("CINT_CROSS_BLOCKED", it.toString()) }
        intervalDir?.let { put("CINT_CROSS_INTERVAL_DIR", it.toString()) }
        conflictDir?.let { put("CINT_CROSS_CONFLICT_DIR", it.toString()) }
        writerId?.let { put("CINT_CROSS_WRITER_ID", it) }
    }
    builder.redirectErrorStream(true)
    builder.redirectOutput(log.toFile())
    return Spawned(builder.start(), log)
}

internal fun ProcessTracker.spawn(
    harness: Harness,
    file: Path,
    mode: String,
    line: Int,
    col: Int = 5,
    intent: String? = null,
    note: String? = null,
    started: Path? = null,
    ready: Path? = null,
    gate: Path? = null,
    blocked: Path? = null,
    intervalDir: Path? = null,
    conflictDir: Path? = null,
    writerId: String? = null,
): Spawned {
    val item =
        spawnNvim(
            harness,
            file,
            mode,
            line,
            col,
            intent,
            note,
            started,
            ready,
            gate,
            blocked,
            intervalDir,
            conflictDir,
            writerId,
        )
    add(item)
    return item
}

internal fun awaitFile(
    path: Path,
    timeoutMillis: Long = 10_000,
) {
    val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
    while (!Files.exists(path)) {
        if (System.nanoTime() > deadline) fail("timed out waiting for $path")
        Thread.sleep(5)
    }
}

internal fun awaitAnyFile(
    directory: Path,
    prefix: String,
    timeoutMillis: Long = 10_000,
): Path {
    val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
    while (true) {
        val found =
            Files.list(directory).use { stream ->
                stream.filter { it.fileName.toString().startsWith(prefix) }.findFirst().orElse(null)
            }
        if (found != null) return found
        if (System.nanoTime() > deadline) fail("timed out waiting for $prefix* in $directory")
        Thread.sleep(5)
    }
}

internal fun awaitProcess(spawned: Spawned) {
    if (!spawned.process.waitFor(30, TimeUnit.SECONDS)) {
        spawned.process.destroyForcibly()
        spawned.process.waitFor(5, TimeUnit.SECONDS)
        fail("nvim timed out\n${readLog(spawned.log)}")
    }
    assertEquals(0, spawned.process.exitValue(), "nvim failed\n${readLog(spawned.log)}")
}

internal fun readLog(log: Path): String = runCatching { Files.readString(log) }.getOrElse { "<no log: ${it.message}>" }

internal fun writeFixture(
    root: Path,
    lines: List<String>,
): Path {
    val file = root.resolve("Sample.kt")
    Files.writeString(file, lines.joinToString("\n", postfix = "\n"))
    return file
}

internal fun defaultLines(): List<String> = (0..8).map { index -> "fun op$index(): String = \"$index\"" }

internal fun annotation(
    note: String,
    line: Int,
): UserAnnotation =
    UserAnnotation(
        path = "Sample.kt",
        span = Span(line, 0, line, 1),
        intents = setOf("PURE"),
        symbol = note,
        note = note,
    )

internal fun notes(root: Path): Set<String> = UserAnnotationsFile.load(root).mapNotNull { it.note }.toSet()

internal fun writeDeadLock(
    root: Path,
    pid: Long,
    start: String,
    token: String = "d".repeat(64),
) {
    val lock = lockPath(root)
    Files.createDirectories(lock)
    Files.writeString(lock.resolve("owner"), "token=$token\npid=$pid\nstart=$start\n")
}

internal data class Harness(
    val root: Path,
    val script: Path,
    val repoRoot: Path,
) : AutoCloseable {
    val processes = ProcessTracker()

    override fun close() {
        processes.terminateAll()
        root.toFile().deleteRecursively()
        Files.deleteIfExists(script)
    }
}

internal data class Spawned(
    val process: Process,
    val log: Path,
)

internal data class Worker(
    val id: String,
    val line: Int,
    val mode: String,
)

internal class ProcessTracker {
    private val spawned = Collections.synchronizedList(mutableListOf<Spawned>())

    fun add(item: Spawned) {
        spawned.add(item)
    }

    fun snapshot(): List<Spawned> = synchronized(spawned) { spawned.toList() }

    fun terminateAll() {
        val items = snapshot()
        items.forEach { item -> if (item.process.isAlive) item.process.destroyForcibly() }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline && items.any { it.process.isAlive }) {
            Thread.sleep(10)
        }
        items.forEach { item ->
            if (item.process.isAlive) {
                item.process.destroyForcibly()
                item.process.waitFor(5, TimeUnit.SECONDS)
            }
        }
    }
}

internal class FileLockObserver(
    private val intervalDir: Path,
    private val conflictDir: Path,
    private val writer: String,
) : AnnotationLockObserver {
    private val conflicts = AtomicLong()

    override fun acquired(
        root: Path,
        lockPath: Path,
        token: String,
    ) {
        writeIntervalRecord(
            intervalDir,
            writer,
            token,
            "enter",
            ProcessHandle.current().pid(),
            System.nanoTime(),
        )
    }

    override fun beforeRelease(
        root: Path,
        lockPath: Path,
        token: String,
    ) {
        writeIntervalRecord(
            intervalDir,
            writer,
            token,
            "exit",
            ProcessHandle.current().pid(),
            System.nanoTime(),
        )
    }

    override fun conflict(lockPath: Path) {
        val index = conflicts.incrementAndGet()
        Files.writeString(conflictDir.resolve("$writer-conflict-$index.conflict"), "1\n")
    }
}
