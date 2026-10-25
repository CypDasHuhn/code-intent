package dev.cint.v2.spike

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.MemScope
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.refTo
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import kotlinx.cinterop.sizeOf
import platform.linux.sockaddr_un
import platform.posix.AF_UNIX
import platform.posix.SIGTERM
import platform.posix.SOCK_STREAM
import platform.posix._exit
import platform.posix.close
import platform.posix.fork
import platform.posix.kill
import platform.posix.sockaddr
import platform.posix.socket
import platform.posix.unlink
import platform.posix.usleep
import platform.posix.waitpid

@OptIn(ExperimentalForeignApi::class)
actual fun createUnixSocketClient(): UnixSocketClient = NativeUnixSocketClient()

@OptIn(ExperimentalForeignApi::class)
private class NativeUnixSocketClient : UnixSocketClient {
    private var fd: Int = -1

    override fun connect(socketPath: String): Boolean {
        val socketFd = socket(AF_UNIX, SOCK_STREAM, 0)
        if (socketFd < 0) return false

        val connected =
            memScoped {
                val address = allocAddress(socketPath) ?: return@memScoped false
                platform.posix.connect(socketFd, address.ptr.reinterpret<sockaddr>(), sizeOf<sockaddr_un>().toUInt()) == 0
            }

        if (!connected) {
            close(socketFd)
            return false
        }
        fd = socketFd
        return true
    }

    override fun send(message: String): Boolean {
        if (fd < 0) return false
        val bytes = message.encodeToByteArray()
        return memScoped {
            val buffer = allocArray<ByteVar>(bytes.size + 1)
            bytes.forEachIndexed { index, value -> buffer[index] = value }
            buffer[bytes.size] = 0
            platform.posix.send(fd, buffer, bytes.size.toULong(), 0) == bytes.size.toLong()
        }
    }

    override fun receive(): String? {
        if (fd < 0) return null
        return memScoped {
            val buffer = allocArray<ByteVar>(4096)
            val count = platform.posix.recv(fd, buffer, 4096uL, 0)
            if (count <= 0L) null else buffer.readBytes(count.toInt()).decodeToString()
        }
    }

    override fun close() {
        if (fd >= 0) {
            platform.posix.close(fd)
            fd = -1
        }
    }

    override fun roundTrip(
        socketPath: String,
        message: String,
    ): UnixSocketResult {
        val serverFd = socket(AF_UNIX, SOCK_STREAM, 0)
        if (serverFd < 0) {
            return result(socketPath, message, null, "socket(AF_UNIX) failed")
        }

        platform.posix.unlink(socketPath)

        return memScoped {
            val address = allocAddress(socketPath)
            if (address == null) {
                close(serverFd)
                return@memScoped result(socketPath, message, null, "socket path is empty or too long")
            }
            val addressLength = sizeOf<sockaddr_un>().toUInt()
            if (platform.posix.bind(serverFd, address.ptr.reinterpret<sockaddr>(), addressLength) != 0) {
                close(serverFd)
                platform.posix.unlink(socketPath)
                return@memScoped result(socketPath, message, null, "bind failed")
            }
            if (platform.posix.listen(serverFd, 1) != 0) {
                close(serverFd)
                platform.posix.unlink(socketPath)
                return@memScoped result(socketPath, message, null, "listen failed")
            }

            val reply = "pong"
            val replyBytes = reply.encodeToByteArray()
            val replyBuffer = allocArray<ByteVar>(replyBytes.size + 1)
            replyBytes.forEachIndexed { index, value -> replyBuffer[index] = value }
            replyBuffer[replyBytes.size] = 0
            val receiveBuffer = allocArray<ByteVar>(4096)

            val serverPid = fork()
            if (serverPid == 0) {
                val clientFd = platform.posix.accept(serverFd, null, null)
                if (clientFd >= 0) {
                    if (platform.posix.read(clientFd, receiveBuffer, 4096uL) > 0L) {
                        platform.posix.write(clientFd, replyBuffer, replyBytes.size.toULong())
                    }
                    platform.posix.close(clientFd)
                }
                platform.posix.close(serverFd)
                platform.posix.unlink(socketPath)
                _exit(0)
            }

            platform.posix.close(serverFd)

            if (serverPid < 0) {
                platform.posix.unlink(socketPath)
                return@memScoped result(socketPath, message, null, "fork failed")
            }

            val clientFd = socket(AF_UNIX, SOCK_STREAM, 0)
            if (clientFd < 0) {
                kill(serverPid, SIGTERM)
                waitpid(serverPid, null, 0)
                platform.posix.unlink(socketPath)
                return@memScoped result(socketPath, message, null, "client socket(AF_UNIX) failed")
            }

            var connected = false
            for (attempt in 0 until 200) {
                if (platform.posix.connect(clientFd, address.ptr.reinterpret<sockaddr>(), addressLength) == 0) {
                    connected = true
                    break
                }
                usleep(5_000u)
            }

            if (!connected) {
                platform.posix.close(clientFd)
                kill(serverPid, SIGTERM)
                waitpid(serverPid, null, 0)
                platform.posix.unlink(socketPath)
                return@memScoped result(socketPath, message, null, "connect failed")
            }

            val messageBytes = message.encodeToByteArray()
            val sent = platform.posix.send(clientFd, messageBytes.refTo(0), messageBytes.size.toULong(), 0)
            val received =
                memScoped {
                    val buffer = allocArray<ByteVar>(4096)
                    val count = platform.posix.recv(clientFd, buffer, 4096uL, 0)
                    if (count <= 0L) null else buffer.readBytes(count.toInt()).decodeToString()
                }
            platform.posix.close(clientFd)

            waitpid(serverPid, null, 0)
            platform.posix.unlink(socketPath)

            if (sent != messageBytes.size.toLong()) {
                return@memScoped result(socketPath, message, received, "send wrote $sent of ${messageBytes.size} bytes")
            }
            result(socketPath, message, received, if (received == null) "no reply received" else null)
        }
    }

    private fun MemScope.allocAddress(socketPath: String): sockaddr_un? {
        val pathBytes = socketPath.encodeToByteArray()
        if (pathBytes.isEmpty() || pathBytes.size >= 108) return null
        val address = alloc<sockaddr_un>()
        address.sun_family = AF_UNIX.toUShort()
        pathBytes.forEachIndexed { index, value -> address.sun_path[index] = value }
        address.sun_path[pathBytes.size] = 0
        return address
    }

    private fun result(
        socketPath: String,
        message: String,
        received: String?,
        error: String?,
    ): UnixSocketResult =
        UnixSocketResult(
            socketPath = socketPath,
            messageSent = message,
            messageReceived = received ?: "",
            success = received != null && error == null,
            error = error,
        )
}
