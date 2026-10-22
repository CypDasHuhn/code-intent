package dev.cint.core.user

import dev.cint.api.Span
import org.junit.Assume.assumeTrue
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

class CrossWriterAnnotationLockTest {
    @Test
    fun heldKotlinLockBlocksNeovimUntilReleaseAndBothRecordsSurvive() {
        newHarness("held").use { harness ->
            val file = writeFixture(harness.root, defaultLines())
            UserAnnotationsFile.add(harness.root, annotation("kotlin-held", 100))

            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val errors = Collections.synchronizedList(mutableListOf<Throwable>())
            val holder =
                Thread {
                    try {
                        UserAnnotationsFile.withFileLock(harness.root, acquireTimeoutMillis = 5_000) {
                            entered.countDown()
                            release.await(10, TimeUnit.SECONDS)
                        }
                    } catch (error: Throwable) {
                        errors.add(error)
                    }
                }
            holder.isDaemon = true
            holder.start()

            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS))

                val started = harness.root.resolve("nvim-started")
                val blocked = harness.root.resolve("nvim-blocked")
                val spawned =
                    harness.processes.spawn(
                        harness,
                        file,
                        mode = "annotate",
                        line = 2,
                        intent = "PURE",
                        note = "nvim-held",
                        started = started,
                        blocked = blocked,
                    )
                awaitFile(started)
                awaitFile(blocked)

                assertTrue(spawned.process.isAlive, "neovim should wait for the held lock\n${readLog(spawned.log)}")
                assertEquals(
                    setOf("kotlin-held"),
                    notes(harness.root),
                    "neovim must not write while the Kotlin writer holds the lock",
                )
                assertTrue(Files.exists(lockPath(harness.root)), "the Kotlin writer should still hold the lock")

                release.countDown()
                holder.join(10_000)
                assertFalse(holder.isAlive, "the Kotlin lock holder should finish")
                assertTrue(errors.isEmpty(), errors.joinToString())

                awaitProcess(spawned)
                UserAnnotationsFile.save(
                    harness.root,
                    UserAnnotationsFile.load(harness.root) + annotation("kotlin-saved", 101),
                )

