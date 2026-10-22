package dev.cint.core.user.support

import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.test.assertEquals
import kotlin.test.assertTrue

internal data class IntervalRecord(
    val writer: String,
    val pid: Long,
    val token: String,
    val phase: String,
    val nanos: Long,
) {
    val id: String
        get() = "$writer:$token"
}

internal data class LockInterval(
    val writer: String,
    val pid: Long,
    val token: String,
    val enterNanos: Long,
    val exitNanos: Long,
) {
    val id: String
        get() = "$writer:$token"
}

internal fun writeIntervalRecord(
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

internal fun readIntervals(directory: Path): List<LockInterval> {
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

internal fun findOverlaps(intervals: List<LockInterval>): List<String> {
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

internal fun assertIntervalValidatorRejectsOverlap() {
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
