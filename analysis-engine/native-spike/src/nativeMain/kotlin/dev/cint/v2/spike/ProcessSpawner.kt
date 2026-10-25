package dev.cint.v2.spike

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.cstr
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.set
import kotlinx.cinterop.value
import platform.posix.STDERR_FILENO
import platform.posix.STDOUT_FILENO
import platform.posix._exit
import platform.posix.chdir
import platform.posix.close
import platform.posix.dup2
import platform.posix.execvp
import platform.posix.fork
import platform.posix.pipe
import platform.posix.read
import platform.posix.waitpid

@OptIn(ExperimentalForeignApi::class)
actual fun createProcessSpawner(): ProcessSpawner = NativeProcessSpawner()

@OptIn(ExperimentalForeignApi::class)
private class NativeProcessSpawner : ProcessSpawner {
    override fun spawn(
        command: List<String>,
        workingDir: String?,
    ): ProcessResult {
        if (command.isEmpty()) {
            return failure(command, "Empty command")
        }

        return memScoped {
            val outPipe = allocArray<IntVar>(2)
            val errPipe = allocArray<IntVar>(2)
            if (pipe(outPipe) != 0) return@memScoped failure(command, "pipe(stdout) failed")
            if (pipe(errPipe) != 0) {
                close(outPipe[0])
                close(outPipe[1])
                return@memScoped failure(command, "pipe(stderr) failed")
            }

            val argv = allocArray<CPointerVar<ByteVar>>(command.size + 1)
            val argvStorage = command.map { it.cstr }
            for (index in command.indices) {
                argv[index] = argvStorage[index].ptr
            }
            argv[command.size] = null

            val pid = fork()
            if (pid == 0) {
                dup2(outPipe[1], STDOUT_FILENO)
                dup2(errPipe[1], STDERR_FILENO)
                close(outPipe[0])
                close(outPipe[1])
                close(errPipe[0])
                close(errPipe[1])
                if (workingDir != null && chdir(workingDir) != 0) {
                    _exit(126)
                }
                execvp(command[0], argv)
                _exit(127)
            }

            close(outPipe[1])
            close(errPipe[1])

            if (pid < 0) {
                close(outPipe[0])
                close(errPipe[0])
                return@memScoped failure(command, "fork failed")
            }

            val stdout = readAll(outPipe[0])
            val stderr = readAll(errPipe[0])
            close(outPipe[0])
            close(errPipe[0])

            val status = alloc<IntVar>()
            waitpid(pid, status.ptr, 0)
            val raw = status.value
            val exitCode = if ((raw and 0x7f) == 0) (raw shr 8) and 0xff else -1

            ProcessResult(
                command = command,
                exitCode = exitCode,
                stdout = stdout,
                stderr = stderr,
                success = exitCode == 0,
                error = if (exitCode == 0) null else "Process exited with code $exitCode",
            )
        }
    }

    override fun spawn(command: List<String>): ProcessResult = spawn(command, null)

    private fun readAll(fd: Int): String =
        memScoped {
            val buffer = allocArray<ByteVar>(4096)
            val out = StringBuilder()
            while (true) {
                val count = read(fd, buffer, 4096uL)
                if (count <= 0L) break
                out.append(buffer.readBytes(count.toInt()).decodeToString())
            }
            out.toString()
        }

    private fun failure(
        command: List<String>,
        message: String,
    ): ProcessResult =
        ProcessResult(
            command = command,
            exitCode = -1,
            stdout = "",
            stderr = message,
            success = false,
            error = message,
        )
}
