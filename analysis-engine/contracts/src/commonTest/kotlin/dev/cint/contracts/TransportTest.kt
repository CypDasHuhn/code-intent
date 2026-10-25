package dev.cint.contracts

import dev.cint.contracts.ipc.IpcMessage
import dev.cint.contracts.ports.IpcReceive
import dev.cint.contracts.ports.IpcTransport
import dev.cint.contracts.util.CancellationToken
import dev.cint.contracts.util.ContractResult
import dev.cint.contracts.util.ResultFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals

class TransportTest {
    @Test
    fun testReceiveDistinguishesMessageEmptyAndClosed() {
        val message =
            IpcMessage.Handshake(
                fingerprint = "fp",
                clientInfo =
                    dev.cint.contracts.ipc
                        .ClientInfo("client", "1"),
            )
        val transport =
            object : IpcTransport {
                var next: IpcReceive = IpcReceive.Message(message)

                override fun send(
                    message: IpcMessage,
                    cancellation: CancellationToken,
                ): ContractResult<Unit> = ResultFactory.success(Unit)

                override fun receive(cancellation: CancellationToken): ContractResult<IpcReceive> = ResultFactory.success(next)

                override fun close(): ContractResult<Unit> = ResultFactory.success(Unit)
            }

        val received = assertIs<IpcReceive.Message>(transport.receive().getOrThrow())
        assertEquals(message, received.message)

        transport.next = IpcReceive.Empty
        assertEquals(IpcReceive.Empty, transport.receive().getOrThrow())

        transport.next = IpcReceive.Closed
        val closed = transport.receive().getOrThrow()
        assertEquals(IpcReceive.Closed, closed)
        assertNotEquals(IpcReceive.Empty, closed)
    }
}
