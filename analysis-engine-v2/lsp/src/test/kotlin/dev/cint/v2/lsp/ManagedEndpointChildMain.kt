package dev.cint.v2.lsp

import dev.cint.v2.testing.lsp.ScriptedLspServer
import java.net.InetAddress
import java.net.ServerSocket
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.ServerSocketChannel

object ManagedEndpointChildMain {
    @JvmStatic
    fun main(args: Array<String>) {
        val raw =
            args.firstOrNull { it.startsWith("--endpoint=") }?.substringAfter('=')
                ?: error("missing --endpoint")
        when (val endpoint = LspEndpoint.parse(raw)) {
            is LspEndpoint.Unix -> {
                val channel = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
                channel.bind(UnixDomainSocketAddress.of(endpoint.path))
                val client = channel.accept()
                ScriptedLspServer(
                    Channels.newInputStream(client),
                    Channels.newOutputStream(client),
                ).start().await(30_000)
                runCatching { client.close() }
                runCatching { channel.close() }
            }

            is LspEndpoint.Tcp -> {
                ServerSocket(endpoint.port, 16, InetAddress.getByName(endpoint.host)).use { server ->
                    val client = server.accept()
                    ScriptedLspServer(client.getInputStream(), client.getOutputStream()).start().await(30_000)
                    runCatching { client.close() }
                }
            }
        }
    }
}
