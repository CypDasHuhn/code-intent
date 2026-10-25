package dev.cint.v2.spike

import kotlinx.serialization.json.Json
import kotlin.system.exitProcess

fun main() {
    println("=== Kotlin/Native Spike Demo (JVM) ===")
    var failures = 0

    fun check(
        name: String,
        condition: Boolean,
        detail: String = "",
    ) {
        if (condition) {
            println("PASS: $name")
        } else {
            failures += 1
            println("FAIL: $name${if (detail.isEmpty()) "" else " ($detail)"}")
        }
    }

    println()
    println("--- Test 1: Tree-sitter ---")
    println("NOTE: tree-sitter C interop is native-only; the JVM SyntaxEngine returns DependencyUnavailable.")
    println("NOTE: real parsing is proven by native-spike.kexe, not by this JVM run.")

    println()
    println("--- Test 2: JSON Round-trip ---")
    try {
        val sample = """{"name":"spike","value":42,"nested":{"array":[1,2,3],"ok":true}}"""
        val result = createJsonCodec().roundTrip(sample)
        val equivalent =
            result.success &&
                runCatching {
                    Json.parseToJsonElement(result.decoded) == Json.parseToJsonElement(sample)
                }.getOrDefault(false)
        println("encoded=${result.encoded}")
        println("decoded=${result.decoded}")
        check("JSON round trip preserves document", equivalent, result.error ?: "decoded document differs")
    } catch (t: Throwable) {
        check("JSON round trip preserves document", false, t.message ?: t.toString())
    }

    println()
    println("--- Test 3: Child Process Spawn ---")
    try {
        val result = createProcessSpawner().spawn(listOf("/bin/sh", "-c", "printf child-ok"))
        println("exit=${result.exitCode} stdout='${result.stdout}' stderr='${result.stderr}'")
        check(
            "JVM spawn captures real stdout and exit code",
            result.exitCode == 0 && result.stdout == "child-ok",
            result.error ?: "stdout='${result.stdout}'",
        )
    } catch (t: Throwable) {
        check("JVM spawn captures real stdout and exit code", false, t.message ?: t.toString())
    }

    println()
    println("--- Test 4: HTTP/TLS (JVM-only) ---")
    testHttpClient()

    println()
    println("--- Test 5: Unix Domain Socket (JVM client; no listener started) ---")
    testUnixSocket()

    println()
    if (failures == 0) {
        println("JVM SPIKE PASSED (local checks)")
    } else {
        println("JVM SPIKE FAILED: $failures check(s)")
        exitProcess(1)
    }
}

fun testHttpClient() {
    val client = createHttpClient()

    println("Testing HTTP GET to httpbin.org...")
    val httpResult = client.get("http://httpbin.org/get")
    println("URL: ${httpResult.url}")
    println("Method: ${httpResult.method}")
    println("Status: ${httpResult.statusCode}")
    println("TLS used: ${httpResult.tlsUsed}")
    println("Success: ${httpResult.success}")
    if (httpResult.error != null) {
        println("Error: ${httpResult.error}")
    } else {
        println("Response body (first 200 chars): ${httpResult.responseBody?.take(200)}...")
    }

    println()
    println("Testing HTTPS GET to httpbin.org...")
    val httpsResult = client.get("https://httpbin.org/get")
    println("URL: ${httpsResult.url}")
    println("Method: ${httpsResult.method}")
    println("Status: ${httpsResult.statusCode}")
    println("TLS used: ${httpsResult.tlsUsed}")
    println("Success: ${httpsResult.success}")
    if (httpsResult.error != null) {
        println("Error: ${httpsResult.error}")
    } else {
        println("Response body (first 200 chars): ${httpsResult.responseBody?.take(200)}...")
    }

    println("NOTE: HTTP/TLS is JVM-only in this spike; failures here are not counted as acceptance failures")
}

fun testUnixSocket() {
    val client = createUnixSocketClient()
    val socketPath = "/tmp/kotlin-spike-test.sock"
    val message = "Hello from Kotlin/JVM!"

    println("Testing Unix domain socket without a listener...")
    val result = client.roundTrip(socketPath, message)
    println("Socket path: ${result.socketPath}")
    println("Message sent: ${result.messageSent}")
    println("Message received: ${result.messageReceived}")
    println("Success: ${result.success}")
    if (result.error != null) {
        println("Error: ${result.error}")
    }
    println("NOTE: native round trip with a real listener is proven by native-spike.kexe")
}
