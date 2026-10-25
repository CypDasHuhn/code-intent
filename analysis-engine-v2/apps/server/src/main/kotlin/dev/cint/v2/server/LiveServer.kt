package dev.cint.v2.server

import dev.cint.contracts.ports.Runtime
import dev.cint.contracts.util.ContractError
import dev.cint.ipc.NdjsonCodec
import java.io.IOException
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal class LiveServer(
    private val runtime: Runtime,
    private val options: ServerOptions,
    private val codec: NdjsonCodec = NdjsonCodec(),
    private val gate: RuntimeGate = RuntimeGate(),
) {
    private val sessions = ConcurrentHashMap.newKeySet<ClientSession>()
    private val clients = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "cint-v2-client").apply { isDaemon = true }
    }
    private val startedAt = System.currentTimeMillis()
    private val started = CountDownLatch(1)
    private val stopped = CountDownLatch(1)
    private val closing = AtomicBoolean(false)

    @Volatile
    private var server: ServerSocketChannel? = null

    @Volatile
    private var ownedSocket: Path? = null

    @Volatile
    private var ownedSocketKey: Any? = null

    @Volatile
    private var shutdownFailure: ContractError? = null

    fun run() {
        val socketPath = options.socketPath
        prepareSocketDirectory(socketPath)
        removeStaleSocket(socketPath)

        val channel = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
        try {
            channel.bind(UnixDomainSocketAddress.of(socketPath))
            ownedSocket = socketPath
            ownedSocketKey = fileKey(socketPath)
            Files.setPosixFilePermissions(socketPath, PosixFilePermissions.fromString("rw-------"))
        } catch (error: Throwable) {
            runCatching { channel.close() }
            deleteOwnedSocket()
            throw error
        }
        server = channel
        val hook =
            Thread {
                requestShutdown()
                closeSessions()
            }
        runCatching { java.lang.Runtime.getRuntime().addShutdownHook(hook) }
        started.countDown()
        System.err.println("cint-server-v2 on $socketPath (root: ${options.root})")
        try {
            while (!closing.get() && channel.isOpen) {
                val client = runCatching { channel.accept() }.getOrNull() ?: break
                val session = ClientSession(client, runtime, options, codec, gate, this, ::removeSession)
                sessions += session
                clients.execute(session)
            }
        } finally {
            closing.set(true)
            runCatching { channel.close() }
            closeSessions()
            clients.shutdown()
            runCatching { clients.awaitTermination(options.closeGraceMs, TimeUnit.MILLISECONDS) }
            clients.shutdownNow()
            val runtimeShutdown = gate.shutdown(runtime, options.closeGraceMs)
            shutdownFailure = runtimeShutdown.errorOrNull
            shutdownFailure?.let { System.err.println("cint-server-v2 runtime shutdown failed: $it") }
            deleteOwnedSocket()
            runCatching { java.lang.Runtime.getRuntime().removeShutdownHook(hook) }
            stopped.countDown()
        }
    }

    fun requestShutdown() {
        if (!closing.compareAndSet(false, true)) return
        runCatching { server?.close() }
    }

    fun awaitStarted() {
        started.await(10, TimeUnit.SECONDS)
    }

    fun awaitStopped() {
        stopped.await(options.closeGraceMs + 5_000, TimeUnit.MILLISECONDS)
    }

    fun activeDocuments(): Int = sessions.sumOf { it.activeDocumentCount() }

    fun runtimeShutdownFailure(): ContractError? = shutdownFailure

    fun uptimeMs(): Long = System.currentTimeMillis() - startedAt

    fun <T> withRuntime(block: () -> T): T = gate.call(block)

    private fun removeSession(session: ClientSession) {
        sessions.remove(session)
    }

    private fun closeSessions() {
        sessions.forEach { session -> runCatching { session.close() } }
    }

    private fun prepareSocketDirectory(socketPath: Path) {
        val parent = socketPath.parent ?: return
        val existed = Files.exists(parent)
        Files.createDirectories(parent)
        if (!existed) {
            Files.setPosixFilePermissions(parent, PosixFilePermissions.fromString("rwx------"))
        }
    }

    private fun removeStaleSocket(socketPath: Path) {
        if (!Files.exists(socketPath)) return
        if (!isUnixSocket(socketPath)) {
            throw IllegalStateException("socket path exists and is not a Unix socket: $socketPath")
        }
        if (isLiveSocket(socketPath)) {
            throw IllegalStateException("socket is already in use: $socketPath")
        }
        Files.delete(socketPath)
    }

    private fun isLiveSocket(socketPath: Path): Boolean =
        try {
            SocketChannel.open(StandardProtocolFamily.UNIX).use { probe ->
                probe.connect(UnixDomainSocketAddress.of(socketPath))
                true
            }
        } catch (_: IOException) {
            false
        }

    private fun isUnixSocket(socketPath: Path): Boolean =
        runCatching {
            val mode = Files.getAttribute(socketPath, "unix:mode") as Int
            mode and FILE_TYPE_MASK == SOCKET_TYPE
        }.getOrDefault(false)

    private fun deleteOwnedSocket() {
        val owned = ownedSocket ?: return
        val key = ownedSocketKey
        ownedSocket = null
        ownedSocketKey = null
        if (key != null && runCatching { fileKey(owned) }.getOrNull() != key) return
        runCatching { Files.deleteIfExists(owned) }
    }

    private fun fileKey(path: Path): Any? =
        runCatching { Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes::class.java).fileKey() }
            .getOrNull()

    private companion object {
        const val FILE_TYPE_MASK = 0xF000
        const val SOCKET_TYPE = 0xC000
    }
}
