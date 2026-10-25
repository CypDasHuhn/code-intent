package dev.cint.v2.spike

import java.io.File
import java.lang.ProcessBuilder

actual fun createProcessSpawner(): ProcessSpawner = JvmProcessSpawner()

private class JvmProcessSpawner : ProcessSpawner {
    override fun spawn(command: List<String>, workingDir: String?): ProcessResult {
        if (command.isEmpty()) {
            return ProcessResult(
                command = command,
                exitCode = -1,
                stdout = "",
                stderr = "Empty command",
                success = false,
                error = "Empty command"
            )
        }

        return try {
            val pb = ProcessBuilder(command)
            workingDir?.let { pb.directory(File(it)) }
            pb.redirectErrorStream(false)

            val process = pb.start()
            val stdout = process.inputStream.readAllBytes().decodeToString()
            val stderr = process.errorStream.readAllBytes().decodeToString()
            val exitCode = process.waitFor()

            ProcessResult(
                command = command,
                exitCode = exitCode,
                stdout = stdout,
                stderr = stderr,
                success = exitCode == 0,
                error = if (exitCode != 0) "Process exited with code $exitCode" else null
            )
        } catch (e: Exception) {
            ProcessResult(
                command = command,
                exitCode = -1,
                stdout = "",
                stderr = e.message ?: "Unknown error",
                success = false,
                error = e.message
            )
        }
    }

    override fun spawn(command: List<String>): ProcessResult {
        return spawn(command, null)
    }
}