                assertEquals(setOf("kotlin-held", "nvim-held", "kotlin-saved"), notes(harness.root))
                assertFalse(Files.exists(lockPath(harness.root)), "the writers should release the shared lock")
            } finally {
                release.countDown()
                holder.join(10_000)
                harness.processes.terminateAll()
            }
        }
    }

    @Test
    fun deadHolderStaleLockIsRecoveredByBothWritersWithoutLosingAnnotations() {
        newHarness("stale").use { harness ->
            val file = writeFixture(harness.root, defaultLines())
            val helper = ProcessBuilder("sleep", "60").start()
            try {
                val pid = helper.pid()
                val start = waitForStartMarker(pid)
                helper.destroyForcibly()
                assertTrue(helper.waitFor(5, TimeUnit.SECONDS), "the dead-holder helper should exit")

                writeDeadLock(harness.root, pid, start)
                assertTrue(UserAnnotationsFile.isStaleForTest(harness.root))
                UserAnnotationsFile.add(harness.root, annotation("kotlin-stale", 100))
                assertFalse(
                    Files.exists(lockPath(harness.root)),
                    "the Kotlin writer should recover the stale lock",
                )
                assertEquals(setOf("kotlin-stale"), notes(harness.root))

                writeDeadLock(harness.root, pid, start)
                assertTrue(UserAnnotationsFile.isStaleForTest(harness.root))
                val spawned =
                    harness.processes.spawn(
                        harness,
                        file,
                        mode = "annotate",
                        line = 2,
                        intent = "PURE",
                        note = "nvim-stale",
                    )
                awaitProcess(spawned)

                assertFalse(
                    Files.exists(lockPath(harness.root)),
                    "the Neovim writer should recover the stale lock",
                )
                assertEquals(setOf("kotlin-stale", "nvim-stale"), notes(harness.root))
            } finally {
                if (helper.isAlive) helper.destroyForcibly()
                helper.waitFor(5, TimeUnit.SECONDS)
                harness.processes.terminateAll()
            }
        }
    }

    @Test
    fun concurrentNeovimAnnotateUnannotateAndKotlinAddsPreserveEveryRecord() {
        newHarness("concurrent").use { harness ->
            val file = writeFixture(harness.root, (0..8).map { index -> "fun op$index(): String = \"$index\"" })
            UserAnnotationsFile.add(
                harness.root,
                UserAnnotation(
                    path = "Sample.kt",
                    span = Span(4, 0, 4, 1),
                    intents = setOf("PURE"),
                    symbol = "op4",
                    note = "remove-me",
                ),
            )

            val intervalDir = Files.createDirectories(harness.root.resolve("intervals"))
            val conflictDir = Files.createDirectories(harness.root.resolve("conflicts"))
            val readyDir = Files.createDirectories(harness.root.resolve("ready"))
            val gate = harness.root.resolve("gate")
            val workers =
                listOf(
                    Worker("nvim-a", 1, "annotate"),
                    Worker("nvim-b", 2, "annotate"),
                    Worker("nvim-c", 3, "annotate"),
                    Worker("nvim-d", 4, "annotate-unannotate"),
                    Worker("nvim-remove", 5, "unannotate"),
                )

            val holderRelease = CountDownLatch(1)
            val holderEntered = CountDownLatch(1)
            val holderErrors = Collections.synchronizedList(mutableListOf<Throwable>())
            val holder =
                Thread {
                    try {
                        UserAnnotationsFile.withFileLock(harness.root, acquireTimeoutMillis = 5_000) {
                            holderEntered.countDown()
                            holderRelease.await(10, TimeUnit.SECONDS)
                        }
                    } catch (error: Throwable) {
                        holderErrors.add(error)
                    }
                }
            holder.isDaemon = true

            val writerCount = 8
            val executor = Executors.newFixedThreadPool(writerCount)
            val start = CountDownLatch(1)
            val errors = Collections.synchronizedList(mutableListOf<Throwable>())
            val observer = FileLockObserver(intervalDir, conflictDir, "kotlin")
            var holderStarted = false

            try {
                val spawned =
                    workers.map { worker ->
                        harness.processes.spawn(
                            harness,
                            file,
                            mode = worker.mode,
                            line = worker.line,
                            intent = if (worker.mode == "unannotate") null else "PURE",
                            note = if (worker.mode == "unannotate") "remove-me" else worker.id,
                            ready = readyDir.resolve(worker.id),
                            gate = gate,
                            intervalDir = intervalDir,
                            conflictDir = conflictDir,
                            writerId = worker.id,
                        )
                    }
                workers.forEach { worker -> awaitFile(readyDir.resolve(worker.id)) }

                UserAnnotationsFile.lockObserver = observer
                holder.start()
                holderStarted = true
                assertTrue(holderEntered.await(5, TimeUnit.SECONDS), "the Kotlin holder should acquire the lock")

                repeat(writerCount) { index ->
                    executor.execute {
                        try {
                            start.await(10, TimeUnit.SECONDS)
                            UserAnnotationsFile.add(harness.root, annotation("kotlin-$index", 100 + index))
                        } catch (error: Throwable) {
                            errors.add(error)
                        }
                    }
                }

                Files.writeString(gate, "go\n")
                awaitAnyFile(conflictDir, "nvim-")
                start.countDown()
                awaitAnyFile(conflictDir, "kotlin-")
                holderRelease.countDown()
                holder.join(10_000)
                assertFalse(holder.isAlive, "the Kotlin holder should finish")
                assertTrue(holderErrors.isEmpty(), holderErrors.joinToString())

                spawned.forEach { item -> awaitProcess(item) }
                executor.shutdown()
                assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS), "Kotlin writers should finish")
                assertTrue(errors.isEmpty(), errors.joinToString())
            } finally {
                UserAnnotationsFile.lockObserver = null
                start.countDown()
                holderRelease.countDown()
                if (holderStarted) holder.join(10_000)
                executor.shutdownNow()
                executor.awaitTermination(10, TimeUnit.SECONDS)
                harness.processes.terminateAll()
            }

            val intervals = readIntervals(intervalDir)
            assertTrue(intervals.any { it.writer == "kotlin" }, "the Kotlin writer produced no lock interval")
            assertTrue(intervals.any { it.writer.startsWith("nvim-") }, "Neovim produced no lock interval")
            val overlaps = findOverlaps(intervals)
            assertTrue(overlaps.isEmpty(), overlaps.joinToString("\n"))
            assertIntervalValidatorRejectsOverlap()
            val conflicts =
                Files.list(conflictDir).use { stream ->
                    stream.map { it.fileName.toString() }.toList()
                }
            assertTrue(conflicts.any { it.startsWith("nvim-") }, "Neovim did not observe a lock conflict: $conflicts")
            assertTrue(conflicts.any { it.startsWith("kotlin-") }, "Kotlin did not observe a lock conflict: $conflicts")

            val expected = setOf("nvim-a", "nvim-b", "nvim-c") + (0 until writerCount).map { "kotlin-$it" }
            assertEquals(expected, notes(harness.root))
            assertFalse(Files.exists(lockPath(harness.root)), "all writers should release the shared lock")
        }
    }

    @Test
    fun failurePathTerminatesSpawnedNeovimBeforeRootCleanup() {
        newHarness("cleanup").use { harness ->
            val file = writeFixture(harness.root, defaultLines())
            val readyDir = Files.createDirectories(harness.root.resolve("ready"))
            var failureObserved = false
            try {
                val spawned =
                    harness.processes.spawn(
                        harness,
                        file,
                        mode = "annotate",
                        line = 1,
                        intent = "PURE",
                        note = "never-written",
                        ready = readyDir.resolve("cleanup"),
                        gate = harness.root.resolve("missing-gate"),
                    )
                awaitFile(readyDir.resolve("cleanup"))
                assertTrue(spawned.process.isAlive, "the cleanup control process should be alive")
                failureObserved =
                    runCatching {
                        awaitFile(harness.root.resolve("missing-marker"), timeoutMillis = 50)
                    }.isFailure
            } finally {
                harness.processes.terminateAll()
            }

            assertTrue(failureObserved, "the cleanup control should observe an early failure")
            assertTrue(
                harness.processes.snapshot().none { it.process.isAlive },
                "spawned Neovim processes must terminate before the root is removed",
            )
            assertFalse(
                Files.exists(lockPath(harness.root)),
                "the gate-only Neovim process should not take the lock",
            )
        }
    }

    private fun newHarness(name: String): Harness {
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
        Files.writeString(script, LUA_SCRIPT)
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

    private fun ProcessTracker.spawn(
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

    private fun awaitFile(
        path: Path,
        timeoutMillis: Long = 10_000,
    ) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        while (!Files.exists(path)) {
            if (System.nanoTime() > deadline) fail("timed out waiting for $path")
            Thread.sleep(5)
        }
    }

    private fun awaitAnyFile(
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

    private fun awaitProcess(spawned: Spawned) {
        if (!spawned.process.waitFor(30, TimeUnit.SECONDS)) {
            spawned.process.destroyForcibly()
            spawned.process.waitFor(5, TimeUnit.SECONDS)
            fail("nvim timed out\n${readLog(spawned.log)}")
        }
        assertEquals(0, spawned.process.exitValue(), "nvim failed\n${readLog(spawned.log)}")
    }

    private fun readLog(log: Path): String = runCatching { Files.readString(log) }.getOrElse { "<no log: ${it.message}>" }

    private fun writeFixture(
        root: Path,
        lines: List<String>,
    ): Path {
        val file = root.resolve("Sample.kt")
        Files.writeString(file, lines.joinToString("\n", postfix = "\n"))
        return file
    }

    private fun defaultLines(): List<String> = (0..8).map { index -> "fun op$index(): String = \"$index\"" }

    private fun annotation(
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

    private fun notes(root: Path): Set<String> = UserAnnotationsFile.load(root).mapNotNull { it.note }.toSet()

    private fun writeDeadLock(
        root: Path,
        pid: Long,
        start: String,
        token: String = "d".repeat(64),
    ) {
        val lock = lockPath(root)
        Files.createDirectories(lock)
        Files.writeString(lock.resolve("owner"), "token=$token\npid=$pid\nstart=$start\n")
    }

    private fun waitForStartMarker(pid: Long): String {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            val marker = UserAnnotationsFile.processStartMarkerForTest(pid)
            if (marker.isNotEmpty()) return marker
            Thread.sleep(5)
        }
        return ""
    }

    private fun lockPath(root: Path): Path =
        root
            .resolve(UserAnnotationsFile.DIRECTORY)
            .resolve("${UserAnnotationsFile.FILE_NAME}.lock")

    private fun assertIntervalValidatorRejectsOverlap() {
        val directory = Files.createTempDirectory("cint-cross-writer-overlap-control")
        try {
            val firstToken = "a".repeat(64)
            val secondToken = "b".repeat(64)
            writeIntervalRecord(directory, "control-a", firstToken, "enter", 1, 100)
            writeIntervalRecord(directory, "control-a", firstToken, "exit", 1, 300)
            writeIntervalRecord(directory, "control-b", secondToken, "enter", 2, 200)
            writeIntervalRecord(directory, "control-b", secondToken, "exit", 2, 400)

            val intervals = readIntervals(directory)
            assertEquals(2, intervals.size)
            assertTrue(findOverlaps(intervals).isNotEmpty(), "the overlap validator must reject a forced overlap")
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    private data class Harness(
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

    private data class Spawned(
        val process: Process,
        val log: Path,
    )

    private data class Worker(
        val id: String,
        val line: Int,
        val mode: String,
    )

    private class ProcessTracker {
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

    private class FileLockObserver(
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

    companion object {
        private val LUA_SCRIPT =
            """
            vim.opt.runtimepath:prepend(vim.env.CINT_CROSS_REPO_ROOT .. "/nvim-renderer")
            vim.opt.swapfile = false
            vim.opt.shadafile = "NONE"

            local cint = require("cint")
            require("cint.commands").register(cint)
            local annotations = require("cint.ops.annotations")
            local state = require("cint.core.state")

            local function required(name)
              local value = vim.env[name]
              assert(value and value ~= "", "missing " .. name)
              return value
            end

            local function optional(name)
              local value = vim.env[name]
              if value == nil or value == "" then
                return nil
              end
              return value
            end

            local function write(path, text)
              local file = assert(io.open(path, "w"))
              file:write(text)
              file:close()
            end

            local root = required("CINT_CROSS_ROOT")
            local file = required("CINT_CROSS_FILE")
            local mode = required("CINT_CROSS_MODE")
            local line = tonumber(required("CINT_CROSS_LINE"))
            local col = tonumber(required("CINT_CROSS_COL"))
            local intent = optional("CINT_CROSS_INTENT")
            local note = optional("CINT_CROSS_NOTE")
            local started = optional("CINT_CROSS_STARTED")
            local ready = optional("CINT_CROSS_READY")
            local gate = optional("CINT_CROSS_GATE")
            local blocked = optional("CINT_CROSS_BLOCKED")
            local interval_dir = optional("CINT_CROSS_INTERVAL_DIR")
            local conflict_dir = optional("CINT_CROSS_CONFLICT_DIR")
            local writer_id = optional("CINT_CROSS_WRITER_ID")
            local current_token = nil
            local conflict_written = false
            local blocked_written = false

            local function interval_record(token, phase)
              assert(interval_dir and writer_id, "interval environment is incomplete")
              local target = interval_dir .. "/" .. writer_id .. "-" .. token .. "." .. phase
              local temporary = target .. ".tmp-" .. tostring(vim.uv.os_getpid())
              write(temporary, table.concat({
                "writer=" .. writer_id,
                "pid=" .. tostring(vim.uv.os_getpid()),
                "token=" .. token,
                "phase=" .. phase,
                "nanos=" .. string.format("%.0f", vim.uv.hrtime()),
              }, "\n") .. "\n")
              local ok, err = vim.uv.fs_rename(temporary, target)
              assert(ok, tostring(err))
            end

            local function interval_enter(lock, token)
              current_token = token
              interval_record(token, "enter")
            end

            local function interval_exit()
              if current_token == nil then
                return
              end
              interval_record(current_token, "exit")
              current_token = nil
            end

            local function on_conflict(path)
              if conflict_dir and writer_id and not conflict_written then
                conflict_written = true
                write(conflict_dir .. "/" .. writer_id .. ".conflict", "1\n")
              end
              if blocked and not blocked_written then
                blocked_written = true
                write(blocked, "blocked\n")
              end
            end

            local function has_note(buf, expected)
              for _, record in ipairs(annotations.annotations(buf)) do
                if record.note == expected then
                  return true
                end
              end
              return false
            end

            local function run()
              cint.setup({ root = root, auto_render = false })
              state.live_failed = true
              vim.cmd.edit(vim.fn.fnameescape(file))
              local buf = vim.api.nvim_get_current_buf()
              vim.api.nvim_win_set_cursor(0, { line, col })

              local hooks = {}
              if interval_dir and writer_id then
                hooks.after_acquire = interval_enter
                hooks.before_release = interval_exit
              end
              if conflict_dir or blocked then
                hooks.before_conflict = on_conflict
              end
              if next(hooks) ~= nil then
                annotations._set_lock_hooks(hooks)
              end

              if started then
                write(started, "started\n")
              end
              if ready then
                write(ready, "ready\n")
              end
              if gate then
                assert(
                  vim.wait(15000, function()
                    return vim.uv.fs_stat(gate) ~= nil
                  end, 5),
                  "timed out waiting for the cross-writer gate"
                )
              end

              if mode == "annotate" then
                vim.cmd("CintAnnotate " .. intent .. " " .. note)
                local persisted = nil
                for _, record in ipairs(annotations.annotations(buf)) do
                  if record.note == note then
                    persisted = record
                  end
                end
                assert(persisted, "CintAnnotate did not persist the expected note")
                assert(vim.tbl_contains(persisted.intents, intent), "CintAnnotate persisted the wrong intent")
              elseif mode == "annotate-unannotate" then
                vim.cmd("CintAnnotate " .. intent .. " " .. note)
                local before = annotations.annotations(buf)
                local persisted = false
                for _, record in ipairs(before) do
                  if record.note == note then
                    persisted = true
                  end
                end
                assert(persisted, "CintAnnotate did not persist the expected note")
                vim.cmd("CintUnannotate")
                assert(not has_note(buf, note), "CintUnannotate did not remove the annotated record")
              elseif mode == "unannotate" then
                assert(note, "CintUnannotate needs an expected note")
                assert(has_note(buf, note), "CintUnannotate needs a record to remove")
                vim.cmd("CintUnannotate")
                assert(not has_note(buf, note), "CintUnannotate did not remove the expected record")
              else
                error("unknown mode " .. mode)
              end
            end

            local ok, err = xpcall(run, debug.traceback)
            if not ok then
              io.stderr:write(tostring(err) .. "\n")
              vim.cmd("cquit 1")
            end
            vim.cmd("qa!")
            """.trimIndent()
    }
}

private data class IntervalRecord(
    val writer: String,
    val pid: Long,
    val token: String,
    val phase: String,
    val nanos: Long,
) {
    val id: String
        get() = "$writer:$token"
}

private data class LockInterval(
    val writer: String,
    val pid: Long,
    val token: String,
    val enterNanos: Long,
    val exitNanos: Long,
) {
    val id: String
        get() = "$writer:$token"
}

private fun writeIntervalRecord(
    directory: Path,
    writer: String,
    token: String,
    phase: String,
    pid: Long,
    nanos: Long,
) {
    val target = directory.resolve("$writer-$token.$phase")
    val temporary = Files.createTempFile(directory, "$writer-$token-$phase-", ".tmp")
    Files.writeString(
        temporary,
        "writer=$writer\npid=$pid\ntoken=$token\nphase=$phase\nnanos=$nanos\n",
    )
    try {
        Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } catch (_: AtomicMoveNotSupportedException) {
        Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
    }
}

private fun readIntervals(directory: Path): List<LockInterval> {
    val records =
        Files.list(directory).use { stream ->
            stream
                .filter { path ->
                    val name = path.fileName.toString()
                    name.endsWith(".enter") || name.endsWith(".exit")
                }.map { path -> readIntervalRecord(path) }
                .toList()
        }
    return records
        .groupBy { record -> record.id }
        .map { (id, entries) ->
            val enters = entries.filter { record -> record.phase == "enter" }
            val exits = entries.filter { record -> record.phase == "exit" }
            assertEquals(1, enters.size, "expected one enter record for $id: $entries")
            assertEquals(1, exits.size, "expected one exit record for $id: $entries")
            val enter = enters.single()
            val exit = exits.single()
            assertEquals(enter.writer, exit.writer, "writer mismatch for $id")
            assertEquals(enter.pid, exit.pid, "pid mismatch for $id")
            assertEquals(enter.token, exit.token, "token mismatch for $id")
            assertTrue(exit.nanos >= enter.nanos, "exit precedes enter for $id")
            LockInterval(enter.writer, enter.pid, enter.token, enter.nanos, exit.nanos)
        }.sortedBy { interval -> interval.enterNanos }
}

private fun readIntervalRecord(path: Path): IntervalRecord {
    val fields =
        Files
            .readAllLines(path)
            .mapNotNull { line ->
                val separator = line.indexOf('=')
                if (separator <= 0) {
                    null
                } else {
                    line.substring(0, separator) to line.substring(separator + 1)
                }
            }.toMap()
    val record =
        IntervalRecord(
            writer = fields.getValue("writer"),
            pid = fields.getValue("pid").toLong(),
            token = fields.getValue("token"),
            phase = fields.getValue("phase"),
            nanos = fields.getValue("nanos").toLong(),
        )
    assertTrue(record.token.matches(Regex("^[0-9a-f]{64}$")), "invalid lock token in $path: ${record.token}")
    assertTrue(record.pid > 0, "invalid pid in $path: ${record.pid}")
    return record
}

private fun findOverlaps(intervals: List<LockInterval>): List<String> {
    val overlaps = mutableListOf<String>()
    for (leftIndex in intervals.indices) {
        for (rightIndex in leftIndex + 1 until intervals.size) {
            val left = intervals[leftIndex]
            val right = intervals[rightIndex]
            if (left.enterNanos < right.exitNanos && right.enterNanos < left.exitNanos) {
                overlaps.add(
                    "${left.id} [${left.enterNanos},${left.exitNanos}] overlaps " +
                        "${right.id} [${right.enterNanos},${right.exitNanos}]",
                )
            }
        }
    }
    return overlaps
}
