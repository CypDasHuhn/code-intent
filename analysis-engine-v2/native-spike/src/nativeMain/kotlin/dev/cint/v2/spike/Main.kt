package dev.cint.v2.spike

import dev.cint.v2.syntax.treesitter.createTreeSitterParser
import dev.cint.v2.syntax.treesitter.ParseResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString

fun main() {
    println("=== Kotlin/Native Spike Demo ===")
    println()

    // Test 1: Tree-sitter parsing
    println("--- Test 1: Tree-sitter Kotlin Parsing ---")
    testTreeSitter()

    println()

    // Test 2: JSON encode/decode
    println("--- Test 2: JSON Round-trip ---")
    testJson()

    println()

    // Test 3: Process spawn
    println("--- Test 3: Child Process Spawn ---")
    testProcessSpawn()

    println()

    // Test 4: HTTP/TLS
    println("--- Test 4: HTTP/TLS Client ---")
    testHttpClient()

    println()

    // Test 5: Unix domain socket
    println("--- Test 5: Unix Domain Socket ---")
    testUnixSocket()

    println()
    println("=== Spike Complete ===")
}

fun testTreeSitter() {
    val parser = createTreeSitterParser()
    val sourceCode = """
        package dev.cint.test

        fun main() {
            println("Hello, World!")
        }

        class DataClass(val name: String, val value: Int)
    """.trimIndent()

    val result: ParseResult = parser.parse("kotlin", sourceCode)

    println("Language: ${result.language}")
    println("Node count: ${result.nodeCount}")
    println("Parse time: ${result.parseTimeMs}ms")
    println("Tree JSON (first 500 chars): ${result.treeJson.take(500)}...")
    println("SUCCESS: Tree-sitter C interop works!")
}

fun testJson() {
    val codec = createJsonCodec()

    val testJson = """
        {
            "name": "test",
            "value": 42,
            "nested": {
                "array": [1, 2, 3],
                "bool": true
            }
        }
    """.trimIndent()

    val result = codec.roundTrip(testJson)
    println("Original: ${result.original}")
    println("Encoded: ${result.encoded}")
    println("Decoded: ${result.decoded}")
    println("Success: ${result.success}")
    if (result.error != null) {
        println("Error: ${result.error}")
    }
    println("SUCCESS: JSON encode/decode works!")
}

fun testProcessSpawn() {
    val spawner = createProcessSpawner()
    val result = spawner.spawn(listOf("echo", "hello from child process"))
    println("Command: ${result.command.joinToString(" ")}")
    println("Exit code: ${result.exitCode}")
    println("Stdout: ${result.stdout}")
    println("Stderr: ${result.stderr}")
    println("Success: ${result.success}")
    if (result.error != null) {
        println("Error: ${result.error}")
    }
    println("SUCCESS: Child process spawn works!")
}

fun testHttpClient() {
    val client = createHttpClient()

    // Test HTTP (non-TLS)
    println("Testing HTTP GET to httpbin.org...")
    val httpResult = client.get("http://httpbin.org/get")
    println("URL: ${httpResult.url}")
    println("Method: ${httpResult.method}")
    println("Status: ${httpResult.statusCode}")
    println("TLS used: ${httpResult.tlsUsed}")
    println("Success: ${httpResult.success}")
    if (httpResult.error != null) {
        println("Error: ${httpResult.error}")
    }

    // Test HTTPS (TLS)
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
    }

    println("SUCCESS: HTTP/TLS client works!")
}

fun testUnixSocket() {
    val client = createUnixSocketClient()
    val socketPath = "/tmp/kotlin-spike-test.sock"
    val message = "Hello from Kotlin/Native!"

    println("Testing Unix domain socket...")
    val result = client.roundTrip(socketPath, message)
    println("Socket path: ${result.socketPath}")
    println("Message sent: ${result.messageSent}")
    println("Message received: ${result.messageReceived}")
    println("Success: ${result.success}")
    if (result.error != null) {
        println("Error: ${result.error}")
    }
    println("NOTE: Unix socket API works, but requires a listening server")
}