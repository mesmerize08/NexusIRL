package io.github.mesmerize08.nexusirl.moblink

import io.github.mesmerize08.nexusirl.core.websocket.ReconnectingWebSocket
import io.github.mesmerize08.nexusirl.transport.srt.net.BondingInterface
import io.github.mesmerize08.nexusirl.transport.srt.net.InterfaceType
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class MoblinkStreamerTest {
    private class Cellular : BondingInterface {
        override val id = "cellular"
        override val type = InterfaceType.CELLULAR
        override val name = "rmnet0"

        override fun bind(socket: DatagramSocket) = Unit
    }

    private class Events : MoblinkStreamerListener {
        val added = LinkedBlockingQueue<MoblinkTunnel>()
        val removed = LinkedBlockingQueue<MoblinkTunnel>()
        val changed = LinkedBlockingQueue<Unit>()

        override fun tunnelAdded(tunnel: MoblinkTunnel) {
            added += tunnel
        }

        override fun tunnelRemoved(tunnel: MoblinkTunnel) {
            removed += tunnel
        }

        override fun relaysChanged() {
            changed += Unit
        }
    }

    /** Our streamer and our relay together: the relay tunnels SRTLA from the streamer to the server. */
    @Test
    fun streamerAndRelay() {
        val port = ServerSocket(0).use { it.localPort }
        val events = Events()
        val streamer = MoblinkStreamer(port, "1234", events).also { it.start() }
        val server = DatagramSocket(0).apply { soTimeout = 5000 }
        val relayId = UUID.fromString("0B3B5C3E-6D5A-4D47-9D7B-6B7C1D8F2A11")
        val relay = MoblinkRelay(
            relayId,
            "Pixel",
            "ws://127.0.0.1:$port",
            "1234",
            { MoblinkResponse.Status(42, MoblinkThermalState.YELLOW) },
            ReconnectingWebSocket.LOCAL_NETWORK_CLIENT,
        ) {}
        try {
            Thread.sleep(300)
            relay.setInterfaces(listOf(Cellular()))
            // Going live with SRTLA: tunnels to the server.
            streamer.startTunnels("127.0.0.1", server.localPort)
            val tunnel = assertNotNull(events.added.poll(10, TimeUnit.SECONDS))
            assertEquals(relayId, tunnel.relayId)
            assertEquals("Pixel", tunnel.relayName)
            assertEquals("127.0.0.1", tunnel.host)
            DatagramSocket(0).use { media ->
                media.soTimeout = 5000
                media.send(DatagramPacket("srtla".toByteArray(), 5, InetSocketAddress(tunnel.host, tunnel.port)))
                val received = DatagramPacket(ByteArray(100), 100)
                server.receive(received)
                assertEquals("srtla", String(received.data, 0, received.length))
                server.send(DatagramPacket("ack".toByteArray(), 3, received.socketAddress))
                val reply = DatagramPacket(ByteArray(100), 100)
                media.receive(reply)
                assertEquals("ack", String(reply.data, 0, reply.length))
            }
            // The relay's battery and thermal state arrive after identifying.
            val deadline = System.currentTimeMillis() + 5000
            while (streamer.statuses().firstOrNull()?.batteryPercentage == null && System.currentTimeMillis() < deadline) {
                Thread.sleep(50)
            }
            assertEquals(listOf(MoblinkRelayStatus("Pixel", 42, MoblinkThermalState.YELLOW)), streamer.statuses())
            // Stopping the stream removes the tunnel.
            streamer.stopTunnels()
            assertEquals(tunnel, events.removed.poll(5, TimeUnit.SECONDS))
        } finally {
            relay.stop()
            streamer.stop()
            server.close()
        }
    }

    @Test
    fun wrongPasswordLocksOut() {
        val port = ServerSocket(0).use { it.localPort }
        var now = 0L
        val events = Events()
        val streamer = MoblinkStreamer(port, "1234", events, clock = { now }).also { it.start() }
        val states = LinkedBlockingQueue<MoblinkRelayState>()
        val relay = MoblinkRelay(
            UUID.randomUUID(),
            "Wrong",
            "ws://127.0.0.1:$port",
            "0000",
            { MoblinkResponse.Status() },
            ReconnectingWebSocket.LOCAL_NETWORK_CLIENT,
        ) { states += it }
        try {
            Thread.sleep(300)
            relay.setInterfaces(listOf(Cellular()))
            var state: MoblinkRelayState? = null
            val deadline = System.currentTimeMillis() + 10_000
            while (state != MoblinkRelayState.WRONG_PASSWORD && System.currentTimeMillis() < deadline) {
                state = states.poll(1, TimeUnit.SECONDS)
            }
            assertEquals(MoblinkRelayState.WRONG_PASSWORD, state)
            assertEquals(emptyList(), streamer.statuses())
        } finally {
            relay.stop()
            streamer.stop()
        }
    }
}
