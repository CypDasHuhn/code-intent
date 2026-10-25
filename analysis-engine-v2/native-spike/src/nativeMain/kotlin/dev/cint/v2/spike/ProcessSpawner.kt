package dev.cint.v2.spike

import kotlinx.cinterop.ExperimentalForeignApi

@OptIn(ExperimentalForeignApi::class)
actual fun createProcessSpawner(): ProcessSpawner = NativeProcessSpawner()

@OptIn(ExperimentalForeignApi::class)
private class NativeProcessSpawner : ProcessSpawner {
    override fun spawn(command: List<String>, workingDir: String?): ProcessResult {
        // Stub implementation for native spike
        return ProcessResult(
            command = command,
            exitCode = 0,
            stdout = "Native process spawn stub",
            stderr = "",
            success = true,
            error = null
        )
    }

    override fun spawn(command: List<String>): ProcessResult {
        return spawn(command, null)
    }
}