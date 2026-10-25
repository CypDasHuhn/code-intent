package dev.cint.v2.spike

import dev.cint.v2.syntax.treesitter.createSyntaxEngine
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.readBytes
import kotlinx.serialization.json.Json
import platform.posix.F_OK
import platform.posix.SEEK_END
import platform.posix.SEEK_SET
import platform.posix.access
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fread
import platform.posix.fseek
import platform.posix.ftell
import platform.posix.getpid
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    println("=== Kotlin/Native Platform Spike ===")
    var failures = 0

    fun check(name: String, condition: Boolean, detail: String = "") {
        if (condition) {
            println("PASS: $name")
        } else {
            failures += 1
            println("FAIL: $name${if (detail.isEmpty()) "" else " ($detail)"}")
        }
    }

    val fixture = resolveFixture(args)
    if (fixture == null) {
        println("FAIL: C fixture not found; pass its path as the first argument")
        exitProcess(2)
    }

    println()
    println("--- tree-sitter (tree-sitter-c, SyntaxEngine) ---")
    try {
        val result = createSyntaxEngine("c").parse(readFixture(fixture), previous = null)
        if (!result.isSuccess) {
            error("parse failed: ${result.errorOrNull}")
        }
        val tree = result.getOrThrow()
        val root = tree.getNode(tree.rootId)
        val nodeCount = tree.nodes.size
        val errorNodes = tree.nodes.values.count { it.kind == "ERROR" }
        println("fixture=$fixture")
        println("language=${tree.language} root=${root?.kind} nodes=$nodeCount errorNodes=$errorNodes")
        println("hash=${tree.contentHash.take(16)} version=${tree.version}")
        check(
            "tree-sitter parses C fixture",
            root?.kind == "translation_unit" && nodeCount > 10 && errorNodes == 0,
            "root=${root?.kind} nodes=$nodeCount errorNodes=$errorNodes"
        )
    } catch (t: Throwable) {
        check("tree-sitter parses C fixture", false, t.message ?: t.toString())
    }

    println()
    println("--- JSON round trip ---")
    try {
        val sample = """{"name":"spike","value":42,"nested":{"array":[1,2,3],"ok":true}}"""
        val roundTrip = createJsonCodec().roundTrip(sample)
        val equivalent = roundTrip.success && runCatching {
            Json.parseToJsonElement(roundTrip.decoded) == Json.parseToJsonElement(sample)
        }.getOrDefault(false)
        println("encoded=${roundTrip.encoded}")
        println("decoded=${roundTrip.decoded}")
        check("JSON round trip preserves document", equivalent, roundTrip.error ?: "decoded document differs")
    } catch (t: Throwable) {
        check("JSON round trip preserves document", false, t.message ?: t.toString())
    }

    println()
    println("--- child process spawn ---")
    try {
        val spawner = createProcessSpawner()
        val child = spawner.spawn(listOf("/bin/sh", "-c", "printf child-ok"))
        println("exit=${child.exitCode} stdout='${child.stdout}' stderr='${child.stderr}'")
        check(
            "spawn captures real stdout and exit code",
            child.exitCode == 0 && child.stdout == "child-ok",
            child.error ?: "stdout='${child.stdout}'"
        )
        val nonzero = spawner.spawn(listOf("/bin/sh", "-c", "exit 7"))
        println("nonzero exit=${nonzero.exitCode}")
        check("spawn reports non-zero exit code", nonzero.exitCode == 7, "exit=${nonzero.exitCode}")
    } catch (t: Throwable) {
        check("spawn captures real stdout and exit code", false, t.message ?: t.toString())
    }

    println()
    println("--- Unix domain socket round trip ---")
    try {
        val socketPath = "/tmp/cint-native-spike-${getpid()}.sock"
        val roundTrip = createUnixSocketClient().roundTrip(socketPath, "hello-native-socket")
        println("path=$socketPath sent='${roundTrip.messageSent}' received='${roundTrip.messageReceived}'")
        check(
            "Unix socket listener/client round trip",
            roundTrip.success && roundTrip.messageReceived == "pong",
            roundTrip.error ?: "unexpected reply"
        )
    } catch (t: Throwable) {
        check("Unix socket listener/client round trip", false, t.message ?: t.toString())
    }

    println()
    println("--- HTTP/TLS ---")
    val http = createHttpClient().get("https://example.com")
    println("native result: status=${http.statusCode} error=${http.error}")
    println("SKIP: native HTTP/TLS is out of scope for this spike; see analysis-engine-v2/docs/rewrite/native-spike.md")

    println()
    if (failures == 0) {
        println("SPIKE PASSED")
    } else {
        println("SPIKE FAILED: $failures required check(s)")
        exitProcess(1)
    }
}

private fun resolveFixture(args: Array<String>): String? {
    val candidates = buildList {
        args.firstOrNull()?.let(::add)
        add("native-spike/fixtures/sample.c")
        add("analysis-engine-v2/native-spike/fixtures/sample.c")
        add("../native-spike/fixtures/sample.c")
        add("fixtures/sample.c")
    }
    return candidates.firstOrNull { access(it, F_OK) == 0 }
}

@OptIn(ExperimentalForeignApi::class)
private fun readFixture(path: String): ByteArray {
    val file = fopen(path, "rb") ?: error("cannot open fixture '$path'")
    try {
        check(fseek(file, 0L, SEEK_END) == 0) { "cannot seek fixture '$path'" }
        val size = ftell(file)
        check(size >= 0) { "cannot size fixture '$path'" }
        check(fseek(file, 0L, SEEK_SET) == 0) { "cannot rewind fixture '$path'" }
        return memScoped {
            val buffer = allocArray<ByteVar>(size.toInt().coerceAtLeast(1))
            val count = fread(buffer, 1uL, size.toULong(), file)
            buffer.readBytes(count.toInt())
        }
    } finally {
        fclose(file)
    }
}
